package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexmarkReferenceTest {
    @Test
    fun `Q1 converts prices and Q2 collapses duplicate rows over the live bid set`() {
        val converted = bid(id = 1, auction = 1, price = 1_234)
        val duplicateRow1 = bid(id = 2, auction = 123, price = 500)
        val duplicateRow2 = bid(id = 3, auction = 123, price = 500)
        val removed = bid(id = 4, auction = 246, price = 20)
        val absent = bid(id = 5, auction = 369, price = 30)
        val batches = listOf(
            Batch(
                0,
                listOf(
                    StreamOp.Add(converted),
                    StreamOp.Add(duplicateRow1),
                    StreamOp.Add(duplicateRow2),
                    StreamOp.Add(removed),
                    StreamOp.Remove(removed),
                    StreamOp.Remove(absent),
                ),
            ),
        )

        assertEquals(
            setOf(
                listOf<Any>(1L, 1L, 501L, 1_098L, 1L, "bid-1"),
                listOf<Any>(2L, 123L, 502L, 445L, 2L, "bid-2"),
                listOf<Any>(3L, 123L, 503L, 445L, 3L, "bid-3"),
            ),
            NexmarkReference.evaluate(NexmarkQuery.Q1, batches, null).result.rows,
        )
        assertEquals(
            setOf(listOf<Any>(123L, 500L)),
            NexmarkReference.evaluate(NexmarkQuery.Q2, batches, null).result.rows,
        )
    }

    @Test
    fun `Q3 applies category state and seller predicates`() {
        val events = listOf(
            person(10, "Alice", "Portland", "OR", 100),
            person(20, "Bob", "Seattle", "WA", 200),
            person(30, "Cara", "Boise", "ID", 300),
            auction(100, seller = 10, category = 10, reserve = 1_000, time = 400),
            auction(200, seller = 20, category = 10, reserve = 2_000, time = 500),
            auction(300, seller = 30, category = 11, reserve = 3_000, time = 600),
            auction(400, seller = 99, category = 10, reserve = 4_000, time = 700),
        )

        assertEquals(
            setOf(listOf<Any>("Alice", "Portland", "OR", 100L)),
            evaluateAdds(NexmarkQuery.Q3, events).result.rows,
        )
    }

    @Test
    fun `Q7 chooses lower bid id when maximum prices tie in a window`() {
        val events = listOf(
            bid(id = 8, auction = 80, price = 900, time = 100),
            bid(id = 3, auction = 30, price = 900, time = 200),
            bid(id = 2, auction = 20, price = 800, time = 300),
            bid(id = 9, auction = 90, price = 700, time = 10_100),
        )

        assertEquals(
            setOf(
                listOf<Any>(0L, 3L, 30L, 503L, 900L, 200L),
                listOf<Any>(10_000L, 9L, 90L, 509L, 700L, 10_100L),
            ),
            evaluateAdds(NexmarkQuery.Q7, events).result.rows,
        )
    }

    @Test
    fun `Q8 joins only persons and auctions in the same tumbling window`() {
        val events = listOf(
            person(10, "Alice", "Portland", "OR", 1_000),
            auction(100, seller = 10, category = 10, reserve = 1_500, time = 9_000),
            auction(101, seller = 10, category = 10, reserve = 1_600, time = 10_000),
            person(20, "Bob", "Boise", "ID", 19_999),
            auction(200, seller = 20, category = 10, reserve = 2_500, time = 10_001),
        )

        assertEquals(
            setOf(
                listOf<Any>(10L, "Alice", 1_500L),
                listOf<Any>(20L, "Bob", 2_500L),
            ),
            evaluateAdds(NexmarkQuery.Q8, events).result.rows,
        )
    }

    @Test
    fun `Q7 policy drops late bids evicts whole windows and ignores an evicted remove`() {
        val firstWindow = bid(id = 1, auction = 10, price = 100, time = 1_000)
        val secondWindow = bid(id = 2, auction = 20, price = 200, time = 15_000)
        val late = bid(id = 3, auction = 30, price = 999, time = 9_000)
        val winner = bid(id = 4, auction = 40, price = 300, time = 21_000)
        val tied = bid(id = 5, auction = 50, price = 300, time = 26_000)
        val batches = listOf(
            Batch(0, listOf(StreamOp.Add(firstWindow), StreamOp.Add(secondWindow))),
            Batch(1, listOf(StreamOp.Add(late), StreamOp.Remove(late), StreamOp.Add(winner))),
            Batch(2, listOf(StreamOp.Remove(firstWindow), StreamOp.Add(tied))),
        )

        val expected = ReferenceAnswer(
            result = NexmarkResult(
                columns = listOf("windowStart", "id", "auction", "bidder", "price", "dateTime"),
                rows = setOf(listOf<Any>(20_000L, 4L, 40L, 504L, 300L, 21_000L)),
            ),
            floor = 21_000L,
            droppedLate = 1,
            evicted = 2,
        )

        assertEquals(expected, NexmarkReference.evaluate(NexmarkQuery.Q7, batches, LatenessPolicy(5_000)))
        assertEquals(expected, NexmarkReference.evaluate(NexmarkQuery.Q7, batches, LatenessPolicy(5_000)))
    }

    @Test
    fun `Q8 policy evicts join rows individually and ignores removes of evicted rows`() {
        val oldPerson = person(10, "Alice", "Portland", "OR", 1_000)
        val oldAuction = auction(100, seller = 10, category = 10, reserve = 1_000, time = 2_000)
        val middlePerson = person(20, "Bob", "Boise", "ID", 12_000)
        val middleAuction = auction(200, seller = 20, category = 10, reserve = 2_000, time = 13_000)
        val lateAuction = auction(101, seller = 10, category = 10, reserve = 9_999, time = 3_000)
        // The final floor is exactly 14_000: row eviction is strict, so this person survives.
        val livePerson = person(30, "Cara", "Boise", "ID", 14_000)
        val liveAuction = auction(300, seller = 30, category = 10, reserve = 3_000, time = 19_000)
        val batches = listOf(
            Batch(
                0,
                listOf(
                    StreamOp.Add(oldPerson),
                    StreamOp.Add(oldAuction),
                    StreamOp.Add(middlePerson),
                    StreamOp.Add(middleAuction),
                ),
            ),
            Batch(1, listOf(StreamOp.Add(lateAuction), StreamOp.Add(livePerson), StreamOp.Add(liveAuction))),
            Batch(2, listOf(StreamOp.Remove(oldPerson), StreamOp.Remove(oldAuction))),
        )

        assertEquals(
            ReferenceAnswer(
                result = NexmarkResult(
                    columns = listOf("personId", "name", "reserve"),
                    rows = setOf(listOf<Any>(30L, "Cara", 3_000L)),
                ),
                floor = 14_000L,
                droppedLate = 1,
                evicted = 4,
            ),
            NexmarkReference.evaluate(NexmarkQuery.Q8, batches, LatenessPolicy(5_000)),
        )
    }

    @Test
    fun `blocked queries refuse to return an approximation`() {
        listOf(NexmarkQuery.Q4, NexmarkQuery.Q5, NexmarkQuery.Q6).forEach { query ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                NexmarkReference.evaluate(query, emptyList(), null)
            }
            assertTrue(failure.message!!.contains(query.blockedCause!!), query.name)
        }
    }

    @Test
    fun `lateness must be non-negative`() {
        assertThrows(IllegalArgumentException::class.java) { LatenessPolicy(-1) }
    }

    private fun evaluateAdds(query: NexmarkQuery, events: List<Event>): ReferenceAnswer =
        NexmarkReference.evaluate(query, listOf(Batch(0, events.map(StreamOp::Add))), null)

    private fun bid(
        id: Long,
        auction: Long,
        price: Long,
        time: Long = id,
    ): Bid = Bid(
        id = id,
        auction = auction,
        bidder = 500 + id,
        price = price,
        dateTime = time,
        extra = "bid-$id",
        eventNumber = id,
    )

    private fun person(
        id: Long,
        name: String,
        city: String,
        state: String,
        time: Long,
    ): Person = Person(
        id = id,
        name = name,
        emailAddress = "$name@example.test",
        creditCard = "0000 0000 0000 0000",
        city = city,
        state = state,
        dateTime = time,
        extra = "person-$id",
        eventNumber = id,
    )

    private fun auction(
        id: Long,
        seller: Long,
        category: Long,
        reserve: Long,
        time: Long,
    ): Auction = Auction(
        id = id,
        itemName = "item-$id",
        description = "description-$id",
        initialBid = reserve / 2,
        reserve = reserve,
        dateTime = time,
        expires = time + 1_000,
        seller = seller,
        category = category,
        extra = "auction-$id",
        eventNumber = id,
    )
}
