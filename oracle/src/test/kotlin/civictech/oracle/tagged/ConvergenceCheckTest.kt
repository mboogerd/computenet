package civictech.oracle.tagged

import civictech.cell.CellRef
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import civictech.cell.verify.ReplicaConvergence
import civictech.oracle.model.Delivery
import civictech.oracle.model.DotModel
import civictech.oracle.model.DotOrder
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.ConvergenceCheck
import civictech.oracle.run.MeshObservation
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.TaggedMapTerminalFold
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The convergence oracle over **hand-built** OR-map meshes — `[ORA2-CONV-01..04]`,
 * `[ORA2-DIFF-08]`, `[ORA2-DIFF-09]`, BS-1, BS-6, BS-7.
 *
 * Hand-built rather than generated on purpose (the generator's replica-placement dimension is a
 * sibling task): every mesh here states its own script, so what a test asserts about the model's
 * dot-order pick is checkable by reading the test, not by trusting a generator.
 *
 * ## What makes these tests discriminating rather than decorative
 *
 * A convergence suite that only ran BS-1 would be green against a mesh that agrees on the wrong
 * value, because every replica runs the same merge over the same dots and a shared bug in the
 * algebra produces *agreement*. So three of these tests exist to fail:
 *
 * - BS-7 hands the check a uniformly wrong reference (an inverted dot order) over a mesh that
 *   really did converge, and requires [RunOutcome.ReplicasAgreeButWrong] — a verdict a
 *   replica-vs-replica check cannot produce at all (`[ORA2-CONV-02]`).
 * - `the checked fold is the replica's own outlet stream` attaches one replica's fold late. Every
 *   *cell* still holds the converged state; only the reconstructed streams differ, so the verdict
 *   flips to [RunOutcome.ReplicaDivergence] exactly when the fold is stream-derived and stays
 *   Success when it is read off the cell (`[ORA2-CONV-04]`).
 * - The two verdicts are asserted **distinct from each other and from [RunOutcome.Mismatch]**
 *   (`[ORA2-CONV-03]`).
 */
class ConvergenceCheckTest {

    // =====================================================================
    // the harness: a hand-built OR-map mesh and the script that describes it
    // =====================================================================

    /** The inlet a hosted `OrMapCell` exposes — the same proxy shape `OrMapConvergenceTest` uses. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    /** One thing one replica was asked to do, in one round. */
    private data class Op(val replica: Int, val event: ScriptEvent)

    private fun put(replica: Int, key: String, value: String) =
        Op(replica, ScriptEvent.Put(WriterId("w$replica"), key, value))

    private fun removeKey(replica: Int, key: String) =
        Op(replica, ScriptEvent.RemoveKey(WriterId("w$replica"), key))

    /**
     * A mesh script as **rounds separated by gossip barriers**, plus the observations those
     * barriers established that the model has to be told about.
     *
     * Everything in one round is issued without any replica having observed any other's writes
     * in that round — that is what makes the concurrency BS-1 needs *real* rather than hoped for.
     * The barrier that closes a round is a full drain, so at its end every replica has absorbed
     * every other's emissions so far.
     *
     * ## Why the deliveries are stated per test rather than generated from the barriers
     *
     * A full barrier is **mutual**: after it, r0 has seen r1's writes and r1 has seen r0's. That
     * is not expressible as a [Delivery] graph, and deliberately so — [DotModel] refuses a cyclic
     * delivery script by name (`DotModelTest`'s "a cyclic delivery is refused") because two slices
     * that each claim to have absorbed a prefix of the other *containing that very claim* describe
     * no reachable state. Generating an all-to-all delivery per barrier produces exactly that
     * cycle.
     *
     * What the model actually needs is narrower than "everything the barrier delivered": a
     * delivery matters only where it changes what a **later write at that replica tombstones**.
     * Every dot exists somewhere regardless, so the converged merge is unaffected by an unstated
     * delivery — only a reset-remove's or a re-put's tombstone set is. So each test states, with
     * [observed], the causality its own writes depend on, and nothing else.
     *
     * Understating it is a **loud** error, not a silent one: an unstated delivery leaves the model
     * with fewer tombstones than the kernel produced, which surfaces as a
     * [RunOutcome.ReplicasAgreeButWrong] rather than as a quiet pass.
     */
    private class MeshScript(private val replicaCount: Int) {
        private val rounds = mutableListOf<List<Op>>()
        private val observations = mutableListOf<Observation>()

