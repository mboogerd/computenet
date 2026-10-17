package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexmarkGeneratorTest {
    @Test
    fun `default slice follows Beam proportions ids categories states and event time`() {
        val events = NexmarkGenerator.generate(7L, NexmarkScale(events = 2_000, eventsPerSecond = 50))

        assertEquals(40, events.count { it is Person })
        assertEquals(120, events.count { it is Auction })
        assertEquals(1_840, events.count { it is Bid })
        assertTrue(events.zipWithNext().all { (left, right) -> left.eventNumber <= right.eventNumber })
        assertTrue(events.all { it.dateTime >= 0L })
        assertTrue(events.filterIsInstance<Person>().all { it.id >= NexmarkSpec.FIRST_PERSON_ID })
        assertTrue(events.filterIsInstance<Auction>().all { it.id >= NexmarkSpec.FIRST_AUCTION_ID })
        assertTrue(events.filterIsInstance<Auction>().all { it.category in 10L..14L })
        assertTrue(events.filterIsInstance<Person>().all { it.state in NexmarkSpec.US_STATES })
        assertEquals(
            NexmarkSpec.SPEC_REVISION,
            "Apache Beam Nexmark (sdks/java/testing/nexmark) at " +
                "2be52540457e3887080a67641240b243ea6dad45, inspected 2026-10-17",
        )
        assertTrue(NexmarkGenerator.deviations.any { it.contains("event-rate") })
        assertTrue(NexmarkGenerator.deviations.any { it.contains("synthetic-bid-id") })
    }

    /**
     * Beam's literal hot-seller draw is `Random(eventId).nextInt(hotSellersRatio) > 0`, the first
     * draw of a freshly seeded java.util.Random. For nearby seeds that first power-of-two draw is
     * nearly constant, so over the 2 000-event default slice every auction is hot; only at the
     * 100 000-event measurement scale does the share approach 1 - 1/4. Expected values come from an
     * independent emulation of java.util.Random's specified LCG (seed 7: 120/120 hot at 2 000
     * events, 4 529/6 000 = 0.7548 at 100 000).
     */
    @Test
    fun `hot sellers follow Beam's literal draw including its low-seed artifact`() {
        fun hotShare(events: Int): Double {
            val auctions = NexmarkGenerator.generate(7L, NexmarkScale(events, 1_000)).filterIsInstance<Auction>()
            return auctions.count { a ->
                val lastPerson = (7L + a.eventNumber) / NexmarkSpec.PROPORTION_DENOMINATOR
                a.seller == (lastPerson / 100L) * 100L + NexmarkSpec.FIRST_PERSON_ID
            }.toDouble() / auctions.size
        }
        assertEquals(1.0, hotShare(2_000))
        val large = hotShare(100_000)
        assertTrue(large in 0.65..0.85, "hot seller share at 100 000 events was $large")
    }
}
