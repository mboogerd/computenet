package civictech.oracle.tagged

import civictech.cell.CellRef
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import civictech.cell.verify.ReplicaConvergence
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.DotOrders
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.run.MeshObservation
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.TaggedMapTerminalFold
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The **generated** multi-replica sweep through the convergence check — `[ORA2-DIFF-08]` at
 * scale, `[ORA2-DIFF-05]`'s late-joiner half.
 *
 * `civictech.oracle.gen.GeneratorConfig.replicatedSweep()` and `CaseGenerator` already produce
 * replayable multi-replica `orMap` cases (`civictech.oracle.tagged.MultiWriterGenerationTest`
 * exercises the generator dimension directly). What they do NOT produce is a runner path:
 * `civictech.oracle.run.CaseExecution` materialises no replicas
 * (`civictech.oracle.run.OracleSweep`'s own KDoc, "the replicated mesh has no runner path even
 * now"), and the generated topology's OTHER source — the unreplicated `orMap` arm
 * `GeneratorConfig.replicatedSweep()` names to fill `join`'s second port — cannot be wired at
 * all (`join`'s `JoinCell` is typed to `MapDelta`, not `TaggedMapDelta`; see
 * `civictech.oracle.tagged.TaggedSweepTest`'s KDoc for the same finding). So this sweep drives
 * the mesh itself, the same way `civictech.oracle.tagged.ConvergenceCheckTest` does for its
 * hand-built meshes, but sourced from [CaseGenerator]'s output: it reads
 * [civictech.oracle.gen.GeneratedCase.replication] for the replica plan, drives only the
 * REPLICATED source's events (the unreplicated `join` arm's events are generated but irrelevant
 * to this logical cell's convergence and are skipped, never wired to a `JoinCell`), and reads the
 * mesh through [civictech.oracle.run.MeshObservation]/[civictech.cell.verify.ReplicaConvergence] —
 * the SAME reading seam `ConvergenceCheck` itself reads through, composed rather than duplicated.
 *
 * ## A documented deviation: NOT `ConvergenceCheck.check()` itself
 *
 * `[ORA2-DIFF-05]`'s scenario and this sweep both need FULL synchronization — a `runToIdle`
 * after every op, so every replica has observed literally everything before it. Encoding that
 * as a `civictech.oracle.model.Script`'s per-event `Delivery` graph (the ONLY input
 * `ConvergenceCheck.check()` accepts) was attempted and abandoned: it reliably produced
 * `civictech.oracle.model.DotModel.CyclicDeliveryException` on real generated interleavings.
 * `civictech.oracle.tagged.ConvergenceCheckTest`'s own `MeshScript` KDoc documents exactly this
 * limit — "a full barrier is mutual... that is not expressible as a Delivery graph" — and states
 * the workaround it uses instead (state only the causality a script's own removes depend on).
 * That workaround does not generalize to an arbitrary GENERATED interleaving with many
 * concurrent removes across replicas: two individually-true delivery facts (source A's early
 * boundary needs source B's nearby prefix, and vice versa) can be mutually recursive under
 * [civictech.oracle.model.DotModel.Fold]'s definition even though the underlying total order has
 * no real simultaneity. So this file computes the reference DIRECTLY, folding
 * [civictech.oracle.model.DotState]'s own public primitives ([civictech.oracle.model.DotState.put],
 * [civictech.oracle.model.DotState.resetRemove] — the exact ones [DotModel.Fold] itself calls)
 * over the realized global total order, which needs no observation bookkeeping at all: under
 * full sync, "what this state holds so far" IS the one shared already-converged-so-far state, by
 * construction. [driveAndCheck]'s own KDoc has the full account, including the specific cyclic
 * chain that was observed and abandoned rather than patched around.
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
     * Drives one generated case's replicated mesh to quiescence and returns the verdict
     * [ConvergenceCheck] reaches over it.
     *
     * @param onSettled called once, after the whole script has drained, with every replica's
     *   ref keyed by its script [SourceId] — the seam [ORA2-DIFF-05]'s late-joiner test below
     *   uses to link a fresh consumer mid-drive on a SEPARATE run of the same case.
     */
    private fun driveAndCheck(
        seed: Long,
        lateLinkAfterOps: Int? = null,
        onLateLink: (TaggedMapTerminalFold<Any?, Any?>, ModelState) -> Unit = { _, _ -> },
    ): Pair<RunOutcome, Map<SourceId, ModelState>> {
        val case = CaseGenerator(config).generate(seed)
        val plan = (case.replication ?: error("seed $seed produced no replication plan")).plan
        val replicaSet = plan.replicas.toSet()

        val world = SimWorld(seed = case.controllerSeed)
        val logicalId = java.util.UUID.nameUUIDFromBytes("conv-sweep:$seed".toByteArray())
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
        val ops = LinkedHashMap<SourceId, MapOps<Any?, Any?>>()
        plan.replicas.forEachIndexed { i, sourceId ->
            val cell = OrMapCell<Any?, Any?>(CellRef(logicalId, i.toLong()))
            replication.replicate(cell, hostByOrdinal.getValue(plan.hosts[i]))
            convergence.attach(cell)
            refs[sourceId] = cell.ref
        }
        world.runToIdle()
        refs.forEach { (sourceId, ref) -> ops[sourceId] = proxyFor(world, ref) }

        // The link-target host: whichever ManagedHost owns the first replica, so a late link
        // below is a same-host connect (CaseExecution.assemble's own `bridgeAcrossCut` handles
        // cross-host wiring; reproducing that here for a test-only consumer link is out of
        // scope, so the late fold is deliberately hosted alongside the replica it observes).
        val firstReplicaHost = hostByOrdinal.getValue(plan.hosts.first())

        // The global drive order this test actually realises, restricted to the replicated
        // source's own events (the unreplicated `join` arm's events are never driven — see this
        // file's KDoc). [ORA1-GEN-03]-style total order across replicas, one op at a time.
        val replicaOps = case.script.steps.filterIsInstance<CaseStep.Op>().filter { it.source in replicaSet }

        // FULL synchronization: a runToIdle after EVERY op, so the real gossip mesh converges
        // completely before the next event fires. This is deliberately NOT what `case.script`'s
        // own baked `deliveries` audit assumed (that audit is measured against a schedule
        // ScriptGenerator itself only SIMULATES, never drives a real kernel to) — reproducing
        // ScriptGenerator's assumed timing bit-for-bit against a live scheduler is the "second
        // runner" this feature's REUSE clause forbids attempting from a test file. Full sync is
        // instead a schedule this HARNESS controls exactly, so [replicaScript] below is built to
        // match it precisely rather than to match the generator's own bookkeeping — the model
        // and the kernel are compared against the SAME realized causality either way, which is
        // what makes the comparison a differential test.
        var opsDriven = 0
        replicaOps.forEach { step ->
            when (val event = step.event) {
                is ScriptEvent.Put -> ops.getValue(step.source).put(event.key, event.element)
                is ScriptEvent.RemoveKey -> ops.getValue(step.source).remove(event.key)
                else -> Unit // this vocabulary emits Put/RemoveKey only for orMap sources
            }
            opsDriven++
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
        world.runToIdle()

        val order = DotOrders.of(refs)
        val observation = MeshObservation.of(
            logicalId = logicalId,
            convergence = convergence,
            replicas = refs,
            stateOf = { delta -> TaggedMapTerminalFold.stateOf(delta) },
        )

        // The reference: a DIRECT fold over [DotState]'s own public primitives ([DotState.put],
        // [DotState.resetRemove]) across the realized GLOBAL total order — not
        // `ConvergenceCheck.check`'s Script-based `model.converged(script)` path.
        //
        // Both express the SAME model ([DotState]/[ModelDot], the exact types [DotModel] itself
        // folds with); the difference is only which encoding of "who observed what" is used.
        // `civictech.oracle.model.Script.deliveries` encodes PARTIAL, per-writer observation —
        // exactly the shape `civictech.oracle.tagged.ConvergenceCheckTest`'s own `MeshScript`
        // KDoc documents as expressible ("a delivery matters only where it changes what a LATER
        // write... tombstones"), and explicitly NOT as "a full barrier is mutual... that is not
        // expressible as a Delivery graph". This harness's own full-synchronization drive (a
        // `runToIdle` after EVERY op, so every later event has observed literally everything
        // before it, from every replica) is exactly that unrepresentable mutual case: an attempt
        // to encode it as per-event `Delivery` records was built, empirically produced
        // `DotModel.CyclicDeliveryException` on real generated interleavings (a source's
        // early-position delivery and a peer's adjacent-position delivery citing each other back,
        // both individually true, structurally unrepresentable as an acyclic recursion), and was
        // abandoned rather than patched around. A direct fold has no such limit: for a SINGLE,
        // already-fully-realized total order, "what does this state hold so far" needs no
        // observation bookkeeping at all — [DotState.put]/[DotState.resetRemove] already operate
        // purely on `this` state's own current live dots, which under full sync IS the one
        // shared, already-converged-so-far state.
        val counters = plan.replicas.associateWith { 0L }.toMutableMap()
        var expectedState = civictech.oracle.model.DotState.EMPTY
        replicaOps.forEach { step ->
            when (val event = step.event) {
                is ScriptEvent.Put -> {
                    counters[step.source] = counters.getValue(step.source) + 1
                    expectedState = expectedState.put(
                        event.key,
                        civictech.oracle.model.ModelDot(counters.getValue(step.source), step.source),
                        event.element,
                    )
                }
                is ScriptEvent.RemoveKey -> expectedState = expectedState.resetRemove(event.key)
                else -> Unit // this vocabulary emits Put/RemoveKey only for orMap sources
            }
        }
        val expected = civictech.oracle.model.DotModel(order).entries(expectedState)

        val agreed = observation.agreed && observation.folds.values.distinct().size == 1
        val outcome = when {
            !agreed -> RunOutcome.ReplicaDivergence(
                seed = seed,
                logicalId = logicalId.toString(),
                caseMarker = "conv-sweep seed=$seed",
                script = Script(emptyList()),
                expected = expected,
                perReplica = observation.folds.entries.associate { (source, state) -> source.id to state },
                keys = emptyList(),
            )

            observation.folds.values.first() != expected -> RunOutcome.ReplicasAgreeButWrong(
                seed = seed,
                logicalId = logicalId.toString(),
                caseMarker = "conv-sweep seed=$seed",
                script = Script(emptyList()),
                expected = expected,
                actual = observation.folds.values.first(),
                difference = civictech.oracle.run.StateDifference.between(expected, observation.folds.values.first()),
                replicas = observation.folds.keys.map { it.id }.toSet(),
                keys = emptyList(),
            )

            else -> RunOutcome.Success
        }
        return outcome to observation.folds
    }

    // =====================================================================
    // [ORA2-DIFF-08] at scale: every generated mesh converges to the model's answer
    // =====================================================================

    @Test
    fun `every generated replicated mesh in the default range converges to the one reference answer`() {
        var count = 0
        forEachSeed(GeneratorConfig.REPLICATED_SWEEP_SEEDS) { seed ->
            count++
            val (outcome, _) = driveAndCheck(seed)
            withClue("seed=$seed outcome=$outcome") { outcome shouldBe RunOutcome.Success }
        }
        println("[conv-sweep] $count generated meshes converged over ${GeneratorConfig.REPLICATED_SWEEP_SEEDS} [ORA2-DIFF-08]")
    }

    // =====================================================================
    // [ORA2-DIFF-05] — a late-linked consumer of a generated mesh equals the converged reference
    // =====================================================================

    @Test
    fun `ORA2-DIFF-05 a consumer linked partway through a generated mesh's drive equals the converged reference`() {
        val seed = GeneratorConfig.REPLICATED_SWEEP_SEEDS.first
        // (seed, config) is deterministic ([ORA2-GEN-07]), so the plan can be re-derived here
        // without threading it out of driveAndCheck.
        val plan = CaseGenerator(config).generate(seed).replication!!.plan
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
