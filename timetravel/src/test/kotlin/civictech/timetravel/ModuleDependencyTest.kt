package civictech.timetravel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * `[TTD1-53]`: `:timetravel` depends (main scope) only on `:kernel` and
 * kotlinx-serialization, and `:kernel` must never depend on `:timetravel`. Modeled on
 * `oracle/src/test/kotlin/civictech/oracle/ModuleDependencyTest.kt` — deliberately no
 * ArchUnit, per the feature's design field. Three independent checks:
 *
 * - the **classpath** half catches a forbidden module arriving *transitively*;
 * - the **:timetravel build-file** half catches a forbidden module declared but not
 *   yet reachable by any class this test happens to name;
 * - the **:kernel build-file** half catches the wrong-direction edge — `:kernel`
 *   growing a dependency back on `:timetravel`.
 */
class ModuleDependencyTest {

    /**
     * One public top-level type per forbidden module, used as that module's fingerprint on
     * the classpath. Each is `public` and top-level, so it is loadable by name whenever its
     * module is present.
     */
    private val fingerprints = mapOf(
        ":inspect" to "civictech.inspect.InspectorServer",
        ":wire" to "civictech.wire.WsTransport",
        ":concord" to "civictech.concord.generator.ScenarioGenerator",
        ":identity" to "civictech.identity.PeerIdentity",
        ":iroh" to "civictech.iroh.SidecarProtocol",
        ":oracle" to "civictech.oracle.bind.OperatorCatalog",
        ":query" to "civictech.query.schema.Catalog",
        ":demo:shell" to "civictech.demo.shell.DemoShell",
    )

    @Test
    fun `TTD1-53 no forbidden module is on the timetravel test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :timetravel's classpath, so $module is reachable from " +
                    ":timetravel — [TTD1-53] forbids it. Check timetravel/build.gradle.kts " +
                    "and the transitive closure of :kernel and :testkit."
            ) {
                Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader)
            }
        }
    }

    @Test
    fun `TTD1-53 kernel is reachable from timetravel (non-vacuity control)`() {
        // Without this, a classpath broken in some unrelated way would make every
        // assertThrows above pass vacuously.
        Class.forName(
            "civictech.cell.durability.FileJournal",
            false,
            ModuleDependencyTest::class.java.classLoader
        )
    }

    @Test
    fun `TTD1-53 timetravel build file declares only kernel and testkit, and no forbidden module`() {
        // A Gradle Test task's working directory is the project directory, so this
        // resolves to timetravel/build.gradle.kts.
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true

        val declared = declaredProjectDeps(buildFile)

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the forbidden-list check below pass against zero declarations.
        declared shouldContainAll listOf(":kernel", ":testkit")

        val forbidden = declared.filter { it in fingerprints.keys || it.startsWith(":demo") }
        withClue("timetravel/build.gradle.kts declares forbidden module dependencies $forbidden [TTD1-53]") {
            forbidden.shouldBeEmpty()
        }
    }

    @Test
    fun `TTD1-53 kernel build file does not declare a dependency on timetravel`() {
        val kernelBuildFile = File("../kernel/build.gradle.kts")
        kernelBuildFile.isFile shouldBe true

        val declared = declaredProjectDeps(kernelBuildFile)

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the forbidden-entry check below pass against zero declarations.
        declared shouldContainAll listOf(":testkit")

        withClue("kernel/build.gradle.kts declares a dependency on :timetravel [TTD1-53]") {
            declared.shouldNotContainTimetravel()
        }
    }

    private fun List<String>.shouldNotContainTimetravel() {
        (":timetravel" in this) shouldBe false
    }

    /**
     * Comments are stripped first: this file's own KDoc quotes project-dependency
     * declarations as prose, and a scan that read commentary as declarations would
     * report whatever a comment happened to name.
     */
    private fun declaredProjectDeps(buildFile: File): List<String> {
        val code = buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        return Regex("""project\(\s*"(:[^"]+)"\s*\)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()
    }
}
