package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Nation
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Part
import civictech.bench.tpch.PartSupp
import civictech.bench.tpch.Q9Params
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

internal data class Q09PartLine(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val quantity: Long,
    val extendedPrice: Long,
    val discount: Int,
) : OrderKeyCarrier, Serializable

internal data class Q09PartLineSupply(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val suppKey: Long,
    val quantity: Long,
    val extendedPrice: Long,
    val discount: Int,
    val supplyCost: Long,
) : OrderKeyCarrier, Serializable

internal data class Q09PartLineSupplySupplier(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val quantity: Long,
    val extendedPrice: Long,
    val discount: Int,
    val supplyCost: Long,
    val nationKey: Int,
) : OrderKeyCarrier, Serializable

internal data class Q09PartLineSupplySupplierOrder(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val quantity: Long,
    val extendedPrice: Long,
    val discount: Int,
    val supplyCost: Long,
    val nationKey: Int,
    val orderDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q09Complete(
    override val orderKey: Long,
    val lineNumber: Int,
    val supplyCost: Long,
    val quantity: Long,
    val extendedPrice: Long,
    val discount: Int,
    val nationName: String,
    val orderDate: Int,
) : OrderKeyCarrier, Serializable

internal data class Q09Group(
    val nationName: String,
    val year: Int,
) : Serializable

/** Product Type Profit Measure Query from TPC-H Revision 3.0.1 §2.4.9. */
class Q09Builder(
    private val params: Q9Params = Q9Params(),
    rowPayload: Class<*> = Q09Complete::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q09
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var profit: GroupCollectorCell<Q09Group>

        graph(host) {
            adopt(base.part)
            adopt(base.lineitem)
            adopt(base.partSupp)
            adopt(base.supplier)
            adopt(base.orders)
            adopt(base.nation)

            val parts = spawn("q09-parts") { ref ->
                FilterCell(ref = ref) { part: Part -> params.color in part.name }
            }
            val partLines = spawn("q09-part-lines") { ref ->
                JoinSetCell<Part, Lineitem, Long, Q09PartLine>(
                    ref = ref,
                    leftKey = Part::partKey,
                    rightKey = Lineitem::partKey,
                    combine = { _, line ->
                        Q09PartLine(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            quantity = line.quantity,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                        )
                    },
                )
            }
            val partLineSupplies = spawn("q09-part-line-supplies") { ref ->
                JoinSetCell<Q09PartLine, PartSupp, Pair<Long, Long>, Q09PartLineSupply>(
                    ref = ref,
                    leftKey = { line -> line.partKey to line.suppKey },
                    rightKey = { supply -> supply.partKey to supply.suppKey },
                    combine = { line, supply ->
                        Q09PartLineSupply(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            suppKey = line.suppKey,
                            quantity = line.quantity,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplyCost = supply.supplyCost,
                        )
                    },
                )
            }
            val partLineSupplySuppliers = spawn("q09-part-line-supply-suppliers") { ref ->
                JoinSetCell<Q09PartLineSupply, Supplier, Long, Q09PartLineSupplySupplier>(
                    ref = ref,
                    leftKey = Q09PartLineSupply::suppKey,
                    rightKey = Supplier::suppKey,
                    combine = { line, supplier ->
                        Q09PartLineSupplySupplier(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            quantity = line.quantity,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplyCost = line.supplyCost,
                            nationKey = supplier.nationKey,
                        )
                    },
                )
            }
            val partLineSupplySupplierOrders = spawn("q09-part-line-supply-supplier-orders") { ref ->
                JoinSetCell<Q09PartLineSupplySupplier, Orders, Long, Q09PartLineSupplySupplierOrder>(
                    ref = ref,
                    leftKey = Q09PartLineSupplySupplier::orderKey,
                    rightKey = Orders::orderKey,
                    combine = { line, order ->
                        Q09PartLineSupplySupplierOrder(
                            partKey = line.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            quantity = line.quantity,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            supplyCost = line.supplyCost,
                            nationKey = line.nationKey,
                            orderDate = order.orderDate,
                        )
                    },
                )
            }
            val complete = spawn("q09-complete") { ref ->
                JoinSetCell<Q09PartLineSupplySupplierOrder, Nation, Int, Q09Complete>(
                    ref = ref,
                    leftKey = Q09PartLineSupplySupplierOrder::nationKey,
                    rightKey = Nation::nationKey,
                    combine = { line, nation ->
                        Q09Complete(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            supplyCost = line.supplyCost,
                            quantity = line.quantity,
                            extendedPrice = line.extendedPrice,
                            discount = line.discount,
                            nationName = nation.name,
                            orderDate = line.orderDate,
                        )
                    },
                )
            }
            val profitFold = spawnFold("q09-profit", Q09Complete::class) { ref ->
                GroupByCell<Q09Complete, Q09Group, Long, Long>(
                    ref = ref,
                    keyFn = { row ->
                        Q09Group(
                            nationName = row.nationName,
                            year = LocalDate.ofEpochDay(row.orderDate.toLong()).year,
                        )
                    },
                    aggregator = Aggregators.sumOf<Q09Complete> {
                        TpchDecimal.revenueS4(it.extendedPrice, it.discount) -
                            it.supplyCost * it.quantity * 100L
                    },
                )
            }

            val partRows = spawn("q09-parts-collector") { ref -> SetRowsCollectorCell<Part>(ref) }
            val partLineRows = spawn("q09-part-lines-collector") { ref -> SetRowsCollectorCell<Q09PartLine>(ref) }
            val partLineSupplyRows = spawn("q09-part-line-supplies-collector") { ref ->
                SetRowsCollectorCell<Q09PartLineSupply>(ref)
            }
            val partLineSupplySupplierRows = spawn("q09-part-line-supply-suppliers-collector") { ref ->
                SetRowsCollectorCell<Q09PartLineSupplySupplier>(ref)
            }
            val partLineSupplySupplierOrderRows = spawn("q09-part-line-supply-supplier-orders-collector") { ref ->
                SetRowsCollectorCell<Q09PartLineSupplySupplierOrder>(ref)
            }
            val completeRows = spawn("q09-complete-collector") { ref -> SetRowsCollectorCell<Q09Complete>(ref) }
            val profitRows = spawn("q09-profit-collector") { ref -> GroupCollectorCell<Q09Group>(ref) }

            link(base.part.outlet, parts.cell.inlet)
            link(parts.cell.outlet, partLines.cell.left)
            link(base.lineitem.outlet, partLines.cell.right)
            link(partLines.cell.outlet, partLineSupplies.cell.left)
            link(base.partSupp.outlet, partLineSupplies.cell.right)
            link(partLineSupplies.cell.outlet, partLineSupplySuppliers.cell.left)
            link(base.supplier.outlet, partLineSupplySuppliers.cell.right)
            link(partLineSupplySuppliers.cell.outlet, partLineSupplySupplierOrders.cell.left)
            link(base.orders.outlet, partLineSupplySupplierOrders.cell.right)
            link(partLineSupplySupplierOrders.cell.outlet, complete.cell.left)
            link(base.nation.outlet, complete.cell.right)
            link(complete.cell.outlet, profitFold.cell.inlet)

            link(parts.cell.outlet, partRows.cell.inlet)
            link(partLines.cell.outlet, partLineRows.cell.inlet)
            link(partLineSupplies.cell.outlet, partLineSupplyRows.cell.inlet)
            link(partLineSupplySuppliers.cell.outlet, partLineSupplySupplierRows.cell.inlet)
            link(partLineSupplySupplierOrders.cell.outlet, partLineSupplySupplierOrderRows.cell.inlet)
            link(complete.cell.outlet, completeRows.cell.inlet)
            link(profitFold.cell.outlet, profitRows.cell.inlet)

            sets["q09-parts"] = partRows.cell
            sets["q09-part-lines"] = partLineRows.cell
            sets["q09-part-line-supplies"] = partLineSupplyRows.cell
            sets["q09-part-line-supply-suppliers"] = partLineSupplySupplierRows.cell
            sets["q09-part-line-supply-supplier-orders"] = partLineSupplySupplierOrderRows.cell
            sets["q09-complete"] = completeRows.cell
            profit = profitRows.cell
        }

        return Q09Query(sets, profit)
    }
}

private class Q09Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val profit: GroupCollectorCell<Q09Group>,
) : CollectedQuery(
    query = TpchQuery.Q09,
    setOutlets = sets,
    groupOutlets = mapOf("q09-profit" to profit),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("sum_profit"),
        rows = profit.values().entries.associateTo(linkedMapOf()) { (key, value) ->
            listOf<Any>(key.nationName, key.year) to longArrayOf(value)
        },
    )
}
