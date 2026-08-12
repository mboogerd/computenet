package civictech.demo

import civictech.inspect.Node
import civictech.inspect.TopologySnapshot
import civictech.testkit.HttpProbe
import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * V4-PILOT — the first genuine **same-logical-id replicated** graph across a
 * real socket in this repository.
 *
 * Everything replication has ever been proved over here is `Peering.loopback`
 * under a `SimulationController` (`ReplicationTest`, `ReplicatedSessionTest`,
 * `ExchangeCompositionExitTest`); `demo/shopping`'s own peering is *role-
 * distinct counterparts* (`unionRef(name, role)` — two different logical ids
 * chained into each other), not replicas. This test launches two real JVMs of
 * `demo/shopping` with `--replicate`, so both mint `CellRef(SHARED_ID, n)` for
 * one shared logical id, and drives:
 *
 * 1. bidirectional convergence over the real `:wire` WebSocket —
 *    `doc/spec/40-distribution/42-replication.md` **[42-REPL-04]**;
 * 2. one disconnect/reconnect cycle, with a write parking at the survivor;
 * 3. what each side's *inspector* says about the other side's replica.
 *
 * The graph ids are **captured, never asserted**: whether the min-uuid
 * component heuristic (`inspect/.../Graphs.kt:22-36`, `:179-180`) gives one id
 * or two across a peer boundary is the measurement this ticket exists to take
 * (MRB-156), and an assertion written from a guess would freeze an undecided
 * behaviour into a regression test.
 */
