package civictech.demo.tiering

import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * E1.6 lifecycle acceptance, OS-process level (BS-17, [KE1-32]): a host that
 * joins the mesh **after** a key was put and released must never show that key,
 * and must re-emit nothing that resurrects it elsewhere ([KE1-38]'s demo
 * shadow).
 *
 * ## Which form of BS-17 this is, and why
 *
 * The bead's preferred form was a **three-JVM star** — A listens, B and C both
 * dial A, C joining late. It is not available through shipped wiring, measured
 * on this branch with a three-launch smoke: `TierPipeline.manualInstance` maps
 * a *peering role* to an instance id, and there are only two roles, so B and C
 * both mint their replica of [TierPipeline.MANUAL_ID] at instance `1` and land
 * on the **same** `CellRef`. The probe's own output showed all three processes
 * announcing the shared logical id with instances `0`, `1`, `1`; a pin written
 * at A then reached C and never reached B, and the run failed at
 * `timed out awaiting: x reaches B and C`. A third star point needs a
 * per-process instance identity the role derivation cannot supply — that is a
 * change to the app's identity scheme, filed to feature `computenet-j2x.6`,
 * not something a test may improvise.
 *
 * So this is the decided two-JVM fallback: the late joiner is B, launched only
 * after A has already put **and** released `x`. B's catch-up is a
 * delta-from-empty and must carry the tombstone, not a live dot
 * (`[24-CATCHUP-01]`).
 *
 * `m` and `n` are the non-vacuousness markers of the two directions. `m` is
 * pinned at A before B exists, so B seeing it proves the catch-up actually
 * delivered A's state rather than B merely starting empty — without it, "B does
 * not show x" would be true of a peer that received nothing at all. `n` is
 * written at B after the join and awaited at A: a later op converging
 * end-to-end is the marker that everything before it has been processed, so the
 * final `x`-is-absent check is not a negative await.
 *
 * **[KE1-42]:** nothing here prunes dots, tombstones or dead sources.
 *
 * Port discipline is `demo/shopping`'s `TwoJvmConvergenceTest`: every port is
 * `0`, bound and announced by the process that owns it (computenet-dqy.25).
 */
class TieringLateJoinerTest {

    private fun state(httpPort: Int): String {
        val connection = URI("http://localhost:$httpPort/state").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    /** The converged manual map, verbatim: `{"item":"tier",…}`, keys sorted by the app. */
    private fun manual(httpPort: Int): String =
        state(httpPort).substringAfter("\"manual\":").removeSuffix("}")

    private fun pinned(httpPort: Int, item: String): Boolean = "\"$item\":" in manual(httpPort)

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

    @Tag("multi-jvm")
    @Test
    fun `a late joiner is seeded with tombstones and never shows a released key`() {
        val peerA = JvmPeer.launch("civictech.demo.tiering.TieringAppKt", "0", "--listen", "0")
        val httpA = peerA.port("http")
        val ws = peerA.port("ws")
        var peerB: JvmPeer.Peer? = null
        try {
            JvmPeer.await("peer A serving HTTP", listOf(peerA), timeoutMs = 45_000) { up(httpA) }

            // put and release `x`, entirely before the late joiner exists
            retier(httpA, "x", "S")
            awaitUntil("x pinned on A", timeoutMs = 45_000) { pinned(httpA, "x") }
            retier(httpA, "x", "none")
            awaitUntil("x released on A", timeoutMs = 45_000) { !pinned(httpA, "x") }

            // the live marker the catch-up must carry
            retier(httpA, "m", "B")
            awaitUntil("m pinned on A", timeoutMs = 45_000) { pinned(httpA, "m") }

            // B joins late
            val late = JvmPeer.launch("civictech.demo.tiering.TieringAppKt", "0", "--peer", "ws://localhost:$ws")
            peerB = late
            val httpB = late.port("http")
            JvmPeer.await("late joiner serving HTTP", listOf(late), timeoutMs = 45_000) { up(httpB) }

            // the catch-up delivered A's live state …
            awaitUntil("catch-up delivered A's live pin to the late joiner", timeoutMs = 45_000) {
                pinned(httpB, "m")
            }
            // … and the released key was not among it ([KE1-32])
            check(!pinned(httpB, "x")) {
                "the late joiner's catch-up resurrected a released key: B=${manual(httpB)}"
            }

            // a write from the late joiner converges, and carries no resurrection
            // with it ([KE1-38]'s demo shadow)
            retier(httpB, "n", "D")
            awaitUntil("the late joiner's own write converged to A", timeoutMs = 45_000) { pinned(httpA, "n") }
            check(!pinned(httpA, "x") && !pinned(httpB, "x")) {
                "a released key came back after the late joiner wrote: A=${manual(httpA)} B=${manual(httpB)}"
            }
            check(manual(httpA) == manual(httpB)) {
                "manual maps diverged after the late join: A=${manual(httpA)} B=${manual(httpB)}"
            }
        } finally {
            JvmPeer.destroy(listOfNotNull(peerA, peerB))
        }
    }
}
