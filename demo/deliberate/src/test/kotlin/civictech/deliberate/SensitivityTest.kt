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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Model C: the sensitivity cells compute d root / d node top-down, beside the
 * credence cells and without feeding them.
 */
class SensitivityTest {
    private val schedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun tearDown() = schedulers.forEach { it.shutdown() }

    private fun graph(layers: LayerSet, sensitivity: Boolean = true): CredenceGraph {
        val scheduler = VirtualThreadScheduler("sensitivity-test").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        return CredenceGraph(host, registry, layers, sensitivity = sensitivity)
    }

    private data class E(val ref: String, val source: String, val target: String, val polarity: Polarity)

    /**
     * The hand-computable graph: root R; level 1 A (pro) and B (con); level 2
     * C (pro of A) and D (con of A); U undercuts B's link (attacks edge eB).
     */
    private val claims = listOf("R", "A", "B", "C", "D", "U")
    private val edges = listOf(
        E("eA", "A", "R", Polarity.SUPPORT),
        E("eB", "B", "R", Polarity.ATTACK),
        E("eC", "C", "A", Polarity.SUPPORT),
        E("eD", "D", "A", Polarity.ATTACK),
        E("eU", "U", "eB", Polarity.ATTACK),
    )
    private val stances = mapOf(
        "R" to 0.6, "A" to 0.7, "B" to 0.55, "C" to 0.65, "D" to 0.35, "U" to 0.6,
        "eA" to 0.8, "eB" to 0.7, "eC" to 0.6, "eD" to 0.5, "eU" to 0.75,
    )
    /** Fixed refs, so two graphs of the same shape can be compared. */
    private val refs = (claims + edges.map { it.ref }).associateWith { CellRef(UUID.nameUUIDFromBytes(it.toByteArray())) }

    private fun CredenceGraph.build() {
        claims.forEach { createClaim(it, refs.getValue(it), question = it == "R") }
        edges.forEach { createEdge(refs.getValue(it.source), refs.getValue(it.target), it.polarity, refs.getValue(it.ref)) }
        stances.forEach { (k, v) -> setStance(refs.getValue(k), "jev", v) }
    }

    /**
     * The graph's credence of [node] in layer [l], evaluated directly from the
     * stances with the semantics, [override] replacing some node's credence
     * (claim or edge) — the reference the cells are checked against.
     */
    private fun credence(layers: LayerSet, l: Int, node: String, override: Map<String, Double> = emptyMap()): Double {
        override[node]?.let { return it }
        val s = layers.semantics[l]
        val incoming = edges.filter { it.target == node }
        fun arg(e: E) = Arg(credence(layers, l, e.ref, override), credence(layers, l, e.source, override))
        return s.evaluate(
            s.base(listOf(stances.getValue(node))),
            incoming.filter { it.polarity == Polarity.ATTACK }.map(::arg),
            incoming.filter { it.polarity == Polarity.SUPPORT }.map(::arg),
        )
    }

    /** Global finite difference d root\[l] / d node\[l], every other node recomputed. */
    private fun finiteDifference(layers: LayerSet, l: Int, node: String, h: Double = 1e-5): Double {
        val x = credence(layers, l, node)
        return (credence(layers, l, "R", mapOf(node to x + h)) - credence(layers, l, "R", mapOf(node to x - h))) / (2 * h)
    }

