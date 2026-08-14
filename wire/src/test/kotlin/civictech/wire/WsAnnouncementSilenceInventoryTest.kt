package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryMirrorCell
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * computenet-dqy.40: **which ways of losing an announcement are silent?**
 *
 * The 2026-08-12 Linux event
 * (`scripts/flake-loop/evidence/2026-08-12-linux-announcement-loss.txt`) is one
 * catch-up announcement that never arrived in 15 seconds, with **nothing on
 * `System.err` for the whole iteration** — and the stress test tees stderr, so
 * that silence is a measurement, not an absence of looking. Silence is
 * therefore the sharpest constraint the record carries, and this test turns it
 * into eliminations by making each candidate fire on demand and recording
 * whether it speaks.
 *
 * The result, measured here rather than argued:
 *
 * - **A throw mid-sweep truncated the burst** — [Peering.announceTo] ran
 *   `localRefs().forEach(announce::published)` with no per-ref isolation, so
 *   the first failure abandoned every ref behind it while the `onLocalPublish`
 *   hook it registered *first* survived to announce later publishes. That is
 *   exactly the shape the 2026-08-12 record has (a burst that stopped, one ref
 *   present, the collector missing) — and it is a real path: the `:wire` suite
 *   itself raises it, from both sides, in 3 of 3 Linux container runs. It is
 *   repaired here; see [Peering.announceTo].
 * - **But on the transport it is loud.** Driving a throw out of `onText`
 *   through a real listener puts `[WsListener] …` plus a stack trace on
 *   stderr. So the truncating-throw mechanism **cannot** be the silent
 *   2026-08-12 event, however well its shape fits. (It also leaves the socket
 *   *open*, which is why that loss would have been permanent — see that test.)
 * - **The mirror's shut gate is silent** — it was, before this item, a drop
 *   with no counter and no log at all. So is `Session`'s pre-hello frame drop
 *   (counted per Session since T05 finding 7, but never surfaced). Those two,
 *   plus a delivery that simply never ran, are what a silent loss can be —
 *   and both now have a number the stress report prints.
 *
 * Platform note: every count below is deterministic (a forced fault, not a
 * race), so the trial counts here are about repeatability, not rate. All of it
 * was run on macOS 26.6 aarch64 and inside groovy:4.0-jdk21 on Linux aarch64.
 */
