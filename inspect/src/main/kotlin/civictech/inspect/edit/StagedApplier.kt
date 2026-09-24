package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.graph.ApplyProgress
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.CellFactory
import civictech.cell.graph.Direction
import civictech.cell.graph.HostLiveView
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.Plan
import civictech.cell.graph.PlannedAction
import civictech.cell.graph.PlannedStep
import civictech.cell.graph.RefusalCode
import civictech.cell.graph.StepCheck
import civictech.cell.graph.StepResult
import civictech.cell.graph.Verdict
import civictech.cell.graph.precheck
import civictech.cell.host.HostManagementApi
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.natures
import civictech.inspect.Edge
import civictech.inspect.Endpoint
import civictech.inspect.InspectorServer
import civictech.nature.ContractRegistry
import civictech.nature.NatureAxis
import civictech.nature.Ownership
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The write plane's staged applier (WKB2 F3, `computenet-e1ojt`): runs one
 * [Draft] as PRECHECK → STAGE → CUT_OVER → (UNWIND | RETIRE) (`[WKB2-16]`)
 * and reports it as an [ApplyRecord] whose terminal [ApplyRecord.outcome] is
 * one of the closed [ApplyOutcome] names — never a partially-applied graph
 * left in place (`[WKB2-20]`).
 *
 * - **PRECHECK** — F2's cold `GraphSpec.precheck` over a [HostLiveView],
 *   plus one DESPAWN step per [Draft.despawns] (e1ojt-D6). A drained target
 *   host is refused with one synthetic step keyed `"host"`. Any refusal ends
 *   the apply as [ApplyOutcome.RefusedAtPrecheck] with the whole plan on the
 *   record and every step [StepOutcome.NotRun] (`[WKB2-15]`).
 * - **STAGE** — `GraphSpec.applyRemote` through a recording
 *   [HostManagementApi] that delegates every verb to the target host and
 *   remembers each ref `spawnBound` returns and each `Link` a 4-arg `connect`
 *   makes (e1ojt-D2). Nothing here touches a pre-existing cell: every spawn
 *   and every spec-internal link is between staged cells (`[WKB2-17]`).
 * - **CUT_OVER** — the boundary links, in list order, each wrapped in the
 *   applier's own payload-agnostic tap on the producing outlet
 *   (`FanOutlet.observe`, installed before `connect`, baseline read right
 *   after; e1ojt-D3). A rejected or throwing boundary `connect` unwinds.
 * - **RETIRE** — reached only when every boundary link connected. The record
 *   is marked `outcome = Committed` first, then [Draft.despawns] run in list
 *   order; a despawn that fails (it dead-letters on the host — see
 *   [Run.despawnAwaited]) is recorded [StepOutcome.Failed] on its step and
 *   the outcome stays committed (`[WKB2-24]`, e1ojt-D7).
 * - **UNWIND** — the single [Run.unwind] both a STAGE/CUT_OVER failure and
 *   [abort] run (e1ojt-D8): unlink every boundary link in reverse (reading its
 *   tap for residue), unlink every spec-internal link in reverse, then
 *   despawn every staged ref in reverse creation order (`[WKB2-18]`).
 *   `ManagedHost.despawn` does not retract links (e1ojt-D1), which is why the
 *   links are unlinked explicitly first.
 *
 * **Residue** (`[WKB2-25]`): a boundary link whose tap counted an emission
 * between its baseline and its unlink is reported
 * [Residue.EmittedAcrossBoundary]; into an exclusive staged inlet it is also
 * [Residue.OwnedConsumed] or [Residue.LeasedDischarged] (e1ojt-D11). A link
 * whose producing outlet is not a [FanOutlet] cannot be tapped, and is
 * reported as emitted — the honest direction when absence cannot be shown.
 * **Known limitation** (e1ojt-D7, handed to F13): an OUTBOUND boundary link's
 * `onLinked` catch-up baseline is pushed through `FanOutlet.at`, which
 * bypasses taps by construction (`Flow.kt` §Attribution), so a staged cell's
 * initial state delivered into a live inlet at link time is NOT observed as
 * residue. An emission racing `connect`'s return is counted before the
 * baseline and so is missed as well; one racing the unlink is counted.
 *
 * **Hosts are never un-drained and cells never un-suspended here**
 * (`[WKB2-58]`): a drained host or a suspended boundary/despawn target is a
 * PRECHECK refusal (`UNREACHABLE_REF`), and the recording management API
 * overrides only `spawnBound`, the 4-arg `connect` and `despawn`.
 *
 * One apply runs at a time: a second caller blocks on the apply lock
 * (admission is F7's, in front of this). [abort] and [record] never take that
 * lock.
 *
 * @param skipPrecheck test-only (e1ojt-D11): still plans, stores the plan,
 *   but ignores a `NotAppliable` verdict.
 * @param afterStage test-only (e1ojt-D8): invoked on the applying thread once
 *   STAGE succeeded, before CUT_OVER — the one pause point where [abort] is
 *   honoured.
 * @param beforeBoundaryLink test-only (e1ojt-D8): invoked with the list index
 *   before each boundary `connect`.
 */
class StagedApplier(
    private val hosts: Map<String, ManagedHost>,
    private val registry: LocationRegistry,
    private val clock: () -> Long,
    internal val skipPrecheck: Boolean = false,
    internal val afterStage: (ApplyRecord) -> Unit = {},
    internal val beforeBoundaryLink: (Int) -> Unit = {},
) {
    private val applyLock = ReentrantLock()

    @Volatile
    private var inFlight: Run? = null

    /** Retained records, oldest evicted past [RETAINED]; guarded by itself. */
    private val records = object : LinkedHashMap<String, ApplyRecord>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ApplyRecord>?) = size > RETAINED
    }

    /**
     * Applies [draft] and returns its terminal record. [submittedDraft] is the
     * verbatim draft as the caller received it (`[WKB2-04]`); the applier only
     * carries it.
     *
     * @throws IllegalArgumentException when [Draft.host] is not a known host,
     *   [Draft.promotions] is non-empty (`NOT_YET_SUPPORTED`, F9), a despawn
     *   target is listed twice, or [applyId] was already used.
     */
    fun apply(
        draft: Draft,
        applyId: String,
        identity: String,
        baseTopologyVersion: Long,
        submittedDraft: JsonElement = JsonNull,
    ): ApplyRecord {
        require(draft.host in hosts) { "unknown host '${draft.host}' (known: ${hosts.keys})" }
        require(draft.promotions.isEmpty()) {
            "NOT_YET_SUPPORTED: promotion requests are applied by WKB2 F9, not by this applier"
        }
        require(draft.despawns.distinct().size == draft.despawns.size) { "a despawn target is listed more than once" }
        applyLock.withLock {
            require(record(applyId) == null) { "apply id '$applyId' was already used" }
            val run = Run(
                applyId,
                hosts.getValue(draft.host),
                draft,
                ApplyRecord(
                    applyId = applyId,
                    identity = identity,
                    submittedDraft = submittedDraft,
                    baseTopologyVersion = baseTopologyVersion,
                    submittedAtMs = clock(),
                ),
            )
            inFlight = run
            try {
                run.execute()
            } finally {
                inFlight = null
            }
            return run.record
        }
    }

    /**
     * Requests that the in-flight apply [applyId] unwind. True — and the flag
     * set — only while that apply is in STAGE; it is honoured at the pause
     * point after STAGE (e1ojt-D8) by the same [Run.unwind] a failure runs.
     * Otherwise false, and nothing changes. Does no work itself.
     */
    fun abort(applyId: String): Boolean {
        val run = inFlight ?: return false
        if (run.applyId != applyId || run.record.phase != ApplyPhase.STAGE) return false
        run.abortRequested.set(true)
        return true
    }

    /** The latest snapshot of [applyId]'s record — in flight or terminal — or null. */
    fun record(applyId: String): ApplyRecord? = synchronized(records) { records[applyId] }

    /** A boundary link CUT_OVER connected, with the tap around its live interval. */
    private class Attachment(
        val boundary: BoundaryLink,
        val key: String,
        val link: Link,
        val edge: Edge,
        val stagedRef: CellRef,
        val inlet: Port?,
        val outlet: FanOutlet<*>?,
        val tapRef: PortRef,
        val count: AtomicLong,
        val baseline: Long,
    )

    /** One apply's mutable state; lives on the applying thread under [applyLock]. */
    private inner class Run(
        val applyId: String,
        val host: ManagedHost,
        val draft: Draft,
        initial: ApplyRecord,
    ) {
        @Volatile
        var record: ApplyRecord = initial
            private set

        val abortRequested = AtomicBoolean(false)

        private val steps = LinkedHashMap<String, StepOutcome>()
        private val stagedRefs = mutableListOf<CellRef>()
        private val handleByRef = mutableMapOf<CellRef, String>()
        private val refByHandle = mutableMapOf<String, CellRef>()
        private val internalLinks = mutableListOf<Pair<Link, String>>()
        private val attachments = mutableListOf<Attachment>()

        private val delegate: HostManagementApi = host.managementInlet.call

        /**
         * e1ojt-D2: every verb goes to the target host; `spawnBound` and the
         * 4-arg `connect` are also remembered, so UNWIND can retract exactly
         * what this apply created. Only STAGE's links are recorded here —
         * CUT_OVER's boundary links are kept with their taps in [attachments].
         */
        private val recorder: HostManagementApi = object : HostManagementApi by delegate {
            override fun spawnBound(factory: CellFactory, identity: IdentityBinding, parent: CellRef?): CellRef =
                delegate.spawnBound(factory, identity, parent).also { ref ->
                    stagedRefs += ref
                    publish()
                }

            override fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult =
                delegate.connect(from, outletName, to, inletName).also { result ->
                    if (result is LinkResult.Connected && record.phase == ApplyPhase.STAGE) {
                        // applyRemote's own step key; both handles resolved on earlier steps' events.
                        val key = "${handleByRef[from]}.$outletName->${handleByRef[to]}.$inletName"
                        internalLinks += result.link to key
                    }
                }

            override fun despawn(ref: CellRef) = delegate.despawn(ref)
        }

        fun execute() {
            publish()
            if (!precheck()) return
            if (!stage()) return
            cutOver()
        }

        private fun publish(
            phase: ApplyPhase = record.phase,
            outcome: ApplyOutcome? = record.outcome,
            plan: PlanDto? = record.plan,
            completedAtMs: Long? = record.completedAtMs,
        ) {
            record = record.copy(
                phase = phase,
                outcome = outcome,
                plan = plan,
                completedAtMs = completedAtMs,
                steps = LinkedHashMap(steps),
                stagedRefs = stagedRefs.map(InspectorServer::encodeRef),
            )
            synchronized(records) { records[applyId] = record }
        }

        private fun finish(outcome: ApplyOutcome) = publish(outcome = outcome, completedAtMs = clock())

        // ---- PRECHECK ------------------------------------------------------

        /** True when STAGE may run. */
        private fun precheck(): Boolean {
            val plan = plan()
            plan.steps.forEach { steps[it.key] = StepOutcome.NotRun }
            publish(plan = plan.toDto())
            if (plan.verdict is Verdict.NotAppliable && !skipPrecheck) {
                finish(ApplyOutcome.RefusedAtPrecheck)
                return false
            }
            return true
        }

        private fun plan(): Plan {
            if (host.isDrained) {
                val refused = PlannedStep(
                    HOST_STEP, null, PlannedAction.SPAWN, emptySet(),
                    StepCheck.Refused(RefusalCode.UNREACHABLE_REF, "target host '${draft.host}' is drained"),
                )
                return Plan(listOf(refused), Verdict.NotAppliable(listOf(refused)))
            }
            val planned = draft.spec.precheck(draft.boundary, HostLiveView(host, registry)).steps +
                draft.despawns.map(::planDespawn)
            val refusals = planned.filter { it.result is StepCheck.Refused }
            return Plan(planned, if (refusals.isEmpty()) Verdict.Appliable else Verdict.NotAppliable(refusals))
        }

        /** e1ojt-D6: a despawn target must be a reachable cell local to the target host. */
        private fun planDespawn(ref: CellRef): PlannedStep {
            val result = when (val location = registry.location(ref)) {
                null -> StepCheck.Refused(RefusalCode.UNKNOWN_REF, "despawn target $ref is not located by the registry")
                is LocationRegistry.Remote -> StepCheck.Refused(RefusalCode.MULTI_HOST, notLocal(ref))
                is LocationRegistry.Local -> when {
                    location.host !== host -> StepCheck.Refused(RefusalCode.MULTI_HOST, notLocal(ref))
                    host.isDrained || host.isSuspended(ref) -> StepCheck.Refused(
                        RefusalCode.UNREACHABLE_REF,
                        "despawn target $ref is not reachable: its host is drained or the cell is suspended",
                    )
                    else -> StepCheck.Ok
                }
            }
            return PlannedStep(despawnKey(ref), null, PlannedAction.DESPAWN, setOf(ref), result)
        }

        private fun notLocal(ref: CellRef) =
            "despawn target $ref is not local to target host '${draft.host}' — one host per draft (e1ojt-D5)"

        // ---- STAGE ---------------------------------------------------------

        /** True when CUT_OVER may run; otherwise UNWIND has already run. */
        private fun stage(): Boolean {
            publish(phase = ApplyPhase.STAGE)
            val progress = ApplyProgress { event ->
                val result = event.result
                steps[event.handle] = when (result) {
                    is StepResult.Applied -> StepOutcome.Applied
                    is StepResult.Rejected -> StepOutcome.Failed(result.reason)
                }
                // A spawn step's Applied carries its ref; a connect step's carries null.
                val spawned = (result as? StepResult.Applied)?.ref
                if (spawned != null) {
                    refByHandle[event.handle] = spawned
                    handleByRef[spawned] = event.handle
                }
                publish()
            }
            val allApplied = try {
                draft.spec.applyRemote(Use.fixed(recorder, PortRef.generate()), progress).allApplied
            } catch (e: Exception) {
                false
            }
            if (!allApplied) {
                unwind()
                return false
            }
            afterStage(record)
            if (abortRequested.get()) {
                unwind()
                return false
            }
            return true
        }

        // ---- CUT_OVER / RETIRE ---------------------------------------------

        private fun cutOver() {
            publish(phase = ApplyPhase.CUT_OVER)
            draft.boundary.forEachIndexed { index, boundary ->
                beforeBoundaryLink(index)
                val key = boundaryKey(boundary)
                val attached = try {
                    attach(boundary, key)
                } catch (e: Exception) {
                    steps[key] = StepOutcome.Failed(e.message ?: e.toString())
                    null
                }
                publish()
                if (attached == null) {
                    unwind()
                    return
                }
            }
            // The point of no return (e1ojt-D7): committed before the first despawn.
            publish(phase = ApplyPhase.RETIRE, outcome = ApplyOutcome.Committed, completedAtMs = clock())
            draft.despawns.forEach { ref ->
                steps[despawnKey(ref)] = despawnAwaited(ref)?.let(StepOutcome::Failed) ?: StepOutcome.Applied
                publish()
            }
        }

        /** Connects one boundary link inside its tap; null (step marked Failed) when refused. */
        private fun attach(boundary: BoundaryLink, key: String): Attachment? {
            val stagedRef = refByHandle[boundary.handle]
                ?: throw IllegalStateException("boundary handle '${boundary.handle}' was not spawned by STAGE")
            val inbound = boundary.direction == Direction.INBOUND
            val fromRef = if (inbound) boundary.liveRef else stagedRef
            val outletName = if (inbound) boundary.livePort else boundary.handlePort
            val toRef = if (inbound) stagedRef else boundary.liveRef
            val inletName = if (inbound) boundary.handlePort else boundary.livePort

            // e1ojt-D3: the tap goes on BEFORE connect so no emission over the link escapes it.
            val outlet = host.portAt(fromRef, outletName) as? FanOutlet<*>
            val tapRef = PortRef.generate()
            val count = AtomicLong()
            outlet?.observe(tapRef) { count.incrementAndGet() }
            val result = try {
                recorder.connect(fromRef, outletName, toRef, inletName)
            } catch (e: Exception) {
                outlet?.untap(tapRef)
                throw e
            }
            if (result !is LinkResult.Connected) {
                outlet?.untap(tapRef)
                steps[key] = StepOutcome.Failed(
                    (result as? LinkResult.Rejected)?.reason ?: "boundary link not connected: $result",
                )
                return null
            }
            val baseline = count.get()
            val edge = Edge(
                id = result.link.id.toString(),
                from = Endpoint(InspectorServer.encodeRef(fromRef), outletName),
                to = Endpoint(InspectorServer.encodeRef(toRef), inletName),
                role = Edge.CONSUME,
                fused = null,
            )
            val attachment = Attachment(
                boundary, key, result.link, edge, stagedRef,
                host.portAt(toRef, inletName), outlet, tapRef, count, baseline,
            )
            attachments += attachment
            steps[key] = StepOutcome.Applied
            return attachment
        }

        /**
         * Despawns [ref] and waits for it; null on success, else why it failed.
         *
         * The management `despawn` is fire-and-forget — `ManagedHost`'s
         * management dispatch enqueues it and returns, so a failing despawn
         * never throws here: it dead-letters on the host. It is therefore
         * bracketed by two awaited management calls ([barrier]); the host
         * runs tasks in (band, sequence) order, so when the second returns the
         * despawn has run, and a rise in the host's fault dead-letter count
         * across the bracket is its failure. **Attribution limit:** a fault
         * from another task enqueued on the host between the two brackets is
         * counted too, so the error is only ever toward reporting a failure.
         */
        private fun despawnAwaited(ref: CellRef): String? {
            barrier()
            val before = host.supervisionAccounting().deadLetters
            recorder.despawn(ref)
            barrier()
            val lettered = host.supervisionAccounting().deadLetters - before
            return if (lettered > 0) "despawn of $ref dead-lettered on the target host ($lettered fault(s))" else null
        }

        /** An awaited no-op management call: returns once every earlier management task has run. */
        private fun barrier() {
            delegate.lookup(CellRef(UUID.randomUUID()), HostManagementApi::class.java)
        }

        // ---- UNWIND --------------------------------------------------------

        /**
         * The one compensation path (e1ojt-D8): failure and [abort] both land
         * here. Boundary links first (each one's residue read between its
         * unlink and its untap), then spec-internal links, then staged refs —
         * each list in reverse, each despawn awaited before the next. A staged
         * ref whose despawn dead-letters (its `onDeactivate` threw, or it was
         * already gone) has left the host either way; its step is recorded
         * [StepOutcome.Failed] and the outcome is still decided by residue.
         */
        fun unwind() {
            publish(phase = ApplyPhase.UNWIND)
            val residue = mutableListOf<Residue>()
            attachments.asReversed().forEach { a ->
                a.link.unlink()
                val emitted = a.outlet == null || a.count.get() - a.baseline > 0
                a.outlet?.untap(a.tapRef)
                if (emitted) {
                    residue += Residue.EmittedAcrossBoundary(a.edge)
                    exclusiveResidue(a)?.let { residue += it }
                }
                steps[a.key] = StepOutcome.Unwound
                publish()
            }
            internalLinks.asReversed().forEach { (link, key) ->
                link.unlink()
                if (steps[key] == StepOutcome.Applied) steps[key] = StepOutcome.Unwound
                publish()
            }
            stagedRefs.asReversed().forEach { ref ->
                val outcome = despawnAwaited(ref)?.let { StepOutcome.Failed("unwind: $it") } ?: StepOutcome.Unwound
                handleByRef[ref]?.let { steps[it] = outcome }
                publish()
            }
            finish(if (residue.isEmpty()) ApplyOutcome.UnwoundClean else ApplyOutcome.UnwoundWithResidue(residue))
        }

        /**
         * e1ojt-D11: an INBOUND link into an exclusive staged inlet that carried
         * an emission consumed an `Owned` payload or discharged a `Leased` one.
         * The contract's method parameter types decide which; an inlet exclusive
         * only by its natures stamp names no type, and is reported owned-consumed.
         */
        private fun exclusiveResidue(a: Attachment): Residue? {
            if (a.boundary.direction != Direction.INBOUND) return null
            val inlet = a.inlet ?: return null
            val clazz = (inlet as? FanInlet<*>)?.clazz
            val byDescriptor = clazz?.let { ContractRegistry.descriptor(it)?.methods?.any { m -> m.exclusive } } == true
            if (!byDescriptor && inlet.natures.level(NatureAxis.OWNERSHIP) != Ownership.EXCLUSIVE) return null
            val ref = InspectorServer.encodeRef(a.stagedRef)
            val port = a.boundary.handlePort
            val parameterTypes = clazz?.methods?.flatMap { it.parameterTypes.asList() }.orEmpty()
            return when {
                Leased::class.java in parameterTypes && Owned::class.java !in parameterTypes ->
                    Residue.LeasedDischarged(ref, port)
                else -> Residue.OwnedConsumed(ref, port)
            }
        }
    }

    private companion object {
        /** Bound on retained records (the same size as [AuditRing]'s default). */
        const val RETAINED = 200

        /** The synthetic step a drained target host is refused under (e1ojt-D6). */
        const val HOST_STEP = "host"

        fun despawnKey(ref: CellRef) = "despawn:${InspectorServer.encodeRef(ref)}"

        /** F2's boundary step key, so plan keys and record step keys agree. */
        fun boundaryKey(link: BoundaryLink): String {
            val liveSide = "${link.liveRef}.${link.livePort}"
            val stagedSide = "${link.handle}.${link.handlePort}"
            return when (link.direction) {
                Direction.INBOUND -> "$liveSide->$stagedSide"
                Direction.OUTBOUND -> "$stagedSide->$liveSide"
            }
        }
    }
}
