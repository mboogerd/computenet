package civictech.cell.graph

import civictech.cell.data.FlatMapSetCell
import civictech.cell.data.JoinSetCell
import civictech.cell.data.SemiJoinCell
import civictech.cell.data.UnionSetCell

/**
 * Outer joins as graph compositions (M11.6): no new cell semantics — matched
 * rows from the relational join ([JoinSetCell]) union with null-completed
 * unmatched rows from the antijoin ([SemiJoinCell] + [FlatMapSetCell]).
 * Eventually consistent, not glitch-free: while opposing updates are in
 * flight a row may transiently appear as both `(a, null)` and `(a, b)` or
 * neither; converges at idle (22's wrapper is the per-consumer remedy).
 * Build an atomic outer-join cell only when a real consumer can't tolerate
 * the transient.
 */
fun <A, B, K, C> GraphBuilder.leftJoin(
    name: String,
    left: CellHandle,
    right: CellHandle,
    leftKey: (A) -> K,
    rightKey: (B) -> K,
    combine: (A, B?) -> C,
): CellHandle {
    val matched = spawn("$name-matched") {
        JoinSetCell(leftKey = leftKey, rightKey = rightKey, combine = { a: A, b: B -> combine(a, b) })
    }
    val unmatched = spawn("$name-unmatched") {
        SemiJoinCell(leftKey = leftKey, rightKey = rightKey, negated = true)
    }
    val nullCompleted = spawn("$name-null") { FlatMapSetCell(f = { a: A -> listOf(combine(a, null)) }) }
    val merged = spawn(name) { UnionSetCell<C>() }
    connect(left, "outlet", matched, "left")
    connect(right, "outlet", matched, "right")
    connect(left, "outlet", unmatched, "left")
    connect(right, "outlet", unmatched, "right")
    connect(unmatched, "outlet", nullCompleted, "inlet")
    connect(matched, "outlet", merged, "inlet")
    connect(nullCompleted, "outlet", merged, "inlet")
    return merged
}

/** Right outer join: the left join with sides swapped. */
fun <A, B, K, C> GraphBuilder.rightJoin(
    name: String,
    left: CellHandle,
    right: CellHandle,
    leftKey: (A) -> K,
    rightKey: (B) -> K,
    combine: (A?, B) -> C,
): CellHandle = leftJoin(
    name, left = right, right = left,
    leftKey = rightKey, rightKey = leftKey,
    combine = { b: B, a: A? -> combine(a, b) },
)

/** Full outer join: matched ∪ left-only(null-completed) ∪ right-only(null-completed). */
fun <A, B, K, C> GraphBuilder.fullJoin(
    name: String,
    left: CellHandle,
    right: CellHandle,
    leftKey: (A) -> K,
    rightKey: (B) -> K,
    combine: (A?, B?) -> C,
): CellHandle {
    val matched = spawn("$name-matched") {
        JoinSetCell(leftKey = leftKey, rightKey = rightKey, combine = { a: A, b: B -> combine(a, b) })
    }
    val leftOnly = spawn("$name-left-only") {
        SemiJoinCell(leftKey = leftKey, rightKey = rightKey, negated = true)
    }
    val leftNull = spawn("$name-left-null") { FlatMapSetCell(f = { a: A -> listOf(combine(a, null)) }) }
    val rightOnly = spawn("$name-right-only") {
        SemiJoinCell(leftKey = rightKey, rightKey = leftKey, negated = true)
    }
    val rightNull = spawn("$name-right-null") { FlatMapSetCell(f = { b: B -> listOf(combine(null, b)) }) }
    val merged = spawn(name) { UnionSetCell<C>() }
    connect(left, "outlet", matched, "left")
    connect(right, "outlet", matched, "right")
    connect(left, "outlet", leftOnly, "left")
    connect(right, "outlet", leftOnly, "right")
    connect(right, "outlet", rightOnly, "left")
    connect(left, "outlet", rightOnly, "right")
    connect(leftOnly, "outlet", leftNull, "inlet")
    connect(rightOnly, "outlet", rightNull, "inlet")
    connect(matched, "outlet", merged, "inlet")
    connect(leftNull, "outlet", merged, "inlet")
    connect(rightNull, "outlet", merged, "inlet")
    return merged
}
