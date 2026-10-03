package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitUntil
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Exact on-demand value of information, replacing model C's derivative cells. */
class SensitivityTest {
    private val schedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun tearDown() = schedulers.forEach { it.shutdown() }

    private fun graph(layers: LayerSet): CredenceGraph {
        val scheduler = VirtualThreadScheduler("exact-voi-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = scheduler,
            registry = registry,
            attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
        )
        return CredenceGraph(host, registry, layers)
    }

    private data class E(val ref: String, val source: String, val target: String, val polarity: Polarity)

    private fun direct(
        layers: LayerSet,
        layer: Int,
        node: String,
        edges: List<E>,
        stances: Map<String, Double>,
        override: Map<String, Double> = emptyMap(),
    ): Double {
        override[node]?.let { return it }
        val semantics = layers.semantics[layer]
        val incoming = edges.filter { it.target == node }
        fun arg(edge: E) = Arg(
            direct(layers, layer, edge.ref, edges, stances, override),
            direct(layers, layer, edge.source, edges, stances, override),
        )
        return semantics.evaluate(
            semantics.base(listOf(stances.getValue(node))),
            incoming.filter { it.polarity == Polarity.ATTACK }.map(::arg),
            incoming.filter { it.polarity == Polarity.SUPPORT }.map(::arg),
        )
    }

    private fun headline(layers: LayerSet, values: List<Double>) = layers.headlineOf(values, layers.consensus(values))

    private fun expected(
        layers: LayerSet,
        subject: String,
        root: String,
        edges: List<E>,
        stances: Map<String, Double>,
    ): ExactValueOfInformation {
        fun values(node: String, override: Map<String, Double> = emptyMap()) =
            layers.ids.indices.map { direct(layers, it, node, edges, stances, override) }
        val q = headline(layers, values(subject))
        val current = headline(layers, values(root))
        val low = headline(layers, values(root, mapOf(subject to 0.0)))
        val high = headline(layers, values(root, mapOf(subject to 1.0)))
        return ExactValueOfInformation(q, listOf(ExactRootChange(CellRef(UUID(0, 0)), current, low, high)))
    }

    @Test
    fun `value of information at the weight-of-evidence clamp matches the exact expected root change`() {
        val layers = LayerSet.of(listOf("woe"), headline = "woe")
        val g = graph(layers)
        val root = g.createClaim("root", question = true)
        val child = g.createClaim("child")
        val edge = g.createEdge(child, root, Polarity.SUPPORT)
        g.setStance(root, "jev", 0.5)
        g.setStance(child, "jev", 0.9)
        g.setStance(edge, "jev", 0.9)

        val semantics = WeightOfEvidence()
        val whenFalse = semantics.evaluate(0.5, emptyList(), listOf(Arg(0.9, 0.0)))
        val whenTrue = semantics.evaluate(0.5, emptyList(), listOf(Arg(0.9, 1.0)))
        awaitUntil("the clamp fixture settles") {
            g.credenceOf(root)?.values?.single()?.let { abs(it - whenTrue) < 1e-9 } == true &&
                g.credenceOf(child)?.values?.single()?.let { abs(it - 0.9) < 1e-9 } == true
        }
        val current = g.credenceOf(root)!!.values.single()
        val q = g.credenceOf(child)!!.values.single()
        val exact = 2 * (q * abs(whenTrue - current) + (1 - q) * abs(whenFalse - current))
        val got = g.exactValueOf(child, listOf(root))!!

        assertTrue(exact > DeliberationEngine.Config.DEFAULT_VOI_EPSILON, "the fixture must be worth exploring: $exact")
        assertEquals(exact, got.expectedRootChange, exact * 0.25, "VoI at the clamp must be within 25% of exact")
        assertEquals(whenTrue - whenFalse, got.dominantSway!!, 1e-12, "the DTO sway is the signed exact secant")
    }

    @Test
    fun `exact re-evaluation includes every converging path to the root`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo"), headline = LayerSet.CONSENSUS)
        val claims = listOf("R", "A", "B", "C")
        val edges = listOf(
            E("eA", "A", "R", Polarity.SUPPORT),
            E("eB", "B", "R", Polarity.SUPPORT),
            E("eCA", "C", "A", Polarity.SUPPORT),
            E("eCB", "C", "B", Polarity.SUPPORT),
        )
        val stances = mapOf(
            "R" to 0.45, "A" to 0.55, "B" to 0.6, "C" to 0.7,
            "eA" to 0.8, "eB" to 0.65, "eCA" to 0.75, "eCB" to 0.7,
        )
        val refs = (claims + edges.map { it.ref }).associateWith { CellRef(UUID.nameUUIDFromBytes(it.toByteArray())) }
        val g = graph(layers)
        claims.forEach { g.createClaim(it, refs.getValue(it), question = it == "R") }
        edges.forEach { g.createEdge(refs.getValue(it.source), refs.getValue(it.target), it.polarity, refs.getValue(it.ref)) }
        stances.forEach { (node, stance) -> g.setStance(refs.getValue(node), "jev", stance) }

