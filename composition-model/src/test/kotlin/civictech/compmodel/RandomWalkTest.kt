package civictech.compmodel

import civictech.compmodel.cell.Budget
import civictech.compmodel.cell.CellModel
import civictech.compmodel.cell.LeafKind
import civictech.compmodel.cell.Layer
import civictech.compmodel.cell.Scenario
import civictech.compmodel.check.Explorer
import civictech.compmodel.check.Report
import civictech.compmodel.check.Seeds
import civictech.compmodel.check.Spec
import civictech.compmodel.composite.FlipModel
import civictech.compmodel.composite.FlipVariant
import civictech.compmodel.composite.PromotionModel
import civictech.compmodel.composite.PromotionVariant
import civictech.compmodel.composite.ReplicaSetModel
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Seeded random walks over configurations too large for exhaustive search (more frames,
 * more faults). `-Pcompmodel.seeds=N` sets the seed count; a failure prints its seed and
 * whole trace and reproduces from the seed alone.
 */
class RandomWalkTest {
    private fun <S : Any> walk(tag: String, spec: Spec<S>, steps: Int = 300) {
        val w = Explorer.walks(spec, Seeds.all(), steps)
        Report.line("[walk/$tag] seeds=${w.seeds} steps=${w.steps} ${if (w.holds) "HOLDS" else "VIOLATION"}")
        withClue(w.counterexample?.render() ?: "") { w.holds shouldBe true }
    }

    private val big = Budget(crash = 3, reconnect = 3, restart = 2, suspend = 2, checkpoint = 3, designate = 1)

    @Test fun `canonical stack, effect leaf, two lanes of three waves, many faults`() =
        walk("cell-effect-align", CellModel(Scenario("big-align", Layer.entries.toSet(), LeafKind.EFFECT, lanes = 2, perLane = 3, waves = true, ownedFirst = true, budget = big), Layer.CANONICAL))

    @Test fun `canonical stack, mergeable leaf with catch-up after RESTART`() =
        walk("cell-merge", CellModel(Scenario("big-merge", Layer.entries.toSet(), LeafKind.SET, lanes = 2, perLane = 3, budget = big), Layer.CANONICAL))

    @Test fun `canonical stack, relay with downstream positions`() =
        walk("cell-relay", CellModel(Scenario("big-relay", Layer.entries.toSet(), LeafKind.RELAY, perLane = 4, budget = big), Layer.CANONICAL))

    @Test fun `flip with two router and two shard crashes`() =
        walk("flip", FlipModel(FlipVariant(abortFencesFirst = true), routerCrashes = 2, shardCrashes = 2))

    @Test fun `promotion with five frames`() =
        walk("promotion", PromotionModel(PromotionVariant(recheckOnRelease = true, tier = 2), frames = 5))

    @Test fun `replica set with five positions`() = walk("replica", ReplicaSetModel(frames = 5))
}
