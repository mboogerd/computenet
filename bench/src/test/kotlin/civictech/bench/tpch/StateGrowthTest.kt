package civictech.bench.tpch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateGrowthTest {

    @Test
    fun `balanced Q3 state returns to its post-LOAD live rows after every pair`() {
        val record = assertInstanceOf(
            TpchRecord.Verified::class.java,
            TpchRun.run(
                query = TpchQuery.Q03,
                seed = 7L,
                sf = 0.001,
                plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 20),
                drive = Drive.SIM,
                env = tpchTestEnvironment(),
                hooks = TpchRunHooks(refreshFactory = ::pairedRefreshes),
            ),
        )

        val series = record.header.stateSeries
        assertEquals(21, series.size)
        val postLoad = series.first().liveRows
        series.drop(2).filterIndexed { index, _ -> index % 2 == 0 }.forEachIndexed { pair, sample ->
            assertEquals(postLoad, sample.liveRows, "balanced pair ${pair + 1}")
        }
        assertEquals(GrowthNote.Flat, record.header.retainedState.growth)
        assertFalse(record.header.retainedState.foldKeys.isEmpty(), "Q3 fold keys were not accounted")
    }

    @Test
    fun `default balanced Q3 reports its population shift before monotone growth`() {
        val record = assertInstanceOf(
            TpchRecord.Verified::class.java,
            TpchRun.run(
                query = TpchQuery.Q03,
                seed = 7L,
                sf = 0.001,
                plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 20),
                drive = Drive.SIM,
                env = tpchTestEnvironment(),
            ),
        )

        val note = assertInstanceOf(
            GrowthNote.NotOutletBalanced::class.java,
            record.header.retainedState.growth,
        )
        val counts = record.header.stateSeries.map { it.liveRows.getValue(note.outlet) }
        val baseline = counts.first()
        val pairCheckpoints = counts.drop(2).filterIndexed { index, _ -> index % 2 == 0 }
        assertTrue(
            pairCheckpoints.any { it != baseline },
            "${note.outlet} returned to its baseline at every pair checkpoint: $counts",
        )
    }

    @Test
    fun `balanced outlet shifts without monotone growth are named separately`() {
        val plan = RefreshPlan.alternating(seed = 17L, sf = 0.001, batches = 4)
        val series = listOf(10L, 12L, 9L, 11L, 10L).map(::retainedState)

        assertEquals(
            GrowthNote.NotOutletBalanced("outlet"),
            StateAccounting.growthNote(series, plan),
        )
    }

    @Test
    fun `insert-only Q3 control grows at least one outlet`() {
        val plan = RefreshPlan(
            seed = 11L,
            sf = 0.001,
            batches = List(20) { RefreshKind.RF1 },
        )
        val record = assertInstanceOf(
            TpchRecord.Verified::class.java,
            TpchRun.run(
                query = TpchQuery.Q03,
                seed = 11L,
                sf = 0.001,
                plan = plan,
                drive = Drive.SIM,
                env = tpchTestEnvironment(),
            ),
        )

        assertInstanceOf(GrowthNote.Growing::class.java, record.header.retainedState.growth)
    }

    private fun pairedRefreshes(plan: RefreshPlan, base: TpchTables): List<RefreshBatch> {
        val logical = LogicalTables(base)
        var inserted = emptyList<Long>()
        return plan.batches.mapIndexed { index, kind ->
            val batch = when (kind) {
                RefreshKind.RF1 -> RefreshStream.generate(
                    plan.copy(seed = plan.seed + index, batches = listOf(RefreshKind.RF1)),
                    logical.snapshot(),
                ).single().also { refresh ->
                    inserted = (refresh as RefreshBatch.Rf1).orders.map(Orders::orderKey).sorted()
                }
                RefreshKind.RF2 -> RefreshBatch.Rf2(inserted).also { inserted = emptyList() }
            }
            logical.apply(batch)
            batch
        }
    }

    private fun retainedState(rows: Long): RetainedState = RetainedState(
        liveRows = mapOf("outlet" to rows),
        foldKeys = emptyMap(),
        growth = GrowthNote.Flat,
    )
}
