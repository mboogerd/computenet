package civictech.query.run

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
import civictech.query.diag.RejectionCode
import civictech.query.plan.PlanNode
import civictech.query.plan.PlanOrder
import civictech.query.plan.Planner
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import kotlin.random.Random

/**
 * The knobs of [QueryGenerator] (computenet-cab.6.5, epic computenet-cab §4.6 `[QRY1-ORA-05]`,
 * §9 risk 5).
 *
 * - [relations] EDB relations per catalog, each of arity `2..`[maxArity].
 * - [maxRules] the most rules a multi-rule (Union) head gets; [maxBodyAtoms] the longest join chain.
 * - [admissibleFraction] the probability a generated query is admissible; the rest carry exactly
 *   one injected inadmissible construct ([InjectedConstruct]).
 * - [deletionRatio] and [steps] are handed to [QueryScripts] by the sweep, not used here.
 *
 * The lower bounds are what the recipes need: two relations so an EDB-redefining head has a body
 * relation other than itself, arity two so a semijoin atom has a head column left over, two rules
 * for a Union, and three body atoms for a join chain.
 */
data class GeneratorConfig(
    val relations: Int = 3,
    val maxArity: Int = 3,
    val maxRules: Int = 3,
    val maxBodyAtoms: Int = 3,
    val admissibleFraction: Double = 0.8,
    val deletionRatio: Double = 0.4,
    val steps: Int = 40,
) {
    init {
        require(relations >= 2) { "relations must be at least 2, got $relations" }
        require(maxArity >= 2) { "maxArity must be at least 2, got $maxArity" }
        require(maxRules >= 2) { "maxRules must be at least 2, got $maxRules" }
        require(maxBodyAtoms >= 3) { "maxBodyAtoms must be at least 3, got $maxBodyAtoms" }
        require(admissibleFraction in 0.0..1.0) { "admissibleFraction must be in [0, 1], got $admissibleFraction" }
        require(deletionRatio in 0.0..1.0) { "deletionRatio must be in [0, 1], got $deletionRatio" }
        require(steps >= 0) { "steps must not be negative, got $steps" }
    }
}

/**
 * One generated case.
 *
 * - [recipe] names the construct recipe that built it — an [AdmissibleRecipe] or an
 *   [InjectedConstruct] name — so a failure names what the generator meant to build.
 * - [source] is [query] rendered by [QueryText]; the sweep compiles the text and checks it
 *   parses back to [query].
 * - [expectedRejection] is `null` iff [admissible].
 * - [kinds] is every [PlanNode] class in `Planner.plan(query)`, for an admissible query whose
 *   planning did not throw; `null` otherwise (a throwing admissible plan is a generator bug the
 *   sweep reports through the compiler, which fences the planner).
 * - [joinChains] the EDB relations of each body that joins three or more atoms, in body order
 *   (`[QRY1-ORA-06]`'s "deletion inside a three-atom join chain" is counted against these).
 */
data class GeneratedQuery(
    val recipe: String,
    val catalog: Catalog,
    val query: Query,
    val source: String,
    val admissible: Boolean,
    val expectedRejection: RejectionCode?,
    val kinds: Set<Class<out PlanNode>>?,
    val joinChains: List<List<String>>,
)

/** The admissible construct recipes; each draws with equal probability. */
enum class AdmissibleRecipe {
    PROJECT,
    SELECT_COMPARISON,
    SELECT_CONSTANT,
    JOIN,
    SELF_JOIN,
    JOIN_CHAIN,
    CROSS_PRODUCT,
    SEMIJOIN,
    NEGATION,
    UNION_RULES,
    DEFINE_UNION,
    DEFINE_INTERSECT,
    DEFINE_EXCEPT,
    DEFINE_LEFT_OUTER_JOIN,
    DEFINE_RIGHT_OUTER_JOIN,
    DEFINE_FULL_OUTER_JOIN,
    AGGREGATE_COUNT,
    AGGREGATE_SUM,
    AGGREGATE_AVG,
    AGGREGATE_MIN,
    AGGREGATE_MAX,
    AGGREGATE_TOP_K,
    AGGREGATE_COLLECT_TO_SET,
    IDB_CONSUMER,
}

