package civictech.query.plan

import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Definition
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.schema.Catalog

/**
 * AST -> [LogicalPlan] translation (epic computenet-cab §4.2).
 *
 * The input is assumed **well-formed**: safety analysis has already passed, so every
 * variable in a negated atom, a comparison, or a head is bound by some positive body atom,
 * and no head predicate is recursive. Refusing an ill-formed query is the rejection
 * feature's front door (computenet-cab.2), not this file's — the `require` calls below are
 * fail-fast guards against a planner bug or an unsupported surface shape, not the query
 * language's diagnostics.
 *
 * Translation rules implemented here, each settled by the epic:
 *
 * - Shared variables between two positive atoms become an equi-join on those variables; a
 *   body with no shared variable becomes a cross product, represented as [Join] with an
 *   empty `equiKeys` (the representation [Join]'s own KDoc fixes). A shared-variable pair is
 *   **never** planned as a cross product with an equality [Select] on top (`[QRY1-PLAN-02]`).
 * - Each comparison literal is placed at the lowest node at which all its variables are
 *   bound: comparisons over one atom's variables sit directly on that atom's node, below any
 *   join it later participates in (`[QRY1-PLAN-03]`).
 * - A positive atom whose variables are all bound by other positive atoms and none of which
 *   reach the head plans as [SemiJoin], not [Join] (`[QRY1-PLAN-04]`, citing
 *   `[24-OP-SEMIJOIN-01]` in `doc/spec/20-dataflow-semantics/24-data-cells.md`).
 * - A negated atom plans as [AntiJoin] on its shared variables.
 * - An aggregate-annotated head plans as [GroupAggregate] over the body plan.
 * - A head projects its variables via [Project].
 * - A body atom over an IDB predicate (one defined by rules rather than declared in the
 *   [Catalog]) takes that predicate's plan as its input; a head predicate with more than one
 *   rule combines its rules by [Union] (set semantics, `[QRY1-LOWER-10]`).
 * - A [Definition] (`define h(...) := expr.`, `[QRY1-LANG-04]`) plans its [RelationalExpr]:
 *   `union`/`intersect`/`except` become [Union]/[Intersect]/[Difference] and an outer join
 *   becomes [OuterJoin] (computenet-cab.4.6). A defined head is a root exactly like a rule
 *   head, and a rule body atom or a definition leaf naming it takes its plan as input. A
 *   predicate defined by two definitions, or by a rule and a definition, is a fail-fast
 *   `require`, as is an `ALL` set operation — bag semantics is `BAG_SEMANTICS_REQUIRED`'s
 *   refusal (`[QRY1-SEM-04]`), never silently planned as the `DISTINCT` form.
 *
 * **Definition column naming.** A definition's head names the derived relation's columns
 * **positionally**, the way a SQL view's column list does: `define h(Y, X) := a(X, Y).` makes
 * `h`'s first column `a`'s first column, named `Y` — the head's variable names are *names*,
 * not a by-name projection of the expression. An expression's own columns are defined
 * recursively: a leaf `Relation(atom)` exposes the atom's distinct variables in
 * first-occurrence order (exactly [PlanningContext.planAtom]'s output); a set operation
 * exposes its left operand's columns, and its right operand must have the same arity and is
 * matched to them positionally; an outer join exposes its left operand's columns followed by
 * its right operand's columns that are not its key columns. Since there is no rename node,
 * every rename is a substitution applied to the leaf atoms *before* they are planned — the
 * order [renameRule] already uses — in two steps: [normalizeExpr] makes the operand columns
 * agree (a set operation's right operand is substituted to the left's names; an outer join's
 * right key variable is substituted to its left key variable, so the node's keys are
 * `JoinKey(k, k)` over one merged column, and a right non-key variable that merely shares a
 * name with a left column is renamed apart, because only the `on` clause joins), and then the
 * whole normalized expression is substituted positionally to the caller's head names. After
 * normalization every variable of an expression is one of its columns, so that final
 * substitution is total and injective and cannot capture a name.
 *
 * **Determinism (cab.3-D1).** Nothing on the planning path iterates a hash-ordered
 * collection. Rule bodies, atom terms and head terms are `List`s and are consumed in their
 * declared order; head predicates are planned in sorted name order; every [PlanNode.provenance]
 * set is built from a sorted list into a `LinkedHashSet`. The only `Map`s read here —
 * [Catalog.relations], the rules-by-head index and the definitions-by-head index — are used
 * for point lookups, never iterated, and the substitution maps of definition renaming are
 * likewise only looked up.
 *
 * **Column naming.** A plan node's `outputColumns` are *variable* names, not relation
 * attribute names, so that a join key is the shared variable itself. A [Scan]'s columns are
 * therefore the atom's variable names positionally, with a scoped synthetic name
 * (`!`-prefixed, which no surface identifier can be) standing in at a position holding a
 * constant or a repeated variable; the resulting equality is then a [Select] directly above
 * the [Scan] and the synthetic column is projected away. That keeps [Project] a genuine
 * subset-narrowing of its input, as its KDoc requires — this planner never uses [Project] to
 * rename a column, and needs no rename node, because an IDB predicate consumed by another
 * rule is planned with its head variables already substituted to the *caller's* names.
 *
 * **Analysis slots.** [PlanNode.provenance] (`[QRY1-PLAN-05]`) and
 * [PlanNode.keyPreserving]/[PlanNode.preservedKey] (`[QRY1-PLAN-06]`) are populated at every
 * construction site below by the propagation rules of [PlanAnalyses], which owns them: this
 * file decides *what node* to build, that one decides *what the node's analyses say*, and
 * the per-node reasoning — including which `false`s are genuine negatives and which are
 * unestablished claims — is written out in its KDoc. No analysis value is computed inline
 * here, so a consumer reading a plan node and a consumer re-deriving the rule are reading
 * the same statement of them.
 *
 * **Aggregate convention.** [civictech.query.ast.Aggregate] carries no column reference, so
 * the aggregated column has to come from the head's shape: this planner takes the **last**
 * head variable as the aggregate's input and result column, and the preceding head variables
 * as the grouping columns. That is a convention of this planner, not of the AST; a surface
 * syntax that lets a rule name the aggregated column explicitly would need an AST change
 * first.
 *
 * **Determinism audit (computenet-cab.3.4, extending cab.3-D1 over this file and
 * [PlanAnalyses] as amended by cab.3.3).** Every collection actually *iterated* on this
 * planning path is re-checked here, not assumed:
 * - [query]'s `rules` and each [Rule.body]/[Atom.terms] are `List`s consumed in declared
 *   order — untouched by this file.
 * - `rulesByHead` (built in [Planner.plan]) is a `LinkedHashMap`, but it is never iterated by
 *   insertion order: [Planner.plan] walks `rulesByHead.keys.sorted()`, so the root map's
 *   iteration order is a function of the predicate *names* alone. Per-predicate rule lists
 *   inside it are read by point lookup (`rulesByHead[predicate]`/`rulesByHead.getValue`) and
 *   preserve the caller's own list order — never re-sorted, because a multi-rule head's
 *   [Union] branch order is part of its observable shape and must track the rules' own
 *   textual order, not a hash.
 * - `definitionsByHead` (built in [Planner.plan], computenet-cab.4.6) is a `LinkedHashMap`
 *   read only by point lookup; the roots loop walks the *sorted* union of its keys with
 *   `rulesByHead`'s, so a definitions-bearing query's root order is still a function of names
 *   alone. `PlannerDeterminismTest` extends its two-seed check to such a query.
 * - [PlanningContext.catalog]'s `relations` map ([civictech.query.schema.Catalog]) is read
 *   only by point lookup (`catalog.relations[atom.predicate]`) — never iterated — so its
 *   concrete `Map` implementation and insertion order are both irrelevant to the plan built
 *   from it. `PlannerDeterminismTest` exercises this directly: two [Query]s built from
 *   differently-ordered `Catalog` maps and differently-ordered (but per-predicate-order-
 *   preserving) rule lists must still plan identically.
 * - `classifySemiJoinAtoms`'s one `HashSet` (`binders.flatMapTo(HashSet())`) is queried only
 *   by `containsAll` and never iterated — this is the read cab.3.2's reviewer flagged as a
 *   lead, re-confirmed here as still true after cab.3.3's amendments, which did not touch this
 *   function.
 * - `positionalColumns`'s `LinkedHashSet<String>` (`seen`) is used for `add`/membership only,
 *   in insertion order, which is itself a function of `atom.terms`' own declared order — not
 *   a hash order.
 * - Every `Set` this file constructs ([Scan.provenance] via [provenanceOf] and friends) comes
 *   from [PlanAnalyses], whose own KDoc records the same discipline: every returned set is a
 *   `LinkedHashSet` built from a `.sorted()` list.
 *
 * No planning-path collection is iterated in hash order; nothing above needed a fix.
 *
 * **Reordering (`[QRY1-PLAN-07]`, computenet-cab.3.4).** This planner performs **no
 * cost-based join reordering**: the join core in [PlanningContext.planRule] is folded
 * strictly left-to-right over the rule body's own atom order (step 2's `coreIndices` loop),
 * with no comparator, size estimate, or pivot choice anywhere on the path. The guard is
 * therefore the vacuous case the epic anticipates — the join tree is, by construction, the
 * canonical left-deep assoc/comm form of the textual-order tree — stated precisely and
 * checked structurally in [PlanOrder] and `JoinReorderTest`.
 */
