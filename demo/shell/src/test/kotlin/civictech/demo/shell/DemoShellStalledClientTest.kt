package civictech.demo.shell

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * computenet-t9kpr: one connected SSE client that has stopped reading must not
 * wedge the shell.
 *
 * Before the fix, [DemoShell.broadcast] wrote to every client under the shared
 * `clientsLock`, and an SSE write is a blocking socket write. A client that stays
 * connected but never reads fills its socket buffers, and the broadcasting thread
 * — a demo host's single scheduler thread in production — then blocks inside the
 * write, holding the lock. The next `/events` connection blocks the (single, since
 * `server.executor = null`) HTTP dispatcher thread waiting for that lock, so every
 * route of the demo goes unanswered too.
 *
 * The test builds exactly that client: a raw socket with a small receive buffer
 * that sends `GET /events` and then never reads a byte. It then broadcasts far
 * more data than any socket buffer holds, and asserts three bounded outcomes:
 * the broadcasts return (the scheduler is not held), a fresh `/events` client
 * receives its initial frame, and a plain route answers.
 *
 * The bound is deliberately generous ([BOUND_MS]): the failure mode is an
 * INDEFINITE block, so any finite bound discriminates, and this machine's load
 * must not be what decides the verdict.
 */
class DemoShellStalledClientTest {

    @Test
    fun `a client that never reads does not block broadcasts, new SSE connections, or other routes`() {
        val shell = DemoShell(0)
        shell.sse("/events") { "\"HELLO\"" }
        shell.route("/ping") { it.respond(200, "pong") }
        shell.start()

        // The stalled client: connected, subscribed, never reading.
        val stalled = Socket().apply {
            receiveBufferSize = 4 * 1024
            connect(InetSocketAddress(DemoShell.LOOPBACK, shell.boundPort), BOUND_MS)
            getOutputStream().apply {
                write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
        }
        try {
            // Let the handler register the stalled client before broadcasting,
            // so the broadcasts below are actually addressed to it.
            awaitClients(shell, 1)

            // ~32 MiB in total — orders of magnitude more than the stalled
            // client's socket buffers (its receive buffer is 4 KiB; the
            // server's send buffer is kernel-sized), so a synchronous write
            // path is guaranteed to block partway through.
            val payload = "\"" + "x".repeat(FRAME_BYTES) + "\""
            val broadcastsDone = CountDownLatch(1)
            val broadcaster = thread(name = "stalled-client-broadcaster", isDaemon = true) {
                repeat(FRAMES) { shell.broadcast { payload } }
                broadcastsDone.countDown()
            }

            // 1. The broadcasting thread — the host scheduler, in a demo — is
            //    not held by the client that stopped reading.
            broadcastsDone.await(BOUND_MS.toLong(), TimeUnit.MILLISECONDS) shouldBe true

            // 2. A subsequent SSE connection completes and gets its initial
            //    frame.
            firstDataLine("http://localhost:${shell.boundPort}/events") shouldBe "\"HELLO\""

            // 3. A subsequent non-SSE request is answered.
            get("http://localhost:${shell.boundPort}/ping") shouldBe 200

            // The scenario was real: the stalled client's queue overflowed, so
            // its socket genuinely stopped accepting bytes. Without this the
            // three assertions above could pass on a machine whose buffers
            // happened to swallow everything.
            (shell.droppedFrames > 0) shouldBe true

            broadcaster.join(BOUND_MS.toLong())
        } finally {
            runCatching { stalled.close() }
            shell.stop()
        }
    }

    private fun awaitClients(shell: DemoShell, n: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS.toLong())
        while (shell.clientCount < n) {
            check(System.nanoTime() < deadline) { "the stalled client never registered" }
            Thread.sleep(10)
        }
    }

    private fun firstDataLine(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = BOUND_MS
        connection.readTimeout = BOUND_MS
        try {
            return connection.inputStream.bufferedReader().lineSequence()
                .first { it.startsWith("data: ") }
                .removePrefix("data: ")
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = BOUND_MS
        connection.readTimeout = BOUND_MS
        try {
            val code = connection.responseCode
            connection.inputStream.use { it.readAllBytes() }
            return code
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BOUND_MS = 20_000
        const val FRAME_BYTES = 64 * 1024
        const val FRAMES = 512
    }
}
