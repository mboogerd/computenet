package civictech.demo.allocatorobserve.view

import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import java.time.Instant

/**
 * A read-only, sorted view over a declaration history (F2's
 * `DeclarationIngester.history()`), answering "which declaration was in
 * force at instant t" and "what declaration intervals overlap [from, to)".
 *
 * `DeclarationIngester.history()`'s own KDoc leaves ordering among events
 * with equal [DeclarationEvent.observedAt] unspecified. This timeline picks
 * one deterministic rule: among events sharing an instant, the one that
 * appears LAST in the [events] iteration order is the one in force from that
 * instant onward. This falls out of a stable sort by `observedAt` (which
 * this class performs internally): equal-keyed elements keep their relative
 * input order, so an earlier duplicate is immediately superseded by the
 * later one and contributes only a zero-length interval.
 *
 * Interval i, for consecutive sorted events, is `[observedAt_i,
 * observedAt_{i+1})`; the last event's interval is open-ended.
 */
class DeclarationTimeline(events: Collection<DeclarationEvent>) {

    private val sortedEvents: List<DeclarationEvent> = events.sortedBy { it.observedAt }

    /** The declaration in force at [t], or null before the first event. */
    fun inForceAt(t: Instant): AllocationDeclaration? =
        sortedEvents.lastOrNull { !it.observedAt.isAfter(t) }?.declaration

    /** One declaration-backed sub-range of a queried window. */
    data class DeclarationInterval(
        val from: Instant,
        val to: Instant,
        val declaration: AllocationDeclaration,
    )

    /**
     * The declaration intervals overlapping `[from, to)`, clipped to that
     * range, in ascending order. Empty when no declaration covers any part
     * of `[from, to)` — including when there are no events at all.
     */
    fun intervalsWithin(from: Instant, to: Instant): List<DeclarationInterval> {
        if (!from.isBefore(to)) return emptyList()
        val result = mutableListOf<DeclarationInterval>()
        for (i in sortedEvents.indices) {
            val eventStart = sortedEvents[i].observedAt
            val eventEnd = if (i + 1 < sortedEvents.size) sortedEvents[i + 1].observedAt else null

            val clippedFrom = if (eventStart.isAfter(from)) eventStart else from
            val clippedTo =
                when {
                    eventEnd == null -> to
                    eventEnd.isBefore(to) -> eventEnd
                    else -> to
                }

            if (clippedFrom.isBefore(clippedTo)) {
                result.add(DeclarationInterval(clippedFrom, clippedTo, sortedEvents[i].declaration))
            }
        }
        return result
    }

    /**
     * The leading sub-range of `[from, to)` that no declaration covers —
     * `[from, min(firstObservedAt, to))` — or null when the whole range is
     * covered (including when there are no events and `from >= to`).
     */
    fun uncoveredBefore(from: Instant, to: Instant): Pair<Instant, Instant>? {
        if (!from.isBefore(to)) return null
        val firstObservedAt = sortedEvents.firstOrNull()?.observedAt
        val uncoveredEnd = if (firstObservedAt == null || firstObservedAt.isAfter(to)) to else firstObservedAt
        return if (from.isBefore(uncoveredEnd)) from to uncoveredEnd else null
    }
}

/**
 * Weights normalized to fractions summing to 1.0. An empty or all-zero
 * weight map normalizes to an empty map (declared share 0 for every
 * project) rather than dividing by zero.
 */
fun AllocationDeclaration.normalizedWeights(): Map<String, Double> {
    val sum = weights.values.sum()
    if (sum == 0.0) return emptyMap()
    return weights.mapValues { (_, v) -> v / sum }
}
