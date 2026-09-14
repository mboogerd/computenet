package civictech.query.diag

import civictech.cell.graph.SpawnStep
import civictech.query.QueryCompiler
import civictech.query.ast.Atom
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.diag.RejectionCoverage.catalog
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.parse.SpanTable
import civictech.query.schema.AttrType
import civictech.query.schema.Catalog
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [QRY1-REJECT-03]: `QueryCompiler.compile` is total. A hand-written corpus of malformed,
 * unsafe, inadmissible and unsupported inputs — one per fenced `Planner.plan` precondition
 * (named by the planner's message text), one per pre-existing code, and the edge shapes the
 * bead lists — is compiled through both overloads, and every call must return a
 * [CompileResult]. A throw is collected and reported by case name rather than aborting the run,
 * so a regression names every input it breaks at once.
 *
 * The planner's internal checks over plan-node columns (project subset, unplaced comparisons,
 * definition / set-operation / outer-join column agreement) are not fenced by a code of their
 * own; this corpus running clean is the evidence they are unreachable behind well-formedness
 * and safety.
 *
 * Deliberately absent: `union all` / `intersect all` / `except all` — the planner's `ALL`
 * precondition is the bag-semantics sibling task's to fence (`BAG_SEMANTICS_REQUIRED`), not
 * computenet-cab.5.1's. A `RelationalExpr` nested deep enough to exhaust the JVM stack is also
 * outside this corpus: the AST case below nests 200 levels, three times the parser's
 * `MAX_NESTING`, and no deeper.
 */
class CompileTotalityTest {

    private data class TextCase(val name: String, val source: String, val catalog: Catalog)

    private val r1s1 = catalog("r" to 1, "s" to 1)
    private val r2 = catalog("r" to 2)

    private val textCorpus: List<TextCase> = listOf(
        TextCase("empty text", "", r1s1),
        TextCase("garbage text", "  ((( :- ,, . @@ \"unterminated", r1s1),
        TextCase("SYNTAX_ERROR missing terminator", "q(X) :- r(X)", r1s1),
        TextCase(
            "nesting-depth overflow",
            "define h(X) := " + "(".repeat(QueryParser.MAX_NESTING + 6) + "r(X)" + ")".repeat(QueryParser.MAX_NESTING + 6) + ".",
            r1s1,
        ),
        TextCase("UNSAFE_RULE unbound head variable", "q(X) :- r(Y).", r1s1),
        TextCase("UNSAFE_RULE comparison over an unbound variable (unplaced comparison check)", "q(X) :- r(X), X < Y.", r1s1),
        TextCase("UNSAFE_RULE aggregate head not bound by the body", "@count c(X, N) :- r(X, Y).", r2),
        TextCase("EDB_REDEFINED", "r(X) :- s(X).", r1s1),
        TextCase("RECURSION_UNSUPPORTED self", "p(X) :- p(X), r(X).", r1s1),
        TextCase("RECURSION_UNSUPPORTED mutual, with a dependent", "p(X) :- q(X), r(X).\nq(X) :- p(X).\nd(X) :- q(X).", r1s1),
        TextCase("defined by both a rule and a define statement", "q(X) :- r(X).\ndefine q(X) := s(X).\nd(X) :- q(X).", r1s1),
        TextCase("defined by more than one define statement", "define q(X) := r(X).\ndefine q(X) := s(X).", r1s1),
        TextCase("a constant in a head", "q(X, 1) :- r(X).", r1s1),
        TextCase("a repeated variable in a head", "q(X, X) :- r(X).", r1s1),
        TextCase("names neither a catalog relation nor a head (body)", "q(X) :- nope(X).", r1s1),
        TextCase("names neither a catalog relation nor a head (negated)", "q(X) :- r(X), not nope(X).", r1s1),
        TextCase("names neither a catalog relation nor a head (definition leaf)", "define h(X) := r(X) except nope(X).", r1s1),
        TextCase("No rules define predicate, over an empty catalog", "q(X) :- r(X).", Catalog(emptyMap())),
        TextCase("has no positive body atom (a fact)", "q(1).", r1s1),
        TextCase("has no positive body atom (a variable fact)", "q(X).", r1s1),
        TextCase("has no positive body atom (comparison only)", "q() :- 1 < 2.", r1s1),
        TextCase("has no positive body atom (negation only)", "q(X) :- not r(X).", r1s1),
        TextCase("aggregate-annotated nullary head", "@count c() :- r(X).", r1s1),
        TextCase("definition used at another arity", "define h(X) := r(X).\nq(X) :- h(X, Y), r(Y).", r1s1),
        TextCase("definition head arity vs its expression", "define h(X, Y) := r(X).", r1s1),
        TextCase("atom arity vs catalog", "q(X) :- r(X, Y).", r1s1),
        TextCase("rule head used at another arity", "p(X) :- r(X).\nq(X) :- p(X, Y), r(Y).", r1s1),
        TextCase("two rules of one head at different arities", "q(X) :- r(X).\nq(X, Y) :- r(X), s(Y).", r1s1),
        TextCase("set-operation operand arity", "define h(X) := r(X) union s(X, Y).", catalog("r" to 1, "s" to 2)),
        TextCase("nested set-operation operand arity", "define h(X) := r(X) union (s(X) intersect t(X, Y)).", catalog("r" to 1, "s" to 1, "t" to 2)),
        TextCase("outer join key not a column", "define h(X, Y) := r(X) left outer join s(Y) on X = Z.", r1s1),
        TextCase("outer join key used twice", "define h(X, Y) := r(X, Y) full outer join s(A, B) on X = A, X = B.", catalog("r" to 2, "s" to 2)),
        TextCase("a query with only definitions", "define h(X) := r(X) union s(X).\ndefine o(X) := r(X) left outer join s(Y) on X = Y.", r1s1),
        TextCase("NO_LOWERING non-root aggregate", "@count c(X, N) :- r(X, N).\nq(X) :- c(X, N).", r2),
        TextCase("NO_LOWERING ill-typed comparison", "q(X) :- r(X, Y), Y = \"three\".", r2),
        TextCase("aggregate and plain rules for one head", "@count q(X, N) :- r(X, N).\nq(X, N) :- r(X, N).", r2),
        TextCase("a dependent chain of a rejected head", "q(X) :- nope(X).\na(X) :- q(X), r(X, X).\nb(X) :- a(X), not q(X), r(X, Y).", r2),
        TextCase("every phase at once", "q(X) :- nope(X).\nbad(X) :- r(Y, Z).\n@count c(X, N) :- r(X, N).\nu(X) :- c(X, N).\np(X) :- p(X), r(X, X).", r2),
    )

