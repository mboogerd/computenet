package civictech.oracle

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * `[ORA1-API-04]`: `:oracle` depends on `:concord`, `:wire`, `:inspect` and `:demo:*` not at
 * all. The epic's design decision D1 (epic §9 risk 6, affirmed on computenet-4ru.3) turns on
 * this: the oracle is a *separate* leaf module precisely so that concord can stay
 * implementation-neutral, and a dependency edge either way is what would collapse that.
 *
 * Deliberately dependency-free — no ArchUnit, per the feature's design field. Two
 * independent checks, because each catches something the other cannot:
 *
 * - the **classpath** check catches a forbidden module arriving *transitively*, through a
 *   dependency of `:kernel` or `:testkit` that nobody edited here;
 * - the **build-file** check catches a forbidden module declared but not yet reachable by
 *   any class this test happens to name, and names the offending line rather than a missing
 *   class.
 */
class ModuleDependencyTest {

    /**
     * One public top-level type per forbidden module, used as that module's fingerprint on
     * the classpath. Each is `public` and top-level, so it is loadable by name whenever its
     * module is present — an `internal` one would be too (JVM-public), but a public one
     * cannot be renamed silently by a visibility change.
     */
    private val fingerprints = mapOf(
        ":concord" to "civictech.concord.generator.ScenarioGenerator",
        ":wire" to "civictech.wire.WsTransport",
        ":inspect" to "civictech.inspect.InspectorServer",
        ":demo:shell" to "civictech.demo.shell.DemoShell",
    )

    @Test
    fun `ORA1-API-04 no forbidden module is on the oracle test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :oracle's classpath, so $module is reachable from :oracle — " +
                    "[ORA1-API-04] forbids it. Check oracle/build.gradle.kts and the transitive " +
                    "closure of :kernel and :testkit."
            ) {
                Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader)
            }
        }
    }

    @Test
    fun `ORA1-API-04 oracle build file declares no dependency on concord, wire, inspect or a demo module`() {
        // A Gradle Test task's working directory is the project directory (relied on the same
        // way by kernel/build.gradle.kts's expected-failure ledger), so this resolves to
        // oracle/build.gradle.kts.
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true

        // Comments are stripped first: this file's own KDoc quotes
        // `testImplementation(project(":oracle"))` as the consumer form, and a scan that
        // read commentary as declarations would report whatever a comment happened to name.
        val code = buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        val declared = Regex("""project\(\s*"(:[^"]+)"\s*\)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the forbidden-list check below pass against zero declarations.
        declared shouldContainAll listOf(":kernel", ":testkit")

        val forbidden = declared.filter { it in setOf(":concord", ":wire", ":inspect") || it.startsWith(":demo") }
        withClue("oracle/build.gradle.kts declares forbidden module dependencies $forbidden [ORA1-API-04]") {
            forbidden.shouldBeEmpty()
        }
    }
}
