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

    @Test fun `control - Supervised outside Durable re-mints identity on crash and downstream takes an output twice`() {
        diverges("control/S-outside-D", CellModel(Scenario("relay", emptySet(), LeafKind.RELAY, perLane = 2, budget = faults), Layer.parse("SDOPFAX")), "took one output twice")
    }

    @Test fun `control - un-journaled RESTART epoch rotation reverts on crash`() {
        diverges("control/unjournaled-epoch", CellModel(Scenario("relay", emptySet(), LeafKind.RELAY, perLane = 2, budget = faults), canonical, Variant(journalEpochRotation = false)), "I3")
    }

    // -- findings ------------------------------------------------------------------------
    /**
     * FINDING CELL-1 (model-results.md): a non-idempotent, non-Effectful durable inlet that
     * enforces dedup on `applied` (PLP §5.3) loses that memory on a succession RESTART
     * (`applied` is inside `S`); a duplicate retransmitted after the RESTART is absorbed again
     * and, re-emitted under the fresh epoch, is taken twice downstream.
     */
    @Test fun `FINDING CELL-1 applied-only dedup does not survive a succession RESTART`() {
        diverges("finding/CELL-1", CellModel(Scenario("relay-noX", emptySet(), LeafKind.RELAY, perLane = 2, budget = Budget(reconnect = 1, restart = 1)), Layer.parse("DOPS")), "took one output twice")
        holds("finding/CELL-1-with-X", CellModel(Scenario("relay-X", emptySet(), LeafKind.RELAY, perLane = 2, budget = Budget(reconnect = 1, restart = 1)), Layer.parse("DOPXS")))
    }
}
