package civictech.concord.oracle

import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Hand-computed fixtures for every function-catalog entry (function-catalog.md). */
class FunctionsTest {

    private fun i(n: Long) = Value.IntVal(n)
    private fun s(v: String) = Value.StrVal(v)

    @Test
    fun `predicates evaluate per catalog`() {
        Functions.predicate("eq(apple)")(s("apple")) shouldBe true
        Functions.predicate("eq(apple)")(s("pear")) shouldBe false
        Functions.predicate("eq(3)")(i(3)) shouldBe true
        Functions.predicate("gt(3)")(i(4)) shouldBe true
        Functions.predicate("gt(3)")(i(3)) shouldBe false
        Functions.predicate("lt(3)")(i(2)) shouldBe true
        Functions.predicate("mod-eq(2,0)")(i(4)) shouldBe true
        Functions.predicate("mod-eq(2,0)")(i(5)) shouldBe false
        Functions.predicate("mod-eq(3,1)")(i(7)) shouldBe true
        Functions.predicate("even")(i(100)) shouldBe true
        Functions.predicate("even")(i(3)) shouldBe false
        Functions.predicate("odd")(i(3)) shouldBe true
        Functions.predicate("odd")(i(2)) shouldBe false
    }

    @Test
    fun `predicates are false on non-numeric where numeric is required`() {
        Functions.predicate("gt(3)")(s("apple")) shouldBe false
        Functions.predicate("even")(s("apple")) shouldBe false
    }

    @Test
    fun `transforms evaluate per catalog`() {
        Functions.transform("identity")(i(7)) shouldBe i(7)
        Functions.transform("concat(-x)")(s("a")) shouldBe s("a-x")
        Functions.transform("add(5)")(i(3)) shouldBe i(8)
        // key-of extracts the first component of a pair, identity otherwise.
        Functions.transform("key-of")(Value.ListVal(listOf(s("k"), i(1)))) shouldBe s("k")
        Functions.transform("key-of")(s("scalar")) shouldBe s("scalar")
    }

    @Test
    fun `aggregators fold a multiset to one value`() {
        Functions.aggregate("sum", listOf(i(50), i(50))) shouldBe i(100)
        Functions.aggregate("sum", listOf(i(1), i(2), i(3))) shouldBe i(6)
        Functions.aggregate("min", listOf(i(3), i(1), i(2))) shouldBe i(1)
        Functions.aggregate("max", listOf(i(3), i(1), i(2))) shouldBe i(3)
        Functions.aggregate("count", listOf(i(3), i(1), i(2))) shouldBe i(3)
        Functions.aggregate("min", listOf(s("pear"), s("apple"), s("plum"))) shouldBe s("apple")
    }

    @Test
    fun `sum widens to real when any input is real`() {
        Functions.aggregate("sum", listOf(i(1), Value.RealVal(0.5))) shouldBe Value.RealVal(1.5)
    }
}
