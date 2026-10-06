package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Replicable
import civictech.cell.data.op.FrontierGateable
import civictech.cell.host.HostManagementApi
import civictech.cell.host.UpstreamAncestry
import civictech.cell.link.Linked
import civictech.cell.link.LinkRole
import civictech.cell.port.CycleHead
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry

/** The structural admission result for one [observeAligned] construction. */
sealed interface AdmissionVerdict {
    /** The graph is admissible; [opaque] producers were intentionally not traversed. */
    class Admitted(val opaque: Set<PortRef>) : AdmissionVerdict

    /** A local structural condition makes wave alignment unsound. */
    sealed interface Rejected : AdmissionVerdict {
        /** One named view has an ungated non-monotone cell in its inclusive ancestry. */
        class UngatedAncestor(
            val view: String,
            val cell: CellRef,
            val cellClass: Class<out Cell>,
            val outlet: PortRef,
        ) : Rejected {
            internal var outletName: String = "<unknown>"
        }

        /** A branch re-mints origin identity while a sibling still observes its prior provenance. */
        class DivergentOrigination(
            val point: CellRef,
            val viewsUpstreamOfIt: Set<String>,
            val viewsNotUpstreamOfIt: Set<String>,
            val sharedAncestor: CellRef,
        ) : Rejected
    }
}

/** Raised before an aligned sink is constructed when [verdict] rejects its contributors. */
class AlignedAdmissionException(val verdict: AdmissionVerdict.Rejected) :
    IllegalStateException(describe(verdict))

private fun describe(verdict: AdmissionVerdict.Rejected): String = when (verdict) {
    is AdmissionVerdict.Rejected.UngatedAncestor ->
        "observeAligned view '${verdict.view}' has ungated ${verdict.cellClass.simpleName} " +
            "${verdict.cell} at outlet '${verdict.outletName}' (${verdict.outlet})"

    is AdmissionVerdict.Rejected.DivergentOrigination ->
        "observeAligned re-origination point ${verdict.point} reaches views " +
            "${verdict.viewsUpstreamOfIt} but not ${verdict.viewsNotUpstreamOfIt}; " +
            "shared ancestor ${verdict.sharedAncestor} would cross origin identities"
}

private class ViewAncestry(
    val cells: Map<CellRef, Cell>,
    val outlets: Map<CellRef, PortRef>,
    val outletNames: Map<CellRef, String>,
)

/**
 * Admit one aligned contributor set from the host's live Consume-link structure.
 *
 * Two rules apply, in order. First, every checked view's inclusive ancestry
 * must contain no ungated [FrontierGateable]. Second, a [CycleHead] or a
 * [Replicable] whose `deltaInlet` is linked as a Consume re-origination point
 * may not sit on only one branch of shared upstream provenance. [unchecked]
 * exempts its named view from the first rule only.
 *
 * The walk sees links present during these synchronous management reads only.
 * Links added after construction are not rechecked, and bypass wiring absent
 * from the host's live link set reads as a root. Callers must serialize graph
 * construction around this call: the underlying live link collections are not
 * an atomic topology snapshot and are not safe to mutate concurrently. A
 * producer outside this host is opaque and admitted rather than guessed at.
 */
internal fun admitAligned(
    api: HostManagementApi,
    specs: Map<String, AlignedObserveBuilder.Spec>,
    unchecked: Set<String>,
): AdmissionVerdict {
    val opaque = linkedSetOf<PortRef>()
    val ancestryByView = linkedMapOf<String, ViewAncestry>()

    specs.forEach { (view, spec) ->
        val ancestry = api.upstreamConsumeAncestors(spec.source)
        opaque += ancestry.opaque
        ancestryByView[view] = ancestry.includingSelf(spec)
    }

    specs.forEach { (view, _) ->
        if (view in unchecked) return@forEach
        val ancestry = ancestryByView.getValue(view)
        ancestry.cells.forEach { (ref, cell) ->
            if (cell is FrontierGateable && !cell.frontierGated) {
                val verdict = AdmissionVerdict.Rejected.UngatedAncestor(
                    view = view,
                    cell = ref,
                    cellClass = cell.javaClass,
                    outlet = ancestry.outlets.getValue(ref),
                )
                verdict.outletName = ancestry.outletNames.getValue(ref)
                return verdict
            }
        }
    }

    val allViews = ancestryByView.keys
    val candidates = linkedMapOf<CellRef, Cell>()
    ancestryByView.values.forEach { ancestry ->
        ancestry.cells.forEach { (ref, cell) -> candidates.putIfAbsent(ref, cell) }
    }
    candidates.forEach { (pointRef, point) ->
        if (!point.isReoriginationPoint()) return@forEach
        val through = allViews.filterTo(linkedSetOf()) { pointRef in ancestryByView.getValue(it).cells }
        val around = allViews.filterTo(linkedSetOf()) { it !in through }
        if (around.isEmpty()) return@forEach

        val strictAncestors = api.upstreamConsumeAncestors(pointRef).local.keys
        val aroundAncestors = linkedSetOf<CellRef>()
        around.forEach { aroundAncestors += ancestryByView.getValue(it).cells.keys }
        val shared = strictAncestors.firstOrNull { it in aroundAncestors } ?: return@forEach
        return AdmissionVerdict.Rejected.DivergentOrigination(
            point = pointRef,
            viewsUpstreamOfIt = through,
            viewsNotUpstreamOfIt = around,
            sharedAncestor = shared,
        )
    }

    return AdmissionVerdict.Admitted(opaque)
}

private fun UpstreamAncestry.includingSelf(spec: AlignedObserveBuilder.Spec): ViewAncestry {
    val cells = linkedMapOf<CellRef, Cell>()
    val outlets = linkedMapOf<CellRef, PortRef>()
    val outletNames = linkedMapOf<CellRef, String>()
    self?.let { cell ->
        val outlet = requireNotNull(PortRegistry.of(cell)[spec.outletName]) {
            "observeAligned: source ${spec.source} has no outlet '${spec.outletName}'"
        }
        cells[spec.source] = cell
        outlets[spec.source] = outlet.ref
        outletNames[spec.source] = spec.outletName
    }
    local.forEach { (ref, ancestor) ->
        cells[ref] = ancestor.cell
        outlets[ref] = ancestor.viaOutlet
        outletNames[ref] = ancestor.cell.portName(ancestor.viaOutlet)
    }
    return ViewAncestry(cells, outlets, outletNames)
}

private fun Cell.portName(ref: PortRef): String {
    val ports = PortRegistry.of(this)
    return ports.names().firstOrNull { ports[it]?.ref == ref } ?: "<unknown>"
}

private fun Cell.isReoriginationPoint(): Boolean = when (this) {
    is CycleHead<*> -> true
    is Replicable<*> -> {
        val deltaInlet = PortRegistry.of(this)["deltaInlet"] as? Linked
        deltaInlet?.linking?.links?.any { it.role == LinkRole.Consume } == true
    }

    else -> false
}
