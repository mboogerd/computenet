package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.cell.durability.FileJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LccRecoveryTest {

    @Test
    fun `directed LCC remains validated after journal recovery`(@TempDir root: Path) {
        assertRecovered(Datasets.EXAMPLE_DIRECTED, root)
    }

    @Test
    fun `undirected LCC remains validated after journal recovery`(@TempDir root: Path) {
        assertRecovered(Datasets.EXAMPLE_UNDIRECTED, root)
    }

    @Test
    fun `empty journal cannot masquerade as recovered input`(@TempDir root: Path) {
        val journalDir = Files.createDirectories(root.resolve("empty"))

        val failure = assertThrows(LoadFidelityException::class.java) {
            GraRun.recoverAndCompute(
                dataset = Datasets.EXAMPLE_DIRECTED,
                journalDir = journalDir,
                drive = Drive.SIM,
                seed = 1L,
                env = environment(),
            )
        }

        assertTrue(failure.message!!.contains("post-load fidelity gate"))
        assertTrue(failure.message!!.contains("loaded=0"))
    }

    private fun assertRecovered(dataset: DatasetRef, root: Path) {
        val journalDir = Files.createDirectories(root.resolve(dataset.name))
        val original = validated(
            GraRun.run(
                dataset = dataset,
                drive = Drive.SIM,
                seed = 1L,
                journal = FileJournal(GraRun.journalPath(dataset, journalDir).toFile()),
                env = environment(),
            ),
        )

        val recovered = validated(
            GraRun.recoverAndCompute(
                dataset = dataset,
                journalDir = journalDir,
                drive = Drive.SIM,
                seed = 2L,
                env = environment(),
            ),
        )

        assertEquals(Status.validated, recovered.status)
        assertEquals(null, original.header.recovered)
        assertEquals(original.header.retainedState.edgeRows, recovered.header.retainedState.edgeRows)
        assertEquals("WAL-bound", recovered.header.loadMode.durability)
        assertEquals(true, recovered.header.recovered)
        assertInstanceOf(Figure.Work::class.java, recovered.load)
        assertInstanceOf(Figure.Work::class.java, recovered.compute)
    }

    private fun validated(record: GraRecord): GraRecord.Validated =
        assertInstanceOf(GraRecord.Validated::class.java, record)

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