/**
 * The inadmissible constructs (epic §9 risk 5, cab.6-D2), each with the [RejectionCode]
 * `QueryCompiler` is expected to report for it. Codes checked against `RejectionCode.kt` and
 * `RejectionTest` at the cab.5 merge (87afc985): all six match the bead's list.
 */
enum class InjectedConstruct(val code: RejectionCode) {
    UNSAFE_RULE(RejectionCode.UNSAFE_RULE),
    RECURSIVE_PAIR(RejectionCode.RECURSION_UNSUPPORTED),
    EDB_NAMED_HEAD(RejectionCode.EDB_REDEFINED),
    ALL_SET_OPERATION(RejectionCode.BAG_SEMANTICS_REQUIRED),
    NON_ROOT_AGGREGATE(RejectionCode.NO_LOWERING),
    SUM_OVER_STRING(RejectionCode.NO_LOWERING),
}

/**
 * A seeded generator of query cases across the whole admissible vocabulary, plus a configured
 * share of cases carrying one injected inadmissible construct (computenet-cab.6.5,
 * `[QRY1-ORA-05]`, epic §9 risk 5).
 *
 * **Deterministic from [seed].** One `kotlin.random.Random(seed)`; catalogs are built into
 * `LinkedHashMap`s and every collection the generator draws from is a `List` in a fixed order —
 * no hash-order iteration touches the random stream. Two generators with one seed and one
 * config produce `==` sequences of [GeneratedQuery].
 *
 * **Catalogs.** Relations `e0..`, arity `2..maxArity`, attribute types INT and STRING; every
 * relation carries an INT column (so any two relations can equi-join and SUM has a column),
 * `e0` is `(INT, STRING, ...)` (so SUM-over-STRING has a column), and the last relation's last
 * column is the catalog's one LONG column. Every relation's row key is **all** its attributes, so
 * a constant-free scan is key-preserving and a COUNT/SUM/AVG directly over EDB atoms is not a
 * `BAG_SEMANTICS_REQUIRED` refusal (cab.5-D8).
 *
 * **What keeps an admissible query admissible**, per recipe: every variable is typed at its first
 * binding and only ever reused at a position of the same type (so join keys, set-operation operands
 * and comparisons are well-typed); comparisons compare a variable with a constant of its own type;
 * head and negated/comparison variables are bound by a positive atom; no head names a relation; no
 * head is reached from its own body; SUM/AVG aggregate an INT or LONG column; aggregates sit at a
 * root over EDB atoms only, with no constant in those atoms (a constant's synthetic column is
 * projected away, which loses the row key).
 *
 * **Limits.** The recipe set is the construct vocabulary one statement or small statement group at
 * a time; recipes do not compose with each other (no aggregate over a join chain with negation, no
 * outer join of a set operation). Coverage of every `PlanNode` kind and `AggregateKind` is a
 * property the sweep asserts at its default seed count, not a guarantee of any single seed.
 */
class QueryGenerator(val seed: Long, val config: GeneratorConfig = GeneratorConfig()) {

    private val random = Random(seed)

    fun next(): GeneratedQuery {
        val catalog = catalog()
        val admissible = random.nextDouble() < config.admissibleFraction
        val build = Build(catalog)
        val recipe: String
        val expected: RejectionCode?
        if (admissible) {
            val chosen = AdmissibleRecipe.entries.pick()
            build.admissible(chosen)
            recipe = chosen.name
            expected = null
        } else {
            val chosen = InjectedConstruct.entries.pick()
            build.project()
            build.inject(chosen)
            recipe = chosen.name
            expected = chosen.code
        }
        val query = Query(build.rules.toList(), catalog, build.definitions.toList())
        val kinds = if (!admissible) {
            null
        } else {
            runCatching { Planner.plan(query) }.getOrNull()?.let { plan ->
                plan.roots.keys.sorted().flatMap { PlanOrder.allNodes(plan.roots.getValue(it)) }
                    .map { it.javaClass }
                    .sortedBy { it.name }
                    .toCollection(LinkedHashSet())
            }
        }
        return GeneratedQuery(
            recipe = recipe,
            catalog = catalog,
            query = query,
            source = QueryText.render(query),
            admissible = admissible,
            expectedRejection = expected,
            kinds = kinds,
            joinChains = build.joinChains.toList(),
        )
    }

