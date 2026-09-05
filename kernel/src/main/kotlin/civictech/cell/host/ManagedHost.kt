package civictech.cell.host



import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.protocol.*
import civictech.cell.BlockingCell
import civictech.cell.BoundaryDenialAccounting
import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Provenance
import civictech.cell.ReBaselineEmitting
import civictech.cell.StateRead
import civictech.cell.StateReadResult
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

    /**
     * Snapshots captured by the last drain (spec 33 step 3; starts G-25).
     *
     * Concurrent for T04 finding 4's reason, applied to the one drain map an
     * outside reader now consults: every writer is the scheduler thread
     * (`beginDrain`'s phase 2, `migrate`'s continuation), but [readState]'s
     * checkpoint arm (V1C-KERNEL) reads it from an observer's own thread after
     * observing the `@Volatile` [state] as `DRAINED` — and a plain HashMap read
     * racing `migrate`'s `snapshots.clear()` can spin or false-negate.
     */
    private val snapshots = ConcurrentHashMap<CellRef, Serializable>()

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
     * A cell's coarse lifecycle transition on its host (V2-KERNEL): the four
     * state changes [isSuspended] and [isDrained] already expose *as
     * predicates*, named as events.
     *
     * The predicates say what is true now; they cannot say *when* it became
     * true, and they cannot report a change that flips back between two reads
     * at all. Sampling them is what an out-of-kernel observer had to do
     * (spec 90/98 V2, the per-cell activity log), and a transition is
     * individually rare — so the cost belongs on the rare path that causes it,
     * not on a periodic sweep of every known cell.
     *
     * Deliberately *not* an exhaustive lifecycle vocabulary: spawn and despawn
     * are already announced by [LocationRegistry.onPublish]/[LocationRegistry.onUnpublish],
     * a `SupervisionPolicy.RESTART` is already countable through [generationOf],
     * and a migration is a drain plus a publish elsewhere. Only the four
     * transitions with no existing seam are here.
     */
    enum class LifecycleTransition {
        /**
         * The cell was parked — `SupervisionPolicy.SUSPEND` on a failure, or an
         * explicit [HostManagementApi.suspend]. [isSuspended] is true.
         */
        SUSPENDED,

        /**
         * The cell was unparked by [HostManagementApi.resume]. [isSuspended] is
         * false; its parked traffic replays immediately after.
         */
        RESUMED,

        /**
         * This host finished draining (spec 33 §Drain, G-16) — reported once per
         * cell the host held at that moment. [isDrained] is true.
         */
        DRAINED,

        /**
         * This host was resumed by [HostManagementApi.resumeHost] — reported once
         * per cell it holds. [isDrained] is false.
         */
        HOST_RESUMED,
    }

    /**
     * Registered [LifecycleTransition] observers. [CopyOnWriteArrayList] for the
     * same reason [LocationRegistry]'s hooks use one: a listener may register or
     * deregister from inside a notification.
     */
    private val lifecycleListeners = CopyOnWriteArrayList<(CellRef, LifecycleTransition) -> Unit>()

    /**
     * Subscribe to this host's per-cell [LifecycleTransition]s (V2-KERNEL),
     * returning a deregistration handle exactly like [LocationRegistry]'s hooks.
     *
     * **On the host, not the registry**, because every transition is a host
     * concern and a host may run registry-less (`ManagedHost(registry = null)`);
     * a registry-level fan-in would need a host→registry back-edge that does not
     * exist. **Per cell**, because a host-level drain is a per-cell fact to every
     * consumer that keeps per-cell rows.
     *
     * Contract, identical to [LocationRegistry]'s announcement hooks:
     * notification is **synchronous, on the mutating thread** — this host's
     * scheduler, management band for suspend/resume/resumeHost and drain
     * priority 30 for the drain — and runs **after the state change is
     * visible**, so a listener that re-reads [isSuspended]/[isDrained] sees the
     * transition it was told about.
     *
     * **Notifications, not participation.** A throwing listener is contained
     * (stderr, carry on): it must not break a suspend, a drain, or the
     * invocation whose failure triggered supervision. Nothing here is on the
     * per-message data path (P2) — the call sites are the four rare
     * state-transition points themselves.
     *
     * **Idempotence follows the kernel's, not the listener's convenience.** A
     * transition is reported only where one actually happened: [HostManagementApi.suspend]
     * on an already-suspended cell is a no-op today and stays silent, and so
     * does a second `SupervisionPolicy.SUSPEND` on the same cell.
     */
    fun onLifecycle(listener: (CellRef, LifecycleTransition) -> Unit): AutoCloseable {
        lifecycleListeners += listener
        return AutoCloseable { lifecycleListeners -= listener }
    }

    /**
     * Containment for [onLifecycle], mirroring [LocationRegistry]'s hook-failure
     * rule (`LocationRegistry.notify`). Widened to `Throwable` for this file's own
     * reason (T04 finding 5.3, [enqueue] and [deliver]): a listener's `TODO()` or
     * `NoClassDefFoundError` is an `Error`, and letting one abort a drain
     * mid-flight would make an observer a participant. `VirtualMachineError`
     * stays fatal.
     */
    private fun notifyLifecycle(cellRef: CellRef, transition: LifecycleTransition) {
        lifecycleListeners.forEach { listener ->
            try {
                listener(cellRef, transition)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                System.err.println("[ManagedHost] lifecycle listener failed for $cellRef/$transition: $e")
            }
        }
    }

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
     * KFX-20: how many invocations the `Effectful` processed-frontier guard
     * suppressed as already-acted (G-59, C-9). Each such suppression discharges
     * the invocation's exclusive args explicitly ([Proxy.discharge]), so this
     * counter is also the discharge count — the suppression path is the only
     * caller. Counted because a suppression is a *drop*: spec 23 §Ownership and
     * the AGENTS.md no-silent-drop invariant require that an `Owned`/`Leased`
     * payload leaving the happy path be both discharged and observable.
     */
    private val effectfulSuppressionCount = AtomicLong()

    /**
     * KFX-16 / `[24-DUR-06]`: how many `PORT_API` invocations were refused at an
     * `Effectful` inlet for carrying no [civictech.cell.MessageContext], hence no
     * position on that inlet's processed-frontier. Counted for the same reason
     * [effectfulSuppressionCount] is: a refusal is a *drop*, so the refused
     * invocation's exclusive payloads are discharged explicitly and the drop is
     * made observable rather than silent (spec 23 §Ownership, the AGENTS.md
     * no-silent-drop invariant, G-46).
     */
    private val effectfulContextlessRefusalCount = AtomicLong()

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

    /**
     * Refusals reported to this host by a hosted membrane's
     * [civictech.cell.BoundaryDenialSink], summed over every boundary
     * (computenet-usd.6).
     *
     * Deliberately **not** a field of [SupervisionAccounting], and deliberately
     * not folded into its `deadLetters`: a denial is not a supervision event
     * (`[SEC1-29]`, BS-14), and `deadLetters` is a fault count that a dozen
     * suites read as one. `computenet-usd.1.1` put the authoritative counter on
     * the sink, per exposure, because one host may carry many membranes whose
     * refusals must stay distinguishable; this is the host-wide sum, for a
     * reader that holds the host and not the membrane.
     */
    internal fun boundaryDenialCount(): Long = deadLetters.boundaryDenialCount

    /**
     * How many stderr lines this host's denial reporting has actually written.
     * The metered quantity (`DeadLetters.shouldLogDenial`) — exposed so a test
     * can assert the bound directly instead of scraping the console.
     */
    internal fun boundaryDenialLogLines(): Long = deadLetters.boundaryDenialLogLines

    /**
     * Snapshot of this host's supervision counters (G-46). Its `deadLetters`
     * counts **faults only** — boundary refusals are counted on their own
     * channel ([boundaryDenialCount]), so an assertion here reading
     * `deadLetters` as a fault count stays correct under a boundary policy.
     */
    fun supervisionAccounting(): SupervisionAccounting = SupervisionAccounting(
        deadLetters = deadLetters.deadLetterCount,
        parkedDrainedOnTeardown = parkedDrainedOnTeardownCount.get(),
        restarts = restartCount.get(),
        effectfulSuppressionsDischarged = effectfulSuppressionCount.get(),
        effectfulContextlessRefusals = effectfulContextlessRefusalCount.get(),
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
     * Depth of work this host **accepted and staged** but has not dispatched yet,
     * per cell (computenet-hdq). Purely an observability seam: it reads
     * [civictech.cell.control.AttentionScheduler.stagedDepths] and changes
     * nothing about when or whether work runs.
     *
     * It exists because "a delivery that never ran" was the one silent outcome on
     * the announcement path with no instrument. [LocationRegistry.parkedFor] —
     * the depth every announcement diagnostic prints — counts invocations parked
     * *before* a host accepted them; an invocation past [enqueueHostedInvocation]
     * is parked nowhere it can see. Both hops behind a peering socket (the bridge
     * ingress decode and the registry mirror delivery) go through this staging, so
     * a stalled hop and an announcement that was never sent used to read
     * identically: zero everywhere, stderr silent.
     *
     * Counts only — never the staged [civictech.cell.proxy.HostedPortInvocation]s
     * — so this cannot be used to drain, reorder or re-own staged work, and no
     * `Owned`/`Leased` payload is reachable through it. Per host, not per subtree:
     * child hosts have their own.
     */
    fun stagedWorkDepth(): Map<CellRef, Int> = attentionScheduler.stagedDepths()

    /** Total of [stagedWorkDepth], summed from a single snapshot. */
    fun stagedWorkTotal(): Int = stagedWorkDepth().values.sum()

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
            // V2-KERNEL: per cell, after [isDrained] is true and *before*
            // [andThen] — migrate's continuation clears [cells], so the set this
            // drain actually deactivated is only nameable here. Scheduler
            // thread, drain band (priority 30).
            cells.keys.forEach { notifyLifecycle(it, LifecycleTransition.DRAINED) }
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

    /**
     * [ref]'s current [AttentionBand] (spec 34, G-6; V2-KERNEL) — the band this
     * host's own dispatch uses, made readable. [bandOf] above is the dispatch
     * path's version and must answer with a band for every cell it schedules;
     * this one answers an *observer*, so it distinguishes "no band" from
     * "middle band" instead of folding both to `NORMAL`.
     *
     * **Null, not a guess.** Null when [ref] is not hosted here, and null when
     * this host runs without an [AttentionPolicy]: with no policy, scheduling is
     * plain FIFO and no band is in effect anywhere, so reporting `NORMAL` would
     * invent a scheduling fact. Neutral `NORMAL` — "nobody said anything" —
     * remains a real answer on a host that *does* have a policy.
     *
     * **The read never raises attention (P6) and never mutates.**
     * [AttentionSupport.of] lazily *creates and wires* a support object for a
     * cell that has none — installing protocol handlers and unlink listeners as
     * a side effect of what its caller thinks is a read. That is why the
     * `attention == null` gate comes first and is not merely a formatting
     * choice: a host **with** a policy already called [AttentionSupport.of] for
     * every cell at spawn, so here `of` is a pure lookup, and a host **without**
     * one never reaches the call at all. Nothing else is touched — no
     * [AttentionSupport.refresh], no [AttentionSupport.attend]: both `recompute`,
     * which can change the band, fire listeners and push attention up the cone.
     *
     * Cost is one map lookup plus one volatile field read, on the caller's
     * thread. Nothing is enqueued on this host, no cell is entered, and
     * [dataLock] is not taken. The read is racy with a concurrent recompute by
     * construction, which is exactly what the dispatch path already does with
     * the same field — attention is advisory scheduling metadata (spec 34).
     */
    fun attentionOf(ref: CellRef): AttentionBand? {
        if (attention == null) return null
        val cell = cells[ref] ?: return null
        return AttentionSupport.of(cell).band
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
                    // The transport identity of the delivery is ambient here for
                    // exactly the reason it already is on the PORT_MANAGEMENT
                    // branch below (G-29 phase 1, M8.2; spec 40/43 §Identity,
                    // decided 93 I-28 §4.1, "Principal at every crossing").
                    // `BridgeIngressCell` stamps [HostedPortInvocation.peer] on
                    // every frame it decodes, whatever the type, but until
                    // `computenet-usd.4.3` only management deliveries installed
                    // it — so `currentPrincipal()` answered `LocalTrusted` for a
                    // protocol frame that had demonstrably come off the wire, and
                    // `BoundaryPolicy.protocolAuthority` (seam 3) therefore took
                    // its local no-op fast path: a remotely asserted `Attention`
                    // crossing a bridge into an exposure declaring a ceiling was
                    // applied UNCLAMPED (measured over `Peering.loopback` in
                    // `BridgeBoundaryPolicyTest`).
                    //
                    // **This does not weaken the local fast path** (93 I-28 §4.2,
                    // "local crossings carry `LocalTrusted` and every predicate is
                    // a no-op"), and the distinction is carried by the data rather
                    // than inferred: [HostedPortInvocation.peer] is non-null *iff*
                    // a bridge ingress decoded this frame, and is never
                    // serialized, so an in-process `Protocols.sendUpstream`
                    // arrives with a null peer and `CurrentPeer.with(null)` leaves
                    // `currentPrincipal()` at `LocalTrusted` exactly as before.
                    // A live broadcast driven by *data* is untouched for the same
                    // reason: it is a `PORT_API` emission in the *emitting* cell's
                    // own dispatch, and that branch installs nothing.
                    //
                    // What this DOES newly cover is any emission a remote protocol
                    // frame synchronously causes *inside* this frame, and there are
                    // two shapes of that, not one:
                    //  - a unicast reply to the asking peer — `pullServe`'s
                    //    `StateRequest` -> `baselineTo` (`CatchUp.kt`). Remote-
                    //    *triggered* by definition, and `Peer` is the honest
                    //    principal there: the identical reading the `onLinked`
                    //    catch-up already has one branch down (`PORT_MANAGEMENT`).
                    //  - a *fan-out* the frame merely unblocks. A
                    //    `Protocols.Progress` ack completing a wave in
                    //    `CoalescingCombineCell`/`WaveGate` runs `flushReady()` ->
                    //    `outlet.call.propagate(...)` on this thread, so a mediated
                    //    outlet's `disclosureFilter` would evaluate under the
                    //    *acking* peer, for a delta addressed to every attached
                    //    observer rather than to that peer.
                    // The second shape is DECIDED (`computenet-usd.8`, 2026-08-16):
                    // a fan-out carries `Principal.LocalTrusted`. The acking peer is
                    // one arm's upstream producer — neither requester nor recipient
                    // — and the emission has no single rightful principal at all,
                    // since each attachment that is itself remote is stamped at its
                    // own ingress a hop further down. The reset lives at the
                    // fan-out (`FanOutlet.call` runs its broadcast loops under
                    // `CurrentPeer.with(null)`), NOT here: narrowing this frame
                    // would also take the unicast reply above, which is genuinely
                    // addressed to the asking peer and must keep `Peer`. Both
                    // halves are pinned by
                    // `civictech.cell.membrane.PeerUnblockedFanOutPrincipalTest`.
                    // What stays open is per-*recipient* disclosure — 93 I-28 §8
                    // cross-hop composition, outside SEC1, incompatible with
                    // `[SEC1-19]`'s single shared verdict as landed
                    // (`concord/corpus/DISPUTES.md`).
                    //
                    // Non-suspending by construction: `ProtocolSupport.deliver` is
                    // a plain `fun`, so nothing can park inside this frame and
                    // resume on a worker whose thread-local was never set (the
                    // hazard `Invocation.invokeSuspending` carries context
                    // elements for).
                    CurrentPeer.with(hostedInvocation.peer, hostedInvocation.peerAuth) {
                        ProtocolSupport.of(port).deliver(id, directed, hostedInvocation.protocolMessage as Any)
                    }
                }

                HostedPortInvocation.Type.PORT_MANAGEMENT -> {
                    // the transport identity of the delivery is ambient for the
                    // handshake running inside (G-29 phase 1, M8.2)
                    val result = CurrentPeer.with(hostedInvocation.peer, hostedInvocation.peerAuth) {
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
                        //
                        // KFX-16, decided as an ADMISSION rule rather than a dedup
                        // rule (`[24-DUR-06]`, spec 24 §Effectful): an `Effectful`
                        // cell is not directly manipulable by a caller that cannot
                        // supply frontier information. A `PORT_API` frame carrying
                        // no `MessageContext` — `Invocation.context` is "null on
                        // management paths and spontaneous calls", and every
                        // in-kernel producer stamps `CurrentContext.get()`, so this
                        // is exactly the externally-driven root case — has no
                        // position on this inlet's frontier, and is therefore
                        // UNDELIVERABLE here rather than delivered unguarded. That
                        // is what makes `[24-DUR-05]` hold unconditionally at this
                        // guard: past the refusal below, every frame the sink acts
                        // on has a position to compare.
                        //
                        // The external actor's own frontier — a stable per-actor
                        // source id plus a monotonic counter, stamped before the
                        // journal tee — belongs to the connector ingress that mints
                        // and persists that identity (CON1), not here;
                        // [ActorIngress] is the kernel-side stamping seam it plugs
                        // into, and the one a direct in-process driver uses today.
                        //
                        // KFX-BASELINE / `[24-DUR-07]`+`[24-DUR-08]` (93 I-24, human decision of
                        // 2026-08-10): a frame carrying a `MessageContext.baseline`
                        // is a catch-up baseline — an I-24 pull baseline answering a
                        // late join, or PN-2's replay stamp. An `Effectful` inlet
                        // ACTS on it (a newly-joined sink fires for the state it
                        // caught up to; one rule for every `Effectful` cell, not a
                        // per-cell option), but its timestamp NEVER advances the
                        // processed-frontier: a baseline is causally anchored at the
                        // stamped link-install event, not at a wave position, so
                        // advancing a wave-position high-water from it would suppress
                        // genuine live frames from that source sitting below it.
                        //
                        // That leaves a baseline firing with nothing to suppress its
                        // own replay, so the discharge is journaled instead — in the
                        // sink's own durable state, separately from the wave frontier
                        // (`HostDurability.recordAndJournalBaselineDischarge`, an
                        // EXACT position rather than a high-water). Two consequences
                        // worth stating: the crash-consistency obligation stays inside
                        // the sink — no producer, ingress or catch-up-protocol change
                        // — and replay-vs-pull never becomes an observable distinction
                        // at this effect boundary, because both baseline kinds take
                        // the same branch. PN-2 keeps `[24-DUR-05]` exactly: a
                        // replayed frame the sink already acted on live is at-or-behind
                        // the restored frontier and suppressed; a journal-tail frame
                        // fires.
                        val context = hostedInvocation.invocation.context
                        val timestamp = context?.timestamp
                        val baseline = context?.baseline
                        if (cell is Effectful && timestamp == null) {
                            // Refused, not delivered. A refusal is a drop, and the
                            // same no-silent-drop rule the suppression branch obeys
                            // applies (spec 23 §Ownership, KFX-20): discharge the
                            // refused invocation's exclusives here (consume `Owned`,
                            // release `Leased`) and count the refusal so the denial
                            // is observable (G-46). The dead letter is the *report*
                            // — `DeadLetters` sanitizes the captured invocation, so
                            // no live exclusive handle enters the fan-out outlet
                            // (spec 23 R8); the payload was already discharged above.
                            hostedInvocation.invocation.args.forEach(Proxy::discharge)
                            effectfulContextlessRefusalCount.incrementAndGet()
                            deadLetter(
                                null,
                                "refused at Effectful inlet '${hostedInvocation.portName}' on $cellRef: " +
                                    "PORT_API invocation carries no MessageContext, so it has no processed-frontier " +
                                    "position and cannot be deduped across recovery (spec 24 §Effectful [24-DUR-06]). " +
                                    "Drive it through a stamped ingress (ActorIngress) carrying the actor's own " +
                                    "(sourceId, counter).",
                                hostedInvocation,
                            )
                        } else if (cell is Effectful && timestamp != null &&
                            (hostDurability.alreadyProcessed(cellRef, hostedInvocation.portName, timestamp) ||
                                // `[24-DUR-08]`: or already discharged as a baseline
                                // firing at this exact position. Consulted for every
                                // frame, baseline-marked or not: the record is exact,
                                // so it can only ever match a re-delivery of the very
                                // frame that fired — never collateral live traffic
                                // below it, which is the whole reason it is kept out
                                // of the wave-position frontier.
                                hostDurability.alreadyDischargedBaseline(
                                    cellRef, hostedInvocation.portName, timestamp,
                                ))
                        ) {
                            // Suppressed: already-acted, dropped rather than re-acted.
                            // KFX-20: a drop is not a licence to leak. The sink
                            // never runs, so nothing downstream will ever consume
                            // this invocation's exclusive payloads — discharge
                            // them here (consume `Owned`, release `Leased`),
                            // exactly as the ADMIT tier does for the invocation it
                            // drops (`InletPolicy`), and count the suppression so
                            // the drop is observable (G-46). Explicit
                            // consume/release, NOT a re-route into the dead-letter
                            // fan-out — spec 23 R8: a live exclusive must not
                            // enter it. Origin-blind on purpose: replay traffic,
                            // post-recovery live re-delivery, and a journaled
                            // source's replayed emissions all arrive here.
                            hostedInvocation.invocation.args.forEach(Proxy::discharge)
                            effectfulSuppressionCount.incrementAndGet()
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
                            if (cell is Effectful) {
                                // per-cell tee (CP-C1): the frontier advance rides the same
                                // journal as this cell's frames — volatile cells (null) skip it.
                                //
                                // `checkNotNull`, not a `timestamp != null` guard: the
                                // refusal branch above is the only way a contextless frame
                                // can reach an `Effectful` inlet and it does not fall
                                // through, so an acted-on `Effectful` frame ALWAYS has a
                                // position. Written as an assertion rather than a condition
                                // precisely so a future path that reintroduces the
                                // contextless case fails loudly instead of silently
                                // reinstating the `[24-DUR-05]` hole this closed.
                                val position = checkNotNull(timestamp)
                                if (baseline != null) {
                                    // `[24-DUR-07]` / `[24-DUR-08]`: a baseline firing records its exact
                                    // position in the sink's separate discharged-baseline
                                    // state — never in the wave-position frontier.
                                    hostDurability.recordAndJournalBaselineDischarge(
                                        cellRef, hostedInvocation.portName, position,
                                    )
                                } else {
                                    hostDurability.advanceAndJournalFrontier(
                                        cellRef, hostedInvocation.portName, position,
                                    )
                                }
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
                    // V2-KERNEL: idempotent, exactly like [HostManagementApi.suspend].
                    // This branch IS reachable on an already-suspended cell: the
                    // park gate above exempts PORT_PROTOCOL, so a metadata-plane
                    // handler can still fail here while the cell is parked.
                    // Re-installing a fresh [ParkQueue] there would silently
                    // discard everything the first suspension parked —
                    // Owned/Leased payloads included, with no dead letter and no
                    // accounting. No state changes, so nothing is notified either.
                    if (!suspendedCells.containsKey(cellRef)) {
                        suspendedCells[cellRef] = ParkQueue()
                        notifyLifecycle(cellRef, LifecycleTransition.SUSPENDED)
                    }
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

    /**
     * The host's own [HostManagementApi] implementation — the same object the
     * [managementInlet] proxy dispatches into, held as a field (rather than a
     * local of [init]) so a host-internal caller that must choose the scheduler
     * band for itself can invoke it directly. [drainCellThenDespawn] is the one
     * such caller: the management proxy always enqueues at priority 0, which is
     * exactly the ordering it has to avoid.
     *
     * Every call through this field runs it **on the scheduler thread**, inside
     * an [enqueue]d task, exactly as the proxy path does; it is not a bypass of
     * the queue, only of the band choice.
     */
    private lateinit var internalApi: HostManagementApi

    /**
     * Spec 33's drain protocol (`33 §The drain protocol` steps 1–3) applied at
     * **cell** granularity, then despawn — which is what spec 42 defines an
     * eviction to be: *"intake closes (spec 33's drain, applied at cell instead
     * of host granularity)"* (`40/42 §Eviction is a gated drain+despawn`), with
     * 93 I-3 §4.6 naming the mechanism that makes it lose nothing — *"30/33 step
     * 2, whose phase-2 task sits below data priority"*.
     *
     * The one thing it adds to the previous suspend/despawn pair is the **drain
     * barrier**: an awaited empty task on the drain band (priority 30 — BELOW
     * data's 20, so no priority inversion), the same device [beginDrain] uses at
     * host granularity. A priority-30 task cannot run while anything at 0/10/20
     * is pending, so when the barrier returns every invocation the host had
     * already accepted has been dispatched to its cell — spec 33 step 2,
     * *"process (or park) everything already accepted"*. Only then does the
     * teardown run: [HostManagementApi.suspend] closes the cell's intake (the
     * cell-granularity analogue of [closeIntake]; there is no fail-fast per cell,
     * so a later arrival parks rather than being refused), [beforeDespawn] fires
     * the caller's final anti-entropy push — genuinely drain-gated now, reading
     * state as of the drained intake rather than as of the call — and
     * [HostManagementApi.despawn] tears the cell down.
     *
     * The teardown stays on the management band rather than riding the barrier's
     * own task, and that is load-bearing: `spawn` is priority 0, so a *deferred*
     * despawn is overtaken by a caller that evicts and immediately re-spawns the
     * same ref, which fails as `Cell already spawned` (measured against
     * `:testkit`'s `RejoinSubscriptionTest` / `ChurnMeshTest` while building
     * this). Departure ordering is unchanged; only the barrier is new.
     *
     * The barrier **awaits**, like `spawn` and `lookup` on the same API, so the
     * caller's own bookkeeping after `evict` still runs after the drain. A host
     * under a saturating data stream could in principle hold the barrier off;
     * unlike [beginDrain] there is no per-cell intake to close first, and that
     * residual is the price of cell rather than host granularity.
     *
     * **What this fixed (computenet-078s).** `Replication.evict` used to enqueue
     * both `suspend` and `despawn` on the management band at priority 0, ahead of
     * data's 20. So `suspend` PREEMPTED a write the host had already accepted and
     * journalled but not yet dispatched, `deliver` parked it, and `despawn`'s
     * [clearSupervision] drained that queue into dead letters — accounted as
     * `parkedDrainedOnTeardown`, never silently dropped, but never applied to the
     * cell and so never gossiped to the surviving replicas either. That is the
     * inverse of the ordering the spec's own no-loss argument rests on. Pinned by
     * `civictech.cell.replication.ChurnReconvergenceTest."a write issued one step
     * before a clean evict reaches the survivors"`.
     *
     */
    internal fun drainCellThenDespawn(ref: CellRef, beforeDespawn: () -> Unit = {}) {
        // Phase 1+2 barrier, drain band (priority 30). An empty task at 30 cannot
        // run until nothing at 0/10/20 is pending, so when this returns every
        // invocation this host had already accepted has been dispatched to its
        // cell — spec 33 step 2, obtained by exactly the device [beginDrain] uses
        // at host granularity, and the only thing this method adds.
        enqueueAwaiting(30) { }
        // Phase 3 — the teardown, on the management band exactly as before, so a
        // caller that evicts and immediately re-spawns the same ref still sees
        // the despawn first (a deferred despawn breaks depart-then-rejoin:
        // `spawn` is priority 0 and would overtake it — "Cell already spawned").
        enqueue(0) { internalApi.suspend(ref) }
        beforeDespawn()
        enqueue(0) { internalApi.despawn(ref) }
    }

    init {
        internalApi = object : HostManagementApi {
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
                // SEC1 denial accounting, realization (B) (spec 40/43, [SEC1-26]):
                // a membrane's per-exposure BoundaryDenialSink reports through THIS
                // host's DeadLetters, so the spec-23-R8 sanitization is inherited
                // rather than reimplemented behind the membrane. The seam stays
                // narrow — `DeadLetters` itself is never handed out; the membrane
                // can only submit a record plus the refused arguments. Reporting a
                // denial consults no SupervisionPolicy and mints no wave: a refusal
                // is not a cell fault ([SEC1-29], BS-14).
                if (cell is BoundaryDenialAccounting) {
                    cell.boundaryDenials.attachReporter { denial, deniedArgs ->
                        deadLetters.boundaryDenial(cell.ref, denial, deniedArgs)
                    }
                }
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
                // KFX-12 (spec [24-DUR-04], 93 I-14 Rule S1): a journaled cell's outlets
                // emit under their ref-derived epoch, so a rebuilt instance re-mints the
                // identity the network already observed instead of a fresh random one.
                // Before onActivate — a cell may emit from it. Volatile cells keep the
                // fresh-epoch default ([KFX-14]); see [civictech.cell.port.OutletWaveState.durable].
                hostDurability.installDurableEpochs(cell.ref, cell)
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
                // V2-KERNEL: before the replay, not after. [isSuspended] is
                // already false — the state change is complete — and the replay
                // is its consequence, not part of it: `enqueueHostedInvocation`
                // refuses a closed intake, so notifying afterwards would lose a
                // transition that definitively happened (resuming a cell on a
                // drained host does exactly that).
                notifyLifecycle(ref, LifecycleTransition.RESUMED)
                // re-enqueue at data priority: replay order = park order (sequence tiebreaker)
                parked.forEach { this@ManagedHost.enqueueHostedInvocation(it) }
            }

            override fun suspend(ref: CellRef) {
                require(cells.containsKey(ref)) { "Cell not found: $ref" }
                if (!suspendedCells.containsKey(ref)) {
                    suspendedCells[ref] = ParkQueue()
                    cells[ref]?.let { notifyDownstream(it, StallNotice.Stall(StallReason.SUSPENDED)) }
                    // V2-KERNEL: inside the guard — a repeated suspend changes
                    // nothing and so reports nothing.
                    notifyLifecycle(ref, LifecycleTransition.SUSPENDED)
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
                // V2-KERNEL: last, so a listener sees a fully resumed host —
                // reactivated, intake open, republished, [isDrained] false.
                // Scheduler thread, management band (priority 0).
                cells.keys.forEach { notifyLifecycle(it, LifecycleTransition.HOST_RESUMED) }
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
            /**
             * computenet-weo8 — the route fault site, and the ONLY place a
             * failing `route` can honour AGENTS.md's core invariant ("no
             * failure, suppression, shadow, park, or dead-letter path may
             * silently drop an exclusive payload").
             *
             * The throw below lands in [enqueue]'s catch, which dead-letters
             * *without* a [HostedPortInvocation] (computenet-mouq, see the
             * `routerInlet.serve` comment), so `DeadLetters.sanitizeForDeadLetter`
             * — which keys off exactly that invocation — never runs and can
             * never be what discharges these args. Discharging here, rather
             * than synthesizing a capture for the fault path, keeps the
             * dead-letter record shape unchanged (`invocation == null`, the
             * host-ref fallback the Inspector asserts) while closing the leak.
             *
             * Discharge-exactly-once holds because this runs strictly *before*
             * the target inlet is reached: on any path that reaches
             * `invocation.invoke(inlet.call)` this has not run, and the
             * downstream owner takes the payload as usual. A throw out of the
             * inlet's own handler is a different case with a different owner
             * (the handler received the args) and is deliberately untouched.
             */
            private fun refuse(invocation: Invocation, message: String): Nothing {
                invocation.args.forEach(Proxy::discharge)
                throw IllegalArgumentException(message)
            }

            override fun route(target: CellRef, inletName: String, invocation: Invocation) {
                val toCell = cells[target] ?: refuse(invocation, "Target cell not found: $target")
                val port = findPort(toCell, inletName)
                    ?: refuse(invocation, "Inlet not found or not usable: $inletName on $target")
                val inlet = port as? Use<*>
                    ?: refuse(invocation, "Inlet not found or not usable: $inletName on $target")

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

        // computenet-mouq — **dead letters from this dispatch carry no
        // HostedPortInvocation.** The routing handler runs inside the private
        // [enqueue] helper, whose fault path calls `deadLetter(e, ...)` with no
        // `invocation:` argument. This dispatch is a raw management shortcut —
        // it never builds a [HostedPortInvocation] at all — so there is nothing
        // to hand that fault path, whether the throw comes from an unresolved
        // cell/port or from the target inlet's own handler. Two consequences
        // the next reader should not have to rediscover:
        //
        //  - `DeadLetters.sanitizeForDeadLetter` keys off exactly that
        //    invocation, so **per-argument capture never runs here**. A test
        //    that drives a fault through `host.routerInlet.call.route(...)` and
        //    then asserts Frozen/Redacted forms or per-argument discharge
        //    accounting is asserting over an absent record and can pass while
        //    covering nothing. Use [enqueueHostedInvocation] for those — it is
        //    the path that carries the invocation into the fault catch. The
        //    difference is pinned executably by
        //    `LifecycleAndDeadLetterTest."a route-driven dead letter carries no
        //    invocation, so per-argument capture is unreachable through it"`.
        //  - An `Owned`/`Leased` argument handed to a *failing* `route` is
        //    therefore NOT discharged by this fault path. computenet-weo8
        //    closed that leak at the route fault site itself
        //    (`internalHostRoutingApi.refuse` above) rather than by giving this
        //    dispatch an invocation to hand the catch — so the record shape
        //    described here is unchanged, and the exclusives are consumed/
        //    released before the throw ever reaches it.
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
        // computenet-3u6x: the third JVM-global owner-keyed map. Spawn installs an
        // onBandChange listener whose closure captures the cell, so the entry can
        // reach its own key; despawn is where that stops being wanted.
        AttentionSupport.release(cell)
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

    /**
     * Host-routed **bounded** state read (V1C-KERNEL, closing MRB-157) — the
     * paged sibling of [snapshotOf], and the seam an instrument can afford to
     * point at a big cell.
     *
     * [snapshotOf] already has every property a read-only observer wants except
     * a bound, a consistency stamp and an answer for a cell that is not hot.
     * This adds all three, and changes nothing about the first: everything
     * [snapshotOf]'s KDoc says about threading, cancellation, wave-neutrality
     * and never completing exceptionally is true here **per page**.
     *
     * **One page = one scheduler task.** A 10⁵-row read becomes ~500 short
     * tasks interleaved with the cell's own work instead of one task that owns
     * its thread for the whole copy. Measured
     * (`doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`):
     * a concurrent whole-state copy stalls a 10⁵-element cell's live traffic
     * for ~28 ms because a priority-0 submit jumps ahead of every queued
     * data-priority task and then holds the thread; paging removes ~85–99% of
     * that stall for a ~1.7–2.4× total-work premium. Pages are therefore never
     * batched inside one task, and a walk is driven one round trip at a time by
     * its caller.
     *
     * **Wave-neutral, like [snapshotOf] and for the same reason: it is not a
     * message.** It never enters `FanOutlet.call`/`at`, so no wave counter
     * moves and `waveState()` is unchanged; it fires no tap, so no delivered
     * watermark advances; it is never offered to a `WaveFrontier`, so it arms
     * no join and joins no completeness set; it reaches no inlet, so it cannot
     * advance an `Effectful` processed frontier; it installs no link, so no
     * `PullOnOpen` fires and no attention is raised (P6). This is exactly what
     * a `StateRequest` pull *cannot* offer an instrument — not because a pull
     * perturbs the wave plane in any way that matters, but because a reply is
     * delivered only to a `consumers`/`taps` entry, so a read-only observer
     * would have to install topology first.
     *
     * **The answer for a cell that is not hot, decided rather than guessed:**
     *
     * - **Suspended** ([isSuspended]): answered **from the live cell**, with
     *   [Provenance.LIVE_SUSPENDED]. Only the cell's data intake is parked; its
     *   fold is quiescent by construction, which makes it the most stable thing
     *   in the graph to read. This read is submitted at priority 0 and is not
     *   itself parked.
     * - **Drained host** ([isDrained]): answered from the checkpoint blob this
     *   host already holds, with [Provenance.CHECKPOINT], **without scheduling
     *   anything on any cell thread**. That blob is a whole [Stateful.snapshot]
     *   value, not a page, and nothing in the kernel can slice an opaque
     *   `Serializable` without reflection — so it is offered as
     *   [StateReadResult.Unbounded] under [StateRead.allowWholeCopy] and
     *   refused with [StateReadResult.Reason.CHECKPOINT_NOT_BOUNDED] otherwise.
     *   No frontier rides it: none is recoverable from the blob, and inventing
     *   one would be worse than omitting it.
     * - **Held for a migration flip, or published on another host**:
     *   [StateReadResult.Reason.MIGRATING]. The authoritative instance is not
     *   this host's.
     * - **Unhosted, not [Stateful], terminated scheduler**: the named
     *   [StateReadResult.Reason] arms — the cases [snapshotOf] can only report
     *   as a null completion.
     *
     * **Never a silent whole copy.** A cell that is [Stateful] but not
     * [BoundedStateful] is refused with
     * [StateReadResult.Reason.NOT_BOUNDED] unless the caller passed
     * [StateRead.allowWholeCopy]. The whole point of the primitive is that a
     * caller learns what a read costs *before* paying for it — which is what
     * the inspector's search notice currently has to reconstruct afterwards.
     *
     * **A bound is never silently widened.** [StateRead.since] and
     * [StateRead.scope] are refused up front — on the caller's thread, before
     * anything is submitted — for a cell that does not declare
     * [BoundedStateful.supportsSince] / [BoundedStateful.supportsScope].
     *
     * **This is not a back door around a pull refusal.** It is not a
     * `StateRequest`, installs no link and fires no `PullOnOpen`, so a
     * `PullOnOpen(requireServing = true)` handshake refusal is untouched by it;
     * equally, a cell that does not serve pulls may still be read here, exactly
     * as [snapshotOf] already reads one.
     *
     * Callable from any thread, against any scheduler including
     * [SimulationController] — on a simulated host each page lands on a later
     * `step()`/`runToIdle()`, which is the correct and testable behaviour.
     * Cancellation is honoured per page: a caller that abandons a walk at its
     * own deadline leaves at most one queued task, which checks the future
     * before entering the cell.
     */
    fun readState(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
        fun answered(result: StateReadResult): CompletableFuture<StateReadResult> =
            CompletableFuture<StateReadResult>().also { it.complete(result) }

        // Ordered so the honest refusals are decided on the caller's thread and
        // cost nothing on any cell thread. The migration arm comes first: a held
        // ref may still be present in [cells], and reading it there would be a
        // stale answer wearing a fresh timestamp.
        if (registry?.holds?.isHeld(ref) == true) return answered(unavailable(StateReadResult.Reason.MIGRATING))
        val cell = cells[ref]
        if (cell == null) {
            // Not here. If this host's registry places the ref at all, it is
            // another host's — the completed-`migrate` case (the ref left
            // [cells] and the target republished it) is indistinguishable from
            // "never local", and both have the same honest answer: not readable
            // here, and no local object may be invented.
            val elsewhere = registry?.location(ref) != null
            return answered(
                unavailable(
                    if (elsewhere) StateReadResult.Reason.MIGRATING else StateReadResult.Reason.NOT_HOSTED
                )
            )
        }
        if (cell !is Stateful) return answered(unavailable(StateReadResult.Reason.NOT_STATEFUL))

        // Drained: the blob already exists, so no cell thread is at risk and
        // none is used. The volatile [state] read gives happens-before on the
        // drain's writes to [snapshots].
        if (state == State.DRAINED) {
            val blob = snapshots[ref] ?: return answered(unavailable(StateReadResult.Reason.READ_FAILED))
            return answered(
                if (request.allowWholeCopy) StateReadResult.Unbounded(blob, Provenance.CHECKPOINT)
                else unavailable(StateReadResult.Reason.CHECKPOINT_NOT_BOUNDED)
            )
        }

        if (cell !is BoundedStateful) {
            if (!request.allowWholeCopy) return answered(unavailable(StateReadResult.Reason.NOT_BOUNDED))
            return submitRead {
                val snapshot = runCatching { cell.snapshot() }.getOrNull()
                if (snapshot == null) unavailable(StateReadResult.Reason.READ_FAILED)
                else StateReadResult.Unbounded(snapshot, liveProvenance(ref))
            }
        }

        // Refused before the submit, so a caller learns a bound cannot be
        // honoured without paying for a scheduler round trip — and never
        // receives state widened past the bound it asked for.
        if (request.since != null && !cell.supportsSince) {
            return answered(unavailable(StateReadResult.Reason.SINCE_UNSUPPORTED))
        }
        if (request.scope != null && request.scope !is Interest.Total && !cell.supportsScope) {
            return answered(unavailable(StateReadResult.Reason.SCOPE_UNSUPPORTED))
        }

        return submitRead {
            runCatching { cell.readBounded(request) }
                .fold(
                    onSuccess = { page ->
                        StateReadResult.Page(
                            // Provenance is the host's to state, not the cell's:
                            // only the host knows the cell is parked.
                            if (page.provenance == Provenance.LIVE) page.copy(provenance = liveProvenance(ref))
                            else page
                        )
                    },
                    onFailure = { unavailable(StateReadResult.Reason.READ_FAILED) },
                )
        }
    }

    private fun unavailable(reason: StateReadResult.Reason): StateReadResult =
        StateReadResult.Unavailable(reason)

    /** [Provenance.LIVE_SUSPENDED] iff [ref]'s data intake is parked right now (V1C-KERNEL). */
    private fun liveProvenance(ref: CellRef): Provenance =
        if (isSuspended(ref)) Provenance.LIVE_SUSPENDED else Provenance.LIVE

    /**
     * The [snapshotOf] submit block, once (V1C-KERNEL): one page = one
     * scheduler task at priority 0, the cancellation check before the cell is
     * entered (an abandoned read costs a dequeue, not a page), and a terminated
     * scheduler answered rather than thrown.
     */
    private fun submitRead(produce: () -> StateReadResult): CompletableFuture<StateReadResult> {
        val future = CompletableFuture<StateReadResult>()
        return try {
            scheduler.submit(0) {
                if (!future.isCancelled) future.complete(produce())
            }
            future
        } catch (_: IllegalStateException) {
            future.also { it.complete(unavailable(StateReadResult.Reason.SCHEDULER_TERMINATED)) }
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
