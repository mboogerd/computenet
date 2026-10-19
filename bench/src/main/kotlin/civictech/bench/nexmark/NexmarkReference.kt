package civictech.bench.nexmark

/** The lateness horizon used by the windowed Nexmark reference. */
data class LatenessPolicy(val lateness: Long) {
    init {
        require(lateness >= 0) { "lateness must be non-negative" }
    }
}

/** One set operation in an input batch. */
sealed interface StreamOp {
    data class Add(val event: Event) : StreamOp

    data class Remove(val event: Event) : StreamOp
}

data class Batch(val index: Int, val ops: List<StreamOp>)

data class ReferenceAnswer(
    val result: NexmarkResult,
    val floor: Long?,
    val droppedLate: Long,
    val evicted: Long,
)

/**
 * Independent, deliberately direct batch oracle for the Nexmark query slice.
 *
 * The evaluator folds the input into ordinary sets and recomputes each query
 * from those sets. Window assignment, waterline movement, late-drop and
 * eviction are derived here from the specification rather than shared with
 * the maintained query cells.
 */
object NexmarkReference {
    fun evaluate(
        query: NexmarkQuery,
        batches: List<Batch>,
        policy: LatenessPolicy?,
    ): ReferenceAnswer {
        rejectBlocked(query)

        val live = LiveEvents()
        var floor: Long? = null
        var maxEventTime: Long? = null
        var droppedLate = 0L
        var evicted = 0L

        for (batch in batches) {
            for (op in batch.ops) {
                when (op) {
                    is StreamOp.Add -> {
                        val eventTime = eventTime(op.event)
                        maxEventTime = maxEventTime?.let { maxOf(it, eventTime) } ?: eventTime
                        if (
                            policy != null &&
                            queryConsumes(query, op.event) &&
                            floor?.let { eventTime < it } == true
                        ) {
                            droppedLate++
                        } else {
                            live.add(op.event)
                        }
                    }

                    is StreamOp.Remove -> live.remove(op.event)
                }
            }

            if (policy != null && maxEventTime != null) {
                val candidate = maxEventTime - policy.lateness
                floor = floor?.let { maxOf(it, candidate) } ?: candidate
                evicted += live.evict(query, floor)
            }
        }

        return ReferenceAnswer(
            result = live.result(query),
            floor = floor,
            droppedLate = droppedLate,
            evicted = evicted,
        )
    }

    private fun rejectBlocked(query: NexmarkQuery) {
        val cause = query.blockedCause ?: return
        throw IllegalArgumentException("${query.name} is not expressible: $cause")
    }

    /** Only graph inputs participate in that query's late-drop accounting. */
    private fun queryConsumes(query: NexmarkQuery, event: Event): Boolean = when (query) {
        NexmarkQuery.Q1, NexmarkQuery.Q2, NexmarkQuery.Q7 -> event is Bid
        NexmarkQuery.Q3, NexmarkQuery.Q8 -> event is Person || event is Auction
        NexmarkQuery.Q4, NexmarkQuery.Q5, NexmarkQuery.Q6 -> false
    }

    private fun eventTime(event: Event): Long = when (event) {
        is Bid -> BidTime(event)
        is Auction -> AuctionTime(event)
        is Person -> PersonTime(event)
    }

    private fun windowStart(eventTime: Long): Long =
        Math.floorDiv(eventTime, NexmarkSpec.WINDOW_MS) * NexmarkSpec.WINDOW_MS

    private class LiveEvents {
        private val people = LinkedHashSet<Person>()
        private val auctions = LinkedHashSet<Auction>()
        private val bids = LinkedHashSet<Bid>()

        fun add(event: Event) {
            when (event) {
                is Person -> people.add(event)
                is Auction -> auctions.add(event)
                is Bid -> bids.add(event)
            }
        }

        fun remove(event: Event) {
            when (event) {
                is Person -> people.remove(event)
                is Auction -> auctions.remove(event)
                is Bid -> bids.remove(event)
            }
        }

