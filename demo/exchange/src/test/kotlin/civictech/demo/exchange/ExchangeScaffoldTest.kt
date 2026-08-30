package civictech.demo.exchange

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * CP-E1 composition probe. The `:demo:exchange` scaffold — two symmetric JVM
 * peers, region-keyed order writers → mesh-chained union → per-region
 * GroupBy(sum) → glitch-free board → SSE — exercises the Phase-1 kernel features
 * together:
 *
 *  - per-cell journaling (CP-C1): only the writer SetCells are journaled;
 *  - the glitch-free board (CP-A4): the board reads the GroupBy behind a
 *    `GlitchFreeCell` whose inlet carries the WaveFrontier(WAIT) policy;
 *  - replication over the existing mesh: the order unions are chained peer↔peer.
 *
 * Mirrors `demo/shopping`'s `TwoJvmConvergenceTest` + `CrashRestartConvergenceTest`
 * shapes: two OS processes peered over WebSocket, plus a kill -9 / recover.
 */
class ExchangeScaffoldTest {

    // `JvmPeer.launch` again (computenet-dqy.25). The local launcher and the local
    // log-folding wait that used to sit here existed only because the shared helper
    // redirected the peer to INHERIT, which Gradle's console never renders — the
    // finding that shaped them still holds, so `JvmPeer` now buffers every peer's
    // output itself and folds it into the failure message of `JvmPeer.await` and
    // `Peer.port`.
    private fun launch(vararg appArgs: String): JvmPeer.Peer =
        JvmPeer.launch("civictech.demo.exchange.MainKt", *appArgs)

    /** One SSE frame from /events — a fresh tab is sent the current board immediately. */
    private fun currentState(httpPort: Int): String {
        val connection = URI("http://localhost:$httpPort/events").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 3000
        connection.connectTimeout = 3000
        return connection.inputStream.bufferedReader().use { reader ->
            generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ")
        }.also { connection.disconnect() }
    }

    /** The board sub-object, e.g. `{"north":17,"south":5}`. */
    private fun boardOf(httpPort: Int): String =
        currentState(httpPort).substringAfter("\"board\":").substringBefore("},\"total\"") + "}"

    private fun post(httpPort: Int, action: String, region: String, id: String, amount: Long) {
        val connection = URI("http://localhost:$httpPort/op").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use {
            it.write("action=$action&region=$region&id=$id&amount=$amount".toByteArray())
        }
        check(connection.responseCode == 200) { "op failed: ${connection.responseCode}" }
        connection.disconnect()
    }

    private fun up(httpPort: Int): Boolean = runCatching {
        (URI("http://localhost:$httpPort/").toURL().openConnection() as HttpURLConnection)
            .apply { connectTimeout = 500; readTimeout = 500 }
            .responseCode == 200
    }.getOrDefault(false)

    private fun down(httpPort: Int): Boolean = !up(httpPort)

    @Tag("multi-jvm")
    @Test
    fun `edits on either JVM converge to the same region-sum board`() {
        // every port is `0`: each peer binds its own and announces what it got, so
        // no test-side number is ever handed to a process that has yet to bind it
        // (computenet-dqy.25). A must announce its listening port before B can be
        // told to dial it, which is what orders these two launches.
        val peerA = launch("0", "--listen", "0")
        val httpA = peerA.port("http")
        val peerB = launch("0", "--peer", "ws://localhost:${peerA.port("ws")}")
        val httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // orders written on BOTH peers, two regions
            post(httpA, "add", "north", "o1", 10)
            post(httpB, "add", "north", "o2", 20)
            post(httpB, "add", "south", "o3", 5)

            // both boards converge to the SAME region→sum: north 30, south 5, total 35
            awaitUntil("A converged", timeoutMs = 45_000) { boardOf(httpA) == """{"north":30,"south":5}""" }
            awaitUntil("B converged", timeoutMs = 45_000) { boardOf(httpB) == """{"north":30,"south":5}""" }
            awaitUntil("totals equal", timeoutMs = 45_000) {
                "\"total\":35" in currentState(httpA) && "\"total\":35" in currentState(httpB)
            }
            // the two peers' boards are byte-identical (sorted, deterministic)
            awaitUntil("boards identical", timeoutMs = 45_000) { currentState(httpA) == currentState(httpB) }

            // a retraction re-folds the sum incrementally on both sides
            post(httpB, "remove", "north", "o2", 20)
            awaitUntil("A re-folds after retraction", timeoutMs = 45_000) { boardOf(httpA) == """{"north":10,"south":5}""" }
            awaitUntil("B re-folds after retraction", timeoutMs = 45_000) { boardOf(httpB) == """{"north":10,"south":5}""" }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }

    @Tag("multi-jvm")
    @Test
    fun `a kill -9'd peer reconnects, catches up from the survivor, and both sides re-converge`() {
        val journalB = Files.createTempDirectory("computenet-exchange-journal-b").toFile()
        // `0` everywhere, as in the test above. B's relaunch is a fresh process and
        // gets a fresh HTTP port — hence `var httpB`: this test needs the restarted
        // peer to come back with the converged board, not at the same address.
        //
        // SCOPE (computenet-do5r, measured by the computenet-emtx audit at dcb2defa2):
        // this is a kill / reconnect / park-replay / bidirectional-convergence test,
        // NOT a journal-replay test. A survives the whole restart holding the converged
        // board, so A's catch-up baseline subsumes everything B could have replayed —
        // every assertion below still passes with `--journal` dropped from B's relaunch.
        // B is still journaled here because that is the realistic restart shape, and
        // because the journal must not *break* reconnect; the discriminating evidence
        // for CP-C1 per-cell WAL replay is the solo sibling below, `writer journal alone
        // reconstructs the board after restart`, which has no peer to supply anything
        // (point it at a fresh journal dir and it fails: "timed out awaiting: board
        // recovered from writer journal").
        val peerA = launch("0", "--listen", "0")
        val httpA = peerA.port("http")
        val ws = peerA.port("ws")
        var peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        var httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // shared pre-crash state: north written AT A, south written AT B (B journals its own)
            post(httpA, "add", "north", "o1", 10)
            post(httpB, "add", "south", "o2", 5)
            awaitUntil("pre-crash convergence", timeoutMs = 45_000) {
                boardOf(httpA) == """{"north":10,"south":5}""" && boardOf(httpB) == """{"north":10,"south":5}"""
            }

            // B is kill -9'd mid-session
            peerB.kill()
            awaitUntil("peer B is gone", timeoutMs = 45_000) { down(httpB) }

            // life goes on at A: this order parks at A until B returns
            post(httpA, "add", "north", "o3", 7)

            // B relaunches with the SAME journal dir — the realistic restart — and re-peers
            peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            httpB = peerB.port("http")
            JvmPeer.await("peer B back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

            // B came back, re-peered, and caught up over the mesh: A's north (o1), A's
            // parked north (o3), and B's own pre-crash south (o2=5) — which A also holds
            // — land as north 17, south 5. Nothing here separates catch-up from WAL
            // replay; see SCOPE above.
            awaitUntil("B re-converged after reconnect", timeoutMs = 45_000) { boardOf(httpB) == """{"north":17,"south":5}""" }
            awaitUntil("A re-converged", timeoutMs = 45_000) { boardOf(httpA) == """{"north":17,"south":5}""" }

            // fully live again, both directions
            post(httpB, "add", "south", "o4", 8)
            awaitUntil("post-restart edit visible on A", timeoutMs = 45_000) { boardOf(httpA) == """{"north":17,"south":13}""" }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }

    /**
     * CP-C1 in isolation, single process: the journaled writers alone reconstruct
     * the board after a stop/restart on the same journal dir — no wire, no peer
     * re-sync in the picture. This is the per-cell durability claim by itself: the
     * volatile aggregates (union / GroupBy / board) rebuild purely from replaying
     * the writer WAL.
     *
     * This is exchange's ONLY journal-discriminating test — the two-peer crash test
     * above is subsumed by the surviving peer (computenet-do5r). Verified by mutation:
     * give the recovered `ExchangeApp` a fresh journal dir instead of `journal` and it
     * fails with `timed out awaiting: board recovered from writer journal`.
     */
    @Test
    fun `writer journal alone reconstructs the board after restart`() {
        val journal = Files.createTempDirectory("computenet-exchange-journal-solo").toFile()
        // port `0` in-process too, read back from the app that bound it. The two
        // ExchangeApps here were the same defect's in-process form: the second
        // demanded the exact port the first had just released (computenet-dqy.25).
        // What this test is about is the journal, not the address, so each app
        // simply binds its own.
        val first = ExchangeApp(0, journalDir = journal).start()
        val firstPort = first.boundPort
        try {
            post(firstPort, "add", "north", "o1", 10)
            post(firstPort, "add", "north", "o2", 20)
            post(firstPort, "add", "south", "o3", 5)
            awaitUntil("board built", timeoutMs = 45_000) { boardOf(firstPort) == """{"north":30,"south":5}""" }
        } finally {
            first.stop()
        }
        // still a real barrier: the first app must be fully gone before the second
        // replays the journal directory they share
        awaitUntil("the first app is gone", timeoutMs = 45_000) { down(firstPort) }

        val recovered = ExchangeApp(0, journalDir = journal).start()
        val recoveredPort = recovered.boundPort
        try {
            awaitUntil("board recovered from writer journal", timeoutMs = 45_000) {
                boardOf(recoveredPort) == """{"north":30,"south":5}""" &&
                    "\"total\":35" in currentState(recoveredPort)
            }
        } finally {
            recovered.stop()
            journal.deleteRecursively()
        }
    }
}
