package civictech.wire.vector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `[WIR1-I18]`, epic B1.7: no vector's `encoded.utf8` carries a reflection
 * artifact — no `civictech.`, `java.` or `kotlin.` fragment.
 *
 * Per decision ncz.2-D7, the five quoted tokens `"kotlin.String"`,
 * `"kotlin.Long"`, `"kotlin.Int"`, `"kotlin.Boolean"`, `"kotlin.Double"` are the
 * builtin descriptor serial names the codec writes for its primitive
 * registrations (SCHEMA.md §Polymorphic positions call-out), permitted as pinned
 * discriminators under `[WIR1-I18]`; anything else `kotlin.`-prefixed
 * (`kotlin.collections.`, `kotlin.Unit`, an unquoted fragment) still fails.
 *
 * At this base no primitive vector exists, so the allowlist strips nothing from
 * the corpus; the `allowlist and forbidden fragments` test pins its shape on
 * plain strings, and the Double vector task computenet-ncz.2.5 authors is what
 * makes it load-bearing on corpus data.
 */
class NoReflectionArtifactsTest {

    @Test
    fun `no encoded utf8 in the corpus carries a civictech, java or kotlin fragment`() {
        val scanned = VectorLoader.locate().documents().mapNotNull { doc -> doc.encoded?.utf8?.let { doc.id to it } }
        assertTrue(scanned.isNotEmpty(), "vacuity guard: no manifest vector carries encoded.utf8, so nothing was scanned")
        val failures = scanned.flatMap { (id, utf8) -> findings(utf8).map { "$id: $it" } }
        assertTrue(failures.isEmpty(), "reflection artifacts in encoded.utf8 ([WIR1-I18], ncz.2-D7):\n" + failures.joinToString("\n"))
    }

    @Test
    fun `allowlist and forbidden fragments`() {
        // Plain strings, not frames: they pin what the scanner strips and what it keeps.
        assertEquals(emptyList<String>(), findings("""[["kotlin.Double",0.1],["kotlin.String","x"],["kotlin.Long",1],["kotlin.Int",2],["kotlin.Boolean",true]]"""))
        for (bad in listOf(
            """["kotlin.Unit",{}]""",
            """["kotlin.collections.LinkedHashMap",[]]""",
            """{"k":"a kotlin.Double inside text"}""",
            """["java.lang.Double",1]""",
            """["civictech.cell.control.Stall",{}]""",
            """["kotlin.DoubleArray",[]]""",
        )) {
            assertTrue(findings(bad).isNotEmpty(), "scanner must flag $bad")
        }
    }

    companion object {
        /** ncz.2-D7: exactly these five, quotes included (discriminator position only). */
        val ALLOWED_PRIMITIVE_DISCRIMINATORS: List<String> =
            listOf("kotlin.String", "kotlin.Long", "kotlin.Int", "kotlin.Boolean", "kotlin.Double").map { "\"$it\"" }

        val FORBIDDEN: List<String> = listOf("civictech.", "java.", "kotlin.")

        /** Each forbidden fragment left after stripping the allowlist, with ~20 characters of context either side. */
        fun findings(utf8: String): List<String> {
            val remainder = ALLOWED_PRIMITIVE_DISCRIMINATORS.fold(utf8) { acc, token -> acc.replace(token, "") }
            return FORBIDDEN.flatMap { fragment ->
                Regex(Regex.escape(fragment)).findAll(remainder).map { m ->
                    val from = (m.range.first - 20).coerceAtLeast(0)
                    val to = (m.range.last + 21).coerceAtMost(remainder.length)
                    "`$fragment` in …${remainder.substring(from, to)}… (after stripping the ncz.2-D7 allowlist)"
                }.toList()
            }
        }
    }
}
