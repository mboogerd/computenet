package civictech.demo.alignment

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.Aggregator
import civictech.cell.data.KeyedSetApi
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.MapApi
import civictech.cell.data.MapCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.view.MapDiffPublisher
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.refAs
import civictech.cell.host.ManagedHost
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import kotlin.math.max
import kotlin.math.sqrt

/** [RatingStatsAggregator]'s accumulator: count and the first two moment sums of the live ratings. */
data class StatsAcc(val n: Long, val sum: Double, val sumSq: Double) : Serializable

/**
 * Per-(idea, dimension) rating statistics for `GroupByCell`: count, mean and
 * SAMPLE standard deviation (0.0 when n < 2). Ratings are small integers, so
 * both sums stay exact integers in a Double under any insert/retract order and
 * the fold never drifts; the variance is `n·Σx² − (Σx)²` over `n(n−1)` — exact
 * up to the one final division — and clamped at 0 so `sqrt` never sees a
 * negative.
 */
class RatingStatsAggregator : Aggregator<Rating, DimStats, StatsAcc> {
    override fun empty(): StatsAcc = StatsAcc(0, 0.0, 0.0)

    override fun insert(acc: StatsAcc, element: Rating): StatsAcc {
        val x = element.value.toDouble()
        return StatsAcc(acc.n + 1, acc.sum + x, acc.sumSq + x * x)
    }

    override fun retract(acc: StatsAcc, element: Rating): StatsAcc {
        val x = element.value.toDouble()
        return StatsAcc(acc.n - 1, acc.sum - x, acc.sumSq - x * x)
    }

    override fun value(acc: StatsAcc): DimStats {
        val n = acc.n
        val variance = if (n < 2) 0.0 else max(0.0, (n * acc.sumSq - acc.sum * acc.sum) / (n * (n - 1)))
        return DimStats(n, acc.sum / n, sqrt(variance))
    }
}

/**
 * `@CellBase` Api for [WeightedFusionCell] (computenet-sigl0-D1). KSP generates
 * `WeightedFusionCellBase` with the two inlets `stats` and `weights` bound to
 * `onStats`/`onWeights` and the `outlet` registered — the first two-inlet
 * `@CellBase` cell outside `:kernel` (kernel precedent: `CombineLatestApi`).
 */
@CellBase
interface WeightedFusionApi {
    val stats: Serve<Propagate<MapDelta<IdeaDimKey, DimStats>>>
    val weights: Serve<Propagate<MapDelta<DimKey, Double>>>
    val outlet: Subscribe<Propagate<MapDelta<IdeaKey, Scored>>>
}

/**
 * WeightedFusionCell — the per-idea weighted aggregate across a topic's
 * creator-defined dimensions (computenet-sigl0-D1..D4).
 *
 * Demo-local rather than a chain of kernel `CombineLatestCell`s: that cell is
 * binary, and here the number of dimensions is decided at run time per topic.
 * Its ports are nonetheless fixed at authoring time — one stream of per-(idea,
 * dim) stats and one of per-dim weights — so it is an honest `@CellBase` cell
 * (contrast backlog-triage's `MetaRankCell`).
 *
 * Holds the latest stats and weights and recomputes only the ideas an incoming
 * delta touches: a stats delta touches the ideas of its keys; a weights delta
 * touches every idea of that topic with stats on the reweighted dimension.
 * Emission is effective-only through [MapDiffPublisher] (exact value
 * equality): an idea whose recompute is unchanged is not re-emitted, and an
 * idea left with no rated-and-weighted dimension is REMOVED — unranked is an
 * absent key, never a sentinel [Scored].
 */
class WeightedFusionCell(ref: CellRef = CellRef(UUID.randomUUID())) : WeightedFusionCellBase(ref) {
    private val statsOf = HashMap<IdeaDimKey, DimStats>()
    private val weightOf = HashMap<DimKey, Double>()

    /** idea → its dimensions that currently have stats. */
    private val dimsOfIdea = HashMap<IdeaKey, MutableSet<String>>()

    /** (topic, dim) → the ideas that currently have stats on it: the weights path's reverse index. */
    private val ideasOfDim = HashMap<DimKey, MutableSet<String>>()

    private val publisher = MapDiffPublisher<IdeaKey, Scored>()