    @Test
    fun `each node's sensitivity matches a finite-difference d root over d node, layer by layer`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo"))
        val g = graph(layers)
        g.build()
        // Stated tolerance: the cells chain central differences of each local rule, the
        // reference differences the whole tree; both are accurate to far below 1e-4 here.
        val tolerance = 1e-4
        val want = (claims + edges.map { it.ref }).associateWith { n -> layers.ids.indices.map { l -> finiteDifference(layers, l, n) } }
        for ((n, expected) in want) {
            awaitUntil("sensitivity of $n settles on the finite difference $expected") {
                val got = g.sensitivityVectorOf(refs.getValue(n)) ?: return@awaitUntil false
                got.indices.all { abs(got[it] - expected[it]) < tolerance }
            }
        }
        // The fixture is not degenerate: the root is 1, every level below it moves the root, and the con,
        // the undercutter and the undercut link pull the opposite way from the pro side.
        assertEquals(listOf(1.0, 1.0), g.sensitivityVectorOf(refs.getValue("R")))
        for (l in layers.ids.indices) {
            assertTrue(want.getValue("A")[l] > 0.01 && want.getValue("C")[l] > 0.001, "pro side at layer $l: $want")
            assertTrue(want.getValue("B")[l] < -0.01 && want.getValue("D")[l] < -0.001, "con side at layer $l: $want")
            assertTrue(want.getValue("eB")[l] < 0 && want.getValue("U")[l] > 0 && want.getValue("eU")[l] > 0, "undercut at layer $l: $want")
        }
    }

    @Test
    fun `the scalar sensitivity is d consensus of the root over a uniform move of the node`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo", "jnb"), listOf("wlo", "jnb"), headline = LayerSet.CONSENSUS)
        val g = graph(layers)
        g.build()
        val h = 1e-5
        fun consensusOfRoot(node: String, dx: Double) = layers.consensus(
            layers.ids.indices.map { l -> credence(layers, l, "R", mapOf(node to credence(layers, l, node) + dx)) },
        )
        for (n in listOf("A", "D", "eB", "U")) {
            val expected = (consensusOfRoot(n, h) - consensusOfRoot(n, -h)) / (2 * h)
            awaitUntil("scalar sensitivity of $n settles on $expected") {
                g.sensitivityOf(refs.getValue(n))?.let { abs(it - expected) < 1e-4 } == true
            }
        }
    }

    @Test
    fun `the sensitivity layer adds no input to any credence cell and changes no credence`() {
        val layers = LayerSet.of(listOf("dfquad", "wlo"))
        val with = graph(layers, sensitivity = true).apply { build() }
        val without = graph(layers, sensitivity = false).apply { build() }
        val sensCells = with.sensitivityCells
        assertEquals(refs.size + 1, sensCells.size, "one sensitivity cell per node, and the hub")
        assertTrue(without.sensitivityCells.size == 1 && without.wiring.none { it.from in without.sensitivityCells || it.to in without.sensitivityCells })
        // Every wire into a credence cell (or the credence hub) comes from a credence cell ...
        val intoCredence = with.wiring.filter { it.to !in sensCells }
        assertTrue(intoCredence.none { it.from in sensCells }, "a sensitivity cell feeds a credence cell: $intoCredence")
        // ... and they are exactly the wires the graph installs without the layer (hub refs normalised).
        fun CredenceGraph.normal(ws: List<CredenceGraph.Wire>) =
            ws.map { w -> w.copy(to = if (w.to == hub.ref) HUB else w.to) }.toSet()
        assertEquals(without.normal(without.wiring), with.normal(intoCredence))
        // The sensitivity layer does read the credence side (the influences arriving at a node).
        assertTrue(with.wiring.any { it.from in refs.values && it.to in sensCells && it.inlet == "influenceInlet" })
        // Same credences either way.
        for ((n, ref) in refs) {
            awaitUntil("$n settles to the same credence with and without the layer") {
                val a = with.credenceOf(ref)?.values
                val b = without.credenceOf(ref)?.values
                a != null && a == b && a == layers.ids.indices.map { l -> credence(layers, l, n) }
            }
        }
    }

    @Test
    fun `local partials are the semantics' own slopes`() {
        val layers = LayerSet.of(listOf("dfquad"))
        // DF-QuAD, one support: c = b + (1 - b)·s·x, so d/ds = (1 - b)·x and d/dx = (1 - b)·s.
        val inf = Influence(CellRef(UUID.randomUUID()), Polarity.SUPPORT, listOf(0.8), listOf(0.5), 0.0)
        val (dStrength, dSource) = localPartials(layers, listOf(0.4), listOf(inf)).single()
        assertEquals(0.6 * 0.5, dStrength.single(), 1e-6)
        assertEquals(0.6 * 0.8, dSource.single(), 1e-6)
    }

    private companion object {
        val HUB = CellRef(UUID(0, 0))
    }
}
