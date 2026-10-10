package civictech.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The bootstrap knows its bindings; the bindings and kernel never know the bootstrap. */
class ModuleDependencyTest {

    @Test
    fun `runtime declares exactly the composition-root project dependencies`() {
        val buildFile = File("build.gradle.kts")
        assertTrue(buildFile.isFile, "missing ${buildFile.absolutePath}")

        assertEquals(
            setOf(":kernel", ":wire", ":iroh", ":inspect", ":economy", ":identity", ":testkit"),
            projectReferences(buildFile).toSet(),
            ":runtime must be the one composition root over the kernel bindings",
        )
    }

    @Test
    fun `kernel and every runtime binding remain independent of runtime`() {
        val root = repoRoot()
        val sentinels = mapOf(
            "kernel" to ":nature",
            "wire" to ":kernel",
            "iroh" to ":kernel",
            "inspect" to ":kernel",
            "economy" to ":kernel",
            "identity" to ":kernel",
        )

        sentinels.forEach { (module, sentinel) ->
            val buildFile = File(root, "$module/build.gradle.kts")
            assertTrue(buildFile.isFile, "missing ${buildFile.absolutePath}")
            val references = projectReferences(buildFile)
            assertTrue(sentinel in references, "parsed no $sentinel dependency from $module/build.gradle.kts")
            assertFalse(
                references.any { it == ":runtime" || it.startsWith(":runtime:") },
                "$module/build.gradle.kts depends on :runtime; the dependency direction is reversed",
            )
        }
    }

    private fun projectReferences(file: File): List<String> =
        Regex("""project\(\s*"(:[^"]+)"\s*\)""")
            .findAll(
                file.readText()
                    .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("""//[^\n]*"""), ""),
            )
            .map { it.groupValues[1] }
            .toList()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not find settings.gradle.kts from ${System.getProperty("user.dir")}")
        }
        return dir
    }
}
