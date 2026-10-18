package civictech.bench.nexmark.queries

import civictech.bench.nexmark.Bid
import civictech.bench.nexmark.BidTime
import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NexmarkQuery
import civictech.bench.nexmark.NexmarkResult
import civictech.bench.nexmark.NexmarkSpec
import civictech.cell.data.Aggregators
import civictech.cell.data.SortSpec
import civictech.cell.data.SortSpec.SortKey.Companion.desc
import civictech.cell.data.Windows
import civictech.cell.data.op.GroupByCell
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import java.util.TreeMap

/** Highest bid per Beam-sized tumbling window, with a total-order tie-break. */
class Q7Builder(
    policy: LatenessPolicy? = null,
    eventPayload: Class<*> = Bid::class.java,
) : WindowedQueryBuilder(eventPayload, policy) {
    override val query: NexmarkQuery = NexmarkQuery.Q7

    override fun attach(host: Use<HostManagementApi>, streams: EventStreams): MaintainedQuery {
        val tumbling = Windows.tumbling(NexmarkSpec.WINDOW_MS)
        lateinit var ingress: SetRowsCollectorCell<Bid>
        lateinit var output: WindowGroupCollectorCell<Long, List<Bid>>
        var accounting: WindowedAccounting? = null

        graph(host) {
            adopt(streams.bids)
            val ingressCollector = spawn("q7-bids-collector") { ref -> SetRowsCollectorCell<Bid>(ref) }
            val grouped = spawn("q7-window-max") { ref ->
                GroupByCell<Bid, Long, List<Bid>, TreeMap<Bid, Int>>(
                    ref = ref,
                    keyFn = { bid -> tumbling(bid.dateTime) },
                    aggregator = Aggregators.topKBy(
                        1,
                        SortSpec.by(desc(Bid::price), tieBreak = Bid::id),
                    ),
                    lateness = policy?.let { Windows.Lateness(BidTime, it.lateness) },
                    keyTime = policy?.let { WindowEnd(NexmarkSpec.WINDOW_MS) },
                )
            }
            val outputCollector = spawn("q7-output-collector") { ref ->
                WindowGroupCollectorCell<Long, List<Bid>>(ref)
            }

            if (policy != null) {
                val waterline = spawnWaterline("q7-waterline", streams)
                val lateCollector = spawn("q7-late-collector") { ref -> LateCollectorCell<Bid>(ref) }
                // The floor arm reaches the operator before its data arm.
                link(waterline.cell.outlet, grouped.cell.waterline)
                link(grouped.cell.late, lateCollector.cell.inlet)
                accounting = WindowedAccounting.group(
                    grouped.cell,
                    lateCollector.cell,
                    evicted = { outputCollector.cell.removals },
                )
            }

            link(streams.bids.outlet, ingressCollector.cell.inlet)
            link(streams.bids.outlet, grouped.cell.inlet)
            link(grouped.cell.outlet, outputCollector.cell.inlet)
            ingress = ingressCollector.cell
            output = outputCollector.cell
        }

        return Q7Query(ingress, output, accounting, recordedMaxFanOut)
    }
}

private class Q7Query(
    ingress: SetRowsCollectorCell<Bid>,
    private val output: WindowGroupCollectorCell<Long, List<Bid>>,
    private val accounting: WindowedAccounting?,
    maxFanOut: Int,
) : CollectedQuery(
    query = NexmarkQuery.Q7,
    outlets = linkedMapOf(
        "q7-bids" to ingress,
        "q7-windows" to output,
    ),
    maxFanOut = maxFanOut,
) {
    override fun read(): NexmarkResult = NexmarkResult(
        columns = listOf("windowStart", "id", "auction", "bidder", "price", "dateTime"),
        rows = output.values().mapTo(linkedSetOf()) { (windowStart, bids) ->
            val bid = bids.single()
            listOf<Any>(
                windowStart,
                bid.id,
                bid.auction,
                bid.bidder,
                bid.price,
                bid.dateTime,
            )
        },
    )

    override fun lateness() = accounting?.read()
}
