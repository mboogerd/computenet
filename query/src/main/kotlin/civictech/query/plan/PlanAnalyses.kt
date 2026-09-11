package civictech.query.plan

/**
 * The two per-node plan analyses of epic computenet-cab §4.2 — provenance
 * (`[QRY1-PLAN-05]`) and key-preservation (`[QRY1-PLAN-06]`, cab.3-D2) — as propagation
 * rules stated once, here, rather than inline at each construction site in [Planner].
 *
 * They live in their own file because they are *read* by features that never call the
 * planner: the lowering feature's `emitOnFrontier` gating (`[QRY1-LOWER-08]`, the
 * shared-source diamond of BS-6) reads [provenanceOf], and the rejection feature's
 * `[QRY1-SEM-02]`/`BAG_SEMANTICS_REQUIRED` decision reads the key-preservation pair and
 * names [KeyAnalysis.preservedKey] in its message (BS-7). Those features need the *reasoning*
 * below, not just the values, so the reasoning is written down next to the code that
 * implements it and nowhere else.
 *
 * ## What each analysis claims
 *
 * **Provenance** of a node is the set of EDB ([civictech.query.schema.Catalog]-declared)
 * relation names whose contents can affect that node's output. A [Scan] is `{relation}`;
 * every other node is the union over *all* of its inputs. "All" deliberately includes the
 * witness side of a [SemiJoin]/[AntiJoin] and every branch of a [Union]: an antijoin's
 * output changes when its witness changes, so a consumer deciding whether two nodes can
 * observe a change from the same source must see that witness relation. An IDB predicate's
 * provenance is the union over its rules (cab.3-D2) — which falls out of the same rule,
 * since a multi-rule head is a [Union] of its rules and a single-rule head *is* its rule.
 *
 * **Key-preservation** claims that the map from source rows to this node's output rows is
 * injective, witnessed by a named attribute set: no two rows that a declared row key
 * distinguishes are collapsed into one output row by the time they reach here. `true` with
 * witness `K` means "`K` is a subset of this node's output columns, it derives from some
 * relation's declared row key, and it determines the node's output row". `false` means the
 * claim is **not established** — either because the node genuinely collapses rows, or
 * because no declared key was available to start from. It is never a proof that the node's
 * output has no key at all; a consumer must not read `false` as evidence about the relation.
 *
 * ## Per-node reasoning (`[QRY1-PLAN-06]`; each of these is a decision, not a default)
 *
 * - **[Scan]** — the declared [civictech.query.schema.RelationSchema.rowKey], mapped
 *   positionally onto the node's column names. `rowKey == null` (a relation with no declared
 *   key — a valid catalog entry, `[QRY1-API-05]`) yields `false` with no witness: no key
 *   exists to be preserved, so there is nothing to claim and nothing to crash on.
 *   A key attribute that lands on a *synthetic* column (a constant or repeated-variable
 *   position, see [Planner]'s column-naming note) is still carried here; it is the
 *   projection above that withdraws the claim when that column is dropped. That is
 *   conservative in a knowable direction: `r(x, x)` over a relation keyed on both attributes
 *   is in fact keyed by `x` alone in the output, and this planner will report `false`.
 * - **[Select] / [SemiJoin] / [AntiJoin]** — row filters. They remove rows and change no
 *   column, and removing rows cannot merge two surviving rows, so injectivity survives
 *   untouched: propagate the (filtered) input's annotation verbatim.
 * - **[Project]** — keeps the input's claim exactly when every witnessing column survives
 *   the projection, and withdraws it otherwise. Dropping a non-key column can merge two
 *   rows that differed only there; dropping a key column is precisely what BS-7 is about.
 *   Note the asymmetry: a surviving witness is still a witness even though the projection
 *   is duplicate-eliminating, because distinct key values stay distinct.
 * - **[Join]** (equi-join and the zero-key cross product alike) — an output row is the pair
 *   of the two input rows that produced it, so the pair `(K_left, K_right)` determines it
 *   whenever each side's own key determines its row. Key-preserving iff **both** inputs are,
 *   with witness `K_left ∪ K_right`, and only when both witnesses actually appear in the
 *   node's output columns (a shared join column appears under one name on both sides, so it
 *   satisfies both). If either input's claim is unestablished the product's is too — not
 *   because the join collapses rows, but because an un-keyed side leaves the pair unnamed.
 * - **[Union]** — always `false`, and this one is a genuine negative rather than a gap.
 *   `UNION DISTINCT` (`[QRY1-LOWER-10]`) merges tuples that are equal across branches, which
 *   is exactly a collapse of two source rows into one output row; and even when no tuple
 *   repeats, two branches can carry the same key value with different remaining columns, so
 *   no branch's witness determines a union row. There is no branch-level condition that
 *   rescues it in general, so no conditional rule is offered here rather than a wrong one.
 * - **[Intersect]** — `INTERSECT DISTINCT` outputs a subset of the tuples of *each* side,
 *   so any witness of either side is still a witness. Takes the left input's claim when it
 *   has one and the right's otherwise; the choice is arbitrary between two equally valid
 *   witnesses, and the left-first order keeps the result a function of the node alone.
 * - **[Difference]** — `EXCEPT DISTINCT` outputs a subset of the left side's tuples and
 *   nothing from the right, so it propagates the left input's claim and ignores the right's
 *   entirely. (The right side still contributes to provenance: removing a row is an
 *   observable effect.)
 * - **[GroupAggregate]** — key-preserving iff the input's witness survives *into the
 *   grouping columns*, i.e. `K ⊆ groupByColumns`. When it does, every group holds at most
 *   one input row, so nothing is collapsed and `K` still determines the output row.
 *   Otherwise `false`: aggregation deliberately folds many input rows into one, which is the
 *   collapse this analysis exists to detect. Note what is deliberately *not* claimed: the
 *   group-by tuple is always a key of a [GroupAggregate]'s own output in the ordinary
 *   relational sense, but it is not a *declared row key preserved from a source*, and this
 *   analysis answers only the latter question. A consumer that wants the former must derive
 *   it from [GroupAggregate.groupByColumns] itself. A scalar aggregate (empty
 *   `groupByColumns`) reports `false` for the additional reason that its output has at most
 *   one row and therefore no non-empty witness to name — and [PlanNode.preservedKey] forbids
 *   an empty set standing in for a witness.
 * - **[OuterJoin]** — the same pair argument as [Join], on all three
 *   [OuterJoinSide]s. Null-padding adds rows, it never merges two: an unmatched left row
 *   yields `(K_left, null)`, which collides neither with another unmatched left row (their
 *   `K_left` differ) nor with a matched row (whose right half is present). So the witness is
 *   `K_left ∪ K_right` under the same both-sides-established condition as [Join].
 *   **Caveat for consumers:** unlike [Join]'s, an outer join's witness columns can be *null*
 *   in padded rows. The set is injective, but a consumer that needs a totally non-null key —
 *   a keyed store, say — must treat an [OuterJoin]'s witness as nullable rather than assume
 *   [Join]'s shape.
 *
 * ## Determinism (cab.3-D1)
 *
 * Every set returned here is a `LinkedHashSet` built from a sorted list, so two runs over
 * equal inputs produce sets that are equal *and* iterate identically — a plan's `equals` and
 * any order-sensitive consumer both stay a function of the query alone.
 */
