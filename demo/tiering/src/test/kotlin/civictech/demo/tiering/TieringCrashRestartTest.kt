package civictech.demo.tiering

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * E1.6 lifecycle acceptance, OS-process level (BS-16, plus [KE1-33]'s demo
 * half): two `demo/tiering` peers bridged over WebSocket, sharing one
 * replicated manual re-tier `OrMapCell<String, String>`; one is kill -9'd
 * mid-session and relaunched against the same `--journal` directory.
 *
 * Two properties are proved here, on the manual lane:
 *
 * - **[KE1-31] / BS-16 — replay without resurrection.** The restarted host
 *   re-derives its replica ref from [TierPipeline.MANUAL_ID] plus its peering
 *   role, so journal replay re-mints the *exact* dots the mesh already saw. A
 *   key it had put and then released before the crash stays released on both
 *   hosts afterwards, and a post-restore **re-pin of that same key** still
 *   converges — it did not draw a dot the mesh has already spent. Dot-exactness
 *   itself is kernel-tested at M10.1/M10.2; what this test asserts is its two
 *   observable consequences. The re-pin is deliberately of the released key
 *   rather than of a fresh one: see the comment at that assertion for why a
 *   fresh key cannot discriminate a restored counter from a reset one.
 * - **[KE1-33] demo half — partition and heal.** At the demo level the only
 *   partition primitive a real socket offers is connection death, so the dead
 *   bridge here *is* the crash: writes exist on both sides of it (`q` written
 *   at B before the kill, `p` written at A while B is down), and after the heal
 *   both boards must show identical membership and identical per-key tiers. The
 *   other two halves of [KE1-33] live elsewhere and are not restated here: the
 *   corpus scenario is `42-TMAP-REPL-01` (feature computenet-j2x.4) and the
 *   in-kernel partition/heal property is `UntagJoinConvergenceTest`.
 *
 * **Scope of `--journal` on this demo (computenet-3san).** The flag covers the
 * MANUAL lane only — `tier`/`pref` payloads are outside `WireCodec`'s
 * `polymorphic(Any)` scope, and every `graph { }`-spawned cell takes a fresh
 * random ref per process start, so nothing else here survives a restart. Hence
 * every write below is a `retier`, and an `items`/`signals` view that comes
 * back empty after the relaunch is that known bound rather than a defect.
 *
 * **[KE1-42].** Nothing in this test or in the demo prunes dots, tombstones or
 * dead sources; the assertions below are all monotone-set consequences.
 *
 * Idioms taken verbatim from `demo/shopping`'s `CrashRestartConvergenceTest`:
 * every port is `0` and announced by the process that bound it
 * (computenet-dqy.25), the relaunched peer gets a *fresh* HTTP port (hence
 * `var httpB`), 45s bounded waits, and a later op converging end-to-end is the
 * "everything before it has been processed" marker — never a sleep, never a
 * negative await.
 */
class TieringCrashRestartTest {

