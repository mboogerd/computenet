package civictech.bench.tpch

import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.FileJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class TpchBenchmarkParamsTest {

    @Test
    fun `every query scale and load mode parameter maps without starting JMH`(@TempDir root: Path) {
        assertEquals(13, TpchBenchmarkParameters.queryNames.size)
        TpchBenchmarkParameters.queryNames.forEach { name ->
            assertEquals(TpchTier.HAND_1, TpchBenchmarkParameters.query(name).tier)
        }
        assertEquals(listOf(0.001, 0.01), TpchBenchmarkParameters.scaleFactors.map {
            TpchBenchmarkParameters.scaleFactor(it)
        })

        TpchBenchmarkParameters.loadModes.forEach { mode ->
            val (journal, dispatchBatch) = LoadModes.resolve(mode, root.resolve(mode))
            when (mode) {
                LoadModes.VOLATILE -> assertNull(journal)
                LoadModes.WAL_BOUND -> assertTrue(journal is FileJournal)
                LoadModes.BATCHED_8 -> assertTrue(journal is BatchedFileJournal)
                LoadModes.BATCHED_INGRESS_64 -> {
                    assertNull(journal)
                    assertEquals(64, dispatchBatch)
                }
            }
        }
    }

    @Test
    fun `JMH exposes exactly rf1 and rf2 refresh bodies`() {
        val list = File(requireNotNull(System.getProperty("civictech.bench.jmhBenchmarkList")))
        val records = list.readLines().filter { "civictech.bench.tpch.TpchBenchmark" in it }
        assertEquals(2, records.size, records.joinToString("\n").take(2_000))
        assertTrue(records.any { "rf1" in it }, records.joinToString("\n").take(2_000))
        assertTrue(records.any { "rf2" in it }, records.joinToString("\n").take(2_000))
    }

    @Test
    fun `timed refresh bodies exclude retained-state capture`() {
        val root = File(requireNotNull(System.getProperty("computenet.repo.root")))
        val source = root.resolve(
            "bench/src/jmh/kotlin/civictech/bench/tpch/TpchBenchmark.kt",
        ).readText()

        listOf("realRf1", "realRf2").forEach { method ->
            val body = requireNotNull(
                Regex(
                    """fun $method\([^)]*\) \{(.*?)^    \}""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE),
                ).find(source),
            ) { "could not find $method body in TpchBenchmark.kt" }.groupValues[1]

            assertFalse(
                "retainedState(" in body,
                "$method must time only one refresh batch and its quiescence drain",
            )
            assertEquals(1, Regex("""session\.applyPrepared\(""").findAll(body).count(), method)
            assertTrue("rows.record(refreshed)" in body, "$method must record the refresh result")
            assertTrue(
                "blackhole.consume(refreshed)" in body,
                "$method must consume the refresh result without materialising retained state",
            )
        }
    }
}
