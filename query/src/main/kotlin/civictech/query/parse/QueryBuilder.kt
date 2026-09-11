package civictech.query.parse

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Definition
import civictech.query.ast.JoinKey
import civictech.query.ast.Literal
import civictech.query.ast.OuterJoinSide
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.schema.AttrType
import civictech.query.schema.Catalog

/**
 * The typed Kotlin builder surface (`[QRY1-LANG-02]`): `query { }` produces the *identical*
 * `civictech.query.ast` vocabulary the text parser produces, so no construct is expressible
 * in one surface and not the other. It absorbs `backlog/05-relational-view-dsl.md`'s
 * `RelationalScope` sketch — the same "write the algebra, not the wiring" feel, one level up
 * (over the query AST rather than over `graph { }`'s spawn/connect).
 *
 * Typed-ness means exactly what cab.2-D1 decided and no more: **compile-time relation and
 * attribute references derived from a [Catalog], lowered to the same `Term`/`Atom`
 * vocabulary**. There is deliberately NO richer parallel type layer — a builder handle is a
 * thin [RelationRef] over a catalog entry, and every value it produces is an ordinary AST
 * node. Concretely the builder buys three things a hand-written `Query(...)` does not:
 * constants typed by Kotlin overload resolution ([QueryScope.const]), relation names and
 * arities checked against the [Catalog] ([QueryScope.relation]), and attribute names checked
 * and ordered by the declared schema ([RelationRef.by]).
 *
 * **The builder judges nothing.** No safety analysis, no EDB-redefinition check, no
 * recursion check, no `Rejection`, no `RejectionCode`, no lowering. An unsafe rule, a head
 * that redefines a catalog relation, and an `all = true` bag-semantics set operation
 * (cab.2-D3) all *build* — refusing them is the analysis and rejection features' job, and
 * refusing them here would hide the construct from the very code written to refuse it. The
 * only failures here are structural mistakes the [Catalog] can see (an unknown relation, a
 * wrong arity, an unknown attribute), raised as [IllegalArgumentException] from the builder
 * call, never as a `Rejection`.
 *
 * ```kotlin
 * val q = query(catalog) {
 *     val x = v("X"); val y = v("Y"); val d = v("D")
 *     val link = relation("link")
 *     val edge = derived("edge")
 *
 *     rule(edge(x, y)) {
 *         +link(x, y)
 *         not(derived("blocked")(x, y))
 *         d le const(10L)
 *     }
 *     rule(derived("edgeCount")(x), aggregate = count()) { +edge(x, y) }
 *     define(derived("either")(x, y)) { rel(edge(x, y)) union rel(link(x, y)) }
 * }
 * ```
 */
fun query(catalog: Catalog = Catalog(emptyMap()), block: QueryScope.() -> Unit): Query =
    QueryScope(catalog).apply(block).build()

/**
 * The `query { }` body's receiver: declares terms, relation references and aggregate
 * annotations, and collects [rule] and [define] statements in source order.
 *
 * No `@DslMarker` is applied, deliberately: [RuleScope] and [RelationalScope] bodies need
 * the term and constant helpers declared here (`d le const(10L)` inside a `rule { }` is the
 * common case), and a DSL marker would shadow exactly those.
 */
class QueryScope internal constructor(val catalog: Catalog) {

    private val rules = mutableListOf<Rule>()
    private val definitions = mutableListOf<Definition>()

    /**
     * A reference to a relation *declared in the [Catalog]* — the typed half of the builder.
     * Applying it checks arity against the declared schema, and [RelationRef.by] checks
     * attribute names, so a miscounted or misnamed column fails at the builder call rather
     * than in a downstream planner.
     */
    fun relation(name: String): RelationRef {
        val schema = requireNotNull(catalog.relations[name]) {
            "relation(\"$name\") is not declared in the Catalog (declared: " +
                "${catalog.relations.keys.sorted()}); use derived(\"$name\") for a relation " +
                "this query itself defines"
        }
        return RelationRef(name, schema.attributeNames)
    }

