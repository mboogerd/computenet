package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.CountView
import civictech.cell.data.MapDelta
import civictech.cell.data.MapView
import civictech.cell.Propagate
import civictech.cell.data.SetDelta
import civictech.cell.data.SetView
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkResult
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.UUID

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
     * ordered with change delivery.
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
 * any thread. Listeners fire under the lock so change delivery and a fresh
 * subscriber's catch-up are totally ordered (no stale-after-fresh).
 */
class ObserveCell<D : Any, S>(
    private val view: View<D, S>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, Stateful, ObservationSink<S> {

    val inlet = registerPort("inlet", FanInlet.create<Propagate<D>>())

    private val lock = Any()
    private val listeners = mutableListOf<(S) -> Unit>()

    @Volatile
    private var latest: S = view.current()

    init {
        inlet.serve(object : Propagate<D> {
            override fun propagate(value: D) {
                synchronized(lock) {
                    if (view.apply(value)) {
                        latest = view.current()
                        listeners.forEach { it(latest) }
                    }
                }
            }
        })
    }

    override fun current(): S = latest

    override fun onChange(listener: (S) -> Unit) {
        synchronized(lock) {
            listeners += listener
            // late-join catch-up: one call with the materialized snapshot,
            // under the lock so no interleaving change fires it out of order.
            listener(latest)
        }
    }

    override fun snapshot(): Serializable = synchronized(lock) { view.snapshot() }

    override fun restore(state: Serializable) {
        synchronized(lock) {
            view.restore(state)
            latest = view.current()
        }
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
 */
class ObserveAllBuilder internal constructor(private val mgmt: Use<HostManagementApi>) {
    internal val sinks = LinkedHashMap<String, ObservationSink<*>>()

    private fun add(name: String, sink: ObservationSink<*>) {
        require(sinks.put(name, sink) == null) { "duplicate observe name '$name'" }
    }

    /** Fold a [SetDelta] outlet into a `Set` under [name]. */
    fun set(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, mgmt.observe(source, View.set<Any?>(), outletName))

    /** Fold a [MapDelta] outlet into a `Map` under [name]. */
    fun map(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, mgmt.observe(source, View.map<Any?, Any?>(), outletName))

    /** Fold a per-key count [MapDelta] outlet into counts under [name]. */
    fun count(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, mgmt.observe(source, View.count<Any?>(), outletName))
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
 * flash is possible here). The wave-aligned glitch-free composite behind
 * [civictech.cell.consistency.GlitchFreeCell] is deferred — see [observeAll].
 * Each *individual* per-outlet value is always internally consistent.
 */
class CompositeSink internal constructor(
    sinks: Map<String, ObservationSink<*>>,
) : ObservationSink<Map<String, Any?>> {

    private val lock = Any()
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = mutableListOf<(Map<String, Any?>) -> Unit>()

    @Volatile
    private var snapshot: Map<String, Any?> = emptyMap()

    init {
        sinks.forEach { (name, sink) ->
            values[name] = null
            // each sink's onChange fires immediately (catch-up) then on every
            // settled change; both funnel through here under the composite lock.
            sink.onChange { v ->
                synchronized(lock) {
                    values[name] = v
                    snapshot = LinkedHashMap(values)
                    listeners.forEach { it(snapshot) }
                }
            }
        }
    }

    override fun current(): Map<String, Any?> = snapshot

    override fun onChange(listener: (Map<String, Any?>) -> Unit) {
        synchronized(lock) {
            listeners += listener
            listener(snapshot)
        }
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
 * **Deferred: the glitch-free (wave-aligned) composite.** The spec's ideal is a
 * cross-outlet snapshot behind [civictech.cell.consistency.GlitchFreeCell] so
 * the app never sees `common ∋ s` while `filtered ∌ s` mid-wave. That is NOT
 * shipped here — this composite is point-consistent per outlet (see
 * [CompositeSink]). Two things block the clean composition and neither exists
 * yet:
 *   1. `GlitchFreeCell` replays raw `propagate(delta)` invocations to one
 *      outlet, erasing which *named* source each delta came from — two
 *      `set(...)` outlets both emit `SetDelta`, indistinguishable downstream —
 *      so routing folds by name needs per-name label-envelope machinery.
 *   2. Its frontier is a *static link set* (its own doc: "real upstream
 *      traversal needs multiplex ports (G-13)"). With an absorbing mid-pipeline
 *      edge — slotfinder's `FilterCell` drops slots and emits no `Progress`
 *      absorb-ack — a wave that never reaches the filtered edge leaves that
 *      edge's watermark behind, so the join can stall the last wave until the
 *      next write. Robust wave-alignment there needs the G-13 multiplex-port
 *      frontier that is not built.
 * Per the ticket's guidance (honest partial delivery over a fragile
 * wave-alignment), the composite ships point-consistent and the glitch-free
 * path is deferred to G-13.
 */
fun ManagedHost.observeAll(block: ObserveAllBuilder.() -> Unit): CompositeSink {
    val builder = ObserveAllBuilder(managementInlet).apply(block)
    return CompositeSink(builder.sinks)
}
