package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Propagate
import civictech.cell.graph.TypedRef
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import civictech.cell.data.MapApi
import civictech.cell.data.SetApi
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.QuorumSetApi
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.data.view.CountView

/**
 * The read/observe dual of the `graph { }` builder (spec
 * `observation-sink-materialized-edge`): a hosted sink that folds one (or more)
 * cell outlets' delta streams back into a materialized value the *app* can read
 * and subscribe to. It is assembly + ergonomics over the read-model folds in
 * `civictech.cell.data` ([SetView] / [MapView] / [CountView]) and the host's
 * existing spawn / connect / `onLinked` catch-up — no new dataflow semantics,
 * and transport-neutral: the sink exposes only [current] and [onChange], never
 * SSE/HTTP. It replaces the hand-rolled per-outlet `HubCell` + synchronized
 * `state` + `/state` boilerplate every serving demo reinvents.
 */
interface ObservationSink<out S> {
    /**
     * The current materialized value — a consistent, immutable snapshot safe to
     * read from any thread (each settled change publishes a fresh immutable
     * value; a reader never sees a partially-applied delta).
     */
    fun current(): S

    /**
     * Register [listener], fired once per *settled effective change* with the
     * new snapshot. Built-in late-join catch-up: the listener is invoked
     * immediately with [current] (the materialized state, NOT a replay of
     * historical deltas), so a fresh subscriber — a new SSE client, a reloaded
     * tab — is caught up in one call. Listener invocations are serialized and
     * ordered with change delivery (T08 finding 4: off the host scheduler
     * thread — see [ObserveCell]'s class doc for the dispatch mechanism), so a
     * blocking listener delays only its own sink's catch-up/notifications,
     * never the host's dispatch of other cells.
     */
    fun onChange(listener: (S) -> Unit)
}

/**
 * The per-outlet fold strategy: a thin adapter over a `civictech.cell.data`
 * read model. `apply` returns the read model's effective-change Boolean so the
 * sink fires [ObservationSink.onChange] only on a real change (tag churn / a
 * restated put folds to no fire). The factory methods name the three shipped
 * folds — `View.set()`, `View.map()`, `View.count()`.
 */
interface View<in D, out S> {
    /** Fold one delta in; returns whether the materialized value effectively changed. */
    fun apply(delta: D): Boolean

    /** The current materialized value as an immutable snapshot. */
    fun current(): S

    /** Mirror the [Stateful] shape so the sink can checkpoint/restore its fold. */
    fun snapshot(): Serializable
    fun restore(state: Serializable)

    companion object {
        /** Fold a [SetDelta] stream (a `SetCell`/`UnionSetCell`/… outlet) into live membership. */
        fun <E> set(): View<SetDelta<E>, Set<E>> = object : View<SetDelta<E>, Set<E>> {
            private val v = SetView<E>()
            override fun apply(delta: SetDelta<E>) = v.apply(delta)
            override fun current() = v.current()
            override fun snapshot() = v.snapshot()
            override fun restore(state: Serializable) = v.restore(state)
        }

        /** Fold a [MapDelta] stream (a `MapCell`/`GroupByCell` outlet) into a queryable map. */
        fun <K, V> map(): View<MapDelta<K, V>, Map<K, V>> = object : View<MapDelta<K, V>, Map<K, V>> {
            private val v = MapView<K, V>()
            override fun apply(delta: MapDelta<K, V>) = v.apply(delta)
            override fun current() = v.current()
            override fun snapshot() = v.snapshot()
            override fun restore(state: Serializable) = v.restore(state)
        }

        /** Fold a per-key count [MapDelta] (the slotfinder `byDay` fold) into queryable counts. */
        fun <K> count(): View<MapDelta<K, Long>, Map<K, Long>> = object : View<MapDelta<K, Long>, Map<K, Long>> {
            private val v = CountView<K>()
            override fun apply(delta: MapDelta<K, Long>) = v.apply(delta)
            override fun current() = v.current()
            override fun snapshot() = v.snapshot()
            override fun restore(state: Serializable) = v.restore(state)
        }
    }
}

