package civictech.cell.host



import civictech.cell.port.*
import civictech.cell.BlockingCell
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellError
import civictech.cell.CellRef
import civictech.cell.ErrorReporting
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.ReBaselineEmitting
import civictech.cell.Redacted
import civictech.cell.Stateful
import civictech.cell.SuspendingCell
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import civictech.cell.attention.AttentionBand
import civictech.cell.attention.AttentionSupport
import civictech.cell.attention.NonSuspendable
import civictech.cell.attention.StallNotice
import civictech.cell.attention.StallReason
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.durability.Journal
import civictech.cell.evolve.Effectful
import civictech.cell.Timestamp
import civictech.cell.wire.WireCodec
import civictech.cell.graph.CellFactory
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.requireBoundRef
import civictech.cell.data.Magnitude
import civictech.cell.data.Propagate
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.RoutedInletResolution
import civictech.gen.wire.ProtocolRegistry
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

private const val RECORD_FRAME: Byte = 1
private const val RECORD_CHECKPOINT: Byte = 2
private const val RECORD_FRONTIER: Byte = 3

/**
 * Durable record of an [Effectful] inlet's processed-frontier advance (G-59,
 * fixes C-9; spec 20/24, 30/31, 50/52 "Effectful recovery"): the last applied
 * `(sourceId, counter)` for one `(cellRef, portName)`.
 */
private data class FrontierRecord(val cellRef: CellRef, val portName: String, val timestamp: Timestamp) :
    Serializable

/** Checkpoint payload (M10.2, extended G-59): cell state plus the processed-frontier, atomically together. */
private data class CheckpointRecord(
    val state: Map<CellRef, Serializable>,
    val frontier: Map<Pair<CellRef, String>, Map<UUID, Long>>,
) : Serializable

/**
 * A Host that manages the lifecycle and connectivity of [Cell]s.
 *
 * Execution is delegated to a [HostScheduler]: threaded ([VirtualThreadScheduler], the default)
 * or deterministic ([civictech.cell.host.SimulationController]).
 */
