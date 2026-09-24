package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LinkAdmission
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.RoutedInletResolution
import civictech.cell.host.TopologyIndex
import civictech.cell.host.TopologyLink
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkRequest
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.Linked
import civictech.cell.link.checkPayload
import civictech.cell.link.reconcileNatures
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.FeedbackInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkTo
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.natures
import civictech.nature.ContractRegistry
import civictech.nature.NatureAxis
import civictech.nature.NatureMismatch
import civictech.nature.Ownership
import java.util.UUID

/*
 * WKB2 F2 (computenet-91xzn) — cold structural precheck of a GraphSpec against
 * a live host ([WKB2-10]..[WKB2-14], [WKB2-28]). Staged cells are constructed
 * cold (`factory.create(ref)` registers their ports, [15-RULE-01]/[15-RULE-02])
 * and live only in a scratch map that is dropped when `precheck` returns; the
 * live side is read through a [LiveView]. Nothing here spawns, links,
 * despawns, subscribes or taps anything.
 */

/** Which way a [BoundaryLink] crosses the staged/live boundary. */
enum class Direction {
    /** Live outlet → staged inlet. */
    INBOUND,

    /** Staged outlet → live inlet. */
    OUTBOUND,
}

/**
 * One planned edge between a live cell ([liveRef]'s port [livePort]) and a
 * staged spawn ([handle]'s port [handlePort]). [direction] decides which side
 * is the outlet: [Direction.INBOUND] = live outlet → staged inlet,
 * [Direction.OUTBOUND] = staged outlet → live inlet.
 */
data class BoundaryLink(
    val liveRef: CellRef,
    val livePort: String,
    val handle: String,
    val handlePort: String,
    val direction: Direction,
)

/**
 * The read-only live side a precheck consults (91xzn-D3). Hands out live
 * [Port] objects rather than descriptors, because policies, cardinality,
 * exclusivity and the payload class are on the port, not on a
 * `PortDescriptor`.
 */
interface LiveView {
    /** The apply host: every staged spawn lands here (91xzn-D4). */
    val host: ManagedHost

    /** The live port [name] on [ref]; null when [ref] is not hosted here or registers no such port. */
    fun portOf(ref: CellRef, name: String): Port?

    fun locationOf(ref: CellRef): LocationRegistry.Location?

    fun linksOf(ref: CellRef): Set<TopologyLink>

    fun allLinks(): Set<TopologyLink>

    /** False when the host is drained or [ref] is suspended. */
    fun isReachable(ref: CellRef): Boolean
}

/** Kernel [LiveView] over one [ManagedHost] and the [LocationRegistry] it publishes to. */
class HostLiveView(override val host: ManagedHost, private val registry: LocationRegistry) : LiveView {
    override fun portOf(ref: CellRef, name: String): Port? = host.portAt(ref, name)
    override fun locationOf(ref: CellRef): LocationRegistry.Location? = registry.location(ref)
    override fun linksOf(ref: CellRef): Set<TopologyLink> = registry.swapSet(ref)
    override fun allLinks(): Set<TopologyLink> = registry.all()
    override fun isReachable(ref: CellRef): Boolean = !host.isDrained && !host.isSuspended(ref)
}

/**
 * Why a planned step would not apply. `UNRESOLVED_PORT` (91xzn-D2) and
 * `LIVE_REF` extend the feature's list. `AT_CAPACITY`, `OWNERSHIP_VIOLATION`,
 * `POLICY_DENIAL` and `CYCLE_WITHOUT_HEAD` are the admission dry-run's codes
 * ([WKB2-12]) and carry the live admission path's own reason strings;
 * `OWNED_INTAKE` is [WKB2-26]'s refusal of an INBOUND boundary link into an
 * exclusive staged inlet. `CYCLE_WITHOUT_HEAD` covers both of
 * `LinkAdmission`'s cycle strings (`CycleWithoutHead:` and
 * `CycleWithoutDamping:`): [13-LINK-06] names one reason for both.
 *
 * `UNKNOWN_CATALOGUE_ID` and `INVALID_PARAMS` (WKB2 F12, va0c4-D5) are minted
 * only by `civictech.inspect.edit.DraftCompiler`, when a browser draft names a
 * catalogue entry it cannot resolve (an unregistered id) or a parameter map
 * that does not fit the entry's schema; [precheck] itself never produces
 * them. They live here because the compiler reports them in this [Plan]
 * shape, keyed by the draft node's handle, and an enum is not extensible from
 * another module.
 */
