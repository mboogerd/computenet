package civictech.query.ast

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * `[QRY1-LANG-04]`: the AST carries union, intersection and difference as first-class
 * constructs — each with the cab.2-D3 `all` flag — and left/right/full outer joins, all
 * composable as one expression tree.
 */
class RelationalExprTest {

    private val x = Term.Var("X")
    private val y = Term.Var("Y")
    private val a = RelationalExpr.Relation(Atom("a", listOf(x, y)))
    private val b = RelationalExpr.Relation(Atom("b", listOf(x, y)))

    @Test
    fun `the three set operations exist as first-class constructs`() {
        SetOpKind.entries.toSet() shouldBe setOf(SetOpKind.UNION, SetOpKind.INTERSECTION, SetOpKind.DIFFERENCE)
    }

    @Test
    fun `the three outer join sides exist as first-class constructs`() {
        OuterJoinSide.entries.toSet() shouldBe
            setOf(OuterJoinSide.LEFT, OuterJoinSide.RIGHT, OuterJoinSide.FULL)
    }

    @Test
    fun `a set operation defaults to distinct semantics and stores the ALL flag when asked`() {
        RelationalExpr.SetOp(SetOpKind.UNION, a, b).all shouldBe false
        RelationalExpr.SetOp(SetOpKind.UNION, a, b, all = true).all shouldBe true
    }

    @Test
    fun `the ALL flag is part of structural identity, so it cannot be silently dropped`() {
        // cab.2-D3: the flag is STORED, never judged here — which is only true if two
        // otherwise-identical nodes differing in `all` are different AST values. If `all`
        // were not a property, the rejection feature could never see UNION ALL to refuse it.
        SetOpKind.entries.forEach { kind ->
            val distinct = RelationalExpr.SetOp(kind, a, b, all = false)
            val bag = RelationalExpr.SetOp(kind, a, b, all = true)
            (distinct == bag) shouldBe false
        }
    }

    @Test
    fun `an ALL set operation is constructible - the AST passes no judgment on it`() {
        // The SEM-04 refusal belongs to the rejection feature; building it here must not throw.
        val bagUnion = RelationalExpr.SetOp(SetOpKind.UNION, a, b, all = true)
        bagUnion.kind shouldBe SetOpKind.UNION
    }

    @Test
    fun `set operations nest, so a three-way union is one expression`() {
        val c = RelationalExpr.Relation(Atom("c", listOf(x, y)))
        val nested = RelationalExpr.SetOp(
            SetOpKind.UNION,
            RelationalExpr.SetOp(SetOpKind.UNION, a, b),
            c,
        )
        (nested.left as RelationalExpr.SetOp).right shouldBe b
        nested.right shouldBe c
    }

    @Test
    fun `an outer join stores its side and key equalities`() {
        val join = RelationalExpr.OuterJoin(OuterJoinSide.FULL, a, b, listOf(JoinKey(x, y)))
        join.side shouldBe OuterJoinSide.FULL
        join.on shouldBe listOf(JoinKey(x, y))
    }

    @Test
    fun `an outer join with no key equality is rejected by the constructor`() {
        val error = shouldThrow<IllegalArgumentException> {
            RelationalExpr.OuterJoin(OuterJoinSide.LEFT, a, b, emptyList())
        }
        error.message!! shouldContain "requires at least one JoinKey"
    }

    @Test
    fun `a definition names a relational expression's result`() {
        val head = Atom("either", listOf(x, y))
        val definition = Definition(head, RelationalExpr.SetOp(SetOpKind.UNION, a, b))
        definition.head shouldBe head
        definition.expr shouldBe RelationalExpr.SetOp(SetOpKind.UNION, a, b)
    }

    @Test
    fun `a Query carries definitions alongside rules, defaulting to none`() {
        val catalog = civictech.query.schema.Catalog(emptyMap())
        Query(rules = emptyList(), catalog = catalog).definitions shouldBe emptyList()

        val definition = Definition(Atom("either", listOf(x, y)), RelationalExpr.SetOp(SetOpKind.UNION, a, b))
        Query(
            rules = emptyList(),
            catalog = catalog,
            definitions = listOf(definition),
        ).definitions shouldBe listOf(definition)
    }
}
