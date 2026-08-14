package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.ByteBuffer

/**
 * computenet-dqy.68's fifth instrument, pinned.
 *
 * The nine retained occurrences from run 31756952711 read **zero on every
 * existing instrument** — nothing parked, nothing staged on either bridge host,
 * no pre-hello drop, no gate refusal, `stderr <silent>` — while the client held
 * a strict prefix of the announcing registry's `localRefs()` order. Zero
 * everywhere is compatible with three different truncation points on the
 * announcement channel, and the four instruments cannot separate them:
 *
 * 1. **above the socket** — no frame was produced for the lost refs;
 * 2. **at the socket** — frames were handed to java-websocket and never reached
 *    the peer's `onMessage`;
 * 3. **below the peer's socket** — frames arrived and were lost between the
 *    bridge ingress and the registry mirror.
 *
 * [WsTransport.Session.framesSent] and [WsTransport.Session.framesReceived] cut
 * those apart by counting the channel's two ends. This test's job is to make
 * sure the readings mean what the report claims:
 *
 * - they are **real counts, not stubs** — a healthy peering announces the
 *   announcer's local refs and the two counters agree with each other and with
 *   the refs the peer installed (`the counters equal the announcements`);
 * - they **discriminate** case 2 from case 1 — a `send` that accepts a frame and
 *   silently never delivers it (exactly what a lost java-websocket write demand
 *   looks like: `send` returns normally, the out-queue keeps the frame, nothing
 *   throws anywhere) leaves `framesSent` counting and the peer's
 *   `framesReceived` at zero, which is the reading the three cases differ on.
 *
 * Run 31770947583 then took that reading at nine REAL occurrences and all nine
 * read case 2 — `sent=3 received=K<3` with the listener's out-queue still
 * non-empty 15s later. [WsTransport.Session.framesSent] carries the full
 * reading and the java-websocket race it names.
 */
class WsAnnouncementFrameCountTest {

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    @Test
    fun `the counters equal the announcements that crossed the socket`() {
        val server = Stack()
        val client = Stack()
        val early = WsAnnouncementStressTest.Companion.CollectingCell()
        server.host.managementInlet.call.spawn(early)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (client.registry.location(early.ref) !is LocationRegistry.Remote &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(1)
            }
            assertTrue(
                client.registry.location(early.ref) is LocationRegistry.Remote,
                "the announcement under test never arrived, so this run says nothing about the counters",
            )
            // Every Remote the client installed came from a frame this listener
            // sent and this connection received. The counters are compared to
            // that set rather than to a literal, so a legitimate extra
            // announcement (the mirror/ingress racing the sweep) cannot redden
            // this while a stubbed-out counter still does.
            val installed = client.registry.remoteRefs().size
            assertTrue(installed >= 1, "expected at least the collector to be installed, saw $installed")
            assertTrue(
                listener.framesSent >= installed,
                "listener framesSent=${listener.framesSent} cannot be below the $installed remotes it produced",
            )
            // Bounded wait, not an instantaneous read: the awaited Remote being
            // installed proves only that ITS frame crossed, and `framesSent` is
            // incremented after `send` returns, so a sibling frame in flight (or
            // a sender preempted between the write and the increment) can leave
            // the two ends transiently unequal in either direction. Measured
            // 0/200 on darwin/arm64, but a slower runner is exactly where that
            // window opens, and the property under test is convergence, not the
            // scheduling that reaches it.
            //
            // That is not a guess. WITHOUT this wait, ubuntu-latest reddened
            // this test in 163 of 500 fresh-JVM `:wire` iterations (run
            // 31770947583 at 3dd7e0e — 103 on the s->c assertion, 60 on the
            // reverse one, every one of them lag-by-one or lag-by-two). WITH
            // it, 0 of 100 on the same runner over the same 30-test suite (run
            // 31775175399 at 58d4e13); at the old 32.6% rate 0/100 has
            // probability ~1e-17, so the window is closed rather than
            // unluckily missed. The one way this assertion can still fail is
            // the real defect — a listener out-queue that never drains, see
            // [WsTransport.Session.framesSent] — measured at 9 in 12,500
            // peerings, and that is a failure this test SHOULD report.
            val settle = System.currentTimeMillis() + 15_000
            while (
                (listener.framesSent != connection.framesReceived ||
                    connection.framesSent != listener.framesReceived) &&
                System.currentTimeMillis() < settle
            ) {
                Thread.sleep(1)
            }
            assertEquals(
                listener.framesSent,
                connection.framesReceived,
                "every frame the listener handed to the socket reached the dialer in a healthy peering",
            )
            // and the reverse direction, which the report also prints
            assertEquals(connection.framesSent, listener.framesReceived)
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    @Test
    fun `a frame accepted by the socket and never delivered reads as sent-but-not-received`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val side = Peering.Side(registry, host)
        val peer = Peering.Side(LocationRegistry(), ManagedHost())
        // one ref for the catch-up sweep to announce
        val published = WsAnnouncementStressTest.Companion.CollectingCell()
        host.managementInlet.call.spawn(published)
        val spawnDeadline = System.currentTimeMillis() + 5_000
        while (registry.location(published.ref) == null && System.currentTimeMillis() < spawnDeadline) Thread.sleep(1)
        // `send` accepts and swallows: the shape of a lost write demand — the
        // call returns normally, so nothing throws, nothing is staged and
        // nothing is parked, and only these two counters disagree.
        val blackHole = WsTransport.Session(side, send = { }, refuse = {})
        val receiver = WsTransport.Session(peer, send = { }, refuse = {})

        blackHole.framesSent shouldBe 0L
        receiver.framesReceived shouldBe 0L

        // each side opens its own connection instance first, then hears the
        // peer's hello — the ordering both transports use
        val announcerHello = blackHole.hello()
        val receiverHello = receiver.hello()
        blackHole.onText(receiverHello)
        receiver.onText(announcerHello)
        val deadline = System.currentTimeMillis() + 5_000
        while (blackHole.framesSent == 0L && System.currentTimeMillis() < deadline) Thread.sleep(1)

        assertTrue(blackHole.framesSent > 0L, "the announcer handed at least one frame to its socket")
        // nothing was wired between the two sessions, so the peer's socket
        // delivered nothing — the exact asymmetry that names case 2.
        receiver.framesReceived shouldBe 0L

        // and a frame that IS delivered moves the other end's counter, so the
        // zero above is an observation and not a counter that never counts.
        receiver.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        receiver.framesReceived shouldBe 1L
    }
}
