package civictech.oracle.bind

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Epic computenet-4ru §9 risk 2, decided at breakdown on computenet-4ru.3 (see that bead's
 * comments): a change to `civictech.cell.data.op` fails `:oracle:test` until the checked-in
 * inventory (`oracle/src/test/resources/operator-inventory.txt`) is updated. That file's own
 * header names the required follow-through — updating it is the moment to also update
 * [OperatorCatalog]'s registration and the reference model for the changed operator, in the
 * same change.
 *
 * This works while the catalog is still nearly empty because it diffs the *kernel package's
 * actual classes* against the inventory, not the catalog's registrations against it.
 *
 * **Kotlin file-facade filtering (computenet-4ru.15).** Diffing the classpath rather than
 * hand-authored declarations originally meant some reddened diffs carried no operator-vocabulary
 * information at all: Kotlin emits a compiler-generated file-facade class `<File>Kt` for any
 * source file with top-level declarations, so adding or removing the *only* top-level
 * declaration in an existing file flipped its facade in or out of the classpath with nothing
 * about the operator vocabulary having changed (measured: an unrelated top-level private helper
 * added to `CountCell.kt` alone reddened this gate as `Added: [CountCellKt]`,
 * computenet-4ru.3.2 review). [isKotlinFileFacade] now filters those classes out of the diffed
 * set entirely, so that case no longer reddens the gate — see its KDoc for why the filter is
 * sound rather than merely quieter (it must still catch a genuinely new operator class, and
 * still catch a rename of a hand-written class whose generated `*Base`/`*Ports`/`*Api` follows
 * it, both of which real operator-vocabulary churn and neither of which this filter touches).
 *
 * The jar branch of [actualTopLevelClassNames] (below) is exercised by this task's own
 * `:oracle:test` run only if Gradle resolves `:kernel`'s project dependency as a jar rather than
 * a directory classpath entry; on this build it resolves as a directory, so the jar branch is
 * implemented per the bead's instruction but not exercised by this test suite.
 *
 * **Bytecode-metadata facade detection (computenet-4ru.19).** [isKotlinFileFacade] originally
 * decided facade-ness from the **source tree** — a `<Bare>Kt` classpath name was treated as a
 * facade iff a same-named `<Bare>.kt` source file existed. That had two edges, both measured by
 * computenet-4ru.15's own reviewer and, until this item, accepted as residual: a `<Bare>.kt` file
 * with no top-level declaration emits no facade, so a hand-written class literally named
 * `<Bare>Kt` beside it was silently dropped from the diffed set (a false negative — a genuinely
 * new operator invisible to the gate); and `@file:JvmName(...)` renames a facade off its file's
 * base name, so that facade's churn still reddened the gate (a residual false positive of the
 * original kind). [isKotlinFileFacade] now asks the **class file itself**: the Kotlin compiler
 * stamps every class it emits with a runtime-retained `kotlin.Metadata` annotation whose `kind`
 * distinguishes a file facade (`FILE_FACADE_KIND`, 2) from an ordinary class (`CLASS_KIND`, 1) —
 * see its KDoc for why that is exact rather than merely quieter.
 */
class OperatorInventoryTest {

    private val packagePath = "civictech/cell/data/op"

    /**
     * Every top-level (non-nested, no `$` in the class file name) class the compiled
     * `civictech.cell.data.op` package puts on this module's test classpath: hand-written
     * cells and their `*Api` companions, KSP-generated `*Base`/`*Ports` classes, and any
     * top-level-function file facade (`FooKt`). ClassLoader resources for a package resolve
     * to either a directory URL or a jar URL depending on how Gradle wires the runtime
     * classpath for a project dependency — both are handled so the test does not depend on
     * which one :kernel happens to produce.
     */
    private fun actualTopLevelClassNames(): Set<String> = actualTopLevelClassNames(packagePath)

