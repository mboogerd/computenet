package civictech.query

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * `[QRY1-API-02]`: `:query` depends on `:concord`, `:wire`, `:inspect` and `:demo:*` not at
 * all — the same non-negotiable that `civictech.oracle.ModuleDependencyTest` enforces for
 * `:oracle`, for the same reason (AGENTS.md reserves `civictech.cell.*` imports to
 * `civictech.concord.driver.kernel`; `:query` is kernel-coupled by construction).
 *
 * `[QRY1-API-01]`'s shape half: `:oracle` sits on `:query`'s TEST classpath only — it is the
 * differential-testing harness `:query`'s own tests are built against, not a runtime
 * dependency of the compiler — and no `ksp-cell` plugin is applied, because `:query` authors
 * no cell (`[QRY1-LOWER-03]`).
 *
 * Deliberately dependency-free — no ArchUnit, per `computenet-cab.1`'s design field
 * (cab.1-D2). Two independent checks for the forbidden-module rule, because each catches
 * something the other cannot:
 *
 * - the **classpath** check catches a forbidden module arriving *transitively*, through a
 *   dependency of `:kernel` that nobody edited here;
 * - the **build-file** check catches a forbidden module declared but not yet reachable by
 *   any class this test happens to name, and names the offending line rather than a missing
 *   class.
 */
class ModuleDependencyTest {

    /**
     * One public top-level type per forbidden module, used as that module's fingerprint on
     * the classpath. Each is `public` and top-level, so it is loadable by name whenever its
     * module is present.
     */
    private val fingerprints = mapOf(
        ":concord" to "civictech.concord.generator.ScenarioGenerator",
        ":wire" to "civictech.wire.WsTransport",
        ":inspect" to "civictech.inspect.InspectorServer",
        ":demo:shell" to "civictech.demo.shell.DemoShell",
    )

    @Test
    fun `QRY1 §API-02 no forbidden module is on the query test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :query's classpath, so $module is reachable from :query — " +
                    "[QRY1-API-02] forbids it. Check query/build.gradle.kts and the transitive " +
                    "closure of :kernel."
            ) {
                Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader)
            }
        }
    }

    @Test
    fun `QRY1 §API-02 query build file declares no dependency on concord, wire, inspect or a demo module`() {
        val code = buildFileText()

        val declaredProjects = Regex("""project\(\s*"(:[^"]+)"\s*\)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the forbidden-list check below pass against zero declarations.
        declaredProjects shouldContainAll listOf(":kernel", ":oracle")

        val forbidden = declaredProjects.filter { it in setOf(":concord", ":wire", ":inspect") || it.startsWith(":demo") }
        withClue("query/build.gradle.kts declares forbidden module dependencies $forbidden [QRY1-API-02]") {
            forbidden.shouldBeEmpty()
        }
    }

    @Test
    fun `QRY1 §API-01 oracle sits on the test classpath only, never api or implementation`() {
        val code = buildFileText()

        // Configuration-scoped dependency declarations: `<config>(project(":name"))`.
        val declarations = Regex("""(\w+)\(\s*project\(\s*"(:[^"]+)"\s*\)\s*\)""")
            .findAll(code)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        val oracleDeclarations = declarations.filter { (_, module) -> module == ":oracle" }
        // Non-vacuity control: without it, the "never on a non-test configuration" check
        // below would pass just as well against a build file that dropped :oracle entirely.
        withClue(":oracle must appear on query/build.gradle.kts at least once, as testImplementation") {
            oracleDeclarations.isEmpty() shouldBe false
        }

        val nonTestOracle = oracleDeclarations.filter { (config, _) -> !config.startsWith("test") }
        withClue(
            "query/build.gradle.kts declares :oracle on non-test configuration(s) " +
                "${nonTestOracle.map { it.first }} — [QRY1-API-01] requires :oracle test-scope only"
        ) {
            nonTestOracle.shouldBeEmpty()
        }
    }

    @Test
    fun `QRY1 §LOWER-03 query applies no ksp-cell convention plugin`() {
        val code = buildFileText()
        withClue("query/build.gradle.kts applies buildsrc.convention.ksp-cell — [QRY1-LOWER-03] forbids it: :query authors no cell") {
            code.contains("ksp-cell") shouldBe false
        }
    }

    /**
     * A Gradle Test task's working directory is the project directory (the same idiom
     * `civictech.oracle.ModuleDependencyTest` relies on), so this resolves to
     * `query/build.gradle.kts`.
     *
     * Comments are stripped first: this file's own KDoc quotes fully-qualified module
     * references, and a scan that read commentary as declarations would report whatever a
     * comment happened to name.
     */
    private fun buildFileText(): String {
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true
        return buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }
}