object Planner {

    /**
     * Translates [query] into a [LogicalPlan] with one root per head predicate — every rule
     * head and every definition head.
     */
    fun plan(query: Query): LogicalPlan {
        val rulesByHead = LinkedHashMap<String, MutableList<Rule>>()
        for (rule in query.rules) {
            rulesByHead.getOrPut(rule.head.predicate) { mutableListOf() } += rule
        }
        val definitionsByHead = LinkedHashMap<String, Definition>()
        for (definition in query.definitions) {
            val predicate = definition.head.predicate
            require(predicate !in rulesByHead) {
                "Planner does not support predicate '$predicate' defined by both a rule and a " +
                    "define statement; a head predicate has exactly one kind of definition."
            }
            require(definitionsByHead.put(predicate, definition) == null) {
                "Planner does not support predicate '$predicate' defined by more than one " +
                    "define statement; combine the expressions with union instead."
            }
        }
        val context = PlanningContext(query.catalog, rulesByHead, definitionsByHead)
        val roots = LinkedHashMap<String, PlanNode>()
        // Sorted so the root map's own iteration order is a function of the predicate names
        // alone, not of insertion or hash order (cab.3-D1). The two key sets are disjoint
        // (checked above), so this is every head exactly once.
        for (predicate in (rulesByHead.keys + definitionsByHead.keys).sorted()) {
            val canonicalHeadNames = rulesByHead[predicate]?.let { headVariables(it.first().head) }
                ?: headVariables(definitionsByHead.getValue(predicate).head)
            roots[predicate] = context.planPredicate(
                predicate = predicate,
                headNames = canonicalHeadNames,
                scopeTag = "",
                stack = emptyList(),
            )
        }
        return LogicalPlan(roots)
    }
}

