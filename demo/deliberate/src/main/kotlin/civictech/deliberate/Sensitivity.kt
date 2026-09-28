package civictech.deliberate

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Magnitude
import civictech.cell.link.catchUpOnLinked
import civictech.cell.observe.View
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.TreeMap
import kotlin.math.abs

/**
 * Model C: the **sensitivity layer** — how much the root's credence moves per
 * unit change of each node's credence, d root / d node, computed top-down by
 * cells of their own beside the credence graph ([CredenceGraph]).
 *
 * The root's sensitivity cell emits 1 (per layer). Every node's sensitivity
 * cell follows the same inputs its credence cell folds — its stances and the
 * [Influence]s arriving at it — and sends, for each incoming edge, (its own
 * sensitivity × the local partial derivative of its credence w.r.t. that edge's
 * strength) to the edge, and (its own sensitivity × the partial w.r.t. the
 * edge's source credence) through the edge to the source ([SensitivityFrame]).
 * A claim sums what arrives through every edge it sources (the chain rule over
 * every path; a deliberation tree has exactly one). Values are vectors indexed
 * like the [LayerSet], layer by layer: sensitivity\[l] = d root\[l] / d node\[l].
 *
 * The layer is one-way: sensitivity cells read what credence cells emit and
 * the stances the graph routes, and no credence cell reads anything a
 * sensitivity cell emits, so the credence graph gains no cycle and no input.
 * Like every credence, sensitivity is derived: it is never journaled, and a
 * restart recomputes it from the replayed structure and stances.
 *
 * The local partials are central finite differences of the layer's own
 * [Semantics.evaluate] at the node's current inputs ([localPartials]) — one
 * rule for every semantics, kinks included (a one-sided difference at 0 and 1).
 */

/** What a node's sensitivity cell tells each incoming edge: the edge's own sensitivity and its source's share. */
@Serializable
@SerialName("deliberate.EdgeSensitivity")
data class EdgeSensitivity(val edge: CellRef, val strength: List<Double>, val source: List<Double>) : java.io.Serializable

/** One node's per-edge sensitivities ([EdgeSensitivity]), broadcast to the sensitivity cells of its incoming edges. */
@Serializable
@SerialName("deliberate.SensitivityFrame")
data class SensitivityFrame(
    val from: CellRef,
    val root: CellRef?,
    val entries: List<EdgeSensitivity>,
    val size: Double,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}

/**
 * A node's sensitivity vector ([values], null: not reached from a root yet) —
 * to the hub keyed by the node ([source]), or, from an edge to its source
 * claim, the share that arrives through that edge ([source] = the edge).
 */
@Serializable
@SerialName("deliberate.Sensitivity")
data class Sensitivity(
    val source: CellRef,
    val root: CellRef?,
    val values: List<Double>?,
    val size: Double,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}

private fun maxDelta(a: List<Double>?, b: List<Double>?): Double = when {
    a == null && b == null -> 0.0
    a == null || b == null -> 1.0
    else -> a.indices.maxOfOrNull { abs(a[it] - b[it]) } ?: 0.0
}

/**
 * One node's sensitivity cell (see the file comment). [subject] is the claim or
 * edge it is about; a [question] root emits 1. An edge's cell ([isEdge]) takes
 * its value from its target's [SensitivityFrame] and passes the source's share
 * on through [sourceOutlet]; a claim's cell sums the shares arriving at
 * [shareInlet].
 */