enum class RefusalCode {
    UNRESOLVED_HANDLE,
    UNRESOLVED_PORT,
    LIVE_REF,
    CONTRACT_MISMATCH,
    AT_CAPACITY,
    OWNERSHIP_VIOLATION,
    POLICY_DENIAL,
    CYCLE_WITHOUT_HEAD,
    INSTANCE_SCOPING,
    DURABLE,
    UNKNOWN_REF,
    UNREACHABLE_REF,
    OWNED_INTAKE,
    MULTI_HOST,
    UNKNOWN_CATALOGUE_ID,
    INVALID_PARAMS,
}

/** The cold verdict on one planned step. */
sealed interface StepCheck {
    data object Ok : StepCheck

    /** [reason] is the string the live path would produce where one exists; [mismatch] is set for a nature refusal. */
    data class Refused(
        val code: RefusalCode,
        val reason: String,
        val mismatch: NatureMismatch? = null,
    ) : StepCheck
}

/**
 * What a planned step would do ([WKB2-14]). Precheck produces only [SPAWN] and
 * [LINK]; [UNLINK], [DESPAWN] and [PROMOTE] are arms for the write plane's
 * draft (WKB2 F3/F9).
 */
enum class PlannedAction { SPAWN, LINK, UNLINK, DESPAWN, PROMOTE }

/**
 * One step of a [Plan]. [key] mirrors [ApplyReport] (91xzn-D8): the spawn
 * handle, `"from.outlet->to.inlet"` for a [ConnectStep], and
 * `"<liveRef>.<port>->handle.<port>"` / `"handle.<port>-><liveRef>.<port>"`
 * for a [BoundaryLink] by direction. [touches] is the live refs the step
 * would change: empty for spec-internal steps, `{liveRef}` for a boundary link.
 */
data class PlannedStep(
    val key: String,
    val handle: String?,
    val action: PlannedAction,
    val touches: Set<CellRef>,
    val result: StepCheck,
)

sealed interface Verdict {
    data object Appliable : Verdict

    /** Every refused step — the whole spec is checked, not first-failure. */
    data class NotAppliable(val refusals: List<PlannedStep>) : Verdict
}

data class Plan(val steps: List<PlannedStep>, val verdict: Verdict)

/**
 * Plans this spec plus its [boundary] links against [live] without touching
 * the host ([WKB2-10]): it never calls `spawn`, `connect`, `despawn`,
 * `linkTo`, `linkFrom`, `subscribe` or `tap`.
 *
 * Steps are walked in declaration order, never through [GraphSpec.lowered]
 * (91xzn-D6): an [InstanceSetStep] is lowered inside a `try` so that
 * [InstanceSetStep.validate]'s refusal surfaces verbatim as one
 * `INSTANCE_SCOPING`/`DURABLE` step keyed by the set's handle, and its spawns
 * are skipped. Boundary links are checked after every step.
 *
 * Mapping decided here: a factory that builds a cell whose ref differs from
 * the one an explicit [IdentityBinding] chose ([requireBoundRef]) is
 * `CONTRACT_MISMATCH` — the factory did not construct what the binding
 * contracted for. An exception thrown by a factory itself is not a
 * structural verdict and propagates.
 */
fun GraphSpec.precheck(boundary: List<BoundaryLink> = emptyList(), live: LiveView): Plan {
    val scratch = Scratch(live)
    val planned = mutableListOf<PlannedStep>()
    steps.forEach { step ->
        when (step) {
            is SpawnStep -> planned += scratch.spawn(step, live)
            is ConnectStep -> planned += scratch.connect(step)
            is InstanceSetStep -> {
                val lowered = try {
                    step.lower()
                } catch (e: IllegalArgumentException) {
                    planned += refusedInstanceSet(step, e)
                    null
                }
                lowered?.forEach { planned += scratch.spawn(it as SpawnStep, live) }
            }
        }
    }
    boundary.forEach { planned += scratch.boundary(it, live) }
    val refusals = planned.filter { it.result is StepCheck.Refused }
    return Plan(planned, if (refusals.isEmpty()) Verdict.Appliable else Verdict.NotAppliable(refusals))
}