        private data class Observation(val into: Int, val afterEvents: Int, val from: Int, val throughEvents: Int)

        fun round(vararg ops: Op): MeshScript = apply { rounds += ops.toList() }

        /**
         * *"Replica [into], having issued [afterEvents] of its own events, had absorbed replica
         * [from]'s first [throughEvents] events."* — one barrier's effect on one later write.
         */
        fun observed(into: Int, afterEvents: Int, from: Int, throughEvents: Int): MeshScript =
            apply { observations += Observation(into, afterEvents, from, throughEvents) }

        fun rounds(): List<List<Op>> = rounds.toList()

        /** The rounds as a [Script]: one slice per replica, carrying the stated deliveries. */
        fun script(sources: List<SourceId>): Script = Script(
            (0 until replicaCount).map { i ->
                SourceScript(
                    source = sources[i],
                    events = rounds.flatten().filter { it.replica == i }.map { it.event },
                    deliveries = observations.filter { it.into == i }
                        .map { Delivery(it.afterEvents, sources[it.from], it.throughEvents) },
                )
            },
        )
    }

    /**
     * A quiescent OR-map replica mesh on one simulated host, plus the kernel convergence
     * invariant folding each replica's own delta outlet.
     *
     * One host and one registry deliberately: gossip is still the real
     * [Replication] mesh over routed proxies, but a single scheduler queue makes "these writes
     * were issued concurrently" a fact about the drive rather than about a random host choice.
     * The proxy calls of one round are all enqueued before the round's barrier drains any of
     * them, so each of them mints its dot against a state that has absorbed nothing from the
     * round — verified by [BS-1]'s three live dots at one key.
     */
    private class Mesh(seed: Long, val logicalId: UUID = UUID(0xC0FFEEL, 42L)) {
        val world = SimWorld(seed)
        val replication = Replication(world.registry)
        val convergence = ReplicaConvergence<TaggedMapDelta<String, String>, TaggedMapDelta<String, String>>(
            world.registry,
            logicalId,
            TaggedMapDelta(),
        ) { acc, delta -> TaggedMapTerminalFold.merge(acc, delta) }

        private val cells = mutableListOf<OrMapCell<String, String>>()
        private val ops = mutableListOf<MapOps<String, String>>()

        val replicas: List<OrMapCell<String, String>> get() = cells

        /** Spawn, replicate and attach one more replica. Callable mid-run: that is BS-6's late joiner. */
        fun join(): Int {
            val index = cells.size
            val cell = OrMapCell<String, String>(CellRef(logicalId, index.toLong()))
            replication.replicate(cell, world.host)
            cells += cell
            // Attached BEFORE the first drain, deliberately: a replica's fold is its OWN outlet
            // stream from the instant it exists (`[ORA2-CONV-04]`), and a late joiner's whole state
            // arrives as the link-time catch-up it re-emits as novelty. Attaching after the drain
            // would silently skip exactly that emission and make BS-6's late joiner fold to the
            // scraps that happened to arrive afterwards.
            convergence.attach(cell)
            world.runToIdle()
            ops += (
                HostedCellProxy.create(cell.ref, world.registry, OrMapInletProxy::class.java)
                    as OrMapInletProxy
                ).inlet.call
            return index
        }

        fun join(count: Int): Mesh = apply { repeat(count) { join() } }

        /** This mesh's script slice ids, one per replica, in instance order. */
        fun sources(): List<SourceId> = cells.indices.map { SourceId("r$it") }

