package civictech.demo

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /** Just the `items` array — a vote for a since-removed item legitimately stays in `votes`. */
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

    // Frame-attribution helpers for the aligned items/produce pair, mirroring
    // AlignedFrameTest.kt's single-JVM method (duplicated here rather than
    // shared, since this task's claim is this file only).
    private val itemsRe = Regex(""""items":\[([^]]*)]""")
    private val produceRe = Regex(""""produce":\[([^]]*)]""")

    private fun fieldOf(regex: Regex, frame: String): Set<String> =
        regex.find(frame)?.groupValues?.get(1).orEmpty()
            .split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun itemsOf(frame: String) = fieldOf(itemsRe, frame)
    private fun produceOf(frame: String) = fieldOf(produceRe, frame)

    private fun assertProduceSubsetOfItems(frames: List<String>) {
        frames.forEach { frame ->
            val produce = produceOf(frame)
            val items = itemsOf(frame)
            assertTrue(
                produce.all { it in items },
                "produce not a subset of items in frame: $frame (produce=$produce, items=$items)",
            )
        }
    }

    /** Frame indices `i` (1 until frames.size) at which both `items` and `produce` changed vs `frames[i - 1]`. */
    private fun bothChangedIndices(frames: List<String>): List<Int> =
        (1 until frames.size).filter { i ->
            itemsOf(frames[i]) != itemsOf(frames[i - 1]) && produceOf(frames[i]) != produceOf(frames[i - 1])
        }

    @Tag("multi-jvm")
    @Test
    fun `edits on either JVM converge on the other`() {
        // every port is `0`: each peer binds its own and announces what it got, so
        // no test-side number is ever handed to a process that has yet to bind it
        // (computenet-dqy.25). A must announce its listening port before B can be
        // told to dial it, which is what orders these two launches.
        val peerA = JvmPeer.launch("civictech.demo.MainKt", "0", "--listen", "0")
        val httpA = peerA.port("http")
        val peerB = JvmPeer.launch("civictech.demo.MainKt", "0", "--peer", "ws://localhost:${peerA.port("ws")}")
        val httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB)) { up(httpA) && up(httpB) }

            post(httpA, user = "alice", action = "add", item = "apples")
            awaitUntil("apples visible on peer B") { "apples" in currentState(httpB) }

            post(httpB, user = "bob", action = "add", item = "bread")
            post(httpB, user = "bob", action = "vote", item = "apples")
            awaitUntil("bread visible on peer A") { "bread" in currentState(httpA) }
            awaitUntil("bob's vote counted on peer A") { "\"voteCount\":1" in currentState(httpA) }

            post(httpA, user = "alice", action = "remove", item = "apples")
            awaitUntil("removal visible on peer B") {
                // scope to the items array — the vote for apples legitimately remains
                "apples" !in items(httpB)
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }

    /**
     * D-UNION, criterion (d): the case the test above never exercises —
     * the **remover is not the adder**. Before the union-scoped observed
     * remove, `bob remove apples` tombstoned only bob's own (nonexistent)
     * add-tags: a silent no-op, on a button the UI offered to everyone.
     * It must now retract alice's add on both JVMs — and the writer-local
     * intent must still be reachable, and still writer-local.
     */
    @Tag("multi-jvm")
    @Test
    fun `a remove by a user who did not add the item converges on both JVMs`() {
        // every port is `0`: each peer binds its own and announces what it got, so
        // no test-side number is ever handed to a process that has yet to bind it
        // (computenet-dqy.25). A must announce its listening port before B can be
        // told to dial it, which is what orders these two launches.
        val peerA = JvmPeer.launch("civictech.demo.MainKt", "0", "--listen", "0")
        val httpA = peerA.port("http")
        val peerB = JvmPeer.launch("civictech.demo.MainKt", "0", "--peer", "ws://localhost:${peerA.port("ws")}")
        val httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB)) { up(httpA) && up(httpB) }

            post(httpA, user = "alice", action = "add", item = "apples")
            awaitUntil("apples visible on peer B") { "apples" in items(httpB) }

            // bob never added apples, and is on the other JVM
            post(httpB, user = "bob", action = "remove", item = "apples")
            awaitUntil("cross-user removal visible on peer B") { "apples" !in items(httpB) }
            awaitUntil("cross-user removal converged on peer A") { "apples" !in items(httpA) }

            // the distinct writer-local intent survives: "remove mine" of an
            // item bob never added retracts nothing
            post(httpA, user = "alice", action = "add", item = "bread")
            awaitUntil("bread visible on peer B") { "bread" in items(httpB) }
            post(httpB, user = "bob", action = "remove-mine", item = "bread")
            // a later op flowing end-to-end is the "everything before it has
            // been processed" marker — no sleep, no negative await
            post(httpB, user = "bob", action = "add", item = "dates")
            awaitUntil("a later edit has converged both ways") {
                "dates" in items(httpA) && "dates" in items(httpB)
            }
            check("bread" in items(httpA) && "bread" in items(httpB)) {
                "remove-mine must stay writer-local: bread was added by alice, not bob"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }

    /**
     * The two-JVM half of [KE2-33]/`[22-OBS-01]`: `computenet-sozzn.1` wave-aligns
     * `items`/`produce` behind one composite sink per JVM (`Main.kt`'s
     * `host.observeAligned`), and the same wiring runs on both peers — so an
     * op posted on one JVM must settle on the **other** JVM's SSE stream as
     * exactly one frame with both fields changed too, never a frame with
     * `produce` containing an element absent from `items`.
     *
     * Uses `collectSseFrames` (top-level `internal`, defined alongside
     * `AlignedFrameTest.kt` for exactly this reuse) and the
     * sentinel-frame attribution method from `AlignedFrameTest`: subscribe to
     * peer B's stream, perform the op(s) on peer A, then a sentinel `add`
     * outside `produce`'s `a..m` filter range (which changes `items` only) to
     * mark where the op's own frames end.
     */
    @Tag("multi-jvm")
    @Test
    fun `an edit on one JVM lands on the peer's stream as one aligned items-produce frame`() {
        // every port is `0`: each peer binds its own and announces what it got, so
        // no test-side number is ever handed to a process that has yet to bind it
        // (computenet-dqy.25). A must announce its listening port before B can be
        // told to dial it, which is what orders these two launches.
        val peerA = JvmPeer.launch("civictech.demo.MainKt", "0", "--listen", "0")
        val httpA = peerA.port("http")
        val peerB = JvmPeer.launch("civictech.demo.MainKt", "0", "--peer", "ws://localhost:${peerA.port("ws")}")
        val httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB)) { up(httpA) && up(httpB) }

            // Seed convergence with an out-of-range add first, so the frames
            // measured below are not catch-up traffic (catch-up installs as arm
            // state and is not a wave — AlignedObserve.kt "Catch-up is arm
            // state, not a wave").
            post(httpA, user = "alice", action = "add", item = "zzz-seed")
            awaitUntil("seed visible on peer B") { "zzz-seed" in items(httpB) }

            // --- add: posted on A, observed aligned on B's own SSE stream ---
            val addFrames = collectSseFrames(
                "http://localhost:$httpB/events",
                onSubscribed = {
                    post(httpA, user = "alice", action = "add", item = "apples")
                    post(httpA, user = "alice", action = "add", item = "zebra")
                },
            ) { "zebra" in itemsOf(it) }

            assertTrue(
                "apples" !in itemsOf(addFrames.first()),
                "initial frame on B already shows apples — subscription raced the op " +
                    "rather than the sink being wrong: ${addFrames.first()}",
            )
            assertProduceSubsetOfItems(addFrames)

            val addOpFrames = addFrames.subList(0, addFrames.size - 1)
            val addChanged = bothChangedIndices(addOpFrames)
            assertEquals(
                1, addChanged.size,
                "expected exactly one frame on peer B with both items and produce changed by " +
                    "the peer's `add apples`; frames=$addOpFrames",
            )
            assertTrue("apples" in itemsOf(addOpFrames[addChanged.single()]), "the changed frame never shows apples in items")
            assertTrue(
                "apples" in produceOf(addOpFrames[addChanged.single()]),
                "the changed frame never shows apples in produce",
            )

            // --- remove: posted on A, observed aligned on B's own SSE stream ---
            val removeFrames = collectSseFrames(
                "http://localhost:$httpB/events",
                onSubscribed = {
                    post(httpA, user = "alice", action = "remove", item = "apples")
                    post(httpA, user = "alice", action = "add", item = "yak")
                },
            ) { "yak" in itemsOf(it) }

            assertTrue(
                "apples" in itemsOf(removeFrames.first()),
                "initial frame on B already lacks apples — subscription raced the op " +
                    "rather than the sink being wrong: ${removeFrames.first()}",
            )
            assertProduceSubsetOfItems(removeFrames)

            val removeOpFrames = removeFrames.subList(0, removeFrames.size - 1)
            val removeChanged = bothChangedIndices(removeOpFrames)
            assertEquals(
                1, removeChanged.size,
                "expected exactly one frame on peer B with both items and produce changed by " +
                    "the peer's `remove apples`; frames=$removeOpFrames",
            )
            assertTrue(
                "apples" !in itemsOf(removeOpFrames[removeChanged.single()]),
                "the changed frame still shows apples in items",
            )
            assertTrue(
                "apples" !in produceOf(removeOpFrames[removeChanged.single()]),
                "the changed frame still shows apples in produce",
            )
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }
}
