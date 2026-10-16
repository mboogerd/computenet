package civictech.bench.tpch

import civictech.bench.micro.HeapProbe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The expensive BS-6 half: 400 batches of 50 orders = 10k RF1 + 10k RF2 refreshes. */
@Tag("bench")
class StateGrowthBenchTest {

    @Test
    fun `balanced Q3 retained heap is classified against G-42`() {
        HeapProbe.requireCollectableHeap()
        val samples = (500..20_000 step 500).map { target ->
            var flat = false
            val bytes = HeapProbe.retainedBytes {
                val session = TpchRun.openRefreshSession(
                    query = TpchQuery.Q03,
                    seed = 20261016L,
                    sf = 0.001,
                    drive = Drive.SIM,
                    ordersPerRefresh = 50,
                )
                val baseline = session.retainedState().liveRows
                repeat(target / 50) { batch ->
                    val kind = if (batch % 2 == 0) RefreshKind.RF1 else RefreshKind.RF2
                    session.prepare(kind, restorePrevious = false)
                    session.applyPrepared(kind)
                }
                flat = session.retainedState().liveRows == baseline
                session
            }
            HeapGrowthSample(target, bytes, flat)
        }
        val finding = heapGrowthFinding(samples)

        println("BS-6 retained heap samples (refreshes, bytes, liveRowsFlat):")
        samples.forEach { println("${it.refreshes}, ${it.retainedBytes}, ${it.liveRowsFlat}") }
        println(
            "BS-6 ${finding.verdict}: growth=${finding.growth}, " +
                "slope=${finding.slopeBytesPerRefresh}, " +
                "firstWindowSpread=${finding.firstWindowSpreadBytes}",
        )
        assertTrue(samples.all(HeapGrowthSample::liveRowsFlat), "balanced stream changed live rows")
    }
}
