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

/** [RatingStatsAggregator]'s accumulator: count and the first two moment sums of the live ratings, in exact thousandths. */
data class StatsAcc(val n: Long, val sum: Long, val sumSq: Long) : Serializable

/**
 * Per-(idea, dimension) rating statistics for `GroupByCell`: count, mean and
 * SAMPLE standard deviation (0.0 when n < 2). Ratings are fixed-point
 * thousandths ([RatingScale]), so both sums are exact `Long`s under any
 * insert/retract order and the fold never drifts; the variance is
 * `n·Σx² − (Σx)²` over `n(n−1)` — exact up to the one final division, and the
 * same arithmetic [Alignment.rankBatch] does on the same integers — and
 * clamped at 0 so `sqrt` never sees a negative.
 */
class RatingStatsAggregator : Aggregator<Rating, DimStats, StatsAcc> {
    override fun empty(): StatsAcc = StatsAcc(0, 0, 0)

    override fun insert(acc: StatsAcc, element: Rating): StatsAcc {
        val x = element.milli.toLong()
        return StatsAcc(acc.n + 1, acc.sum + x, acc.sumSq + x * x)
    }

    override fun retract(acc: StatsAcc, element: Rating): StatsAcc {
        val x = element.milli.toLong()
        return StatsAcc(acc.n - 1, acc.sum - x, acc.sumSq - x * x)
    }

    override fun value(acc: StatsAcc): DimStats {
        val n = acc.n
        val variance = if (n < 2) 0.0 else max(0.0, (n * acc.sumSq - acc.sum * acc.sum).toDouble() / (n * (n - 1)))
        return DimStats(n, acc.sum.toDouble() / (1000 * n), sqrt(variance) / 1000.0)
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
    val weights: Serve<Propagate<MapDelta<DimKey, DimConfig>>>
    val outlet: Subscribe<Propagate<MapDelta<IdeaKey, Scored>>>
}

/**
 * WeightedFusionCell — the per-idea value ÷ cost aggregate across a topic's
 * creator-defined dimensions (computenet-sigl0-D1..D4, computenet-k1d4g-D2..D4).
 *
 * Demo-local rather than a chain of kernel `CombineLatestCell`s: that cell is
 * binary, and here the number of dimensions is decided at run time per topic.
 * Its ports are nonetheless fixed at authoring time — one stream of per-(idea,
 * dim) stats and one of per-dim weights — so it is an honest `@CellBase` cell
 * (contrast backlog-triage's `MetaRankCell`).
 *
 * Holds the latest stats and dimension configs and recomputes only the ideas
 * an incoming delta touches: a stats delta touches the ideas of its keys; a
 * config delta touches every idea of that topic with stats on the changed
 * dimension, AND — when it flips the topic's has-cost bit (first COST config
 * put; last COST config removed or redirected to VALUE) — every idea of that
 * topic with any stats, because whether a topic has a cost dimension at all
 * decides whether an idea rated only on value dimensions is ranked
 * (k1d4g-D4). Emission is effective-only through [MapDiffPublisher] (exact
 * value equality): an idea whose recompute is unchanged is not re-emitted,
 * and an idea left with no rated-and-configured dimension is REMOVED —
 * unscored is an absent key. A [Scored] with a null score is present: it
 * carries the stats and names the unrated side (k1d4g-D3).
 */
class WeightedFusionCell(ref: CellRef = CellRef(UUID.randomUUID())) : WeightedFusionCellBase(ref) {
    private val statsOf = HashMap<IdeaDimKey, DimStats>()
    private val configOf = HashMap<DimKey, DimConfig>()

    /** topic → its dimensions currently configured COST (rated or not): the has-cost bit is non-emptiness. */
    private val costDimsOfTopic = HashMap<TopicId, MutableSet<String>>()

