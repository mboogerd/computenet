package civictech.query.diag

import civictech.query.QueryCompiler
import civictech.query.ast.Atom
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.lower.PlanFixtures
import civictech.query.schema.Catalog

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
                catalog("r" to 2),
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
    )
}