private fun refusedInstanceSet(step: InstanceSetStep, e: IllegalArgumentException): PlannedStep {
    val message = e.message ?: e.toString()
    // 91xzn-D6: the axis is named in validate()'s message; the "no instances
    // declared" require names none and is a scoping fault.
    val code = if ("refused on the DURABLE nature" in message) RefusalCode.DURABLE else RefusalCode.INSTANCE_SCOPING
    return PlannedStep(step.handle, step.handle, PlannedAction.SPAWN, emptySet(), StepCheck.Refused(code, message))
}

/** A staged spawn: the ref its binding resolved to and the cold cell. */
private class Staged(val ref: CellRef, val cell: Cell)

/**
 * A link accepted earlier in this plan (every one is a Consume link). The
 * cardinality and SPSC checks count these beside the live `linking.links`; a
 * refused link is never recorded, so it never counts.
 */
private class PlannedLink(val outlet: LinkTo<*>, val inlet: LinkFrom<*>, val from: CellRef, val to: CellRef)

/**
 * A link about to be checked, both endpoints resolved. [outletName] and
 * [inletName] are the port names as the step spelled them, which is what the
 * live `LinkAdmission` cycle strings print.
 */
private class LinkCandidate(
    val outlet: LinkTo<*>,
    val inlet: LinkFrom<*>,
    val from: CellRef,
    val to: CellRef,
    val outletName: String,
    val inletName: String,
)

private class Scratch(live: LiveView) {
    val staged = mutableMapOf<String, Staged>()
    val refusedHandles = mutableSetOf<String>()
    val links = mutableListOf<PlannedLink>()

    /**
     * 91xzn-D5: a private [TopologyIndex] seeded once with the live links and
     * fed every link this plan accepts, so [cycleCheck] asks
     * [TopologyIndex.wouldCloseCycle] — the live walk, verbatim — over
     * live ∪ planned. Planned edges are keyed by the endpoints' cell refs (a
     * staged endpoint's is the ref its binding resolved to); the port ids are
     * fresh because the walk reads only `link.to.cell`.
     */
    val topology = TopologyIndex().apply { live.allLinks().forEach(::linked) }

    fun unresolvedHandle(handle: String, role: String) =
        if (handle in refusedHandles) "unresolved handle '$handle' ($role): its spawn step was refused"
        else "unresolved handle '$handle' ($role): no spawn step in this spec produced it"

    fun spawn(step: SpawnStep, live: LiveView): PlannedStep {
        fun planned(result: StepCheck) = PlannedStep(step.handle, step.handle, PlannedAction.SPAWN, emptySet(), result)
        fun refuse(code: RefusalCode, reason: String): PlannedStep {
            refusedHandles += step.handle
            return planned(StepCheck.Refused(code, reason))
        }

        step.parent?.let { parent ->
            if (parent !in staged) return refuse(RefusalCode.UNRESOLVED_HANDLE, unresolvedHandle(parent, "parent of '${step.handle}'"))
        }
        val ref = step.identity.resolve()
        if (step.identity is IdentityBinding.Exact && isLive(ref, live)) {
            return refuse(
                RefusalCode.LIVE_REF,
                "spawn step '${step.handle}': ref $ref is already live — re-applying an Exact spawn of a " +
                    "live ref is refused by the live-ref spawn guard ([15-APPLY-01] idempotent re-apply)",
            )
        }
        val cell = step.factory.create(ref)
        try {
            requireBoundRef(step.handle, step.identity, ref, cell.ref)
        } catch (e: IllegalArgumentException) {
            return refuse(RefusalCode.CONTRACT_MISMATCH, e.message ?: e.toString())
        }
        staged[step.handle] = Staged(ref, cell)
        return planned(StepCheck.Ok)
    }

