package civictech.query.architecture

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `[QRY1-ORA-03]`, BS-17 (first half): `civictech.query.ref` — the compiler's raw-source
 * interface to the operator algebra it exists to invoke, not to reimplement — may not import
 * any `civictech.cell.data` or `civictech.cell.data.op` type. That independence from the
 * kernel operator implementation is what makes `civictech.query.ref` a caller of the algebra
 * rather than a second copy of it (the same principle `:oracle`'s
 * `civictech.oracle.model.ReferenceOp` documents for its own independence, per
 * `oracle/src/main/kotlin/civictech/oracle/model/ReferenceOp.kt`).
 *
 * The gate is proven ARMED by the synthetic-source negative controls, following the feature's
 * own example: a scratch file importing `civictech.cell.data.op.FilterCell` must be flagged.
 * Since computenet-cab.6.1 populated `civictech.query.ref` (`BatchEvaluator`, `RelationValue`)
 * the live-tree scan is no longer vacuous: it asserts the package directory exists and that it
 * scanned at least two `.kt` files, so a moved or renamed package fails the scan instead of
 * passing it with zero files read.
 *
 * Plain-JUnit source-text scan (cab.1-D2, no ArchUnit) — same technique as
 * `NoCellClassArchitectureTest` and `civictech.oracle.ModuleDependencyTest`.
 */
class RefImportBoundaryTest {

    companion object {
        /** Every forbidden `civictech.cell.data`/`civictech.cell.data.op` import in [source]. */
        fun classify(source: String): List<String> {
            val forbiddenImport =
                Regex("""^\s*import\s+(civictech\.cell\.data(?:\.op)?\.[A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
            return forbiddenImport.findAll(source).map { it.groupValues[1] }.toList()
        }
    }

    @Test
    fun `QRY1 §ORA-03 synthetic ref file importing civictech-cell-data-op is flagged`() {
        val src = """
            package civictech.query.ref

            import civictech.cell.data.op.FilterCell

            class Scratch(val cell: FilterCell)
        """.trimIndent()
        classify(src) shouldContainExactly listOf("civictech.cell.data.op.FilterCell")
    }

    @Test
    fun `QRY1 §ORA-03 synthetic ref file importing civictech-cell-data directly is also flagged`() {
        val src = """
            package civictech.query.ref

            import civictech.cell.data.SetDelta

            class Scratch(val delta: SetDelta)
        """.trimIndent()
        classify(src) shouldContainExactly listOf("civictech.cell.data.SetDelta")
    }

    @Test
    fun `QRY1 §ORA-03 synthetic clean ref file is not flagged`() {
        val src = """
            package civictech.query.ref

            import civictech.cell.graph.GraphSpec

            class Scratch(val spec: GraphSpec)
        """.trimIndent()
        classify(src).shouldBeEmpty()
    }

    @Test
    fun `QRY1 §ORA-03 the civictech-query-ref package exists and scans clean`() {
        val root = File("src/main/kotlin/civictech/query/ref")
        withClue("civictech.query.ref must exist at ${root.absolutePath} — a missing package is a vacuous scan, not a pass") {
            root.isDirectory shouldBe true
        }

        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        withClue("civictech.query.ref scanned ${ktFiles.map { it.name }} — a scan of fewer than two files is vacuous") {
            ktFiles.size shouldBeGreaterThanOrEqual 2
        }
        val offenders = ktFiles.flatMap { file ->
            classify(file.readText()).map { import -> "${file.path}: $import" }
        }
        withClue("civictech.query.ref files importing a forbidden civictech.cell.data type — [QRY1-ORA-03]") {
            offenders.shouldBeEmpty()
        }
    }
}