/**
 * The hosted cell behind [ObservationSink]: a single [FanInlet] served with the
 * [view] fold. It is [Stateful] so a host checkpoint captures the materialized
 * value, and it participates in the ordinary catch-up path — when its inlet is
 * connected to a producer outlet, the producer's `onLinked` push seeds it with
 * a state-as-delta, so a freshly-observed outlet's snapshot equals `current()`
 * without replaying history.
 *
 * Threading: the fold runs on the host scheduler thread (per-cell FIFO); a
 * [lock] serializes the fold against [onChange] registration, and the published
 * snapshot is a `@Volatile` immutable value so [current] is torn-read-free from
 * any thread.
 *
 * **Listener dispatch (T08 finding 4)**: a blocking app listener (an SSE
 * write, e.g.) used to run *inside* [lock] on the host scheduler thread —
 * pinning the whole host's dispatch on one slow app I/O call. Each sink now
 * owns a dedicated single-thread daemon executor ([dispatcher]); the fold and
 * the snapshot swap still run under [lock] on the host thread (unchanged —
 * that is what makes [current] and the catch-up value consistent), but
 * *invoking* a listener is submitted to [dispatcher] instead of run inline.
 * The total-ordering guarantee this class has always documented (change
 * delivery and a fresh subscriber's catch-up never interleave out of order)
 * is preserved by construction: every submission to [dispatcher] — a change
 * fire from [propagate], or a catch-up fire from [onChange] — happens while
 * holding [lock], so submission order is a strict total order, and a
 * single-consumer executor executes in submission order. What changes is
 * *where* the listener runs: never the host thread, so a listener that
 * blocks stalls only its own sink's later notifications, never other cells'
 * dispatch.
 */
class ObserveCell<D : Any, S>(
    private val view: View<D, S>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, Stateful, ObservationSink<S> {

    val inlet = registerPort("inlet", FanInlet.create<Propagate<D>>())

    private val lock = Any()
    private val listeners = mutableListOf<(S) -> Unit>()

    /** T08 finding 4: single-consumer ⇒ FIFO submission order is delivery order. */
    private fun newDispatcher(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "observe-cell-${ref.id}").apply { isDaemon = true }
    }

    @Volatile
    private var dispatcher: ExecutorService = newDispatcher()

    @Volatile
    private var closed = false

    @Volatile
    private var latest: S = view.current()

    init {
        inlet.serve(object : Propagate<D> {
            override fun propagate(value: D) {
                synchronized(lock) {
                    if (view.apply(value)) {
                        latest = view.current()
                        val snapshot = latest
                        val fired = listeners.toList()
                        dispatchIfOpen { fired.forEach { it(snapshot) } }
                    }
                }
            }
        })
    }

    override fun current(): S = latest

    override fun onChange(listener: (S) -> Unit) {
        synchronized(lock) {
            listeners += listener
            // late-join catch-up: one submission with the materialized
            // snapshot, made under the lock so no interleaving change's
            // submission can land out of order around it (no
            // stale-after-fresh, no gap, no duplicate — see class doc).
            val snapshot = latest
            dispatchIfOpen { listener(snapshot) }
        }
    }

    /** Submits [block] to [dispatcher] unless [close]d; silently drops on a close race (no live listener to reach). */
    private fun dispatchIfOpen(block: () -> Unit) {
        if (closed) return
        try {
            dispatcher.execute(block)
        } catch (_: RejectedExecutionException) {
            // raced a concurrent close(); nothing left to notify.
        }
    }

    override fun snapshot(): Serializable = synchronized(lock) { view.snapshot() }

    override fun restore(state: Serializable) {
        synchronized(lock) {
            view.restore(state)
            latest = view.current()
        }
    }

    /**
     * T08 finding 4 lifecycle: stops [dispatcher]. Idempotent. Wired into
     * [onDeactivate] — the host already calls this on despawn (`Cell`'s own
     * disposal hook) — so a despawned sink's dispatch thread does not
     * outlive it; a caller that never despawns the sink (the common demo
     * shape: the sink lives for the process) may call this directly at
     * shutdown instead.
     */
    fun close() {
        val doomed = synchronized(lock) {
            if (closed) return
            closed = true
            dispatcher
        }
        doomed.shutdown()
    }

    /**
     * Reopens a [close]d sink with a fresh [dispatcher]. Idempotent, and a
     * no-op on an already-open sink.
     *
     * Necessary because [onDeactivate] is **not** only a despawn hook:
     * `ManagedHost` calls it on the `SupervisionPolicy.RESTART` path (paired
     * with an immediate [onActivate]) and on host drain before a migration
     * re-activates the cell elsewhere. Without this, one restart or migration
     * left the sink permanently deaf — the fold kept working, so [current]
     * stayed correct, but every registered listener silently stopped firing
     * forever, which is precisely the class of silent degrade T08 set out to
     * remove.
     *
     * Ordering across the reopen: the new dispatcher's first act is to wait
     * for the old one to drain, so a listener invocation queued before the
     * restart can never be overtaken by one submitted after it — the
     * total-order guarantee in this class's doc survives the boundary. A
     * listener still blocked on the old dispatcher therefore also holds up
     * the new one, which is the same "delays only its own sink" property,
     * unchanged.
     */
    private fun reopen() {
        val drained = synchronized(lock) {
            if (!closed) return
            val previous = dispatcher
            dispatcher = newDispatcher()
            closed = false
            previous
        }
        dispatcher.execute { runCatching { drained.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) } }
    }

    override fun onActivate(ctx: CellContext) {
        reopen()
    }

    override fun onDeactivate(ctx: CellContext) {
        close()
    }
}

