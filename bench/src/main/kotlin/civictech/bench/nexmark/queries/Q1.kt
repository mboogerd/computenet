package civictech.bench.nexmark.queries

import civictech.bench.nexmark.Bid
import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NexmarkQuery
import civictech.bench.nexmark.NexmarkResult
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use

/** Currency conversion over bids: price' = price * 89 / 100. */
class Q1Builder(
    policy: LatenessPolicy? = null,
    eventPayload: Class<*> = Bid::class.java,
) : NexmarkQueryBuilder(eventPayload, policy) {
    override val query: NexmarkQuery = NexmarkQuery.Q1

    init {
        validatePolicyFor(query)
    }

    override fun attach(host: Use<HostManagementApi>, streams: EventStreams): MaintainedQuery {
        lateinit var ingress: SetRowsCollectorCell<Bid>
        lateinit var output: SetRowsCollectorCell<Bid>

        graph(host) {
            adopt(streams.bids)
            val ingressCollector = spawn("q1-bids-collector") { ref -> SetRowsCollectorCell<Bid>(ref) }
            val mapped = spawn("q1-map") { ref ->
                FlatMapSetCell<Bid, Bid>(ref = ref) { bid ->
                    listOf(bid.copy(price = bid.price * 89L / 100L))
                }
            }
            val outputCollector = spawn("q1-output-collector") { ref -> SetRowsCollectorCell<Bid>(ref) }

            link(streams.bids.outlet, ingressCollector.cell.inlet)
            link(streams.bids.outlet, mapped.cell.inlet)
            link(mapped.cell.outlet, outputCollector.cell.inlet)
            ingress = ingressCollector.cell
            output = outputCollector.cell
        }

        return Q1Query(ingress, output, recordedMaxFanOut)
    }
}

private class Q1Query(
    ingress: SetRowsCollectorCell<Bid>,
    private val output: SetRowsCollectorCell<Bid>,
    maxFanOut: Int,
) : CollectedQuery(
    query = NexmarkQuery.Q1,
    outlets = linkedMapOf(
        "q1-bids" to ingress,
        "q1-output" to output,
    ),
    maxFanOut = maxFanOut,
) {
    override fun read(): NexmarkResult = NexmarkResult(
        columns = listOf("id", "auction", "bidder", "price", "dateTime", "extra"),
        rows = output.rows().mapTo(linkedSetOf()) { bid ->
            listOf<Any>(bid.id, bid.auction, bid.bidder, bid.price, bid.dateTime, bid.extra)
        },
    )
}
