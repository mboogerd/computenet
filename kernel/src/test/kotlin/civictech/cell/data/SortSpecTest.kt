package civictech.cell.data

import civictech.cell.data.SortSpec.SortKey.Companion.asc
import civictech.cell.data.SortSpec.SortKey.Companion.desc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Row fixture shared by the `topKBy` tests (AggregatorTest, GroupByCellTest). */
data class Emp(val dept: String, val salary: Long, val name: String, val id: Int) : Serializable

/** `ORDER BY salary DESC, name ASC` with `id` as the tie-break (KAGG-R-11). */
fun salaryDescNameAsc(): SortSpec<Emp> = SortSpec.by(desc(Emp::salary), asc(Emp::name), tieBreak = Emp::id)

@Suppress("UNCHECKED_CAST")
fun <T : Serializable> serialRoundTrip(value: T): T {
    val bytes = ByteArrayOutputStream()
    ObjectOutputStream(bytes).use { it.writeObject(value) }
    return ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() as T }
}

/**
 * [SortSpec] construction and ordering (KAGG-R-11, the construction half of
 * KAGG-R-12). The insert-time half of KAGG-R-12 is in [AggregatorTest].
 */
class SortSpecTest {

    private val rows = listOf(
        Emp("d", 100, "bob", 3),
        Emp("d", 120, "ann", 1),
        Emp("d", 100, "amy", 5),
        Emp("d", 100, "amy", 2), // identical to id=5 in every sort column
        Emp("d", 90, "cid", 4),
    )

    @Test
    fun `orders by columns in sequence with per-column direction, then ascending tie-break`() {
        val sorted = rows.sortedWith(salaryDescNameAsc()).map { it.id }
        // 120 first (DESC); among the 100s: amy(2), amy(5) by id, then bob; 90 last
        assertEquals(listOf(1, 2, 5, 3, 4), sorted)
    }

    @Test
    fun `distinct rows never compare equal under a declared unique tie-break`() {
        val spec = salaryDescNameAsc()
        for (a in rows) for (b in rows) {
            assertEquals(a == b, spec.compare(a, b) == 0, "$a vs $b")
        }
    }

    @Test
    fun `list path without a tie-break throws naming totality`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SortSpec(listOf(desc(Emp::salary)), tieBreak = null)
        }
        assertTrue(ex.message.orEmpty().contains("total"), "got: ${ex.message}")
    }

    @Test
    fun `no columns throws naming totality`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SortSpec(emptyList(), tieBreak = asc(Emp::id))
        }
        assertTrue(ex.message.orEmpty().contains("total"), "got: ${ex.message}")
    }

    @Test
    fun `a descending tie-break is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SortSpec(listOf(desc(Emp::salary)), tieBreak = desc(Emp::id))
        }
    }

    @Test
    fun `a non-serializable selector is refused at construction, not at first snapshot`() {
        // Kotlin 2.x compiles plain lambdas via invokedynamic: not Serializable
        val ex = assertThrows(IllegalArgumentException::class.java) {
            asc<Emp, Long> { it.salary }
        }
        assertTrue(ex.message.orEmpty().contains("Serializable"), "got: ${ex.message}")
        // the annotated form is accepted
        asc<Emp, Long>(@JvmSerializableLambda { it.salary })
    }

    @Test
    fun `survives java serialization with its ordering intact`() {
        val restored = serialRoundTrip(salaryDescNameAsc())
        assertEquals(rows.sortedWith(salaryDescNameAsc()), rows.sortedWith(restored))
    }
}
