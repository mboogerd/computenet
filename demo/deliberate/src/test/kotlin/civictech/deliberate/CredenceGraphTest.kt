package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.SimWorld
import civictech.testkit.awaitUntil
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one-graph credence propagation (SPEC CRED-04..06): vector messages, derived consensus. */
class CredenceGraphTest {
    private val schedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun tearDown() = schedulers.forEach { it.shutdown() }

    private fun graph(layers: LayerSet = LayerSet.of(SemanticsCatalog.IDS), log: java.io.File? = null): CredenceGraph {
        val scheduler = VirtualThreadScheduler("credence-graph-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        return CredenceGraph(host, registry, layers, structureLog = log)
    }

    private fun CredenceGraph.near(ref: CellRef, want: List<Double>) =
        credenceOf(ref)?.values?.let { v -> v.indices.all { abs(v[it] - want[it]) < 1e-12 } } == true

    /** R ← A (support, .8), R ← B (attack, .6), A ← C (support, .9), A ← D (attack, .5). */
    private fun CredenceGraph.tree(): Map<String, CellRef> {
        val r = createClaim("R")
        val a = createClaim("A")
        val b = createClaim("B")
        val c = createClaim("C")
        val d = createClaim("D")
        val ea = createEdge(a, r, Polarity.SUPPORT)
        val eb = createEdge(b, r, Polarity.ATTACK)
        val ec = createEdge(c, a, Polarity.SUPPORT)
        val ed = createEdge(d, a, Polarity.ATTACK)
        val refs = mapOf("R" to r, "A" to a, "B" to b, "C" to c, "D" to d, "eA" to ea, "eB" to eb, "eC" to ec, "eD" to ed)
        mapOf("R" to 0.7, "A" to 0.9, "B" to 0.4, "C" to 0.3, "D" to 0.8, "eA" to 0.8, "eB" to 0.6, "eC" to 0.9, "eD" to 0.5)
            .forEach { (k, v) -> setStance(refs.getValue(k), "jev", v) }
        return refs
    }

    /** Node, prototype `semantics.js` `evaluateTree` on the same tree, per layer: R and A. */
    private val prototype = mapOf(
        "dfquad" to listOf(0.81591999999999998, 0.78300000000000003),
        "wlo" to listOf(0.87775693895829232, 0.87050815473215959),
        "jnb" to listOf(0.85883921096734783, 0.90737990042083450),
        "woe" to listOf(0.87682928663712545, 0.87673699408171235),
        "euler" to listOf(0.76008123726605459, 0.89387169876513017),
        "qe" to listOf(0.75390794847182951, 0.88504277706755830),
        "mlp" to listOf(0.78875532139705162, 0.88767653023916870),
    )

    @Test
    fun `every layer of one graph equals the prototype's tree evaluation`() {
        val g = graph()
        val refs = g.tree()
        val ids = g.layers.ids
        for ((i, node) in listOf("R", "A").withIndex()) {
            val want = ids.map { prototype.getValue(it)[i] }
            awaitUntil("$node settles on the prototype's values") { g.near(refs.getValue(node), want) }
        }
        // The consensus and the spread arrive with the vector, derived by the cell.
        val root = g.credenceOf(refs.getValue("R"))!!
        assertEquals(Consensus.of(g.layers.named(root.values), Consensus.DEFAULT_MEMBERS), root.consensus, 1e-15)
        assertEquals(root.values.min(), root.spreadLow)
        assertEquals(root.values.max(), root.spreadHigh)
    }

    @Test
    fun `model D - only a question root carries the neutral-prior verdict, which follows its arguments`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val r = g.createClaim("R", question = true)
        g.setStance(r, "jev", 0.9)
        awaitUntil("the root starts at its first impression, the neutral verdict at one half") {
            g.credenceOf(r)?.let { abs(it.values.single() - 0.9) < 1e-12 && it.neutral == listOf(0.5) } == true
        }
        val a = g.createClaim("A")
        val e = g.createEdge(a, r, Polarity.SUPPORT)
        g.setStance(a, "jev", 0.8)
        g.setStance(e, "jev", 0.5)
        // DF-QuAD, one support of energy 0.5 x 0.8 = 0.4: 0.9 + 0.1 x 0.4 = 0.94 from 0.9, 0.5 + 0.5 x 0.4 = 0.7 from one half.
        awaitUntil("both root verdicts follow the support") {
            g.credenceOf(r)?.let { abs(it.values.single() - 0.94) < 1e-12 && abs(it.neutral!!.single() - 0.7) < 1e-12 } == true
        }
        awaitUntil("the argument's credence reaches the hub") { g.credenceOf(a)?.values?.single() == 0.8 && g.credenceOf(e) != null }
        assertEquals(null, g.credenceOf(a)!!.neutral, "a claim that is not a question root carries no neutral-prior vector")
        assertEquals(null, g.credenceOf(e)!!.neutral, "an edge carries no neutral-prior vector")
    }

