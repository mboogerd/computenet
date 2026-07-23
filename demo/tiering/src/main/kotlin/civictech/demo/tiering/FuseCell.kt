package civictech.demo.tiering

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.MapDelta
import civictech.cell.data.MapDiffPublisher
import civictech.cell.data.Propagate
import civictech.cell.data.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

/** A fused score and its fixed-threshold tier. */
data class Tiered(val score: Double, val tier: String) : Serializable

/** Score fusion + fixed-threshold bucketing (the user-decided semantics). */
object Tiering {
    /** Tiers best-first; absolute valuations map S=6 .. F=0. */
    val TIERS = listOf("S", "A", "B", "C", "D", "E", "F")
    val SCORE_OF: Map<String, Long> = TIERS.withIndex().associate { (i, t) -> t to (6 - i).toLong() }
    val TIER_OF_SCORE: Map<Long, String> = SCORE_OF.entries.associate { (t, s) -> s to t }

    const val TIER_WEIGHT = 0.7
    const val PREF_WEIGHT = 0.3

    /** Fixed cutoffs on the fused score in [0,1]. */
    fun tierOf(score: Double): String = when {
        score >= 0.85 -> "S"
        score >= 0.70 -> "A"
        score >= 0.55 -> "B"
        score >= 0.40 -> "C"
        score >= 0.25 -> "D"
        score >= 0.10 -> "E"
        else -> "F"
    }

    /**
     * tierAvg ∈ [0,6] (mean absolute score), prefAvg ∈ [-1,1] (mean pairwise
     * contribution). Both normalized to [0,1] and blended 0.7/0.3; an item
     * with only one signal uses that signal alone; no signal at all → null.
     */
    fun fuse(tierAvg: Double?, prefAvg: Double?): Tiered? {
        val tierNorm = tierAvg?.let { it / 6.0 }
        val prefNorm = prefAvg?.let { (it + 1.0) / 2.0 }
        val score = when {
            tierNorm != null && prefNorm != null -> TIER_WEIGHT * tierNorm + PREF_WEIGHT * prefNorm
            tierNorm != null -> tierNorm
            prefNorm != null -> prefNorm
            else -> return null
        }
        return Tiered(score, tierOf(score))
    }
}

interface FuseApi {
    val left: Serve<Propagate<MapDelta<String, Double>>>
    val right: Serve<Propagate<MapDelta<String, Double>>>
    val outlet: Subscribe<Propagate<MapDelta<String, Tiered>>>
}

/**
 * Per-key combine-latest over two MapDelta streams with OUTER semantics: an
 * item present on either side gets a fused value; a key dropped from both
 * sides is removed (group-death flows through). Emission is effective-only —
 * a key re-fuses only when its `Tiered` actually changes.
 *
 * Deliberately app-level: the kernel has no map-stream fusion/outer-join
 * cell (`JoinCell` is inner-join) and no threshold-bucketing operator. This
 * cell is the discovered-requirement prototype recorded as findings F-1 and
 * F-2 in doc/demo-findings.md — a candidate for a generalized kernel
 * `CombineLatestCell`.
 */
class FuseCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : FuseApi, Cell, Stateful {
    override val left =
        registerPort("left", FanInlet.create<Propagate<MapDelta<String, Double>>>())

    override val right =
        registerPort("right", FanInlet.create<Propagate<MapDelta<String, Double>>>())

    override val outlet =
        registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Tiered>>>())

    private val tierAvg = mutableMapOf<String, Double>()
    private val prefAvg = mutableMapOf<String, Double>()
    private val publisher = MapDiffPublisher<String, Tiered>()

    init {
        left.onEach { fold(tierAvg, it) }
        right.onEach { fold(prefAvg, it) }
        // late-join catch-up (G-22): current tiers as a delta-from-empty
        outlet.catchUpOnLinked { publisher.catchUpDelta() }
    }

    private fun fold(side: MutableMap<String, Double>, value: MapDelta<String, Double>) {
        side.putAll(value.puts)
        value.removals.forEach { side.remove(it) }
        publisher.publish(value.puts.keys + value.removals) { item ->
            Tiering.fuse(tierAvg[item], prefAvg[item])
        }?.let { outlet.call.propagate(it) }
    }

    override fun snapshot(): Serializable = arrayListOf(HashMap(tierAvg), HashMap(prefAvg))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (t, p) = state as ArrayList<HashMap<String, Double>>
        tierAvg.clear(); tierAvg.putAll(t)
        prefAvg.clear(); prefAvg.putAll(p)
        val fused = mutableMapOf<String, Tiered>()
        (tierAvg.keys + prefAvg.keys).forEach { item ->
            Tiering.fuse(tierAvg[item], prefAvg[item])?.let { fused[item] = it }
        }
        publisher.reset(fused)
    }
}
