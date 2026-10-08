package civictech.demograph.ranking

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.view.MapDiffPublisher
import civictech.cell.link.Link
import civictech.cell.link.LinkRole
import civictech.cell.link.catchUpOnLinked
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.registerPort
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.gen.wire.CellBase
import java.util.*
import kotlin.math.abs

/**
 * `@CellBase` Api for [RatingCell] (T09 §C: it now lives in `:demograph` and
 * was first authored in `demo/backlog-triage` — see [RatingCell] for why it,
 * and not [MetaRankCell], is the honest candidate). KSP generates
 * `RatingCellBase`:
 * `inlet` registered and statically bound to `onInlet`, `outlet` registered
 * (emission stays [RatingCell]'s own logic, as `@CellBase`'s authoring
 * contract documents).
 */
@CellBase
interface RatingApi {
    val inlet: Serve<Propagate<SetDelta<PairwisePreference>>>
    val outlet: Subscribe<Propagate<MapDelta<String, Double>>>
}

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
    ref: CellRef = CellRef(UUID.randomUUID()),
) : RatingCellBase(ref) {
    private val live = mutableMapOf<PairwisePreference, MutableSet<Timestamp>>()
    private val ratings = MapDiffPublisher<String, Double>(changed = { a, b -> abs(a - b) > epsilon })
    private val settlement = RankingWaveSettlement(listOf(inlet), outlet)

    init {
        // late-join catch-up (G-22): current ratings as a delta-from-empty
        outlet.catchUpOnLinked { ratings.catchUpDelta() }
    }

    override fun onInlet(value: SetDelta<PairwisePreference>) {
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
        settlement.onData(inlet, publishDiff())
    }

    /** Returns whether this inlet arrival emitted a real data delta. */
    private fun publishDiff(): Boolean {
        val delta = ratings.publishAll(engine.ratings()) ?: return false
        outlet.call.propagate(delta)
        return true
    }
}

/**
 * MetaRankCell — Borda aggregation as dataflow: one named inlet per source
 * algorithm consumes that algorithm's rating `MapDelta` stream (including
 * the kernel-operator mean pipeline), and the combined ranking re-emits
 * effective-only on any upstream change. Input settlement is folded across
 * every open delegate edge before an effective no-op absorb-acks its wave, so
 * an earlier rank-preserving inlet cannot settle a downstream observation
 * before a later inlet emits data for that same wave. The cellular twin of
 * [MetaRank]: meta sits genuinely *downstream* of its delegates in the graph
 * instead of owning private copies of them.
 *
 * The named inlets update on separate propagations, so a mid-wave read can
 * see meta computed from a partially-updated source set before it settles —
 * the same observation-edge glitch recorded as finding F-5 (and targeted by
 * the SnapshotView/observe() backlog items).
 *
 * Deliberately NOT `@CellBase` (T09 §C): its `inlets` are a runtime-sized map
 * keyed by the `sources` constructor argument — one `FanInlet` per algorithm
 * name, decided at construction, not at authoring time. `@CellBase`'s Api
 * interface declares a fixed, statically-named port per property; it has no
 * way to express "N ports, names known only at runtime". [RatingCell] above
 * is the honest `@CellBase` candidate in this file — every one of its ports
 * is fixed at authoring time.
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
        registerPort(name, FanInlet.create<Propagate<MapDelta<String, Double>>>())
    }

    private val settlement = RankingWaveSettlement(inlets.values.toList(), outlet)

    init {
        inlets.forEach { (name, port) ->
            val fold = folded.getValue(name)
            port.onEach { value ->
                fold.putAll(value.puts)
                value.removals.forEach { fold.remove(it) }
                settlement.onData(port, publishDiff())
            }
        }
        outlet.catchUpOnLinked { publisher.catchUpDelta() }
    }

    /** Returns whether this inlet arrival emitted a real data delta. */
    private fun publishDiff(): Boolean {
        val delta = publisher.publishAll(Borda.combine(folded.values.toList())) ?: return false
        outlet.call.propagate(delta)
        return true
    }
}

private fun FanOutlet<*>.sendProgress(progress: Progress) {
    linking.links.forEach { link ->
        Protocols.sendDownstream(link, Protocols.Progress, progress)
    }
}

/**
 * Ranking cells' counterpart of the kernel's fan-in
 * `SettledAbsorbAckRelay` (which is intentionally module-internal). Every data
 * arrival and upstream [Progress] advances only its own open Consume edge.
 * Progress is sent once all currently open delegate edges settle the wave,
 * unless any arrival already emitted data.
 *
 * Unlike the kernel's generic relay this fold does not need
 * `SourceProvenance`: [RatingCell]'s inputs and [MetaRankCell]'s named inlets
 * are algorithm delegates over one preference stream, so all open delegate
 * edges intentionally share one wave domain. Independent roots are not a
 * supported ranking topology. Keeping that limit here prevents this
 * cell-local mechanism being mistaken for a general transparent-hop relay.
 */
