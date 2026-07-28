package civictech.demo

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * M5.7 demonstration smoke: the demo app running across **two OS processes**
 * peered over WebSocket — the M4 graph unchanged, placement is the only
 * difference. An edit posted to either peer converges on the other, observed
 * through the SSE endpoints (each fresh SSE connection is a "browser tab").
 */
class TwoJvmConvergenceTest {

    /** One SSE message from /events — a fresh tab is sent the current state immediately. */
    private fun currentState(httpPort: Int): String {
        val connection = URI("http://localhost:$httpPort/events").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 3000
        connection.connectTimeout = 3000
        return connection.inputStream.bufferedReader().use { reader ->
            generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ")
        }.also { connection.disconnect() }
    }

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

    @Tag("multi-jvm")
    @Test
    fun `edits on either JVM converge on the other`() {
        val httpA = JvmPeer.freePort()
        val httpB = JvmPeer.freePort()
        val ws = JvmPeer.freePort()
        val peerA = JvmPeer.launch("civictech.demo.MainKt", "$httpA", "--listen", "$ws")
        val peerB = JvmPeer.launch("civictech.demo.MainKt", "$httpB", "--peer", "ws://localhost:$ws")
        try {
            awaitUntil("both peers serving HTTP") { up(httpA) && up(httpB) }

            post(httpA, user = "alice", action = "add", item = "apples")
            awaitUntil("apples visible on peer B") { "apples" in currentState(httpB) }

            post(httpB, user = "bob", action = "add", item = "bread")
            post(httpB, user = "bob", action = "vote", item = "apples")
            awaitUntil("bread visible on peer A") { "bread" in currentState(httpA) }
            awaitUntil("bob's vote counted on peer A") { "\"voteCount\":1" in currentState(httpA) }

            post(httpA, user = "alice", action = "remove", item = "apples")
            awaitUntil("removal visible on peer B") {
                // scope to the items array — the vote for apples legitimately remains
                "apples" !in currentState(httpB).substringAfter("\"items\":").substringBefore("]")
            }
        } finally {
            peerA.destroy()
            peerB.destroy()
        }
    }
}