    fun connect(step: ConnectStep): PlannedStep {
        fun planned(result: StepCheck) =
            PlannedStep("${step.from}.${step.outlet}->${step.to}.${step.inlet}", null, PlannedAction.LINK, emptySet(), result)

        val from = staged[step.from]
            ?: return planned(StepCheck.Refused(RefusalCode.UNRESOLVED_HANDLE, unresolvedHandle(step.from, "source")))
        val to = staged[step.to]
            ?: return planned(StepCheck.Refused(RefusalCode.UNRESOLVED_HANDLE, unresolvedHandle(step.to, "target")))
        val outlet = PortRegistry.of(from.cell)[step.outlet] as? LinkTo<*>
            ?: return planned(StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, outletUnresolved(step.outlet, step.from)))
        val inlet = PortRegistry.of(to.cell)[step.inlet] as? LinkFrom<*>
            ?: return planned(StepCheck.Refused(RefusalCode.UNRESOLVED_PORT, inletUnresolved(step.inlet, step.to)))
        return planned(checkLink(LinkCandidate(outlet, inlet, from.ref, to.ref, step.outlet, step.inlet)))
    }

    fun boundary(link: BoundaryLink, live: LiveView): PlannedStep {
        val liveSide = "${link.liveRef}.${link.livePort}"
        val stagedSide = "${link.handle}.${link.handlePort}"
        val key = when (link.direction) {
            Direction.INBOUND -> "$liveSide->$stagedSide"
            Direction.OUTBOUND -> "$stagedSide->$liveSide"
        }
        fun planned(result: StepCheck) = PlannedStep(key, link.handle, PlannedAction.LINK, setOf(link.liveRef), result)
        fun refuse(code: RefusalCode, reason: String) = planned(StepCheck.Refused(code, reason))

        when (val location = live.locationOf(link.liveRef)) {
            null -> return refuse(
                RefusalCode.UNKNOWN_REF,
                "boundary ref ${link.liveRef} is not located by the live view",
            )
            is LocationRegistry.Local -> if (location.host !== live.host) return refuse(RefusalCode.MULTI_HOST, multiHost(link))
            is LocationRegistry.Remote -> return refuse(RefusalCode.MULTI_HOST, multiHost(link))
        }
        if (!live.isReachable(link.liveRef)) {
            return refuse(
                RefusalCode.UNREACHABLE_REF,
                "boundary ref ${link.liveRef} is not reachable: its host is drained or the cell is suspended",
            )
        }
        val stagedCell = staged[link.handle]
            ?: return refuse(RefusalCode.UNRESOLVED_HANDLE, unresolvedHandle(link.handle, "boundary"))
        val livePort = live.portOf(link.liveRef, link.livePort)
        val stagedPort = PortRegistry.of(stagedCell.cell)[link.handlePort]
        val candidate = when (link.direction) {
            Direction.INBOUND -> {
                val outlet = livePort as? LinkTo<*>
                    ?: return refuse(RefusalCode.UNRESOLVED_PORT, outletUnresolved(link.livePort, link.liveRef))
                val inlet = stagedPort as? LinkFrom<*>
                    ?: return refuse(RefusalCode.UNRESOLVED_PORT, inletUnresolved(link.handlePort, link.handle))
                // [WKB2-26]: refused on its shape, before any admission check.
                if (carriesExclusive(inlet)) {
                    return refuse(
                        RefusalCode.OWNED_INTAKE,
                        "boundary link into staged '${link.handle}'.${link.handlePort}: the inlet carries " +
                            "Owned/Leased payloads, and STAGE would consume an Owned/Leased payload that " +
                            "cannot be un-consumed ([WKB2-26])",
                    )
                }
                LinkCandidate(outlet, inlet, link.liveRef, stagedCell.ref, link.livePort, link.handlePort)
            }
            Direction.OUTBOUND -> {
                val outlet = stagedPort as? LinkTo<*>
                    ?: return refuse(RefusalCode.UNRESOLVED_PORT, outletUnresolved(link.handlePort, link.handle))
                val inlet = livePort as? LinkFrom<*>
                    ?: return refuse(RefusalCode.UNRESOLVED_PORT, inletUnresolved(link.livePort, link.liveRef))
                LinkCandidate(outlet, inlet, stagedCell.ref, link.liveRef, link.handlePort, link.livePort)
            }
        }
        return planned(checkLink(candidate))
    }

    /**
     * The per-link dry-run: [linkChecks] in order, first refusal wins. An
     * accepted link is recorded in [links] so later checks can see links
     * planned earlier in the same plan.
     */
    fun checkLink(candidate: LinkCandidate): StepCheck {
        linkChecks.forEach { check -> check(candidate)?.let { return it } }
        links += PlannedLink(candidate.outlet, candidate.inlet, candidate.from, candidate.to)
        topology.linked(TopologyLink(UUID.randomUUID(), PortRef.generate(candidate.from), PortRef.generate(candidate.to)))
        return StepCheck.Ok
    }

    /**
     * The live admission order, as `LinkAdmission.connect` actually runs it:
     * `admitCycle` before `outlet.linkTo`; `FanOutlet.linkTo`'s SPSC check
     * before it delegates to `inlet.linkFrom`; `FanInlet`/`FeedbackInlet.linkFrom`'s
     * cardinality before `handshake`; then `handshake`'s target policies,
     * source policies, payload class, natures. First refusal wins, so a link
     * with several faults reports the one a real connect would.
     *
     * This deviates from the order 91xzn-D7 lists (cardinality, policies,
     * payload, natures, SPSC, cycle): D7's stated intent is "the live order",
     * and the list it gives is not the order the code runs (computenet-91xzn.2
     * bead comment). OWNED_INTAKE is not here: it is a refusal of an INBOUND
     * boundary link's shape and runs in [boundary] before any of these.
     */
    val linkChecks: List<(LinkCandidate) -> StepCheck.Refused?> = listOf(
        ::cycleCheck,
        ::ownershipCheck,
        ::capacityCheck,
        ::policyCheck,
        ::payloadCheck,
        ::natureCheck,
    )

    private fun plannedInto(inlet: LinkFrom<*>) = links.any { it.inlet.ref == inlet.ref }

    private fun plannedFrom(outlet: LinkTo<*>) = links.any { it.outlet.ref == outlet.ref }

    /**
     * CYCLE_WITHOUT_HEAD ([13-LINK-06]): [topology] (live ∪ planned) decides
     * whether the edge closes a cycle; `LinkAdmission.cycleRefusal` supplies
     * the headedness and damping verdicts and their strings, so a headed,
     * damped closing edge is admitted exactly as it would be live.
     */
    private fun cycleCheck(c: LinkCandidate): StepCheck.Refused? {
        if (!topology.wouldCloseCycle(c.from, c.to)) return null
        return LinkAdmission.cycleRefusal(c.from, c.outletName, c.outlet, c.to, c.inletName, c.inlet)
            ?.toRefused(RefusalCode.CYCLE_WITHOUT_HEAD)
    }

    /**
     * OWNERSHIP_VIOLATION (spec 23 SPSC, 91xzn-D1): an exclusive-carrying
     * [FanOutlet] admits one Consume subscriber. The live rule counts the
     * outlet's private `consumers` map; the witness here is the outlet's
     * Consume records in `linking.links` (the handshake registers every
     * link on the source side too) plus links planned earlier in this plan.
     * A subscriber attached by a bare `subscribe`, outside any handshake, is
     * in `consumers` but not in `linking.links`, and is not seen here.
     * Exclusivity is either witness [carriesExclusive] reads; the live rule
     * reads only the descriptor bit, which KSP sets together with the
     * natures stamp.
     */
    private fun ownershipCheck(c: LinkCandidate): StepCheck.Refused? {
        val outlet = c.outlet as? FanOutlet<*> ?: return null
        if (!carriesExclusive(outlet)) return null
        val subscribed = outlet.linking.links.any { it.role == LinkRole.Consume } || plannedFrom(outlet)
        if (!subscribed) return null
        return StepCheck.Refused(
            RefusalCode.OWNERSHIP_VIOLATION,
            "SPSC (spec 23): ${outlet.clazz.name} carries Owned/Leased payloads; outlet already has a subscriber",
        )
    }

    /**
     * AT_CAPACITY ([13-LINK-05] "at capacity"): a single-writer [FanInlet]
     * (FU-6) or a [FeedbackInlet] admits one Consume producer. For
     * `FanInlet` the witness is the live rule's own (`linking.links`). For
     * `FeedbackInlet` the live rule reads its private `activeProducer`; the
     * witness here is `linking.links` Consume records, which agree with it
     * on the in-process path: `handshake` sets `activeProducer` in `install`
     * and registers the link right after, and the unlink clears both.
     * A staged inlet has no live links; planned ones still count.
     */
    private fun capacityCheck(c: LinkCandidate): StepCheck.Refused? {
        val inlet = c.inlet
        val live = (inlet as? Linked)?.linking?.links.orEmpty().any { it.role == LinkRole.Consume }
        return when {
            inlet is FanInlet<*> && inlet.singleWriter && (live || plannedInto(inlet)) -> StepCheck.Refused(
                RefusalCode.AT_CAPACITY,
                "single-writer inlet already has a producer (strict point-to-point, FU-6)",
            )
            inlet is FeedbackInlet<*> && (live || plannedInto(inlet)) -> StepCheck.Refused(
                RefusalCode.AT_CAPACITY,
                "FeedbackInlet at capacity: already has an active producer (strict point-to-point)",
            )
            else -> null
        }
    }

    /**
     * POLICY_DENIAL ([13-LINK-05] "policy denial"): `handshake`'s walk over
     * the same request — the target's policies first, then the source's. It
     * is also what `Promotion.reauthorizeRebinds` dry-runs
     * (`LinkSupport.reauthorize` = `reject`), reused here without a
     * `graph -> evolve` edge (91xzn-D5).
     */
    private fun policyCheck(c: LinkCandidate): StepCheck.Refused? {
        val request = LinkRequest(c.outlet.ref, c.inlet.ref, CurrentPeer.get(), LinkRole.Consume)
        val rejected = (c.inlet as? Linked)?.linking?.reject(request)
            ?: (c.outlet as? Linked)?.linking?.reject(request)
            ?: return null
        return rejected.toRefused(RefusalCode.POLICY_DENIAL)
    }
}

