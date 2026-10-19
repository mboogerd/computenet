package civictech.bench.nexmark.queries

import civictech.bench.nexmark.Auction
import civictech.bench.nexmark.AuctionTime
import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.Event
import civictech.bench.nexmark.IdNameReserve
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NexmarkQuery
import civictech.bench.nexmark.NexmarkResult
import civictech.bench.nexmark.NexmarkSpec
import civictech.bench.nexmark.Person
import civictech.bench.nexmark.PersonTime
import civictech.bench.nexmark.WindowKey
import civictech.cell.data.Windows
import civictech.cell.data.op.JoinSetCell
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use

/** Persons joined to auctions by identity and Beam-sized tumbling window. */
class Q8Builder(
    policy: LatenessPolicy? = null,
    eventPayload: Class<*> = Event::class.java,
) : WindowedQueryBuilder(eventPayload, policy) {
    override val query: NexmarkQuery = NexmarkQuery.Q8

    override fun attach(host: Use<HostManagementApi>, streams: EventStreams): MaintainedQuery {
        val tumbling = Windows.tumbling(NexmarkSpec.WINDOW_MS)
        lateinit var people: SetRowsCollectorCell<Person>
        lateinit var auctions: SetRowsCollectorCell<Auction>
        lateinit var output: WindowSetCollectorCell<IdNameReserve>
        var accounting: WindowedAccounting? = null

        graph(host) {
            adopt(streams.persons)
            adopt(streams.auctions)
            val peopleCollector = spawn("q8-people-collector") { ref -> SetRowsCollectorCell<Person>(ref) }
            val auctionCollector = spawn("q8-auctions-collector") { ref -> SetRowsCollectorCell<Auction>(ref) }
            val joined = spawn("q8-window-join") { ref ->
                JoinSetCell<Person, Auction, WindowKey, IdNameReserve>(
                    ref = ref,
                    leftKey = { person -> WindowKey(person.id, tumbling(person.dateTime)) },
                    rightKey = { auction -> WindowKey(auction.seller, tumbling(auction.dateTime)) },
                    leftLateness = policy?.let { Windows.Lateness(PersonTime, it.lateness) },
                    rightLateness = policy?.let { Windows.Lateness(AuctionTime, it.lateness) },
                    combine = { person, auction ->
                        IdNameReserve(person.id, person.name, auction.reserve)
                    },
                )
            }
            val outputCollector = spawn("q8-output-collector") { ref ->
                WindowSetCollectorCell<IdNameReserve>(ref)
            }

            if (policy != null) {
                val waterline = spawnWaterline("q8-waterline", streams)
                val latePeople = spawn("q8-late-people") { ref -> LateCollectorCell<Person>(ref) }
                val lateAuctions = spawn("q8-late-auctions") { ref -> LateCollectorCell<Auction>(ref) }
                // One floor is linked before either data arm.
                link(waterline.cell.outlet, joined.cell.waterline)
                link(joined.cell.lateLeft, latePeople.cell.inlet)
                link(joined.cell.lateRight, lateAuctions.cell.inlet)
                accounting = WindowedAccounting.join(
                    joined.cell,
                    latePeople.cell,
                    lateAuctions.cell,
                    evicted = { outputCollector.cell.removals },
                )
            }

            link(streams.persons.outlet, peopleCollector.cell.inlet)
            link(streams.auctions.outlet, auctionCollector.cell.inlet)
            link(streams.persons.outlet, joined.cell.left)
            link(streams.auctions.outlet, joined.cell.right)
            link(joined.cell.outlet, outputCollector.cell.inlet)
            people = peopleCollector.cell
            auctions = auctionCollector.cell
            output = outputCollector.cell
        }

        return Q8Query(people, auctions, output, accounting, recordedMaxFanOut)
    }
}

private class Q8Query(
    people: SetRowsCollectorCell<Person>,
    auctions: SetRowsCollectorCell<Auction>,
    private val output: WindowSetCollectorCell<IdNameReserve>,
    private val accounting: WindowedAccounting?,
    maxFanOut: Int,
) : CollectedQuery(
    query = NexmarkQuery.Q8,
    outlets = linkedMapOf(
        "q8-people" to people,
        "q8-auctions" to auctions,
        "q8-output" to output,
    ),
    maxFanOut = maxFanOut,
) {
    override fun read(): NexmarkResult = NexmarkResult(
        columns = listOf("personId", "name", "reserve"),
        rows = output.rows().mapTo(linkedSetOf()) { row ->
            listOf<Any>(row.personId, row.name, row.reserve)
        },
    )

    override fun lateness() = accounting?.read()
}