    private fun <T> List<T>.pick(): T = this[random.nextInt(size)]

    private fun catalog(): Catalog {
        val relations = LinkedHashMap<String, RelationSchema>()
        val count = config.relations
        for (r in 0 until count) {
            val arity = 2 + random.nextInt(config.maxArity - 1)
            val types = MutableList(arity) { if (random.nextBoolean()) AttrType.INT else AttrType.STRING }
            if (r == 0) {
                types[0] = AttrType.INT
                types[1] = AttrType.STRING
            }
            if (r == count - 1) {
                types[arity - 1] = AttrType.LONG
                if (AttrType.INT !in types) types[0] = AttrType.INT
            }
            if (AttrType.INT !in types) types[random.nextInt(arity)] = AttrType.INT
            val attributes = types.mapIndexed { i, type -> Attribute("a$i", type) }
            relations["e$r"] = RelationSchema(attributes, rowKey = attributes.mapTo(LinkedHashSet()) { it.name })
        }
        return Catalog(relations)
    }

    /** A variable and the type it was bound at. */
    private data class TVar(val name: String, val type: AttrType) {
        val term: Term.Var get() = Term.Var(name)
    }

    /** One query under construction: its statements and its fresh-name counters. */
    private inner class Build(val catalog: Catalog) {
        val rules = mutableListOf<Rule>()
        val definitions = mutableListOf<Definition>()
        val joinChains = mutableListOf<List<String>>()
        private var nextVar = 0
        private var nextHead = 0
        private val relationNames: List<String> = catalog.relations.keys.toList()

        fun fresh(type: AttrType) = TVar("V${nextVar++}", type)
        fun headName(prefix: String) = "$prefix${nextHead++}"
        fun types(relation: String): List<AttrType> = catalog.relations.getValue(relation).attributes.map { it.type }
        fun anyRelation(): String = relationNames.pick()
        fun relationsWith(type: AttrType): List<String> = relationNames.filter { type in types(it) }

        fun constant(type: AttrType): Term.Const = Term.Const(QueryScripts.DEFAULT_DOMAIN.getValue(type).pick(), type)

        fun freshVars(relation: String): List<TVar> = types(relation).map { fresh(it) }

        fun atom(predicate: String, vars: List<TVar>) = Atom(predicate, vars.map { it.term })

        fun rule(head: String, headVars: List<TVar>, body: List<Literal>, aggregate: Aggregate? = null) {
            rules += Rule(atom(head, headVars), body, aggregate)
        }

        /** A non-empty proper subset of [vars], in order. */
        fun properSubset(vars: List<TVar>): List<TVar> {
            val keep = vars.filter { random.nextBoolean() }.ifEmpty { listOf(vars.pick()) }
            return if (keep.size == vars.size) keep - keep.pick() else keep
        }

        /**
         * An atom over [relation] that shares one variable of [with] at a position of that
         * variable's type (such a pair always exists when both sides carry an INT), every other
         * position a fresh variable. Returns the atom's terms as variables.
         */
        fun sharing(relation: String, with: List<TVar>): List<TVar> {
            val relationTypes = types(relation)
            val pairs = relationTypes.indices.flatMap { p -> with.filter { it.type == relationTypes[p] }.map { p to it } }
            check(pairs.isNotEmpty()) { "no shared type between $with and $relation$relationTypes" }
            val (position, shared) = pairs.pick()
            return relationTypes.mapIndexed { p, type -> if (p == position) shared else fresh(type) }
        }

        fun admissible(recipe: AdmissibleRecipe) {
            when (recipe) {
                AdmissibleRecipe.PROJECT -> project()
                AdmissibleRecipe.SELECT_COMPARISON -> {
                    val relation = anyRelation()
                    val vars = freshVars(relation)
                    val v = vars.pick()
                    val c = constant(v.type)
                    val op = ComparisonOp.entries.pick()
                    val comparison = if (random.nextBoolean()) {
                        Literal.Comparison(v.term, op, c)
                    } else {
                        Literal.Comparison(c, op, v.term)
                    }
                    rule(headName("q"), vars, listOf(Literal.Positive(atom(relation, vars)), comparison))
                }
                AdmissibleRecipe.SELECT_CONSTANT -> {
                    val relation = anyRelation()
                    val relationTypes = types(relation)
                    val position = relationTypes.indices.toList().pick()
                    val terms = relationTypes.mapIndexed { p, type -> if (p == position) constant(type) else fresh(type) }
                    val vars = terms.filterIsInstance<TVar>()
                    val atom = Atom(relation, terms.map { if (it is TVar) it.term else it as Term })
                    rule(headName("q"), vars, listOf(Literal.Positive(atom)))
                }
                AdmissibleRecipe.JOIN -> join(anyRelation(), anyRelation())
                AdmissibleRecipe.SELF_JOIN -> anyRelation().let { join(it, it) }
                AdmissibleRecipe.JOIN_CHAIN -> {
                    val length = 3 + random.nextInt(config.maxBodyAtoms - 2)
                    val chain = mutableListOf<String>()
                    val atoms = mutableListOf<List<TVar>>()
                    repeat(length) {
                        val relation = anyRelation()
                        val vars = if (atoms.isEmpty()) freshVars(relation) else sharing(relation, atoms.last())
                        chain += relation
                        atoms += vars
                    }
                    val all = atoms.flatten().distinct()
                    rule(headName("q"), all, chain.zip(atoms).map { (r, vs) -> Literal.Positive(atom(r, vs)) })
                    joinChains += chain.toList()
                }
                AdmissibleRecipe.CROSS_PRODUCT -> {
                    val a = anyRelation()
                    val b = anyRelation()
                    val av = freshVars(a)
                    val bv = freshVars(b)
                    rule(
                        headName("q"),
                        listOf(av.pick(), bv.pick()),
                        listOf(Literal.Positive(atom(a, av)), Literal.Positive(atom(b, bv))),
                    )
                }
                AdmissibleRecipe.SEMIJOIN -> {
                    val a = anyRelation()
                    val av = freshVars(a)
                    val witnessVar = av.pick()
                    val b = relationsWith(witnessVar.type).pick()
                    val bTypes = types(b)
                    val position = bTypes.indices.filter { bTypes[it] == witnessVar.type }.pick()
                    val bTerms: List<Term> = bTypes.mapIndexed { p, type -> if (p == position) witnessVar.term else constant(type) }
                    rule(
                        headName("q"),
                        av - witnessVar,
                        listOf(Literal.Positive(atom(a, av)), Literal.Positive(Atom(b, bTerms))),
                    )
                }
                AdmissibleRecipe.NEGATION -> {
                    val a = anyRelation()
                    val av = freshVars(a)
                    val bound = av.pick()
                    val b = relationsWith(bound.type).pick()
                    val bTypes = types(b)
                    val position = bTypes.indices.filter { bTypes[it] == bound.type }.pick()
                    val bTerms: List<Term> = bTypes.mapIndexed { p, type -> if (p == position) bound.term else constant(type) }
                    rule(headName("q"), av, listOf(Literal.Positive(atom(a, av)), Literal.Negated(Atom(b, bTerms))))
                }
                AdmissibleRecipe.UNION_RULES -> {
                    val head = headName("q")
                    repeat(2 + random.nextInt(config.maxRules - 1)) {
                        val relation = anyRelation()
                        val vars = freshVars(relation)
                        rule(head, listOf(vars.filter { it.type == AttrType.INT }.pick()), listOf(Literal.Positive(atom(relation, vars))))
                    }
                }
                AdmissibleRecipe.DEFINE_UNION -> defineSetOp(SetOpKind.UNION, all = false)
                AdmissibleRecipe.DEFINE_INTERSECT -> defineSetOp(SetOpKind.INTERSECTION, all = false)
                AdmissibleRecipe.DEFINE_EXCEPT -> defineSetOp(SetOpKind.DIFFERENCE, all = false)
                AdmissibleRecipe.DEFINE_LEFT_OUTER_JOIN -> defineOuterJoin(OuterJoinSide.LEFT)
                AdmissibleRecipe.DEFINE_RIGHT_OUTER_JOIN -> defineOuterJoin(OuterJoinSide.RIGHT)
                AdmissibleRecipe.DEFINE_FULL_OUTER_JOIN -> defineOuterJoin(OuterJoinSide.FULL)
                AdmissibleRecipe.AGGREGATE_COUNT -> aggregate(AggregateKind.COUNT)
                AdmissibleRecipe.AGGREGATE_SUM -> aggregate(AggregateKind.SUM)
                AdmissibleRecipe.AGGREGATE_AVG -> aggregate(AggregateKind.AVG)
                AdmissibleRecipe.AGGREGATE_MIN -> aggregate(AggregateKind.MIN)
                AdmissibleRecipe.AGGREGATE_MAX -> aggregate(AggregateKind.MAX)
                AdmissibleRecipe.AGGREGATE_TOP_K -> aggregate(AggregateKind.TOP_K)
                AdmissibleRecipe.AGGREGATE_COLLECT_TO_SET -> aggregate(AggregateKind.COLLECT_TO_SET)
                AdmissibleRecipe.IDB_CONSUMER -> {
                    val a = anyRelation()
                    val av = freshVars(a)
                    val exposed = if (random.nextBoolean()) av else properSubset(av)
                    val p = headName("p")
                    rule(p, exposed, listOf(Literal.Positive(atom(a, av))))
                    val pv = exposed.map { fresh(it.type) }
                    val b = relationsWith(pv.pick().type).pick()
                    val bv = sharing(b, pv)
                    rule(
                        headName("q"),
                        (pv + bv).distinct(),
                        listOf(Literal.Positive(atom(p, pv)), Literal.Positive(atom(b, bv))),
                    )
                }
            }
        }

        /** `q(proper subset) :- r(fresh vars).` — also the base every injected construct joins. */
        fun project() {
            val relation = anyRelation()
            val vars = freshVars(relation)
            rule(headName("q"), properSubset(vars), listOf(Literal.Positive(atom(relation, vars))))
        }

        fun join(a: String, b: String) {
            val av = freshVars(a)
            val bv = sharing(b, av)
            rule(headName("q"), (av + bv).distinct(), listOf(Literal.Positive(atom(a, av)), Literal.Positive(atom(b, bv))))
        }

        /**
         * A leaf over some relation whose exposed variables have exactly [shape]'s types, in
         * order: [shape] is embedded leftmost into the relation's columns, every other column a
         * constant. The relation is drawn from those that can embed it.
         */
        fun leafOfShape(shape: List<AttrType>): Pair<Atom, List<TVar>> {
            fun embedding(relation: String): List<Int>? {
                val relationTypes = types(relation)
                val positions = mutableListOf<Int>()
                var p = 0
                for (type in shape) {
                    while (p < relationTypes.size && relationTypes[p] != type) p++
                    if (p == relationTypes.size) return null
                    positions += p++
                }
                return positions
            }
            val relation = relationNames.filter { embedding(it) != null }.pick()
            val positions = embedding(relation)!!
            val vars = mutableListOf<TVar>()
            val terms = types(relation).mapIndexed { p, type ->
                if (p in positions) fresh(type).also { vars += it }.term else constant(type)
            }
            return Atom(relation, terms) to vars
        }

        fun defineSetOp(kind: SetOpKind, all: Boolean) {
            val a = anyRelation()
            val aTypes = types(a)
            val exposed = aTypes.indices.filter { random.nextBoolean() }.ifEmpty { listOf(aTypes.indices.toList().pick()) }
            val shape = exposed.map { aTypes[it] }
            val (left, _) = leafOfShape(shape)
            val (right, _) = leafOfShape(shape)
            var expr: RelationalExpr = RelationalExpr.SetOp(kind, RelationalExpr.Relation(left), RelationalExpr.Relation(right), all)
            if (random.nextInt(3) == 0) {
                val (third, _) = leafOfShape(shape)
                expr = RelationalExpr.SetOp(SetOpKind.entries.pick(), expr, RelationalExpr.Relation(third), all = false)
            }
            definitions += Definition(atom(headName("h"), shape.map { fresh(it) }), expr)
        }

        fun defineOuterJoin(side: OuterJoinSide) {
            val a = anyRelation()
            val b = anyRelation()
            val av = freshVars(a)
            val bv = freshVars(b)
            val pairs = av.flatMap { l -> bv.filter { it.type == l.type }.map { l to it } }
            val (l, r) = pairs.pick()
            val expr = RelationalExpr.OuterJoin(
                side,
                RelationalExpr.Relation(atom(a, av)),
                RelationalExpr.Relation(atom(b, bv)),
                listOf(JoinKey(l.term, r.term)),
            )
            val headVars = (av + (bv - r)).map { fresh(it.type) }
            definitions += Definition(atom(headName("h"), headVars), expr)
        }

        /**
         * `@kind q([group,] aggregated) :- body.` over one EDB atom or a two-atom equi-join, no
         * constants, so the aggregate's input is key-preserving (cab.5-D8). COUNT/SUM/AVG
         * aggregate an INT or LONG column.
         */
        fun aggregate(kind: AggregateKind) {
            val a = anyRelation()
            val av = freshVars(a)
            val body = mutableListOf<Literal>(Literal.Positive(atom(a, av)))
            var vars = av
            if (random.nextInt(3) == 0) {
                val b = anyRelation()
                val bv = sharing(b, av)
                body += Literal.Positive(atom(b, bv))
                vars = (av + bv).distinct()
            }
            val numeric = kind == AggregateKind.COUNT || kind == AggregateKind.SUM || kind == AggregateKind.AVG
            val aggregated = vars.filter { !numeric || it.type == AttrType.INT || it.type == AttrType.LONG }.pick()
            val group = if (random.nextBoolean()) listOf((vars - aggregated).pick()) else emptyList()
            val k = if (kind == AggregateKind.TOP_K) 1 + random.nextInt(3) else null
            rule(headName("q"), group + aggregated, body, Aggregate(kind, k))
        }

        fun inject(construct: InjectedConstruct) {
            when (construct) {
                InjectedConstruct.UNSAFE_RULE -> {
                    val relation = anyRelation()
                    val vars = freshVars(relation)
                    val unbound = fresh(AttrType.INT)
                    rule(headName("u"), listOf(vars.pick(), unbound), listOf(Literal.Positive(atom(relation, vars))))
                }
                InjectedConstruct.RECURSIVE_PAIR -> {
                    val relation = anyRelation()
                    val vars = freshVars(relation)
                    val x = vars.filter { it.type == AttrType.INT }.pick()
                    val p = headName("p")
                    val s = headName("s")
                    rule(p, listOf(x), listOf(Literal.Positive(atom(relation, vars)), Literal.Positive(atom(s, listOf(x)))))
                    rule(s, listOf(x), listOf(Literal.Positive(atom(p, listOf(x)))))
                }
                InjectedConstruct.EDB_NAMED_HEAD -> {
                    val target = anyRelation()
                    val arity = types(target).size
                    val others = relationNames - target
                    val body = mutableListOf<Literal>()
                    val vars = mutableListOf<TVar>()
                    while (vars.size < arity) {
                        val relation = others.pick()
                        val vs = freshVars(relation)
                        body += Literal.Positive(atom(relation, vs))
                        vars += vs
                    }
                    rule(target, vars.take(arity), body)
                }
                InjectedConstruct.ALL_SET_OPERATION -> defineSetOp(SetOpKind.entries.pick(), all = true)
                InjectedConstruct.NON_ROOT_AGGREGATE -> {
                    val relation = anyRelation()
                    val vars = freshVars(relation)
                    val kind = listOf(AggregateKind.MIN, AggregateKind.MAX, AggregateKind.COLLECT_TO_SET).pick()
                    val m = headName("m")
                    val mHead = listOf(vars[0], vars[1])
                    rule(m, mHead, listOf(Literal.Positive(atom(relation, vars))), Aggregate(kind))
                    // The consumer must put a node above the aggregate: a body of the bare IDB
                    // atom at full arity plans as the aggregate itself at the consumer's root
                    // (the planner inlines it with no Project), which lowers. A narrowing head
                    // puts a Project on top.
                    val consumer = mHead.map { fresh(it.type) }
                    rule(headName("q"), listOf(consumer[0]), listOf(Literal.Positive(atom(m, consumer))))
                }
                InjectedConstruct.SUM_OVER_STRING -> {
                    val relation = relationsWith(AttrType.STRING).pick()
                    val vars = freshVars(relation)
                    val summed = vars.filter { it.type == AttrType.STRING }.pick()
                    val group = if (random.nextBoolean()) listOf((vars - summed).pick()) else emptyList()
                    rule(headName("q"), group + summed, listOf(Literal.Positive(atom(relation, vars))), Aggregate(AggregateKind.SUM))
                }
            }
        }
    }
}

