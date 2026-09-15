package civictech.query.expr

import civictech.query.ast.ComparisonOp
import civictech.query.schema.AttrType
import civictech.query.schema.Row
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * cab.4-D1's structural-equality/round-trip guarantee for every interpreter, plus the
 * evaluation examples the task prescribes. Two interpreters built from equal constructor
 * arguments must be `==`/hash-equal (the `CellFactory` equality precondition,
 * [QRY1-LOWER-05]/[QRY1-LOWER-06]), and a Java-serialization round-trip must yield an equal
 * value — including for the eagerly-cached, non-constructor index maps each interpreter
 * carries (`Interpreters.kt`'s class doc): a round-trip that broke on those would show up here
 * as a non-equal result, not as a `NotSerializableException`, since a `Map<String, Int>` field
 * serializes with no special handling.
 */
class ExprInterpreterTest {

    private fun <T : Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().apply {
            ObjectOutputStream(this).use { it.writeObject(value) }
        }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as T
    }

    // ---- ExprPredicate ----

    @Test
    fun `ExprPredicate structural equality and serialization round-trip`() {
        val a = ExprPredicate(listOf("x", "y"), Expr.Cmp(ComparisonOp.GT, Expr.Attr("y"), Expr.Const(3, AttrType.INT)))
        val b = ExprPredicate(listOf("x", "y"), Expr.Cmp(ComparisonOp.GT, Expr.Attr("y"), Expr.Const(3, AttrType.INT)))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
    }

    @Test
    fun `ExprPredicate evaluates GT comparison against a column and a constant`() {
        val predicate = ExprPredicate(
            listOf("x", "y"),
            Expr.Cmp(ComparisonOp.GT, Expr.Attr("y"), Expr.Const(3, AttrType.INT)),
        )
        predicate(Row(listOf(1, 5))) shouldBe true
        predicate(Row(listOf(1, 3))) shouldBe false
    }

    // ---- RowProjection ----

    @Test
    fun `RowProjection structural equality and serialization round-trip`() {
        val a = RowProjection(listOf("x", "y", "z"), listOf("z", "x"))
        val b = RowProjection(listOf("x", "y", "z"), listOf("z", "x"))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
    }

    @Test
    fun `RowProjection permutes and narrows columns by name`() {
        val projection = RowProjection(listOf("x", "y", "z"), listOf("z", "x"))
        projection(Row(listOf(1, 2, 3))) shouldBe listOf(Row(listOf(3, 1)))
    }

    // ---- RowKey ----

    @Test
    fun `RowKey structural equality and serialization round-trip`() {
        val a = RowKey(listOf("x", "y"), listOf("y"))
        val b = RowKey(listOf("x", "y"), listOf("y"))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
    }

    @Test
    fun `RowKey extracts the named key columns in key order`() {
        val key = RowKey(listOf("x", "y"), listOf("y"))
        key(Row(listOf(1, 2))) shouldBe Row(listOf(2))
    }

    @Test
    fun `RowKey with empty keyColumns yields the unit key`() {
        val key = RowKey(listOf("x", "y"), emptyList())
        key(Row(listOf(1, 2))) shouldBe Row(emptyList())
    }

    // ---- RowCombine ----

    @Test
    fun `RowCombine structural equality and serialization round-trip`() {
        val a = RowCombine(listOf("x", "y"), listOf("y", "z"), listOf("x", "y", "z"))
        val b = RowCombine(listOf("x", "y"), listOf("y", "z"), listOf("x", "y", "z"))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
    }

    @Test
    fun `RowCombine builds the output row, reading a shared column from the left`() {
        val combine = RowCombine(listOf("x", "y"), listOf("y", "z"), listOf("x", "y", "z"))
        combine(Row(listOf(1, 2)), Row(listOf(2, 3))) shouldBe Row(listOf(1, 2, 3))
    }

    @Test
    fun `RowCombine reads a null left value, not the right row, for a column only the left carries`() {
        // computenet-67vnx: a left row whose value for a left-only column is null must not fall
        // through to the right row. leftColumns=[K,A,B], rightColumns=[K,Z], outputColumns=[K,A,B,Z]
        // mirrors the bug's q(K,B,Z) :- j(K,A,B), c(K,Z). over a left-outer-join-padded j.
        val combine = RowCombine(listOf("K", "A", "B"), listOf("K", "Z"), listOf("K", "A", "B", "Z"))
        combine(Row(listOf(1, 10, null)), Row(listOf(1, 5))) shouldBe Row(listOf(1, 10, null, 5))
    }

    // ---- RowCombinePadded ----

    @Test
    fun `RowCombinePadded structural equality and serialization round-trip`() {
        val a = RowCombinePadded(listOf("x", "y"), listOf("z"), listOf("x", "y", "z"))
        val b = RowCombinePadded(listOf("x", "y"), listOf("z"), listOf("x", "y", "z"))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
    }

    @Test
    fun `RowCombinePadded pads a missing right side with null`() {
        val combine = RowCombinePadded(listOf("x", "y"), listOf("z"), listOf("x", "y", "z"))
        combine(Row(listOf(1, 2)), null) shouldBe Row(listOf(1, 2, null))
    }

    @Test
    fun `RowCombinePadded pads a missing left side with null`() {
        val combine = RowCombinePadded(listOf("x", "y"), listOf("z"), listOf("x", "y", "z"))
        combine(null, Row(listOf(3))) shouldBe Row(listOf(null, null, 3))
    }

    // ---- RowSelector ----

    @Test
    fun `RowSelector structural equality, serialization round-trip and evaluation`() {
        val a = RowSelector(listOf("x", "y"), "y")
        val b = RowSelector(listOf("x", "y"), "y")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
        a(Row(listOf(1, 2))) shouldBe 2
    }

    // ---- RowLongSelector ----

    @Test
    fun `RowLongSelector structural equality, serialization round-trip and evaluation`() {
        val a = RowLongSelector(listOf("x", "y"), "y")
        val b = RowLongSelector(listOf("x", "y"), "y")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        roundTrip(a) shouldBe a
        a(Row(listOf(1, 2))) shouldBe 2L
    }

    @Test
    fun `RowLongSelector widens an Int slot to Long`() {
        val selector = RowLongSelector(listOf("x"), "x")
        selector(Row(listOf(7))) shouldBe 7L
        selector(Row(listOf(7L))) shouldBe 7L
    }
}