class WsAnnouncementSilenceInventoryTest {

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    private class Collecting(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    /**
     * The burst-stopping shape, made to fire on demand — and the regression
     * gate on its repair (computenet-dqy.40).
     *
     * **Before the fix** (verified by reverting [Peering.announceTo]'s
     * `catchUp` isolation, 10/10 iterations, macOS aarch64): `announceTo`
     * propagated the failing announcement, only `failAt - 1` of the 8 refs were
     * ever announced, and the method never returned — so its three hooks
     * leaked. **After**: all 8 are announced, the failure is reported, and the
     * announcer handle comes back so the caller can close it.
     *
     * This is the same failure a real socket raises: `WsTransport.Session`'s
     * egress subscriber rethrows `IntakeClosedException` on a failed send, and
     * that lands here.
     */
    @Test
    fun `one failing announcement does not abandon the rest of the catch-up sweep`() {
        repeat(10) {
            val stack = Stack()
            repeat(8) { stack.host.managementInlet.call.spawn(Collecting()) }
            val announced = mutableListOf<HostedPortInvocation>()
            val seen = AtomicInteger()
            val failAt = 3
            val sink = InvocationSink { invocation ->
                if (seen.incrementAndGet() == failAt) throw IllegalStateException("simulated send failure")
                announced += invocation
            }

            stack.registry.localRefs().size shouldBe 8
            val announcer = Peering.announceTo(stack.side, CellRef(UUID.randomUUID()), sink)

            // every ref except the one whose send failed reaches the peer; a
            // truncating sweep would stop at failAt - 1
            announced.size shouldBe 7

            // and the handle came back, so the hooks are closeable rather than
            // leaked on a `via` that has just failed
            announcer.close()
            stack.host.managementInlet.call.spawn(Collecting())
            announced.size shouldBe 7
        }
    }

    /**
     * The same truncating throw, driven through a real listener — and it is
     * **not** silent. `Session.onText`'s own `require(startsWith(HELLO))` is
     * the cheapest way to raise it without touching production code: any
     * exception out of `onText` takes the same route, and that route ends at
     * [WsTransport.WsListener.onError], which prints.
     *
     * Two things are asserted, and the second one is the ugly one. The
     * exception **is** reported, so a truncated sweep cannot be the silent
     * 2026-08-12 event. But the socket **stays open** and nothing retries:
     * java-websocket reports the failed callback and carries on, so a sweep
     * that dies half-way leaves a live connection whose peer will never hear
     * about the refs behind the failure — no close, so no reconnect, so no
     * re-announce. Measured, not assumed; recorded here because it is what
     * would make such a loss permanent rather than transient if it ever did
     * fire.
     */
    @Test
    fun `a throw out of the listener's onText is reported, and leaves the socket open`() {
        val server = Stack()
        val listener = WsTransport.listen(0, server.side)
        val captured = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(captured, true))
        val closed = CountDownLatch(1)
        val stillOpen: Boolean
        try {
            val opened = CountDownLatch(1)
            val raw = object : WebSocketClient(URI("ws://localhost:${listener.port}")) {
                override fun onOpen(handshake: ServerHandshake) = opened.countDown()
                override fun onMessage(message: String) = Unit
                override fun onClose(code: Int, reason: String?, remote: Boolean) = closed.countDown()
                override fun onError(ex: Exception) = Unit
            }
            raw.connectBlocking(10, TimeUnit.SECONDS) shouldBe true
            opened.await(10, TimeUnit.SECONDS) shouldBe true
            raw.send("NOT A HELLO")
            // the report is written from the listener's decoder worker; give it
            // a bounded moment, then read both facts off the same instant
            val deadline = System.currentTimeMillis() + 5_000
            while (!captured.toString().contains("[WsListener]") && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            stillOpen = raw.isOpen && closed.count == 1L
            raw.closeBlocking()
        } finally {
            System.setErr(realErr)
            runCatching { listener.stop(1000) }
        }
        captured.toString() shouldContain "[WsListener]"
        captured.toString() shouldContain "unexpected text message"
        stillOpen shouldBe true
    }

    /**
     * The gate: a detached mirror drops an announcement outright, writes
     * nothing, and — since this item — counts it. Delivered straight to the
     * served inlet, which is the same handler the bridge host dispatches into,
     * so this is the drop itself rather than a stand-in for it.
     */
    @Test
    fun `a shut mirror gate drops an announcement silently, and now counts it`() {
        val stack = Stack()
        val egressless = InvocationSink { }
        val mirror = RegistryMirrorCell(stack.registry, egressless)
        stack.bridgeHost.managementInlet.call.spawn(mirror)
        val announced = CellRef(UUID.randomUUID())

        mirror.inlet.call.published(announced)
        stack.registry.location(announced) shouldBe LocationRegistry.Remote(egressless, null)

        mirror.detach()
        stack.registry.location(announced) shouldBe null // detach retracts what it installed

        // The capture is scoped to this thread, and the neighbour write below
        // is why (computenet-dqy.67). `System.err` is process-wide: a plain
        // `System.setErr(PrintStream(ByteArrayOutputStream()))` here asserted
        // that *no thread in the JVM* wrote anything, which is not what this
        // item is about and is not something this test can hold anyone to —
        // `WsTransport.WsConnection.onError` prints `[WsConnection] <exception>`
        // on every failed dial, and a superseded dialer's `ws-reconnect-*` loop
        // keeps dialing forever, outliving the test that made it. That bled in
        // at 8/200 in-process suite iterations on darwin/arm64, rising with JVM
        // age. The line the neighbour prints here is verbatim the one that was
        // observed doing it; it is what this assertion must ignore. The drop
        // path itself is a synchronous call on this thread, so scoping the
        // capture loses nothing the mirror could have said.
        val captured = stderrWrittenByThisThread {
            val neighbour = Thread(
                { System.err.println("[WsConnection] java.net.ConnectException: Connection refused") },
                "ws-reconnect-stand-in",
            )
            neighbour.start()
            neighbour.join()
            repeat(4) { mirror.inlet.call.published(CellRef(UUID.randomUUID())) }
        }

        stack.registry.remoteRefs() shouldBe emptySet()
        captured shouldBe "" // the silence this item is about
        mirror.refusedAnnouncements shouldBe 4L
    }

    /**
     * The transport surfaces both silent counts, so a stress-report diagnosis
     * can read them off a live connection instead of inferring them.
     */
    @Test
    fun `the transport exposes its two silent drop counts`() {
        val side = Peering.Side(LocationRegistry(), ManagedHost())
        val session = WsTransport.Session(side, send = {}, refuse = {})
        session.preHelloDrops shouldBe 0L
        session.refusedAnnouncements shouldBe 0L

        session.onFrame(java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        session.preHelloDrops shouldBe 1L

        session.onText(session.hello())
        session.refusedAnnouncements shouldBe 0L
        session.onClose() // detaches this instance's mirror; later announcements are refused
        session.onFrame(java.nio.ByteBuffer.wrap(byteArrayOf(4, 5, 6)))
        session.preHelloDrops shouldBe 1L // the ingress survives the close, so this one is not a pre-hello drop
    }

    /**
     * A live listener/dialer pair reports zero of both while the announcement
     * path is healthy — the calibration that makes a non-zero count in a
     * failure report mean something.
     */
    @Test
    fun `a healthy connection reports no silent drops`() {
        val server = Stack()
        val client = Stack()
        val early = Collecting()
        server.host.managementInlet.call.spawn(early)
        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (client.registry.location(early.ref) !is LocationRegistry.Remote &&
                System.currentTimeMillis() < deadline
            ) Thread.sleep(2)
            (client.registry.location(early.ref) is LocationRegistry.Remote) shouldBe true
            (client.registry.remoteRefs().size > 0) shouldBe true
            connection.preHelloDrops shouldBe 0L
            connection.refusedAnnouncements shouldBe 0L
            listener.preHelloDrops shouldBe 0L
            listener.refusedAnnouncements shouldBe 0L
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
