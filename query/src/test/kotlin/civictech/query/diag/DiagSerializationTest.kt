package civictech.query.diag

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * [QRY1-LANG-06]-style round-trip guarantee for the diag half of the module.
 *
 * [RejectionCode] landed empty under cab.1-D1 and gained its first variant ([SYNTAX_ERROR][RejectionCode.SYNTAX_ERROR])
 * from the parser feature (computenet-cab.2.2); computenet-cab.2.3 adds three more
 * ([UNSAFE_RULE][RejectionCode.UNSAFE_RULE], [EDB_REDEFINED][RejectionCode.EDB_REDEFINED],
 * [RECURSION_UNSUPPORTED][RejectionCode.RECURSION_UNSUPPORTED]), so a full [Rejection] is now
 * constructible and round-tripped here alongside [CompileResult.Rejected] carrying one, and
 * every [Locus] variant standalone (including [Locus.RuleStatement], this task's additive
 * variant). `DiagShapeTest` covers the rest of the guarantee — that every diag type
 * implements [Serializable] and declares no function-typed field.
 */
class DiagSerializationTest {

    private fun <T : Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().also { bos ->
            ObjectOutputStream(bos).use { it.writeObject(value) }
        }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as T }
    }

    @Test
    fun `CompileResult Rejected with no rejections round-trips to an equal value`() {
        val original: CompileResult = CompileResult.Rejected(emptyList())
        roundTrip(original) shouldBe original
    }

    @Test
    fun `Locus SourceSpan round-trips to an equal value`() {
        val original: Locus = Locus.SourceSpan(startLine = 3, startColumn = 5, endLine = 3, endColumn = 12)
        roundTrip(original) shouldBe original
    }

    @Test
    fun `Locus PlanNode round-trips to an equal value`() {
        val original: Locus = Locus.PlanNode(id = "join/0")
        roundTrip(original) shouldBe original
    }

    @Test
    fun `Locus RuleStatement round-trips to an equal value`() {
        val original: Locus = Locus.RuleStatement(ruleIndex = 1, headPredicate = "path")
        roundTrip(original) shouldBe original
    }

    @Test
    fun `Rejection round-trips to an equal value`() {
        val original = Rejection(
            code = RejectionCode.UNSAFE_RULE,
            locus = Locus.RuleStatement(ruleIndex = 0, headPredicate = "q"),
            specId = "[QRY1-LANG-07]: rule head 'q' is unsafe — variable(s) not bound by any positive body atom: Z",
        )
        roundTrip(original) shouldBe original
    }

    @Test
    fun `CompileResult Rejected with rejections round-trips to an equal value`() {
        val original: CompileResult = CompileResult.Rejected(
            listOf(
                Rejection(
                    code = RejectionCode.EDB_REDEFINED,
                    locus = Locus.RuleStatement(ruleIndex = 0, headPredicate = "r"),
                    specId = "[QRY1-LANG-08]: rule head redefines EDB relation 'r' declared in the Catalog",
                ),
            ),
        )
        roundTrip(original) shouldBe original
    }
}