/**
 * Whether [port] carries `Owned`/`Leased` payloads, by either witness the KSP
 * processor writes: a descriptor method with `exclusive = true` on the Fan
 * port's contract class, or the port's natures stamped
 * `OWNERSHIP = EXCLUSIVE`.
 */
private fun carriesExclusive(port: Port): Boolean {
    val clazz = when (port) {
        is FanOutlet<*> -> port.clazz
        is FanInlet<*> -> port.clazz
        else -> null
    }
    val byDescriptor = clazz?.let { ContractRegistry.descriptor(it)?.methods?.any { m -> m.exclusive } } == true
    return byDescriptor || port.natures.level(NatureAxis.OWNERSHIP) == Ownership.EXCLUSIVE
}

private fun payloadCheck(c: LinkCandidate): StepCheck.Refused? =
    checkPayload(c.outlet, c.inlet, c.outlet.ref, c.inlet.ref)?.toRefused(RefusalCode.CONTRACT_MISMATCH)

private fun natureCheck(c: LinkCandidate): StepCheck.Refused? =
    reconcileNatures(c.outlet.natures, c.inlet.natures)?.toRefused(RefusalCode.CONTRACT_MISMATCH)

private fun LinkResult.Rejected.toRefused(code: RefusalCode) = StepCheck.Refused(code, reason, mismatch)

/** A ref is live when the view locates it anywhere, or the apply host itself hosts it. */
private fun isLive(ref: CellRef, live: LiveView): Boolean =
    live.locationOf(ref) != null || live.host.resolveInlet(ref, "") !is RoutedInletResolution.NoCell

private fun outletUnresolved(name: String, owner: Any) = "Outlet not found or not linkable: $name on $owner"

private fun inletUnresolved(name: String, owner: Any) = "Inlet not found or not linkable: $name on $owner"

private fun multiHost(link: BoundaryLink) =
    "boundary ref ${link.liveRef} is not local to the apply host — a plan spanning more than one host " +
        "is not supported (G-61)"
