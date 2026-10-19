package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Customer
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Nation
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Q7Params
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

internal data class Q07LineSupplier(
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val partKey: Long,
    val extendedPrice: Long,
    val discount: Int,
    val supplierNationKey: Int,
    val shipDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q07LineSupplierOrder(
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val partKey: Long,
    val extendedPrice: Long,
    val discount: Int,
    val supplierNationKey: Int,
    val custKey: Long,
    val shipDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q07LineSupplierOrderCustomer(
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val partKey: Long,
    val extendedPrice: Long,
    val discount: Int,
    val supplierNationKey: Int,
    val custNationKey: Int,
    val shipDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q07SupplierNation(
    override val orderKey: Long,
    val lineNumber: Int,
    val extendedPrice: Long,
    val discount: Int,
    val supplierNation: String,
    val custNationKey: Int,
    val shipDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q07Complete(
    override val orderKey: Long,
    val lineNumber: Int,
    val extendedPrice: Long,
    val discount: Int,
    val supplierNation: String,
    val customerNation: String,
    val shipDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q07Group(
    val supplierNation: String,
    val customerNation: String,
    val year: Int,
) : Serializable

/** Volume Shipping Query from TPC-H Revision 3.0.1 §2.4.7. */
class Q07Builder(
    private val params: Q7Params = Q7Params(),
    rowPayload: Class<*> = Q07Complete::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q07
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val startDate = tpchDate("1995-01-01")
        val endDate = tpchDate("1996-12-31")
        val allowed = setOf(params.nation1 to params.nation2, params.nation2 to params.nation1)
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var revenue: GroupCollectorCell<Q07Group>

        graph(host) {
            adopt(base.lineitem)
            adopt(base.supplier)
            adopt(base.orders)
            adopt(base.customer)
            adopt(base.nation)

            val lineitems = spawn("q07-lineitems") { ref ->
                FilterCell(ref = ref) { line: Lineitem -> line.shipDate in startDate..endDate }
            }
            val lineSuppliers = spawn("q07-line-suppliers") { ref ->
                JoinSetCell<Lineitem, Supplier, Long, Q07LineSupplier>(
                    ref = ref,
                    leftKey = Lineitem::suppKey,
                    rightKey = Supplier::suppKey,
                    combine = { line, supplier ->
                        Q07LineSupplier(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            partKey = line.partKey,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplierNationKey = supplier.nationKey,
                            shipDate = line.shipDate,
                        )
                    },
                )
            }
            val lineSupplierOrders = spawn("q07-line-supplier-orders") { ref ->
                JoinSetCell<Q07LineSupplier, Orders, Long, Q07LineSupplierOrder>(
                    ref = ref,
                    leftKey = Q07LineSupplier::orderKey,
                    rightKey = Orders::orderKey,
                    combine = { line, order ->
                        Q07LineSupplierOrder(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            partKey = line.partKey,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplierNationKey = line.supplierNationKey,
                            custKey = order.custKey,
                            shipDate = line.shipDate,
                        )
                    },
                )
            }
            val lineSupplierOrderCustomers = spawn("q07-line-supplier-order-customers") { ref ->
                JoinSetCell<Q07LineSupplierOrder, Customer, Long, Q07LineSupplierOrderCustomer>(
                    ref = ref,
                    leftKey = Q07LineSupplierOrder::custKey,
                    rightKey = Customer::custKey,
                    combine = { line, customer ->
                        Q07LineSupplierOrderCustomer(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            partKey = line.partKey,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplierNationKey = line.supplierNationKey,
                            custNationKey = customer.nationKey,
                            shipDate = line.shipDate,
                        )
                    },
                )
            }
            val supplierNations = spawn("q07-supplier-nations") { ref ->
                JoinSetCell<Q07LineSupplierOrderCustomer, Nation, Int, Q07SupplierNation>(
                    ref = ref,
                    leftKey = Q07LineSupplierOrderCustomer::supplierNationKey,
                    rightKey = Nation::nationKey,
                    combine = { line, nation ->
                        Q07SupplierNation(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplierNation = nation.name,
                            custNationKey = line.custNationKey,
                            shipDate = line.shipDate,
                        )
                    },
                )
            }
            val customerNations = spawn("q07-customer-nations") { ref ->
                JoinSetCell<Q07SupplierNation, Nation, Int, Q07Complete>(
                    ref = ref,
                    leftKey = Q07SupplierNation::custNationKey,
                    rightKey = Nation::nationKey,
                    combine = { line, nation ->
                        Q07Complete(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplierNation = line.supplierNation,
                            customerNation = nation.name,
                            shipDate = line.shipDate,
                        )
                    },
                )
            }
            val pair = spawn("q07-nation-pair") { ref ->
                FilterCell(ref = ref) { row: Q07Complete ->
                    row.supplierNation to row.customerNation in allowed
                }
            }
            val revenueFold = spawnFold("q07-revenue", Q07Complete::class) { ref ->
                GroupByCell<Q07Complete, Q07Group, Long, Long>(
                    ref = ref,
                    keyFn = { row ->
                        Q07Group(
                            supplierNation = row.supplierNation,
                            customerNation = row.customerNation,
                            year = LocalDate.ofEpochDay(row.shipDate.toLong()).year,
                        )
                    },
                    aggregator = Aggregators.sumOf<Q07Complete> {
                        TpchDecimal.revenueS4(it.extendedPrice, it.discount)
                    },
                )
            }

            val lineitemRows = spawn("q07-lineitems-collector") { ref -> SetRowsCollectorCell<Lineitem>(ref) }
            val lineSupplierRows = spawn("q07-line-suppliers-collector") { ref ->
                SetRowsCollectorCell<Q07LineSupplier>(ref)
            }
            val lineSupplierOrderRows = spawn("q07-line-supplier-orders-collector") { ref ->
                SetRowsCollectorCell<Q07LineSupplierOrder>(ref)
            }
            val lineSupplierOrderCustomerRows = spawn("q07-line-supplier-order-customers-collector") { ref ->
                SetRowsCollectorCell<Q07LineSupplierOrderCustomer>(ref)
            }
            val supplierNationRows = spawn("q07-supplier-nations-collector") { ref ->
                SetRowsCollectorCell<Q07SupplierNation>(ref)
            }
            val customerNationRows = spawn("q07-customer-nations-collector") { ref ->
                SetRowsCollectorCell<Q07Complete>(ref)
            }
            val pairRows = spawn("q07-nation-pair-collector") { ref -> SetRowsCollectorCell<Q07Complete>(ref) }
            val revenueRows = spawn("q07-revenue-collector") { ref -> GroupCollectorCell<Q07Group>(ref) }

            link(base.lineitem.outlet, lineitems.cell.inlet)
            link(lineitems.cell.outlet, lineSuppliers.cell.left)
            link(base.supplier.outlet, lineSuppliers.cell.right)
            link(lineSuppliers.cell.outlet, lineSupplierOrders.cell.left)
            link(base.orders.outlet, lineSupplierOrders.cell.right)
            link(lineSupplierOrders.cell.outlet, lineSupplierOrderCustomers.cell.left)
            link(base.customer.outlet, lineSupplierOrderCustomers.cell.right)
            link(lineSupplierOrderCustomers.cell.outlet, supplierNations.cell.left)
            link(base.nation.outlet, supplierNations.cell.right)
            link(supplierNations.cell.outlet, customerNations.cell.left)
            link(base.nation.outlet, customerNations.cell.right)
            link(customerNations.cell.outlet, pair.cell.inlet)
            link(pair.cell.outlet, revenueFold.cell.inlet)

            link(lineitems.cell.outlet, lineitemRows.cell.inlet)
            link(lineSuppliers.cell.outlet, lineSupplierRows.cell.inlet)
            link(lineSupplierOrders.cell.outlet, lineSupplierOrderRows.cell.inlet)
            link(lineSupplierOrderCustomers.cell.outlet, lineSupplierOrderCustomerRows.cell.inlet)
            link(supplierNations.cell.outlet, supplierNationRows.cell.inlet)
            link(customerNations.cell.outlet, customerNationRows.cell.inlet)
            link(pair.cell.outlet, pairRows.cell.inlet)
            link(revenueFold.cell.outlet, revenueRows.cell.inlet)

            sets["q07-lineitems"] = lineitemRows.cell
            sets["q07-line-suppliers"] = lineSupplierRows.cell
            sets["q07-line-supplier-orders"] = lineSupplierOrderRows.cell
            sets["q07-line-supplier-order-customers"] = lineSupplierOrderCustomerRows.cell
            sets["q07-supplier-nations"] = supplierNationRows.cell
            sets["q07-customer-nations"] = customerNationRows.cell
            sets["q07-nation-pair"] = pairRows.cell
            revenue = revenueRows.cell
        }

        return Q07Query(sets, revenue)
    }
}

private class Q07Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val revenue: GroupCollectorCell<Q07Group>,
) : CollectedQuery(
    query = TpchQuery.Q07,
    setOutlets = sets,
    groupOutlets = mapOf("q07-revenue" to revenue),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("revenue"),
        rows = revenue.values().entries.associateTo(linkedMapOf()) { (key, value) ->
            listOf<Any>(key.supplierNation, key.customerNation, key.year) to longArrayOf(value)
        },
    )
}
