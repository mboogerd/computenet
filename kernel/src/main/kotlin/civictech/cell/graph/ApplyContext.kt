package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.Cell
import civictech.cell.data.Replicable
import civictech.cell.durability.Journal
import civictech.cell.evolve.Evolve
import civictech.cell.evolve.EvolutionHandle
import civictech.cell.evolve.EvolutionHooks
import civictech.cell.evolve.Promotion
import civictech.cell.evolve.PromotionJournal
import civictech.cell.evolve.PromotionJudge
import civictech.cell.evolve.Shadow
import civictech.cell.evolve.StateMigrating
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.DurableInput
import civictech.cell.host.KeyedCells
import civictech.cell.host.ManagedHost
import civictech.cell.host.Recovery
import civictech.cell.host.JournalRecords
import civictech.cell.link.CurrentPeer
import civictech.cell.link.Link
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.OutletWaveState
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.identity
import civictech.cell.replication.Replication
import civictech.cell.replication.WriteAuthority
import civictech.cell.replication.WriteSigner
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Marks the one topology fold owned by an [ApplyContext], distinct from auxiliary providers. */
internal class ApplyContextTopologyProvider(
    private val events: () -> List<TopoEvent>,
) : () -> List<TopoEvent> {
    override fun invoke(): List<TopoEvent> = events()
}

/**
 * The local services a parameterized [GraphSpec] may lower onto. Parameters
 * remain data on graph steps; this context supplies the already-existing
 * runtime mechanisms that realize them (93 I-21, x0oag-D2).
 *
 * [journalFor] is intentionally backed by a per-ref binding table rather than
 * by [journals] directly: a later durable spawn binds its resolved ref before
 * the host evaluates its `journalFor` selector. The durable graph task owns
 * those bindings; this type only provides their shared home. A context owner
 * wires the host to that table before applying a spec, for example:
 *
 * ```
 * lateinit var context: ApplyContext
 * val host = ManagedHost(journalFor = { ref -> context.journalFor(ref) ?: defaultJournal })
 * context = ApplyContext(host, journals = mapOf("default" to defaultJournal))
 * ```
 *
 * The selector is evaluated once at spawn, so [GraphSpec.apply] binds a
 * `journalId` before calling the host's spawn path. A promotion traffic gate
 * without an explicit `journalId` is bound to [topology], allowing
 * [civictech.cell.host.HostDurability] to capture its internal checkpoint state and held
 * frames. That hosting decision does not make the gate `Stateful` or publish the `DURABLE`
 * nature; journal-less traffic gates remain valid.
 */
