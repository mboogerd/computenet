package civictech.cell.data.view

import java.io.Serializable
import civictech.cell.data.delta.TaggedMapDelta

/**
 * Consumer-side materialized read model over a [TaggedMapDelta] stream: folds
 * dot-tagged puts/tombstones into `{k -> value(k) : k in membership()}` — the
 * canonical fold for anything holding an `OrMapCell` outlet (an app
 * subscriber, a test, the observation sink). No ports, no host, no wave logic.
 *
 * Membership and value resolution are delegated entirely to [TaggedMapDelta]
 * ([TaggedMapDelta.membership], [TaggedMapDelta.value]) — this view never
 * reimplements dot resolution, so it inherits whatever embedded-mergeable
 * fold that type applies to a key's live dots.
 *
 * G-23 discharge: unlike [MapView] (whose deltas carry no causal tags and so
 * are sound only over one FIFO stream), [TaggedMapDelta]'s per-put dots make
 * [merge][TaggedMapDelta.merge] commutative, associative and idempotent
 * (`[24-TMAP-01]`), so this fold is order-insensitive — any permutation,
 * duplication or interleaving of the same delta multiset converges to the
 * same [current] — sound across concurrent multi-writer streams, not just a
 * single FIFO one.
 *
 * Not thread-safe: apply deltas from one thread at a time, like the cells.
 */
class TaggedMapView<K, V> {
    private var state: TaggedMapDelta<K, V> = TaggedMapDelta()

    /**
     * Fold one delta in: pointwise dot union with the accumulated delta.
     * Returns whether the *exposed* map effectively changed — a key's
     * presence or its resolved [TaggedMapDelta.value] — so a caller can guard
     * a broadcast on the return. `false` when every dot in [delta] was
     * already held (a re-delivered put) or every tombstone covers a dot
     * already covered; `true` when a tombstone kills a key's last live dot
     * (the key disappears from [current]).
     */
    fun apply(delta: TaggedMapDelta<K, V>): Boolean {
        val touched = delta.keys()
        if (touched.isEmpty()) return false
        val before = touched.associateWith { state.value(it) }
        state = state.merge(delta)
        return touched.any { key -> state.value(key) != before.getValue(key) }
    }

    /** Current entries as an immutable snapshot: `{k -> value(k) : k in membership()}`. */
    fun current(): Map<K, V> =
        state.membership().associateWithTo(LinkedHashMap()) { state.value(it) as V }

    operator fun get(key: K): V? = if (key in state.membership()) state.value(key) else null
    operator fun contains(key: K): Boolean = key in state.membership()
    val size: Int get() = state.membership().size

    // snapshot/restore mirror the Stateful shape (matching MapView / OrMapCell):
    // the merged delta is itself Serializable, so persisting it directly is
    // the natural choice — no separate materialized-map encoding is needed.
    fun snapshot(): Serializable = state

    @Suppress("UNCHECKED_CAST")
    fun restore(state: Serializable) {
        this.state = state as TaggedMapDelta<K, V>
    }
}
