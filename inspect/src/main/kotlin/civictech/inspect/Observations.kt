package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.CurrentContext
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
 * The `Stateful.snapshot()` fallback seam.
 *
 * **Not wired in M1, deliberately.** The ticket asks for a *host-routed*
 * snapshot: `Stateful.snapshot()` must run on the owning host's execution
 * context, because off-thread it races the cell's own fold. Routing it there
 * needs one of two things the kernel does not expose today —
 *
 * - the hosted `Cell` instance (`ManagedHost.cells` is private, and
 *   `LocationRegistry.describe` deliberately hands back only the `Class`), or
 * - a way to run a block on the host's management band
 *   (`ManagedHost.enqueueAwaiting` is private; `HostManagementApi` has no
 *   general-purpose form, and `HostedCellProxy` answers `snapshot()` with
 *   `null` because it is neither `getRef` nor a port accessor).
 *
 * Both are kernel edits, which this ticket excludes without orchestrator
 * sign-off ("No kernel edits without orchestrator sign-off"). So the seam is
 * declared, the whole `kind: "snapshot"` path behind it is implemented and
 * tested, and the shipped default reports [CellState.UNAVAILABLE] rather than
 * reading a live cell from the HTTP thread — the one thing the ticket
 * explicitly warns against.
 *
 * **M5 update — the accessor now exists.** `ManagedHost.snapshotOf(ref)` landed
 * with M5-SEARCH, which needed a host-routed read for content search; it
 * returns a `CompletableFuture` rather than the `Serializable?` predicted here,
 * so the caller owns the deadline instead of inheriting the scheduler's. Wiring
 * it into this seam is still one line at [InspectorServer]'s construction and
 * would finally make `kind: "snapshot"` reachable — but that changes what
 * `GET /cell/{ref}/state` answers for an *unobserved* cell, which belongs to
 * whoever owns M1's remaining residual, not to M5-SEARCH. Left deliberately
 * unwired; [DataSearch] calls the accessor directly.
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
 */
internal class Observations(
    private val registry: LocationRegistry,
    /** Fires on every settled effective change of an open observation. */
    private val onChange: (CellRef, StateReading) -> Unit,
    private val snapshots: SnapshotSource = SnapshotSource.Unavailable,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val lock = Any()
    private val open = LinkedHashMap<CellRef, Observation>()

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

        val observation = Observation(host, sink, sink.ref, result.link, view, clock())
        val existing = synchronized(lock) {
            open[ref] ?: run { open[ref] = observation; null }
        }
        if (existing != null) {
            // lost a concurrent POST race: keep the winner, release this one
            release(observation)
            existing.touch(clock())
            return true
        }

        // Registered last, exactly like `observe`'s optional listener: its
        // built-in late-join catch-up then already carries the connected
        // producer's current state, so a client sees one summary immediately.
        sink.onChange { value -> onChange(ref, observation.reading(value, clock())) }
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
     * Unlink then despawn — both halves, in that order, so the producer stops
     * pushing before the sink stops existing. Failures are swallowed: a release
     * racing a despawned target must not fail the request that asked for it.
     */
    private fun release(observation: Observation) {
        runCatching { observation.link.unlink() }
        runCatching { observation.host.managementInlet.call.despawn(observation.sinkRef) }
        // after despawn: a despawned ref never returns (each ObserveCell has a
        // fresh uuid), so removal cannot re-admit it, and the model's
        // unpublished() is a no-op for a node it never adopted
        instruments -= observation.sinkRef
    }

    private class Observation(
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

        fun touch(now: Long) {
            lastTouchedMs = now
        }

        fun reading(value: Any?, now: Long) = StateReading(
            kind = CellState.VIEW,
            value = value,
            frontier = view.frontier,
            staleMs = (now - view.changedAtMs).coerceAtLeast(0),
        )
    }

    internal companion object {
        /** Contract/ticket: "auto-release after 5 min without a matching `GET state`". */
        const val IDLE_RELEASE_MS = 5 * 60 * 1000L

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

    override fun apply(delta: D): Boolean {
        val changed = delegate.apply(delta)
        if (changed) {
            frontier = CurrentContext.get()?.timestamp
            changedAtMs = clock()
        }
        return changed
    }

    override fun current(): S = delegate.current()
    override fun snapshot(): Serializable = delegate.snapshot()
    override fun restore(state: Serializable) = delegate.restore(state)
}
