package civictech.demo.backlogtriage

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.MapDiffPublisher
import civictech.cell.Propagate
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.util.*
import kotlin.math.abs
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta

/**
 * RatingCell — hosts one incremental [RatingEngine] as a dataflow cell.
 *
 * Folds the tagged preference set-stream into membership transitions (an
 * element with N causal tags is still ONE preference — the engine sees each
 * pref exactly once, on 0→live and live→0 transitions) and re-emits per-item
 * rating changes as effective-only `MapDelta`s: a key is put only when its
 * rating actually moved, removed when it leaves the rated set.
 *
 * Deliberately app-level, in the discovered-requirement-prototype tradition
 * (as tiering's FuseCell was before CombineLatestCell). The pref stream defeats
 * the kernel's per-key GroupBy aggregators for a different reason per
 * algorithm class:
 *
 * - mean — per-key independent; GroupBy CAN express it (the default
 *   pipeline does exactly that).
 * - elo / trueskill — updates are *pairwise-local by design*: one game moves
 *   only the two participants' state (that locality is what keeps them
 *   online-computable). But they are cross-key: updating the winner needs
 *   the loser's current accumulator, which a per-key aggregator can never
 *   see. The missing kernel shape is a keyed-state cell with atomic
 *   two-key updates.
 * - bt — a genuinely global fixpoint: one game re-couples every strength
 *   through the comparison graph at refit.
 *
 * Effective-only emission makes each algorithm's true footprint observable
 * downstream: two puts per vote for elo/trueskill, potentially many for bt
 * (pinned by tests). The internal diff scans all keys — a shim cost a real
 * kernel operator would avoid by threading the touched-key set.
 */
class RatingCell(
    private val engine: RatingEngine,
    private val epsilon: Double = 1e-9,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Pref>>>())

    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Double>>>())

    private val live = mutableMapOf<Pref, MutableSet<Timestamp>>()
    private val ratings = MapDiffPublisher<String, Double>(changed = { a, b -> abs(a - b) > epsilon })

    init {
        inlet.onEach { value ->
            value.adds.forEach { (p, tags) ->
                val t = live.getOrPut(p) { mutableSetOf() }
                val wasLive = t.isNotEmpty()
                t += tags
                if (!wasLive && t.isNotEmpty()) engine.add(p.winner, p.loser)
            }
            value.dels.forEach { (p, tags) ->
                val t = live[p] ?: return@forEach
                val wasLive = t.isNotEmpty()
                t -= tags
                if (wasLive && t.isEmpty()) {
                    live.remove(p)
                    engine.retract(p.winner, p.loser)
                }
            }
            publishDiff()
        }
        // late-join catch-up (G-22): current ratings as a delta-from-empty
        outlet.catchUpOnLinked { ratings.catchUpDelta() }
    }

    private fun publishDiff() {
        ratings.publishAll(engine.ratings())?.let { outlet.call.propagate(it) }
    }
}

/**
 * MetaRankCell — Borda aggregation as dataflow: one named inlet per source
 * algorithm consumes that algorithm's rating `MapDelta` stream (including
 * the kernel-operator mean pipeline), and the combined ranking re-emits
 * effective-only on any upstream change. The cellular twin of [MetaRank]:
 * meta sits genuinely *downstream* of its delegates in the graph instead of
 * owning private copies of them.
 *
 * The four inlets update on separate propagations, so a mid-wave read can
 * see meta computed from a partially-updated source set before it settles —
 * the same observation-edge glitch recorded as finding F-5 (and targeted by
 * the SnapshotView/observe() backlog items this demo ranks).
 */
class MetaRankCell(
    sources: List<String> = listOf("mean", "elo", "bt", "trueskill", "glicko", "wenglin", "wilson"),
    private val epsilon: Double = 1e-9,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Double>>>())

    private val folded = LinkedHashMap<String, MutableMap<String, Double>>()
    private val publisher = MapDiffPublisher<String, Double>(changed = { a, b -> abs(a - b) > epsilon })

    val inlets: Map<String, FanInlet<Propagate<MapDelta<String, Double>>>> = sources.associateWith { name ->
        val fold = mutableMapOf<String, Double>()
        folded[name] = fold
        val port = registerPort(name, FanInlet.create<Propagate<MapDelta<String, Double>>>())
        port.onEach { value ->
            fold.putAll(value.puts)
            value.removals.forEach { fold.remove(it) }
            publishDiff()
        }
        port
    }

    init {
        outlet.catchUpOnLinked { publisher.catchUpDelta() }
    }

    private fun publishDiff() {
        publisher.publishAll(Borda.combine(folded.values.toList()))?.let { outlet.call.propagate(it) }
    }
}
