package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LeaderMark
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.nature.ContractRegistry
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-f7h.2.1` — the **wire surface** of the single-writer leadership
 * mark ([MEM1-11], [MEM1-06]/[MEM1-22]; spec 42 §Single-writer replication):
 * [LeaderMark] is a registered wire type, [RegistryAnnounce.leaderMarked] is
 * its fifth (ids-only) method, and [Peering.announceTo] both subscribes to
 * locally adopted marks and replays every folded mark on catch-up.
 *
 * Everything here drives [LocationRegistry.markLeader] directly: no engine, no
 * roles, no shipping. The engine-level examples belong to a sibling task; what
 * this file owns is that a mark crosses a bridge, folds once on the far side,
 * and is not re-announced back.
 */
class LeaderMarkWireTest {

    // ---------------------------------------------------------------- codec

    private val fixedId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")

    /**
     * `instanceId = 9`, not the default `0`: [WireCodec] never sets
     * `encodeDefaults`, so a defaulted `0` is omitted and the pinned literal
     * below would not actually show the field.
     */
    private val mark = LeaderMark(fixedId, 7L, CellRef(fixedId, 9L))

    private fun leaderMarkedFrame(m: LeaderMark): HostedPortInvocation =
        HostedPortInvocation(
            cellRef = CellRef(fixedId),
            portName = "inlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(
                RegistryAnnounce::class.java.getMethod("leaderMarked", LeaderMark::class.java),
                arrayOf<Any?>(m),
                null,
            ),
        )

    @Test
    fun `a LeaderMark round-trips through the codec, and its encoding is ids-only and additive`() {
        val bytes = WireCodec.encode(leaderMarkedFrame(mark))

        WireCodec.decode(bytes).invocation.args.single() shouldBe mark

        // ids only — logical id, epoch, leader ref. Pinned so that widening the
        // announcement to carry state has to change this literal.
        val args = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["args"]!!.jsonArray
        args.single().toString() shouldBe
            """["LeaderMark",{"logicalId":"00000000-0000-0000-0000-000000000042","epoch":7,""" +
            """"leaderRef":{"id":"00000000-0000-0000-0000-000000000042","instanceId":9}}]"""

        // additive: no VERSION bump, and no frame carries a version key at all
        // (the observation `WireCodecTest` pins for every frame this codec emits).
        WireCodec.VERSION shouldBe 2
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.containsKey("version") shouldBe false
    }

    // -------------------------------------------------- registry-level fixture

    /** A peer: registry, application host and bridge host on one controller. */
    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    /** The `methodId` every `leaderMarked` frame carries. */
    private val leaderMarkedMethodId: Long =
        ContractRegistry.idsOf(
            RegistryAnnounce::class.java.getMethod("leaderMarked", LeaderMark::class.java),
        )!!.second

    private val publishedMethodId: Long =
        ContractRegistry.idsOf(
            RegistryAnnounce::class.java.getMethod("published", CellRef::class.java),
        )!!.second

    /** Records the `methodId` of every frame that crosses, in order. */
    private class MethodIdTap : Peering.FrameInterpose {
        val methodIds = CopyOnWriteArrayList<Long>()
        override fun apply(frame: ByteArray): List<ByteArray> {
            methodIds += WireCodec.decodeFrame(frame).frame.methodId
            return listOf(frame)
        }
    }

    private fun MethodIdTap.countOf(methodId: Long): Int = methodIds.count { it == methodId }

    // --------------------------------------------- [MEM1-11] one hop, one fold

    @Test
    fun `a local markLeader crosses once, folds on the peer, and is not re-announced back`() {
        val controller = SimulationController(0)
        val a = Peer(controller)
        val b = Peer(controller)
        val aToB = MethodIdTap()
        val bToA = MethodIdTap()
        val loopback = Peering.loopback(a.side, b.side, interposeAToB = aToB, interposeBToA = bToA)
        controller.runToIdle()

        var fires = 0
        var localFires = 0
        b.registry.onLeaderMark { fires++ }
        b.registry.onLocalLeaderMark { localFires++ }

        val id = UUID.randomUUID()
        val m1 = LeaderMark(id, 1L, CellRef(id, 0L))
        a.registry.markLeader(m1) shouldBe true
        controller.runToIdle()

        b.registry.instances.leaderOf(id) shouldBe m1
        fires shouldBe 1
        localFires shouldBe 0 // a mirrored fold is not a local adoption (f7h.2-D4)
        aToB.countOf(leaderMarkedMethodId) shouldBe 1
        bToA.countOf(leaderMarkedMethodId) shouldBe 0 // never re-announced onward

        // and the fold is idempotent at registry level ([MEM1-22]): the same
        // mark again is refused, so nothing fires a second time
        b.registry.mirrorLeaderMark(m1) shouldBe false
        fires shouldBe 1

        // the announcer's AutoCloseable closed the leader-mark registration too
        loopback.partition()
        a.registry.markLeader(LeaderMark(id, 2L, CellRef(id, 0L))) shouldBe true
        controller.runToIdle()
        aToB.countOf(leaderMarkedMethodId) shouldBe 1 // unchanged: the hook is gone
    }

    // ------------------ [MEM1-06]/[MEM1-22] catch-up order, and a heal's replay

    @Test
    fun `catch-up replays folded marks after the refs they name, and a heal replays them again`() {
        val controller = SimulationController(0)
        val a = Peer(controller)
        val b = Peer(controller)

        // a local cell and a folded mark, both BEFORE any peering exists
        a.host.managementInlet.call.spawn(SetCell<String>())
        controller.runToIdle()
        a.registry.localRefs().size shouldBeGreaterThan 0

        val id = UUID.randomUUID()
        val m2 = LeaderMark(id, 5L, CellRef(id, 3L))
        a.registry.markLeader(m2) shouldBe true

        var fires = 0
        b.registry.onLeaderMark { fires++ }

        val aToB = MethodIdTap()
        val loopback = Peering.loopback(a.side, b.side, interposeAToB = aToB)
        controller.runToIdle()

        b.registry.instances.leaderOf(id) shouldBe m2
        fires shouldBe 1

        // ordering (f7h.2-D2): the peer holds every announced ref before the
        // first mark naming one arrives
        val order = aToB.methodIds.toList()
        val firstMark = order.indexOf(leaderMarkedMethodId)
        val lastPublished = order.lastIndexOf(publishedMethodId)
        firstMark shouldBeGreaterThan lastPublished

        // a heal is a full catch-up: the mark crosses again, and B rejects it as
        // a duplicate rather than re-notifying ([MEM1-22])
        val before = aToB.countOf(leaderMarkedMethodId)
        loopback.heal()
        controller.runToIdle()
        aToB.countOf(leaderMarkedMethodId) shouldBe before + 1
        fires shouldBe 1
        b.registry.instances.leaderOf(id) shouldBe m2
    }

    // ------------------------------------------------------------ the gate

    @Test
    fun `a leaderMarked announcement is a plain wire type - nothing else changed shape`() {
        // the decoded frame names the RegistryAnnounce contract, and its single
        // argument decodes back to a LeaderMark rather than to a map
        val decoded = WireCodec.decodeFrame(WireCodec.encode(leaderMarkedFrame(mark))).frame
        decoded.methodId shouldBe leaderMarkedMethodId
        decoded.contractId shouldBe ContractRegistry.descriptor(RegistryAnnounce::class.java)!!.contractId
        decoded.context.shouldBeNull()
        (decoded.args.single() as LeaderMark).leaderRef.instanceId shouldBe 9L
    }
}
