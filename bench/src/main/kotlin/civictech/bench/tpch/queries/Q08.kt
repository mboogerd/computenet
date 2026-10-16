package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Customer
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Nation
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Part
import civictech.bench.tpch.Q8Params
import civictech.bench.tpch.Region
import civictech.bench.tpch.Supplier
import civictech.bench.tpch.TpchDecimal
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.bench.tpch.tpchDate
import civictech.cell.data.Aggregators
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.io.Serializable
import java.time.LocalDate

internal data class Q08PartLine(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q08PartLineSupplier(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val supplierNationKey: Int,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q08PartLineSupplierOrder(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val supplierNationKey: Int,
    val custKey: Long,
    val orderDate: Int,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q08CustomerOrderLine(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val supplierNationKey: Int,
    val custNationKey: Int,
    val orderDate: Int,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q08CustomerRegionLine(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val supplierNationKey: Int,
    val orderDate: Int,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

internal data class Q08Complete(
    override val orderKey: Long,
    val lineNumber: Int,
    val supplierNation: String,
    val orderDate: Int,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

/** National Market Share Query from TPC-H Revision 3.0.1 §2.4.8. */
class Q08Builder(
    private val params: Q8Params = Q8Params(),
    rowPayload: Class<*> = Q08Complete::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q08
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val startDate = tpchDate("1995-01-01")
        val endDate = tpchDate("1996-12-31")
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var numerator: GroupCollectorCell<Int>
        lateinit var denominator: GroupCollectorCell<Int>

        graph(host) {
            adopt(base.part)
            adopt(base.lineitem)
            adopt(base.supplier)
            adopt(base.orders)
            adopt(base.customer)
            adopt(base.nation)
            adopt(base.region)

            val parts = spawn("q08-parts") { ref ->
                FilterCell(ref = ref) { part: Part -> part.type == params.type }
            }
            val orders = spawn("q08-orders") { ref ->
                FilterCell(ref = ref) { order: Orders -> order.orderDate in startDate..endDate }
            }
            val regions = spawn("q08-regions") { ref ->
                FilterCell(ref = ref) { region: Region -> region.name == params.region }
            }
            val partLines = spawn("q08-part-lines") { ref ->
                JoinSetCell<Part, Lineitem, Long, Q08PartLine>(
                    ref = ref,
                    leftKey = Part::partKey,
                    rightKey = Lineitem::partKey,
                    combine = { part, line ->
                        Q08PartLine(
                            partKey = part.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                        )
                    },
                )
            }
            val partLineSuppliers = spawn("q08-part-line-suppliers") { ref ->
                JoinSetCell<Q08PartLine, Supplier, Long, Q08PartLineSupplier>(
                    ref = ref,
                    leftKey = Q08PartLine::suppKey,
                    rightKey = Supplier::suppKey,
                    combine = { line, supplier ->
                        Q08PartLineSupplier(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            supplierNationKey = supplier.nationKey,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val partLineSupplierOrders = spawn("q08-part-line-supplier-orders") { ref ->
                JoinSetCell<Q08PartLineSupplier, Orders, Long, Q08PartLineSupplierOrder>(
                    ref = ref,
                    leftKey = Q08PartLineSupplier::orderKey,
                    rightKey = Orders::orderKey,
                    combine = { line, order ->
                        Q08PartLineSupplierOrder(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            supplierNationKey = line.supplierNationKey,
                            custKey = order.custKey,
                            orderDate = order.orderDate,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val customerOrderLines = spawn("q08-customer-order-lines") { ref ->
                JoinSetCell<Q08PartLineSupplierOrder, Customer, Long, Q08CustomerOrderLine>(
                    ref = ref,
                    leftKey = Q08PartLineSupplierOrder::custKey,
                    rightKey = Customer::custKey,
                    combine = { line, customer ->
                        Q08CustomerOrderLine(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            supplierNationKey = line.supplierNationKey,
                            custNationKey = customer.nationKey,
                            orderDate = line.orderDate,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val regionCustomerNations = spawn("q08-region-customer-nations") { ref ->
                JoinSetCell<Q08CustomerOrderLine, Nation, Int, Q08CustomerOrderLine>(
                    ref = ref,
                    leftKey = Q08CustomerOrderLine::custNationKey,
                    rightKey = Nation::nationKey,
                    combine = { line, nation ->
                        line.copy(custNationKey = nation.regionKey)
                    },
                )
            }
            val regionLines = spawn("q08-region-lines") { ref ->
                JoinSetCell<Q08CustomerOrderLine, Region, Int, Q08CustomerRegionLine>(
                    ref = ref,
                    leftKey = Q08CustomerOrderLine::custNationKey,
                    rightKey = Region::regionKey,
                    combine = { line, _ ->
                        Q08CustomerRegionLine(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            supplierNationKey = line.supplierNationKey,
                            orderDate = line.orderDate,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val supplierNations = spawn("q08-supplier-nations") { ref ->
                JoinSetCell<Q08CustomerRegionLine, Nation, Int, Q08Complete>(
                    ref = ref,
                    leftKey = Q08CustomerRegionLine::supplierNationKey,
                    rightKey = Nation::nationKey,
                    combine = { line, nation ->
                        Q08Complete(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            supplierNation = nation.name,
                            orderDate = line.orderDate,
                            revenueS4 = line.revenueS4,
                        )
                    },
                )
            }
            val numeratorFold = spawn("q08-share-num") { ref ->
                GroupByCell<Q08Complete, Int, Long, Long>(
                    ref = ref,
                    keyFn = { row -> LocalDate.ofEpochDay(row.orderDate.toLong()).year },
                    aggregator = Aggregators.sumOf<Q08Complete> {
                        if (it.supplierNation == params.nation) it.revenueS4 else 0L
                    },
                )
            }
            val denominatorFold = spawn("q08-share-den") { ref ->
                GroupByCell<Q08Complete, Int, Long, Long>(
                    ref = ref,
                    keyFn = { row -> LocalDate.ofEpochDay(row.orderDate.toLong()).year },
                    aggregator = Aggregators.sumOf(Q08Complete::revenueS4),
                )
            }

            val partRows = spawn("q08-parts-collector") { ref -> SetRowsCollectorCell<Part>(ref) }
            val orderRows = spawn("q08-orders-collector") { ref -> SetRowsCollectorCell<Orders>(ref) }
            val regionRows = spawn("q08-regions-collector") { ref -> SetRowsCollectorCell<Region>(ref) }
            val partLineRows = spawn("q08-part-lines-collector") { ref -> SetRowsCollectorCell<Q08PartLine>(ref) }
            val partLineSupplierRows = spawn("q08-part-line-suppliers-collector") { ref ->
                SetRowsCollectorCell<Q08PartLineSupplier>(ref)
            }
            val partLineSupplierOrderRows = spawn("q08-part-line-supplier-orders-collector") { ref ->
                SetRowsCollectorCell<Q08PartLineSupplierOrder>(ref)
            }
            val customerOrderLineRows = spawn("q08-customer-order-lines-collector") { ref ->
                SetRowsCollectorCell<Q08CustomerOrderLine>(ref)
            }
            val regionLineRows = spawn("q08-region-lines-collector") { ref ->
                SetRowsCollectorCell<Q08CustomerRegionLine>(ref)
            }
            val supplierNationRows = spawn("q08-supplier-nations-collector") { ref ->
                SetRowsCollectorCell<Q08Complete>(ref)
            }
            val numeratorRows = spawn("q08-share-num-collector") { ref -> GroupCollectorCell<Int>(ref) }
            val denominatorRows = spawn("q08-share-den-collector") { ref -> GroupCollectorCell<Int>(ref) }

            link(base.part.outlet, parts.cell.inlet)
            link(base.orders.outlet, orders.cell.inlet)
            link(base.region.outlet, regions.cell.inlet)
            link(parts.cell.outlet, partLines.cell.left)
            link(base.lineitem.outlet, partLines.cell.right)
            link(partLines.cell.outlet, partLineSuppliers.cell.left)
            link(base.supplier.outlet, partLineSuppliers.cell.right)
            link(partLineSuppliers.cell.outlet, partLineSupplierOrders.cell.left)
            link(orders.cell.outlet, partLineSupplierOrders.cell.right)
            link(partLineSupplierOrders.cell.outlet, customerOrderLines.cell.left)
            link(base.customer.outlet, customerOrderLines.cell.right)
            link(customerOrderLines.cell.outlet, regionCustomerNations.cell.left)
            link(base.nation.outlet, regionCustomerNations.cell.right)
            link(regionCustomerNations.cell.outlet, regionLines.cell.left)
            link(regions.cell.outlet, regionLines.cell.right)
            link(regionLines.cell.outlet, supplierNations.cell.left)
            link(base.nation.outlet, supplierNations.cell.right)
            link(supplierNations.cell.outlet, numeratorFold.cell.inlet)
            link(supplierNations.cell.outlet, denominatorFold.cell.inlet)

            link(parts.cell.outlet, partRows.cell.inlet)
            link(orders.cell.outlet, orderRows.cell.inlet)
            link(regions.cell.outlet, regionRows.cell.inlet)
            link(partLines.cell.outlet, partLineRows.cell.inlet)
            link(partLineSuppliers.cell.outlet, partLineSupplierRows.cell.inlet)
            link(partLineSupplierOrders.cell.outlet, partLineSupplierOrderRows.cell.inlet)
            link(customerOrderLines.cell.outlet, customerOrderLineRows.cell.inlet)
            link(regionLines.cell.outlet, regionLineRows.cell.inlet)
            link(supplierNations.cell.outlet, supplierNationRows.cell.inlet)
            link(numeratorFold.cell.outlet, numeratorRows.cell.inlet)
            link(denominatorFold.cell.outlet, denominatorRows.cell.inlet)

            sets["q08-parts"] = partRows.cell
            sets["q08-orders"] = orderRows.cell
            sets["q08-regions"] = regionRows.cell
            sets["q08-part-lines"] = partLineRows.cell
            sets["q08-part-line-suppliers"] = partLineSupplierRows.cell
            sets["q08-part-line-supplier-orders"] = partLineSupplierOrderRows.cell
            sets["q08-customer-order-lines"] = customerOrderLineRows.cell
            sets["q08-region-lines"] = regionLineRows.cell
            numerator = numeratorRows.cell
            denominator = denominatorRows.cell
        }

        return Q08Query(sets, numerator, denominator)
    }
}

private class Q08Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val numerator: GroupCollectorCell<Int>,
    private val denominator: GroupCollectorCell<Int>,
) : CollectedQuery(
    query = TpchQuery.Q08,
    setOutlets = sets,
    groupOutlets = mapOf("q08-share-num" to numerator, "q08-share-den" to denominator),
) {
    override fun read(): ViewResult {
        val years = linkedSetOf<Int>().apply {
            addAll(numerator.values().keys)
            addAll(denominator.values().keys)
        }
        return ViewResult(
            columns = listOf("share_num", "share_den"),
            rows = years.associateTo(linkedMapOf()) { year ->
                listOf<Any>(year) to longArrayOf(numerator.values()[year] ?: 0L, denominator.values()[year] ?: 0L)
            },
        )
    }
}
