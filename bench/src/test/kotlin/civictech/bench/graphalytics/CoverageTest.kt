package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageTest {

    @Test
    fun `coverage reports LCC and names every blocked algorithm cause`() {
        val table = coverageFor(listOf(validatedLcc()))

        assertEquals(Algorithm.entries.size, table.rows.size)
        assertEquals(Status.validated, table.rows.single { it.algorithm == Algorithm.LCC }.status)

        val rows = table.rows.filterNot { it.algorithm == Algorithm.LCC }
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.status == Status.blocked })
        assertTrue(rows.all { !it.cause.isNullOrBlank() })
        assertTrue(rows.filter { it.algorithm in setOf(Algorithm.PR, Algorithm.CDLP) }
            .all { cause ->
                cause.cause!!.contains("[BB-GRA-16]") &&
                    cause.cause.contains("E5.2") && cause.cause.contains("E5.3") &&
                    !cause.cause.contains("E5.4")
            })
        assertTrue(rows.filter { it.algorithm in setOf(Algorithm.BFS, Algorithm.SSSP, Algorithm.WCC) }
            .all { cause ->
                cause.cause!!.contains("[BB-GRA-16]") &&
                    cause.cause.contains("E5.2") && cause.cause.contains("E5.3") &&
                    cause.cause.contains("E5.4")
            })
    }

    @Test
    fun `failed LCC coverage keeps the named dataset and differing vertices`() {
        val failed = GraRecord.Failed(
            header = header(dataset = "example-undirected"),
            validation = Validation.Failed(
                rule = ComparisonRule.Epsilon,
                epsilonUsed = EPSILON,
                differing = listOf(VertexDiff(2L, 0.5, 0.25), VertexDiff(9L, 0.0, 1.0)),
                missing = setOf(7L),
                extra = setOf(11L),
            ),
        )

        val row = coverageFor(listOf(failed)).rows.single { it.algorithm == Algorithm.LCC }

        assertEquals(Status.failed, row.status)
        assertTrue(row.cause!!.contains("example-undirected"))
        assertTrue(row.cause.contains("differing vertices=[2, 9]"))
        assertTrue(row.cause.contains("missing vertices=[7]"))
        assertTrue(row.cause.contains("extra vertices=[11]"))
        assertFalse(row.cause.isBlank())
    }

    private fun validatedLcc(): GraRecord.Validated = assertInstanceOf(
        GraRecord.Validated::class.java,
        GraRecord.Validated(
            header = header(dataset = "example-directed"),
            load = Figure.Work(10),
            compute = Figure.Work(20),
            validation = Validation.Passed(ComparisonRule.Epsilon, EPSILON),
        ),
    )

    private fun header(dataset: String): RunHeader = RunHeader(
        dataset = DatasetFacts(
            name = dataset,
            declaredVertices = 10,
            declaredEdges = 17,
            sourceCommit = "7b8bde76cf7aab5e90b25ecd4b38829e2f98b292",
        ),
        algorithm = Algorithm.LCC,
        parameters = emptyMap(),
        drive = Drive.SIM,
        scheduler = "SimulationController",
        loadMode = LoadModeLabel("volatile", "per-message"),
        env = RunEnvironment(
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
        ),
        iterations = Iterations.NonIterative,
        retainedState = RetainedState(
            edgeRows = 0,
            neighbourRows = 0,
            candidateRows = 0,
            triangleRows = 0,
            foldKeys = emptyMap(),
            growthAcrossIterations = GrowthNote.NotApplicableNonIterative,
        ),
    )
}
