package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Customer
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Q3Params
import civictech.bench.tpch.TpchDecimal
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.cell.data.Aggregators
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.io.Serializable

/** Customer/order row retaining both source primary keys (3xi.3-D4). */
internal data class Q03CustomerOrder(
    val custKey: Long,
    override val orderKey: Long,
    val orderDate: Int,
    val shipPriority: Int,
) : OrderKeyCarrier, Serializable

/** Customer/order/line row retaining all three source primary keys. */
internal data class Q03CustomerOrderLine(
    val custKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val orderDate: Int,
    val shipPriority: Int,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q03Group(
    override val orderKey: Long,
    val orderDate: Int,
    val shipPriority: Int,
) : OrderKeyCarrier, Serializable

/** Shipping Priority Query from TPC-H Revision 3.0.1 §2.4.3. */
class Q03Builder(
    private val params: Q3Params = Q3Params(),
    rowPayload: Class<*> = Q03CustomerOrderLine::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q03
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var revenue: GroupCollectorCell<Q03Group>

        graph(host) {
            adopt(base.customer)
            adopt(base.orders)
            adopt(base.lineitem)

            val customers = spawn("q03-customers") { ref ->
                FilterCell(ref = ref) { customer: Customer -> customer.mktSegment == params.segment }
            }
            val orders = spawn("q03-orders") { ref ->
                FilterCell(ref = ref) { order: Orders -> order.orderDate < params.date }
            }
            val customerOrders = spawn("q03-customer-orders") { ref ->
                JoinSetCell<Customer, Orders, Long, Q03CustomerOrder>(
                    ref = ref,
                    leftKey = Customer::custKey,
                    rightKey = Orders::custKey,
                    combine = { customer, order ->
                        Q03CustomerOrder(
                            custKey = customer.custKey,
                            orderKey = order.orderKey,
                            orderDate = order.orderDate,
                            shipPriority = order.shipPriority,
                        )
                    },
                )
            }
            val lineitems = spawn("q03-lineitems") { ref ->
                FilterCell(ref = ref) { line: Lineitem -> line.shipDate > params.date }
            }
            val orderLines = spawn("q03-order-lines") { ref ->
                JoinSetCell<Q03CustomerOrder, Lineitem, Long, Q03CustomerOrderLine>(
                    ref = ref,
                    leftKey = Q03CustomerOrder::orderKey,
                    rightKey = Lineitem::orderKey,
                    combine = { order, line ->
                        Q03CustomerOrderLine(
                            custKey = order.custKey,
                            orderKey = order.orderKey,
                            lineNumber = line.lineNumber,
                            orderDate = order.orderDate,
                            shipPriority = order.shipPriority,
                            revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                        )
                    },
                )
            }
            val revenueFold = spawnFold("q03-revenue", Q03CustomerOrderLine::class) { ref ->
                GroupByCell<Q03CustomerOrderLine, Q03Group, Long, Long>(
                    ref = ref,
                    keyFn = { row -> Q03Group(row.orderKey, row.orderDate, row.shipPriority) },
                    aggregator = Aggregators.sumOf(Q03CustomerOrderLine::revenueS4),
                )
            }

            val customerRows = spawn("q03-customers-collector") { ref -> SetRowsCollectorCell<Customer>(ref) }
            val orderRows = spawn("q03-orders-collector") { ref -> SetRowsCollectorCell<Orders>(ref) }
            val customerOrderRows = spawn("q03-customer-orders-collector") { ref ->
                SetRowsCollectorCell<Q03CustomerOrder>(ref)
            }
            val lineitemRows = spawn("q03-lineitems-collector") { ref -> SetRowsCollectorCell<Lineitem>(ref) }
            val orderLineRows = spawn("q03-order-lines-collector") { ref ->
                SetRowsCollectorCell<Q03CustomerOrderLine>(ref)
            }
            val revenueRows = spawn("q03-revenue-collector") { ref -> GroupCollectorCell<Q03Group>(ref) }

            link(base.customer.outlet, customers.cell.inlet)
            link(base.orders.outlet, orders.cell.inlet)
            link(customers.cell.outlet, customerOrders.cell.left)
            link(orders.cell.outlet, customerOrders.cell.right)
            link(base.lineitem.outlet, lineitems.cell.inlet)
            link(customerOrders.cell.outlet, orderLines.cell.left)
            link(lineitems.cell.outlet, orderLines.cell.right)
            link(orderLines.cell.outlet, revenueFold.cell.inlet)

            link(customers.cell.outlet, customerRows.cell.inlet)
            link(orders.cell.outlet, orderRows.cell.inlet)
            link(customerOrders.cell.outlet, customerOrderRows.cell.inlet)
            link(lineitems.cell.outlet, lineitemRows.cell.inlet)
            link(orderLines.cell.outlet, orderLineRows.cell.inlet)
            link(revenueFold.cell.outlet, revenueRows.cell.inlet)

            sets["q03-customers"] = customerRows.cell
            sets["q03-orders"] = orderRows.cell
            sets["q03-customer-orders"] = customerOrderRows.cell
            sets["q03-lineitems"] = lineitemRows.cell
            sets["q03-order-lines"] = orderLineRows.cell
            revenue = revenueRows.cell
        }

        return Q03Query(sets, revenue)
    }
}

private class Q03Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val revenue: GroupCollectorCell<Q03Group>,
) : CollectedQuery(
    query = TpchQuery.Q03,
    setOutlets = sets,
    groupOutlets = mapOf("q03-revenue" to revenue),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("revenue"),
        rows = revenue.values().entries.associateTo(linkedMapOf()) { (key, value) ->
            listOf<Any>(key.orderKey, key.orderDate, key.shipPriority) to longArrayOf(value)
        },
    )
}
