package civictech.compmodel

import civictech.compmodel.cell.Budget
import civictech.compmodel.cell.CellModel
import civictech.compmodel.cell.FormationCheck
import civictech.compmodel.cell.LeafKind
import civictech.compmodel.cell.Layer
import civictech.compmodel.cell.Scenario
import civictech.compmodel.cell.Variant
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The per-cell stack (COH §2) with exact per-link positions (PLP §5): the canonical stack
 * keeps every invariant, and each control modelled on today's behaviour or a rejected design
 * produces a counterexample (the checker can see that defect).
 */
class CellModelTest {
    private val canonical = Layer.CANONICAL
    private val faults = Budget(crash = 1, reconnect = 1, restart = 1, checkpoint = 1)

    @Test fun `canonical stack is accepted by the formation rules`() {
        FormationCheck.accepts(canonical) shouldBe true
        FormationCheck.check(Layer.parse("DSPXOFA")).map { it.rule }.toSet().contains("F2") shouldBe true
    }

    // -- design holds -----------------------------------------------------------------
    @Test fun `diamond and double emit into one Effectful port both act (positions, PLP 5_3)`() {
        holds("cell/same-wave", CellModel(Scenario("same-wave", emptySet(), LeafKind.EFFECT, perLane = 2, sameWave = true, budget = faults), canonical))
    }

    @Test fun `upstream succession with checkpoint and crash fences the dead epoch`() {
        holds("cell/upstream-succession", CellModel(Scenario("succession", emptySet(), LeafKind.EFFECT, upstreamSuccession = true, budget = faults), canonical))
    }

    @Test fun `replay-deterministic relay keeps positions across crash and RESTART`() {
        holds("cell/relay", CellModel(Scenario("relay", emptySet(), LeafKind.RELAY, perLane = 2, budget = faults), canonical))
    }

    @Test fun `M1 option (a) - replay the transition, suppress only the act - keeps Effectful x Stateful state`() {
        holds("cell/M1a", CellModel(Scenario("eff-stateful", emptySet(), LeafKind.EFFECT_STATEFUL, perLane = 2, budget = Budget(crash = 1, checkpoint = 1, reconnect = 1)), canonical, Variant(suppressOnlyAct = true)))
    }

    // -- controls that must diverge ----------------------------------------------------
    @Test fun `control wlwjw - frontier keyed on root wave per port drops a live frame`() {
        diverges("control/wlwjw", CellModel(Scenario("same-wave", emptySet(), LeafKind.EFFECT, perLane = 2, sameWave = true, budget = faults), canonical, Variant(xKeyedOnWave = true)), "silent loss")
    }

    @Test fun `control kxdjx - dead epochs not snapshotted re-admit a straggler`() {
        diverges("control/kxdjx", CellModel(Scenario("succession", emptySet(), LeafKind.EFFECT, upstreamSuccession = true, budget = faults), canonical, Variant(snapshotDead = false)), "I2 effect")
    }

    @Test fun `control DISPUTES 1232 - whole-invocation suppression loses Effectful x Stateful state (F8 refuses it)`() {
        FormationCheck.leafRefusal(canonical, LeafKind.EFFECT_STATEFUL) shouldBe "F8"
        diverges("control/DISPUTES-1232", CellModel(Scenario("eff-stateful", emptySet(), LeafKind.EFFECT_STATEFUL, perLane = 2, budget = Budget(crash = 1, checkpoint = 1, reconnect = 1)), canonical), "I4 refinement")
    }

    /**
     * `S` outside `D` with a crash and NO RESTART: the crash loses `S`'s identity tier, recovery
     * re-mints it, and replay re-derives an output under the fresh epoch with no `ReBaseline`.
     * The trace must contain the crash, so the divergence is the crash defect, not F2's.
     */
    @Test fun `control - Supervised outside Durable re-mints identity on a crash without RESTART`() {
        val e = diverges("control/S-outside-D-crash", CellModel(Scenario("relay", emptySet(), LeafKind.RELAY, perLane = 2, budget = Budget(crash = 1, reconnect = 1, checkpoint = 1)), Layer.parse("SDOPFAX")), "took one output twice")
        val trace = e.counterexample!!.trace
        trace.any { it.startsWith("CRASH") } shouldBe true
        trace.none { it.startsWith("RESTART") } shouldBe true
    }

    @Test fun `control - Supervised outside Durable, RESTART re-mints without the journal`() {
        diverges("control/S-outside-D-restart", CellModel(Scenario("relay", emptySet(), LeafKind.RELAY, perLane = 2, budget = Budget(restart = 1, reconnect = 1)), Layer.parse("SDOPFAX")), "took one output twice")
    }

    @Test fun `control - un-journaled RESTART epoch rotation reverts on crash`() {
        diverges("control/unjournaled-epoch", CellModel(Scenario("relay", emptySet(), LeafKind.RELAY, perLane = 2, budget = faults), canonical, Variant(journalEpochRotation = false)), "I3")
    }

    // -- findings ------------------------------------------------------------------------
    /**
     * FINDING REPLAY-1 (new, open; model-results.md §4): `P`'s release of a parked frame is not
     * a `D` record. Live: RESUME, `P` releases, the relay emits under epoch e, then RESTART
     * (journaled) rotates the epoch. Replay re-runs the RESTART before the release (which is not
     * in the log), so the frame is re-derived under the fresh epoch and downstream takes it
     * twice. Only the full stack shows it: no projection has both `P` and a non-`Effectful`
     * emitter with RESTART. The candidate fix (D logs each release) holds.
     */
    @Test fun `FINDING REPLAY-1 an unlogged P release replays after a later RESTART`() {
        val sc = Scenario("relay-suspend-restart", Layer.entries.toSet(), LeafKind.RELAY, perLane = 2, budget = Budget(crash = 1, restart = 1, suspend = 1, checkpoint = 1))
        val e = diverges("finding/REPLAY-1", CellModel(sc, canonical), "took one output twice")
        val tr = e.counterexample!!.trace
        (tr.indexOfFirst { it.startsWith("P releases") } < tr.indexOfFirst { it.startsWith("RESTART") }) shouldBe true
        holds("finding/REPLAY-1-logged-release", CellModel(sc, canonical, Variant(logPRelease = true)))
    }

    /**
     * FINDING CELL-1 (model-results.md §4), fixed by COH F12: a durable non-idempotent term
     * deduplicating on `applied` alone (the `299d4e9c` text of PLP §5.3) loses that memory on a
     * succession RESTART; a retained duplicate is absorbed again and, re-emitted under the fresh
     * epoch, taken twice downstream. F12 refuses that stack; the stack it admits holds.
     */
    @Test fun `FINDING CELL-1 fixed - F12 refuses applied-only dedup, the old text still diverges`() {
        val budget = Budget(reconnect = 1, restart = 1, crash = 1, checkpoint = 1)
        FormationCheck.leafRefusal(Layer.parse("DOPS"), LeafKind.RELAY) shouldBe "F12"
        FormationCheck.leafRefusal(Layer.parse("DOPXS"), LeafKind.RELAY) shouldBe null
        diverges("control/CELL-1-old-text", CellModel(Scenario("relay-noX", emptySet(), LeafKind.RELAY, perLane = 2, budget = budget), Layer.parse("DOPS")), "took one output twice")
        holds("finding/CELL-1-fixed", CellModel(Scenario("relay-X", emptySet(), LeafKind.RELAY, perLane = 2, budget = budget), Layer.parse("DOPXS")))
    }
}