        awaitUntil("the shared-path fixture settles") {
            claims.all { node ->
                g.credenceOf(refs.getValue(node))?.values ==
                    layers.ids.indices.map { direct(layers, it, node, edges, stances) }
            }
        }
        val want = expected(layers, "C", "R", edges, stances)
        val got = g.exactValueOf(refs.getValue("C"), listOf(refs.getValue("R")))!!
        assertEquals(want.expectedRootChange, got.expectedRootChange, 1e-12)
        assertEquals(want.dominantSway!!, got.dominantSway!!, 1e-12)

        val onePath = expected(layers, "C", "R", edges.filter { it.ref != "eCB" }, stances)
        assertNotEquals(onePath.expectedRootChange, got.expectedRootChange, "both C→root paths must contribute")
    }

    @Test
    fun `random trees never stop a node whose exact expected root change reaches epsilon`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo", "jnb", "woe"), headline = LayerSet.CONSENSUS)
        val epsilon = DeliberationEngine.Config.DEFAULT_VOI_EPSILON
        val policy = ExplorationPolicy(DeliberationEngine.Config(voiEpsilon = epsilon))

        repeat(8) { seed ->
            val random = Random(seed)
            val claims = List(24) { "n$it" }
            val edges = (1 until claims.size).map { child ->
                E("e$child", "n$child", "n${random.nextInt(child)}", if (random.nextBoolean()) Polarity.SUPPORT else Polarity.ATTACK)
            }
            val stances = (claims + edges.map { it.ref }).associateWith { 0.05 + random.nextDouble() * 0.9 }
            val refs = (claims + edges.map { it.ref }).associateWith {
                CellRef(UUID.nameUUIDFromBytes("$seed:$it".toByteArray()))
            }
            val g = graph(layers)
            claims.forEach { g.createClaim(it, refs.getValue(it), question = it == "n0") }
            edges.forEach { g.createEdge(refs.getValue(it.source), refs.getValue(it.target), it.polarity, refs.getValue(it.ref)) }
            stances.forEach { (node, stance) -> g.setStance(refs.getValue(node), "jev", stance) }

            awaitUntil("random tree $seed settles") {
                claims.all { node ->
                    g.credenceOf(refs.getValue(node))?.values?.zip(
                        layers.ids.indices.map { direct(layers, it, node, edges, stances) },
                    )?.all { (actual, exact) -> abs(actual - exact) < 1e-12 } == true
                }
            }
            (claims.drop(1) + edges.map { it.ref }).forEach { node ->
                val independent = expected(layers, node, "n0", edges, stances).expectedRootChange
                val exact = g.exactValueOf(refs.getValue(node), listOf(refs.getValue("n0")))!!.expectedRootChange
                assertEquals(independent, exact, 1e-11, "seed $seed, $node")
                val gate = policy.scheduleGate(ClaimView(valueOfInformation = exact), QuestionView(treeSize = claims.size))
                assertTrue(independent < epsilon || gate != Status.DIMINISHING, "seed $seed, $node: exact=$independent")
            }
        }
    }

    @Test
    fun `new topology contains no sensitivity cells and exact reads do not change credence`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo"))
        val g = graph(layers)
        val root = g.createClaim("root", question = true)
        val child = g.createClaim("child")
        val edge = g.createEdge(child, root, Polarity.ATTACK)
        g.setStance(root, "jev", 0.6)
        g.setStance(child, "jev", 0.7)
        g.setStance(edge, "jev", 0.8)
        val rootValues = layers.evaluate(listOf(0.6), listOf(listOf(Arg(0.8, 0.7), Arg(0.8, 0.7))), emptyList())
        awaitUntil("the graph settles") {
            g.credenceOf(root)?.values == rootValues &&
                g.credenceOf(child)?.values == listOf(0.7, 0.7) &&
                g.credenceOf(edge)?.values == listOf(0.8, 0.8)
        }
        val before = g.graph().associate { it.ref to it.credence?.values }

        assertEquals(1, g.sensitivityCells.size, "only the legacy restore hub remains")
        assertTrue(g.wiring.none { it.from in g.sensitivityCells || it.to in g.sensitivityCells })
        assertTrue(g.exactValueOf(child, listOf(root))!!.expectedRootChange > 0)
        assertEquals(before, g.graph().associate { it.ref to it.credence?.values })
    }
}
