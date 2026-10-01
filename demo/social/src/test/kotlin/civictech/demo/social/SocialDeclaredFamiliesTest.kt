package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the GraphSpec-backed keyed-family declaration used by [SnbPipeline]. */
class SocialDeclaredFamiliesTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    @Test
    fun `social main declares keyed families without direct KeyedCells construction`() {
        val sourceRoot = File(repoRoot(), "demo/social/src/main")
        assertTrue(sourceRoot.isDirectory, "missing demo/social/src/main: ${sourceRoot.path}")

        val constructor = Regex("""KeyedCells<[^>\n]*>\s*\(""")
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (constructor.containsMatchIn(line)) "${file.relativeTo(repoRoot()).path}:${index + 1}: $line" else null
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "demo/social main must declare keyed families as GraphSpec content; direct constructors:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `SOC1-SCHEMA-05 declared person family keeps per-key refs deterministic across hosts`() {
        val controller1 = SimulationController(seed = 1)
        val controller2 = SimulationController(seed = 2)
        val pipeline1 = SnbPipeline.build(ManagedHost(scheduler = controller1.scheduler()), journalDir = null)
        val pipeline2 = SnbPipeline.build(ManagedHost(scheduler = controller2.scheduler()), journalDir = null)

        val expected = CellRef(UUID.nameUUIDFromBytes("snb-person:42".toByteArray()))
        val ref1 = pipeline1.families.person.getOrSpawn(42L).ref
        val ref2 = pipeline2.families.person.getOrSpawn(42L).ref

        assertEquals(expected, ref1)
        assertEquals(ref1, ref2)
    }
}