@Tag("multi-jvm")
class TwoJvmReplicaPilotTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Where the captured evidence bodies land, for the runbook's "What we observed". */
    private val evidenceDir = File("build/v4-pilot-evidence").apply { mkdirs() }

    // `JvmPeer.launch` again (computenet-dqy.25): it buffers each peer's output and
    // folds it into the failure message, which is what the local per-process log file
    // was for — see CrashRestartConvergenceTest's note on the same change.
    private fun launch(vararg appArgs: String): JvmPeer.Peer =
        JvmPeer.launch("civictech.demo.MainKt", *appArgs)

    /** The demo's SSE state frame — its only read model (there is no `/state`). */
    private fun currentState(httpPort: Int): String {
        val connection = URI("http://localhost:$httpPort/events").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 3000
        connection.connectTimeout = 3000
        return connection.inputStream.bufferedReader().use { reader ->
            generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ")
        }.also { connection.disconnect() }
    }

    /** The `"shared"` array — present only in `--replicate` mode. */
    private fun shared(httpPort: Int): String =
        runCatching { currentState(httpPort).substringAfter("\"shared\":").substringBefore("]") }
            .getOrDefault("")

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

    private fun topology(inspectPort: Int): TopologySnapshot =
        json.decodeFromString(HttpProbe("http://localhost:$inspectPort").state("/api/inspect/topology"))

    private fun body(inspectPort: Int, path: String): String =
        HttpProbe("http://localhost:$inspectPort").state(path)

    private fun capture(name: String, content: String) {
        File(evidenceDir, name).writeText(content)
        println("[V4-PILOT evidence] $name -> ${File(evidenceDir, name).absolutePath}")
        println(content)
    }

    /** `Node.ref` is `"<uuid>:<instanceId>"` (`Dto.kt:33`, `InspectorServer.encodeRef`). */
    private fun instanceIdsOfShared(snapshot: TopologySnapshot): Set<String> =
        snapshot.nodes.map { it.ref }
            .filter { it.substringBefore(":") == DemoApp.SHARED_ID.toString() }
            .mapTo(LinkedHashSet()) { it.substringAfter(":") }

    private fun sharedRef(instanceId: Long) = "${DemoApp.SHARED_ID}:$instanceId"

    /**
     * [42-REPL-04] over a real socket: replicas of one logical cell converge to
     * equal folds regardless of which replica accepted each write. Plus the
     * inspector-visible facts on both sides, and the graph-id capture.
     */
    @Test
    fun `two same-logical-id replicas converge over a real socket, and both inspectors see both instances`() {
        // every port is `0`: each peer binds its own and announces what it got, so
        // no test-side number is ever handed to a process that has yet to bind it
        // (computenet-dqy.25). A must announce its listening port before B can be
        // told to dial it, which is what orders these two launches.
        val peerA = launch(
            "0", "--listen", "0", "--replicate",
            "--inspect-port", "0", "--net-name", "jvm-a",
        )
        val httpA = peerA.port("http")
        val inspectA = peerA.port("inspect")
        val peerB = launch(
            "0", "--peer", "ws://localhost:${peerA.port("ws")}", "--replicate",
            "--inspect-port", "0", "--net-name", "jvm-b",
        )
        val httpB = peerB.port("http")
        val inspectB = peerB.port("inspect")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            // ---- 1. convergence, both directions, across the socket ----
            post(httpA, user = "alice", action = "share", item = "flour")
            awaitUntil("A's shared write reached B's replica", timeoutMs = 45_000) { "flour" in shared(httpB) }

            post(httpB, user = "bob", action = "share", item = "yeast")
            awaitUntil("B's shared write reached A's replica", timeoutMs = 45_000) { "yeast" in shared(httpA) }

            awaitUntil("both replicas hold both elements", timeoutMs = 45_000) {
                val a = shared(httpA)
                val b = shared(httpB)
                "flour" in a && "yeast" in a && "flour" in b && "yeast" in b
            }

            // ---- 2. the inspector-visible facts, from BOTH inspectors ----
            awaitUntil("A's inspector adopted a mirrored cell from B", timeoutMs = 45_000) {
                runCatching { topology(inspectA).nodes.any { it.host == null } }.getOrDefault(false)
            }
            awaitUntil("B's inspector adopted a mirrored cell from A", timeoutMs = 45_000) {
                runCatching { topology(inspectB).nodes.any { it.host == null } }.getOrDefault(false)
            }
            awaitUntil("both inspectors see both instances of the shared logical id", timeoutMs = 45_000) {
                runCatching {
                    instanceIdsOfShared(topology(inspectA)) == setOf("0", "1") &&
                        instanceIdsOfShared(topology(inspectB)) == setOf("0", "1")
                }.getOrDefault(false)
            }

            val snapA = topology(inspectA)
            val snapB = topology(inspectB)

            // each side carries the PEER's mirrored shared replica; `host == null`
            // is the remote discriminator (TwoJvmInspectorTest.kt:57,66)
            val mirroredOnA: Node = snapA.nodes.single { it.ref == sharedRef(1) }
            mirroredOnA.host shouldBe null
            val mirroredOnB: Node = snapB.nodes.single { it.ref == sharedRef(0) }
            mirroredOnB.host shouldBe null

            // ...and each side's own replica is genuinely local
            snapA.nodes.single { it.ref == sharedRef(0) }.host shouldBe "shopping"
            snapB.nodes.single { it.ref == sharedRef(1) }.host shouldBe "shopping"

            // two instances of ONE logical id, decidable from the encoded refs alone
            instanceIdsOfShared(snapA) shouldBe setOf("0", "1")
            instanceIdsOfShared(snapB) shouldBe setOf("0", "1")

            // ---- 3. capture, do not assert: the graph ids (MRB-156) ----
            capture("graphs-A.json", body(inspectA, "/api/inspect/graphs"))
            capture("graphs-B.json", body(inspectB, "/api/inspect/graphs"))
            capture("topology-A.json", body(inspectA, "/api/inspect/topology"))
            capture("topology-B.json", body(inspectB, "/api/inspect/topology"))
            capture("errors-A.json", body(inspectA, "/api/inspect/errors"))
            capture("errors-B.json", body(inspectB, "/api/inspect/errors"))
            capture("flow-A.json", body(inspectA, "/api/inspect/flow"))
            capture("flow-B.json", body(inspectB, "/api/inspect/flow"))
            capture("state-mirrored-on-A.json", body(inspectA, "/api/inspect/cell/${sharedRef(1)}/state"))
            capture("state-local-on-A.json", body(inspectA, "/api/inspect/cell/${sharedRef(0)}/state"))
            // node-count legibility (finding 6): the replicate-mode totals
            capture(
                "node-counts.txt",
                "A nodes=${snapA.nodes.size} edges=${snapA.edges.size}\n" +
                    "B nodes=${snapB.nodes.size} edges=${snapB.edges.size}\n" +
                    "A refs:\n" + snapA.nodes.joinToString("\n") { "  ${it.ref} name=${it.name} host=${it.host} net=${it.net} graph=${it.graph}" } +
                    "\nB refs:\n" + snapB.nodes.joinToString("\n") { "  ${it.ref} name=${it.name} host=${it.host} net=${it.net} graph=${it.graph}" } +
                    "\nA edges:\n" + snapA.edges.joinToString("\n") { "  $it" } +
                    "\nB edges:\n" + snapB.edges.joinToString("\n") { "  $it" },
            )
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }

    /**
     * One disconnect/reconnect cycle. What is asserted is what the mechanism
     * actually promises: **no `--journal`**, so B does not recover its own
     * pre-crash writes from disk — what must hold is that after B re-announces,
     * anti-entropy catch-up (`Replication.kt:417-428`, through
     * `SetCell`'s `catchUpOnLinked`) leaves B holding everything A holds,
     * including the write A accepted while B was gone.
     */
    @Test
    fun `the replica mesh survives a killed and relaunched dialer`() {
        // see the sibling test: `0` everywhere, each peer announces what it bound
        // (computenet-dqy.25). `bArgs` names no port, so it stays reusable across B's
        // relaunch; the returning B binds fresh ones and re-announces them, which is
        // why `httpB` is a `var` — nothing here needs B back at the same address,
        // only back.
        val peerA = launch(
            "0", "--listen", "0", "--replicate",
            "--inspect-port", "0", "--net-name", "jvm-a",
        )
        val httpA = peerA.port("http")
        val inspectA = peerA.port("inspect")
        val bArgs = arrayOf(
            "0", "--peer", "ws://localhost:${peerA.port("ws")}", "--replicate",
            "--inspect-port", "0", "--net-name", "jvm-b",
        )
        var peerB = launch(*bArgs)
        var httpB = peerB.port("http")
        try {
            JvmPeer.await("both peers serving HTTP", listOf(peerA, peerB), timeoutMs = 45_000) {
                up(httpA) && up(httpB)
            }

            post(httpA, user = "alice", action = "share", item = "flour")
            post(httpB, user = "bob", action = "share", item = "yeast")
            awaitUntil("pre-partition convergence", timeoutMs = 45_000) {
                "flour" in shared(httpB) && "yeast" in shared(httpA)
            }

            fun mirroredOnA(): List<Node> =
                runCatching { topology(inspectA).nodes.filter { it.host == null } }.getOrDefault(emptyList())

            // V4-PEERID: the peer hull's label before the cycle
            val labelsBefore = mirroredOnA().map { it.net }.distinct()
            capture("reconnect-mirrored-before.txt", mirroredOnA().joinToString("\n") { "${it.ref} net=${it.net}" })

            // the dialer dies
            peerB.kill()
            awaitUntil("peer B is gone", timeoutMs = 45_000) { down(httpB) }
            awaitUntil("A retracted B's mirrored cells", timeoutMs = 45_000) { mirroredOnA().isEmpty() }

            // what the inspector says while the peer is partitioned (finding 4)
            capture("partitioned-topology-A.json", body(inspectA, "/api/inspect/topology"))
            capture("partitioned-graphs-A.json", body(inspectA, "/api/inspect/graphs"))
            capture("partitioned-errors-A.json", body(inspectA, "/api/inspect/errors"))
            capture("partitioned-flow-A.json", body(inspectA, "/api/inspect/flow"))

            // life goes on at A: this write parks (no reachable replica)
            post(httpA, user = "alice", action = "share", item = "salt")

            // B returns with the SAME arguments — no journal, so its own
            // pre-crash "yeast" is gone from its disk-less memory; anti-entropy
            // is what must put it back
            peerB = launch(*bArgs)
            httpB = peerB.port("http")
            JvmPeer.await("peer B back up", listOf(peerB), timeoutMs = 45_000) { up(httpB) }

            awaitUntil("the parked write reached the returning replica", timeoutMs = 45_000) {
                "salt" in shared(httpB)
            }
            awaitUntil("anti-entropy left B holding everything A holds", timeoutMs = 45_000) {
                val a = shared(httpA)
                val b = shared(httpB)
                a.isNotEmpty() && listOf("flour", "yeast", "salt").all { it in a && it in b }
            }

            // and the mesh is live again in the reverse direction
            post(httpB, user = "bob", action = "share", item = "water")
            awaitUntil("post-reconnect write reached A", timeoutMs = 45_000) { "water" in shared(httpA) }

            awaitUntil("A re-adopted B's mirrored cells", timeoutMs = 45_000) { mirroredOnA().isNotEmpty() }
            capture("reconnect-mirrored-after.txt", mirroredOnA().joinToString("\n") { "${it.ref} net=${it.net}" })
            capture("reconnect-graphs-A.json", body(inspectA, "/api/inspect/graphs"))
            capture("reconnect-errors-A.json", body(inspectA, "/api/inspect/errors"))
            capture("reconnect-flow-A.json", body(inspectA, "/api/inspect/flow"))

            // V4-PEERID's first real consumer test: the peer label survived
            labelsBefore shouldBe listOf("jvm-b")
            mirroredOnA().map { it.net }.distinct() shouldBe listOf("jvm-b")
        } finally {
            JvmPeer.destroy(peerA, peerB)
        }
    }
}
