package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.onEach
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.GlitchViolation
import civictech.cell.control.Progress
import civictech.cell.control.StallNotice
import civictech.cell.data.MapApi
import civictech.cell.data.SetApi
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.QuorumSetApi
import civictech.cell.graph.TypedRef
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import java.io.Serializable
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The **wave-aligned** multi-view observation sink (spec 20/22 §The observation
 * frontier, `[22-OBS-01]`/`[22-OBS-02]`; 96 §E2.3): several named outlets folded
 * into one composite read that is never assembled from mixed waves.
 *
 * [CompositeSink] — behind [observeAll] — is the honest *point-consistent*
 * fallback: each named outlet is folded by its own [ObserveCell] and the
 * composite republishes on every per-outlet change, so a read can pair `items`
 * at wave `t` with `filtered` at wave `t-1` (the F-5 flash). Such a composite is
 * "the correct output" for **no** per-source frontier of the inputs, which is
 * exactly what `[22-OBS-01]` forbids. This class is the aligned sibling; both
 * ship, and neither replaces the other (a sink that must never block on a
 * stalled arm keeps [observeAll]).
 *
 * ### Per-name inlets — `[22-OBS-02]`'s inlet clause
 *
 * One [FanInlet] is registered **per view name** (the port name *is* the view
 * name), each served with that view's own [View] fold. View identity is
 * therefore *structural* — which inlet a delta arrived on — never inferred from
 * the delta's type. This is what dissolves [observeAll]'s documented blocker 1:
 * [civictech.cell.consistency.GlitchFreeCell] replays raw deltas to one outlet,
 * erasing which named source each came from, so two `set(...)`-registered views
 * both emitting `SetDelta` become indistinguishable downstream. Nothing
 * downstream has to tell them apart here, because they never merge.
 *
 * ### The cross-inlet completeness fold — `[22-OBS-02]`'s assembly clause
 *
 * A wave's deltas are buffered per [Timestamp] and released only when **every**
 * open expected `Consume` edge — across **every** named inlet, one shared edge
 * set, not one per arm — has settled that wave: watermark ≥ counter, the
 * watermark advancing on a real delta, on a metadata-plane [Progress] absorb-ack
 * ([civictech.cell.control.absorbAck], CP-A3), or on a later wave (monotone
 * `max`). Completed waves are applied in per-source counter order, and each
 * publishes **one** composite snapshot — effective-only: a completed wave in
 * which no view's [View.apply] reported a change publishes nothing.
 *
 * The condition is [civictech.cell.consistency.WaveFrontier]'s, *mirrored at
 * cell scope* rather than installed as an inlet policy — the same structural
 * argument [civictech.cell.data.op.CoalescingCombineCell] records (D-COMBINE):
 * an installed frontier releases a completed wave's invocations *individually*
 * and holds the inlet's single [Protocols.Progress] handler, and it is
 * **per-inlet**, so per-inlet installs cannot express one frontier spanning the
 * named inlets. Mirroring the fold is the composition actually available;
 * `WaveFrontier` itself is untouched. What is mirrored: edges + `EdgeOpen`
 * floors, per-edge per-source watermarks, the `Progress` lane (including
 * `noteAbsorbed`'s buffer-the-acked-wave move, so a wave *every* arm swallows
 * still retires), the flushed high-water straggler rule, and per-source-ordered
 * release.
 *
 * ### Stalled edges: WAIT, DEGRADE and RE-SCOPE (spec 20/22 §Completeness over silent or stuck edges)
 *
 * The `Protocols.Suspension` lane is mirrored per arm inlet, exactly as
 * `WaveFrontier.attach` handles it, with the [mode] reused from
 * [GlitchFreeCell.WaveMode] (there is no third, RE-SCOPE, mode — a terminal
 * stall re-scopes in every mode):
 *
 * - **WAIT** (the default): a *recoverable* `Stall` changes nothing — the
 *   stalled edge's waves stay buffered until it resumes and delivers, produces
 *   a later wave, absorb-acks, or its edge closes. [heldWaves] names, per held
 *   wave, the expected edges it is waiting on.
 * - **DEGRADE**: a recoverable `Stall` removes the edge from every pending and
 *   future wave's expected set (the frontier shrinks) and the waves that
 *   thereby complete publish at once; `Resume` restores it. An edge dropped
 *   this way stays *disclosed* on every later composite until its own
 *   watermark catches up with the flushed frontier — a late straggler it
 *   delivers installs into its view, and the composite that install publishes
 *   still names the edge, because that view is behind the composite's
 *   frontier until it has caught up.
 * - **RE-SCOPE** (a terminal `Stall`, `recoverable == false`, every mode): with
 *   a `Stall.timestamp` the edge's watermark advances past exactly that wave
 *   (the edge stays open, so the next wave waits for it again); without one
 *   the edge closes, exactly as `EdgeClose`. Either way [violations] counts it
 *   and [onViolation] receives a [GlitchViolation].
 *
 * **Disclosure.** Every published composite is an immutable [AlignedComposite]
 * ([composite], [onComposite]): the views, the flushed [AlignedComposite.frontier],
 * and [AlignedComposite.droppedEdges] — every edge that was expected for the
 * released wave and did not settle it by its own watermark (DEGRADE-suspended
 * at release, re-scoped for that wave, or closed while the wave was held on it),
 * plus every still-open edge a DEGRADE shrink has left behind the frontier.
 * A composite published before a shrink is never retroactively marked. The
 * same set completes that wave's write-visibility handles
 * ([VisibleDegraded] when non-empty).
 *
 * **Still not mirrored:** replica-fed settlement (E3.4) and pull-on-open — as
 * for [civictech.cell.data.op.CoalescingCombineCell].
 *
 * **The phantom expected edge (G-13).** The frontier is a static link set with
 * no upstream traversal, so an arm that structurally *never* carries a given
 * source's waves is nonetheless an expected edge for them, and holds them.
 * Register views that share a common source (the `items` / `filtered` /
 * `byDay` shape of one pipeline); two views fed by *independent* roots will each
 * hold the other's waves until an ack, a later wave, or an unlink shrinks the
 * condition. Same caveat, same cause, as `WaveFrontier` and
 * `CoalescingCombineCell` both document.
 *
 * **Catch-up is arm state, not a wave.** Unwaved traffic (push catch-up, the
 * `onLinked` state-as-delta, context-free sends) and pull-catch-up baselines
 * (`ctx.baseline != null`, 93 I-24) install into their view *immediately* and
 * are admitted to no completeness set — `WaveFrontier.offer`'s two catch-up
 * arms. A late-attached sink therefore catches each arm up as that arm's
 * baseline arrives, which can transiently expose arms seeded at different
 * points; from the first waved delta onward, every published composite is
 * aligned. Attach before the graph starts writing if the very first snapshot
 * must be aligned too.
 *
 * **Bridged (two-JVM) coverage.** `EdgeOpen`/`EdgeClose` and `Progress` already
 * cross the wire as frames (20/22 §Completeness, CP-A2), so a remote arm is
 * expected to settle exactly as a local one — but that path is **not covered by
 * a test here**: `:kernel` cannot depend on `:wire` (transport neutrality), and
 * this cell's tests live in `:kernel`. Bridged alignment is an explicit,
 * untested limitation of this delivery.
 *
 * ### Threading
 *
 * [ObserveCell]'s discipline exactly (T08 finding 4): the fold, the frontier
 * state and the snapshot swap run under [lock] on the host scheduler thread
 * (protocol deliveries are synchronous on the sender's thread, which is why the
 * lock is real rather than decorative); [current] reads a `@Volatile` immutable
 * value, so it is torn-read-free from any thread; *invoking* a listener is
 * submitted to a dedicated single-consumer daemon executor, so a blocking app
 * listener delays only this sink's later notifications, never the host's
 * dispatch. Every submission is made while holding [lock], so submission order
 * is a strict total order and a single-consumer executor executes it in that
 * order — a fresh subscriber's catch-up never interleaves out of order with a
 * change. [onDeactivate]/[onActivate] close and reopen the dispatcher across a
 * `SupervisionPolicy.RESTART` or a migration, as [ObserveCell] does.
 *
 * **The dispatcher is minted lazily, on the first submission** — and a
 * submission only ever happens when at least one listener is registered
 * ([publish] skips the fire when [listeners] is empty). An aligned view nobody
 * observes therefore owns no thread at all, and unlike [observeAll] — where the
 * composite registers a listener on each member [ObserveCell], so every member
 * legitimately mints one — an unobserved [observeAligned] costs *zero* threads,
 * not one per outlet: the arms here are inlets on this one cell, not sinks of
 * their own. This is [ObserveCell]'s fix (computenet-dqy.19) applied verbatim,
 * for its reason: a graph is built and torn down per scenario per seed in the
 * conformance suite, and the in-tree instruments read through [current] rather
 * than a listener, so an eager dispatcher was one idle daemon thread leaked per
 * view per run inside a long-lived test JVM.
 *
 * Race-freedom of the lazy mint is *mechanical*, not argued: [dispatchIfOpen]
 * asserts it holds [lock], and [lock] already serialized every arrival, wave
 * release, registration, close and reopen — so two concurrent registrations
 * cannot both mint, and a registration racing a wave release either lands in
 * that release's fired list or takes its catch-up from the already-published
 * [latest]. Neither order drops a notification.
 *
 * **Alignment is unaffected by any of it.** What makes a composite visible is
 * the [latest] swap inside [publish], under [lock], after a *complete* wave has
 * been applied to every arm — the dispatcher only carries the notification
 * that a swap happened. So deferring or skipping the mint cannot change *when*
 * an aligned view becomes visible ([current] is thread-free either way), and a
 * listener registered concurrently with a release cannot see a torn composite:
 * [onChange] takes the same [lock] that [flushReady] holds for the whole
 * apply-all-arms-then-publish sequence, so its catch-up snapshot is a composite
 * from before that wave or after it, never inside it.
 *
 * **Write-visibility handles run elsewhere — except when already complete.**
 * A [visibilityOf] handle that has to wait is registered and retired under
 * [lock], but its completion is *submitted* to the JDK default async pool
 * (`CompletableFuture.runAsync`), so the `complete` call and every plain
 * `thenAccept`/`thenApply` dependent run on a pool thread — never on the host
 * scheduler thread, never under [lock], and never on the listener dispatcher
 * (which a handle-only sink therefore never mints) — save that a thread
 * blocked in `get`/`join` on the handle may run a pending plain dependent
 * itself, per [FrontierWitness.visibilityOf]. That pool guarantee is for the
 * waiting case only: [visibilityOf] also returns an already-complete handle
 * for a closed sink, a wave already at or behind the flushed frontier, or the
 * outstanding-handle bound exceeded, and a plain dependent registered on one
 * of those runs synchronously on the calling thread instead, per
 * [FrontierWitness.visibilityOf]. Handles
 * carry no ordering guarantee relative to each other or to listener
 * notifications.
 */
