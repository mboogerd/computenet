package civictech.deliberate

import civictech.agora.cell.Polarity
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
 * The deliberation's credence graph (SPEC CRED-04): one cell per claim and
 * one per edge, each computing a credence *vector* — one value per layer of a
 * [LayerSet] — instead of one agora graph per semantics. It keeps agora's
 * model: an edge is a claim ("source supports/attacks target") with its own
 * stances, its own incoming edges (undercutters) and its own credence; a
 * claim's credence is a deterministic function of its current stances and
 * the current influences of its incoming edges, never of arrival order.
 */

/** A user's stance on a claim or edge, last-writer-wins per user; null clears it. The graph's only input. */
@Serializable
@SerialName("deliberate.Stance")
data class Stance(val user: String, val value: Double?) : java.io.Serializable

/**
 * A node's current credence vector ([values], indexed like the [LayerSet])
 * with its derived summary: the [consensus] of the member layers and the
 * [spreadLow]..[spreadHigh] range over all layers (SPEC CRED-05). [size] is
 * the largest per-layer change against the node's previous emission — the
 * magnitude the host schedules by.
 */
@Serializable
@SerialName("deliberate.Credence")
data class Credence(
    val source: CellRef,
    val values: List<Double>,
    val consensus: Double,
    val spreadLow: Double,
    val spreadHigh: Double,
    val size: Double,
    /**
     * Model D's arguments-first view: the same direct argument inputs weighed
     * from a neutral prior ([LayerSet.WEAK_PRIOR_WEIGHT]) instead of this
     * node's Jev prior. An unargued node keeps [values], so the view never
     * invents a neutral standing where no argument exists. Nullable only for
     * additive payload compatibility; new claim and edge cells always emit it.
     */
    val neutral: List<Double>? = null,
    /**
     * Model D: true once the node has an incoming argument, so [neutral] is its
     * own neutral-prior evaluation rather than a copy of [values]. A question
     * or reading root's "arguments alone" verdict reads it to keep its
     * unargued value at the neutral ½, exactly as before the view generalised.
     */
    val argued: Boolean = false,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}

/**
 * The current influence of one [edge] on its target, as structured data rather
 * than a pre-multiplied energy: the edge's own credence vector ([strength]:
 * its Jev strength stance, lowered by undercutters) and its source's credence
 * vector ([sourceCredence]), so each layer computes its own energy from the
 * two ([Semantics.energy]). A complete value, not an increment; null vectors
 * retract it. [size] is the largest per-layer change of either vector against
 * the previous emission.
 */
@Serializable
@SerialName("deliberate.Influence")
data class Influence(
    val edge: CellRef,
    val polarity: Polarity,
    val strength: List<Double>?,
    val sourceCredence: List<Double>?,
    val size: Double,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}

private fun maxDelta(a: List<Double>, b: List<Double>): Double =
    a.indices.maxOfOrNull { abs(a[it] - b[it]) } ?: 0.0

/**
 * A claim. Emits its [Credence] with the consensus and spread already derived
 * — the choice SPEC CRED-06 records: the consensus is a pure function of the
 * vector, so computing it where the vector is computed makes it a derived
 * value of the graph (it rides every credence emission to the hub) without a
 * second cell per claim, a second hop, or a second fold; `snapshot()` only
 * reads the hub.
 */
