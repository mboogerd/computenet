package civictech.compmodel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * `:composition-model` depends on no other project at all. It models the DESIGN of
 * `doc/integration/2026-10-04-composition/`, independently of the kernel, so that it can
 * later serve as an oracle for the implementation — the same independence argument
 * `:oracle` makes for its reference ops (`oracle/.../ModuleDependencyTest.kt` is the
 * pattern). Two checks, because each catches what the other cannot:
 *
 * - the **classpath** check catches a project arriving on the test runtime classpath by any
 *   route (`civictech.cell.Cell` is the kernel's fingerprint);
 * - the **build-file** check names the offending declaration.
 */
class ModuleDependencyTest {

    private val fingerprints = mapOf(
        ":kernel" to "civictech.cell.Cell",
        ":testkit" to "civictech.testkit.SimWorld",
        ":oracle" to "civictech.oracle.bind.OperatorCatalog",
    )

    @Test
    fun `no other project is on the composition-model test runtime classpath`() {
        fingerprints.forEach { (module, fqn) ->
            assertThrows<ClassNotFoundException>(
                "$fqn loaded from :composition-model's classpath, so $module is reachable; " +
                    "the model must stay independent of the implementation.",
            ) { Class.forName(fqn, false, ModuleDependencyTest::class.java.classLoader) }
        }
    }

    @Test
    fun `composition-model build file declares no project dependency`() {
        val buildFile = File("build.gradle.kts")
        buildFile.isFile shouldBe true
        val code = buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        // Non-vacuity control: the scan must see the dependencies block it is checking.
        code.contains("dependencies") shouldBe true
        val declared = Regex("""project\(\s*"(:[^"]+)"\s*\)""").findAll(code).map { it.groupValues[1] }.toList()
        withClue("composition-model/build.gradle.kts declares project dependencies $declared") { declared.shouldBeEmpty() }
    }
}
