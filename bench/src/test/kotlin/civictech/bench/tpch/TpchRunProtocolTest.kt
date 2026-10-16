package civictech.bench.tpch

import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TpchRunProtocolTest {

    @Test
    fun `SIM work reports per-query collector-applied elements`() {
        data class Evidence(
            val query: TpchQuery,
            val rf1Work: List<Long>,
            val rf2Work: List<Long>,
            val emittedPerBatch: List<Long>,
        )

        fun evidence(query: TpchQuery): Evidence {
            val snapshots = mutableListOf<Map<String, Long>>()
            val record = TpchRun.run(
                query = query,
                seed = 7L,
                plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 4),
                drive = Drive.SIM,
                env = tpchTestEnvironment(),
                hooks = TpchRunHooks(
                    emissionCounters = { maintained ->
                        maintained.emissionCounters().also(snapshots::add)
                    },
                ),
            )
            val verified = assertInstanceOf(TpchRecord.Verified::class.java, record)
            val emittedPerBatch = snapshots.chunked(2).map { (before, after) ->
                after.keys.sumOf { outlet -> after.getValue(outlet) - before.getValue(outlet) }
            }
            val reportedPerBatch = (verified.rf1 + verified.rf2)
                .sortedBy(BatchFigure::index)
                .map { (it.figure as Figure.Work).steps }
            assertEquals(emittedPerBatch, reportedPerBatch, query.name)
            return Evidence(
                query = query,
                rf1Work = verified.rf1.map { (it.figure as Figure.Work).steps },
                rf2Work = verified.rf2.map { (it.figure as Figure.Work).steps },
                emittedPerBatch = emittedPerBatch,
            )
        }

        val evidence = listOf(evidence(TpchQuery.Q06), evidence(TpchQuery.Q09))
        assertNotEquals(
            evidence[0].rf1Work,
            evidence[1].rf1Work,
            "Q06 and Q09 must report different RF1 query-maintenance work: $evidence",
        )
        println("SIM_WORK_EVIDENCE=$evidence")
    }

    @Test
    fun `one quiescence occurs per phase and batch figures retain their kind`() {
        val phases = mutableListOf<String>()
        val record = TpchRun.run(
            query = TpchQuery.Q06,
            seed = 7L,
            plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 4),
            drive = Drive.SIM,
            env = tpchTestEnvironment(),
            hooks = TpchRunHooks(
                rigHooks = TpchRigHooks(phaseQuiesced = phases::add),
            ),
        )

        val verified = assertInstanceOf(TpchRecord.Verified::class.java, record)
        assertEquals(listOf("load", "attach", "rf1-1", "rf2-2", "rf1-3", "rf2-4"), phases)
        assertEquals(listOf(1, 3), verified.rf1.map(BatchFigure::index))
        assertEquals(listOf(2, 4), verified.rf2.map(BatchFigure::index))
        assertTrue(verified.rf1.all { it.kind == RefreshKind.RF1 })
        assertTrue(verified.rf2.all { it.kind == RefreshKind.RF2 })
    }

    @Test
    fun `a batch-three mismatch stops without later phases or cost fields`() {
        val phases = mutableListOf<String>()
        val comparisons = mutableListOf<Int>()
        var evaluationIndex = 0
        val record = TpchRun.run(
            query = TpchQuery.Q06,
            seed = 7L,
            plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 6),
            drive = Drive.SIM,
            env = tpchTestEnvironment(),
            hooks = TpchRunHooks(
                referenceFactory = {
                    TpchReferenceEvaluator { query, tables, params ->
                        val result = TpchReference.evaluate(query, tables, params)
                        val current = evaluationIndex++
                        if (current == 3) result.plusOne() else result
                    }
                },
                comparisonObserved = { index, _ -> comparisons += index },
                rigHooks = TpchRigHooks(phaseQuiesced = phases::add),
            ),
        )

        val failed = assertInstanceOf(TpchRecord.Failed::class.java, record)
        assertEquals(3, failed.batchIndex)
        assertFalse(failed.differingRows.isEmpty())
        assertEquals(listOf(0, 1, 2, 3), comparisons)
        assertEquals(listOf("load", "attach", "rf1-1", "rf2-2", "rf1-3"), phases)
        val failedFields = TpchRecord.Failed::class.java.declaredFields.map { it.name }
        assertFalse("rf1" in failedFields)
        assertFalse("rf2" in failedFields)
    }

    private fun ViewResult.plusOne(): ViewResult {
        var changed = false
        val shifted = rows.entries.associateTo(linkedMapOf()) { (key, values) ->
            val copy = values.copyOf()
            if (!changed) {
                copy[0] = Math.addExact(copy[0], 1L)
                changed = true
            }
            key to copy
        }
        check(changed) { "wrong-reference injection needs at least one row" }
        return copy(rows = shifted)
    }
}

internal fun tpchTestEnvironment(): RunEnvironment = RunEnvironment(
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