/**
 * Observe a single outlet: spawn an [ObserveCell] folding it with [view],
 * connect the producer's [outletName] to the sink's inlet (whose `onLinked`
 * catch-up seeds the current materialized value), and return the sink. An
 * optional [onChange] listener is registered *after* connect, so its immediate
 * catch-up already reflects the producer's current state.
 *
 * ```
 * val common = host.observe(refs.common, View.set<Slot>()) { set -> broadcast(set) }
 * common.current()   // consistent snapshot, any thread
 * ```
 */
fun <D : Any, S> Use<HostManagementApi>.observe(
    source: CellRef,
    view: View<D, S>,
    outletName: String = "outlet",
    onChange: ((S) -> Unit)? = null,
): ObservationSink<S> {
    val sink = ObserveCell(view)
    call.spawn(sink)
    val result = call.connect(source, outletName, sink.ref, "inlet")
    check(result !is LinkResult.Rejected) {
        "observe: link $source.$outletName -> sink rejected: ${(result as LinkResult.Rejected).reason}"
    }
    onChange?.let { sink.onChange(it) }
    return sink
}

/** Convenience for the common case of observing a cell on this [ManagedHost]. */
fun <D : Any, S> ManagedHost.observe(
    source: CellRef,
    view: View<D, S>,
    outletName: String = "outlet",
    onChange: ((S) -> Unit)? = null,
): ObservationSink<S> = managementInlet.observe(source, view, outletName, onChange)

/**
 * Declarative builder for [observeAll]: names one fold per outlet.
 *
 * T08 finding 2: the `CellRef` overloads below are the original, untyped
 * form — kept byte-for-byte, `View.set<Any?>()`'s erased fold and all — so
 * every existing caller keeps compiling. Alongside them, additive typed
 * overloads accept a [TypedRef] minted by [civictech.cell.graph.refAs] at
 * graph-build time: the element/key type flows from the ref's declared API
 * shape, so wiring the wrong-shaped source (a `TypedRef<MapApi<K, V>>` where
 * a `set` was meant) is a compile error instead of an `Any?` fold that a call
 * site has to re-assert with an unchecked cast. One overload per *shape* this
 * codebase's `civictech.cell.data`/`.data.op` package actually declares
 * (`SetApi`, `QuorumSetApi`, `FilterSetApi`, `MapApi`, `GroupByApi`'s count
 * form) — a JVM signature clash forces distinct `@JvmName`s per overload
 * (`TypedRef<...>`'s generic argument erases the same way regardless of the
 * bound), so each carries one below.
 */
class ObserveAllBuilder internal constructor(private val mgmt: Use<HostManagementApi>) {
    internal val sinks = LinkedHashMap<String, ObservationSink<*>>()

    /** What each [sinks] entry was registered as — [CompositeSink.get]'s checked-cast diagnostic. */
    internal val kinds = LinkedHashMap<String, String>()

    private fun add(name: String, sink: ObservationSink<*>, kind: String) {
        require(sinks.put(name, sink) == null) { "duplicate observe name '$name'" }
        kinds[name] = kind
    }

    /** Fold a [SetDelta] outlet into a `Set` under [name]. */
    fun set(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, mgmt.observe(source, View.set<Any?>(), outletName), "set")

    /** Fold a [MapDelta] outlet into a `Map` under [name]. */
    fun map(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, mgmt.observe(source, View.map<Any?, Any?>(), outletName), "map")

    /** Fold a per-key count [MapDelta] outlet into counts under [name]. */
    fun count(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, mgmt.observe(source, View.count<Any?>(), outletName), "count")

    // ---- T08 finding 2: typed overloads — the element type is compile-checked ----