/** Distinct variable names of [atom]'s head positions, in declaration order. */
private fun headVariables(atom: Atom): List<String> {
    val names = atom.terms.map { term ->
        require(term is Term.Var) {
            "Planner does not support a constant in a head: ${atom.predicate} carries $term. " +
                "A head position must be a variable (bound by a positive body atom, or naming " +
                "a definition's column)."
        }
        term.name
    }
    require(names.distinct().size == names.size) {
        "Planner does not support a repeated variable in a head: ${atom.predicate}($names)."
    }
    return names
}

/** Distinct variable names appearing in [atom]'s terms, in first-occurrence order. */
private fun variablesOf(atom: Atom): List<String> =
    atom.terms.filterIsInstance<Term.Var>().map { it.name }.distinct()

/** Distinct variable names appearing in [comparison], in first-occurrence order. */
private fun variablesOf(comparison: Literal.Comparison): List<String> =
    listOf(comparison.left, comparison.right).filterIsInstance<Term.Var>().map { it.name }.distinct()

/** Union of every input's provenance ([PlanAnalyses.provenanceOf]). */
private fun provenanceOf(inputs: List<PlanNode>): Set<String> = PlanAnalyses.provenanceOf(inputs)

/** [input] filtered by [condition]; a selection narrows rows, not columns or key injectivity. */
private fun select(input: PlanNode, condition: Literal.Comparison): Select {
    val key = PlanAnalyses.filterKey(input)
    return Select(
        input = input,
        condition = condition,
        outputColumns = input.outputColumns,
        provenance = input.provenance,
        keyPreserving = key.keyPreserving,
        preservedKey = key.preservedKey,
    )
}

/**
 * Narrows [input] to [columns] (a subset of its own output columns), skipping the node
 * entirely when the projection would be the identity — so an atom that already exposes
 * exactly its variables stays a bare [Scan] rather than acquiring a no-op [Project].
 */
private fun projectTo(input: PlanNode, columns: List<String>): PlanNode {
    if (input.outputColumns == columns) return input
    require(input.outputColumns.containsAll(columns)) {
        "Project columns $columns are not a subset of input columns ${input.outputColumns}"
    }
    val key = PlanAnalyses.projectKey(input, columns)
    return Project(
        input = input,
        outputColumns = columns,
        provenance = input.provenance,
        keyPreserving = key.keyPreserving,
        preservedKey = key.preservedKey,
    )
}

/**
 * Everything one statement's translation needs: the [catalog] (EDB predicates) and the
 * rules-by-head and definitions-by-head indexes (IDB predicates). All are read by point
 * lookup only.
 */
