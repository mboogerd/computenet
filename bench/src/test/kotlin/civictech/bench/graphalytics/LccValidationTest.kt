package civictech.bench.graphalytics

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class LccValidationTest {

    @Test
    fun directedExampleValidatesUnderSim() {
        assertValidated(Datasets.EXAMPLE_DIRECTED, Drive.SIM, expectedVertices = 10)
    }

    @Test
    fun undirectedExampleValidatesUnderSim() {
        assertValidated(Datasets.EXAMPLE_UNDIRECTED, Drive.SIM, expectedVertices = 9)
    }

    @Test
    fun directedExampleValidatesUnderReal() {
        assertValidated(Datasets.EXAMPLE_DIRECTED, Drive.REAL, expectedVertices = 10)
    }

    @Test
    fun undirectedExampleValidatesUnderReal() {
        assertValidated(Datasets.EXAMPLE_UNDIRECTED, Drive.REAL, expectedVertices = 9)
    }

    private fun assertValidated(dataset: DatasetRef, drive: Drive, expectedVertices: Int) {
        val record = assertInstanceOf(
            GraRecord.Validated::class.java,
            GraRun.run(
                dataset = dataset,
                drive = drive,
                seed = 1L,
                env = environment(),
            ),
        )

        assertEquals(Status.validated, record.status)
        assertEquals(Algorithm.LCC, record.header.algorithm)
        assertEquals(drive, record.header.drive)
        assertEquals(expectedVertices, Datasets.referenceOutput(dataset, Algorithm.LCC).size)
        assertEquals(ComparisonRule.Epsilon, record.validation.rule)
        assertEquals(EPSILON, record.validation.epsilonUsed)
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
