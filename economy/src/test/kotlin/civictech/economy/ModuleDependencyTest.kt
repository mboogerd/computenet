package civictech.economy

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Epic `computenet-66m` decision `66m-D3`'s dependency direction: `:economy` sits between
 * `:kernel` and its consumers, the same shape `:identity` and `:demograph` already have. It
 * depends on `:kernel` (and, through `:kernel`'s own `api(:nature)`, transitively on
 * `:nature`); `:kernel` — and `:nature`/`:gen`, which `:kernel` itself depends on — must never
 * depend on it.
 *
 * Modelled directly on `civictech.demograph.ModuleDependencyTest`, extended with `:identity`
 * and `:iroh` fingerprints (`:economy` must not reach either), dependency-free for the same
 * reason (no ArchUnit). Three checks, because each sees something the others cannot:
 *
 * - the **classpath** check catches a forbidden module arriving *transitively*, through a
 *   dependency of `:kernel` or `:testkit` that nobody edited here;
 * - the **own-build-file** check catches a forbidden module declared in
 *   `economy/build.gradle.kts` but not yet reachable by any class this test happens to
 *   name, and names the offending line rather than a missing class;
 * - the **inbound** check is the only one that can see the direction this epic actually
 *   mandates. A dependency *on* `:economy` is invisible from inside `:economy` — it changes
 *   `:kernel`'s, `:nature`'s or `:gen`'s classpath, not this module's — so it is checked by
 *   reading those three build files' text.
 */
class ModuleDependencyTest {

    /**
     * One public top-level type per forbidden module, used as that module's fingerprint on
     * the classpath. Each is `public` and top-level, so it is loadable by name whenever its
     * module is present. The `:concord`/`:wire`/`:inspect`/`:demo:shell`/`:oracle` FQNs are
     * the same ones `:demograph`'s, `:oracle`'s and `:loader`'s tests use; `:identity` and
     * `:iroh` are added here because `:economy` must reach neither (66m-D3 — no `:identity`
     * dependency, no kernel edit, no issuer key material in this module).
     */
    private val fingerprints = mapOf(
        ":concord" to "civictech.concord.generator.ScenarioGenerator",
        ":wire" to "civictech.wire.WsTransport",
        ":inspect" to "civictech.inspect.InspectorServer",
        ":demo:shell" to "civictech.demo.shell.DemoShell",
        ":oracle" to "civictech.oracle.bind.OperatorCatalog",
        ":identity" to "civictech.identity.Ed25519",
        ":iroh" to "civictech.iroh.IrohTransport",
    )

    /** Strips block and line comments, so commentary quoting a `project(":x")` form is not read as a declaration. */
    private fun code(file: File): String =
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun projectReferences(file: File): List<String> =
        Regex("""project\(\s*"(:[^"]+)"\s*\)""").findAll(code(file)).map { it.groupValues[1] }.toList()

    /**
     * A Gradle `Test` task's working directory is the project directory (relied on the same
     * way by `demograph`'s, `oracle`'s and `loader`'s dependency tests), so this walks up
     * from `economy/` to the checkout root.
     */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    @Test
    fun `no forbidden module is on the economy test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :economy's classpath, so $module is reachable from " +
                    ":economy. Check economy/build.gradle.kts and the transitive closure " +
                    "of :kernel and :testkit."
            ) {
                Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader)
            }
        }
    }

    @Test
    fun `economy build file depends on no module outside kernel, nature and testkit`() {
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true

        val declared = projectReferences(buildFile)

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the forbidden-list check below pass against zero declarations.
        declared shouldContainAll listOf(":kernel")

        val allowed = setOf(":kernel", ":nature", ":testkit")
        val outsideAllowed = declared.filterNot { it in allowed }
        withClue(
            "economy/build.gradle.kts declares $outsideAllowed, outside {:kernel, :nature, " +
                ":testkit}. computenet-66m (66m-D3) fixes :economy's dependencies to that set."
        ) {
            outsideAllowed.shouldBeEmpty()
        }

        val forbidden = declared.filter {
            it in setOf(":concord", ":wire", ":inspect", ":oracle", ":identity", ":iroh") || it.startsWith(":demo")
        }
        withClue("economy/build.gradle.kts declares forbidden module dependencies $forbidden") {
            forbidden.shouldBeEmpty()
        }
    }

    @Test
    fun `neither kernel, nature nor gen depends on economy`() {
        val root = repoRoot()

        // Each file is read, not just scanned for the string, so a renamed or moved build
        // file fails loudly here instead of turning this check into a silent no-op. The
        // value is that module's non-vacuity sentinel: a dependency it demonstrably
        // declares today, so an empty parse fails as a broken parse rather than passing as
        // "no forbidden edge found".
        mapOf("kernel" to ":nature", "nature" to null, "gen" to ":nature").forEach { (module, sentinel) ->
            val buildFile = File(root, "$module/build.gradle.kts")
            withClue("expected ${buildFile.absolutePath} to exist") { buildFile.isFile shouldBe true }

            val declared = projectReferences(buildFile)

            if (sentinel != null) {
                withClue("parsed no $sentinel reference at all from $module/build.gradle.kts") {
                    declared.shouldContainAll(listOf(sentinel))
                }
            }

            val offending = declared.filter { it == ":economy" || it.startsWith(":economy:") }
            withClue(
                "$module/build.gradle.kts declares $offending. Epic computenet-66m (66m-D3) " +
                    "mandates the opposite direction: :economy depends on :kernel (and " +
                    ":nature transitively), never the reverse."
            ) {
                offending.shouldBeEmpty()
            }
        }
    }
}
