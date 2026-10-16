package civictech.bench.tpch

import civictech.bench.tpch.queries.GroupCollectorCell
import civictech.bench.tpch.queries.MaintainedQuery
import civictech.bench.tpch.queries.SetRowsCollectorCell
import civictech.cell.Timestamp
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class IncrementalityTest {

    @Test
    fun `Q3 and Q5 live collector volumes stay within k eight`() {
        listOf(TpchQuery.Q03, TpchQuery.Q05).forEach { query ->
            val counterSnapshots = mutableListOf<Map<String, Long>>()
            val productionCounters = TpchRunHooks().emissionCounters
            val plan = RefreshPlan(
                seed = 7L,
                sf = 0.001,
                batches = listOf(RefreshKind.RF2),
            )
            val record = assertInstanceOf(
                TpchRecord.Verified::class.java,
                TpchRun.run(
                    query = query,
                    seed = 7L,
                    plan = plan,
                    drive = Drive.SIM,
                    env = tpchTestEnvironment(),
                    hooks = TpchRunHooks(
                        refreshFactory = { _, base ->
                            listOf(RefreshBatch.Rf2(base.orders.map(Orders::orderKey).sorted()))
                        },
                        emissionCounters = { maintained ->
                            productionCounters(maintained).also(counterSnapshots::add)
                        },
                    ),
                ),
            )
            val measurement = assertInstanceOf(
                Incrementality.Measured::class.java,
                record.header.incremental,
            )
            assertTrue(measurement.incremental, query.name)
            assertNull(measurement.operator, query.name)
            assertEquals(INCREMENTALITY_K, measurement.k)
            assertTrue(measurement.note.contains("not a proof of operator internals"))
            assertEquals(2, counterSnapshots.size, "${query.name} counter sample count")
            val emitted = IncrementalityAccounting.delta(counterSnapshots[0], counterSnapshots[1])
            assertTrue(emitted.values.any { it > 0L }, "${query.name} counters did not advance: $emitted")
        }
    }

    @Test
    fun `collectors count applied delta elements rather than delta arrivals`() {
        val setCollector = SetRowsCollectorCell<String>()
        val source = UUID.randomUUID()
        setCollector.inlet.call.propagate(
            SetDelta(
                adds = mapOf(
                    "a" to setOf(Timestamp(source, 1)),
                    "b" to setOf(Timestamp(source, 2)),
                ),
                dels = mapOf("missing" to setOf(Timestamp(source, 3))),
            ),
        )

        val groupCollector = GroupCollectorCell<String>()
        groupCollector.inlet.call.propagate(
            MapDelta(
                puts = mapOf("a" to 1L, "b" to 2L),
                removals = setOf("missing"),
            ),
        )

        assertEquals(3L, setCollector.appliedElements)
        assertEquals(3L, groupCollector.appliedElements)
    }

    @Test
    fun `a re-emitting collector names itself as the violating outlet`() {
        val fake = ReEmittingQuery(live = 100)
        val before = fake.counters()
        fake.deleteOneAndReemitWholeSet()
        val emitted = IncrementalityAccounting.delta(before, fake.counters())
        val measurement = assertInstanceOf(
            Incrementality.Measured::class.java,
            IncrementalityAccounting.measure(
                listOf(EmissionObservation(2, RefreshKind.RF2, emitted, upstreamRowsDeleted = 1)),
            ),
        )

        assertFalse(measurement.incremental)
        assertEquals("fake-reemit", measurement.operator)
    }

    private class ReEmittingQuery(private var live: Long) : MaintainedQuery {
        override val query: TpchQuery = TpchQuery.Q03
        private var emitted = 0L

        fun counters(): Map<String, Long> = mapOf("fake-reemit" to emitted)

        fun deleteOneAndReemitWholeSet() {
            live--
            emitted += live
        }

        override fun read(): ViewResult = ViewResult(listOf("value"), emptyMap())
        override fun liveRows(): Map<String, Long> = mapOf("fake-reemit" to live)
        override fun emissionCounters(): Map<String, Long> = counters()
        override fun rowsReferencing(orderKey: Long): List<Pair<String, Any>> = emptyList()
    }
}
