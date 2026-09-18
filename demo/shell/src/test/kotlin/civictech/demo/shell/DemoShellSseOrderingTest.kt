package civictech.demo.shell

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * computenet-tp93v: pins the ordering guarantee `DemoShell.sse`/[DemoShell.broadcast]
 * now give every SSE client — no client ever receives a frame older than one
 * it has already been sent, even when a [DemoShell.broadcast] races a
 * connecting client's own initial-frame computation.
 *
 * The bug (reasoned from source, never reproduced before this test): `sse`
 * used to add the connecting exchange to the broadcast list BEFORE computing
 * and writing its initial frame. A `broadcast` landing in that window found
 * the exchange already registered and could win the per-exchange write lock
 * ([HttpExchange.sseFrame]'s `synchronized(this)`) ahead of the connecting
 * client's own (older, already-computed) initial frame — delivering the
 * newer broadcast frame first and the stale initial frame second. For
 * allocator-observe's terminal `frozenJson` broadcast, that meant a
 * newly-connecting client could see the frozen envelope and then, right
 * after, an unlabelled live-looking document — exactly the confusion
 * computenet-w20a4 removed for every non-racing case.
 *
 * This test forces exactly that race: [DemoShell.sse]'s `initialFrame` lambda
 * blocks mid-computation while a concurrent [DemoShell.broadcast] is fired,
 * then resumes. A revert to registering before computing lets the broadcast
 * reach the client while its own initial frame is still in flight, and the
 * broadcast's frame — "FROZEN" here — is written first; this test's ordering
 * assertion then fails.
 */
class DemoShellSseOrderingTest {

    @Test
    fun `a broadcast racing a connecting client's initial frame is never followed by that client's own stale frame`() {
        val computing = CountDownLatch(1)
        val resume = CountDownLatch(1)

        val shell = DemoShell(0)
        shell.sse("/events") {
            // Signal that the initial frame's computation has started, then
            // hold it open until the test has fired a racing broadcast — the
            // window the bug lived in.
            computing.countDown()
            resume.await(5, TimeUnit.SECONDS)
            "\"LIVE\""
        }
        shell.start()

        val received = Collections.synchronizedList(mutableListOf<String>())
        val readerStarted = CountDownLatch(1)
        val reader = thread(name = "sse-reader") {
            val connection = URI("http://localhost:${shell.boundPort}/events").toURL()
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            val body = connection.inputStream.bufferedReader()
            readerStarted.countDown()
            runCatching {
                body.lineSequence().forEach { line ->
                    if (line.startsWith("data: ")) received += line.removePrefix("data: ")
                }
            }
        }

        readerStarted.await(5, TimeUnit.SECONDS)
        // The connection's headers are flushed (HttpExchange.beginSse) before
        // `initialFrame` is ever called, so the reader above is already
        // attached by the time this fires.
        check(computing.await(5, TimeUnit.SECONDS)) { "the initial frame was never computed" }

        val broadcaster = thread(name = "racing-broadcast") {
            shell.broadcast { "\"FROZEN\"" }
        }

        // Give the racing broadcast a head start before letting the initial
        // frame's computation resume. Under the bug this is generous: with no
        // lock guarding registration, `broadcast`'s only work is one method
        // call and a write into a loopback socket buffer, which completes
        // well under this margin every time it has been run locally. Under
        // the fix, `broadcast` is blocked on `clientsLock` for this entire
        // window regardless, so the margin costs nothing but wall time.
        Thread.sleep(300)
        resume.countDown()
        broadcaster.join(5_000)

        shell.stop()
        reader.join(5_000)

        received shouldContain "\"LIVE\""
        received shouldContain "\"FROZEN\""
        // The property computenet-tp93v exists to guarantee: once this client
        // has received a frame, no older one arrives after it. Here "FROZEN"
        // is the newer of the two (fired while "LIVE" was still being
        // computed), so it must be the last frame on the wire.
        received.indexOf("\"FROZEN\"") shouldBe received.lastIndex
    }
}
