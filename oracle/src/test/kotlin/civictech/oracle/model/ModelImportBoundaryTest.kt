package civictech.oracle.model

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * `[ORA1-MODEL-10]`, BS-18 (epic computenet-4ru §2.1-2.3, D2): `civictech.oracle.model` may
 * reference value, key and delta types but no `civictech.cell.data.op.*` type and no concrete
 * data-cell class. A source-text import scan, not ArchUnit, per the feature's design field —
 * this is the permanent proof that the rule stated in `ElementShape.kt`'s and `ReferenceOp.kt`'s
 * KDoc is actually enforced rather than merely documented.
 *
 * Forbidden, enumerated against this task's base (`kernel/src/main/kotlin/civictech/cell/data`
 * at `9a94a80a`):
 *  - the whole `civictech.cell.data.op` package (`FilterCell`, `UnionSetCell`, ... every
 *    operator, present or future — matched by prefix so a new operator file needs no update
 *    here);
 *  - the concrete data-cell classes directly under `civictech.cell.data`: `SetCell`, `MapCell`,
 *    `ListCell`, `CounterCell`, `KeyedSetCell`, `OrMapCell`, `PnCounterCell`;
 *  - the hub cells in `civictech.cell.data.view.HubCells`: `SetHubCell`, `MapHubCell`.
 *
 * Allowed: `civictech.cell.data.delta.*` (`SetDelta`, `MapDelta`, ...), value/key types, stdlib
 * — none of which the predicate below touches.
 */
object ModelImportBoundaryScanner {

    data class Violation(val file: String, val import: String)

    private val forbiddenPrefixes = listOf("civictech.cell.data.op.")

    private val forbiddenExact = setOf(
        "civictech.cell.data.SetCell",
        "civictech.cell.data.MapCell",
        "civictech.cell.data.ListCell",
        "civictech.cell.data.CounterCell",
        "civictech.cell.data.KeyedSetCell",
        "civictech.cell.data.OrMapCell",
        "civictech.cell.data.PnCounterCell",
        "civictech.cell.data.view.SetHubCell",
        "civictech.cell.data.view.MapHubCell",
    )

    fun isForbidden(importFqn: String): Boolean =
        forbiddenPrefixes.any { importFqn.startsWith(it) } || importFqn in forbiddenExact

    /**
     * Pure over source text (no filesystem access): whether an import line is forbidden
     * depends only on its own text, which is what makes this independently testable against a
     * synthetic string and against a real file's content.
     */
    fun scanText(fileLabel: String, text: String): List<Violation> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim().substringBefore(" as ").trim() }
            .filter(::isForbidden)
            .map { Violation(fileLabel, it) }
            .toList()

    /**
     * Scans every `.kt` file under [dir]. Fails loudly — never an empty, and therefore
     * vacuously "clean", result — when [dir] is missing or contains no `.kt` file. Without
     * this, a moved or deleted `civictech.oracle.model` source set would make the positive
     * test below pass for the wrong reason (zero files scanned, not zero violations found).
     */
    fun scanDirectory(dir: File): List<Violation> {
        check(dir.isDirectory) {
            "Model source directory does not exist: ${dir.absolutePath} " +
                "— cannot verify [ORA1-MODEL-10] against an absent source set."
        }
        val ktFiles = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(ktFiles.isNotEmpty()) {
            "Model source directory has no .kt files: ${dir.absolutePath} " +
                "— cannot verify [ORA1-MODEL-10] against zero files."
        }
        return ktFiles.flatMap { scanText(it.path, it.readText()) }
    }
}

class ModelImportBoundaryTest {

    /**
     * The permanent proof the scanner CAN fail (Ex/BS-18): a synthetic model source importing
     * `FilterCell` is flagged, naming both the offending file and the offending import.
     */
    @Test
    fun `BS-18 a synthetic model source importing civictech-cell-data-op-FilterCell is flagged by file and import`() {
        val tempDir = Files.createTempDirectory("model-import-boundary-negative").toFile()
        try {
            val offender = File(tempDir, "Offender.kt")
            offender.writeText(
                """
                package civictech.oracle.model

                import civictech.cell.data.op.FilterCell

                class Offender {
                    val factory: FilterCell<*>? = null
                }
                """.trimIndent(),
            )

            val violations = ModelImportBoundaryScanner.scanDirectory(tempDir)

            violations shouldBe listOf(
                ModelImportBoundaryScanner.Violation(offender.path, "civictech.cell.data.op.FilterCell"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `an absent model source directory fails loudly rather than passing vacuously`() {
        val parent = Files.createTempDirectory("model-import-boundary-missing").toFile()
        try {
            val missing = File(parent, "does-not-exist")

            val failure = assertThrows<IllegalStateException> {
                ModelImportBoundaryScanner.scanDirectory(missing)
            }
            failure.message!! shouldContain "does not exist"
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `an empty model source directory fails loudly rather than passing vacuously`() {
        val emptyDir = Files.createTempDirectory("model-import-boundary-empty").toFile()
        try {
            val failure = assertThrows<IllegalStateException> {
                ModelImportBoundaryScanner.scanDirectory(emptyDir)
            }
            failure.message!! shouldContain "no .kt files"
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    /**
     * The positive gate: the real `civictech.oracle.model` source set has zero forbidden
     * imports today. A Gradle Test task's working directory is the project directory (relied
     * on the same way by the expected-failure ledger path comment in kernel/build.gradle.kts),
     * so this resolves to `oracle/src/main/kotlin/civictech/oracle/model`.
     */
    @Test
    fun `BS-18 the real civictech-oracle-model source set has zero forbidden imports`() {
        val modelSourceDir = File("src/main/kotlin/civictech/oracle/model")

        val violations = ModelImportBoundaryScanner.scanDirectory(modelSourceDir)

        withClue("civictech.oracle.model imports forbidden types $violations [ORA1-MODEL-10]") {
            violations.shouldBeEmpty()
        }
    }
}
