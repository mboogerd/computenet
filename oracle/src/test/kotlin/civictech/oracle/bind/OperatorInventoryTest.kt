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
    private fun actualTopLevelClassNames(): Set<String> {
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

    /**
     * The `civictech.cell.data.op` **source** directory, read the same way
     * [civictech.oracle.ModuleDependencyTest] reads `oracle/build.gradle.kts`: a Gradle `Test`
     * task's working directory is the project directory (`oracle/`), so `..` reaches the sibling
     * `:kernel` module's checkout.
     */
    private val kernelOpSourceDir = File("../kernel/src/main/kotlin/civictech/cell/data/op")

    /**
     * True iff [className] is a Kotlin compiler-generated file-facade class rather than a
     * hand-written or KSP-generated one, determined by whether the package's **source
     * directory** — not the classpath, not a declaration count — contains a file named after
     * `className` with its trailing `Kt` stripped.
     *
     * **Why this is sound, not merely quieter (computenet-4ru.15).** Kotlin derives a file
     * facade's default JVM class name directly from its containing file's own base name
     * (`<Bare>.kt` -> `<Bare>Kt`), and two top-level declarations cannot share one qualified name
     * in a package — so a *hand-written* class literally named `<Bare>Kt` can never coexist with
     * a `<Bare>.kt` file that itself carries top-level declarations (that would be the same JVM
     * name twice, a compile error). Finding a `<Bare>.kt` file therefore proves the matching
     * `<Bare>Kt` classpath entry is the compiler's facade for *that* file, never a hand-written
     * class — independent of how many top-level declarations `<Bare>.kt` currently has (zero,
     * one, or many).
     *
     * That independence is what fixes the measured false positive without opening the false
     * negative the bead calls out: the file `<Bare>.kt` already existed before and after the
     * edit that added or removed its only top-level declaration, so this filter drops
     * `<Bare>Kt` from the diffed set in both cases and the gate never reddens on it. A genuinely
     * new operator lives in a *new* file, so its class names are never `<Bare>Kt` for a
     * `<Bare>.kt` that predates it — new vocabulary is unaffected. A rename of a hand-written
     * class doesn't touch this function at all: its generated `*Base`/`*Ports`/`*Api` names
     * don't end in `Kt`, so they are never filtered and still redden the gate, as they must.
     */
    private fun isKotlinFileFacade(className: String): Boolean {
        if (!className.endsWith("Kt")) return false
        val bare = className.removeSuffix("Kt")
        return File(kernelOpSourceDir, "$bare.kt").isFile
    }

    private fun declaredInventory(): Set<String> {
        val stream = OperatorInventoryTest::class.java.classLoader
            .getResourceAsStream("operator-inventory.txt")
            ?: error(
                "oracle/src/test/resources/operator-inventory.txt is missing from the test " +
                    "classpath.",
            )
        val declared = stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
        check(declared.isNotEmpty()) {
            "operator-inventory.txt parsed to zero declared names — cannot diff the real " +
                "package against an empty inventory."
        }
        return declared
    }

    @Test
    fun `civictech-cell-data-op's top-level classes match the checked-in inventory`() {
        check(kernelOpSourceDir.isDirectory) {
            "Expected kernel source directory not found at " +
                "${kernelOpSourceDir.path} (resolved from :oracle's project directory) — " +
                "isKotlinFileFacade cannot tell a compiler file-facade class from a " +
                "hand-written one without it."
        }

        val actual = actualTopLevelClassNames().filterNot(::isKotlinFileFacade).toSet()
        val declared = declaredInventory()

        val added = (actual - declared).sorted()
        val removed = (declared - actual).sorted()

        withClue(
            "civictech.cell.data.op has drifted from " +
                "oracle/src/test/resources/operator-inventory.txt (epic computenet-4ru §9 " +
                "risk 2). Added: $added. Removed: $removed. (Kotlin file-facade `*Kt` classes " +
                "are already filtered out by isKotlinFileFacade, so every name here is real " +
                "operator-vocabulary churn.) Update the inventory AND OperatorCatalog's " +
                "registration AND the reference model for the changed operator(s) in the same " +
                "change, per the inventory file's own header.",
        ) {
            (added + removed).shouldBeEmpty()
        }
    }
}
