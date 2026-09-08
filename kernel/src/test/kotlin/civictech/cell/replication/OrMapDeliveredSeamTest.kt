package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * The OR-map's **delivered seam** (spec 40/42 §Delivered watermarks, 96 §E3.3(a);
 * feature computenet-9sm.8 clause 1, decisions 9sm.8-D1/D5/D9) — `SetCell`'s
 * seam, dot-shaped, asserted where `DeliveredWatermarkTest` asserts the
 * PN-counter's.
 *
 * What each test is the witness for, i.e. the datum a non-satisfying build
 * returns differently:
 *
 * - **origin-keyed, not outlet-tapped.** A replicated [OrMapCell] that does not
 *   implement [civictech.cell.data.delta.DeliveryTracking] has no companion row
 *   keyed by its `dotSource` at all — `Replication.trackDeliveries` installs the
 *   `onDeliver` listener only `if (cell is DeliveryTracking)` — while the CP-B2
 *   outlet tap keys the SAME companion by the cell's outlet epoch either way. So
 *   the pair "`watermark(aDotSource)` non-null **and** `watermark(aOutletEpoch)`
 *   non-null, and they are different keys" is what proves the row moved into the
 *   fold rather than being read off the tap.
 * - **the del-dot is delivered.** `watermark(aDotSource)` reaching `2` after a
 *   remove is only possible if the remove minted a dot of its own
 *   (`[24-TAG-04]`, 9sm.8-D5) AND every peer folded the `dels` lane of the delta
 *   it absorbed. A build that mints no del-dot leaves it at `1`; a build that
 *   folds only the `puts` lane in `applyRemote` leaves it at `1` on the
 *   NON-origin peers alone.
 * - **the re-put's retract half mints too**, ahead of the new value's dot, so a
 *   re-put consumes two counters and its `dels` entry carries exactly one dot
 *   that is in no `puts` anywhere.
 * - **the tag lane continues across a reincarnation** (9sm.8-D9): a fresh
 *   [OrMapCell] replicated onto the same [CellRef] mints strictly above the
 *   departed incarnation's high-water, instead of restarting at 1 and re-minting
 *   counters peers' rows already certify.
 *
 * The three-loopback fixture and its `Peer` are copied from
 * `OrMapConvergenceTest` (whose `Peer` is file-private — copying is that file's
 * own sanctioned idiom); the `watermarkOf(logicalId)!!` read is
 * `DeliveredWatermarkTest`'s.
 */
class OrMapDeliveredSeamTest {

    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
        val deadLetters = mutableListOf<DeadLetter>()

        init {
            listOf(host, bridgeHost).forEach { h ->
                h.deadLetterOutlet.subscribe(
                    Use.fixed(Propagate<DeadLetter> { deadLetters += it }, PortRef.generate())
                )
            }
        }

