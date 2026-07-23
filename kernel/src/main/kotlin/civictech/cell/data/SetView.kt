package civictech.cell.data

import java.io.Serializable

/**
 * Consumer-side materialized read model over a [SetDelta] stream: folds the
 * kernel's OR-set tag algebra into live membership an app subscriber, a test,
 * or the observation sink can query. This is the *same* fold that
 * [UnionSetCell] / [IntersectSetCell] / [CountCell] run internally — it wraps
 * the internal [TagState], so a consumer that materializes a cell's outlet
 * agrees with the cell on what "current membership" means (important once tags
 * carry convergence identity across `wire`). No ports, no host, no wave logic —
 * just the fold; usable unhosted and in a plain unit test.
 *
 * Not thread-safe: apply deltas from one thread at a time, like the cells.
 */
class SetView<E> {
    private val state = TagState<E>()

    /**
     * Fold one delta in. Returns whether *membership* effectively changed —
     * `false` for a delta carrying only tag churn (a fresh add-tag for an
     * already-live element, or a del of an unseen tag), so a caller can guard a
     * broadcast on the return and skip redundant sends. Duplicate deliveries
     * across a diamond fan-in dedup: a re-seen tag folds to no change.
     */
    fun apply(delta: SetDelta<E>): Boolean {
        // Only elements this delta touches can flip membership; compare their
        // presence across the fold instead of copying the whole key set.
        val touched = delta.adds.keys + delta.dels.keys
        val before = touched.filterTo(HashSet()) { it in state }
        state.apply(delta)
        val after = touched.filterTo(HashSet()) { it in state }
        return before != after
    }

    /** Current membership: elements with at least one un-tombstoned add-tag. */
    fun current(): Set<E> = state.elements.toSet()

    operator fun contains(element: E): Boolean = element in state
    val size: Int get() = state.size

    // snapshot/restore mirror the Stateful shape (not the interface): seed a
    // view from a StateRequest reply / catch-up delta-from-empty snapshot.
    fun snapshot(): Serializable = state.snapshot()
    fun restore(state: Serializable) = this.state.restore(state)
}
