package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * E3.5 (`computenet-9sm.3.2`): [Replication]'s three causal-stability
 * additions — the [Replication.stableFrontier] facade over
 * [civictech.cell.consistency.CausalStability], the
 * [Replication.onStabilityAdvance] rise notification ([KE3-21], decision
 * 9sm.3-D2), and the `internal` [Replication.localDeliveredFrontier] control
 * seam ([KE3-20], decision 9sm.3-D5).
 *
 * `localDeliveredFrontier` being **`internal`** is a compile-time fact, not an
 * assertion: this test compiles only because it lives in `:kernel`'s own test
 * source set. A consumer outside the module cannot name it, which is the whole
 * point of the seam — it is the *wrong* trigger `computenet-9sm.4`'s BS-13
 * control switches to, and nothing in production may read it.
 *
 * **The rig** is one real peer L (a replicated [SetCell]) plus two PHANTOM
 * members A and B injected straight into L's companion through
 * `deltaInlet` — so every row in the lattice is under deterministic control,
 * with no second [ManagedHost] and no gossip mesh to schedule. Phantom slots
 * are derived exactly as [Replication.watermarkRef] would derive them for
 * replicas at instance ids 1 and 2, and are added to the companion's announced
 * `members` set so they count as OPEN slots ([KE3-24], the FU-2 union).
 *
 * **Two columns, one row.** L's own companion row carries the per-origin tag
 * source (`s` below — what the stability read is about) *and* the CP-B2
 * per-outlet-epoch source. The epoch column is L-only: no phantom row ever has
 * it, so it reads as bottom for the open set and must be ABSENT from every
 * [Replication.stableFrontier] result here. Each assertion therefore compares
 * `perSource` for exact equality rather than containment — a containment check
 * would pass while the epoch column leaked into a global frontier.
 */
class StabilityAdvanceTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /**
     * One peer, one replicated [SetCell], two phantom member slots.
     *
     * [adds] local `add`s are performed before the rig is handed over, so L's
     * per-origin column sits at a known value ([sourceValue]).
     */
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
        val slotC: UUID

        /** L's per-origin tag source — the source every assertion below is about. */
        val s: UUID

        /** The CP-B2 per-outlet-epoch source: L-only, and therefore never stable. */
        val epochSource: UUID

        /** L's own delivered value for [s] after the constructor's `add`s. */
        val sourceValue: Long

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
            slotC = WatermarkCell.slotId(replication.watermarkRef(CellRef(logicalId, 3)))

            @Suppress("UNCHECKED_CAST")
            epochSource = (cell.outlet as FanOutlet<Propagate<SetDelta<String>>>).waveState().sourceId
            val row = companion.rows().getValue(slotL)
            s = (row.keys - epochSource).single()
            sourceValue = row.getValue(s)
        }

        /** Feed the companion a peer delta, then drain the scheduler. */
        fun inject(delta: WatermarkDelta) {
            companion.deltaInlet.call.propagate(delta)
            controller.runToIdle()
        }

        /** `A:{s→a}, B:{s→b}`, with both slots announced as open members. */
        fun phantoms(a: Long, b: Long) = inject(
            WatermarkDelta(
                rows = mapOf(slotA to mapOf(s to a), slotB to mapOf(s to b)),
                members = setOf(slotA, slotB),
            )
        )

        fun stable(): Map<UUID, Long> = replication.stableFrontier(logicalId).perSource

        /** Every [TagFrontier] a listener registered now is handed. */
        fun record(): List<TagFrontier> = mutableListOf<TagFrontier>().also { calls ->
            replication.onStabilityAdvance(logicalId) { calls += it }
        }
    }

    @Test
    fun `the per-origin column sits where the rig claims and the epoch column is L-only`() {
        val rig = Rig()

        // The probe the breakdown flagged `unverified:` — 7 adds on a SetCell put
        // the per-origin delivered prefix at exactly 7 (unit-counter tags, and
        // DeliveredFrontier is a contiguous prefix from 0).
        rig.sourceValue shouldBe 7L
        rig.companion.rows().getValue(rig.slotL).keys shouldBe setOf(rig.s, rig.epochSource)
        rig.s shouldNotBe rig.epochSource
    }

    @Test
    fun `KE3-21 a listener fires exactly once per effective rise and never on a capped or echoed delta`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)

        // Baseline: MIN over {L:7, A:5, B:5} is 5; the epoch column is absent
        // because no phantom row carries it (bottom).
        rig.stable() shouldBe mapOf(rig.s to 5L)

        // Registration takes the baseline and never fires for it.
        val calls = rig.record()
        val other = rig.record()
        calls.shouldBeEmpty()

        // A 5→7 while B still caps the MIN at 5: a real lattice advance, no rise.
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotA to mapOf(rig.s to 7L))))
        rig.stable() shouldBe mapOf(rig.s to 5L)
        calls.shouldBeEmpty()

        // B 5→7 lifts the MIN to 7 — one call, carrying the NEW frontier.
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotB to mapOf(rig.s to 7L))))
        calls.map { it.perSource } shouldBe listOf(mapOf(rig.s to 7L))

        // Redelivery/echo of the same row: pointwise-max absorbs it, nothing rose.
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotB to mapOf(rig.s to 7L))))
        calls.map { it.perSource } shouldBe listOf(mapOf(rig.s to 7L))

        // A 7→9 with L's own row still at 7: the MIN cannot move past L.
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotA to mapOf(rig.s to 9L))))
        rig.stable() shouldBe mapOf(rig.s to 7L)
        calls.map { it.perSource } shouldBe listOf(mapOf(rig.s to 7L))

        // "Exactly once" is PER LISTENER: the second listener saw its own single call.
        other.map { it.perSource } shouldBe listOf(mapOf(rig.s to 7L))
    }

    @Test
    fun `KE3-21 a closed arrival that lifts the MIN fires exactly once`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 7L)
        rig.stable() shouldBe mapOf(rig.s to 5L)

        val calls = rig.record()

        // A departs cleanly. Its row leaves the open set, so the MIN over the
        // survivors {B:7, L:7} is 7 — a rise driven by `closed`, not by `rows`.
        // B stays open deliberately: closing BOTH phantoms would leave L alone,
        // and L's row also carries the epoch column, so the frontier would then
        // legitimately include it — which the exact-equality discipline of this
        // file is meant to keep out of every result.
        rig.inject(WatermarkDelta(closed = setOf(rig.slotA)))

        calls.map { it.perSource } shouldBe listOf(mapOf(rig.s to 7L))
        rig.stable() shouldBe mapOf(rig.s to 7L)
    }

    @Test
    fun `KE3-21 membership growth drops the frontier without firing and does not lower the baseline`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)
        rig.stable() shouldBe mapOf(rig.s to 5L)

        val calls = rig.record()

        // A rowless member marker: C is open, has no row, so `s` reads as bottom
        // and the frontier empties. A drop-out is not a rise.
        rig.inject(WatermarkDelta(members = setOf(rig.slotC)))
        rig.stable() shouldBe emptyMap()
        calls.shouldBeEmpty()

        // C gossips a row that only restores the PREVIOUS level. If the drop-out had
        // lowered the recorded baseline to the empty frontier, coming back to
        // {s→5} would read as a rise and fire — it must not.
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotC to mapOf(rig.s to 5L))))
        rig.stable() shouldBe mapOf(rig.s to 5L)
        calls.shouldBeEmpty()

        // Now every open slot reaches 7. The baseline was NOT lowered in between, so
        // this is one rise over {s→5} — a single call, not one for re-entering
        // {s→5} and another for reaching {s→7}.
        rig.inject(
            WatermarkDelta(
                rows = mapOf(
                    rig.slotA to mapOf(rig.s to 7L),
                    rig.slotB to mapOf(rig.s to 7L),
                    rig.slotC to mapOf(rig.s to 7L),
                )
            )
        )
        calls.map { it.perSource } shouldBe listOf(mapOf(rig.s to 7L))
    }

    @Test
    fun `KE3-21 a source becoming present at all is a rise - absent reads as bottom`() {
        val rig = Rig()

        // C is announced with no row, so every source reads as bottom for the open
        // set {L, C} and the frontier is empty — the baseline this listener takes.
        rig.inject(WatermarkDelta(members = setOf(rig.slotC)))
        rig.stable() shouldBe emptyMap()

        val calls = rig.record()

        // C gossips its first row. `s` goes from ABSENT (bottom) to present at 3.
        // Nothing was strictly greater than a previous value — there was no previous
        // value — so only the "present now, absent before" half of the rise test can
        // catch this, and it must fire exactly once.
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotC to mapOf(rig.s to 3L))))
        rig.stable() shouldBe mapOf(rig.s to 3L)
        calls.map { it.perSource } shouldBe listOf(mapOf(rig.s to 3L))
    }

    @Test
    fun `KE3-22 a stability read is inert - no emission, no tag, no lattice movement`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)

        val emissions = AtomicInteger()
        rig.companion.outlet.tap(
            Use.fixed(Propagate<WatermarkDelta> { emissions.incrementAndGet() }, PortRef.generate())
        )
        val before = rig.companion.snapshot()
        val rows = rig.companion.rows()
        val closed = rig.companion.closed()
        val suspended = rig.companion.suspended()
        val members = rig.companion.members()

        repeat(100) { rig.replication.stableFrontier(rig.logicalId) shouldBe TagFrontier(mapOf(rig.s to 5L)) }
        rig.replication.localDeliveredFrontier(rig.logicalId)

        emissions.get() shouldBe 0
        rig.companion.snapshot() shouldBe before
        rig.companion.rows() shouldBe rows
        rig.companion.closed() shouldBe closed
        rig.companion.suspended() shouldBe suspended
        rig.companion.members() shouldBe members

        // Nothing was queued either: draining the scheduler produces no delta.
        rig.controller.runToIdle()
        emissions.get() shouldBe 0
        rig.companion.snapshot() shouldBe before
    }

    @Test
    fun `KE3-20 localDeliveredFrontier is this peer's own row and differs from the stable frontier`() {
        val rig = Rig()
        rig.phantoms(a = 5L, b = 5L)

        val local = rig.replication.localDeliveredFrontier(rig.logicalId)

        // The local row verbatim — BOTH columns, including the L-only epoch source
        // the stability read correctly withholds.
        local.perSource shouldBe rig.companion.rows().getValue(rig.slotL)
        local.perSource[rig.s] shouldBe 7L
        local.perSource.keys shouldBe setOf(rig.s, rig.epochSource)

        // The seam BS-13 needs: the wrong trigger is strictly ahead of the right one.
        local shouldNotBe rig.replication.stableFrontier(rig.logicalId)
        rig.stable() shouldBe mapOf(rig.s to 5L)
    }

    @Test
    fun `an id with no local replica has an empty frontier and a no-op registration`() {
        val rig = Rig()
        val unknown = UUID.randomUUID()

        rig.replication.stableFrontier(unknown) shouldBe TagFrontier(emptyMap())
        rig.replication.localDeliveredFrontier(unknown) shouldBe TagFrontier(emptyMap())

        // Mirrors onWatermarkAdvance: returns silently rather than throwing, and
        // registers nothing that a later delta on the known id could fire.
        val calls = mutableListOf<TagFrontier>()
        rig.replication.onStabilityAdvance(unknown) { calls += it }
        rig.phantoms(a = 5L, b = 5L)
        rig.inject(WatermarkDelta(rows = mapOf(rig.slotA to mapOf(rig.s to 9L), rig.slotB to mapOf(rig.s to 9L))))
        calls.shouldBeEmpty()
    }
}
