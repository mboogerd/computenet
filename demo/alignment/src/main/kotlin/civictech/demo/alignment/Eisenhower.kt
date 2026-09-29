package civictech.demo.alignment

/**
 * The Eisenhower quadrant an idea falls in (feature computenet-i00bh): the 2×2
 * over importance and urgency, and the advice each corner carries.
 *
 * [wire] is the JSON string on `/aggregate`.
 */
internal enum class Quadrant(val wire: String) {
    /** Important and urgent. */
    DO("do"),

    /** Important, not urgent. */
    SCHEDULE("schedule"),

    /** Urgent, not important. */
    DELEGATE("delegate"),

    /** Neither. */
    DROP("drop"),
}

/**
 * The two-dimension Eisenhower configuration of a triage topic (feature
 * computenet-i00bh).
 *
 * Both dimensions are [Direction.VALUE] and equally weighted, so the topic's
 * ordinary [Scored.score] is their weighted mean — which is the right *single*
 * ordering (a thing both important and urgent outranks everything), while
 * [quadrantOf] recovers the 2×2 the score necessarily flattens. The quadrant is
 * derived, never stored: it is a read over the same per-dimension means
 * `/aggregate` already publishes in `byDim`, so nothing new enters the dataflow
 * or the journal.
 *
 * Deliberately only these two dimensions this phase. readiness, unlock, effort,
 * risk, kernel-truth and demo-leverage are designed (session 2026-09-29) and
 * deferred; a facilitator can still add any of them through
 * `POST /topics/{t}/dimensions` at runtime, in which case the score becomes
 * their weighted combination and the quadrant keeps reading only these two.
 */
internal object Eisenhower {

    const val IMPORTANCE = "importance"
    const val URGENCY = "urgency"

    /** The scale's midpoint: the axis both halves of the 2×2 split on. */
    const val MID = 5.0

    /** The dimensions a triage topic is created with, in board order. */
    val DIMENSIONS: List<Triple<String, Dimension, DimConfig>> = listOf(
        Triple(
            IMPORTANCE,
            Dimension("importance", lowLabel = "peripheral", highLabel = "critical"),
            DimConfig(1.0, Direction.VALUE),
        ),
        Triple(
            URGENCY,
            Dimension("urgency", lowLabel = "can wait", highLabel = "needed now"),
            DimConfig(1.0, Direction.VALUE),
        ),
    )

    /**
     * The quadrant from an idea's per-dimension means, or null when either
     * dimension is unrated — an honest "not placed yet", never a default corner
     * (the same rule as [Scored.score]'s null naming the missing side).
     *
     * A mean exactly on [MID] counts as the high side on both axes, so an idea
     * rated 5/5 reads [Quadrant.DO] rather than [Quadrant.DROP]: the ambiguous
     * case resolves towards attention, because the cost of looking at something
     * that did not need it is lower than dropping something that did.
     */
    fun quadrantOf(byDim: Map<String, DimStats>): Quadrant? {
        val importance = byDim[IMPORTANCE]?.mean ?: return null
        val urgency = byDim[URGENCY]?.mean ?: return null
        return when {
            importance >= MID && urgency >= MID -> Quadrant.DO
            importance >= MID -> Quadrant.SCHEDULE
            urgency >= MID -> Quadrant.DELEGATE
            else -> Quadrant.DROP
        }
    }
}