class AlignedCompositeCell(
    views: Map<String, View<*, *>>,
    /** What each named view was registered as (T08 finding 2) — [get]'s checked-cast diagnostic. */
    @PublishedApi internal val registeredAs: Map<String, String> = emptyMap(),
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    /** The bound on outstanding write-visibility handles (zvq3e-D7): a default, not a measurement. */
    override val maxOutstandingHandles: Int = 1024,
    /** What a *recoverable* stall does: WAIT holds its waves, DEGRADE shrinks the frontier (see the class doc). */
    val mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT,
    /**
     * Invoked once per terminal-stall RE-SCOPE. The sink has no `errorOutlet`,
     * so the default is a no-op and [violations] is the always-on observable.
     * Runs synchronously **under the sink's lock** on the delivering thread:
     * it must not block, and must not register listeners or handles on this
     * sink (reading [current]/[composite] is fine — they are lock-free).
     */
    private val onViolation: (GlitchViolation) -> Unit = {},
) : Cell, Stateful, ObservationSink<Map<String, Any?>>, FrontierWitness {

    init {
        require(maxOutstandingHandles > 0) { "maxOutstandingHandles must be positive (was $maxOutstandingHandles)" }
    }

    /** One contributing view: its name, its fold, and the inlet that carries only its deltas. */
    private class Arm(val name: String, val view: View<Any, Any?>, val inlet: FanInlet<Propagate<Any>>)

    private val arms: Map<String, Arm> = views.entries.associateTo(LinkedHashMap()) { (name, view) ->
        @Suppress("UNCHECKED_CAST")
        val erased = view as View<Any, Any?>
        name to Arm(name, erased, registerPort(name, FanInlet.create<Propagate<Any>>()))
    }

    /**
     * The per-name inlets ([22-OBS-02]'s mechanism), in registration order —
     * exposed for the same reasons [ObserveCell.inlet] is: wiring by port
     * object, and topology introspection (a test that unlinks one arm to watch
     * the completeness condition shrink).
     */
    val inlets: Map<String, FanInlet<Propagate<Any>>> = arms.mapValues { it.value.inlet }

    // ---- the mirrored cross-inlet frontier fold (WaveFrontier at cell scope) ----

    /**
     * One tracked inlink, remembering which [arm] it feeds. [floors] snapshots
     * [flushedHighWater] at `EdgeOpen`: an edge that opened after a wave already
     * flushed is not an expected sibling for it (otherwise a freshly attached
     * view would gate waves it can never deliver).
     */
    private class EdgeState(val arm: Arm, val link: Link, val floors: Map<UUID, Long>, var open: Boolean = true)

    /** Every arm's edges in ONE map: the union is the shared completeness condition. */
    private val edges = LinkedHashMap<UUID, EdgeState>()

    /** Per-edge, per-source watermark: the highest counter known settled on that edge. */
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()

    /** Highest flushed wave per source: a straggler installs late, it never re-buffers. */
    private val flushedHighWater = mutableMapOf<UUID, Long>()

    /** A delta held for its wave, tagged with the arm it arrived on (structural view identity). */
    private class Buffered(val arm: Arm, val delta: Any)

    /** The transient version buffer, keyed by wave; a wave known only from an ack holds an empty list. */
    private val pending = LinkedHashMap<Timestamp, MutableList<Buffered>>()

    /** Edges a recoverable DEGRADE-mode stall removed from the expected set, until `Resume`. */
    private val suspendedEdges = mutableSetOf<UUID>()

    /**
     * Edges dropped from a specific wave at the moment it happened — a terminal
     * re-scope for that wave, or a close while the wave was held on the edge —
     * read (and removed) when the wave releases. Entries for waves that never
     * arrive are pruned once their source's flushed high-water passes them.
     */
    private val exclusions = mutableMapOf<Timestamp, MutableSet<DroppedEdge>>()

    /**
     * Edges released-without (dropped from a flushed wave) per source, kept
     * until the edge's own watermark reaches that source's flushed high-water
     * or the edge closes: while listed and behind, the edge is disclosed on
     * every published composite, because its view lags the composite frontier.
     */
    private val behind = mutableMapOf<UUID, MutableSet<UUID>>()

    /**
     * Waves currently held awaiting the shared frontier (a stalled or phantom
     * expected edge shows up here, and a healthy graph returns to 0 at
     * quiescence). Diagnostic only; [heldWaves] names what each is waiting on.
     */
    val bufferedWaves: Int get() = synchronized(lock) { pending.size }

    /**
     * Every held wave mapped to its unsettled expected edges ([KE2-13]) — the
     * base completeness predicate (open `Consume` edges above their floor),
     * **before** the DEGRADE suspension filter, so a WAIT-mode reader sees the
     * stalled edge a wave is held on. Diagnostic snapshot, taken under the lock.
     */
    fun heldWaves(): Map<Timestamp, Set<DroppedEdge>> = synchronized(lock) {
        pending.keys.associateWith { timestamp ->
            edges.values
                .filter { isBaseExpected(it, timestamp) && !isSettled(it.link.id, timestamp) }
                .mapTo(LinkedHashSet()) { DroppedEdge(it.arm.name, it.link.id) }
        }
    }

    /**
     * Terminal stalls re-scoped so far (each also reported to `onViolation`,
     * incremented before it runs). Always observable, since the sink has no
     * error outlet.
     */
    @Volatile
    var violations: Long = 0L
        private set

    /**
     * Deltas that matched no open `Consume` edge and were therefore installed
     * unaligned instead of buffered (PN-0a's F1 tripwire shape: a replayed
     * frame, a `streamTo`/`tap` producer that announced no `EdgeOpen`, a
     * duplicate edge). `WaveFrontier` dead-letters such an invocation; a
     * materialized fold cannot drop one without corrupting every later read of
     * that view, so it is installed and counted — the same trade
     * [civictech.cell.data.op.CoalescingCombineCell] makes for its sum.
     */
    var unmatchedDeltas: Long = 0L
        private set

    // ---- the app surface (ObservationSink), ObserveCell's discipline ----

    private val lock = Any()
    private val listeners = mutableListOf<(Map<String, Any?>) -> Unit>()
    private val compositeListeners = mutableListOf<(AlignedComposite) -> Unit>()

    /** T08 finding 4: single-consumer ⇒ FIFO submission order is delivery order. */
    private fun newDispatcher(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aligned-observe-${ref.id}").apply { isDaemon = true }
    }

    /**
     * The listener-dispatch executor, or `null` while this sink has never had
     * anything to dispatch. Read and written **only under [lock]** (hence no
     * `@Volatile`) — that is what makes the lazy mint in [dispatchIfOpen]
     * race-free.
     */
    private var dispatcher: ExecutorService? = null

    /**
     * A [close]d dispatcher awaiting hand-off: [reopen] does not mint a
     * replacement eagerly (that would resurrect a thread for a sink that may
     * never dispatch again), so the superseded executor is parked here and the
     * *next* mint chains a drain-wait on it as its first task, preserving the
     * cross-reopen ordering documented on [reopen]. Lock-guarded like
     * [dispatcher].
     */
    private var draining: ExecutorService? = null

    @Volatile
    private var closed = false

    // ---- write-visibility handles (FrontierWitness, KE2 §5.5) ----

    /** Registered handles: source → counter → futures. Lock-guarded. */
    private val handles = mutableMapOf<UUID, TreeMap<Long, MutableList<CompletableFuture<Visibility>>>>()

    /** Registered-and-uncompleted handle count; an atomic so it is readable without [lock]. */
    private val outstanding = AtomicInteger(0)

    override val outstandingHandles: Int get() = outstanding.get()

    /**
     * The one published value. [current] and [composite] both read this single
     * `@Volatile`, so a reader never pairs one wave's views with another's
     * dropped set (the torn-pair rule); there is deliberately no second one.
     */
    @Volatile
    private var latest: AlignedComposite = assemble(dropped = emptySet())

    init {
        arms.values.forEach { arm ->
            arm.inlet.onEach { value -> onArrival(arm, value) }
            // PN-9: through the inlet's edge-event fan-out, so this fold composes
            // with any other policy that also observes EdgeOpen/EdgeClose.
            arm.inlet.onEdgeEvent { link, event ->
                synchronized(lock) {
                    when (event) {
                        EdgeOpen -> edges[link.id] = EdgeState(arm, link, flushedHighWater.toMap())
                        EdgeClose -> edges[link.id]?.let(::closeEdge)
                    }
                    flushReady() // the shared condition just grew or shrank
                }
            }
            // WaveFrontier.attach's Suspension handler, mirrored at cell scope.
            ProtocolSupport.of(arm.inlet).handle(Protocols.Suspension) { link, message ->
                synchronized(lock) {
                    when (val notice = message as StallNotice) {
                        is StallNotice.Stall -> when {
                            !notice.recoverable -> reScope(link, notice)
                            mode == GlitchFreeCell.WaveMode.DEGRADE -> {
                                suspendedEdges += link.id
                                flushReady() // shrinking the frontier may complete waves
                            }
                            // WAIT: nothing — the wave stays held (heldWaves names it)
                            // until Resume + a real delta, a later wave, an ack, or a close.
                            else -> Unit
                        }

                        StallNotice.Resume -> {
                            suspendedEdges -= link.id
                            flushReady()
                        }
                    }
                }
            }
            ProtocolSupport.of(arm.inlet).handle(Protocols.Progress) { link, message ->
                val progress = message as Progress
                synchronized(lock) {
                    advanceWatermark(link.id, progress.sourceId, progress.thru)
                    noteAbsorbed(link, progress)
                    flushReady()
                }
            }
        }
    }

    override fun current(): Map<String, Any?> = latest.views

    override fun onChange(listener: (Map<String, Any?>) -> Unit) {
        synchronized(lock) {
            listeners += listener
            // late-join catch-up: one submission with the materialized composite,
            // made under the lock so no interleaving publication's submission can
            // land out of order around it.
            val snapshot = latest.views
            dispatchIfOpen { listener(snapshot) }
        }
    }

    /** The latest published [AlignedComposite] — the same swap [current] reads. */
    fun composite(): AlignedComposite = latest

    /**
     * [onChange] for the full [AlignedComposite]: the same catch-up (one
     * submission with the current composite, under the lock, on the same
     * dispatcher) and the same per-publication firing — a publication notifies
     * [onChange] and [onComposite] listeners from one submission, so both see
     * the same wave.
     */
    fun onComposite(listener: (AlignedComposite) -> Unit) {
        synchronized(lock) {
            compositeListeners += listener
            val snapshot = latest
            dispatchIfOpen { listener(snapshot) }
        }
    }

    /**
     * T08 finding 2: checked accessor over the named composite — mirrors
     * [CompositeSink.get], including its erasure-level caveat (it confirms the
     * *shape*, not the element/key/value type argument).
     */
    inline fun <reified T> get(name: String): T {
        val snap = current()
        require(name in snap) { "no observe named '$name' (available: ${snap.keys})" }
        val value = snap.getValue(name)
        return value as? T ?: throw IllegalStateException(
            "'$name' was registered as ${registeredAs[name] ?: "unknown"} " +
                "(actual snapshot type ${value?.let { it::class.simpleName } ?: "null"}), " +
                "requested ${T::class.simpleName ?: T::class}",
        )
    }

    /**
     * Registration (zvq3e-D3), in order: a closed sink abandons with
     * `SINK_CLOSED`; a wave at or behind the flushed frontier is already
     * visible; past the bound, `BOUND_EXCEEDED` and nothing registered;
     * otherwise the handle waits for [completeHandles].
     */
    override fun visibilityOf(wave: Timestamp): CompletableFuture<Visibility> {
        synchronized(lock) {
            if (closed) {
                return CompletableFuture.failedFuture(VisibilityAbandoned(VisibilityAbandoned.Reason.SINK_CLOSED, wave))
            }
            val flushed = flushedHighWater[wave.sourceId]
            if (flushed != null && wave.counter <= flushed) {
                return CompletableFuture.completedFuture(Visible(Timestamp(wave.sourceId, flushed)))
            }
            if (outstanding.get() >= maxOutstandingHandles) {
                return CompletableFuture.failedFuture(VisibilityAbandoned(VisibilityAbandoned.Reason.BOUND_EXCEEDED, wave))
            }
            val future = CompletableFuture<Visibility>()
            handles.getOrPut(wave.sourceId) { TreeMap() }.getOrPut(wave.counter) { mutableListOf() } += future
            outstanding.incrementAndGet()
            return future
        }
    }

    /**
     * The single completion point (zvq3e-D4): every handle for
     * `timestamp.sourceId` with counter ≤ `timestamp.counter`, completed with
     * the one outcome this retirement earned. Called from [flushReady] after
     * the wave's publish, so [latest] already reflects it. Completion is
     * submitted to the default async pool (zvq3e-D5).
     */
    private fun completeHandles(timestamp: Timestamp, effective: Boolean, dropped: Set<DroppedEdge>) {
        val bySource = handles[timestamp.sourceId] ?: return
        val due = bySource.headMap(timestamp.counter, true)
        if (due.isEmpty()) return
        val futures = due.values.flatten()
        due.clear()
        if (bySource.isEmpty()) handles.remove(timestamp.sourceId)
        outstanding.addAndGet(-futures.size)
        val outcome: Visibility = when {
            dropped.isNotEmpty() -> VisibleDegraded(timestamp, dropped)
            effective -> Visible(timestamp)
            else -> VisibleVacuously(timestamp)
        }
        futures.forEach { future -> CompletableFuture.runAsync { future.complete(outcome) } }
    }

    /** Removes every registered handle, paired with its wave, and zeroes the counter. Lock-guarded. */
    private fun drainHandles(): List<Pair<Timestamp, CompletableFuture<Visibility>>> {
        val drained = handles.flatMap { (sourceId, byCounter) ->
            byCounter.flatMap { (counter, futures) -> futures.map { Timestamp(sourceId, counter) to it } }
        }
        handles.clear()
        outstanding.set(0)
        return drained
    }

    private fun abandon(handles: List<Pair<Timestamp, CompletableFuture<Visibility>>>, reason: VisibilityAbandoned.Reason) {
        handles.forEach { (wave, future) ->
            val cause = VisibilityAbandoned(reason, wave)
            CompletableFuture.runAsync { future.completeExceptionally(cause) }
        }
    }

    // ---- arrival: buffer the waved, install the unwaved ----

    private fun onArrival(arm: Arm, value: Any) {
        synchronized(lock) {
            val ctx = CurrentContext.get()
            // Unwaved traffic (push catch-up, context-free sends) and pull-catch-up
            // baselines (93 I-24) are never wave positions: installed as arm state
            // immediately, admitted to no completeness set.
            if (ctx == null || ctx.baseline != null) return@synchronized install(arm, value)

            // Scoped to THIS arm's edges: two views may legitimately observe the
            // same outlet under different folds, so a cell-wide `singleOrNull` on
            // the source port would find two edges and give up on both.
            val edge = edges.values.singleOrNull { it.open && it.arm === arm && it.link.from == ctx.sourcePort }
            if (edge == null) {
                unmatchedDeltas++
                return@synchronized install(arm, value)
            }
            val timestamp = ctx.timestamp
            val floor = edge.floors[timestamp.sourceId] ?: Long.MIN_VALUE
            val flushed = flushedHighWater[timestamp.sourceId] ?: Long.MIN_VALUE
            if (timestamp.counter <= floor || timestamp.counter <= flushed) {
                // The wave already completed without this edge (a late-opened arm,
                // a resume replay): install late rather than buffer forever —
                // catch-up, spec 21 — and never lose the delta. The watermark still
                // records that this edge has now delivered the wave, which is what
                // lets a DEGRADE-dropped edge stop being disclosed once caught up.
                advanceWatermark(edge.link.id, timestamp.sourceId, timestamp.counter)
                return@synchronized install(arm, value)
            }
            advanceWatermark(edge.link.id, timestamp.sourceId, timestamp.counter)
            pending.getOrPut(timestamp) { mutableListOf() } += Buffered(arm, value)
            flushReady()
        }
    }

    /**
     * Fold a delta that belongs to no wave set straight into its view, publishing
     * if effective. It drops no edge of its own, so its composite discloses only
     * the edges a DEGRADE shrink has left behind the frontier (none, outside
     * DEGRADE).
     */
    private fun install(arm: Arm, delta: Any) {
        if (arm.view.apply(delta)) publish(stillBehind())
    }

    // ---- the fold's mechanics, mirrored from WaveFrontier / CoalescingCombineCell ----

    /**
     * A wave known only from an absorb-ack (CP-A3): an arm consumed it and
     * emitted nothing. Buffering the empty wave is what lets a wave *every* arm
     * absorbs still retire here — it completes with no deltas, publishes nothing
     * (effective-only), and advances [flushedHighWater] — instead of being
     * invisible and leaving a straggler to re-buffer against a wave that is
     * conceptually already past.
     */
    private fun noteAbsorbed(link: Link, progress: Progress) {
        val edge = edges[link.id] ?: return
        if (!edge.open || edge.link.role != LinkRole.Consume) return
        if (progress.thru <= (flushedHighWater[progress.sourceId] ?: Long.MIN_VALUE)) return
        if ((edge.floors[progress.sourceId] ?: Long.MIN_VALUE) >= progress.thru) return
        pending.getOrPut(Timestamp(progress.sourceId, progress.thru)) { mutableListOf() }
    }

    private fun advanceWatermark(edgeId: UUID, sourceId: UUID, counter: Long) {
        watermark.getOrPut(edgeId) { mutableMapOf() }.merge(sourceId, counter, ::maxOf)
    }

    private fun isSettled(edgeId: UUID, timestamp: Timestamp): Boolean =
        (watermark[edgeId]?.get(timestamp.sourceId) ?: Long.MIN_VALUE) >= timestamp.counter

    /**
     * The expected edges of a wave — **the union across every named inlet**,
     * which is the one line that generalizes `WaveFrontier`'s per-inlet
     * condition to a many-named-inlet composite. Every open `Consume` edge whose
     * floor for this source is below the wave's counter must settle before the
     * composite is assembled. Observe-role edges (negotiated `tap`/`streamTo`
     * announcements, PN-10) never carry consume waves and so never gate one.
     */
    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { isBaseExpected(it, timestamp) }
        .filter { it.link.id !in suspendedEdges }
        .map { it.link.id }
        .toSet()

    /** The completeness predicate before the DEGRADE suspension filter: open, `Consume`, above its floor. */
    private fun isBaseExpected(edge: EdgeState, timestamp: Timestamp): Boolean =
        edge.link.role == LinkRole.Consume &&
            edge.open &&
            (edge.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter

    private fun ready(timestamp: Timestamp): Boolean = expectedEdges(timestamp).all { isSettled(it, timestamp) }

    /**
     * Records [edge] as dropped from every wave it is expected for and has not
     * settled — every pending wave, or (with [through]) the pending waves of that
     * source up to it plus [through] itself even if not yet pending. Called at the
     * moment of a close or a re-scope, **before** the watermark moves or the edge
     * closes, since afterwards the edge would no longer look unsettled.
     */
    private fun recordExclusions(edge: EdgeState, through: Timestamp?) {
        val waves = if (through == null) {
            pending.keys.toList()
        } else {
            val flushed = flushedHighWater[through.sourceId] ?: Long.MIN_VALUE
            pending.keys.filter { it.sourceId == through.sourceId && it.counter <= through.counter } +
                listOfNotNull(through.takeIf { it.counter > flushed })
        }
        val dropped = DroppedEdge(edge.arm.name, edge.link.id)
        for (wave in waves.toSet()) {
            if (isBaseExpected(edge, wave) && !isSettled(edge.link.id, wave)) {
                exclusions.getOrPut(wave) { mutableSetOf() } += dropped
            }
        }
    }

    /** `EdgeClose`, or a terminal stall without a timestamp: the edge leaves the condition. */
    private fun closeEdge(edge: EdgeState) {
        if (!edge.open) return
        recordExclusions(edge, through = null)
        edge.open = false
    }

    /**
     * RE-SCOPE, `WaveFrontier.reScope` mirrored: with a known [StallNotice.Stall.timestamp]
     * only that edge's watermark advances past the poisoned wave (the edge stays
     * open); without one the edge closes. [violations] is incremented before
     * [onViolation] runs.
     */
    private fun reScope(link: Link, stall: StallNotice.Stall) {
        val timestamp = stall.timestamp
        val edge = edges[link.id]
        if (timestamp != null) {
            edge?.let { recordExclusions(it, through = timestamp) }
            advanceWatermark(link.id, timestamp.sourceId, timestamp.counter)
        } else {
            edge?.let(::closeEdge)
        }
        violations++
        val detail = if (timestamp != null) " wave $timestamp" else ""
        onViolation(GlitchViolation("edge ${link.id} ${stall.reason}$detail — advanced past the poisoned wave"))
        flushReady()
    }

    /**
     * Release every complete wave in per-source counter order (per-link FIFO
     * makes completion monotone per source), applying its buffered deltas to
     * their own views and publishing at most one composite per wave.
     *
     * A wave's dropped set is fixed here, at release: the exclusions recorded for
     * it (re-scoped or closed while it was held), plus every base-expected edge
     * that is DEGRADE-suspended and has not settled it. Each such edge is then
     * remembered as [behind] for that source until it catches up.
     */
    private fun flushReady() {
        val ready = pending.keys
            .filter { timestamp -> ready(timestamp) }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            val dropped = LinkedHashSet<DroppedEdge>()
            exclusions.remove(timestamp)?.let { dropped += it }
            edges.values
                .filter { it.link.id in suspendedEdges && isBaseExpected(it, timestamp) && !isSettled(it.link.id, timestamp) }
                .forEach { dropped += DroppedEdge(it.arm.name, it.link.id) }
            dropped.forEach { behind.getOrPut(it.linkId) { mutableSetOf() } += timestamp.sourceId }
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            var effective = false
            // arrival order within the wave; the wave itself is the alignment unit
            for (buffered in wave) if (buffered.arm.view.apply(buffered.delta)) effective = true
            val disclosed = dropped + stillBehind()
            if (effective) publish(disclosed)
            completeHandles(timestamp, effective, dropped = disclosed)
        }
        // A stall for a wave that never arrives must not leak its exclusion.
        exclusions.keys.removeIf { it !in pending && it.counter <= (flushedHighWater[it.sourceId] ?: Long.MIN_VALUE) }
    }

    /**
     * The open edges a DEGRADE shrink released waves without and whose own
     * watermark has not yet reached the flushed high-water of that source — their
     * views lag the composite frontier. Resolved entries (caught up, or the edge
     * closed) are forgotten here.
     */
    private fun stillBehind(): Set<DroppedEdge> {
        val result = LinkedHashSet<DroppedEdge>()
        val iterator = behind.entries.iterator()
        while (iterator.hasNext()) {
            val (edgeId, sources) = iterator.next()
            val edge = edges[edgeId]
            if (edge == null || !edge.open) {
                iterator.remove()
                continue
            }
            sources.removeIf { source ->
                (watermark[edgeId]?.get(source) ?: Long.MIN_VALUE) >= (flushedHighWater[source] ?: Long.MIN_VALUE)
            }
            if (sources.isEmpty()) iterator.remove() else result += DroppedEdge(edge.arm.name, edgeId)
        }
        return result
    }

    /**
     * The one composite snapshot: assembled under [lock], immutable, published
     * once. The [latest] swap *is* the publication — it is what a [current]
     * read sees, and it happens whether or not anyone is listening, so an
     * aligned view's visibility never depends on the dispatcher. [onChange]
     * and [onComposite] listeners are notified from one submission.
     */
    private fun publish(disclosed: Set<DroppedEdge>) {
        val snapshot = assemble(disclosed)
        latest = snapshot
        val fired = listeners.toList()
        val firedComposite = compositeListeners.toList()
        // Nothing to notify ⇒ no submission, so an unobserved aligned view never
        // mints a dispatcher (see the class doc). Without this the lazy mint
        // would merely relocate from construction to the first released wave.
        if (fired.isNotEmpty() || firedComposite.isNotEmpty()) {
            dispatchIfOpen {
                fired.forEach { it(snapshot.views) }
                firedComposite.forEach { it(snapshot) }
            }
        }
    }

    private fun assemble(dropped: Set<DroppedEdge>): AlignedComposite = AlignedComposite(
        views = arms.entries.associateTo(LinkedHashMap()) { (name, arm) -> name to arm.view.current() },
        frontier = flushedHighWater.toMap(),
        droppedEdges = dropped.toSet(),
        alignedFrom = emptyMap(),
    )

    /**
     * Submits [block] to [dispatcher] unless [close]d, minting the dispatcher on
     * first use; silently drops on a close race.
     *
     * **Must be called holding [lock]** — asserted, not merely documented,
     * because that is the whole race argument for the lazy mint: mint, listener
     * registration, wave release and snapshot swap all happen inside the same
     * monitor, so two concurrent registrations cannot each create an executor
     * and no submission can be interleaved out of its total order.
     */
    private fun dispatchIfOpen(block: () -> Unit) {
        check(Thread.holdsLock(lock)) { "dispatchIfOpen must be called under the sink lock" }
        if (closed) return
        val target = dispatcher ?: newDispatcher().also { fresh ->
            dispatcher = fresh
            // First act of a post-[reopen] dispatcher: wait out the superseded
            // one, so an invocation queued before the restart can never be
            // overtaken by one submitted after it.
            draining?.let { previous ->
                draining = null
                fresh.execute { runCatching { previous.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) } }
            }
        }
        try {
            target.execute(block)
        } catch (_: RejectedExecutionException) {
            // raced a concurrent close(); nothing left to notify.
        }
    }

    // ---- lifecycle ----

    /**
     * Stops the listener-dispatch executor if one was ever minted (a
     * never-observed sink has no thread to stop, so this is a pure flag flip).
     * Idempotent. Wired into [onDeactivate] (which the host calls on despawn),
     * so a despawned sink's dispatch thread does not outlive it; a caller that
     * never despawns the sink may call this directly at shutdown.
     *
     * Every outstanding write-visibility handle is abandoned with
     * [VisibilityAbandoned.Reason.SINK_CLOSED] (zvq3e-D6); [onDeactivate]
     * abandons them first with `HOST_SHUTDOWN`, so this finds none on that path.
     */
    fun close() {
        val (doomed, abandoned) = synchronized(lock) {
            if (closed) return
            closed = true
            dispatcher to drainHandles()
        }
        doomed?.shutdown()
        abandon(abandoned, VisibilityAbandoned.Reason.SINK_CLOSED)
    }

    /**
     * Reopens a [close]d sink so it can dispatch again — [ObserveCell.reopen]'s
     * reason verbatim: [onDeactivate] is not only a despawn hook (`RESTART` and
     * migration drain call it too), and without this one restart would leave the
     * sink permanently deaf.
     *
     * Ordering across the reopen: the replacement dispatcher's first act is to
     * wait for the old one to drain, so a listener invocation queued before the
     * restart can never be overtaken by one submitted after it.
     *
     * The replacement is *not* minted here: reopen only clears the closed flag
     * and parks the superseded executor in [draining], leaving the mint to the
     * next actual submission ([dispatchIfOpen], which chains the drain-wait
     * there). A restart or migration of an aligned view nobody observes
     * therefore stays thread-free, exactly as its first activation was.
     */
    private fun reopen() {
        synchronized(lock) {
            if (!closed) return
            // Whatever close() shut down becomes the next mint's predecessor.
            // Only overwrite when there is something to hand off, so a
            // close/reopen cycle that dispatches nothing in between cannot lose
            // an earlier still-draining executor.
            dispatcher?.let { draining = it }
            dispatcher = null
            closed = false
        }
    }

    override fun onActivate(ctx: CellContext) {
        reopen()
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the transient wave
     * buffer is dropped — a partially collected wave was never observed by the
     * app. Floors, watermarks and flushed high-water record what genuinely
     * happened and stay valid. Write-visibility handles are transient too: each
     * is abandoned with [VisibilityAbandoned.Reason.HOST_SHUTDOWN] before
     * [close] runs, and [snapshot]/[restore] never carry them.
     */
    override fun onDeactivate(ctx: CellContext) {
        val abandoned = synchronized(lock) {
            pending.clear()
            drainHandles()
        }
        abandon(abandoned, VisibilityAbandoned.Reason.HOST_SHUTDOWN)
        close()
    }

    override fun snapshot(): Serializable = synchronized(lock) {
        arms.entries.associateTo(LinkedHashMap<String, Serializable>()) { (name, arm) -> name to arm.view.snapshot() }
    }

    override fun restore(state: Serializable) {
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val folds = state as Map<String, Serializable>
            folds.forEach { (name, fold) -> arms[name]?.view?.restore(fold) }
            latest = assemble(dropped = emptySet())
        }
    }
}

