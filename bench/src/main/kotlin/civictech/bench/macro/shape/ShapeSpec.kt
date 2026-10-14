package civictech.bench.macro.shape

import civictech.cell.graph.GraphSpec

/** The seeded topologies available to the macro benchmark generator. */
enum class Shape {
    /** A linear chain; [fanOut] is ignored. */
    CHAIN,

    /** A breadth-first [fanOut]-ary tree; [fanOut] controls its branching. */
    FAN_OUT_TREE,

    /** A [FAN_OUT_TREE] with every edge reversed; [fanOut] controls its branching. */
    FAN_IN_LATTICE,

    /** A two-arm graph ending in an intersection; [fanOut] is ignored. */
    DIAMOND,

    /** A seeded acyclic graph; every node has at most [fanOut] successors. */
    RANDOM_DAG,
}

/**
 * Parameters for one deterministic benchmark graph.
 *
 * [cardinality] is the number of graph nodes. [hostCount] only affects spawn
 * placement; it does not affect topology or predicate selection.
 */
data class ShapeSpec(
    val shape: Shape,
    val seed: Long,
    val cardinality: Int,
    val fanOut: Int,
    val hostCount: Int,
) {
    init {
        val minimumCardinality = if (shape == Shape.DIAMOND) 4 else 2
        require(cardinality >= minimumCardinality) {
            "cardinality must be >= $minimumCardinality for $shape"
        }
        require(fanOut >= 1) { "fanOut must be >= 1" }
        require(hostCount >= 1) { "hostCount must be >= 1" }
    }

    /** Generate the graph and its source/sink handle indexes. */
    fun generate(): GeneratedShape = generateShape(this)

    /** Generate just the declarative graph specification. */
    fun toGraphSpec(): GraphSpec = generate().spec
}

/** A generated graph together with its in-degree-zero and out-degree-zero handles. */
data class GeneratedShape(
    val spec: GraphSpec,
    val sources: List<String>,
    val sinks: List<String>,
)
