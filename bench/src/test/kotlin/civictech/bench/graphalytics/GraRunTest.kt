package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.cell.Owned
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.host.ManagedHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GraRunTest {

    @Test
    fun `run drives exactly one load phase and one compute phase`() {
        val phases = mutableListOf<RunPhase>()

        val record = validated(
            GraRun.runLoaded(
                dataset = Datasets.EXAMPLE_DIRECTED,
                loaded = GraphLoader.load(Datasets.EXAMPLE_DIRECTED),
                drive = Drive.SIM,
                seed = 1L,
                env = environment(),
                hooks = GraRunHooks(phaseQuiesced = phases::add),
            ),
        )

        assertEquals(listOf(RunPhase.LOAD, RunPhase.COMPUTE), phases)
        assertInstanceOf(Figure.Work::class.java, record.load)
        assertInstanceOf(Figure.Work::class.java, record.compute)
    }

    @Test
    fun `sim compute figure counts work for both published example datasets`() {
        Datasets.all().forEach { dataset ->
            val record = validated(
                GraRun.run(
                    dataset = dataset,
                    drive = Drive.SIM,
                    seed = 1L,
                    env = environment(),
                ),
            )

            val compute = assertInstanceOf(Figure.Work::class.java, record.compute)
            assertTrue(
                compute.steps > 0,
                "${dataset.name} SIM compute must count the scheduler work that produced its validated LCC result",
            )
        }
    }

    @Test
    fun `real drive records separate durations`() {
        val record = validated(
            GraRun.run(
                dataset = Datasets.EXAMPLE_DIRECTED,
                drive = Drive.REAL,
                env = environment(),
            ),
        )

        assertInstanceOf(Figure.Duration::class.java, record.load)
        assertInstanceOf(Figure.Duration::class.java, record.compute)
    }

    @Test
    fun `post-load fidelity gate refuses a truncated relation before operators spawn`() {
        val complete = GraphLoader.load(Datasets.EXAMPLE_DIRECTED)
        val truncated = complete.copy(edges = complete.edges.dropLast(1))
        var hostConstructions = 0

        val failure = assertThrows(LoadFidelityException::class.java) {
            GraRun.runLoaded(
                dataset = Datasets.EXAMPLE_DIRECTED,
                loaded = truncated,
                drive = Drive.SIM,
                seed = 1L,
                env = environment(),
                hooks = GraRunHooks(
                    hostFactory = { scheduler, journal, dispatchBatch ->
                        hostConstructions++
                        // One slot admits the load relation and makes any premature
                        // operator spawn fail before it can obscure the fidelity error.
                        ManagedHost(
                            scheduler = scheduler,
                            journal = journal,
                            dispatchBatch = dispatchBatch,
                            quota = 1,
                        )
                    },
                ),
            )
        }

        assertTrue(failure.message!!.contains("post-load fidelity gate"))
        assertEquals(1, hostConstructions)
    }

    @Test
    fun `load mode labels come from each journal guarantee and dispatch batch`(@TempDir root: Path) {
        val cases = listOf(
            Triple<Journal?, Int, LoadModeLabel>(null, 1, LoadModeLabel("volatile", "per-message")),
            Triple(
                FileJournal(root.resolve("sync.journal").toFile()),
                1,
                LoadModeLabel("WAL-bound", "per-message"),
            ),
            Triple(
                BatchedFileJournal(root.resolve("batch.journal").toFile(), syncEvery = 8),
                8,
                LoadModeLabel("batched(syncEvery=8)", "batched(8)"),
            ),
            Triple(
                InMemoryJournal(),
                1,
                LoadModeLabel("in-memory", "per-message"),
            ),
        )

        cases.forEachIndexed { index, (journal, dispatchBatch, expected) ->
            val record = validated(
                GraRun.run(
                    dataset = Datasets.EXAMPLE_DIRECTED,
                    drive = Drive.SIM,
                    seed = index.toLong(),
                    journal = journal,
                    dispatchBatch = dispatchBatch,
                    env = environment(),
                ),
            )
            assertEquals(expected, record.header.loadMode)
        }
    }

    @Test
    fun `header copies every raw property and reads the fixture source commit`() {
        val record = validated(
            GraRun.run(
                dataset = Datasets.EXAMPLE_DIRECTED,
                drive = Drive.SIM,
                seed = 1L,
                env = environment(),
            ),
        )

        assertEquals(
            mapOf(
                "graph.example-directed.algorithms" to "bfs, cdlp, lcc, pr, sssp, wcc",
                "graph.example-directed.bfs.source-vertex" to "1",
                "graph.example-directed.cdlp.max-iterations" to "2",
                "graph.example-directed.directed" to "true",
                "graph.example-directed.edge-file" to "example-directed.e",
                "graph.example-directed.edge-properties.names" to "weight",
                "graph.example-directed.edge-properties.types" to "real",
                "graph.example-directed.meta.edges" to "17",
                "graph.example-directed.meta.vertices" to "10",
                "graph.example-directed.pr.damping-factor" to "0.85",
                "graph.example-directed.pr.num-iterations" to "2",
                "graph.example-directed.sssp.source-vertex" to "1",
                "graph.example-directed.sssp.weight-property" to "weight",
                "graph.example-directed.vertex-file" to "example-directed.v",
            ),
            record.header.parameters,
        )
        assertEquals(
            "7b8bde76cf7aab5e90b25ecd4b38829e2f98b292",
            record.header.dataset.sourceCommit,
        )
    }

    @Test
    fun `record carries structural retained state and non-iterative accounting`() {
        val record = validated(
            GraRun.run(
                dataset = Datasets.EXAMPLE_DIRECTED,
                drive = Drive.SIM,
                seed = 1L,
                env = environment(),
            ),
        )
        val state = record.header.retainedState

        assertEquals(17L, state.edgeRows)
        assertTrue(state.neighbourRows > 0)
        assertTrue(state.candidateRows >= state.triangleRows)
        assertTrue(state.foldKeys.getValue("degree") > 0)
        assertTrue(state.foldKeys.getValue("triangles") > 0)
        assertEquals(GrowthNote.NotApplicableNonIterative, state.growthAcrossIterations)
        assertEquals(Iterations.NonIterative, record.header.iterations)
    }

    @Test
    fun `exclusive payload is refused before host construction`() {
        var hostConstructions = 0
        val failure = assertThrows(IllegalArgumentException::class.java) {
            GraRun.runLoaded(
                dataset = Datasets.EXAMPLE_DIRECTED,
                loaded = GraphLoader.load(Datasets.EXAMPLE_DIRECTED),
                drive = Drive.SIM,
                seed = 1L,
                env = environment(),
                vertexPayload = Owned::class.java,
                hooks = GraRunHooks(
                    hostFactory = { scheduler, journal, dispatchBatch ->
                        hostConstructions++
                        ManagedHost(
                            scheduler = scheduler,
                            journal = journal,
                            dispatchBatch = dispatchBatch,
                        )
                    },
                ),
            )
        }

        assertTrue(failure.message!!.contains("[BB-GRA-28]"))
        assertEquals(0, hostConstructions)
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
