package civictech.bench.tpch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeletionPropagationTest {

    @Test
    fun `RF2 retracts every Q3 row contributed by a multi-line order`() {
        val params = Q3Params()
        val selected = (1L..64L).firstNotNullOfOrNull { seed ->
            val tables = TpchGenerator.generate(seed = seed, sf = 0.001)
            findMultiLineQ3Order(tables, params)?.let { orderKey -> Triple(seed, tables, orderKey) }
        }
        val (seed, selectedTables, orderKey) = requireNotNull(selected) {
            "no SF 0.001 seed in 1..64 had a Q3 order with two qualifying lineitems"
        }
        assertTrue(selectedTables.lineitem.count {
            it.orderKey == orderKey && it.shipDate > params.date
        } >= 2)

        val plan = RefreshPlan(
            seed = seed,
            sf = 0.001,
            batches = listOf(RefreshKind.RF2),
        )
        val comparisons = mutableListOf<Comparison>()
        val record = TpchRun.run(
            query = TpchQuery.Q03,
            seed = seed,
            sf = 0.001,
            plan = plan,
            drive = Drive.SIM,
            env = tpchTestEnvironment(),
            hooks = TpchRunHooks(
                refreshFactory = { _, generatedBase ->
                    assertEquals(selectedTables, generatedBase)
                    listOf(RefreshBatch.Rf2(listOf(orderKey)))
                },
                comparisonObserved = { _, comparison -> comparisons += comparison },
            ),
        )

        assertInstanceOf(TpchRecord.Verified::class.java, record)
        assertTrue(comparisons.all { it == Comparison.Equal })
    }

    private fun findMultiLineQ3Order(tables: TpchTables, params: Q3Params): Long? {
        val customers = tables.customer.asSequence()
            .filter { it.mktSegment == params.segment }
            .map(Customer::custKey)
            .toSet()
        val qualifyingOrders = tables.orders.asSequence()
            .filter { it.custKey in customers && it.orderDate < params.date }
            .map(Orders::orderKey)
            .toSet()
        return tables.lineitem.asSequence()
            .filter { it.orderKey in qualifyingOrders && it.shipDate > params.date }
            .groupingBy(Lineitem::orderKey)
            .eachCount()
            .entries
            .firstOrNull { it.value >= 2 }
            ?.key
    }
}
