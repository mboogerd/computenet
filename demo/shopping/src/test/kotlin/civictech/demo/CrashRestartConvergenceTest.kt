package civictech.demo

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun launch(vararg appArgs: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        // per-process log files (not INHERIT): on failure the peers' own output
        // is the first diagnostic you want
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
    fun `a kill -9'd peer recovers from its journal, re-peers, and both sides converge`() {
        val httpA = freePort()
        val httpB = freePort()
        val ws = freePort()
        val journalB = Files.createTempDirectory("computenet-journal-b").toFile()
        val peerA = launch("$httpA", "--listen", "$ws")
        var peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        try {
            await("both peers serving HTTP") { up(httpA) && up(httpB) }

            // shared pre-crash state, written on BOTH sides
            post(httpA, user = "alice", action = "add", item = "apples")
            post(httpB, user = "bob", action = "add", item = "bread")
            await("pre-crash convergence") { "apples" in items(httpB) && "bread" in items(httpA) }

            // B is kill -9'd mid-session
            peerB.destroyForcibly()
            await("peer B is gone") { down(httpB) }

            // life goes on at A: this edit parks at A until B returns
            post(httpA, user = "alice", action = "add", item = "cheese")

            // B relaunches with the SAME journal directory — recovery + reconnect
            peerB = launch("$httpB", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            await("peer B back up") { up(httpB) }

            // B recovered its own pre-crash state from the journal (bread was
            // written AT B; apples arrived over the wire pre-crash — both are
            // journal records at B, neither is re-sent by A)
            await("journal-recovered state visible on B") {
                "bread" in items(httpB) && "apples" in items(httpB)
            }
            // the edit made while B was down replays out of A's park
            await("parked edit replayed to B") { "cheese" in items(httpB) }

            // and the session is fully live again, both directions
            post(httpB, user = "bob", action = "add", item = "dates")
            await("post-restart edit visible on A") { "dates" in items(httpA) }
        } finally {
            peerA.destroy()
            peerB.destroyForcibly()
            journalB.deleteRecursively()
        }
    }
}