/**
 * One published aligned composite, immutable ([KE2-12]: a composite published
 * before a frontier shrink is never retroactively marked).
 *
 * @property views the named views' folds — exactly what [AlignedCompositeCell.current] returns.
 * @property frontier the sink's flushed high-water per source at assembly: the
 *   per-source frontier this composite is the output for.
 * @property droppedEdges every contributing edge this composite was released
 *   without (see [AlignedCompositeCell]'s class doc, §Stalled edges); empty for
 *   a fully aligned composite.
 * @property alignedFrom always empty in this delivery: F5 (`computenet-zvdt1`)
 *   adds the one write that fills it.
 */
data class AlignedComposite(
    val views: Map<String, Any?>,
    val frontier: Map<UUID, Long>,
    val droppedEdges: Set<DroppedEdge>,
    val alignedFrom: Map<UUID, Long>,
)

/**
 * Declarative builder for [observeAligned]: names one fold per source outlet —
 * [ObserveAllBuilder]'s registrars, minus the per-outlet [ObserveCell] spawn.
 * The named views become the aligned cell's per-name inlets, so a name is both
 * the composite key and the inlet's port name.
 *
 * The untyped `CellRef` registrars mirror [ObserveAllBuilder]'s original erased
 * form; the additive typed overloads accept a [TypedRef] minted by
 * [civictech.cell.graph.refAs] at graph-build time, so wiring a wrong-shaped
 * source is a compile error rather than an `Any?` fold. As there, a JVM
 * signature clash forces a distinct `@JvmName` per typed overload.
 */
