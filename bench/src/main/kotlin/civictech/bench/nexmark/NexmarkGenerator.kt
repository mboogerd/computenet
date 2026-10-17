package civictech.bench.nexmark

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToLong

data class GeneratorConfig(
    val hotAuctionRatio: Int = 2,
    val hotSellersRatio: Int = 4,
    val hotBiddersRatio: Int = 4,
    val numInFlightAuctions: Int = 100,
    val numActivePeople: Int = 1_000,
    val outOfOrderGroupSize: Int = 1,
    val avgPersonByteSize: Int = 200,
    val avgAuctionByteSize: Int = 500,
    val avgBidByteSize: Int = 100,
) {
    init {
        require(hotAuctionRatio > 0) { "hotAuctionRatio must be positive" }
        require(hotSellersRatio > 0) { "hotSellersRatio must be positive" }
        require(hotBiddersRatio > 0) { "hotBiddersRatio must be positive" }
        require(numInFlightAuctions > 0) { "numInFlightAuctions must be positive" }
        require(numActivePeople > 0) { "numActivePeople must be positive" }
        require(outOfOrderGroupSize > 0) { "outOfOrderGroupSize must be positive" }
        require(avgPersonByteSize >= 0) { "avgPersonByteSize must be non-negative" }
        require(avgAuctionByteSize >= 0) { "avgAuctionByteSize must be non-negative" }
        require(avgBidByteSize >= 0) { "avgBidByteSize must be non-negative" }
    }
}

data class NexmarkScale(val events: Int, val eventsPerSecond: Int = 1_000) {
    init {
        require(events >= 0) { "events must be non-negative" }
        require(eventsPerSecond > 0) { "eventsPerSecond must be positive" }
    }
}

/** Beam-faithful seeded Nexmark event generation, adapted to value rows. */
object NexmarkGenerator {
    val deviations: List<String> = listOf(
        "event-rate deviation: 1 000 events/s scale replaces Beam's firstEventRate 10 000 so " +
            "100 000 events span ten 10 s windows",
        "synthetic-bid-id deviation: Bid.id is synthetic and equals eventNumber; Beam bids have no id",
        "event model: eventNumber is carried on each row so generated prefixes can be replayed " +
            "without a source-side event wrapper",
        "rate shape: only the deterministic constant-rate path is transcribed; Beam's wall-clock " +
            "rate-shape scheduler is outside this benchmark",
        "hot-key sampling: a stable event-id mix avoids Beam's low-id Random(nextInt) artifact " +
            "at the 2 000-event default scale while retaining the configured ratio",
    )

    private const val AUCTION_ID_LEAD = 10L
    private const val PERSON_ID_LEAD = 10L
    private const val HOT_SELLER_RATIO = 100L
    private const val HOT_AUCTION_RATIO = 100L
    private const val HOT_BIDDER_RATIO = 100L
    private const val NUM_CATEGORIES = 5

    private val states = listOf("AZ", "CA", "ID", "OR", "WA", "WY")
    private val cities = listOf(
        "Phoenix", "Los Angeles", "San Francisco", "Boise", "Portland",
        "Bend", "Redmond", "Seattle", "Kent", "Cheyenne",
    )
    private val firstNames = listOf("Peter", "Paul", "Luke", "John", "Saul", "Vicky", "Kate", "Julie", "Sarah", "Deiter", "Walter")
    private val lastNames = listOf("Shultz", "Abrams", "Spencer", "White", "Bartels", "Walton", "Smith", "Jones", "Noris")

    fun generate(
        seed: Long,
        scale: NexmarkScale,
        config: GeneratorConfig = GeneratorConfig(),
    ): List<Event> = buildList(scale.events) {
        repeat(scale.events) { sequenceNumber ->
            val eventNumber = adjustedEventNumber(sequenceNumber.toLong(), config.outOfOrderGroupSize)
            val eventId = seed + eventNumber
            val random = Random(eventId)
            val dateTime = eventTime(eventNumber, scale.eventsPerSecond)
            val remainder = Math.floorMod(eventId, NexmarkSpec.PROPORTION_DENOMINATOR.toLong())
            add(
                when {
                    remainder < NexmarkSpec.PERSON_PROPORTION ->
                        nextPerson(eventId, eventNumber, dateTime, random, config)
                    remainder < NexmarkSpec.PERSON_PROPORTION + NexmarkSpec.AUCTION_PROPORTION ->
                        nextAuction(eventId, eventNumber, dateTime, random, scale.eventsPerSecond, config)
                    else -> nextBid(eventId, eventNumber, dateTime, random, config)
                },
            )
        }
    }

