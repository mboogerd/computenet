package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Part
import civictech.bench.tpch.Q14Params
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

/** Fold input retaining the PART and LINEITEM primary keys. */
internal data class Q14PartLine(
    val partKey: Long,
    override val orderKey: Long,
    val lineNumber: Int,
    val partType: String,
    val revenueS4: Long,
) : OrderKeyCarrier, Serializable

/**
 * Promotion Effect Query from TPC-H Revision 3.0.1 §2.4.14.
 * Sources: PART, LINEITEM.
 */
class Q14Builder(
    private val params: Q14Params = Q14Params(),
    rowPayload: Class<*> = Q14PartLine::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q14
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val endDate = LocalDate.ofEpochDay(params.date.toLong()).plusMonths(1).toEpochDay().toInt()
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var promoRevenue: GroupCollectorCell<String>
        lateinit var totalRevenue: GroupCollectorCell<String>

        graph(host) {
            adopt(base.lineitem)
            adopt(base.part)

            val lines = spawn("q14-lines") { ref ->
                FilterCell(ref = ref) { line: Lineitem ->
                    line.shipDate >= params.date && line.shipDate < endDate
                }
            }
            val partLines = spawn("q14-part-lines") { ref ->
                JoinSetCell<Lineitem, Part, Long, Q14PartLine>(
                    ref = ref,
                    leftKey = Lineitem::partKey,
                    rightKey = Part::partKey,
                    combine = { line, part ->
                        Q14PartLine(
                            partKey = part.partKey,
                            orderKey = line.orderKey,
                            lineNumber = line.lineNumber,
                            partType = part.type,
                            revenueS4 = TpchDecimal.revenueS4(line.extendedPrice, line.discount),
                        )
                    },
                )
            }
            val promoFold = spawnFold("q14-promo-revenue", Q14PartLine::class) { ref ->
                GroupByCell.global(
                    ref = ref,
                    aggregator = Aggregators.sumOf<Q14PartLine> {
                        if (it.partType.startsWith("PROMO")) it.revenueS4 else 0L
                    },
                )
            }
            val totalFold = spawnFold("q14-total-revenue", Q14PartLine::class) { ref ->
                GroupByCell.global(
                    ref = ref,
                    aggregator = Aggregators.sumOf(Q14PartLine::revenueS4),
                )
            }

            val lineRows = spawn("q14-lines-collector") { ref -> SetRowsCollectorCell<Lineitem>(ref) }
            val partLineRows = spawn("q14-part-lines-collector") { ref -> SetRowsCollectorCell<Q14PartLine>(ref) }
            val promoRows = spawn("q14-promo-revenue-collector") { ref -> GroupCollectorCell<String>(ref) }
            val totalRows = spawn("q14-total-revenue-collector") { ref -> GroupCollectorCell<String>(ref) }

            link(base.lineitem.outlet, lines.cell.inlet)
            link(lines.cell.outlet, partLines.cell.left)
            link(base.part.outlet, partLines.cell.right)
            link(partLines.cell.outlet, promoFold.cell.inlet)
            link(partLines.cell.outlet, totalFold.cell.inlet)

            link(lines.cell.outlet, lineRows.cell.inlet)
            link(partLines.cell.outlet, partLineRows.cell.inlet)
            link(promoFold.cell.outlet, promoRows.cell.inlet)
            link(totalFold.cell.outlet, totalRows.cell.inlet)

            sets["q14-lines"] = lineRows.cell
            sets["q14-part-lines"] = partLineRows.cell
            promoRevenue = promoRows.cell
            totalRevenue = totalRows.cell
        }

        return Q14Query(sets, promoRevenue, totalRevenue)
    }
}

private class Q14Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val promoRevenue: GroupCollectorCell<String>,
    private val totalRevenue: GroupCollectorCell<String>,
) : CollectedQuery(
    query = TpchQuery.Q14,
    setOutlets = sets,
    groupOutlets = mapOf(
        "q14-promo-revenue" to promoRevenue,
        "q14-total-revenue" to totalRevenue,
    ),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("promo_num", "promo_den"),
        rows = mapOf(
            emptyList<Any>() to longArrayOf(
                promoRevenue.values()["global"] ?: 0L,
                totalRevenue.values()["global"] ?: 0L,
            ),
        ),
    )
}
