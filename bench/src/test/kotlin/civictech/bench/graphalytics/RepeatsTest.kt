package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [BB-GRA-27]: run-to-run variance over the threshold yields `unreportable`. */
class RepeatsTest {

    @Test
    fun `variance above the threshold yields unreportable with the variance in place of a mean`() {
        // load 100, 120: mean 110, spread 20 -> 20 / 110 > 0.10.
        val record = summarise(listOf(validated(100, 200), validated(120, 200)))

        val unreportable = assertInstanceOf(GraRecord.Unreportable::class.java, record)
        assertEquals(Status.unreportable, unreportable.status)
        assertEquals(20.0 / 110.0, unreportable.variance)
    }

    @Test
    fun `variance at or below the threshold yields the mean with the observed spread`() {
        // load 95, 105: mean 100, spread 10 -> exactly 0.10, which is not above it.
        val atThreshold = summarise(listOf(validated(95, 200), validated(105, 200)))
        val validated = assertInstanceOf(GraRecord.Validated::class.java, atThreshold)
        assertEquals(Figure.Duration(nanos = 100, dispersionNanos = 10), validated.load)
        assertEquals(Figure.Duration(nanos = 200, dispersionNanos = 0), validated.compute)

        // compute 1000, 1050, 1100: mean 1050, spread 100 -> 0.095.
        val three = summarise(listOf(validated(10, 1000), validated(10, 1050), validated(10, 1100)))
        assertEquals(
            Figure.Duration(nanos = 1050, dispersionNanos = 100),
            assertInstanceOf(GraRecord.Validated::class.java, three).compute,
        )
    }

    @Test
    fun `a failed run dominates and carries no timing figure`() {
        val failed = GraRecord.Failed(
            header(),
            Validation.Failed(ComparisonRule.Epsilon, EPSILON, emptyList(), setOf(1L), emptySet()),
        )

        assertSame(failed, summarise(listOf(validated(100, 200), failed)))
    }

    @Test
    fun `one run has no run-to-run variance and mixed configurations are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            summarise(listOf(validated(100, 200)))
        }
        val otherDataset = GraRecord.Validated(
            header = header().copy(
                dataset = header().dataset.copy(name = "example-undirected"),
            ),
            load = Figure.Duration(100),
            compute = Figure.Duration(200),
            validation = Validation.Passed(ComparisonRule.Epsilon, EPSILON),
        )
        assertThrows(IllegalArgumentException::class.java) {
            summarise(listOf(validated(100, 200), otherDataset))
        }
    }

    @Test
    fun `coverage does not hide an unreportable dataset behind a validated one`() {
        val unreportable = summarise(listOf(validated(100, 200), validated(120, 200)))

        val row = coverageFor(listOf(unreportable, validated(100, 200)))
            .rows.single { it.algorithm == Algorithm.LCC }

        assertEquals(Status.unreportable, row.status)
        assertTrue(row.cause!!.contains("exceeds the reportability threshold"))
    }

    @Test
    fun `a single REAL run records no dispersion and repeated REAL runs record an observed one`() {
        val runs = (1..2).map {
            GraRun.run(dataset = Datasets.EXAMPLE_DIRECTED, drive = Drive.REAL, env = env())
        }
        runs.forEach { run ->
            val record = assertInstanceOf(GraRecord.Validated::class.java, run)
            assertNull(assertInstanceOf(Figure.Duration::class.java, record.load).dispersionNanos)
            assertNull(assertInstanceOf(Figure.Duration::class.java, record.compute).dispersionNanos)
        }

        when (val summary = summarise(runs)) {
            is GraRecord.Validated -> {
                assertNotNull((summary.load as Figure.Duration).dispersionNanos)
                assertNotNull((summary.compute as Figure.Duration).dispersionNanos)
            }
            is GraRecord.Unreportable -> assertTrue(summary.variance > VARIANCE_THRESHOLD)
            else -> throw AssertionError("unexpected summary $summary")
        }
    }

    private fun validated(loadNanos: Long, computeNanos: Long) = GraRecord.Validated(
        header = header(),
        load = Figure.Duration(loadNanos),
        compute = Figure.Duration(computeNanos),
        validation = Validation.Passed(ComparisonRule.Epsilon, EPSILON),
    )

    private fun header(): RunHeader = RunHeader(
        dataset = DatasetFacts(
            name = "example-directed",
            declaredVertices = 10,
            declaredEdges = 17,
            sourceCommit = "7b8bde76cf7aab5e90b25ecd4b38829e2f98b292",
        ),
        algorithm = Algorithm.LCC,
        parameters = emptyMap(),
        drive = Drive.REAL,
        scheduler = "VirtualThreadScheduler",
        loadMode = LoadModeLabel("volatile", "per-message"),
        env = env(),
        iterations = Iterations.NonIterative,
        retainedState = RetainedState(
            edgeRows = 17,
            neighbourRows = 0,
            candidateRows = 0,
            triangleRows = 0,
            foldKeys = emptyMap(),
            growthAcrossIterations = GrowthNote.NotApplicableNonIterative,
        ),
    )

    private fun env(): RunEnvironment = RunEnvironment(
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