        fun evict(query: NexmarkQuery, floor: Long): Long = when (query) {
            NexmarkQuery.Q7 -> {
                val expired = bids.filterTo(LinkedHashSet()) {
                    windowStart(it.dateTime) + NexmarkSpec.WINDOW_MS <= floor
                }
                bids.removeAll(expired)
                expired.size.toLong()
            }

            NexmarkQuery.Q8 -> {
                val expiredPeople = people.filterTo(LinkedHashSet()) { PersonTime(it) < floor }
                val expiredAuctions = auctions.filterTo(LinkedHashSet()) { AuctionTime(it) < floor }
                people.removeAll(expiredPeople)
                auctions.removeAll(expiredAuctions)
                (expiredPeople.size + expiredAuctions.size).toLong()
            }

            NexmarkQuery.Q1,
            NexmarkQuery.Q2,
            NexmarkQuery.Q3,
            NexmarkQuery.Q4,
            NexmarkQuery.Q5,
            NexmarkQuery.Q6,
            -> 0L
        }

        fun result(query: NexmarkQuery): NexmarkResult = when (query) {
            NexmarkQuery.Q1 -> q1()
            NexmarkQuery.Q2 -> q2()
            NexmarkQuery.Q3 -> q3()
            NexmarkQuery.Q7 -> q7()
            NexmarkQuery.Q8 -> q8()
            NexmarkQuery.Q4,
            NexmarkQuery.Q5,
            NexmarkQuery.Q6,
            -> error("blocked query reached reference evaluation")
        }

        private fun q1(): NexmarkResult = NexmarkResult(
            columns = listOf("id", "auction", "bidder", "price", "dateTime", "extra"),
            rows = bids.mapTo(LinkedHashSet()) { bid ->
                listOf<Any>(
                    bid.id,
                    bid.auction,
                    bid.bidder,
                    bid.price * 89L / 100L,
                    bid.dateTime,
                    bid.extra,
                )
            },
        )

        private fun q2(): NexmarkResult = NexmarkResult(
            columns = listOf("auction", "price"),
            rows = bids.asSequence()
                .filter { it.auction % NexmarkSpec.AUCTION_SKIP == 0L }
                .mapTo(LinkedHashSet()) { listOf<Any>(it.auction, it.price) },
        )

        private fun q3(): NexmarkResult {
            val eligiblePeople = people.filter { it.state in ELIGIBLE_STATES }.groupBy { it.id }
            return NexmarkResult(
                columns = listOf("name", "city", "state", "auctionId"),
                rows = auctions.asSequence()
                    .filter { it.category == NexmarkSpec.FIRST_CATEGORY_ID }
                    .flatMap { auction ->
                        eligiblePeople[auction.seller].orEmpty().asSequence().map { person ->
                            listOf<Any>(person.name, person.city, person.state, auction.id)
                        }
                    }
                    .toCollection(LinkedHashSet()),
            )
        }

        private fun q7(): NexmarkResult = NexmarkResult(
            columns = listOf("windowStart", "id", "auction", "bidder", "price", "dateTime"),
            rows = bids.groupBy { windowStart(it.dateTime) }.mapTo(LinkedHashSet()) { (window, rows) ->
                val winner = rows.minWithOrNull(
                    compareByDescending<Bid> { it.price }.thenBy { it.id },
                ) ?: error("a grouped window must contain a bid")
                listOf<Any>(
                    window,
                    winner.id,
                    winner.auction,
                    winner.bidder,
                    winner.price,
                    winner.dateTime,
                )
            },
        )

        private fun q8(): NexmarkResult {
            val peopleByWindow = people.groupBy { WindowKey(it.id, windowStart(it.dateTime)) }
            return NexmarkResult(
                columns = listOf("personId", "name", "reserve"),
                rows = auctions.asSequence().flatMap { auction ->
                    val key = WindowKey(auction.seller, windowStart(auction.dateTime))
                    peopleByWindow[key].orEmpty().asSequence().map { person ->
                        listOf<Any>(person.id, person.name, auction.reserve)
                    }
                }.toCollection(LinkedHashSet()),
            )
        }
    }

    private val ELIGIBLE_STATES = setOf("OR", "ID", "CA")
}
