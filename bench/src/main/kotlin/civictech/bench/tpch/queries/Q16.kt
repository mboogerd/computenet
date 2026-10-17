package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Part
import civictech.bench.tpch.PartSupp
import civictech.bench.tpch.Q16Params
import civictech.bench.tpch.Supplier
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.cell.data.Aggregators
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.graph.graph
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.io.Serializable

internal data class Q16PartSupply(
    val brand: String,
    val type: String,
    val size: Int,
    val suppKey: Long,
) : Serializable

/** Parts/Supplier Relationship Query from TPC-H Revision 3.0.1 §2.4.16. */
class Q16Builder(
    private val params: Q16Params = Q16Params(),
    rowPayload: Class<*> = Q16PartSupply::class.java,
) : QueryGraphBuilder(rowPayload) {
    override val query: TpchQuery = TpchQuery.Q16
    override val referenceParams: Any = params

    override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery {
        val complaintPattern = Regex("Customer.*Complaints", RegexOption.DOT_MATCHES_ALL)
        val sets = linkedMapOf<String, SetRowsCollectorCell<*>>()
        lateinit var supplierCount: GroupCollectorCell<Q16Group>

        graph(host) {
            adopt(base.part)
            adopt(base.partSupp)
            adopt(base.supplier)

            val parts = spawn("q16-parts") { ref ->
                FilterCell(ref = ref) { part: Part ->
                    part.brand != params.brand &&
                        !part.type.startsWith(params.type) &&
                        part.size in params.sizes
                }
            }
            val complaints = spawn("q16-complaints") { ref ->
                FilterCell(ref = ref) { supplier: Supplier -> complaintPattern.containsMatchIn(supplier.comment) }
            }
            val partSupplies = spawn("q16-part-supplies") { ref ->
                JoinSetCell<Part, PartSupp, Long, Q16PartSupply>(
                    ref = ref,
                    leftKey = Part::partKey,
                    rightKey = PartSupp::partKey,
                    combine = { part, supply ->
                        Q16PartSupply(
                            brand = part.brand,
                            type = part.type,
                            size = part.size,
                            suppKey = supply.suppKey,
                        )
                    },
                )
            }
            val eligible = spawn("q16-eligible") { ref ->
                SemiJoinCell<Q16PartSupply, Supplier, Long>(
                    ref = ref,
                    leftKey = Q16PartSupply::suppKey,
                    rightKey = Supplier::suppKey,
                    negated = true,
                )
            }
            val supplierCountFold = spawnFold("q16-supplier-count", Q16PartSupply::class) { ref ->
                GroupByCell<Q16PartSupply, Q16Group, Long, java.util.TreeMap<Long, Int>>(
                    ref = ref,
                    keyFn = { row -> Q16Group(row.brand, row.type, row.size) },
                    aggregator = Aggregators.countDistinct(Q16PartSupply::suppKey),
                )
            }

            val partRows = spawn("q16-parts-collector") { ref -> SetRowsCollectorCell<Part>(ref) }
            val complaintRows = spawn("q16-complaints-collector") { ref -> SetRowsCollectorCell<Supplier>(ref) }
            val partSupplyRows = spawn("q16-part-supplies-collector") { ref ->
                SetRowsCollectorCell<Q16PartSupply>(ref)
            }
            val eligibleRows = spawn("q16-eligible-collector") { ref ->
                SetRowsCollectorCell<Q16PartSupply>(ref)
            }
            val supplierCountRows = spawn("q16-supplier-count-collector") { ref ->
                GroupCollectorCell<Q16Group>(ref)
            }

            link(base.part.outlet, parts.cell.inlet)
            link(base.supplier.outlet, complaints.cell.inlet)
            link(parts.cell.outlet, partSupplies.cell.left)
            link(base.partSupp.outlet, partSupplies.cell.right)
            link(partSupplies.cell.outlet, eligible.cell.left)
            link(complaints.cell.outlet, eligible.cell.right)
            link(eligible.cell.outlet, supplierCountFold.cell.inlet)

            link(parts.cell.outlet, partRows.cell.inlet)
            link(complaints.cell.outlet, complaintRows.cell.inlet)
            link(partSupplies.cell.outlet, partSupplyRows.cell.inlet)
            link(eligible.cell.outlet, eligibleRows.cell.inlet)
            link(supplierCountFold.cell.outlet, supplierCountRows.cell.inlet)

            sets["q16-parts"] = partRows.cell
            sets["q16-complaints"] = complaintRows.cell
            sets["q16-part-supplies"] = partSupplyRows.cell
            sets["q16-eligible"] = eligibleRows.cell
            supplierCount = supplierCountRows.cell
        }

        return Q16Query(sets, supplierCount)
    }
}

internal data class Q16Group(
    val brand: String,
    val type: String,
    val size: Int,
) : Serializable

private class Q16Query(
    sets: Map<String, SetRowsCollectorCell<*>>,
    private val supplierCount: GroupCollectorCell<Q16Group>,
) : CollectedQuery(
    query = TpchQuery.Q16,
    setOutlets = sets,
    groupOutlets = mapOf("q16-supplier-count" to supplierCount),
) {
    override fun read(): ViewResult = ViewResult(
        columns = listOf("supplier_cnt"),
        rows = supplierCount.values().entries.associateTo(linkedMapOf()) { (key, value) ->
            listOf<Any>(key.brand, key.type, key.size) to longArrayOf(value)
        },
    )
}