/**
 * Renders a [Query] in `QueryParser`'s concrete syntax, so that parsing the text against the
 * query's catalog yields the same AST (the sweep asserts it per case). Rules first, then
 * definitions, each in list order; every non-leaf relational operand is parenthesized. Covers the
 * term types the generator emits (INT, LONG, STRING) and BOOL/DOUBLE for completeness; a STRING
 * constant is quoted without escaping, which the generator's `a`..`d` domain never needs.
 */
object QueryText {

    fun render(query: Query): String = buildString {
        query.rules.forEach { appendLine(rule(it)) }
        query.definitions.forEach { appendLine("define ${atom(it.head)} := ${expr(it.expr)}.") }
    }

    private fun rule(rule: Rule): String {
        val prefix = rule.aggregate?.let { aggregate(it) + " " }.orEmpty()
        val body = if (rule.body.isEmpty()) "" else " :- " + rule.body.joinToString(", ") { literal(it) }
        return "$prefix${atom(rule.head)}$body."
    }

    private fun aggregate(aggregate: Aggregate): String = when (aggregate.kind) {
        AggregateKind.COUNT -> "@count"
        AggregateKind.SUM -> "@sum"
        AggregateKind.AVG -> "@avg"
        AggregateKind.MIN -> "@min"
        AggregateKind.MAX -> "@max"
        AggregateKind.TOP_K -> "@topK(${aggregate.k})"
        AggregateKind.COLLECT_TO_SET -> "@collectToSet"
    }

