package civictech.bench.nexmark

import civictech.bench.graphalytics.LoadModes
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.FileJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class NexmarkBenchmarkParamsTest {

    @Test
    fun `every query scale load mode and policy parameter maps without starting JMH`(@TempDir root: Path) {
        assertEquals(listOf("Q1", "Q2", "Q3", "Q7", "Q8"), NexmarkBenchmarkParameters.queryNames)
        NexmarkBenchmarkParameters.queryNames.forEach { name ->
            assertEquals(Tier.EXPRESSIBLE, NexmarkBenchmarkParameters.query(name).tier)
        }

        assertEquals(listOf(10_000, 100_000), NexmarkBenchmarkParameters.scales.map {
            NexmarkBenchmarkParameters.scale(it).events
        })
        NexmarkBenchmarkParameters.scales.forEach { value ->
            assertEquals(NexmarkScale(value.toInt(), 1_000), NexmarkBenchmarkParameters.scale(value))
        }

        NexmarkBenchmarkParameters.loadModes.forEach { mode ->
            val (journal, dispatchBatch) = LoadModes.resolve(mode, root.resolve(mode))
            when (mode) {
                LoadModes.VOLATILE -> {
                    assertNull(journal)
                    assertEquals(1, dispatchBatch)
                }

                LoadModes.WAL_BOUND -> {
                    assertTrue(journal is FileJournal)
                    assertEquals(1, dispatchBatch)
                }

                LoadModes.BATCHED_8 -> {
                    assertTrue(journal is BatchedFileJournal)
                    assertEquals(1, dispatchBatch)
                }

                LoadModes.BATCHED_INGRESS_64 -> {
                    assertNull(journal)
                    assertEquals(64, dispatchBatch)
                }
            }
        }

        assertNull(NexmarkBenchmarkParameters.policy("none"))
        assertEquals(LatenessPolicy(5_000), NexmarkBenchmarkParameters.policy("lateness-5000"))
        assertNull(NexmarkBenchmarkParameters.policyFor(NexmarkQuery.Q7, "none"))
        assertEquals(
            LatenessPolicy(5_000),
            NexmarkBenchmarkParameters.policyFor(NexmarkQuery.Q7, "lateness-5000"),
        )
    }

    @Test
    fun `blocked queries and policies on non-windowed queries are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkBenchmarkParameters.query("Q4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkBenchmarkParameters.query("Q5")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkBenchmarkParameters.policyFor(NexmarkQuery.Q1, "lateness-5000")
        }
    }

    @Test
    fun `JMH parameter literals stay equal to the default-lane catalog`() {
        val root = File(requireNotNull(System.getProperty("computenet.repo.root")))
        val source = root.resolve(
            "bench/src/jmh/kotlin/civictech/bench/nexmark/NexmarkBenchmark.kt",
        ).readText()

        assertEquals(
            NexmarkBenchmarkParameters.queryNames,
            paramValues(source, "query"),
        )
        assertEquals(
            NexmarkBenchmarkParameters.scales,
            paramValues(source, "scale"),
        )
        assertEquals(
            NexmarkBenchmarkParameters.loadModes,
            paramValues(source, "loadMode"),
        )
        assertEquals(
            NexmarkBenchmarkParameters.policies,
            paramValues(source, "policy"),
        )
    }

    @Test
    fun `JMH exposes exactly insert and retract bodies`() {
        val list = File(requireNotNull(System.getProperty("civictech.bench.jmhBenchmarkList")))
        val records = list.readLines().filter {
            "civictech.bench.nexmark.NexmarkBenchmark" in it
        }
        assertEquals(2, records.size, records.joinToString("\n").take(2_000))
        assertTrue(records.any { " insert " in it }, records.joinToString("\n").take(2_000))
        assertTrue(records.any { " retract " in it }, records.joinToString("\n").take(2_000))
    }

    private fun paramValues(source: String, field: String): List<String> {
        val match = requireNotNull(
            Regex(
                """@Param\(([^)]*)\)\s+@JvmField\s+var $field\b""",
            ).find(source),
        ) { "could not find @Param for $field" }
        return Regex("\"([^\"]+)\"")
            .findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
    }
}
