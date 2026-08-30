package civictech.demo

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * M10 exit, OS-process level: two demo peers over WebSocket, one kill -9'd
 * mid-session and relaunched — the "survive a restart" criterion, live.
 *
 * Two tests, and the split between them is the point (computenet-o0nh):
 *
 * - [a kill -9'd peer re-peers with the survivor and both sides converge]
 *   keeps the survivor alive across the restart. It is evidence for the
 *   reconnect/re-hello path, for parked edits replaying out of the survivor,
 *   and for bidirectional convergence afterwards. It is **not** evidence for
 *   journal replay, and this file no longer claims that it is: while A is
 *   alive, A's catch-up baseline carries the entire converged OR-map — B's
 *   own pre-crash dots and tombstones included — so no observable at the demo
 *   level can tell journal replay from reconnect-and-catch-up. Measured, not
 *   reasoned: with `--journal` dropped from B's relaunch every assertion in
 *   that test still passes (computenet-emtx, at dcb2defa2).
 * - [a relaunched peer rebuilds the list from its own journal when no peer can
 *   supply it] is the discriminating one: the whole mesh dies, the listener
 *   comes back **empty**, and everything the mesh then holds came out of the
 *   dialer's journal or out of nothing. Dropping its `--journal` fails it.
 *
 * The same split, for the same reason, is documented in `demo/tiering`'s
 * [civictech.demo.tiering.TieringCrashRestartTest].
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
    fun `a kill -9'd peer re-peers with the survivor and both sides converge`() {
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

            // B relaunches with the SAME journal directory. The journal is
            // passed because that is what the demo's restart story is, but this
            // test cannot see it work: A is alive, so every assertion below is
            // also satisfiable out of A's catch-up baseline alone. The test
            // below removes A as a state source; that one is the journal test.
            peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            httpB = peerB.port("http")
            JvmPeer.await("peer B back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

            // B's pre-crash state is visible on B again — `bread` was written
            // AT B and `apples` arrived over the wire before the crash. Both
            // are records in B's journal AND entries in A's baseline, so this
            // asserts they came back, not which of the two supplied them.
            awaitUntil("pre-crash state visible on B again", timeoutMs = 45_000) {
                "bread" in items(httpB) && "apples" in items(httpB)
            }
            // the edit made while B was down replays out of A's park
            awaitUntil("parked edit replayed to B", timeoutMs = 45_000) { "cheese" in items(httpB) }

            // and the session is fully live again, both directions
            post(httpB, user = "bob", action = "add", item = "dates")
            awaitUntil("post-restart edit visible on A", timeoutMs = 45_000) { "dates" in items(httpA) }

            // D-UNION criterion (c): with everything else converged in both
            // directions, the observed-removed item is still gone — the
            // restarted peer reproduced the post-remove membership rather than
            // resurrecting it out of re-minted add-tags. Which mechanism did
            // that (replay, or A's retained tombstone reaching B on catch-up)
            // this test does not distinguish; the journal-only test below does.
            check("olives" !in items(httpB) && "olives" !in items(httpA)) {
                "an observed-removed item came back after the restart: " +
                    "A=${items(httpA)} B=${items(httpB)}"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }

    /**
     * The journal's own half, isolated — and the reason it needs its own test.
     *
     * The test above keeps the survivor alive, and with A alive **no** demo-level
     * observable can tell journal replay from catch-up: A's baseline carries the
     * whole converged OR-map, B's own pre-crash dots and tombstones included.
     * Measured — `--journal` dropped from that test's relaunch changes nothing,
     * every assertion still passes (computenet-emtx).
     *
     * What discriminates is removing the peer as a state source: both hosts die,
     * A comes back **empty** (no `--journal`), and B comes back against its own
     * journal. Everything the mesh then holds came out of B's journal or out of
     * nothing.
     *
     * So every write here is made at **B**, by two different users, and includes
     * the D-UNION criterion (c) shape the test above can only assert loosely:
     * `alice` adds `olives`, `bob` removes it at B's union. The del is minted at
     * the union — alice's writer has no tombstone for it — and alice's replayed
     * add re-mints the very tag it covers, so only the replayed remove's retained
     * tombstones keep it out. With A empty there is no other tombstone anywhere.
     *
     * A relaunches *first*, and as the **listener**, because both the writer
     * family namespace (`demo-writer@$myRole`) and the union refs
     * (`unionRef(name, role)`) are derived from the peering role: B must come
     * back a **dialer** or its journal records — which name the refs they were
     * written against — would replay into a different graph.
     */
    @Tag("multi-jvm")
    @Test
    fun `a relaunched peer rebuilds the list from its own journal when no peer can supply it`() {
        val journalB = Files.createTempDirectory("computenet-journal-only-b").toFile()
        var peerA = launch("0", "--listen", "0")
        var httpA = peerA.port("http")
        var ws = peerA.port("ws")
        var peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
        var httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // everything is written at B, so B's journal is the only record of it
            post(httpB, user = "bob", action = "add", item = "bread")
            post(httpB, user = "alice", action = "add", item = "olives")
            awaitUntil("pre-crash convergence to A", timeoutMs = 45_000) {
                "bread" in items(httpA) && "olives" in items(httpA)
            }
            // D-UNION criterion (c), both sides of it at B: a union-scoped
            // observed remove by one user of another user's add
            post(httpB, user = "bob", action = "remove", item = "olives")
            awaitUntil("pre-crash cross-user removal converged", timeoutMs = 45_000) {
                "olives" !in items(httpA) && "olives" !in items(httpB)
            }

            // the whole mesh dies
            peerA.kill()
            peerB.kill()
            awaitUntil("both peers are gone", timeoutMs = 45_000) { down(httpA) && down(httpB) }

            // A comes back EMPTY — no journal, nothing to catch anyone up with
            peerA = launch("0", "--listen", "0")
            httpA = peerA.port("http")
            ws = peerA.port("ws")
            JvmPeer.await("empty listener back up", listOf(peerA), timeoutMs = 45_000) { up(httpA) }
            check("bread" !in items(httpA) && "olives" !in items(httpA)) {
                "the relaunched listener was meant to be empty: ${items(httpA)}"
            }

            // B comes back against its journal, as a dialer — the same role, so
            // the same writer namespace, the same union refs, the same dots
            peerB = launch("0", "--peer", "ws://localhost:$ws", "--journal", journalB.absolutePath)
            httpB = peerB.port("http")
            JvmPeer.await("journal-restored peer back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

            // replay rebuilt B's list, and its replayed union-scoped remove
            // still covers the tag its replayed add re-minted
            awaitUntil("journal-recovered item visible on B", timeoutMs = 45_000) {
                "bread" in items(httpB)
            }
            check("olives" !in items(httpB)) {
                "journal replay resurrected an observed-removed item: B=${items(httpB)}"
            }

            // and the empty peer converges to exactly the replayed state
            awaitUntil("the empty peer took the replayed state", timeoutMs = 45_000) {
                "bread" in items(httpA)
            }
            check("olives" !in items(httpA)) {
                "an observed-removed item reached the empty peer: A=${items(httpA)}"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }
}
