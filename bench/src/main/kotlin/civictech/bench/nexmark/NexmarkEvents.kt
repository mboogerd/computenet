package civictech.bench.nexmark

import kotlinx.serialization.Serializable as KotlinSerializable
import java.io.Serializable

/** The event vocabulary emitted by [NexmarkGenerator]. */
sealed interface Event {
    val eventNumber: Long
    val dateTime: Long
}

@KotlinSerializable
data class Person(
    val id: Long,
    val name: String,
    val emailAddress: String,
    val creditCard: String,
    val city: String,
    val state: String,
    override val dateTime: Long,
    val extra: String,
    override val eventNumber: Long,
) : Event, Serializable

@KotlinSerializable
data class Auction(
    val id: Long,
    val itemName: String,
    val description: String,
    val initialBid: Long,
    val reserve: Long,
    override val dateTime: Long,
    val expires: Long,
    val seller: Long,
    val category: Long,
    val extra: String,
    override val eventNumber: Long,
) : Event, Serializable

@KotlinSerializable
data class Bid(
    /** Synthetic identity used by the benchmark for deterministic tie-breaking. */
    val id: Long,
    val auction: Long,
    val bidder: Long,
    val price: Long,
    override val dateTime: Long,
    val extra: String,
    override val eventNumber: Long,
) : Event, Serializable

/** A clock value used by the later lateness-aware harness increment. */
@KotlinSerializable
data class ClockTick(val at: Long) : Serializable

/** Named event-time extractors; named classes survive graph-spec capture. */
object BidTime : (Bid) -> Long, Serializable {
    override fun invoke(value: Bid): Long = value.dateTime

    private fun readResolve(): Any = BidTime
}

object PersonTime : (Person) -> Long, Serializable {
    override fun invoke(value: Person): Long = value.dateTime

    private fun readResolve(): Any = PersonTime
}

object AuctionTime : (Auction) -> Long, Serializable {
    override fun invoke(value: Auction): Long = value.dateTime

    private fun readResolve(): Any = AuctionTime
}

object ClockTime : (ClockTick) -> Long, Serializable {
    override fun invoke(value: ClockTick): Long = value.at

    private fun readResolve(): Any = ClockTime
}

/** Derived rows used by the query catalogue and later graph increment. */
data class AuctionPrice(val auction: Long, val price: Long) : Serializable

data class NameCityStateId(
    val name: String,
    val city: String,
    val state: String,
    val auctionId: Long,
) : Serializable

data class IdNameReserve(
    val personId: Long,
    val name: String,
    val reserve: Long,
) : Serializable

data class WindowKey(val id: Long, val windowStart: Long) : Serializable
