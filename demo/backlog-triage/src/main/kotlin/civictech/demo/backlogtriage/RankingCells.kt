package civictech.demo.backlogtriage

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.util.*
import kotlin.math.abs

/**
 * RatingCell — hosts one incremental [RatingEngine] as a dataflow cell.
 *
 * Folds the tagged preference set-stream into membership transitions (an
 * element with N causal tags is still ONE preference — the engine sees each
 * pref exactly once, on 0→live and live→0 transitions) and re-emits per-item
 * rating changes as effective-only `MapDelta`s: a key is put only when its
 * rating actually moved, removed when it leaves the rated set.
 *
 * Deliberately app-level, in the FuseCell tradition. The pref stream defeats
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
    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<Pref>>>))

    @Suppress("UNCHECKED_CAST")
    val outlet = registerPort("outlet", FanOutlet(Propagate::class.java as Class<Propagate<MapDelta<String, Double>>>))

    private val live = mutableMapOf<Pref, MutableSet<Timestamp>>()
    private val published = mutableMapOf<String, Double>()

    init {
        inlet.serve(object : Propagate<SetDelta<Pref>> {
            override fun propagate(value: SetDelta<Pref>) {
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
        })
        // late-join catch-up (G-22): current ratings as a delta-from-empty
        outlet.linking.onLinked = { link ->
            if (published.isNotEmpty()) {
                outlet.at(link.to).propagate(MapDelta(published.toMap(), emptySet()))
            }
        }
    }

    private fun publishDiff() {
        val next = engine.ratings()
        val puts = mutableMapOf<String, Double>()
        next.forEach { (item, r) ->
            val prev = published[item]
            if (prev == null || abs(prev - r) > epsilon) {
                published[item] = r
                puts[item] = r
            }
        }
        val removals = published.keys.filter { it !in next }.toSet()
        removals.forEach { published.remove(it) }
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        }
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
    @Suppress("UNCHECKED_CAST")
    val outlet = registerPort("outlet", FanOutlet(Propagate::class.java as Class<Propagate<MapDelta<String, Double>>>))

    private val folded = LinkedHashMap<String, MutableMap<String, Double>>()
    private val published = mutableMapOf<String, Double>()

    @Suppress("UNCHECKED_CAST")
    val inlets: Map<String, FanInlet<Propagate<MapDelta<String, Double>>>> = sources.associateWith { name ->
        val fold = mutableMapOf<String, Double>()
        folded[name] = fold
        val port = registerPort(name, FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, Double>>>))
        port.serve(object : Propagate<MapDelta<String, Double>> {
            override fun propagate(value: MapDelta<String, Double>) {
                fold.putAll(value.puts)
                value.removals.forEach { fold.remove(it) }
                publishDiff()
            }
        })
        port
    }

    init {
        outlet.linking.onLinked = { link ->
            if (published.isNotEmpty()) {
                outlet.at(link.to).propagate(MapDelta(published.toMap(), emptySet()))
            }
        }
    }

    private fun publishDiff() {
        val next = Borda.combine(folded.values.toList())
        val puts = mutableMapOf<String, Double>()
        next.forEach { (item, r) ->
            val prev = published[item]
            if (prev == null || abs(prev - r) > epsilon) {
                published[item] = r
                puts[item] = r
            }
        }
        val removals = published.keys.filter { it !in next }.toSet()
        removals.forEach { published.remove(it) }
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        }
    }
}