        fun ops(replica: OrMapCell<String, String>): MapOps<String, String> =
            (HostedCellProxy.create(replica.ref, registry, OrMapInletProxy::class.java)
                    as OrMapInletProxy).inlet.call
    }

    /** Three peers on a full loopback mesh, one OR-map replica each. */
    private class Mesh {
        val controller = SimulationController()
        val peers = List(3) { Peer(controller) }
        val logicalId: UUID = UUID.randomUUID()
        val replicas: List<OrMapCell<String, String>>

        /**
         * What each replica's OWN [civictech.cell.data.delta.DeliveryTracking]
         * fold reported, per replica — the per-cell datum the merged companion
         * lattice cannot give back.
         *
         * The companion row is a **gossiped pointwise max**, so
         * `watermarkOf(id).watermark(origin)` on a peer that folded nothing
         * still reads the origin's own figure the moment the companions
         * exchange. That makes the companion read the right witness for "the
         * row is origin-keyed and it converges" and the WRONG one for "this
         * replica's fold absorbed the dot" (measured: dropping the `dels` lane
         * from `applyRemote`'s fold leaves every companion assertion in this
         * file green). This listener is that second witness.
         */
        val folded: List<MutableList<Pair<UUID, Long>>>

        init {
            Peering.loopback(peers[0].side, peers[1].side)
            Peering.loopback(peers[1].side, peers[2].side)
            Peering.loopback(peers[0].side, peers[2].side)
            replicas = peers.mapIndexed { i, peer ->
                OrMapCell<String, String>(CellRef(logicalId, i.toLong()))
                    .also { peer.replication.replicate(it, peer.host) }
            }
            folded = replicas.map { replica ->
                mutableListOf<Pair<UUID, Long>>().also { seen ->
                    replica.onDeliver { source, thru -> seen += source to thru }
                }
            }
            controller.runToIdle()
        }

        fun watermarks(source: UUID): List<Long?> =
            peers.map { it.replication.watermarkOf(logicalId)!!.watermark(source) }

        /** The highest prefix each replica's own fold reported for [source]. */
        fun foldedThru(source: UUID): List<Long?> =
            folded.map { seen -> seen.filter { it.first == source }.maxOfOrNull { it.second } }
    }

    /** Record a replica's broadcast emissions (`OrMapConvergenceTest`'s recorder). */
    private fun record(cell: OrMapCell<String, String>): MutableList<TaggedMapDelta<String, String>> {
        val out = mutableListOf<TaggedMapDelta<String, String>>()
        cell.outlet.subscribe(
            Use.fixed(
                Propagate<TaggedMapDelta<String, String>> { out += it },
                PortRef.generate(),
            )
        )
        return out
    }

    /** The `sourceId` of the cell's own outlet epoch — the CP-B2 tap's key space. */
    @Suppress("UNCHECKED_CAST")
    private fun outletEpochOf(cell: OrMapCell<String, String>): UUID =
        (cell.outlet as FanOutlet<Propagate<TaggedMapDelta<String, String>>>).waveState().sourceId

    @Test
    fun `every peer's companion row is keyed by the ORIGIN dot source, and a put advances it to 1`() {
        val mesh = Mesh()
        val a = mesh.replicas[0]
        val emitted = record(a)
        mesh.peers[0].ops(a).put("k", "v")
        mesh.controller.runToIdle()

        val aDotSource = emitted.single().puts.getValue("k").keys.single().sourceId
        // the origin lane: every peer, including the two that only ABSORBED the
        // delta, has the origin's row at its contiguous delivered prefix
        mesh.watermarks(aDotSource) shouldBe listOf(1L, 1L, 1L)

        // …and that is a DIFFERENT key space from the CP-B2 outlet tap, which
        // also rides this companion. Both rows exist; neither stands in for the
        // other.
        val aEpoch = outletEpochOf(a)
        withClue("the outlet epoch is not the dot source") { (aEpoch == aDotSource) shouldBe false }
        mesh.peers[0].replication.watermarkOf(mesh.logicalId)!!.watermark(aEpoch).shouldNotBeNull()

        mesh.peers.forEach { it.deadLetters shouldBe emptyList<DeadLetter>() }
    }

    @Test
    fun `a remove mints a del-dot that every peer delivers - the row reaches 2`() {
        val mesh = Mesh()
        val a = mesh.replicas[0]
        val emitted = record(a)
        val ops = mesh.peers[0].ops(a)

        ops.put("k", "v")
        mesh.controller.runToIdle()
        val aDotSource = emitted[0].puts.getValue("k").keys.single().sourceId
        mesh.watermarks(aDotSource) shouldBe listOf(1L, 1L, 1L)

        ops.remove("k")
        mesh.controller.runToIdle()

        // the remove shipped its own dot beside the put-dot it covers…
        val removeEntry = emitted[1].dels.getValue("k")
        removeEntry shouldBe setOf(Timestamp(aDotSource, 1L), Timestamp(aDotSource, 2L))
        emitted[1].puts shouldBe emptyMap()
        // …and every peer's row advanced through it. Without the mint the row
        // stays at 1 everywhere.
        mesh.watermarks(aDotSource) shouldBe listOf(2L, 2L, 2L)
        // Each replica's OWN fold reached 2 as well — the non-origin two by
        // folding the `dels` lane of the delta they absorbed. This is the
        // assertion that discriminates that lane: the companion read above does
        // NOT, because the companion rows gossip a pointwise max and a replica
        // that folded nothing still reads the origin's figure (see [Mesh.folded]).
        mesh.foldedThru(aDotSource) shouldBe listOf(2L, 2L, 2L)

        // a remove that mints nothing (no live dot) leaves the row where it was
        ops.remove("k")
        ops.remove("never-present")
        mesh.controller.runToIdle()
        emitted.size shouldBe 2
        mesh.watermarks(aDotSource) shouldBe listOf(2L, 2L, 2L)
    }

    @Test
    fun `a re-put mints the retract del-dot first and the new value's dot second`() {
        val mesh = Mesh()
        val a = mesh.replicas[0]
        val emitted = record(a)
        val ops = mesh.peers[0].ops(a)

        ops.put("k", "v1")
        ops.put("k", "v2")
        mesh.controller.runToIdle()

        val firstPut = emitted[0].puts.getValue("k").keys
        val src = firstPut.single().sourceId
        // del-dot n, put-dot n+1 — one delta, two counters
        emitted[1].dels.getValue("k") shouldBe firstPut + Timestamp(src, 2L)
        emitted[1].puts.getValue("k").keys shouldBe setOf(Timestamp(src, 3L))

        // exactly one dot of the retract entry is in no `puts` anywhere: the
        // del-dot. A build that mints no retract dot emits put-dot 2 and a
        // `dels` entry wholly contained in the put-dots.
        val allPutDots = emitted.flatMap { d -> d.puts.values.flatMap { it.keys } }.toSet()
        (emitted[1].dels.getValue("k") - allPutDots).size shouldBe 1

        // the value is untouched by the del-dot on every replica
        mesh.replicas.forEach { it.value("k") shouldBe "v2" }
        mesh.watermarks(src) shouldBe listOf(3L, 3L, 3L)
    }

    @Test
    fun `a reincarnation on the same ref continues the tag lane instead of re-minting`() {
        val mesh = Mesh()
        val a = mesh.replicas[0]
        val emitted = record(a)
        mesh.peers[0].ops(a).put("k", "v")
        mesh.controller.runToIdle()
        val src = emitted.single().puts.getValue("k").keys.single().sourceId
        val previousHighWater = 1L

        // clean departure, then a FRESH cell on the same CellRef
        mesh.peers[0].replication.evict(a, mesh.peers[0].host) shouldBe true
        mesh.controller.runToIdle()

        val reborn = OrMapCell<String, String>(CellRef(mesh.logicalId, 0L))
        mesh.peers[0].replication.replicate(reborn, mesh.peers[0].host)
        mesh.controller.runToIdle()
        val rebornEmissions = record(reborn)
        mesh.peers[0].ops(reborn).put("z", "w")
        mesh.controller.runToIdle()

        val mint = rebornEmissions.first { "z" in it.puts }.puts.getValue("z").keys.single()
        mint.sourceId shouldBe src // the source is ref-derived, so it is the same lane
        // a cell without TagLaneContinuity restarts the counter and mints 1 here
        mint.counter shouldBe previousHighWater + 1
    }
}