private class PlanningContext(
    val catalog: Catalog,
    val rulesByHead: Map<String, List<Rule>>,
    val definitionsByHead: Map<String, Definition>,
) {

    /**
     * Plans head predicate [predicate] so that its output columns are exactly [headNames],
     * combining its rules by [Union] when it has more than one (`[QRY1-LOWER-10]`), or
     * planning its one [Definition]. [stack] carries the predicates currently being expanded,
     * so a recursive definition — through rules, definitions or both — fails loudly here
     * rather than looping.
     */
    fun planPredicate(
        predicate: String,
        headNames: List<String>,
        scopeTag: String,
        stack: List<String>,
    ): PlanNode {
        require(predicate !in stack) {
            "Planner does not support recursion: ${(stack + predicate).joinToString(" -> ")}. " +
                "Recursive rule sets are rejected upstream by the safety/rejection feature."
        }
        definitionsByHead[predicate]?.let { definition ->
            return planDefinition(definition, headNames, "$scopeTag$predicate#def/", stack + predicate)
        }
        val rules = rulesByHead[predicate]
        require(!rules.isNullOrEmpty()) { "No rules define predicate '$predicate'" }
        val branches = rules.mapIndexed { index, rule ->
            val renamed = renameRule(rule, headNames, "$scopeTag$predicate#$index/")
            planRule(renamed, "$scopeTag$predicate#$index/", stack + predicate)
        }
        return union(branches)
    }

    /** Plans one rule; the result's output columns are the rule's head variables, in order. */
    fun planRule(rule: Rule, scopeTag: String, stack: List<String>): PlanNode {
        val headVars = headVariables(rule.head)

        val positiveAtoms = ArrayList<Atom>()
        val negatedAtoms = ArrayList<Atom>()
        val comparisons = ArrayList<Literal.Comparison>()
        for (literal in rule.body) {
            when (literal) {
                is Literal.Positive -> positiveAtoms += literal.atom
                is Literal.Negated -> negatedAtoms += literal.atom
                is Literal.Comparison -> comparisons += literal
            }
        }
        require(positiveAtoms.isNotEmpty()) {
            "Rule ${rule.head.predicate} has no positive body atom; a safe rule always has one."
        }

        val atomVariables = positiveAtoms.map { variablesOf(it) }
        val isSemiJoin = classifySemiJoinAtoms(atomVariables, headVars.toSet())

        // Comparison placement bookkeeping: a comparison is attached exactly once, at the
        // first (hence lowest) node whose columns bind all of its variables.
        val placed = BooleanArray(comparisons.size)

        fun pushBoundComparisons(node: PlanNode): PlanNode {
            var current = node
            val bound = current.outputColumns.toSet()
            for (index in comparisons.indices) {
                if (placed[index]) continue
                if (!bound.containsAll(variablesOf(comparisons[index]))) continue
                placed[index] = true
                current = select(current, comparisons[index])
            }
            return current
        }

        // 1. Every positive atom's own source node, with its atom-local comparisons already
        //    pushed onto it — that is what makes "lowest node" mean the atom's node and not
        //    the first join it feeds.
        val atomNodes = positiveAtoms.mapIndexed { index, atom ->
            val node = planAtom(atom, "$scopeTag@$index/", stack)
            if (isSemiJoin[index]) node else pushBoundComparisons(node)
        }

        // 2. Left-deep equi-join / cross-product fold over the core (non-semijoin) atoms, in
        //    body order, pushing each comparison as soon as its variables are all bound.
        val coreIndices = positiveAtoms.indices.filter { !isSemiJoin[it] }
        var plan = atomNodes[coreIndices.first()]
        for (index in coreIndices.drop(1)) {
            plan = joinOn(plan, atomNodes[index])
            plan = pushBoundComparisons(plan)
        }

        // 3. Existential atoms that bind nothing new and reach no head column: semijoins.
        for (index in positiveAtoms.indices.filter { isSemiJoin[it] }) {
            val key = PlanAnalyses.filterKey(plan)
            plan = SemiJoin(
                input = plan,
                witness = atomNodes[index],
                keys = sharedKeys(plan.outputColumns, atomNodes[index].outputColumns),
                outputColumns = plan.outputColumns,
                provenance = provenanceOf(listOf(plan, atomNodes[index])),
                keyPreserving = key.keyPreserving,
                preservedKey = key.preservedKey,
            )
        }

        // 4. Negated atoms: antijoin on the variables they share with the plan so far.
        for ((index, atom) in negatedAtoms.withIndex()) {
            val witness = planAtom(atom, "$scopeTag~$index/", stack)
            val key = PlanAnalyses.filterKey(plan)
            plan = AntiJoin(
                input = plan,
                witness = witness,
                keys = sharedKeys(plan.outputColumns, witness.outputColumns),
                outputColumns = plan.outputColumns,
                provenance = provenanceOf(listOf(plan, witness)),
                keyPreserving = key.keyPreserving,
                preservedKey = key.preservedKey,
            )
        }

        // 5. Anything still unplaced (a comparison over a variable only a semijoin or
        //    negated atom mentions) sits at the top of the body plan.
        plan = pushBoundComparisons(plan)
        require(placed.all { it }) {
            "Rule ${rule.head.predicate}: comparison(s) " +
                comparisons.filterIndexed { i, _ -> !placed[i] } +
                " reference variables bound nowhere in the body; safety analysis should have refused this."
        }

        val aggregate = rule.aggregate ?: return projectTo(plan, headVars)

        // 6. Aggregate-annotated head: group by every head variable but the last, aggregate
        //    over the last. See this file's KDoc for why the column comes from the head shape.
        //
        // Aggregate input population: [GroupAggregate] is built over the FULL body plan
        // `plan`, not over a [Project] narrowed to `groupByColumns` + the aggregated column.
        // The body plan is already a set of distinct rows ([QRY1-SEM-01]: "every compiled
        // operator's result is a set of rows, and duplicate elimination is a consequence of
        // the algebra rather than an added step"), so the population COUNT/SUM/AVG aggregate
        // over is the distinct body rows the rule binds, not the distinct values of the
        // grouped/aggregated columns alone. Concretely, `c(count X) :- e(X, Y).` over
        // `e = {(1,a), (1,b)}` counts the 2 distinct `(X, Y)` body rows, answering 2 — not 1,
        // the count of distinct `X` values.
        //
        // A narrowing [Project] to grouping keys + aggregated column before the aggregate
        // would be exactly the distinct-semantics approximation [QRY1-SEM-02] forbids: that
        // projection is not key-preserving, and SEM-02 requires the compiler to reject a
        // non-key-preserving projection feeding a multiplicity-sensitive consumer
        // (`count`/`sum`/`avg`) with `RejectionCode.BAG_SEMANTICS_REQUIRED` rather than
        // silently compile an approximation. This planner does not yet implement that
        // rejection (computenet-cab.5); until it does, a rule whose aggregated head variable
        // set is a strict subset of its body variables silently aggregates over full body
        // rows rather than being refused.
        require(headVars.isNotEmpty()) {
            "Rule ${rule.head.predicate} is aggregate-annotated but has a nullary head; " +
                "the aggregated column is the last head variable, so there must be one."
        }
        require(plan.outputColumns.containsAll(headVars)) {
            "Rule ${rule.head.predicate}: head variables $headVars are not all bound by its " +
                "body plan ${plan.outputColumns}"
        }
        val aggregatedColumn = headVars.last()
        val groupByColumns = headVars.dropLast(1)
        val aggregateKey = PlanAnalyses.groupAggregateKey(plan, groupByColumns)
        return GroupAggregate(
            input = plan,
            groupByColumns = groupByColumns,
            aggregatedColumn = aggregatedColumn,
            aggregate = aggregate,
            outputColumn = aggregatedColumn,
            outputColumns = headVars,
            provenance = plan.provenance,
            keyPreserving = aggregateKey.keyPreserving,
            preservedKey = aggregateKey.preservedKey,
        )
    }

    /**
     * Plans one [Definition] so that its output columns are exactly [headNames]: the
     * expression is normalized, substituted positionally to [headNames], then planned (see
     * this file's "Definition column naming" KDoc). [stack] already includes the head.
     */
    fun planDefinition(
        definition: Definition,
        headNames: List<String>,
        scopeTag: String,
        stack: List<String>,
    ): PlanNode {
        val predicate = definition.head.predicate
        val declared = headVariables(definition.head)
        require(declared.size == headNames.size) {
            "Head predicate '$predicate' is used at arity ${headNames.size} but defined at arity " +
                "${declared.size}"
        }
        val normalized = normalizeExpr(definition.expr, scopeTag)
        val columns = exprColumns(normalized)
        require(columns.size == headNames.size) {
            "Definition '$predicate' declares arity ${headNames.size} in its head but its " +
                "expression has arity ${columns.size} (columns $columns)"
        }
        val bound = substituteExpr(normalized, columns.zip(headNames).toMap())
        val node = planExpr(bound, scopeTag, stack)
        check(node.outputColumns == headNames) {
            "Definition '$predicate' planned to columns ${node.outputColumns}, expected $headNames"
        }
        return node
    }

    /**
     * Plans a normalized, head-substituted expression. Operand columns already agree by
     * construction ([normalizeExpr]); the checks here are planner-bug guards.
     */
    private fun planExpr(expr: RelationalExpr, scopeTag: String, stack: List<String>): PlanNode = when (expr) {
        is RelationalExpr.Relation -> planAtom(expr.atom, scopeTag, stack)
        is RelationalExpr.SetOp -> {
            requireDistinctSetOp(expr)
            val left = planExpr(expr.left, "${scopeTag}L/", stack)
            val right = planExpr(expr.right, "${scopeTag}R/", stack)
            check(left.outputColumns == right.outputColumns) {
                "${expr.kind} operands disagree on output columns: ${left.outputColumns} vs ${right.outputColumns}"
            }
            val columns = left.outputColumns
            val provenance = provenanceOf(listOf(left, right))
            when (expr.kind) {
                SetOpKind.UNION -> union(listOf(left, right))
                SetOpKind.INTERSECTION -> {
                    val key = PlanAnalyses.intersectKey(left, right, columns)
                    Intersect(left, right, columns, provenance, key.keyPreserving, key.preservedKey)
                }
                SetOpKind.DIFFERENCE -> {
                    val key = PlanAnalyses.differenceKey(left, columns)
                    Difference(left, right, columns, provenance, key.keyPreserving, key.preservedKey)
                }
            }
        }
        is RelationalExpr.OuterJoin -> {
            val left = planExpr(expr.left, "${scopeTag}L/", stack)
            val right = planExpr(expr.right, "${scopeTag}R/", stack)
            val keys = expr.on.map { JoinKey(it.left.name, it.right.name) }
            check(keys.all { it.left == it.right && it.left in left.outputColumns && it.right in right.outputColumns }) {
                "OuterJoin keys $keys are not merged columns of ${left.outputColumns} and ${right.outputColumns}"
            }
            val columns = left.outputColumns + right.outputColumns.filter { it !in left.outputColumns }
            val key = PlanAnalyses.outerJoinKey(left, right, columns)
            OuterJoin(
                left = left,
                right = right,
                keys = keys,
                side = when (expr.side) {
                    civictech.query.ast.OuterJoinSide.LEFT -> OuterJoinSide.LEFT
                    civictech.query.ast.OuterJoinSide.RIGHT -> OuterJoinSide.RIGHT
                    civictech.query.ast.OuterJoinSide.FULL -> OuterJoinSide.FULL
                },
                outputColumns = columns,
                provenance = provenanceOf(listOf(left, right)),
                keyPreserving = key.keyPreserving,
                preservedKey = key.preservedKey,
            )
        }
    }

    /**
     * Plans one body atom into a node whose output columns are exactly the atom's distinct
     * variables, in first-occurrence order.
     */
    fun planAtom(atom: Atom, scopeTag: String, stack: List<String>): PlanNode {
        val columns = positionalColumns(atom, scopeTag)
        val schema = catalog.relations[atom.predicate]
        var node: PlanNode = if (schema != null) {
            require(schema.attributes.size == atom.terms.size) {
                "Atom ${atom.predicate}/${atom.terms.size} does not match its declared arity " +
                    "${schema.attributes.size}"
            }
            val key = PlanAnalyses.scanKey(schema.rowKey, schema.attributeNames, columns)
            Scan(
                relation = atom.predicate,
                outputColumns = columns,
                provenance = setOf(atom.predicate),
                keyPreserving = key.keyPreserving,
                preservedKey = key.preservedKey,
            )
        } else {
            require(atom.predicate in rulesByHead || atom.predicate in definitionsByHead) {
                "Atom '${atom.predicate}' names neither a catalog relation nor a rule or definition head"
            }
            planPredicate(atom.predicate, columns, scopeTag, stack)
        }

        // A constant or a repeated variable at a position is an equality against the
        // synthetic column standing in there — a selection directly above the source, which
        // is the lowest node that could carry it.
        for ((index, term) in atom.terms.withIndex()) {
            val column = columns[index]
            if (term is Term.Var && term.name == column) continue
            node = select(node, Literal.Comparison(Term.Var(column), ComparisonOp.EQ, term))
        }
        return projectTo(node, variablesOf(atom))
    }

    /**
     * The column name this atom exposes at each position: the variable's own name at a
     * first-occurrence variable position, and a scoped synthetic name — which no surface
     * identifier can collide with, since `!` is not an identifier character — at a constant
     * or repeated-variable position.
     */
    private fun positionalColumns(atom: Atom, scopeTag: String): List<String> {
        val seen = LinkedHashSet<String>()
        return atom.terms.mapIndexed { index, term ->
            if (term is Term.Var && seen.add(term.name)) term.name
            else "!$scopeTag${atom.predicate}_$index"
        }
    }

    /**
     * Joins [left] and [right] on every variable they share. An empty key list is a cross
     * product and is reached only when they share nothing — `[QRY1-PLAN-02]` forbids
     * planning a shared-variable pair as a cross product with an equality filter on top, and
     * the only place this planner can build a [Join] is here.
     */
    private fun joinOn(left: PlanNode, right: PlanNode): Join {
        val keys = sharedKeys(left.outputColumns, right.outputColumns)
        val columns = left.outputColumns + right.outputColumns.filter { it !in left.outputColumns }
        val key = PlanAnalyses.joinKey(left, right, columns)
        return Join(
            left = left,
            right = right,
            equiKeys = keys,
            outputColumns = columns,
            provenance = provenanceOf(listOf(left, right)),
            keyPreserving = key.keyPreserving,
            preservedKey = key.preservedKey,
        )
    }
}

