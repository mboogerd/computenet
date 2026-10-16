package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Part
import civictech.bench.tpch.Q19Params
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

/** Fold input retaining the PART and LINEITEM primary keys. */
internal data class Q19PartLine(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val brand: String,
    val container: String,
    val size: Int,
    val quantity: Long,
    val shipMode: String,
    val shipInstruct: String,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

/**
 * Discounted Revenue Query from TPC-H Revision 3.0.1 §2.4.19.
 * Sources: PART, LINEITEM.
 */
class Q19Builder(
    private val params: Q19Params = Q19Params(),
    rowPayload: Class<*> = Q19PartLine::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q19
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var revenue: GroupCollectorCell<String>

        graph(host) {
            adopt(base.lineitem)
            adopt(base.part)

            val partLines = spawn("q19-part-lines") { ref ->
                JoinSetCell<Lineitem, Part, Long, Q19PartLine>(
                    ref = ref,
                    leftKey = Lineitem::partKey,
                    rightKey = Part::partKey,
                    combine = { line, part ->
                        Q19PartLine(
                            partKey = part.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            brand = part.brand,
                            container = part.container,
                            size = part.size,
                            quantity = line.quantity,
                            shipMode = line.shipMode,
                            shipInstruct = line.shipInstruct,
                            revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                        )
                    },
                )
            }
            val selected = spawn("q19-selected") { ref ->
                FilterCell(ref = ref) { row: Q19PartLine -> row.matches(params) }
            }
            val revenueFold = spawn("q19-revenue") { ref ->
                GroupByCell.global(
                    ref = ref,
                    aggregator = Aggregators.sumOf(Q19PartLine::revenueS4),
                )
            }

            val partLineRows = spawn("q19-part-lines-collector") { ref -> SetRowsCollectorCell<Q19PartLine>(ref) }
            val selectedRows = spawn("q19-selected-collector") { ref -> SetRowsCollectorCell<Q19PartLine>(ref) }
            val revenueRows = spawn("q19-revenue-collector") { ref -> GroupCollectorCell<String>(ref) }

            link(base.lineitem.outlet, partLines.cell.left)
            link(base.part.outlet, partLines.cell.right)
            link(partLines.cell.outlet, selected.cell.inlet)
            link(selected.cell.outlet, revenueFold.cell.inlet)

            link(partLines.cell.outlet, partLineRows.cell.inlet)
            link(selected.cell.outlet, selectedRows.cell.inlet)
            link(revenueFold.cell.outlet, revenueRows.cell.inlet)

            sets["q19-part-lines"] = partLineRows.cell
            sets["q19-selected"] = selectedRows.cell
            revenue = revenueRows.cell
        }

        return Q19Query(sets, revenue)
    }
}

private fun Q19PartLine.matches(params: Q19Params): Boolean {
    if (shipMode !in setOf("AIR", "AIR REG") || shipInstruct != "DELIVER IN PERSON") return false
    return (
        brand == params.brand1 && container in setOf("SM CASE", "SM BOX", "SM PACK", "SM PKG") &&
            quantity in params.quantity1..params.quantity1 + 10L && size in 1..5
        ) || (
        brand == params.brand2 && container in setOf("MED BAG", "MED BOX", "MED PKG", "MED PACK") &&
            quantity in params.quantity2..params.quantity2 + 10L && size in 1..10
        ) || (
        brand == params.brand3 && container in setOf("LG CASE", "LG BOX", "LG PACK", "LG PKG") &&
            quantity in params.quantity3..params.quantity3 + 10L && size in 1..15
        )
}

private class Q19Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val revenue: GroupCollectorCell<String>,
) : CollectedQuery(
    query = TpchQuery.Q19,
    setOutlets = sets,
    groupOutlets = mapOf("q19-revenue" to revenue),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("revenue"),
        rows = mapOf(emptyList<Any>() to longArrayOf(revenue.values()["global"] ?: 0L)),
    )
}
