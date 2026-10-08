package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.evolve.Evolve
import civictech.cell.evolve.Promotion
import civictech.cell.graph.Direction
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.PlannedAction
import civictech.cell.graph.PlannedStep
import civictech.cell.graph.RefusalCode
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.StepCheck
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.TrafficLightApi
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.inspect.InspectorServer

/**
 * Plans and performs the single-instance promotion arm of WKB2 F9. Planning
 * is cold; execution delegates the complete swap and rollback protocol to the
 * authority-gated kernel primitive.
 */
internal object PromotionCutOver {
    sealed interface Result {
        data object Committed : Result
        data class RefusedAtPrecheck(val reason: String) : Result
        data class RolledBackAtCommit(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun plan(
        host: ManagedHost,
        registry: LocationRegistry,
        draft: Draft,
        request: PromotionRequest,
    ): PlannedStep {
        val gate = requireNotNull(request.gate)
        val handle = requireNotNull(request.candidateHandle)
        val key = key(request.incumbent)
        val liveOutlet = host.portAt(request.incumbent, request.outletName) as? FanOutlet<*>
        val touches = buildSet {
            add(request.incumbent)
            add(gate)
            if (liveOutlet != null) {
                registry.swapSet(request.incumbent)
                    .filter { it.from == liveOutlet.ref }
                    .mapNotNullTo(this) { it.to.cell }
            }
        }

        val refusal = localRef(host, registry, request.incumbent, draft.host, "incumbent")
            ?: localRef(host, registry, gate, draft.host, "gate")
            ?: when {
                host.cellAt(request.incumbent) is CompositeCell -> refused(
                    RefusalCode.COUPLED_FLOW,
                    "promotion of CompositeCell ${request.incumbent} is refused by G-53 ([WKB2-27]): " +
                        "the fate of a coupled transaction in a swap window is undefined",
                )
                liveOutlet == null -> refused(
                    RefusalCode.UNRESOLVED_PORT,
                    "incumbent ${request.incumbent} has no FanOutlet named '${request.outletName}'",
                )
                registry.replicasOf(request.incumbent.id).size > 1 -> refused(
                    RefusalCode.ROLLING_ONLY,
                    "logical cell ${request.incumbent.id} has more than one live instance; " +
                        "a replicated incumbent is offered only the rolling form ([WKB2-54])",
                )
                host.cellAt(gate) !is TrafficLightApi<*> -> refused(
                    RefusalCode.NO_GATE,
                    "gate $gate is not a live TrafficLightApi",
                )
                registry.swapSet(request.incumbent).none { link ->
                    link.from == (host.cellAt(gate) as TrafficLightApi<*>).dataOutlet.ref &&
                        link.to.cell == request.incumbent
                } -> refused(
                    RefusalCode.NO_GATE,
                    "gate $gate does not serve incumbent ${request.incumbent}'s inbound",
                )
                draft.boundary.none { boundary ->
                    boundary.direction == Direction.INBOUND &&
                        boundary.liveRef == gate &&
                        boundary.livePort == "dataOutlet" &&
                        boundary.handle == handle
                } -> refused(
                    RefusalCode.NO_GATE,
                    "the candidate would be promoted unfed; the draft must draw the gate's dataOutlet into it",
                )
                else -> candidateRefusal(draft, request, handle, liveOutlet)
            }

        return PlannedStep(key, handle, PlannedAction.PROMOTE, touches, refusal ?: StepCheck.Ok)
    }

    fun perform(
        host: ManagedHost,
        registry: LocationRegistry,
        request: PromotionRequest,
        candidateRef: CellRef?,
    ): Result {
        val incumbent = host.cellAt(request.incumbent)
            ?: return Result.Failed("promotion incumbent ${request.incumbent} is no longer hosted")
        val gateRef = request.gate ?: return Result.Failed("single-instance promotion has no gate")
        val gate = host.cellAt(gateRef)
            ?: return Result.Failed("promotion gate $gateRef is no longer hosted")
        val candidate = candidateRef?.let(host::cellAt)
            ?: return Result.Failed("promotion candidate for handle '${request.candidateHandle}' is no longer hosted")
        val incumbentOutlet = host.portAt(request.incumbent, request.outletName) as? FanOutlet<*>
            ?: return Result.RefusedAtPrecheck(
                "incumbent ${request.incumbent} has no FanOutlet named '${request.outletName}'",
            )

        val downstream = mutableListOf<Use<*>>()
        for (link in registry.swapSet(request.incumbent).filter { host.outletAt(it.from) === incumbentOutlet }) {
            val targetRef = link.to.cell
                ?: return Result.RefusedAtPrecheck("downstream endpoint ${link.to} has no owning cell")
            val target = host.cellAt(targetRef)
                ?: return Result.RefusedAtPrecheck("downstream cell $targetRef is not hosted locally")
            val ports = PortRegistry.of(target)
            val port = ports.names().asSequence().mapNotNull(ports::get).firstOrNull { it.ref == link.to }
            val use = port as? Use<*>
                ?: return Result.RefusedAtPrecheck("downstream endpoint ${link.to} is not a Use port")
            downstream += use
        }

        return try {
            Evolve.promoteDirect(
                host = host,
                gate = gate,
                incumbent = incumbent,
                candidate = candidate,
                outletName = request.outletName,
                downstream = downstream,
                judge = null,
            )
            Result.Committed
        } catch (e: Evolve.Refused) {
            Result.RefusedAtPrecheck(e.message ?: e.toString())
        } catch (e: Promotion.PromotionAborted) {
            val reason = e.message ?: e.toString()
            when {
                reason.startsWith(COMMIT_ABORT) -> Result.RolledBackAtCommit(reason)
                reason.startsWith(PRECHECK_ABORT) -> Result.RefusedAtPrecheck(reason)
                else -> Result.Failed(reason)
            }
        } catch (e: Exception) {
            Result.Failed(e.toString())
        }
    }

    fun key(incumbent: CellRef): String = "promote ${InspectorServer.encodeRef(incumbent)}"

    private fun localRef(
        host: ManagedHost,
        registry: LocationRegistry,
        ref: CellRef,
        hostName: String,
        role: String,
    ): StepCheck.Refused? = when (val location = registry.location(ref)) {
        null -> refused(RefusalCode.UNKNOWN_REF, "promotion $role $ref is not located by the registry")
        is LocationRegistry.Remote -> refused(
            RefusalCode.MULTI_HOST,
            "promotion $role $ref is not local to target host '$hostName' — one host per draft",
        )
        is LocationRegistry.Local -> when {
            location.host !== host -> refused(
                RefusalCode.MULTI_HOST,
                "promotion $role $ref is not local to target host '$hostName' — one host per draft",
            )
            host.isDrained || host.isSuspended(ref) -> refused(
                RefusalCode.UNREACHABLE_REF,
                "promotion $role $ref is not reachable: its host is drained or the cell is suspended",
            )
            else -> null
        }
    }

    private fun candidateRefusal(
        draft: Draft,
        request: PromotionRequest,
        handle: String,
        incumbentOutlet: FanOutlet<*>,
    ): StepCheck.Refused? {
        val step = draft.spec.steps.filterIsInstance<SpawnStep>().firstOrNull { it.handle == handle }
            ?: return refused(RefusalCode.UNRESOLVED_HANDLE, "promotion candidate handle '$handle' does not resolve")
        if (step.identity != IdentityBinding.NewInstanceOf(request.incumbent.id)) {
            return refused(
                RefusalCode.CONTRACT_MISMATCH,
                "the candidate must be a new instance of the incumbent's logical cell, 53 §graphs-as-data",
            )
        }
        val cold = step.factory.create(step.identity.resolve())
        val candidateOutlet = PortRegistry.of(cold)[request.outletName] as? FanOutlet<*>
            ?: return refused(
                RefusalCode.UNRESOLVED_PORT,
                "candidate '$handle' has no FanOutlet named '${request.outletName}'",
            )
        return if (candidateOutlet.clazz != incumbentOutlet.clazz) {
            refused(
                RefusalCode.CONTRACT_MISMATCH,
                "candidate outlet '${request.outletName}' contract ${candidateOutlet.clazz.name} does not match " +
                    "incumbent's ${incumbentOutlet.clazz.name} (structural port sameness, 93 I-2)",
            )
        } else {
            null
        }
    }

    private fun refused(code: RefusalCode, reason: String) = StepCheck.Refused(code, reason)

    private const val PRECHECK_ABORT = "promotion aborted at PRECHECK:"
    private const val COMMIT_ABORT = "promotion aborted at COMMIT:"
}