class SensitivityNode(
    override val ref: CellRef,
    val subject: CellRef,
    private val layers: LayerSet,
    private val question: Boolean = false,
    private val isEdge: Boolean = false,
) : Cell {
    val stanceInlet = registerPort("stanceInlet", FanInlet.create<Propagate<Stance>>())
    val influenceInlet = registerPort("influenceInlet", FanInlet.create<Propagate<Influence>>())
    /** An edge's: its target's frames. */
    val frameInlet = registerPort("frameInlet", FanInlet.create<Propagate<SensitivityFrame>>())
    /** A claim's: the shares its outgoing edges pass on. */
    val shareInlet = registerPort("shareInlet", FanInlet.create<Propagate<Sensitivity>>())
    val frameOutlet = registerPort("frameOutlet", FanOutlet.create<Propagate<SensitivityFrame>>())
    /** An edge's: its source's share, to the source's [shareInlet]. */
    val sourceOutlet = registerPort("sourceOutlet", FanOutlet.create<Propagate<Sensitivity>>())
    val hubOutlet = registerPort("hubOutlet", FanOutlet.create<Propagate<Sensitivity>>())

    private val stances = HashMap<String, Double>()
    private val influences = TreeMap<CellRef, Influence>(ClaimNode.REF_ORDER)
    private val shares = TreeMap<CellRef, Sensitivity>(ClaimNode.REF_ORDER)
    private var fromTarget: SensitivityFrame? = null

    @Volatile
    var sensitivity: Sensitivity = Sensitivity(subject, if (question) subject else null, if (question) List(layers.ids.size) { 1.0 } else null, 0.0)
        private set

    @Volatile
    private var frame: SensitivityFrame? = null

    @Volatile
    private var share: Sensitivity? = null

    init {
        stanceInlet.onEach { s ->
            if (s.value == null) stances.remove(s.user) else stances[s.user] = s.value
            recompute()
        }
        influenceInlet.onEach { i ->
            if (i.strength == null || i.sourceCredence == null) influences.remove(i.edge) else influences[i.edge] = i
            recompute()
        }
        frameInlet.onEach { f ->
            fromTarget = f
            recompute()
        }
        shareInlet.onEach { s ->
            if (s.values == null) shares.remove(s.source) else shares[s.source] = s
            recompute()
        }
        hubOutlet.catchUpOnLinked { sensitivity.copy(size = 0.0) }
        frameOutlet.catchUpOnLinked { frame?.copy(size = 0.0) }
        sourceOutlet.catchUpOnLinked { share?.copy(size = 0.0) }
    }

    /** This node's own sensitivity vector, from the root's 1, its target's frame, or the shares of its edges. */
    private fun own(): Pair<CellRef?, List<Double>?> = when {
        question -> subject to List(layers.ids.size) { 1.0 }
        isEdge -> fromTarget?.let { f -> f.root to f.entries.firstOrNull { it.edge == subject }?.strength }
            ?: (null to null)
        shares.isEmpty() -> null to null
        else -> shares.values.first().root to layers.ids.indices.map { l -> shares.values.sumOf { it.values!![l] } }
    }

    private fun recompute() {
        val (root, values) = own()
        if (values != sensitivity.values || root != sensitivity.root) {
            sensitivity = Sensitivity(subject, root, values, maxDelta(values, sensitivity.values))
            hubOutlet.call.propagate(sensitivity)
        }
        if (isEdge) {
            val sourceShare = fromTarget?.entries?.firstOrNull { it.edge == subject }?.source
            val last = share
            if (last == null || sourceShare != last.values || root != last.root) {
                share = Sensitivity(subject, root, sourceShare, maxDelta(sourceShare, last?.values))
                sourceOutlet.call.propagate(share!!)
            }
        }
        val entries = if (values == null) emptyList() else {
            val ordered = influences.values.toList()
            localPartials(layers, stances.values, ordered).mapIndexed { i, (dStrength, dSource) ->
                EdgeSensitivity(
                    ordered[i].edge,
                    values.indices.map { l -> values[l] * dStrength[l] },
                    values.indices.map { l -> values[l] * dSource[l] },
                )
            }
        }
        val last = frame
        if (last == null || last.entries != entries || last.root != root) {
            val size = entries.maxOfOrNull { e ->
                val was = last?.entries?.firstOrNull { it.edge == e.edge }
                maxOf(maxDelta(e.strength, was?.strength), maxDelta(e.source, was?.source))
            } ?: 0.0
            frame = SensitivityFrame(subject, root, entries, size)
            frameOutlet.call.propagate(frame!!)
        }
    }
}

/** Central-difference step of [localPartials]. */
internal const val PARTIAL_STEP = 1e-6

/**
 * The local partial derivatives of a node's credence, per layer, w.r.t. each
 * of its [influences] (in the given order): (d node / d edge strength,
 * d node / d source credence). Central finite differences of the layer's own
 * [Semantics.evaluate] at the current inputs, one-sided at the ends of [0, 1].
 */
internal fun localPartials(
    layers: LayerSet,
    stances: Collection<Double>,
    influences: List<Influence>,
): List<Pair<List<Double>, List<Double>>> {
    val perLayer = layers.semantics.mapIndexed { l, s ->
        val base = s.base(stances)
        val args = influences.map { Arg(it.strength!![l], it.sourceCredence!![l]) }
        fun eval(i: Int, arg: Arg): Double {
            val attacks = ArrayList<Arg>()
            val supports = ArrayList<Arg>()
            influences.forEachIndexed { j, inf ->
                val a = if (j == i) arg else args[j]
                if (inf.polarity == civictech.agora.cell.Polarity.SUPPORT) supports += a else attacks += a
            }
            return s.evaluate(base, attacks, supports)
        }
        fun diff(x: Double, at: (Double) -> Double): Double {
            val lo = (x - PARTIAL_STEP).coerceAtLeast(0.0)
            val hi = (x + PARTIAL_STEP).coerceAtMost(1.0)
            return if (hi <= lo) 0.0 else (at(hi) - at(lo)) / (hi - lo)
        }
        args.indices.map { i ->
            val a = args[i]
            diff(a.strength) { v -> eval(i, a.copy(strength = v)) } to diff(a.credence) { v -> eval(i, a.copy(credence = v)) }
        }
    }
    return influences.indices.map { i ->
        perLayer.map { it[i].first } to perLayer.map { it[i].second }
    }
}

/**
 * The sensitivity read model: every node's latest [Sensitivity], folded by a
 * kernel `ObserveCell` like [CredenceHubView]. [onUpdate] fires on every
 * effective change.
 */
class SensitivityHubView(private val onUpdate: () -> Unit = {}) : View<Sensitivity, Map<CellRef, Sensitivity>> {
    @Volatile
    private var sensitivities: Map<CellRef, Sensitivity> = emptyMap()

    override fun apply(delta: Sensitivity): Boolean {
        val was = sensitivities[delta.source]
        val changed = was?.values != delta.values || was?.root != delta.root
        if (changed) {
            sensitivities = sensitivities + (delta.source to delta)
            onUpdate()
        }
        return changed
    }

    override fun current(): Map<CellRef, Sensitivity> = sensitivities

    override fun snapshot(): java.io.Serializable = HashMap(sensitivities)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: java.io.Serializable) {
        sensitivities = HashMap(state as Map<CellRef, Sensitivity>)
    }
}
