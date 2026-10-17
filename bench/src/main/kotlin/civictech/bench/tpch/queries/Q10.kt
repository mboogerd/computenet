package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Customer
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Nation
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Q10Params
import civictech.bench.tpch.TpchDecimal
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.cell.data.Aggregators
import civictech.cell.data.SortSpec
import civictech.cell.data.SortSpec.SortKey
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.io.Serializable
import java.time.LocalDate

internal data class Q10CustomerOrder(
    val custKey: Long,
    override val orderKey: Long,
    val nationKey: Int,
    val customerName: String,
    val acctBal: Long,
    val phone: String,
    val address: String,
    val comment: String,
) : OrderKeyCarrier, Serializable

internal data class Q10CustomerOrderLine(
    val custKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val nationKey: Int,
    val customerName: String,
    val acctBal: Long,
    val phone: String,
    val address: String,
    val comment: String,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

/** Serializable composite tie-break for the LINEITEM primary key. */
data class Q10LineKey(
    val orderKey: Long,
    val lineNumber: Int,
) : Comparable<Q10LineKey>, Serializable {
    override fun compareTo(other: Q10LineKey): Int =
        compareValuesBy(this, other, Q10LineKey::orderKey, Q10LineKey::lineNumber)
}

/** The four-way join row used by both Q10 folds, with every source key retained. */
data class CustOrderLineNation(
    val custKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val nationKey: Int,
    val customerName: String,
    val acctBal: Long,
    val phone: String,
    val nationName: String,
    val address: String,
    val comment: String,
    val revenueS4: Long,
    val lineKey: Q10LineKey = Q10LineKey(orderKey, lineNumber),
) : OrderKeyCarrier, Serializable

internal data class Q10CustomerGroup(
    val custKey: Long,
    val customerName: String,
    val acctBal: Long,
    val phone: String,
    val nationName: String,
    val address: String,
    val comment: String,
) : Serializable

/** Q10-specific observation of the maintained full-support top-K fold. */
interface Q10 : MaintainedQuery {
    fun topRows(): List<CustOrderLineNation>
}

/**
 * Returned Item Reporting Query from TPC-H Revision 3.0.1 §2.4.10.
 * Sources: CUSTOMER, ORDERS, LINEITEM, NATION.
 */
class Q10Builder(
    private val params: Q10Params = Q10Params(),
    rowPayload: Class<*> = CustOrderLineNation::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q10
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): Q10 {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusMonths(3).toEpochDay().toInt()
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var revenue: GroupCollectorCell<Q10CustomerGroup>
        lateinit var topRows: GroupListCollectorCell<String, CustOrderLineNation>

        graph(host) {
            adopt(base.customer)
            adopt(base.orders)
            adopt(base.lineitem)
            adopt(base.nation)

            val orders = spawn("q10-orders") { ref ->
                FilterCell(ref = ref) { order: Orders ->
                    order.orderDate >= params.date && order.orderDate < endDate
                }
            }
            val customerOrders = spawn("q10-customer-orders") { ref ->
                JoinSetCell<Customer, Orders, Long, Q10CustomerOrder>(
                    ref = ref,
                    leftKey = Customer::custKey,
                    rightKey = Orders::custKey,
                    combine = { customer, order ->
                        Q10CustomerOrder(
                            custKey = customer.custKey,
                            orderKey = order.orderKey,
                            nationKey = customer.nationKey,
                            customerName = customer.name,
                            acctBal = customer.acctBal,
                            phone = customer.phone,
                            address = customer.address,
                            comment = customer.comment,
                        )
                    },
                )
            }
            val returnedLines = spawn("q10-returned-lines") { ref ->
                FilterCell(ref = ref) { line: Lineitem -> line.returnFlag == "R" }
            }
            val customerOrderLines = spawn("q10-customer-order-lines") { ref ->
                JoinSetCell<Q10CustomerOrder, Lineitem, Long, Q10CustomerOrderLine>(
                    ref = ref,
                    leftKey = Q10CustomerOrder::orderKey,
                    rightKey = Lineitem::orderKey,
                    combine = { order, line ->
                        Q10CustomerOrderLine(
                            custKey = order.custKey,
                            orderKey = order.orderKey,
                            lineNumber = line.lineNumber,
                            nationKey = order.nationKey,
                            customerName = order.customerName,
                            acctBal = order.acctBal,
                            phone = order.phone,
                            address = order.address,
                            comment = order.comment,
                            revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                        )
                    },
                )
            }
            val joined = spawn("q10-joined") { ref ->
                JoinSetCell<Q10CustomerOrderLine, Nation, Int, CustOrderLineNation>(
                    ref = ref,
                    leftKey = Q10CustomerOrderLine::nationKey,
                    rightKey = Nation::nationKey,
                    combine = { line, nation ->
                        CustOrderLineNation(
                            custKey = line.custKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            nationKey = nation.nationKey,
                            customerName = line.customerName,
                            acctBal = line.acctBal,
                            phone = line.phone,
                            nationName = nation.name,
                            address = line.address,
                            comment = line.comment,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val revenueFold = spawnFold("q10-revenue", CustOrderLineNation::class) { ref ->
                GroupByCell<CustOrderLineNation, Q10CustomerGroup, Long, Long>(
                    ref = ref,
                    keyFn = { row ->
                        Q10CustomerGroup(
                            custKey = row.custKey,
                            customerName = row.customerName,
                            acctBal = row.acctBal,
                            phone = row.phone,
                            nationName = row.nationName,
                            address = row.address,
                            comment = row.comment,
                        )
                    },
                    aggregator = Aggregators.sumOf(CustOrderLineNation::revenueS4),
                )
            }
            val topRowsFold = spawnFold("q10-top-rows", CustOrderLineNation::class) { ref ->
                GroupByCell.global(
                    ref = ref,
                    aggregator = Aggregators.topKBy(
                        k = 20,
                        spec = SortSpec.by(
                            SortKey.desc(CustOrderLineNation::revenueS4),
                            tieBreak = CustOrderLineNation::lineKey,
                        ),
                    ),
                )
            }

            val orderRows = spawn("q10-orders-collector") { ref -> SetRowsCollectorCell<Orders>(ref) }
            val customerOrderRows = spawn("q10-customer-orders-collector") { ref ->
                SetRowsCollectorCell<Q10CustomerOrder>(ref)
            }
            val returnedLineRows = spawn("q10-returned-lines-collector") { ref ->
                SetRowsCollectorCell<Lineitem>(ref)
            }
            val customerOrderLineRows = spawn("q10-customer-order-lines-collector") { ref ->
                SetRowsCollectorCell<Q10CustomerOrderLine>(ref)
            }
            val joinedRows = spawn("q10-joined-collector") { ref ->
                SetRowsCollectorCell<CustOrderLineNation>(ref)
            }
            val revenueRows = spawn("q10-revenue-collector") { ref ->
                GroupCollectorCell<Q10CustomerGroup>(ref)
            }
            val topRowsCollector = spawn("q10-top-rows-collector") { ref ->
                GroupListCollectorCell<String, CustOrderLineNation>(ref)
            }

            link(base.orders.outlet, orders.cell.inlet)
            link(base.customer.outlet, customerOrders.cell.left)
            link(orders.cell.outlet, customerOrders.cell.right)
            link(base.lineitem.outlet, returnedLines.cell.inlet)
            link(customerOrders.cell.outlet, customerOrderLines.cell.left)
            link(returnedLines.cell.outlet, customerOrderLines.cell.right)
            link(customerOrderLines.cell.outlet, joined.cell.left)
            link(base.nation.outlet, joined.cell.right)
            link(joined.cell.outlet, revenueFold.cell.inlet)
            link(joined.cell.outlet, topRowsFold.cell.inlet)

            link(orders.cell.outlet, orderRows.cell.inlet)
            link(customerOrders.cell.outlet, customerOrderRows.cell.inlet)
            link(returnedLines.cell.outlet, returnedLineRows.cell.inlet)
            link(customerOrderLines.cell.outlet, customerOrderLineRows.cell.inlet)
            link(joined.cell.outlet, joinedRows.cell.inlet)
            link(revenueFold.cell.outlet, revenueRows.cell.inlet)
            link(topRowsFold.cell.outlet, topRowsCollector.cell.inlet)

            sets["q10-orders"] = orderRows.cell
            sets["q10-customer-orders"] = customerOrderRows.cell
            sets["q10-returned-lines"] = returnedLineRows.cell
            sets["q10-customer-order-lines"] = customerOrderLineRows.cell
            sets["q10-joined"] = joinedRows.cell
            revenue = revenueRows.cell
            topRows = topRowsCollector.cell
        }

        return Q10Query(sets, revenue, topRows)
    }
}

private class Q10Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val revenue: GroupCollectorCell<Q10CustomerGroup>,
    private val topRows: GroupListCollectorCell<String, CustOrderLineNation>,
) : CollectedQuery(
    query = TpchQuery.Q10,
    setOutlets = sets,
    groupOutlets = mapOf("q10-revenue" to revenue),
    additionalOutlets = mapOf("q10-top-rows" to topRows),
), Q10 {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("revenue"),
        rows = revenue.values().entries.associateTo(linkedMapOf()) { (key, value) ->
            listOf<Any>(
                key.custKey,
                key.customerName,
                key.acctBal,
                key.phone,
                key.nationName,
                key.address,
                key.comment,
            ) to longArrayOf(value)
        },
    ).topN(
        n = 20,
        ordering = compareByDescending<ViewResult.Row> { it.values[0] }
            .thenBy { it.key[0] as Long },
    )

    override fun topRows(): List<CustOrderLineNation> = topRows.values()["global"].orEmpty()
}