    @Test
    fun `an undercutter lowers the edge's credence and with it the argument's influence`() {
        val g = graph(LayerSet.of(listOf("dfquad", "jnb")))
        val refs = g.tree()
        awaitUntil("tree settles") { g.near(refs.getValue("R"), listOf(prototype["dfquad"]!![0], prototype["jnb"]!![0])) }
        val before = g.credenceOf(refs.getValue("R"))!!.values
        val u = g.createClaim("U")
        val eu = g.createEdge(u, refs.getValue("eA"), Polarity.ATTACK)
        g.setStance(u, "jev", 0.9)
        g.setStance(eu, "jev", 0.9)
        awaitUntil("the undercut edge loses credence in every layer") {
            g.credenceOf(refs.getValue("eA"))!!.values.all { it < 0.8 - 1e-6 }
        }
        awaitUntil("the root loses the argument's support in every layer") {
            g.credenceOf(refs.getValue("R"))!!.values.zip(before).all { (now, was) -> now < was - 1e-6 }
        }
    }

    @Test
    fun `a stance the node already holds is not sent again`() {
        val g = graph(LayerSet.of(listOf("dfquad")))
        val r = g.createClaim("R")
        g.setStance(r, "jev", 0.7)
        awaitUntil("stance lands") { g.near(r, listOf(0.7)) }
        val seen = g.credenceOf(r)
        g.setStance(r, "jev", 0.7)
        Thread.sleep(50)
        assertTrue(seen === g.credenceOf(r), "an unchanged stance must not reach the cell")
    }

    @Test
    fun `the structure log rebuilds the graph, and stances recompute the same credences`() {
        val dir = Files.createTempDirectory("credence-graph").toFile()
        try {
            val log = java.io.File(dir, "graph.jsonl")
            val first = graph(log = log)
            val refs = first.tree()
            awaitUntil("first settles") { first.near(refs.getValue("R"), first.layers.ids.map { prototype.getValue(it)[0] }) }
            val before = refs.mapValues { first.credenceOf(it.value)!!.values }
            // A torn tail (kill -9 mid-append) is cut off, not fatal.
            log.appendText("{\"op\":\"claim\",\"ref\":\"")
            val second = graph(log = log)
            assertEquals(first.graph().map { it.ref to it.info }, second.graph().map { it.ref to it.info })
            assertFalse(log.readText().contains("{\"op\":\"claim\",\"ref\":\"\n"))
            assertTrue(log.readText().endsWith("\n"))
            mapOf("R" to 0.7, "A" to 0.9, "B" to 0.4, "C" to 0.3, "D" to 0.8, "eA" to 0.8, "eB" to 0.6, "eC" to 0.9, "eD" to 0.5)
                .forEach { (k, v) -> second.setStance(refs.getValue(k), "jev", v) }
            for ((k, want) in before) awaitUntil("$k recomputes") {
                second.credenceOf(refs.getValue(k))?.values?.zip(want)?.all { (a, b) -> abs(a - b) < 1e-9 } == true
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `influence folding reaches the same fixpoint in every arrival order`() {
        fun ref(name: String) = CellRef(UUID.nameUUIDFromBytes(name.toByteArray()))
        fun evaluate(reverse: Boolean): List<Double> {
            val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val g = CredenceGraph(world.host, world.registry, LayerSet.of(SemanticsCatalog.IDS))
            val root = g.createClaim("root", ref("order-root"))
            val a = g.createClaim("a", ref("order-a"))
            val b = g.createClaim("b", ref("order-b"))
            val specs = listOf(
                Triple(ref("order-edge-a"), a, Polarity.SUPPORT),
                Triple(ref("order-edge-b"), b, Polarity.ATTACK),
            )
            (if (reverse) specs.reversed() else specs).forEach { (edge, source, polarity) ->
                g.createEdge(source, root, polarity, edge)
            }
            val stances = listOf(root to 0.61, a to 0.83, b to 0.37, specs[0].first to 0.72, specs[1].first to 0.58)
            (if (reverse) stances.reversed() else stances).forEach { (node, value) -> g.setStance(node, "jev", value) }
            world.runToIdle()
            return g.credenceOf(root)!!.values
        }

        assertEquals(evaluate(false), evaluate(true))
    }

    @Test
    fun `the cycle-closing edge is a head and the vector graph quiesces`() {
        val world = SimWorld(attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        val g = CredenceGraph(world.host, world.registry, LayerSet.of(SemanticsCatalog.IDS), quiescence = 1e-3)
        val a = g.createClaim("A")
        val b = g.createClaim("B")
        g.createEdge(a, b, Polarity.ATTACK)
        val head = g.createEdge(b, a, Polarity.ATTACK)
        assertTrue(g.nodeInfo(head)!!.head)
        g.setStance(a, "jev", 0.99)
        g.setStance(b, "jev", 0.99)

        val steps = world.runToIdle()
        assertTrue(steps > 0)
        assertEquals(0, world.runToIdle(), "a quiescent cycle must not leave another lap queued")
        assertTrue(g.credenceOf(a)!!.values.all { it in 0.0..1.0 })
        assertTrue(g.credenceOf(b)!!.values.all { it in 0.0..1.0 })
    }
}