/** The shared columns of [left] and [right], as equi-join keys, in [left]'s column order. */
private fun sharedKeys(left: List<String>, right: List<String>): List<JoinKey> {
    val rightColumns = right.toSet()
    return left.filter { it in rightColumns }.map { JoinKey(it, it) }
}

/** [branches] combined by [Union], or the single branch itself when there is only one. */
private fun union(branches: List<PlanNode>): PlanNode {
    if (branches.size == 1) return branches.single()
    val columns = branches.first().outputColumns
    require(branches.all { it.outputColumns == columns }) {
        "Union branches disagree on output columns: ${branches.map { it.outputColumns }}"
    }
    val key = PlanAnalyses.unionKey(branches)
    return Union(
        inputs = branches,
        outputColumns = columns,
        provenance = provenanceOf(branches),
        keyPreserving = key.keyPreserving,
        preservedKey = key.preservedKey,
    )
}

/**
 * Marks the positive atoms that plan as [SemiJoin] rather than [Join] (`[QRY1-PLAN-04]`): an
 * atom none of whose variables reach the head and all of whose variables are bound by the
 * atoms that remain in the join core. Marking is iterated to a fixpoint in index order, and
 * an atom already marked no longer counts as a binder — so two atoms that only bind each
 * other cannot both vanish into semijoins, and at least one core atom always remains.
 */
