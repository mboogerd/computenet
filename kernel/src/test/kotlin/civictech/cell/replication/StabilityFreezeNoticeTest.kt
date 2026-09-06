package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * E3.5 (`computenet-9sm.5.1`): [Replication.onStabilityStall] — the
 * stability-freeze notice's carrier ([KE3-27], [KE3-24]; decisions
 * 9sm.5-D5/D6/D7). The predicate itself is pinned by
 * `StabilityFreezeDetectorTest`; this file pins the *wiring*: the companion
 * tap, the one-snapshot open set, the app listeners, and the
 * `notifyDownstream` fan over every local replica.
 *
 * **The rig** is `StabilityAdvanceTest`'s — one real peer L (a replicated
 * [SetCell]) plus PHANTOM member slots injected straight into L's companion
 * through `deltaInlet`, so every row is under deterministic control with no
 * second [ManagedHost] and no gossip mesh to schedule.
 *
 * **Evaluations are counted, not assumed.** The detector latches on the H-th
 * *consecutive* (lagging ∧ unchanged) evaluation, and one evaluation is one
 * companion-outlet delta — so [Rig.deltas] taps that outlet and every test
 * below asserts how many evaluations its injections produced. Without that
 * count a stall arriving "eventually" would be indistinguishable from one
 * arriving at the wrong threshold.
 */
class StabilityFreezeNoticeTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Rig(adds: Int = 7) {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId: UUID = UUID.randomUUID()
        val cell = SetCell<String>(CellRef(logicalId, 0))

        val companion: WatermarkCell
        val slotL: UUID
        val slotA: UUID
        val slotB: UUID

        /** L's per-origin tag source — the source every assertion below is about. */
        val s: UUID

        /** Companion-outlet deltas seen so far: one delta is one detector evaluation. */
        var deltas: Int = 0
            private set

        init {
            replication.replicate(cell, host)
            controller.runToIdle()
            val ops = (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java)
                as SetInletProxy).inlet.call
            repeat(adds) { i ->
                ops.add("e$i")
                controller.runToIdle()
            }
            companion = replication.watermarkOf(logicalId)!!
            slotL = WatermarkCell.slotId(replication.watermarkRef(cell.ref))
            slotA = WatermarkCell.slotId(replication.watermarkRef(CellRef(logicalId, 1)))
            slotB = WatermarkCell.slotId(replication.watermarkRef(CellRef(logicalId, 2)))

            @Suppress("UNCHECKED_CAST")
            val epochSource = (cell.outlet as FanOutlet<Propagate<SetDelta<String>>>).waveState().sourceId
            s = (companion.rows().getValue(slotL).keys - epochSource).single()

