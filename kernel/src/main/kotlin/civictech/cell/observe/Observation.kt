package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.data.MapApi
import civictech.cell.data.OrMapApi
import civictech.cell.data.SetApi
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.QuorumSetApi
import civictech.cell.graph.TypedRef
import civictech.cell.host.DeclaredWrite
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One app-edge observation over one or more independently published [views].
 *
 * Every view is point-consistent: its value is a complete publication from one
 * [ObserveCell]. A frame may pair view A at wave `t` with view B at wave `t-1`;
 * no alignment between views is promised. Once the graph is idle, every view
 * converges to its sink's current value.
 */
interface Observation {
    /** The latest point-consistent assembly of all independently published views. */
    fun current(): ObservationFrame

    /**
     * Registers a listener with late-join catch-up. A one-view observation
     * forwards through that sink's dispatcher; a multi-view observation uses
     * one observation-owned dispatcher for all view publications.
     */
    fun onChange(listener: (ObservationFrame) -> Unit)

    /** The sink serving [view]. */
    fun sink(view: String): ObserveCell<*, *>

    /** The declared multi-cell write registered under [name]. */
    fun write(name: String): DeclaredWrite

    /** View names, in builder registration order. */
    val views: Set<String>

    /** Closes every view sink and the multi-view dispatcher, if one was minted. */
    fun close()

    /**
     * Waits for every sink listener queue and the multi-view dispatcher to
     * terminate within one shared timeout budget.
     *
     * @throws IllegalStateException if [close] has not happened.
     */
    fun awaitTermination(timeoutMillis: Long): Boolean
}

/**
 * Checked named-view accessor, matching [CompositeSink.get]'s missing-name and
 * runtime-shape checks (generic element/key/value arguments remain erased).
 */
inline fun <reified T> Observation.get(view: String): T {
    val snapshot = current().views
    require(view in snapshot) { "no observe named '$view' (available: ${snapshot.keys})" }
    val value = snapshot.getValue(view)
    return value as? T ?: throw IllegalStateException(
        "'$view' has snapshot type ${value?.let { it::class.simpleName } ?: "null"}, " +
            "requested ${T::class.simpleName ?: T::class}",
    )
}

/** One point-consistent assembly of the latest publication from every view. */
data class ObservationFrame(val views: Map<String, Any?>)

/** The canonical app-edge builder for named set, map, count, and tagged-map views. */
class ObservationBuilder internal constructor() {
    internal class Spec(
        val source: CellRef,
        val outletName: String,
        val view: View<*, *>,
        val kind: String,
    )

    internal val specs = LinkedHashMap<String, Spec>()
    internal val writes = linkedMapOf<String, Set<CellRef>>()

    private fun add(name: String, source: CellRef, outletName: String, view: View<*, *>, kind: String) {
        require(name !in specs) { "duplicate observe name '$name'" }
        specs[name] = Spec(source, outletName, view, kind)
    }

