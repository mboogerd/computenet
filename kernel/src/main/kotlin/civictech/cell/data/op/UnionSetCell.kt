package civictech.cell.data.op

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.SetDelta

/**
 * Remove *the element*, not *my contribution* (D-UNION) — the union-scoped
 * half of the two intents a multi-writer set conflates.
 * [civictech.cell.data.SetOps.remove] is the writer-local one: it can only
 * tombstone tags its own cell minted, so an element another writer also added
 * survives until that writer removes it too (correct per-replica OR-set
 * semantics, and a silent no-op for anyone else). This one tombstones every
 * tag the *merged* view currently holds for the element, so a single remove
 * retracts every causally-preceding add the remover has seen.
 *
 * **The distributed boundary is intended, per spec 24 `[24-SET-03]`**: only
 * tags this node has already observed are covered. A concurrent add elsewhere
 * — unobserved at remove time — is not retracted, and the element re-enters
 * when that add arrives. That is add-wins falling out of tag-set union, not a
 * lost remove; closing it would need coordination this model deliberately does
 * not have. Removing an unobserved element is a no-op (`[24-SET-01]`).
 */
@Contract
interface ObservedRemoveOps<E> {
    fun removeObserved(element: E)
}

@CellBase
interface UnionSetApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>

    /** Union-scoped observed remove (D-UNION) — see [ObservedRemoveOps]. */
    val removeInlet: Use<ObservedRemoveOps<E>>
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
 * (§MessageContext) folds through [civictech.cell.data.delta.TagState.applyReBaseline]
 * instead of the ordinary [civictech.cell.data.delta.TagState.apply] —
 * dropping un-reasserted tags from the superseded sources and fencing them as
 * dead lanes. [ReBaselineEmitting] lets this cell itself act as the
 * re-baselining producer when it sits
 * directly behind a RESTARTed host.
 *
 * D-UNION: this is the cell that *holds* the merged view, so it is where
 * "remove the element as it currently exists in the merged view" is
 * expressible — [ObservedRemoveOps.removeObserved] on [removeInlet]. That
 * makes this ledger a retaining one
 * ([civictech.cell.data.delta.TagState]'s `retainTombstones`): a del minted
 * here covers add-tags that live on in the originating writers' own state,
 * and those writers re-assert them on every catch-up, so the tombstones must
 * outlive the fold that applied them.
 */
class UnionSetCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    UnionSetCellBase<E>(ref), Stateful, ReBaselineEmitting {
    private val op = TaggedSetOperator<E>(retainTombstones = true)

    init {
        // late-join catch-up (G-22): live tags as a delta-from-empty, plus the
        // retained tombstones (D-UNION) — a re-linking peer that kept its own
        // copy of a removed element's add-tag learns the del from this stream
        outlet.catchUpOnLinked { if (op.state.isEmpty) null else op.state.asDelta() }
    }

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`.
    override fun removeInletHandler(): ObservedRemoveOps<E> = object : ObservedRemoveOps<E> {
        override fun removeObserved(element: E) {
            // effective-only (21, [24-SET-01]): an unobserved element is a
            // no-op. A command-driven emission, like SetCell's own ops — there
            // is no inbound wave here to absorb-ack.
            val effective = op.state.removeObserved(element)
            if (effective.dels.isNotEmpty()) outlet.call.propagate(effective)
        }
    }

    override fun onInlet(value: SetDelta<E>) {
        val notice = CurrentContext.get()?.reBaseline
        val effective = if (notice != null) op.state.applyReBaseline(value, notice) else op.state.apply(value)
        op.emitOrAbsorb(
            effective,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() }, // diamond-fan-in duplicate deduped — ack the swallowed wave (CP-A3)
        )
    }

    override fun snapshot(): Serializable = op.snapshot()

    override fun restore(state: Serializable) = op.restore(state)

    /** RESTART re-baseline (93 I-22 R2): re-emit restored state, flagged over the ordinary catch-up path. */
    override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
        if (!op.state.isEmpty || supersedes.isNotEmpty()) {
            outlet.reBaseline(supersedes, supersede) { propagate(op.state.asDelta()) }
        }
    }

    companion object {
        fun <E> create(): UnionSetApi<E> = UnionSetCell()
    }
}
