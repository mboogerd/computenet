package civictech.bench.tpch

import civictech.bench.tpch.queries.MaintainedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncrementalityTest {

    @Test
    fun `Q3 and Q5 collector volumes stay within k eight`() {
        listOf(TpchQuery.Q03, TpchQuery.Q05).forEach { query ->
            val measurement = assertInstanceOf(
                Incrementality.Measured::class.java,
                IncrementalityAccounting.measure(
                    listOf(
                        EmissionObservation(
                            batchIndex = 2,
                            kind = RefreshKind.RF2,
                            emittedElements = mapOf("${query.name.lowercase()}-outlet" to 32L),
                            upstreamRowsDeleted = 4L,
                        ),
                    ),
                ),
            )
            assertTrue(measurement.incremental, query.name)
            assertNull(measurement.operator, query.name)
            assertEquals(INCREMENTALITY_K, measurement.k)
            assertTrue(measurement.note.contains("not a proof of operator internals"))
        }
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
        override fun rowsReferencing(orderKey: Long): List<Pair<String, Any>> = emptyList()
    }
}
