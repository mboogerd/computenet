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

    /**
     * A declaration header runs from the `class`/`object` keyword to the `{` that opens its body,
     * and the gaps below exclude `{`, `}` and `;` rather than newlines — so the match can span the
     * multi-line headers this codebase actually writes, but can never run past one declaration's
     * opening brace into the next. Excluding the newline instead (the first version of this scan,
     * computenet-n00e) missed `GroupByModel`, a real `OperatorModel` in
     * `civictech.oracle.model` whose constructor parameters put `: OperatorModel, Serializable {`
     * on its own line, and any `ReferenceOp` outside `civictech.oracle.model` formatted the same
     * way would have evaded [scanForReferenceOpDeclarations] entirely — the gate passing because
     * it recognised no declaration, not because the declaration was clean (review of
     * computenet-n00e; demonstrated by the two synthetic-text tests below).
     *
     * The looser form can over-match — a class that merely *takes* a `SourceModel` constructor
     * parameter reads the same to a text scanner — and that is the intended direction: an
     * over-match scans one extra file whole and can only produce a loud, visible violation, while
     * an under-match is a silent hole of exactly the kind this widening closes. (On the real tree
     * today the over-match costs nothing: the only file matched for a reason other than its own
     * `ReferenceOp` declaration is `ReferenceModel.kt`, which `scanDirectory` already scans.)
     *
     * Two further under-matches were measured by the second reader of computenet-n00e and are
     * closed here, because each is a *silent* hole — the same failure mode as the newline one:
     *  - a gap may contain a **balanced brace pair** ([headerGap]), so a default lambda argument
     *    (`val f: (Int) -> Int = { it },`) or a brace inside a string literal in the header no
     *    longer terminates the header early. Measured: a compiling `SourceModel` written into
     *    `civictech.oracle.bind` importing `civictech.cell.data.op.FilterCell` left the real-tree
     *    gate GREEN when its constructor carried `= { it }`. The pair is single-level on purpose
     *    — it must not be able to swallow a nested body and run into the next declaration.
     *  - a header may end at `by` as well as at `{`, so a body-less delegating declaration
     *    (`class J(d: SourceModel) : SourceModel by d`) is recognised.
     *
     * Known remaining hole, deliberately not closed: a declaration with **no body and no
     * delegation** (`object K : ReferenceOp`) has no terminator to match. Only the memberless
     * marker [ReferenceOp] can be implemented that way — [SourceModel] and [OperatorModel] both
     * carry an abstract `evaluate`, so an implementation of either always has a body or a `by` —
     * and such an object is not evaluable and cannot be registered. Terminating at end-of-line
     * instead was tried and rejected: it drags in unrelated wiring files (`OperatorCatalog.kt`
     * matches) for no gain.
     */
    private val headerGap = """(?:[^{};]|\{[^{}]*\})*?"""

    private val declarationHeader = Regex(
        """\b(?:class|object)\s+\w+$headerGap:$headerGap""" +
            """\b(?:ReferenceOp|SourceModel|OperatorModel)\b$headerGap(?:\{|\bby\b)""",
    )

    /**
     * Whether [text] declares a `class`/`object` implementing [ReferenceOp] (directly, or via
     * [SourceModel]/[OperatorModel], the only two evaluable sub-interfaces — see
     * `ReferenceOp.kt`). A supertype-list match over the declaration header, single- or
     * multi-line ([declarationHeader]): pure text, not a compiler-level check.
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
        withClue(
            "sanity: GroupByModel.kt too — a REAL OperatorModel whose multi-line constructor puts " +
                "': OperatorModel, Serializable {' on its own line. The first version of this scan " +
                "matched single-line headers only and silently skipped this file; the same " +
                "formatting outside civictech.oracle.model would have evaded the gate entirely",
        ) {
            ModelImportBoundaryScanner.declaresReferenceOp(
                File(root, "civictech/oracle/model/GroupByModel.kt").readText(),
            ) shouldBe true
        }

        val violations = ModelImportBoundaryScanner.scanForReferenceOpDeclarations(root)

        withClue("ReferenceOp declarations anywhere in :oracle import forbidden types $violations [ORA2-MODEL-11]") {
            violations.shouldBeEmpty()
        }
    }

    /**
     * The acceptance's synthetic again, in the two multi-line header shapes this codebase
     * actually writes — a wrapped supertype list, and constructor parameters one per line. Both
     * were **missed** by the first version of [ModelImportBoundaryScanner.declaresReferenceOp],
     * whose header match could not cross a newline: the offending file was never scanned and the
     * gate went green on a `ReferenceOp` importing `civictech.cell.data.op.FilterCell`. Found by
     * the review of computenet-n00e, by writing exactly these files into `:oracle`'s real
     * `civictech.oracle.bind` source directory and watching the positive gate above stay green.
     *
     * These are text-level, not filesystem-level, on purpose: a formatting variant is a property
     * of the predicate, and pinning it here keeps it independent of what happens to be committed.
     */
    @Test
    fun `computenet-n00e a ReferenceOp declared with a multi-line header is recognised, so its file is scanned`() {
        val wrappedSupertypes =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            object WrappedSupertypes :
                SourceModel,
                Serializable {
                val unused: FilterCell<*>? = null
            }
            """.trimIndent()
        val wrappedConstructor =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            class WrappedConstructor(
                private val keyFn: ElementKey,
                private val aggregate: AggregateFunction,
            ) : OperatorModel, Serializable {
                val unused: FilterCell<*>? = null
            }
            """.trimIndent()

        withClue("a wrapped supertype list is still a ReferenceOp declaration") {
            ModelImportBoundaryScanner.declaresReferenceOp(wrappedSupertypes) shouldBe true
        }
        withClue("so is a multi-line constructor header — GroupByModel's real shape") {
            ModelImportBoundaryScanner.declaresReferenceOp(wrappedConstructor) shouldBe true
        }
        withClue("and recognising them is what makes the forbidden import visible to the scan") {
            ModelImportBoundaryScanner.scanText("WrappedSupertypes.kt", wrappedSupertypes) shouldBe listOf(
                ModelImportBoundaryScanner.Violation("WrappedSupertypes.kt", "civictech.cell.data.op.FilterCell"),
            )
        }
    }

    /**
     * Second reader of computenet-n00e: two further header shapes that the newline fix alone
     * still missed, each a *silent* hole of the same kind — a brace inside the header (a default
     * lambda argument, or a brace in a string literal) terminated the match early, and a
     * body-less delegating declaration has no `{` to terminate at. Measured before the fix by
     * writing the first shape into `:oracle`'s real `civictech.oracle.bind` source directory as a
     * compiling `SourceModel` importing `civictech.cell.data.op.FilterCell`: the real-tree gate
     * stayed GREEN.
     */
    @Test
    fun `computenet-n00e a header carrying braces or ending at by is still a ReferenceOp declaration`() {
        val lambdaDefault =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            class LambdaDefault(
                private val f: (Int) -> Int = { it },
            ) : OperatorModel, Serializable {
                val unused: FilterCell<*>? = null
            }
            """.trimIndent()
        val braceInStringLiteral =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            class BraceInLiteral(
                private val label: String = "group {key}",
            ) : OperatorModel, Serializable {
                val unused: FilterCell<*>? = null
            }
            """.trimIndent()
        val delegatingNoBody =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            class Delegating(delegate: SourceModel, val unused: FilterCell<*>?) : SourceModel by delegate
            """.trimIndent()

        withClue("a default lambda argument is inside the header, not the body") {
            ModelImportBoundaryScanner.declaresReferenceOp(lambdaDefault) shouldBe true
        }
        withClue("so is a brace that only appears inside a string literal") {
            ModelImportBoundaryScanner.declaresReferenceOp(braceInStringLiteral) shouldBe true
        }
        withClue("a delegating declaration has no body brace at all; its header ends at `by`") {
            ModelImportBoundaryScanner.declaresReferenceOp(delegatingNoBody) shouldBe true
        }
        withClue("and each one's forbidden import is what recognising it makes visible") {
            ModelImportBoundaryScanner.scanText("LambdaDefault.kt", lambdaDefault) shouldBe listOf(
                ModelImportBoundaryScanner.Violation("LambdaDefault.kt", "civictech.cell.data.op.FilterCell"),
            )
        }
    }

    /**
     * The other half of [declaresReferenceOp]'s bound: the header match must not run past a
     * declaration's own opening brace, or a file holding an unrelated `class`/`object` and a
     * later mention of `SourceModel` would be dragged into the scan for no reason. `{`, `}` and
     * `;` are excluded from the match's gaps precisely to hold this.
     */
    @Test
    fun `computenet-n00e a file with no ReferenceOp declaration is not recognised, however it mentions one`() {
        val notADeclaration =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            object Registration {
                fun modelFor(id: String): SourceModel = error(id)
                val cell: FilterCell<*>? = null
            }
            """.trimIndent()

        ModelImportBoundaryScanner.declaresReferenceOp(notADeclaration) shouldBe false

        // Second reader of computenet-n00e: the same bound with a *closed* unrelated body in
        // front of the mention — the header gap admits one balanced brace pair (so a default
        // lambda argument survives), and that pair must not be able to swallow a whole
        // declaration body and carry the match on to an unrelated `SourceModel` mention below it.
        val bodyThenMention =
            """
            package civictech.oracle.bind

            import civictech.cell.data.op.FilterCell

            class Wiring(private val cell: FilterCell<*>?) {
                fun name() = "wiring"
            }

            fun modelFor(id: String): SourceModel = error(id)
            """.trimIndent()

        ModelImportBoundaryScanner.declaresReferenceOp(bodyThenMention) shouldBe false
    }
}
