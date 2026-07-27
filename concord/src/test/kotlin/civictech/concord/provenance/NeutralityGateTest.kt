package civictech.concord.provenance

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T10-A: executable form of the concord neutrality rule stated three times in
 * prose (`concord/build.gradle.kts:8-10`, `doc/ARCHITECTURE.md` §5, and
 * `AGENTS.md`'s `:concord` repo-map entry) but, until this test, enforced
 * nowhere — one violation had already drifted in
 * (`ConcordanceTest.kt` used to import `civictech.cell.data.op.UnionSetCell`
 * for nothing but a fixture string).
 *
 * Rule: only `civictech.concord.driver.kernel` (and any sub-package of it) is
 * the kernel binding — every other package in `:concord`, main or test, must
 * stay kernel-blind. The L0-L4 layering described in `doc/ARCHITECTURE.md`
 * §5 assumes L1/L2/L4 never see `civictech.cell.*`; a live leak there would
 * stay silent until a second `Driver` binding is attempted and discovers it
 * depended on kernel types it was never supposed to reach.
 *
 * Walks `concord/src/{main,test}/kotlin` directly (real tree, not a fixture)
 * so this test is itself the gate — wired into `:concord:test` (and so
 * `:concord:check`, which depends on `test`) the same way `concordanceGate`
 * and `docLints` gate their invariants, just as a plain JUnit test rather
 * than a separate `JavaExec` task.
 */
class NeutralityGateTest {

    private val kernelDriverPackage = "civictech.concord.driver.kernel"
    private val packageLine = Regex("""^\s*package\s+([\w.]+)""")
    private val forbiddenImport = Regex("""^\s*import\s+civictech\.cell(?:\.|\s|$)""")

    /** `:concord`'s project directory — the Gradle test task's working directory. */
    private fun concordSourceRoots(): List<File> =
        listOf("src/main/kotlin", "src/test/kotlin")
            .map { File(it) }
            .filter { it.isDirectory }

    private fun concordKotlinFiles(): List<File> =
        concordSourceRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .sortedBy { it.path }

    @Test
    fun `only civictech-concord-driver-kernel may import civictech-cell`() {
        val violations = mutableListOf<String>()

        val scanned = concordKotlinFiles()
        // Non-vacuity: the source roots are resolved RELATIVE to the test task's
        // working directory (`:concord`'s project dir). If that ever changes,
        // `concordSourceRoots` filters both roots out and this gate would pass
        // forever while reading nothing.
        assertTrue(scanned.size >= 10) {
            "scanned only ${scanned.size} :concord sources from working directory " +
                "${File(".").absolutePath} — the walk is broken, not the corpus"
        }

        scanned.forEach { file ->
            val lines = file.readLines()
            val declaredPackage = lines.firstNotNullOfOrNull { packageLine.find(it)?.groupValues?.get(1) }
            val isDriverKernel = declaredPackage != null &&
                (declaredPackage == kernelDriverPackage || declaredPackage.startsWith("$kernelDriverPackage."))
            if (isDriverKernel) return@forEach

            lines.forEachIndexed { index, line ->
                if (forbiddenImport.containsMatchIn(line)) {
                    violations += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }

        assertTrue(violations.isEmpty()) {
            "concord neutrality violated — only `$kernelDriverPackage` (or a sub-package of it) may " +
                "import `civictech.cell.*`. Move the offending code into that package, or drop the " +
                "import if it is unused:\n" + violations.joinToString("\n")
        }
    }
}
