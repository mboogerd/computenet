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

    @Test
    fun `hot sellers follow the configured three quarters ratio`() {
        val auctions = NexmarkGenerator.generate(7L, NexmarkScale(2_000, 50)).filterIsInstance<Auction>()
        val hotShare = auctions.count { it.seller == NexmarkSpec.FIRST_PERSON_ID }.toDouble() / auctions.size
        assertTrue(hotShare in 0.65..0.85, "hot seller share was $hotShare")
    }
}
