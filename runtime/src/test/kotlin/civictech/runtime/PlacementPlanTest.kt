package civictech.runtime

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.KeyedCellFactory
import civictech.cell.graph.KeyedFamily
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.UnlinkStep
import civictech.cell.link.LinkOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

class PlacementPlanTest {

    @Test
    fun `empty placements leave the placement driver inert`() {
        val spec = GraphSpec(listOf(spawn("source")))

        assertNull(PlacementPlan.of(spec, Manifest(mapOf("a" to NodeSpec())), "a"))
    }

    @Test
    fun `two node plans pin non-replicated identities and retain exactly their local spawns`() {
        val logicalId = UUID.randomUUID()
        val exact = CellRef(UUID.randomUUID(), 17)
        val spec = GraphSpec(
            listOf(
                spawn("fresh"),
                spawn(
                    "instance",
                    placement = "sink",
                    identity = IdentityBinding.NewInstanceOf(logicalId),
                ),
                spawn("exact", placement = "source", identity = IdentityBinding.Exact(exact)),
                spawn("everywhere", replicated = true),
            ),
        )
        val manifest = placedManifest("default" to "a", "source" to "a", "sink" to "b")

        val a = requireNotNull(PlacementPlan.of(spec, manifest, "a"))
        val b = requireNotNull(PlacementPlan.of(spec, manifest, "b"))
        val freshId = placementId("fresh")
        val instanceId = placementId("instance")

        assertEquals(CellRef(freshId), a.refOf("fresh"))
        assertEquals(a.refOf("fresh"), b.refOf("fresh"))
        assertEquals(CellRef(logicalId, instanceId.leastSignificantBits), a.refOf("instance"))
        assertEquals(a.refOf("instance"), b.refOf("instance"))
        assertEquals(exact, a.refOf("exact"))
        assertEquals(a.refOf("exact"), b.refOf("exact"))
        assertEquals(listOf("fresh", "exact", "everywhere"), spawnHandles(a))
        assertEquals(listOf("instance", "everywhere"), spawnHandles(b))
        assertEquals(IdentityBinding.Exact(CellRef(freshId)), localSpawn(a, "fresh").identity)
        assertEquals(
            IdentityBinding.Exact(CellRef(logicalId, instanceId.leastSignificantBits)),
            localSpawn(b, "instance").identity,
        )
        assertEquals(IdentityBinding.FreshLogical, localSpawn(a, "everywhere").identity)
        assertEquals("a", a.nodeOf("fresh"))
        assertEquals("b", a.nodeOf("instance"))
        assertNull(a.nodeOf("everywhere"))
        val replicatedRefusal = assertThrows<IllegalStateException> { a.refOf("everywhere") }
        assertTrue(replicatedRefusal.message!!.contains("everywhere"), replicatedRefusal.message)
    }

    @Test
    fun `local links stay in the spec while cross links become producer and consumer halves`() {
        val local = ConnectStep("source", "outlet", "localSink", "inlet")
        val cross = ConnectStep("source", "outlet", "remoteSink", "inlet")
        val spec = GraphSpec(
            listOf(
                spawn("source", placement = "a"),
                spawn("localSink", placement = "a"),
                spawn("remoteSink", placement = "b"),
                local,
                cross,
            ),
        )
        val manifest = placedManifest("a" to "a", "b" to "b")

        val a = requireNotNull(PlacementPlan.of(spec, manifest, "a"))
        val b = requireNotNull(PlacementPlan.of(spec, manifest, "b"))
        val edge = CrossEdge(
            "source",
            a.refOf("source"),
            "outlet",
            "remoteSink",
            a.refOf("remoteSink"),
            "inlet",
        )

        assertEquals(listOf(local), a.localSpec.steps.filterIsInstance<ConnectStep>())
        assertEquals(listOf(edge), a.producerHalves)
        assertEquals(emptyList<CrossEdge>(), a.consumerHalves)
        assertEquals(emptyList<ConnectStep>(), b.localSpec.steps.filterIsInstance<ConnectStep>())
        assertEquals(emptyList<CrossEdge>(), b.producerHalves)
        assertEquals(listOf(edge), b.consumerHalves)
    }

