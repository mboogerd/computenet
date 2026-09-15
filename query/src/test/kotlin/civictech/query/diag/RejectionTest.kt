package civictech.query.diag

import civictech.query.QueryCompiler
import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.diag.RejectionCoverage.catalog
import civictech.query.diag.RejectionCoverage.keyed
import civictech.query.lower.Lowering
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.schema.Catalog
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The rejection suite epic §8 names: one named test per [RejectionCode] variant, each running
 * its [RejectionCoverage] producer through [QueryCompiler] ([QRY1-REJECT-05]); the
 * `NO_LOWERING` mapping ([QRY1-REJECT-06]); no side effect on a host ([QRY1-REJECT-04]); and
 * multi-phase collection with statement exclusion ([QRY1-REJECT-01], [QRY1-REJECT-10]).
 * Per-precondition well-formedness tests are `civictech.query.parse.WellFormednessAnalysisTest`'s.
 */
class RejectionTest {

    private fun rejectionsOf(code: RejectionCode, index: Int = 0): List<Rejection> {
        val producer = RejectionCoverage.producers.getValue(code)[index]
        return withClue(producer.name) {
            producer.compile().shouldBeInstanceOf<CompileResult.Rejected>().rejections
        }
    }

    private fun spansOf(source: String, catalog: Catalog) =
        (QueryParser.parse(source, catalog) as ParseResult.Parsed).spans

    // ------------------------------------------------------------------ one test per code

