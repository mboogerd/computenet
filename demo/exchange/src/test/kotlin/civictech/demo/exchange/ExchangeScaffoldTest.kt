package civictech.demo.exchange

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
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

    // NOT civictech.testkit.JvmPeer.launch: that helper always redirects the
    // launched peer's output to INHERIT, but this test kills a peer mid-session,
    // so its own per-process log file (not INHERIT) is the first diagnostic you
    // want on failure — kept local deliberately (RS-9.2 divergence, mirrors
    // CrashRestartConvergenceTest).
    private class LaunchedPeer(val process: Process, val log: File)

    private fun launch(vararg appArgs: String): LaunchedPeer {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val log = File.createTempFile("computenet-exchange-peer-", ".log").apply { deleteOnExit() }
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "civictech.demo.exchange.MainKt", *appArgs
        ).redirectErrorStream(true).redirectOutput(log).start()
        return LaunchedPeer(process, log)
    }

    // CI's "both peers serving HTTP" timeout has no other diagnostic: the
    // peer's stdout/stderr goes to its own log file (not INHERIT), so on
    // failure that log is the only way to see whether the JVM even started.
    // Folded into the exception message (not println'd) since Gradle's
    // console only ever renders a failed test's exception, never its stdout.
    private fun awaitBothUp(httpA: Int, httpB: Int, peers: List<LaunchedPeer>) {
        try {
            awaitUntil("both peers serving HTTP", timeoutMs = 45_000) { up(httpA) && up(httpB) }
        } catch (e: AssertionError) {
            val logs = peers.joinToString("\n\n") { peer ->
                "---- peer log (${peer.log.absolutePath}) ----\n" +
                    runCatching { peer.log.readText() }.getOrDefault("<unreadable>")
            }
            throw AssertionError("${e.message}\n\n$logs", e)
        }
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

    private fun up(httpPort: Int): Boolean = runCatching {
        (URI("http://localhost:$httpPort/").toURL().openConnection() as HttpURLConnection)
            .apply { connectTimeout = 500; readTimeout = 500 }
            .responseCode == 200
    }.getOrDefault(false)

    private fun down(httpPort: Int): Boolean = !up(httpPort)

    @Tag("multi-jvm")
    @Test
    fun `edits on either JVM converge to the same region-sum board`() {
        val httpA = JvmPeer.freePort()
        val httpB = JvmPeer.freePort()
        val ws = JvmPeer.freePort()
        val peerA = launch("$httpA", "--listen", "$ws")
        val peerB = launch("$httpB", "--peer", "ws://localhost:$ws")
        try {
            awaitBothUp(httpA, httpB, listOf(peerA, peerB))

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
            JvmPeer.destroy(peerA.process, peerB.process)
        }
    }

    @Tag("multi-jvm")
    @Test
    fun `a kill -9'd peer recovers its journaled writer state and both sides re-converge`() {
        val httpA = JvmPeer.freePort()
        val httpB = JvmPeer.freePort()
        val ws = JvmPeer.freePort()
        val journalB = Files.createTempDirectory("computenet-exchange-journal-b").toFile()
        val peerA = launch("$httpA", "--listen", "$ws")
        var peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        try {
            awaitBothUp(httpA, httpB, listOf(peerA, peerB))

            // shared pre-crash state: north written AT A, south written AT B (B journals its own)
            post(httpA, "add", "north", "o1", 10)
            post(httpB, "add", "south", "o2", 5)
            awaitUntil("pre-crash convergence", timeoutMs = 45_000) {
                boardOf(httpA) == """{"north":10,"south":5}""" && boardOf(httpB) == """{"north":10,"south":5}"""
            }

            // B is kill -9'd mid-session
            peerB.process.destroyForcibly()
            awaitUntil("peer B is gone", timeoutMs = 45_000) { down(httpB) }

            // life goes on at A: this order parks at A until B returns
            post(httpA, "add", "north", "o3", 7)

            // B relaunches with the SAME journal dir — writer replay + reconnect
            peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            awaitUntil("peer B back up", timeoutMs = 45_000) { up(httpB) }

            // B recovered its OWN journaled writer (south o2=5) from the per-cell WAL,
            // re-received A's north (o1) over the re-peered mesh, and replayed A's
            // parked north (o3) — north 17, south 5.
            awaitUntil("B recovered + re-converged", timeoutMs = 45_000) { boardOf(httpB) == """{"north":17,"south":5}""" }
            awaitUntil("A re-converged", timeoutMs = 45_000) { boardOf(httpA) == """{"north":17,"south":5}""" }

            // fully live again, both directions
            post(httpB, "add", "south", "o4", 8)
            awaitUntil("post-restart edit visible on A", timeoutMs = 45_000) { boardOf(httpA) == """{"north":17,"south":13}""" }
        } finally {
            JvmPeer.destroy(peerA.process, peerB.process)
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
        val port = JvmPeer.freePort()
        val first = ExchangeApp(port, journalDir = journal).start()
        try {
            post(port, "add", "north", "o1", 10)
            post(port, "add", "north", "o2", 20)
            post(port, "add", "south", "o3", 5)
            awaitUntil("board built", timeoutMs = 45_000) { boardOf(port) == """{"north":30,"south":5}""" }
        } finally {
            first.stop()
        }
        awaitUntil("port released", timeoutMs = 45_000) { down(port) }

        val recovered = ExchangeApp(port, journalDir = journal).start()
        try {
            awaitUntil("board recovered from writer journal", timeoutMs = 45_000) {
                boardOf(port) == """{"north":30,"south":5}""" && "\"total\":35" in currentState(port)
            }
        } finally {
            recovered.stop()
            journal.deleteRecursively()
        }
    }
}