        /**
         * `[ORA2-MODEL-12]`'s harness half: the replicas ranked by the KERNEL's own dot order.
         *
         * The kernel derives each instance's dot source as
         * `UUID.nameUUIDFromBytes("or-map-tags:<id>:<instanceId>")` and breaks a counter tie with
         * `TaggedMapDelta.DOT_ORDER`'s `thenBy { it.sourceId }` — the `UUID`'s natural order, which
         * is NOT the lexicographic order of its text. Sorting the real sources here and handing the
         * model ranks is what keeps the model from re-deriving a kernel identity it is forbidden to
         * read, and what keeps a hand-written "obviously replica 2 wins" from being wrong on about
         * half of all pairs.
         */
        fun dotOrder(): DotOrder {
            val bySource = cells.indices.associateWith { i ->
                UUID.nameUUIDFromBytes("or-map-tags:${cells[i].ref.id}:${cells[i].ref.instanceId}".toByteArray())
            }
            return DotOrder.ranked(bySource.entries.sortedBy { it.value }.map { SourceId("r${it.key}") })
        }

        /** The replica whose dot wins a counter tie — the highest-ranked one. */
        fun highestRanked(): Int {
            val order = dotOrder()
            return cells.indices.maxBy { order.rankOf(SourceId("r$it")) }
        }

        fun barrier() = world.runToIdle()

        /** Apply one round: every op enqueued before anything drains, then the barrier. */
        fun drive(rounds: List<List<Op>>, onRound: (Int) -> Unit = {}) {
            rounds.forEachIndexed { index, round ->
                round.forEach { op ->
                    when (val event = op.event) {
                        is ScriptEvent.Put -> ops[op.replica].put(event.key as String, event.element as String)
                        is ScriptEvent.RemoveKey -> ops[op.replica].remove(event.key as String)
                        else -> error("A tagged-map mesh drives Put/RemoveKey only, got $event")
                    }
                }
                barrier()
                onRound(index)
            }
        }

        /** How the oracle reads this mesh: outlet-stream folds plus the kernel invariant's verdict. */
        fun observe(): MeshObservation = MeshObservation.of(
            logicalId = logicalId,
            convergence = convergence,
            replicas = cells.indices.associate { SourceId("r$it") to cells[it].ref },
            stateOf = { delta -> TaggedMapTerminalFold.stateOf(delta) },
        )

        /** Each replica CELL's own reading — deliberately not what the oracle checks; see BS's CONV-04 test. */
        fun cellViews(): List<Map<String, String?>> =
            cells.map { cell -> cell.membership().associateWith { cell.value(it) } }
    }

    /** BS-1's mesh and script: three replicas, one key, three genuinely concurrent puts. */
    private fun concurrentSameKeyPuts(seed: Long): Pair<Mesh, MeshScript> {
        val mesh = Mesh(seed).join(3)
        val script = MeshScript(3).round(put(0, "k", "v0"), put(1, "k", "v1"), put(2, "k", "v2"))
        mesh.drive(script.rounds())
        return mesh to script
    }

    // =====================================================================
    // BS-1
    // =====================================================================

    @Test
    fun `BS-1 concurrent same-key puts resolve to the model's dot-order pick at every replica`() {
        val (mesh, meshScript) = concurrentSameKeyPuts(seed = 7L)
        val script = meshScript.script(mesh.sources())
        val check = ConvergenceCheck(mesh.dotOrder())

        // The puts really were concurrent: three live dots at one key, none tombstoned. Without
        // this the test would pass on a mesh that serialized the writes, where "every replica
        // agrees" is trivially true and says nothing about the dot order.
        val dots = mesh.replicas[0].state()
        dots.puts.getValue("k").size shouldBe 3
        dots.dels["k"] shouldBe null

        val outcome = check.check(seed = 7L, caseMarker = "BS-1", script = script, mesh = mesh.observe())
        withClue("outcome=$outcome") { outcome shouldBe RunOutcome.Success }

        // ...and it is the value the MODEL's (counter, rank) order selects, not merely an agreed
        // one: the winner is the highest-ranked instance, named independently of the kernel's answer.
        val winner = mesh.highestRanked()
        check.reference(script) shouldBe ModelState.MapState(mapOf("k" to "v$winner"))
        mesh.observe().folds.values.toSet() shouldBe setOf(ModelState.MapState(mapOf("k" to "v$winner")))
    }

