package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.ReBaselineEmitting
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * KFX feature 4, task 2 — BS-22's non-recovery fresh-mint arms
 * (`[KFX-14]`; 93 I-14 Rule S1), pinned head-on for the two arms
 * [OutletWaveRecoveryTest] does not already cover:
 *
 *  - (b) a replica/candidate spawn mints fresh **by construction** — a new
 *    instance's [FanOutlet] mints a random `sourceId` at construction time,
 *    before any promotion adoption ever runs
 *    (`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:127`).
 *  - (c) the T2 catch-up-fallback promotion swap
 *    (`kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:200-206`)
 *    mints fresh via `to.mintFreshEpoch()` AND emits the `ReBaseline`
 *    supersession — "the fresh epoch MUST NOT be silent" (93 I-14 Rule S5) —
 *    so a downstream glitch-free consumer can recompute its per-source
 *    completeness set instead of silently aliasing the incumbent's spent
 *    counters under a new identity.
 *
 * The third non-recovery arm, RESTART supervision's `mintFreshEpoch`
 * (`ManagedHost.kt:900`), is already asserted head-on by
 * `civictech.cell.host.RestartReBaselineTest` ("RESTART mid-stream mints a
 * fresh epoch..."), pre-dating this feature (W2.1) — not duplicated here.
 *
 * The cold-start arm is [OutletWaveRecoveryTest]'s
 * `a volatile cell's outlet still mints a fresh epoch per incarnation`.
 * BS-23's preserved-epoch counterpart (drain/migration/promotion state
 * transfer keeping the SAME epoch) is [PromotionWaveStateTest].
 */
class EpochTransitionFreshMintTest {

    /**
     * A bare, non-[civictech.cell.Stateful] producer: promoting over one
     * always takes [Promotion]'s T2 catch-up-fallback branch (`migrates` is
     * false whenever the candidate is not [StateMigrating] over a
     * [civictech.cell.Stateful] incumbent).
     */
    class BareProducerCell(override val ref: CellRef) : Cell, ReBaselineEmitting {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        var lastReBaseline: Pair<Set<UUID>, Boolean>? = null
            private set

        override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
            lastReBaseline = supersedes to supersede
            outlet.reBaseline(supersedes, supersede) { propagate("catch-up") }
        }
    }

    /**
     * Arm (b): a spawned replica/candidate's outlet is a disjoint source from
     * the incumbent's — fresh by construction, no promotion involved yet.
     */
    @Test
    fun `arm (b) - a spawned replica-candidate outlet mints a fresh sourceId disjoint from the incumbent's`() {
        val controller = SimulationController(seed = 21)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId = UUID.randomUUID()

        val incumbent = BareProducerCell(CellRef(logicalId, instanceId = 0))
        val candidate = BareProducerCell(CellRef(logicalId, instanceId = 1))
        host.managementInlet.call.spawn(incumbent)
        host.managementInlet.call.spawn(candidate)
        controller.runToIdle()

        // 93 I-14 Rule S1: a newly spawned replica/candidate mints its own
        // random sourceId at construction — disjoint from the incumbent's,
        // long before any promotion adoption (or lack thereof) ever runs.
        (candidate.outlet.waveState().sourceId != incumbent.outlet.waveState().sourceId).shouldBeTrue()
    }

    /**
     * Arm (c): the T2 catch-up-fallback promotion swap. Neither cell is
     * [civictech.cell.Stateful]/[StateMigrating], so [Promotion.promote] must
     * take the fresh-mint branch (`Evolution.kt:200-206`) rather than
     * preserved-epoch adoption — and the succession must be wave-observable,
     * not silent (93 I-14 Rule S5).
     */
    @Test
    fun `arm (c) - the T2 catch-up-fallback promotion swap mints fresh AND emits the supersession downstream`() {
        val controller = SimulationController(seed = 22)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId = UUID.randomUUID()

        val gate = TrafficLightCell.create<Propagate<String>>()
        val incumbent = BareProducerCell(CellRef(logicalId, instanceId = 0))
        val candidate = BareProducerCell(CellRef(logicalId, instanceId = 1))

        host.managementInlet.call.spawn(gate)
        host.managementInlet.call.spawn(incumbent)
        host.managementInlet.call.spawn(candidate)
        controller.runToIdle()

        val incumbentSourceId = incumbent.outlet.waveState().sourceId
        // the candidate's own fresh-by-construction epoch (arm (b)'s minting,
        // spawned before promotion ever runs) — the epoch [Evolution.kt:206]'s
        // `to.mintFreshEpoch()` rotates AWAY from and names as superseded
        val candidatePreEpoch = candidate.outlet.waveState().sourceId

        // watch every emission the candidate's outlet makes across the swap
        val seen = mutableListOf<MessageContext>()
        candidate.outlet.observe(PortRef.generate()) { seen += it }

        Promotion.promote(
            host, gate, incumbent, candidate, "outlet",
            downstream = emptyList(),
        )

        // fresh mint (93 I-14 Rule S1): the candidate's outlet ends up on a
        // THIRD epoch — neither the incumbent's lane (no adoptWaveState ran)
        // nor its own pre-promotion construction-time one (mintFreshEpoch
        // rotated past it)
        val postSourceId = candidate.outlet.waveState().sourceId
        (postSourceId != incumbentSourceId).shouldBeTrue()
        (postSourceId != candidatePreEpoch).shouldBeTrue()

        // "the fresh epoch MUST NOT be silent" (93 I-14 Rule S5): the
        // succession is wave-observable via an ordinary ReBaseline-flagged
        // catch-up emission naming the superseded epoch — the signal a
        // downstream glitch-free consumer needs to recompute its per-source
        // completeness set rather than silently drop the lane
        seen.size shouldBe 1
        seen.single().reBaseline?.supersedes shouldBe setOf(candidatePreEpoch)
        seen.single().reBaseline?.supersede shouldBe true
        candidate.lastReBaseline shouldBe (setOf(candidatePreEpoch) to true)
    }
}