class ApplyContext(
    val host: ManagedHost,
    val replication: Replication? = null,
    val journals: Map<String, Journal> = emptyMap(),
    val journalDirs: Map<String, File> = emptyMap(),
    val topology: Journal? = null,
    val writeSigner: WriteSigner? = null,
    val signatureVerifier: civictech.cell.membrane.SignatureVerifier? = null,
) : TopologyApplier {
    private val journalBindings = ConcurrentHashMap<CellRef, Journal>()
    private val fold = MutableTopologyFold()
    private val activeLinks = mutableMapOf<TopologyLinkKey, Link>()
    private val cells = ConcurrentHashMap<CellRef, Cell>()
    private val familyInstances = mutableMapOf<String, KeyedCells<*>>()
    private var replayDepth = 0
    private var checkpointRehandshakeDepth = 0

    init {
        topology?.let { journal ->
            host.registerTopology(journal, ApplyContextTopologyProvider { live().events() })
        }
    }

    /** Cumulative live spawn handles, including handles explicitly [adopt]ed by the app. */
    val handles: Map<String, CellRef> get() = fold.snapshot().handles

    internal fun bind(ref: CellRef, journal: Journal) {
        journalBindings[ref] = journal
    }

    fun journalFor(ref: CellRef): Journal? = journalBindings[ref]

    /** Register an already-live app-owned ref as a topology handle without journaling it. */
    fun adopt(handle: String, ref: CellRef) {
        fold.adopt(handle, ref)
    }

    /** Immutable view of the successfully-applied live topology. */
    fun live(): TopologyFold = fold.snapshot()

    override fun activeEvolutions(): Set<CellRef> = live().activeEvolutions

    /** Recover topology and frames together, preserving this context's services and handle table. */
    fun recover(journal: Journal): Recovery = host.recoverFrom(journal, this)

    /**
     * Apply only topology records from [journal]. Checkpoints and frames remain untouched; this
     * is the offline topology seam used by consumers that need the fold but not a live replay.
     */
    fun replayTopology(journal: Journal): TopologyFold {
        replaying {
            journal.replay().forEach { record ->
                val decoded = JournalRecords.decode(record)
                if (decoded is DecodedJournalRecord.Topology) decoded.events.forEach(::apply)
            }
        }
        return live()
    }

    /**
     * Suppress topology recording for the dynamic extent of a recovery replay, then abort any
     * recovered evolution whose imperative judge/handle died with the prior process. Cleanup
     * runs after the final replay record, while [ManagedHost]'s recovery record-loop gate is
     * still held, so a later Promote record wins and data cannot race the abort.
     */
    internal fun <T> replaying(action: () -> T): T {
        val outermost = replayDepth == 0
        replayDepth++
        var completed = false
        return try {
            action().also { completed = true }
        } finally {
            replayDepth--
            if (outermost && completed) abortRecoveredEvolutions()
        }
    }

    /**
     * A candidate still named by [TopologyFold.activeEvolutions] after the complete journal has
     * replayed is an interrupted evolution (computenet-q37rn): [TopoEvent.EvolutionTap] is
     * written only by this context's own `evolve` hooks, before the tap it precedes, and is
     * retired by [applyPromote]/[TopoEvent.Promote] or by a [TopoEvent.Despawn] of the same
     * candidate — never by link shape or declaration order. Write the whole reversal before
     * applying any part of it so another crash deterministically finishes the same abort. Gate
     * colour is deliberately untouched.
     */
    private fun abortRecoveredEvolutions() {
        val recovered = live()
        recovered.activeEvolutions
            .mapNotNull { ref -> recovered.spawns[ref] }
            .forEach { spawn ->
                val candidate = spawn.ref
                val taps = recovered.links.values.filter { edge ->
                    edge.to == candidate && edge.options.staged && isEvolutionTap(edge)
                }
                val unlinks = taps.map { edge ->
                    TopoEvent.Unlink(edge.from, edge.outlet, edge.to, edge.inlet)
                }
                journalTopology(unlinks + TopoEvent.Despawn(candidate))
                unlinks.forEach(::applyUnlink)
                applyDespawn(TopoEvent.Despawn(candidate))
            }
    }

    private fun isEvolutionTap(edge: TopoEvent.Connect): Boolean {
        return Evolve.isTrafficLightDataOutlet(cells[edge.from], edge.from, edge.outlet)
    }

    /** One write-ahead topology record for one GraphSpec delta or one builder operation. */
    internal fun journalTopology(events: List<TopoEvent>) {
        if (replayDepth == 0) topology?.let { host.journalTopology(it, events) }
    }

    internal fun hasHandle(handle: String): Boolean = fold.containsHandle(handle)

    internal fun refFor(handle: String): CellRef = fold.refFor(handle)
        ?: throw IllegalStateException("unknown handle '$handle'")

    internal fun linkFor(key: TopologyLinkKey): Link? = synchronized(activeLinks) { activeLinks[key] }

    /** The live keyed-family instance registered for [handle], or `null` when it is not a family handle. */
    fun familyFor(handle: String): KeyedCells<*>? = synchronized(familyInstances) { familyInstances[handle] }

    /** Apply one recovered or already-journaled event. Recording is deliberately separate. */
    override fun apply(event: TopoEvent) {
        when (event) {
            is TopoEvent.Spawn -> applySpawn(event)
            is TopoEvent.Connect -> applyConnect(event)
            is TopoEvent.Unlink -> applyUnlink(event)
            is TopoEvent.Despawn -> applyDespawn(event)
            is TopoEvent.Promote -> applyPromote(event)
            is TopoEvent.Family -> applyFamily(event)
            is TopoEvent.FamilyKey -> {
                host.recoverFamilyKey(event.namespace, event.key)
                fold.record(event)
            }
            is TopoEvent.EvolutionTap -> fold.record(event)
        }
    }

    /**
     * A compacted journal restores cell state after its leading topology fold. Re-handshake
     * the folded links at that boundary so ordinary on-linked catch-up observes the restored
     * state; an uncompacted frame tail needs no such nudge because its replay emits normally.
     */
    override fun checkpointRestored() {
        checkpointRehandshakeDepth++
        try {
            live().links.values.forEach { event ->
                val key = TopologyLinkKey.of(event)
                synchronized(activeLinks) { activeLinks.remove(key) }?.unlink()
                applyConnect(event)
            }
        } finally {
            checkpointRehandshakeDepth--
        }
    }

    internal fun applySpawn(event: TopoEvent.Spawn, prepared: Cell? = null): CellRef {
        check(!fold.containsHandle(event.handle)) { "duplicate handle '${event.handle}'" }
        val authority = event.authority ?: WriteAuthority.Open
        if (authority != WriteAuthority.Open && (writeSigner == null || signatureVerifier == null)) {
            throw IllegalStateException(
                "spawn step '${event.handle}': parameter 'authority' requires a WriteSigner and a " +
                    "SignatureVerifier on the ApplyContext",
            )
        }
        event.journalId?.let { journalId ->
            bind(event.ref, journals[journalId] ?: throw missingJournal(event.handle, journalId))
        }
        val cell = prepared ?: event.factory.create(event.ref)
        if (event.journalId == null && topology != null && Promotion.isDurableGate(cell)) {
            bind(event.ref, topology)
        }
        requireBoundRef(event.handle, IdentityBinding.Exact(event.ref), event.ref, cell.ref)
        val spawned = if (event.replicated) {
            val service = replication ?: throw missingReplication(event.handle)
            val replicable = cell as? Replicable<*>
                ?: throw IllegalStateException(
                    "spawn step '${event.handle}': parameter 'replicated' requires a Replicable cell " +
                        "(built ${cell.javaClass.name})",
                )
            service.replicate(replicable, host, authority, writeSigner, signatureVerifier)
            if (event.shadow) suppressShadow(cell)
            cell.ref
        } else if (event.shadow) {
            Shadow.spawn(host, cell)
        } else {
            host.managementInlet.call.spawn(cell)
        }
        check(spawned == event.ref) {
            "spawn step '${event.handle}' materialized $spawned instead of pinned ref ${event.ref}"
        }
        cells[event.ref] = cell
        fold.record(event)
        return spawned
    }

    internal fun applyConnect(event: TopoEvent.Connect): Link? {
        val key = TopologyLinkKey.of(event)
        val link = when (
            val result = host.managementInlet.call.connectStep(
                event.from, event.outlet, event.to, event.inlet, event.options,
            )
        ) {
            is LinkResult.Connected -> result.link.also { synchronized(activeLinks) { activeLinks[key] = it } }
            is LinkResult.Rejected -> error(
                "link ${event.from}.${event.outlet} → ${event.to}.${event.inlet} rejected: ${result.reason}",
            )
            LinkResult.Deferred -> null
        }
        fold.record(event)
        return link
    }

    internal fun applyUnlink(event: TopoEvent.Unlink) {
        val key = TopologyLinkKey.of(event)
        val link = synchronized(activeLinks) { activeLinks.remove(key) }
            ?: throw IllegalStateException("unlink event '$key': no live link")
        link.unlink()
        fold.record(event)
    }

    internal fun applyDespawn(event: TopoEvent.Despawn) {
        fold.linksTouching(event.ref).forEach { edge ->
            val key = TopologyLinkKey.of(edge)
            val link = synchronized(activeLinks) { activeLinks.remove(key) }
                ?: throw IllegalStateException("despawn ${event.ref}: fold contains live link '$key' with no link object")
            link.unlink()
        }
        host.managementInlet.call.despawn(event.ref)
        cells.remove(event.ref)
        journalBindings.remove(event.ref)
        fold.record(event)
    }

    /**
     * Journal-aware T0/T1 promotion (uwt8b-D7..D10). Both live objects are
     * resolved from the refs materialized by this context; callers do not need
     * to retain implementation objects across a runtime composition boundary.
     */
    fun promote(
        gate: CellRef,
        incumbent: CellRef,
        candidate: CellRef,
        outletName: String,
        downstream: List<Pair<CellRef, String>>,
        judge: PromotionJudge? = null,
    ) = promote(
        gate = gate,
        incumbent = incumbent,
        candidate = candidate,
        outletName = outletName,
        downstream = downstream,
        authorityRefusal = ::defaultEvolutionAuthorityRefusal,
        judge = judge,
    )

    /**
     * Privileged direct promotion with an explicit per-runtime authority policy. This path keeps
     * the synchronous, already-live-cell contract used by journal recovery; normal declarative
     * shadow/judge orchestration enters through [evolve].
     */
    fun promote(
        gate: CellRef,
        incumbent: CellRef,
        candidate: CellRef,
        outletName: String,
        downstream: List<Pair<CellRef, String>>,
        authorityRefusal: () -> String?,
        judge: PromotionJudge? = null,
    ) {
        checkEvolutionAuthority(authorityRefusal)
        val prepared = prepareSinglePromotion(gate, incumbent, candidate, outletName, downstream)

        Promotion.promote(
            host = host,
            gate = prepared.gate,
            incumbent = prepared.incumbent,
            candidate = prepared.candidate,
            outletName = outletName,
            downstream = prepared.downstream,
            judge = judge,
            journal = prepared.journal,
        )
        cells.remove(incumbent)
        journalBindings.remove(incumbent)
    }

    /** Lower one declarative [PromoteStep] onto the live evolution pipeline. */
    fun evolve(step: PromoteStep): EvolutionHandle {
        val incumbentRef = evolutionRef(step.incumbent, "incumbent")
        val incumbentSpawn = live().spawns[incumbentRef]
            ?: throw Promotion.PromotionAborted(
                "PRECHECK",
                "incumbent '${step.incumbent}' ($incumbentRef) has no recorded spawn",
            )
        if (incumbentSpawn.replicated) return evolveReplica(step, incumbentRef)
        if (step.candidate.isBlank() || step.replicatedCandidateFactory != null) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "single-instance promotion requires one candidate handle and no replicated candidate factory",
            )
        }

        val gateRef = evolutionRef(step.gate, "gate")
        val candidateRef = evolutionRef(step.candidate, "candidate")
        val downstream = step.downstream.map { (handle, inlet) ->
            evolutionRef(handle, "downstream") to inlet
        }
        val prepared = prepareSinglePromotion(
            gateRef,
            incumbentRef,
            candidateRef,
            step.outletName,
            downstream,
        )
        if (!prepared.candidateSpawn.shadow) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "candidate '${step.candidate}' must be declared shadow = true",
            )
        }

        val gates = step.gates.map { handle ->
            handle to cells.getValue(evolutionRef(handle, "gate"))
        }
        val baselineTwin = step.baseline?.let { handle ->
            val ref = evolutionRef(handle, "baseline")
            val spawn = live().spawns[ref]
                ?: throw Promotion.PromotionAborted(
                    "PRECHECK",
                    "baseline '$handle' ($ref) has no recorded spawn",
                )
            if (!spawn.shadow) {
                throw Promotion.PromotionAborted(
                    "PRECHECK",
                    "baseline '$handle' must be declared shadow = true",
                )
            }
            cells.getValue(ref)
        }
        val baselineGates = step.baseline?.let {
            step.baselineGates.map { handle ->
                handle to cells.getValue(evolutionRef(handle, "baseline gate"))
            }
        }.orEmpty()
        val hooks = object : EvolutionHooks {
            private val taps = mutableMapOf<PortRef, TopoEvent.Connect>()
            private val tappedCandidates = mutableSetOf<CellRef>()

            override val journal: PromotionJournal = prepared.journal

            override fun <T : Any> tapShadow(outlet: FanOutlet<T>, inlet: FanInlet<T>): PortRef {
                val from = requireNotNull(outlet.identity()) { "evolution gate outlet has no registered identity" }
                val to = requireNotNull(inlet.identity()) { "evolution candidate inlet has no registered identity" }
                // Write-ahead evidence that THIS evolution owns the tap it is about to install,
                // before installing it (computenet-q37rn). One per candidate: a shadow may have
                // several matching inlets, so later calls for the same candidate are no-ops here.
                if (tappedCandidates.add(to.owner)) {
                    val begin = TopoEvent.EvolutionTap(to.owner)
                    journalTopology(listOf(begin))
                    apply(begin)
                }
                val event = TopoEvent.Connect(
                    from = from.owner,
                    outlet = from.name,
                    to = to.owner,
                    inlet = to.name,
                    options = LinkOptions(staged = true),
                )
                journalTopology(listOf(event))
                checkNotNull(applyConnect(event)) { "evolution shadow tap was deferred" }
                taps[inlet.ref] = event
                return inlet.ref
            }

            override fun <T : Any> untapShadow(outlet: FanOutlet<T>, inlet: PortRef) {
                val connected = checkNotNull(taps.remove(inlet)) {
                    "evolution shadow tap $inlet was not installed by this handle"
                }
                val event = TopoEvent.Unlink(
                    connected.from,
                    connected.outlet,
                    connected.to,
                    connected.inlet,
                )
                journalTopology(listOf(event))
                applyUnlink(event)
            }

            override fun despawnShadow(host: ManagedHost, ref: CellRef) {
                val event = TopoEvent.Despawn(ref)
                journalTopology(listOf(event))
                applyDespawn(event)
            }

            override fun promoted(incumbent: CellRef) {
                cells.remove(incumbent)
                journalBindings.remove(incumbent)
            }
        }

        return Evolve.run(
            host = host,
            gateHandle = step.gate,
            gate = prepared.gate,
            incumbent = prepared.incumbent,
            candidate = prepared.candidate,
            outletName = step.outletName,
            downstream = prepared.downstream,
            policy = step.policy,
            gates = gates,
            baselineTwin = baselineTwin,
            baselineGates = baselineGates,
            hooks = hooks,
        )
    }

    private fun evolveReplica(step: PromoteStep, incumbentRef: CellRef): EvolutionHandle {
        val candidateFactory = step.replicatedCandidateFactory
        if (step.candidate.isNotBlank() || candidateFactory == null) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "replicated promotion requires one replicated candidate factory and no candidate handle",
            )
        }
        if (step.gate.isNotBlank()) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "replicated promotion does not accept single-instance field 'gate'",
            )
        }
        if (step.downstream.isNotEmpty()) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "replicated promotion does not accept single-instance field 'downstream'",
            )
        }
        if (step.baseline != null) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "replicated promotion does not accept single-instance field 'baseline'",
            )
        }
        if (step.baselineGates.isNotEmpty()) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "replicated promotion does not accept single-instance field 'baselineGates'",
            )
        }
        checkEvolutionAuthority(::defaultEvolutionAuthorityRefusal)
        val gates = step.gates.map { handle ->
            handle to cells.getValue(evolutionRef(handle, "gate"))
        }
        val prepared = prepareReplicatedPromotion(
            ref = incumbentRef,
            candidateFactory = candidateFactory,
            outletName = step.outletName,
        )
        var replicaTapSeen = false
        var activeReplicaTap: PortRef? = null
        val hooks = object : EvolutionHooks {
            override val journal: PromotionJournal = prepared.journal

            override fun <T : Any> tapReplicatedShadow(
                outlet: FanOutlet<T>,
                inlet: FanInlet<T>,
            ): PortRef {
                check(activeReplicaTap == null) { "replicated evolution already has an active shadow tap" }
                outlet.subscribe(inlet)
                replicaTapSeen = true
                activeReplicaTap = inlet.ref
                return inlet.ref
            }

            override fun <T : Any> untapReplicatedShadow(outlet: FanOutlet<T>, inlet: PortRef) {
                check(activeReplicaTap == inlet) {
                    "replicated evolution shadow tap $inlet was not installed by this handle"
                }
                outlet.unsubscribe(inlet)
                activeReplicaTap = null
            }

            override fun promotedReplica(ref: CellRef) {
                check(replicaTapSeen && activeReplicaTap == null) {
                    "replicated promotion committed without closing its graph-owned shadow tap"
                }
                cells[ref] = prepared.candidateCell
            }
        }

        return Evolve.runReplica(
            host = host,
            replication = prepared.replication,
            incumbent = prepared.incumbent,
            candidate = prepared.candidate,
            policy = step.policy,
            gates = gates.map { (handle, cell) ->
                cell as? civictech.cell.verify.InvariantCell<*, *>
                    ?: throw Promotion.PromotionAborted(
                        "PRECHECK",
                        "gate handle '$handle' (${cell.ref}) is not an InvariantCell",
                    )
            },
            outletName = step.outletName,
            hooks = hooks,
        )
    }

    private data class PreparedSinglePromotion(
        val gate: Cell,
        val incumbent: Cell,
        val candidate: Cell,
        val candidateSpawn: TopoEvent.Spawn,
        val downstream: List<Use<*>>,
        val journal: PromotionJournal,
    )

    /**
     * Shared, side-effect-free PRECHECK and journal seam for both the legacy
     * direct promotion entry point and declarative evolution lowering.
     */
    private fun prepareSinglePromotion(
        gate: CellRef,
        incumbent: CellRef,
        candidate: CellRef,
        outletName: String,
        downstream: List<Pair<CellRef, String>>,
    ): PreparedSinglePromotion {
        val before = live()
        val gateCell = cells[gate]
            ?: throw Promotion.PromotionAborted("PRECHECK", "gate $gate is not a live TrafficLightApi")
        val incumbentCell = cells[incumbent]
            ?: throw Promotion.PromotionAborted("PRECHECK", "incumbent $incumbent is not live")
        val candidateCell = cells[candidate]
            ?: throw Promotion.PromotionAborted("PRECHECK", "candidate $candidate is not live")
        val incumbentSpawn = before.spawns[incumbent]
            ?: throw Promotion.PromotionAborted("PRECHECK", "incumbent $incumbent has no recorded spawn")
        val candidateSpawn = before.spawns[candidate]
            ?: throw Promotion.PromotionAborted("PRECHECK", "candidate $candidate has no recorded spawn")
        if (incumbentSpawn.replicated || candidateSpawn.replicated) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "ApplyContext.promote is the single-instance path; replicated promotion uses promoteReplica",
            )
        }
        val incumbentJournal = journalFor(incumbent)
        val candidateJournal = journalFor(candidate)
        if (incumbentJournal !== candidateJournal) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "incumbent $incumbent and candidate $candidate must share one journal binding",
            )
        }
        if (incumbentJournal != null && topology !== incumbentJournal) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "journaled promotion requires the incumbent journal to be this context's topology journal",
            )
        }
        if (incumbentJournal != null && (candidateCell !is StateMigrating || incumbentCell !is civictech.cell.Stateful)) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "journaled promotion requires a T0/T1 StateMigrating candidate and Stateful incumbent",
            )
        }
        val uses = downstream.map { (ref, inletName) ->
            host.portAt(ref, inletName) as? Use<*>
                ?: throw Promotion.PromotionAborted(
                    "PRECHECK",
                    "downstream $ref.$inletName is not a live usable inlet",
                )
        }
        val priorLinks = before.links.values.filter { it.from == incumbent || it.to == incumbent }
        val promotionJournal = object : PromotionJournal {
            override fun checkpointBeforeStateHandoff() {
                incumbentJournal?.let(host::checkpoint)
            }

            override fun recordCommittedSwap(waveState: OutletWaveState) {
                val event = TopoEvent.Promote(
                    gate = gate,
                    incumbent = incumbent,
                    candidate = candidate,
                    outlet = outletName,
                    candidateFactory = candidateSpawn.factory,
                    replicated = false,
                    sourceId = waveState.sourceId,
                    highWater = waveState.highWater,
                )
                if (incumbentJournal != null) journalTopology(listOf(event))
                fold.record(event)
                synchronizeActiveLinksAfterPromotion(event, priorLinks)
            }
        }
        return PreparedSinglePromotion(
            gateCell,
            incumbentCell,
            candidateCell,
            candidateSpawn,
            uses,
            promotionJournal,
        )
    }

    private fun evolutionRef(handle: String, role: String): CellRef {
        val ref = try {
            refFor(handle)
        } catch (_: IllegalStateException) {
            throw Promotion.PromotionAborted("PRECHECK", "$role handle '$handle' is not live")
        }
        if (cells[ref] == null) {
            throw Promotion.PromotionAborted("PRECHECK", "$role handle '$handle' ($ref) is not live")
        }
        return ref
    }

    /**
     * Journal-aware rolling promotion of one replicated instance behind its
     * existing [ref]. The candidate is constructed only after the live fold and
     * journal binding have passed PRECHECK; [Promotion.promoteReplica] owns the
     * actual rebind and invokes the checkpoint/record seam around its COMMIT.
     */
    fun promoteReplica(
        ref: CellRef,
        candidateFactory: CellFactory,
        outletName: String = "outlet",
        judge: PromotionJudge? = null,
    ) = promoteReplica(
        ref = ref,
        candidateFactory = candidateFactory,
        authorityRefusal = ::defaultEvolutionAuthorityRefusal,
        outletName = outletName,
        judge = judge,
    )

    /** Rolling-replica counterpart to the privileged direct [promote] path. */
    fun promoteReplica(
        ref: CellRef,
        candidateFactory: CellFactory,
        authorityRefusal: () -> String?,
        outletName: String = "outlet",
        judge: PromotionJudge? = null,
    ) {
        checkEvolutionAuthority(authorityRefusal)
        val prepared = prepareReplicatedPromotion(ref, candidateFactory, outletName)

        Promotion.promoteReplica(
            host = host,
            replication = prepared.replication,
            incumbent = prepared.incumbent,
            candidate = prepared.candidate,
            outletName = outletName,
            judge = judge,
            journal = prepared.journal,
        )
        cells[ref] = prepared.candidateCell
    }

    private data class PreparedReplicatedPromotion(
        val replication: Replication,
        val incumbent: Replicable<*>,
        val candidate: Replicable<*>,
        val candidateCell: Cell,
        val journal: PromotionJournal,
    )

    /** Shared PRECHECK and durability seam for direct and declarative rolling promotion. */
    private fun prepareReplicatedPromotion(
        ref: CellRef,
        candidateFactory: CellFactory,
        outletName: String,
    ): PreparedReplicatedPromotion {
        val before = live()
        val service = replication
            ?: throw Promotion.PromotionAborted("PRECHECK", "replicated promotion requires a Replication service")
        val incumbentCell = cells[ref]
            ?: throw Promotion.PromotionAborted("PRECHECK", "incumbent $ref is not live")
        val incumbent = incumbentCell as? Replicable<*>
            ?: throw Promotion.PromotionAborted("PRECHECK", "incumbent $ref is not Replicable")
        val incumbentSpawn = before.spawns[ref]
            ?: throw Promotion.PromotionAborted("PRECHECK", "incumbent $ref has no recorded spawn")
        if (!incumbentSpawn.replicated) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "ApplyContext.promoteReplica requires a replicated spawn for $ref",
            )
        }
        val candidateCell = try {
            candidateFactory.create(ref)
        } catch (e: Exception) {
            throw Promotion.PromotionAborted("PRECHECK", "candidate factory failed for $ref: ${e.message}", e)
        }
        if (candidateCell.ref != ref) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "candidate factory built ${candidateCell.ref}; replicated promotion must reuse incumbent $ref",
            )
        }
        val candidate = candidateCell as? Replicable<*>
            ?: throw Promotion.PromotionAborted(
                "PRECHECK",
                "candidate factory built non-Replicable ${candidateCell.javaClass.name}",
            )
        val incumbentJournal = journalFor(ref)
        if (incumbentJournal != null && topology !== incumbentJournal) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "journaled promotion requires the incumbent journal to be this context's topology journal",
            )
        }
        if (incumbentJournal != null &&
            (incumbentCell !is civictech.cell.Stateful || candidateCell !is civictech.cell.Stateful)
        ) {
            throw Promotion.PromotionAborted(
                "PRECHECK",
                "journaled replicated promotion requires Stateful incumbent and candidate",
            )
        }

        val promotionJournal = object : PromotionJournal {
            override fun checkpointBeforeStateHandoff() {
                incumbentJournal?.let(host::checkpoint)
            }

            override fun recordCommittedSwap(waveState: OutletWaveState) {
                val event = TopoEvent.Promote(
                    gate = null,
                    incumbent = ref,
                    candidate = ref,
                    outlet = outletName,
                    candidateFactory = candidateFactory,
                    replicated = true,
                    sourceId = waveState.sourceId,
                    highWater = waveState.highWater,
                )
                if (incumbentJournal != null) journalTopology(listOf(event))
                fold.record(event)
            }
        }

        return PreparedReplicatedPromotion(
            replication = service,
            incumbent = incumbent,
            candidate = candidate,
            candidateCell = candidateCell,
            journal = promotionJournal,
        )
    }

    private fun checkEvolutionAuthority(authorityRefusal: () -> String?) {
        authorityRefusal()?.let { reason ->
            throw Evolve.Refused("authority: $reason")
        }
    }

    /** Mirrors [civictech.cell.evolve.EvolutionAuthority.LocalTrustedOnly] without crossing layers. */
    private fun defaultEvolutionAuthorityRefusal(): String? =
        CurrentPeer.stamp()?.let { "remote principal ${it.id} may not trigger evolution" }

    /**
     * Replay applies an uncompacted completed swap directly; a compacted fold already contains
     * the active candidate and redirected links, so its retained Promote is provenance only.
     */
    private fun applyPromote(event: TopoEvent.Promote) {
        if (event.replicated) {
            applyReplicatedPromote(event)
            return
        }
        check(event.incumbent != event.candidate) {
            "single-instance promotion replay requires distinct incumbent and candidate refs"
        }
        if (cells[event.incumbent] == null && cells[event.candidate] != null) {
            fold.record(event)
            val gate = event.gate?.let { ref ->
                cells[ref] ?: error("compacted promotion replay gate $ref is not live")
            } ?: error("compacted single-instance promotion replay has no gate")
            Promotion.completeRecoveredGate(gate)
            return
        }
        val before = live()
        val incumbent = cells[event.incumbent]
            ?: error("promotion replay names missing incumbent ${event.incumbent}")
        val candidate = cells[event.candidate]
            ?: error("promotion replay names missing candidate ${event.candidate}")
        val gate = event.gate?.let { ref ->
            cells[ref] ?: error("promotion replay gate $ref is not live")
        } ?: error("single-instance promotion replay has no gate")
        val migrator = candidate as? StateMigrating
            ?: error("promotion replay candidate ${event.candidate} is not StateMigrating")
        val stateful = incumbent as? civictech.cell.Stateful
            ?: error("promotion replay incumbent ${event.incumbent} is not Stateful")
        val outlet = host.portAt(event.candidate, event.outlet) as? FanOutlet<*>
            ?: error("promotion replay candidate ${event.candidate} has no fan-out '${event.outlet}'")

        migrator.importFrom(stateful.snapshot())
        outlet.adoptWaveState(OutletWaveState(event.sourceId, event.highWater))

        before.links.values.filter { it.from == event.incumbent || it.to == event.incumbent }.forEach { edge ->
            val key = TopologyLinkKey.of(edge)
            val link = synchronized(activeLinks) { activeLinks.remove(key) }
                ?: error("promotion replay: fold contains live link '$key' with no link object")
            link.unlink()
        }
        before.links.values
            .filter { it.from == event.incumbent && it.outlet == event.outlet }
            .map { it.copy(from = event.candidate) }
            .forEach(::applyConnect)

        host.managementInlet.call.despawn(event.incumbent)
        cells.remove(event.incumbent)
        journalBindings.remove(event.incumbent)
        fold.record(event)
        // The Promote record is written before live COMMIT turns the gate green. On
        // recovery the record represents that completed commit, so green it before
        // any following frame tail is delivered. Keeping those frames inside their
        // replay provenance lets same-journal duplicate suppression see the copies
        // that the gate re-derives for the already-replayed candidate inlet.
        Promotion.completeRecoveredGate(gate)
    }

    /** Recovery-side reuse-ref swap: no gate and no journal write, only the recorded COMMIT. */
    private fun applyReplicatedPromote(event: TopoEvent.Promote) {
        check(event.gate == null) { "replicated promotion replay must not name a membrane gate" }
        check(event.incumbent == event.candidate) {
            "replicated promotion replay must reuse one ref: ${event.incumbent} != ${event.candidate}"
        }
        val ref = event.incumbent
        val service = replication ?: error("replicated promotion replay requires a Replication service")
        val incumbentCell = cells[ref]
            ?: error("replicated promotion replay names missing incumbent $ref")
        val incumbent = incumbentCell as? Replicable<*>
            ?: error("replicated promotion replay incumbent $ref is not Replicable")
        val candidateCell = event.candidateFactory.create(ref)
        requireBoundRef("promotion replay candidate", IdentityBinding.Exact(ref), ref, candidateCell.ref)
        val candidate = candidateCell as? Replicable<*>
            ?: error("replicated promotion replay candidate $ref is not Replicable")

        // rebind restores the checkpoint-restored incumbent snapshot into the
        // candidate before replacing the hosted object, then ordinary replicate
        // re-establishes gossip and the retained watermark row under the same ref.
        service.rebind(incumbent, candidate, host)
        val outlet = host.portAt(ref, event.outlet) as? FanOutlet<*>
            ?: error("replicated promotion replay candidate $ref has no fan-out '${event.outlet}'")
        outlet.adoptWaveState(OutletWaveState(event.sourceId, event.highWater))
        cells[ref] = candidateCell
        fold.record(event)
    }

    private fun synchronizeActiveLinksAfterPromotion(
        event: TopoEvent.Promote,
        priorLinks: List<TopoEvent.Connect>,
    ) {
        synchronized(activeLinks) {
            priorLinks.forEach { activeLinks.remove(TopologyLinkKey.of(it)) }
            val installed = (host.portAt(event.candidate, event.outlet) as? FanOutlet<*>)
                ?.linking
                ?.links
                .orEmpty()
            priorLinks
                .filter { it.from == event.incumbent && it.outlet == event.outlet }
                .map { it.copy(from = event.candidate) }
                .forEach { edge ->
                    installed.firstOrNull {
                        it.from == PortRef.of(edge.from, edge.outlet) &&
                            it.to == PortRef.of(edge.to, edge.inlet)
                    }?.let { activeLinks[TopologyLinkKey.of(edge)] = it }
                }
        }
    }

    private fun applyFamily(event: TopoEvent.Family) {
        check(!fold.containsHandle(event.handle)) { "duplicate handle '${event.handle}'" }
        val family = buildFamily(
            SpawnStep(handle = event.handle, factory = event.factory, family = event.family),
        )
        host.registerFamily(event.family.namespace, family)
        synchronized(familyInstances) { familyInstances[event.handle] = family }
        fold.record(event)
    }

    /** Construct the wrapper for a keyed family without spawning or recovering any key. */
    internal fun buildFamily(step: SpawnStep): KeyedCells<Any> {
        val family = step.family
            ?: error("spawn step '${step.handle}' has no family parameter")
        val factory = step.factory as? KeyedCellFactory
            ?: error("spawn step '${step.handle}': parameter 'family' requires a KeyedCellFactory")
        val journalDir = family.journalId?.let { journalId ->
            journalDirs[journalId]
                ?: throw missingFamilyJournal(step.handle, journalId)
        }
        return KeyedCells(
            host = host,
            journalDir = journalDir,
            namespace = family.namespace,
            factory = { key, ref -> factory.create(key, ref) },
            render = family.keys.render,
            parse = family.keys.parse,
            spawnOnInterest = family.spawnOnInterest,
        )
    }
}

/** The handles produced by one local [GraphSpec.apply]. */
data class AppliedGraph(
    val refs: Map<String, CellRef>,
    val families: Map<String, KeyedCells<*>>,
    val links: Map<String, Link>,
    val inputs: Map<String, Map<String, DurableInput>> = emptyMap(),
    val evolutions: Map<String, EvolutionHandle> = emptyMap(),
)
