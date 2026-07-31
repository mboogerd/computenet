package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Timestamp
import civictech.cell.data.KeyedSetApi
import civictech.cell.data.MapApi
import civictech.cell.data.SetApi
import civictech.cell.data.op.CombineLatestApi
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.FlatMapSetApi
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.IntersectSetApi
import civictech.cell.data.op.JoinApi
import civictech.cell.data.op.JoinSetApi
import civictech.cell.data.op.LookupJoinApi
import civictech.cell.data.op.MergeableGroupByCell
import civictech.cell.data.op.PresenceCountApi
import civictech.cell.data.op.QuorumSetApi
import civictech.cell.data.op.SemiJoinApi
import civictech.cell.data.op.UnionSetApi
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.cell.partition.ShardCell
import civictech.nature.ContractRegistry
import civictech.nature.PortDirection
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One consistent read of a cell's state, plus the metadata the contract's
 * `CellState` / `state.summary` carry alongside it.
 */
internal data class StateReading(
    /** [CellState.VIEW] or [CellState.SNAPSHOT]. */
    val kind: String,
    val value: Any?,
    val frontier: Timestamp?,
    val staleMs: Long,
)

/**
 * One published `state.summary` window for one observed cell (V1A-BE).
 *
 * [reading] is built *at publish time*, never at change time: that is what
 * makes `staleMs` the honest age of the last effective change on the wire, so
 * it decreases exactly in a window where something settled and grows by a full
 * window across quiet ones. [changes] is how many settled effective changes
 * this window coalesced — 0 for a quiet window.
 */
internal data class StateSummary(
    val ref: CellRef,
    val reading: StateReading,
    val changes: Long,
)

/**
 * The `Stateful.snapshot()` fallback seam.
 *
 * **Not wired in M1, deliberately.** The ticket asked for a *host-routed*
 * snapshot: `Stateful.snapshot()` must run on the owning host's execution
 * context, because off-thread it races the cell's own fold. Routing it there
 * needed one of two things the kernel did not expose at the time —
 *
 * - the hosted `Cell` instance (`ManagedHost.cells` is private, and
 *   `LocationRegistry.describe` deliberately hands back only the `Class`), or
 * - a way to run a block on the host's management band
 *   (`ManagedHost.enqueueAwaiting` is private; `HostManagementApi` has no
 *   general-purpose form, and `HostedCellProxy` answers `snapshot()` with
 *   `null` because it is neither `getRef` nor a port accessor).
 *
 * Both were kernel edits, which M1's ticket excluded without orchestrator
 * sign-off. So the seam was declared, the whole `kind: "snapshot"` path behind
 * it was implemented and tested, and the shipped default reported
 * [CellState.UNAVAILABLE] rather than reading a live cell from the HTTP
 * thread — the one thing the ticket explicitly warned against.
 *
 * **M5 update — the accessor landed.** `ManagedHost.snapshotOf(ref)` shipped
 * with M5-SEARCH, which needed a host-routed read for content search; it
 * returns a `CompletableFuture` rather than the `Serializable?` predicted here,
 * so the caller owns the deadline instead of inheriting the scheduler's.
 * [DataSearch] calls the accessor directly for that reason.
 *
 * **V0-BE update — wired.** [InspectorServer]'s shipped [InspectorServer.snapshots]
 * default now routes through [ManagedHost.snapshotOf] under a bounded wait
 * (`InspectorServer.SNAPSHOT_WAIT_MS`, the same pattern [DataSearch.read] uses
 * for the identical accessor), so `GET /cell/{ref}/state` for an *unobserved*
 * `Stateful` cell answers `kind: "snapshot"` instead of always
 * [CellState.UNAVAILABLE]. A cell with no local host, a non-`Stateful` cell,
 * or a read that misses the deadline all still resolve to null here, which
 * this class turns into "no fallback" exactly as before.
 *
 * **V1C-BE update — no longer the first seam tried.** [BoundedReadSource] below
 * answers `GET /cell/{ref}/state` first, and this remains the fallback for a
 * ref that has no local host at all and for a caller that installed
 * [BoundedReadSource.Unavailable]. Nothing about this interface changed: a
 * bounded read is opt-in *beside* a whole copy, exactly as `BoundedStateful` is
 * opt-in beside `Stateful`.
 */