    init {
        // late-join catch-up (G-22): current scores as a delta-from-empty
        outlet.catchUpOnLinked { publisher.catchUpDelta() }
    }

    override fun onStats(value: MapDelta<IdeaDimKey, DimStats>) {
        val touched = LinkedHashSet<IdeaKey>()
        value.puts.forEach { (k, s) ->
            statsOf[k] = s
            dimsOfIdea.getOrPut(k.ideaKey) { mutableSetOf() } += k.dim
            ideasOfDim.getOrPut(k.dimKey) { mutableSetOf() } += k.idea
            touched += k.ideaKey
        }
        value.removals.forEach { k ->
            if (statsOf.remove(k) == null) return@forEach
            dimsOfIdea[k.ideaKey]?.let { if (it.remove(k.dim) && it.isEmpty()) dimsOfIdea.remove(k.ideaKey) }
            ideasOfDim[k.dimKey]?.let { if (it.remove(k.idea) && it.isEmpty()) ideasOfDim.remove(k.dimKey) }
            touched += k.ideaKey
        }
        publish(touched)
    }

    override fun onWeights(value: MapDelta<DimKey, Double>) {
        val touched = LinkedHashSet<IdeaKey>()
        fun touch(d: DimKey) = ideasOfDim[d]?.forEach { touched += IdeaKey(d.topic, it) }
        value.puts.forEach { (d, w) -> weightOf[d] = w; touch(d) }
        value.removals.forEach { d -> if (weightOf.remove(d) != null) touch(d) }
        publish(touched)
    }

    private fun publish(touched: Set<IdeaKey>) {
        publisher.publish(touched, ::score)?.let { outlet.call.propagate(it) }
    }

    /** The idea's [Scored] from current state, or null when it has no rated-and-weighted dimension. */
    private fun score(idea: IdeaKey): Scored? {
        val byDim = TreeMap<String, DimStats>()
        val weighted = TreeMap<String, Double>() // dim → w_d·mean_d
        var totalWeight = 0.0
        for (dim in dimsOfIdea[idea].orEmpty()) {
            val w = weightOf[DimKey(idea.topic, dim)] ?: continue // unweighted: skipped like unrated (D3)
            val s = statsOf.getValue(IdeaDimKey(idea.topic, idea.idea, dim))
            byDim[dim] = s
            weighted[dim] = w * s.mean
            totalWeight += w
        }
        if (byDim.isEmpty()) return null
        return Scored(
            score = weighted.values.sum() / totalWeight,
            contributions = weighted.mapValues { it.value / totalWeight },
            byDim = byDim,
            split = byDim.values.any { it.n >= 2 && it.stdev >= Alignment.SPLIT_STDEV },
        )
    }
}

/**
 * The alignment dataflow (computenet-sigl0 Design):
 *
 * ```
 * ratings  KeyedSetCell<RatingKey, Rating>   put = rate, remove = unrate
 *   -> stats  GroupByCell(IdeaDimKey, RatingStatsAggregator) -> MapDelta<IdeaDimKey, DimStats>
 * weights  MapCell<DimKey, Double>
 * stats -> fusion.stats ; weights -> fusion.weights
 * fusion   WeightedFusionCell -> MapDelta<IdeaKey, Scored>
 * ```
 */
object AlignmentPipeline {
    data class Refs(
        val ratings: TypedRef<KeyedSetApi<RatingKey, Rating>>,
        val weights: TypedRef<MapApi<DimKey, Double>>,
        val stats: CellRef,
        val fusion: CellRef,
    )

    fun build(host: ManagedHost): Refs {
        lateinit var refs: Refs
        graph(host.managementInlet) {
            val ratings = spawn("ratings") { KeyedSetCell<RatingKey, Rating>() }
            val stats = spawn("stats") {
                GroupByCell(keyFn = { r: Rating -> r.key.ideaDimKey }, aggregator = RatingStatsAggregator())
            }
            val weights = spawn("weights") { MapCell<DimKey, Double>() }
            val fusion = spawn("fusion") { WeightedFusionCell() }
            connect(ratings, "outlet", stats, "inlet")
            connect(stats, "outlet", fusion, "stats")
            connect(weights, "outlet", fusion, "weights")
            refs = Refs(ratings.refAs(), weights.refAs(), stats.ref, fusion.ref)
        }
        return refs
    }
}
