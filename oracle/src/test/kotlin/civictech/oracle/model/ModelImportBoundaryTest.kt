package civictech.oracle.model

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
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
        // ORA2 (`[ORA2-MODEL-12]`, computenet-4ru.1.1). The tagged/keyed model must agree with the
        // kernel about dot ORDER, and the one way to get that wrong invisibly is to reach for the
        // kernel's own dot identity: `Timestamp(sourceId, counter)`, whose `sourceId` the kernel
        // derives per instance. `DotModel` mints `ModelDot`s of its own and takes the instance
        // order from the harness as ranks; an import of `Timestamp` here would mean the model had
        // started reading the kernel's identity instead of stating its own, which is exactly the
        // independence `[ORA2-MODEL-06]`/`[ORA2-MODEL-12]` buy. Not a `civictech.cell.data` type,
        // so no prefix above would have caught it.
        "civictech.cell.Timestamp",
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

    /** Every `import ...` line's FQN in [text], in file order — the shared parsing [scanText] and [scanForReferenceOpDeclarations] both build on. */
    private fun importLines(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim().substringBefore(" as ").trim() }
            .toList()

    /**
     * Pure over source text (no filesystem access): whether an import line is forbidden
     * depends only on its own text, which is what makes this independently testable against a
     * synthetic string and against a real file's content.
     */
    fun scanText(fileLabel: String, text: String): List<Violation> =
        importLines(text).filter(::isForbidden).map { Violation(fileLabel, it) }

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

    // -----------------------------------------------------------------------------------------
    // computenet-n00e: `[ORA2-MODEL-11]`'s general form — a `ReferenceOp` implementation is
    // covered wherever it is declared, not only under `civictech.oracle.model`.
    //
    // Whole-FILE, same as [scanDirectory] above — deliberately not scoped to only the text
    // "inside" the matched declaration. An earlier version of this scan tried body-scoping (so a
    // file could mix a `ReferenceOp` with unrelated kernel-wiring code that legitimately imports
    // a forbidden type) by searching the declaration's body text for the forbidden import's
    // simple name as a token. That is unsound: a `require(...)` message or KDoc paragraph
    // EXPLAINING the restriction in English prose can and does contain the very class name it is
    // talking about — `SingleInstanceOrMapModel`'s own error message says "checks ONE OrMapCell
    // instance's own dot semantics", which the token search flagged as if it were a live
    // reference to `OrMapCell`. Import lines are unambiguous (this scanner's whole premise);
    // free-form text is not, so this scan stays at the same file-wide granularity [scanText]
    // already uses and instead keeps `ReferenceOp` declarations out of files that need a
    // forbidden import for something else — see [declaresReferenceOp]'s own KDoc.
    // -----------------------------------------------------------------------------------------

    private val declarationHeader = Regex(
        """\b(?:class|object)\s+\w+[^\n{]*:\s*[^\n{]*\b(?:ReferenceOp|SourceModel|OperatorModel)\b[^\n{]*\{""",
    )

    /**
     * Whether [text] declares a `class`/`object` implementing [ReferenceOp] (directly, or via
     * [SourceModel]/[OperatorModel], the only two evaluable sub-interfaces — see
     * `ReferenceOp.kt`). A single-line supertype-list match, like the rest of this scanner: pure
     * text, not a compiler-level check.
     */
    fun declaresReferenceOp(text: String): Boolean = declarationHeader.containsMatchIn(text)

    /**
     * `[ORA2-MODEL-11]`'s general form: every file **anywhere** under [root] that declares a
     * `ReferenceOp` implementation is scanned whole, the same way [scanDirectory] scans a whole
     * file under `civictech.oracle.model` — regardless of what package the file lives in. A file
     * with no `ReferenceOp` declaration is never scanned, so registration/wiring code that
     * legitimately imports a kernel type is untouched **as long as it does not share a file with
     * a `ReferenceOp` declaration**; a file that does both is flagged for the wiring import too,
     * same as `civictech.oracle.model` itself would be. That is deliberate, not a limitation:
     * see [declaresReferenceOp]'s KDoc for why a finer-grained, declaration-scoped version of
     * this check is unsound.
     *
     * Fails loudly on an absent/empty [root], for the same reason [scanDirectory] does.
     */
    fun scanForReferenceOpDeclarations(root: File): List<Violation> {
        check(root.isDirectory) {
            "Module source directory does not exist: ${root.absolutePath} " +
                "— cannot verify [ORA2-MODEL-11] against an absent source tree."
        }
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(ktFiles.isNotEmpty()) {
            "Module source directory has no .kt files: ${root.absolutePath} " +
                "— cannot verify [ORA2-MODEL-11] against zero files."
        }
        return ktFiles
            .map { it to it.readText() }
            .filter { (_, text) -> declaresReferenceOp(text) }
            .flatMap { (file, text) -> scanText(file.path, text) }
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

    /**
     * `[ORA2-MODEL-11]`: the boundary covers **ORA2's** sources too, and demonstrably so.
     *
     * The positive gate above scans a directory, so it would keep passing unchanged if ORA2's
     * files were never written, were written elsewhere, or were renamed — a clean result for the
     * wrong reason, the same failure mode `scanDirectory`'s own empty/absent checks exist to
     * prevent one level up. Naming the files is what turns "the package is clean" into "these
     * sources are covered".
     */
    @Test
    fun `ORA2-MODEL-11 the tagged and keyed model sources are inside the scanned boundary`() {
        val modelSourceDir = File("src/main/kotlin/civictech/oracle/model")

        val scanned = modelSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toSet()

        withClue("scanned $scanned") {
            scanned shouldContainAll setOf("DotModel.kt", "TaggedKeyedModels.kt", "Script.kt")
        }
    }

    /**
     * The permanent proof the ORA2 half of the rule CAN fail: a model source reaching for the
     * kernel's own dot identity (`civictech.cell.Timestamp`) is flagged. Without this, the
     * `Timestamp` entry added for `[ORA2-MODEL-12]` would be an untested line in a set.
     */
    @Test
    fun `ORA2-MODEL-12 a model source importing the kernel Timestamp is flagged`() {
        val violations = ModelImportBoundaryScanner.scanText(
            "Synthetic.kt",
            """
            package civictech.oracle.model

            import civictech.cell.Timestamp

            class Synthetic(val dot: Timestamp)
            """.trimIndent(),
        )

        violations shouldBe listOf(
            ModelImportBoundaryScanner.Violation("Synthetic.kt", "civictech.cell.Timestamp"),
        )
    }

    // -------------------------------------------------------------------------------------
    // computenet-n00e: the `scanForReferenceOpDeclarations` gate — `[ORA2-MODEL-11]` covers a
    // `ReferenceOp` wherever it is declared, not only under `civictech.oracle.model`.
    // -------------------------------------------------------------------------------------

    /**
     * The acceptance's own synthetic: a `ReferenceOp` declared OUTSIDE `civictech.oracle.model`
     * (here, `civictech.oracle.bind`, the package the real `SingleInstanceOrMapModel` was
     * declared in before this gate existed) that imports a `civictech.cell.data.op` type is
     * flagged by file and import, exactly like the model-only [scanDirectory] gate's own
     * synthetic above.
     */
    @Test
    fun `computenet-n00e a synthetic ReferenceOp declared outside civictech-oracle-model importing a forbidden op type is flagged`() {
        val tempDir = Files.createTempDirectory("model-import-boundary-outside-model").toFile()
        try {
            val offender = File(tempDir, "OutsideModelOffender.kt")
            offender.writeText(
                """
                package civictech.oracle.bind

                import civictech.cell.data.op.FilterCell
                import civictech.oracle.model.ModelState
                import civictech.oracle.model.SourceModel
                import civictech.oracle.model.SourceScript

                object OutsideModelOffender : SourceModel {
                    private val factory: FilterCell<*>? = null
                    override fun evaluate(slice: SourceScript): ModelState = throw UnsupportedOperationException()
                }
                """.trimIndent(),
            )

            val violations = ModelImportBoundaryScanner.scanForReferenceOpDeclarations(tempDir)

            violations shouldBe listOf(
                ModelImportBoundaryScanner.Violation(offender.path, "civictech.cell.data.op.FilterCell"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * The gate is deliberately whole-file, not declaration-scoped — see
     * [ModelImportBoundaryScanner]'s "computenet-n00e" section comment for why a body-scoped
     * version is unsound (it can mistake a `ReferenceOp`'s own error-message prose ABOUT a
     * forbidden class for a live reference to it). The consequence: a file that declares a
     * `ReferenceOp` AND separately imports a forbidden type for unrelated wiring is flagged too,
     * for the wiring import even though the `ReferenceOp` declaration never touches it. That
     * consequence is exactly what pushed the real `SingleInstanceOrMapModel` out of
     * `civictech.oracle.bind.TaggedOperators.kt` — a file that legitimately needs `OrMapCell`
     * for kernel-cell wiring elsewhere in the same file — and into
     * `civictech.oracle.model.TaggedKeyedModels.kt`, where it lives now.
     */
    @Test
    fun `a forbidden import used only by unrelated wiring code sharing a file with a ReferenceOp declaration is still flagged`() {
        val tempDir = Files.createTempDirectory("model-import-boundary-mixed-file").toFile()
        try {
            val mixed = File(tempDir, "Mixed.kt")
            mixed.writeText(
                """
                package civictech.oracle.bind

                import civictech.cell.data.OrMapCell
                import civictech.oracle.model.ModelState
                import civictech.oracle.model.SourceModel
                import civictech.oracle.model.SourceScript

                object Registration {
                    fun build() = OrMapCell<Any?, Any?>(null)
                }

                object CleanModel : SourceModel {
                    override fun evaluate(slice: SourceScript): ModelState = throw UnsupportedOperationException()
                }
                """.trimIndent(),
            )

            val violations = ModelImportBoundaryScanner.scanForReferenceOpDeclarations(tempDir)

            violations shouldBe listOf(
                ModelImportBoundaryScanner.Violation(mixed.path, "civictech.cell.data.OrMapCell"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * The positive gate for [ModelImportBoundaryScanner.scanForReferenceOpDeclarations]: every
     * `ReferenceOp` declared anywhere under `:oracle`'s main sources is import-clean today.
     * Every real declaration currently lives under `civictech.oracle.model` (computenet-n00e
     * moved the one that did not, `SingleInstanceOrMapModel`, out of `civictech.oracle.bind`),
     * so the sanity check below — that the scan actually recognises a real production file as
     * declaring a `ReferenceOp` — guards against the positive gate passing because zero
     * declarations were found anywhere, rather than because the ones that exist are clean.
     */
    @Test
    fun `computenet-n00e every ReferenceOp declared anywhere under oracle main sources is import-clean`() {
        val root = File("src/main/kotlin")

        withClue("sanity: TaggedKeyedModels.kt really is recognised as declaring a ReferenceOp") {
            ModelImportBoundaryScanner.declaresReferenceOp(
                File(root, "civictech/oracle/model/TaggedKeyedModels.kt").readText(),
            ) shouldBe true
        }

        val violations = ModelImportBoundaryScanner.scanForReferenceOpDeclarations(root)

        withClue("ReferenceOp declarations anywhere in :oracle import forbidden types $violations [ORA2-MODEL-11]") {
            violations.shouldBeEmpty()
        }
    }
}
