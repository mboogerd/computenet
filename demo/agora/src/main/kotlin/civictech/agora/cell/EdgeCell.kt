package civictech.agora.cell

import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PropagateFeedbackInlet
import civictech.cell.port.Use
import civictech.cell.link.catchUpOnLinked
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
 * [quiescence] > 0 designates this edge a **cycle head**. The source feed has
 * both an ordinary inlet and a kernel [PropagateFeedbackInlet]; the service
 * lands the admitted cycle-closing link on the latter. The kernel inlet keeps
 * the cycle's admission and fresh-wave boundary with quiescence `0.0`, while
 * this cell applies [quiescence] to drift from its last accepted source value.
 * That split is deliberate: [CredenceUpdate.size] measures only the source's
 * latest emission, so using it as the app threshold would discard consecutive
 * small updates whose accumulated drift is significant. The service sets the
 * app threshold only on edges that close a cycle.
 */
class EdgeCell(
    val polarity: Polarity,
    ref: CellRef = CellRef(UUID.randomUUID()),
    semantics: GradualSemantics = DfQuad,
    val quiescence: Double = 0.0,
    initialSourceCredence: Double? = null,
) : ClaimCell(ref, semantics), EdgeApi {

    override val sourceInlet = registerPort("sourceInlet", FanInlet.create<Propagate<CredenceUpdate>>())
    val feedbackInlet = registerPort(
        "feedbackInlet",
        PropagateFeedbackInlet<CredenceUpdate>(
            quiescence = 0.0,
            payloadType = CredenceUpdate::class.java,
        ) { value -> onSource(value) },
    )
    val influenceOutlet = registerPort("influenceOutlet", FanOutlet.create<Propagate<InfluenceDelta>>())

    // A feedback inlet absorbs size-zero updates, including FanOutlet's
    // state-as-delta catch-up. Agora therefore primes a new head from its
    // source's current state; ordinary edges still learn it through catch-up.
    private var sourceCredence: Double = initialSourceCredence ?: credence
    private var lastInfluence: Double = credence * sourceCredence

    init {
        sourceInlet.onEach { value -> onSource(value) }
        // a fresh target learns this edge's current influence at once
        influenceOutlet.catchUpOnLinked {
            if (catchUp) InfluenceDelta(ref, polarity, lastInfluence, size = 0.0) else null
        }
    }

    override fun onCredence(value: Double) = emitInfluence()

    private fun onSource(value: CredenceUpdate) {
        val drift = abs(value.credence - sourceCredence)
        // CredenceUpdate.size is relative to the source's last emission, not
        // this edge's last accepted source value. Accumulate sub-threshold
        // laps against the stored value so their total drift stays bounded.
        if (quiescence > 0 && drift < quiescence) return
        sourceCredence = value.credence
        emitInfluence()
    }

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