    /**
     * A reference to a relation this query *derives* — a rule or definition head, or a body
     * atom over one. Unchecked against the [Catalog] by construction: the derived relations
     * are not in it, and a head that happens to collide with a declared one is
     * `EDB_REDEFINED` territory, which this builder must not pre-empt.
     */
    fun derived(name: String): RelationRef = RelationRef(name, null)

    /** A variable term. Two `v(name)` with the same [name] are the same binding. */
    fun v(name: String): Term.Var = Term.Var(name)

    /** A typed constant. The five overloads are the five [AttrType] members. */
    fun const(value: Int): Term.Const = Term.Const(value, AttrType.INT)

    fun const(value: Long): Term.Const = Term.Const(value, AttrType.LONG)

    fun const(value: Double): Term.Const = Term.Const(value, AttrType.DOUBLE)

    fun const(value: String): Term.Const = Term.Const(value, AttrType.STRING)

    fun const(value: Boolean): Term.Const = Term.Const(value, AttrType.BOOL)

    /** `[QRY1-LANG-03]`'s closed aggregate list — these seven and no other. */
    fun count(): Aggregate = Aggregate(AggregateKind.COUNT)

    fun sum(): Aggregate = Aggregate(AggregateKind.SUM)

    fun avg(): Aggregate = Aggregate(AggregateKind.AVG)

    fun min(): Aggregate = Aggregate(AggregateKind.MIN)

    fun max(): Aggregate = Aggregate(AggregateKind.MAX)

    fun collectToSet(): Aggregate = Aggregate(AggregateKind.COLLECT_TO_SET)

    /** `topK`'s `k` is validated by [Aggregate] itself, not re-validated here. */
    fun topK(k: Int): Aggregate = Aggregate(AggregateKind.TOP_K, k)

    /** A `[QRY1-LANG-01]` rule: [head] (optionally [aggregate]-annotated) from a literal body. */
    fun rule(head: Atom, aggregate: Aggregate? = null, body: RuleScope.() -> Unit) {
        rules += Rule(head, RuleScope().apply(body).literals(), aggregate)
    }

    /** A `[QRY1-LANG-04]` definition: [head] from a set-operation / outer-join expression. */
    fun define(head: Atom, expr: RelationalScope.() -> RelationalExpr) {
        definitions += Definition(head, RelationalScope().expr())
    }

    internal fun build(): Query =
        Query(rules = rules.toList(), catalog = catalog, definitions = definitions.toList())
}

/**
 * A relation name plus, when it came from the [Catalog], its declared attribute names —
 * `null` for a [QueryScope.derived] relation, which has no declared schema to check against.
 */
class RelationRef internal constructor(val name: String, val attributes: List<String>?) {

    /** Positional application: `link(x, y)`. Arity is checked when it is known. */
    operator fun invoke(vararg terms: Term): Atom {
        attributes?.let { declared ->
            require(terms.size == declared.size) {
                "relation \"$name\" declares ${declared.size} attribute(s) $declared but was " +
                    "applied to ${terms.size} term(s)"
            }
        }
        return Atom(name, terms.toList())
    }

    /**
     * Application by attribute name: `link.by("src" to x, "dst" to y)`. Every declared
     * attribute must be bound exactly once; the resulting [Atom]'s terms are ordered by the
     * schema's declaration order, so the caller never has to know the positions.
     */
    fun by(vararg bindings: Pair<String, Term>): Atom {
        val declared = requireNotNull(attributes) {
            "by(...) needs declared attribute names; \"$name\" is a derived relation with no " +
                "Catalog schema — apply it positionally instead"
        }
        val bound = bindings.toMap()
        require(bound.size == bindings.size) {
            "by(...) binds an attribute of \"$name\" more than once: " +
                "${bindings.map { it.first }}"
        }
        val unknown = bound.keys - declared.toSet()
        require(unknown.isEmpty()) {
            "relation \"$name\" declares no attribute(s) $unknown (declared: $declared)"
        }
        val missing = declared.toSet() - bound.keys
        require(missing.isEmpty()) {
            "by(...) leaves attribute(s) $missing of \"$name\" unbound (declared: $declared)"
        }
        return Atom(name, declared.map { bound.getValue(it) })
    }
}

