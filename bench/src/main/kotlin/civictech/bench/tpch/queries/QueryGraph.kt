package civictech.bench.tpch.queries

import civictech.bench.tpch.BaseRelations
import civictech.bench.tpch.Lineitem
import civictech.bench.tpch.Orders
import civictech.bench.tpch.TpchQuery
import civictech.bench.tpch.ViewResult
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.host.HostManagementApi
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/** A live hand-assembled TPC-H query attached to hosted base relations. */
interface MaintainedQuery {
    val query: TpchQuery

    fun read(): ViewResult

    /** Current rows/keys at each operator outlet, keyed by the operator's spawn name. */
    fun liveRows(): Map<String, Long>

    /** Cumulative delta elements applied by each outlet collector. */
    fun emissionCounters(): Map<String, Long>

    /** Every live intermediate row or grouped key that still names [orderKey]. */
    fun rowsReferencing(orderKey: Long): List<Pair<String, Any>>
}

/** Marker implemented by key-carrying join rows and grouped keys. */
interface OrderKeyCarrier {
    val orderKey: Long
}

/**
 * Payload-validated plan for one hand-assembled graph.
 *
 * Validation occurs in this constructor, before [attach] can be given a host.
 */
abstract class QueryGraphBuilder(
    private val rowPayload: Class<*>,
) {
    init {
        require(
            !Owned::class.java.isAssignableFrom(rowPayload) &&
                !Leased::class.java.isAssignableFrom(rowPayload),
        ) {
            "[BB-TPCH-28] TPC-H query graph refuses Owned or Leased row payloads: ${rowPayload.name}"
        }
    }

    abstract val query: TpchQuery

    /** Parameters shared with the independent batch reference for this graph. */
    internal abstract val referenceParams: Any

    /**
     * Runtime row types entering this graph's folds. Repeated entries represent
     * independent folds over the same row type (3xi.3-D4 / BS-11).
     */
    fun foldInputTypes(): List<KClass<*>> = List(foldCount(query)) { rowPayload.kotlin }

    abstract fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery

    private fun foldCount(query: TpchQuery): Int = when (query) {
        TpchQuery.Q01 -> 11
        TpchQuery.Q08, TpchQuery.Q10, TpchQuery.Q12, TpchQuery.Q14 -> 2
        else -> 1
    }
}

/** Accounting and deletion-scan surface shared by materialized operator outlets. */
internal interface CollectedOutlet {
    val size: Int
    val appliedElements: Long
    fun referenceCandidates(): Sequence<Any>
}

/** A tag-correct materialization of a set-shaped operator outlet. */
internal class SetRowsCollectorCell<E>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    private val view = SetView<E>()
    private val appliedElementCounter = AtomicLong()

    override val size: Int
        get() = view.size

    override val appliedElements: Long
        get() = appliedElementCounter.get()

    fun rows(): Set<E> = view.current()

    override fun referenceCandidates(): Sequence<Any> = rows().asSequence().filterNotNull().map { it as Any }

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.adds.size.toLong() + delta.dels.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
        }
    }
}

/** A materialization of one `GroupByCell` Long-valued outlet. */
internal class GroupCollectorCell<K>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, Long>>>())
    private val view = MapView<K, Long>()
    private val appliedElementCounter = AtomicLong()

    override val size: Int
        get() = view.size

    override val appliedElements: Long
        get() = appliedElementCounter.get()

    fun values(): Map<K, Long> = view.current()

    override fun referenceCandidates(): Sequence<Any> = values().keys.asSequence().filterNotNull().map { it as Any }

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.puts.size.toLong() + delta.removals.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
        }
    }
}

/** A materialization of a `GroupByCell` whose value is an ordered row list. */
internal class GroupListCollectorCell<K, E>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, List<E>>>>())
    private val view = MapView<K, List<E>>()
    private val appliedElementCounter = AtomicLong()

    override val size: Int
        get() = view.size

    override val appliedElements: Long
        get() = appliedElementCounter.get()

    fun values(): Map<K, List<E>> = view.current()

    override fun referenceCandidates(): Sequence<Any> = sequence {
        values().forEach { (key, rows) ->
            if (key != null) yield(key as Any)
            rows.filterNotNull().forEach { yield(it as Any) }
        }
    }

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.puts.size.toLong() + delta.removals.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
        }
    }
}

/** Shared accounting and deletion scan for concrete query graphs. */
internal abstract class CollectedQuery(
    final override val query: TpchQuery,
    private val setOutlets: Map<String, SetRowsCollectorCell<*>>,
    private val groupOutlets: Map<String, GroupCollectorCell<*>>,
    private val additionalOutlets: Map<String, CollectedOutlet> = emptyMap(),
) : MaintainedQuery {

    final override fun liveRows(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        setOutlets.forEach { (name, collector) -> put(name, collector.size.toLong()) }
        groupOutlets.forEach { (name, collector) -> put(name, collector.size.toLong()) }
        additionalOutlets.forEach { (name, collector) -> put(name, collector.size.toLong()) }
    }

    final override fun emissionCounters(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        setOutlets.forEach { (name, collector) -> put(name, collector.appliedElements) }
        groupOutlets.forEach { (name, collector) -> put(name, collector.appliedElements) }
        additionalOutlets.forEach { (name, collector) -> put(name, collector.appliedElements) }
    }

    final override fun rowsReferencing(orderKey: Long): List<Pair<String, Any>> = buildList {
        setOutlets.forEach { (name, collector) ->
            collector.rows().forEach { row ->
                if (row != null && row.references(orderKey)) add(name to row)
            }
        }
        groupOutlets.forEach { (name, collector) ->
            collector.values().keys.forEach { key ->
                if (key != null && key.references(orderKey)) add(name to key)
            }
        }
        additionalOutlets.forEach { (name, collector) ->
            collector.referenceCandidates().forEach { candidate ->
                if (candidate.references(orderKey)) add(name to candidate)
            }
        }
    }

    private fun Any.references(expected: Long): Boolean = when (this) {
        is OrderKeyCarrier -> orderKey == expected
        is Orders -> orderKey == expected
        is Lineitem -> orderKey == expected
        else -> false
    }
}
