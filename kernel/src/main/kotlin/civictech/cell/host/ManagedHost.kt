package civictech.cell.host



import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.protocol.*
import civictech.cell.BlockingCell
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.SuspendingCell
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionScheduler
import civictech.cell.control.AttentionSupport
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.durability.Journal
import civictech.cell.evolve.Effectful
import civictech.cell.graph.CellFactory
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.requireBoundRef
import civictech.cell.Propagate
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.control.ParkQueue
import civictech.nature.ProtocolRegistry
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

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

    // T04 finding 4: writers are always the scheduler thread, but public
    // readers (lookup, resolveInlet, subtreeCellCount from a *child* host's
    // thread during the quota walk, IntakeControl/HostDurability's
    // cellsView()) are not. A plain HashMap's concurrent resize can
    // false-negative a containsKey or spin; ConcurrentHashMap (already
    // LocationRegistry's choice throughout) makes those reads safe.
    // Weakly-consistent iteration is acceptable here: the quota walk and
    // checkpoint/state-capture paths already run on the scheduler thread.
    private val childHosts = CopyOnWriteArrayList<ManagedHost>()

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

    /**
     * Dead-letter emission (G-26) — extracted to [DeadLetters] (RS-8.3). The
     * only external coupling is [deadLetterOutlet] itself, since a registered
     * port belongs to this host, not to the collaborator.
     */
    // T04 finding 6: emission is routed through the host scheduler instead of
    // running synchronously on the raising thread — DeadLetters.deadLetter's
    // stderr print and counter increment stay synchronous (thread-safe by
    // construction: println and AtomicLong), only the outlet propagate
    // (which mutates subscriber state) is deferred. Before this, the
    // enqueueHostedInvocation hop-bound guard dead-lettered directly on the
    // caller's thread (a WS read thread, in the traced production path),
    // and DeadLetters.emit -> propagate dispatched synchronously into
    // subscribed cells, mutating their state concurrently with the
    // scheduler thread.
    private val deadLetters = DeadLetters(
        hostRef = ref,
        // this.scheduler (not the bare constructor parameter): the scheduler
        // MEMBER is declared below, but this lambda only runs after
        // construction completes, once the member is initialized.
        emit = { dl -> this.scheduler.submit(0) { deadLetterOutlet.call.propagate(dl) } },
    )

    private val scheduler: HostScheduler = scheduler ?: VirtualThreadScheduler("ManagedHost-${ref.id}")

    /** The concurrency color of this host's execution context (spec 32). */
    val color: HostColor get() = scheduler.color

    private val cells = ConcurrentHashMap<CellRef, Cell>()

    /** `spawnBound`'s recorded `parent` association (93 I-21 §4.3): bookkeeping only —
     * membrane/exposure enforcement over this is G-9, unbuilt. */
    private val cellParents = ConcurrentHashMap<CellRef, CellRef>()
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
     * Forwards to [IntakeControl] (RS-8.3).
     */
    val currentIntakeState: IntakeState get() = intakeControl.intakeState

    internal fun onIntakeAvailable(listener: () -> Unit) = intakeControl.onIntakeAvailable(listener)

    internal fun closeIntake() = intakeControl.closeIntake()

    internal fun openIntake() = intakeControl.openIntake()

    private enum class State { RUNNING, DRAINING, DRAINED }

    // @Volatile for the same reason [suspendedCells] became concurrent: every
    // writer is the scheduler thread, but [isDrained] is read from an
    // observer's own thread.
    @Volatile
    private var state = State.RUNNING

    /**
     * Has this host finished draining (spec 33 §Drain, G-16) — intake closed,
     * every accepted invocation flushed, every cell deactivated and
     * snapshotted, awaiting [HostManagementApi.resumeHost]?
     *
     * Read-only introspection, safe from any thread, and deliberately **not**
     * true mid-drain: `DRAINING` is a host that is still flushing work it
     * accepted, so calling it drained would be a lie in both directions — an
     * observer would report a graph parked while it is still computing, and
     * `resumeHost` (which requires `DRAINED`) would be refused. The inspector's
     * cold-graph screen (spec 90/97 M5) is the caller: a drained host's cells
     * stay published — a drain unpublishes nothing — so their structure remains
     * readable as registry metadata while nothing about them runs, which is
     * exactly the state that screen exists to name and to offer to end.
     */
    val isDrained: Boolean get() = state == State.DRAINED

    /** Snapshots captured by the last drain (spec 33 step 3; starts G-25). */
    private val snapshots = mutableMapOf<CellRef, Serializable>()

    /** Supervision (G-26): per-cell failure policies, spawn-time checkpoints, and suspended-cell parking. */
    private val policies = mutableMapOf<CellRef, SupervisionPolicy>()
    private val checkpoints = mutableMapOf<CellRef, Serializable>()

    // T04 finding 4's rationale, applied to the one supervision map an outside
    // reader now consults: every writer is the scheduler thread (supervise /
    // suspend / resume / clearSupervision / deliver), but [isSuspended] is
    // called from an observer's own thread, and a plain HashMap's concurrent
    // resize can false-negative a containsKey or spin.
    private val suspendedCells = ConcurrentHashMap<CellRef, ParkQueue<HostedPortInvocation>>()

    /**
     * Is [ref] currently suspended here — parked by `SupervisionPolicy.SUSPEND`
     * or by an explicit [HostManagementApi.suspend] (spec 34, G-26)? A suspended
     * cell's data and ordinary-management traffic parks until [HostManagementApi.resume];
     * only the always-open metadata plane still reaches it.
     *
     * Read-only introspection, safe from any thread: an outside observer (the
     * inspector's content search, spec 90/97 M5) must be able to *tell* that a
     * cone is parked without touching it, since the whole point is to leave it
     * alone. False for a ref this host does not hold at all.
     */
    fun isSuspended(ref: CellRef): Boolean = suspendedCells.containsKey(ref)

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
    private val parkedDrainedOnTeardownCount = AtomicLong()
    private val restartCount = AtomicLong()

    /**
     * PN-12 — spawn-time consumption of the [civictech.nature.CellDescriptor.manifest]:
     * a `Manifest.DURABLE` cell placed on this host with a journal selector that
     * returns `null` for it is volatile — a previously *silent* durability gap
     * (data lost on restart with nothing to replay from). Counted here so the
     * condition is observable rather than silent. It is deliberately NOT a hard
     * refusal: a durable-capable cell run volatile is a legitimate deployment (the
     * exchange demo's aggregates are exactly that — rebuilt from the replayed
     * writer WAL), so the manifest surfaces the gap without vetoing the spawn.
     */
    private val volatileDurableSpawnCount = AtomicLong()

    /** PN-12: how many `DURABLE`-manifest cells were spawned onto a null journal selector. */
    internal fun volatileDurableSpawns(): Long = volatileDurableSpawnCount.get()

    /** Snapshot of this host's supervision counters (G-46). */
    fun supervisionAccounting(): SupervisionAccounting = SupervisionAccounting(
        deadLetters = deadLetters.deadLetterCount,
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

    /**
     * Attention-driven dispatch (spec 34, M6.3/M17) — per-cell FIFO staging,
     * band selection, the stride floor, magnitude boost, and attention
     * parking — extracted to [civictech.cell.control.AttentionScheduler]
     * (RS-8.1). It shares [dataLock] with this host rather than owning an
     * independent lock: [clearSupervision] and [beginDrain] still touch its
     * [AttentionScheduler.attentionParked] directly inside
     * `synchronized(dataLock)`, and [intakeLowWaterCheck] runs the intake
     * bookkeeping below from inside the scheduler's own dequeue critical
     * section — the same one shared monitor keeps every critical section
     * byte-identical to before the extraction.
     */
    private val attentionScheduler = AttentionScheduler(
        attention = attention,
        dataLock = dataLock,
        bandOf = ::bandOf,
        suspensionRegionOf = { cellRef -> suspensionRegionOf(cells, cellRef) },
        notifyParked = ::notifyParked,
        notifyResumed = ::notifyResumed,
        intakeLowWaterCheck = ::intakeLowWaterCheck,
        deliver = ::deliver,
        submit = ::enqueueHostedInvocation,
    )

    /**
     * WAL/journal/checkpoint/frontier durability (M10.1/M10.2, G-59) —
     * extracted to [HostDurability] (RS-8.2). Shares [journalSelector] (the
     * SAME lambda instance this host also uses for its own two
     * `enqueueHostedInvocation` journal writes and the PN-12 spawn check) so
     * per-cell journal selection stays byte-identical; reads a live view of
     * [cells] and delegates dead-letter reporting and replayed-frame
     * re-intake back to the host. [checkpoint] still runs via
     * [enqueueAwaiting] at management priority 0, unable to interleave with
     * a dispatching cell — nothing here touches [dataLock].
     */
    private val hostDurability = HostDurability(
        journalSelector = journalSelector,
        cellsView = { cells },
        deadLetter = { message -> deadLetter(null, message) },
        submit = ::enqueueHostedInvocation,
        awaitOnManagementBand = { action -> enqueueAwaiting(0, action) },
    )

    /**
     * Intake closed/saturated gating, coalescing, and saturation announce —
     * extracted to [IntakeControl] (RS-8.3). Shares [dataLock] with the host,
     * same as [attentionScheduler]/[hostDurability]; reads
     * [AttentionScheduler]'s queue state via narrow accessors
     * (`attentionScheduler::dataQueuedCount`, a queue-by-ref lookup) rather
     * than holding a reference to the scheduler itself.
     */
    private val intakeControl = IntakeControl(
        dataLock = dataLock,
        intakeBound = intakeBound,
        cellsView = { cells },
        dataQueuedCount = attentionScheduler::dataQueuedCount,
        dataQueueFor = { cellRef -> attentionScheduler.dataQueues[cellRef] },
    )

    /** Supervision state is per-host: on despawn/migrate it clears, and parked traffic dead-letters rather than vanishing. */
    private fun clearSupervision(cellRef: CellRef, cell: Cell) {
        policies.remove(cellRef)
        checkpoints.remove(cellRef)
        generations.remove(cellRef)
        suspendedCells.remove(cellRef)?.drain()?.forEach {
            parkedDrainedOnTeardownCount.incrementAndGet()
            deadLetter(null, "cell $cellRef left the host while suspended", it)
        }
        synchronized(dataLock) { attentionScheduler.attentionParked.remove(cellRef) }?.forEach {
            parkedDrainedOnTeardownCount.incrementAndGet()
            deadLetter(null, "cell $cellRef left the host while attention-parked", it)
        }
        // T05 finding 5: a FanInlet's ACTIVATE-tier cold tail (invocations
        // that arrived before a handler was installed, spec 10/15 §Admission
        // vs activation) used to vanish with the cell object on despawn — no
        // dead letter, no counter, no exclusive discharge. Same accounting
        // path as the two park queues above.
        PortRegistry.of(cell).names().forEach { name ->
            (PortRegistry.of(cell)[name] as? FanInlet<*>)?.drainParked()?.forEach { invocation ->
                parkedDrainedOnTeardownCount.incrementAndGet()
                deadLetter(
                    null,
                    "cell $cellRef left the host with a parked cold-inlet invocation on port '$name'",
                    HostedPortInvocation(cellRef, name, HostedPortInvocation.Type.PORT_API, invocation),
                )
            }
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
                attentionScheduler.attentionParked.values.flatten().also { attentionScheduler.attentionParked.clear() }
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

    /** Forwards to [DeadLetters] (RS-8.3) — kept as a same-named private method so every call site is unchanged. */
    private fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) =
        deadLetters.deadLetter(cause, description, invocation)

    private fun enqueue(priority: Int, action: suspend () -> Any?) {
        scheduler.submit(priority) {
            try {
                action()
            } catch (e: Throwable) {
                // T04 finding 5.3: was `catch (e: Exception)` — a TODO()
                // (NotImplementedError IS an Error), StackOverflowError, or
                // NoClassDefFoundError from a generated proxy escaped this,
                // so supervision/dead-letter accounting never saw it.
                // VirtualMachineError (OOM etc.) stays fatal.
                if (e is VirtualMachineError) throw e
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

    /**
     * Test seam for `DurableGlitchFreeReplayTest`'s control (PN-2); forwards to
     * [HostDurability.replayAsBaseline] (RS-8.2). Production always replays as
     * baseline; see [HostDurability]'s KDoc for the full semantics.
     */
    internal var replayAsBaseline: Boolean
        get() = hostDurability.replayAsBaseline
        set(value) { hostDurability.replayAsBaseline = value }

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
        if (!isManagement && intakeControl.intakeState == IntakeState.CLOSED) throw IntakeClosedException(ref)
        // T05 finding 4: a replayed frame is already-accepted work re-entering
        // through the ordinary intake (a journal is a bridge to disk, per
        // HostDurability's KDoc) — the SATURATED gate must not re-reject it.
        // Pre-fix, a durable host with an intakeBound deterministically
        // aborted recovery once the journal exceeded high-water (nothing
        // drains during the synchronous replay under the sim controller).
        if (!isManagement && !hostDurability.recovering) {
            synchronized(dataLock) {
                if (intakeControl.intakeState == IntakeState.SATURATED) {
                    if (intakeBound?.policy == SaturationPolicy.Coalesce && intakeControl.coalesce(hostedInvocation)) {
                        // Coalescing is acceptance, not loss: retain every original
                        // in the WAL so recovery may replay the equivalent sequence.
                        if (!hostDurability.recovering) journalSelector(hostedInvocation.cellRef)?.append(hostDurability.journalFrame(hostedInvocation))
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
        //
        // T04 finding 2: the append now runs INSIDE the same synchronized
        // block as staging (below), adjacent to it — matching the coalesce
        // branch above, which already appended inside its own dataLock
        // block. Before this, two threads sending to one cell could
        // interleave append/stage so replay re-drove a different order than
        // the live run; journal order = acceptance order is now actually
        // true, not just documented.
        //
        // ponytail: append holds dataLock so journal order == acceptance
        // order; decouple via accept-sequence + async append if fsync
        // contention matters.
        //
        // stage at SEND time (not dispatch time) so a backlog can form and band
        // selection has something to choose between; one dispatcher task per
        // message keeps message count <= task count (a task may find nothing)
        //
        // T04 finding 1: checkSaturationOnAccept returns a deferred announce
        // instead of running Protocols.sendUpstream's relay traversal here —
        // that traversal can reach another host's enqueueHostedInvocation
        // and ITS dataLock, so it must run only after this lock releases.
        val announce = synchronized(dataLock) {
            if (!hostDurability.recovering) journalSelector(hostedInvocation.cellRef)?.append(hostDurability.journalFrame(hostedInvocation))
            attentionScheduler.stage(hostedInvocation)
            intakeControl.checkSaturationOnAccept(hostedInvocation, isManagement)
        }
        announce?.invoke()
        enqueue(20) { attentionScheduler.dispatchOne() }
    }

    /**
     * Runs inside [AttentionScheduler]'s own `dataLock` critical section,
     * right after it dequeues a message (RS-8.1) — preserves the original
     * atomicity of the low-water intake transition, which used to happen
     * inline in the same `synchronized(dataLock)` block as the dequeue. Kept
     * as a same-named private method on the host (RS-8.3) — rather than
     * wiring [AttentionScheduler]'s constructor directly at `intakeControl`
     * — to avoid a construction-order cycle: this callback is captured when
     * [attentionScheduler] is built, before [intakeControl] (which itself
     * needs [attentionScheduler]'s queue accessors) exists.
     */
    private fun intakeLowWaterCheck(): List<() -> Unit> = intakeControl.lowWaterCheck()

    private fun notifyParked(cellRef: CellRef) {
        cells[cellRef]?.let { notifyDownstream(it, StallNotice.Stall(StallReason.SUSPENDED)) }
    }

    private fun notifyResumed(cellRef: CellRef) {
        cells[cellRef]?.let { notifyDownstream(it, StallNotice.Resume) }
    }

    /**
     * Replay this host's [journal] (M10.1). See [HostDurability.recoverFrom]
     * for the full behavior; delegates there (RS-8.2).
     */
    fun recoverFrom(journal: Journal) = hostDurability.recoverFrom(journal)

    /**
     * Checkpoint (M10.2, extended G-59). See [HostDurability.checkpoint] for
     * the full behavior; delegates there (RS-8.2).
     */
    fun checkpoint(journal: Journal) = hostDurability.checkpoint(journal)

    private fun bandOf(cellRef: CellRef): AttentionBand = when {
        attention == null -> AttentionBand.NORMAL
        else -> cells[cellRef]?.let { AttentionSupport.of(it).band } ?: AttentionBand.NORMAL
    }

    // suspensionRegionOf, hasFrontierPolicy, bfs, notifyDownstream: moved to
    // TopologyWalks.kt (RS-8.4) — same package, so every existing bare call
    // site (notifyDownstream(...) below and in deliver()/resume()/suspend())
    // still resolves unchanged; suspensionRegionOf is wired into
    // attentionScheduler above as a `{ cellRef -> suspensionRegionOf(cells,
    // cellRef) }` closure since its signature grew a `cells` parameter.

    private suspend fun deliver(hostedInvocation: HostedPortInvocation) {
        val cellRef = hostedInvocation.cellRef
        // A supervised cell parks only its data/ordinary management traffic.
        // Metadata protocols remain on the always-open plane: resume and
        // catch-up protocols must not deadlock behind what they unpark.
        if (hostedInvocation.type != HostedPortInvocation.Type.PORT_PROTOCOL) {
            suspendedCells[cellRef]?.let {
                it.park(hostedInvocation)
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
                        DirectedProtocolLink(link, port, localIsFrom = descriptor?.direction == civictech.nature.ProtocolDirection.UPSTREAM)
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
                            hostDurability.alreadyProcessed(cellRef, hostedInvocation.portName, timestamp)
                        ) {
                            // suppressed: already-acted, dropped rather than re-acted
                        } else {
                            // suspend-aware: a 🟣 target's suspend fun may park this task (spec 32).
                            // T04 finding 7 (extended, T06 §C1a): re-install the
                            // replay scope HostDurability.recoverFrom captured at
                            // stage time (HostedPortInvocation.replayFrontier) —
                            // staging and this delivery run on different
                            // scheduler tasks, so the ambient ReplayScope
                            // thread-local from recoverFrom's own call frame is
                            // long gone by now. withSuspending carries it across
                            // any suspension the handler does, even to a
                            // different worker thread.
                            civictech.cell.ReplayScope.withSuspending(hostedInvocation.replayFrontier) {
                                hostedInvocation.invocation.invokeSuspending(port.call)
                            }
                            if (cell is Effectful && timestamp != null) {
                                // per-cell tee (CP-C1): the frontier advance rides the same
                                // journal as this cell's frames — volatile cells (null) skip it
                                hostDurability.advanceAndJournalFrontier(cellRef, hostedInvocation.portName, timestamp)
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // T04 finding 5.3: was `catch (e: Exception)` — widened to
            // Throwable so supervision, the dead letter, and the
            // DEAD_LETTERED stall notice fire for a TODO()/StackOverflowError
            // /NoClassDefFoundError too. VirtualMachineError stays fatal.
            if (e is VirtualMachineError) throw e
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
                    suspendedCells[cellRef] = ParkQueue()
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
                val descriptor = civictech.nature.ContractRegistry.cellDescriptor(cell.javaClass)
                descriptor?.ports?.takeIf { it.isNotEmpty() }?.let { declared ->
                    val registered = PortRegistry.of(cell).names()
                    val missing = declared.map { it.name }.filterNot { it in registered }
                    require(missing.isEmpty()) {
                        "cell ${cell.javaClass.name}: descriptor declares ports $missing not found in " +
                            "registry $registered — registerPort's name must equal the property name (G-17)"
                    }
                }
                // PN-12: surface a durable cell placed volatile (see [volatileDurableSpawnCount]).
                if (descriptor != null &&
                    civictech.nature.Manifest.DURABLE in descriptor.manifest &&
                    journalSelector(cell.ref) == null
                ) volatileDurableSpawnCount.incrementAndGet()
                cell.onActivate(ctx)
                // spawn-time checkpoint: what a RESTART supervision restores (G-26)
                if (cell is Stateful) checkpoints[cell.ref] = cell.snapshot()
                if (attention != null) {
                    // renewed interest resumes a parked cell (spec 34): the listener
                    // may fire on any thread, so hop through the management band
                    AttentionSupport.of(cell).onBandChange { band ->
                        if (band > AttentionBand.NONE) enqueue(0) { attentionScheduler.unparkForAttention(cell.ref) }
                    }
                    // scheduling-step clock for time-aware aggregators (decay);
                    // racy read is fine — attention is advisory metadata (34)
                    AttentionSupport.of(cell).ticks = { attentionScheduler.dispatchStep }
                }
                // location becomes visible only after activation, so replayed
                // parked invocations find served ports (spec 33 step 7). The
                // instance rides along so the registry can answer
                // `describe(ref)` without reflecting at read time.
                registry?.publish(cell.ref, this@ManagedHost, cell)
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
                clearSupervision(ref, cell)
                cell.onDeactivate(ctx)
                // PN-9 (leak bound), widened by T04 finding 3: release the cell's
                // ProtocolSupport + PortRegistry entries so a despawned cell's ports
                // (and their handler closures) can be collected. When the despawned
                // cell is itself a nested ManagedHost (G-28), recurse into every cell
                // IT hosts too — before this, unbind only ran for the host-cell's own
                // three ports (managementInlet/routerInlet/deadLetterOutlet), so a
                // dropped host leaked every port of every cell it ever hosted.
                unbindPortsRecursively(cell)
            }

            override fun supervise(ref: CellRef, policy: SupervisionPolicy) {
                require(cells.containsKey(ref)) { "Cell not found: $ref" }
                policies[ref] = policy
            }

            override fun resume(ref: CellRef) {
                val parked = (suspendedCells.remove(ref)
                    ?: throw IllegalArgumentException("Cell not suspended: $ref")).drain()
                cells[ref]?.let { notifyDownstream(it, StallNotice.Resume) }
                // re-enqueue at data priority: replay order = park order (sequence tiebreaker)
                parked.forEach { this@ManagedHost.enqueueHostedInvocation(it) }
            }

            override fun suspend(ref: CellRef) {
                require(cells.containsKey(ref)) { "Cell not found: $ref" }
                if (!suspendedCells.containsKey(ref)) {
                    suspendedCells[ref] = ParkQueue()
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
                    clearSupervision(cellRef, cell)
                    // the serialization seam is exercised even in-process (G-25):
                    // restore from a round-tripped snapshot, not the live object
                    snapshots[cellRef]?.let { (cell as Stateful).restore(roundTrip(it)) }
                    // target spawn activates, publishes, and replays parked traffic
                    to.call.spawn(cell)
                }
                snapshots.clear()
            }

            // Link admission (cycle detection, headedness, damping witness,
            // topology recording) is extracted to [LinkAdmission] (T11-B):
            // no dataLock interaction anywhere in that path, so the
            // extraction is a pure delegation — no lock-order change.
            override fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult =
                LinkAdmission.connect(cells, registry, from, outletName, to, inletName)

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
            if (intakeControl.intakeState == IntakeState.CLOSED) throw IntakeClosedException(ref)
            val invocation = Invocation.of(method, args).withTarget(internalHostRoutingApi)
            enqueue(10) { invocation.invoke() }
            null
        })
    }

    /**
     * T04 finding 3 (leak mitigation until instance-scoping, T02's marker):
     * release [cell]'s [ProtocolSupport]/[PortRegistry] entries, recursing
     * into a nested [ManagedHost]'s own hosted cells (G-28) — a host-as-cell
     * only ever exposed its OWN three ports to [ProtocolSupport.unbind]
     * before, leaving every cell it hosted internally permanently bound.
     */
    private fun unbindPortsRecursively(cell: Cell) {
        ProtocolSupport.unbind(cell)
        PortRegistry.release(cell)
        if (cell is ManagedHost) cell.cells.values.forEach { unbindPortsRecursively(it) }
    }

    private fun findPort(cell: Cell, name: String): Port? = PortRegistry.of(cell)[name]

    /**
     * Routed-handle validation seam ([civictech.cell.host.inlet]): resolve a
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
            else -> null // e.g. FeedbackInlet carries no erased api class — skip the wrapper check
        }
        return RoutedInletResolution.Usable(apiClass)
    }

    /**
     * Observe-role attachment seam (spec 20/23 §Taps): the broadcasting outlet
     * [port] names on a cell hosted here, so an observer that did not build the
     * graph can [FanOutlet.tap] it. Null when [port] names no locally hosted
     * cell, no registered port of that cell, or a port that is not a
     * [FanOutlet].
     *
     * A null for a *known* endpoint is information, not an error: a topology
     * edge whose producing endpoint is not a [FanOutlet] has no emission point
     * of its own — a delegating pass-through (spec 20/21 §Fusion, 10/14
     * "chains of delegation MUST flatten") — so there is nothing observable on
     * that edge, and a flow feed must report it as fused rather than as zero
     * traffic.
     *
     * Resolution is by [PortRef.id] rather than by registered name because a
     * `TopologyLink`'s endpoint is a ref, and a cell whose ports were never
     * name-derived (PN-1) has no recoverable name at all. Everything handed
     * back is already reachable via `PortRegistry.of(cell)` to any caller
     * holding the cell object; this only threads the host's own [cells] map
     * into that lookup instead of reflecting the map out of it.
     */
    fun outletAt(port: PortRef): FanOutlet<*>? {
        val cell = port.cell?.let { cells[it] } ?: return null
        val ports = PortRegistry.of(cell)
        return ports.names().firstNotNullOfOrNull { name ->
            (ports[name] as? FanOutlet<*>)?.takeIf { it.ref.id == port.id }
        }
    }

    /**
     * Host-routed state read (the [Stateful] half of the observation seam,
     * spec 33 §Snapshot / G-25): [ref]'s own `snapshot()`, captured **on this
     * host's execution context** rather than on the caller's thread — off-thread
     * it would race the cell's fold, which is exactly why no such accessor
     * existed and why an outside reader could not have one.
     *
     * A future rather than a value, deliberately: the caller owns the bound.
     * An observer that must not stall (the inspector's content search, spec
     * 90/97 M5 — "viz never blocks the graph") applies its own deadline and
     * abandons the read, where a blocking `enqueueAwaiting` would hand it the
     * scheduler's 5 s default instead. Completed with null — never
     * exceptionally — when [ref] is not hosted here, is not [Stateful], the
     * scheduler is gone, or `snapshot()` itself throws; a diagnostic read must
     * not turn a broken cell into a broken caller.
     *
     * Cost, stated plainly because it is real: `snapshot()` copies the cell's
     * whole state, on the cell's own thread. It is off the per-message data
     * path (P2) and raises no attention or subscription (P6) — nothing is
     * linked, nothing is emitted, no wave counter moves — but it is not free,
     * and a caller that fans it out must bound the fan-out.
     *
     * **Callable from any thread, against any scheduler (T18, finding B10).**
     * The hand-off is [HostScheduler.submit], whose threading contract now says
     * so explicitly: every implementation accepts a foreign-thread enqueue. The
     * two production schedulers always did (concurrent queues);
     * [SimulationController]'s guards its queue as of T18, so an observer on an
     * HTTP thread may read a *simulated* host's cell as safely as a threaded
     * one's. Only the enqueue is concurrent — the snapshot itself still runs on
     * this host's execution context, which is the whole point of routing it —
     * and on a simulated host that means the read lands on the next `step()` /
     * `runToIdle()`, not before. There is no scheduler class this refuses to
     * submit to; the sole null-completing scheduler case remains a *terminated*
     * one, below.
     *
     * **Cancellation is honored (T18).** A caller that abandons the read at its
     * own deadline — the inspector's content search does, with `cancel(false)` —
     * leaves a task already queued here. The task checks the future before
     * touching the cell, so an abandoned read costs a dequeue instead of a whole
     * state copy. It is not withdrawn from the queue: nothing in the ordering
     * contract lets a submitted task be recalled, and nothing needs to be, since
     * the caller blocks per read and so has at most one in flight.
     */
    fun snapshotOf(ref: CellRef): CompletableFuture<Serializable?> {
        val future = CompletableFuture<Serializable?>()
        val cell = cells[ref]
        if (cell !is Stateful) return future.also { it.complete(null) }
        return try {
            scheduler.submit(0) {
                // cancellation check before the copy (T18): `isCancelled` only
                // ever transitions false -> true, so the check races nothing
                // that matters — a cancellation landing after it wastes one
                // snapshot exactly as before, one landing before it saves the
                // copy. `complete` on an already-completed future is a no-op
                // either way; what is being avoided is `snapshot()` itself.
                if (!future.isCancelled) future.complete(runCatching { cell.snapshot() }.getOrNull())
            }
            future
        } catch (_: IllegalStateException) {
            // terminated scheduler (T04 finding 5): a dead host has no state to read
            future.also { it.complete(null) }
        }
    }

    // wouldCloseCycle: moved onto TopologyIndex itself (RS-8.4) — it only
    // ever read its `topology` parameter, so it fits as a member; it is now
    // called as `reg.wouldCloseCycle(from, to)` from LinkAdmission.admitCycle
    // (T11-B), routed through LocationRegistry's read-only projection (T03)
    // rather than the raw TopologyIndex (which `LocationRegistry.topology` no
    // longer exposes).

    // hasDampingWitness: moved to civictech.cell.link.Handshake.kt (T11-A) —
    // it reads nature vectors the same way Handshake's reconcileNatures does,
    // and both are link-admission-time nature predicates; see that file for
    // the FU-8 KDoc. Its call site moved out of this file too (T11-B): it is
    // now the top-level `hasDampingWitness` invoked from
    // LinkAdmission.admitCycle.
}
