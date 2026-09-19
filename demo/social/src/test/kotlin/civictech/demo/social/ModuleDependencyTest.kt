package civictech.demo.social

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * `[SOC1-MOD-01]`: `:demo:social` declares implementation deps on exactly `:kernel` and
 * `:demo:shell`, and a testImplementation dep on `:testkit`, and no other project dep — no
 * `:wire`, `:inspect`, `:query`, `:oracle`, `:concord`, and no `ksp-cell` plugin (feature
 * `computenet-jo2jk` decided design). Modelled on
 * `oracle/src/test/kotlin/civictech/oracle/ModuleDependencyTest.kt` (observed at 237d8b99).
 *
 * Two independent checks, because each catches something the other cannot:
 *
 * - the **classpath** check catches a forbidden module arriving *transitively*, through a
 *   dependency of `:kernel` or `:demo:shell` that nobody edited here;
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
        ":wire" to "civictech.wire.WsTransport",
        ":inspect" to "civictech.inspect.InspectorServer",
        ":query" to "civictech.query.schema.Catalog",
        ":oracle" to "civictech.oracle.bind.OperatorCatalog",
        ":concord" to "civictech.concord.generator.ScenarioGenerator",
    )

    @Test
    fun `SOC1-MOD-01 no forbidden module is on the demo-social test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :demo:social's classpath, so $module is reachable from " +
                    ":demo:social — SOC1-MOD-01 forbids it. Check demo/social/build.gradle.kts " +
                    "and the transitive closure of :kernel and :demo:shell."
            ) {
                Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader)
            }
        }
    }

    @Test
    fun `SOC1-MOD-01 demo-social build file declares exactly kernel, demo-shell and testkit`() {
        // A Gradle Test task's working directory is the project directory, so this resolves
        // to demo/social/build.gradle.kts.
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true

        // Comments are stripped first, the same way the oracle test does: a scan that read
        // commentary (this file's own KDoc, say) as declarations would report whatever a
        // comment happened to name.
        val code = buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        val declared = Regex("""project\(\s*"(:[^"]+)"\s*\)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()

        // Non-vacuity control: without it, a regex that stopped matching anything at all
        // would make the exact-set check below pass vacuously.
        declared shouldContainAll listOf(":kernel", ":demo:shell", ":testkit")

        withClue("demo/social/build.gradle.kts declares project deps $declared, expected exactly [:kernel, :demo:shell, :testkit]") {
            declared.toSet() shouldBe setOf(":kernel", ":demo:shell", ":testkit")
        }

        withClue("demo/social/build.gradle.kts must not apply the ksp-cell convention plugin") {
            code.contains("ksp-cell") shouldBe false
        }
    }
}
