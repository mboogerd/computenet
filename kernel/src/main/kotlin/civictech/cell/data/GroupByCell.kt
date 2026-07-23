package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

interface GroupByApi<E, K, A> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<MapDelta<K, A>>>
}

/**
 * Incremental grouped aggregation (M11.3): folds a tagged set stream into
 * per-key aggregates, `keyFn` deriving the group and [aggregator] the value.
 * Membership flips (not tag churn) drive insert/retract; a group's last
 * retraction removes it (`MapDelta` removal — SQL group-death semantics);
 * emission is effective-only by value equality (21). All groups touched by
 * one input delta emit as one `MapDelta` under the input's wave id (22).
 *
 * The cell is the single writer of its output stream, which is exactly
 * `MapDelta`'s documented contract — so it is not `Replicable`, and needn't
 * be: an aggregate is a deterministic function of convergent membership, so
 * peers recompute from their replicated inputs and converge with no
 * aggregate-level gossip (42).
 */
class GroupByCell<E, K, A, ACC : Serializable>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    private val keyFn: (E) -> K,
    private val aggregator: Aggregator<E, A, ACC>,
) : GroupByApi<E, K, A>, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<K, A>>>())

    private val state = TagState<E>()

    private class Group<ACC>(var count: Int, var acc: ACC)

    private val groups = mutableMapOf<K, Group<ACC>>()

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                val touched = value.adds.keys + value.dels.keys
                val liveBefore = touched.filterTo(mutableSetOf()) { it in state }
                state.apply(value)

                // first-touch snapshot per affected group: emission compares
                // against the value before this delta, not mid-fold values
                val before = mutableMapOf<K, A?>()
                touched.forEach { e ->
                    val was = e in liveBefore
                    val now = e in state
                    if (was == now) return@forEach // tag churn, no membership flip
                    val k = keyFn(e)
                    if (k !in before) before[k] = groups[k]?.let { aggregator.value(it.acc) }
                    if (now) {
                        val g = groups.getOrPut(k) { Group(0, aggregator.empty()) }
                        g.count++
                        g.acc = aggregator.insert(g.acc, e)
                    } else {
                        val g = checkNotNull(groups[k]) { "retract for untracked group $k" }
                        g.count--
                        g.acc = aggregator.retract(g.acc, e)
                        if (g.count == 0) groups.remove(k)
                    }
                }

                val puts = mutableMapOf<K, A>()
                val removals = mutableSetOf<K>()
                before.forEach { (k, old) ->
                    val now = groups[k]?.let { aggregator.value(it.acc) }
                    when {
                        now == null && old != null -> removals += k
                        now != null && now != old -> puts[k] = now // effective-only: value-equals gates
                    }
                }
                if (puts.isNotEmpty() || removals.isNotEmpty()) {
                    outlet.call.propagate(MapDelta(puts, removals))
                }
            }
        })
        // late-join catch-up (G-22): current aggregates as a delta-from-empty
        outlet.catchUpOnLinked {
            if (groups.isEmpty()) null
            else MapDelta(groups.mapValues { aggregator.value(it.value.acc) }, emptySet())
        }
    }

    // ponytail: acc is not deep-copied — every snapshot consumer (checkpoint,
    // migrate) serializes immediately; copy-on-snapshot if one ever retains it
    override fun snapshot(): Serializable = arrayListOf(
        state.snapshot(),
        HashMap(groups.mapValues { arrayListOf(it.value.count, it.value.acc) }),
    )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (tags, gs) = state as ArrayList<Serializable>
        this.state.restore(tags)
        groups.clear()
        (gs as Map<K, List<Serializable>>).forEach { (k, g) ->
            groups[k] = Group(g[0] as Int, g[1] as ACC)
        }
    }

    companion object {
        /** Fold-to-scalar: one global group under the constant key `"global"`. */
        fun <E, A, ACC : Serializable> global(
            aggregator: Aggregator<E, A, ACC>,
            ref: CellRef = CellRef(UUID.randomUUID()),
        ): GroupByCell<E, String, A, ACC> = GroupByCell(ref, keyFn = { "global" }, aggregator = aggregator)
    }
}
