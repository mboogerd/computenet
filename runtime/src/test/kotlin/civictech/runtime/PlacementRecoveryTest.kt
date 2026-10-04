package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.registerPort
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
import java.util.UUID
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
            assertEquals(
                1,
                recoveredUnion.outlet.linking.links.size,
                "the recovered producer half was not installed exactly once",
            )
            val producerLink = assertInstanceOf(
                WireEdgeLink::class.java,
                recoveredUnion.outlet.linking.links.single(),
            )
            assertEquals(PortAddress(plan.refOf("v"), "inlet"), producerLink.toAddr)
            assertEquals(
                1,
                recoveredUnion.inlet.linking.links.size,
                "the recovered consumer half was not installed exactly once",
            )
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
    fun `a post-boot cross-node edge is reinstalled from the cumulative boot spec`() {
        captured.clear()
        val base = evolvingBaseSpec()
        val delta = evolvingDeltaSpec()
        val cumulative = GraphSpec(base.steps + delta.steps)
        val manifest = evolvingManifest()
        val a = Runtime.boot(manifest, "a", base)
        var b1: Runtime.Node? = null
        var b2: Runtime.Node? = null

        try {
            a.open()
            val address = requireNotNull(a.boundAddress).text
            b1 = Runtime.boot(manifest, "b", base, overrides = mapOf("a" to address))
            b1.open()

            b1.apply(delta)
            a.apply(delta)
            val writer = captured.getValue("w") as SetCell<String>
            val firstView = captured.getValue("v") as UnionSetCell<String>
            writer.inlet.call.add("before-reboot")
            awaitUntil("the post-boot edge carries data before recovery", 15_000) {
                membership(firstView) == setOf("before-reboot")
            }
            val before = requireNotNull(b1.placement)

            b1.close()
            b2 = Runtime.boot(manifest, "b", cumulative, overrides = mapOf("a" to address))
            assertTrue(b2.recovered)
            val after = requireNotNull(b2.placement)
            assertEquals(before.localSpec, after.localSpec)
            assertEquals(before.consumerHalves, after.consumerHalves)
            assertEquals(before.refOf("v"), after.refOf("v"))

            val recoveredView = captured.getValue("v") as UnionSetCell<String>
            assertNotSame(firstView, recoveredView)
            assertEquals(setOf("before-reboot"), membership(recoveredView))
            assertEquals(
                1,
                recoveredView.inlet.linking.links.size,
                "the post-boot consumer half was not reinstalled exactly once",
            )

            b2.open()
            writer.inlet.call.add("after-reboot")
            awaitUntil("the recovered post-boot edge carries a later delta", 15_000) {
                membership(recoveredView) == setOf("before-reboot", "after-reboot")
            }
        } finally {
            b2?.close()
            b1?.close()
            a.close()
        }
    }

    @Test
    @Timeout(60)
    fun `a surviving edge-keyed consumer does not double count a recovered producer`() {
        val expected = singleHostPresenceOutcome("before-reboot", "after-reboot")
        captured.clear()
        val spec = presenceSpec()
        val manifest = presenceManifest(recovering = "a", listening = "b")
        val b = Runtime.boot(manifest, "b", spec)
        var a1: Runtime.Node? = null
        var a2: Runtime.Node? = null

        try {
            b.open()
            val address = requireNotNull(b.boundAddress).text
            a1 = Runtime.boot(manifest, "a", spec, overrides = mapOf("b" to address))
            a1.open()

            (captured.getValue("w") as SetCell<String>).inlet.call.add("before-reboot")
            val view = captured.getValue("v") as CountFoldCell
            awaitUntil("the surviving consumer receives the pre-reboot value", 15_000) {
                view.counts == mapOf("before-reboot" to 1)
            }

            a1.close()
            a2 = Runtime.boot(manifest, "a", spec, overrides = mapOf("b" to address))
            assertTrue(a2.recovered)
            a2.open()

            (captured.getValue("w") as SetCell<String>).inlet.call.add("after-reboot")
            awaitUntil("the surviving consumer receives through the recovered producer", 15_000) {
                view.counts.keys == expected.keys
            }
            assertEquals(expected, view.counts)
        } finally {
            a2?.close()
            a1?.close()
            b.close()
        }
    }

    @Test
    @Timeout(60)
    fun `a recovered edge-keyed consumer observes its surviving producer exactly once`() {
        val expected = singleHostPresenceOutcome("before-reboot", "after-reboot")
        captured.clear()
        val spec = presenceSpec()
        val manifest = presenceManifest(recovering = "b", listening = "a")
        val a = Runtime.boot(manifest, "a", spec)
        var b1: Runtime.Node? = null
        var b2: Runtime.Node? = null

        try {
            a.open()
            val address = requireNotNull(a.boundAddress).text
            b1 = Runtime.boot(manifest, "b", spec, overrides = mapOf("a" to address))
            b1.open()

            val writer = captured.getValue("w") as SetCell<String>
            writer.inlet.call.add("before-reboot")
            val firstView = captured.getValue("v") as CountFoldCell
            awaitUntil("the first consumer receives the pre-reboot value", 15_000) {
                firstView.counts == mapOf("before-reboot" to 1)
            }

            b1.close()
            b2 = Runtime.boot(manifest, "b", spec, overrides = mapOf("a" to address))
            assertTrue(b2.recovered)
            b2.open()
            val recoveredPresence = captured.getValue("p") as PresenceCountCell<String>
            val recoveredView = observeCounts(recoveredPresence)

            writer.inlet.call.add("after-reboot")
            awaitUntil("the recovered consumer receives from the surviving producer", 15_000) {
                recoveredView.counts.keys == expected.keys
            }
            assertEquals(expected, recoveredView.counts)
        } finally {
            b2?.close()
            b1?.close()
            a.close()
        }
    }

    @Test
    @Timeout(60)
    fun `placed recovery refuses a boot spec that omits a post-boot local handle`() {
        captured.clear()
        val base = evolvingBaseSpec()
        val delta = evolvingDeltaSpec()
        val manifest = evolvingManifest()

        Runtime.boot(manifest, "b", base).use { first ->
            first.apply(delta)
        }

        val outcome = runCatching { Runtime.boot(manifest, "b", base) }
        outcome.getOrNull()?.close()
        val failure = outcome.exceptionOrNull()
            ?: throw AssertionError("placed recovery accepted a non-cumulative boot GraphSpec")

        assertTrue(failure.message!!.contains("cumulative placed GraphSpec"), failure.message)
        assertTrue(failure.message!!.contains("'v'"), failure.message)
    }

    @Test
    @Timeout(60)
    fun `a cumulative placed boot spec may contain a recovered local despawn`() {
        captured.clear()
        val base = GraphSpec(
            listOf(SpawnStep("u", UnionFactory("u"), placement = "op")),
        )
        val cumulative = GraphSpec(base.steps + DespawnStep("u"))
        val manifest = placedManifest(journalTopology = true)

        Runtime.boot(manifest, "b", base).use { first ->
            first.apply(GraphSpec(listOf(DespawnStep("u"))))
        }

        Runtime.boot(manifest, "b", cumulative).use { recovered ->
            assertTrue(recovered.recovered)
            assertEquals(emptySet<String>(), recovered.refs.keys)
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

    private fun evolvingBaseSpec(): GraphSpec = GraphSpec(
        listOf(SpawnStep("w", SetFactory("w"), placement = "source")),
    )

    private fun evolvingDeltaSpec(): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("v", UnionFactory("v"), placement = "sink"),
            ConnectStep("w", "outlet", "v", "inlet"),
        ),
    )

    private fun evolvingManifest(): Manifest = Manifest(
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
                journalDir = tempDir.resolve("evolving-b").toString(),
                journalTopology = true,
            ),
        ),
        placements = mapOf("source" to "a", "sink" to "b"),
    )

    private fun singleHostPresenceOutcome(vararg values: String): Map<String, Int> {
        captured.clear()
        val manifest = Manifest(
            nodes = mapOf("only" to NodeSpec(transport = "ws", peerName = "only")),
            placements = mapOf("source" to "only", "sink" to "only"),
        )
        return Runtime.boot(manifest, "only", presenceSpec()).use {
            val writer = captured.getValue("w") as SetCell<String>
            val view = captured.getValue("v") as CountFoldCell
            values.forEach(writer.inlet.call::add)
            awaitUntil("the single-host presence fold reaches its expected keys", 15_000) {
                view.counts.keys == values.toSet()
            }
            view.counts
        }
    }

    private fun presenceSpec(): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("w", SetFactory("w"), placement = "source"),
            SpawnStep("p", PresenceFactory("p"), placement = "sink"),
            SpawnStep("v", CountFoldFactory("v"), placement = "sink"),
            ConnectStep("w", "outlet", "p", "inlet"),
            ConnectStep("p", "outlet", "v", "inlet"),
        ),
    )

    private fun presenceManifest(recovering: String, listening: String): Manifest = Manifest(
        nodes = listOf("a", "b").associateWith { name ->
            NodeSpec(
                transport = "ws",
                listen = if (name == listening) "ws://127.0.0.1:0" else null,
                dial = if (name == listening) emptyList() else listOf(listening),
                peerName = name,
                journalDir = if (name == recovering) tempDir.resolve("presence-$name").toString() else null,
                journalTopology = name == recovering,
            )
        },
        placements = mapOf("source" to "a", "sink" to "b"),
    )

    private fun membership(cell: UnionSetCell<String>): Set<String> =
        (cell.snapshot() as Map<String, *>).keys

    @Suppress("UNCHECKED_CAST")
    private fun observeCounts(cell: PresenceCountCell<String>): CountFoldCell =
        CountFoldCell(CellRef(UUID.randomUUID())).also { view ->
            cell.outlet.linkTo(view.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        }

    private data class SetFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): SetCell<String> =
            SetCell<String>(ref).also { captured[handle] = it }
    }

    private data class UnionFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): UnionSetCell<String> =
            UnionSetCell<String>(ref).also { captured[handle] = it }
    }

    private data class PresenceFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): PresenceCountCell<String> =
            PresenceCountCell<String>(ref).also { captured[handle] = it }
    }

    private data class CountFoldFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): CountFoldCell =
            CountFoldCell(ref).also { captured[handle] = it }
    }

    private data class FoldFactory(private val handle: String) : CellFactory {
        override fun create(ref: CellRef): PlacementFixture.SetFoldCell =
            PlacementFixture.SetFoldCell(ref).also { captured[handle] = it }
    }

    private class CountFoldCell(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Int>>>())

        @Volatile
        var counts: Map<String, Int> = emptyMap()
            private set

        init {
            inlet.serve(object : Propagate<MapDelta<String, Int>> {
                override fun propagate(value: MapDelta<String, Int>) {
                    synchronized(this@CountFoldCell) {
                        counts = counts.toMutableMap().apply {
                            putAll(value.puts)
                            value.removals.forEach(::remove)
                        }
                    }
                }
            })
        }
    }

    companion object {
        private val captured = ConcurrentHashMap<String, Cell>()
    }
}
