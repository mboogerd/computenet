package civictech.oracle.tagged

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.replication.Replication
import civictech.cell.verify.ReplicaConvergence
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.DotOrders
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.run.ConvergenceCheck
import civictech.oracle.run.MeshObservation
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.TaggedMapTerminalFold
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The **generated** multi-replica sweep through the convergence check — `ORA2 §DIFF-08` at
 * scale, `ORA2 §DIFF-05`'s late-joiner half.
 *
 * The mesh is driven so that **the gossip it exchanges is exactly the gossip its own
 * `civictech.oracle.gen.CaseDelivery` schedule states**, and the reference is that same schedule
 * read through `civictech.oracle.gen.CaseScript.toScript()` and handed to
 * [ConvergenceCheck.check] **unchanged** — no second reference, no second verdict precedence,
 * no second runner. computenet-9892.
 *
 * ## The design question this file exists to answer, and the answer
 *
 * computenet-9ips measured that the previous drive here — a `runToIdle` after every op — realised
 * **no concurrency at all** (max 1 live dot at any key, over all 40 seeds), and that its
 * causality was not expressible as a `civictech.oracle.model.Delivery` graph at all (cyclic on
 * 40 of 40). computenet-9892 was filed on the open sub-problem: *there is no way today to keep
 * the real mesh from delivering MORE gossip than the script states.* Three findings settle it,
 * and they are recorded here because the file is where the next reader will look.
 *
 * **1. Batching a round of writes before draining does not fix it, and structurally cannot.**
 * That was the prescribed shape (computenet-9ips route (a), and this bead's own prose). It is
 * wrong. `Delivery(afterEvents, from, throughEvents)` names the delivered state by the sender's
 * own **event prefix**, and `DotModel.Fold.stateAfter(t, n)` applies the deliveries stated at
 * `position == n` before returning. An all-to-all drain needs each replica to absorb its peers'
 * *pre-drain export* at exactly the prefix at which those peers are absorbing *its* export —
 * two different states at the same event prefix, and the vocabulary can name only the later
 * one. So `A@a -> B@b -> A@a` is a cycle **inside one round**, and batching (which only makes
 * event counts strictly increase *between* rounds) leaves it untouched. Corroboration is in
 * `ScriptGenerator.openGossipRound`'s own KDoc, which says a full all-to-all round "is exactly
 * such a cycle" and emits a permutation **chain** instead.
 *
 * **2. Therefore the drive must gossip in a chain — directed, one edge at a time.**
 * `Replication`'s public surface cannot: `maybeLink` links every interest-overlapping pair and
 * overlap is symmetric, so formation is never directional; and there is no unlink at all — its
 * `onUnpublish` reconciliation drops the `linked` bookkeeping and, in `gossipRef`'s own words,
 * leaves the attachment "WITHOUT unsubscribing the outlet".
 *
 * **3. One level down, the seam is already public.** `Replicable.outlet` is a `Subscribe`, which
 * declares `unsubscribe(PortRef)`, and since T21 the gossip subscription's `PortRef` is
 * **derived**, not generated: `UUID.nameUUIDFromBytes("gossip:<id>:<inst>:<id>:<inst>")`.
 * Restating a kernel derivation in the harness is the sanctioned pattern here — it is exactly
 * what `ORA2 §MODEL-12` requires of the dot order, and what `DotOrders.dotSourceOf` already
 * does for `"or-map-tags:..."`. So [silence] unsubscribes every derived gossip ref the instant
 * the mesh is built, and [deliver] re-streams **one** directed edge per [CaseDelivery],
 * `runToIdle`s, and unsubscribes it again. `streamTo`'s `fireLinked` catch-up ships the sender's
 * whole current state as one delta, which is precisely what a model `Delivery` means.
 *
 * `civictech.oracle.tagged.DirectedGossipProbeTest` is the isolated evidence for step 3 —
 * withholding, one-directional delivery, and retraction, each asserted on a real two-replica
 * mesh.
 *
 * ## What that buys, measured rather than argued
 *
 * Because no barrier ever occurs, the writes between two gossip rounds are genuinely concurrent,
 * so the converged state really does hold more than one live dot at a key. Both numbers are
 * **printed on every run** and asserted, so neither can rot silently:
 *
 * - [maxLiveDotsRealised] — the largest number of live dots any key held in a REAL replica's
 *   converged `TaggedMapDelta`, read off the kernel (not off the model). `> 1` is the property
 *   the previous drive could not reach.
 * - [seedsWithCounterTie] — how many seeds produced a key whose live dots share a counter, so
 *   `[24-TMAP-03]`'s `DOT_ORDER` tie-break is the only thing deciding the value. That is the
 *   subset on which reversing the kernel's tie-break can be caught at all.
 *
 * The mesh has no operator layer, deliberately: `replicatedOrMapMeshCase` builds a topology with
 * no consuming operator (computenet-880k — `join`'s `JoinCell` is typed to `MapDelta`, not
 * `TaggedMapDelta`). The unreplicated second `orMap` arm's events are generated but not driven
 * and not part of the reference: [replicaScript] restricts the script to the replicated
 * source's own slices, which is sound because every stated delivery is between replicas.
 *
 * The density loop is `civictech.testkit.forEachSeed` over
 * [GeneratorConfig.REPLICATED_SWEEP_SEEDS] — the SAME fixed range
 * `civictech.oracle.tagged.MultiWriterGenerationTest` and `civictech.oracle.tagged.TaggedControlsTest`
 * already measure the generator's concurrency/tie-break properties against, so a report here is
 * directly comparable to theirs.
 */
class ConvergenceSweepTest {

    @org.junit.jupiter.api.BeforeEach
    fun registerCatalog() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        TaggedOperators.registerAll()
    }

    @org.junit.jupiter.api.AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private val config = GeneratorConfig.replicatedSweep()

    /**
     * The largest number of live dots any key ever held in a REAL replica's converged state,
     * across every case driven so far. **1 would mean no concurrency was realised at all** — the
     * defect computenet-9ips found in the previous full-synchronization drive. Measured off the
     * kernel's own `TaggedMapDelta`, never off the model.
     */
    private var maxLiveDotsRealised = 0

    /** Seeds whose converged state holds a key where two live dots share a counter. */
    private val seedsWithCounterTie = mutableListOf<Long>()

    /** Each driven case's generator-*achieved* concurrency, for contrast with [maxLiveDotsRealised]. */
    private val configuredConcurrency = mutableListOf<Double>()

    /** [MapOps] as the sink one replica's driving reads from. */
    private fun proxyFor(world: SimWorld, ref: CellRef): MapOps<Any?, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (
            HostedCellProxy.create(ref, world.registry, OrMapInletProxy::class.java) as OrMapInletProxy
            ).inlet.call as MapOps<Any?, Any?>
    }

    /** The hosted `OrMapCell` inlet proxy shape, mirroring [ConvergenceCheckTest]'s. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<Any?, Any?>>
    }

    /**
     * `Replication.gossipRef`'s derivation, restated here — `ORA2 §MODEL-12`'s pattern applied
     * to the gossip subscription instead of to the dot source. Private in `Replication`, and
     * deliberately *derived* there (its own KDoc: "Derived rather than `PortRef.generate()`d
     * because re-linking is a normal event"), which is what makes restating it stable rather
     * than a guess.
     */
    private fun gossipRef(local: CellRef, other: CellRef): PortRef = PortRef(
        UUID.nameUUIDFromBytes(
            "gossip:${local.id}:${local.instanceId}:${other.id}:${other.instanceId}".toByteArray(),
        ),
    )

    /**
     * Drives one generated case's replicated mesh under **its own** [CaseDelivery] schedule and
     * returns the verdict [ConvergenceCheck.check] reaches over it.
     *
     * @param onSettled called once, after the whole script has drained, with every replica's
     *   ref keyed by its script [SourceId] — the seam ORA2 §DIFF-05's late-joiner test below
     *   uses to link a fresh consumer mid-drive on a SEPARATE run of the same case.
     */
    private fun driveAndCheck(
        seed: Long,
        lateLinkAfterOps: Int? = null,
        onLateLink: (TaggedMapTerminalFold<Any?, Any?>, ModelState) -> Unit = { _, _ -> },
    ): Pair<RunOutcome, Map<SourceId, ModelState>> {
        val case = GeneratorConfig.replicatedOrMapMeshCase(config, seed)
        val plan = (case.replication ?: error("seed $seed produced no replication plan")).plan
        val replicaSet = plan.replicas.toSet()

        val world = SimWorld(seed = case.controllerSeed)
        val logicalId = UUID.nameUUIDFromBytes("conv-sweep:$seed".toByteArray())
        val hostByOrdinal = plan.hosts.distinct().associateWith { ordinal ->
            if (ordinal == 0) world.host else ManagedHost(scheduler = world.controller.scheduler(), registry = world.registry)
        }

        val convergence = ReplicaConvergence<TaggedMapDelta<Any?, Any?>, TaggedMapDelta<Any?, Any?>>(
            world.registry,
            logicalId,
            TaggedMapDelta(),
        ) { acc, delta -> TaggedMapTerminalFold.merge(acc, delta) }
        val replication = Replication(world.registry)

        val refs = LinkedHashMap<SourceId, CellRef>()
        val cells = LinkedHashMap<SourceId, OrMapCell<Any?, Any?>>()
        val ops = LinkedHashMap<SourceId, MapOps<Any?, Any?>>()
        plan.replicas.forEachIndexed { i, sourceId ->
            val cell = OrMapCell<Any?, Any?>(CellRef(logicalId, i.toLong()))
            replication.replicate(cell, hostByOrdinal.getValue(plan.hosts[i]))
            convergence.attach(cell)
            refs[sourceId] = cell.ref
            cells[sourceId] = cell
        }
        world.runToIdle()
        refs.forEach { (sourceId, ref) -> ops[sourceId] = proxyFor(world, ref) }

        // SILENCE the mesh. `Replication` has formed the real all-to-all gossip; every one of
        // those attachments is dropped at its derived ref, so from here nothing crosses between
        // replicas except what [deliver] explicitly re-streams. This is the whole answer to
        // "keep the real mesh from delivering more than the script states" — see the file KDoc.
        plan.replicas.forEach { from ->
            plan.replicas.forEach { into ->
                if (from != into) {
                    cells.getValue(from).outlet.unsubscribe(gossipRef(refs.getValue(from), refs.getValue(into)))
                }
            }
        }

        val firstReplicaHost = hostByOrdinal.getValue(plan.hosts.first())

        /**
         * One stated delivery, as ONE directed edge: stream `from`'s outlet to `into`'s
         * replica delta inlet at the derived gossip ref, drain, then retract the edge.
         * `streamTo`'s `fireLinked` catch-up carries `from`'s whole current state, which is
         * exactly `DotModel.Fold.stateAfter(from, throughEvents)`.
         */
        fun deliverOne(from: SourceId, into: SourceId) {
            val fromRef = refs.getValue(from)
            val intoRef = refs.getValue(into)
            @Suppress("UNCHECKED_CAST")
            val intoInlet = (
                HostedCellProxy.create(intoRef, world.registry, Replication.ReplicaDeltaInlet::class.java)
                    as Replication.ReplicaDeltaInlet
                ).deltaInlet.call
            @Suppress("UNCHECKED_CAST")
            (cells.getValue(from).outlet as FanOutlet<Propagate<Any?>>)
                .streamTo(intoInlet, at = gossipRef(fromRef, intoRef))
            world.runToIdle()
            cells.getValue(from).outlet.unsubscribe(gossipRef(fromRef, intoRef))
        }

        // Deliveries are applied in the order the generator emitted them within a round, which
        // is the permutation CHAIN order (`openGossipRound` appends into=order[i], from=order[i-1]
        // for increasing i). That order matters to the kernel and not to the model: the model's
        // `stateAfter(order[i-1], n)` already includes that source's own delivery at the same
        // position, so applying the chain head-first is what makes the two agree.
        val deliveriesByStep = case.script.deliveries.groupBy { it.atStep }
        fun deliverAt(index: Int) {
            deliveriesByStep[index]?.forEach { deliverOne(it.from, it.into) }
        }

        var opsDriven = 0
        case.script.steps.forEachIndexed { index, step ->
            deliverAt(index)
            if (step is CaseStep.Op && step.source in replicaSet) {
                when (val event = step.event) {
                    is ScriptEvent.Put -> ops.getValue(step.source).put(event.key, event.element)
                    is ScriptEvent.RemoveKey -> ops.getValue(step.source).remove(event.key)
                    else -> Unit // this vocabulary emits Put/RemoveKey only for orMap sources
                }
                opsDriven++
                // Drain the LOCAL queues only — with the mesh silenced this settles the write at
                // its own replica without gossiping it, which is what makes the writes between
                // two stated deliveries genuinely concurrent.
                world.runToIdle()
                if (lateLinkAfterOps != null && opsDriven == lateLinkAfterOps) {
                    val lateFold = TaggedMapTerminalFold<Any?, Any?>()
                    firstReplicaHost.managementInlet.call.spawn(lateFold)
                    val link = firstReplicaHost.managementInlet.call
                        .connect(refs.getValue(plan.replicas.first()), "outlet", lateFold.ref, "inlet")
                    check(link !is LinkResult.Rejected) { "late link rejected: $link" }
                    world.runToIdle()
                    onLateLink(lateFold, lateFold.current())
                }
            }
        }
        deliverAt(case.script.steps.size)
        world.runToIdle()

        // QUIESCENCE, after the last write and after the last stated delivery: restore every
        // gossip edge and drain.
        //
        // This is not a relaxation of "the mesh delivers exactly what the script states" — it is
        // what the reference already means. `DotModel.converged(script)` is defined as the merge
        // of EVERY instance's final state, i.e. the state a mesh reaches once all gossip has
        // settled, and `ORA2 §DIFF-08`'s agreement half asks for exactly that mesh. The reason
        // it costs nothing is that a tombstone is minted only by a put or a remove, and there are
        // none left: from here the mesh does a pure lattice join, which cannot add a tombstone the
        // model does not have. Restoring an edge one step EARLIER would be the over-delivery this
        // item refuses, because a later write at the over-supplied replica would tombstone dots
        // the model left live.
        plan.replicas.forEach { from ->
            plan.replicas.forEach { into ->
                if (from != into) {
                    @Suppress("UNCHECKED_CAST")
                    val intoInlet = (
                        HostedCellProxy.create(
                            refs.getValue(into),
                            world.registry,
                            Replication.ReplicaDeltaInlet::class.java,
                        ) as Replication.ReplicaDeltaInlet
                        ).deltaInlet.call
                    @Suppress("UNCHECKED_CAST")
                    (cells.getValue(from).outlet as FanOutlet<Propagate<Any?>>)
                        .streamTo(intoInlet, at = gossipRef(refs.getValue(from), refs.getValue(into)))
                }
            }
        }
        world.runToIdle()

        val order = DotOrders.of(refs)
        val observation = MeshObservation.of(
            logicalId = logicalId,
            convergence = convergence,
            replicas = refs,
            stateOf = { delta -> TaggedMapTerminalFold.stateOf(delta) },
        )

        // The realised concurrency, read off the KERNEL's own converged delta rather than off any
        // model — so the number cannot be an artefact of the reference agreeing with itself.
        // `converged` here is the pointwise merge of every replica's fold, which is what every
        // replica holds once the last gossip round has run.
        val converged = plan.replicas.fold(TaggedMapDelta<Any?, Any?>()) { acc, source ->
            TaggedMapTerminalFold.merge(acc, convergence.state(refs.getValue(source)) ?: TaggedMapDelta())
        }
        var tie = false
        converged.membership().forEach { key ->
            val live = converged.liveDots(key)
            if (live.size > maxLiveDotsRealised) maxLiveDotsRealised = live.size
            if (live.keys.groupBy { it.counter }.any { it.value.size > 1 }) tie = true
        }
        if (tie) seedsWithCounterTie += seed
        configuredConcurrency += case.replication!!.concurrency.achieved

        // ConvergenceCheck.check, UNCHANGED: its own reference (DotModel over the script) and its
        // own verdict precedence. Nothing about the expected value or the kind ordering is
        // recomputed here.
        val outcome = ConvergenceCheck(order).check(
            seed = seed,
            caseMarker = "conv-sweep seed=$seed",
            script = replicaScript(case.script.toScript(), replicaSet),
            mesh = observation,
        )
        return outcome to observation.folds
    }

    /**
     * The generated script restricted to the replicated source's slices.
     *
     * The unreplicated second `orMap` arm is generated (the config asks for two sources) but has
     * no replica in this mesh and no operator consuming it, so its slice would add dots to the
     * reference that no replica could ever hold. Sound because every stated `CaseDelivery` is
     * between two replicas — `ScriptGenerator` emits gossip only among `replicas` — so dropping
     * the other slices drops no causality.
     */
    private fun replicaScript(script: Script, replicaSet: Set<SourceId>): Script =
        Script(script.slices.filter { it.source in replicaSet })

    // =====================================================================
    // ORA2 §DIFF-08 at scale: every generated mesh converges to the model's answer
    // =====================================================================

    @Test
    fun `every generated replicated mesh in the default range converges to the one reference answer`() {
        var count = 0
        forEachSeed(GeneratorConfig.REPLICATED_SWEEP_SEEDS) { seed ->
            count++
            val (outcome, _) = driveAndCheck(seed)
            withClue("seed=$seed outcome=$outcome") { outcome shouldBe RunOutcome.Success }
        }
        println("[conv-sweep] $count generated meshes converged over ${GeneratorConfig.REPLICATED_SWEEP_SEEDS} ORA2 §DIFF-08")
        println(
            "[conv-sweep] REALISED concurrency: max live dots at any key = $maxLiveDotsRealised " +
                "(1 == none); seeds with a counter tie among live dots = ${seedsWithCounterTie.size} " +
                "${seedsWithCounterTie.take(10)}; generator-achieved concurrency mean = " +
                "${"%.3f".format(configuredConcurrency.average())}",
        )
        withClue(
            "the drive must REALISE concurrency, not merely be configured for it — a max of 1 is " +
                "computenet-9ips's defect (add-wins and the [24-TMAP-03] tie-break unreached)",
        ) {
            (maxLiveDotsRealised > 1) shouldBe true
        }
        withClue(
            "reversing TaggedMapDelta.DOT_ORDER's tie-break can only be caught on a key whose live " +
                "dots share a counter; with none, this sweep says nothing about ORA2 §MODEL-12",
        ) {
            seedsWithCounterTie.isNotEmpty() shouldBe true
        }
    }

    // =====================================================================
    // ORA2 §DIFF-05 — a late-linked consumer of a generated mesh equals the converged reference
    // =====================================================================

    @Test
    fun `ORA2 §DIFF-05 a consumer linked partway through a generated mesh's drive equals the converged reference`() {
        val seed = GeneratorConfig.REPLICATED_SWEEP_SEEDS.first
        // (seed, config) is deterministic (ORA2 §GEN-07), so the plan can be re-derived here
        // without threading it out of driveAndCheck.
        val plan = GeneratorConfig.replicatedOrMapMeshCase(config, seed).replication!!.plan
        var lateFold: TaggedMapTerminalFold<Any?, Any?>? = null
        var linked = false
        val (outcome, folds) = driveAndCheck(seed, lateLinkAfterOps = 3) { fold, _ ->
            lateFold = fold
            linked = true
        }
        withClue("outcome=$outcome") { outcome shouldBe RunOutcome.Success }
        withClue("a late link must actually have happened") { linked shouldBe true }

        // The late fold linked mid-drive but [driveAndCheck] runs the mesh to idle again after
        // it, so by now it has absorbed everything the rest of the script produced too — and, by
        // OrMapCell's late-join catch-up, everything before the link as well. It should therefore
        // read exactly what the EARLY-linked convergence fold of the SAME replica reads.
        withClue("folds=$folds") {
            lateFold.shouldNotBeNull().current() shouldBe folds.getValue(plan.replicas.first())
        }
    }
}
