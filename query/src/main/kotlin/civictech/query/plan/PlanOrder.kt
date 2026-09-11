package civictech.query.plan

/**
 * Join-order structural facts for the reordering discipline (`[QRY1-PLAN-07]`, computenet-cab.3.4).
 *
 * **Whether the planner does any cost-based join reordering at all (settled by reading
 * [Planner.planRule], not assumed): it does not.** The join core is folded strictly
 * left-to-right over `positiveAtoms.indices.filter { !isSemiJoin[it] }` — the *rule body's
 * own textual order*, filtered only by which atoms are semijoin/antijoin/negation candidates
 * (a **different** rewrite class, decided before any join is built, never a join-vs-join
 * choice). Nothing on that path sorts atoms, scores a join order, or picks a pivot — no
 * comparator, no size estimate, no `minBy`/`maxBy`/`sortedBy` call sees an atom. So the
 * reordering guard this task owns collapses to the KDoc-only case the bead prescribes: the
 * join tree over the rule's core (non-semijoin, non-negated) atoms is **exactly** the
 * canonical assoc/comm form of the textual-order tree — because a left-deep chain built by
 * folding left-to-right *is* that canonical form by construction, with no rotation or
 * reassociation ever applied on top of it. `[QRY1-PLAN-07]` is therefore satisfied vacuously
 * by construction, not by an optimizer that happens to agree with textual order; the
 * structural test in `JoinReorderTest` pins the identity so a future cost-based rewrite
 * cannot silently start reordering without redwing it.
 *
 * The two functions below give that identity, and the other rewrite-class boundaries the
 * structural test checks, a name instead of ad hoc tree-walking duplicated per test:
 * - [leftDeepJoinLeaves] recovers the leaf sequence of a pure equi-join/cross-product spine,
 *   transparently unwrapping the [Select] nodes `Planner.pushBoundComparisons` interleaves
 *   into that spine (a comparison pushed onto a join is a *filter*, not a join reordering, so
 *   it must not count as a leaf or break the spine).
 * - [allNodes] is the same whole-tree walk `PlannerStructureTest`/`PlanAnalysesTest` each
 *   define privately, promoted here so `JoinReorderTest` does not need its own fourth copy and
 *   so a boundary check (does a [Join] ever appear *inside* an [AntiJoin]'s witness in a way
 *   that would mean negation got folded into the join spine?) can walk the whole tree, not
 *   just the spine.
 */
object PlanOrder {

    /** Every node reachable from [root], root first, in a deterministic pre-order walk. */
    fun allNodes(root: PlanNode): List<PlanNode> = listOf(root) + when (root) {
        is Scan -> emptyList()
        is Select -> allNodes(root.input)
        is Project -> allNodes(root.input)
        is Join -> allNodes(root.left) + allNodes(root.right)
        is SemiJoin -> allNodes(root.input) + allNodes(root.witness)
        is AntiJoin -> allNodes(root.input) + allNodes(root.witness)
        is Union -> root.inputs.flatMap { allNodes(it) }
        is Intersect -> allNodes(root.left) + allNodes(root.right)
        is Difference -> allNodes(root.left) + allNodes(root.right)
        is GroupAggregate -> allNodes(root.input)
        is OuterJoin -> allNodes(root.left) + allNodes(root.right)
    }

    /**
     * The left-to-right leaf sequence of the [Join] spine rooted at [node]: for a left-deep
     * chain `Join(Join(Join(A, B), C), D)` this returns `[A, B, C, D]` — the same order
     * [Planner.planRule] builds it in, atom by atom, in body order.
     *
     * A [Select] is transparent on the way down — both the ones `pushBoundComparisons` layers
     * on top of an accumulated join and the ones an individual atom's own node carries
     * ([Planner.planAtom]'s constant/repeated-variable equality filters) — because a filter
     * changes which rows survive, never which inputs are joined or in what shape. Any other
     * node kind ([Scan], [Project], [Union], [SemiJoin], [AntiJoin], [GroupAggregate],
     * [Intersect], [Difference], [OuterJoin]) is a leaf: the spine stops there by definition,
     * since none of those are how this planner represents "another join".
     */
    fun leftDeepJoinLeaves(node: PlanNode): List<PlanNode> {
        val unwrapped = unwrapSelects(node)
        return if (unwrapped is Join) {
            leftDeepJoinLeaves(unwrapped.left) + listOf(unwrapSelects(unwrapped.right))
        } else {
            listOf(unwrapped)
        }
    }

    private tailrec fun unwrapSelects(node: PlanNode): PlanNode =
        if (node is Select) unwrapSelects(node.input) else node
}
