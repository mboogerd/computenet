package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.GlitchViolation
import civictech.cell.data.MapApi
import civictech.cell.data.OrMapApi
import civictech.cell.data.SetApi
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.QuorumSetApi
import civictech.cell.graph.TypedRef
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.host.DeclaredWrite
import civictech.cell.host.UpstreamAncestry
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One app-edge observation over one or more internally aligned [groups].
 *
 * Each group contains exactly the registered views with the same structural
 * root set and is served by one [AlignedCompositeCell]. Different groups never
 * wait on one another: [ObservationFrame.crossRoot] discloses the frontier lag
 * that is actually comparable between them instead.
 */
interface Observation {
    /** The latest point-consistent assembly of the independently published groups. */
    fun current(): ObservationFrame

    /**
     * Registers a listener with late-join catch-up. A one-group observation
     * forwards through that group's dispatcher; a multi-group observation uses
     * one observation-owned dispatcher for all group publications.
     */
    fun onChange(listener: (ObservationFrame) -> Unit)

    /** The aligned sink serving [id]. */
    fun group(id: String): AlignedCompositeCell

    /** The declared multi-cell write registered under [name]. */
    fun write(name: String): DeclaredWrite

    /** Group ids, in builder registration order. */
    val groups: Set<String>

    /** Waves currently held inside all aligned groups. */
    val bufferedWaves: Int

    /** Closes every aligned group and the multi-group dispatcher, if one was minted. */
    fun close()

    /**
     * Waits for every group listener queue and the multi-group dispatcher to
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

/**
 * One point-consistent assembly of the latest publication from every aligned
 * root group. The groups are each wave-aligned; values across different groups
 * are deliberately not claimed to share a frontier.
 */
data class ObservationFrame(
    val views: Map<String, Any?>,
    val groups: Map<String, AlignedComposite>,
    val groupOf: Map<String, String>,
    val crossRoot: Map<GroupPair, CrossRootStaleness>,
)

/** One unordered pair of distinct observation groups, stored in lexical order. */
data class GroupPair(val a: String, val b: String) {
    init {
        require(a < b) { "group pair must be distinct and ordered (was '$a', '$b')" }
    }

    internal companion object {
        fun of(left: String, right: String): GroupPair =
            if (left < right) GroupPair(left, right) else GroupPair(right, left)
    }
}

/**
 * Signed frontier lag for the sources two groups can actually compare.
 * An empty map means the latest group publications have no shared source.
 */
data class CrossRootStaleness(val lagBySource: Map<UUID, Long>) {
    val independent: Boolean get() = lagBySource.isEmpty()
}

/**
 * The canonical app-edge builder. It offers the same set/map/count folds as
 * [AlignedObserveBuilder], plus the tagged-map fold used by [ObserveAllBuilder].
 * Views with equal structural root sets become one aligned group; unequal sets
 * become independent groups whose latest publications are assembled together.
 */
class ObservationBuilder internal constructor() {
    private val aligned = AlignedObserveBuilder()

    internal val specs: Map<String, AlignedObserveBuilder.Spec> get() = aligned.specs
    internal val unchecked: Set<String> get() = aligned.unchecked
    internal val writes = linkedMapOf<String, Set<CellRef>>()

    /** Declares one named multi-cell write alongside this observation. */
    fun write(name: String, cells: Set<CellRef>) {
        val declaredCells = cells.toSet()
        val existing = writes.putIfAbsent(name, declaredCells)
        require(existing == null || existing == declaredCells) {
            "observation write '$name' was already registered with cells $existing"
        }
    }

    /** Skip ungated-ancestor admission for the registered view [name] only. */
    fun unchecked(name: String) = aligned.unchecked(name)

    /** Fold a `SetDelta` outlet into a `Set` under [name]. */
    fun set(name: String, source: CellRef, outletName: String = "outlet") =
        aligned.set(name, source, outletName)

