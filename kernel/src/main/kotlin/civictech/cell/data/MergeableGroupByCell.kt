package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

/**
 * Replicable grouped aggregation (CP-G1): the mergeable sibling of
 * [GroupByCell]. Where [GroupByCell] recomputes accumulators from convergent
 * membership and is deliberately **not** [Replicable] (last-writer-wins on its
 * `MapDelta` would lose a peer's concurrent partial sum), this cell is
 * parameterized by a **commutative-associative** [merge] on the accumulator, so
 * peers gossip O(groups) *aggregate* deltas over [deltaInlet] and converge —
 * instead of replicating the O(input) membership and recomputing per peer.
 *
 * - [inlet] folds local elements: `accumulate` projects an element to a partial
 *   accumulator, [merge] combines partials sharing a key.
 * - [deltaInlet] folds a peer's aggregate delta into local groups via [merge]
 *   (absent key = the operator's identity — see [MapDelta.merge]); only keys
 *   whose value actually changed re-emit (effective-only, 21), which is what
 *   terminates mesh echoes when [merge] is **idempotent** (max/min/set-union).
 *   A *counted* operator (`+`) converges only over disjoint keys (no echo
 *   re-merge) — exactly the disjoint-shard scatter-gather the demo uses.
 *
 * Declaring [Replicable] makes CP-F2's marker scan stamp the ports
 * `MergeClass.IDEMPOTENT` on `MERGE_IDEMPOTENCE`, so a non-idempotent
 * accumulator wired to a replicated sink is refused at link time (CP-F3) rather
 * than silently drifting.
 *
 * Retraction: unlike [GroupByCell] there is no `retract` — a merge cannot be
 * un-applied in general. The accumulator itself must encode removal (a peer's
 * `MapDelta.removals` on [deltaInlet] drop a key); element-level retraction on
 * [inlet] belongs to the non-replicated [GroupByCell].
 */
class MergeableGroupByCell<E, K, A : Serializable>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    private val keyOf: (E) -> K,
    private val accumulate: (E) -> A,
    private val merge: (A, A) -> A,
) : Cell, Stateful, Replicable<MapDelta<K, A>> {

    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<E>>>))

    @Suppress("UNCHECKED_CAST")
    override val outlet =
        registerPort("outlet", FanOutlet(Propagate::class.java as Class<Propagate<MapDelta<K, A>>>))

    @Suppress("UNCHECKED_CAST")
    override val deltaInlet =
        registerPort("deltaInlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<K, A>>>))

    private val groups = mutableMapOf<K, A>()

    /** Current per-key aggregates — the materialized group-by value. */
    fun aggregates(): Map<K, A> = groups.toMap()

    /** Fold [value] into `groups[key]` via [merge]; return the new value iff it changed. */
    private fun fold(key: K, value: A): A? {
        val merged = groups[key]?.let { merge(it, value) } ?: value
        if (merged == groups[key]) return null // effective-only (21) — echo dies here
        groups[key] = merged
        return merged
    }

    /** Local elements → per-key accumulator (grow/merge only; see class KDoc on retraction). */
    private fun onLocal(delta: SetDelta<E>) {
        val puts = mutableMapOf<K, A>()
        delta.adds.keys.forEach { e -> fold(keyOf(e), accumulate(e))?.let { puts[keyOf(e)] = it } }
        if (puts.isNotEmpty()) outlet.call.propagate(MapDelta(puts, emptySet()))
    }

    /** A peer's aggregate delta → merged into local groups; re-emit only the net change.
     *  The net change rides the INCOMING wave (like the scatter-gather forward it
     *  replaces), so a downstream wave-aligned sink still sees one wave per input. */
    private fun onRemote(delta: MapDelta<K, A>) {
        val puts = mutableMapOf<K, A>()
        delta.puts.forEach { (k, v) -> fold(k, v)?.let { puts[k] = it } }
        val removals = delta.removals.filterTo(mutableSetOf()) { groups.remove(it) != null }
        if (puts.isNotEmpty() || removals.isNotEmpty())
            outlet.call.propagate(MapDelta(puts, removals))
    }

    init {
        inlet.serve(Propagate { onLocal(it) })
        deltaInlet.serve(Propagate { onRemote(it) })
        // late-join catch-up (G-22) / replica anti-entropy: current aggregates
        // as a delta-from-empty; merge idempotence makes the replay harmless.
        outlet.catchUpOnLinked { if (groups.isEmpty()) null else MapDelta(groups.toMap(), emptySet()) }
    }

    override fun snapshot(): Serializable = HashMap(groups)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        groups.clear()
        groups.putAll(state as Map<K, A>)
    }
}
