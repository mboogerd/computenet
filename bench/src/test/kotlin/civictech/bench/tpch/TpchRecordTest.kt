package civictech.bench.tpch

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TpchRecordTest {

    @Test
    fun `run header refuses alternate specification and framing`() {
        assertThrows(IllegalArgumentException::class.java) {
            header(specRevision = "TPC-H 2.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            header(framing = "audited TPC-H execution")
        }
        assertEquals(SPEC_REVISION, header().specRevision)
        assertEquals(FRAMING, header().framing)
        assertEquals("test-sha", header().harnessSha)
    }

    @Test
    fun `verified keeps RF1 and RF2 figures in separate lists`() {
        val record = TpchRecord.Verified(
            header = header(),
            load = Figure.Work(10),
            rf1 = listOf(BatchFigure(RefreshKind.RF1, 0, Figure.Work(4))),
            rf2 = listOf(BatchFigure(RefreshKind.RF2, 1, Figure.Work(5))),
            finalDrift = Comparison.Equal,
        )

        assertEquals(Status.verified, record.status)
        assertEquals(listOf(RefreshKind.RF1), record.rf1.map { it.kind })
        assertEquals(listOf(RefreshKind.RF2), record.rf2.map { it.kind })
        assertFalse(TpchRecord.Verified::class.java.declaredFields.any { it.name == "total" })
    }

    @Test
    fun `failed and unverified declare no Figure property`() {
        val failed = TpchRecord.Failed(header(), 2, emptyList())
        val unverified = TpchRecord.Unverified(header(), "comparison was skipped")

        assertEquals(Status.failed, failed.status)
        assertEquals(Status.unverified, unverified.status)
        assertFalse(TpchRecord.Failed::class.java.declaredFields.any { Figure::class.java.isAssignableFrom(it.type) })
        assertFalse(
            TpchRecord.Unverified::class.java.declaredFields.any { Figure::class.java.isAssignableFrom(it.type) },
        )
    }

    @Test
    fun `unreportable requires variance strictly above ten percent`() {
        val record = TpchRecord.Unreportable(header(), variance = 0.25)

        assertEquals(Status.unreportable, record.status)
        assertEquals(VARIANCE_THRESHOLD, record.threshold)
        assertThrows(IllegalArgumentException::class.java) {
            TpchRecord.Unreportable(header(), variance = VARIANCE_THRESHOLD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TpchRecord.Unreportable(header(), variance = VARIANCE_THRESHOLD / 2)
        }
    }

    @Test
    fun `blocked causes may not be blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            TpchRecord.Blocked(TpchQuery.Q02, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TpchRecord.NotExpressible(TpchQuery.Q02, " ")
        }
    }

    @Test
    fun `summarise marks a repeated batch spread of twenty five percent unreportable`() {
        val runs = listOf(100L, 125L, 100L).map { steps ->
            TpchRecord.Verified(
                header = header(),
                load = Figure.Work(10),
                rf1 = listOf(BatchFigure(RefreshKind.RF1, 0, Figure.Work(steps))),
                rf2 = emptyList(),
                finalDrift = Comparison.Equal,
            )
        }

        val summary = summarise(runs)
        assertTrue(summary is TpchRecord.Unreportable)
        assertTrue((summary as TpchRecord.Unreportable).variance > VARIANCE_THRESHOLD)
    }

    private fun header(
        specRevision: String = SPEC_REVISION,
        framing: String = FRAMING,
        query: TpchQuery = TpchQuery.Q01,
    ): RunHeader = RunHeader(
        seed = 7,
        sf = 0.001,
        query = query,
        refreshPlan = RefreshPlan.alternating(seed = 7, sf = 0.001, batches = 2),
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
        retainedState = RetainedState(
            liveRows = mapOf("lineitem" to 0),
            foldKeys = mapOf("q01" to 0),
            growth = GrowthNote.Flat,
        ),
        specRevision = specRevision,
        framing = framing,
    )
}

