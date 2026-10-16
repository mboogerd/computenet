package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Customer
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Nation
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Q5Params
import civictech.bench.tpch.Region
import civictech.bench.tpch.Supplier
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
import java.time.LocalDate

internal data class Q05RegionNation(
    val regionKey: Int,
    val nationKey: Int,
    val nationName: String,
) : Serializable

internal data class Q05RegionNationCustomer(
    val regionKey: Int,
    val nationKey: Int,
    val nationName: String,
    val custKey: Long,
) : Serializable

internal data class Q05CustomerOrder(
    val regionKey: Int,
    val nationKey: Int,
    val nationName: String,
    val custKey: Long,
    override val orderKey: Long,
) : OrderKeyCarrier, Serializable

internal data class Q05OrderLine(
    val regionKey: Int,
    val nationKey: Int,
    val nationName: String,
    val custKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q05LocalSale(
    val regionKey: Int,
    val nationKey: Int,
    val nationName: String,
    val custKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

/** Local Supplier Volume Query from TPC-H Revision 3.0.1 §2.4.5. */
class Q05Builder(
    private val params: Q5Params = Q5Params(),
    rowPayload: Class<*> = Q05LocalSale::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q05
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusYears(1).toEpochDay().toInt()
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var revenue: GroupCollectorCell<String>

        graph(host) {
            adopt(base.region)
            adopt(base.nation)
            adopt(base.customer)
            adopt(base.orders)
            adopt(base.lineitem)
            adopt(base.supplier)

            val regions = spawn("q05-regions") { ref ->
                FilterCell(ref = ref) { region: Region -> region.name == params.region }
            }
            val regionNations = spawn("q05-region-nations") { ref ->
                JoinSetCell<Region, Nation, Int, Q05RegionNation>(
                    ref = ref,
                    leftKey = Region::regionKey,
                    rightKey = Nation::regionKey,
                    combine = { region, nation ->
                        Q05RegionNation(region.regionKey, nation.nationKey, nation.name)
                    },
                )
            }
            val regionCustomers = spawn("q05-region-customers") { ref ->
                JoinSetCell<Q05RegionNation, Customer, Int, Q05RegionNationCustomer>(
                    ref = ref,
                    leftKey = Q05RegionNation::nationKey,
                    rightKey = Customer::nationKey,
                    combine = { nation, customer ->
                        Q05RegionNationCustomer(
                            regionKey = nation.regionKey,
                            nationKey = nation.nationKey,
                            nationName = nation.nationName,
                            custKey = customer.custKey,
                        )
                    },
                )
            }
            val orders = spawn("q05-orders") { ref ->
                FilterCell(ref = ref) { order: Orders ->
                    order.orderDate >= params.date && order.orderDate < endDate
                }
            }
            val customerOrders = spawn("q05-customer-orders") { ref ->
                JoinSetCell<Q05RegionNationCustomer, Orders, Long, Q05CustomerOrder>(
                    ref = ref,
                    leftKey = Q05RegionNationCustomer::custKey,
                    rightKey = Orders::custKey,
                    combine = { customer, order ->
                        Q05CustomerOrder(
                            regionKey = customer.regionKey,
                            nationKey = customer.nationKey,
                            nationName = customer.nationName,
                            custKey = customer.custKey,
                            orderKey = order.orderKey,
                        )
                    },
                )
            }
            val orderLines = spawn("q05-order-lines") { ref ->
                JoinSetCell<Q05CustomerOrder, Lineitem, Long, Q05OrderLine>(
                    ref = ref,
                    leftKey = Q05CustomerOrder::orderKey,
                    rightKey = Lineitem::orderKey,
                    combine = { order, line ->
                        Q05OrderLine(
                            regionKey = order.regionKey,
                            nationKey = order.nationKey,
                            nationName = order.nationName,
                            custKey = order.custKey,
                            orderKey = order.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                        )
                    },
                )
            }
            val localSales = spawn("q05-local-sales") { ref ->
                JoinSetCell<Q05OrderLine, Supplier, Pair<Long, Int>, Q05LocalSale>(
                    ref = ref,
                    leftKey = { line -> line.suppKey to line.nationKey },
                    rightKey = { supplier -> supplier.suppKey to supplier.nationKey },
                    combine = { line, supplier ->
                        Q05LocalSale(
                            regionKey = line.regionKey,
                            nationKey = supplier.nationKey,
                            nationName = line.nationName,
                            custKey = line.custKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = supplier.suppKey,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val revenueFold = spawn("q05-revenue") { ref ->
                GroupByCell<Q05LocalSale, String, Long, Long>(
                    ref = ref,
                    keyFn = Q05LocalSale::nationName,
                    aggregator = Aggregators.sumOf(Q05LocalSale::revenueS4),
                )
            }

            val regionRows = spawn("q05-regions-collector") { ref -> SetRowsCollectorCell<Region>(ref) }
            val regionNationRows = spawn("q05-region-nations-collector") { ref ->
                SetRowsCollectorCell<Q05RegionNation>(ref)
            }
            val regionCustomerRows = spawn("q05-region-customers-collector") { ref ->
                SetRowsCollectorCell<Q05RegionNationCustomer>(ref)
            }
            val orderRows = spawn("q05-orders-collector") { ref -> SetRowsCollectorCell<Orders>(ref) }
            val customerOrderRows = spawn("q05-customer-orders-collector") { ref ->
                SetRowsCollectorCell<Q05CustomerOrder>(ref)
            }
            val orderLineRows = spawn("q05-order-lines-collector") { ref -> SetRowsCollectorCell<Q05OrderLine>(ref) }
            val localSaleRows = spawn("q05-local-sales-collector") { ref -> SetRowsCollectorCell<Q05LocalSale>(ref) }
            val revenueRows = spawn("q05-revenue-collector") { ref -> GroupCollectorCell<String>(ref) }

            link(base.region.outlet, regions.cell.inlet)
            link(regions.cell.outlet, regionNations.cell.left)
            link(base.nation.outlet, regionNations.cell.right)
            link(regionNations.cell.outlet, regionCustomers.cell.left)
            link(base.customer.outlet, regionCustomers.cell.right)
            link(base.orders.outlet, orders.cell.inlet)
            link(regionCustomers.cell.outlet, customerOrders.cell.left)
            link(orders.cell.outlet, customerOrders.cell.right)
            link(customerOrders.cell.outlet, orderLines.cell.left)
            link(base.lineitem.outlet, orderLines.cell.right)
            link(orderLines.cell.outlet, localSales.cell.left)
            link(base.supplier.outlet, localSales.cell.right)
            link(localSales.cell.outlet, revenueFold.cell.inlet)

            link(regions.cell.outlet, regionRows.cell.inlet)
            link(regionNations.cell.outlet, regionNationRows.cell.inlet)
            link(regionCustomers.cell.outlet, regionCustomerRows.cell.inlet)
            link(orders.cell.outlet, orderRows.cell.inlet)
            link(customerOrders.cell.outlet, customerOrderRows.cell.inlet)
            link(orderLines.cell.outlet, orderLineRows.cell.inlet)
            link(localSales.cell.outlet, localSaleRows.cell.inlet)
            link(revenueFold.cell.outlet, revenueRows.cell.inlet)

            sets["q05-regions"] = regionRows.cell
            sets["q05-region-nations"] = regionNationRows.cell
            sets["q05-region-customers"] = regionCustomerRows.cell
            sets["q05-orders"] = orderRows.cell
            sets["q05-customer-orders"] = customerOrderRows.cell
            sets["q05-order-lines"] = orderLineRows.cell
            sets["q05-local-sales"] = localSaleRows.cell
            revenue = revenueRows.cell
        }

        return Q05Query(sets, revenue)
    }
}

private class Q05Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val revenue: GroupCollectorCell<String>,
) : CollectedQuery(
    query = TpchQuery.Q05,
    setOutlets = sets,
    groupOutlets = mapOf("q05-revenue" to revenue),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("revenue"),
        rows = revenue.values().entries.associateTo(linkedMapOf()) { (nation, value) ->
            listOf<Any>(nation) to longArrayOf(value)
        },
    )
}