    fun digest(events: List<Event>): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalRendering(events).toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun canonicalRendering(events: List<Event>): String = buildString {
        events.forEach { event ->
            when (event) {
                is Person -> append("P|").append(event.id).append('|').append(event.name).append('|')
                    .append(event.emailAddress).append('|').append(event.creditCard).append('|')
                    .append(event.city).append('|').append(event.state).append('|').append(event.dateTime)
                    .append('|').append(event.extra).append('|').append(event.eventNumber)
                is Auction -> append("A|").append(event.id).append('|').append(event.itemName).append('|')
                    .append(event.description).append('|').append(event.initialBid).append('|').append(event.reserve)
                    .append('|').append(event.dateTime).append('|').append(event.expires).append('|')
                    .append(event.seller).append('|').append(event.category).append('|').append(event.extra)
                    .append('|').append(event.eventNumber)
                is Bid -> append("B|").append(event.id).append('|').append(event.auction).append('|')
                    .append(event.bidder).append('|').append(event.price).append('|').append(event.dateTime)
                    .append('|').append(event.extra).append('|').append(event.eventNumber)
            }
            append('\n')
        }
    }

    private fun adjustedEventNumber(sequenceNumber: Long, groupSize: Int): Long {
        val n = groupSize.toLong()
        val base = (sequenceNumber / n) * n
        val offset = (sequenceNumber * 953L) % n
        return base + offset
    }

    private fun eventTime(eventNumber: Long, eventsPerSecond: Int): Long =
        eventNumber * 1_000L / eventsPerSecond

    private fun nextPerson(
        eventId: Long,
        eventNumber: Long,
        timestamp: Long,
        random: Random,
        config: GeneratorConfig,
    ): Person {
        val id = lastBase0PersonId(eventId) + NexmarkSpec.FIRST_PERSON_ID
        val name = "${firstNames[random.nextInt(firstNames.size)]} ${lastNames[random.nextInt(lastNames.size)]}"
        val email = "${nextString(random, 7)}@${nextString(random, 5)}.com"
        val creditCard = (0 until 4).joinToString(" ") { "%04d".format(random.nextInt(10_000)) }
        val city = cities[random.nextInt(cities.size)]
        val state = states[random.nextInt(states.size)]
        val currentSize = 8 + name.length + email.length + creditCard.length + city.length + state.length
        return Person(id, name, email, creditCard, city, state, timestamp, nextExtra(random, currentSize, config.avgPersonByteSize), eventNumber)
    }

    private fun nextAuction(
        eventId: Long,
        eventNumber: Long,
        timestamp: Long,
        random: Random,
        eventsPerSecond: Int,
        config: GeneratorConfig,
    ): Auction {
        val id = lastBase0AuctionId(eventId) + NexmarkSpec.FIRST_AUCTION_ID
        val sellerBase = if (isHot(eventId, config.hotSellersRatio, random, 0x51L)) {
            (lastBase0PersonId(eventId) / HOT_SELLER_RATIO) * HOT_SELLER_RATIO
        } else {
            nextBase0PersonId(eventId, random, config)
        }
        val seller = sellerBase + NexmarkSpec.FIRST_PERSON_ID
        val category = NexmarkSpec.FIRST_CATEGORY_ID + random.nextInt(NUM_CATEGORIES)
        val initialBid = nextPrice(random)
        val name = nextString(random, 20)
        val description = nextString(random, 100)
        val expires = timestamp + nextAuctionLengthMs(eventNumber, timestamp, eventsPerSecond, random, config)
        val reserve = initialBid + nextPrice(random)
        val currentSize = 8 + name.length + description.length + 8 + 8 + 8 + 8 + 8
        return Auction(
            id, name, description, initialBid, reserve, timestamp, expires, seller, category,
            nextExtra(random, currentSize, config.avgAuctionByteSize), eventNumber,
        )
    }

    private fun nextBid(
        eventId: Long,
        eventNumber: Long,
        timestamp: Long,
        random: Random,
        config: GeneratorConfig,
    ): Bid {
        val auctionBase = if (isHot(eventId, config.hotAuctionRatio, random, 0x71L)) {
            (lastBase0AuctionId(eventId) / HOT_AUCTION_RATIO) * HOT_AUCTION_RATIO
        } else {
            nextBase0AuctionId(eventId, random, config)
        }
        val bidderBase = if (isHot(eventId, config.hotBiddersRatio, random, 0x91L)) {
            (lastBase0PersonId(eventId) / HOT_BIDDER_RATIO) * HOT_BIDDER_RATIO + 1L
        } else {
            nextBase0PersonId(eventId, random, config)
        }
        return Bid(
            eventNumber,
            auctionBase + NexmarkSpec.FIRST_AUCTION_ID,
            bidderBase + NexmarkSpec.FIRST_PERSON_ID,
            nextPrice(random),
            timestamp,
            nextExtra(random, 32, config.avgBidByteSize),
            eventNumber,
        )
    }