class AlignedObserveBuilder internal constructor() {

    internal class Spec(val source: CellRef, val outletName: String, val view: View<*, *>, val kind: String)

    internal val specs = LinkedHashMap<String, Spec>()

    private fun add(name: String, source: CellRef, outletName: String, view: View<*, *>, kind: String) {
        require(specs.put(name, Spec(source, outletName, view, kind)) == null) { "duplicate observe name '$name'" }
    }

    /** Fold a `SetDelta` outlet into a `Set` under [name]. */
    fun set(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, source, outletName, View.set<Any?>(), "set")

    /** Fold a `MapDelta` outlet into a `Map` under [name]. */
    fun map(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, source, outletName, View.map<Any?, Any?>(), "map")

    /** Fold a per-key count `MapDelta` outlet into counts under [name]. */
    fun count(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, source, outletName, View.count<Any?>(), "count")

    /** Typed [set]: [source]'s element type flows from a `TypedRef<SetApi<E>>`. */
    @JvmName("setFromSetApi")
    fun <E> set(name: String, source: TypedRef<out SetApi<E>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.set<E>(), "set")

    /** Typed [set]: [source]'s element type flows from a `TypedRef<QuorumSetApi<E>>`. */
    @JvmName("setFromQuorumSetApi")
    fun <E> set(name: String, source: TypedRef<out QuorumSetApi<E>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.set<E>(), "set")