    /** Typed [set]: [source]'s element type flows from a `TypedRef<SetApi<E>>`. */
    @JvmName("setFromSetApi")
    fun <E> set(name: String, source: TypedRef<out SetApi<E>>, outletName: String = "outlet") =
        add(name, mgmt.observe(source.ref, View.set<E>(), outletName), "set")

    /** Typed [set]: [source]'s element type flows from a `TypedRef<QuorumSetApi<E>>`. */
    @JvmName("setFromQuorumSetApi")
    fun <E> set(name: String, source: TypedRef<out QuorumSetApi<E>>, outletName: String = "outlet") =
        add(name, mgmt.observe(source.ref, View.set<E>(), outletName), "set")

    /** Typed [set]: [source]'s element type flows from a `TypedRef<FilterSetApi<E>>`. */
    @JvmName("setFromFilterSetApi")
    fun <E> set(name: String, source: TypedRef<out FilterSetApi<E>>, outletName: String = "outlet") =
        add(name, mgmt.observe(source.ref, View.set<E>(), outletName), "set")

    /** Typed [map]: [source]'s key/value types flow from a `TypedRef<MapApi<K, V>>`. */
    @JvmName("mapFromMapApi")
    fun <K, V> map(name: String, source: TypedRef<out MapApi<K, V>>, outletName: String = "outlet") =
        add(name, mgmt.observe(source.ref, View.map<K, V>(), outletName), "map")

    /**
     * Typed [count]: [source]'s key type flows from a `TypedRef<GroupByApi<E, K, Long>>`
     * (`Aggregators.count`'s accumulator, the shipped per-key-count shape —
     * `slotfinder`'s `byDay`). A `GroupByApi` folded with a non-`Long`
     * aggregator is a `map`, not a `count`; use the [map] overload instead.
     */
    @JvmName("countFromGroupByApi")
    fun <E, K> count(name: String, source: TypedRef<out GroupByApi<E, K, Long>>, outletName: String = "outlet") =
        add(name, mgmt.observe(source.ref, View.count<K>(), outletName), "count")
}

/**
 * A composite over several named outlets, assembling one `{ name -> value }`
 * snapshot for the app (slotfinder's participants + pair + common + filtered +
 * byDay as one JSON object).
 *
 * **Consistency (honest):** this composite is **point-consistent per outlet**,
 * NOT wave-aligned. Each named outlet is folded by its own [ObserveCell] and
 * the composite assembles the latest per-outlet snapshot; a read may therefore
 * pair `common` at wave `t` with `filtered` at wave `t-1` mid-pipeline (the F-5
 * flash is possible here). Each *individual* per-outlet value is always
 * internally consistent. The wave-aligned alternative is shipped beside it:
 * [AlignedCompositeCell], behind [observeAligned] (spec 20/22 §The observation
 * frontier, `[22-OBS-01]`/`[22-OBS-02]`). This composite remains the right
 * choice when an arm may stall and a stale-but-prompt read beats a delayed
 * aligned one — the aligned sink is the WAIT shape and holds a wave until every
 * contributing view has settled it.
 *
 * T08 finding 4: routes its own listener dispatch through the same
 * dedicated-executor mechanism as [ObserveCell] (its own `dispatcher`, not a
 * shared one) — a blocking composite listener no longer runs inline on
 * whichever upstream [ObserveCell]'s dispatcher thread happened to fire, so
 * it can no longer delay that per-outlet sink's *own* later notifications
 * either; the nested-lock shape (`sinkLock` → this composite's `lock`) this
 * class's KDoc used to flag as a hazard dissolves the same way finding 4's
 * fix dissolves it in [ObserveCell]: nothing blocking runs under either lock
 * anymore.
 */