open class ManagedHost(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    scheduler: HostScheduler? = null,
    private val registry: LocationRegistry? = null,
    /** Attention → resources mapping (spec 34, M6.3); null = pre-M6 FIFO scheduling. */
    private val attention: AttentionPolicy? = null,
    /**
     * Max cells in this host's subtree — itself plus child hosts, recursively
     * (G-28, M8.1). A spawn anywhere in the subtree counts against every
     * ancestor's quota. Null = unlimited. Hosts count as cells of their
     * parent; this is a sandbox budget, not a resource model.
     */
    private val quota: Int? = null,
    /**
     * Write-ahead journal (spec 24 durability, G-25, M10.1): every accepted
     * data invocation is appended as a wire frame before staging — a journal
     * is a bridge to disk. Null = volatile host (default, pre-M10 behavior).
     * Recovery: rebuild the graph (spawn the same cells), then [recoverFrom].
     *
     * This is the whole-host convenience form: it maps to the degenerate
     * constant [journalFor] selector that returns this same journal for every
     * cell. Prefer [journalFor] when durability is per-cell (CP-C1).
     */
    private val journal: Journal? = null,
    /**
     * Per-cell journal selector (CP-C1): durability is a **per-cell** concern.
     * For each cell, the selector names the write-ahead [Journal] its accepted
     * invocations (and [Effectful] processed-frontier advances) tee to, or
     * `null` to make that cell **volatile** — never journaled, never replayed.
     * The whole-host [journal] above is the degenerate case: a constant
     * selector returning the one journal for every cell, byte-identical to
     * pre-CP-C1 behavior. When omitted, the selector derives from [journal].
     */
    private val journalFor: ((CellRef) -> Journal?)? = null,
    /** Opt-in data intake bound; management invocations remain exempt. */
    private val intakeBound: IntakeBound? = null,
    /**
     * Host-configured hop bound (spec 20/22 §MessageContext, 21 §Cycles hop
     * guard, 93 I-5): a data invocation whose [civictech.cell.MessageContext.hop]
     * exceeds this is dead-lettered as a [civictech.cell.port.CycleError]
     * instead of staged — the backstop for headless loops and cross-host
     * cycles no link-time check can see. In a correctly-headed graph it never
     * fires.
     */
    private val hopBound: Int = 64,
) : Host {

    /** Parent/child host relations (G-28): recorded when a host spawns a host. */
    internal var parentHost: ManagedHost? = null
        private set
    private val childHosts = mutableListOf<ManagedHost>()

    /**
     * The effective per-cell journal selector (CP-C1). Explicit [journalFor]
     * wins; otherwise the whole-host [journal] becomes the constant selector
     * (returning it — possibly null — for every cell), preserving pre-CP-C1
     * behavior exactly.
     */
    private val journalSelector: (CellRef) -> Journal? = journalFor ?: { journal }

    internal fun subtreeCellCount(): Int = cells.size + childHosts.sumOf { it.subtreeCellCount() }
    override val managementInlet = registerPort("managementInlet", FanInlet.create<HostManagementApi>())
    override val routerInlet = registerPort("routerInlet", FanInlet.create<HostRoutingApi>())

    /** Failed/undeliverable invocations are published here instead of being dropped (G-26). */
    val deadLetterOutlet = registerPort("deadLetterOutlet", FanOutlet.create<Propagate<DeadLetter>>())

    private val scheduler: HostScheduler = scheduler ?: VirtualThreadScheduler("ManagedHost-${ref.id}")

    /** The concurrency color of this host's execution context (spec 32). */
    val color: HostColor get() = scheduler.color

    private val cells = mutableMapOf<CellRef, Cell>()

    /** `spawnBound`'s recorded `parent` association (93 I-21 §4.3): bookkeeping only —
     * membrane/exposure enforcement over this is G-9, unbuilt. */
    private val cellParents = mutableMapOf<CellRef, CellRef>()
    private val ctx = object : CellContext {
        // CycleHead fusion barrier (spec 21 §Fusion, 93 I-6): route
        // re-origination through the real host queue instead of the default
        // inline call.
        override fun enqueueBarrier(block: () -> Unit) {
            enqueue(20) { block() }
        }
    }

    /**
     * Closable intake (spec 33, G-5): while closed, data and router sends fail
     * fast with [IntakeClosedException] — the sender's re-resolution signal.
     * Management stays open (a closed host must remain administrable).
     */
    @Volatile
    private var intakeState = IntakeState.OPEN
    private var saturationOrigin: Pair<CellRef, String>? = null

    private val lowWaterListeners = mutableListOf<() -> Unit>()

    val currentIntakeState: IntakeState get() = intakeState

    internal fun onIntakeAvailable(listener: () -> Unit) {
        val runNow = synchronized(dataLock) {
            if (intakeState == IntakeState.SATURATED) {
                lowWaterListeners += listener
                false
            } else true
        }
        if (runNow) listener()
    }

    internal fun closeIntake() {
        intakeState = IntakeState.CLOSED
    }

    internal fun openIntake() {
        intakeState = IntakeState.OPEN
    }

    private enum class State { RUNNING, DRAINING, DRAINED }

    private var state = State.RUNNING

    /** Snapshots captured by the last drain (spec 33 step 3; starts G-25). */
    private val snapshots = mutableMapOf<CellRef, Serializable>()

    /**
     * Processed-frontier (G-59, fixes C-9; spec 20/24, 30/31, 50/52): per
     * [Effectful] inlet `(cellRef, portName)`, the last applied `Timestamp`
     * per source — durable via [FrontierRecord]/[CheckpointRecord] so both
     * journal replay and post-recovery live re-delivery dedupe an
     * already-acted invocation instead of re-firing it.
     */
    private val processedFrontier = mutableMapOf<Pair<CellRef, String>, MutableMap<UUID, Long>>()

    /** Supervision (G-26): per-cell failure policies, spawn-time checkpoints, and suspended-cell parking. */
    private val policies = mutableMapOf<CellRef, SupervisionPolicy>()
    private val checkpoints = mutableMapOf<CellRef, Serializable>()
    private val suspendedCells = mutableMapOf<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Host-held per-instance recovery generation (spec 00/03 glossary
     * "Generation"; 93 I-22 R1): never on the wire, outside the `Stateful`
     * checkpoint, bumped on every RESTART *before* reactivation so a
     * checkpoint restore can never roll it back. Its only observable role is
     * seeding the dead-lane `supersedes` set on the RESTART re-baseline.
     */
    private val generations = mutableMapOf<CellRef, Long>()

    /** This instance's current recovery generation (0 = never restarted). */
    fun generationOf(ref: CellRef): Long = generations[ref] ?: 0L

    /** Park/crash accounting (G-46): observability for exclusive payloads off the happy path. */
    private val deadLetterCount = AtomicLong()
    private val parkedDrainedOnTeardownCount = AtomicLong()
    private val restartCount = AtomicLong()

    /** Snapshot of this host's supervision counters (G-46). */
    fun supervisionAccounting(): SupervisionAccounting = SupervisionAccounting(
        deadLetters = deadLetterCount.get(),
        parkedDrainedOnTeardown = parkedDrainedOnTeardownCount.get(),
        restarts = restartCount.get(),
    )

    /**
     * Data plane (spec 34, M6.3): messages stage in per-cell FIFO queues; each
     * staged message submits one dispatcher task at data priority, and each
     * dispatch picks the next cell by attention band. Per-cell FIFO (a superset
     * of per-link FIFO, spec 31 rule 3) holds because band selection happens
     * BETWEEN cells, never within one — and the one-task-per-message shape
     * keeps drain's phase 2 (priority 30) behind every accepted message.
     */
    private val dataLock = Any()
    private val dataQueues = LinkedHashMap<CellRef, ArrayDeque<Pair<Long, HostedPortInvocation>>>()
    private var dataSequence = 0L
    private var dispatchStep = 0L
    private var strideCount = 0
    private val lastAttended = mutableMapOf<CellRef, Long>()

    /** Attention-parked traffic (spec 34 decision 2): parked, never dropped. */
    private val attentionParked = mutableMapOf<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Magnitude-band boost (spec 34, M17): per cell, the band its largest
     * staged [Magnitude] payload maps to under the policy's `magnitudeBands`.
     * Lifetime is the pending queue — cleared when it drains (or parks), so a
     * despawned cell's entry drains out through ordinary dead-letter dispatch.
     */
    private val magnitudeBoost = mutableMapOf<CellRef, AttentionBand>()

    /** Supervision state is per-host: on despawn/migrate it clears, and parked traffic dead-letters rather than vanishing. */
    private fun clearSupervision(cellRef: CellRef) {
        policies.remove(cellRef)
        checkpoints.remove(cellRef)
        generations.remove(cellRef)
        suspendedCells.remove(cellRef)?.forEach {
            parkedDrainedOnTeardownCount.incrementAndGet()
            deadLetter(null, "cell $cellRef left the host while suspended", it)
        }
        synchronized(dataLock) { attentionParked.remove(cellRef) }?.forEach {
            parkedDrainedOnTeardownCount.incrementAndGet()
            deadLetter(null, "cell $cellRef left the host while attention-parked", it)
        }
    }

    private fun roundTrip(state: Serializable): Serializable {
        val bytes = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(state) } }
            .toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as Serializable
    }

    /**
     * Two-phase drain: phase 1 (management, priority 0) closes the intake at
     * once; phase 2 runs at priority 30 — BELOW data's 20, so every accepted
     * invocation flushes first (no priority inversion) — then deactivates
     * cells and captures snapshots. G-16's ordering remainder: deactivation
     * provably follows the drained queue.
     */
    private fun beginDrain(andThen: () -> Unit = {}) {
        require(state == State.RUNNING) { "drain requires a RUNNING host (was $state)" }
        state = State.DRAINING
        closeIntake()
        enqueue(30) {
            // attention-parked traffic is accepted work: flush it before
            // deactivation, same guarantee as the ordinary queue (spec 33/34)
            val parked = synchronized(dataLock) {
                attentionParked.values.flatten().also { attentionParked.clear() }
            }
            parked.forEach { deliver(it) }
            snapshots.clear()
            cells.forEach { (cellRef, cell) ->
                cell.onDeactivate(ctx)
                if (cell is Stateful) snapshots[cellRef] = cell.snapshot()
            }
            state = State.DRAINED
            andThen()
        }
    }

    private fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) {
        System.err.println("[ManagedHost ${ref.id}] dead letter: $description" + (cause?.let { " ($it)" } ?: ""))
        deadLetterCount.incrementAndGet()
        deadLetterOutlet.call.propagate(DeadLetter(ref, cause, description, invocation?.let(::sanitizeForDeadLetter)))
    }

    /**
     * Dead-letter capture applies the boundary rules (spec 23 R8, G-46): the
     * dead-letter outlet is a fan-out, so a live [Owned]/[Leased] reference
     * MUST NOT enter it. `Owned` degenerates to move-by-serialize — frozen,
     * exactly as at the bridge egress — and `Leased` is released and stands
     * in as a [Redacted] marker; the outlet only ever fans `Frozen`/redacted/
     * ordinary values, never a live exclusive handle. A wrapper the failing
     * invocation had already taken/released before throwing has nothing left
     * to capture; it is redacted with no value rather than crashing capture.
     */
    private fun sanitizeForDeadLetter(hostedInvocation: HostedPortInvocation): HostedPortInvocation {
        val args = hostedInvocation.invocation.args
        if (args.none { it is Owned<*> || it is Leased<*> }) return hostedInvocation
        val sanitized = args.map { arg ->
            when (arg) {
                is Owned<*> -> runCatching { arg.freeze() }
                    .getOrElse { Redacted("Owned payload already consumed before capture") }
                is Leased<*> -> Redacted("Leased payload released at dead-letter capture")
                    .also { runCatching { arg.release() } }
                else -> arg
            }
        }
        return hostedInvocation.copy(invocation = hostedInvocation.invocation.copy(args = sanitized))
    }

    private fun enqueue(priority: Int, action: suspend () -> Any?) {
        scheduler.submit(priority) {
            try {
                action()
            } catch (e: Exception) {
                deadLetter(e, "invocation failed: $e")
            }
        }
    }

    private fun <T> enqueueAwaiting(priority: Int, action: suspend () -> T): T {
        val future = CompletableFuture<T>()
        scheduler.submit(priority) {
            try {
                future.complete(action())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return scheduler.await(future)
    }

    /** Suppresses journaling while [recoverFrom] replays — replay must not re-journal itself. */
    @Volatile
    private var recovering = false

    open fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
        if (hostedInvocation.type == HostedPortInvocation.Type.PORT_PROTOCOL) {
            require(hostedInvocation.invocation.context == null) { "protocol invocations must carry null MessageContext" }
            val id = requireNotNull(hostedInvocation.protocolId) { "PORT_PROTOCOL requires protocolId" }
            val descriptor = requireNotNull(ProtocolRegistry.protocol(id.name)) { "unknown protocol ${id.name}" }
            // The metadata plane remains available while data intake is closed or
            // saturated and uses the protocol's scheduler band, not data staging.
            scheduler.submit(descriptor.band) { deliver(hostedInvocation) }
            return
        }
        val isManagement = hostedInvocation.type == HostedPortInvocation.Type.PORT_MANAGEMENT
        if (!isManagement) {
            // Hop guard (spec 20/22 §MessageContext, 21 §Cycles, 93 I-5): the
            // backstop for headless loops and cross-host cycles no link-time
            // check can see. A correctly-headed graph never trips this — hop
            // resets to 0 at every CycleHead re-origination.
            val hop = hostedInvocation.invocation.context?.hop
            if (hop != null && hop > hopBound) {
                deadLetter(
                    CycleError("hop bound $hopBound exceeded (hop=$hop) — undeclared or cross-host cycle (spec 21 §Cycles)"),
                    "cycle hop guard tripped",
                    hostedInvocation,
                )
                return
            }
        }
        if (!isManagement && intakeState == IntakeState.CLOSED) throw IntakeClosedException(ref)
        if (!isManagement) {
            synchronized(dataLock) {
                if (intakeState == IntakeState.SATURATED) {
                    if (intakeBound?.policy == SaturationPolicy.Coalesce && coalesce(hostedInvocation)) {
                        // Coalescing is acceptance, not loss: retain every original
                        // in the WAL so recovery may replay the equivalent sequence.
                        if (!recovering) journalSelector(hostedInvocation.cellRef)?.append(journalFrame(hostedInvocation))
                        return
                    }
                    throw IntakeSaturatedException(ref)
                }
            }
        }
        // write-ahead (M10.1): the intake is the single funnel, so journal
        // order = acceptance order = per-cell FIFO on replay. The tee is
        // per-cell (CP-C1): a volatile cell's selector returns null and it is
        // never written.
        if (!recovering) journalSelector(hostedInvocation.cellRef)?.append(journalFrame(hostedInvocation))
        // stage at SEND time (not dispatch time) so a backlog can form and band
        // selection has something to choose between; one dispatcher task per
        // message keeps message count <= task count (a task may find nothing)
        synchronized(dataLock) {
            stage(hostedInvocation)
            intakeBound?.let { bound ->
                if (!isManagement && dataQueuedCount() >= bound.highWater) {
                    if (intakeState != IntakeState.SATURATED) {
                        intakeState = IntakeState.SATURATED
                        saturationOrigin = hostedInvocation.cellRef to hostedInvocation.portName
                        announceSaturation(true)
                    }
                }
            }
        }
        enqueue(20) { dispatchOne() }
    }

    private fun dataQueuedCount(): Int = dataQueues.values.sumOf { queue ->
        queue.count { (_, invocation) -> invocation.type != HostedPortInvocation.Type.PORT_MANAGEMENT }
    }

    /** Caller holds dataLock. Same source+wave slot preserves wave identity and source FIFO. */
    private fun coalesce(incoming: HostedPortInvocation): Boolean {
        val payload = incoming.invocation.args.singleOrNull() as? MergeablePayload ?: return false
        val context = incoming.invocation.context ?: return false
        val queue = dataQueues[incoming.cellRef] ?: return false
        val index = queue.indexOfLast { (_, old) ->
            old.portName == incoming.portName &&
                old.invocation.methodId == incoming.invocation.methodId &&
                old.invocation.context?.timestamp == context.timestamp &&
                old.invocation.context?.sourcePort == context.sourcePort &&
                old.invocation.args.singleOrNull() is MergeablePayload
        }
        if (index < 0) return false
        val entries = queue.toMutableList()
        val (sequence, old) = entries[index]
        val merged = (old.invocation.args.single() as MergeablePayload).mergeWith(payload)
        entries[index] = sequence to old.copy(invocation = old.invocation.copy(args = listOf(merged)))
        queue.clear()
        queue.addAll(entries)
        return true
    }

    /**
     * Replay this host's [journal] (M10.1): checkpoint records restore
     * `Stateful` state directly; invocation frames re-enter through the
     * ordinary intake (decode = the same path a network frame takes — a
     * journal is a bridge to disk). Call after the graph is rebuilt (cells
     * spawned) and before new traffic; replays are not re-journaled.
     *
     * Per-cell (CP-C1): a journal only ever holds records for the cells whose
     * selector tees to it (the write path is per-cell), so replaying it
     * restores exactly those cells and re-delivers nothing to volatile cells
     * that were never written. Recover each distinct journal once.
     */
    fun recoverFrom(journal: Journal) {
        recovering = true
        try {
            journal.replay().forEach { record ->
                when (record[0]) {
                    RECORD_FRAME -> enqueueHostedInvocation(
                        WireCodec.decode(record.copyOfRange(1, record.size))
                    )

                    RECORD_CHECKPOINT -> restoreCheckpoint(record.copyOfRange(1, record.size))
                    RECORD_FRONTIER -> restoreFrontier(record.copyOfRange(1, record.size))
                    else -> error("unknown journal record type ${record[0]}")
                }
            }
        } finally {
            recovering = false
        }
    }

    private fun journalFrame(hostedInvocation: HostedPortInvocation): ByteArray =
        byteArrayOf(RECORD_FRAME) + WireCodec.encode(hostedInvocation)

    private fun journalFrontier(record: FrontierRecord): ByteArray {
        val blob = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(record) } }
            .toByteArray()
        return byteArrayOf(RECORD_FRONTIER) + blob
    }

    /**
     * Checkpoint (M10.2, extended G-59; keyed per-cell CP-C1): capture the
     * `Stateful` snapshot AND processed-frontier of exactly the cells whose
     * selector tees to THIS [journal], as one record, and compact that journal
     * down to it — replay after a checkpoint is restore + tail. Keying keeps a
     * per-cell journal free of state belonging to another journal (or to a
     * volatile cell). For the degenerate whole-host constant selector every
     * cell maps here, byte-identical to pre-CP-C1. Runs on the management band
     * so it can't interleave with a dispatching cell.
     */
    fun checkpoint(journal: Journal) {
        enqueueAwaiting(0) {
            val state = HashMap<CellRef, Serializable>()
            cells.forEach { (cellRef, cell) ->
                if (cell is Stateful && journalSelector(cellRef) === journal) state[cellRef] = cell.snapshot()
            }
            val frontier = processedFrontier
                .filterKeys { journalSelector(it.first) === journal }
                .mapValues { HashMap(it.value) as Map<UUID, Long> }
            val blob = ByteArrayOutputStream()
                .also { ObjectOutputStream(it).use { out -> out.writeObject(CheckpointRecord(state, frontier)) } }
                .toByteArray()
            journal.reset(listOf(byteArrayOf(RECORD_CHECKPOINT) + blob))
        }
    }

    private fun restoreCheckpoint(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as CheckpointRecord
        record.state.forEach { (cellRef, snapshot) ->
            (cells[cellRef] as? Stateful)?.restore(snapshot)
                ?: deadLetter(null, "checkpoint state for $cellRef but no Stateful cell — graph rebuilt differently?")
        }
        record.frontier.forEach { (key, sources) -> processedFrontier.getOrPut(key) { mutableMapOf() } += sources }
    }

    private fun restoreFrontier(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as FrontierRecord
        advanceFrontier(record.cellRef, record.portName, record.timestamp)
    }

    /**
     * Effectful processed-frontier (G-59, fixes C-9): true iff [timestamp]
     * (from a specific source) is at or behind the last applied counter for
     * this `(cellRef, portName)` — an effect-boundary replay to suppress.
     */
    private fun alreadyProcessed(cellRef: CellRef, portName: String, timestamp: Timestamp): Boolean =
        (processedFrontier[cellRef to portName]?.get(timestamp.sourceId) ?: -1L) >= timestamp.counter

    /** Advances the frontier in memory. Callers decide whether to also durably journal it. */
    private fun advanceFrontier(cellRef: CellRef, portName: String, timestamp: Timestamp) {
        processedFrontier.getOrPut(cellRef to portName) { mutableMapOf() }[timestamp.sourceId] = timestamp.counter
    }

    /** Callers hold [dataLock]: append to the target cell's FIFO (or its attention park). */
    private fun stage(hostedInvocation: HostedPortInvocation) {
        val cellRef = hostedInvocation.cellRef
        attentionParked[cellRef]?.let {
            it += hostedInvocation // parked cells accumulate in arrival order
            return // no boost: magnitude is urgency, interest owns park/resume
        }
        dataQueues.getOrPut(cellRef) { ArrayDeque() }.addLast(++dataSequence to hostedInvocation)
        attention?.magnitudeBands?.let { bands ->
            hostedInvocation.invocation.args.filterIsInstance<Magnitude>()
                .maxOfOrNull { it.size() }
                ?.let { magnitudeBoost.merge(cellRef, bands(it), ::maxOf) }
        }
    }

    private fun bandOf(cellRef: CellRef): AttentionBand = when {
        attention == null -> AttentionBand.NORMAL
        else -> cells[cellRef]?.let { AttentionSupport.of(it).band } ?: AttentionBand.NORMAL
    }

    /**
     * Run (at most) one staged message: highest band first, oldest head as
     * tiebreaker; the stride floor (spec 34 decision 2) bounds how long
     * lower-band work can be passed over; a cell at NONE past the policy
     * window parks instead of running (park, never drop).
     */
    private suspend fun dispatchOne() {
        var toPark: Set<CellRef>? = null
        var listeners: List<() -> Unit> = emptyList()
        val next: HostedPortInvocation? = synchronized(dataLock) {
            if (dataQueues.isEmpty()) return
            dispatchStep++
            // effective band = interest ⊔ staged urgency (spec 34, M17): the
            // boost is a data-region sub-priority — it can never lift a task
            // above the router/management bands, and the stride floor below
            // bounds what it can starve
            val bands = dataQueues.keys.associateWith {
                maxOf(bandOf(it), magnitudeBoost[it] ?: AttentionBand.NONE)
            }
            bands.forEach { (cellRef, band) ->
                if (band > AttentionBand.NONE) lastAttended[cellRef] = dispatchStep
            }
            val maxBand = bands.values.max()
            val lower = bands.filterValues { it < maxBand }.keys
            val stride = attention?.stride ?: Int.MAX_VALUE
            val pickFrom: Collection<CellRef> = if (strideCount >= stride && lower.isNotEmpty()) {
                strideCount = 0
                lower
            } else {
                if (lower.isNotEmpty()) strideCount++ else strideCount = 0
                bands.filterValues { it == maxBand }.keys
            }
            val pick = pickFrom.minByOrNull { dataQueues.getValue(it).first().first }!!
            val suspendAfter = attention?.suspendAfter
            val region = if (suspendAfter != null && bands.getValue(pick) == AttentionBand.NONE &&
                dispatchStep - (lastAttended[pick] ?: 0L) > suspendAfter
            ) suspensionRegionOf(pick) else null
            if (region != null) {
                toPark = region
                null
            } else {
                // runnable — or its region vetoed suspension (spec 34 decision 3):
                // a vetoed cell keeps running, it does not strand its queue
                val queue = dataQueues.getValue(pick)
                val head = queue.removeFirst().second
                if (queue.isEmpty()) {
                    dataQueues.remove(pick)
                    magnitudeBoost.remove(pick) // boost lifetime = the pending queue
                }
                intakeBound?.let { bound ->
                    if (intakeState == IntakeState.SATURATED && dataQueuedCount() <= bound.lowWater) {
                        intakeState = IntakeState.OPEN
                        announceSaturation(false)
                        saturationOrigin = null
                        listeners = lowWaterListeners.toList()
                        lowWaterListeners.clear()
                    }
                }
                head
            }
        }
        listeners.forEach { it() }
        toPark?.forEach { parkForAttention(it) }
        next?.let { deliver(it) }
    }

    /** Emits the host intake state on every inbound data edge; G-36 relays it producer-ward. */
    private fun announceSaturation(asserted: Boolean) {
        val (cellRef, portName) = saturationOrigin ?: return
        val port = cells[cellRef]?.let { PortRegistry.of(it)[portName] } as? Linked ?: return
        port.linking.links.forEach { link ->
            if (link.toPort === port) {
                Protocols.sendUpstream(
                    link,
                    Protocols.Saturation,
                    SaturationSignal((port as Port).ref, asserted),
                )
            }
        }
    }

    /**
     * Session delta 3 (spec 34 decision 3): the unit of attention suspension
     * is the **glitch-free region** — the local downstream `GlitchFreeCell`
     * join(s) plus their transitive local upstream contributors, bounded by
     * further glitch-free cells (the frontier, spec 22). Parking one diamond
     * branch would stall waves at the join; parking the whole region cannot.
     * Returns null (veto) if any member is [NonSuspendable] or still attended.
     * A cell with no local downstream join is its own region (per-cell parking,
     * as before). Cross-host region members are invisible here by design —
     * remote branches remain the WAIT/DEGRADE fallback's job (GlitchFreeCell).
     */
    private fun suspensionRegionOf(cellRef: CellRef): Set<CellRef>? {
        val joins = mutableSetOf<CellRef>()
        bfs(cellRef, downstream = true) { ref, cell ->
            if (cell is GlitchFreeCell<*>) {
                joins += ref
                false // the join bounds the walk; regions don't chain through it
            } else true
        }
        if (joins.isEmpty()) return setOf(cellRef)
        val region = mutableSetOf<CellRef>()
        joins.forEach { join ->
            region += join
            bfs(join, downstream = false) { ref, cell ->
                if (cell is GlitchFreeCell<*>) false // another region's join: frontier
                else {
                    region += ref
                    true
                }
            }
        }
        val vetoed = region.any { ref ->
            val cell = cells[ref] ?: return@any false
            cell is NonSuspendable || AttentionSupport.of(cell).band > AttentionBand.NONE
        }
        return if (vetoed) null else region
    }

    /**
     * Local link-graph walk from [start] (exclusive). [visit] returns whether
     * to walk past the visited cell; only cells on this host are reachable.
     * Neighbors resolve by link **port object identity** (the same rule
     * AttentionSupport uses) — PortRefs don't reliably carry their cell.
     */
    private fun bfs(start: CellRef, downstream: Boolean, visit: (CellRef, Cell) -> Boolean) {
        val portOwner = HashMap<Port, CellRef>()
        cells.forEach { (ref, cell) ->
            val ports = PortRegistry.of(cell)
            ports.names().forEach { name -> ports[name]?.let { portOwner[it] = ref } }
        }
        val seen = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            val current = cells[frontier.removeFirst()] ?: continue
            val ports = PortRegistry.of(current)
            ports.names().forEach { name ->
                val port = ports[name] as? Linked ?: return@forEach
                port.linking.links.forEach { link ->
                    val outbound = link.fromPort === port
                    if (outbound != downstream) return@forEach
                    val neighborPort = (if (outbound) link.toPort else link.fromPort) ?: return@forEach
                    val neighbor = portOwner[neighborPort] // absent: remote — fallback territory
                        ?.takeIf { it != current.ref && seen.add(it) } ?: return@forEach
                    val cell = cells[neighbor] ?: return@forEach
                    if (visit(neighbor, cell)) frontier.addLast(neighbor)
                }
            }
        }
    }

    private fun parkForAttention(cellRef: CellRef) {
        synchronized(dataLock) {
            val queue = dataQueues.remove(cellRef) ?: ArrayDeque()
            magnitudeBoost.remove(cellRef) // re-staged on unpark replay
            attentionParked[cellRef] = queue.map { it.second }.toMutableList()
        }
        cells[cellRef]?.let { notifyDownstream(it, StallNotice.Stall(StallReason.SUSPENDED)) }
    }

    private fun unparkForAttention(cellRef: CellRef) {
        val parked = synchronized(dataLock) {
            attentionParked.remove(cellRef)?.also { lastAttended[cellRef] = dispatchStep }
        } ?: return
        cells[cellRef]?.let { notifyDownstream(it, StallNotice.Resume) }
        parked.forEach { enqueueHostedInvocation(it) }
    }

    /** spec 34 decision 3, 20/22 (G-40): typed Stall/Resume notices travel downstream, with data. */
    private fun notifyDownstream(cell: Cell, notice: StallNotice) {
        val ports = PortRegistry.of(cell)
        ports.names().forEach { name ->
            val port = ports[name] as? Linked ?: return@forEach
            port.linking.links.forEach { link ->
                if (link.fromPort === port) Protocols.sendDownstream(link, Protocols.Suspension, notice)
            }
        }
    }

    private suspend fun deliver(hostedInvocation: HostedPortInvocation) {
        val cellRef = hostedInvocation.cellRef
        // A supervised cell parks only its data/ordinary management traffic.
        // Metadata protocols remain on the always-open plane: resume and
        // catch-up protocols must not deadlock behind what they unpark.
        if (hostedInvocation.type != HostedPortInvocation.Type.PORT_PROTOCOL) {
            suspendedCells[cellRef]?.let {
                it += hostedInvocation
                return
            }
        }
        val cell = cells[cellRef] ?: return deadLetter(
            null, "unknown cell $cellRef", hostedInvocation
        )
        val port = findPort(cell, hostedInvocation.portName) ?: return deadLetter(
            null, "unknown port '${hostedInvocation.portName}' on $cellRef", hostedInvocation
        )

        try {
            when (hostedInvocation.type) {
                HostedPortInvocation.Type.PORT_PROTOCOL -> {
                    val id = requireNotNull(hostedInvocation.protocolId)
                    val link = requireNotNull(hostedInvocation.protocolLink)
                    // A wire-originated link (G-35 phase B, `WireEdgeLink`)
                    // carries no local Port object yet — decode only knows
                    // ids/refs. Patch in this delivery's real local endpoint
                    // so identity-gated handlers (Attention.wire(),
                    // GlitchFreeCell) see the same port they'd see from an
                    // in-process link, while the rest (protocolBridge,
                    // capabilities) still comes from the wire-constructed
                    // link for further hops. Other Link implementations
                    // (in-process, including hand-built test doubles) pass
                    // through unchanged.
                    val directed = if (link !is civictech.cell.wire.WireEdgeLink || link.fromPort === port || link.toPort === port) {
                        link
                    } else {
                        val descriptor = ProtocolRegistry.protocol(id.name)
                        DirectedProtocolLink(link, port, localIsFrom = descriptor?.direction == civictech.gen.wire.ProtocolDirection.UPSTREAM)
                    }
                    ProtocolSupport.of(port).deliver(id, directed, hostedInvocation.protocolMessage as Any)
                }

                HostedPortInvocation.Type.PORT_MANAGEMENT -> {
                    // the transport identity of the delivery is ambient for the
                    // handshake running inside (G-29 phase 1, M8.2)
                    val result = CurrentPeer.with(hostedInvocation.peer) {
                        hostedInvocation.invocation.invoke(port)
                    }
                    if (result is LinkResult.Rejected) {
                        // proxy-initiated handshakes are fire-and-forget until the
                        // wire layer (M5); rejection is observable here only
                        deadLetter(null, "link rejected: ${result.reason}", hostedInvocation)
                    }
                }

                HostedPortInvocation.Type.PORT_API -> {
                    if (port is Use<*>) {
                        // Effectful processed-frontier (G-59, fixes C-9): an
                        // effect-boundary inlet's replay (journal replay or a
                        // post-recovery live re-delivery) at/behind the last
                        // applied (sourceId, counter) is suppressed-emission —
                        // the sink already acted on it, so it does not act again.
                        val timestamp = hostedInvocation.invocation.context?.timestamp
                        if (cell is Effectful && timestamp != null &&
                            alreadyProcessed(cellRef, hostedInvocation.portName, timestamp)
                        ) {
                            // suppressed: already-acted, dropped rather than re-acted
                        } else {
                            // suspend-aware: a 🟣 target's suspend fun may park this task (spec 32)
                            hostedInvocation.invocation.invokeSuspending(port.call)
                            if (cell is Effectful && timestamp != null) {
                                advanceFrontier(cellRef, hostedInvocation.portName, timestamp)
                                // per-cell tee (CP-C1): the frontier advance rides the same
                                // journal as this cell's frames — volatile cells (null) skip it
                                journalSelector(cellRef)?.append(journalFrontier(FrontierRecord(cellRef, hostedInvocation.portName, timestamp)))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // every policy dead-letters — observability is not a policy (G-26)
            deadLetter(e, "invocation failed: $e", hostedInvocation)
            // a declared error outlet additionally receives the failure as data
            (cell as? ErrorReporting)?.errorOutlet?.call?.propagate(CellError(cellRef, e, hostedInvocation))
            when (policies[cellRef] ?: SupervisionPolicy.PROPAGATE) {
                SupervisionPolicy.PROPAGATE ->
                    // 30/31 rule 5 (93 I-18): a dead-letter on a glitch-free frontier
                    // edge additionally emits Stall(DEAD_LETTERED) — the contribution
                    // for this wave is gone, so the join RE-SCOPEs past it rather than
                    // waiting forever or silently degrading. The wave the failing
                    // invocation was processing rides along so the join can rescue
                    // exactly that wave, not every wave pending on the edge.
                    notifyDownstream(
                        cell,
                        StallNotice.Stall(StallReason.DEAD_LETTERED, hostedInvocation.invocation.context?.timestamp),
                    )
                SupervisionPolicy.RESTART -> {
                    // state-restore, never input-replay (spec 23 R6): the failing
                    // invocation (and any Owned/Leased it carried) is not re-driven
                    notifyDownstream(cell, StallNotice.Stall(StallReason.RESTARTING))
                    restartCount.incrementAndGet()
                    // R1 (93 I-22): bump the host-held generation before reactivation —
                    // a checkpoint restore can never roll it back
                    generations[cellRef] = generationOf(cellRef) + 1
                    cell.onDeactivate(ctx)
                    // R1/S1 (spec 20/22, 93 I-14): fresh emission epoch per outlet —
                    // post-restart tags and waves alias nothing pre-crash. Collect the
                    // superseded source ids to seed the ReBaseline.supersedes list.
                    val supersedes = PortRegistry.of(cell).names().mapNotNull { name ->
                        when (val port = PortRegistry.of(cell)[name]) {
                            is FanOutlet<*> -> port.mintFreshEpoch()
                            is Outlet<*> -> port.mintFreshEpoch()
                            else -> null
                        }
                    }.toSet()
                    cell.onActivate(ctx)
                    // R3 (93 I-22): restore-the-freshest-available checkpoint — the
                    // spawn-time local checkpoint is the degenerate non-durable case
                    checkpoints[cellRef]?.let { (cell as Stateful).restore(roundTrip(it)) }
                    // R2/R4 (93 I-22): RESTART completes with a re-baseline over the
                    // ordinary catch-up path — push-authoritative for a single-writer root
                    if (cell is ReBaselineEmitting) cell.reBaseline(supersedes, supersede = true)
                    notifyDownstream(cell, StallNotice.Resume)
                }
                SupervisionPolicy.SUSPEND -> {
                    suspendedCells[cellRef] = mutableListOf()
                    notifyDownstream(cell, StallNotice.Stall(StallReason.SUSPENDED))
                }
            }
        }
    }

    /**
     * Returns a managed reference to the API of a hosted cell.
     */
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
        if (!cells.containsKey(ref)) {
            // remote-backed proxy when the ref lives across the wire (M5.4, spec 41):
            // sends route through the bridge, parking/replaying like any registry send.
            // Local refs on *other* hosts still answer null — that host's lookup owns them.
            @Suppress("UNCHECKED_CAST")
            return registry?.takeIf { it.location(ref) is LocationRegistry.Remote }
                ?.let { HostedCellProxy.create(ref, it, clazz) } as T?
        }
        @Suppress("UNCHECKED_CAST")
        // with a registry the proxy re-resolves, surviving relocation (spec 33)
        return (registry?.let { HostedCellProxy.create(ref, it, clazz) }
            ?: HostedCellProxy.create(ref, this, clazz)) as T
    }

    inline fun <reified T : Any> lookup(ref: CellRef): T? = lookup(ref, T::class.java)

    init {
        val internalApi = object : HostManagementApi {
            override fun spawn(cell: Cell): CellRef {
                require(!cells.containsKey(cell.ref)) { "Cell already spawned: ${cell.ref}" }
                // quota walks every ancestor (G-28): a sandboxed subtree cannot
                // grow past any enclosing budget
                var scope: ManagedHost? = this@ManagedHost
                while (scope != null) {
                    scope.quota?.let { limit ->
                        check(scope!!.subtreeCellCount() < limit) {
                            "quota exceeded: host ${scope!!.ref} allows $limit cells in its subtree (G-28)"
                        }
                    }
                    scope = scope.parentHost
                }
                // color validation (spec 32, G-3): 🔵/🟣 markers must match the host; unmarked = 🟢 pure
                when (color) {
                    HostColor.BLOCKING -> require(cell !is SuspendingCell) {
                        "SuspendingCell ${cell.ref} cannot spawn on a BLOCKING host"
                    }
                    HostColor.SUSPENDING -> require(cell !is BlockingCell) {
                        "BlockingCell ${cell.ref} cannot spawn on a SUSPENDING host"
                    }
                }
                if (cell is ManagedHost) {
                    // hosts hosting hosts (31): record the relation for quota
                    // walking and shutdown cascade
                    childHosts += cell
                    cell.parentHost = this@ManagedHost
                }
                cells[cell.ref] = cell
                ProtocolSupport.bind(cell)
                PortRegistry.of(cell).names().forEach { name ->
                    PortRegistry.of(cell)[name]?.let { port ->
                        ProtocolSupport.of(port).relay(Protocols.Saturation)
                        // CycleHead fusion barrier (spec 21 §Fusion, 93 I-6):
                        // route every FeedbackInlet's re-origination through
                        // this host's real queue, port-generic, no cell-
                        // specific wiring needed (mirrors AttentionSupport).
                        if (port is FeedbackInlet<*>) port.barrier = { ctx.enqueueBarrier(it) }
                    }
                }
                // generated descriptors are authoritative: if the processor saw
                // this cell's ports, every declared port must be registered under
                // its property name — the KSP-unlintable half of G-17, enforced
                // here. Subset check: dynamic extra ports stay legal.
                civictech.gen.wire.ContractRegistry.cellDescriptor(cell.javaClass)
                    ?.ports?.takeIf { it.isNotEmpty() }?.let { declared ->
                        val registered = PortRegistry.of(cell).names()
                        val missing = declared.map { it.name }.filterNot { it in registered }
                        require(missing.isEmpty()) {
                            "cell ${cell.javaClass.name}: descriptor declares ports $missing not found in " +
                                "registry $registered — registerPort's name must equal the property name (G-17)"
                        }
                    }
                cell.onActivate(ctx)
                // spawn-time checkpoint: what a RESTART supervision restores (G-26)
                if (cell is Stateful) checkpoints[cell.ref] = cell.snapshot()
                if (attention != null) {
                    // renewed interest resumes a parked cell (spec 34): the listener
                    // may fire on any thread, so hop through the management band
                    AttentionSupport.of(cell).onBandChange { band ->
                        if (band > AttentionBand.NONE) enqueue(0) { unparkForAttention(cell.ref) }
                    }
                    // scheduling-step clock for time-aware aggregators (decay);
                    // racy read is fine — attention is advisory metadata (34)
                    AttentionSupport.of(cell).ticks = { dispatchStep }
                }
                // location becomes visible only after activation, so replayed
                // parked invocations find served ports (spec 33 step 7)
                registry?.publish(cell.ref, this@ManagedHost)
                return cell.ref
            }

            override fun spawnBound(factory: CellFactory, identity: IdentityBinding, parent: CellRef?): CellRef {
                // organelle nesting (G-28's parent field, 93 I-21 §4.3): recorded
                // for introspection; membrane/exposure enforcement is G-9 (unbuilt,
                // out of this ticket's scope) — a non-null parent is bookkept only.
                val ref = identity.resolve()
                val cell = factory.create(ref)
                requireBoundRef("spawnBound", identity, ref, cell.ref)
                return try {
                    spawn(cell).also { spawnedRef -> if (parent != null) cellParents[spawnedRef] = parent }
                } catch (e: Exception) {
                    // G-51: per-step rejections surface as dead letters on the
                    // target host — this is what makes re-applying an `Exact`
                    // spawn of a live ref an *idempotent, observed* no-op rather
                    // than a silent drop or a crash of the whole apply.
                    deadLetter(e, "spawnBound rejected: $ref (${e.message})")
                    throw e
                }
            }

            override fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
                return this@ManagedHost.lookup(ref, clazz)
            }

            override fun despawn(ref: CellRef) {
                val cell = cells.remove(ref) ?: throw IllegalArgumentException("Cell not found: $ref")
                registry?.unpublish(ref)
                clearSupervision(ref)
                cell.onDeactivate(ctx)
            }

            override fun supervise(ref: CellRef, policy: SupervisionPolicy) {
                require(cells.containsKey(ref)) { "Cell not found: $ref" }
                policies[ref] = policy
            }

            override fun resume(ref: CellRef) {
                val parked = suspendedCells.remove(ref)
                    ?: throw IllegalArgumentException("Cell not suspended: $ref")
                cells[ref]?.let { notifyDownstream(it, StallNotice.Resume) }
                // re-enqueue at data priority: replay order = park order (sequence tiebreaker)
                parked.forEach { this@ManagedHost.enqueueHostedInvocation(it) }
            }

            override fun suspend(ref: CellRef) {
                require(cells.containsKey(ref)) { "Cell not found: $ref" }
                if (ref !in suspendedCells) {
                    suspendedCells[ref] = mutableListOf()
                    cells[ref]?.let { notifyDownstream(it, StallNotice.Stall(StallReason.SUSPENDED)) }
                }
            }

            override fun drainHost() {
                // shutdown cascade (G-28, M8.1): children drain first — a child
                // must not outlive (or keep accepting after) its parent
                childHosts.forEach { it.managementInlet.call.drainHost() }
                beginDrain()
            }

            override fun resumeHost() {
                require(state == State.DRAINED) { "resume requires a DRAINED host (was $state)" }
                cells.values.forEach { it.onActivate(ctx) }
                openIntake()
                // republish only after the intake reopens: replay enqueues here (spec 33 step 7)
                cells.keys.forEach { registry?.publish(it, this@ManagedHost) }
                state = State.RUNNING
            }

            override fun migrate(to: Use<HostManagementApi>) = beginDrain {
                val moving = cells.toList()
                cells.clear()
                moving.forEach { (cellRef, cell) ->
                    registry?.unpublish(cellRef)
                    // supervision is per-host and does not migrate (31)
                    clearSupervision(cellRef)
                    // the serialization seam is exercised even in-process (G-25):
                    // restore from a round-tripped snapshot, not the live object
                    snapshots[cellRef]?.let { (cell as Stateful).restore(roundTrip(it)) }
                    // target spawn activates, publishes, and replays parked traffic
                    to.call.spawn(cell)
                }
                snapshots.clear()
            }

            override fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult {
                val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
                val toCell = cells[to] ?: throw IllegalArgumentException("Target cell not found: $to")

                val outlet = findPort(fromCell, outletName) as? LinkTo<*>
                    ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")
                val inlet = findPort(toCell, inletName) as? LinkFrom<*>
                    ?: throw IllegalArgumentException("Inlet not found or not linkable: $inletName on $to")

                // Cycle admission (spec 10/13 `CycleWithoutHead`, 20/21 §Cycles,
                // 93 I-5): a connect that would close a cycle wholly visible to
                // this host's topology index is rejected unless the closing
                // inlet is a declared CycleHead (a FeedbackInlet). Cross-host
                // cycles are not locally visible here; they fall to the runtime
                // hop guard (20/22) instead.
                registry?.topology?.let { topology ->
                    if (inlet !is FeedbackInlet<*> && wouldCloseCycle(topology, from, to)) {
                        return LinkResult.Rejected(
                            "CycleWithoutHead: connecting $from.$outletName -> $to.$inletName would close a " +
                                "locally-visible cycle with no declared CycleHead (spec 10/13, 20/21 §Cycles)"
                        )
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val result = (outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)
                if (result is LinkResult.Connected) {
                    val edge = TopologyLink(
                        result.link.id,
                        result.link.from.copy(cell = from),
                        result.link.to.copy(cell = to),
                    )
                    registry?.link(edge)
                    result.link.onUnlink { registry?.unlink(it.id) }
                }
                return result
            }

            override fun connect(from: CellRef, outletName: String, to: Use<*>) {
                val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
                val outlet = findPort(fromCell, outletName) as? LinkTo<*>
                    ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")

                @Suppress("UNCHECKED_CAST")
                (outlet as LinkTo<Any>).linkTo(to as Use<Any>)
            }
        }

        val internalHostRoutingApi = object : HostRoutingApi {
            override fun route(target: CellRef, inletName: String, invocation: Invocation) {
                val toCell = cells[target] ?: throw IllegalArgumentException("Target cell not found: $target")
                val inlet = findPort(toCell, inletName) as? Use<*>
                    ?: throw IllegalArgumentException("Inlet not found or not usable: $inletName on $target")

                invocation.invoke(inlet.call)
            }
        }

        managementInlet.serve(Proxy.fromClass(HostManagementApi::class.java) { _, method, args ->
            val invocation = Invocation.of(method, args).withTarget(internalApi)
            if (method.name == "spawn") {
                enqueueAwaiting(0) { internalApi.spawn(args!![0] as Cell) }
            } else if (method.name == "spawnBound") {
                // the wire-crossing form (93 I-21 §4.4): awaited here because
                // there is no real socket boundary to cross in-process, but the
                // caller-facing degrade-to-async behavior lives in
                // GraphSpec.applyRemote, which never lets a rejection here
                // abort the whole apply or surface as a synchronous reply.
                enqueueAwaiting(0) { invocation.invoke() }
            } else if (method.name.startsWith("lookup")) {
                @Suppress("UNCHECKED_CAST")
                enqueueAwaiting(0) { internalApi.lookup(args!![0] as CellRef, args[1] as Class<Any>) }
            } else if (method.name.startsWith("connect")) {
                // surfaces the LinkResult (management calls may await, spec 31 rule 4)
                enqueueAwaiting(0) { invocation.invoke() }
            } else {
                enqueue(0) { invocation.invoke() }
                null
            }
        })

        routerInlet.serve(Proxy.fromClass(HostRoutingApi::class.java) { _, method, args ->
            if (intakeState == IntakeState.CLOSED) throw IntakeClosedException(ref)
            val invocation = Invocation.of(method, args).withTarget(internalHostRoutingApi)
            enqueue(10) { invocation.invoke() }
            null
        })
    }

    private fun findPort(cell: Cell, name: String): Port? = PortRegistry.of(cell)[name]

    /**
     * Routed-handle validation seam ([civictech.cell.proxy.inlet]): resolve a
     * named inlet on a locally-hosted cell to the erased api class a `propagate`
     * send would reach, or a typed reason it cannot. Uses the same private
     * [findPort] the delivery path uses, so a routed handle validates against
     * exactly the port a send lands on. The payload type argument is erased on
     * the port and is deliberately not recoverable here — the wrapper class is.
     */
    fun resolveInlet(ref: CellRef, portName: String): RoutedInletResolution {
        val cell = cells[ref] ?: return RoutedInletResolution.NoCell
        val port = findPort(cell, portName)
            ?: return RoutedInletResolution.NoPort(PortRegistry.of(cell).names())
        if (port !is Use<*>) return RoutedInletResolution.NotUsable
        val apiClass = when (port) {
            is FanInlet<*> -> port.clazz
            is Inlet<*> -> port.clazz
            else -> null // e.g. FeedbackInlet carries no erased api class — skip the wrapper check
        }
        return RoutedInletResolution.Usable(apiClass)
    }

    /**
     * Would a `from -> to` edge close a cycle already visible in [topology]?
     * True exactly when [to] can already reach [from] by following existing
     * outbound edges (spec 10/13 rare-path cycle walk; P2 permits expensive
     * linking).
     */
    private fun wouldCloseCycle(topology: TopologyIndex, from: CellRef, to: CellRef): Boolean {
        if (from == to) return true
        val visited = mutableSetOf<CellRef>()
        val stack = ArrayDeque<CellRef>().apply { add(to) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            if (current == from) return true
            topology.outbound(current).forEach { link -> link.to.cell?.let(stack::add) }
        }
        return false
    }
}

/**
 * Overlays a delivery's real local [Port] onto a wire-reconstructed [base]
 * link (spec 41 point 4, G-35 phase B) so identity-gated protocol handlers
 * (`Attention.wire()`, `GlitchFreeCell`) treat it exactly like an in-process
 * link. Everything else — [protocolBridge]/[protocolCapabilities] for the
 * next hop — still comes from [base].
 */
private class DirectedProtocolLink(
    private val base: Link,
    private val localPort: Port,
    localIsFrom: Boolean,
) : Link by base {
    override val fromPort: Port? = if (localIsFrom) localPort else null
    override val toPort: Port? = if (!localIsFrom) localPort else null
}
