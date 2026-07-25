package civictech.demo.exchange

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun launch(vararg appArgs: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        // per-process log file: on failure the peers' own output is the first
        // diagnostic you want (and never an INHERIT stream that outlives us).
        val log = File.createTempFile("computenet-exchange-peer-", ".log").apply { deleteOnExit() }
        return ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "civictech.demo.exchange.MainKt", *appArgs
        ).redirectErrorStream(true).redirectOutput(log).start()
    }

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

    private fun await(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(100)
        }
    }

    private fun up(httpPort: Int): Boolean = runCatching {
        (URI("http://localhost:$httpPort/").toURL().openConnection() as HttpURLConnection)
            .apply { connectTimeout = 500; readTimeout = 500 }
            .responseCode == 200
    }.getOrDefault(false)

    private fun down(httpPort: Int): Boolean = !up(httpPort)

    @Test
    fun `edits on either JVM converge to the same region-sum board`() {
        val httpA = freePort()
        val httpB = freePort()
        val ws = freePort()
        val peerA = launch("$httpA", "--listen", "$ws")
        val peerB = launch("$httpB", "--peer", "ws://localhost:$ws")
        try {
            await("both peers serving HTTP") { up(httpA) && up(httpB) }

            // orders written on BOTH peers, two regions
            post(httpA, "add", "north", "o1", 10)
            post(httpB, "add", "north", "o2", 20)
            post(httpB, "add", "south", "o3", 5)

            // both boards converge to the SAME region→sum: north 30, south 5, total 35
            await("A converged") { boardOf(httpA) == """{"north":30,"south":5}""" }
            await("B converged") { boardOf(httpB) == """{"north":30,"south":5}""" }
            await("totals equal") {
                "\"total\":35" in currentState(httpA) && "\"total\":35" in currentState(httpB)
            }
            // the two peers' boards are byte-identical (sorted, deterministic)
            await("boards identical") { currentState(httpA) == currentState(httpB) }

            // a retraction re-folds the sum incrementally on both sides
            post(httpB, "remove", "north", "o2", 20)
            await("A re-folds after retraction") { boardOf(httpA) == """{"north":10,"south":5}""" }
            await("B re-folds after retraction") { boardOf(httpB) == """{"north":10,"south":5}""" }
        } finally {
            peerA.destroy(); peerB.destroy()
            peerA.destroyForcibly(); peerB.destroyForcibly()
        }
    }

    @Test
    fun `a kill -9'd peer recovers its journaled writer state and both sides re-converge`() {
        val httpA = freePort()
        val httpB = freePort()
        val ws = freePort()
        val journalB = Files.createTempDirectory("computenet-exchange-journal-b").toFile()
        val peerA = launch("$httpA", "--listen", "$ws")
        var peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        try {
            await("both peers serving HTTP") { up(httpA) && up(httpB) }

            // shared pre-crash state: north written AT A, south written AT B (B journals its own)
            post(httpA, "add", "north", "o1", 10)
            post(httpB, "add", "south", "o2", 5)
            await("pre-crash convergence") {
                boardOf(httpA) == """{"north":10,"south":5}""" && boardOf(httpB) == """{"north":10,"south":5}"""
            }

            // B is kill -9'd mid-session
            peerB.destroyForcibly()
            await("peer B is gone") { down(httpB) }

            // life goes on at A: this order parks at A until B returns
            post(httpA, "add", "north", "o3", 7)

            // B relaunches with the SAME journal dir — writer replay + reconnect
            peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            await("peer B back up") { up(httpB) }

            // B recovered its OWN journaled writer (south o2=5) from the per-cell WAL,
            // re-received A's north (o1) over the re-peered mesh, and replayed A's
            // parked north (o3) — north 17, south 5.
            await("B recovered + re-converged") { boardOf(httpB) == """{"north":17,"south":5}""" }
            await("A re-converged") { boardOf(httpA) == """{"north":17,"south":5}""" }

            // fully live again, both directions
            post(httpB, "add", "south", "o4", 8)
            await("post-restart edit visible on A") { boardOf(httpA) == """{"north":17,"south":13}""" }
        } finally {
            peerA.destroy(); peerB.destroy()
            peerA.destroyForcibly(); peerB.destroyForcibly()
            journalB.deleteRecursively()
        }
    }

    /**
     * CP-C1 in isolation, single process: the journaled writers alone reconstruct
     * the board after a stop/restart on the same journal dir — no wire, no peer
     * re-sync in the picture. This is the per-cell durability claim by itself: the
     * volatile aggregates (union / GroupBy / board) rebuild purely from replaying
     * the writer WAL.
     */
    @Test
    fun `writer journal alone reconstructs the board after restart`() {
        val journal = Files.createTempDirectory("computenet-exchange-journal-solo").toFile()
        val port = freePort()
        val first = ExchangeApp(port, journalDir = journal).start()
        try {
            post(port, "add", "north", "o1", 10)
            post(port, "add", "north", "o2", 20)
            post(port, "add", "south", "o3", 5)
            await("board built") { boardOf(port) == """{"north":30,"south":5}""" }
        } finally {
            first.stop()
        }
        await("port released") { down(port) }

        val recovered = ExchangeApp(port, journalDir = journal).start()
        try {
            await("board recovered from writer journal") {
                boardOf(port) == """{"north":30,"south":5}""" && "\"total\":35" in currentState(port)
            }
        } finally {
            recovered.stop()
            journal.deleteRecursively()
        }
    }
}
