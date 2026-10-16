package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Q1Params
import civictech.bench.tpch.TpchDecimal
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.bench.tpch.tpchDate
import civictech.cell.data.Aggregators
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.io.Serializable

/** Group key from TPC-H Revision 3.0.1 §2.4.1.2. */
internal data class Q01Group(
    val returnFlag: String,
    val lineStatus: String,
) : Serializable

/** Q1 pricing summary, one independent fold for each exposed aggregate column. */
class Q01Builder(
    private val params: Q1Params = Q1Params(),
    rowPayload: Class<*> = Lineitem::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q01
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val cutoff = tpchDate("1998-12-01") - params.delta
        val groups = linkedMapOf<String, GroupCollectorCell<Q01Group>>()
        lateinit var filteredRows: SetRowsCollectorCell<Lineitem>

        graph(host) {
            adopt(base.lineitem)

            val filtered = spawn("q01-filtered") { ref ->
                FilterCell(ref = ref) { line: Lineitem -> line.shipDate <= cutoff }
            }
            val rowCollector = spawn("q01-filtered-collector") { ref ->
                SetRowsCollectorCell<Lineitem>(ref)
            }

            fun groupKey(line: Lineitem) = Q01Group(line.returnFlag, line.lineStatus)

            val sumQty = spawn("q01-sum-qty") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf(Lineitem::quantity))
            }
            val sumBasePrice = spawn("q01-sum-base-price") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf(Lineitem::extendedPrice))
            }
            val sumDiscPrice = spawn("q01-sum-disc-price") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf<Lineitem> {
                    TpchDecimal.revenueS4(it.extendedPrice, it.discount)
                })
            }
            val sumCharge = spawn("q01-sum-charge") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf<Lineitem> {
                    TpchDecimal.chargeS6(it.extendedPrice, it.discount, it.tax)
                })
            }
            val avgQtySum = spawn("q01-avg-qty-sum") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf(Lineitem::quantity))
            }
            val avgQtyCount = spawn("q01-avg-qty-count") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.count<Lineitem>())
            }
            val avgPriceSum = spawn("q01-avg-price-sum") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf(Lineitem::extendedPrice))
            }
            val avgPriceCount = spawn("q01-avg-price-count") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.count<Lineitem>())
            }
            val avgDiscSum = spawn("q01-avg-disc-sum") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.sumOf<Lineitem> { it.discount.toLong() })
            }
            val avgDiscCount = spawn("q01-avg-disc-count") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.count<Lineitem>())
            }
            val countOrder = spawn("q01-count-order") { ref ->
                GroupByCell(ref, ::groupKey, Aggregators.count<Lineitem>())
            }

            val folds = listOf(
                "q01-sum-qty" to sumQty,
                "q01-sum-base-price" to sumBasePrice,
                "q01-sum-disc-price" to sumDiscPrice,
                "q01-sum-charge" to sumCharge,
                "q01-avg-qty-sum" to avgQtySum,
                "q01-avg-qty-count" to avgQtyCount,
                "q01-avg-price-sum" to avgPriceSum,
                "q01-avg-price-count" to avgPriceCount,
                "q01-avg-disc-sum" to avgDiscSum,
                "q01-avg-disc-count" to avgDiscCount,
                "q01-count-order" to countOrder,
            )
            folds.forEach { (name, fold) ->
                val collector = spawn("$name-collector") { ref -> GroupCollectorCell<Q01Group>(ref) }
                link(filtered.cell.outlet, fold.cell.inlet)
                link(fold.cell.outlet, collector.cell.inlet)
                groups[name] = collector.cell
            }

            link(base.lineitem.outlet, filtered.cell.inlet)
            link(filtered.cell.outlet, rowCollector.cell.inlet)
            filteredRows = rowCollector.cell
        }

        return Q01Query(filteredRows, groups)
    }
}

private class Q01Query(
    filteredRows: SetRowsCollectorCell<Lineitem>,
    private val groups: Map<String, GroupCollectorCell<Q01Group>>,
) : CollectedQuery(
    query = TpchQuery.Q01,
    setOutlets = mapOf("q01-filtered" to filteredRows),
    groupOutlets = groups,
) {
    override fun read(): ViewResult {
        val values = groups.values.map { it.values() }
        val keys = values.flatMapTo(linkedSetOf()) { it.keys }
        return ViewResult(
            columns = listOf(
                "sum_qty", "sum_base_price", "sum_disc_price", "sum_charge",
                "avg_qty_sum", "avg_qty_count", "avg_price_sum", "avg_price_count",
                "avg_disc_sum", "avg_disc_count", "count_order",
            ),
            rows = keys.associateTo(linkedMapOf()) { key ->
                listOf<Any>(key.returnFlag, key.lineStatus) to
                    values.map { it[key] ?: 0L }.toLongArray()
            },
        )
    }
}
