package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNCHECKED_CAST")
class PlacementRecoveryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `a placed node recovers its local cell and bridge halves before opening`() {
        captured.clear()
        val spec = splitSpec()
        val manifest = placedManifest(journalTopology = true)
        val a = Runtime.boot(manifest, "a", spec)
        var b1: Runtime.Node? = null
        var b2: Runtime.Node? = null

        try {
            a.open()
            val address = requireNotNull(a.boundAddress).text
            b1 = Runtime.boot(manifest, "b", spec, overrides = mapOf("a" to address))
            assertEquals(false, b1.recovered)
            b1.open()

            val writer = captured.getValue("w") as SetCell<String>
            val view = captured.getValue("v") as PlacementFixture.SetFoldCell
            val firstUnion = captured.getValue("u") as UnionSetCell<String>
            writer.inlet.call.add("x")
            awaitUntil("the placed fold receives x before recovery", 15_000) {
                view.membership == setOf("x")
            }

            b1.close()
            b2 = Runtime.boot(manifest, "b", spec, overrides = mapOf("a" to address))
            assertTrue(b2.recovered)
            assertEquals(setOf("u"), b2.refs.keys)

            val recoveredUnion = captured.getValue("u") as UnionSetCell<String>
            assertNotSame(firstUnion, recoveredUnion)
            assertEquals(setOf("x"), membership(recoveredUnion))

            val plan = requireNotNull(b2.placement)
            val producerLink = assertInstanceOf(
                WireEdgeLink::class.java,
                recoveredUnion.outlet.linking.links.single(),
            )
            assertEquals(PortAddress(plan.refOf("v"), "inlet"), producerLink.toAddr)
            val consumerLink = assertInstanceOf(
                WireEdgeLink::class.java,
                recoveredUnion.inlet.linking.links.single(),
            )
            assertEquals(PortAddress(plan.refOf("w"), "outlet"), consumerLink.fromAddr)

            b2.open()
            writer.inlet.call.add("y")
            awaitUntil("the placed fold receives y through the recovered node", 15_000) {
                view.membership == setOf("x", "y")
            }
        } finally {
            b2?.close()
            b1?.close()
            a.close()
        }
    }

    @Test
    @Timeout(60)
    fun `a placed node without topology journalling boots fresh after close`() {
        captured.clear()
        val spec = splitSpec()
        val manifest = placedManifest(journalTopology = false)
        val a = Runtime.boot(manifest, "a", spec)
        var b1: Runtime.Node? = null
        var b2: Runtime.Node? = null

        try {
            a.open()
            val address = requireNotNull(a.boundAddress).text
            b1 = Runtime.boot(manifest, "b", spec, overrides = mapOf("a" to address))
            b1.open()

            val writer = captured.getValue("w") as SetCell<String>
            val view = captured.getValue("v") as PlacementFixture.SetFoldCell
            writer.inlet.call.add("x")
            awaitUntil("the control fold receives x before reboot", 15_000) {
                view.membership == setOf("x")
            }

            b1.close()
            b2 = Runtime.boot(manifest, "b", spec, overrides = mapOf("a" to address))

            assertEquals(false, b2.recovered)
            assertEquals(emptySet<String>(), membership(captured.getValue("u") as UnionSetCell<String>))
        } finally {
            b2?.close()
            b1?.close()
            a.close()
        }
    }

    private fun placedManifest(journalTopology: Boolean): Manifest = Manifest(
        nodes = mapOf(
            "a" to NodeSpec(
                transport = "ws",
                listen = "ws://127.0.0.1:0",
                peerName = "a",
            ),
            "b" to NodeSpec(
                transport = "ws",
                dial = listOf("a"),
                peerName = "b",
                journalDir = tempDir.resolve("b").toString(),
                journalTopology = journalTopology,
            ),
        ),
        placements = mapOf("source" to "a", "op" to "b", "sink" to "a"),
    )

    private fun splitSpec(): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("w", SetFactory("w"), placement = "source"),
            SpawnStep("u", UnionFactory("u"), placement = "op"),
            SpawnStep("v", FoldFactory("v"), placement = "sink"),
            ConnectStep("w", "outlet", "u", "inlet"),
            ConnectStep("u", "outlet", "v", "inlet"),
        ),
    )

    private fun membership(cell: UnionSetCell<String>): Set<String> =
        (cell.snapshot() as Map<String, *>).keys

    private data class SetFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): SetCell<String> =
            SetCell<String>(ref).also { captured[handle] = it }
    }

    private data class UnionFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): UnionSetCell<String> =
            UnionSetCell<String>(ref).also { captured[handle] = it }
    }

    private data class FoldFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): PlacementFixture.SetFoldCell =
            PlacementFixture.SetFoldCell(ref).also { captured[handle] = it }
    }

    companion object {
        private val captured = ConcurrentHashMap<String, Cell>()
    }
}
