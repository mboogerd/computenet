package civictech.bench.tpch

import civictech.bench.tpch.queries.Q16Builder
import civictech.bench.tpch.Customer
import civictech.bench.tpch.Nation
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Supplier
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Default-lane BS-2 validation for the second four maintained hand graphs. */
class TierOneCValidationTest {

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("queriesAndDrives")
    fun `twenty alternating refreshes equal the independent reference`(
        query: TpchQuery,
        drive: Drive,
    ) {
        val seed = if (query == TpchQuery.Q07) Q7_VALIDATION_SEED else 7L
        val comparisons = mutableListOf<Pair<Int, Comparison>>()
        val record = TpchRun.run(
            query = query,
            seed = seed,
            sf = 0.001,
            plan = RefreshPlan.alternating(seed = seed, sf = 0.001, batches = 20),
            drive = drive,
            env = tpchTestEnvironment(),
            hooks = TpchRunHooks(
                comparisonObserved = { index, comparison -> comparisons += index to comparison },
            ),
        )

        assertTrue(record is TpchRecord.Verified) { "record=$record" }
        val verified = record as TpchRecord.Verified
        assertEquals(22, comparisons.size, "attach + 20 batches + final drift")
        assertTrue(comparisons.all { it.second == Comparison.Equal }) {
            "${query.name}/$drive differed at ${comparisons.filter { it.second != Comparison.Equal }.map { it.first }}"
        }
        val orderedFigures = (verified.rf1 + verified.rf2).sortedBy(BatchFigure::index)
        assertEquals(RefreshKind.RF1, orderedFigures.first().kind)
        assertEquals((1..20).toList(), orderedFigures.map(BatchFigure::index))
    }

    @Test
    fun `Q7 nation pair keeps a row in the selected validation population`() {
        val seed = Q7_VALIDATION_SEED
        assertTrue(q7PairRows(seed) > 0, "seed $seed produced no Q7 nation-pair rows")
    }

    @Test
    fun `Q16 negated semijoin removes a partsupp row`() {
        val tables = TpchGenerator.generate(seed = 7L, sf = 0.001)
        TpchRig.open(drive = Drive.SIM, seed = 7L).use { rig ->
            rig.beginPhase()
            val base = BaseRelations.spawn(rig)
            base.load(tables)
            rig.completePhase("load")

            rig.beginPhase()
            val maintained = Q16Builder().attach(rig.host.managementInlet, base)
            rig.completePhase("attach")

            val before = maintained.liveRows().getValue("q16-part-supplies")
            val after = maintained.liveRows().getValue("q16-eligible")
            assertTrue(before > after, "Q16 anti-join removed no row: before=$before after=$after")
            assertTrue(maintained.liveRows().getValue("q16-complaints") > 0L)
        }
    }

    companion object {
        // Seed 7 has no FRANCE/GERMANY pair; seed 2 is the first seed in 1..64 that does.
        private const val Q7_VALIDATION_SEED = 2L

        @JvmStatic
        fun queriesAndDrives(): Stream<Arguments> = Stream.of(
            TpchQuery.Q07,
            TpchQuery.Q08,
            TpchQuery.Q09,
            TpchQuery.Q16,
        ).flatMap { query ->
            Stream.of(Drive.SIM, Drive.REAL).map { drive -> Arguments.of(query, drive) }
        }
    }
}

private fun q7PairRows(seed: Long): Int {
    val tables = TpchGenerator.generate(seed = seed, sf = 0.001)
    val nations = tables.nation.associateBy(Nation::nationKey)
    val customers = tables.customer.associateBy(Customer::custKey)
    val suppliers = tables.supplier.associateBy(Supplier::suppKey)
    val orders = tables.orders.associateBy(Orders::orderKey)
    val allowed = setOf("FRANCE" to "GERMANY", "GERMANY" to "FRANCE")
    return tables.lineitem.count { line ->
        val supplierNation = suppliers[line.suppKey]?.nationKey ?: return@count false
        val order = orders[line.orderKey] ?: return@count false
        val customerNation = customers[order.custKey]?.nationKey ?: return@count false
        line.shipDate in tpchDate("1995-01-01")..tpchDate("1996-12-31") &&
            (nations[supplierNation]?.name to nations[customerNation]?.name) in allowed
    }
}
