package civictech.query.plan

import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
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
 *
 * **Determinism (cab.3-D1).** Nothing on the planning path iterates a hash-ordered
 * collection. Rule bodies, atom terms and head terms are `List`s and are consumed in their
 * declared order; head predicates are planned in sorted name order; every [PlanNode.provenance]
 * set is built from a sorted list into a `LinkedHashSet`. The only `Map`s read here —
 * [Catalog.relations] and the rules-by-head index — are used for point lookups, never
 * iterated.
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

    /** Translates [query] into a [LogicalPlan] with one root per head predicate. */
    fun plan(query: Query): LogicalPlan {
        val rulesByHead = LinkedHashMap<String, MutableList<Rule>>()
        for (rule in query.rules) {
            rulesByHead.getOrPut(rule.head.predicate) { mutableListOf() } += rule
        }
        val context = PlanningContext(query.catalog, rulesByHead)
        val roots = LinkedHashMap<String, PlanNode>()
        // Sorted so the root map's own iteration order is a function of the predicate names
        // alone, not of insertion or hash order (cab.3-D1).
        for (predicate in rulesByHead.keys.sorted()) {
            val rules = rulesByHead.getValue(predicate)
            val canonicalHeadNames = headVariables(rules.first().head)
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
            "Planner does not support a constant in a rule head: ${atom.predicate} carries $term. " +
                "A head position must be a variable bound by a positive body atom."
        }
        term.name
    }
    require(names.distinct().size == names.size) {
        "Planner does not support a repeated variable in a rule head: ${atom.predicate}($names)."
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
 * Everything one rule's translation needs: the [catalog] (EDB predicates) and the
 * rules-by-head index (IDB predicates). Both are read by point lookup only.
 */
private class PlanningContext(
    val catalog: Catalog,
    val rulesByHead: Map<String, List<Rule>>,
) {

    /**
     * Plans head predicate [predicate] so that its output columns are exactly [headNames],
     * combining its rules by [Union] when it has more than one (`[QRY1-LOWER-10]`).
     * [stack] carries the predicates currently being expanded, so a recursive definition
     * fails loudly here rather than looping.
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
            require(atom.predicate in rulesByHead) {
                "Atom '${atom.predicate}' names neither a catalog relation nor a rule head"
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