    /** Typed [set]: [source]'s element type flows from a `TypedRef<FilterSetApi<E>>`. */
    @JvmName("setFromFilterSetApi")
    fun <E> set(name: String, source: TypedRef<out FilterSetApi<E>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.set<E>(), "set")

    /** Typed [map]: [source]'s key/value types flow from a `TypedRef<MapApi<K, V>>`. */
    @JvmName("mapFromMapApi")
    fun <K, V> map(name: String, source: TypedRef<out MapApi<K, V>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.map<K, V>(), "map")

    /** Typed [count]: [source]'s key type flows from a `TypedRef<GroupByApi<E, K, Long>>`. */
    @JvmName("countFromGroupByApi")
    fun <E, K> count(name: String, source: TypedRef<out GroupByApi<E, K, Long>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.count<K>(), "count")
}

/**
 * Observe several outlets as one **wave-aligned** composite snapshot — the
 * glitch-free sibling of [observeAll]:
 *
 * ```
 * val view = host.observeAligned {
 *     set("items",    refs.items)
 *     set("filtered", refs.filtered)
 *     count("byDay",  refs.byDay)
 * }
 * view.current()   // { items, filtered, byDay } — never assembled from mixed waves
 * ```
 *
 * Spawns one [AlignedCompositeCell] carrying one inlet per named view and
 * connects each source outlet to its own inlet (whose `onLinked` catch-up seeds
 * that view's arm state). Every published composite is the correct composite for
 * one settled per-source frontier of this replica's inputs (`[22-OBS-01]`), and
 * a composite for wave `(s, t)` is assembled only once every contributing view
 * has settled it (`[22-OBS-02]`).
 *
 * Choose this when a mixed-wave read is a *correctness* problem (an SSE frame or
 * a `/state` document whose named views must agree); keep [observeAll] when an
 * arm may stall and a stale-but-prompt read beats a delayed aligned one —
 * [AlignedCompositeCell] defaults to WAIT and holds a wave until every arm
 * settles it (see its class doc for the phantom-expected-edge caveat); pass
 * [mode] `DEGRADE` to shrink the frontier on a recoverable stall instead, with
 * the dropped edges disclosed on [AlignedCompositeCell.composite].
 */
