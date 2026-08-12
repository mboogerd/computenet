package civictech.demo

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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

    // `JvmPeer.launch` again (computenet-dqy.25). The local launcher this replaced
    // existed only because the shared one redirected to INHERIT, which Gradle's
    // console never renders for a passing-then-failing peer; JvmPeer now buffers
    // each peer's output and folds it into the failure message instead — the same
    // diagnostic the per-process log file was for, minus the file.
    private fun launch(vararg appArgs: String): JvmPeer.Peer =
        JvmPeer.launch("civictech.demo.MainKt", *appArgs)

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
        val journalB = Files.createTempDirectory("computenet-journal-b").toFile()
        // every port is `0`: each peer binds its own and announces what it got, so no
        // test-side number is handed to a process that has yet to bind it
        // (computenet-dqy.25). B's relaunch below is a fresh process and therefore
        // gets a fresh HTTP port — which is why `httpB` is a `var`; nothing here
        // needs the restarted peer to reappear at the same address, only to reappear.
        val peerA = launch("0", "--listen", "0")
        val httpA = peerA.port("http")
        val ws = peerA.port("ws")
        var peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        var httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // shared pre-crash state, written on BOTH sides
            post(httpA, user = "alice", action = "add", item = "apples")
            post(httpB, user = "bob", action = "add", item = "bread")
            awaitUntil("pre-crash convergence", timeoutMs = 45_000) {
                "apples" in items(httpB) && "bread" in items(httpA)
            }

            // D-UNION criterion (c): a union-scoped observed remove, made at
            // the peer about to be kill -9'd, of an item ANOTHER user added on
            // the other JVM. Its del is minted at B's union — bob's writer has
            // no tombstone for it, and alice's writer re-mints the very tag it
            // covers on replay (ref-derived tag sources, M10.1) — so only the
            // replayed remove's retained tombstones keep it out afterwards.
            post(httpA, user = "alice", action = "add", item = "olives")
            awaitUntil("olives converged", timeoutMs = 45_000) { "olives" in items(httpB) }
            post(httpB, user = "bob", action = "remove", item = "olives")
            awaitUntil("pre-crash cross-user removal converged", timeoutMs = 45_000) {
                "olives" !in items(httpB) && "olives" !in items(httpA)
            }

            // B is kill -9'd mid-session
            peerB.kill()
            awaitUntil("peer B is gone", timeoutMs = 45_000) { down(httpB) }

            // life goes on at A: this edit parks at A until B returns
            post(httpA, user = "alice", action = "add", item = "cheese")

            // B relaunches with the SAME journal directory — recovery + reconnect
            peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            httpB = peerB.port("http")
            JvmPeer.await("peer B back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

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

            // D-UNION criterion (c): with everything else converged in both
            // directions, the observed-removed item is still gone — journal
            // replay reproduced the post-remove membership rather than
            // resurrecting it out of the re-minted add-tags
            check("olives" !in items(httpB) && "olives" !in items(httpA)) {
                "an observed-removed item came back after journal replay: " +
                    "A=${items(httpA)} B=${items(httpB)}"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }
}
