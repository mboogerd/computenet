package civictech.demo.tiering

import civictech.testkit.HttpProbe
import civictech.testkit.JvmPeer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.assertTrue

/**
 * Two-JVM acceptance tests for the tiering demo's replicated manual re-tier
 * lane (feature computenet-j2x.5, task .2): BS-14 (concurrent same-item
 * re-tier converges to the DOT_ORDER winner, [KE1-28]/[KE1-29]) and BS-15
 * (concurrent remove loses to concurrent re-tier, [KE1-30]).
 *
 * Template: `demo/shopping`'s `TwoJvmConvergenceTest` — `JvmPeer.launch`,
 * `--listen 0` / `--peer ws://...`, ports read back via `Peer.port`, no
 * sleep, no test-picked port (computenet-dqy.25).
 *
 * **Only the `manual` OR-map replicates across the two hosts** — unlike
 * `demo/shopping`, `TieringApp`'s `items`/`vals`/`prefs` cells are plain
 * per-host graph-DSL cells with no cross-host chaining wired in wire mode
 * (`TieringApp.kt`'s `if (wire != null)` block only builds the peering
 * bridge; see `TieringServerTest`'s "wire mode replicates a manual re-tier to
 * the peer" test, which seeds `item` independently on both probes for exactly
 * this reason). So these tests never rely on `items` propagating, and the
 * idle marker below is itself a `retier` on a private key — the same lane the
 * assertions are about — rather than an `item` post.
 */
class TwoJvmTieringConvergenceTest {

    private fun up(httpPort: Int): Boolean = runCatching {
        (URI("http://localhost:$httpPort/").toURL().openConnection() as HttpURLConnection)
            .apply { connectTimeout = 500; readTimeout = 500 }
            .responseCode == 200
    }.getOrDefault(false)

    /**
     * Launch a peered pair of `TieringApp` JVMs and wait until both serve
     * HTTP. Every port is `0`: each peer binds its own and announces what it
     * got, so no test-side number is ever handed to a process that has yet to
     * bind it (computenet-dqy.25) — A must announce its listening port before
     * B can be told to dial it, which is what orders these two launches.
     */
    private fun launchPair(): Pair<JvmPeer.Peer, JvmPeer.Peer> {
        val peerA = JvmPeer.launch("civictech.demo.tiering.TieringAppKt", "0", "--listen", "0")
        val peerB = JvmPeer.launch(
            "civictech.demo.tiering.TieringAppKt", "0",
            "--peer", "ws://localhost:${peerA.port("ws")}",
        )
        JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB)) {
            up(peerA.port("http")) && up(peerB.port("http"))
        }
        return peerA to peerB
    }

    /**
     * Fire both writes on separate threads and wait for both HTTP responses,
     * so the two ops race onto the wire rather than one completing before the
     * other is even issued — "post both before awaiting either".
     */
    private fun concurrently(a: () -> Unit, b: () -> Unit) {
        val ta = Thread(a)
        val tb = Thread(b)
        ta.start(); tb.start()
        ta.join(); tb.join()
    }

    /**
     * The no-sleep idle marker (the `dates` idiom in `TwoJvmConvergenceTest`),
     * doubled because BS-14/BS-15 write concurrently from BOTH sides and the
     * only replicated lane is the manual OR-map: a fresh `retier` on a
     * private per-direction key from each host, awaited on the OTHER host.
     * Once both markers are visible everywhere, every write issued before
     * them on this same lane has necessarily been applied on both replicas —
     * gossip over one `:wire` connection is ordered, so a later write cannot
     * overtake an earlier one.
     */
    private fun awaitIdleBothWays(probeA: HttpProbe, probeB: HttpProbe, markerFromA: String, markerFromB: String) {
        probeA.post("action=retier&item=$markerFromA&tier=A")
        probeB.post("action=retier&item=$markerFromB&tier=A")
        probeA.await { """"$markerFromB":"A"""" in it }
        probeB.await { """"$markerFromA":"A"""" in it }
    }

    @Tag("multi-jvm")
    @Test
    fun `BS-14 concurrent same-item re-tier converges to the DOT_ORDER winner`() {
        val (peerA, peerB) = launchPair()
        try {
            HttpProbe("http://localhost:${peerA.port("http")}").use { probeA ->
                HttpProbe("http://localhost:${peerB.port("http")}").use { probeB ->
                    // concurrent same-item re-tier to DIFFERENT tiers — both posts fire
                    // before either is awaited
                    concurrently(
                        { probeA.post("action=retier&item=widget&tier=S") },
                        { probeB.post("action=retier&item=widget&tier=F") },
                    )

                    awaitIdleBothWays(probeA, probeB, "marker-14-a", "marker-14-b")

                    val stateA = probeA.state()
                    val stateB = probeB.state()

                    val sOnA = """"widget":"S"""" in stateA
                    val fOnA = """"widget":"F"""" in stateA
                    val sOnB = """"widget":"S"""" in stateB
                    val fOnB = """"widget":"F"""" in stateB

                    assertTrue(
                        sOnA != fOnA,
                        "widget must show exactly one tier on peer A, never both and never neither: $stateA",
                    )
                    assertTrue(
                        sOnB != fOnB,
                        "widget must show exactly one tier on peer B, never both and never neither: $stateB",
                    )
                    // agreement, not a specific winner — which tier wins is DOT_ORDER's
                    // business, not a scheduling assumption this test may encode
                    assertTrue(
                        (sOnA && sOnB) || (fOnA && fOnB),
                        "both hosts must converge on the SAME DOT_ORDER winner: A=$stateA B=$stateB",
                    )
                }
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }

    @Tag("multi-jvm")
    @Test
    fun `BS-15 concurrent remove loses to concurrent re-tier`() {
        val (peerA, peerB) = launchPair()
        try {
            HttpProbe("http://localhost:${peerA.port("http")}").use { probeA ->
                HttpProbe("http://localhost:${peerB.port("http")}").use { probeB ->
                    // gadget manually tiered on both hosts, and converged, before the race
                    probeA.post("action=retier&item=gadget&tier=B")
                    probeA.await { """"gadget":"B"""" in it }
                    probeB.await { """"gadget":"B"""" in it }

                    // A removes the pin it observed (only d1, gadget=B); B concurrently
                    // re-tiers to a NEW value — B's dot was never observed by A's remove
                    // (observed-remove semantics, [24-TMAP-04] reset-remove)
                    concurrently(
                        { probeA.post("action=retier&item=gadget&tier=none") },
                        { probeB.post("action=retier&item=gadget&tier=D") },
                    )

                    awaitIdleBothWays(probeA, probeB, "marker-15-a", "marker-15-b")

                    val stateA = probeA.state()
                    val stateB = probeB.state()
                    assertTrue(
                        """"gadget":"D"""" in stateA,
                        "the concurrent put's dot must survive A's remove-vs-put race: $stateA",
                    )
                    assertTrue(
                        """"gadget":"D"""" in stateB,
                        "the concurrent put's dot must survive on B too: $stateB",
                    )
                }
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }
}
