package civictech.query.plan

import civictech.query.ast.Aggregate
import civictech.query.ast.Literal
import java.io.Serializable

/**
 * The closed logical-plan node vocabulary (epic computenet-cab §2.2, `[QRY1-PLAN-01]`):
 * exactly [Scan], [Select], [Project], [Join], [SemiJoin], [AntiJoin], [Union], [Intersect],
 * [Difference], [GroupAggregate], [OuterJoin] — no other case. Sealed so an exhaustive
 * `when` over a plan tree is checkable by the compiler, not by convention.
 *
 * Every node is pure data, `Serializable` and free of function-typed values
 * (`[QRY1-LANG-06]` extended from the AST to the plan) — the module's existing convention
 * (`query/build.gradle.kts`: plain `java.io.Serializable`, the
 * `kernel/src/main/kotlin/civictech/cell/graph/GraphDsl.kt:232` precedent, no
 * kotlinx-serialization). This task defines the representation only: computing correct
 * [provenance] and [keyPreserving]/[preservedKey] values is the planner's job
 * (`[QRY1-PLAN-05]`, `[QRY1-PLAN-06]`), not this file's — the `init` blocks below validate
 * only internal shape consistency (no duplicate output columns, the boolean/witness pairing),
 * never relational correctness.
 *
 * Each node carries the cab.3-D2 analysis slots directly (not inherited from this sealed
 * class's own constructor) so that every generated `data class` `equals`/`hashCode` — which
 * Kotlin derives only from a data class's *own* declared primary-constructor properties, not
 * from a superclass's — actually includes them. A node whose [provenance] or
 * [keyPreserving]/[preservedKey] were superclass-only state would silently drop out of
 * equality and round-trip comparisons, which is exactly the shape a serialization test is
 * supposed to catch.
 */
sealed class PlanNode : Serializable {

    /** The output columns (bound variable / head-position names) this node's rows carry, in order. */
    abstract val outputColumns: List<String>

    /**
     * The EDB relation names ([civictech.query.schema.Catalog] keys) this node's rows
     * descend from (`[QRY1-PLAN-05]`) — the provenance slot [QRY1-LOWER-08]/[QRY1-LOWER-09]'s
     * `emitOnFrontier` gating decision reads later. Computing it correctly is the planner's
     * job; this task only gives it a place to live.
     */
    abstract val provenance: Set<String>

    /**
     * `true` iff this node's output rows are key-preserving — some relation's declared row
     * key is injective into this node's output (`[QRY1-PLAN-06]`, `[QRY1-SEM-02]`'s decision
     * procedure). When `true`, [preservedKey] names the witnessing key attribute set so a
     * later rejection can say *which* key was lost, not just that one was.
     */
    abstract val keyPreserving: Boolean

    /**
     * The witnessing key attribute subset of [outputColumns] that makes [keyPreserving]
     * `true`. `null` when [keyPreserving] is `false` — never an empty set standing in for
     * "not preserving", following [civictech.query.schema.RelationSchema.rowKey]'s
     * null-means-absent convention rather than conflating the two states.
     */
    abstract val preservedKey: Set<String>?
}

private fun requireKeySlotConsistency(keyPreserving: Boolean, preservedKey: Set<String>?) {
    if (keyPreserving) {
        require(!preservedKey.isNullOrEmpty()) {
            "PlanNode.preservedKey must be a non-empty witnessing key when keyPreserving is " +
                "true, got $preservedKey"
        }
    } else {
        require(preservedKey == null) {
            "PlanNode.preservedKey must be null when keyPreserving is false — a node that is " +
                "not key-preserving names no witness. Got $preservedKey"
        }
    }
}

private fun requireNoDuplicateColumns(outputColumns: List<String>) {
    val duplicates = outputColumns.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
        "PlanNode.outputColumns declares duplicate column names $duplicates in $outputColumns"
    }
}

/**
 * One equi-join key: [left] column name from the left input, [right] column name from the
 * right input, joined on equality (`[QRY1-PLAN-02]`'s equi-join translation of shared
 * variables).
 */
data class JoinKey(val left: String, val right: String) : Serializable

/** The three outer-join shapes the frontend accepts (`[QRY1-LANG-04]`, epic §3.2's table row). */
enum class OuterJoinSide {
    LEFT, RIGHT, FULL
}

/**
 * A base-relation scan: [relation] names a [civictech.query.schema.Catalog] entry, and
 * [outputColumns] are the attribute names it exposes (`[24-SET-*]`, §3.2's EDB row). A scan
 * is its own provenance: `provenance == setOf(relation)`, always.
 */
data class Scan(
    val relation: String,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
        require(provenance == setOf(relation)) {
            "Scan($relation).provenance must be exactly {$relation}, got $provenance"
        }
    }
}

/**
 * A selection `σ`: [input] filtered by [condition], a comparison or constant-binding subgoal
 * over two [civictech.query.ast.Term]s (reusing the AST's [Literal.Comparison] rather than
 * inventing a parallel condition type — §3.2's "Selection σ (comparison / constant-binding
 * subgoal)" row covers both shapes with one AST node). [outputColumns] equals [input]'s —
 * selection narrows rows, not columns.
 */
