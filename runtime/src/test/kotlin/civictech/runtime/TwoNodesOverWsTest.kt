package civictech.runtime

import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.replication.Replication
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.opentest4j.AssertionFailedError
import java.util.UUID

class TwoNodesOverWsTest {

    @Test
    @Timeout(60)
    fun `two manifest nodes replicate over ws and partition-heal through the exposed connection`() {
        RuntimeTransportTestRig.exercise(
            Manifest(
                mapOf(
                    "a" to NodeSpec(
                        transport = "ws",
                        listen = "ws://127.0.0.1:0",
                        replica = 0,
                        peerName = "a",
                    ),
                    "b" to NodeSpec(
                        transport = "ws",
                        dial = listOf("a"),
                        replica = 1,
                        peerName = "b",
                    ),
                ),
            ),
        )
    }
}

/** The same two-phase Runtime/Replication exercise used by the ws and iroh acceptance tests. */
internal object RuntimeTransportTestRig {

    private const val CONVERGENCE_MS = 15_000L
    private const val PARTITION_HOLD_MS = 500L

    private class CapturedSpec {
        val bootCells = mutableListOf<SetCell<String>>()
        private val logicalId = UUID.randomUUID()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "items",
                    factory = CellFactory { ref -> SetCell<String>(ref).also(bootCells::add) },
                    identity = IdentityBinding.NewInstanceOf(logicalId),
                ),
            ),
        )
    }

    private class ReplicatedNode(
        val runtime: Runtime.Node,
        val cell: SetCell<String>,
        @Suppress("unused") val replication: Replication,
    )

    fun exercise(manifest: Manifest) {
        val captured = CapturedSpec()
        val aRuntime = Runtime.boot(manifest, "a", captured.spec)
        var bRuntime: Runtime.Node? = null

        try {
            val a = replicated(aRuntime, captured.bootCells.single())
            aRuntime.open()
            val grantedAddress = requireNotNull(aRuntime.boundAddress) { "node a did not expose its granted address" }

            bRuntime = Runtime.boot(manifest, "b", captured.spec, overrides = mapOf("a" to grantedAddress.text))
            val b = replicated(bRuntime, captured.bootCells.last())
            assertTrue(a.cell.ref.sameLogical(b.cell.ref), "the shared spec did not mint one logical cell")
            assertNotEquals(a.cell.ref, b.cell.ref, "the replicas reused one instance id")
            bRuntime.open()

            ops(a).add("apple")
            awaitUntil("node a applies apple locally", CONVERGENCE_MS) { "apple" in a.cell.membership() }
            awaitUntil("node b observes apple replicated from node a", CONVERGENCE_MS) {
                "apple" in b.cell.membership()
            }

            val connection = bRuntime.connections.single()
            connection.partition()
            ops(a).add("pear")
            awaitUntil("node a applies pear while b is partitioned", CONVERGENCE_MS) { "pear" in a.cell.membership() }
            neverWithin("pear crossed while node b's connection was partitioned", PARTITION_HOLD_MS) {
                "pear" in b.cell.membership()
            }

            connection.heal()
            awaitUntil("node b observes pear after heal", CONVERGENCE_MS) { "pear" in b.cell.membership() }
        } finally {
            bRuntime?.close()
            aRuntime.close()
        }
    }

    private fun replicated(runtime: Runtime.Node, bootCell: SetCell<String>): ReplicatedNode {
        val replication = Replication(runtime.registry)
        val replica = SetCell<String>(bootCell.ref)
        replication.rebind(bootCell, replica, runtime.mainHost)
        runtime.mainHost.quiescence().await(10_000, "attaching replication before runtime open")
        return ReplicatedNode(runtime, replica, replication)
    }

    private fun ops(node: ReplicatedNode): civictech.cell.data.SetOps<String> =
        node.runtime.mainHost.lookup<SetApi<String>>(node.runtime.refs.getValue("items"))!!.inlet.call

    private fun neverWithin(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) throw AssertionFailedError("expected never within ${timeoutMs}ms, but $what")
            Thread.sleep(10)
        }
        assertFalse(condition(), what)
    }
}
