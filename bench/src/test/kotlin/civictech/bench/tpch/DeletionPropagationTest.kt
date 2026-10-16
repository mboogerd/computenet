package civictech.bench.tpch

import civictech.bench.tpch.queries.Q03Builder
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

    /**
     * The run-level test above only proves `Verified`; `TpchRun`'s RF2 scan would
     * pass vacuously if `rowsReferencing` could never see a row. This drives the
     * same graph directly and requires the scan to find the order on every
     * key-carrying Q3 outlet before the delete, and on none after it.
     */
    @Test
    fun `rowsReferencing sees the order on every Q3 outlet before RF2 and on none after`() {
        val params = Q3Params()
        val (seed, tables, orderKey) = requireNotNull((1L..64L).firstNotNullOfOrNull { seed ->
            val tables = TpchGenerator.generate(seed = seed, sf = 0.001)
            findMultiLineQ3Order(tables, params)?.let { Triple(seed, tables, it) }
        }) { "no SF 0.001 seed in 1..64 had a Q3 order with two qualifying lineitems" }

        TpchRig.open(drive = Drive.SIM, seed = seed).use { rig ->
            rig.beginPhase()
            val base = BaseRelations.spawn(rig)
            base.load(tables)
            rig.completePhase("load")
            rig.beginPhase()
            val maintained = Q03Builder().attach(rig.host.managementInlet, base)
            rig.completePhase("attach")

            val before = maintained.rowsReferencing(orderKey).map { it.first }.toSet()
            assertEquals(
                setOf("q03-orders", "q03-customer-orders", "q03-lineitems", "q03-order-lines", "q03-revenue"),
                before,
                "outlets naming order $orderKey before RF2",
            )

            rig.beginPhase()
            base.apply(RefreshBatch.Rf2(listOf(orderKey)))
            rig.completePhase("rf2-1")

            assertEquals(emptyList<Pair<String, Any>>(), maintained.rowsReferencing(orderKey))
            assertEquals(
                Comparison.Equal,
                ViewComparison.compare(
                    TpchReference.evaluate(TpchQuery.Q03, base.logicalTables.snapshot(), params),
                    maintained.read(),
                ),
            )
        }
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
