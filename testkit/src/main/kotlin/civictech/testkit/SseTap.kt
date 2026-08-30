package civictech.testkit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Attach [body] to [future] so that it runs on [readers] — **never** on the
 * thread that attached it (computenet-i45n).
 *
 * `CompletableFuture.thenAccept` is the trap this function exists to close. It
 * runs the dependent action on the completing thread, and when the future is
 * *already* complete at attach time it runs it inline on the **calling** thread
 * (`CompletableFuture.uniAcceptNow`). `sendAsync`'s future completes as soon as
 * the response *headers* arrive, while `BodyHandlers.ofLines()` leaves the body
 * to be pulled lazily — so on a loaded machine the loopback headers can beat the
 * `thenAccept` call, and the never-ending `text/event-stream` body is then read
 * to exhaustion on the JUnit `Test worker` thread. The test never returns from
 * its `listen()` and dies on JUnit's 5-minute method timeout, whose console
 * stack is the uninformative pair of `ArrayList.forEach` frames.
 *
 * That is not a hypothesis: it is the thread dump CI captured for run
 * 32247784663 (job 96051983983, head 92ad387a, 2026-08-19), preserved in that
 * run's `test-results-fast` artifact by
 * `junit.jupiter.execution.timeout.threaddump.enabled`. `Test worker` was
 * WAITING in
 * `ArrayBlockingQueue.take <- HttpResponseInputStream.read <-
 * BufferedReader.readLine <- ReferencePipeline$Head.forEach <-
 * SseTap.reader$lambda <- CompletableFuture.uniAcceptNow <-
 * CompletableFuture.thenAccept <- SseTap.<init> <- listen()`.
 *
 * `thenAcceptAsync` with an **explicit** executor is the fix: it has no
 * inline-on-the-caller case at all. The executor is explicit rather than the
 * default `ForkJoinPool.commonPool()` because an SSE body blocks for the
 * lifetime of the test, and the common pool is shared with the rest of the JVM.
 *
 * Exposed rather than inlined into [SseTap]'s constructor so that one test can
 * pin the property directly, by attaching to an already-complete future —
 * `SseTapInstrumentTest` in `:inspect`. That is the whole discrimination:
 * observing that an async executor is async passes against `thenAccept`'s own
 * happy path, whereas an already-complete future is exactly the state in which
 * `thenAccept` runs inline.
 */
fun <T> readOffCallerThread(
    future: CompletableFuture<T>,
    readers: Executor,
    body: (T) -> Unit,
): CompletableFuture<Void> = future.thenAcceptAsync(body, readers)

/**
 * A live `text/event-stream` reader: one subscription, read off the calling
 * thread by construction, delivering each `data:` frame through [parse] into a
 * queue the test can count and wait on.
 *
 * The single implementation of a helper that used to be hand-copied into every
 * `:inspect` SSE test class (computenet-0sfc). Eight copies of it were eight
 * copies of one defect — see [readOffCallerThread] — which is what turned a
 * single mistake into four separately-investigated bead reports.
 *
 * [F] is the frame type the caller wants: [parse] receives the text *after*
 * `data: ` and returns whatever the test asserts on (a deserialized event, a
 * projection of it, or the string itself). Query helpers are deliberately
 * predicate-shaped rather than kind-shaped, since "kind" is a property of the
 * caller's frame type, not of SSE.
 *
 * Bounded by design at both ends: the subscription's handshake is bounded by
 * [HttpBounds.CONNECT] via [boundedHttpClient] (a *request* timeout would abort
 * a healthy stream — see [awaitSseData]), every wait here carries an explicit
 * `timeoutMs`, and [close] never awaits.
 */
class SseTap<F>(url: String, parse: (String) -> F) : AutoCloseable {

    private val frames = LinkedBlockingQueue<F>()

    /**
     * Where the SSE body is read. One virtual thread per tap, owned here rather
     * than left to a collector or to `ForkJoinPool.commonPool()`, and shut down
     * in [close] — exactly as computenet-4vh required of the tap's own
     * [HttpClient].
     */
    private val readers: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Held so [close] can release it (computenet-4vh): one client per tap, i.e.
     * per test method, each with its own selector thread and executor pool;
     * cancelling [reader] alone left all of that alive.
     */
    private val client: HttpClient = boundedHttpClient()

    private val reader: CompletableFuture<Void> = readOffCallerThread(
        client.sendAsync(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofLines()),
        readers,
    ) { response ->
        response.body().forEach { line ->
            if (line.startsWith(DATA)) frames += parse(line.removePrefix(DATA))
        }
    }

    /** Every frame delivered so far, in arrival order — no waiting. */
    fun frames(): List<F> = frames.toList()

    /** How many frames delivered so far satisfy [predicate] — no waiting. */
    fun count(predicate: (F) -> Boolean): Int = frames.count(predicate)

    /** Every frame delivered so far satisfying [predicate], in arrival order — no waiting. */
    fun matching(predicate: (F) -> Boolean): List<F> = frames.filter(predicate)

    /**
     * Wait until at least [count] frames have been delivered, then answer every
     * frame delivered so far. [what] names the wait in the timeout message.
     */
    fun awaitAtLeast(count: Int, what: String = "$count sse frames", timeoutMs: Long = 10_000): List<F> {
        awaitUntil("$what (saw ${frames.size})", timeoutMs = timeoutMs) { frames.size >= count }
        return frames.toList()
    }

    /**
     * Wait until at least [count] frames satisfying [predicate] have been
     * delivered, then answer all of them. [what] names the wait in the timeout
     * message.
     */
    fun awaitMatching(
        what: String,
        count: Int = 1,
        timeoutMs: Long = 10_000,
        predicate: (F) -> Boolean,
    ): List<F> {
        awaitUntil("$what (saw ${count(predicate)})", timeoutMs = timeoutMs) { count(predicate) >= count }
        return matching(predicate)
    }

    /**
     * `shutdownNow()`, never `close()`: this client is deliberately parked on an
     * SSE response that never ends, so `close()` — which awaits termination of
     * in-flight exchanges — would turn teardown into the unbounded wait this
     * whole fixture exists to keep out of the suite. [readers] is shut down the
     * same way and for the same reason: its one task is that parked read.
     */
    override fun close() {
        reader.cancel(true)
        client.shutdownNow()
        readers.shutdownNow()
    }

    companion object {
        const val DATA = "data: "
    }
}
