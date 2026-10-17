package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Orders
import civictech.bench.tpch.Q12Params
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

/** Fold input retaining the ORDERS and LINEITEM primary keys. */
internal data class Q12OrderLine(
    override val orderKey: Long,
    val lineNumber: Int,
    val shipMode: String,
    val orderPriority: String,
) : OrderKeyCarrier, Serializable

/**
 * Shipping Modes and Order Priority Query from TPC-H Revision 3.0.1 §2.4.12.
 * Sources: ORDERS, LINEITEM.
 */
class Q12Builder(
    private val params: Q12Params = Q12Params(),
    rowPayload: Class<*> = Q12OrderLine::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q12
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusYears(1).toEpochDay().toInt()
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var highCounts: GroupCollectorCell<String>
        lateinit var lowCounts: GroupCollectorCell<String>

        graph(host) {
            adopt(base.orders)
            adopt(base.lineitem)

            val lines = spawn("q12-lines") { ref ->
                FilterCell(ref = ref) { line: Lineitem ->
                    (line.shipMode == params.shipMode1 || line.shipMode == params.shipMode2) &&
                        line.commitDate < line.receiptDate &&
                        line.shipDate < line.commitDate &&
                        line.receiptDate >= params.date && line.receiptDate < endDate
                }
            }
            val orderLines = spawn("q12-order-lines") { ref ->
                JoinSetCell<Lineitem, Orders, Long, Q12OrderLine>(
                    ref = ref,
                    leftKey = Lineitem::orderKey,
                    rightKey = Orders::orderKey,
                    combine = { line, order ->
                        Q12OrderLine(
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            shipMode = line.shipMode,
                            orderPriority = order.orderPriority,
                        )
                    },
                )
            }
            val highFold = spawnFold("q12-high-line-count", Q12OrderLine::class) { ref ->
                GroupByCell<Q12OrderLine, String, Long, Long>(
                    ref = ref,
                    keyFn = Q12OrderLine::shipMode,
                    aggregator = Aggregators.sumOf { row -> if (row.isHighPriority()) 1L else 0L },
                )
            }
            val lowFold = spawnFold("q12-low-line-count", Q12OrderLine::class) { ref ->
                GroupByCell<Q12OrderLine, String, Long, Long>(
                    ref = ref,
                    keyFn = Q12OrderLine::shipMode,
                    aggregator = Aggregators.sumOf { row -> if (row.isHighPriority()) 0L else 1L },
                )
            }

            val lineRows = spawn("q12-lines-collector") { ref -> SetRowsCollectorCell<Lineitem>(ref) }
            val orderLineRows = spawn("q12-order-lines-collector") { ref -> SetRowsCollectorCell<Q12OrderLine>(ref) }
            val highRows = spawn("q12-high-line-count-collector") { ref -> GroupCollectorCell<String>(ref) }
            val lowRows = spawn("q12-low-line-count-collector") { ref -> GroupCollectorCell<String>(ref) }

            link(base.lineitem.outlet, lines.cell.inlet)
            link(lines.cell.outlet, orderLines.cell.left)
            link(base.orders.outlet, orderLines.cell.right)
            link(orderLines.cell.outlet, highFold.cell.inlet)
            link(orderLines.cell.outlet, lowFold.cell.inlet)

            link(lines.cell.outlet, lineRows.cell.inlet)
            link(orderLines.cell.outlet, orderLineRows.cell.inlet)
            link(highFold.cell.outlet, highRows.cell.inlet)
            link(lowFold.cell.outlet, lowRows.cell.inlet)

            sets["q12-lines"] = lineRows.cell
            sets["q12-order-lines"] = orderLineRows.cell
            highCounts = highRows.cell
            lowCounts = lowRows.cell
        }

        return Q12Query(sets, highCounts, lowCounts)
    }
}

private fun Q12OrderLine.isHighPriority(): Boolean =
    orderPriority == "1-URGENT" || orderPriority == "2-HIGH"

private class Q12Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val highCounts: GroupCollectorCell<String>,
    private val lowCounts: GroupCollectorCell<String>,
) : CollectedQuery(
    query = TpchQuery.Q12,
    setOutlets = sets,
    groupOutlets = mapOf(
        "q12-high-line-count" to highCounts,
        "q12-low-line-count" to lowCounts,
    ),
) {
    override fun read(): ViewResult {
        val keys = linkedSetOf<String>().apply {
            addAll(highCounts.values().keys)
            addAll(lowCounts.values().keys)
        }
        return ViewResult(
            columns = listOf("high_line_count", "low_line_count"),
            rows = keys.associateTo(linkedMapOf()) { shipMode ->
                listOf<Any>(shipMode) to longArrayOf(
                    highCounts.values()[shipMode] ?: 0L,
                    lowCounts.values()[shipMode] ?: 0L,
                )
            },
        )
    }
}
