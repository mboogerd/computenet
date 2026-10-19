package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraRecordTest {

    @Test
    fun `run header carries the exact specification and all run context fields`() {
        val header = header()

        assertEquals(SPEC_VERSION, header.specVersion)
        assertEquals("example-directed", header.dataset.name)
        assertEquals(Algorithm.LCC, header.algorithm)
        assertEquals(mapOf("pr.damping-factor" to "0.85"), header.parameters)
        assertEquals(Drive.SIM, header.drive)
        assertEquals("SimulationController", header.scheduler)
        assertEquals("volatile", header.loadMode.durability)
        assertEquals("per-message", header.loadMode.ingress)
        assertEquals(RunEnvironment::class, header.env::class)
        assertEquals(Iterations.NonIterative, header.iterations)
        assertEquals(3L, header.retainedState.edgeRows)
    }

    @Test
    fun `figures keep simulation work separate from real duration`() {
        val work = Figure.Work(42)
        val duration = Figure.Duration(nanos = 100, dispersionNanos = 4)

        assertEquals(42L, work.steps)
        assertEquals(100L, duration.nanos)
        assertEquals(4L, duration.dispersionNanos)
        assertThrows(IllegalArgumentException::class.java) {
            Figure.Duration(nanos = 1, dispersionNanos = -1)
        }
    }

    @Test
    fun `record variants keep validation and figures in disjoint types`() {
        val passed = Validation.Passed(ComparisonRule.Epsilon, EPSILON)
        val failed = Validation.Failed(
            rule = ComparisonRule.Epsilon,
            epsilonUsed = EPSILON,
            differing = listOf(VertexDiff(1L, 0.1, 0.2)),
            missing = emptySet(),
            extra = emptySet(),
        )
        val validated = GraRecord.Validated(header(), Figure.Work(1), Figure.Work(2), passed)
        val failedRecord = GraRecord.Failed(header(), failed)
        val measured = GraRecord.MeasurementOnly(header(), Figure.Work(1), Figure.Work(2))

        assertEquals(Status.validated, validated.status)
        assertEquals(Status.failed, failedRecord.status)
        assertEquals(Status.measurement_only, measured.status)
        assertEquals(
            setOf("header", "load", "compute", "validation"),
            GraRecord.Validated::class.java.declaredFields.map { it.name }
                .filterNot { it == "status" }.toSet(),
        )
        assertFalse(GraRecord.Failed::class.java.declaredFields.any { it.name == "load" })
        assertFalse(GraRecord.Failed::class.java.declaredFields.any { it.name == "compute" })
        assertFalse(GraRecord.MeasurementOnly::class.java.declaredFields.any { it.name == "validation" })
        assertEquals(
            setOf("header", "load", "compute"),
            GraRecord.MeasurementOnly::class.java.declaredFields.map { it.name }.toSet(),
        )
        assertFalse(
            GraRecord.Failed::class.java.declaredFields.any { Figure::class.java.isAssignableFrom(it.type) },
        )
    }

    @Test
    fun `unreportable records variance instead of timing mean`() {
        val record = GraRecord.Unreportable(header(), variance = 0.25)

        assertEquals(Status.unreportable, record.status)
        assertEquals(0.25, record.variance)
        assertEquals(VARIANCE_THRESHOLD, record.threshold)
        assertThrows(IllegalArgumentException::class.java) {
            GraRecord.Unreportable(header(), variance = VARIANCE_THRESHOLD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GraRecord.Unreportable(header(), variance = VARIANCE_THRESHOLD / 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GraRecord.Unreportable(header(), variance = 0.25, threshold = 0.20)
        }
    }

    @Test
    fun `every record carries the exact specification version`() {
        val passed = Validation.Passed(ComparisonRule.Epsilon, EPSILON)
        val failed = Validation.Failed(
            rule = ComparisonRule.Epsilon,
            epsilonUsed = EPSILON,
            differing = emptyList(),
            missing = emptySet(),
            extra = emptySet(),
        )
        val records: List<GraRecord> = listOf(
            GraRecord.Validated(header(), Figure.Work(1), Figure.Work(2), passed),
            GraRecord.Failed(header(), failed),
            GraRecord.MeasurementOnly(header(), Figure.Work(1), Figure.Work(2)),
            GraRecord.Unreportable(header(), variance = 0.25),
            GraRecord.Blocked(Algorithm.PR, "requires E5.2 and E5.3"),
            GraRecord.NotExpressible(Algorithm.CDLP, "unsupported graph shape"),
        )

        assertEquals(6, records.size)
        assertTrue(records.all { it.specVersion == SPEC_VERSION })
    }

    @Test
    fun `blocked and not expressible records retain explicit causes`() {
        val blocked = GraRecord.Blocked(Algorithm.PR, "requires E5.2 and E5.3")
        val notExpressible = GraRecord.NotExpressible(Algorithm.CDLP, "unsupported graph shape")

        assertEquals(Status.blocked, blocked.status)
        assertEquals(Status.not_expressible, notExpressible.status)
        assertEquals("requires E5.2 and E5.3", blocked.cause)
        assertEquals("unsupported graph shape", notExpressible.cause)
    }

    @Test
    fun `coverage table requires one row for every algorithm and causes for nonvalidated rows`() {
        val rows = Algorithm.entries.map { algorithm ->
            CoverageRow(
                algorithm = algorithm,
                status = if (algorithm == Algorithm.LCC) Status.validated else Status.blocked,
                cause = if (algorithm == Algorithm.LCC) null else "not implemented in this slice",
            )
        }
        val table = CoverageTable(rows)
        val rendered = table.render()

        assertEquals(Algorithm.entries.size + 2, rendered.trimEnd().lines().size)
        assertTrue(rendered.contains("| LCC | validated |  |"))
        assertTrue(rendered.contains("| BFS | blocked | not implemented in this slice |"))
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.map { row ->
                if (row.status == Status.blocked) row.copy(cause = "") else row
            })
        }
    }

    private fun header(): RunHeader = RunHeader(
        dataset = DatasetFacts(
            name = "example-directed",
            declaredVertices = 10,
            declaredEdges = 17,
            sourceCommit = "7b8bde76cf7aab5e90b25ecd4b38829e2f98b292",
        ),
        algorithm = Algorithm.LCC,
        parameters = mapOf("pr.damping-factor" to "0.85"),
        drive = Drive.SIM,
        scheduler = "SimulationController",
        loadMode = LoadModeLabel("volatile", "per-message"),
        env = RunEnvironment(
            jvmVendor = "test-vendor",
            jvmVersion = "21",
            heapSettings = "-Xmx2g",
            cpuModel = "test-cpu",
            coreCount = 4,
            os = "test-os",
            jmhMode = "AverageTime",
            forkCount = 1,
            warmupIterations = 1,
            measurementIterations = 1,
            harnessCommitSha = "abcdef1",
        ),
        iterations = Iterations.NonIterative,
        retainedState = RetainedState(
            edgeRows = 3,
            neighbourRows = 4,
            candidateRows = 5,
            triangleRows = 6,
            foldKeys = mapOf("degree" to 3, "triangles" to 2),
            growthAcrossIterations = GrowthNote.NotApplicableNonIterative,
        ),
    )
}