fun interface SnapshotSource {
    /** [ref]'s snapshot, captured on its host's execution context, or null. */
    fun snapshotOf(ref: CellRef): Serializable?

    companion object {
        /** The shipped default: no host-routed snapshot exists yet (see the interface doc). */
        val Unavailable = SnapshotSource { null }
    }
}

/**
 * V1C-BE — the **bounded** state read's seam: `ManagedHost.readState`'s half of
 * `GET /cell/{ref}/state`, and a sibling of [SnapshotSource] rather than a
 * widening of it.
 *
 * Opt-in-beside, for the same reason `BoundedStateful` sits beside `Stateful`:
 * [SnapshotSource] is public API with installed stand-ins in three existing
 * tests, and widening it would make a paged read a precondition for standing in
 * for a whole copy. A caller that installs [Unavailable] here gets exactly the
 * shipped M5/V0 behaviour back — which is how the whole-copy labelling path
 * stays testable now that the data-cell family is `BoundedStateful`.
 *
 * **The future, not the value.** Unlike [SnapshotSource] this seam hands back
 * `ManagedHost.readState`'s own `CompletableFuture` and lets the caller own the
 * deadline (the ticket's sketch returned a resolved `StateReadResult?`). That is
 * a deliberate deviation with one concrete reason: a resolved value cannot
 * distinguish "this seam has nothing to say" from "the read missed its bound",
 * and the two have different answers — the first falls through to
 * [Observations.snapshotReading], the second must **not**, because falling
 * through would spend a second deadline on the same request. With the future
 * here, `null` means only "no local host for this ref" and the single
 * `InspectorServer.SNAPSHOT_WAIT_MS` deadline is applied in exactly one place.
 */
