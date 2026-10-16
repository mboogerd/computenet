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

/** A live hand-assembled TPC-H query attached to hosted base relations. */
interface MaintainedQuery {
    val query: TpchQuery

    fun read(): ViewResult

    /** Current rows/keys at each operator outlet, keyed by the operator's spawn name. */
    fun liveRows(): Map<String, Long>

    /** Every live intermediate row or grouped key that still names [orderKey]. */
    fun rowsReferencing(orderKey: Long): List<Pair<String, Any>>
}

/** Marker implemented by key-carrying join rows and grouped keys. */
internal interface OrderKeyCarrier {
    val orderKey: Long
}

/**
 * Payload-validated plan for one hand-assembled graph.
 *
 * Validation occurs in this constructor, before [attach] can be given a host.
 */
abstract class QueryGraphBuilder(
    rowPayload: Class<*>,
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

    abstract fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery
}

/** A tag-correct materialization of a set-shaped operator outlet. */
internal class SetRowsCollectorCell<E>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    private val view = SetView<E>()

    val size: Int
        get() = view.size

    fun rows(): Set<E> = view.current()

    init {
        inlet.onEach(view::apply)
    }
}

/** A materialization of one `GroupByCell` Long-valued outlet. */
internal class GroupCollectorCell<K>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, Long>>>())
    private val view = MapView<K, Long>()

    val size: Int
        get() = view.size

    fun values(): Map<K, Long> = view.current()

    init {
        inlet.onEach(view::apply)
    }
}

/** Shared accounting and deletion scan for concrete query graphs. */
internal abstract class CollectedQuery(
    final override val query: TpchQuery,
    private val setOutlets: Map<String, SetRowsCollectorCell<*>>,
    private val groupOutlets: Map<String, GroupCollectorCell<*>>,
) : MaintainedQuery {

    final override fun liveRows(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        setOutlets.forEach { (name, collector) -> put(name, collector.size.toLong()) }
        groupOutlets.forEach { (name, collector) -> put(name, collector.size.toLong()) }
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
    }

    private fun Any.references(expected: Long): Boolean = when (this) {
        is OrderKeyCarrier -> orderKey == expected
        is Orders -> orderKey == expected
        is Lineitem -> orderKey == expected
        else -> false
    }
}
