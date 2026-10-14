package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.FileJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LccBenchmarkParamsTest {

    @Test
    fun `load modes resolve to the declared journal and ingress mapping`(@TempDir root: Path) {
        val resolved = LoadModes.names.associateWith { name ->
            LoadModes.resolve(name, root.resolve(name))
        }

        assertNull(resolved.getValue(LoadModes.VOLATILE).first)
        assertEquals(1, resolved.getValue(LoadModes.VOLATILE).second)

        val wal = assertInstanceOf(FileJournal::class.java, resolved.getValue(LoadModes.WAL_BOUND).first)
        assertEquals(DurabilityClass.SYNCHRONOUS, wal.durability)
        assertEquals(1, resolved.getValue(LoadModes.WAL_BOUND).second)

        val batched = assertInstanceOf(
            BatchedFileJournal::class.java,
            resolved.getValue(LoadModes.BATCHED_8).first,
        )
        assertEquals(DurabilityClass.BATCHED, batched.durability)
        assertEquals(8, batched.syncEvery)
        assertEquals(1, resolved.getValue(LoadModes.BATCHED_8).second)

        assertNull(resolved.getValue(LoadModes.BATCHED_INGRESS_64).first)
        assertEquals(64, resolved.getValue(LoadModes.BATCHED_INGRESS_64).second)
    }

    @Test
    fun `unknown load mode is refused`(@TempDir root: Path) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            LoadModes.resolve("not-a-mode", root)
        }

        assertEquals(true, failure.message!!.contains("Unknown Graphalytics load mode"))
    }

    @Test
    fun `GraRun records the label for every benchmark load mode`(@TempDir root: Path) {
        val expected = mapOf(
            LoadModes.VOLATILE to LoadModeLabel("volatile", "per-message"),
            LoadModes.WAL_BOUND to LoadModeLabel("WAL-bound", "per-message"),
            LoadModes.BATCHED_8 to LoadModeLabel("batched(syncEvery=8)", "per-message"),
            LoadModes.BATCHED_INGRESS_64 to LoadModeLabel("volatile", "batched(64)"),
        )

        expected.forEach { (name, label) ->
            val (journal, dispatchBatch) = LoadModes.resolve(name, root.resolve(name))
            val record = assertInstanceOf(
                GraRecord.Validated::class.java,
                GraRun.run(
                    dataset = Datasets.EXAMPLE_DIRECTED,
                    drive = Drive.SIM,
                    seed = name.hashCode().toLong(),
                    journal = journal,
                    dispatchBatch = dispatchBatch,
                    env = environment(),
                ),
            )

            assertEquals(label, record.header.loadMode, name)
        }
    }

    private fun environment(): RunEnvironment = RunEnvironment(
        jvmVendor = "test-vendor",
        jvmVersion = "21",
        heapSettings = "test-heap",
        cpuModel = "test-cpu",
        coreCount = 1,
        os = "test-os",
        jmhMode = "test",
        forkCount = 1,
        warmupIterations = 1,
        measurementIterations = 1,
        harnessCommitSha = "test-sha",
    )
}
