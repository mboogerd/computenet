package civictech.query.diag

import civictech.query.QueryCompiler
import civictech.query.ast.Atom
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.lower.PlanFixtures
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema

/**
 * The BS-13 registry (cab.5-D1, cab.5-D11): for every [RejectionCode] variant, the named
 * inputs that produce it through [QueryCompiler]. `RejectionExhaustivenessTest` asserts the
 * key set is exactly `RejectionCode.entries` and that every producer yields its code;
 * `RejectionTest`'s named per-code tests run the same producers, so a registered producer is
 * also a behaviour test and not a list entry only.
 *
 * A feature that adds a variant registers its producer here in the same change — that is what
 * [QRY1-REJECT-05]'s "adding an unsupported-construct path without a test fails the build"
 * means in this module.
 */
internal object RejectionCoverage {

    /** A named input and the compile call that produces its [CompileResult]. */
    data class Producer(val name: String, val compile: () -> CompileResult)

    /** Every relation named, each with INT attributes of the given arity. */
    fun catalog(vararg relations: Pair<String, Int>): Catalog = PlanFixtures.catalog(*relations)

    /**
     * One relation per entry: INT attributes `a0..a(n-1)`, with the row key on the attributes at
     * the given positions, or no declared row key when none are given. A `COUNT`/`SUM`/`AVG`
     * producer for any other code needs a keyed input, or it is also `BAG_SEMANTICS_REQUIRED`.
     */
    fun keyed(vararg relations: Triple<String, Int, List<Int>>): Catalog = Catalog(
        relations.associate { (name, arity, key) ->
            name to RelationSchema(
                (0 until arity).map { Attribute("a$it", AttrType.INT) },
                key.takeIf { it.isNotEmpty() }?.mapTo(LinkedHashSet()) { "a$it" },
            )
        },
    )

    /** BS-7's `lineitem(o, x, v)`, row key `{o}`. */
    val lineitem: Catalog = keyed(Triple("lineitem", 3, listOf(0)))

    private fun text(name: String, source: String, catalog: Catalog) =
        Producer(name) { QueryCompiler.compile(source, catalog) }

    private fun built(name: String, query: Query) = Producer(name) { QueryCompiler.compile(query) }

    private fun v(name: String) = Term.Var(name)

    val producers: Map<RejectionCode, List<Producer>> = mapOf(
        RejectionCode.SYNTAX_ERROR to listOf(
            text("a rule missing its terminating dot", "q(X) :- r(X)", catalog("r" to 1)),
        ),
        RejectionCode.UNSAFE_RULE to listOf(
            text("a head variable bound by no positive body atom", "q(X) :- r(Y).", catalog("r" to 1)),
        ),
        RejectionCode.EDB_REDEFINED to listOf(
            text("a rule head naming a catalog relation", "r(X) :- s(X).", catalog("r" to 1, "s" to 1)),
        ),
        RejectionCode.RECURSION_UNSUPPORTED to listOf(
            text("a self-recursive rule", "p(X) :- p(X), r(X).", catalog("r" to 1)),
        ),
        RejectionCode.NO_LOWERING to listOf(
            text(
                "an aggregate consumed as a relation (non-root GroupAggregate)",
                "@count c(X, N) :- r(X, N).\nq(X) :- c(X, N).",
                keyed(Triple("r", 2, listOf(0))),
            ),
            text(
                "an ill-typed comparison (INT column against a STRING constant)",
                "q(X) :- r(X, Y), Y = \"three\".",
                catalog("r" to 2),
            ),
        ),
        RejectionCode.UNKNOWN_PREDICATE to listOf(
            text("a body atom naming no relation and no head", "q(X) :- nope(X).", catalog("r" to 1)),
            built(
                "a built query whose body atom names no relation and no head",
                Query(listOf(Rule(Atom("q", listOf(v("X"))), listOf(Literal.Positive(Atom("nope", listOf(v("X"))))))), catalog("r" to 1)),
            ),
        ),
        RejectionCode.ARITY_MISMATCH to listOf(
            text("an atom used at an arity other than its catalog schema's", "q(X) :- r(X, Y).", catalog("r" to 1)),
        ),
        RejectionCode.PREDICATE_REDEFINED to listOf(
            text(
                "a head defined by both a rule and a define",
                "q(X) :- r(X).\ndefine q(X) := s(X).",
                catalog("r" to 1, "s" to 1),
            ),
        ),
        RejectionCode.UNPLANNABLE_STATEMENT to listOf(
            text("a fact", "q(1).", catalog("r" to 1)),
        ),
        RejectionCode.ORDER_DEPENDENT_AGGREGATE to listOf(
            text("the arrival-order aggregate @first", "@first f(X) :- r(X, Y).", catalog("r" to 2)),
            text("the arrival-order aggregate @last", "@last f(X) :- r(X, Y).", catalog("r" to 2)),
            text("the arrival-order aggregate @scan", "@scan f(X) :- r(X, Y).", catalog("r" to 2)),
            text("an aggregate name outside the closed seven", "@foo f(X) :- r(X, Y).", catalog("r" to 2)),
        ),
        RejectionCode.BAG_SEMANTICS_REQUIRED to listOf(
            text("EXCEPT ALL (BS-1)", "define h(X) := r(X) except all s(X).", catalog("r" to 1, "s" to 1)),
            text("UNION ALL", "define h(X) := r(X) union all s(X).", catalog("r" to 1, "s" to 1)),
            text("INTERSECT ALL", "define h(X) := r(X) intersect all s(X).", catalog("r" to 1, "s" to 1)),
            text(
                "a sum over a projection that drops the row key (BS-7)",
                "@sum total(V) :- q(X, V).\nq(X, V) :- lineitem(O, X, V).",
                lineitem,
            ),
            text(
                "a count over a projection that drops the row key",
                "@count c(X) :- p(X).\np(X) :- e(X, Y).",
                keyed(Triple("e", 2, listOf(0, 1))),
            ),
            text(
                "an avg over a projection that drops the row key",
                "@avg a(V) :- q(X, V).\nq(X, V) :- lineitem(O, X, V).",
                lineitem,
            ),
            text(
                "a sum over a relation with no declared row key",
                "@sum t(V) :- p(V).\np(V) :- r(K, V).",
                catalog("r" to 2),
            ),
        ),
    )
}
