package civictech.cell.link

import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.streamTo
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.PortAddress
import civictech.cell.wire.bridgeTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * computenet-7u22s — the computenet-1rvt failure-isolation policy
 * (`NotificationFailures`/`notifyAll` in `LinkSupport.kt`), pinned at the
 * sites the primary handshake overload's own `LinkSupersessionTest` cases
 * cannot reach: the BRIDGED handshake overload (`Handshake.kt`'s two-arg
 * `handshake` for `bridgeTo`/`bridgeFrom`) and an ordinary TEARDOWN multicast
 * (as opposed to the deferred supersession-retraction multicast 1rvt's own
 * tests exercise). Every case here registers a throwing listener ahead of a
 * recording one in the SAME `onLinkedListeners`/`onUnlinkListeners` list,
 * then asserts: the recording listener still ran, and the throw still
 * propagates (later ones would be suppressed, but there is only one here).
 *
 * Before computenet-7u22s these sites ran a bare
 * `listeners.forEach { it(link) }`, so the first throwing listener stopped
 * every listener registered after it in the same list — the exact defect
 * computenet-1rvt closed for the primary handshake overload alone.
 */
class ListenerIsolationTest {

    private class Boom : RuntimeException("listener failure (computenet-7u22s)")

    private fun addr(port: String) = PortAddress(CellRef(UUID.randomUUID()), port)
    private val nullSink = InvocationSink { }

    // ---- bridged handshake overload: connect-time onLinkedListeners ----

    @Test
    fun `bridged handshake isolates onLinkedListeners siblings on connect`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val notified = mutableListOf<Link>()
        outlet.linking.onLinkedListeners += { throw Boom() }
        outlet.linking.onLinkedListeners += { notified += it }

        val error = assertThrows<Boom> {
            outlet.bridgeTo(selfAddr = addr("outlet"), toAddr = addr("inlet"), sink = nullSink)
        }

        error.suppressed.size shouldBe 0
        notified.size shouldBe 1
    }

    // ---- bridged handshake overload: teardown onUnlinkListeners ----

    @Test
    fun `bridged handshake isolates onUnlinkListeners siblings on teardown`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val link = (
            outlet.bridgeTo(selfAddr = addr("outlet"), toAddr = addr("inlet"), sink = nullSink)
                as LinkResult.Connected
            ).link
        val notified = mutableListOf<Link>()
        outlet.linking.onUnlinkListeners += { throw Boom() }
        outlet.linking.onUnlinkListeners += { notified += it }

        assertThrows<Boom> { link.unlink() }

        notified shouldBe listOf(link)
        // the record is still removed despite the throwing sibling — teardown
        // bookkeeping (`support.remove`) runs before the multicast, unaffected.
        outlet.linking.links shouldBe emptyList()
    }

    // ---- ordinary (non-supersession) teardown of the primary handshake overload ----

    @Suppress("UNCHECKED_CAST")
    private fun collectingInlet(): FanInlet<Consumer<String>> =
        FanInlet.create<Consumer<String>>().apply {
            serve(object : Consumer<String> {
                override fun provide(input: String) {}
            })
        }

    @Test
    fun `an ordinary unlink isolates onUnlinkListeners siblings on the target side`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val inlet = collectingInlet()
        @Suppress("UNCHECKED_CAST")
        val link = (outlet.linkTo(inlet as LinkFrom<Consumer<String>>) as LinkResult.Connected).link

        val notified = mutableListOf<Link>()
        inlet.linking.onUnlinkListeners += { throw Boom() }
        inlet.linking.onUnlinkListeners += { notified += it }

        assertThrows<Boom> { link.unlink() }

        notified shouldBe listOf(link)
        inlet.linking.links shouldBe emptyList()
        outlet.linking.links shouldBe emptyList()
    }

    @Test
    fun `an ordinary unlink still notifies the source side when a target-side listener throws`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val inlet = collectingInlet()
        @Suppress("UNCHECKED_CAST")
        val link = (outlet.linkTo(inlet as LinkFrom<Consumer<String>>) as LinkResult.Connected).link

        // The target (inlet) list is multicast first; AttentionSupport's
        // retraction lives on the SOURCE (outlet) list, run second.
        val notified = mutableListOf<Link>()
        inlet.linking.onUnlinkListeners += { throw Boom() }
        outlet.linking.onUnlinkListeners += { notified += it }

        assertThrows<Boom> { link.unlink() }

        notified shouldBe listOf(link)
    }

    // ---- StreamTo's bypass teardown and supersession sites ----

    @Test
    fun `streamTo's bypass teardown isolates onUnlinkListeners siblings`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val target = object : Consumer<String> {
            override fun provide(input: String) {}
        }
        val link = outlet.streamTo(target, negotiated = false)

        val notified = mutableListOf<Link>()
        outlet.linking.onUnlinkListeners += { throw Boom() }
        outlet.linking.onUnlinkListeners += { notified += it }

        assertThrows<Boom> { link.unlink() }

        notified shouldBe listOf(link)
    }

    @Test
    fun `streamTo's supersession site isolates onUnlinkListeners siblings`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val at = PortRef.generate()
        val target = object : Consumer<String> {
            override fun provide(input: String) {}
        }
        val first = outlet.streamTo(target, at = at, negotiated = false)

        val notified = mutableListOf<Link>()
        outlet.linking.onUnlinkListeners += { throw Boom() }
        outlet.linking.onUnlinkListeners += { notified += it }

        // re-streaming to the same `at` supersedes `first`, evicting and
        // notifying about it before registering the replacement.
        assertThrows<Boom> { outlet.streamTo(target, at = at, negotiated = false) }

        notified shouldBe listOf(first)
    }
}
