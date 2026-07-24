package civictech.cell.graph

import civictech.cell.data.CountCell
import civictech.cell.data.FilterCell
import civictech.cell.data.IntersectSetCell
import civictech.cell.data.UnionSetCell

/**
 * The everyday set-algebra operators as graph compositions (M4): sugar only —
 * no new cell semantics, no new DSL semantics (GraphDsl.kt:216). Each combinator
 * spawns the one underlying cell ([FilterCell]/[IntersectSetCell]/[UnionSetCell]/
 * [CountCell]), wires its inputs, records the spawn+connect steps into the
 * [GraphSpec] (graphs-as-data), and returns the derived [CellHandle] so a view
 * reads left-to-right as algebra:
 * ```
 * val produce = items.filter("produce") { it.first().lowercaseChar() in 'a'..'m' }
 * val wanted  = items.intersect("wanted", votes)
 * val voteN   = wanted.count("count")
 * ```
 * This fills the vocabulary gaps around [leftJoin]/[rightJoin]/[fullJoin]:
 * the join family already proved "operator as `GraphBuilder` composition
 * returning a `CellHandle`"; these are the core operators the demos lean on.
 */

/** Incremental filter: forwards deltas whose element passes [pred], absorbs the rest. */
fun <E> CellHandle.filter(name: String, pred: (E) -> Boolean): CellHandle {
    val filtered = builder.spawn(name) { ref -> FilterCell<E>(ref = ref, predicate = pred) }
    builder.connect(this, "outlet", filtered, "inlet")
    return filtered
}

/** Distinct-element count of this set: a [CounterDelta][civictech.cell.data.CounterDelta] stream. */
fun <E> CellHandle.count(name: String): CellHandle {
    val counted = builder.spawn(name) { ref -> CountCell<E>(ref = ref) }
    builder.connect(this, "outlet", counted, "inlet")
    return counted
}

/**
 * Binary intersection: an element is live downstream iff live on both inputs.
 * This handle wires to the `left` inlet, [other] to the `right`.
 */
fun <E> CellHandle.intersect(name: String, other: CellHandle): CellHandle {
    val intersected = builder.spawn(name) { ref -> IntersectSetCell<E>(ref = ref) }
    builder.connect(this, "outlet", intersected, "left")
    builder.connect(other, "outlet", intersected, "right")
    return intersected
}

/**
 * Union of this set with [other]: both fan into the single `inlet` of a
 * [UnionSetCell], so duplicate deliveries dedup on the OR-set tag algebra.
 */
fun <E> CellHandle.union(name: String, other: CellHandle): CellHandle {
    val united = builder.spawn(name) { ref -> UnionSetCell<E>(ref = ref) }
    builder.connect(this, "outlet", united, "inlet")
    builder.connect(other, "outlet", united, "inlet")
    return united
}