    private fun literal(literal: Literal): String = when (literal) {
        is Literal.Positive -> atom(literal.atom)
        is Literal.Negated -> "not ${atom(literal.atom)}"
        is Literal.Comparison -> "${term(literal.left)} ${op(literal.op)} ${term(literal.right)}"
    }

    private fun op(op: ComparisonOp): String = when (op) {
        ComparisonOp.EQ -> "="
        ComparisonOp.NE -> "!="
        ComparisonOp.LT -> "<"
        ComparisonOp.LE -> "<="
        ComparisonOp.GT -> ">"
        ComparisonOp.GE -> ">="
    }

    private fun atom(atom: Atom): String = "${atom.predicate}(${atom.terms.joinToString(", ") { term(it) }})"

    private fun term(term: Term): String = when (term) {
        is Term.Var -> term.name
        is Term.Const -> when (term.type) {
            AttrType.INT, AttrType.DOUBLE, AttrType.BOOL -> term.value.toString()
            AttrType.LONG -> "${term.value}L"
            AttrType.STRING -> "\"${term.value}\""
        }
    }

    private fun expr(expr: RelationalExpr): String = when (expr) {
        is RelationalExpr.Relation -> atom(expr.atom)
        is RelationalExpr.SetOp -> {
            val keyword = when (expr.kind) {
                SetOpKind.UNION -> "union"
                SetOpKind.INTERSECTION -> "intersect"
                SetOpKind.DIFFERENCE -> "except"
            }
            "${operand(expr.left)} $keyword${if (expr.all) " all" else ""} ${operand(expr.right)}"
        }
        is RelationalExpr.OuterJoin -> {
            val side = expr.side.name.lowercase()
            val keys = expr.on.joinToString(", ") { "${it.left.name} = ${it.right.name}" }
            "${operand(expr.left)} $side outer join ${operand(expr.right)} on $keys"
        }
    }

    private fun operand(expr: RelationalExpr): String =
        if (expr is RelationalExpr.Relation) expr(expr) else "(${expr(expr)})"
}