    // =====================================================================
    // BS-7 — replicas agree but are wrong is a distinct verdict
    // =====================================================================

    @Test
    fun `BS-7 a uniformly mutated dot order yields replicas-agree-but-wrong, distinct from divergence and from Mismatch`() {
        val (mesh, meshScript) = concurrentSameKeyPuts(seed = 11L)
        val script = meshScript.script(mesh.sources())

        // The mutant: the kernel's dot order, inverted. Applied uniformly, so every replica still
        // agrees with every other — only the reference moves. This is [ORA2-CTL-02]'s substitution.
        val inverted = DotOrder.ranked(mesh.sources().sortedBy { source -> -mesh.dotOrder().rankOf(source) })
        val outcome = ConvergenceCheck(inverted)
            .check(seed = 11L, caseMarker = "BS-7", script = script, mesh = mesh.observe())

        val wrong = outcome.shouldBeInstanceOf<RunOutcome.ReplicasAgreeButWrong>()
        outcome shouldNotBe RunOutcome.Success
        // distinct KINDS, matched on type — not on a message and not on a flag ([ORA2-CONV-03])
        (outcome is RunOutcome.ReplicaDivergence) shouldBe false
        (outcome is RunOutcome.Mismatch) shouldBe false

        wrong.replicas shouldBe setOf("r0", "r1", "r2")
        wrong.actual shouldBe ModelState.MapState(mapOf("k" to "v${mesh.highestRanked()}"))
        wrong.expected shouldNotBe wrong.actual
        // the report still names the accepting replica of the (mutated) reference's winning dot
        wrong.keys shouldHaveSize 1
        wrong.keys[0].key shouldBe "k"
        val invertedWinner = mesh.sources().maxBy { inverted.rankOf(it) }
        wrong.keys[0].winningDot.shouldNotBeNull().source shouldBe invertedWinner
        wrong.keys[0].actualByReplica.values.toSet() shouldBe setOf("v${mesh.highestRanked()}")
    }

    @Test
    fun `BS-7's control - the same mesh under the kernel's own dot order is Success, so the verdict is the order and not the mesh`() {
        val (mesh, meshScript) = concurrentSameKeyPuts(seed = 11L)
        ConvergenceCheck(mesh.dotOrder())
            .check(11L, "BS-7 control", meshScript.script(mesh.sources()), mesh.observe()) shouldBe RunOutcome.Success
    }

    // =====================================================================
    // [ORA2-CONV-04] — the fold is the replica's own outlet stream
    // =====================================================================

