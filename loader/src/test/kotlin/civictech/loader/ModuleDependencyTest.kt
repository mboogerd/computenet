package civictech.loader

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Feature computenet-051.1's dependency direction: `:loader` sits ABOVE the runtime.
 * It may reach down into `:nature` and `:kernel`; `:kernel` and `:concord` must not reach
 * up into it. A `:kernel -> :loader` edge would put dynamic jar loading inside the runtime
 * it is supposed to load modules into, and a `:concord -> :loader` edge would cost concord
 * the implementation-neutrality that `AGENTS.md` reserves it (only
 * `civictech.concord.driver.kernel` may import `civictech.cell.*` at all).
 *
 * Modelled on `civictech.oracle.ModuleDependencyTest`, and dependency-free for the same
 * reason (no ArchUnit). Three checks, because each sees something the others cannot:
 *
 * - the **classpath** check catches a forbidden module arriving *transitively*, through a
 *   dependency of `:nature`, `:kernel` or `:testkit` that nobody edited here;
 * - the **own-build-file** check catches a forbidden module declared in
 *   `loader/build.gradle.kts` but not yet reachable by any class this test happens to
 *   name, and names the offending line rather than a missing class;
 * - the **inbound** check is the only one that can see the direction this feature actually
 *   mandates. A dependency *on* `:loader` is invisible from inside `:loader` — it changes
 *   `:kernel`'s and `:concord`'s classpaths, not this module's — so it is checked by
 *   reading those two build files' text.
 */
class ModuleDependencyTest {

    /**
     * One public top-level type per forbidden module, used as that module's fingerprint on
     * the classpath. Each is `public` and top-level, so it is loadable by name whenever its
     * module is present. Deliberately the same FQNs `:oracle`'s test uses: the forbidden
     * set is the same one, and two copies that drifted apart would be worse than one.
     */
    private val fingerprints = mapOf(
        ":concord" to "civictech.concord.generator.ScenarioGenerator",
        ":wire" to "civictech.wire.WsTransport",
        ":inspect" to "civictech.inspect.InspectorServer",
        ":demo:shell" to "civictech.demo.shell.DemoShell",
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
     * way by `oracle/build.gradle.kts`'s dependency test and `kernel/build.gradle.kts`'s
     * expected-failure ledger), so this walks up from `loader/` to the checkout root.
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
    fun `no forbidden module is on the loader test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :loader's classpath, so $module is reachable from :loader. " +
                    "Check loader/build.gradle.kts and the transitive closure of :nature, " +
                    ":kernel and :testkit."
            ) {
                Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader)
            }
        }
    }

    @Test
    fun `loader build file declares no dependency on concord, wire, inspect or a demo module`() {
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true

        val declared = projectReferences(buildFile)

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the forbidden-list check below pass against zero declarations.
        declared shouldContainAll listOf(":nature", ":kernel", ":testkit")

        val forbidden = declared.filter {
            it in setOf(":concord", ":wire", ":inspect") || it.startsWith(":demo")
        }
        withClue("loader/build.gradle.kts declares forbidden module dependencies $forbidden") {
            forbidden.shouldBeEmpty()
        }
    }

    @Test
    fun `neither kernel nor concord depends on loader`() {
        val root = repoRoot()

        // Both files are read, not just scanned for the string, so a renamed or moved build
        // file fails loudly here instead of turning this check into a silent no-op. The
        // value is that module's non-vacuity sentinel: a dependency it demonstrably
        // declares today, so an empty parse fails as a broken parse rather than passing as
        // "no forbidden edge found".
        mapOf("kernel" to ":nature", "concord" to ":kernel").forEach { (module, sentinel) ->
            val buildFile = File(root, "$module/build.gradle.kts")
            withClue("expected ${buildFile.absolutePath} to exist") { buildFile.isFile shouldBe true }

            val declared = projectReferences(buildFile)

            withClue("parsed no $sentinel reference at all from $module/build.gradle.kts") {
                declared.shouldContainAll(listOf(sentinel))
            }

            val offending = declared.filter { it == ":loader" || it.startsWith(":loader:") }
            withClue(
                "$module/build.gradle.kts declares $offending. Feature computenet-051.1 " +
                    "mandates the opposite direction: :loader depends on :kernel/:nature, " +
                    "never the reverse, and :concord must stay implementation-neutral."
            ) {
                offending.shouldBeEmpty()
            }
        }
    }
}
