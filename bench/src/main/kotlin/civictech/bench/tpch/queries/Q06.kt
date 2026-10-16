package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Q6Params
import civictech.bench.tpch.TpchDecimal
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.cell.data.Aggregators
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.time.LocalDate

/** Forecasting Revenue Change Query from TPC-H Revision 3.0.1 §2.4.6. */
class Q06Builder(
    private val params: Q6Params = Q6Params(),
    rowPayload: Class<*> = Lineitem::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q06
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusYears(1).toEpochDay().toInt()
        lateinit var filteredRows: SetRowsCollectorCell<Lineitem>
        lateinit var revenue: GroupCollectorCell<String>

        graph(host) {
            adopt(base.lineitem)

            val filtered = spawn("q06-filtered") { ref ->
                FilterCell(ref = ref) { line: Lineitem ->
                    line.shipDate >= params.date && line.shipDate < endDate &&
                        line.discount in (params.discount - 1)..(params.discount + 1) &&
                        line.quantity < params.quantity
                }
            }
            val revenueFold = spawn("q06-revenue") { ref ->
                GroupByCell.global(
                    ref = ref,
                    aggregator = Aggregators.sumOf<Lineitem> {
                        TpchDecimal.discountedS4(it.extendedPrice, it.discount)
                    },
                )
            }
            val rowCollector = spawn("q06-filtered-collector") { ref ->
                SetRowsCollectorCell<Lineitem>(ref)
            }
            val revenueCollector = spawn("q06-revenue-collector") { ref ->
                GroupCollectorCell<String>(ref)
            }

            link(base.lineitem.outlet, filtered.cell.inlet)
            link(filtered.cell.outlet, revenueFold.cell.inlet)
            link(filtered.cell.outlet, rowCollector.cell.inlet)
            link(revenueFold.cell.outlet, revenueCollector.cell.inlet)

            filteredRows = rowCollector.cell
            revenue = revenueCollector.cell
        }

        return Q06Query(filteredRows, revenue)
    }
}

private class Q06Query(
    filteredRows: SetRowsCollectorCell<Lineitem>,
    private val revenue: GroupCollectorCell<String>,
) : CollectedQuery(
    query = TpchQuery.Q06,
    setOutlets = mapOf("q06-filtered" to filteredRows),
    groupOutlets = mapOf("q06-revenue" to revenue),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("revenue"),
        rows = mapOf(emptyList<Any>() to longArrayOf(revenue.values()["global"] ?: 0L)),
    )
}
