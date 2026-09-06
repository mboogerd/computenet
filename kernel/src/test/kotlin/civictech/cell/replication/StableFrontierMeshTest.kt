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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * E3.5 (`computenet-9sm.3.3`): [Replication.stableFrontier] over a REAL
 * three-peer gossip mesh (three [SetCell] replicas, full triangle of
 * [Peering.loopback] links, one shared [SimulationController]) rather than
 * the single-peer-plus-hand-crafted-phantoms rig `StabilityAdvanceTest` uses
 * — this file exercises the same causal-stability read
 * ([civictech.cell.consistency.CausalStability]) through actual gossip
 * delivery instead of direct companion injection for the converging half of
 * each scenario.
 *
 * **BS-6 / [KE3-18]**: replaying an arbitrary, already-delivered prefix of
 * captured [WatermarkDelta]s — including cross-peer, i.e. peer X's captured
 * deltas fed into peer Y's companion, exactly what a gossip redelivery/echo
 * looks like — leaves every peer's [Replication.stableFrontier] unchanged.
 *
 * **BS-7 / [KE3-19] / [KE3-24]**: a fourth slot D, known to a peer only
 * through the companion's gossiped `members` set (never through
 * [civictech.cell.host.InstanceIndex.instancesOf] — D is never [replicate]d
 * anywhere), drags every source to bottom the moment it is announced; its
 * partial row lifts only the sources it names back to their pre-marker
 * value, `d`'s still-missing columns stay absent, and a delta that carries
 * the marker and a complete row *together* never dips at all.
 *
 * **Substitution against the prescribed state (KE3-19 clause)**: the
 * breakdown's BS-7 scenario has only A write (9 adds), which leaves a single
 * real per-origin tag source `s` in the converged frontier — the CP-B2
 * per-outlet-epoch column each peer's own row also carries never joins the
 * MIN (it is present in exactly one of the three open rows, so it is already
 * bottom/absent before D exists at all). Against that state, the acceptance
 * clause "sources the new row still lacks SHALL remain absent" is checkable
 * only vacuously — there is no second source for D's partial row to be
 * missing. This file adds one write on B (`sB`) so the converged frontier
 * carries two real sources; the property (BS-7 / [KE3-19]) is unchanged, the
 * state is enlarged so the "still lacks a column, still absent" half of the
 * property is exercised for real. The `perSource[s] == 9` half of the
 * clause is unaffected and is checked exactly as prescribed.
 */
class StableFrontierMeshTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** Full triangle mesh of three [SetCell] replicas of [logicalId], converged. */
    private class Mesh(controller: SimulationController, val logicalId: UUID) {
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)

        val ra: SetCell<String>
        val rb: SetCell<String>
        val rc: SetCell<String>

        init {
            Peering.loopback(a.side, b.side)
            Peering.loopback(b.side, c.side)
            Peering.loopback(a.side, c.side)
            ra = SetCell<String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
            rb = SetCell<String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
            rc = SetCell<String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }
            controller.runToIdle()
        }

        val peers get() = listOf(a, b, c)

        fun ops(peer: Peer, cell: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(cell.ref, peer.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        fun frontiers(): List<civictech.cell.TagFrontier> = peers.map { it.replication.stableFrontier(logicalId) }
    }

    @Test
    fun `KE3-18 replaying an arbitrary seeded prefix of already-delivered WatermarkDeltas leaves every peer's stableFrontier unchanged`() {
        forEachSeed(0L until 30L) { seed ->
            val controller = SimulationController(seed)
            val rnd = Random(seed)
            val logicalId = UUID.randomUUID()
            val mesh = Mesh(controller, logicalId)
            val opA = mesh.ops(mesh.a, mesh.ra)
            val opB = mesh.ops(mesh.b, mesh.rb)
            val opC = mesh.ops(mesh.c, mesh.rc)

            // Tap EVERY peer's companion outlet and CAPTURE every emitted
            // WatermarkDelta (the onWatermarkAdvance tap shape, capturing
            // instead of discarding) — before any write, so nothing is missed.
            val captured: List<MutableList<WatermarkDelta>> = mesh.peers.map { peer ->
                val companion = peer.replication.watermarkOf(logicalId)!!
                mutableListOf<WatermarkDelta>().also { list ->
                    companion.outlet.tap(Use.fixed(Propagate<WatermarkDelta> { list += it }, PortRef.generate()))
                }
            }

            // Workload: 6 rounds, one add per peer per round, converging each round.
            repeat(6) { round ->
                opA.add("a-$round"); opB.add("b-$round"); opC.add("c-$round")
                controller.runToIdle()
            }

            // Non-vacuity: the taps actually captured something on every peer.
            captured.forEach { it.shouldNotBeEmpty() }

            val before = mesh.frontiers()
            // Non-vacuity: the mesh is converged and every peer's frontier is
            // non-empty before the replay — a vacuous replay of nothing over an
            // empty frontier would prove nothing.
            before.forEach { it.perSource.shouldNotBeEmpty() }

            // Replay an arbitrary (seeded, non-empty) prefix of EVERY peer's
            // captured deltas into EVERY peer's companion inlet — cross-peer
            // redelivery too, e.g. A's captured deltas fed into B's companion,
            // which is exactly what gossip redelivery/echo looks like.
            var replayed = 0
            captured.forEach { deltas ->
                val prefixLen = 1 + rnd.nextInt(deltas.size) // always >= 1: non-empty prefix
                val prefix = deltas.take(prefixLen)
                replayed += prefix.size
                mesh.peers.forEach { target ->
                    val companion = target.replication.watermarkOf(logicalId)!!
                    prefix.forEach { companion.deltaInlet.call.propagate(it) }
                }
            }
            controller.runToIdle()

            // Non-vacuity: a zero-length prefix would prove nothing.
            (replayed > 0).shouldBeTrue()

            val after = mesh.frontiers()
            after shouldBe before
        }
    }

    @Test
    fun `KE3-19 KE3-24 an announced rowless member reads bottom until its row arrives, and a partial row only lifts what it names`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)
        val opB = mesh.ops(mesh.b, mesh.rb)

        val companionA = mesh.a.replication.watermarkOf(logicalId)!!
        val slotA = WatermarkCell.slotId(mesh.a.replication.watermarkRef(mesh.ra.ref))
        val slotB = WatermarkCell.slotId(mesh.a.replication.watermarkRef(mesh.rb.ref))
        @Suppress("UNCHECKED_CAST")
        val epochA = (mesh.ra.outlet as civictech.cell.port.FanOutlet<Propagate<civictech.cell.data.delta.SetDelta<String>>>).waveState().sourceId
        @Suppress("UNCHECKED_CAST")
        val epochB = (mesh.rb.outlet as civictech.cell.port.FanOutlet<Propagate<civictech.cell.data.delta.SetDelta<String>>>).waveState().sourceId

        // 9 adds on A — the prescribed reproduction for `s`, A's per-origin tag
        // source. Captured now, BEFORE B writes anything, while A's own row
        // still carries only its own tag plus its own epoch column (the same
        // "row.keys minus epoch" trick `StabilityAdvanceTest`'s single-writer
        // rig uses) — B's relay would otherwise pollute this row with `sB` too.
        repeat(9) { i -> opA.add("a$i"); controller.runToIdle() }
        val s = (companionA.rows().getValue(slotA).keys - epochA).single()

        // One add on B: see the class KDoc "Substitution" note — this gives the
        // converged frontier a second real source `sB` so the "D's row is still
        // incomplete for the source it doesn't name" half of KE3-19 is checkable
        // for real rather than vacuously. `sB` is derived from B's OWN row minus
        // the two columns already known by identity (the relayed `s`, and B's
        // own directly-read epoch column) rather than by count, since B's row
        // now also carries the relayed `s`.
        opB.add("b0")
        controller.runToIdle()
        val sB = (companionA.rows().getValue(slotB).keys - setOf(s, epochB)).single()

        val before = mesh.frontiers()
        // Non-vacuity / probe: the frontier is non-empty and converged with `s`
        // at exactly 9 on every peer before the phantom marker exists at all.
        before.forEach { it.perSource shouldBe mapOf(s to 9L, sB to before[0].perSource.getValue(sB)) }

        // A fourth slot D, announced ONLY through the companion's gossiped
        // `members` set — never through replicate()/instancesOf ([KE3-24]).
        val slotD = WatermarkCell.slotId(mesh.a.replication.watermarkRef(CellRef(logicalId, 3)))
        companionA.deltaInlet.call.propagate(WatermarkDelta(members = setOf(slotD)))
        controller.runToIdle()

        // D is an open slot with no row: EVERY source reads as bottom, not just
        // `s` — on A, and (the shared controller drains the whole mesh in one
        // runToIdle) on B and C once the marker gossips too.
        mesh.frontiers().forEach { it.perSource shouldBe emptyMap() }

        // D's row arrives, but PARTIAL — only `s`, not `sB`.
        companionA.deltaInlet.call.propagate(WatermarkDelta(rows = mapOf(slotD to mapOf(s to 9L))))
        controller.runToIdle()

        // `s` is restored to its pre-marker value everywhere; `sB` — the source
        // D's row still lacks — remains absent (bottom is per-source, not per
        // member): the frontier's key set is EXACTLY {s}, not {s, sB}.
        mesh.frontiers().forEach { it.perSource shouldBe mapOf(s to 9L) }

        // D's row finally covers every source `before` had — the frontier
        // returns to exactly the pre-marker value: monotone through the join,
        // bottom -> restored, never above.
        companionA.deltaInlet.call.propagate(
            WatermarkDelta(rows = mapOf(slotD to mapOf(s to 9L, sB to before[0].perSource.getValue(sB))))
        )
        controller.runToIdle()

        mesh.frontiers() shouldBe before
    }

    @Test
    fun `a single delta carrying a slot's marker and its full row together never dips`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val opA = mesh.ops(mesh.a, mesh.ra)

        repeat(9) { i -> opA.add("a$i"); controller.runToIdle() }

        val companionA = mesh.a.replication.watermarkOf(logicalId)!!
        val rowA = companionA.rows().getValue(WatermarkCell.slotId(mesh.a.replication.watermarkRef(mesh.ra.ref)))
        @Suppress("UNCHECKED_CAST")
        val epochA = (mesh.ra.outlet as civictech.cell.port.FanOutlet<Propagate<civictech.cell.data.delta.SetDelta<String>>>).waveState().sourceId
        val s = (rowA.keys - epochA).single()

        val before = mesh.frontiers()
        // Non-vacuity: converged and non-empty before the combined delta.
        before.forEach { it.perSource shouldBe mapOf(s to 9L) }

        val slotE = WatermarkCell.slotId(mesh.a.replication.watermarkRef(CellRef(logicalId, 4)))
        companionA.deltaInlet.call.propagate(
            WatermarkDelta(rows = mapOf(slotE to mapOf(s to 9L)), members = setOf(slotE))
        )
        controller.runToIdle()

        // Marker and full row arrived in the SAME delta: no peer ever observed
        // the rowless-marker intermediate state, so the frontier never dips.
        mesh.frontiers() shouldBe before
    }
}