    /**
     * Same walk as [actualTopLevelClassNames] above, generalized to any [packagePath] so this
     * file's second gate (`civictech.cell.data`, computenet-y9p4) can reuse it.
     */
    private fun actualTopLevelClassNames(packagePath: String): Set<String> {
        val classLoader = OperatorInventoryTest::class.java.classLoader
        val resources = classLoader.getResources(packagePath).toList()
        check(resources.isNotEmpty()) {
            "No classpath entries found for '$packagePath' — :oracle's test classpath is " +
                "missing :kernel's compiled output, so this test cannot see the operator " +
                "package at all."
        }

        val names = mutableSetOf<String>()
        for (url in resources) {
            when (url.protocol) {
                "file" -> {
                    val dir = File(url.toURI())
                    dir.listFiles { f -> f.isFile && f.extension == "class" }
                        ?.map { it.nameWithoutExtension }
                        ?.filterNot { it.contains('$') }
                        ?.let(names::addAll)
                }

                "jar" -> {
                    val jarPath = url.path.substringBefore("!").removePrefix("file:")
                    JarFile(jarPath).use { jar ->
                        val prefix = "$packagePath/"
                        jar.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".class") }
                            .map { it.name.removePrefix(prefix).removeSuffix(".class") }
                            .filterNot { it.contains('/') || it.contains('$') }
                            .forEach(names::add)
                    }
                }

                else -> error("Unsupported classpath entry protocol '${url.protocol}' for $packagePath: $url")
            }
        }

        check(names.isNotEmpty()) {
            "Zero top-level classes found under '$packagePath' across ${resources.size} " +
                "classpath entries — cannot diff an empty actual set against the inventory."
        }
        return names
    }

    /** `kotlin.Metadata.kind` for a Kotlin file facade — see [kotlin.Metadata]'s own KDoc. */
    private val fileFacadeMetadataKind = 2

    /**
     * True iff [className] is a Kotlin compiler-generated file-facade class rather than a
     * hand-written or KSP-generated one, determined by loading the class and reading its own
     * `kotlin.Metadata` annotation — positive evidence stamped by the compiler on the class file
     * itself, rather than an inference from the source tree.
     *
     * **Why this is exact, not merely quieter (computenet-4ru.19, replacing computenet-4ru.15's
     * source-existence heuristic).** `kotlin.Metadata` carries `RUNTIME` retention (it is how
     * the Kotlin reflection and compiler tooling read a class's shape at all), and every class
     * the Kotlin compiler emits — file facade, regular class, KSP-generated class alike — carries
     * one. Its `kind` property distinguishes `FILE_FACADE_KIND` (2, what a file's top-level
     * declarations compile to) from `CLASS_KIND` (1, an ordinary named class) unconditionally:
     * unlike the old filter, this does not depend on the class's name matching any source file,
     * so it is immune to both edges the old heuristic missed:
     * 1. A `<Bare>.kt` carrying *no* top-level declaration emits no facade at all, so a
     *    hand-written class literally named `<Bare>Kt` beside it has `CLASS_KIND`, not
     *    `FILE_FACADE_KIND` — it is correctly left in the diffed set and reddens the gate as any
     *    new operator must (the old filter, keying on the source file's mere existence, dropped
     *    it silently instead).
     * 2. `@file:JvmName("…")` renames a facade off its file's base name, but the renamed class
     *    still carries `FILE_FACADE_KIND` regardless of what it is named — it is correctly
     *    filtered out (the old filter, keying on a name match against the source tree, missed the
     *    rename and left the facade reddening the gate as a false positive).
     *
     * A hand-written class's own generated `*Base`/`*Ports`/`*Api` companions never carry
     * `FILE_FACADE_KIND` either (they are ordinary KSP-emitted classes), so a rename of a
     * hand-written class still reddens the gate on all of its renamed companions, as it must.
     *
     * A class outside `civictech.cell.data.op`'s Kotlin-compiled surface (there is none on this
     * classpath, but defensively) has no `kotlin.Metadata` at all and is treated as not a facade.
     */
    private fun isKotlinFileFacade(className: String): Boolean =
        isKotlinFileFacade(packagePath, className)

    /**
     * Same check as [isKotlinFileFacade] above, generalized to any [packagePath] so this file's
     * second gate ([civictech.cell.data]'s source cells, computenet-y9p4) can reuse it without
     * duplicating the reasoning in that function's KDoc.
     */
    private fun isKotlinFileFacade(packagePath: String, className: String): Boolean {
        val classLoader = OperatorInventoryTest::class.java.classLoader
        val fqcn = packagePath.replace('/', '.') + "." + className
        val clazz = Class.forName(fqcn, false, classLoader)
        val metadata = clazz.getAnnotation(Metadata::class.java) ?: return false
        return metadata.kind == fileFacadeMetadataKind
    }

    private fun declaredInventory(resourceName: String): Set<String> {
        val stream = OperatorInventoryTest::class.java.classLoader
            .getResourceAsStream(resourceName)
            ?: error(
                "oracle/src/test/resources/$resourceName is missing from the test classpath.",
            )
        val declared = stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
        check(declared.isNotEmpty()) {
            "$resourceName parsed to zero declared names — cannot diff the real package " +
                "against an empty inventory."
        }
        return declared
    }

    @Test
    fun `civictech-cell-data-op's top-level classes match the checked-in inventory`() {
        val actual = actualTopLevelClassNames().filterNot(::isKotlinFileFacade).toSet()
        val declared = declaredInventory("operator-inventory.txt")

        val added = (actual - declared).sorted()
        val removed = (declared - actual).sorted()

        withClue(
            "civictech.cell.data.op has drifted from " +
                "oracle/src/test/resources/operator-inventory.txt (epic computenet-4ru §9 " +
                "risk 2). Added: $added. Removed: $removed. (Kotlin file-facade classes are " +
                "filtered out by isKotlinFileFacade using each class's own kotlin.Metadata " +
                "kind, so every name here is real operator-vocabulary churn — see that " +
                "function's KDoc.) " +
                "Update the inventory AND OperatorCatalog's " +
                "registration AND the reference model for the changed operator(s) in the same " +
                "change, per the inventory file's own header.",
        ) {
            (added + removed).shouldBeEmpty()
        }
    }

    /**
     * computenet-y9p4: the same drift guard as above, over `civictech.cell.data`'s SOURCE
     * cells rather than `civictech.cell.data.op`'s OPERATOR cells. Before this test,
     * `civictech.cell.data` had no such guard at all: `VocabularyCompletenessTest` enumerates
     * a hand-written list of requirement ids and cannot notice a new cell, and the
     * `[ORA1-HONEST-02]` exclusion ledger in `MapCellModel.kt` is prose — so a new source cell
     * could land absent from both the vocabulary and the ledger with nothing red (epic
     * computenet-4ru §9 risk 2, the exact failure mode this guard family exists to prevent).
     *
     * `civictech.cell.data` (unlike `.op`, which is one directory of cells and nothing else)
     * also holds non-cell top-level types the two directories' listing already shows are
     * genuinely present: `Aggregator`/`Aggregators` (the merge-function vocabulary `MapCell`
     * and the window operators share), `Replicable` (the replication marker interface),
     * `Windows` (window-boundary helpers), and `BoundedWalk.kt`'s internal `KeyWalk`/
     * `EntryOrder`/`PageBudget`. Those are exactly as real as `civictech.cell.data.op`'s own
     * non-cell helper names already in `operator-inventory.txt` (`FrontierBuilder`,
     * `GatedFold`, `OperatorEntry`, `OperatorWalk`, `PagedEntry`, `SubState`, `TaggedEntry`,
     * `KeyedEntry`, `GroupEntry`, `AdvertisedLedger`, `MintedLedger`, `JoinLedger`,
     * `PresenceLanes`) — this gate diffs the package's whole compiled surface the same way
     * [OperatorInventoryTest]'s does, not a hand-picked subset of "the cells", so a human still
     * has to read what actually changed before deciding whether it is vocabulary churn.
     *
     * Also present here and *not* named in the bead that requested this test: `WatermarkCell`
     * / `WatermarkCellPorts` (`kernel/src/main/kotlin/civictech/cell/data/Watermark.kt`) — a
     * cell in this package that is neither registered in [OperatorCatalog] nor listed in the
     * `[ORA1-HONEST-02]` exclusion ledger. The bead's own description names five registered
     * source cells (`SetCell`, `KeyedSetCell`, `MapCell`, `CounterCell`, `PnCounterCell`) and
     * two ledger exclusions (`ListCell`, `OrMapCell`) — seven cell types — but the compiled
     * package has 39 top-level classpath names across those seven cells' own `*Api`/`*Base`/
     * `*Ports` companions plus the non-cell helpers above plus `WatermarkCell`'s pair, none of
     * which is a discrepancy this test resolves: it is a mechanical classpath diff, the same
     * contract [OperatorInventoryTest] already has for `.op`, and closing `WatermarkCell`'s
     * gap (registering it, or adding it to the exclusion ledger) is vocabulary work outside
     * this task's claim.
     */
    private val sourceCellPackagePath = "civictech/cell/data"

    @Test
    fun `civictech-cell-data's top-level classes match the checked-in inventory`() {
        val actual = actualTopLevelClassNames(sourceCellPackagePath)
            .filterNot { isKotlinFileFacade(sourceCellPackagePath, it) }
            .toSet()
        val declared = declaredInventory("source-cell-inventory.txt")

        val added = (actual - declared).sorted()
        val removed = (declared - actual).sorted()

        withClue(
            "civictech.cell.data has drifted from " +
                "oracle/src/test/resources/source-cell-inventory.txt (epic computenet-4ru §9 " +
                "risk 2, computenet-y9p4). Added: $added. Removed: $removed. (Kotlin " +
                "file-facade classes are filtered out by isKotlinFileFacade using each class's " +
                "own kotlin.Metadata kind, so every name here is real classpath churn — see " +
                "that function's KDoc.) " +
                "Update the inventory AND OperatorCatalog's registration (or the " +
                "[ORA1-HONEST-02] exclusion ledger in MapCellModel.kt) for the changed source " +
                "cell(s) in the same change, per the inventory file's own header.",
        ) {
            (added + removed).shouldBeEmpty()
        }
    }
}
