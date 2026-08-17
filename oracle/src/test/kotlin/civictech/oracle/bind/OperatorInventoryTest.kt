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
 * Caveat (see operator-inventory.txt's header for the full caution): diffing the classpath
 * rather than hand-authored source means some reddened diffs — a Kotlin file-facade `*Kt`
 * class appearing/disappearing, or a generated `*Base`/`*Ports`/`*Api` name changing — carry no
 * operator-vocabulary information on their own; verify an operator was actually added, removed,
 * or renamed before updating [OperatorCatalog] or the reference model.
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
        val actual = actualTopLevelClassNames()
        val declared = declaredInventory()

        val added = (actual - declared).sorted()
        val removed = (declared - actual).sorted()

        withClue(
            "civictech.cell.data.op has drifted from " +
                "oracle/src/test/resources/operator-inventory.txt (epic computenet-4ru §9 " +
                "risk 2). Added: $added. Removed: $removed. Update the inventory AND " +
                "OperatorCatalog's registration AND the reference model for the changed " +
                "operator(s) in the same change, per the inventory file's own header.",
        ) {
            (added + removed).shouldBeEmpty()
        }
    }
}