class CompositeSink internal constructor(
    sinks: Map<String, ObservationSink<*>>,
    /**
     * What each named sink was registered as (T08 finding 2) — [get]'s
     * checked-cast diagnostic. `@PublishedApi internal`, not `private`: [get]
     * is a public inline function (it needs `reified T`) and Kotlin forbids a
     * public inline function from reaching a `private` member of its own
     * class, since inlining would copy that access out to external call sites.
     */
    @PublishedApi internal val registeredAs: Map<String, String> = emptyMap(),
) : ObservationSink<Map<String, Any?>> {

    private val lock = Any()
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = mutableListOf<(Map<String, Any?>) -> Unit>()

    /** T08 finding 4: this composite's own single-consumer dispatch executor. */
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "observe-composite-${System.identityHashCode(this)}").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var snapshot: Map<String, Any?> = emptyMap()

    init {
        synchronized(lock) {
            sinks.forEach { (name, sink) ->
                // Seed each slot SYNCHRONOUSLY from the sink's materialized
                // state. Before T08 finding 4 this seeding was implicit: the
                // `onChange` registration below fired its catch-up inline, so
                // the composite was fully populated by the time the
                // constructor returned. Finding 4 made that catch-up an
                // asynchronous submission on the per-sink dispatcher, which
                // left `current()` an EMPTY map for a window after
                // construction — `get(name)` threw "no observe named '<name>'
                // (available: [])" and the README's "current() gives a
                // consistent snapshot from any thread" was false at startup
                // (e.g. slotfinder's `/state` before its first fold landed).
                // The asynchronous catch-up still arrives and re-writes the
                // same slot; being the FIRST submission on that sink's
                // dispatcher it can never land after a newer change, so the
                // no-stale-after-fresh ordering guarantee is unaffected.
                values[name] = sink.current()
                // each sink's onChange fires immediately (catch-up) then on every
                // settled change; both funnel through here under the composite lock
                // — state assembly stays serialized, only listener dispatch defers.
                sink.onChange { v ->
                    synchronized(lock) {
                        values[name] = v
                        snapshot = LinkedHashMap(values)
                        val fired = listeners.toList()
                        val s = snapshot
                        dispatchIfOpen { fired.forEach { it(s) } }
                    }
                }
            }
            snapshot = LinkedHashMap(values)
        }
    }

    override fun current(): Map<String, Any?> = snapshot

    override fun onChange(listener: (Map<String, Any?>) -> Unit) {
        synchronized(lock) {
            listeners += listener
            val s = snapshot
            dispatchIfOpen { listener(s) }
        }
    }

    private fun dispatchIfOpen(block: () -> Unit) {
        if (closed) return
        try {
            dispatcher.execute(block)
        } catch (_: RejectedExecutionException) {
            // raced a concurrent close(); nothing left to notify.
        }
    }

    /**
     * T08 finding 2: checked accessor — casts the named entry's current value
     * to [T], throwing a message naming both what [name] was registered as
     * and what was requested, instead of the silent `as? … ?: emptySet()`
     * degrade a raw `current()[name]` invites. Still erasure-level (same
     * caveat as every generic runtime check in this codebase): it confirms
     * the *shape* (a `Set` requested where a `Map` was registered fails
     * loudly), not the element/key/value type argument.
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
     * T08 finding 4 lifecycle: stops this composite's own dispatch executor.
     * Idempotent. Does **not** close the underlying per-outlet [ObserveCell]s
     * — [observeAll] does not own their lifecycle (each is despawned, and so
     * closed, independently — see [ObserveCell.close]); this only stops the
     * composite's own listener-dispatch thread.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        dispatcher.shutdown()
    }
}

/**
 * Observe several outlets as one composite snapshot:
 *
 * ```
 * val view = host.observeAll {
 *     set("common",   refs.common)
 *     set("filtered", refs.filtered)
 *     count("byDay",  refs.byDay)
 * }
 * view.current()   // { common, filtered, byDay }
 * ```
 *
 * **This composite is point-consistent per outlet** (see [CompositeSink]): a
 * read may pair `common` at wave `t` with `filtered` at wave `t-1` mid-wave.
 * The wave-aligned composite is [observeAligned] / [AlignedCompositeCell] (spec
 * 20/22 §The observation frontier, `[22-OBS-01]`/`[22-OBS-02]`; 96 §E2.3) —
 * shipped, not deferred. It reaches alignment by *not* going through
 * [civictech.cell.consistency.GlitchFreeCell]: one **named inlet per view**
 * makes each delta's source view structural, dissolving the name erasure a
 * single-outlet replay would impose (two `set(...)` outlets both emit
 * `SetDelta`, indistinguishable once merged), and one completeness fold spans
 * every inlet's edges.
 *
 * Both sinks ship. Prefer this one when an arm may stall and a stale-but-prompt
 * read beats a delayed aligned one; prefer [observeAligned] when a mixed-wave
 * read is a correctness problem. The frontier's static-link-set residual
 * (G-13: no upstream traversal, so an arm that structurally never carries a
 * source is a phantom expected edge for its waves) is documented on
 * [AlignedCompositeCell] and is the reason this fallback is kept rather than
 * retired.
 */
fun ManagedHost.observeAll(block: ObserveAllBuilder.() -> Unit): CompositeSink {
    val builder = ObserveAllBuilder(managementInlet).apply(block)
    return CompositeSink(builder.sinks, builder.kinds)
}