object PlanAnalyses {

    /** The unestablished key claim: not key-preserving, no witness. */
    val NO_KEY: KeyAnalysis = KeyAnalysis(keyPreserving = false, preservedKey = null)

    /**
     * The union of every input's [PlanNode.provenance] (`[QRY1-PLAN-05]`) — the propagation
     * rule for every node that is not a [Scan]. Pass *all* inputs, witnesses and union
     * branches included; see this file's KDoc for why a witness counts.
     */
    fun provenanceOf(inputs: List<PlanNode>): Set<String> =
        deterministicSet(inputs.flatMap { it.provenance })

    /**
     * A [Scan]'s key claim: [rowKey] (the relation's declared key, `null` when it has none)
     * translated from attribute names to the column names [columns] gives those positions,
     * using [attributeNames] for the positional correspondence.
     */
    fun scanKey(rowKey: Set<String>?, attributeNames: List<String>, columns: List<String>): KeyAnalysis {
        if (rowKey == null) return NO_KEY
        require(attributeNames.size == columns.size) {
            "Scan column list $columns does not match the relation's attributes $attributeNames"
        }
        val positionOf = attributeNames.withIndex().associate { (index, name) -> name to index }
        val witness = rowKey.sorted().map { attribute ->
            val position = requireNotNull(positionOf[attribute]) {
                "Row key attribute '$attribute' is not declared on this relation ($attributeNames)"
            }
            columns[position]
        }
        return KeyAnalysis(keyPreserving = true, preservedKey = deterministicSet(witness))
    }

    /**
     * The key claim of a row filter over [input] — [Select], [SemiJoin], [AntiJoin]: the
     * input's own claim, unchanged. Filtering removes rows and merges none.
     */
    fun filterKey(input: PlanNode): KeyAnalysis = input.keyAnalysis()

