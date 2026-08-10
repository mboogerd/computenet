package civictech.demo

import civictech.inspect.Node
import civictech.inspect.TopologySnapshot
import civictech.testkit.HttpProbe
import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * T22 — the real cross-socket counterpart to `InspectorNetTest`, which peers
 * two `LocationRegistry`s in one JVM via `Peering.loopback` and says so in its
 * own KDoc: that is a stand-in for the actual `:wire` path, never itself
 * checked. This test launches two real, separate JVMs of the M5-NET pilot
 * (`Main.kt`'s `--listen`/`--peer` peering, unchanged by this ticket) each
 * with its own `--inspect-port`/`--net-name`, and asserts peer A's inspector
 * reports both its own cells and B's mirrored ones with the placement the
 * contract promises (`Dto.kt`'s `Node.host`/`Node.net`, `Peers.kt`'s label
 * derivation).
 */
@Tag("multi-jvm")
class TwoJvmInspectorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun topology(inspectPort: Int): TopologySnapshot =
        json.decodeFromString(HttpProbe("http://localhost:$inspectPort").state("/api/inspect/topology"))

    /**
     * Poll an inspector's topology until [predicate] holds, and return **that**
     * snapshot — the one the wait was satisfied by.
     *
     * Two disciplines, both of which this test used to get wrong, and both of
     * which matter precisely because it perturbs a peer mid-flight:
     *
     * - **Await and assert on one observation.** Awaiting a condition and then
     *   re-fetching the topology to assert on it is a check-then-act race: none
     *   of the properties here is monotonic (A's view of B's cells legitimately
     *   grows during an announcement burst, and empties again when the socket
     *   drops), so the second read can be a different world than the one that
     *   satisfied the wait.
     * - **An unanswered probe is "not yet", never "condition met".** The old
     *   `runCatching { … }.getOrDefault(emptyList())` made a failed request
     *   indistinguishable from an empty topology — which silently *satisfies*
     *   the retraction barrier, exactly when A's inspector is most likely to
     *   hiccup (it is tearing down a peer session while being polled every
     *   100ms). The same silent-pass class `HttpProbe.await` fixed in T12: a
     *   request that did not answer is not evidence of anything, so the bounded
     *   wait keeps polling and, failing that, times out naming what it wanted.
     */
    private fun awaitTopology(
        inspectPort: Int,
        what: String,
        predicate: (TopologySnapshot) -> Boolean,
    ): TopologySnapshot {
        var satisfied: TopologySnapshot? = null
        awaitUntil(what) {
            val snapshot = runCatching { topology(inspectPort) }.getOrNull()
            (snapshot != null && predicate(snapshot)).also { if (it) satisfied = snapshot }
        }
        return satisfied!!
    }

    /** The peer-announced (mirrored) nodes in [snapshot]: no process host (`Dto.kt`'s `Node.host`). */
    private fun mirrored(snapshot: TopologySnapshot): List<Node> = snapshot.nodes.filter { it.host == null }

    @Test
    fun `peer A's inspector places its own cells and B's mirrored cells correctly`() {
        val httpA = JvmPeer.freePort()
        val httpB = JvmPeer.freePort()
        val ws = JvmPeer.freePort()
        val inspectA = JvmPeer.freePort()
        val inspectB = JvmPeer.freePort()

        val peerA = JvmPeer.launch(
            "civictech.demo.MainKt", "$httpA", "--listen", "$ws",
            "--inspect-port", "$inspectA", "--net-name", "jvm-a",
        )
        val peerB = JvmPeer.launch(
            "civictech.demo.MainKt", "$httpB", "--peer", "ws://localhost:$ws",
            "--inspect-port", "$inspectB", "--net-name", "jvm-b",
        )
        try {
            // A has adopted at least one of B's announced cells once a node
            // with no process host shows up in A's topology — there is no
            // `knowsNow` to call from outside the process, so poll the HTTP
            // response itself (mirrors InspectorNetTest.awaitNode's intent,
            // out-of-process). The snapshot every assertion below reads is the
            // one that satisfied the wait, not a later re-fetch (see
            // [awaitTopology]).
            val snapshot = awaitTopology(inspectA, "peer A's inspector has adopted a cell mirrored from B") {
                it.nodes.any { node -> node.host == null }
            }

            val own = snapshot.nodes.filter { it.host == "shopping" }
            own.isEmpty() shouldBe false
            own.forEach { it.net shouldBe "jvm-a" }

            val mirrored = mirrored(snapshot)
            mirrored.isEmpty() shouldBe false
            // V4-PEERID: B's own --net-name DOES now cross the wire — as the
            // `PeerId` in B's transport hello, which B's announcements' mirror
            // records on every location it installs (`Peers.netOf` prefers it
            // over the derived label). So A shows B's cells under "jvm-b",
            // which is what makes two side-by-side inspectors legible.
            //
            // This corrects the previous framing here, which said a peer's
            // name was never transmitted and pinned A's locally derived
            // "peer-<...>" label. That label is still what an *anonymous* peer
            // gets — `InspectorNetTest` keeps pinning it for a peering whose
            // sides are unnamed.
            mirrored.forEach { node: Node ->
                node.net shouldBe "jvm-b"
                node.net shouldNotBe "jvm-a"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }

    /**
     * V4-PEERID's acceptance case, over two real JVMs: the listener (A) stays
     * up while the dialer (B) is killed and relaunched. A's listener builds a
     * brand-new `WsTransport.Session` for the returning B — hence a new
     * `BridgeEgressCell`, hence a new derived label — so before this ticket B's
     * hull was renamed by exactly this sequence (observed live:
     * `peer-0ae324f9` → `peer-804f5917`). B re-asserts the same `--net-name` in
     * its re-hello, so the name is what survives.
     */
    @Test
    fun `peer A keeps B's network host across B being killed and relaunched`() {
        val httpA = JvmPeer.freePort()
        val httpB = JvmPeer.freePort()
        val ws = JvmPeer.freePort()
        val inspectA = JvmPeer.freePort()
        val inspectB = JvmPeer.freePort()

        val peerA = JvmPeer.launch(
            "civictech.demo.MainKt", "$httpA", "--listen", "$ws",
            "--inspect-port", "$inspectA", "--net-name", "jvm-a",
        )
        val bArgs = arrayOf(
            "$httpB", "--peer", "ws://localhost:$ws",
            "--inspect-port", "$inspectB", "--net-name", "jvm-b",
        )
        var peerB = JvmPeer.launch("civictech.demo.MainKt", *bArgs)
        try {
            fun awaitMirroredOnA(what: String, predicate: (List<Node>) -> Boolean): List<Node> =
                mirrored(awaitTopology(inspectA, what) { predicate(mirrored(it)) })

            val adopted = awaitMirroredOnA("peer A adopted B's cells") { it.isNotEmpty() }
            adopted.forEach { it.net shouldBe "jvm-b" }

            // B dies mid-burst — the adoption barrier above is satisfied by the
            // FIRST of B's announced refs, so the rest of the burst is still in
            // flight on A's bridge host when the socket dies. A's listener
            // session closes and must retract everything it learned through
            // that socket, including whatever lands after the close
            // (`RegistryMirrorCell.detach`: without that fence a late-applied
            // announcement re-installed B's locations behind the dead egress
            // and this barrier could never come true).
            JvmPeer.destroy(peerB)
            awaitMirroredOnA("peer A retracted B's cells") { it.isEmpty() }

            // B returns, dials the same listener, and re-announces the same
            // refs through a *different* listener-side egress
            peerB = JvmPeer.launch("civictech.demo.MainKt", *bArgs)
            val afterReconnect = awaitMirroredOnA("peer A re-adopted B's cells") { it.isNotEmpty() }
            afterReconnect.isEmpty() shouldBe false
            afterReconnect.forEach { node: Node ->
                // the assertion the whole ticket exists for
                node.net shouldBe "jvm-b"
            }
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }
}
