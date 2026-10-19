package civictech.bench.nexmark.queries

import civictech.bench.nexmark.Auction
import civictech.bench.nexmark.Event
import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NameCityStateId
import civictech.bench.nexmark.NexmarkQuery
import civictech.bench.nexmark.NexmarkResult
import civictech.bench.nexmark.NexmarkSpec
import civictech.bench.nexmark.Person
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use

/** Category-10 auctions joined to persons in OR, ID or CA by seller identity. */
class Q3Builder(
    policy: LatenessPolicy? = null,
    eventPayload: Class<*> = Event::class.java,
) : NexmarkQueryBuilder(eventPayload, policy) {
    override val query: NexmarkQuery = NexmarkQuery.Q3

    init {
        validatePolicyFor(query)
    }

    override fun attach(host: Use<HostManagementApi>, streams: EventStreams): MaintainedQuery {
        lateinit var auctions: SetRowsCollectorCell<Auction>
        lateinit var people: SetRowsCollectorCell<Person>
        lateinit var output: SetRowsCollectorCell<NameCityStateId>

        graph(host) {
            adopt(streams.auctions)
            adopt(streams.persons)
            val auctionFilter = spawn("q3-auction-filter") { ref ->
                FilterCell(ref = ref) { auction: Auction -> auction.category == NexmarkSpec.FIRST_CATEGORY_ID }
            }
            val personFilter = spawn("q3-person-filter") { ref ->
                FilterCell(ref = ref) { person: Person -> person.state in ELIGIBLE_STATES }
            }
            val joined = spawn("q3-join") { ref ->
                JoinSetCell<Auction, Person, Long, NameCityStateId>(
                    ref = ref,
                    leftKey = { auction -> auction.seller },
                    rightKey = { person -> person.id },
                    combine = { auction, person ->
                        NameCityStateId(person.name, person.city, person.state, auction.id)
                    },
                )
            }
            val auctionCollector = spawn("q3-auctions-collector") { ref ->
                SetRowsCollectorCell<Auction>(ref)
            }
            val peopleCollector = spawn("q3-people-collector") { ref ->
                SetRowsCollectorCell<Person>(ref)
            }
            val outputCollector = spawn("q3-output-collector") { ref ->
                SetRowsCollectorCell<NameCityStateId>(ref)
            }

            link(streams.auctions.outlet, auctionFilter.cell.inlet)
            link(streams.persons.outlet, personFilter.cell.inlet)
            link(auctionFilter.cell.outlet, auctionCollector.cell.inlet)
            link(personFilter.cell.outlet, peopleCollector.cell.inlet)
            link(auctionFilter.cell.outlet, joined.cell.left)
            link(personFilter.cell.outlet, joined.cell.right)
            link(joined.cell.outlet, outputCollector.cell.inlet)
            auctions = auctionCollector.cell
            people = peopleCollector.cell
            output = outputCollector.cell
        }

        return Q3Query(auctions, people, output, recordedMaxFanOut)
    }

    private companion object {
        val ELIGIBLE_STATES = setOf("OR", "ID", "CA")
    }
}

private class Q3Query(
    auctions: SetRowsCollectorCell<Auction>,
    people: SetRowsCollectorCell<Person>,
    private val output: SetRowsCollectorCell<NameCityStateId>,
    maxFanOut: Int,
) : CollectedQuery(
    query = NexmarkQuery.Q3,
    outlets = linkedMapOf(
        "q3-auctions" to auctions,
        "q3-people" to people,
        "q3-output" to output,
    ),
    maxFanOut = maxFanOut,
) {
    override fun read(): NexmarkResult = NexmarkResult(
        columns = listOf("name", "city", "state", "auctionId"),
        rows = output.rows().mapTo(linkedSetOf()) { row ->
            listOf<Any>(row.name, row.city, row.state, row.auctionId)
        },
    )
}