/**
 * A rule body's receiver: the three `[QRY1-LANG-01]` literal forms and nothing else.
 * Literals are collected in the order they are written.
 */
class RuleScope internal constructor() {

    private val body = mutableListOf<Literal>()

    /** `+atom` — a positive body atom. */
    operator fun Atom.unaryPlus() {
        body += Literal.Positive(this)
    }

    /** `not(atom)` — a negated body atom. */
    fun not(atom: Atom) {
        body += Literal.Negated(atom)
    }

    infix fun Term.eq(other: Term) = compare(this, ComparisonOp.EQ, other)

    infix fun Term.ne(other: Term) = compare(this, ComparisonOp.NE, other)

    infix fun Term.lt(other: Term) = compare(this, ComparisonOp.LT, other)

    infix fun Term.le(other: Term) = compare(this, ComparisonOp.LE, other)

    infix fun Term.gt(other: Term) = compare(this, ComparisonOp.GT, other)

    infix fun Term.ge(other: Term) = compare(this, ComparisonOp.GE, other)

    private fun compare(left: Term, op: ComparisonOp, right: Term) {
        body += Literal.Comparison(left, op, right)
    }

    internal fun literals(): List<Literal> = body.toList()
}

/**
 * A [QueryScope.define] body's receiver: the first-class set operations and outer joins of
 * `[QRY1-LANG-04]`, each in a plain and an `ALL` spelling (cab.2-D3 — the `ALL` forms build
 * and carry `all = true`; nothing here judges them).
 */
class RelationalScope internal constructor() {

    /** Lifts an [Atom] to the expression algebra — the leaf of every expression. */
    fun rel(atom: Atom): RelationalExpr = RelationalExpr.Relation(atom)

    infix fun RelationalExpr.union(other: RelationalExpr): RelationalExpr =
        setOp(SetOpKind.UNION, this, other, all = false)

    infix fun RelationalExpr.unionAll(other: RelationalExpr): RelationalExpr =
        setOp(SetOpKind.UNION, this, other, all = true)

    infix fun RelationalExpr.intersect(other: RelationalExpr): RelationalExpr =
        setOp(SetOpKind.INTERSECTION, this, other, all = false)

    infix fun RelationalExpr.intersectAll(other: RelationalExpr): RelationalExpr =
        setOp(SetOpKind.INTERSECTION, this, other, all = true)

    infix fun RelationalExpr.difference(other: RelationalExpr): RelationalExpr =
        setOp(SetOpKind.DIFFERENCE, this, other, all = false)

    infix fun RelationalExpr.differenceAll(other: RelationalExpr): RelationalExpr =
        setOp(SetOpKind.DIFFERENCE, this, other, all = true)

    /** `on(a matches b, ...)` — an outer join's key equalities. */
    infix fun Term.Var.matches(other: Term.Var): JoinKey = JoinKey(this, other)

    fun leftJoin(left: RelationalExpr, right: RelationalExpr, vararg on: JoinKey): RelationalExpr =
        outerJoin(OuterJoinSide.LEFT, left, right, on)

    fun rightJoin(left: RelationalExpr, right: RelationalExpr, vararg on: JoinKey): RelationalExpr =
        outerJoin(OuterJoinSide.RIGHT, left, right, on)

    fun fullJoin(left: RelationalExpr, right: RelationalExpr, vararg on: JoinKey): RelationalExpr =
        outerJoin(OuterJoinSide.FULL, left, right, on)

    private fun setOp(
        kind: SetOpKind,
        left: RelationalExpr,
        right: RelationalExpr,
        all: Boolean,
    ): RelationalExpr = RelationalExpr.SetOp(kind, left, right, all)

    private fun outerJoin(
        side: OuterJoinSide,
        left: RelationalExpr,
        right: RelationalExpr,
        on: Array<out JoinKey>,
    ): RelationalExpr = RelationalExpr.OuterJoin(side, left, right, on.toList())
}
