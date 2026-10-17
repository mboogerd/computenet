package civictech.bench.nexmark.queries

import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.LatenessAccounting
import civictech.bench.nexmark.LatenessPolicy
import civictech.bench.nexmark.NexmarkQuery
import civictech.bench.nexmark.NexmarkResult
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.graph.CellHandle
import civictech.cell.graph.GraphBuilder
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.TypedCellFactory
import civictech.cell.graph.TypedCellHandle
import civictech.cell.graph.graph as buildGraph
import civictech.cell.host.HostManagementApi
import civictech.cell.link.LinkOptions
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.identity
import civictech.cell.port.registerPort
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A live, collector-observed Nexmark query. */
interface MaintainedQuery {
    val query: NexmarkQuery

    fun read(): NexmarkResult

    fun liveRows(): Map<String, Long>

    fun emissionCounters(): Map<String, Long>

    fun lateness(): LatenessAccounting?

    val maxFanOut: Int
}

/**
 * Payload-validated base for a hand-assembled Nexmark graph.
 *
 * Exclusive payload validation occurs before [attach] can be given a host.
 * Concrete non-windowed builders call [validatePolicyFor] from their own init.
 */
abstract class NexmarkQueryBuilder(
    eventPayload: Class<*>,
    val policy: LatenessPolicy?,
) {
    private val linkCounts = linkedMapOf<String, Int>()

    init {
        require(
            !Owned::class.java.isAssignableFrom(eventPayload) &&
                !Leased::class.java.isAssignableFrom(eventPayload),
        ) {
            "[BB-NEX-27] Nexmark query graph refuses Owned or Leased event payloads: ${eventPayload.name}"
        }
    }

    abstract val query: NexmarkQuery

    abstract fun attach(
        host: Use<HostManagementApi>,
        streams: EventStreams,
    ): MaintainedQuery

    protected fun validatePolicyFor(query: NexmarkQuery) {
        require(policy == null || query.windowed) {
            "${query.name} is not windowed and refuses a lateness policy"
        }
    }

    /** Build through the typed graph path while inventorying fan-out per source outlet. */
    protected fun graph(
        host: Use<HostManagementApi>,
        block: QueryGraphScope.() -> Unit,
    ): GraphSpec {
        linkCounts.clear()
        return buildGraph(host) {
            QueryGraphScope(this, ::recordLink).block()
        }
    }

    protected val recordedMaxFanOut: Int
        get() = linkCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

    private fun recordLink(outlet: Subscribe<*>) {
        val identity = outlet.identity()
        val key = identity?.let { "${it.owner}.${it.name}" }
            ?: "unidentified-${System.identityHashCode(outlet)}"
        linkCounts[key] = Math.addExact(linkCounts[key] ?: 0, 1)
    }

    /** The deliberately small graph surface needed by hand-written query builders. */
    protected class QueryGraphScope internal constructor(
        private val delegate: GraphBuilder,
        private val onLink: (Subscribe<*>) -> Unit,
    ) {
        fun <C : Cell> spawn(
            name: String,
            factory: TypedCellFactory<C>,
        ): TypedCellHandle<C> = delegate.spawn(name, factory = factory)

        fun adopt(cell: Cell): CellHandle = delegate.adopt(cell)

        fun <Api> link(
            out: Subscribe<Api>,
            inn: Serve<Api>,
            options: LinkOptions = LinkOptions.DEFAULT,
        ) {
            onLink(out)
            delegate.link(out, inn, options)
        }
    }
}

/** Accounting surface shared by materialized operator outlets. */
internal interface CollectedOutlet {
    val size: Int
    val appliedElements: Long
}

/** A tag-correct materialization of a set-shaped outlet. */
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

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.adds.size.toLong() + delta.dels.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
        }
    }
}

/** A materialization of a map-shaped grouped outlet. */
internal class GroupCollectorCell<K, A>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, A>>>())
    private val view = MapView<K, A>()
    private val appliedElementCounter = AtomicLong()

    override val size: Int
        get() = view.size

    override val appliedElements: Long
        get() = appliedElementCounter.get()

    fun values(): Map<K, A> = view.current()

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.puts.size.toLong() + delta.removals.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
        }
    }
}

/** Counts adds emitted by a lateness outlet without using them as query results. */
internal class LateCollectorCell<E>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    private val droppedCounter = AtomicLong()

    override val size: Int
        get() = 0

    override val appliedElements: Long
        get() = droppedCounter.get()

    val droppedLate: Long
        get() = droppedCounter.get()

    init {
        inlet.onEach { delta ->
            droppedCounter.updateAndGet { current ->
                Math.addExact(current, delta.adds.size.toLong())
            }
        }
    }
}

/** Shared collector accounting for concrete query graphs. */
internal abstract class CollectedQuery(
    final override val query: NexmarkQuery,
    private val outlets: Map<String, CollectedOutlet>,
    final override val maxFanOut: Int,
) : MaintainedQuery {
    final override fun liveRows(): Map<String, Long> =
        outlets.mapValuesTo(linkedMapOf()) { (_, collector) -> collector.size.toLong() }

    final override fun emissionCounters(): Map<String, Long> =
        outlets.mapValuesTo(linkedMapOf()) { (_, collector) -> collector.appliedElements }

    override fun lateness(): LatenessAccounting? = null
}