    private fun state(httpPort: Int): String {
        val connection = URI("http://localhost:$httpPort/state").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    /**
     * The converged manual map, verbatim: `{"item":"tier",…}`, keys sorted by
     * the app. It is read off the [UntagCell]'s *effective* state, so it is the
     * OR-map after dots and tombstones resolve rather than any per-host view of
     * the tags — which is what lets plain string equality stand as the
     * convergence assertion.
     */
    private fun manual(httpPort: Int): String =
        state(httpPort).substringAfter("\"manual\":").removeSuffix("}")

    /** The rendered board: per-tier membership plus each item's displayed score. */
    private fun board(httpPort: Int): String =
        state(httpPort).substringAfter("\"board\":").substringBefore(",\"signals\":")

    private fun pinned(httpPort: Int, item: String): Boolean = "\"$item\":" in manual(httpPort)

    /** `retier`; a tier of `none` releases the pin (`MapOps.remove`). */
    private fun retier(httpPort: Int, item: String, tier: String) {
        val connection = URI("http://localhost:$httpPort/op").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write("action=retier&item=$item&tier=$tier".toByteArray()) }
        check(connection.responseCode == 200) { "retier failed: ${connection.responseCode}" }
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
    fun `a kill -9'd tiering peer replays its journal, re-peers, and resurrects nothing`() {
        val journalB = Files.createTempDirectory("computenet-tiering-journal-b").toFile()
        val peerA = JvmPeer.launch("civictech.demo.tiering.TieringAppKt", "0", "--listen", "0")
        val httpA = peerA.port("http")
        val ws = peerA.port("ws")
        var peerB = JvmPeer.launch(
            "civictech.demo.tiering.TieringAppKt", "0", "--peer", "ws://localhost:$ws",
            "--journal", journalB.absolutePath,
        )
        var httpB = peerB.port("http")
        try {
            JvmPeer.await("both tiering peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // The pair whose replay must not resurrect: put and release of `x`,
            // both minted at B — the host about to die. B's replayed put re-mints
            // the very dot its replayed remove tombstones, so only the retained
            // tombstone keeps `x` out afterwards.
            retier(httpB, "x", "S")
            awaitUntil("x pinned on A", timeoutMs = 45_000) { pinned(httpA, "x") }
            retier(httpB, "x", "none")
            awaitUntil("x released on both", timeoutMs = 45_000) {
                !pinned(httpA, "x") && !pinned(httpB, "x")
            }

            // [KE1-33], B's side of the bridge: written at B before the kill.
            retier(httpB, "q", "A")
            awaitUntil("q converged to A", timeoutMs = 45_000) { pinned(httpA, "q") }

            peerB.kill()
            awaitUntil("peer B is gone", timeoutMs = 45_000) { down(httpB) }

            // [KE1-33], A's side of the dead bridge: written while B is down, so
            // these park at A until the heal.
            retier(httpA, "p", "D")
            retier(httpA, "y", "C")

            // heal: same journal directory, fresh HTTP port
            peerB = JvmPeer.launch(
                "civictech.demo.tiering.TieringAppKt", "0", "--peer", "ws://localhost:$ws",
                "--journal", journalB.absolutePath,
            )
            httpB = peerB.port("http")
            JvmPeer.await("peer B back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

            // B's own pre-crash pin is back, and A's parked edits reach it
            awaitUntil("B recovered its own pin and took A's parked edits", timeoutMs = 45_000) {
                pinned(httpB, "q") && pinned(httpB, "p") && pinned(httpB, "y")
            }

            // the session is fully live again in both directions
            retier(httpB, "z", "S")
            awaitUntil("post-restore write converged to A", timeoutMs = 45_000) { pinned(httpA, "z") }

            // [KE1-31], the no-resurrection half: with everything else converged
            // in both directions, the released key is still released.
            check(!pinned(httpA, "x") && !pinned(httpB, "x")) {
                "a released key came back after journal replay: A=${manual(httpA)} B=${manual(httpB)}"
            }
            // [KE1-33] demo half: identical membership and identical per-key
            // tiers on both sides of the healed bridge.
            check(manual(httpA) == manual(httpB)) {
                "manual maps diverged after heal: A=${manual(httpA)} B=${manual(httpB)}"
            }
            check(board(httpA) == board(httpB)) {
                "boards diverged after heal: A=${board(httpA)} B=${board(httpB)}"
            }

            // [KE1-31], the spent-dot half — and it must be a re-put of the
            // RELEASED key, not of a fresh one. Dot novelty in `OrMapCell` is
            // per (key, dot): a re-minted dot arriving under a key that never
            // held it is novel there and lands regardless, so a post-restore
            // write to a *new* key converges whether or not the counter was
            // restored, and asserting on one proves nothing about replay. `x`
            // is the key whose dot `(B, 1)` A tombstoned, so a B that came back
            // with a reset counter re-mints exactly that dot, A discards it as
            // already-removed, and this pin never appears there. Measured: with
            // `--journal` dropped from the relaunch above, this is the
            // assertion — and the only one — that fails.
            retier(httpB, "x", "B")
            awaitUntil("a post-restore re-pin of the released key converged to A", timeoutMs = 45_000) {
                pinned(httpA, "x")
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }

    /**
     * The journal's own half of BS-16, isolated — and the reason it needs its
     * own test.
     *
     * **Measured on this branch:** re-running the test above with `--journal`
     * dropped from B's relaunch changes *nothing*. Every assertion still
     * passes, the released key still stays released, and the post-restore
     * re-pin still converges. A surviving peer subsumes the journal: B's
     * catch-up baseline from A carries the whole OR-map — B's own pre-crash
     * dots included, tombstones included — and the RESTART re-baseline settles
     * what A retains of B's superseded source. So while A is alive, **no**
     * observable at the demo level can tell journal replay from catch-up, and
     * an assertion in the test above claiming to prove replay would be vacuous.
     *
     * What discriminates is removing the peer as a state source: both hosts die,
     * A comes back **empty** (no `--journal`), and B comes back against its own
     * journal. Everything the mesh then holds came out of B's journal or out of
     * nothing. Its pre-crash pin must be back, the key it released must still be
     * released — replay re-mints the same dots and its replayed release covers
     * them, `[KE1-31]` — and A must converge to exactly that.
     *
     * A relaunches *first*, and as the listener, because
     * [TierPipeline.manualInstance] derives the replica instance id from the
     * peering role: B must come back a **dialer** or it would re-derive a
     * different ref and its journal records — which name the ref they were
     * written against — would replay into nothing.
     */
    @Tag("multi-jvm")
    @Test
    fun `a relaunched peer recovers its own pins from the journal when no peer can supply them`() {
        val journalB = Files.createTempDirectory("computenet-tiering-journal-only").toFile()
        var peerA = JvmPeer.launch("civictech.demo.tiering.TieringAppKt", "0", "--listen", "0")
        var httpA = peerA.port("http")
        var ws = peerA.port("ws")
        var peerB = JvmPeer.launch(
            "civictech.demo.tiering.TieringAppKt", "0", "--peer", "ws://localhost:$ws",
            "--journal", journalB.absolutePath,
        )
        var httpB = peerB.port("http")
        try {
            JvmPeer.await("both tiering peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // everything written at B, so the journal is the only record of it
            retier(httpB, "kept", "A")
            retier(httpB, "released", "S")
            awaitUntil("both pins converged to A", timeoutMs = 45_000) {
                pinned(httpA, "kept") && pinned(httpA, "released")
            }
            retier(httpB, "released", "none")
            awaitUntil("the release converged", timeoutMs = 45_000) {
                !pinned(httpA, "released") && !pinned(httpB, "released")
            }

            // the whole mesh dies
            peerA.kill()
            peerB.kill()
            awaitUntil("both peers are gone", timeoutMs = 45_000) { down(httpA) && down(httpB) }

            // A comes back EMPTY — no journal, nothing to catch anyone up with
            peerA = JvmPeer.launch("civictech.demo.tiering.TieringAppKt", "0", "--listen", "0")
            httpA = peerA.port("http")
            ws = peerA.port("ws")
            JvmPeer.await("empty listener back up", listOf(peerA), timeoutMs = 45_000) { up(httpA) }
            check(manual(httpA) == "{}") { "the relaunched listener was meant to be empty: ${manual(httpA)}" }

            // B comes back against its journal, as a dialer — the same role, so
            // the same ref, so the same dot source
            peerB = JvmPeer.launch(
                "civictech.demo.tiering.TieringAppKt", "0", "--peer", "ws://localhost:$ws",
                "--journal", journalB.absolutePath,
            )
            httpB = peerB.port("http")
            JvmPeer.await("journal-restored peer back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

            // replay restored B's own pin, and its replayed release still covers
            // the dot its replayed put re-minted ([KE1-31])
            awaitUntil("journal-recovered pin visible on B", timeoutMs = 45_000) { pinned(httpB, "kept") }
            check(!pinned(httpB, "released")) {
                "journal replay resurrected a released key: B=${manual(httpB)}"
            }

            // and the empty peer converges to exactly the replayed state
            awaitUntil("the empty peer took the replayed state", timeoutMs = 45_000) { pinned(httpA, "kept") }
            check(!pinned(httpA, "released")) {
                "a released key reached the empty peer: A=${manual(httpA)}"
            }
            check(manual(httpA) == manual(httpB)) {
                "the two hosts diverged after journal-only recovery: A=${manual(httpA)} B=${manual(httpB)}"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }
}