            companion.outlet.tap(
                Use.fixed(Propagate<WatermarkDelta> { deltas++ }, PortRef.generate())
            )
        }

        /** Feed the companion a peer delta, then drain the scheduler. */
        fun inject(delta: WatermarkDelta) {
            companion.deltaInlet.call.propagate(delta)
            controller.runToIdle()
        }

        /** Announce A and B as open members with rows `A:{s→a}, B:{s→b}`. */
        fun phantoms(a: Long, b: Long) = inject(
            WatermarkDelta(
                rows = mapOf(slotA to mapOf(s to a), slotB to mapOf(s to b)),
                members = setOf(slotA, slotB),
            )
        )

        /** Advance A to [a], leaving B where it is — one evaluation. */
        fun advanceA(a: Long) = inject(WatermarkDelta(rows = mapOf(slotA to mapOf(s to a))))

        /** Every stability notice a listener registered now is handed. */
        fun record(): MutableList<StallNotice> = mutableListOf<StallNotice>().also { seen ->
            replication.onStabilityStall(logicalId) { seen += it }
        }

        /**
         * A real downstream consumer on L's replica outlet, recording the
         * `Suspension` notices its own link receives — the `FanOutletTest`
         * idiom. A real link, not a `Use.fixed` subscription: `notifyDownstream`
         * walks `linking.links`, so a subscriber that never handshook has no
         * edge to be told about and the assertion would pass vacuously.
         */
        fun downstream(): MutableList<StallNotice> {
            val seen = mutableListOf<StallNotice>()
            val inlet = FanInlet.create<Propagate<SetDelta<String>>>().also {
                it.serve(object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) = Unit
                })
            }
            ProtocolSupport.of(inlet).handle(Protocols.Suspension) { _, m -> seen += m as StallNotice }
            @Suppress("UNCHECKED_CAST")
            val result = (cell.outlet as FanOutlet<Propagate<SetDelta<String>>>)
                .linkTo(inlet as LinkFrom<Propagate<SetDelta<String>>>)
            check(result is LinkResult.Connected) { "the edge must really open: $result" }
            return seen
        }
    }

    /**
     * The default threshold is 3 consecutive (lagging ∧ unchanged) evaluations,
     * and the evaluation immediately after registration cannot count (the
     * detector's previous-rows view is empty, so every row reads as changed).
     * So four post-registration evaluations produce exactly one stall.
     */
    private fun Rig.freezeB() {
        val before = deltas
        advanceA(6L)
        advanceA(7L)
        advanceA(8L)
        advanceA(9L)
        (deltas - before) shouldBe 4
    }

    @Test
    fun `KE3-27 a frozen phantom slot yields exactly one Stall naming its slot and wave position`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)

        val seen = rig.record()
        rig.freezeB()

        // B never moved while L (7) and A (6..9) both ran past it.
        seen shouldBe listOf(
            StallNotice.Stall(StallReason.STABILITY_FROZEN, Timestamp(rig.s, 5L), rig.slotB)
        )
        val stall = seen.single() as StallNotice.Stall
        stall.slot shouldBe rig.slotB
        stall.timestamp!!.sourceId shouldBe rig.s
        stall.timestamp!!.counter shouldBe 5L
        // 9sm.5-D3: recoverable, so WAIT/DEGRADE apply and RE-SCOPE does not.
        stall.recoverable shouldBe true

        // Latched: the lag continuing says nothing further.
        rig.advanceA(10L)
        rig.advanceA(11L)
        seen.size shouldBe 1
    }

    @Test
    fun `KE3-27 a closed arrival retracts the latch with exactly one Resume and stability advances`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)
        val seen = rig.record()
        rig.freezeB()
        seen.size shouldBe 1

        // B departs cleanly. The row leaves the open set, so the MIN over the
        // survivors {L:7, A:9} rises to 7 — and the latch is retracted.
        rig.replication.stableFrontier(rig.logicalId).perSource shouldBe mapOf(rig.s to 5L)
        rig.inject(WatermarkDelta(closed = setOf(rig.slotB)))

        seen.drop(1) shouldBe listOf(StallNotice.Resume)
        rig.replication.stableFrontier(rig.logicalId).perSource shouldBe mapOf(rig.s to 7L)
    }

    @Test
    fun `9sm5-D7 the same Stall and Resume are fanned downstream over every local replica`() {
        val rig = Rig()
        val downstream = rig.downstream()
        rig.phantoms(a = 5L, b = 5L)

        val seen = rig.record()
        rig.freezeB()
        rig.inject(WatermarkDelta(closed = setOf(rig.slotB)))

        // The downstream consumer's Suspension edge sees exactly what the app
        // listener saw — an ordinary Stall it can apply the D3 disposition to.
        downstream shouldBe seen
        downstream shouldBe listOf(
            StallNotice.Stall(StallReason.STABILITY_FROZEN, Timestamp(rig.s, 5L), rig.slotB),
            StallNotice.Resume,
        )
    }

    @Test
    fun `two listeners each see exactly one Stall - the latch is per id, not per listener`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)

        val first = rig.record()
        val second = rig.record()
        rig.freezeB()

        first.size shouldBe 1
        second shouldBe first
    }

    @Test
    fun `an id with no local replica registers nothing`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)

        // Mirrors onStabilityAdvance: returns silently rather than throwing, and
        // registers nothing a later delta on the known id could fire.
        val seen = mutableListOf<StallNotice>()
        rig.replication.onStabilityStall(UUID.randomUUID()) { seen += it }
        rig.freezeB()
        seen.shouldBeEmpty()
    }

    @Test
    fun `a slot that lags but keeps advancing never trips`() {
        val rig = Rig(adds = 20)
        rig.phantoms(a = 5L, b = 5L)
        val seen = rig.record()

        // B is always behind L and A, but it moves on every evaluation: slow,
        // not frozen. The `unchanged` half of the conjunction never holds.
        for (step in 6L..12L) {
            rig.inject(
                WatermarkDelta(rows = mapOf(rig.slotA to mapOf(rig.s to step + 1), rig.slotB to mapOf(rig.s to step)))
            )
        }
        seen.shouldBeEmpty()
    }
}