open class ClaimNode(
    override val ref: CellRef,
    protected val layers: LayerSet,
) : Cell {
    val stanceInlet = registerPort("stanceInlet", FanInlet.create<Propagate<Stance>>())
    val influenceInlet = registerPort("influenceInlet", FanInlet.create<Propagate<Influence>>())
    val credenceOutlet = registerPort("credenceOutlet", FanOutlet.create<Propagate<Credence>>())

    private val stances = HashMap<String, Double>()

    /** Ref-sorted so every layer folds its arguments in one fixed order (FP determinism). */
    private val influences = TreeMap<CellRef, Influence>(REF_ORDER)

    private val initial = layers.evaluate(emptyList(), emptyList(), emptyList())

    @Volatile
    var credence: Credence = credenceOf(initial, initial, size = 0.0)
        private set

    init {
        stanceInlet.onEach { s ->
            if (s.value == null) stances.remove(s.user) else stances[s.user] = s.value
            recompute()
        }
        influenceInlet.onEach { i ->
            if (i.strength == null || i.sourceCredence == null) influences.remove(i.edge) else influences[i.edge] = i
            recompute()
        }
        // A catch-up is state-as-delta-from-empty: it is effective for a fresh
        // receiver even when it is not a live change at this source. Using the
        // model's null-to-present magnitude keeps a feedback head from absorbing
        // its only baseline. The snapshot read can run on the linking thread, so
        // [credence] is safely published above.
        credenceOutlet.catchUpOnLinked { credence.copy(size = 1.0) }
    }

    private fun credenceOf(values: List<Double>, neutral: List<Double>, size: Double, argued: Boolean = false) =
        Credence(ref, values, layers.consensus(values), values.min(), values.max(), size, neutral, argued)

    private fun recompute() {
        val attacks = ArrayList<List<Arg>>()
        val supports = ArrayList<List<Arg>>()
        for (i in influences.values) {
            val args = i.strength!!.indices.map { l -> Arg(i.strength[l], i.sourceCredence!![l]) }
            if (i.polarity == Polarity.SUPPORT) supports += args else attacks += args
        }
        val values = layers.evaluate(stances.values, attacks, supports)
        // No arguments means there is nothing to evaluate "arguments first":
        // keep the ordinary Jev prior. Once argued, change only this node's
        // base; its argument vectors are the same ordinary inputs as before.
        val argued = influences.isNotEmpty()
        val neutral = if (!argued) values else
            layers.evaluate(stances.values, attacks, supports, LayerSet.WEAK_PRIOR_WEIGHT)
        if (values != credence.values || neutral != credence.neutral || argued != credence.argued) {
            val size = maxOf(maxDelta(values, credence.values), maxDelta(neutral, credence.neutral ?: credence.values))
            credence = credenceOf(values, neutral, size, argued)
            credenceOutlet.call.propagate(credence)
            onCredence()
        }
    }

    /** Hook for [EdgeNode]: the own credence changed (already emitted). */
    protected open fun onCredence() {}

    companion object {
        val REF_ORDER: Comparator<CellRef> = compareBy({ it.id }, { it.instanceId })
    }
}

/**
 * An edge: a [ClaimNode] (its stance is the Jev relation strength, CRED-02;
 * undercutters attack it) that also follows its source's credence and pushes
 * an [Influence] carrying both vectors at its target.
 *
 * [quiescence] is the cycle-head absorb threshold. The admitted closing link
 * lands on [feedbackInlet] when this edge is a head; ordinary source links
 * land on [sourceInlet]. Both inlets feed the same handler. A deliberation
 * tree has no cycles, but the graph does not assume that.
 */
class EdgeNode(
    val polarity: Polarity,
    ref: CellRef,
    layers: LayerSet,
    val quiescence: Double = 0.0,
) : ClaimNode(ref, layers) {
    val sourceInlet = registerPort("sourceInlet", FanInlet.create<Propagate<Credence>>())
    val feedbackInlet = registerPort(
        "feedbackInlet",
        PropagateFeedbackInlet<Credence>(
            quiescence = quiescence,
            payloadType = Credence::class.java,
        ) { onSource(it) },
    )
    val influenceOutlet = registerPort("influenceOutlet", FanOutlet.create<Propagate<Influence>>())

    /** Neutral until the source's state-as-delta-from-empty catch-up arrives. */
    private var sourceCredence: List<Double> = credence.values
    private var last = Influence(ref, polarity, credence.values, sourceCredence, size = 0.0)

    init {
        sourceInlet.onEach(::onSource)
        influenceOutlet.catchUpOnLinked { last.copy(size = 0.0) }
    }

    private fun onSource(c: Credence) {
        sourceCredence = c.values
        emitInfluence()
    }

    override fun onCredence() = emitInfluence()

    private fun emitInfluence() {
        val strength = credence.values
        if (strength == last.strength && sourceCredence == last.sourceCredence) return
        val size = maxOf(maxDelta(strength, last.strength!!), maxDelta(sourceCredence, last.sourceCredence!!))
        last = Influence(ref, polarity, strength, sourceCredence, size)
        influenceOutlet.call.propagate(last)
    }
}

