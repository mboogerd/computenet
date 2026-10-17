package civictech.bench.nexmark.queries

import civictech.bench.nexmark.AuctionPrice
import civictech.bench.nexmark.Bid
import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NexmarkQuery
import civictech.bench.nexmark.NexmarkResult
import civictech.bench.nexmark.NexmarkSpec
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use

/** Selection of bids whose auction id is divisible by the Beam auction skip. */
class Q2Builder(
    policy: LatenessPolicy? = null,
    eventPayload: Class<*> = Bid::class.java,
) : NexmarkQueryBuilder(eventPayload, policy) {
    override val query: NexmarkQuery = NexmarkQuery.Q2

    init {
        validatePolicyFor(query)
    }

    override fun attach(host: Use<HostManagementApi>, streams: EventStreams): MaintainedQuery {
        lateinit var ingress: SetRowsCollectorCell<Bid>
        lateinit var filtered: SetRowsCollectorCell<Bid>
        lateinit var output: SetRowsCollectorCell<AuctionPrice>

        graph(host) {
            adopt(streams.bids)
            val ingressCollector = spawn("q2-bids-collector") { ref -> SetRowsCollectorCell<Bid>(ref) }
            val filter = spawn("q2-filter") { ref ->
                FilterCell(ref = ref) { bid: Bid -> bid.auction % NexmarkSpec.AUCTION_SKIP == 0L }
            }
            val filteredCollector = spawn("q2-filtered-collector") { ref -> SetRowsCollectorCell<Bid>(ref) }
            val mapped = spawn("q2-map") { ref ->
                FlatMapSetCell<Bid, AuctionPrice>(ref = ref) { bid ->
                    listOf(AuctionPrice(bid.auction, bid.price))
                }
            }
            val outputCollector = spawn("q2-output-collector") { ref ->
                SetRowsCollectorCell<AuctionPrice>(ref)
            }

            link(streams.bids.outlet, ingressCollector.cell.inlet)
            link(streams.bids.outlet, filter.cell.inlet)
            link(filter.cell.outlet, filteredCollector.cell.inlet)
            link(filter.cell.outlet, mapped.cell.inlet)
            link(mapped.cell.outlet, outputCollector.cell.inlet)
            ingress = ingressCollector.cell
            filtered = filteredCollector.cell
            output = outputCollector.cell
        }

        return Q2Query(ingress, filtered, output, recordedMaxFanOut)
    }
}

private class Q2Query(
    ingress: SetRowsCollectorCell<Bid>,
    filtered: SetRowsCollectorCell<Bid>,
    private val output: SetRowsCollectorCell<AuctionPrice>,
    maxFanOut: Int,
) : CollectedQuery(
    query = NexmarkQuery.Q2,
    outlets = linkedMapOf(
        "q2-bids" to ingress,
        "q2-filtered" to filtered,
        "q2-output" to output,
    ),
    maxFanOut = maxFanOut,
) {
    override fun read(): NexmarkResult = NexmarkResult(
        columns = listOf("auction", "price"),
        rows = output.rows().mapTo(linkedSetOf()) { row ->
            listOf<Any>(row.auction, row.price)
        },
    )
}
