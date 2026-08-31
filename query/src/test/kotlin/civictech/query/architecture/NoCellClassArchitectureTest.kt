package civictech.query.architecture

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `[QRY1-LOWER-03]`, BS-17 (second half): `:query`'s main source set authors no cell — no
 * `@Contract`, no `@CellBase`, and no class/object implementing `civictech.cell.Cell`.
 * `:query` compiles a Datalog/relational query down to a kernel `civictech.cell.graph.GraphSpec`
 * (epic computenet-cab §2.1, §4.7); it is not itself a cell author, which is also why
 * `query/build.gradle.kts` applies no `ksp-cell` convention plugin.
 *
 * Plain-JUnit source scan over file TEXT (cab.1-D2, no ArchUnit) — following
 * `civictech.oracle.ModuleDependencyTest`'s technique of a regex-fingerprinted forbidden set
 * checked against source text rather than loaded classes: a violating class may not even
 * compile as valid `:query` code once other gates land, so a classpath check alone could
 * never be the whole proof, and a text scan catches the violation regardless of whether the
 * rest of the module happens to compile.
 *
 * The classifier ([classify]) is a pure function of source text, proven both ways in this
 * file: synthetic strings for each violation kind (negative controls), and the real
 * `query/src/main/kotlin` tree (positive control — scans clean).
 */
class NoCellClassArchitectureTest {

    companion object {
        /**
         * Flags a single `.kt` source string, naming which violation kind(s) it carries (or
         * empty if clean).
         */
        fun classify(source: String): List<String> {
            val violations = mutableListOf<String>()
            if (Regex("""@Contract\b""").containsMatchIn(source)) {
                violations += "@Contract annotation"
            }
            if (Regex("""@CellBase\b""").containsMatchIn(source)) {
                violations += "@CellBase annotation"
            }
            // A class/object declaring `civictech.cell.Cell` (imported or fully qualified) as
            // a supertype. `\bCell\b` word-boundaries so a name like `CellHandler` or
            // `FooCell` — neither of which is the forbidden type — is not matched.
            if (Regex("""\b(class|object)\s+\w+[^\n{]*:\s*(civictech\.cell\.)?Cell\b""").containsMatchIn(source)) {
                violations += "civictech.cell.Cell implementation"
            }
            return violations
        }
    }

    @Test
    fun `QRY1 §LOWER-03 synthetic source with @Contract is flagged`() {
        val src = """
            package civictech.query.fixture

            import civictech.gen.wire.Contract

            @Contract
            interface FixtureApi
        """.trimIndent()
        classify(src) shouldContain "@Contract annotation"
    }

    @Test
    fun `QRY1 §LOWER-03 synthetic source with @CellBase is flagged`() {
        val src = """
            package civictech.query.fixture

            import civictech.gen.wire.CellBase

            @CellBase
            interface FixtureApi
        """.trimIndent()
        classify(src) shouldContain "@CellBase annotation"
    }

    @Test
    fun `QRY1 §LOWER-03 synthetic source implementing Cell is flagged`() {
        val src = """
            package civictech.query.fixture

            import civictech.cell.Cell
            import civictech.cell.CellRef

            class FixtureCell(override val ref: CellRef) : Cell
        """.trimIndent()
        classify(src) shouldContain "civictech.cell.Cell implementation"
    }

    @Test
    fun `QRY1 §LOWER-03 synthetic source implementing Cell by fully-qualified name is flagged`() {
        val src = """
            package civictech.query.fixture

            class FixtureCell(override val ref: civictech.cell.CellRef) : civictech.cell.Cell
        """.trimIndent()
        classify(src) shouldContain "civictech.cell.Cell implementation"
    }

    @Test
    fun `QRY1 §LOWER-03 synthetic clean source is not flagged`() {
        val src = """
            package civictech.query.fixture

            data class FixtureRow(val a: Int, val b: String)
        """.trimIndent()
        classify(src).shouldBeEmpty()
    }

    @Test
    fun `QRY1 §LOWER-03 the real query main source tree scans clean`() {
        val root = File("src/main/kotlin")
        withClue("expected query/src/main/kotlin to exist relative to the :query project directory") {
            root.isDirectory shouldBe true
        }

        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        withClue("non-vacuity: :query's main source set should contain at least one .kt file by this task") {
            ktFiles.isEmpty() shouldBe false
        }

        val offenders = ktFiles.mapNotNull { file ->
            val violations = classify(file.readText())
            if (violations.isEmpty()) null else "${file.path}: $violations"
        }
        withClue("query/src/main/kotlin files carrying a forbidden cell-authoring construct — [QRY1-LOWER-03]") {
            offenders.shouldBeEmpty()
        }
    }
}