    /** Fold a `MapDelta` outlet into a `Map` under [name]. */
    fun map(name: String, source: CellRef, outletName: String = "outlet") =
        aligned.map(name, source, outletName)

    /** Fold a per-key count `MapDelta` outlet into counts under [name]. */
    fun count(name: String, source: CellRef, outletName: String = "outlet") =
        aligned.count(name, source, outletName)

    /** Fold a tagged-map outlet into a `Map` under [name]. */
    fun taggedMap(name: String, source: CellRef, outletName: String = "outlet") =
        aligned.add(name, source, outletName, View.taggedMap<Any?, Any?>(), "taggedMap")

    /** Typed [set] for a `SetApi<E>` source. */
    @JvmName("setFromSetApi")
    fun <E> set(name: String, source: TypedRef<out SetApi<E>>, outletName: String = "outlet") =
        aligned.set(name, source, outletName)

    /** Typed [set] for a `QuorumSetApi<E>` source. */
    @JvmName("setFromQuorumSetApi")
    fun <E> set(name: String, source: TypedRef<out QuorumSetApi<E>>, outletName: String = "outlet") =
        aligned.set(name, source, outletName)

    /** Typed [set] for a `FilterSetApi<E>` source. */
    @JvmName("setFromFilterSetApi")
    fun <E> set(name: String, source: TypedRef<out FilterSetApi<E>>, outletName: String = "outlet") =
        aligned.set(name, source, outletName)

    /** Typed [map] for a `MapApi<K, V>` source. */
    @JvmName("mapFromMapApi")
    fun <K, V> map(name: String, source: TypedRef<out MapApi<K, V>>, outletName: String = "outlet") =
        aligned.map(name, source, outletName)

    /** Typed [count] for the shipped `GroupByApi<E, K, Long>` count shape. */
    @JvmName("countFromGroupByApi")
    fun <E, K> count(name: String, source: TypedRef<out GroupByApi<E, K, Long>>, outletName: String = "outlet") =
        aligned.count(name, source, outletName)