fun Use<HostManagementApi>.observeAligned(
    maxOutstandingHandles: Int = 1024,
    mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT,
    onViolation: (GlitchViolation) -> Unit = {},
    block: AlignedObserveBuilder.() -> Unit,
): AlignedCompositeCell {
    val builder = AlignedObserveBuilder().apply(block)
    val cell = AlignedCompositeCell(
        views = builder.specs.mapValues { it.value.view },
        registeredAs = builder.specs.mapValues { it.value.kind },
        maxOutstandingHandles = maxOutstandingHandles,
        mode = mode,
        onViolation = onViolation,
    )
    call.spawn(cell)
    builder.specs.forEach { (name, spec) ->
        val result = call.connect(spec.source, spec.outletName, cell.ref, name)
        check(result !is LinkResult.Rejected) {
            "observeAligned: link ${spec.source}.${spec.outletName} -> '$name' rejected: " +
                (result as LinkResult.Rejected).reason
        }
    }
    return cell
}

/** Convenience for the common case of observing cells on this [ManagedHost]. */
fun ManagedHost.observeAligned(
    maxOutstandingHandles: Int = 1024,
    mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT,
    onViolation: (GlitchViolation) -> Unit = {},
    block: AlignedObserveBuilder.() -> Unit,
): AlignedCompositeCell = managementInlet.observeAligned(maxOutstandingHandles, mode, onViolation, block)