    @Test
    fun `the checked fold is the replica's own outlet stream, not the cell's state`() {
        // A fourth replica joins the mesh AFTER the writes and is attached to the convergence
        // invariant only then, so its outlet stream carries the catch-up it receives but its fold
        // starts from empty at a different point than the others'. Every CELL converges — that is
        // asserted below — so a check that read `OrMapCell.membership()`/`value()` would report
        // Success. The oracle reads the streams, and therefore can tell the difference.
        val mesh = Mesh(seed = 13L).join(3)
        val meshScript = MeshScript(3).round(put(0, "k", "v0"))

        // a SECOND invariant, attached to r1 and r2 up front and to r0 only AFTER the write, so
        // r0's stream is deliberately truncated while its CELL holds the converged state.
        val late = ReplicaConvergence<TaggedMapDelta<String, String>, TaggedMapDelta<String, String>>(
            mesh.world.registry, mesh.logicalId, TaggedMapDelta(),
        ) { acc, delta -> TaggedMapTerminalFold.merge(acc, delta) }
        mesh.replicas.drop(1).forEach { late.attach(it) }
        mesh.drive(meshScript.rounds())
        late.attach(mesh.replicas[0])
        mesh.barrier()

        val truncated = MeshObservation.of(
            logicalId = mesh.logicalId,
            convergence = late,
            replicas = mesh.replicas.indices.associate { SourceId("r$it") to mesh.replicas[it].ref },
            stateOf = { delta -> TaggedMapTerminalFold.stateOf(delta) },
        )

        // every cell agrees — the mesh itself converged
        mesh.cellViews().toSet() shouldHaveSize 1
        // ...but the folds do not, because one of them was reconstructed from a partial stream
        truncated.folds.getValue(SourceId("r0")) shouldBe ModelState.MapState(emptyMap())

        val outcome = ConvergenceCheck(mesh.dotOrder())
            .check(13L, "CONV-04", meshScript.script(mesh.sources()), truncated)
        val divergence = outcome.shouldBeInstanceOf<RunOutcome.ReplicaDivergence>()
        (outcome is RunOutcome.ReplicasAgreeButWrong) shouldBe false
        (outcome is RunOutcome.Mismatch) shouldBe false
        divergence.perReplica.keys shouldBe setOf("r0", "r1", "r2")
        divergence.expected shouldBe ModelState.MapState(mapOf("k" to "v0"))
        divergence.keys shouldHaveSize 1
        // [ORA2-DIFF-09]: the differing key names the accepting replica of the winning dot
        divergence.keys[0].key shouldBe "k"
        divergence.keys[0].winningDot.shouldNotBeNull().source shouldBe SourceId("r0")
        divergence.keys[0].actualByReplica shouldBe mapOf("r0" to null, "r1" to "v0", "r2" to "v0")

        // the same mesh read through the FULL streams is Success — so the verdict above is the
        // truncation and not the mesh
        ConvergenceCheck(mesh.dotOrder())
            .check(13L, "CONV-04 control", meshScript.script(mesh.sources()), mesh.observe()) shouldBe
            RunOutcome.Success
    }

    // =====================================================================
    // [ORA2-DIFF-08] — quiescent replicas expose equal membership and values
    // =====================================================================

    @Test
    fun `ORA2-DIFF-08 quiescent replicas expose equal membership and equal per-key values, and they are the model's`() {
        val mesh = Mesh(seed = 17L).join(3)
        val meshScript = MeshScript(3)
            .round(put(0, "milk", "a0"), put(1, "eggs", "b1"))
            .round(put(2, "milk", "c2"), removeKey(1, "eggs"))
            .round(put(0, "bread", "d0"), removeKey(2, "milk"))
            // the only barrier effect any later write depends on: r2's re-put of milk tombstones
            // r0's a0, which r2 could only have seen because the first barrier delivered it
            .observed(into = 2, afterEvents = 0, from = 0, throughEvents = 1)
        mesh.drive(meshScript.rounds())
        val script = meshScript.script(mesh.sources())
        val observation = mesh.observe()

        // equal membership AND equal per-key values across every replica...
        observation.folds.values.map { (it as ModelState.MapState).entries.keys }.toSet() shouldHaveSize 1
        observation.folds.values.toSet() shouldHaveSize 1
        // ...and equal to the one converged reference, which is the claim agreement alone cannot make
        val check = ConvergenceCheck(mesh.dotOrder())
        withClue("reference=${check.reference(script)} folds=${observation.folds}") {
            check.check(17L, "DIFF-08", script, observation) shouldBe RunOutcome.Success
        }
        // the script is not trivially empty: a removed key is really gone and a survivor really survives
        (check.reference(script) as ModelState.MapState).entries.keys shouldBe setOf("bread")
    }

    // =====================================================================
    // BS-6 — late joiner equals early joiner
    // =====================================================================