    /**
     * The key claim of a projection of [input] onto [outputColumns]: kept iff every
     * witnessing column survives, withdrawn otherwise (the BS-7 case).
     */
    fun projectKey(input: PlanNode, outputColumns: List<String>): KeyAnalysis {
        val witness = input.preservedKey ?: return NO_KEY
        return if (outputColumns.containsAll(witness)) KeyAnalysis(true, witness) else NO_KEY
    }

    /**
     * The key claim of a [Join] (or cross product) of [left] and [right] emitting
     * [outputColumns]: the union of the two witnesses, when both sides have one and both
     * survive into the output.
     */
    fun joinKey(left: PlanNode, right: PlanNode, outputColumns: List<String>): KeyAnalysis =
        pairKey(left, right, outputColumns)

    /**
     * The key claim of a [Union] of [inputs]: never established. [inputs] is taken only so a
     * caller cannot silently pass the wrong node's children; see this file's KDoc for why no
     * branch condition rescues the claim.
     */
    @Suppress("UNUSED_PARAMETER")
    fun unionKey(inputs: List<PlanNode>): KeyAnalysis = NO_KEY

    /**
     * The key claim of an [Intersect] of [left] and [right] emitting [outputColumns]: either
     * side's witness is valid over a subset of both, so the left's is taken when it has one
     * and the right's otherwise.
     */
    fun intersectKey(left: PlanNode, right: PlanNode, outputColumns: List<String>): KeyAnalysis {
        val fromLeft = projectKeyOf(left, outputColumns)
        return if (fromLeft.keyPreserving) fromLeft else projectKeyOf(right, outputColumns)
    }

    /**
     * The key claim of a [Difference] emitting [outputColumns]: the [left] side's, since the
     * output is a subset of the left side's tuples and carries none of the right's.
     */
    fun differenceKey(left: PlanNode, outputColumns: List<String>): KeyAnalysis =
        projectKeyOf(left, outputColumns)

    /**
     * The key claim of a [GroupAggregate] over [input] grouping by [groupByColumns]:
     * established only when the input's witness survives into the grouping columns, which is
     * exactly the case where each group holds at most one input row.
     */
    fun groupAggregateKey(input: PlanNode, groupByColumns: List<String>): KeyAnalysis {
        val witness = input.preservedKey ?: return NO_KEY
        return if (groupByColumns.containsAll(witness)) KeyAnalysis(true, witness) else NO_KEY
    }

    /**
     * The key claim of an [OuterJoin] emitting [outputColumns]: the same paired witness as
     * [joinKey], on every [OuterJoinSide] — null-padding adds rows and merges none. The
     * witness columns may be null-valued in padded rows; see this file's KDoc.
     */
    fun outerJoinKey(left: PlanNode, right: PlanNode, outputColumns: List<String>): KeyAnalysis =
        pairKey(left, right, outputColumns)

    /** `(K_left ∪ K_right)`, when both sides are established and both survive [outputColumns]. */
    private fun pairKey(left: PlanNode, right: PlanNode, outputColumns: List<String>): KeyAnalysis {
        val leftWitness = left.preservedKey ?: return NO_KEY
        val rightWitness = right.preservedKey ?: return NO_KEY
        val witness = deterministicSet(leftWitness + rightWitness)
        return if (outputColumns.containsAll(witness)) KeyAnalysis(true, witness) else NO_KEY
    }

    /** [node]'s own claim, narrowed to [outputColumns] — the subset-of-tuples propagation. */
    private fun projectKeyOf(node: PlanNode, outputColumns: List<String>): KeyAnalysis =
        projectKey(node, outputColumns)

    /** A set whose iteration order is a function of its contents alone (cab.3-D1). */
    private fun deterministicSet(names: Collection<String>): Set<String> =
        LinkedHashSet(names.distinct().sorted())
}

/**
 * A node's key-preservation answer: the [PlanNode.keyPreserving] flag and the
 * [PlanNode.preservedKey] witness that has to accompany it, as one value so the pair cannot
 * be constructed half-updated. The same invariant [PlanNode] enforces holds here: a witness
 * is present and non-empty exactly when [keyPreserving] is `true`.
 */
data class KeyAnalysis(val keyPreserving: Boolean, val preservedKey: Set<String>?) {
    init {
        if (keyPreserving) {
            require(!preservedKey.isNullOrEmpty()) {
                "A key-preserving KeyAnalysis must name a non-empty witness, got $preservedKey"
            }
        } else {
            require(preservedKey == null) {
                "A non-key-preserving KeyAnalysis names no witness, got $preservedKey"
            }
        }
    }
}

/** This node's recorded key claim, as the pair the [PlanAnalyses] rules pass around. */
fun PlanNode.keyAnalysis(): KeyAnalysis = KeyAnalysis(keyPreserving, preservedKey)
