package civictech.bench

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Leaf-module guard [BEN1-04] / BS-6: `:bench` sits at the edge of the project
 * graph. Nothing else depends on it, and it depends only on the two modules its
 * benchmarks are allowed to exercise.
 *
 * Deliberately NOT the Gradle Tooling API: opening a `ProjectConnection` spawns a
 * *nested* Gradle build, which is neither fast nor "unit" — the whole point of a
 * companion test living in `:bench:test` is that it runs in the same sub-second
 * budget as the rest of the suite. Instead this parses the build scripts as text,
 * the same way a human skimming `git grep` would: read `settings.gradle.kts` for
 * the module list, then each module's `build.gradle.kts` for `project(":...")`
 * references.
 *
 * ## What the parsing does and does not see
 *
 * This is a regex over source text, not a build-language parser, and that has
 * real limits worth stating rather than discovering by surprise:
 *
 * - It only sees a literal `project(":path")` call. A dependency expressed
 *   through a variable, a version-catalog bundle, `subprojects {}`, or any other
 *   indirection is invisible to it.
 * - It does not distinguish *why* a script mentions `project(":x")` — a
 *   `dependencies { implementation(project(":x")) }` entry and an unrelated
 *   `project(":x").tasks.named(...)` task-graph wire (kernel/build.gradle.kts
 *   has exactly this, wiring `:gen:test` ahead of `:kernel:compileKotlin`) both
 *   match. That is harmless for the two rules this test checks — both are
 *   phrased as "must / must not mention `:bench`" and "`:bench` mentions only
 *   these" — but it means the map this test builds is not a general-purpose
 *   dependency graph and must not be reused as one.
 * - Full-line `//` comments are stripped before scanning so a commented-out
 *   `project(":bench")` example does not trip the guard, but a `/* ... */` block
 *   comment or a trailing same-line `//` comment is not stripped and would still
 *   match if it quoted a `project(":...")` call.
 * - `include(":a", ":b")` (multiple projects in one call) is handled; an
 *   `include` built from a variable or a loop is not.
 *
 * Given those limits are about *false negatives* (missing a real dependency
 * expressed unusually) rather than false positives on the straightforward
 * `dependencies { implementation(project(":x")) }` shape every module in this
 * repository actually uses (verified by reading every `build.gradle.kts` at
 * authoring time), a regex is an acceptable trade for staying a fast unit test.
 */
class ProjectGraphTest {

    private val repoRoot: File by lazy {
        val property = System.getProperty("computenet.repo.root")
            ?: error(
                "System property 'computenet.repo.root' is not set. It must be wired " +
                    "in bench/build.gradle.kts on the :bench `test` task so this test can " +
                    "locate settings.gradle.kts and every module's build.gradle.kts."
            )
        File(property).also {
            check(it.isDirectory) { "computenet.repo.root '$property' is not a directory" }
        }
    }

    /** Matches `include(":a", ":b", ...)`, one or more quoted project paths per call. */
    private val includeCallRegex = Regex("""include\s*\(([^)]*)\)""")

    /** Matches a literal `project(":path")` reference anywhere in a build script. */
    private val projectRefRegex = Regex("""project\(\s*"([^"]+)"\s*\)""")

    private val quotedStringRegex = Regex(""""([^"]+)"""")

    /** Strips full-line `//` comments before scanning (see class doc for the limit). */
    private fun sourceTextOf(file: File): String =
        file.readLines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")

    /** All project paths declared in settings.gradle.kts, e.g. [":kernel", ":demo:shell"]. */
    private fun declaredProjectPaths(): List<String> {
        val settingsFile = File(repoRoot, "settings.gradle.kts")
        check(settingsFile.isFile) { "Expected ${settingsFile.absolutePath} to exist" }
        val text = sourceTextOf(settingsFile)
        return includeCallRegex.findAll(text)
            .flatMap { match -> quotedStringRegex.findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .filter { it.startsWith(":") }
            .toList()
    }

    /** Maps a Gradle project path to its build script, e.g. ":demo:shell" -> demo/shell/build.gradle.kts. */
    private fun buildFileFor(projectPath: String): File {
        val relativeDir = projectPath.removePrefix(":").replace(":", File.separator)
        return File(repoRoot, "$relativeDir${File.separator}build.gradle.kts")
    }

    /** Every `project(":...")` reference found anywhere in `projectPath`'s own build script. */
    private fun projectReferencesOf(projectPath: String): List<String> {
        val buildFile = buildFileFor(projectPath)
        // A project without its own build.gradle.kts (there are none today, but a future
        // aggregator module could be added) trivially references nothing.
        if (!buildFile.isFile) return emptyList()
        val text = sourceTextOf(buildFile)
        return projectRefRegex.findAll(text).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `no project other than bench depends on bench`() {
        val offenders = declaredProjectPaths()
            .filter { it != ":bench" }
            .filter { projectPath -> ":bench" in projectReferencesOf(projectPath) }

        offenders.shouldBeEmpty()
    }

    @Test
    fun `bench depends only on kernel and testkit`() {
        val benchDeps = projectReferencesOf(":bench").distinct()

        // Assert both directions: bench must not reach beyond the allowed set, and (the
        // sentinel that the parse itself is actually finding something, rather than
        // silently matching zero references and passing vacuously) it must still declare
        // the two dependencies the module is built around.
        benchDeps.forEach { dep ->
            require(dep == ":kernel" || dep == ":testkit") {
                "bench/build.gradle.kts declares project(\"$dep\"), which is neither " +
                    ":kernel nor :testkit [BEN1-03]"
            }
        }
        benchDeps.shouldContainAll(listOf(":kernel", ":testkit"))
    }
}
