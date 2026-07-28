package civictech.demo

import civictech.inspect.Node
import civictech.inspect.TopologySnapshot
import civictech.testkit.HttpProbe
import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
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
            // Correction to the naive framing: a peer's own --net-name is
            // never transmitted over the wire (Peers.kt) — `Peers.netOf`
            // derives the label locally, on the observing side, from the
            // bridge egress cell the mirrored ref routes through. So B's
            // mirrored cells do NOT carry "jvm-b"; they carry A's derived
            // "peer-<...>" label (Peers.PREFIX), exactly what
            // InspectorNetTest.kt pins for the in-process loopback stand-in.
            mirrored.forEach { node: Node ->
                node.net shouldStartWith "peer-"
                node.net shouldNotBe "jvm-a"
            }
        } finally {
            peerA.destroy()
            peerB.destroy()
        }
    }
}
