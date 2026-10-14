package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive

/**
 * In-memory admission input for one benchmark point ([BEN2-25], [BEN2-41]).
 *
 * This vocabulary is deliberately not serializable: only admitted structural costs
 * and explicit exclusions belong in the export. Every outcome retains its coordinate
 * and subject so an excluded point remains attributable to the sweep that produced it.
 */
sealed interface PointOutcome {
    val at: Coordinate
    val subject: CostSubject

    /**
     * A point whose structural counters may be admitted. A simulated point has no
     * wall-clock result ([BEN2-25]).
     */
    data class Ok(
        override val at: Coordinate,
        override val subject: CostSubject,
        val counts: StructuralCounts,
        val wall: BenchResult?,
    ) : PointOutcome {
        init {
            require(at.drive != Drive.SIM || wall == null) {
                "SIM structural point must not carry a wall-clock BenchResult"
            }
        }
    }

    /** A point that produced a result which cannot be reported ([BEN2-41]). */
    data class Unreportable(
        override val at: Coordinate,
        override val subject: CostSubject,
        val result: BenchResult,
    ) : PointOutcome

    /** A point that did not converge within its declared budget ([BEN2-41]). */
    data class NonConvergent(
        override val at: Coordinate,
        override val subject: CostSubject,
        val budget: String,
    ) : PointOutcome

    /** A point whose repeated structural observations disagreed ([BEN2-41]). */
    data class NonDeterministic(
        override val at: Coordinate,
        override val subject: CostSubject,
        val first: StructuralCounts,
        val second: StructuralCounts,
    ) : PointOutcome

    /** A point the scheduler could not execute ([BEN2-41]). */
    data class Unschedulable(
        override val at: Coordinate,
        override val subject: CostSubject,
        val reason: String,
        val observedLimit: String,
    ) : PointOutcome

    /** A point that failed and retained its dead-letter evidence ([BEN2-41]). */
    data class Failed(
        override val at: Coordinate,
        override val subject: CostSubject,
        val cause: String,
        val deadLetters: List<String>,
    ) : PointOutcome
}
