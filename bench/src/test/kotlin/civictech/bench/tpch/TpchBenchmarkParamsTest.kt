package civictech.bench.tpch

import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.FileJournal
import org.junit.jupiter.api.Assertions.assertEquals
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
}
