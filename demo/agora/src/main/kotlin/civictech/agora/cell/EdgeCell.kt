package civictech.agora.cell

import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.CellRef
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
 * Routed-write surface of an edge, consumed via `TypedRef<EdgeApi>` +
 * `host.lookup`: the claim surface plus the source-credence feed.
 */
interface EdgeApi : ClaimApi {
    val sourceInlet: Use<Propagate<CredenceUpdate>>
}

/**
 * An edge is a claim — "source supports/attacks target" — with its own
 * stances, its own incoming edges, and its own credence (that is the whole
 * point: relations are attackable). On top of the inherited claim state it
 * tracks its source's credence and pushes `influence = ownCredence ×
 * sourceCredence`, signed by [polarity], at its target.
 *
 * [quiescence] > 0 designates this edge a **cycle head** (the app-side form
 * of the decided I-5/I-6 model, spec 21 §Cycles): a returning source update
 * whose change is below the threshold is absorbed at the *inbound* feedback
 * edge — re-origination is gated, the credence outlet broadcast never is.
 * The service sets it on exactly the edges that close a cycle.
 */
class EdgeCell(
    val polarity: Polarity,
    ref: CellRef = CellRef(UUID.randomUUID()),
    semantics: GradualSemantics = DfQuad,
    val quiescence: Double = 0.0,
) : ClaimCell(ref, semantics), EdgeApi {

    override val sourceInlet = registerPort("sourceInlet", FanInlet.create<Propagate<CredenceUpdate>>())
    val influenceOutlet = registerPort("influenceOutlet", FanOutlet.create<Propagate<InfluenceDelta>>())

    private var sourceCredence: Double = credence // neutral until the source's catch-up arrives
    private var lastInfluence: Double = credence * sourceCredence

    init {
        sourceInlet.onEach { value ->
            // cycle-head absorb gate: drift accumulates against the stored
            // value, so total absorbed error stays below the threshold
            if (quiescence > 0 && abs(value.credence - sourceCredence) < quiescence) return@onEach
            sourceCredence = value.credence
            emitInfluence()
        }
        // a fresh target learns this edge's current influence at once
        influenceOutlet.catchUpOnLinked {
            if (catchUp) InfluenceDelta(ref, polarity, lastInfluence, size = 0.0) else null
        }
    }

    override fun onCredence(value: Double) = emitInfluence()

    private fun emitInfluence() {
        val v = credence * sourceCredence
        if (v != lastInfluence) {
            val size = abs(v - lastInfluence)
            lastInfluence = v
            influenceOutlet.call.propagate(InfluenceDelta(ref, polarity, v, size))
        }
    }

    override fun snapshot(): Serializable =
        arrayListOf(super.snapshot(), sourceCredence, lastInfluence)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (base, src, last) = state as ArrayList<Serializable>
        super.restore(base)
        sourceCredence = src as Double
        lastInfluence = last as Double
    }
}
