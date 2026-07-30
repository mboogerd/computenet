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
            // out-of-process).
            awaitUntil("peer A's inspector has adopted a cell mirrored from B") {
                runCatching { topology(inspectA).nodes.any { it.host == null } }.getOrDefault(false)
            }

            val snapshot = topology(inspectA)

            val own = snapshot.nodes.filter { it.host == "shopping" }
            own.isEmpty() shouldBe false
            own.forEach { it.net shouldBe "jvm-a" }

            val mirrored = snapshot.nodes.filter { it.host == null }
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
            fun mirroredOnA(): List<Node> =
                runCatching { topology(inspectA).nodes.filter { it.host == null } }.getOrDefault(emptyList())

            awaitUntil("peer A adopted B's cells") { mirroredOnA().isNotEmpty() }
            mirroredOnA().forEach { it.net shouldBe "jvm-b" }

            // B dies; A's listener session closes and unpublishes everything it
            // learned through that socket
            JvmPeer.destroy(peerB)
            awaitUntil("peer A retracted B's cells") { mirroredOnA().isEmpty() }

            // B returns, dials the same listener, and re-announces the same
            // refs through a *different* listener-side egress
            peerB = JvmPeer.launch("civictech.demo.MainKt", *bArgs)
            awaitUntil("peer A re-adopted B's cells") { mirroredOnA().isNotEmpty() }

            val afterReconnect = mirroredOnA()
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
