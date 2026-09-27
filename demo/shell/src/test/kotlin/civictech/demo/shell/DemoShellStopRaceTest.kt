package civictech.demo.shell

import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import com.sun.net.httpserver.Headers
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * computenet-ojxbs: `DemoShell.stop()` used to snapshot the client list with
 * `clients.toList()`. Kotlin's `Iterable<T>.toList()` (kotlin-stdlib
 * `commonMain/generated/_Collections.kt`) special-cases a `Collection` of
 * size 1:
 *
 * ```
 * public fun <T> Iterable<T>.toList(): List<T> {
 *     if (this is Collection) {
 *         return when (size) {
 *             0 -> emptyList()
 *             1 -> listOf(if (this is List) get(0) else iterator().next())
 *             else -> this.toMutableList()
 *         }
 *     }
 *     ...
 * }
 * ```
 *
 * `CopyOnWriteArrayList` `is List`, so this reads `size` and, finding 1,
 * calls `get(0)` as a second, separate operation rather than going through
 * the list's own snapshotting iterator. If another thread removes that one
 * element between the `size` read and the `get(0)` call, the backing array
 * has already been replaced with an empty one by the time `get(0)` runs, and
 * `CopyOnWriteArrayList.get`/`elementAt` throws
 * `ArrayIndexOutOfBoundsException` against it — exactly the crash CI hit
 * (`CopyOnWriteArrayList.elementAt` <- `.get` <- `kotlin.collections.
 * CollectionsKt___CollectionsKt.toList` <- `DemoShell.stop`) from a real SSE
 * client detaching mid-`stop()`. Confirmed by reading the stdlib source
 * (`kotlin-stdlib-2.1.21-sources.jar!/commonMain/generated/_Collections.kt`,
 * matching the exact `elementAt`/`get`/`toList` frames in the CI stack).
 *
 * This test reproduces that race directly against the field `stop()` reads,
 * without depending on real HTTP/SSE connection timing. Several threads
 * churn a *real* `Client` instance's membership in the list — obtained via
 * reflection, since `Client` is a private inner class with no public
 * constructor, but backed by a genuine (never-started) `HttpExchange` stub
 * so the elements satisfy every `Client`-typed use the same way a live SSE
 * subscriber would — while the main thread calls the real `stop()` many
 * times. Each `stop()` call performs two such snapshots (`awaitDrained` then
 * `stop`), so a 50,000-iteration loop gives 100,000 attempts to land a
 * removal inside the narrow window: reliable enough in practice to fail
 * before the fix (`clients.toList()`) and never to fail after it
 * (`clients.forEach { ... }`, which uses `CopyOnWriteArrayList`'s own
 * iterator — it snapshots the backing array once, atomically, when the
 * iterator is created, so a concurrent add or remove elsewhere is invisible
 * to a sweep already in progress).
 *
 * Neither the stubbed `HttpExchange` nor the `Client`'s pump thread is ever
 * exercised for real I/O: the pump is constructed but never started, so
 * `awaitDrained` (pending == 0) and `stop` (interrupting an unstarted
 * thread, which is legal) both return immediately without touching the
 * exchange.
 */
class DemoShellStopRaceTest {

    @Suppress("UNCHECKED_CAST")
    private fun rawClients(shell: DemoShell): CopyOnWriteArrayList<Any?> {
        val field = DemoShell::class.java.getDeclaredField("clients")
        field.isAccessible = true
        return field.get(shell) as CopyOnWriteArrayList<Any?>
    }

    /** A new, real `DemoShell.Client` wrapping a stub exchange, via reflection. */
    private fun newClient(shell: DemoShell): Any {
        val clientClass = Class.forName("civictech.demo.shell.DemoShell\$Client")
        val ctor = clientClass.getDeclaredConstructor(DemoShell::class.java, HttpExchange::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(shell, StubHttpExchange())
    }

    @Test
    fun `stop does not throw when a client is concurrently added and removed`() {
        val shell = DemoShell(0).start()
        val clients = rawClients(shell)
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)

        val churners = (1..CHURNER_COUNT).map { i ->
            thread(name = "stop-race-churner-$i", isDaemon = true) {
                try {
                    while (running.get()) {
                        val client = newClient(shell)
                        clients.add(client)
                        clients.remove(client)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }

        try {
            repeat(STOP_ITERATIONS) {
                if (failure.get() != null) return@repeat
                try {
                    shell.stop()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        } finally {
            running.set(false)
            churners.forEach { it.join(5_000) }
        }

        failure.get() shouldBe null
    }

    /**
     * The minimal [HttpExchange] `Client`'s constructor needs to satisfy its
     * non-null field; never invoked, since the pump thread this test's
     * `Client`s hold is never started.
     */
    private class StubHttpExchange : HttpExchange() {
        override fun getRequestHeaders(): Headers = throw UnsupportedOperationException()
        override fun getResponseHeaders(): Headers = throw UnsupportedOperationException()
        override fun getRequestURI(): URI = throw UnsupportedOperationException()
        override fun getRequestMethod(): String = throw UnsupportedOperationException()
        override fun getHttpContext(): HttpContext = throw UnsupportedOperationException()
        override fun close() {}
        override fun getRequestBody(): InputStream = throw UnsupportedOperationException()
        override fun getResponseBody(): OutputStream = throw UnsupportedOperationException()
        override fun sendResponseHeaders(rCode: Int, responseLength: Long) = throw UnsupportedOperationException()
        override fun getRemoteAddress(): InetSocketAddress = throw UnsupportedOperationException()
        override fun getResponseCode(): Int = throw UnsupportedOperationException()
        override fun getLocalAddress(): InetSocketAddress = throw UnsupportedOperationException()
        override fun getProtocol(): String = throw UnsupportedOperationException()
        override fun getAttribute(name: String): Any = throw UnsupportedOperationException()
        override fun setAttribute(name: String, value: Any?) = throw UnsupportedOperationException()
        override fun setStreams(i: InputStream?, o: OutputStream?) = throw UnsupportedOperationException()
        override fun getPrincipal(): HttpPrincipal = throw UnsupportedOperationException()
    }

    private companion object {
        const val CHURNER_COUNT = 4
        const val STOP_ITERATIONS = 50_000
    }
}