    /** Declares one named multi-cell write alongside this observation. */
    fun write(name: String, cells: Set<CellRef>) {
        val declaredCells = cells.toSet()
        val existing = writes.putIfAbsent(name, declaredCells)
        require(existing == null || existing == declaredCells) {
            "observation write '$name' was already registered with cells $existing"
        }
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

    /** Fold a tagged-map outlet into a `Map` under [name]. */
    fun taggedMap(name: String, source: CellRef, outletName: String = "outlet") =
        add(name, source, outletName, View.taggedMap<Any?, Any?>(), "taggedMap")

    /** Typed [set] for a `SetApi<E>` source. */
    @JvmName("setFromSetApi")
    fun <E> set(name: String, source: TypedRef<out SetApi<E>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.set<E>(), "set")

    /** Typed [set] for a `QuorumSetApi<E>` source. */
    @JvmName("setFromQuorumSetApi")
    fun <E> set(name: String, source: TypedRef<out QuorumSetApi<E>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.set<E>(), "set")

    /** Typed [set] for a `FilterSetApi<E>` source. */
    @JvmName("setFromFilterSetApi")
    fun <E> set(name: String, source: TypedRef<out FilterSetApi<E>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.set<E>(), "set")

    /** Typed [map] for a `MapApi<K, V>` source. */
    @JvmName("mapFromMapApi")
    fun <K, V> map(name: String, source: TypedRef<out MapApi<K, V>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.map<K, V>(), "map")

    /** Typed [count] for the shipped `GroupByApi<E, K, Long>` count shape. */
    @JvmName("countFromGroupByApi")
    fun <E, K> count(name: String, source: TypedRef<out GroupByApi<E, K, Long>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.count<K>(), "count")

    /** Typed [taggedMap] for an `OrMapApi<K, V>` source. */
    @JvmName("taggedMapFromOrMapApi")
    fun <K, V> taggedMap(name: String, source: TypedRef<out OrMapApi<K, V>>, outletName: String = "outlet") =
        add(name, source.ref, outletName, View.taggedMap<K, V>(), "taggedMap")
}

private class ObservationCoordinator(
    private val sinks: LinkedHashMap<String, ObserveCell<*, *>>,
    private val declaredWrites: LinkedHashMap<String, DeclaredWrite>,
) : Observation {
    private val lock = Any()
    private val listeners = mutableListOf<(ObservationFrame) -> Unit>()
    private val snapshots = sinks.mapValuesTo(linkedMapOf()) { it.value.current() }
    private val singleView = sinks.entries.singleOrNull()
    private var dispatcher: ExecutorService? = null
    private var closed = false

    @Volatile
    private var latest = ObservationFrame(LinkedHashMap(snapshots))

    init {
        // A one-view observation forwards listeners directly to its sink below.
        // Registering the coordinator as another listener would eagerly mint the
        // sink dispatcher even when the app only calls current().
        if (singleView == null) {
            sinks.forEach { (name, sink) ->
                sink.onChange { value -> onView(name, value) }
            }
        }
    }

    override fun current(): ObservationFrame = ObservationFrame(
        sinks.mapValuesTo(linkedMapOf()) { it.value.current() },
    )

    override fun onChange(listener: (ObservationFrame) -> Unit) {
        val one = singleView
        if (one != null) {
            synchronized(lock) { if (closed) return }
            one.value.onChange { value ->
                listener(ObservationFrame(linkedMapOf(one.key to value)))
            }
            return
        }
        synchronized(lock) {
            if (closed) return
            listeners += listener
            val snapshot = latest
            dispatchIfOpen { listener(snapshot) }
        }
    }

    override fun sink(view: String): ObserveCell<*, *> =
        requireNotNull(sinks[view]) { "no observation view '$view' (available: ${sinks.keys})" }

    override fun write(name: String): DeclaredWrite =
        requireNotNull(declaredWrites[name]) {
            "no observation write '$name' (available: ${declaredWrites.keys})"
        }

    override val views: Set<String> = sinks.keys.toCollection(LinkedHashSet())

    private fun onView(name: String, value: Any?) {
        synchronized(lock) {
            if (closed || snapshots[name] == value) return
            snapshots[name] = value
            latest = ObservationFrame(LinkedHashMap(snapshots))
            val snapshot = latest
            val fired = listeners.toList()
            if (fired.isNotEmpty()) dispatchIfOpen { fired.forEach { it(snapshot) } }
        }
    }

    private fun dispatchIfOpen(block: () -> Unit) {
        check(Thread.holdsLock(lock)) { "dispatchIfOpen must be called under the observation lock" }
        if (closed) return
        val target = dispatcher ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "observation-${System.identityHashCode(this)}").apply { isDaemon = true }
        }.also { dispatcher = it }
        try {
            target.execute(block)
        } catch (_: RejectedExecutionException) {
            // raced close(); no live observation listener remains to notify.
        }
    }

    override fun close() {
        val doomed = synchronized(lock) {
            if (closed) return
            closed = true
            dispatcher
        }
        doomed?.shutdown()
        sinks.values.forEach(ObserveCell<*, *>::close)
    }

    override fun awaitTermination(timeoutMillis: Long): Boolean {
        val ownDispatcher = synchronized(lock) {
            check(closed) { "awaitTermination requires close() first" }
            dispatcher
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative (was $timeoutMillis)" }
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val started = System.nanoTime()

        fun remainingMillis(): Long {
            val remaining = timeoutNanos - (System.nanoTime() - started)
            return if (remaining <= 0L) 0L else TimeUnit.NANOSECONDS.toMillis(remaining)
        }

        for (sink in sinks.values) {
            if (!sink.awaitTermination(remainingMillis())) return false
        }
        return awaitExecutorsTermination(listOf(ownDispatcher), remainingMillis())
    }
}

@Suppress("UNCHECKED_CAST")
private fun observeCell(spec: ObservationBuilder.Spec, ref: CellRef): ObserveCell<*, *> =
    ObserveCell(spec.view as View<Any, Any?>, ref)

/**
 * Canonical app-edge observation entry point.
 *
 * Each registered view is folded by its own [ObserveCell]. Every view is
 * point-consistent, but a frame may pair view A at wave `t` with view B at wave
 * `t-1`; no alignment between views is promised. Applications that need one
 * atomic multi-input value keep that joined state in one cell.
 *
 * [sinkRef] is called once per registered view, in registration order, with
 * that view's name.
 */
fun Use<HostManagementApi>.observation(
    sinkRef: (view: String) -> CellRef = { CellRef(UUID.randomUUID()) },
    block: ObservationBuilder.() -> Unit,
): Observation {
    val builder = ObservationBuilder().apply(block)
    require(builder.specs.isNotEmpty()) { "observation requires at least one registered view" }

    val declaredWrites = builder.writes.mapValuesTo(linkedMapOf()) { (name, cells) ->
        call.declareWrite(name, cells)
    }

    val sinks = linkedMapOf<String, ObserveCell<*, *>>()
    builder.specs.forEach { (name, spec) ->
        val cell = observeCell(spec, sinkRef(name))
        call.spawn(cell)
        val result = call.connect(spec.source, spec.outletName, cell.ref, "inlet")
        if (result is LinkResult.Rejected) {
            throw IllegalStateException(
                "observation: link ${spec.source}.${spec.outletName} -> view '$name' rejected: ${result.reason}",
            )
        }
        sinks[name] = cell
    }
    return ObservationCoordinator(sinks, declaredWrites)
}

/** Convenience for observing cells on this [ManagedHost]. */
fun ManagedHost.observation(
    sinkRef: (view: String) -> CellRef = { CellRef(UUID.randomUUID()) },
    block: ObservationBuilder.() -> Unit,
): Observation = managementInlet.observation(sinkRef, block)
