package civictech.demo

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * M10 exit, OS-process level: two demo peers over WebSocket; one is
 * kill -9'd mid-session and relaunched with the same `--journal` directory.
 * It recovers its state from the journal, the reconnect/re-hello path
 * re-peers it, parked edits made while it was down replay, and both browsers
 * converge in both directions — the "survive a restart" criterion, live.
 */
class CrashRestartConvergenceTest {

    // NOT civictech.testkit.JvmPeer.launch: that helper always redirects the
    // launched peer's output to INHERIT, but this test kills a peer mid-session,
    // so its own per-process log file (not INHERIT) is the first diagnostic you
    // want on failure — kept local deliberately (RS-9.2 divergence).
    private fun launch(vararg appArgs: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val log = File.createTempFile("computenet-crash-peer-", ".log").apply { deleteOnExit() }
        return ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "civictech.demo.MainKt", *appArgs
        ).redirectErrorStream(true).redirectOutput(log).start()
    }

    private fun currentState(httpPort: Int): String {
        val connection = URI("http://localhost:$httpPort/events").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 3000
        connection.connectTimeout = 3000
        return connection.inputStream.bufferedReader().use { reader ->
            generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ")
        }.also { connection.disconnect() }
    }

    private fun items(httpPort: Int): String =
        currentState(httpPort).substringAfter("\"items\":").substringBefore("]")

    private fun post(httpPort: Int, user: String, action: String, item: String) {
        val connection = URI("http://localhost:$httpPort/op").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write("user=$user&action=$action&item=$item".toByteArray()) }
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
    fun `a kill -9'd peer recovers from its journal, re-peers, and both sides converge`() {
        val httpA = JvmPeer.freePort()
        val httpB = JvmPeer.freePort()
        val ws = JvmPeer.freePort()
        val journalB = Files.createTempDirectory("computenet-journal-b").toFile()
        val peerA = launch("$httpA", "--listen", "$ws")
        var peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        try {
            awaitUntil("both peers serving HTTP", timeoutMs = 45_000) { up(httpA) && up(httpB) }

            // shared pre-crash state, written on BOTH sides
            post(httpA, user = "alice", action = "add", item = "apples")
            post(httpB, user = "bob", action = "add", item = "bread")
            awaitUntil("pre-crash convergence", timeoutMs = 45_000) {
                "apples" in items(httpB) && "bread" in items(httpA)
            }

            // B is kill -9'd mid-session
            peerB.destroyForcibly()
            awaitUntil("peer B is gone", timeoutMs = 45_000) { down(httpB) }

            // life goes on at A: this edit parks at A until B returns
            post(httpA, user = "alice", action = "add", item = "cheese")

            // B relaunches with the SAME journal directory — recovery + reconnect
            peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            awaitUntil("peer B back up", timeoutMs = 45_000) { up(httpB) }

            // B recovered its own pre-crash state from the journal (bread was
            // written AT B; apples arrived over the wire pre-crash — both are
            // journal records at B, neither is re-sent by A)
            awaitUntil("journal-recovered state visible on B", timeoutMs = 45_000) {
                "bread" in items(httpB) && "apples" in items(httpB)
            }
            // the edit made while B was down replays out of A's park
            awaitUntil("parked edit replayed to B", timeoutMs = 45_000) { "cheese" in items(httpB) }

            // and the session is fully live again, both directions
            post(httpB, user = "bob", action = "add", item = "dates")
            awaitUntil("post-restart edit visible on A", timeoutMs = 45_000) { "dates" in items(httpA) }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }
}
