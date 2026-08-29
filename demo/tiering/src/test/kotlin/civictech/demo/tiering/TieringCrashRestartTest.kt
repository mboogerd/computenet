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
 *   hosts afterwards, and a **fresh** write made after the restore still
 *   converges — it did not draw a dot the mesh has already spent. Dot-exactness
 *   itself is kernel-tested at M10.1/M10.2; what this test asserts is its two
 *   observable consequences.
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

            // [KE1-31], the spent-dot half: a FRESH write at the restored host
            // converges. Without journal replay B's dot counter would restart and
            // this put would carry a dot the mesh already tombstoned, so A would
            // swallow it — which is exactly what was observed when this test was
            // run once with `--journal` omitted from the relaunch.
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
        } finally {
            JvmPeer.destroy(peerA, peerB)
            journalB.deleteRecursively()
        }
    }
}