    /** Typed [taggedMap] for an `OrMapApi<K, V>` source. */
    @JvmName("taggedMapFromOrMapApi")
    fun <K, V> taggedMap(name: String, source: TypedRef<out OrMapApi<K, V>>, outletName: String = "outlet") =
        aligned.add(name, source.ref, outletName, View.taggedMap<K, V>(), "taggedMap")
}

private data class RootSet(val local: Set<CellRef>, val opaque: Set<PortRef>)

private data class GroupDefinition(
    val id: String,
    val specs: LinkedHashMap<String, AlignedObserveBuilder.Spec>,
)

/**
 * Build the structural roots from the same live Consume-link walk admission
 * uses. A local root is an inclusive ancestor with no inbound Consume ancestry;
 * opaque producer ports are roots in their own right.
 */
private fun rootsOf(
    api: HostManagementApi,
    spec: AlignedObserveBuilder.Spec,
    walks: MutableMap<CellRef, UpstreamAncestry>,
): RootSet {
    val ancestry = walks.getOrPut(spec.source) { api.upstreamConsumeAncestors(spec.source) }
    val candidates = linkedSetOf<CellRef>()
    if (ancestry.self != null) candidates += spec.source
    candidates += ancestry.local.keys
    val localRoots = candidates.filterTo(linkedSetOf()) { candidate ->
        val candidateAncestry = walks.getOrPut(candidate) { api.upstreamConsumeAncestors(candidate) }
        candidateAncestry.self != null && candidateAncestry.local.isEmpty() && candidateAncestry.opaque.isEmpty()
    }
    return RootSet(localRoots, ancestry.opaque.toSet())
}

private class ObservationCoordinator(
    private val groupCells: LinkedHashMap<String, AlignedCompositeCell>,
    private val groupOfView: LinkedHashMap<String, String>,
    private val declaredWrites: LinkedHashMap<String, DeclaredWrite>,
) : Observation {
    private val lock = Any()
    private val listeners = mutableListOf<(ObservationFrame) -> Unit>()
    private val groupSnapshots = groupCells.mapValuesTo(LinkedHashMap()) { it.value.composite() }
    private val singleGroup = groupCells.entries.singleOrNull()
    private var dispatcher: ExecutorService? = null
    private var closed = false

    @Volatile
    private var latest: ObservationFrame = assemble(groupSnapshots)

    init {
        // A one-group observation reads that group directly and forwards
        // listeners to it below. Registering this coordinator as a second
        // listener would eagerly mint the group's dispatcher even when the
        // app only calls current(), defeating the one-group/no-extra-thread
        // contract used by per-key observations.
        if (singleGroup == null) {
            groupCells.forEach { (id, cell) ->
                cell.onComposite { composite -> onGroup(id, composite) }
            }
        }
    }

    override fun current(): ObservationFrame = synchronized(lock) {
        // A group's published composite advances on the host path, before its
        // listener callback reaches this coordinator's dispatcher. Synchronous
        // reads therefore assemble from those authoritative publications rather
        // than from the callback cache used to preserve listener delivery order.
        assemble(groupCells.mapValuesTo(LinkedHashMap()) { it.value.composite() })
    }

    override fun onChange(listener: (ObservationFrame) -> Unit) {
        val one = singleGroup
        if (one != null) {
            synchronized(lock) { if (closed) return }
            one.value.onComposite { composite ->
                listener(assemble(linkedMapOf(one.key to composite)))
            }
            return
        }
        synchronized(lock) {
            listeners += listener
            val snapshot = latest
            dispatchIfOpen { listener(snapshot) }
        }
    }

    override fun group(id: String): AlignedCompositeCell =
        requireNotNull(groupCells[id]) { "no observation group '$id' (available: ${groupCells.keys})" }

    override fun write(name: String): DeclaredWrite =
        requireNotNull(declaredWrites[name]) {
            "no observation write '$name' (available: ${declaredWrites.keys})"
        }

    override val groups: Set<String> = groupCells.keys.toCollection(LinkedHashSet())

    override val bufferedWaves: Int get() = groupCells.values.sumOf { it.bufferedWaves }

    private fun onGroup(id: String, composite: AlignedComposite) {
        synchronized(lock) {
            if (closed || groupSnapshots[id] == composite) return
            groupSnapshots[id] = composite
            latest = assemble(groupSnapshots)
            if (singleGroup != null) return
            val snapshot = latest
            val fired = listeners.toList()
            if (fired.isNotEmpty()) dispatchIfOpen { fired.forEach { it(snapshot) } }
        }
    }

    private fun assemble(snapshots: Map<String, AlignedComposite>): ObservationFrame {
        val copiedGroups = LinkedHashMap(snapshots)
        val views = linkedMapOf<String, Any?>()
        groupCells.keys.forEach { group ->
            copiedGroups.getValue(group).views.forEach { (name, value) -> views[name] = value }
        }
        val crossRoot = linkedMapOf<GroupPair, CrossRootStaleness>()
        val ids = groupCells.keys.toList()
        for (leftIndex in ids.indices) {
            for (rightIndex in leftIndex + 1 until ids.size) {
                val pair = GroupPair.of(ids[leftIndex], ids[rightIndex])
                val left = copiedGroups.getValue(pair.a).frontier
                val right = copiedGroups.getValue(pair.b).frontier
                val lag = left.keys.intersect(right.keys).associateWithTo(linkedMapOf()) { source ->
                    left.getValue(source) - right.getValue(source)
                }
                crossRoot[pair] = CrossRootStaleness(lag)
            }
        }
        return ObservationFrame(
            views = views,
            groups = copiedGroups,
            groupOf = LinkedHashMap(groupOfView),
            crossRoot = crossRoot,
        )
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
        groupCells.values.forEach(AlignedCompositeCell::close)
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

        for (group in groupCells.values) {
            if (!group.awaitTermination(remainingMillis())) return false
        }
        return awaitExecutorsTermination(listOf(ownDispatcher), remainingMillis())
    }
}

/**
 * Canonical app-edge observation entry point.
 *
 * Registered views are partitioned by **equal** structural root sets, never by
 * root overlap. Each partition is admitted and served by one
 * [AlignedCompositeCell], preserving `[22-OBS-01]`/`[22-OBS-02]` within the
 * group while `[22-LIVE-01]` forbids waiting across groups. [groupRef] can give
 * each group a stable sink identity; its argument is the group's registered
 * view names, in order, joined by `+`.
 *
 * Admission for every group completes before any group cell is spawned. The
 * topology read has [observeAligned]'s live-link caveat: later links are not
 * rechecked and graph construction must not mutate the link set concurrently.
 */
fun Use<HostManagementApi>.observation(
    mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT,
    onViolation: (GlitchViolation) -> Unit = {},
    groupRef: (groupId: String) -> CellRef = { CellRef(UUID.randomUUID()) },
    block: ObservationBuilder.() -> Unit,
): Observation {
    val builder = ObservationBuilder().apply(block)
    require(builder.specs.isNotEmpty()) { "observation requires at least one registered view" }
    require(builder.unchecked.all { it in builder.specs }) {
        "observation: unchecked views must be registered: " +
            builder.unchecked.filterNot { it in builder.specs }
    }

    val walks = mutableMapOf<CellRef, UpstreamAncestry>()
    val byRoots = LinkedHashMap<RootSet, LinkedHashMap<String, AlignedObserveBuilder.Spec>>()
    builder.specs.forEach { (name, spec) ->
        byRoots.getOrPut(rootsOf(call, spec, walks)) { linkedMapOf() }[name] = spec
    }
    val definitions = byRoots.values.map { specs -> GroupDefinition(specs.keys.joinToString("+"), specs) }

    // All rejection paths precede every constructor/spawn/groupRef call.
    definitions.forEach { definition ->
        val unchecked = builder.unchecked.filterTo(linkedSetOf()) { it in definition.specs }
        when (val verdict = admitAligned(call, definition.specs, unchecked)) {
            is AdmissionVerdict.Rejected -> throw AlignedAdmissionException(verdict)
            is AdmissionVerdict.Admitted -> Unit
        }
    }

    val declaredWrites = builder.writes.mapValuesTo(linkedMapOf()) { (name, cells) ->
        call.declareWrite(name, cells)
    }

    val groups = linkedMapOf<String, AlignedCompositeCell>()
    val groupOf = linkedMapOf<String, String>()
    definitions.forEach { definition ->
        val cell = AlignedCompositeCell(
            views = definition.specs.mapValues { it.value.view },
            registeredAs = definition.specs.mapValues { it.value.kind },
            ref = groupRef(definition.id),
            mode = mode,
            onViolation = onViolation,
        )
        call.spawn(cell)
        definition.specs.forEach { (name, spec) ->
            val result = call.connect(spec.source, spec.outletName, cell.ref, name)
            check(result !is LinkResult.Rejected) {
                "observation: link ${spec.source}.${spec.outletName} -> '$name' rejected: " +
                    (result as LinkResult.Rejected).reason
            }
            groupOf[name] = definition.id
        }
        groups[definition.id] = cell
    }
    return ObservationCoordinator(groups, groupOf, declaredWrites)
}

/** Convenience for observing cells on this [ManagedHost]. */
fun ManagedHost.observation(
    mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT,
    onViolation: (GlitchViolation) -> Unit = {},
    groupRef: (groupId: String) -> CellRef = { CellRef(UUID.randomUUID()) },
    block: ObservationBuilder.() -> Unit,
): Observation = managementInlet.observation(mode, onViolation, groupRef, block)