private fun classifySemiJoinAtoms(atomVariables: List<List<String>>, headVariables: Set<String>): BooleanArray {
    val isSemiJoin = BooleanArray(atomVariables.size)
    var changed = true
    while (changed) {
        changed = false
        for (index in atomVariables.indices) {
            if (isSemiJoin[index]) continue
            val variables = atomVariables[index]
            if (variables.isEmpty()) continue
            if (variables.any { it in headVariables }) continue
            val binders = atomVariables.indices.filter { it != index && !isSemiJoin[it] }
            if (binders.isEmpty()) continue
            val bound = binders.flatMapTo(HashSet()) { atomVariables[it] }
            if (bound.containsAll(variables)) {
                isSemiJoin[index] = true
                changed = true
            }
        }
    }
    return isSemiJoin
}

/** `ALL` set operations are bag semantics, refused by `BAG_SEMANTICS_REQUIRED` (`[QRY1-SEM-04]`). */
private fun requireDistinctSetOp(expr: RelationalExpr.SetOp) {
    require(!expr.all) {
        "Planner does not plan ${expr.kind} ALL: bag semantics is refused as BAG_SEMANTICS_REQUIRED " +
            "([QRY1-SEM-04], the rejection front door's, computenet-cab.5) and is never planned " +
            "as the DISTINCT form."
    }
}