    @Test
    fun `BS-6 a replica that joins after a mid-script barrier folds to the same answer as the early ones`() {
        val mesh = Mesh(seed = 19L).join(3)
        val early = MeshScript(3)
            .round(put(0, "milk", "a0"), put(1, "eggs", "b1"))
            .round(put(2, "milk", "c2"), put(0, "bread", "d0"))
        mesh.drive(early.rounds())

        // the late joiner: a fourth replica, spawned and attached only now
        mesh.join() shouldBe 3
        mesh.barrier()

        // one more round, now four-wide
        val whole = MeshScript(4)
            .round(put(0, "milk", "a0"), put(1, "eggs", "b1"))
            .round(put(2, "milk", "c2"), put(0, "bread", "d0"))
            .round(put(3, "jam", "e3"), removeKey(1, "eggs"))
            .observed(into = 2, afterEvents = 0, from = 0, throughEvents = 1)
        val lastRound = whole.rounds().last()
        mesh.drive(listOf(lastRound))

        val script = whole.script(mesh.sources())
        val observation = mesh.observe()
        val check = ConvergenceCheck(mesh.dotOrder())

        // the late replica's fold equals the early ones' AND equals the model's
        observation.folds.getValue(SourceId("r3")) shouldBe observation.folds.getValue(SourceId("r0"))
        withClue("reference=${check.reference(script)} folds=${observation.folds}") {
            check.check(19L, "BS-6", script, observation) shouldBe RunOutcome.Success
        }

        // and a late CONSUMER of one replica's outlet — linked only now, so its whole fold is the
        // link-time catch-up — reads the same answer ([ORA2-DIFF-05]'s consumer half)
        val terminal = TaggedMapTerminalFold<String, String>()
        mesh.world.host.managementInlet.call.spawn(terminal)
        val link = mesh.world.host.managementInlet.call
            .connect(mesh.replicas[2].ref, "outlet", terminal.ref, "inlet")
        (link is LinkResult.Rejected) shouldBe false
        mesh.barrier()
        terminal.current() shouldBe check.reference(script)
    }

    // =====================================================================
    // [ORA2-CONV-01] — one reference, invariant under the gossip interleaving
    // =====================================================================

    @Test
    fun `ORA2-CONV-01 the converged answer is invariant under a different seed-derived interleaving`() {
        val reference = concurrentSameKeyPuts(seed = 3L).let { (mesh, script) ->
            ConvergenceCheck(mesh.dotOrder()).reference(script.script(mesh.sources()))
        }
        val sources = (0 until 3).map { SourceId("r$it") }
        val template = MeshScript(3).round(put(0, "k", "v0"), put(1, "k", "v1"), put(2, "k", "v2"))

        // Every interleaving is checked against the SAME single reference, which is why this can
        // fail on a pair of interleavings that agree with each other and not with the model.
        val outcome = ConvergenceCheck(concurrentSameKeyPuts(seed = 3L).first.dotOrder())
            .acrossInterleavings(listOf(3L, 101L, 202L), "CONV-01", template.script(sources)) { seed ->
                concurrentSameKeyPuts(seed).first.observe()
            }
        outcome shouldBe RunOutcome.Success
        reference shouldBe ModelState.MapState(mapOf("k" to "v${concurrentSameKeyPuts(3L).first.highestRanked()}"))
    }

    // =====================================================================
    // [ORA2-DIFF-11] — a broken oracle is never read as a broken kernel
    // =====================================================================

    @Test
    fun `a script the model cannot fold is a ModelEvaluationFailure, never a divergence`() {
        val (mesh, _) = concurrentSameKeyPuts(seed = 23L)
        val sources = mesh.sources()
        // a cyclic delivery pair: each slice claims to have absorbed a prefix of the other that
        // contains that very claim, which names no reachable replica state
        val cyclic = Script(
            listOf(
                SourceScript(sources[0], listOf(ScriptEvent.Put(WriterId("w0"), "k", "v0")), listOf(Delivery(1, sources[1], 1))),
                SourceScript(sources[1], listOf(ScriptEvent.Put(WriterId("w1"), "k", "v1")), listOf(Delivery(1, sources[0], 1))),
                SourceScript(sources[2], emptyList()),
            ),
        )
        val outcome = ConvergenceCheck(mesh.dotOrder()).check(23L, "cyclic", cyclic, mesh.observe())
        val failure = outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
        failure.cause.shouldBeInstanceOf<DotModel.CyclicDeliveryException>()
        (outcome is RunOutcome.ReplicaDivergence) shouldBe false
        (outcome is RunOutcome.ReplicasAgreeButWrong) shouldBe false
    }
}