private class RankingWaveSettlement(
    private val inlets: List<FanInlet<*>>,
    private val output: FanOutlet<*>,
) {
    private class EdgeState(
        val inlet: FanInlet<*>,
        val link: Link,
        val floors: Map<UUID, Long>,
        var open: Boolean = true,
    )

    private class Wave(var emitted: Boolean = false)

    private val edges = LinkedHashMap<UUID, EdgeState>()
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()
    private val flushedHighWater = mutableMapOf<UUID, Long>()
    private val pending = LinkedHashMap<Timestamp, Wave>()
    private val lock = Any()

    init {
        require(inlets.isNotEmpty()) { "ranking wave settlement needs at least one input" }
        inlets.forEach { inlet ->
            inlet.onEdgeEvent { link, event ->
                val ready = synchronized(lock) {
                    when (event) {
                        EdgeOpen -> edges[link.id] = EdgeState(inlet, link, flushedHighWater.toMap())
                        EdgeClose -> edges[link.id]?.open = false
                    }
                    flushReady()
                }
                deliver(ready)
            }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { link, message ->
                onProgress(link, message as Progress)
            }
        }
    }

    /** Called after the handler, so real data is downstream before settlement can be acknowledged. */
    fun onData(inlet: FanInlet<*>, emitted: Boolean) {
        val context = CurrentContext.get() ?: return
        if (context.baseline != null) return
        val timestamp = context.timestamp
        val ready = synchronized(lock) {
            val edge = edges.values.singleOrNull {
                it.open && it.inlet === inlet && it.link.role == LinkRole.Consume && it.link.from == context.sourcePort
            }
            if (edge == null) {
                return@synchronized if (emitted) emptyList() else listOf(timestamp)
            }
            val floor = edge.floors[timestamp.sourceId] ?: Long.MIN_VALUE
            val flushed = flushedHighWater[timestamp.sourceId] ?: Long.MIN_VALUE
            if (timestamp.counter <= floor || timestamp.counter <= flushed) {
                return@synchronized if (emitted) emptyList() else listOf(timestamp)
            }
            advanceWatermark(edge.link.id, timestamp.sourceId, timestamp.counter)
            val wave = pending.getOrPut(timestamp) { Wave() }
            wave.emitted = wave.emitted || emitted
            flushReady()
        }
        deliver(ready)
    }

    private fun onProgress(link: Link, progress: Progress) {
        val ready = synchronized(lock) {
            val edge = edges[link.id]
            if (edge == null || !edge.open || edge.link.role != LinkRole.Consume) {
                return@synchronized emptyList()
            }
            advanceWatermark(link.id, progress.sourceId, progress.thru)
            val floor = edge.floors[progress.sourceId] ?: Long.MIN_VALUE
            val flushed = flushedHighWater[progress.sourceId] ?: Long.MIN_VALUE
            if (progress.thru > floor && progress.thru > flushed) {
                pending.getOrPut(Timestamp(progress.sourceId, progress.thru)) { Wave() }
            }
            flushReady()
        }
        deliver(ready)
    }

    private fun advanceWatermark(edgeId: UUID, sourceId: UUID, counter: Long) {
        watermark.getOrPut(edgeId) { mutableMapOf() }.merge(sourceId, counter, ::maxOf)
    }

    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.open && it.link.role == LinkRole.Consume }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    private fun settled(edgeId: UUID, timestamp: Timestamp): Boolean =
        (watermark[edgeId]?.get(timestamp.sourceId) ?: Long.MIN_VALUE) >= timestamp.counter

    /** Called with [lock] held; returns acknowledgements to send after releasing it. */
    private fun flushReady(): List<Timestamp> {
        val ready = pending.keys
            .filter { timestamp -> expectedEdges(timestamp).all { settled(it, timestamp) } }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        val acknowledgements = mutableListOf<Timestamp>()
        ready.forEach { timestamp ->
            val wave = pending.remove(timestamp) ?: return@forEach
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            if (!wave.emitted) acknowledgements += timestamp
        }
        return acknowledgements
    }

    private fun deliver(timestamps: List<Timestamp>) {
        timestamps.forEach { timestamp ->
            output.sendProgress(Progress(timestamp.sourceId, timestamp.counter))
        }
    }
}
