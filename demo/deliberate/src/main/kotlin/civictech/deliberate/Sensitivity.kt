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
import civictech.cell.port.PropagateFeedbackInlet
import civictech.cell.port.registerPort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.TreeMap
import kotlin.math.abs

/**
 * Legacy model-C sensitivity payloads and cells. New graphs do not create or
 * consult these: [ExactValueEvaluator] replaces them. The classes remain so a
 * topology journal written before exact VoI can deserialize its recorded
 * [SensitivityFactory] spawns and restore the ordinary credence graph.
 *
 * The retired layer computed how much the root's credence moves per
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
    private val quiescence: Double = 0.0,
) : Cell {
    val stanceInlet = registerPort("stanceInlet", FanInlet.create<Propagate<Stance>>())
    val influenceInlet = registerPort("influenceInlet", FanInlet.create<Propagate<Influence>>())
    /** An edge's: its target's frames. */
    val frameInlet = registerPort("frameInlet", FanInlet.create<Propagate<SensitivityFrame>>())
    val feedbackFrameInlet = registerPort(
        "feedbackFrameInlet",
        PropagateFeedbackInlet<SensitivityFrame>(
            quiescence = quiescence,
            payloadType = SensitivityFrame::class.java,
        ) { onFrame(it) },
    )
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

    // recompute() runs on the host scheduler, while catchUpOnLinked snapshots
    // run on the linking thread. These immutable snapshot references therefore
    // need volatile publication so a link-time catch-up sees the latest state.
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
        frameInlet.onEach(::onFrame)
        shareInlet.onEach { s ->
            if (s.values == null) shares.remove(s.source) else shares[s.source] = s
            recompute()
        }
        hubOutlet.catchUpOnLinked { sensitivity.copy(size = 0.0) }
        // A linked edge has no prior target frame, so this state-as-delta-from-
        // empty is effective even though the target itself did not just change.
        // The null-to-present magnitude also lets a feedback-frame inlet learn
        // a frame that already contains this edge instead of absorbing its only
        // baseline at quiescence.
        frameOutlet.catchUpOnLinked { frame?.copy(size = 1.0) }
        sourceOutlet.catchUpOnLinked { share?.copy(size = 0.0) }
    }

    private fun onFrame(f: SensitivityFrame) {
        fromTarget = f
        recompute()
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

/** One edge in the immutable snapshot used by [ExactValueEvaluator]. */
internal data class ExactEdge(val source: CellRef, val target: CellRef, val polarity: civictech.agora.cell.Polarity)

/**
 * One node in the immutable snapshot used by [ExactValueEvaluator]. [current]
 * is the graph's settled credence vector; [stances] are its replayable inputs.
 */
internal data class ExactNode(
    val current: List<Double>,
    val stances: List<Double>,
    val edge: ExactEdge? = null,
)

/** The answer root under the two possible resolutions of one node. */
internal data class ExactRootChange(
    val root: CellRef,
    val current: Double,
    val whenFalse: Double,
    val whenTrue: Double,
) {
    val signedSway: Double get() = whenTrue - whenFalse

    /**
     * Twice the expected absolute movement from the current answer. The factor
     * two keeps the scale of model C: for a locally linear path this is exactly
     * `|d root / d node| * 4q(1-q)`.
     */
    fun expected(probability: Double): Double = 2 * (
        probability * abs(whenTrue - current) +
            (1 - probability) * abs(whenFalse - current)
        )
}

/** Exact, q-weighted value of resolving one node, summed over the requested answer roots. */
internal data class ExactValueOfInformation(
    val probability: Double,
    val roots: List<ExactRootChange>,
) {
    val expectedRootChange: Double = roots.sumOf { it.expected(probability) }

    /** The signed secant for the root to which this node has the largest exact value. */
    val dominantSway: Double? = roots.maxByOrNull { it.expected(probability) }?.signedSway
}

/**
 * Pure two-point re-evaluation of a credence graph snapshot. Resolving a node
 * replaces its emitted credence vector with all-zeroes or all-ones, then every
 * dependent node is recomputed from its original stances and the changed
 * upstream vectors. The dependency walk includes both the source and the edge
 * node of every influence, so converging paths are evaluated together rather
 * than as one chosen node-to-root path.
 *
 * Acyclic regions settle in dependency order. A remaining cyclic region uses
 * deterministic fixed-point relaxation to [quiescence], matching the live
 * graph's bounded cycle semantics; deliberate's current exploration graphs
 * are acyclic.
 */
internal class ExactValueEvaluator(
    private val layers: LayerSet,
    private val nodes: Map<CellRef, ExactNode>,
    private val quiescence: Double,
) {
    private val neutral = List(layers.ids.size) { 0.5 }
    private val incoming = nodes.entries.mapNotNull { (ref, node) -> node.edge?.let { it.target to ref } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, refs) -> refs.sortedWith(ClaimNode.REF_ORDER) }
    private val dependencies = nodes.keys.associateWith { linkedSetOf<CellRef>() }.toMutableMap()
    private val dependents = nodes.keys.associateWith { linkedSetOf<CellRef>() }.toMutableMap()

    init {
        nodes.forEach { (edgeRef, node) ->
            node.edge?.let { edge ->
                dependencies.getOrPut(edge.target) { linkedSetOf() }.add(edgeRef)
                dependencies.getOrPut(edge.target) { linkedSetOf() }.add(edge.source)
                dependents.getOrPut(edgeRef) { linkedSetOf() }.add(edge.target)
                dependents.getOrPut(edge.source) { linkedSetOf() }.add(edge.target)
            }
        }
    }

    fun valueOf(subject: CellRef, roots: Collection<CellRef>): ExactValueOfInformation? {
        if (subject !in nodes) return null
        // Do not use the live hub values as the mathematical baseline. A stance
        // is recorded before its asynchronous cell propagation reaches the hub,
        // and exploration asks for VoI at exactly that boundary. Re-evaluating
        // the captured inputs makes R, q, R0 and R1 one coherent snapshot.
        val baseline = settle()
        val probability = headline(baseline.getValue(subject))
        val low = settle(subject, 0.0, baseline)
        val high = settle(subject, 1.0, baseline)
        val changes = roots.distinct().mapNotNull { root ->
            val current = baseline[root] ?: return@mapNotNull null
            ExactRootChange(
                root = root,
                current = headline(current),
                whenFalse = headline(low[root] ?: current),
                whenTrue = headline(high[root] ?: current),
            )
        }
        return ExactValueOfInformation(probability, changes)
    }

    private fun headline(values: List<Double>): Double = layers.headlineOf(values, layers.consensus(values))

    private fun settle(
        subject: CellRef? = null,
        resolution: Double = 0.0,
        seed: Map<CellRef, List<Double>> = nodes.mapValues { it.value.current },
    ): Map<CellRef, List<Double>> {
        val values = seed.toMutableMap()
        if (subject != null) values[subject] = List(layers.ids.size) { resolution }

        val affected = if (subject == null) {
            nodes.keys.toMutableSet()
        } else {
            linkedSetOf(subject).also { reached ->
                val work = ArrayDeque<CellRef>().apply { add(subject) }
                while (work.isNotEmpty()) {
                    val next = work.removeFirst()
                    dependents[next].orEmpty().forEach { if (reached.add(it)) work.add(it) }
                }
            }
        }

        // A forced node is already settled. Kahn's order evaluates every
        // other DAG node once after all of its affected dependencies.
        val pending = affected.filter { subject == null || it != subject }.toMutableSet()
        val indegree = pending.associateWith { node ->
            dependencies[node].orEmpty().count { it in pending }
        }.toMutableMap()
        val ready = java.util.PriorityQueue(ClaimNode.REF_ORDER)
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        while (ready.isNotEmpty()) {
            val ref = ready.remove()
            if (!pending.remove(ref)) continue
            values[ref] = evaluate(ref, values)
            dependents[ref].orEmpty().forEach { downstream ->
                if (downstream in pending) {
                    val left = indegree.getValue(downstream) - 1
                    indegree[downstream] = left
                    if (left == 0) ready.add(downstream)
                }
            }
        }

        // Only cycles (and nodes downstream of them) remain. The iteration is
        // deterministic, and the live graph uses the same quiescence bound.
        if (pending.isNotEmpty()) {
            val ordered = pending.sortedWith(ClaimNode.REF_ORDER)
            repeat(MAX_RELAXATIONS) {
                var largest = 0.0
                ordered.forEach { ref ->
                    val before = values[ref] ?: neutral
                    val after = evaluate(ref, values)
                    largest = maxOf(largest, before.indices.maxOfOrNull { abs(before[it] - after[it]) } ?: 0.0)
                    values[ref] = after
                }
                if (largest <= maxOf(quiescence, EXACT_EPSILON)) return values
            }
        }
        return values
    }

    private fun evaluate(ref: CellRef, values: Map<CellRef, List<Double>>): List<Double> {
        val node = nodes.getValue(ref)
        val attacks = ArrayList<List<Arg>>()
        val supports = ArrayList<List<Arg>>()
        incoming[ref].orEmpty().forEach { edgeRef ->
            val edge = nodes.getValue(edgeRef).edge!!
            val strength = values[edgeRef] ?: neutral
            val source = values[edge.source] ?: neutral
            val args = layers.ids.indices.map { layer -> Arg(strength[layer], source[layer]) }
            if (edge.polarity == civictech.agora.cell.Polarity.SUPPORT) supports += args else attacks += args
        }
        return layers.evaluate(node.stances, attacks, supports)
    }

    private companion object {
        const val MAX_RELAXATIONS = 10_000
        const val EXACT_EPSILON = 1e-12
    }
}
