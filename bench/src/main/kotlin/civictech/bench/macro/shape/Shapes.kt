package civictech.bench.macro.shape

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Random

/** Factories used by generated shapes; implementations intentionally contain no live runtime objects. */
sealed interface ShapeFactory : CellFactory

/** Constructs a source set. [elementSeed] is reserved for the runner's input stream. */
data class SourceFactory(val elementSeed: Long) : ShapeFactory {
    override fun create(ref: CellRef): Cell = SetCell<Long>(ref)
}

/** Constructs a unary filter from the stable [PREDICATES] table. */
data class FilterFactory(val predicateIndex: Int) : ShapeFactory {
    init {
        require(predicateIndex in PREDICATES.indices) { "predicateIndex must identify a PREDICATES entry" }
    }

    override fun create(ref: CellRef): Cell = FilterCell(ref, PREDICATES[predicateIndex])
}

/** Constructs a many-input union set. [elementSeed] is reserved for the runner's input stream. */
data class UnionFactory(val elementSeed: Long) : ShapeFactory {
    override fun create(ref: CellRef): Cell = UnionSetCell<Long>(ref)
}

/** Constructs the binary intersection used by the diamond sink. */
data class IntersectFactory(val elementSeed: Long) : ShapeFactory {
    override fun create(ref: CellRef): Cell = IntersectSetCell<Long>(ref)
}

/** Predicates are indexed by factories so generated specs contain no lambdas. */
val PREDICATES: List<(Long) -> Boolean> = listOf(
    { value -> value % 2L == 0L },
    { value -> value % 3L != 0L },
    { value -> value >= 250L },
)

/**
 * Seeds known to fail a later benchmark replay. A recorded seed is never
 * removed or replaced with a passing one; task 2 owns the vacuity assertion.
 */
val KNOWN_FAILING_SEEDS: Map<Shape, Set<Long>> = emptyMap()

private data class Edge(val from: Int, val to: Int)

internal fun generateShape(input: ShapeSpec): GeneratedShape {
    val random = Random(input.seed)
    val edges = buildEdges(input, random)
    val inbound = IntArray(input.cardinality)
    val outbound = IntArray(input.cardinality)
    edges.forEach { edge ->
        inbound[edge.to]++
        outbound[edge.from]++
    }

    val handles = Array(input.cardinality) { index -> "" }
    val sources = mutableListOf<String>()
    val sinks = mutableListOf<String>()
    var sourceIndex = 0
    var sinkIndex = 0
    for (node in handles.indices) {
        val handle = when {
            inbound[node] == 0 -> "s${input.seed}-source${sourceIndex++}"
            outbound[node] == 0 -> "s${input.seed}-sink${sinkIndex++}"
            else -> "s${input.seed}-n$node"
        }
        handles[node] = handle
        if (inbound[node] == 0) sources += handle
        if (outbound[node] == 0) sinks += handle
    }

    // Predicate draws happen in node order, before any placement draw. This keeps
    // a host-count sweep on one topology comparable across all host counts.
    val factories = Array<ShapeFactory>(input.cardinality) { node ->
        when {
            input.shape == Shape.DIAMOND && node == input.cardinality - 1 ->
                IntersectFactory(node.toLong())
            inbound[node] == 0 -> SourceFactory(node.toLong())
            inbound[node] == 1 -> FilterFactory(random.nextInt(PREDICATES.size))
            else -> UnionFactory(node.toLong())
        }
    }

    val spawns = handles.indices.map { node ->
        SpawnStep(
            handle = handles[node],
            factory = factories[node],
            identity = IdentityBinding.Exact(identityFor(handles[node])),
            placement = "host${random.nextInt(input.hostCount)}",
        )
    }

    var diamondSinkInlet = 0
    val connects = edges.map { edge ->
        val inlet = if (input.shape == Shape.DIAMOND && edge.to == input.cardinality - 1) {
            if (diamondSinkInlet++ == 0) "left" else "right"
        } else {
            "inlet"
        }
        ConnectStep(handles[edge.from], "outlet", handles[edge.to], inlet)
    }
    return GeneratedShape(GraphSpec(spawns + connects), sources, sinks)
}

private fun buildEdges(input: ShapeSpec, random: Random): List<Edge> {
    val edges = when (input.shape) {
        Shape.CHAIN -> buildChain(input.cardinality)
        Shape.FAN_OUT_TREE -> buildFanOutTree(input.cardinality, input.fanOut)
        Shape.FAN_IN_LATTICE -> buildFanOutTree(input.cardinality, input.fanOut).map { Edge(it.to, it.from) }
        Shape.DIAMOND -> buildDiamond(input.cardinality)
        Shape.RANDOM_DAG -> buildRandomDag(input.cardinality, input.fanOut, random)
    }
    return edges.sortedWith(compareBy<Edge> { it.from }.thenBy { it.to })
}

private fun buildChain(cardinality: Int): List<Edge> =
    (0 until cardinality - 1).map { Edge(it, it + 1) }

private fun buildFanOutTree(cardinality: Int, fanOut: Int): List<Edge> =
    (1 until cardinality).map { child -> Edge((child - 1) / fanOut, child) }

private fun buildDiamond(cardinality: Int): List<Edge> {
    val firstArmLength = (cardinality - 2) / 2
    val sink = cardinality - 1
    val firstArm = (1..firstArmLength).toList()
    val secondArm = ((firstArmLength + 1)..(cardinality - 2)).toList()
    return buildArm(0, firstArm, sink) + buildArm(0, secondArm, sink)
}

private fun buildArm(source: Int, arm: List<Int>, sink: Int): List<Edge> = buildList {
    add(Edge(source, arm.first()))
    arm.zipWithNext { from, to -> add(Edge(from, to)) }
    add(Edge(arm.last(), sink))
}

private fun buildRandomDag(cardinality: Int, fanOut: Int, random: Random): List<Edge> = buildList {
    for (from in 0 until cardinality - 1) {
        val successorCount = 1 + random.nextInt(minOf(fanOut, cardinality - 1 - from))
        val candidates = (from + 1 until cardinality).toMutableList()
        repeat(successorCount) {
            add(Edge(from, candidates.removeAt(random.nextInt(candidates.size))))
        }
    }
}

private fun identityFor(handle: String): CellRef =
    CellRef(UUID.nameUUIDFromBytes(handle.toByteArray(StandardCharsets.UTF_8)), 0L)