    private fun lastBase0PersonId(eventId: Long): Long {
        val epoch = eventId / NexmarkSpec.PROPORTION_DENOMINATOR
        var offset = eventId % NexmarkSpec.PROPORTION_DENOMINATOR
        if (offset >= NexmarkSpec.PERSON_PROPORTION) offset = NexmarkSpec.PERSON_PROPORTION.toLong() - 1L
        return epoch * NexmarkSpec.PERSON_PROPORTION + offset
    }

    private fun nextBase0PersonId(eventId: Long, random: Random, config: GeneratorConfig): Long {
        val numPeople = lastBase0PersonId(eventId) + 1L
        val activePeople = minOf(numPeople, config.numActivePeople.toLong())
        return numPeople - activePeople + nextLong(random, activePeople + PERSON_ID_LEAD)
    }

    private fun lastBase0AuctionId(eventId: Long): Long {
        val epoch = eventId / NexmarkSpec.PROPORTION_DENOMINATOR
        var offset = eventId % NexmarkSpec.PROPORTION_DENOMINATOR
        if (offset < NexmarkSpec.PERSON_PROPORTION) {
            return (epoch - 1L) * NexmarkSpec.AUCTION_PROPORTION + NexmarkSpec.AUCTION_PROPORTION - 1L
        }
        if (offset >= NexmarkSpec.PERSON_PROPORTION + NexmarkSpec.AUCTION_PROPORTION) {
            offset = NexmarkSpec.AUCTION_PROPORTION.toLong() - 1L
        } else {
            offset -= NexmarkSpec.PERSON_PROPORTION
        }
        return epoch * NexmarkSpec.AUCTION_PROPORTION + offset
    }

    private fun nextBase0AuctionId(eventId: Long, random: Random, config: GeneratorConfig): Long {
        val last = lastBase0AuctionId(eventId)
        val minimum = maxOf(last - config.numInFlightAuctions, 0L)
        return minimum + nextLong(random, last - minimum + 1L + AUCTION_ID_LEAD)
    }

    private fun nextAuctionLengthMs(
        eventNumber: Long,
        timestamp: Long,
        eventsPerSecond: Int,
        random: Random,
        config: GeneratorConfig,
    ): Long {
        val eventsForAuctions = config.numInFlightAuctions.toLong() * NexmarkSpec.PROPORTION_DENOMINATOR / NexmarkSpec.AUCTION_PROPORTION
        val futureTimestamp = eventTime(eventNumber + eventsForAuctions, eventsPerSecond)
        return 1L + nextLong(random, maxOf((futureTimestamp - timestamp) * 2L, 1L))
    }

    private fun nextPrice(random: Random): Long =
        (10.0.pow(random.nextDouble() * 6.0) * 100.0).roundToLong()

    private fun nextLong(random: Random, bound: Long): Long {
        require(bound > 0) { "random bound must be positive" }
        return if (bound < Int.MAX_VALUE) random.nextInt(bound.toInt()).toLong()
        else Math.floorMod(random.nextLong(), bound)
    }

    private fun isHot(eventId: Long, ratio: Int, random: Random, salt: Long): Boolean {
        // Consume the same random draw Beam uses before generating the rest of the row. The
        // mixed selector makes the configured ratio observable even when the small event-id
        // range happens to alias the low bits of java.util.Random's first draw.
        random.nextInt(ratio)
        var value = eventId + salt - 0x61C8864680B583EBL
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return Math.floorMod(value, ratio.toLong()) != 0L
    }

    private fun nextString(random: Random, maxLength: Int): String {
        val length = 3 + random.nextInt(maxLength - 3)
        return buildString(length) {
            repeat(length) {
                append(if (random.nextInt(13) == 0) ' ' else ('a'.code + random.nextInt(26)).toChar())
            }
        }.trim()
    }

    private fun nextExtra(random: Random, currentSize: Int, desiredAverageSize: Int): String {
        if (currentSize > desiredAverageSize) return ""
        val remaining = desiredAverageSize - currentSize
        val delta = (remaining * 0.2).roundToLong().toInt()
        val desiredSize = remaining - delta + if (delta == 0) 0 else random.nextInt(2 * delta)
        return buildString(desiredSize) {
            var randomBits = 0
            var remainingCharacters = 0
            repeat(desiredSize) {
                if (remainingCharacters == 0) {
                    randomBits = random.nextInt()
                    remainingCharacters = 6
                }
                append(('a'.code + Math.floorMod(randomBits, 26)).toChar())
                randomBits /= 26
                remainingCharacters--
            }
        }
    }
}
