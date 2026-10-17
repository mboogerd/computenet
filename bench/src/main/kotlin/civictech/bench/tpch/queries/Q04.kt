package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Q4Params
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.cell.data.Aggregators
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.time.LocalDate

/**
 * Order Priority Checking Query from TPC-H Revision 3.0.1 §2.4.4.
 * Sources: ORDERS, LINEITEM.
 */
class Q04Builder(
    private val params: Q4Params = Q4Params(),
    rowPayload: Class<*> = Orders::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q04
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusMonths(3).toEpochDay().toInt()
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var counts: GroupCollectorCell<String>

        graph(host) {
            adopt(base.orders)
            adopt(base.lineitem)

            val orders = spawn("q04-orders") { ref ->
                FilterCell(ref = ref) { order: Orders ->
                    order.orderDate >= params.date && order.orderDate < endDate
                }
            }
            val lateLines = spawn("q04-late-lines") { ref ->
                FilterCell(ref = ref) { line: Lineitem -> line.commitDate < line.receiptDate }
            }
            val lateOrders = spawn("q04-late-orders") { ref ->
                SemiJoinCell<Orders, Lineitem, Long>(
                    ref = ref,
                    leftKey = Orders::orderKey,
                    rightKey = Lineitem::orderKey,
                    negated = false,
                )
            }
            val countFold = spawnFold("q04-order-count", Orders::class) { ref ->
                GroupByCell<Orders, String, Long, Long>(
                    ref = ref,
                    keyFn = Orders::orderPriority,
                    aggregator = Aggregators.count(),
                )
            }

            val orderRows = spawn("q04-orders-collector") { ref -> SetRowsCollectorCell<Orders>(ref) }
            val lateLineRows = spawn("q04-late-lines-collector") { ref -> SetRowsCollectorCell<Lineitem>(ref) }
            val lateOrderRows = spawn("q04-late-orders-collector") { ref -> SetRowsCollectorCell<Orders>(ref) }
            val countRows = spawn("q04-order-count-collector") { ref -> GroupCollectorCell<String>(ref) }

            link(base.orders.outlet, orders.cell.inlet)
            link(base.lineitem.outlet, lateLines.cell.inlet)
            link(orders.cell.outlet, lateOrders.cell.left)
            link(lateLines.cell.outlet, lateOrders.cell.right)
            link(lateOrders.cell.outlet, countFold.cell.inlet)

            link(orders.cell.outlet, orderRows.cell.inlet)
            link(lateLines.cell.outlet, lateLineRows.cell.inlet)
            link(lateOrders.cell.outlet, lateOrderRows.cell.inlet)
            link(countFold.cell.outlet, countRows.cell.inlet)

            sets["q04-orders"] = orderRows.cell
            sets["q04-late-lines"] = lateLineRows.cell
            sets["q04-late-orders"] = lateOrderRows.cell
            counts = countRows.cell
        }

        return Q04Query(sets, counts)
    }
}

private class Q04Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val counts: GroupCollectorCell<String>,
) : CollectedQuery(
    query = TpchQuery.Q04,
    setOutlets = sets,
    groupOutlets = mapOf("q04-order-count" to counts),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("order_count"),
        rows = counts.values().entries.associateTo(linkedMapOf()) { (priority, count) ->
            listOf<Any>(priority) to longArrayOf(count)
        },
    )
}