data class Select(
    val input: PlanNode,
    val condition: Literal.Comparison,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * A projection `π`: [input] narrowed (and possibly reordered) to [outputColumns], a subset of
 * [input]'s own output columns (head construction / `flatMap`, §3.2).
 */
data class Project(
    val input: PlanNode,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * An equi-join `⋈` of [left] and [right] on [equiKeys] (`[QRY1-PLAN-02]`).
 *
 * **Cross-product representation (decided here, cab.3-D2):** a body with no shared variable
 * between two positive atoms plans as a `Join` with `equiKeys = emptyList()` — a cross
 * product, per §3.2's "Cross product (no shared variable)" row, is `crossProduct` /
 * `JoinSetCell` at the unit key, so it is representable as this same node with zero equi-join
 * conditions rather than as a distinct sealed case. `[QRY1-PLAN-02]` forbids representing a
 * *has-shared-variables* body as a filtered cross product where an equi-join is available;
 * an *empty* [equiKeys] list is reserved exclusively for the no-shared-variable case, never
 * used as a place to stash comparisons that could have been equi-join keys.
 */
data class Join(
    val left: PlanNode,
    val right: PlanNode,
    val equiKeys: List<JoinKey>,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * `input ⋉ witness` on [keys]: rows of [input] that have a matching row in [witness], with
 * [witness]'s columns not projected into [outputColumns] (existential positive subgoal not
 * itself projected, §3.2's `SemiJoinCell(negated = false)` row).
 */
data class SemiJoin(
    val input: PlanNode,
    val witness: PlanNode,
    val keys: List<JoinKey>,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * `input ▷ witness` on [keys]: rows of [input] that have **no** matching row in [witness]
 * (negated subgoal / `NOT EXISTS`, §3.2's `SemiJoinCell(negated = true)` row and
 * `EXCEPT DISTINCT`'s antijoin-on-identity-keys row).
 */
data class AntiJoin(
    val input: PlanNode,
    val witness: PlanNode,
    val keys: List<JoinKey>,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * `UNION DISTINCT` of [inputs] — one node per head predicate with more than one rule
 * (`[QRY1-LOWER-10]`), which is why [inputs] must carry at least two branches: a single-rule
 * head needs no union node at all, so a `Union` of fewer than two inputs is not a smaller
 * union, it is a planner bug.
 */
data class Union(
    val inputs: List<PlanNode>,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
        require(inputs.size >= 2) {
            "Union.inputs must have at least 2 branches (a single-rule head needs no union " +
                "node), got ${inputs.size}"
        }
    }
}

/** `INTERSECT DISTINCT` of [left] and [right] (`[24-OP-INTERSECT-01]`). */
data class Intersect(
    val left: PlanNode,
    val right: PlanNode,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/** `EXCEPT DISTINCT` of [left] minus [right] (`[24-OP-SEMIJOIN-01]`'s antijoin-on-identity row). */
data class Difference(
    val left: PlanNode,
    val right: PlanNode,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * `GROUP BY groupByColumns … aggregate`, reusing the AST's [Aggregate]/`AggregateKind`
 * closed vocabulary rather than a parallel plan-level enum. [aggregatedColumn] is the input
 * column the aggregate is computed over — `null` for an aggregate that needs none (e.g. a
 * bare `count` over whole groups). [outputColumn] names the aggregate result in
 * [outputColumns]. An empty [groupByColumns] is a scalar aggregate over the whole relation
 * (§3.2's "Scalar aggregate (no GROUP BY)" row), not an error.
 */
data class GroupAggregate(
    val input: PlanNode,
    val groupByColumns: List<String>,
    val aggregatedColumn: String?,
    val aggregate: Aggregate,
    val outputColumn: String,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
        require(groupByColumns.toSet().size == groupByColumns.size) {
            "GroupAggregate.groupByColumns must not repeat a column, got $groupByColumns"
        }
    }
}

/**
 * A `LEFT`/`RIGHT`/`FULL OUTER JOIN` of [left] and [right] on [keys], per [side]
 * (`[QRY1-LANG-04]`, §3.2's outer-join row — lowers to a `GraphBuilder`
 * `leftJoin`/`rightJoin`/`fullJoin` composition, not a single cell).
 */
data class OuterJoin(
    val left: PlanNode,
    val right: PlanNode,
    val keys: List<JoinKey>,
    val side: OuterJoinSide,
    override val outputColumns: List<String>,
    override val provenance: Set<String>,
    override val keyPreserving: Boolean,
    override val preservedKey: Set<String>? = null,
) : PlanNode() {
    init {
        requireNoDuplicateColumns(outputColumns)
        requireKeySlotConsistency(keyPreserving, preservedKey)
    }
}

/**
 * The whole-query plan: one root [PlanNode] per head predicate name, keyed the same way
 * `CompiledQuery.outputs` and `BatchEvaluator.evaluate` are (epic §2.3) so lowering and the
 * reference evaluator can both walk this map directly without a second index. Pure data,
 * `Serializable` (`[QRY1-LANG-06]`); shape only — building one from a [civictech.query.ast.Query]
 * is the planner's job (`Planner`, not built by this task).
 */
data class LogicalPlan(val roots: Map<String, PlanNode>) : Serializable
