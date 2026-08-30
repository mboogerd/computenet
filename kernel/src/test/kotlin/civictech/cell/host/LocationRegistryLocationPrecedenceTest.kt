package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * **Location precedence in [LocationRegistry.install]: last writer wins,
 * unconditionally, in every direction** (computenet-mx6p).
 *
 * `install` ends with a bare `locations[ref] = location`. It never compares the
 * incoming [LocationRegistry.Location] against the one already held, so a
 * peer-announced [LocationRegistry.Remote] replaces this host's own
 * [LocationRegistry.Local] binding for the same ref — after which every send
 * for a cell this host is itself serving leaves for the wire, and the local
 * instance never hears from its own process again.
 *
 * That was filed as a suspected defect after two peered `demo/tiering` nodes
 * lost every routed write (surfaced by computenet-3san's review). **It is the
 * intended behaviour**, and these tests are the record of why — each names the
 * transition it pins, so a later reader can see which of them a "local wins"
 * guard would have to break.
 *
 * The load-bearing fact is that [CellRef] is a *globally unique* identity:
 * "Instance ids must be minted collision-free without coordination" ([CellRef]'s
 * own KDoc, G-8/M7.1), and replicas of one logical cell are distinct instances
 * (spec 42). So two `Local` bindings for one ref, on two peers, is a violated
 * precondition, not a state the registry is asked to arbitrate — and the absence
 * of enforcement is already filed as spec gap **G-57**
 * (`doc/spec/40-distribution/41-location-transparency.md`: "instanceId minting
 * has no stated collision discipline across hosts"), not as registry behaviour.
 *
 * **Measured, not argued**: inserting a naive local-wins guard
 * (`if (locations[ref] is Local && location is Remote) return`) into
 * [LocationRegistry.install] turns the first test below red and leaves
 * `:kernel:test` (1271 tests) and `:wire:test` (91) otherwise entirely green.
 * So the two "dependent transition" tests here do **not** discriminate against
 * that guard, and nobody should cite them as if they did — they pin transitions
 * the registry relies on, not a reason the guard is impossible. The reason is
 * the identity precondition above, plus one recovery a guard would cost by
 * argument rather than by measurement (a host holding a stale `Local` could
 * never learn the ref moved, because it would refuse the only announcement that
 * could tell it): see [LocationRegistry.install]'s KDoc, which states both.
 *
 * The overwrite's *silence* is a real residual and is filed as computenet-rfbt,
 * open — it is not fixed here.
 *
 * The last test states the other half of the mechanism: `Peering.announceTo`'s
 * catch-up sweep announces *every* `Local` ref, with no replication filter.
 */
class LocationRegistryLocationPrecedenceTest {

    interface CollectorProxy {
        val inlet: Use<Consumer<Int>>
    }

    class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    /** Stands in for a peer's bridge egress: records rather than serializes. */
    private class RecordingSink(private val name: String) : InvocationSink {
        val delivered = mutableListOf<HostedPortInvocation>()
        override fun deliver(invocation: HostedPortInvocation) {
            delivered += invocation
        }

        override fun toString() = "peer($name)"
    }

    /** Registry-resolving consumer API for [cell], as a registry-aware `lookup` builds it. */
    private fun registryApi(registry: LocationRegistry, cell: CollectorCell): Consumer<Int> =
        (HostedCellProxy.create(cell.ref, registry, CollectorProxy::class.java) as CollectorProxy).inlet.call

    // ---------------------------------------------------------------- Local -> Remote

    /**
     * The behaviour computenet-mx6p was filed about, demonstrated rather than
     * read: an announcement for a ref this host serves *locally* wins, and
     * routing follows it off-process immediately. Nothing is logged, nothing is
     * refused, nothing is counted — the write is simply not where the sender
     * thinks it is.
     */
    @Test
    fun `a peer announcement replaces this host's own Local binding, and routing follows it away`() {
        val controller = SimulationController(0L)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val peer = RecordingSink("b")

        val cell = CollectorCell()
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        val api = registryApi(registry, cell)

        api.provide(1)
        controller.runToIdle()
        cell.received shouldBe listOf(1)
        registry.locate(cell.ref) shouldBe host

        // the peer announces the same ref — an identity collision (G-57), not a move
        registry.publish(cell.ref, peer)

        registry.location(cell.ref).shouldBeInstanceOf<LocationRegistry.Remote>()
        registry.locate(cell.ref) shouldBe null
        registry.localRefs().shouldBeEmpty()

        api.provide(2)
        controller.runToIdle()

        // the locally hosted cell never sees its own process's write again
        cell.received shouldBe listOf(1)
        peer.delivered.map { it.cellRef } shouldBe listOf(cell.ref)
    }

    // ---------------------------------------------------------------- Remote -> Local

    /**
     * The first transition that *depends* on the unconditional overwrite:
     * inbound mobility. A ref mirrored here as `Remote` (its host is a peer)
     * becomes `Local` the moment it is spawned here — `ManagedHost.spawn` calls
     * `registry.publish(ref, host, cell)` and there is no intervening
     * [LocationRegistry.unpublish] on *this* registry, because the retraction
     * that would produce one is the departing host's own announcement, a
     * separate message on a separate connection. A guard that made the held
     * binding authoritative in general would strand this ref pointing at the
     * host it just left.
     */
    @Test
    fun `a local spawn replaces a mirrored Remote binding — inbound mobility depends on it`() {
        val controller = SimulationController(0L)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val formerHost = RecordingSink("a")

        val cell = CollectorCell()
        registry.publish(cell.ref, formerHost)
        registry.location(cell.ref).shouldBeInstanceOf<LocationRegistry.Remote>()

        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        registry.locate(cell.ref) shouldBe host
        registryApi(registry, cell).provide(1)
        controller.runToIdle()

        cell.received shouldBe listOf(1)
        formerHost.delivered.shouldBeEmpty()
    }

    // ---------------------------------------------------------------- Remote -> Remote

    /**
     * The second dependent transition: a re-announcement must be able to
     * re-point a ref that is already mirrored. Every heal and every reconnect
     * replays a full `localRefs` catch-up (`Peering.announceTo`,
     * `Peering.Loopback.heal`), so *the normal case* is an announcement landing
     * on a ref this registry already has a location for — through a fresh
     * mirror and therefore a fresh sink. If the held binding won, a peer that
     * came back on a new connection could never be reached again.
     */
    @Test
    fun `a later peer announcement re-points a ref already mirrored — reconnect catch-up depends on it`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        val staleConnection = RecordingSink("stale")
        val freshConnection = RecordingSink("fresh")

        registry.publish(ref, staleConnection)
        registry.publish(ref, freshConnection)

        val invocation = HostedPortInvocation(ref, "inlet", HostedPortInvocation.Type.PORT_API, probe())
        registry.deliver(invocation)

        freshConnection.delivered.size shouldBe 1
        staleConnection.delivered.shouldBeEmpty()
    }

    // ---------------------------------------------------------------- announceTo's scope

    /**
     * The other half of the mechanism, executed rather than inherited:
     * `Peering.announceTo`'s catch-up sweep is `registry.localRefs().forEach`,
     * and [LocationRegistry.localRefs] filters on `is Local` alone. So a peer
     * hears about **every** ref this host serves — replicated, partitioned,
     * plain, and the bridge's own mirror cells alike. There is no
     * replication-scoped announcement anywhere on this path, and none is
     * needed: an announcement says "ref X lives here", which is true of every
     * local ref, and it is the *uniqueness of X* (G-8, gap G-57) that keeps two
     * hosts from ever saying it about the same ref.
     */
    @Test
    fun `announceTo's catch-up announces every Local ref, with no replication filter`() {
        val controller = SimulationController(0L)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val via = RecordingSink("peer")

        val plain = CollectorCell()
        val alsoPlain = CollectorCell()
        host.managementInlet.call.spawn(plain)
        host.managementInlet.call.spawn(alsoPlain)
        val mirroredFromElsewhere = CellRef(UUID.randomUUID())
        registry.publish(mirroredFromElsewhere, RecordingSink("other"))
        controller.runToIdle()

        val announcer = Peering.announceTo(
            Peering.Side(registry = registry, bridgeHost = bridgeHost),
            peerMirror = CellRef(UUID.randomUUID()),
            via = via,
        )
        controller.runToIdle()
        announcer.close()

        val announced = via.delivered
            .filter { it.invocation.methodName == "published" }
            .map { it.invocation.args[0] as CellRef }

        // both plain, non-replicated cells were announced; the peer's own ref was not
        announced shouldContainExactlyInAnyOrder registry.localRefs().toList()
        announced.toSet() shouldBe setOf(plain.ref, alsoPlain.ref)
        (mirroredFromElsewhere in announced) shouldBe false
    }

    private fun probe(): civictech.cell.proxy.Invocation =
        civictech.cell.proxy.Invocation(
            methodName = "provide",
            parameterTypes = listOf("java.lang.Object"),
            args = listOf(1),
        )
}
