package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-4 / [KE3-12] / [KE3-15] flag half (`computenet-9sm.2.2`): echo
 * termination of [Replication.heartbeat] between two peers, and the
 * heartbeat-off control.
 *
 * **Why this must construct non-empty rows before tapping.** A row that was
 * never [civictech.cell.data.WatermarkCell.advance]d emits nothing on
 * [civictech.cell.data.WatermarkCell.republish] ([KE3-11]) — so a heartbeat
 * over two never-written companions would trivially emit zero regardless of
 * whether echo termination works, and a mutated `heartbeat()` that resolved
 * to a no-op for the wrong reason would pass unnoticed. Each peer adds one
 * element and the mesh converges first, so both companions carry a non-empty
 * own row (asserted below) before any heartbeat round fires.
 *
 * **Why this counts loopback frames, not just outlet emissions.** A
 * `heartbeat()` that silently short-circuited to a purely LOCAL re-emission
 * (never reaching the wire) would still trip a local outlet tap the same
 * number of times as a heartbeat that actually crosses the mesh — the
 * emission count alone cannot distinguish "one crossing frame per round" from
 * "swallowed before the peering". [Peering.loopback]'s per-direction
 * `FrameInterpose` seam (`kernel/src/main/kotlin/civictech/cell/wire/Peering.kt`,
 * anchor `fun interface FrameInterpose`) counts the actual A→B frames sent
 * over the wire during the heartbeat rounds; this test asserts that count is
 * non-zero and linear in the round count (25 vs 50 rounds), and reports the
 * measured frames-per-heartbeat constant rather than assuming one frame per
 * round (`unverified:` per AGENTS.md's labelling rule).
 */
class HeartbeatEchoTerminationTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController, heartbeat: Boolean = true) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry, heartbeat = heartbeat)

        fun ops(cell: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
    }

    /** Own companion row key for [peer]'s replica of [dataRef] (M10.1 replay-stable). */
    private fun ownSlot(peer: Peer, dataRef: CellRef): UUID =
        WatermarkCell.slotId(peer.replication.watermarkRef(dataRef))

    @Test
    fun `BS-4 - 50 heartbeat rounds between two converged peers emit exactly 100 deltas, cross the wire linearly, and leave every lane unchanged`() {
        forEachSeed(0L until 20L) { seed ->
            val controller = SimulationController(seed)
            val logicalId = UUID.randomUUID()

            var aToBFrames = 0
            val a = Peer(controller)
            val b = Peer(controller)
            Peering.loopback(
                a.side, b.side,
                interposeAToB = Peering.FrameInterpose { frame -> aToBFrames++; listOf(frame) },
            )

            val refA = CellRef(logicalId, 0)
            val refB = CellRef(logicalId, 1)
            val ra = SetCell<String>(refA).also { a.replication.replicate(it, a.host) }
            val rb = SetCell<String>(refB).also { b.replication.replicate(it, b.host) }
            controller.runToIdle()

            // Converge: each peer adds one element so both companions' OWN rows
            // are non-empty ([KE3-11] guard against a vacuous "never advanced").
            a.ops(ra).add("a0")
            b.ops(rb).add("b0")
            controller.runToIdle()

            val companionA = a.replication.watermarkOf(logicalId)!!
            val companionB = b.replication.watermarkOf(logicalId)!!
            assertNonEmptyOwnRow(companionA.rows()[ownSlot(a, refA)])
            assertNonEmptyOwnRow(companionB.rows()[ownSlot(b, refB)])

            // Tap AFTER convergence: the count below starts at the heartbeat
            // rounds, excluding the convergence traffic above.
            var emissions = 0
            companionA.outlet.tap(Use.fixed(Propagate<WatermarkDelta> { emissions++ }, PortRef.generate()))
            companionB.outlet.tap(Use.fixed(Propagate<WatermarkDelta> { emissions++ }, PortRef.generate()))

            val rowsBeforeA = companionA.rows()
            val rowsBeforeB = companionB.rows()
            val closedBeforeA = companionA.closed()
            val closedBeforeB = companionB.closed()
            val suspendedBeforeA = companionA.suspended()
            val suspendedBeforeB = companionB.suspended()
            val membersBeforeA = companionA.members()
            val membersBeforeB = companionB.members()

            // A→B frame count baseline right after convergence, and after 25
            // heartbeat rounds — measures the frames-per-heartbeat constant
            // rather than assuming it, and proves the crossing is linear in the
            // round count rather than a one-time catch-up artifact.
            val framesAtStart = aToBFrames
            repeat(25) {
                a.replication.heartbeat()
                b.replication.heartbeat()
                controller.runToIdle()
            }
            val frames25 = aToBFrames - framesAtStart
            repeat(25) {
                a.replication.heartbeat()
                b.replication.heartbeat()
                controller.runToIdle()
            }
            val frames50 = aToBFrames - framesAtStart

            // [KE3-12]: exactly one WatermarkDelta emission per republish call
            // across both companions, zero re-emissions from the echo — an
            // echo would make this superlinear (> 100).
            emissions shouldBe 100

            // The heartbeats actually crossed the wire rather than being
            // swallowed locally, and did so linearly in the round count.
            (frames25 > 0) shouldBe true
            frames50 shouldBe 2 * frames25
            // Measured constant, reported rather than assumed (unverified:
            // exactly how many A→B frames one republish() call produces —
            // this asserts the value this run measured, one republish per
            // A-side heartbeat round of a two-peer full mesh: one frame).
            frames25 shouldBe 25

            // Every lane of every companion is byte-identical before and after.
            companionA.rows() shouldBe rowsBeforeA
            companionB.rows() shouldBe rowsBeforeB
            companionA.closed() shouldBe closedBeforeA
            companionB.closed() shouldBe closedBeforeB
            companionA.suspended() shouldBe suspendedBeforeA
            companionB.suspended() shouldBe suspendedBeforeB
            companionA.members() shouldBe membersBeforeA
            companionB.members() shouldBe membersBeforeB
        }
    }

    @Test
    fun `KE3-15 flag half - heartbeat disabled by configuration emits nothing over 50 rounds`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val h = Peer(controller, heartbeat = false)
        val ref = CellRef(logicalId, 0)
        val cell = SetCell<String>(ref).also { h.replication.replicate(it, h.host) }
        controller.runToIdle()

        // Non-empty own row first, so the zero result below is attributable to
        // the flag and not to [KE3-11]'s "never advanced" guard.
        h.ops(cell).add("h0")
        controller.runToIdle()
        val companion = h.replication.watermarkOf(logicalId)!!
        assertNonEmptyOwnRow(companion.rows()[ownSlot(h, ref)])

        var emissions = 0
        companion.outlet.tap(Use.fixed(Propagate<WatermarkDelta> { emissions++ }, PortRef.generate()))

        repeat(50) {
            h.replication.heartbeat()
            controller.runToIdle()
        }

        emissions shouldBe 0
    }

    private fun assertNonEmptyOwnRow(row: Map<UUID, Long>?) {
        require(!row.isNullOrEmpty()) { "expected a non-null, non-empty own row but got $row" }
    }
}
