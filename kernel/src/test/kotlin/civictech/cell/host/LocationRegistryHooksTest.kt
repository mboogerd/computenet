package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T21 — [LocationRegistry]'s notification seam.
 *
 * Two contracts, both of them registry semantics rather than inspector
 * convenience (`doc/remediation/AUDIT-2026-07-28.md` §W5 items 1-2,
 * `doc/architecture-decisions.md` finding B9):
 *
 * 1. **Every hook detaches.** [LocationRegistry.onPublish] and
 *    [LocationRegistry.onUnpublish] return an `AutoCloseable` like their four
 *    `onLocal…` siblings, so an any-scope subscriber that closes stops being
 *    called instead of having to disarm itself with a flag and stay attached
 *    for the registry's lifetime.
 * 2. **Every mutation notifies.** The three paths that used to change registry
 *    state silently — [LocationRegistry.unpublishRemotes] (a peer disconnect),
 *    [LocationRegistry.mirrorLink] and [LocationRegistry.mirrorUnlink] (a
 *    peer's announced edges) — now reach [LocationRegistry.onUnpublish] and the
 *    any-scope [LocationRegistry.onTopology] pair, and every `Remote` location
 *    is readable as a set ([LocationRegistry.remoteRefs]) rather than only
 *    inferable from link endpoints and the replica index.
 *
 * Notification stays what it always was: synchronous, on the mutating thread,
 * and after the mutation is visible — see
 * [a publish hook runs on the publishing thread, after the park replay].
 */
class LocationRegistryHooksTest {

    /**
     * A hand-built remote location: what a bridge egress is to this registry.
     * A named class, not a `InvocationSink { }` lambda — a non-capturing SAM
     * lambda is a JVM singleton, and [LocationRegistry.unpublishRemotes]
     * matches sinks by *identity*, so two "different" empty lambdas would be
     * one peer.
     */
    private class PeerSink(private val name: String) : InvocationSink {
        override fun deliver(invocation: HostedPortInvocation) = Unit
        override fun toString() = "peer($name)"
    }

    private fun sink(name: String = UUID.randomUUID().toString()): InvocationSink = PeerSink(name)

    private fun ref() = CellRef(UUID.randomUUID())

    // ---------------------------------------------------------------- 1. detach

    @Test
    fun `a closed onPublish handle stops receiving while an open one keeps receiving`() {
        val registry = LocationRegistry()
        val detached = mutableListOf<CellRef>()
        val attached = mutableListOf<CellRef>()
        val handle = registry.onPublish { detached += it }
        registry.onPublish { attached += it }

        val first = ref()
        registry.publish(first, sink())
        handle.close()
        val second = ref()
        registry.publish(second, sink())

        detached shouldContainExactly listOf(first)
        attached shouldContainExactly listOf(first, second)
    }

    @Test
    fun `a closed onUnpublish handle stops receiving while an open one keeps receiving`() {
        val registry = LocationRegistry()
        val detached = mutableListOf<CellRef>()
        val attached = mutableListOf<CellRef>()
        val handle = registry.onUnpublish { detached += it }
        registry.onUnpublish { attached += it }

        val first = ref()
        val second = ref()
        registry.publish(first, sink())
        registry.publish(second, sink())

        registry.mirrorUnpublish(first)
        handle.close()
        registry.mirrorUnpublish(second)

        detached shouldContainExactly listOf(first)
        attached shouldContainExactly listOf(first, second)
    }

    @Test
    fun `a closed onTopology handle stops receiving`() {
        val registry = LocationRegistry()
        val seen = mutableListOf<UUID>()
        val handle = registry.onTopology({ seen += it.id }, { seen += it })

        val link = edge()
        registry.mirrorLink(link)
        handle.close()
        registry.mirrorUnlink(link.id)

        seen shouldContainExactly listOf(link.id)
    }

    // ------------------------------------------------- 2. the three silent paths

    @Test
    fun `a peer disconnect notifies the any-scope unpublish hook for every dropped ref`() {
        val registry = LocationRegistry()
        val dying = sink()
        val surviving = sink()
        val theirs = ref()
        val alsoTheirs = ref()
        val someoneElses = ref()
        registry.publish(theirs, dying)
        registry.publish(alsoTheirs, dying)
        registry.publish(someoneElses, surviving)
        val seen = mutableListOf<CellRef>()
        registry.onUnpublish { seen += it }

        // the transport's close path (`Peering.Loopback.partition`, `WsTransport`)
        registry.unpublishRemotes(dying)

        seen.toSet() shouldBe setOf(theirs, alsoTheirs)
        // the other peer's location is untouched, and so is its notification
        registry.remoteRefs() shouldBe setOf(someoneElses)
        registry.location(theirs) shouldBe null
        registry.instancesOf(theirs.id).shouldBeEmpty()
    }

    @Test
    fun `a disconnect notifies only after every location has left the registry`() {
        val registry = LocationRegistry()
        val dying = sink()
        val theirs = ref()
        val alsoTheirs = ref()
        registry.publish(theirs, dying)
        registry.publish(alsoTheirs, dying)
        // an observer that re-reads the registry (deciding whether a mirrored
        // edge still has a live endpoint does exactly that) must never see a
        // half-disconnected peer
        val remainingAtEachNotification = mutableListOf<Int>()
        registry.onUnpublish { remainingAtEachNotification += registry.remoteRefs().size }

        registry.unpublishRemotes(dying)

        remainingAtEachNotification shouldContainExactly listOf(0, 0)
    }

    @Test
    fun `a mirrored link and unlink reach the any-scope topology hook`() {
        val registry = LocationRegistry()
        val linked = mutableListOf<TopologyLink>()
        val unlinked = mutableListOf<UUID>()
        registry.onTopology({ linked += it }, { unlinked += it })
        // the local-only pair must stay local-only: mirroring a peer's edge is
        // not an announcement this registry may relay onward
        val localLinked = mutableListOf<TopologyLink>()
        val localUnlinked = mutableListOf<UUID>()
        registry.onLocalTopology({ localLinked += it }, { localUnlinked += it })

        val link = edge()
        registry.mirrorLink(link)
        registry.mirrorUnlink(link.id)

        linked shouldContainExactly listOf(link)
        unlinked shouldContainExactly listOf(link.id)
        localLinked.shouldBeEmpty()
        localUnlinked.shouldBeEmpty()
        registry.isLocalLink(link.id) shouldBe false
    }

    @Test
    fun `a local link and unlink reach both the local and the any-scope topology hook`() {
        val registry = LocationRegistry()
        val linked = mutableListOf<TopologyLink>()
        val unlinked = mutableListOf<UUID>()
        registry.onTopology({ linked += it }, { unlinked += it })
        val localLinked = mutableListOf<TopologyLink>()
        val localUnlinked = mutableListOf<UUID>()
        registry.onLocalTopology({ localLinked += it }, { localUnlinked += it })

        val link = edge()
        registry.link(link)
        registry.isLocalLink(link.id) shouldBe true
        registry.unlink(link.id)

        linked shouldContainExactly listOf(link)
        unlinked shouldContainExactly listOf(link.id)
        localLinked shouldContainExactly listOf(link)
        localUnlinked shouldContainExactly listOf(link.id)
        registry.isLocalLink(link.id) shouldBe false
    }

    @Test
    fun `remoteRefs names every peer-announced location, and only those`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val cell = Collector()
        host.managementInlet.call.spawn(cell)
        awaitUntil("the spawn published ${cell.ref}") { registry.locate(cell.ref) != null }
        // the case the old link-endpoint/replica-index inference missed: an
        // announced ref that is neither linked to anything nor a replica of a
        // locally published cell
        val unlinkedRemote = ref()
        registry.publish(unlinkedRemote, sink())

        registry.remoteRefs() shouldBe setOf(unlinkedRemote)
        registry.localRefs() shouldBe setOf(cell.ref)

        registry.mirrorUnpublish(unlinkedRemote)
        registry.remoteRefs().shouldBeEmpty()
    }

    // -------------------------------------------------- notification discipline

    /**
     * The park-ordering contract `install`/`deliver`/`replay` documents: a
     * publish drains what parked for the ref *before* the location becomes
     * visible, and the hooks fire after that, on the publishing thread.
     *
     * This fails if a hook call is moved off the mutating thread (the recorded
     * thread stops being this one), or if it is reordered ahead of the park
     * replay (the recorded park depth stops being zero).
     */
    @Test
    fun `a publish hook runs on the publishing thread, after the park replay`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val cell = Collector()
        host.managementInlet.call.spawn(cell)
        awaitUntil("the spawn published ${cell.ref}") { registry.locate(cell.ref) != null }
        val api = (HostedCellProxy.create(cell.ref, registry, CollectorProxy::class.java) as CollectorProxy).inlet.call

        // unlocate it, then send: the invocation parks instead of being dropped
        registry.unpublish(cell.ref)
        api.provide(1)
        registry.parkedFor(cell.ref).size shouldBe 1

        var hookThread: Thread? = null
        var parkedAtHook = -1
        registry.onPublish {
            hookThread = Thread.currentThread()
            parkedAtHook = registry.parkedFor(cell.ref).size
        }
        registry.publish(cell.ref, host)

        hookThread shouldBe Thread.currentThread()
        parkedAtHook shouldBe 0
        awaitUntil("the replayed invocation arrived") { cell.received == listOf(1) }
    }

    // ------------------------------------------------------------------ fixtures

    private fun edge(): TopologyLink =
        TopologyLink(UUID.randomUUID(), PortRef.of(ref(), "outlet"), PortRef.of(ref(), "inlet"))

    interface CollectorProxy {
        val inlet: Use<Consumer<Int>>
    }

    class Collector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
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
}
