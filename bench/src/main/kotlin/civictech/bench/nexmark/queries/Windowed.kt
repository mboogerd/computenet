package civictech.bench.nexmark.queries

import civictech.bench.nexmark.ClockTick
import civictech.bench.nexmark.ClockTime
import civictech.bench.nexmark.EventStreams
import civictech.bench.nexmark.LatenessAccounting
import civictech.bench.nexmark.LatenessPolicy
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.WaterlineCell
import civictech.cell.data.Windows
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.graph.TypedCellHandle
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Common graph assembly for the clock-only waterline used by Q7 and Q8. */
abstract class WindowedQueryBuilder(
    eventPayload: Class<*>,
    policy: LatenessPolicy?,
) : NexmarkQueryBuilder(eventPayload, policy) {
    protected fun QueryGraphScope.spawnWaterline(
        name: String,
        streams: EventStreams,
    ): TypedCellHandle<WaterlineCell<ClockTick>> {
        val lateness = requireNotNull(policy) { "a waterline requires a lateness policy" }.lateness
        adopt(streams.clock)
        val waterline = spawn(name) { ref ->
            WaterlineCell(ref = ref, lateness = Windows.Lateness(ClockTime, lateness))
        }
        // The clock is deliberately the waterline's only source. Data arms are
        // linked by each query only after this arm reaches the operator.
        link(streams.clock.outlet, waterline.cell.inlet)
        return waterline
    }
}

/** A tumbling window key's exclusive end, used only as the eviction clock. */
internal data class WindowEnd(private val size: Long) : (Long) -> Long, Serializable {
    init {
        require(size > 0) { "window size must be positive" }
    }

    override fun invoke(windowStart: Long): Long = Math.addExact(windowStart, size)
}

/**
 * Reads the concrete lateness cells at quiescence and cross-checks their late
 * counters against the linked late collectors.
 */
internal class WindowedAccounting private constructor(
    private val floor: () -> Long?,
    private val cellDropped: () -> Long,
    private val collectedDropped: () -> Long,
    private val refused: () -> Long,
    private val evicted: () -> Long,
) {
    fun read(): LatenessAccounting {
        val fromCell = cellDropped()
        val fromCollectors = collectedDropped()
        check(fromCell == fromCollectors) {
            "lateness outlet counted $fromCollectors drop(s), cell counted $fromCell"
        }
        return LatenessAccounting(
            floor = floor(),
            droppedLate = fromCell,
            refusedEvictions = refused(),
            evicted = evicted(),
        )
    }

    companion object {
        fun <E, K, A, ACC : Serializable> group(
            cell: GroupByCell<E, K, A, ACC>,
            late: LateCollectorCell<E>,
            evicted: () -> Long,
        ): WindowedAccounting = WindowedAccounting(
            floor = cell::floor,
            cellDropped = { cell.droppedBelowFloor },
            collectedDropped = { late.droppedLate },
            refused = { cell.refusedEvictions },
            evicted = evicted,
        )

        fun <A, B, K, C> join(
            cell: JoinSetCell<A, B, K, C>,
            lateLeft: LateCollectorCell<A>,
            lateRight: LateCollectorCell<B>,
            evicted: () -> Long,
        ): WindowedAccounting = WindowedAccounting(
            floor = cell::floor,
            cellDropped = {
                Math.addExact(cell.droppedBelowFloorLeft, cell.droppedBelowFloorRight)
            },
            collectedDropped = {
                Math.addExact(lateLeft.droppedLate, lateRight.droppedLate)
            },
            refused = { cell.refusedEvictions },
            evicted = evicted,
        )
    }
}

/** Map materialization that also counts windows removed from the sink. */
internal class WindowGroupCollectorCell<K, A>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : civictech.cell.Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, A>>>())
    private val view = MapView<K, A>()
    private val appliedElementCounter = AtomicLong()
    private val removalCounter = AtomicLong()

    override val size: Int
        get() = view.size

    override val appliedElements: Long
        get() = appliedElementCounter.get()

    val removals: Long
        get() = removalCounter.get()

    fun values(): Map<K, A> = view.current()

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.puts.size.toLong() + delta.removals.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
            removalCounter.updateAndGet { current ->
                Math.addExact(current, delta.removals.size.toLong())
            }
        }
    }
}

/** Set materialization that also counts rows retracted from the sink. */
internal class WindowSetCollectorCell<E>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : civictech.cell.Cell, CollectedOutlet {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    private val view = SetView<E>()
    private val appliedElementCounter = AtomicLong()
    private val removalCounter = AtomicLong()

    override val size: Int
        get() = view.size

    override val appliedElements: Long
        get() = appliedElementCounter.get()

    val removals: Long
        get() = removalCounter.get()

    fun rows(): Set<E> = view.current()

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            val elements = delta.adds.size.toLong() + delta.dels.size.toLong()
            appliedElementCounter.updateAndGet { current -> Math.addExact(current, elements) }
            removalCounter.updateAndGet { current ->
                Math.addExact(current, delta.dels.size.toLong())
            }
        }
    }
}