    @Test
    fun `SYNTAX_ERROR - a rule missing its terminator is rejected as a syntax error, not thrown`() {
        rejectionsOf(RejectionCode.SYNTAX_ERROR).map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR)
    }

    @Test
    fun `UNSAFE_RULE - an unbound head variable is rejected at the rule's span`() {
        val rejection = rejectionsOf(RejectionCode.UNSAFE_RULE).single()
        rejection.code shouldBe RejectionCode.UNSAFE_RULE
        rejection.locus shouldBe spansOf("q(X) :- r(Y).", catalog("r" to 1)).ruleSpan(0)
    }

    @Test
    fun `EDB_REDEFINED - a rule head naming a catalog relation is rejected`() {
        rejectionsOf(RejectionCode.EDB_REDEFINED).map { it.code } shouldBe listOf(RejectionCode.EDB_REDEFINED)
    }

    @Test
    fun `RECURSION_UNSUPPORTED - a self-recursive rule is rejected, and the planner never sees it`() {
        rejectionsOf(RejectionCode.RECURSION_UNSUPPORTED).map { it.code } shouldBe
            listOf(RejectionCode.RECURSION_UNSUPPORTED)
    }

    @Test
    fun `NO_LOWERING - a non-root GroupAggregate is rejected naming the node kind at its plan node`() {
        val rejection = rejectionsOf(RejectionCode.NO_LOWERING, 0).single()
        rejection.code shouldBe RejectionCode.NO_LOWERING
        rejection.locus.shouldBeInstanceOf<Locus.PlanNode>().id shouldContain ":groupaggregate"
        rejection.specId shouldBe "[QRY1-REJECT-06] GroupAggregate: ${Lowering.AGGREGATE_NOT_A_RELATION}"
    }

    @Test
    fun `NO_LOWERING - an ill-typed comparison is rejected naming Select and the typing reason`() {
        val rejection = rejectionsOf(RejectionCode.NO_LOWERING, 1).single()
        rejection.code shouldBe RejectionCode.NO_LOWERING
        rejection.locus.shouldBeInstanceOf<Locus.PlanNode>().id shouldContain ":select"
        rejection.specId shouldStartWith "[QRY1-REJECT-06] Select: [QRY1-LOWER-07]"
    }

    @Test
    fun `UNKNOWN_PREDICATE - a body atom naming nothing is rejected at the rule's span, or its statement index when built`() {
        val fromText = rejectionsOf(RejectionCode.UNKNOWN_PREDICATE, 0).single()
        fromText.code shouldBe RejectionCode.UNKNOWN_PREDICATE
        fromText.locus shouldBe spansOf("q(X) :- nope(X).", catalog("r" to 1)).ruleSpan(0)

        val built = rejectionsOf(RejectionCode.UNKNOWN_PREDICATE, 1).single()
        built.code shouldBe RejectionCode.UNKNOWN_PREDICATE
        built.locus shouldBe Locus.RuleStatement(0, "q")
    }

    @Test
    fun `ARITY_MISMATCH - an atom used at an arity other than its catalog schema's is rejected`() {
        rejectionsOf(RejectionCode.ARITY_MISMATCH).map { it.code } shouldBe listOf(RejectionCode.ARITY_MISMATCH)
    }

    @Test
    fun `PREDICATE_REDEFINED - a head defined by a rule and a define is rejected at the define`() {
        val rejection = rejectionsOf(RejectionCode.PREDICATE_REDEFINED).single()
        rejection.code shouldBe RejectionCode.PREDICATE_REDEFINED
        rejection.locus shouldBe spansOf("q(X) :- r(X).\ndefine q(X) := s(X).", catalog("r" to 1, "s" to 1))
            .definitionSpan(0)
    }

    @Test
    fun `UNPLANNABLE_STATEMENT - a fact is rejected, not thrown by the planner`() {
        val codes = rejectionsOf(RejectionCode.UNPLANNABLE_STATEMENT).map { it.code }.toSet()
        codes shouldBe setOf(RejectionCode.UNPLANNABLE_STATEMENT)
    }

    @Test
    fun `ORDER_DEPENDENT_AGGREGATE - every registered producer is refused with the arrival-order-aggregate code alone`() {
        // AMENDS computenet-cab.5.3 (2026-09-15): cab.5.3 registered ORDER_DEPENDENT_AGGREGATE's
        // producers in RejectionCoverage but RejectionTest had no per-code entry for it — added
        // here, following the BAG_SEMANTICS_REQUIRED test just below as the pattern for a code
        // with more than one registered producer.
        RejectionCoverage.producers.getValue(RejectionCode.ORDER_DEPENDENT_AGGREGATE).forEach { producer ->
            val rejections = withClue(producer.name) {
                producer.compile().shouldBeInstanceOf<CompileResult.Rejected>().rejections
            }
            withClue("${producer.name} -> $rejections") {
                rejections.map { it.code } shouldBe listOf(RejectionCode.ORDER_DEPENDENT_AGGREGATE)
            }
        }
    }

    @Test
    fun `exceptAll is refused with the bag-semantics code`() {
        // BS-1 ([QRY1-SEM-04], [QRY1-REJECT-01], [QRY1-REJECT-04]).
        val source = "define h(X) := r(X) except all s(X)."
        val catalog = catalog("r" to 1, "s" to 1)
        val world = SimWorld(seed = 43)
        var published = 0
        world.registry.onPublish { published++ }

        val result = QueryCompiler.compile(source, catalog)

        withClue("an EXCEPT ALL compile must not be Compiled (no GraphSpec exists by type)") {
            (result is CompileResult.Compiled) shouldBe false
        }
        val rejection = result.shouldBeInstanceOf<CompileResult.Rejected>().rejections.single()
        rejection.code shouldBe RejectionCode.BAG_SEMANTICS_REQUIRED
        rejection.locus shouldBe spansOf(source, catalog).definitionSpan(0)
        rejection.specId shouldStartWith "[QRY1-SEM-04] EXCEPT ALL"
        rejection.specId shouldContain "[24-OP-SEMIJOIN-01]"
        rejection.specId shouldContain "96 §E6 / 95 R17"
        world.runToIdle() shouldBe 0
        withClue("cells published on the host after a rejected EXCEPT ALL compile") { published shouldBe 0 }
    }

    @Test
    fun `BAG_SEMANTICS_REQUIRED - every registered producer is refused with the bag-semantics code alone, naming the owners`() {
        RejectionCoverage.producers.getValue(RejectionCode.BAG_SEMANTICS_REQUIRED).forEach { producer ->
            val rejections = withClue(producer.name) {
                producer.compile().shouldBeInstanceOf<CompileResult.Rejected>().rejections
            }
            withClue("${producer.name} -> $rejections") {
                rejections.map { it.code } shouldBe listOf(RejectionCode.BAG_SEMANTICS_REQUIRED)
                rejections.single().specId shouldContain "owners: 96 §E6 / 95 R17"
            }
        }
    }

    // ------------------------------------------------------------------ NO_LOWERING mapping

    @Test
    fun `QRY1 §REJECT-06 every lowering refusal becomes its own NO_LOWERING rejection, never collapsed`() {
        // Two independent refusals in two roots: both are reported, one rejection each.
        val result = QueryCompiler.compile(
            """
            @count c(X, N) :- r(X, N).
            q(X) :- c(X, N).
            t(X) :- r(X, Y), Y = "three".
            """.trimIndent(),
            keyed(Triple("r", 2, listOf(0))),
        )
        val rejections = result.shouldBeInstanceOf<CompileResult.Rejected>().rejections
        rejections.map { it.code } shouldBe listOf(RejectionCode.NO_LOWERING, RejectionCode.NO_LOWERING)
        rejections.map { it.specId.substringBefore(":") } shouldContainExactlyInAnyOrder
            listOf("[QRY1-REJECT-06] GroupAggregate", "[QRY1-REJECT-06] Select")
        rejections.map { (it.locus as Locus.PlanNode).id.substringBefore("/") } shouldContainExactlyInAnyOrder
            listOf("q", "t")
    }

    // ------------------------------------------------------------------ no side effect

    @Test
    fun `QRY1 §REJECT-04 a rejected compile spawns no cell on a fresh SimWorld host`() {
        val world = SimWorld(seed = 41)
        var published = 0
        world.registry.onPublish { published++ }

        val result = QueryCompiler.compile(
            "q(X) :- nope(X).\n@count c(X, N) :- r(X, N).\nu(X) :- c(X, N).",
            keyed(Triple("r", 2, listOf(0))),
        )

        result.shouldBeInstanceOf<CompileResult.Rejected>()
        world.runToIdle() shouldBe 0
        withClue("cells published on the host after a rejected compile") { published shouldBe 0 }

        // Positive control: the same instrument does see the spawns of an applied compile.
        val control = SimWorld(seed = 42)
        var controlPublished = 0
        control.registry.onPublish { controlPublished++ }
        QueryCompiler.compile("q(X) :- r(X, Y), Y > 3.", catalog("r" to 2))
            .shouldBeInstanceOf<CompileResult.Compiled>().query.applyTo(control.host.managementInlet)
        withClue("the publish counter must observe spawns, or its zero above proves nothing") {
            (controlPublished > 0) shouldBe true
        }
    }

    // ------------------------------------------------------------------ multi-phase collection

    @Test
    fun `QRY1 §REJECT-10 one compile reports well-formedness, safety and lowering rejections together`() {
        val source = """
            q(X) :- nope(X).
            bad(X) :- r(Y, Z).
            @count c(X, N) :- r(X, N).
            u(X) :- c(X, N).
            dep(X) :- q(X), r(X, X).
            ok(X) :- r(X, Y).
        """.trimIndent()
        // `r` is keyed so the COUNT's input is key-preserving: this test is about the three
        // phases above, not BAG_SEMANTICS_REQUIRED.
        val catalog = keyed(Triple("r", 2, listOf(0)))
        val spans = spansOf(source, catalog)

        val rejections = QueryCompiler.compile(source, catalog).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        // `dep` references the rejected head `q`: it is excluded from planning, and its
        // exclusion is not itself a rejection — without exclusion the planner would throw on it.
        rejections.map { it.code } shouldBe listOf(
            RejectionCode.UNKNOWN_PREDICATE,
            RejectionCode.UNSAFE_RULE,
            RejectionCode.NO_LOWERING,
        )
        rejections[0].locus shouldBe spans.ruleSpan(0)
        rejections[1].locus shouldBe spans.ruleSpan(1)
        rejections[2].specId shouldStartWith "[QRY1-REJECT-06] GroupAggregate"
    }

    @Test
    fun `QRY1 §REJECT-10 a later-phase statement locus is re-indexed to the caller's query after exclusion`() {
        // Rule 0 is rejected by well-formedness and excluded; the unsafe rule is index 0 of the
        // query safety analysis sees, but index 1 of the caller's.
        fun v(name: String) = Term.Var(name)
        val query = Query(
            rules = listOf(
                Rule(Atom("q", listOf(v("X"))), listOf(Literal.Positive(Atom("nope", listOf(v("X")))))),
                Rule(Atom("bad", listOf(v("X"))), listOf(Literal.Positive(Atom("r", listOf(v("Y"), v("Z")))))),
            ),
            catalog = catalog("r" to 2),
        )

        val rejections = QueryCompiler.compile(query).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections.map { it.code to it.locus } shouldBe listOf(
            RejectionCode.UNKNOWN_PREDICATE to Locus.RuleStatement(0, "q"),
            RejectionCode.UNSAFE_RULE to Locus.RuleStatement(1, "bad"),
        )
    }

    @Test
    fun `QRY1 §REJECT-10 a dependent of a rejected rule whose head is a catalog relation still reports its own rejection`() {
        // `r(X) :- s(X).` is EDB_REDEFINED; `q`'s reference to `r` resolves to the catalog
        // relation, not to that rule, so `q` is independent of the rejection and its own
        // ill-typed comparison must still surface as NO_LOWERING (QueryCompiler's KDoc:
        // a head that is also a catalog relation is not tainted by exclusion).
        val rejections = QueryCompiler.compile(
            "r(X) :- s(X).\nq(X) :- r(X), t(X, Y), Y = \"three\".",
            catalog("r" to 1, "s" to 1, "t" to 2),
        ).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections.map { it.code } shouldBe listOf(RejectionCode.EDB_REDEFINED, RejectionCode.NO_LOWERING)
    }

    @Test
    fun `a query with no rejection in any phase compiles`() {
        QueryCompiler.compile("q(X) :- r(X, Y), Y > 3.", catalog("r" to 2))
            .shouldBeInstanceOf<CompileResult.Compiled>()
    }

    // ------------------------------------------------------------------ BS-14: three independent
    // faults in one pass ([QRY1-REJECT-10]). The design's illustrative text (cab.5's design field)
    // reuses one relation `r` at both arity 1 (the unsafe rule and the order-dependent aggregate)
    // and arity 2 (the key-dropping sum) — an arity clash that would itself add a fourth
    // ARITY_MISMATCH rejection. Renamed the sum's underlying relation to `e` (row key `{K}`,
    // same shape the design names) to keep exactly the three intended codes; the acceptance
    // criteria names the codes, not the relation names.

    @Test
    fun `BS-14 - an unsafe rule, an order-dependent aggregate and a key-dropping sum are all reported from one compile`() {
        val source = """
            @first f(V) :- r(V).
            bad(X) :- r(Y).
            @sum t(V) :- p(V).
            p(V) :- e(K, V).
        """.trimIndent()
        val catalog = Catalog(catalog("r" to 1).relations + keyed(Triple("e", 2, listOf(0))).relations)

        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections shouldHaveSize 3
        rejections.map { it.code } shouldContainExactlyInAnyOrder listOf(
            RejectionCode.UNSAFE_RULE,
            RejectionCode.ORDER_DEPENDENT_AGGREGATE,
            RejectionCode.BAG_SEMANTICS_REQUIRED,
        )
        withClue("the three rejections must name three different statements: $rejections") {
            rejections.map { it.locus }.toSet() shouldHaveSize 3
        }
    }

    @Test
    fun `BS-14 - the same three faults spread across parse, safety, plan and lowering, plus a NO_LOWERING trigger, yield four rejections`() {
        val source = """
            @first f(V) :- r(V).
            bad(X) :- r(Y).
            @sum t(V) :- p(V).
            p(V) :- e(K, V).
            @count c(X, N) :- g(X, N).
            q(X) :- c(X, N).
        """.trimIndent()
        val catalog = Catalog(
            catalog("r" to 1).relations +
                keyed(Triple("e", 2, listOf(0))).relations +
                keyed(Triple("g", 2, listOf(0))).relations,
        )

        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections shouldHaveSize 4
        rejections.map { it.code } shouldContainExactlyInAnyOrder listOf(
            // parse phase
            RejectionCode.ORDER_DEPENDENT_AGGREGATE,
            // safety phase
            RejectionCode.UNSAFE_RULE,
            // plan-level semantics phase
            RejectionCode.BAG_SEMANTICS_REQUIRED,
            // lowering phase: `c` is a non-root GroupAggregate, consumed by `q`
            RejectionCode.NO_LOWERING,
        )
    }

    @Test
    fun `BS-14 - a builder-produced query with no spans reports an unsafe rule and a key-dropping sum at RuleStatement and PlanNode loci`() {
        fun v(name: String) = Term.Var(name)
        val query = Query(
            rules = listOf(
                Rule(Atom("bad", listOf(v("X"))), listOf(Literal.Positive(Atom("r", listOf(v("Y")))))),
                Rule(Atom("p", listOf(v("V"))), listOf(Literal.Positive(Atom("e", listOf(v("K"), v("V")))))),
                Rule(
                    Atom("t", listOf(v("V"))),
                    listOf(Literal.Positive(Atom("p", listOf(v("V"))))),
                    Aggregate(AggregateKind.SUM),
                ),
            ),
            catalog = Catalog(catalog("r" to 1).relations + keyed(Triple("e", 2, listOf(0))).relations),
        )

        val rejections = QueryCompiler.compile(query).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections shouldHaveSize 2
        rejections.map { it.code } shouldContainExactlyInAnyOrder listOf(
            RejectionCode.UNSAFE_RULE,
            RejectionCode.BAG_SEMANTICS_REQUIRED,
        )
        rejections.single { it.code == RejectionCode.UNSAFE_RULE }.locus.shouldBeInstanceOf<Locus.RuleStatement>()
        rejections.single { it.code == RejectionCode.BAG_SEMANTICS_REQUIRED }.locus.shouldBeInstanceOf<Locus.PlanNode>()
    }

    // ------------------------------------------------------------------ exclusion is not a cascade
    // (cab.5-D2): a rule consuming a rejected head reports no rejection of its own and the
    // compiler never throws over it.

    @Test
    fun `exclusion - a rule consuming a rejected head adds no rejection and does not throw`() {
        val source = """
            bad(X) :- r(Y).
            c(X) :- bad(X).
        """.trimIndent()
        val catalog = catalog("r" to 1)

        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections

        withClue("a rule consuming a rejected head must not add a rejection of its own: $rejections") {
            rejections.map { it.code } shouldBe listOf(RejectionCode.UNSAFE_RULE)
        }
    }

    @Test
    fun `exclusion - fixing the one genuine fault stops the cascade, and the consumer surfaces nothing new`() {
        // Same shape as the previous test, but `bad` is now safe: `c` is no longer excluded and
        // must compile without adding a rejection of its own alongside the other genuine faults.
        val source = """
            bad(X) :- r(X).
            c(X) :- bad(X).
            @first f(V) :- r(V).
            @sum t(V) :- p(V).
            p(V) :- e(K, V).
        """.trimIndent()
        val catalog = Catalog(catalog("r" to 1).relations + keyed(Triple("e", 2, listOf(0))).relations)

        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections

        withClue("fixing `bad` alone must leave only the two still-genuine faults, with nothing new from `c`: $rejections") {
            rejections.map { it.code } shouldContainExactlyInAnyOrder listOf(
                RejectionCode.ORDER_DEPENDENT_AGGREGATE,
                RejectionCode.BAG_SEMANTICS_REQUIRED,
            )
        }
    }

    @Test
    fun `exclusion - a dependent of a statement whose head could not be parsed is excluded, not UNKNOWN_PREDICATE`() {
        // computenet-l3338: `bad`'s own statement fails to parse (a missing ')' before ':-')
        // and so never becomes a Statement the compiler could exclude by index -- but its head
        // is still lexically recoverable as `bad`, so QueryParser names it in
        // ParseResult.Rejected.excludedHeads and QueryCompiler taints it before well-formedness
        // runs. `good`, which references `bad`, must therefore be excluded exactly as it would
        // be had `bad` parsed and been rejected some other way -- not reported UNKNOWN_PREDICATE.
        val source = """
            bad(X :- r(X).
            good(X) :- bad(X).
        """.trimIndent()
        val catalog = catalog("r" to 1)

        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections

        withClue("a dependent of an unparseable head must not add a rejection of its own: $rejections") {
            rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR)
        }
    }

    @Test
    fun `SYNTAX_ERROR limit - a missing comma before a final atom resynchronises as a second statement, doubling the rejection`() {
        // QueryParser's class KDoc, "A missing comma versus a missing terminator"
        // (computenet-6atl9): `foo(X) :- r(X) baz(Y).` is token for token also `foo(X) :- r(X)`
        // missing its '.' followed by the fact `baz(Y).`, so recovery resumes at `baz` as it
        // must for a genuine statement after a missing terminator (computenet-cab.5.3), and the
        // fact earns its own rejection alongside `foo`'s SYNTAX_ERROR.
        val catalog = Catalog(catalog("r" to 1).relations + catalog("e" to 2).relations)

        val rejections = QueryCompiler.compile("foo(X) :- r(X) baz(Y).", catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections
        withClue("stated limit: one bad statement, two rejections: $rejections") {
            rejections.map { it.code } shouldBe
                listOf(RejectionCode.SYNTAX_ERROR, RejectionCode.UNPLANNABLE_STATEMENT)
        }

        // The same ambiguity after an aggregate-annotated head missing its ':-': the annotation
        // parsed, so the failure at `e(` is an ordinary resync-allowed one, and `e(X, Y).` reads
        // as a fact after a missing terminator.
        val rejections2 = QueryCompiler.compile("@count c(X) e(X, Y).", catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections
        withClue("stated limit, annotated head: $rejections2") {
            rejections2.map { it.code } shouldBe
                listOf(RejectionCode.SYNTAX_ERROR, RejectionCode.UNPLANNABLE_STATEMENT)
        }
    }

    @Test
    fun `SYNTAX_ERROR - a missing comma before a continuing body is one rejection`() {
        // The shape the bounded head-atom lookahead separates (computenet-6atl9): `baz(X)` is
        // followed by ',', which no rule head can be, so it is skipped as `foo`'s own debris
        // rather than re-parsed as a statement that fails again at the ','.
        val catalog = catalog("r" to 1)

        val parsed = QueryParser.parse("foo(X) :- r(X) baz(X), r(X).", catalog)
            .shouldBeInstanceOf<ParseResult.Rejected>()
        parsed.rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR)
        parsed.excludedHeads shouldBe setOf("foo")
    }

    @Test
    fun `exclusion - a failing fragment after a missing comma does not taint an unrelated statement's head`() {
        // QueryParser's class KDoc, "Exclusion of unparseable heads" (computenet-6atl9): `a`'s
        // body fails at the missing comma before `s(X, .`, whose '(' never closes before the
        // '.', so it cannot be a statement start and is skipped as `a`'s debris. `s` is not
        // recorded, the real `s(X) :- r(X).` stays in the partial query, and `t`, which depends
        // on it, is evaluated on its own account: its UNKNOWN_PREDICATE for `zz` is reported.
        val catalog = catalog("r" to 1)
        val source = """
            a(X) :- r(X) s(X, .
            s(X) :- r(X).
            t(X) :- s(X), zz(X).
        """.trimIndent()

        val parsed = QueryParser.parse(source, catalog).shouldBeInstanceOf<ParseResult.Rejected>()
        parsed.excludedHeads shouldBe setOf("a")

        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections
        rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR, RejectionCode.UNKNOWN_PREDICATE)
    }

    @Test
    fun `exclusion limit - a fragment that closes before its terminator and fails inside is still recorded`() {
        // The residue QueryParser's "Exclusion of unparseable heads" states (computenet-6atl9):
        // `s(X X).` closes and is followed by '.', so it is equally a statement after a missing
        // terminator whose arguments are malformed; recovery resumes there and its head is
        // recorded.
        val parsed = QueryParser.parse("a(X) :- r(X) s(X X).", catalog("r" to 1))
            .shouldBeInstanceOf<ParseResult.Rejected>()
        withClue("stated limit: ${parsed.excludedHeads}") {
            parsed.excludedHeads shouldBe setOf("a", "s")
        }
    }

    @Test
    fun `exclusion limit - an unclosed statement after a missing terminator is skipped as debris, so its dependent is not excluded`() {
        // The other side of the ambiguity QueryParser's "A missing comma versus a missing
        // terminator" states (computenet-6atl9 second read): `q(X` never closes before the '.',
        // so the lookahead reads it as `fact`'s debris, exactly as it reads `s(X, .` in the
        // exclusion test above. `q` earns no rejection and is not recorded, so `z` draws an
        // UNKNOWN_PREDICATE for it instead of being excluded.
        val catalog = catalog("r" to 1)
        val source = "fact(1)\nq(X :- r(X).\nz(X) :- q(X)."

        val parsed = QueryParser.parse(source, catalog).shouldBeInstanceOf<ParseResult.Rejected>()
        withClue("stated limit: $parsed") {
            parsed.rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR)
            parsed.excludedHeads shouldBe setOf("fact")
        }
        val rejections = QueryCompiler.compile(source, catalog)
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections
        withClue("stated limit: $rejections") {
            rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR, RejectionCode.UNKNOWN_PREDICATE)
        }

        // Missing its own '.' as well, it takes the well-formed statement after it along.
        val swallowed = QueryParser.parse("fact(1)\nq(X :- r(X)\ngood(X) :- r(X).", catalog)
            .shouldBeInstanceOf<ParseResult.Rejected>()
        withClue("stated limit: $swallowed") {
            swallowed.rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR)
            swallowed.partial.rules shouldBe emptyList()
        }
    }

    @Test
    fun `exclusion limit - a dependent of a statement whose head is not lexically recoverable is UNKNOWN_PREDICATE`() {
        // The limit QueryCompiler's KDoc states (computenet-l3338): the syntax error lies before
        // the head identifier, so no head is recovered and `good` is rejected on its own account.
        val source = """
            1bad(X) :- r(X).
            good(X) :- bad(X).
        """.trimIndent()

        val rejections = QueryCompiler.compile(source, catalog("r" to 1))
            .shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections.map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR, RejectionCode.UNKNOWN_PREDICATE)
    }
}