/**
 * Model A: the shares of the competing positions of one issue ([source] = the
 * issue's root): [values] per position (in [positions] order) per layer, and
 * [consensus] per position. [Softmax.shares] treats the credences as absolute
 * weights: listed shares may sum below one, with the remainder meaning none of
 * the listed positions. Derived and volatile like a credence (SPEC DUR-01).
 * [size] is the largest per-position-per-layer change against the previous
 * emission.
 */
@Serializable
@SerialName("deliberate.Shares")
data class Shares(
    val source: CellRef,
    val positions: List<CellRef>,
    val values: List<List<Double>>,
    val consensus: List<Double>,
    val size: Double,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}

/**
 * Model A: the distribution cell of a POSITIONS issue. Hears every position's
 * [Credence] (keyed by [Credence.source]) and emits their [Shares]; a
 * position not heard from yet counts ½ in every layer and in the consensus.
 * Nothing it emits is wired into a credence cell (CRED-03): shares are a read
 * of the positions' credences, never an input to them.
 */
class IssueNode(
    override val ref: CellRef,
    val root: CellRef,
    val positions: List<CellRef>,
    private val layers: LayerSet,
) : Cell {
    val positionInlet = registerPort("positionInlet", FanInlet.create<Propagate<Credence>>())
    val sharesOutlet = registerPort("sharesOutlet", FanOutlet.create<Propagate<Shares>>())

    private val heard = HashMap<CellRef, Credence>()

    var shares: Shares = compute(size = 0.0)
        private set

    init {
        positionInlet.onEach { c ->
            if (c.source !in positions) return@onEach
            heard[c.source] = c
            val next = compute(size = 0.0)
            if (next.values != shares.values || next.consensus != shares.consensus) {
                val size = next.values.indices.maxOfOrNull { p -> maxDelta(next.values[p], shares.values[p]) } ?: 0.0
                shares = next.copy(size = size)
                sharesOutlet.call.propagate(shares)
            }
        }
        sharesOutlet.catchUpOnLinked { shares.copy(size = 0.0) }
    }

    private fun compute(size: Double): Shares {
        val neutral = List(layers.ids.size) { 0.5 }
        val vectors = positions.map { heard[it]?.values ?: neutral }
        val perLayer = layers.ids.indices.map { l -> Softmax.shares(vectors.map { it[l] }) }
        val values = positions.indices.map { p -> perLayer.map { it[p] } }
        val consensus = Softmax.shares(positions.map { heard[it]?.consensus ?: 0.5 })
        return Shares(root, positions, values, consensus, size)
    }
}

/** Model A: folds every POSITIONS issue's [Shares] into `{ root -> latest shares }`, as [CredenceHubView] does credences. */
class SharesHubView(private val onUpdate: () -> Unit = {}) : View<Shares, Map<CellRef, Shares>> {
    @Volatile
    private var shares: Map<CellRef, Shares> = emptyMap()

    override fun apply(delta: Shares): Boolean {
        val changed = shares[delta.source].let { it?.values != delta.values || it.consensus != delta.consensus }
        if (changed) {
            shares = shares + (delta.source to delta)
            onUpdate()
        }
        return changed
    }

    override fun current(): Map<CellRef, Shares> = shares

    override fun snapshot(): java.io.Serializable = HashMap(shares)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: java.io.Serializable) {
        shares = HashMap(state as Map<CellRef, Shares>)
    }
}

/**
 * The read model: folds every node's [Credence] into one immutable
 * `{ node -> latest credence }` map, run by a kernel `ObserveCell`. It is the
 * only place `snapshot()` reads credences from. [onUpdate] fires on every
 * effective change (the app's SSE dirty flag).
 */
class CredenceHubView(private val onUpdate: () -> Unit = {}) : View<Credence, Map<CellRef, Credence>> {
    @Volatile
    private var credences: Map<CellRef, Credence> = emptyMap()

    override fun apply(delta: Credence): Boolean {
        val changed = credences[delta.source].let { it?.values != delta.values || it.neutral != delta.neutral || it.argued != delta.argued }
        if (changed) {
            credences = credences + (delta.source to delta)
            onUpdate()
        }
        return changed
    }

    override fun current(): Map<CellRef, Credence> = credences

    override fun snapshot(): java.io.Serializable = HashMap(credences)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: java.io.Serializable) {
        credences = HashMap(state as Map<CellRef, Credence>)
    }
}
