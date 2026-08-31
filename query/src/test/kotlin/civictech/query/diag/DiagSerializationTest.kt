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
 * [RejectionCode] is landed empty (cab.1-D1), so [Rejection] cannot be instantiated by any
 * code today — a round-trip of a *full* `Rejection` is not constructible yet. What IS
 * constructible, and round-tripped here: [CompileResult.Rejected] with no rejections, and
 * each [Locus] variant standalone. `DiagShapeTest` covers the rest of the guarantee — that
 * every diag type (including [Rejection] and [RejectionCode], neither instantiable here)
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
}
