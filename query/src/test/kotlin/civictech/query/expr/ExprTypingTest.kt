package civictech.query.expr

import civictech.query.ast.ComparisonOp
import civictech.query.schema.AttrType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * cab.4-D1's typing examples: `ExprTyping.typeOf` is total over well-typed input and reports a
 * type mismatch or unknown attribute as a [TypingResult] value, never by throwing.
 */
class ExprTypingTest {

    @Test
    fun `Cmp of an INT column against a LONG constant is a type mismatch`() {
        val expr = Expr.Cmp(ComparisonOp.LT, Expr.Attr("a"), Expr.Const(1L, AttrType.LONG))
        ExprTyping.typeOf(expr, mapOf("a" to AttrType.INT)) shouldBe
            TypingResult.TypeMismatch(AttrType.INT, AttrType.LONG)
    }

    @Test
    fun `Cmp of a LONG column against a LONG constant types to BOOL`() {
        val expr = Expr.Cmp(ComparisonOp.LT, Expr.Attr("a"), Expr.Const(1L, AttrType.LONG))
        ExprTyping.typeOf(expr, mapOf("a" to AttrType.LONG)) shouldBe TypingResult.Typed(AttrType.BOOL)
    }

    @Test
    fun `And of a Cmp and a BOOL column types to BOOL`() {
        val expr = Expr.And(
            Expr.Cmp(ComparisonOp.LT, Expr.Attr("a"), Expr.Const(1L, AttrType.LONG)),
            Expr.Attr("flag"),
        )
        ExprTyping.typeOf(expr, mapOf("a" to AttrType.LONG, "flag" to AttrType.BOOL)) shouldBe
            TypingResult.Typed(AttrType.BOOL)
    }

    @Test
    fun `Not of a non-BOOL column is a type mismatch`() {
        val expr = Expr.Not(Expr.Attr("n"))
        ExprTyping.typeOf(expr, mapOf("n" to AttrType.INT)) shouldBe
            TypingResult.TypeMismatch(AttrType.BOOL, AttrType.INT)
    }

    @Test
    fun `an unknown attribute name is reported as a value, not thrown`() {
        val expr = Expr.Attr("missing")
        ExprTyping.typeOf(expr, mapOf("a" to AttrType.INT)) shouldBe TypingResult.UnknownAttribute("missing")
    }

    @Test
    fun `Or requires both operands to type to BOOL`() {
        val expr = Expr.Or(Expr.Attr("flag"), Expr.Const(1, AttrType.INT))
        ExprTyping.typeOf(expr, mapOf("flag" to AttrType.BOOL)) shouldBe
            TypingResult.TypeMismatch(AttrType.BOOL, AttrType.INT)
    }
}
