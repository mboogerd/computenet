package civictech.agora

import civictech.demo.shell.DemoShell
import civictech.demo.shell.respond
import civictech.testkit.HttpProbe
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Why `AgoraApp.stop()` is `shell.stop()` alone and needs no host drain
 * (computenet-u7by, the AgoraApp half of computenet-t3sp).
 *
 * `DialogueApp.stop()` had to grow a bounded drain because its mutation
 * thread is an `ExecutorService` it **interrupts**: `driver.shutdownNow()`
 * can land inside `AgoraService.createEdge`, between the moment the edge is
 * published into `nodes` (so `/graph` serves it) and the moment it reaches
 * `graph.jsonl`. `AgoraApp` has no such thread. Its mutation thread is the
 * JDK HttpServer's dispatcher: `DemoShell` sets `server.executor = null`
 * (DemoShell.kt:47), so `sun.net.httpserver.ServerImpl`'s `DefaultExecutor`
 * runs each exchange **inline** on `HTTP-Dispatcher`, and `stop(delay)`
 * ends by `dispatcherThread.join()` — it never interrupts the handler, and
 * it does not return until the handler has finished. So the whole of
 * `createClaim` / `createEdge` / `remove` completes before `AgoraApp.stop()`
 * returns.
 *
 * That property lives in another module and in the JDK, which is exactly why
 * it is pinned here rather than only argued: swapping `DemoShell`'s `null`
 * executor for a thread pool would silently reopen the window that
 * [an in-flight request completes before the shell's stop returns] closes,
 * and [a pooled executor would NOT wait, which is what makes the arm above
 * non-vacuous] is the arm that shows this test can tell the difference.
 *
 * The data half needs no fence either: `ManagedHost.enqueueHostedInvocation`
 * appends the journal frame inside the same `synchronized(dataLock)` as
 * staging (ManagedHost.kt, "write-ahead (M10.1)"), and `FileJournal.append`
 * ends in `fd.sync()` before it returns and buffers nothing (Journal.kt).
 * A credence is therefore fsync'd before any cell can process it, and long
 * before `/graph` can show it — there is nothing left in memory for a
 * `close()` at `stop()` to flush.
 */
class AgoraStopWindowTest {

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        /** The handler body: announce arrival, park until released, then record completion. */
        fun serve() {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            completed.set(true)
        }
    }

    /**
     * The premise `AgoraApp.stop()`'s safety rests on: a handler that is
     * running when `stop()` is called runs to completion, and `stop()` blocks
     * until it has.
     */
    @Test
    fun `an in-flight request completes before the shell's stop returns`() {
        val gate = Gate()
        val shell = DemoShell(port = 0)
        shell.route("/slow") { exchange ->
            gate.serve()
            exchange.respond(200, "done")
        }
        shell.start()
        val probe = HttpProbe("http://localhost:${shell.boundPort}")

        val caller = Thread { probe.use { runCatching { it.get("/slow") } } }.apply { isDaemon = true; start() }
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "handler never started")

        val stopReturned = CountDownLatch(1)
        val stopper = Thread { shell.stop(); stopReturned.countDown() }.apply { isDaemon = true; start() }

        // stop() must NOT return while the handler is parked: it is joining the
        // dispatcher thread the handler is running on.
        assertFalse(
            stopReturned.await(500, TimeUnit.MILLISECONDS),
            "stop() returned while a handler was still in flight — the dispatcher was not joined, " +
                "so AgoraService's published-before-logged span in createEdge becomes reachable",
        )

        gate.release.countDown()
        assertTrue(stopReturned.await(10, TimeUnit.SECONDS), "stop() never returned after the handler finished")
        assertTrue(gate.completed.get(), "the in-flight handler did not complete")
        stopper.join(1_000)
        caller.join(1_000)
    }

    /**
     * The non-vacuity arm. The assertion above is a claim about `DemoShell`'s
     * inline dispatch, not a truism about `HttpServer.stop`: the same server
     * with a pooled executor returns from `stop(0)` with the handler still
     * running. If `DemoShell` ever acquires an executor, the arm above goes
     * red and this one stays green.
     */
    @Test
    fun `a pooled executor would NOT wait, which is what makes the arm above non-vacuous`() {
        val gate = Gate()
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = pool
        server.createContext("/slow") { exchange ->
            gate.serve()
            exchange.respond(200, "done")
        }
        server.start()
        try {
            val probe = HttpProbe("http://localhost:${server.address.port}")
            Thread { probe.use { runCatching { it.get("/slow") } } }.apply { isDaemon = true; start() }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "handler never started")

            server.stop(0)
            assertFalse(
                gate.completed.get(),
                "a pooled handler completed across stop(0) — this arm no longer discriminates",
            )
        } finally {
            gate.release.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * End to end, through `AgoraApp` itself: every ref `/graph` served before
     * `stop()` is in `graph.jsonl` afterwards, so a reboot rebuilds all of it.
     * This is the property computenet-t3sp's lost-EDGE failure violated on the
     * dialogue side.
     */
    @Test
    fun `every ref graph served before stop survives into the structure log`() {
        val dir = kotlin.io.path.createTempDirectory("agora-stop-window").toFile()
        val app = AgoraApp(port = 0, journalDir = dir).start()
        val served: Set<String>
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val a = probe.postRef("action=claim&text=a")
            val b = probe.postRef("action=claim&text=b")
            probe.postRef("action=edge&source=$a&target=$b&polarity=attack")
            served = refsOf(probe.get("/graph").body())
            assertEquals(3, served.size, "expected two claims and one edge")
        } finally {
            app.stop()
        }

        val logged = java.io.File(dir, "graph.jsonl").readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { Regex("\"ref\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            .toSet()
        assertTrue(
            logged.containsAll(served),
            "served by /graph but absent from graph.jsonl (unrecoverable): ${served - logged}",
        )
    }

    private fun HttpProbe.postRef(body: String): String {
        val response = postForm(body)
        assertEquals(200, response.statusCode(), response.body())
        return Regex("\"ref\":\"([^\"]+)\"").find(response.body())!!.groupValues[1]
    }

    private fun refsOf(json: String): Set<String> =
        Regex("\"ref\":\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
}