    /** topic → its ideas that currently have stats on any dimension: the has-cost flip's touch set. */
    private val ideasOfTopic = HashMap<TopicId, MutableSet<String>>()

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
            ideasOfTopic.getOrPut(k.topic) { mutableSetOf() } += k.idea
            ideasOfDim.getOrPut(k.dimKey) { mutableSetOf() } += k.idea
            touched += k.ideaKey
        }
        value.removals.forEach { k ->
            if (statsOf.remove(k) == null) return@forEach
            dimsOfIdea[k.ideaKey]?.let {
                if (it.remove(k.dim) && it.isEmpty()) {
                    dimsOfIdea.remove(k.ideaKey)
                    ideasOfTopic[k.topic]?.let { t -> if (t.remove(k.idea) && t.isEmpty()) ideasOfTopic.remove(k.topic) }
                }
            }
            ideasOfDim[k.dimKey]?.let { if (it.remove(k.idea) && it.isEmpty()) ideasOfDim.remove(k.dimKey) }
            touched += k.ideaKey
        }
        publish(touched)
    }

    override fun onWeights(value: MapDelta<DimKey, DimConfig>) {
        val touched = LinkedHashSet<IdeaKey>()
        fun touch(d: DimKey) = ideasOfDim[d]?.forEach { touched += IdeaKey(d.topic, it) }

        /** Applies [config] (null = removal) to [d]'s cost index; on a has-cost flip, touches the whole topic. */
        fun reindex(d: DimKey, config: DimConfig?) {
            val hadCost = costDimsOfTopic[d.topic].orEmpty().isNotEmpty()
            if (config?.direction == Direction.COST) {
                costDimsOfTopic.getOrPut(d.topic) { mutableSetOf() } += d.dim
            } else {
                costDimsOfTopic[d.topic]?.let { if (it.remove(d.dim) && it.isEmpty()) costDimsOfTopic.remove(d.topic) }
            }
            val hasCost = costDimsOfTopic[d.topic].orEmpty().isNotEmpty()
            if (hadCost != hasCost) ideasOfTopic[d.topic]?.forEach { touched += IdeaKey(d.topic, it) }
        }
        value.puts.forEach { (d, c) -> configOf[d] = c; reindex(d, c); touch(d) }
        value.removals.forEach { d -> if (configOf.remove(d) != null) { reindex(d, null); touch(d) } }
        publish(touched)
    }

    private fun publish(touched: Set<IdeaKey>) {
        publisher.publish(touched, ::score)?.let { outlet.call.propagate(it) }
    }

    /**
     * The idea's [Scored] from current state (k1d4g-D2), or null when it has no
     * rated-and-configured dimension (unconfigured: skipped like unrated, sigl0-D3).
     */
    private fun score(idea: IdeaKey): Scored? {
        val byDim = TreeMap<String, DimStats>()
        val valueWeighted = TreeMap<String, Double>() // VALUE dim → w_d·mean_d
        var valueWeight = 0.0
        var costWeighted = 0.0
        var costWeight = 0.0
        for (dim in dimsOfIdea[idea].orEmpty()) {
            val c = configOf[DimKey(idea.topic, dim)] ?: continue
            val s = statsOf.getValue(IdeaDimKey(idea.topic, idea.idea, dim))
            byDim[dim] = s
            when (c.direction) {
                Direction.VALUE -> { valueWeighted[dim] = c.weight * s.mean; valueWeight += c.weight }
                Direction.COST -> { costWeighted += c.weight * s.mean; costWeight += c.weight }
            }
        }
        if (byDim.isEmpty()) return null
        val value = if (valueWeighted.isEmpty()) null else valueWeighted.values.sum() / valueWeight
        val cost = if (costWeight == 0.0) null else costWeighted / costWeight
        val score = when {
            costDimsOfTopic[idea.topic].isNullOrEmpty() -> value
            value != null && cost != null -> value / cost
            else -> null
        }
        return Scored(
            score = score,
            value = value,
            cost = cost,
            contributions = if (score == null) emptyMap()
            else valueWeighted.mapValues { it.value / valueWeight / (cost ?: 1.0) },
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
 * weights  MapCell<DimKey, DimConfig>     weight + direction per dimension
 * stats -> fusion.stats ; weights -> fusion.weights
 * fusion   WeightedFusionCell -> MapDelta<IdeaKey, Scored>
 * ```
 */
object AlignmentPipeline {
    data class Refs(
        val ratings: TypedRef<KeyedSetApi<RatingKey, Rating>>,
        val weights: TypedRef<MapApi<DimKey, DimConfig>>,
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
            val weights = spawn("weights") { MapCell<DimKey, DimConfig>() }
            val fusion = spawn("fusion") { WeightedFusionCell() }
            connect(ratings, "outlet", stats, "inlet")
            connect(stats, "outlet", fusion, "stats")
            connect(weights, "outlet", fusion, "weights")
            refs = Refs(ratings.refAs(), weights.refAs(), stats.ref, fusion.ref)
        }
        return refs
    }
}
