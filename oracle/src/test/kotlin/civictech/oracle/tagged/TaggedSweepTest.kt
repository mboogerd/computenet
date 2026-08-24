package civictech.oracle.tagged

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.oracle.model.DotOrder
import civictech.oracle.model.DotModel
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.CaseGraph
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.ScriptSource
import civictech.oracle.run.StateDifference
import civictech.oracle.run.TaggedMapTerminalFold
import civictech.oracle.run.OracleSweep
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The **generated** tagged/keyed differential sweep — `[ORA2-DIFF-01]`, `[ORA2-DIFF-06]`,
 * `[ORA2-DIFF-10]`, `[ORA2-DIFF-11]`, `[ORA2-PERF-01]`, `[ORA2-PERF-02]`.
 *
 * ## Why this drives [DifferentialRunner.check] rather than [OracleSweep.run]
 *
 * `civictech.oracle.gen.GraphGenerator.generate` unconditionally requires at least one
 * **operator** entry in the vocabulary (`check(operatorEntries.isNotEmpty())`); `orMap` is
 * registered only as a source (`civictech.oracle.bind.TaggedOperators`'s file KDoc), and no
 * operator in `CoreOperators` can legally consume its output — the map-shaped joins
 * (`JoinCell`/`CombineLatestCell`/`LookupJoinCell`) are typed to `Propagate<MapDelta<K, V>>`,
 * not `Propagate<TaggedMapDelta<K, V>>`, so wiring an `OrMapCell` outlet into one would be a
 * genuine kernel type violation, not a legitimate generated case. So `CaseGenerator` cannot
 * emit a single-instance `orMap` case at all, and [OracleSweep.run] — built on top of it — is
 * not the seam this sweep can reuse.
 *
 * What IS reused, and is the seam this task actually needs, is `[ORA1-DIFF-11]`'s **bring-your-
 * own** entry point, [DifferentialRunner.check]: a caller-supplied graph, script and reference,
 * run through the identical comparison/reporting/taxonomy code the generated path shares. This
 * sweep is such a caller. The density loop is `civictech.testkit.forEachSeed` — the SAME
 * function [OracleSweep.run] itself calls — so there is no second sweep loop; only the case
 * source (a small script generator here, `CaseGenerator` there) differs.
 *
 * ## What is generated
 *
 * A random single-writer `Put`/`RemoveKey` script over a small key/value domain, biased toward
 * already-populated keys so re-puts and reset-removes actually occur — the same bias
 * `[ORA2-GEN-05]`'s generator dimension states, applied here by hand since the case is not a
 * `GeneratedCase`. One instance, one writer, no gossip deliveries: exactly what
 * `civictech.oracle.model.SingleInstanceOrMapModel` can honestly evaluate, and exactly what a
 * single `OrMapCell` driven directly is.
 */
private const val SYNTHETIC_DESCRIPTION = "TaggedSweepTest synthetic dead letter [ORA2-DIFF-10]"

class TaggedSweepTest {

    private val source = SourceId("s")
    private val writer = WriterId("w")
    private val keys = listOf("k0", "k1", "k2")
    private val values = listOf("v0", "v1", "v2", "v3")

    /** One random single-instance `orMap` script, biased toward re-puts on a small key domain. */
    private fun randomScript(seed: Long, length: Int = 40): SourceScript {
        val rnd = Random(seed)
        val events = (0 until length).map {
            val key = keys[rnd.nextInt(keys.size)]
            if (rnd.nextDouble() < 0.75) {
                ScriptEvent.Put(writer, key, values[rnd.nextInt(values.size)])
            } else {
                ScriptEvent.RemoveKey(writer, key)
            }
        }
        return SourceScript(source, events)
    }

    /** [MapOps] as a [ScriptSource] — `orMap`'s put/removeKey, not `SetOps`'s add/remove. */
    private fun MapOps<Any?, Any?>.asOrMapScriptSource(): ScriptSource = object : ScriptSource {
        override fun put(key: Any?, element: Any?) = this@asOrMapScriptSource.put(key, element)
        override fun removeKey(key: Any?) = this@asOrMapScriptSource.remove(key)
    }

    /** Builds one live `OrMapCell` + [TaggedMapTerminalFold] pair, hosted on [world]. */
    private fun buildGraph(world: SimWorld): CaseGraph {
        val cell = OrMapCell<Any?, Any?>(CellRef(java.util.UUID.randomUUID()))
        val fold = TaggedMapTerminalFold<Any?, Any?>()
        world.host.managementInlet.call.spawn(cell)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(cell.ref, "outlet", fold.ref, "inlet")
        @Suppress("UNCHECKED_CAST")
        val ops = (
            HostedCellProxy.create(cell.ref, world.registry, OrMapInletProxy::class.java) as OrMapInletProxy
            ).inlet.call as MapOps<Any?, Any?>
        return CaseGraph(
            terminals = mapOf("orMap" to fold),
            sources = mapOf(source to ops.asOrMapScriptSource()),
        )
    }

    /** The same proxy shape [ConvergenceCheckTest] uses for a hosted `OrMapCell`. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<Any?, Any?>>
    }

    /** The reference: [DotModel] over the single slice, order-irrelevant with one instance. */
    private fun referenceFor(slice: SourceScript): Reference = Reference { script ->
        mapOf("orMap" to DotModel(DotOrder.ranked(listOf(slice.source))).evaluate(script))
    }

    // =====================================================================
    // [ORA2-DIFF-01] / [ORA2-DIFF-06] / [ORA2-PERF-01] / [ORA2-PERF-02]
    // =====================================================================

    /**
     * Every seed of the (possibly `-Poracle.seeds`-widened) default range agrees with the
     * batch reference — `forEachSeed`'s density form, reused verbatim (`[ORA2-DIFF-06]`), over
     * [OracleSweep.defaultSeeds] so `-Poracle.seeds` widens this sweep exactly as it widens
     * ORA1's, with no source change (`[ORA2-PERF-02]`).
     */
    @Test
    fun `every seed of the default range agrees with the batch reference`() {
        val seeds = OracleSweep.defaultSeeds()
        val startedAt = System.nanoTime()
        var count = 0
        forEachSeed(seeds) { seed ->
            count++
            val slice = randomScript(seed)
            val script = Script(listOf(slice))
            val outcome = DifferentialRunner.check(
                seed = seed,
                caseMarker = "tagged-sweep",
                script = script,
                reference = referenceFor(slice),
                buildGraph = ::buildGraph,
            )
            withClue("seed=$seed outcome=$outcome") { outcome shouldBe RunOutcome.Success }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println(
            "[tagged-sweep] $count seeds in ${elapsedMs}ms " +
                "(${"%.2f".format(elapsedMs.toDouble() / count)} ms/seed) [ORA2-PERF-01]",
        )
    }

    // =====================================================================
    // [ORA2-DIFF-01]'s report shape, forced with a mutant reference
    // =====================================================================

    /**
     * A deliberately wrong reference over a real tagged run produces a [RunOutcome.Mismatch]
     * naming the seed, the terminal, and — via [StateDifference.MapDifference] — the differing
     * key set and the per-key expected/actual pair. This is what proves the generic report shape
     * `DifferentialRunner` already carries actually POPULATES those fields for a tagged terminal,
     * not merely that the type exists.
     */
    @Test
    fun `ORA2-DIFF-01 a mutant reference is reported as a Mismatch naming the seed, terminal and per-key difference`() {
        val slice = randomScript(seed = 5L)
        val script = Script(listOf(slice))
        val wrong = Reference { mapOf("orMap" to ModelState.MapState(mapOf("k0" to "not-the-real-value"))) }

        val outcome = DifferentialRunner.check(
            seed = 5L,
            caseMarker = "tagged-sweep-mutant",
            script = script,
            reference = wrong,
            buildGraph = ::buildGraph,
        )

        val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
        mismatch.seed shouldBe 5L
        mismatch.terminal shouldBe "orMap"
        val diff = mismatch.difference.shouldBeInstanceOf<StateDifference.MapDifference>()
        withClue("difference=$diff") {
            (diff.changed.containsKey("k0") || diff.onlyInExpected.containsKey("k0") || diff.onlyInActual.containsKey("k0")) shouldBe true
        }
    }

    // =====================================================================
    // [ORA2-DIFF-11] — a broken model is a ModelEvaluationFailure, never a Mismatch
    // =====================================================================

    @Test
    fun `ORA2-DIFF-11 a reference that throws is a ModelEvaluationFailure, reused unchanged for a tagged run`() {
        val slice = randomScript(seed = 9L)
        val script = Script(listOf(slice))
        val broken = Reference { error("the tagged reference is deliberately broken") }

        val outcome = DifferentialRunner.check(
            seed = 9L,
            caseMarker = "tagged-sweep-broken-model",
            script = script,
            reference = broken,
            buildGraph = ::buildGraph,
        )

        outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>().cause.message shouldBe
            "the tagged reference is deliberately broken"
    }

    // =====================================================================
    // [ORA2-DIFF-10] — a dead letter during a tagged run surfaces as the reused kind
    // =====================================================================

    /**
     * `[ORA2-DIFF-10]`: a dead letter during a tagged run must surface as
     * [RunOutcome.DeadLetterFailure] — the SAME kind ORA1 already produces, unconditionally of
     * which delta type the graph carries (`DifferentialRunner.execute`'s dead-letter check reads
     * `letters`, not the graph's shape).
     *
     * An earlier version of this test asserted only [RunOutcome.Success] and reasoned about the
     * dead-letter path structurally rather than forcing it red — which review correctly rejected:
     * asserting Success is compatible with the dead-letter path never being reached at all.
     * `civictech.oracle.run.FailureTaxonomyTest` (ORA1) shows the sanctioned, non-fault-injection
     * mechanism this test now reuses: `ManagedHost.deadLetterOutlet` is a public `FanOutlet` and
     * [DeadLetter] a public data class, so a component fanned off the source's outlet can publish
     * a SYNTHETIC dead letter without breaking a link or a host — no fault injection, and nothing
     * this feature's NON-GOALS (§6: quiescent meshes only) forbid. [TaggedDeadLetterEmitter] below
     * is that component, ported to `TaggedMapDelta` from ORA1's `SetDelta` original.
     */
    @Test
    fun `ORA2-DIFF-10 a dead letter during a tagged run surfaces as DeadLetterFailure`() {
        val slice = randomScript(seed = 1L)
        val outcome = DifferentialRunner.check(
            seed = 1L,
            caseMarker = "tagged-sweep-dead-letter",
            script = Script(listOf(slice)),
            reference = referenceFor(slice),
            buildGraph = ::buildGraphWithDeadLetterEmitter,
        )

        val failure = outcome.shouldBeInstanceOf<RunOutcome.DeadLetterFailure>()
        failure.seed shouldBe 1L
        failure.deadLetters.isEmpty() shouldBe false
        failure.deadLetters.first().description shouldBe SYNTHETIC_DESCRIPTION
    }

    /**
     * The discrimination this needs: identical seed, script and reference, with only the
     * emitter removed. Without this control, [RunOutcome.DeadLetterFailure] above could be
     * produced by something else in the graph and this test would prove nothing about the
     * dead-letter path specifically.
     */
    @Test
    fun `the same tagged case without the dead-letter emitter is Success, so the verdict is the letter and not the graph`() {
        val slice = randomScript(seed = 1L)
        val outcome = DifferentialRunner.check(
            seed = 1L,
            caseMarker = "tagged-sweep-dead-letter-control",
            script = Script(listOf(slice)),
            reference = referenceFor(slice),
            buildGraph = ::buildGraph,
        )
        outcome shouldBe RunOutcome.Success
    }

    /** [buildGraph], with a [TaggedDeadLetterEmitter] fanned off the source's outlet. */
    private fun buildGraphWithDeadLetterEmitter(world: SimWorld): CaseGraph {
        val cell = OrMapCell<Any?, Any?>(CellRef(java.util.UUID.randomUUID()))
        val fold = TaggedMapTerminalFold<Any?, Any?>()
        val emitter = TaggedDeadLetterEmitter(world.host)
        world.host.managementInlet.call.spawn(cell)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.spawn(emitter)
        world.host.managementInlet.call.connect(cell.ref, "outlet", fold.ref, "inlet")
        world.host.managementInlet.call.connect(cell.ref, "outlet", emitter.ref, "inlet")
        @Suppress("UNCHECKED_CAST")
        val ops = (
            HostedCellProxy.create(cell.ref, world.registry, OrMapInletProxy::class.java) as OrMapInletProxy
            ).inlet.call as MapOps<Any?, Any?>
        return CaseGraph(
            terminals = mapOf("orMap" to fold),
            sources = mapOf(source to ops.asOrMapScriptSource()),
        )
    }

    /**
     * Publishes one synthetic dead letter on [host]'s outlet the first time a `TaggedMapDelta`
     * reaches it — mid-run, driven by the same stream the terminal fold sees. Ported from
     * `civictech.oracle.run.FailureTaxonomyTest.DeadLetterEmitter` (ORA1), whose KDoc records
     * that this route is real and reachable, not merely described.
     */
    private class TaggedDeadLetterEmitter(
        private val host: ManagedHost,
        override val ref: CellRef = CellRef(java.util.UUID.randomUUID()),
    ) : Cell {
        private var emitted = false

        val inlet = registerPort("inlet", FanInlet.create<Propagate<TaggedMapDelta<Any?, Any?>>>())

        init {
            inlet.serve(object : Propagate<TaggedMapDelta<Any?, Any?>> {
                override fun propagate(value: TaggedMapDelta<Any?, Any?>) {
                    if (emitted) return
                    emitted = true
                    host.deadLetterOutlet.call.propagate(
                        DeadLetter(hostRef = host.ref, cause = null, description = SYNTHETIC_DESCRIPTION),
                    )
                }
            })
        }
    }
}
