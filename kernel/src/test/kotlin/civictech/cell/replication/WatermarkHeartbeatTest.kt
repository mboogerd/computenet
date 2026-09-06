package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-2 / BS-3 / BS-3′ ([KE3-13], [KE3-15] read half) for feature
 * `computenet-9sm.2` — the *liveness* half of the heartbeat, measured on
 * [Replication.stableFrontier] over a real three-peer gossip mesh.
 *
 * ## What is actually under test, and why the loss is constructed explicitly
 *
 * The feature's premise as originally written — "an idle replica never
 * republishes, so a peer cannot distinguish idle from lagging" — is FALSE in
 * a lossless mesh, and this file would be vacuous if it were written to it.
 * [WatermarkCell.advance] emits the raised **absolute** value, `applyRemote`
 * re-emits every raised entry, and `outlet.catchUpOnLinked` ships full state
 * on every (re)link (including [Peering.Loopback.heal]). So every peer's view
 * of an idle member's row already equals that row, and `stableFrontier` — a
 * pointwise MIN over open member rows — reads identically with or without a
 * heartbeat. A BS-2 written on a lossless mesh passes with
 * [WatermarkCell.republish] deleted.
 *
 * The heartbeat's ONE observable effect is to **repair a peer's stale view of
 * an idle member's row after that member's last row emission was lost** — the
 * "missed the last gossip" case. Every scenario here therefore constructs
 * that loss explicitly, through [Peering.loopback]'s per-direction
 * [Peering.FrameInterpose] seam (`emptyList()` destroys the frame; nothing
 * replays it):
 *
 *  - **BS-2** ([KE3-13]) — C's last row emission `s→t` is destroyed on both
 *    of C's outbound edges while C itself delivers through `t`; C's inbound is
 *    then cut so C goes idle. A's and B's `stableFrontier[s]` reads the stale
 *    `k` until C's first heartbeat tick, `t` at every observation after it,
 *    and `t+6` once C's inbound is restored and C is re-linked (measured:
 *    lifting the drop and driving traffic alone does not catch C up).
 *  - **BS-3** ([KE3-15]) — the same run with C's `Replication(registry,
 *    heartbeat = false)`. Every observation is the stale `k`, strictly below
 *    C's true row `t`. This control PASSES by asserting the stall.
 *  - **BS-3′** — the same run with the heartbeat off and **no loss at all**.
 *    Every observation is `t`. This is the measurement that the LOSS, not the
 *    absent heartbeat, is what freezes the read; it is what keeps BS-2 honest,
 *    since BS-2 and BS-3 differ only in the flag and BS-3/BS-3′ differ only in
 *    the loss.
 *
 * ## Non-vacuousness
 *
 * This is a test-only item, so a production mutation is out of its scope; the
 * discriminators are the two controls above plus the per-edge frame counters,
 * which prove the loss was constructed rather than assumed (≥ 1 C-outbound
 * frame destroyed in phase 2, ≥ 1 C→A frame delivered in phase 3 — the
 * heartbeat crossing).
 *
 * ## Cadence
 *
 * No clock, anywhere: heartbeats are [Replication.heartbeat] calls made
 * between [SimulationController.runToIdle] drains, and no assertion mentions a
 * step count. All three scenarios run under `forEachSeed(0L until 30L)`; the
 * switches are flipped between drains, so a seed varies host interleaving
 * only.
 */
class WatermarkHeartbeatTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** `k`: what A and B last saw of C's row. `t`: what C's row actually is. */
    private companion object {
        const val K = 4
        const val T = 8
        const val PHASE3_ROUNDS = 12
        const val PHASE3_WRITES = 5
    }

    private class Peer(controller: SimulationController, heartbeat: Boolean = true) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry, heartbeat = heartbeat)
    }

    /**
     * One direction of one loopback, with a test-owned switch and the two
     * counters the acceptance clause is asserted on. The switch is read per
     * frame, so flipping it between drains changes the edge for every frame
     * after that point and none before it.
     */
    private class Edge(val name: String, private val dropping: () -> Boolean) {
        var dropped = 0
            private set
        var delivered = 0
            private set

        val interpose = Peering.FrameInterpose { frame ->
            if (dropping()) {
                dropped++
                emptyList()
            } else {
                delivered++
                listOf(frame)
            }
        }

        override fun toString() = "$name(dropped=$dropped, delivered=$delivered)"
    }

    /**
     * The `StableFrontierMeshTest.Mesh` shape — three [SetCell] replicas of one
     * logical id over a full loopback triangle — with the two C-facing links
     * carrying interposers. The A–B link always passes.
     */
    private class Mesh(val controller: SimulationController, val logicalId: UUID, heartbeatOnC: Boolean) {
        /** Destroys C→A and C→B (C's row emissions never reach A or B). */
        var dropFromC = false

        /** Destroys A→C and B→C (C receives nothing: it is idle behind a cut inbound). */
        var dropToC = false

        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller, heartbeat = heartbeatOnC)

        val aToC = Edge("A->C") { dropToC }
        val cToA = Edge("C->A") { dropFromC }
        val bToC = Edge("B->C") { dropToC }
        val cToB = Edge("C->B") { dropFromC }

        lateinit var linkAC: Peering.Loopback
        lateinit var linkBC: Peering.Loopback

        val ra: SetCell<String>
        val rb: SetCell<String>
        val rc: SetCell<String>

        init {
            Peering.loopback(a.side, b.side)
            linkAC = Peering.loopback(a.side, c.side, interposeAToB = aToC.interpose, interposeBToA = cToA.interpose)
            linkBC = Peering.loopback(b.side, c.side, interposeAToB = bToC.interpose, interposeBToA = cToB.interpose)
            ra = SetCell<String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
            rb = SetCell<String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
            rc = SetCell<String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }
            controller.runToIdle()
        }

        val peers get() = listOf(a, b, c)

        val opA: SetOps<String>
            get() = (HostedCellProxy.create(ra.ref, a.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        fun companionOf(peer: Peer): WatermarkCell = peer.replication.watermarkOf(logicalId)!!

        val slotA: UUID get() = WatermarkCell.slotId(a.replication.watermarkRef(ra.ref))
        val slotC: UUID get() = WatermarkCell.slotId(a.replication.watermarkRef(rc.ref))

        /** A's per-origin tag source: A's own row minus A's own outlet-epoch column. */
        fun sourceOfA(): UUID {
            @Suppress("UNCHECKED_CAST")
            val epochA = (ra.outlet as FanOutlet<Propagate<civictech.cell.data.delta.SetDelta<String>>>).waveState().sourceId
            return (companionOf(a).rows().getValue(slotA).keys - epochA).single()
        }

        /** Every peer's *view* of C's row, for source [s]. */
        fun viewsOfC(s: UUID): List<Long?> = peers.map { companionOf(it).rows()[slotC]?.get(s) }

        fun frontierOn(peer: Peer, s: UUID): Long? = peer.replication.stableFrontier(logicalId).perSource[s]

        /** Tick the cadence. C's is the one under test; A's and B's are harmless. */
        fun heartbeatAll() = peers.forEach { it.replication.heartbeat() }
    }

    /**
     * Phases 1 and 2, shared by all three scenarios. Returns A's tag source.
     * [constructLoss] false is BS-3′: identical phases, no frame destroyed.
     */
    private fun runPhases1And2(mesh: Mesh, constructLoss: Boolean): UUID {
        // Phase 1: A adds K; the mesh converges. Every peer's view of C is K.
        repeat(K) { i -> mesh.opA.add("a$i") }
        mesh.controller.runToIdle()
        val s = mesh.sourceOfA()
        mesh.viewsOfC(s) shouldBe listOf(K.toLong(), K.toLong(), K.toLong())

        // Phase 2: destroy every frame C sends while A drives C's row to T.
        mesh.dropFromC = constructLoss
        repeat(T - K) { i -> mesh.opA.add("b$i") }
        mesh.controller.runToIdle()

        if (constructLoss) {
            // C delivered through T (its own row is T) but A and B still read K.
            mesh.companionOf(mesh.c).rows().getValue(mesh.slotC).getValue(s) shouldBe T.toLong()
            mesh.viewsOfC(s) shouldBe listOf(K.toLong(), K.toLong(), T.toLong())
            // The loss was constructed, not assumed.
            (mesh.cToA.dropped >= 1).shouldBeTrue()
            (mesh.cToB.dropped >= 1).shouldBeTrue()
        } else {
            // BS-3′: no loss — every peer's view of C is C's true row.
            mesh.viewsOfC(s) shouldBe listOf(T.toLong(), T.toLong(), T.toLong())
            mesh.cToA.dropped shouldBe 0
            mesh.cToB.dropped shouldBe 0
        }
        return s
    }

    /**
     * Phase 3: lift C's outbound drop, cut C's inbound (C is now idle), and run
     * [PHASE3_ROUNDS] rounds — A writes in the first [PHASE3_WRITES] of them,
     * and every second round ticks the cadence before the drain. Returns the
     * observed `stableFrontier[s]` sequence on A and on B, one entry per round.
     */
    private fun runPhase3(mesh: Mesh, s: UUID): Pair<List<Long?>, List<Long?>> {
        mesh.dropFromC = false
        mesh.dropToC = true
        val onA = mutableListOf<Long?>()
        val onB = mutableListOf<Long?>()
        repeat(PHASE3_ROUNDS) { round ->
            if (round < PHASE3_WRITES) mesh.opA.add("c$round")
            if (round % 2 == 1) mesh.heartbeatAll()
            mesh.controller.runToIdle()
            onA += mesh.frontierOn(mesh.a, s)
            onB += mesh.frontierOn(mesh.b, s)
        }
        return onA to onB
    }

    private fun List<Long?>.isNonDecreasing(): Boolean =
        zipWithNext().all { (x, y) -> (x ?: Long.MIN_VALUE) <= (y ?: Long.MIN_VALUE) }

    @Test
    fun `KE3-13 BS-2 a lost row emission freezes stableFrontier at the stale view until the idle member heartbeats`() {
        forEachSeed(0L until 30L) { seed ->
            val controller = SimulationController(seed)
            val mesh = Mesh(controller, UUID.randomUUID(), heartbeatOnC = true)
            val s = runPhases1And2(mesh, constructLoss = true)

            // Frozen on the stale view before phase 3 starts.
            mesh.frontierOn(mesh.a, s) shouldBe K.toLong()
            mesh.frontierOn(mesh.b, s) shouldBe K.toLong()

            val deliveredBefore = mesh.cToA.delivered
            val (onA, onB) = runPhase3(mesh, s)

            // Round 0 is before the first tick: still the stale view. Every
            // observation from the first drain after the first tick (round 1)
            // is C's true row T — "advances to t and does not stall", and
            // never anything else.
            val expected = listOf(K.toLong()) + List(PHASE3_ROUNDS - 1) { T.toLong() }
            onA shouldBe expected
            onB shouldBe expected
            // The heartbeat crossed the (now open) C->A edge.
            (mesh.cToA.delivered > deliveredBefore).shouldBeTrue()
            // A's and B's VIEW of C's row — not merely the MIN — is repaired.
            mesh.viewsOfC(s) shouldBe listOf(T.toLong(), T.toLong(), T.toLong())

            // Phase 4: restore C's inbound and drive one more write. A wrote
            // K + (T-K) + PHASE3_WRITES = T+5 before this, so its row is T+6.
            mesh.dropToC = false
            mesh.opA.add("d0")
            mesh.controller.runToIdle()
            mesh.companionOf(mesh.a).rows().getValue(mesh.slotA).getValue(s) shouldBe (T + PHASE3_WRITES + 1).toLong()

            // MEASURED, and worth pinning: lifting the inbound drop and driving
            // traffic is NOT by itself enough to catch C up. The A->C edge
            // delivers the new frame (its `delivered` counter rises), but C's
            // own row stays at T — the deltas destroyed while its inbound was
            // cut are gone, and nothing on the data path replays them. So a
            // frame-loss residue on the DATA plane is repaired by a re-link,
            // not by traffic; the heartbeat repairs the WATERMARK plane only.
            // (Which mechanism holds C's set back — inlet frontier alignment
            // over the missing tag prefix is the obvious candidate — is
            // `unverified:` here; only the outcome is measured.)
            val deliveredAfterWrite = mesh.aToC.delivered
            (deliveredAfterWrite > 0).shouldBeTrue()
            mesh.companionOf(mesh.c).rows().getValue(mesh.slotC).getValue(s) shouldBe T.toLong()

            // The re-link is what catches C up: `catchUpOnLinked` ships full
            // state in both directions, so C reaches A's row and its own row
            // emission reaches A and B. This is the "heal WITH a write" arm of
            // the bead's choice, hence T+6 rather than T+5.
            mesh.linkAC.heal()
            mesh.linkBC.heal()
            mesh.controller.runToIdle()
            val settled = (T + PHASE3_WRITES + 1).toLong()
            mesh.frontierOn(mesh.a, s) shouldBe settled
            mesh.frontierOn(mesh.b, s) shouldBe settled

            (onA + settled).isNonDecreasing().shouldBeTrue()
            (onB + settled).isNonDecreasing().shouldBeTrue()
        }
    }

    @Test
    fun `KE3-15 BS-3 with the heartbeat disabled the same lost row emission leaves the read frozen for the whole run`() {
        forEachSeed(0L until 30L) { seed ->
            val controller = SimulationController(seed)
            val mesh = Mesh(controller, UUID.randomUUID(), heartbeatOnC = false)
            val s = runPhases1And2(mesh, constructLoss = true)

            val (onA, onB) = runPhase3(mesh, s)

            // The control PASSES by asserting the stall: every observation is
            // the stale view K, strictly below C's true last delivery T.
            onA shouldBe List(PHASE3_ROUNDS) { K.toLong() }
            onB shouldBe List(PHASE3_ROUNDS) { K.toLong() }
            (K.toLong() < T.toLong()).shouldBeTrue()
            // C's own row really is T throughout — the freeze is A's and B's
            // stale VIEW of it, not C having failed to deliver.
            mesh.companionOf(mesh.c).rows().getValue(mesh.slotC).getValue(s) shouldBe T.toLong()
        }
    }

    @Test
    fun `BS-3 prime with no loss and no heartbeat an idle member behind a cut inbound still pins the read at its true row`() {
        forEachSeed(0L until 30L) { seed ->
            val controller = SimulationController(seed)
            val mesh = Mesh(controller, UUID.randomUUID(), heartbeatOnC = false)
            val s = runPhases1And2(mesh, constructLoss = false)

            // After phase 2, with nothing lost, the read is already C's true row.
            mesh.frontierOn(mesh.a, s) shouldBe T.toLong()
            mesh.frontierOn(mesh.b, s) shouldBe T.toLong()

            val (onA, onB) = runPhase3(mesh, s)

            // And it stays there for the whole run, with the heartbeat off: it
            // is the LOSS, not the silence, that freezes the read below the
            // member's own row.
            onA shouldBe List(PHASE3_ROUNDS) { T.toLong() }
            onB shouldBe List(PHASE3_ROUNDS) { T.toLong() }
            // Nothing was ever destroyed on C's outbound edges.
            mesh.cToA.dropped shouldBe 0
            mesh.cToB.dropped shouldBe 0
        }
    }
}
