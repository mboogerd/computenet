package civictech.iroh.discover

import civictech.cell.DenialReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One monotonic count. Increment-only by construction — there is no setter, no
 * decrement and no reset, so a reader of two samples can always subtract them
 * ([DSC2-OBS-02]).
 *
 * [name] is a fixed label chosen in this file; it never carries a key, an
 * address or an identity, so a counter cannot become an observability channel
 * for peer material.
 */
class Counter internal constructor(val name: String, private val source: (() -> Long)? = null) {
    private val value = AtomicLong()

    /** The current count. Never decreases. */
    val count: Long get() = source?.invoke() ?: value.get()

    /**
     * Adds one. Returns the new value. Safe from any thread.
     *
     * @throws IllegalStateException on a [derived] counter — its value is
     *   owned elsewhere, so an increment here would be silently lost, which is
     *   worse than a failure.
     */
    fun increment(): Long {
        check(source == null) { "$name is a derived counter; its value is read from its source, never incremented" }
        return value.incrementAndGet()
    }

    override fun toString(): String = "$name=$count"

    companion object {
        /**
         * A counter that **reads** [source] instead of holding a value of its
         * own — for a count some other component already keeps, so that the
         * two can never disagree (`computenet-ktn1l.3`).
         *
         * [source] must itself be monotonic; this class cannot enforce that,
         * and the one use of it — `SidecarClient.malformedDiscoveryEvents`, an
         * `AtomicLong` that is only ever incremented — is why the caveat is
         * written here rather than checked.
         */
        fun derived(name: String, source: () -> Long): Counter = Counter(name, source)
    }
}

/**
 * The counters and the one gauge the discovery dial policy reports
 * ([DSC2-OBS-01..03], F3-D9).
 *
 * This class is the TYPES half: it defines and accounts, and nothing here
 * decides when a count moves — the policy (feature task 3) and the hello gate
 * (task 4) call [Counter.increment] and [refused] at the points their own
 * requirements name.
 *
 * **Nothing here can carry peer material.** The counter labels are fixed
 * strings in this file, [hellosRefused] is keyed by a closed kernel enum, and
 * [keysRetained] is an `Int`. There is no free-text dimension to put a key, an
 * address or a statement into, which is how [DSC2-OBS-03] is held here — by
 * shape, not by a scrubbing step someone has to remember.
 *
 * @param keysRetained the gauge — how many keys the [PeerTable] is holding
 *   right now. A gauge, not a counter: it goes down when an entry is evicted
 *   or expires, so it is supplied by the table rather than counted here.
 * @param malformedEventSource where [DiscoveryCounters.malformedEvents] reads its
 *   value from. The frames it counts are rejected inside `SidecarClient`,
 *   which never reaches this class, so this counter is *derived* from
 *   `SidecarClient.malformedDiscoveryEvents` rather than copied out of it —
 *   a copy would be a second number that can lag or disagree
 *   (`computenet-ktn1l.3`, [DSC2-OBS-01]).
 */
class DiscoveryCounters(
    val keysRetained: () -> Int = { 0 },
    malformedEventSource: () -> Long = { 0 },
) {

    /** Discovery events taken off the sidecar, malformed ones included. */
    val eventsReceived: Counter = Counter("eventsReceived")

    /** Dials this node started ([DSC2-DIAL-02]). */
    val dialsAttempted: Counter = Counter("dialsAttempted")

    /** Dials that did not produce a link, each of which schedules a retry ([DSC2-DIAL-03]). */
    val dialsFailed: Counter = Counter("dialsFailed")

    /** Events naming this node's own key, dropped without retaining anything ([DSC2-DIAL-04]). */
    val selfDropped: Counter = Counter("selfDropped")

    /** Sightings of a key that already has a dial, a peering or a terminal state ([DSC2-DIAL-01], [DSC2-DIAL-07]). */
    val duplicatesSuppressed: Counter = Counter("duplicatesSuppressed")

    /**
     * Discovery events the client could not parse. Counted, never retried.
     *
     * Derived, not incremented: the count lives in `SidecarClient`, where the
     * frames are rejected. @see Counter.derived
     */
    val malformedEvents: Counter = Counter.derived("malformedEvents", malformedEventSource)

    /** Links closed quietly because they lost the mutual-dial tie-break (aas-D7). Not denials: no blame, no reason. */
    val tieBreakClosed: Counter = Counter("tieBreakClosed")

    /** Keys marked superseded because their identity re-appeared under a new key ([DSC2-ID-06]). */
    val superseded: Counter = Counter("superseded")

    /** Entries dropped to keep the table within `maxRetained` ([DSC2-MDNS-05]). */
    val evicted: Counter = Counter("evicted")

    private val refusals = ConcurrentHashMap<DenialReason, AtomicLong>()

    /**
     * Every counter this class **accumulates**, in declaration order.
     * Diagnostics, tests and the view.
     *
     * [malformedEvents] is deliberately not here: it is derived, so it has no
     * increment of its own and a caller that walks this list to move or to
     * total the counters it owns must not meet one that cannot be moved.
     * [derived] holds it, and [every] is the two together
     * (`computenet-ktn1l.3`).
     */
    val all: List<Counter> = listOf(
        eventsReceived,
        dialsAttempted,
        dialsFailed,
        selfDropped,
        duplicatesSuppressed,
        tieBreakClosed,
        superseded,
        evicted,
    )

    /** The counters read from elsewhere. @see Counter.derived */
    val derived: List<Counter> = listOf(malformedEvents)

    /** Every count this class reports, owned and derived — what an observability surface samples. */
    val every: List<Counter> = all + derived

    /**
     * Accounts one refused hello attributed to discovery, by [reason].
     *
     * Kept per reason rather than as one total because the epic's whole point
     * at [DSC2-ID-05] is that two refusals which look alike must be countable
     * apart — an identity that changed under a live link versus statements
     * that do not back the key at all.
     */
    fun refused(reason: DenialReason): Long =
        refusals.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()

    /** The refusals so far, by reason. Only reasons actually recorded appear; a snapshot, safe to hold. */
    fun refusedBy(): Map<DenialReason, Long> = refusals.entries.associate { it.key to it.value.get() }

    /** Refusals summed over every reason. */
    val hellosRefused: Long get() = refusals.values.sumOf { it.get() }

    override fun toString(): String =
        "DiscoveryCounters(${every.joinToString(", ")}, keysRetained=${keysRetained()}, hellosRefused=${refusedBy()})"
}