/**
 * The columns an expression exposes, in order (this file's "Definition column naming"
 * KDoc). Meaningful for a [normalizeExpr]-normalized expression, whose operand columns agree.
 */
private fun exprColumns(expr: RelationalExpr): List<String> = when (expr) {
    is RelationalExpr.Relation -> variablesOf(expr.atom)
    is RelationalExpr.SetOp -> setOpColumns(exprColumns(expr.left), exprColumns(expr.right))
    is RelationalExpr.OuterJoin -> {
        val left = exprColumns(expr.left)
        left + exprColumns(expr.right).filter { it !in left }
    }
}

/**
 * The columns a set operation exposes (this file's "Definition column naming" KDoc): the left
 * operand's columns — a set operation is positional, so [rightColumns] contributes no names of
 * its own, only an arity check ([requireDistinctSetOp]'s size check here,
 * [civictech.query.parse.WellFormednessAnalysis]'s ARITY_MISMATCH report there); it is a
 * parameter here only so a caller has it in hand to check before calling, the same shape
 * [outerJoinRightRename] takes both operands' columns in. Shared with
 * [civictech.query.parse.WellFormednessAnalysis], whose definition-arity check must compute the
 * same exposed columns as [normalizeExpr]/[exprColumns] do here, or a change to this rule could
 * pass planning while the analysis still accepts (or rejects) a definition by the old rule.
 */
internal fun setOpColumns(leftColumns: List<String>, rightColumns: List<String>): List<String> = leftColumns

/**
 * The rename an outer join's right operand undergoes when merged against [leftColumns] (this
 * file's "Definition column naming" KDoc): a right key variable — paired with its left key
 * variable positionally via [leftKeys]/[rightKeys] — is substituted to that left key variable,
 * so the merged join key is one column under the left's name; any other right column
 * ([rightColumns]) that merely collides with a left column's name is renamed apart
 * (`name!scopeTag`, which no surface identifier can be), because only the `on` clause is meant
 * to merge columns. Shared with [civictech.query.parse.WellFormednessAnalysis], whose
 * definition-arity check must compute the same exposed columns as [normalizeExpr]/[exprColumns]
 * do here, or a change to this rename could pass planning while the analysis still accepts (or
 * rejects) a definition by the old rule.
 */
internal fun outerJoinRightRename(
    leftColumns: List<String>,
    rightColumns: List<String>,
    leftKeys: List<String>,
    rightKeys: List<String>,
    scopeTag: String,
): Map<String, String> {
    val keyTarget = rightKeys.zip(leftKeys).toMap()
    return rightColumns.associateWith { column ->
        keyTarget[column] ?: if (column in leftColumns) "$column!$scopeTag" else column
    }
}

/**
 * Rewrites [expr] so every operator's operands agree on column names without a rename node:
 * a set operation's right operand is substituted positionally to its left operand's columns,
 * and an outer join's right operand has each key variable substituted by its left key
 * variable and each other variable that collides with a left column renamed apart
 * (`name!scope`, which no surface identifier can be). Afterwards every variable of the
 * expression is one of [exprColumns]' columns. [scopeTag] keeps renamed-apart names unique
 * per operator position.
 */