    @Test
    fun `a missing placement selector is refused before planning`() {
        val failure = assertThrows<IllegalStateException> {
            PlacementPlan.of(
                GraphSpec(listOf(spawn("source", placement = "missing"))),
                placedManifest("default" to "a"),
                "a",
            )
        }

        assertEquals(
            "spawn step 'source': placement 'missing' names no entry in manifest.placements",
            failure.message,
        )
    }

    @Test
    fun `cross-node link options are refused by edge key`() {
        val failure = assertThrows<IllegalStateException> {
            PlacementPlan.of(
                crossSpec(ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true))),
                placedManifest("a" to "a", "b" to "b"),
                "a",
            )
        }

        assertEquals(
            "link source.outlet->sink.inlet: link options are not supported across nodes (staged/Observe)",
            failure.message,
        )
    }

    @Test
    fun `third node applies the same cross-node link validation as endpoint nodes`() {
        val manifest = threeNodeManifest("a" to "a", "b" to "b")
        val spec = crossSpec(
            ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true)),
        )

        val messages = listOf("a", "b", "c").map { node ->
            assertThrows<IllegalStateException> {
                PlacementPlan.of(spec, manifest, node)
            }.message
        }

        assertEquals(
            List(3) {
                "link source.outlet->sink.inlet: link options are not supported across nodes " +
                    "(staged/Observe)"
            },
            messages,
        )
    }

    @Test
    fun `cross-node unlink is refused by edge key`() {
        val failure = assertThrows<IllegalStateException> {
            PlacementPlan.of(
                crossSpec(UnlinkStep("source", "outlet", "sink", "inlet")),
                placedManifest("a" to "a", "b" to "b"),
                "a",
            )
        }

        assertEquals("unlink source.outlet->sink.inlet: cross-node unlink is not supported", failure.message)
    }

    @Test
    fun `cross-node links refuse replicated and family endpoints`() {
        val manifest = threeNodeManifest("a" to "a", "b" to "b")
        val replicatedSpec = GraphSpec(
            listOf(
                spawn("replica", placement = "a", replicated = true),
                spawn("sink", placement = "b"),
                ConnectStep("replica", "outlet", "sink", "inlet"),
            ),
        )
        val replicatedMessages = listOf("a", "b", "c").map { node ->
            assertThrows<IllegalStateException> {
                PlacementPlan.of(replicatedSpec, manifest, node)
            }.message
        }
        assertEquals(
            List(3) { "link replica.outlet->sink.inlet: replicated handle 'replica' has no single remote ref" },
            replicatedMessages,
        )

        val family = SpawnStep(
            handle = "family",
            factory = KeyedCellFactory { _, ref -> SetCell<String>(ref) },
            family = KeyedFamily("items"),
            placement = "a",
        )
        val familySpec = GraphSpec(
            listOf(
                family,
                spawn("sink", placement = "b"),
                ConnectStep("family", "outlet", "sink", "inlet"),
            ),
        )
        val familyMessages = listOf("a", "b", "c").map { node ->
            assertThrows<IllegalStateException> {
                PlacementPlan.of(familySpec, manifest, node)
            }.message
        }
        assertEquals(
            List(3) { "link family.outlet->sink.inlet: family handle 'family' has no port" },
            familyMessages,
        )
    }

    @Test
    fun `despawn keeps local isolated handles drops remote handles and refuses live cross edges`() {
        val manifest = placedManifest("a" to "a", "b" to "b")
        val local = requireNotNull(
            PlacementPlan.of(
                GraphSpec(
                    listOf(
                        spawn("local", placement = "a"),
                        spawn("remote", placement = "b"),
                        DespawnStep("local"),
                        DespawnStep("remote"),
                    ),
                ),
                manifest,
                "a",
            ),
        )
        assertEquals(listOf("local"), spawnHandles(local))
        assertEquals(listOf(DespawnStep("local")), local.localSpec.steps.filterIsInstance<DespawnStep>())

        val failure = assertThrows<IllegalStateException> {
            PlacementPlan.of(
                crossSpec(
                    ConnectStep("source", "outlet", "sink", "inlet"),
                    DespawnStep("source"),
                ),
                manifest,
                "a",
            )
        }
        assertTrue(failure.message!!.contains("source"), failure.message)
    }

    @Test
    fun `delta planning resolves base handles and retains cross edges for later refusals`() {
        val manifest = placedManifest("a" to "a", "b" to "b")
        val base = GraphSpec(listOf(spawn("source", placement = "a")))
        val baseA = requireNotNull(PlacementPlan.of(base, manifest, "a"))
        val baseB = requireNotNull(PlacementPlan.of(base, manifest, "b"))
        val delta = GraphSpec(
            listOf(
                spawn("sink", placement = "b"),
                ConnectStep("source", "outlet", "sink", "inlet"),
            ),
        )

        val deltaA = requireNotNull(PlacementPlan.of(delta, manifest, "a", baseA))
        val deltaB = requireNotNull(PlacementPlan.of(delta, manifest, "b", baseB))
        val edge = CrossEdge(
            "source",
            baseA.refOf("source"),
            "outlet",
            "sink",
            deltaA.refOf("sink"),
            "inlet",
        )

        assertEquals(listOf(edge), deltaA.producerHalves)
        assertEquals(emptyList<CrossEdge>(), deltaA.consumerHalves)
        assertEquals(emptyList<String>(), spawnHandles(deltaA))
        assertEquals(emptyList<CrossEdge>(), deltaB.producerHalves)
        assertEquals(listOf(edge), deltaB.consumerHalves)
        assertEquals(listOf("sink"), spawnHandles(deltaB))
        assertEquals(baseA.refOf("source"), deltaA.refOf("source"))
        assertEquals(deltaA.refOf("sink"), deltaB.refOf("sink"))

        val failure = assertThrows<IllegalStateException> {
            PlacementPlan.of(GraphSpec(listOf(DespawnStep("source"))), manifest, "a", deltaA)
        }
        assertEquals("despawn 'source': cross-node despawn is not supported", failure.message)
    }

    @Test
    fun `third node retains remote cross edge for later despawn refusal`() {
        val manifest = threeNodeManifest("a" to "a", "b" to "b")
        val base = crossSpec(ConnectStep("source", "outlet", "sink", "inlet"))

        val messages = listOf("a", "b", "c").map { node ->
            val plan = requireNotNull(PlacementPlan.of(base, manifest, node))
            assertThrows<IllegalStateException> {
                PlacementPlan.of(
                    GraphSpec(listOf(DespawnStep("source"))),
                    manifest,
                    node,
                    plan,
                )
            }.message
        }

        assertEquals(
            List(3) { "despawn 'source': cross-node despawn is not supported" },
            messages,
        )
    }

    private fun crossSpec(vararg trailing: civictech.cell.graph.GraphStep): GraphSpec = GraphSpec(
        listOf(
            spawn("source", placement = "a"),
            spawn("sink", placement = "b"),
            *trailing,
        ),
    )

    private fun spawn(
        handle: String,
        placement: String? = null,
        identity: IdentityBinding = IdentityBinding.FreshLogical,
        replicated: Boolean = false,
    ): SpawnStep = SpawnStep(
        handle = handle,
        factory = CellFactory { ref -> SetCell<String>(ref) },
        identity = identity,
        replicated = replicated,
        placement = placement,
    )

    private fun placedManifest(vararg placements: Pair<String, String>): Manifest = Manifest(
        nodes = mapOf("a" to NodeSpec(), "b" to NodeSpec()),
        placements = mapOf(*placements),
    )

    private fun threeNodeManifest(vararg placements: Pair<String, String>): Manifest = Manifest(
        nodes = mapOf("a" to NodeSpec(), "b" to NodeSpec(), "c" to NodeSpec()),
        placements = mapOf(*placements),
    )

    private fun spawnHandles(plan: PlacementPlan): List<String> =
        plan.localSpec.steps.filterIsInstance<SpawnStep>().map { it.handle }

    private fun localSpawn(plan: PlacementPlan, handle: String): SpawnStep =
        plan.localSpec.steps.filterIsInstance<SpawnStep>().single { it.handle == handle }

    private fun placementId(handle: String): UUID =
        UUID.nameUUIDFromBytes("computenet-placement:$handle".toByteArray(UTF_8))
}
