package civictech.bench.tpch

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageTest {

    @Test
    fun `coverage has one row for every query and blocked causes for the nine absent queries`() {
        val table = coverageFor(listOf(verified(TpchQuery.Q01)))

        assertEquals(22, table.rows.size)
        assertEquals(Status.verified, table.rows.single { it.query == TpchQuery.Q01 }.status)

        val blocked = table.rows.filter { it.tier == TpchTier.BLOCKED_2 }
        assertEquals(9, blocked.size)
        assertTrue(blocked.all { it.status == Status.blocked })
        assertTrue(blocked.all { !it.cause.isNullOrBlank() })
        assertTrue(blocked.single { it.query == TpchQuery.Q21 }.cause!!.contains("EXISTS/NOT EXISTS"))
        assertTrue(blocked.all { it.cause!!.contains("[BB-TPCH-14]") })

        val rendered = table.render()
        assertTrue(rendered.lines().first() == "| Query | Tier | Route | Status | Cause |")
        assertTrue(rendered.contains("| Q02 | BLOCKED_2 | hand | blocked |"))
    }

    @Test
    fun `mixed verified and failed records preserve the failure cause`() {
        val failure = TpchRecord.Failed(
            header = header(TpchQuery.Q01, Drive.REAL),
            batchIndex = 3,
            differingRows = listOf(RowDiff(listOf(9L), "sum_qty", 1L, 2L)),
        )

        val row = coverageFor(listOf(verified(TpchQuery.Q01), failure))
            .rows.single { it.query == TpchQuery.Q01 }

        assertEquals(Status.failed, row.status)
        assertEquals("hand", row.route)
        assertTrue(row.cause!!.contains("batch 3"))
        assertTrue(row.cause.contains("sum_qty"))
    }

    @Test
    fun `coverage table refuses wrong row count duplicates and missing causes`() {
        val rows = TpchQuery.entries.map { query ->
            CoverageRow(query, query.tier, "hand", Status.verified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.dropLast(1) + rows.first())
        }
        // 23 rows covering every query once plus a duplicate: only the row-count check refuses it.
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows + rows.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.map { row ->
                if (row.query == TpchQuery.Q01) row.copy(status = Status.failed) else row
            })
        }
    }

    @Test
    fun `a missing hand query is unverified rather than blocked`() {
        val row = coverageFor(emptyList()).rows.single { it.query == TpchQuery.Q01 }

        assertEquals(Status.unverified, row.status)
        assertEquals("no run recorded", row.cause)
        assertFalse(row.cause.isNullOrBlank())
    }

    private fun verified(query: TpchQuery): TpchRecord.Verified = TpchRecord.Verified(
        header = header(query, Drive.SIM),
        load = Figure.Work(1),
        rf1 = emptyList(),
        rf2 = emptyList(),
        finalDrift = Comparison.Equal,
    )

    private fun header(query: TpchQuery, drive: Drive): RunHeader = RunHeader(
        seed = 7,
        sf = 0.001,
        query = query,
        refreshPlan = RefreshPlan.alternating(seed = 7, sf = 0.001, batches = 2),
        drive = drive,
        scheduler = if (drive == Drive.SIM) "SimulationController" else "VirtualThreadScheduler",
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
        retainedState = RetainedState(emptyMap(), emptyMap(), GrowthNote.Flat),
    )
}