    /** Queries only the AST surface can state (or can state without spans). */
    private val astCorpus: List<Pair<String, () -> CompileResult>> = run {
        fun v(name: String) = Term.Var(name)
        val r1 = catalog("r" to 1)
        val deep = (1..200).fold<Int, RelationalExpr>(RelationalExpr.Relation(Atom("r", listOf(v("X"))))) { acc, _ ->
            RelationalExpr.SetOp(SetOpKind.UNION, acc, RelationalExpr.Relation(Atom("r", listOf(v("X")))))
        }
        val fact = Query(listOf(Rule(Atom("q", listOf(Term.Const(1, AttrType.INT))), emptyList())), r1)
        listOf(
            "an empty built query" to { QueryCompiler.compile(Query(emptyList(), Catalog(emptyMap()))) },
            "a built fact" to { QueryCompiler.compile(fact) },
            "a built fact with a span table of the wrong size" to {
                QueryCompiler.compile(fact, SpanTable(emptyList(), listOf(Locus.SourceSpan(1, 1, 1, 2))))
            },
            "a definition 200 set operations deep" to {
                QueryCompiler.compile(Query(emptyList(), r1, listOf(civictech.query.ast.Definition(Atom("h", listOf(v("X"))), deep))))
            },
            "an unknown negated predicate in a built rule" to {
                QueryCompiler.compile(
                    Query(listOf(Rule(Atom("q", listOf(v("X"))), listOf(Literal.Positive(Atom("r", listOf(v("X")))), Literal.Negated(Atom("nope", listOf(v("X"))))))), r1),
                )
            },
        )
    }

    @Test
    fun `QRY1 §REJECT-03 every corpus input returns a CompileResult through both overloads and never throws`() {
        textCorpus.size shouldBeGreaterThanOrEqual 25
        val thrown = mutableListOf<String>()
        fun attempt(name: String, call: () -> CompileResult) {
            try {
                call()
            } catch (t: Throwable) {
                thrown += "$name: ${t::class.simpleName}: ${t.message?.take(160)}"
            }
        }
        for (case in textCorpus) {
            attempt("${case.name} [text]") { QueryCompiler.compile(case.source, case.catalog) }
            val parsed = QueryParser.parse(case.source, case.catalog) as? ParseResult.Parsed ?: continue
            attempt("${case.name} [ast]") { QueryCompiler.compile(parsed.query) }
            attempt("${case.name} [ast+spans]") { QueryCompiler.compile(parsed.query, parsed.spans) }
        }
        for ((name, call) in astCorpus) attempt("$name [ast]", call)

        withClue("compile threw instead of returning a CompileResult:\n${thrown.joinToString("\n")}") {
            thrown.shouldBeEmpty()
        }
    }

    @Test
    fun `every corpus input that is not the well-formed boundary is Rejected, never silently Compiled`() {
        val admissible = setOf("empty text", "a query with only definitions")
        val compiled = textCorpus
            .filter { it.name !in admissible }
            .filter { QueryCompiler.compile(it.source, it.catalog) is CompileResult.Compiled }
            .map { it.name }
        withClue("corpus inputs that compiled: $compiled") {
            compiled.shouldBeEmpty()
        }
    }

    @Test
    fun `boundary - a well-formed query returns Compiled and its spec applies to a SimWorld host`() {
        val compiled = QueryCompiler.compile("q(X) :- r(X, Y), Y > 3.", r2)
            .shouldBeInstanceOf<CompileResult.Compiled>().query
        val world = SimWorld(seed = 11)

        val applied = compiled.applyTo(world.host.managementInlet)

        applied.handles.keys shouldBe compiled.spec.lowered().filterIsInstance<SpawnStep>().map { it.handle }.toSet()
        world.runToIdle()
    }
}
