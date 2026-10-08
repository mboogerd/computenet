package civictech.cell.host



import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.protocol.*
import civictech.cell.BlockingCell
import civictech.cell.BoundaryDenialAccounting
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.BudgetCharging
import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.BudgetRefusedException
import civictech.cell.ClaimClass
import civictech.cell.chargeOrFail
import civictech.cell.denyBudget
import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CheckpointReplayPositions
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
import java.nio.charset.StandardCharsets.UTF_8
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionScheduler
import civictech.cell.control.AttentionSupport
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.Journal
import civictech.cell.evolve.Effectful
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.TopoEvent
import civictech.cell.graph.requireBoundRef
import civictech.cell.Propagate
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.control.ParkQueue
import civictech.cell.KeyBoundMistypedException
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
    /**
     * Per-PORT journal selector (computenet-xy7w4 D4): one cell may journal
     * some inlets and not others — an inlet fed externally (durable) beside a
     * `derived` inlet fed only by another journaled cell's cascade (volatile,
     * since replaying the upstream cell re-derives it). Mutually exclusive
     * with [journalFor] (an `init` refusal names both are set). Effective
     * selector: [journalForPort] if given, else [journalFor] lifted to ignore
     * the port, else the whole-host [journal] constant — each a byte-identical
     * degenerate case of the one before it.
     *
     * Every journaled port of ONE cell MUST name the same [Journal] instance:
     * a cell's `Stateful` snapshot, outlet-wave state and durable epoch are
     * captured once per **cell**, not once per port, so two journals for one
     * cell's ports have no coherent meaning. Checked at spawn ([cellJournal])
     * and refused by name (cell ref plus the two disagreeing port names) — a
     * configuration error, not a dead letter. An outlet may only echo its
     * cell's inlet journal (a port-blind selector answers for it too); a
     * journal named for an outlet alone, or one disagreeing with the inlets',
     * is refused the same way — outlet-side journaling of spontaneous
     * emissions is undecided (spec 90 roadmap I-7 §8). See [cellJournal].
     */
    private val journalForPort: ((CellRef, String) -> Journal?)? = null,
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
    /**
     * Batched ingress dispatch (KBLK, `computenet-t6b.2-D4`): the maximum number
     * of staged invocations one data-band (priority 20) scheduler task may
     * dispatch. A **scheduling** setting beside [attention] and [intakeBound] —
     * not a durability one: it never changes which journal a cell uses or when
     * an append happens.
     *
     * `1` (the default) is the pre-KBLK path, byte-for-byte: every accepted
     * invocation submits its own `dispatchOne` task. A value above `1` (a
     * benchmark host passes e.g. `64`) coalesces task submission instead: one
     * armed task drains up to this many staged invocations, then re-arms while
     * staged work remains (see [drainBatch]).
     *
     * It changes the **task count only**, never which message runs next: the
     * band selection and stride floor run per message inside the task
     * ([AttentionScheduler.dispatchUpTo] loops the unchanged
     * [AttentionScheduler.dispatchOne]), so per-cell FIFO, per-link FIFO,
     * [civictech.cell.MessageContext] and the attention starvation bound are the
     * same either way. Staging and the journal append stay per invocation, under
     * [dataLock], at acceptance time.
     *
     * The one observable cost: a task that delivers into a cell whose handler
     * genuinely suspends parks the host (spec 32; [SimulationController],
     * [CoroutineScheduler]) for the rest of its batch, exactly as one message
     * does unbatched — only longer, and other-band work (management, protocol)
     * waits behind a whole batch rather than behind one message.
     */
    private val dispatchBatch: Int = 1,
    /**
     * This host's own economic budget (ECO1 F3, `computenet-5o1rf`,
     * `5o1rf-D5`): the ledger charged, at this scope, for every remote-driven
     * spawn admitted anywhere in this host's subtree — the per-Principal
     * quota that "reuses the G-28 host-hierarchy quota walk" (spec 43). The
     * default, [BudgetLedger.Unlimited], is identity-checked and skipped:
     * with it on every scope of a chain no claim is constructed, nothing is
     * attached to a spawned cell, and the host behaves byte-for-byte as it
     * did before budgets existed (`[ECO1-BUD-04]`, `[ECO1-POL-06]`).
     *
     * Appended LAST and defaulted so every existing construction site
     * compiles unchanged.
     */
    private val budget: BudgetLedger = BudgetLedger.Unlimited,
) : Host {

    init {
        require(dispatchBatch >= 1) { "dispatchBatch must be >= 1 (was $dispatchBatch)" }
        require(journalFor == null || journalForPort == null) {
            "pass either journalFor or journalForPort, not both (computenet-xy7w4 D4)"
        }
    }

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
     * The effective PER-PORT journal selector (computenet-xy7w4 D4). Explicit
     * [journalForPort] wins; otherwise [journalFor] lifts into a selector that
     * ignores the port; otherwise the whole-host [journal] becomes the
     * port-and-cell-constant selector — each a byte-identical degenerate case
     * of the one before it, preserving pre-D4 behavior exactly when unused.
     */
    private val portJournalSelector: (CellRef, String) -> Journal? =
        journalForPort
            ?: journalFor?.let { f -> { ref: CellRef, _: String -> f(ref) } }
            ?: { _, _ -> journal }

    /**
     * Cache of each spawned cell's unique journal (computenet-xy7w4 D4),
     * populated by [cellJournal] at spawn time and never cleared on despawn
     * (same cumulative convention as [journaledSpawnCounts]) — so a
     * cellRef-only lookup (checkpoint's frontier/baseline filtering, after
     * the cell may have despawned) still answers correctly without a live
     * [Cell] instance. Absent = volatile or never spawned; [ConcurrentHashMap]
     * for the same concurrent-reader reason [cells] uses one.
     */
    private val cellJournals = ConcurrentHashMap<CellRef, Journal>()

    /** Namespace registry used by journaled [TopoEvent.FamilyKey] recovery. */
    private val topologyFamilies = ConcurrentHashMap<String, KeyedCells<*>>()

    /**
     * The effective per-cell journal selector (CP-C1), now DERIVED from
     * [portJournalSelector] (computenet-xy7w4 D4) rather than independently
     * configured: a cell's [Journal] for checkpoint/epoch/accounting purposes
     * is the ONE instance every one of its journaled ports names — see
     * [cellJournal]. Reads the spawn-time cache, so it stays answerable for a
     * cellRef whose cell has since despawned.
     */
    private val journalSelector: (CellRef) -> Journal? = { cellRef -> cellJournals[cellRef] }

    /**
     * The unique [Journal] instance ONE cell's journaled ports all name (D4).
     *
     * When no explicit [journalForPort] was given, this is exactly today's per-cell
     * answer — `journalFor(cellRef)` if given (its null wins), else `journal` — with no port enumeration and no new
     * refusal: a whole-cell selector was never port-aware, so applying the new
     * per-port rules to it retroactively would refuse a cell's ordinary `outlet` port
     * (which every existing selector "answers" the same way it answers every other
     * port, since it never looks at the port at all) for a config it did not opt into.
     *
     * Only when [journalForPort] is explicit does this evaluate it over every one of
     * [cell]'s registered ports and refuse — naming [cellRef] and the two offending
     * port names — if two journaled INLETS disagree, since a cell's `Stateful`
     * snapshot, outlet-wave state and durable epoch are captured once per CELL, not
     * once per port.
     *
     * Outlets are checked separately, and more permissively: an outlet is never the
     * target of an accepted invocation, so nothing ever actually journals through
     * one — a selector that blankets a whole cell (`{ ref, _ -> ... }`-style,
     * ignoring the port, the ordinary style for "journal everything U does") answers
     * for a cell's outlet exactly as it answers for its inlets, and that echo is inert
     * and allowed. What IS refused is an outlet asked to carry a journaling decision
     * of its own: a journal named for an outlet with no journaled inlet on the same
     * cell (nothing to reconcile it with), or one that disagrees with the cell's
     * inlet-derived journal. Outlet-side journaling of spontaneous emissions is
     * undecided (spec 90 roadmap I-7 §8, this feature's D6 non-goal); this is the
     * fail-fast for someone trying to configure it as if it existed. Called once, at
     * spawn; the result is cached in [cellJournals].
     */
    private fun cellJournal(cellRef: CellRef, cell: Cell): Journal? {
        // An explicit journalFor's null wins outright (volatile), exactly as pre-D4's
        // `journalFor ?: { journal }` — never a fallback to the whole-host journal.
        val explicit = journalForPort ?: return if (journalFor != null) journalFor.invoke(cellRef) else journal
        val registry = PortRegistry.of(cell)
        var chosen: Journal? = null
        var chosenPort: String? = null
        val outlets = mutableListOf<Pair<String, Journal>>()
        registry.names().forEach { name ->
            val portJournal = explicit(cellRef, name) ?: return@forEach
            if (registry[name] is FanOutlet<*>) {
                outlets += name to portJournal
                return@forEach
            }
            val previousPort = chosenPort
            if (chosen == null) {
                chosen = portJournal
                chosenPort = name
            } else require(portJournal === chosen) {
                "cell $cellRef has journaled ports naming different journals: '$previousPort' and " +
                    "'$name' — every journaled port of one cell must name the same Journal instance " +
                    "(computenet-xy7w4 D4)"
            }
        }
        outlets.forEach { (name, portJournal) ->
            require(chosen != null && portJournal === chosen) {
                "journal selector named a journal for outlet port '$name' on $cellRef with no " +
                    "matching journaled inlet — only inlet ports may be journaled (outlet-side " +
                    "journaling is undecided, computenet-xy7w4 D6)"
            }
        }
        return chosen
    }

    internal fun subtreeCellCount(): Int = cells.size + childHosts.sumOf { it.subtreeCellCount() }

    /**
     * True when [ref] is hosted directly by this host — the exact membership test
     * [HostManagementApi.declareWrite] applies. Unlike [lookup], a ref the
     * [LocationRegistry] places on a remote peer answers false (computenet-4vxtr.1).
     */
    internal fun hosts(ref: CellRef): Boolean = cells.containsKey(ref)

    /**
     * Charges [claim] once at every scope of this host's ancestor chain —
     * this host, then each [parentHost] upward, the same ascent as the G-28
     * quota walk — against each scope's own [budget] (`5o1rf-D5`). Scopes
     * whose ledger is [BudgetLedger.Unlimited] are skipped without
     * allocating. On the first [BudgetOutcome.Refused] every debit already
     * taken in this ascent is undone, in reverse, and that refusal is
     * returned (`[ECO1-CHG-03]`); a throwing ledger refuses with
     * `LEDGER_FAILURE` ([chargeOrFail], `[ECO1-CHG-10]`). When nothing was
     * charged the shared [BudgetLedger.Unlimited] admission is returned; an
     * admission's `undo` reverses every scope's debit.
     *
     * The caller accounts a refusal — this function never denies.
     */
    internal fun chargeThroughAncestors(claim: BudgetClaim): BudgetOutcome {
        var admitted: ArrayList<BudgetOutcome.Admitted>? = null
        var scope: ManagedHost? = this
        while (scope != null) {
            if (scope.budget !== BudgetLedger.Unlimited) {
                when (val outcome = scope.budget.chargeOrFail(claim, scope.ref.toString())) {
                    is BudgetOutcome.Admitted -> (admitted ?: ArrayList<BudgetOutcome.Admitted>().also { admitted = it }) += outcome
                    is BudgetOutcome.Refused -> {
                        admitted?.let(::undoInReverse)
                        return outcome
                    }
                }
            }
            scope = scope.parentHost
        }
        val taken = admitted ?: return BudgetLedger.Unlimited.charge(claim)
        return taken.singleOrNull() ?: BudgetOutcome.Admitted { undoInReverse(taken) }
    }

    /**
     * The ledger this host hands to a spawned [BudgetCharging] cell: every
     * charge walks [chargeThroughAncestors]. Allocated once per host.
     */
    internal val hierarchyLedger: BudgetLedger = BudgetLedger { claim -> chargeThroughAncestors(claim) }

    /** True iff some scope on this host's ancestor chain has a non-[BudgetLedger.Unlimited] budget (`5o1rf-D6`). */
    private fun anyLedgerOnChain(): Boolean {
        var scope: ManagedHost? = this
        while (scope != null) {
            if (scope.budget !== BudgetLedger.Unlimited) return true
            scope = scope.parentHost
        }
        return false
    }

    private fun undoInReverse(admitted: List<BudgetOutcome.Admitted>) {
        for (i in admitted.indices.reversed()) admitted[i].undo()
    }
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

    /**
     * The host's OWN boundary-denial sink (`5o1rf-D4`): the spawn site's
     * budget refusals are accounted here, reported into this host's
     * [deadLetters] exactly as a hosted membrane's are, so
     * [boundaryDenialCount] counts them and the spec-23-R8 sanitizer runs.
     * The exposure name is the literal `"host"` — exposure is a membrane
     * concept a host has none of; the record's `cellRef` (this host's [ref])
     * already says which host refused. Declared after [deadLetters], which
     * the reporter closes over.
     */
    private val hostDenials = BoundaryDenials().also { denials ->
        denials.attachReporter { denial, deniedArgs -> deadLetters.boundaryDenial(ref, denial, deniedArgs) }
    }

    private val scheduler: HostScheduler = scheduler ?: VirtualThreadScheduler("ManagedHost-${ref.id}")

    /** The concurrency color of this host's execution context (spec 32). */
    val color: HostColor get() = scheduler.color

    private val cells = ConcurrentHashMap<CellRef, Cell>()

    /** Host-local declared ingress lanes; their counters are intentionally volatile. */
    private val declaredWrites = ConcurrentHashMap<String, DeclaredWrite>()

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
     * `[KBLK-06]`/`[KBLK-07]`: per-[DurabilityClass] cumulative spawn count —
     * how many cells, of ANY manifest, were spawned onto a journal of that
     * class (`journalSelector(cell.ref)?.durability`). Counted at the same
     * spawn site as [volatileDurableSpawnCount] (`spawn()`, below), for every
     * cell whose selector returned a journal — not only `DURABLE`-manifest
     * ones, because the question this answers is "what class of journal
     * serves this host's cells", not "which cells declared durability".
     * Cumulative, like [volatileDurableSpawnCount]: never decremented on
     * despawn (`computenet-t6b.2-D3`).
     *
     * Deliberately not a refusal: PN-12's rationale above (a durable-capable
     * cell run volatile can be a legitimate deployment) applies equally here.
     * The kernel only counts; a deployment that requires synchronous
     * durability asserts over [durabilityAccounting] itself at startup —
     * e.g. `check(acct.journaledSpawns[DurabilityClass.BATCHED] == 0L &&
     * acct.journaledSpawns[DurabilityClass.IN_MEMORY] == 0L &&
     * acct.volatileDurableSpawns == 0L)`. The only way to weaken a cell's
     * durability is the [Journal] instance [journalFor] returns for it; the
     * only "no durability" spelling is `journalFor(cellRef) == null`
     * (`[KBLK-04]`) — there is no second, host-level mechanism.
     */
    private val journaledSpawnCounts: Map<DurabilityClass, AtomicLong> =
        DurabilityClass.entries.associateWith { AtomicLong() }

    /**
     * Snapshot of this host's per-[DurabilityClass] and volatile-durable
     * spawn counts (`[KBLK-06]`). Public, not `internal`, because the
     * deployment that asserts over it (`[KBLK-07]`) lives outside `:kernel`.
     */
    data class DurabilityAccounting(
        /** Every [DurabilityClass] present, zero-filled — never a partial map. */
        val journaledSpawns: Map<DurabilityClass, Long>,
        /** The existing PN-12 counter ([ManagedHost.volatileDurableSpawns]), re-exposed here. */
        val volatileDurableSpawns: Long,
    )

    /** `[KBLK-06]`/`[KBLK-07]`: see [journaledSpawnCounts] and [DurabilityAccounting]. */
    fun durabilityAccounting(): DurabilityAccounting = DurabilityAccounting(
        journaledSpawns = journaledSpawnCounts.mapValues { (_, count) -> count.get() },
        volatileDurableSpawns = volatileDurableSpawnCount.get(),
    )

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
     * Data plane (spec 34, M6.3): messages stage in per-cell FIFO queues, and
     * each dispatch picks the next cell by attention band. Per-cell FIFO (a
     * superset of per-link FIFO, spec 31 rule 3) holds because band selection
     * happens BETWEEN cells, never within one.
     *
     * Every accepted message is dispatched by a data-band (priority 20) task
     * submitted after its staging. With [dispatchBatch] `== 1` that is one task
     * per message (message count <= task count); with [dispatchBatch] `> 1` one
     * armed task drains up to the bound and re-arms while work remains
     * ([drainBatch]). Either way drain's phase 2 (priority 30) runs after them,
     * because every [HostScheduler] orders by priority, then sequence.
     */
    private val dataLock = Any()

    /**
     * Number of journal record loops that have not yet restored their complete durability
     * state. A replay frame is staged in journal order while this is positive, but data-band
     * tasks do not dequeue anything until the loop has applied every later frontier,
     * baseline-discharge, outlet-wave and checkpoint record.
     *
     * Recovery runs on the caller's thread while delivery runs on [scheduler]. Without this
     * gate the default free-running scheduler can deliver a frame immediately after it is
     * staged, before the caller reaches the following processed-frontier record. Besides
     * re-firing an `Effectful` sink, that races [HostDurability]'s plain mutable durability
     * maps across the two threads. The management-band fence taken when the first loop starts
     * lets any data delivery already in progress finish before journal restoration begins;
     * topology replay remains free to use the management band while later data tasks defer.
     * Guarded by [dataLock], whose release at the end also publishes the restored maps to the
     * scheduler thread before dispatch resumes.
     *
     * Recovery is exclusive with externally requested queue fences and drains: [quiescence],
     * host drain/migration, and [drainCellThenDespawn] reject while this is positive. The
     * exclusion is two-sided: [externalBarriersInFlight] also makes recovery reject while a
     * fence or drain is pending. Both directions claim their side under [dataLock], so a
     * caller cannot pass a check and submit a barrier while recovery raises its gate. A
     * gated frame may temporarily have no pending data task, so allowing any priority-30 or
     * lower fence through would falsely report it flushed. [Recovery.awaitApplied]'s fence is
     * taken after the gate lifts and is unaffected (computenet-lnr6u, computenet-quf22).
     */
    private var recoveryRecordLoops = 0

    /** External fences/drains claimed atomically against [recoveryRecordLoops]. Guarded by [dataLock]. */
    private var externalBarriersInFlight = 0

    /**
     * Internal queue-drain observers deferred until recovery has restored every record and
     * re-armed delivery of the frames staged behind its gate. Unlike [externalBarriersInFlight],
     * these do not exclude recovery: their scheduler tasks re-check [recoveryRecordLoops] and
     * return here instead of claiming that a gated frame has drained. Guarded by [dataLock].
     */
    private val recoveryAwareFences = ArrayDeque<CompletableFuture<Unit>>()

    /**
     * Times the recovery gate has been lowered to zero. A recovery-aware fence records it
     * at submission and re-submits instead of completing when it changed: a fence task the
     * scheduler dequeued under the gate may reach its check only after the lift, while the
     * re-armed data tasks submitted under that same lock are still queued. Guarded by [dataLock].
     */
    private var recoveryGateLifts = 0L

    private fun requireRecoveryIdleLocked(operation: String) {
        check(recoveryRecordLoops == 0) {
            "$operation is unavailable while journal recovery is restoring records; " +
                "wait for recoverFrom to return and use Recovery.awaitApplied"
        }
    }

    private fun beginExternalBarrier(operation: String) = synchronized(dataLock) {
        requireRecoveryIdleLocked(operation)
        externalBarriersInFlight++
    }

    private fun endExternalBarrier() = synchronized(dataLock) {
        check(externalBarriersInFlight > 0) { "external barrier count underflow" }
        externalBarriersInFlight--
    }

    /**
     * Per-thread durable-input capture for this host. A per-host field is the host-instance
     * key: sends to another host see that host's distinct thread-local and proceed normally.
     */
    private data class DurableInputCapture(
        val cellRef: CellRef,
        val name: String,
        val journal: Journal,
        val frames: MutableList<HostedPortInvocation> = ArrayList(),
    )

    private val durableInputCapture = ThreadLocal<DurableInputCapture?>()

    /**
     * Host-wide acceptance positions retained until delivery, including after
     * a frame leaves [AttentionScheduler] for supervision parking. Identity
     * keys keep repeated byte-identical invocations distinct. Guarded by
     * [dataLock]; inlet policy buffers and cold tails transfer the position
     * into [FanInlet].
     */
    private val checkpointSequences = IdentityHashMap<HostedPortInvocation, Long>()
    private var nextCheckpointSequence = 0L

    /**
     * Exact in-process frames created by [stageBehindData]. Identity, rather
     * than the protocol payload alone, keeps an independently received
     * topology marker on the ordinary dead-letter path. Guarded by [dataLock].
     */
    private val stagedLinkCloseMarkers =
        Collections.newSetFromMap(IdentityHashMap<HostedPortInvocation, Boolean>())

    /**
     * Batched dispatch only ([dispatchBatch] `> 1`): true while a [drainBatch]
     * task is submitted-and-not-yet-finished. Guarded by [dataLock]; never read
     * or written on the `dispatchBatch == 1` path.
     */
    private var dispatchArmed = false

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
        resume = ::resumeAttentionParked,
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
     * extracted to [HostDurability] (RS-8.2). Shares both [portJournalSelector]
     * (the SAME lambda instance this host's own `journalTee` uses) and
     * [journalSelector] (computenet-xy7w4 D4: the cell-level, cache-backed
     * form the PN-12 spawn check and [journaledSpawnCounts] also use), so
     * per-port and per-cell journal selection stay byte-identical between the
     * host and this delegate; reads a live view of [cells] and delegates
     * dead-letter reporting and replayed-frame re-intake back to the host.
     * [checkpoint] still runs via [enqueueAwaiting] at management priority 0,
     * unable to interleave with a dispatching cell; its pending-set read and
     * journal reset run under [dataLock] (computenet-xy7w4 D3,
     * computenet-hknt0) so compaction carries scheduler-staged,
     * supervision-parked, inlet-policy-held and cold-tail frames together in
     * acceptance order, atomically with respect to the intake — lock order
     * `dataLock` -> journal monitor, as on the intake path.
     */
    private val hostDurability = HostDurability(
        journalSelector = portJournalSelector,
        cellJournalSelector = journalSelector,
        cellsView = { cells },
        deadLetter = { message -> deadLetter(null, message) },
        submit = ::enqueueHostedInvocation,
        awaitOnManagementBand = { action -> enqueueAwaiting(0, action) },
        underIntakeLock = { action ->
            synchronized(dataLock) {
                // A staged link's EdgeClose marker ([stageBehindData]) shares the data
                // FIFO but is a protocol frame: it is never journaled, so a checkpoint
                // does not carry it either (and could not encode its in-process link).
                val pending = ArrayList<CheckpointFrame>()
                attentionScheduler.stagedInSequence()
                    .filter { it.type != HostedPortInvocation.Type.PORT_PROTOCOL }
                    .forEach { invocation ->
                        pending += CheckpointFrame(
                            checkNotNull(checkpointSequences[invocation]) {
                                "staged invocation has no host acceptance position: $invocation"
                            },
                            invocation,
                        )
                    }
                suspendedCells.values.forEach { queue ->
                    queue.snapshot().forEach { invocation ->
                        pending += CheckpointFrame(
                            checkNotNull(checkpointSequences[invocation]) {
                                "supervision-parked invocation has no host acceptance position: $invocation"
                            },
                            invocation,
                        )
                    }
                }
                cells.forEach { (cellRef, cell) ->
                    PortRegistry.of(cell).names().forEach { portName ->
                        (PortRegistry.of(cell)[portName] as? FanInlet<*>)
                            ?.checkpointParked()
                            ?.forEach { (sequence, invocation) ->
                                pending += CheckpointFrame(
                                    sequence,
                                    HostedPortInvocation(
                                        cellRef,
                                        portName,
                                        HostedPortInvocation.Type.PORT_API,
                                        invocation,
                                    ),
                                )
                            }
                    }
                }
                action(pending.sortedBy(CheckpointFrame::sequence))
            }
        },
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
        hostDurability.discardCheckpointReplayPositions(cellRef)
        policies.remove(cellRef)
        checkpoints.remove(cellRef)
        generations.remove(cellRef)
        suspendedCells.remove(cellRef)?.drain()?.forEach {
            synchronized(dataLock) { checkpointSequences.remove(it) }
            parkedDrainedOnTeardownCount.incrementAndGet()
            deadLetter(null, "cell $cellRef left the host while suspended", it)
        }
        synchronized(dataLock) { attentionScheduler.attentionParked.remove(cellRef) }?.forEach { (_, parked) ->
            if (isTeardownBookkeepingMarker(parked)) return@forEach
            synchronized(dataLock) { checkpointSequences.remove(parked) }
            parkedDrainedOnTeardownCount.incrementAndGet()
            deadLetter(null, "cell $cellRef left the host while attention-parked", parked)
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
    private fun beginDrain(operation: String, andThen: () -> Unit = {}) {
        synchronized(dataLock) {
            requireRecoveryIdleLocked(operation)
            require(state == State.RUNNING) { "drain requires a RUNNING host (was $state)" }
            externalBarriersInFlight++
            state = State.DRAINING
        }
        closeIntake()
        try {
            enqueue(30) {
                try {
                    // attention-parked traffic is accepted work: flush it before
                    // deactivation, same guarantee as the ordinary queue (spec 33/34)
                    val parked = synchronized(dataLock) {
                        attentionScheduler.attentionParked.values.flatten().map { it.second }
                            .also { attentionScheduler.attentionParked.clear() }
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
                } finally {
                    endExternalBarrier()
                }
            }
        } catch (t: Throwable) {
            endExternalBarrier()
            throw t
        }
    }

    /** Forwards to [DeadLetters] (RS-8.3) — kept as a same-named private method so every call site is unchanged. */
    private fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) =
        deadLetters.deadLetter(cause, description, invocation)

    /**
     * The single-writer **divergent-write** report seam (f7h.5-D4, G-44;
     * [civictech.cell.replication.SingleWriterReplication.onDivergentWrite]).
     * A superseded leader surfaces each delta it produced after it witnessed a
     * member depart, once, as an ordinary dead letter — so a write that the
     * winner may never have received is reported on the channel operators
     * already read rather than silently dropped.
     *
     * `internal`, and deliberately narrower than the `private` [deadLetter] it
     * forwards to: it takes a **required** [invocation], because a divergent
     * write is always describable as the synthetic `deltaInlet.propagate` it
     * would have been, and there is no reason for another kernel caller to
     * reach the general form through this name. No public API on this host
     * widens.
     *
     * [cause] is null for the write itself (it is not a fault — nothing threw)
     * and carries the exception when an `onDivergentWrite` handler threw and
     * its failure is being reported as its own record.
     */
    internal fun deadLetterDivergent(
        cause: Throwable?,
        description: String,
        invocation: HostedPortInvocation,
    ) = deadLetter(cause, description, invocation)

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
     * Submit one spawn on the management band without awaiting it. The caller
     * owns the returned outcome, so a refusal completes it exceptionally rather
     * than entering this host's dead-letter stream.
     */
    internal fun spawnAsync(cell: Cell): CompletableFuture<CellRef> =
        spawnAsync(cell) { }

    /**
     * Submit one spawn and run [afterSpawn] inside the same management task,
     * after admission/activation succeeds and before the returned future
     * completes. Interest-driven keyed families use this to append membership
     * after a successful budgeted spawn without opening a second claim site.
     */
    internal fun spawnAsync(
        cell: Cell,
        afterSpawn: (CellRef) -> Unit,
    ): CompletableFuture<CellRef> {
        val stamp = CurrentPeer.stamp()
        val future = CompletableFuture<CellRef>()
        try {
            scheduler.submit(0) {
                try {
                    val spawned = CurrentPeer.withStamp(stamp) { internalApi.spawn(cell) }
                    afterSpawn(spawned)
                    future.complete(spawned)
                } catch (failure: Throwable) {
                    future.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
        }
        return future
    }

    /** Await a caller-owned management future using this host's scheduler semantics. */
    internal fun <T> awaitManagement(future: CompletableFuture<T>): T = scheduler.await(future)

    /** The registry that admits interest for families hosted here, when configured. */
    internal fun interestRegistry(): LocationRegistry? = registry

    /**
     * Test seam for `DurableGlitchFreeReplayTest`'s control (PN-2); forwards to
     * [HostDurability.replayAsBaseline] (RS-8.2). Production always replays as
     * baseline; see [HostDurability]'s KDoc for the full semantics.
     */
    internal var replayAsBaseline: Boolean
        get() = hostDurability.replayAsBaseline
        set(value) { hostDurability.replayAsBaseline = value }

    /**
     * Test-only qfi22-D7/D8 control; production always suppresses exact
     * same-journal replay duplicates at the intake.
     */
    internal var suppressReplayedDuplicates: Boolean
        get() = hostDurability.suppressReplayedDuplicates
        set(value) { hostDurability.suppressReplayedDuplicates = value }

    open fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
        // computenet-xy7w4 D1: a frame accepted while a replayed frame's delivery is on
        // this thread is derived from it — it inherits that replay's provenance, so a
        // cascade of any depth carries it (see [civictech.cell.ReplayProvenance]).
        val ambient = civictech.cell.ReplayProvenance.get()
        val contextual =
            if (ambient == null || hostedInvocation.replayOf != null) hostedInvocation
            else hostedInvocation.copy(replayOf = ambient)
        durableInputCapture.get()?.let { capture ->
            capture.frames += contextual
            return
        }
        accept(contextual)
    }

    /**
     * Places a staged link's terminal marker in the target cell's data FIFO.
     * The link's stand-in has already been removed, so every frame this marker
     * follows was accepted before unlink. This intentionally bypasses [accept]:
     * protocol-band submission would overtake data, while intake gates, hop
     * accounting and journaling do not apply to an in-band topology marker.
     */
    internal fun stageBehindData(to: CellRef, inletName: String, link: Link) {
        val close = HostedPortInvocation(
            cellRef = to,
            portName = inletName,
            type = HostedPortInvocation.Type.PORT_PROTOCOL,
            invocation = Invocation.of(null, null, null),
            protocolId = Protocols.TopologyOrder,
            protocolLink = link,
            protocolMessage = EdgeClose,
        )
        stageForDataDispatch(close, stagedLinkCloseMarker = true)
    }

    /**
     * Attention parking contains already-accepted work in FIFO order. Ordinary
     * data keeps its existing intake replay, including the closed/saturated gates,
     * but skips the journal tee that acceptance already performed. A protocol
     * marker placed there by [stageBehindData] must instead rejoin the data FIFO:
     * sending it through [accept] would submit it at protocol band 0 and overtake
     * the data before it.
     */
    private fun resumeAttentionParked(hostedInvocation: HostedPortInvocation) {
        if (hostedInvocation.type == HostedPortInvocation.Type.PORT_PROTOCOL) {
            stageForDataDispatch(hostedInvocation)
        } else {
            accept(hostedInvocation, skipJournalTee = true)
        }
    }

    private fun stageForDataDispatch(
        hostedInvocation: HostedPortInvocation,
        stagedLinkCloseMarker: Boolean = false,
    ) {
        synchronized(dataLock) {
            if (stagedLinkCloseMarker) stagedLinkCloseMarkers += hostedInvocation
            attentionScheduler.stage(hostedInvocation)
        }
        if (dispatchBatch == 1) enqueue(20) { dispatchOneWhenRecoveryReady() } else armBatchDispatch()
    }

    private fun consumeStagedLinkCloseMarker(hostedInvocation: HostedPortInvocation): Boolean =
        synchronized(dataLock) { stagedLinkCloseMarkers.remove(hostedInvocation) }

    /**
     * Terminal protocol bookkeeping has no payload fate to account for when a
     * cell leaves the host. In-process EdgeClose markers are tracked by
     * [stagedLinkCloseMarkers]; bridged frontier markers arrive independently
     * over a [civictech.cell.wire.WireEdgeLink] and are identified by their
     * protocol/link shape instead. Both follow the same teardown rule.
     */
    private fun isTeardownBookkeepingMarker(hostedInvocation: HostedPortInvocation): Boolean =
        consumeStagedLinkCloseMarker(hostedInvocation) || isBridgedFrontierMarker(hostedInvocation)

    /**
     * True only for the bridged frontier markers that spec 20/22 requires to
     * retain their wire link's FIFO position against data. The bridge delivers
     * frames here in order, but submitting these markers at their descriptor's
     * protocol band would let them overtake data already staged at band 20.
     * [AttentionScheduler]'s per-cell FIFO is a superset of that per-link FIFO.
     *
     * Only frames arriving at the link's consumer end (`toAddr`) qualify: that
     * is the end the link's data reaches, so an upstream-bound frame (an
     * `EdgeClose` sent back to the producer) has no data to stay ordered with.
     * `StallNotice.Resume` joins `Stall`: were `Stall` staged and `Resume`
     * left on the protocol band, a `Resume` arriving behind a still-staged
     * `Stall` on the same link would be handled first, leaving the edge
     * stalled. Other wire protocols, including handshakes/capability traffic,
     * stay on their descriptor band. In-process protocol delivery has no
     * [civictech.cell.wire.WireEdgeLink] and is unchanged.
     */
    private fun isBridgedFrontierMarker(hostedInvocation: HostedPortInvocation): Boolean {
        val link = hostedInvocation.protocolLink as? civictech.cell.wire.WireEdgeLink ?: return false
        if (link.toAddr.cell != hostedInvocation.cellRef || link.toAddr.port != hostedInvocation.portName) return false
        return when (hostedInvocation.protocolId) {
            Protocols.TopologyOrder -> hostedInvocation.protocolMessage is EdgeEvent
            Protocols.Progress -> hostedInvocation.protocolMessage is civictech.cell.control.Progress
            Protocols.Suspension -> hostedInvocation.protocolMessage is StallNotice
            else -> false
        }
    }

    private fun accept(hostedInvocation: HostedPortInvocation, skipJournalTee: Boolean = false) {
        if (hostedInvocation.type == HostedPortInvocation.Type.PORT_PROTOCOL) {
            require(hostedInvocation.invocation.context == null) { "protocol invocations must carry null MessageContext" }
            val id = requireNotNull(hostedInvocation.protocolId) { "PORT_PROTOCOL requires protocolId" }
            val descriptor = requireNotNull(ProtocolRegistry.protocol(id.name)) { "unknown protocol ${id.name}" }
            // The metadata plane remains available while data intake is closed or
            // saturated. Bridged frontier markers alone join the target cell's
            // staged FIFO so their wire order against data survives host scheduling;
            // unrelated protocol traffic keeps its descriptor's scheduler band.
            if (isBridgedFrontierMarker(hostedInvocation)) {
                stageForDataDispatch(hostedInvocation)
            } else {
                scheduler.submit(descriptor.band) { deliver(hostedInvocation) }
            }
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
        // computenet-xy7w4 D2: keyed on the frame's replay provenance, not on
        // a time window — a live frame accepted during recovery IS gated.
        if (!isManagement && hostedInvocation.replayOf == null) {
            synchronized(dataLock) {
                if (intakeControl.intakeState == IntakeState.SATURATED) {
                    val beforeCoalesce = attentionScheduler.dataQueues[hostedInvocation.cellRef]
                        ?.map { it.second }
                        .orEmpty()
                    if (intakeBound?.policy == SaturationPolicy.Coalesce && intakeControl.coalesce(hostedInvocation)) {
                        val afterCoalesce = attentionScheduler.dataQueues[hostedInvocation.cellRef]
                            ?.map { it.second }
                            .orEmpty()
                        val replaced = beforeCoalesce.singleOrNull { before ->
                            afterCoalesce.none { after -> after === before }
                        }
                        val replacement = afterCoalesce.singleOrNull { after ->
                            beforeCoalesce.none { before -> before === after }
                        }
                        if (replaced != null && replacement != null) {
                            checkpointSequences.remove(replaced)?.let { sequence ->
                                checkpointSequences[replacement] = sequence
                            }
                        }
                        // Coalescing is acceptance, not loss: retain every original
                        // in the WAL so recovery may replay the equivalent sequence.
                        if (!skipJournalTee) journalTee(hostedInvocation)
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
        // selection has something to choose between; every accepted message is
        // then dispatched by a data-band task submitted after its staging. With
        // dispatchBatch == 1 that is one task per message (message count <=
        // task count; a task may find nothing); with dispatchBatch > 1 one armed
        // task drains up to the bound and re-arms while work remains
        // (drainBatch). Either way drain's phase 2 at 30 runs after them because
        // schedulers order by priority.
        //
        // T04 finding 1: checkSaturationOnAccept returns a deferred announce
        // instead of running Protocols.sendUpstream's relay traversal here —
        // that traversal can reach another host's enqueueHostedInvocation
        // and ITS dataLock, so it must run only after this lock releases.
        val announce = synchronized(dataLock) {
            // qfi22-D7/D8, the delivery complement of journalTee's append rule:
            // a frame J would not append because it derives from replay of J is
            // also a frame J already delivers when this exact target-port
            // position occurred in J. Drop that derived duplicate before it is
            // positioned or staged. A crash-window derivation absent from J has
            // no recorded position and therefore continues through normally.
            if (hostDurability.suppressesReplayedDuplicate(hostedInvocation)) {
                // A suppressed delivery has no downstream consumer. Discharge
                // exclusive arguments here rather than silently losing them;
                // the Recovery suppression counter makes the drop observable.
                hostedInvocation.invocation.args.forEach(Proxy::discharge)
                return
            }
            val newlyPositioned = !checkpointSequences.containsKey(hostedInvocation)
            if (newlyPositioned) checkpointSequences[hostedInvocation] = ++nextCheckpointSequence
            try {
                if (!skipJournalTee) journalTee(hostedInvocation)
                attentionScheduler.stage(hostedInvocation)
                intakeControl.checkSaturationOnAccept(hostedInvocation, isManagement)
            } catch (e: Throwable) {
                if (newlyPositioned) checkpointSequences.remove(hostedInvocation)
                throw e
            }
        }
        announce?.invoke()
        if (dispatchBatch == 1) enqueue(20) { dispatchOneWhenRecoveryReady() } else armBatchDispatch()
    }

    /**
     * The write-ahead tee's one append rule (computenet-xy7w4 D2): append to the target's
     * journal unless the frame is a replay of THAT journal (`replayOf === journal`) — the
     * replayed frame itself, or a same-host frame derived from delivering one. Live frames
     * (no provenance) are appended whenever they arrive, recovery running or not; a derived
     * frame whose target tees to a DIFFERENT journal is appended there as live, because that
     * journal may never have received it (in flight at the crash) — see
     * `ReplayProvenanceTest` R-B'. Caller holds [dataLock].
     *
     * Its delivery complement is checked beside this tee in [accept]: a
     * same-journal replay derivation at an exact target-port position the
     * journal already submitted is not only skipped here, but suppressed from
     * staging — a frame J would not append because J already holds it is a
     * frame J already delivers (qfi22-D7/D8).
     *
     * Keyed on the target `(cellRef, portName)` (computenet-xy7w4 D4), not the cell alone:
     * a per-port selector may journal one inlet of a cell and leave a sibling inlet
     * volatile (`PerPortJournalTest` R-E).
     */
    private fun journalTee(hostedInvocation: HostedPortInvocation) {
        val journal = portJournalSelector(hostedInvocation.cellRef, hostedInvocation.portName) ?: return
        if (hostedInvocation.replayOf === journal) return
        journal.append(hostDurability.journalFrame(hostedInvocation))
    }

    /**
     * Batched dispatch ([dispatchBatch] `> 1`), called after a message is
     * staged: submits a [drainBatch] task unless one is already armed. An armed
     * task that has not yet made its final check (in [drainBatch]'s `finally`)
     * is guaranteed to see this message — both that check and the staging
     * happen under [dataLock], and the check re-arms whenever anything is
     * staged — so skipping the submit here never strands a message.
     */
    private fun armBatchDispatch() {
        val arm = synchronized(dataLock) {
            if (dispatchArmed) false else true.also { dispatchArmed = true }
        }
        if (arm) enqueue(20) { drainBatch() }
    }

    /**
     * One batched data-band task: dispatch up to [dispatchBatch] staged
     * messages (band selection and the stride floor re-run per message inside
     * [AttentionScheduler.dispatchUpTo]), then — in `finally`, so an exception
     * escaping the dispatch loop cannot leave the flag armed with nobody coming
     * (a cell handler's own exception is absorbed by [deliver] before it gets
     * here) — disarm and, if staged work remains, re-arm by submitting the next
     * task.
     *
     * **Drain ordering.** [beginDrain] closes the intake and then submits its
     * phase 2 at priority 30. Every message accepted before the intake closed
     * is either already dispatched, or staged with an armed [drainBatch]
     * pending or running. A pending one runs first (20 < 30). A running one
     * re-arms at 20 before it returns, and a host drains one task at a time,
     * so that re-armed task is enqueued before the scheduler next polls — and
     * wins against the pending 30 on priority, whatever its sequence number.
     * That holds on every scheduler because each orders by `(priority,
     * sequence)`: [SimulationController]'s `PriorityQueue<ScheduledTask>`,
     * [VirtualThreadScheduler]'s and [CoroutineScheduler]'s
     * `PriorityBlockingQueue<ScheduledTask>`, all via [ScheduledTask.compareTo].
     * So phase 2 still runs after every accepted message, as it does unbatched.
     */
    private suspend fun drainBatch() {
        try {
            if (synchronized(dataLock) { recoveryRecordLoops == 0 }) {
                attentionScheduler.dispatchUpTo(dispatchBatch)
            }
        } finally {
            val rearm = synchronized(dataLock) {
                (recoveryRecordLoops == 0 && attentionScheduler.dataQueues.isNotEmpty())
                    .also { dispatchArmed = it }
            }
            if (rearm) enqueue(20) { drainBatch() }
        }
    }

    /** A queued unbatched task becomes a no-op while recovery is still restoring records. */
    private suspend fun dispatchOneWhenRecoveryReady() {
        if (synchronized(dataLock) { recoveryRecordLoops == 0 }) {
            attentionScheduler.dispatchOne()
        }
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
     * Obtain a named durable-input handle for [cellRef]. A volatile cell is refused up front:
     * without a cell journal there is nowhere to atomically commit either its cursor or batch.
     */
    fun durableInput(cellRef: CellRef, name: String): DurableInput {
        val selected = journalSelector(cellRef)
            ?: throw IllegalStateException(
                "durable input '$name' for cell $cellRef requires a cell journal, but the cell is volatile"
            )
        return DurableInput(
            readCommitted = { hostDurability.committedInput(cellRef, name) },
            commitBatch = { drive -> commitDurableInput(cellRef, name, selected, drive) },
        )
    }

    /** Capture one external-source batch, validate its one-journal boundary, then commit and stage it. */
    private fun commitDurableInput(
        cellRef: CellRef,
        name: String,
        journal: Journal,
        drive: () -> Serializable,
    ): Serializable {
        check(durableInputCapture.get() == null) {
            "re-entrant durable-input commit on host $ref is not allowed (input '$name' for cell $cellRef)"
        }
        val capture = DurableInputCapture(cellRef, name, journal)
        durableInputCapture.set(capture)
        val cursor = try {
            drive()
        } catch (failure: Throwable) {
            // Remove capture before reporting: refusal reporting must never be mistaken for
            // another member of the batch it is refusing.
            durableInputCapture.remove()
            refuseDurableInput(
                capture.frames,
                failure,
                "durable input '$name' for cell $cellRef refused because its drive threw: $failure",
            )
            throw failure
        } finally {
            durableInputCapture.remove()
        }

        val mismatched = capture.frames.firstOrNull { frame ->
            portJournalSelector(frame.cellRef, frame.portName) !== journal
        }
        if (mismatched != null) {
            val actual = portJournalSelector(mismatched.cellRef, mismatched.portName)
            val refusal = IllegalArgumentException(
                "durable input '$name' for cell $cellRef uses ${journal.identity()} but captured " +
                    "${mismatched.cellRef}.${mismatched.portName}, which uses ${actual.identity()} — " +
                    "every frame sent to this host by one commit must target the input cell's same Journal instance"
            )
            refuseDurableInput(capture.frames, refusal, refusal.message.orEmpty())
            throw refusal
        }

        var appended = false
        try {
            // One critical section makes append-before-stage atomic with checkpoint's pending
            // read/reset and with ordinary intake acceptance. `accept` re-enters this JVM monitor.
            synchronized(dataLock) {
                hostDurability.journalInput(journal, cellRef, name, cursor, capture.frames)
                appended = true
                capture.frames.forEach { frame -> accept(frame.copy(replayOf = journal)) }
            }
        } catch (failure: Throwable) {
            if (!appended) {
                refuseDurableInput(
                    capture.frames,
                    failure,
                    "durable input '$name' for cell $cellRef failed before its commit record completed: $failure",
                )
            }
            throw failure
        }
        return cursor
    }

    /** Every captured drop is discharged and observable; dead-letter capture then sees no live exclusive. */
    private fun refuseDurableInput(
        frames: List<HostedPortInvocation>,
        cause: Throwable,
        description: String,
    ) {
        if (frames.isEmpty()) {
            deadLetter(cause, description)
            return
        }
        frames.forEach { frame ->
            frame.invocation.args.forEach(Proxy::discharge)
            deadLetter(cause, description, frame)
        }
    }

    private fun Journal?.identity(): String = this?.let {
        "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}"
    } ?: "no journal"

    /**
     * Replay this host's [journal] (M10.1). See [HostDurability.recoverFrom]
     * for the full behavior; delegates there (RS-8.2).
     *
     * Replay only STAGES frames, so this returns a [Recovery] handle whose
     * [Recovery.awaitApplied] fences on their delivery and on every same-host
     * frame those deliveries cascade into (computenet-q5jzk). The fence is
     * taken AFTER the replay's last submit — the order is what makes it sound.
     * A failed replay throws [RecoveryIncomplete] and returns no handle. Recovery also
     * throws [IllegalStateException] while this host is draining or an external fence or
     * cell-drain barrier is in flight; that refusal is claimed atomically against those
     * operations under the host's data lock.
     */
    fun recoverFrom(journal: Journal): Recovery = recoverFrom(journal, ApplyContext(this))

    /**
     * Spawn [companion] with the same per-cell journal already selected for
     * [ownerRef].
     *
     * A lifecycle companion has its own ref so it is not a replica-set member,
     * while its checkpoint state is part of the guarded cell's durability
     * boundary. An exact selector such as `ApplyContext.journalFor` cannot infer
     * that relationship from the companion's derived ref, so the owner journal
     * is copied into the spawn-time cache before the ordinary spawn path asks
     * [cellJournal]. The direct selector may be absent or name that same journal;
     * a conflicting journal is refused rather than splitting one logical state
     * across two recovery streams.
     */
    internal fun spawnDurabilityCompanion(companion: Cell, ownerRef: CellRef) {
        require(cells.containsKey(ownerRef)) {
            "durability companion ${companion.ref} requires live owner $ownerRef"
        }
        val ownerJournal = journalSelector(ownerRef)
        val directlySelected = cellJournal(companion.ref, companion)
        require(directlySelected == null || directlySelected === ownerJournal) {
            "durability companion ${companion.ref} and owner $ownerRef name different journals"
        }
        if (ownerJournal == null) cellJournals.remove(companion.ref)
        else cellJournals[companion.ref] = ownerJournal
        managementInlet.call.spawn(companion)
    }

    /** Recover with the services and cumulative topology fold supplied by [context]. */
    fun recoverFrom(journal: Journal, context: ApplyContext): Recovery {
        require(context.host === this) { "recovery ApplyContext belongs to a different ManagedHost" }
        val staged = withRecoveryRecordLoop {
            context.replaying { hostDurability.recoverFrom(journal, context) }
        }
        return Recovery(
            replayedFrames = staged.replayedFrames,
            replayDuplicateSuppressionCount = staged.suppressedReplayDuplicates::get,
            quiescence = scheduler.quiescence(),
        )
    }

    /**
     * Fence the scheduler, restore one journal in-order, then restart data dispatch. The
     * priority-0 fence completes after any data task already executing when the gate was
     * raised; queued data tasks observe [recoveryRecordLoops] and defer without occupying
     * the scheduler, so topology replay can still make awaited management calls.
     */
    private fun <T> withRecoveryRecordLoop(action: () -> T): T {
        synchronized(dataLock) {
            check(state != State.DRAINING && externalBarriersInFlight == 0) {
                "journal recovery is unavailable while an external fence or drain is in flight; " +
                    "wait for that operation to complete"
            }
            recoveryRecordLoops++
        }
        try {
            enqueueAwaiting(0) { }
            return action()
        } finally {
            var deferredFences = emptyList<CompletableFuture<Unit>>()
            var rearmFailure: Throwable? = null
            synchronized(dataLock) {
                check(recoveryRecordLoops > 0) { "recovery record-loop gate underflow" }
                recoveryRecordLoops--
                if (recoveryRecordLoops == 0) {
                    recoveryGateLifts++
                    val pending = attentionScheduler.dataQueues.values.sumOf { it.size }
                    deferredFences = recoveryAwareFences.toList()
                    recoveryAwareFences.clear()
                    try {
                        // Submit reactivated data work before publishing the lifted gate.
                        // A recovery-aware fence cannot then overtake a staged frame in the
                        // caller/scheduler hand-off: data is priority 20, the fence MAX_VALUE.
                        if (pending > 0) {
                            if (dispatchBatch == 1) {
                                repeat(pending) { enqueue(20) { dispatchOneWhenRecoveryReady() } }
                            } else {
                                armBatchDispatch()
                            }
                        }
                    } catch (failure: Throwable) {
                        rearmFailure = failure
                    }
                }
            }
            rearmFailure?.let { failure ->
                deferredFences.forEach { it.completeExceptionally(failure) }
                throw failure
            }
            deferredFences.forEach { submitRecoveryAwareFence(it, throwOnFailure = false, liftsAtSubmit = null) }
        }
    }

    /** Append one write-ahead topology delta to [journal]. */
    fun journalTopology(journal: Journal, events: List<TopoEvent>) =
        hostDurability.journalTopology(journal, events)

    /** Register a fold provider whose compact form leads [journal]'s next checkpoint. */
    fun registerTopology(journal: Journal, provider: () -> List<TopoEvent>) =
        hostDurability.registerTopology(journal, provider)

    /** Task 8xstm.2's per-key entry point: record against the journal selected for [ref]. */
    fun recordTopology(ref: CellRef, event: TopoEvent) {
        topologyJournal(ref, cells[ref])?.let { hostDurability.journalTopology(it, listOf(event)) }
    }

    /**
     * Write-ahead variant used before [cell] is admitted. [beforeAppend] lets a family
     * register its checkpoint provider on the exact selected journal before the record
     * becomes visible.
     */
    internal fun recordTopology(
        ref: CellRef,
        event: TopoEvent,
        cell: Cell,
        beforeAppend: (Journal) -> Unit,
    ): Journal? {
        val selected = topologyJournal(ref, cell) ?: return null
        beforeAppend(selected)
        hostDurability.journalTopology(selected, listOf(event))
        return selected
    }

    /** Resolve the same per-cell selector spawn will cache, including explicit per-port selectors. */
    internal fun topologyJournal(ref: CellRef, cell: Cell?): Journal? =
        journalSelector(ref) ?: when {
            cell != null -> cellJournal(ref, cell)
            journalForPort != null -> null // a cell is required to validate its per-port selection
            journalFor != null -> journalFor.invoke(ref)
            else -> journal
        }

    /** Register one live keyed family for [TopoEvent.FamilyKey] recovery. */
    fun registerFamily(namespace: String, family: KeyedCells<*>) {
        val existing = topologyFamilies.putIfAbsent(namespace, family)
        check(existing == null || existing === family) {
            "family namespace '$namespace' is already registered on host $ref"
        }
    }

    /** Apply a rendered family key, failing loudly when no family owns its namespace. */
    internal fun recoverFamilyKey(namespace: String, key: String) {
        val family = topologyFamilies[namespace]
            ?: error("topology FamilyKey names unregistered namespace '$namespace'")
        family.recoverKey(key)
    }

    /**
     * A [Quiescence] fence on this host's scheduler: completes once its queue
     * holds no task at any band. Rejected while a recovery record loop is active;
     * see [Quiescence] for that exclusivity limit and the general fence argument.
     */
    fun quiescence(): Quiescence {
        beginExternalBarrier("quiescence")
        val future = CompletableFuture<Unit>()
        try {
            scheduler.submit(Int.MAX_VALUE) {
                endExternalBarrier()
                future.complete(Unit)
            }
        } catch (t: Throwable) {
            endExternalBarrier()
            throw t
        }
        return Quiescence(future)
    }

    /**
     * Kernel-internal queue fence for observation settlement that coexists with journal
     * recovery. It never completes while a recovery record loop is active. A request made
     * during recovery, or a previously submitted fence that reaches the scheduler after
     * recovery raised its gate, is deferred and re-submitted after the last loop lowers the
     * gate and re-arms every staged data frame.
     *
     * This is deliberately not the public [quiescence] contract: callers that drain, move,
     * checkpoint around, or otherwise act on a host-wide empty-queue claim still need the
     * two-sided external-barrier exclusion. The seam exists only for passive kernel
     * observers whose one pending fence may safely wait across recovery.
     */
    internal fun recoveryAwareQuiescence(): Quiescence {
        val future = CompletableFuture<Unit>()
        val liftsAtSubmit = synchronized(dataLock) {
            if (recoveryRecordLoops > 0) {
                recoveryAwareFences.addLast(future)
                null
            } else {
                recoveryGateLifts
            }
        }
        if (liftsAtSubmit != null) submitRecoveryAwareFence(future, throwOnFailure = true, liftsAtSubmit)
        return Quiescence(future)
    }

    /**
     * Submit or re-submit one internal fence; its task must re-check the recovery gate.
     * [liftsAtSubmit] is [recoveryGateLifts] as read under [dataLock] when the gate was
     * known lowered, or null to read it now (a re-submission after the lift).
     */
    private fun submitRecoveryAwareFence(
        future: CompletableFuture<Unit>,
        throwOnFailure: Boolean,
        liftsAtSubmit: Long?,
    ) {
        val lifts = liftsAtSubmit ?: synchronized(dataLock) { recoveryGateLifts }
        try {
            scheduler.submit(Int.MAX_VALUE) {
                // Defer while the gate is raised; re-submit behind the re-armed data tasks
                // when a lift happened since submission; complete only when neither did.
                val outcome = synchronized(dataLock) {
                    when {
                        recoveryRecordLoops > 0 -> recoveryAwareFences.addLast(future).let { 0 }
                        recoveryGateLifts != lifts -> 1
                        else -> 2
                    }
                }
                when (outcome) {
                    1 -> submitRecoveryAwareFence(future, throwOnFailure = false, liftsAtSubmit = null)
                    2 -> future.complete(Unit)
                }
            }
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
            if (throwOnFailure) throw failure
        }
    }

    /**
     * Checkpoint (M10.2, extended G-59). See [HostDurability.checkpoint] for
     * the full behavior; delegates there (RS-8.2). Callable on a live host at
     * any time: frames accepted and not yet delivered are carried into the
     * compacted journal (93 I-7 R7, computenet-xy7w4 D3), so no quiescence
     * fence is needed for safety.
     */
    fun checkpoint(journal: Journal) = hostDurability.checkpoint(journal)

    /**
     * Compact [journal] inline when the caller already owns this host's management task.
     * Unlike [checkpoint], this never self-submits or self-awaits. The post-spawn callback
     * used by [KeyedCells] is the only production caller.
     */
    internal fun checkpointInManagementTask(journal: Journal) =
        hostDurability.checkpointInManagementTask(journal)

    internal fun retainedCheckpointReplayPositionCount(): Int =
        hostDurability.retainedCheckpointReplayPositionCount()

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
        var retainCheckpointSequence = false
        try {
        // A supervised cell parks only its data/ordinary management traffic.
        // Metadata protocols remain on the always-open plane: resume and
        // catch-up protocols must not deadlock behind what they unpark.
        if (hostedInvocation.type != HostedPortInvocation.Type.PORT_PROTOCOL) {
            suspendedCells[cellRef]?.let {
                retainCheckpointSequence = true
                it.park(hostedInvocation)
                return
            }
        }
        val cell = cells[cellRef]
        if (cell == null) {
            // A staged EdgeClose is terminal bookkeeping with no payload. If
            // despawn removed its target before this deferred marker reached
            // the head of the FIFO, the edge is already gone and delivery is
            // complete; queued data remains on the dead-letter path below.
            if (consumeStagedLinkCloseMarker(hostedInvocation)) return
            return deadLetter(null, "unknown cell $cellRef", hostedInvocation)
        }
        consumeStagedLinkCloseMarker(hostedInvocation)
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
                    CurrentPeer.with(hostedInvocation.peer, hostedInvocation.peerAuth, hostedInvocation.peerIssuer) {
                        ProtocolSupport.of(port).deliver(id, directed, hostedInvocation.protocolMessage as Any)
                    }
                }

                HostedPortInvocation.Type.PORT_MANAGEMENT -> {
                    // the transport identity of the delivery is ambient for the
                    // handshake running inside (G-29 phase 1, M8.2)
                    val result = CurrentPeer.with(hostedInvocation.peer, hostedInvocation.peerAuth, hostedInvocation.peerIssuer) {
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
                            //
                            // computenet-xy7w4 D1: likewise re-install the frame's replay
                            // provenance, so every frame this handler emits into an intake
                            // inherits it (and is not re-journaled into the replayed journal).
                            CheckpointReplayPositions.withCurrentSuspending(
                                hostDurability.checkpointReplayPositions,
                            ) {
                                civictech.cell.ReplayScope.withSuspending(hostedInvocation.replayFrontier) {
                                    civictech.cell.ReplayProvenance.withSuspending(hostedInvocation.replayOf) {
                                        val inletOffer = (port as? FanInlet<*>)?.let { inlet ->
                                            synchronized(dataLock) { checkpointSequences[hostedInvocation] }
                                                ?.let { sequence ->
                                                    inlet.offerHosted(hostedInvocation.invocation, sequence)
                                                }
                                        } ?: false
                                        if (!inletOffer) {
                                            hostedInvocation.invocation.invokeSuspending(port.call)
                                        }
                                    }
                                }
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
        } finally {
            if (!retainCheckpointSequence && hostedInvocation.type != HostedPortInvocation.Type.PORT_PROTOCOL) {
                synchronized(dataLock) { checkpointSequences.remove(hostedInvocation) }
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
     * Add source-side-only Consume links to the ordinary target-side ancestry.
     *
     * `streamTo`'s routed/bypass branch deliberately has no [Link.toPort], so
     * the ordinary inbound walk cannot encounter it. Two identities recover
     * enough structure without pretending the bypass was admitted topology:
     *
     * - a source-side link whose destination is the stable ref of a hosted port
     *   contributes its producer ref;
     * - a [RoutedPropagate] attached to an outlet hosted by this host contributes
     *   a target-derived ingress-family ref, independent of `streamTo`'s
     *   anonymous attachment ref.
     *
     * Both stay opaque. In particular, even a hosted producer is not traversed:
     * the bypass has no target-side topology record on which to base that walk.
     * This is conservative for a managed `S -> W` plus bypass `W -> U`
     * diamond: `W.outlet` remains an extra root of `U`, so `S` and `U` form
     * separate observation groups rather than risking the over-alignment
     * `[22-LIVE-01]` forbids.
     */
    private fun upstreamConsumeAncestorsIncludingBypasses(ref: CellRef): UpstreamAncestry {
        val ancestry = civictech.cell.host.upstreamConsumeAncestors(cells, ref)
        if (ancestry.self == null) return ancestry

        val reachable = linkedSetOf(ref).apply { addAll(ancestry.local.keys) }
        val reachablePorts = reachable.flatMapTo(linkedSetOf()) { cellRef ->
            val cell = cells[cellRef] ?: return@flatMapTo emptyList()
            val ports = PortRegistry.of(cell)
            ports.names().mapNotNull(ports::get)
        }.mapTo(linkedSetOf()) { it.ref }
        val routedRoots = linkedSetOf<PortRef>()
        val bypassRoots = linkedSetOf<PortRef>()
        cells.values.forEach { cell ->
            val ports = PortRegistry.of(cell)
            ports.names().forEach { name ->
                val port = ports[name] ?: return@forEach
                if (port is FanOutlet<*>) {
                    port.attachedConsumerApis().forEach attachment@{ api ->
                        val ingress = (api as? RoutedPropagate<*>)?.ingress ?: return@attachment
                        if (ingress.cellRef !in reachable) return@attachment
                        val target = cells[ingress.cellRef] ?: return@attachment
                        if (PortRegistry.of(target)[ingress.portName] != null) {
                            routedRoots += ingress.root
                        }
                    }
                }
                val linked = port as? Linked ?: return@forEach
                linked.linking.links.forEach { link ->
                    if (link.role == LinkRole.Consume && link.toPort == null && link.to in reachablePorts) {
                        bypassRoots += link.from
                    }
                }
            }
        }
        if (bypassRoots.isEmpty() && routedRoots.isEmpty()) return ancestry
        return UpstreamAncestry(
            ancestry.self,
            ancestry.local,
            ancestry.opaque + bypassRoots + routedRoots,
        )
    }

    private val topologyView = object : HostTopologyView {
        override fun upstreamConsumeAncestors(ref: CellRef): UpstreamAncestry =
            upstreamConsumeAncestorsIncludingBypasses(ref)
    }

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
        beginExternalBarrier("cell drain")
        var completionOwnsBarrier = false
        try {
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
            enqueue(0) {
                try {
                    internalApi.despawn(ref)
                } finally {
                    endExternalBarrier()
                }
            }
            completionOwnsBarrier = true
        } finally {
            if (!completionOwnsBarrier) endExternalBarrier()
        }
    }

    init {
        internalApi = object : HostManagementApi {
            override fun spawn(cell: Cell): CellRef {
                // Idempotency is structural (66m-D8): this require precedes the
                // charge, so a re-applied spawn of a live ref is refused before any
                // ledger is touched — a spawn claim needs no key.
                require(!cells.containsKey(cell.ref)) { "Cell already spawned: ${cell.ref}" }
                // ECO1 (5o1rf-D1/D2): a remote-driven spawn is charged in the SAME
                // ascent as the G-28 quota walk — per scope, that scope's own budget,
                // then its quota. A null stamp is a local spawn: no claim is ever
                // constructed ([ECO1-BUD-05]); an Unlimited scope is skipped, so with
                // every scope Unlimited nothing is allocated ([ECO1-BUD-04]).
                //
                // [ECO1-DEN-08] (structural half): this is the ONE place ManagedHost
                // constructs a BudgetClaim. Only spawn — and spawnBound, which calls
                // it — charges; drain, migrate, resume, despawn, supervise and
                // promotion are management operations below the budget (spec 34
                // decision 5, §34.5) and never construct a claim.
                val stamp = CurrentPeer.stamp()
                var admitted: ArrayList<BudgetOutcome.Admitted>? = null
                try {
                    // quota walks every ancestor (G-28): a sandboxed subtree cannot
                    // grow past any enclosing budget
                    var scope: ManagedHost? = this@ManagedHost
                    while (scope != null) {
                        if (stamp != null && scope.budget !== BudgetLedger.Unlimited) {
                            val claim = BudgetClaim(stamp, ClaimClass.Spawn)
                            when (val outcome = scope.budget.chargeOrFail(claim, scope.ref.toString())) {
                                is BudgetOutcome.Admitted ->
                                    (admitted ?: ArrayList<BudgetOutcome.Admitted>().also { admitted = it }) += outcome
                                is BudgetOutcome.Refused -> {
                                    // the finally below refunds; account once, then refuse
                                    val denial = hostDenials.sinkFor("host").denyBudget(
                                        outcome, claim, BoundarySeam.HOST_ADMISSION,
                                        subject = cell.javaClass.simpleName,
                                    )
                                    throw BudgetRefusedException(outcome, denial)
                                }
                            }
                        }
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
                } catch (t: Throwable) {
                    // [ECO1-CHG-03]/[ECO1-CHG-04]: a budget refusal, a quota refusal or a
                    // colour mismatch after an admitted debit leaves no debit behind —
                    // every admission of this ascent is undone, in reverse, and the
                    // original failure (its message unchanged) is rethrown. Past this
                    // point the cell is registered, so the charge stands.
                    admitted?.let(::undoInReverse)
                    throw t
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
                // ECO1 attach-at-spawn (5o1rf-D6): a cell that charges at its own
                // seams gets this host's hierarchy-walking ledger — but only when
                // some scope on the chain has a real budget, so an all-Unlimited
                // chain attaches nothing and the cell's flow-time arms stay a
                // null-check away from their pre-budget bytes ([ECO1-BUD-04]).
                if (cell is BudgetCharging && anyLedgerOnChain()) cell.attachBudget(hierarchyLedger)
                PortRegistry.of(cell).names().forEach { name ->
                    PortRegistry.of(cell)[name]?.let { port ->
                        ProtocolSupport.of(port).relay(Protocols.Saturation)
                        // CycleHead fusion barrier (spec 21 §Fusion, 93 I-6):
                        // route every FeedbackPort's re-origination through
                        // this host's real queue, port-generic, no cell-
                        // specific wiring needed (mirrors AttentionSupport).
                        if (port is FeedbackPort<*>) port.barrier = { ctx.enqueueBarrier(it) }
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
                // computenet-xy7w4 D4: resolve and validate this cell's unique journal (across
                // every journaled port) BEFORE the per-cell accounting below, which reads it
                // through [journalSelector] — the cache [cellJournal] populates here.
                cellJournal(cell.ref, cell)?.let { cellJournals[cell.ref] = it }
                // PN-12: surface a durable cell placed volatile (see [volatileDurableSpawnCount]).
                if (descriptor != null &&
                    civictech.nature.Manifest.DURABLE in descriptor.manifest &&
                    journalSelector(cell.ref) == null
                ) volatileDurableSpawnCount.incrementAndGet()
                // [KBLK-06]: per-class accounting, for every cell whose selector
                // returned a journal (see [journaledSpawnCounts]).
                journalSelector(cell.ref)?.durability?.let { journaledSpawnCounts.getValue(it).incrementAndGet() }
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
                return try {
                    // [15-APPLY-01]/G-51: factory construction and the bound-ref
                    // check are part of "the step", not preconditions to it — a
                    // throwing factory or an identity/ref mismatch is as much a
                    // rejected step as a spawn() failure, so both live inside this
                    // dead-lettering try alongside spawn() itself.
                    val cell = factory.create(ref)
                    requireBoundRef("spawnBound", identity, ref, cell.ref)
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

            override fun upstreamConsumeAncestors(ref: CellRef): UpstreamAncestry =
                upstreamConsumeAncestorsIncludingBypasses(ref)

            override fun <T> inspectTopology(inspection: HostTopologyInspection<T>): T =
                inspection.inspect(topologyView)

            override fun declareWrite(name: String, cells: Set<CellRef>): DeclaredWrite {
                val declaredCells = cells.toSet()
                val missing = declaredCells.filterNot(this@ManagedHost.cells::containsKey)
                require(missing.isEmpty()) {
                    "declared write '$name' contains refs not hosted by ${this@ManagedHost.ref}: $missing"
                }
                return declaredWrites.compute(name) { _, existing ->
                    when {
                        existing == null -> DeclaredWrite(
                            name = name,
                            actorId = UUID.nameUUIDFromBytes(
                                "computenet:declared-write:${this@ManagedHost.ref.id}:$name".toByteArray(UTF_8),
                            ),
                            cells = declaredCells,
                        )
                        existing.cells == declaredCells -> existing
                        else -> throw IllegalStateException(
                            "declared write '$name' already exists with cells ${existing.cells}, " +
                                "cannot redeclare it with $declaredCells",
                        )
                    }
                }!!
            }

            override fun declaredWrite(name: String): DeclaredWrite? = declaredWrites[name]

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
                // re-enqueue at data priority: replay order = park order (sequence tiebreaker).
                // Already-accepted work: its intake already teed it to the journal (and a
                // checkpoint taken while it was parked carried it, computenet-hknt0), so
                // re-entering the intake must not tee it again — a second WAL copy is a
                // duplicate delivery on recovery. Same rule as [resumeAttentionParked].
                parked.forEach { accept(it, skipJournalTee = true) }
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
                synchronized(dataLock) {
                    requireRecoveryIdleLocked("host drain")
                    require(state == State.RUNNING) { "drain requires a RUNNING host (was $state)" }
                }
                // shutdown cascade (G-28, M8.1): children drain first — a child
                // must not outlive (or keep accepting after) its parent
                childHosts.forEach { it.managementInlet.call.drainHost() }
                beginDrain("host drain")
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

            override fun migrate(to: Use<HostManagementApi>) {
                beginDrain("host migration") {
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
            }

            // Link admission (cycle detection, headedness, damping witness,
            // topology recording) is extracted to [LinkAdmission] (T11-B):
            // no dataLock interaction anywhere in that path, so the
            // extraction is a pure delegation — no lock-order change.
            override fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult =
                connect(from, outletName, to, inletName, LinkOptions.DEFAULT)

            override fun connect(
                from: CellRef,
                outletName: String,
                to: CellRef,
                inletName: String,
                options: LinkOptions,
            ): LinkResult =
                LinkAdmission.connect(this@ManagedHost, cells, registry, from, outletName, to, inletName, options)

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
            // ECO1 (5o1rf-D1): the delivering thread's CurrentPeer stamp — set
            // around a PORT_MANAGEMENT delivery — is not visible inside a
            // separately submitted scheduler task on every HostScheduler
            // (CoroutineScheduler runs it on its own coroutine), so it is captured
            // here and re-established around the awaited spawn, which charges it.
            val stamp = CurrentPeer.stamp()
            if (method.name == "spawn") {
                enqueueAwaiting(0) { CurrentPeer.withStamp(stamp) { internalApi.spawn(args!![0] as Cell) } }
            } else if (method.name == "spawnBound") {
                // the wire-crossing form (93 I-21 §4.4): awaited here because
                // there is no real socket boundary to cross in-process, but the
                // caller-facing degrade-to-async behavior lives in
                // GraphSpec.applyRemote, which never lets a rejection here
                // abort the whole apply or surface as a synchronous reply.
                enqueueAwaiting(0) { CurrentPeer.withStamp(stamp) { invocation.invoke() } }
            } else if (method.name.startsWith("lookup")) {
                @Suppress("UNCHECKED_CAST")
                enqueueAwaiting(0) { internalApi.lookup(args!![0] as CellRef, args[1] as Class<Any>) }
            } else if (method.name == "upstreamConsumeAncestors" || method.name == "inspectTopology") {
                enqueueAwaiting(0) { invocation.invoke() }
            } else if (method.name == "declareWrite" || method.name == "declaredWrite") {
                enqueueAwaiting(0) { invocation.invoke() }
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
            else -> null // e.g. a FeedbackPort: its apiClass is not consulted here — skip the wrapper check
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
     * The port registered as [name] on the cell [ref] names, when that cell is
     * hosted here; null when it is not, or when it registers no such port.
     * Same rationale as [outletAt]: everything handed back is already reachable
     * via `PortRegistry.of(cell)` to any caller holding the cell object — this
     * threads the host's own [cells] map into that lookup rather than
     * reflecting the map out of it. The caller is
     * [civictech.cell.graph.precheck], which reads a live boundary port
     * (policies, cardinality, payload class, natures) without linking it.
     */
    fun portAt(ref: CellRef, name: String): Port? = cells[ref]?.let { findPort(it, name) }

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
     * **A bound is never silently widened.** [StateRead.since],
     * [StateRead.scope] and [StateRead.keyBound] are refused up front — on the
     * caller's thread, before anything is submitted — for a cell that does not
     * declare [BoundedStateful.supportsSince] / [BoundedStateful.supportsScope]
     * / [BoundedStateful.supportsKeyBound].
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
        if (request.keyBound != null && !cell.supportsKeyBound) {
            return answered(unavailable(StateReadResult.Reason.KEY_BOUND_UNSUPPORTED))
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
                    // A mistyped KeyBound (D9) is a caller error, named apart from
                    // every other readBounded throw — see [KeyBoundMistypedException].
                    onFailure = { thrown ->
                        unavailable(
                            if (thrown is KeyBoundMistypedException) StateReadResult.Reason.KEY_BOUND_MISTYPED
                            else StateReadResult.Reason.READ_FAILED
                        )
                    },
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
