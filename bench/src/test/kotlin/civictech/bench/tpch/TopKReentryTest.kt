package civictech.bench.tpch

import civictech.bench.tpch.queries.CustOrderLineNation
import civictech.bench.tpch.queries.Q10Builder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TopKReentryTest {

    @Test
    fun `RF2 retraction admits reference rank 21 without reloading base relations`() {
        val params = Q10Params()
        val selected = (1L..64L).firstNotNullOfOrNull { seed ->
            val tables = TpchGenerator.generate(seed = seed, sf = 0.001)
            referenceRows(tables, params).takeIf { rows ->
                rows.size >= 21 && rows[0].orderKey != rows[20].orderKey
            }?.let { rows -> Triple(seed, tables, rows) }
        }
        val (seed, tables, beforeReference) = requireNotNull(selected) {
            "no SF 0.001 seed in 1..64 had 21 Q10 join rows with ranks 1 and 21 from different orders"
        }
        require(beforeReference[0].orderKey != beforeReference[20].orderKey) {
            "rank 1 and rank 21 must come from different orders"
        }

        TpchRig.open(drive = Drive.SIM, seed = seed).use { rig ->
            rig.beginPhase()
            val base = BaseRelations.spawn(rig)
            base.load(tables)
            val load = assertInstanceOf(Figure.Work::class.java, rig.completePhase("load"))

            rig.beginPhase()
            val maintained = Q10Builder(params).attach(rig.host.managementInlet, base)
            rig.completePhase("attach")

            assertEquals(beforeReference.take(20), maintained.topRows())
            val deletedOrderKey = beforeReference.first().orderKey
            val formerRank21 = beforeReference[20]

            rig.beginPhase()
            base.apply(RefreshBatch.Rf2(listOf(deletedOrderKey)))
            val rf2 = assertInstanceOf(Figure.Work::class.java, rig.completePhase("rf2-rank-1"))

            val afterReference = referenceRows(base.logicalTables.snapshot(), params).take(20)
            assertEquals(afterReference, maintained.topRows())
            assertTrue(formerRank21 in maintained.topRows(), "former rank 21 did not re-enter")

            // Measured on Darwin/arm64 with selected seed 1: LOAD=8,648 steps, RF2=7 steps.
            // The tenfold gap is the BS-4 evidence that the delete did not re-ingest base relations.
            assertTrue(rf2.steps * 10L < load.steps) {
                "RF2 work ${rf2.steps} was not below one tenth of LOAD work ${load.steps}"
            }
        }
    }

    /** Independent four-way join and the exact revenue-desc / row-key-asc ordering. */
    private fun referenceRows(tables: TpchTables, params: Q10Params): List<CustOrderLineNation> {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusMonths(3).toEpochDay().toInt()
        val customers = tables.customer.associateBy(Customer::custKey)
        val nations = tables.nation.associateBy(Nation::nationKey)
        val orders = tables.orders.asSequence()
            .filter { it.orderDate >= params.date && it.orderDate < endDate }
            .associateBy(Orders::orderKey)
        return tables.lineitem.asSequence()
            .filter { it.returnFlag == "R" }
            .mapNotNull { line ->
                val order = orders[line.orderKey] ?: return@mapNotNull null
                val customer = customers[order.custKey] ?: return@mapNotNull null
                val nation = nations[customer.nationKey] ?: return@mapNotNull null
                CustOrderLineNation(
                    custKey = customer.custKey,
                    orderKey = order.orderKey,
                    lineNumber = line.lineNumber,
                    nationKey = nation.nationKey,
                    customerName = customer.name,
                    acctBal = customer.acctBal,
                    phone = customer.phone,
                    nationName = nation.name,
                    address = customer.address,
                    comment = customer.comment,
                    revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                )
            }
            .sortedWith(
                compareByDescending<CustOrderLineNation>(CustOrderLineNation::revenueS4)
                    .thenBy(CustOrderLineNation::orderKey)
                    .thenBy(CustOrderLineNation::lineNumber),
            )
            .toList()
    }
}
