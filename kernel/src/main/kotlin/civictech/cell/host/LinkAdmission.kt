package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.link.LinkResult
import civictech.cell.link.hasDampingWitness
import civictech.cell.port.FeedbackInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkTo
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry

/**
 * Link-admission logic behind [ManagedHost.connect] (T11-B extraction):
 * cycle detection, headedness, the FU-8 damping-witness check, and the
 * topology-recording that follows a successful link. Each function takes
 * exactly what it reads — the live `cells` view, the read-only `topology`
 * projection, and the already-resolved outlet/inlet — rather than reaching
 * into `ManagedHost`'s private state, so this stays (near-)pure and testable
 * on its own.
 *
 * Deliberately NOT extracted: `ManagedHost.connect(from, outletName, to:
 * Use<*>)`, the other overload sharing the file's `connect` name. It has no
 * cycle-admission or topology-recording concern — a `Use<*>` target is not a
 * locally-hosted [civictech.cell.CellRef], so there is nothing here for it
 * to share — and stays a two-line body on `ManagedHost` itself.
 *
 * No `dataLock` interaction anywhere in this file (RS-8 discipline note):
 * link admission runs entirely off the management band, never touching the
 * data-plane monitor `ManagedHost.dataLock` guards.
 */
internal object LinkAdmission {

    /**
     * Resolves `from.outletName` / `to.inletName` on [cells], admits the
     * link (cycle/headedness/damping), performs it, and — on
     * [LinkResult.Connected] — records the edge on [topology] and wires its
     * unlink to remove that record again. Moved verbatim from
     * `ManagedHost.connect(from, outletName, to, inletName)`.
     */
    fun connect(
        cells: Map<CellRef, Cell>,
        topology: LocationRegistry?,
        from: CellRef,
        outletName: String,
        to: CellRef,
        inletName: String,
    ): LinkResult {
        val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
        val toCell = cells[to] ?: throw IllegalArgumentException("Target cell not found: $to")

        val outlet = findPort(fromCell, outletName) as? LinkTo<*>
            ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")
        val inlet = findPort(toCell, inletName) as? LinkFrom<*>
            ?: throw IllegalArgumentException("Inlet not found or not linkable: $inletName on $to")

        admitCycle(topology, from, outletName, outlet, to, inletName, inlet)?.let { return it }

        @Suppress("UNCHECKED_CAST")
        val result = (outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)
        if (result is LinkResult.Connected) {
            val edge = TopologyLink(
                result.link.id,
                result.link.from.copy(cell = from),
                result.link.to.copy(cell = to),
            )
            topology?.link(edge)
            result.link.onUnlink { topology?.unlink(it.id) }
        }
        return result
    }

    /**
     * Cycle admission (spec 10/13 `CycleWithoutHead`, 20/21 §Cycles, 93 I-5):
     * a connect that would close a cycle wholly visible to [topology] is
     * rejected unless [inlet] is a declared [FeedbackInlet] (headedness)
     * carrying a damping witness (FU-8, ADR 1 feature 8). Cross-host cycles
     * are not locally visible here; they fall to the runtime hop guard
     * (20/22) instead. `null` = admitted.
     */
    private fun admitCycle(
        topology: LocationRegistry?,
        from: CellRef,
        outletName: String,
        outlet: Port,
        to: CellRef,
        inletName: String,
        inlet: LinkFrom<*>,
    ): LinkResult.Rejected? {
        val reg = topology ?: return null
        if (!reg.wouldCloseCycle(from, to)) return null

        // Headedness (spec 10/13): the closing edge MUST land on a declared
        // CycleHead.
        if (inlet !is FeedbackInlet<*>) {
            return LinkResult.Rejected(
                "CycleWithoutHead: connecting $from.$outletName -> $to.$inletName would close a " +
                    "locally-visible cycle with no declared CycleHead (spec 10/13, 20/21 §Cycles)"
            )
        }
        // Damping (FU-8, ADR 1 feature 8): a head only *dampens* a lap when
        // the loop carries a damping witness. Without one a properly-headed
        // loop (non-Magnitude payload, non-idempotent merge, no quiescence
        // override) laps forever — the runaway "magnitude-based throttling"
        // was meant to exclude.
        if (!hasDampingWitness(outlet, inlet)) {
            return LinkResult.Rejected(
                "CycleWithoutDamping: connecting $from.$outletName -> $to.$inletName would close a " +
                    "locally-visible cycle whose head has no damping witness — the feedback payload " +
                    "is not Magnitude-typed, the producer declares neither MONOTONE nor IDEMPOTENT, " +
                    "and the head has no quiescence override (spec 21 §Cycles, ADR 1 feature 8)"
            )
        }
        return null
    }

    private fun findPort(cell: Cell, name: String): Port? = PortRegistry.of(cell)[name]
}
