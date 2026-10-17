package civictech.bench.nexmark

import civictech.cell.host.ManagedHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexmarkRunProtocolTest {
    private val scale = NexmarkScale(events = 2_000, eventsPerSecond = 50)

    @Test
    fun `SIM Q1 reports collector deltas and ordered insert and retract phases`() {
        val phases = mutableListOf<String>()
        val snapshots = mutableListOf<Map<String, Long>>()
        val record = NexmarkRun.run(
            query = NexmarkQuery.Q1,
            seed = 7L,
            scale = scale,
            drive = Drive.SIM,
            env = nexmarkTestEnvironment(),
            hooks = NexmarkRunHooks(
                rigHooks = NexmarkRigHooks(phaseQuiesced = phases::add),
                emissionCounters = { query -> query.emissionCounters().also(snapshots::add) },
            ),
        )

        val verified = assertInstanceOf(NexmarkRecord.Verified::class.java, record)
        assertEquals(SIM_SCHEDULER, verified.header.scheduler)
        assertEquals(20, verified.insert.size)
        assertTrue(verified.retract.size >= 9)
        assertTrue((verified.insert + verified.retract).all { batch ->
            val figure = batch.figure as Figure.Work
            figure.unit == WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS
        })

        val expectedWork = snapshots.chunked(2).map { (before, after) ->
            (before.keys + after.keys).sumOf { outlet ->
                (after[outlet] ?: 0L) - (before[outlet] ?: 0L)
            }
        }
        val reportedWork = (verified.insert + verified.retract).map {
            (it.figure as Figure.Work).steps
        }
        assertEquals(expectedWork, reportedWork)

        val expectedPhases = buildList {
            add("attach")
            (1..20).forEach { add("insert-$it") }
            verified.retract.indices.forEach { add("retract-${it + 1}") }
        }
        assertEquals(expectedPhases, phases)
        assertFalse(phases.any { it.startsWith("clock-") })
    }

    @Test
    fun `a policy on non-windowed Q1 is refused before a host exists`() {
        var hostConstructions = 0
        val failure = assertThrows(IllegalArgumentException::class.java) {
            NexmarkRun.run(
                query = NexmarkQuery.Q1,
                seed = 7L,
                scale = scale,
                policy = LatenessPolicy(5_000L),
                drive = Drive.SIM,
                env = nexmarkTestEnvironment(),
                hooks = NexmarkRunHooks(
                    rigHooks = NexmarkRigHooks(
                        hostFactory = { scheduler, journal, dispatchBatch ->
                            hostConstructions++
                            ManagedHost(
                                scheduler = scheduler,
                                journal = journal,
                                dispatchBatch = dispatchBatch,
                            )
                        },
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("not windowed"))
        assertEquals(0, hostConstructions)
    }

    @Test
    fun `a batch-three mismatch stops without later phases or figures`() {
        val phases = mutableListOf<String>()
        val comparisons = mutableListOf<Int>()
        var evaluation = 0
        val record = NexmarkRun.run(
            query = NexmarkQuery.Q1,
            seed = 7L,
            scale = scale,
            drive = Drive.SIM,
            env = nexmarkTestEnvironment(),
            hooks = NexmarkRunHooks(
                referenceFactory = {
                    NexmarkReferenceEvaluator { query, batches, policy ->
                        NexmarkReference.evaluate(query, batches, policy).let { answer ->
                            if (++evaluation == 3) answer.withShiftedFirstLong() else answer
                        }
                    }
                },
                comparisonObserved = { index, _ -> comparisons += index },
                rigHooks = NexmarkRigHooks(phaseQuiesced = phases::add),
            ),
        )

        val failed = assertInstanceOf(NexmarkRecord.Failed::class.java, record)
        assertEquals(3, failed.batchIndex)
        assertFalse(failed.differingRows.isEmpty())
        assertEquals(listOf(1, 2, 3), comparisons)
        assertEquals(listOf("attach", "insert-1", "insert-2", "insert-3"), phases)
        assertFalse(NexmarkRecord.Failed::class.java.declaredFields.any { field ->
            Figure::class.java.isAssignableFrom(field.type)
        })
    }

    @Test
    fun `generation on the measured path is unreportable under SIM and REAL`() {
        listOf(Drive.SIM, Drive.REAL).forEach { drive ->
            val record = NexmarkRun.run(
                query = NexmarkQuery.Q1,
                seed = 7L,
                scale = scale,
                drive = drive,
                generationPlacement = GenerationPlacement.INSIDE,
                env = nexmarkTestEnvironment(),
            )
            val unreportable = assertInstanceOf(NexmarkRecord.Unreportable::class.java, record)
            val cause = assertInstanceOf(
                NexmarkRecord.UnreportableCause.GenerationOnMeasuredPath::class.java,
                unreportable.cause,
            )
            assertTrue(cause.share > 0.0 && cause.share <= 1.0)
        }
        assertFalse(NexmarkRecord.Unreportable::class.java.declaredFields.any { field ->
            Figure::class.java.isAssignableFrom(field.type)
        })
    }

    @Test
    fun `summarise refuses a two-times SIM work spread`() {
        fun run(multiplier: Long): NexmarkRecord.Verified {
            val record = NexmarkRun.run(
                query = NexmarkQuery.Q1,
                seed = 7L,
                scale = scale,
                drive = Drive.SIM,
                env = nexmarkTestEnvironment(),
                hooks = NexmarkRunHooks(
                    emissionCounters = { query ->
                        query.emissionCounters().mapValues { (_, value) -> value * multiplier }
                    },
                ),
            )
            return assertInstanceOf(NexmarkRecord.Verified::class.java, record)
        }

        val summary = assertInstanceOf(
            NexmarkRecord.Unreportable::class.java,
            summarise(listOf(run(1L), run(2L))),
        )
        assertInstanceOf(NexmarkRecord.UnreportableCause.Variance::class.java, summary.cause)
    }

    private fun ReferenceAnswer.withShiftedFirstLong(): ReferenceAnswer {
        val first = result.rows.first()
        val shifted = first.toMutableList()
        val index = shifted.indexOfFirst { it is Long }
        check(index >= 0) { "wrong-reference injection needs a Long column" }
        shifted[index] = (shifted[index] as Long) + 1L
        val rows = result.rows.toMutableSet()
        rows.remove(first)
        rows.add(shifted)
        return copy(
            result = result.copy(rows = rows),
        )
    }
}
