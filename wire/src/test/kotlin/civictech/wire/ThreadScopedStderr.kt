package civictech.wire

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Run [body] with `System.err` swapped, and return **only what the calling
 * thread wrote** while it ran. Everything any other thread writes in that
 * window is forwarded to the real `System.err` untouched.
 *
 * Why this exists (computenet-dqy.67). `System.err` is process-wide, and a
 * test that swaps it for a plain [ByteArrayOutputStream] is no longer
 * measuring its own code — it is measuring the whole JVM. The `:wire` suite
 * has threads that legitimately write there and outlive the test that created
 * them: [WsTransport.WsConnection.onError] prints `[WsConnection] <exception>`
 * on every failed dial, and a superseded dialer's `ws-reconnect-*` loop keeps
 * dialing a port nobody is listening on for the rest of the JVM's life. So
 * `WsAnnouncementSilenceInventoryTest`'s "the mirror wrote nothing" assertion
 * was really "no thread in this JVM wrote anything", which failed 8/200
 * in-process suite iterations on darwin/arm64 — and at a rate that rose with
 * JVM age, as accumulating retry loops predict.
 *
 * Scoping by thread is exact for that test rather than approximate: the drop
 * path under measurement (`FanInlet.call` → [Use.fixed] → the served
 * `RegistryAnnounce`) is a plain synchronous call, so all of the code under
 * test runs on the caller's thread and nothing it could emit is missed. This
 * is deliberately not a filter on message content: an unrelated prefix is not
 * what distinguishes "our output" from "somebody else's", and filtering one
 * would only hide the next neighbour instead of the current one.
 *
 * The forwarding half matters too — under a plain capture, every neighbour's
 * diagnostic vanished into a buffer nobody printed for the duration of the
 * window. Here it still reaches the console.
 */
internal fun stderrWrittenByThisThread(body: () -> Unit): String {
    val owner = Thread.currentThread()
    val mine = ByteArrayOutputStream()
    val real = System.err
    val router = object : OutputStream() {
        override fun write(b: Int) {
            if (Thread.currentThread() === owner) mine.write(b) else real.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (Thread.currentThread() === owner) mine.write(b, off, len) else real.write(b, off, len)
        }

        override fun flush() = real.flush()
    }
    System.setErr(PrintStream(router, true))
    try {
        body()
    } finally {
        System.setErr(real)
    }
    return mine.toString()
}

/**
 * The capture's own gate: it must record the owner's writes (otherwise every
 * assertion built on it is vacuous) and must not record anybody else's
 * (otherwise it is the process-global capture computenet-dqy.67 is about).
 */
class ThreadScopedStderrTest {

    @Test
    fun `the capture records the calling thread's writes and no other thread's`() {
        val neighbourReached = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(neighbourReached, true))
        val captured = try {
            stderrWrittenByThisThread {
                System.err.println("mine")
                val neighbour = Thread(
                    { System.err.println("[WsConnection] java.net.ConnectException: Connection refused") },
                    "ws-reconnect-stand-in",
                )
                neighbour.start()
                neighbour.join()
            }
        } finally {
            System.setErr(realErr)
        }

        captured shouldBe "mine" + System.lineSeparator()
        // and the neighbour's line was not swallowed — it went to the stream
        // that was current when the scoped capture was installed
        neighbourReached.toString() shouldContain "[WsConnection] java.net.ConnectException: Connection refused"
    }
}
