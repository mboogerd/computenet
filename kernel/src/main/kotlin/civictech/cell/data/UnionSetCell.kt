package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

interface UnionSetApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Merges tagged delta streams (G-23): tracks live add-tags per element and
 * forwards only new tag information, so duplicate deliveries across a diamond
 * fan-in dedup instead of double-counting. Membership (an element is in the
 * union iff it has a live tag) is derivable by any consumer from the forwarded
 * tag algebra.
 *
 * The convergent-consumer half of the RESTART re-baseline (spec 20/24 §Tag
 * continuity, 93 I-22 R5): an inbound delta riding a `ReBaselineNotice`
 * (§MessageContext) folds through [TagState.applyReBaseline] instead of the
 * ordinary [TagState.apply] — dropping un-reasserted tags from the
 * superseded sources and fencing them as dead lanes. [ReBaselineEmitting]
 * lets this cell itself act as the re-baselining producer when it sits
 * directly behind a RESTARTed host.
 */
class UnionSetCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    UnionSetApi<E>, Cell, Stateful, ReBaselineEmitting {
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val state = TagState<E>()

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                val notice = CurrentContext.get()?.reBaseline
                val effective = if (notice != null) state.applyReBaseline(value, notice) else state.apply(value)
                if (effective.adds.isNotEmpty() || effective.dels.isNotEmpty()) {
                    outlet.call.propagate(effective)
                }
            }
        })
        // late-join catch-up (G-22): live tags as a delta-from-empty
        outlet.catchUpOnLinked { if (state.size > 0) state.asDelta() else null }
    }

    override fun snapshot(): Serializable = state.snapshot()

    override fun restore(state: Serializable) = this.state.restore(state)

    /** RESTART re-baseline (93 I-22 R2): re-emit restored state, flagged over the ordinary catch-up path. */
    override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
        if (state.size > 0 || supersedes.isNotEmpty()) {
            outlet.reBaseline(supersedes, supersede) { propagate(state.asDelta()) }
        }
    }

    companion object {
        fun <E> create(): UnionSetApi<E> = UnionSetCell()
    }
}
