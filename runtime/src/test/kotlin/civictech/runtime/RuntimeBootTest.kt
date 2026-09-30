package civictech.runtime

import civictech.cell.BudgetLedger
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import civictech.inspect.InspectorFlag
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RuntimeBootTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `boot builds every host, applies the spec on the first, journals it, and open is one-shot`() {
        val journalRoot = tempDir.resolve("journal")
        val manifest = Manifest(
            mapOf(
                "solo" to NodeSpec(
                    hosts = listOf("main", "worker"),
                    journalDir = journalRoot.toString(),
                    replica = 11,
                ),
            ),
        )
        val node = Runtime.boot(manifest, "solo", setSpec())

        try {
            assertEquals(setOf("main", "worker"), node.hosts.keys)
            assertSame(node.hosts.getValue("main"), node.mainHost)
            assertEquals(setOf("items"), node.refs.keys)
            assertEquals(node.registry.localRefs(), node.refs.values.toSet())
            assertEquals(11, node.replica)
            assertSame(BudgetLedger.Unlimited, node.budget)

            node.mainHost.lookup<SetApi<String>>(node.refs.getValue("items"))!!.inlet.call.add("journal-me")
            node.mainHost.quiescence().await(10_000, "journalled runtime write")
            assertTrue(
                Files.isRegularFile(journalRoot.resolve("main").resolve("host.journal")),
                "the main host did not write journalDir/main/host.journal",
            )
            assertTrue(Files.isDirectory(journalRoot.resolve("worker")), "the worker did not receive its own journal dir")

            node.open()
            assertEquals(null, node.boundAddress)
            assertTrue(node.connections.isEmpty())
            assertEquals(null, node.inspector)
            assertThrows<IllegalStateException> { node.open() }
        } finally {
            node.close()
        }

        assertTrue(node.hosts.values.all { it.isDrained }, "close did not drain every manifest host")
    }

    @Test
    fun `a budget policy file yields one node-scoped token bucket ledger`() {
        val policyFile = tempDir.resolve("policy.json")
        Files.writeString(policyFile, Json.encodeToString(EconomicPolicy.placeholder()))
        val node = Runtime.boot(
            Manifest(mapOf("budgeted" to NodeSpec(hosts = listOf("main", "worker"), budget = policyFile.toString()))),
            "budgeted",
            GraphSpec(emptyList()),
        )

        try {
            assertTrue(node.budget is TokenBucketLedger, "budget file did not build a TokenBucketLedger")
            assertEquals("budgeted", (node.budget as TokenBucketLedger).scope)
        } finally {
            node.close()
        }
    }

    @Test
    fun `open serves the requested inspector over the node hosts and bridge`() {
        val node = Runtime.boot(
            Manifest(mapOf("visible" to NodeSpec())),
            "visible",
            setSpec(),
            inspector = InspectorFlag.Options(port = 0),
        )

        try {
            assertEquals(null, node.inspector, "the inspector started during boot rather than open")
            node.open()
            assertNotNull(node.inspector)
            assertTrue(node.inspector!!.boundPort > 0)
        } finally {
            node.close()
        }

        assertTrue(node.mainHost.isDrained, "close left the inspected host running")
    }

    @Test
    fun `boot refuses a node absent from the manifest`() {
        val failure = assertThrows<IllegalArgumentException> {
            Runtime.boot(Manifest(mapOf("present" to NodeSpec())), "missing", GraphSpec(emptyList()))
        }

        assertTrue(failure.message!!.contains("manifest has no node 'missing'"), failure.message)
    }

    private fun setSpec(): GraphSpec = GraphSpec(
        listOf(SpawnStep("items", CellFactory { ref -> SetCell<String>(ref) })),
    )
}