private fun normalizeExpr(expr: RelationalExpr, scopeTag: String): RelationalExpr = when (expr) {
    is RelationalExpr.Relation -> expr
    is RelationalExpr.SetOp -> {
        requireDistinctSetOp(expr)
        val left = normalizeExpr(expr.left, "${scopeTag}L/")
        val right = normalizeExpr(expr.right, "${scopeTag}R/")
        val leftColumns = exprColumns(left)
        val rightColumns = exprColumns(right)
        require(leftColumns.size == rightColumns.size) {
            "${expr.kind} operands have different arity: left $leftColumns (arity " +
                "${leftColumns.size}) vs right $rightColumns (arity ${rightColumns.size})"
        }
        val targetColumns = setOpColumns(leftColumns, rightColumns)
        expr.copy(left = left, right = substituteExpr(right, rightColumns.zip(targetColumns).toMap()))
    }
    is RelationalExpr.OuterJoin -> {
        val left = normalizeExpr(expr.left, "${scopeTag}L/")
        val right = normalizeExpr(expr.right, "${scopeTag}R/")
        val leftColumns = exprColumns(left)
        val rightColumns = exprColumns(right)
        val leftKeys = expr.on.map { it.left.name }
        val rightKeys = expr.on.map { it.right.name }
        require(leftKeys.all { it in leftColumns } && rightKeys.all { it in rightColumns }) {
            "${expr.side} outer join keys ${expr.on.map { "${it.left.name} = ${it.right.name}" }} must " +
                "name a left column of $leftColumns and a right column of $rightColumns"
        }
        require(leftKeys.distinct().size == leftKeys.size && rightKeys.distinct().size == rightKeys.size) {
            "Planner does not support an outer join key column used twice: " +
                expr.on.map { "${it.left.name} = ${it.right.name}" }
        }
        val substitution = outerJoinRightRename(leftColumns, rightColumns, leftKeys, rightKeys, scopeTag)
        val merged = leftKeys.map { Term.Var(it) }
        expr.copy(
            left = left,
            right = substituteExpr(right, substitution),
            on = merged.map { civictech.query.ast.JoinKey(it, it) },
        )
    }
}

/**
 * [expr] with every variable renamed simultaneously through [substitution] (absent names kept),
 * at the leaf atoms and in outer-join keys. Callers pass an injective map covering every
 * variable of a normalized expression, so no renamed variable can capture another.
 */
private fun substituteExpr(expr: RelationalExpr, substitution: Map<String, String>): RelationalExpr {
    if (substitution.all { (from, to) -> from == to }) return expr
    fun rename(v: Term.Var): Term.Var = Term.Var(substitution[v.name] ?: v.name)
    return when (expr) {
        is RelationalExpr.Relation -> RelationalExpr.Relation(
            Atom(expr.atom.predicate, expr.atom.terms.map { if (it is Term.Var) rename(it) else it }),
        )
        is RelationalExpr.SetOp -> expr.copy(
            left = substituteExpr(expr.left, substitution),
            right = substituteExpr(expr.right, substitution),
        )
        is RelationalExpr.OuterJoin -> expr.copy(
            left = substituteExpr(expr.left, substitution),
            right = substituteExpr(expr.right, substitution),
            on = expr.on.map { civictech.query.ast.JoinKey(rename(it.left), rename(it.right)) },
        )
    }
}

/**
 * Rewrites [rule] so its head variables become [headNames] positionally. A body-local
 * variable is renamed only when it would collide with one of the incoming head names — the
 * one case where substitution could capture it — so an ordinary rule keeps its own variable
 * names and the plans this planner builds stay readable.
 */
private fun renameRule(rule: Rule, headNames: List<String>, scopeTag: String): Rule {
    val currentHeadNames = headVariables(rule.head)
    require(currentHeadNames.size == headNames.size) {
        "Head predicate '${rule.head.predicate}' is used at arity ${headNames.size} but " +
            "defined at arity ${currentHeadNames.size}"
    }
    val substitution = currentHeadNames.zip(headNames).toMap()
    if (substitution.all { (from, to) -> from == to }) return rule

    val targets = headNames.toSet()
    fun rename(name: String): String = substitution[name] ?: if (name in targets) "$name!$scopeTag" else name
    fun renameTerm(term: Term): Term = when (term) {
        is Term.Var -> Term.Var(rename(term.name))
        is Term.Const -> term
    }
    fun renameAtom(atom: Atom): Atom = Atom(atom.predicate, atom.terms.map(::renameTerm))

    return Rule(
        head = renameAtom(rule.head),
        body = rule.body.map { literal ->
            when (literal) {
                is Literal.Positive -> Literal.Positive(renameAtom(literal.atom))
                is Literal.Negated -> Literal.Negated(renameAtom(literal.atom))
                is Literal.Comparison -> Literal.Comparison(
                    renameTerm(literal.left),
                    literal.op,
                    renameTerm(literal.right),
                )
            }
        },
        aggregate = rule.aggregate,
    )
}