fun interface BoundedReadSource {
    /**
     * [ref]'s next page for [request], to be captured on its host's execution
     * context; null when this seam did not answer at all (no local host, or a
     * caller that installed [Unavailable]).
     */
    fun readState(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult>?

    companion object {
        /** No bounded read: every `GET state` falls through to [SnapshotSource]. */
        val Unavailable = BoundedReadSource { _, _ -> null }
    }
}

/**
 * Server-side state observations — the explicit subscription model invariant P6
 * demands ("browsing must not subscribe"). Nothing here runs unless a client
 * asks for it: `POST /cell/{ref}/observe` creates one, `DELETE` releases it, an
 * idle deadline releases the ones a client forgot.
 *
 * ### Why observing is not free
 *
 * An observation is a real `ObserveCell` spawned into the target's host and
 * linked to its outlet. That link raises attention on the upstream cone and can
 * un-park it (P6: "observation is causal"), so a leaked sink does not merely
 * waste memory — it keeps a cone awake. Release therefore does both halves:
 *
 * 1. `link.unlink()` — `ManagedHost.connect` registers
 *    `onUnlink { registry.unlink(id) }`, so this detaches the producer's
 *    consumer entry *and* retracts the edge from the topology index (the
 *    inspector's own `topology.link removed` delta follows);
 * 2. `despawn(sinkRef)` — unpublishes the sink, runs `onDeactivate`, which is
 *    `ObserveCell.close()` (its dispatch thread stops), and releases its ports.
 *
 * Both are needed. `despawn` alone leaves the outlet's consumer entry intact
 * (`FanOutlet.consumers` is keyed by port ref and only `unsubscribe`/`unlink`
 * removes it), so the producer would keep pushing into a despawned cell and the
 * edge would linger in the topology.
 *
 * This is also why the sink is built here from [ObserveCell] + `connect` rather
 * than through the `host.observe(...)` helper the ticket names: `observe`
 * discards the `LinkResult`, and without the `Link` step 1 above is impossible.
 * The two calls below are `observe`'s body verbatim, with the link kept.
 *
 * ### The summary window (V1A-BE)
 *
 * Summaries are **coalesced into a per-cell window**, on exactly the pattern
 * [FlowCollector] established for `flow.rates`:
 *
 * - one summary per open observation per [WINDOW_MS] window, however many
 *   settled effective changes landed in it — driven by [sample] off the
 *   inspector's single existing scheduler thread ([InspectorServer]'s `Tick`
 *   list), so this adds no thread and stays reachable through `tickAll()`;
 * - **published even when the window was quiet**, so a client's decay/aging
 *   logic keys on *received* windows rather than on silence (which would
 *   otherwise be indistinguishable from a released observation, a dropped
 *   frame, or a stopped server);
 * - exactly **one trailing window** when the observation is released — by
 *   `DELETE`, by [sweep], or by [close] — and then nothing at all. A closed
 *   inspector is silent, not one trailing frame short of it.
 *
 * The per-change path therefore no longer emits anything: [StampedView] records
 * the wave, the wall-clock instant and a change counter as the fold settles
 * (three cheap stores, no allocation, no lock the HTTP or graph side contends
 * on), and the window reads them. Nothing here polls, so P6 holds unchanged: a
 * window exists only for a cell someone explicitly observed, and disappears
 * with it.
 */
internal class Observations(
    private val registry: LocationRegistry,
    /** Non-blocking sink for one published window (see [sample]). */
    private val onSummary: (StateSummary) -> Unit,
    private val snapshots: SnapshotSource = SnapshotSource.Unavailable,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val lock = Any()
    private val open = LinkedHashMap<CellRef, Observation>()

    /**
     * Serializes *publication* — a scheduled window against the trailing
     * window a release owes — so "one trailing summary, then silence" survives
     * a `DELETE` landing mid-tick rather than holding only when the two happen
     * not to overlap. Held for the duration of one publish pass and never
     * across a management call (release does its unlink/despawn after leaving
     * it), so nothing that can block ever runs underneath it.
     *
     * Lock order is always [publishLock] → [lock] → the model's own monitor;
     * nothing takes [publishLock] from inside the model.
     */
    private val publishLock = Any()

    /** Refs with a live observation — diagnostics and tests. */
    val openRefs: Set<CellRef> get() = synchronized(lock) { open.keys.toSet() }

    /**
     * The `ObserveCell` sinks this inspector has spawned into the graph — its
     * own instruments. They are published cells like any other, so anything
     * enumerating the graph will find them; [DataSearch] excludes them (an
     * instrument reporting its own readings as results is the observer effect
     * wearing a search box), and [InspectorModel] excludes them from the
     * topology view and its component partition (M5-EVAL — a sink joining the
     * observed cell's component could displace its min-uuid id, kicking the
     * client out of the very graph it selected a cell in; an instrument is not
     * a subject).
     *
     * Registered **before** the sink is spawned: the registry's publish hook
     * fires during `spawn`, so a set derived from [open] — which is written
     * after — would race the model's exclusion check. A failed or released
     * observation removes its entry again.
     */
    val sinkRefs: Set<CellRef> get() = instruments
    private val instruments = java.util.concurrent.ConcurrentHashMap.newKeySet<CellRef>()

    /**
     * Start observing [ref], or renew the idle deadline if it is already
     * observed. False when the cell cannot be observed at all — it is not
     * locally hosted, has no generated descriptor, exposes no outlet, or emits
     * a delta shape no built-in [View] folds (a `CounterDelta`/`ListDelta`
     * producer, or a sink like `ObserveCell` that has no outlet at all).
     */
    fun start(ref: CellRef): Boolean {
        synchronized(lock) {
            open[ref]?.let {
                it.touch(clock())
                return true
            }
        }

        val host = registry.locate(ref) ?: return false
        val type = registry.describe(ref) ?: return false
        val descriptor = ContractRegistry.cellDescriptor(type) ?: return false
        val outlet = outletName(descriptor.ports.filter { it.direction == PortDirection.OUT }.map { it.name })
            ?: return false
        val view = StampedView(viewFor(type) ?: return false, clock)

        // `observe`'s body, with the link retained — see this class's doc.
        val sink = ObserveCell(view)
        val mgmt = host.managementInlet.call
        // before spawn: the publish hook must already see this ref as an
        // instrument, or the model would adopt it as a node (see [sinkRefs])
        instruments += sink.ref
        mgmt.spawn(sink)
        val result = mgmt.connect(ref, outlet, sink.ref, "inlet")
        if (result !is LinkResult.Connected) {
            mgmt.despawn(sink.ref)
            instruments -= sink.ref
            return false
        }

        val observation = Observation(ref, host, sink, sink.ref, result.link, view, clock())
        val existing = synchronized(lock) {
            open[ref] ?: run { open[ref] = observation; null }
        }
        if (existing != null) {
            // lost a concurrent POST race: keep the winner, release this one.
            // No trailing window: this observation never published one, and the
            // cell it names is still observed by the winner — a trailing
            // summary here would announce a release that did not happen.
            release(observation, announce = false)
            existing.touch(clock())
            return true
        }

        // No `sink.onChange` listener any more (V1A-BE). The producer's
        // late-join catch-up still runs — `connect` above pushed the baseline
        // through the fold — but it now lands as a counted change on
        // [StampedView] instead of as an immediate SSE frame, so the first
        // window this observation publishes already carries it. Nothing at all
        // sits on the per-change path except the fold's own three stores.
        return true
    }

    /** Release [ref]'s observation. False when it had none. */
    fun stop(ref: CellRef): Boolean {
        val observation = synchronized(lock) { open.remove(ref) } ?: return false
        release(observation)
        return true
    }

    /** Renew [ref]'s idle deadline — the contract's "matching `GET state`". */
    fun touch(ref: CellRef) {
        synchronized(lock) { open[ref] }?.touch(clock())
    }

    /**
     * V3-BE — [ref]'s current frontier stamp, or null when it is not observed
     * *or* has no wave position yet.
     *
     * The narrowest possible read for the wave-health evaluator: one volatile
     * read off [StampedView], no fold materialization (unlike [reading]) and —
     * critically — **no [touch]**. Renewing the idle deadline here would make a
     * diagnostic keep alive the very observation it is diagnosing, which is
     * exactly the "extend an observation's lifetime to keep a subject alive"
     * P6 forbids. A subject whose observation expires simply stops being one.
     *
     * A null answer for an *open* observation is meaningful and common: a
     * freshly-opened observation's state arrived as a catch-up baseline, and a
     * baseline is deliberately not a wave position (see [StampedView]). The
     * evaluator treats it as "not eligible", never as "at wave zero".
     */
    fun frontierOf(ref: CellRef): Timestamp? = synchronized(lock) { open[ref] }?.frontier

    /** A consistent read of [ref], or null when it is not observed. */
    fun reading(ref: CellRef): StateReading? {
        val observation = synchronized(lock) { open[ref] } ?: return null
        return observation.reading(observation.sink.current(), clock())
    }

    /**
     * The [SnapshotSource] fallback: a state read for a cell with no
     * observation. Null when no source is wired (the shipped default).
     */
    fun snapshotReading(ref: CellRef): StateReading? {
        val snapshot = snapshots.snapshotOf(ref) ?: return null
        return StateReading(CellState.SNAPSHOT, snapshot, frontier = null, staleMs = 0)
    }

    /**
     * One window: exactly one summary per open observation, published whether
     * or not anything settled in it. Runs on the inspector's scheduler thread
     * (`InspectorServer`'s `"stateSummary"` `Tick`), never on a graph thread.
     *
     * Each summary is *built* under [lock] — a handful of volatile reads plus
     * one `getAndSet` on the change counter, no blocking call — and published
     * outside it, so a slow consumer of [onSummary] can never delay a `POST
     * observe` or a fold.
     *
     * Nothing is published for a cell with no open observation: no polling,
     * no synthesis, no summary for a cell nobody asked about (P6).
     */
    fun sample() {
        synchronized(publishLock) {
            val windows = synchronized(lock) { open.values.mapNotNull { it.window(clock()) } }
            windows.forEach(onSummary)
        }
    }

    /**
     * Idle safety net: release observations no client has read for
     * [IDLE_RELEASE_MS]. A tab closed without a `DELETE` (or a client that
     * crashed mid-session) must not keep a cone's attention raised forever.
     */
    fun sweep() {
        val now = clock()
        val expired = synchronized(lock) {
            open.entries.filter { now - it.value.lastTouchedMs >= IDLE_RELEASE_MS }
                .onEach { open.remove(it.key) }
                .map { it.value }
        }
        expired.forEach(::release)
    }

    override fun close() {
        val all = synchronized(lock) { open.values.toList().also { open.clear() } }
        all.forEach(::release)
    }

    /**
     * The one trailing window a released observation owes its client, then
     * silence for that cell. Published *before* the sink is torn down — its
     * fold is still the authoritative value — and under [publishLock] so it
     * cannot be overtaken by a scheduled window for the same cell. The
     * `compareAndSet` in [Observation.trailingWindow] is what makes "exactly
     * one" structural rather than a consequence of who called [release].
     */
    private fun trailing(observation: Observation) {
        synchronized(publishLock) {
            (synchronized(lock) { observation.trailingWindow(clock()) } ?: return).let(onSummary)
        }
    }

    /**
     * Unlink then despawn — both halves, in that order, so the producer stops
     * pushing before the sink stops existing. Failures are swallowed: a release
     * racing a despawned target must not fail the request that asked for it.
     *
     * [trailing] is the summary feed's half of the same release, published
     * first and outside every management call.
     */
    private fun release(observation: Observation, announce: Boolean = true) {
        if (announce) trailing(observation) else observation.suppressTrailing()
        runCatching { observation.link.unlink() }
        runCatching { observation.host.managementInlet.call.despawn(observation.sinkRef) }
        // after despawn: a despawned ref never returns (each ObserveCell has a
        // fresh uuid), so removal cannot re-admit it, and the model's
        // unpublished() is a no-op for a node it never adopted
        instruments -= observation.sinkRef
    }

    private class Observation(
        val ref: CellRef,
        val host: ManagedHost,
        val sink: ObservationSink<*>,
        val sinkRef: CellRef,
        val link: Link,
        private val view: StampedView<*, *>,
        openedAtMs: Long,
    ) {
        @Volatile
        var lastTouchedMs: Long = openedAtMs
            private set

        /** Set once, by whichever of the three release paths gets there first. */
        private val trailed = AtomicBoolean(false)

        /** V3-BE — see [Observations.frontierOf]; one volatile read, nothing else. */
        val frontier: Timestamp? get() = view.frontier

        fun touch(now: Long) {
            lastTouchedMs = now
        }

        fun reading(value: Any?, now: Long) = StateReading(
            kind = CellState.VIEW,
            value = value,
            frontier = view.frontier,
            staleMs = (now - view.changedAtMs).coerceAtLeast(0),
        )

        /**
         * This observation's scheduled window, or null once its trailing one
         * has gone out. The reading is the *latest* one — `current()` is a
         * volatile read of an already-materialized immutable snapshot, and the
         * stamps come off [StampedView] — so a window never carries an
         * intermediate value from earlier in it.
         */
        fun window(now: Long): StateSummary? =
            if (trailed.get()) null else StateSummary(ref, reading(sink.current(), now), view.drainChanges())

        /** The last window this observation will ever publish; null if it already did. */
        fun trailingWindow(now: Long): StateSummary? =
            if (trailed.compareAndSet(false, true)) {
                StateSummary(ref, reading(sink.current(), now), view.drainChanges())
            } else {
                null
            }

        /** Retire without a trailing window — the lost-POST-race path only. */
        fun suppressTrailing() {
            trailed.set(true)
        }
    }

    internal companion object {
        /** Contract/ticket: "auto-release after 5 min without a matching `GET state`". */
        const val IDLE_RELEASE_MS = 5 * 60 * 1000L

        /**
         * The `state.summary` coalescing window (V1A-BE). Deliberately *the*
         * flow window rather than a second, independently-drifting constant:
         * both feeds exist so a client can key its freshness/decay logic on
         * received windows, and a client aging two feeds against two different
         * periods would be answering the same question twice, differently.
         */
        const val WINDOW_MS = FlowCollector.WINDOW_MS

        /**
         * Which outlet to fold. The kernel's convention is a single outlet
         * named `outlet` (every `@CellBase` API in `civictech.cell.data` and
         * `.data.op` declares exactly that); a cell with one differently-named
         * OUT port is still unambiguous, and one with several is not — folding
         * an arbitrary choice of them would be a guess, so it is refused.
         */
        fun outletName(outPorts: List<String>): String? = when {
            DEFAULT_OUTLET in outPorts -> DEFAULT_OUTLET
            outPorts.size == 1 -> outPorts.single()
            else -> null
        }

        private const val DEFAULT_OUTLET = "outlet"

        private val SET_OUTLETS = listOf(
            SetApi::class.java, KeyedSetApi::class.java, UnionSetApi::class.java,
            IntersectSetApi::class.java, FilterSetApi::class.java, QuorumSetApi::class.java,
            JoinSetApi::class.java, SemiJoinApi::class.java, FlatMapSetApi::class.java,
            ShardCell::class.java,
        )

        private val MAP_OUTLETS = listOf(
            MapApi::class.java, JoinApi::class.java, LookupJoinApi::class.java,
            CombineLatestApi::class.java, PresenceCountApi::class.java,
        )

        /**
         * The best-matching built-in fold for [type]'s outlet, keyed on the
         * generated `@CellBase` API the cell implements — the delta *shape* the
         * outlet emits, which is the only thing a `View` cares about:
         *
         * - `SetDelta` producers fold with `View.set()`;
         * - `MapDelta` producers with `View.map()`, except a `GroupByApi` (and
         *   its mergeable sibling `MergeableGroupByCell` — same per-key
         *   generic-aggregate shape, `MapDelta<K, A>` out, just replicated by
         *   commutative-associative merge instead of recomputed from convergent
         *   membership), whose per-key aggregate is the shape `View.count()`
         *   names (its `CountView` is a `MapView` with a zero-defaulting
         *   accessor, so the fold is identical either way — this only keeps
         *   the sink honest about what it is folding);
         * - anything else (`CounterDelta`, `ListDelta`, a sink with no outlet)
         *   has no built-in fold, and is reported unobservable rather than
         *   forced into a mismatched one.
         *
         * `MergeableGroupByCell` and `ShardCell` declare no separate `@CellBase`
         * Api marker interface of their own (plain `Cell` implementations with
         * `registerPort` calls) — so unlike every other entry here, their table
         * membership is keyed on the concrete cell class, not an Api type.
         *
         * The `Any?` type arguments mirror `ObserveAllBuilder`'s untyped
         * overloads: the element type is erased in the fold, and the encoder
         * takes `Any?` anyway.
         */
        fun viewFor(type: Class<*>): View<Any, Any?>? {
            @Suppress("UNCHECKED_CAST")
            return when {
                GroupByApi::class.java.isAssignableFrom(type) -> View.count<Any?>()
                MergeableGroupByCell::class.java.isAssignableFrom(type) -> View.count<Any?>()
                MAP_OUTLETS.any { it.isAssignableFrom(type) } -> View.map<Any?, Any?>()
                SET_OUTLETS.any { it.isAssignableFrom(type) } -> View.set<Any?>()
                else -> null
            } as View<Any, Any?>?
        }
    }
}

/**
 * A [View] decorator that records *when* and *at which wave* the fold last
 * effectively changed — the contract's `CellState.frontier` and `staleMs`.
 *
 * The frontier is read from the ambient [CurrentContext] rather than from the
 * view: emission is the context-stamping point (`FanOutlet.call` wraps its
 * whole fan-out in `CurrentContext.with(ctx)`), so at the instant the sink's
 * fold runs, the ambient timestamp *is* the producing outlet's wave position
 * for this delta. No built-in `View` exposes a frontier of its own, and the
 * ticket's "when the view exposes one" is satisfied without widening the kernel
 * interface.
 *
 * **A freshly-opened observation reports a null frontier**, even though its
 * fold is already full. That is correct, not a gap: the state it starts from
 * arrives as the producer's `catchUpOnLinked` baseline, delivered with
 * `at(link.to).propagate(...)` under no ambient context — and a baseline is
 * deliberately *not* a wave position (spec 20/21 §Pull, 93 I-24: it "MUST NOT
 * be admitted to any wave-completeness set"). The stamp appears with the first
 * live wave the fold sees. Claiming a wave for baseline state would be exactly
 * the lie the frontier exists to prevent.
 *
 * Per-cell only: nothing here claims cross-cell wave alignment (10-target-v3
 * §Constraints 4, defect class F-5 — accepted).
 */
internal class StampedView<D : Any, S>(
    private val delegate: View<D, S>,
    private val clock: () -> Long,
) : View<D, S> {

    @Volatile
    var frontier: Timestamp? = null
        private set

    @Volatile
    var changedAtMs: Long = clock()
        private set

    /**
     * Settled effective changes since the last window drained this (V1A-BE).
     *
     * Counted *here*, beside [changedAtMs], rather than off the sink's
     * `onChange` listener, for two reasons. It is the cheaper of the two — one
     * atomic add on a path that was already doing two volatile stores, with no
     * listener registration, no dispatcher hop, no lambda and no `StateReading`
     * allocation per change. And it is the *consistent* one: a count taken on
     * the sink's dispatcher thread could lag the `changedAtMs` store across a
     * window boundary, publishing a window whose `staleMs` dropped while its
     * count said nothing happened. Incrementing where the stamp is written
     * means the two can only ever agree.
     */
    private val changes = AtomicLong()

    /** Take and reset this window's change count — the publishing thread's only write. */
    fun drainChanges(): Long = changes.getAndSet(0)

    override fun apply(delta: D): Boolean {
        val changed = delegate.apply(delta)
        if (changed) {
            frontier = CurrentContext.get()?.timestamp
            changedAtMs = clock()
            changes.incrementAndGet()
        }
        return changed
    }

    override fun current(): S = delegate.current()
    override fun snapshot(): Serializable = delegate.snapshot()
    override fun restore(state: Serializable) = delegate.restore(state)
}
