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
 * with equal [DeclarationEvent.observedAt] unspecified. This timeline
 * establishes a TOTAL order over [events] that does not depend on the
 * [events] collection's own iteration order: primarily by `observedAt`,
 * and among events sharing an instant, by a deterministic key derived from
 * the declaration's own content (its weights, cap and window). Two
 * `DeclarationTimeline`s built over the same membership therefore always
 * agree on which declaration is in force, regardless of the order their
 * events were folded or iterated in — the same property
 * `AllocatorReportViews.publish` requires of its sorted project set, and for
 * the same reason: an order derived from fold HISTORY rather than membership
 * would make otherwise-identical instances disagree (computenet-2ezv1).
 *
 * Interval i, for consecutive sorted events, is `[observedAt_i,
 * observedAt_{i+1})`; the last event's interval is open-ended.
 */
class DeclarationTimeline(events: Collection<DeclarationEvent>) {

    private val sortedEvents: List<DeclarationEvent> =
        events.sortedWith(compareBy({ it.observedAt }, ::contentKey))

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
 * A deterministic, content-derived tie-break key for [DeclarationTimeline]'s
 * sort: a canonical string built from the declaration's own fields (weights
 * sorted by project name, cap, window), never from object identity or
 * construction/fold order. Two [DeclarationEvent]s with equal
 * [DeclarationEvent.observedAt] and an unequal [DeclarationEvent.declaration]
 * always get an unequal key here, so the sort is a total order over unequal
 * declarations rather than a preorder that falls back to input order.
 *
 * That guarantee rests on the encoding being INJECTIVE, which a plain
 * delimiter-joined string is not: project names are operator-authored map
 * keys, so a name containing the delimiters — `{"a=1.0;b": 2.0}` against
 * `{"a": 1.0, "b": 2.0}` — collides under `a=1.0;b=2.0` and hands the tie
 * back to iteration order, reintroducing exactly the defect this key exists
 * to remove. Each field is therefore length-prefixed (`<len>:<field>`), which
 * is uniquely decodable for any field content; the window is additionally
 * tagged `n`/`s<value>` so an absent window cannot encode as the literal
 * string `"null"` does.
 */
private fun contentKey(event: DeclarationEvent): String {
    val d = event.declaration
    val fields =
        d.weights.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value.toString()) } +
            d.monthlyCapHours.toString() +
            (d.window?.let { "s$it" } ?: "n")
    return fields.joinToString(separator = "") { "${it.length}:$it" }
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
