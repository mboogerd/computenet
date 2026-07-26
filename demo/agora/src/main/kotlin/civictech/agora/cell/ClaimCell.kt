package civictech.agora.cell

import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Propagate
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*
import kotlin.math.abs

/**
 * Routed-write surface of a claim, consumed via `TypedRef<ClaimApi>` +
 * `host.lookup`: property names MUST match the registered port names.
 */
interface ClaimApi {
    val stanceInlet: Use<Propagate<StanceDelta>>
    val influenceInlet: Use<Propagate<InfluenceDelta>>
}

/**
 * A claim (argument) with a credence in [0,1]: a deterministic function of
 * the current per-user stances and the current per-edge influences — never
 * of arrival order — recomputed on every input delta. Emission is exact and
 * effective-only (spec 21 rule 2): every real change propagates, stamped
 * with its size; there is no outlet threshold (93 I-6 rejects per-cell
 * outlet gates — cycle damping lives on the feedback edge, see [EdgeCell]).
 */
open class ClaimCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    protected val semantics: GradualSemantics = DfQuad,
) : Cell, Stateful, ClaimApi {

    override val stanceInlet = registerPort("stanceInlet", FanInlet.create<Propagate<StanceDelta>>())
    override val influenceInlet = registerPort("influenceInlet", FanInlet.create<Propagate<InfluenceDelta>>())
    val credenceOutlet = registerPort("credenceOutlet", FanOutlet.create<Propagate<CredenceUpdate>>())

    private val stances = HashMap<String, Double>()

    /** edge → signed energy (support positive, attack negative), ref-sorted so
     * the fold order matches the batch reference solver exactly (FP determinism). */
    private val influences = TreeMap<CellRef, Double>(REF_ORDER)

    var credence: Double = semantics.combine(semantics.base(emptyList()), emptyList(), emptyList())
        private set

    private var lastEmitted: Double = credence

    /**
     * Gate for the onLinked baseline emissions. The service turns it off
     * while replaying its structure log: rebuild-time links must NOT emit
     * (and thereby journal) fresh neutral baselines — the journal already
     * holds the originals, and a replayed neutral baseline staged after the
     * recovered frames would clobber them (observed live: the LWW hub fold
     * rewound to 0.5 under the virtual-thread scheduler's staging race).
     */
    var catchUp: Boolean = true

    init {
        stanceInlet.onEach { value ->
            if (value.value == null) stances.remove(value.user) else stances[value.user] = value.value
            recompute()
        }
        influenceInlet.onEach { value ->
            if (value.value == null) influences.remove(value.edge)
            else influences[value.edge] = if (value.polarity == Polarity.SUPPORT) value.value else -value.value
            recompute()
        }
        // late-join catch-up (G-22): a fresh subscriber learns the current
        // credence at once — a baseline, so size = 0 (no urgency).
        credenceOutlet.catchUpOnLinked {
            if (catchUp) CredenceUpdate(ref, credence, size = 0.0) else null
        }
    }

    private fun recompute() {
        val attacks = influences.values.filter { it < 0 }.map { -it }
        val supports = influences.values.filter { it > 0 }
        credence = semantics.combine(semantics.base(stances.values), attacks, supports)
        if (credence != lastEmitted) {
            val size = abs(credence - lastEmitted)
            lastEmitted = credence
            credenceOutlet.call.propagate(CredenceUpdate(ref, credence, size))
            onCredence(credence)
        }
    }

    /** Hook for [EdgeCell]: own credence changed (already emitted). */
    protected open fun onCredence(value: Double) {}

    override fun snapshot(): Serializable =
        arrayListOf(HashMap(stances), HashMap(influences), credence, lastEmitted)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (st, inf, cred, last) = state as ArrayList<Serializable>
        stances.clear(); stances.putAll(st as Map<String, Double>)
        influences.clear(); influences.putAll(inf as Map<CellRef, Double>)
        credence = cred as Double
        lastEmitted = last as Double
    }

    companion object {
        val REF_ORDER: Comparator<CellRef> = compareBy({ it.id }, { it.instanceId })
    }
}
