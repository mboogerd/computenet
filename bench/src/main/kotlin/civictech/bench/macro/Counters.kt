package civictech.bench.macro

import civictech.bench.cost.StructuralCounts
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.Journal
import civictech.cell.host.HostScheduler
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.FrameInterposer
import java.util.concurrent.atomic.AtomicLong

/** Counts encoded frames crossing one named simulation edge. */
class FrameCounter : FrameInterposer {
    val frames = AtomicLong()
    val bytes = AtomicLong()

    override fun apply(frame: ByteArray, step: Int): List<ByteArray> {
        frames.incrementAndGet()
        bytes.addAndGet(frame.size.toLong())
        return listOf(frame)
    }
}

/**
 * Counts accepted appends while preserving the wrapped journal's behavior and
 * durability declaration.
 */
class CountingJournal(private val inner: Journal) : Journal {
    val appends = AtomicLong()

    override val formatVersion: Int
        get() = inner.formatVersion

    override val durability: DurabilityClass
        get() = inner.durability

    override fun append(record: ByteArray) {
        appends.incrementAndGet()
        inner.append(record)
    }

    override fun replay(): List<ByteArray> = inner.replay()

    override fun reset(records: List<ByteArray>) {
        inner.reset(records)
    }

    override fun toString(): String = "CountingJournal(inner=$inner)"
}

/**
 * Counts data-host delivery tasks without changing the graph or the kernel.
 *
 * The macro mesh constructs `ManagedHost` with its default `dispatchBatch == 1` and
 * generated shapes are DAGs. In the measured interval (after graph setup, with no
 * recovery loop), the data host's priority-20 tasks are therefore exactly its accepted
 * local deliveries: `ManagedHost.accept` stages one invocation and submits one
 * `dispatchOneWhenRecoveryReady` task for it. Cycle-head barriers share priority 20 but
 * cannot occur in these acyclic shapes; bridge-host work is excluded because only the
 * data-host scheduler is decorated. The counter advances when the task actually starts,
 * so work merely submitted beyond a failed or non-convergent point is never reported.
 *
 * Local cell-to-cell fan-out remains synchronous inside that one delivery task. This is
 * the same observable unit as `MacroCalibrationBenchmark.realLocalDispatch`, which
 * measures one delivery plus its scheduler hop. If the kernel changes the data priority
 * or batching contract, `PointAccountingTest.singleHostChainCountsOneExactLocalDeliveryPerInput`
 * fails instead of silently turning this into an estimate.
 */
class LocalDeliveryCounter(private val inner: HostScheduler) : HostScheduler by inner {
    val deliveries = AtomicLong()

    override fun submit(priority: Int, action: suspend () -> Unit) {
        inner.submit(priority) {
            if (priority == MANAGED_HOST_DATA_PRIORITY) deliveries.incrementAndGet()
            action()
        }
    }

    private companion object {
        const val MANAGED_HOST_DATA_PRIORITY = 20
    }
}

data class CounterSnapshot(
    val messages: Long,
    val frames: Long,
    val bytes: Long,
    val journalRecords: Long,
)

/** Installs and reads the structural counters used by a macro sweep point. */
class Counters(
    private val deliveries: List<LocalDeliveryCounter>,
    private val frames: List<FrameCounter>,
    private val journals: List<CountingJournal>,
) {
    fun snapshot(): CounterSnapshot = CounterSnapshot(
        messages = deliveries.sumOf { it.deliveries.get() },
        frames = frames.sumOf { it.frames.get() },
        bytes = frames.sumOf { it.bytes.get() },
        journalRecords = journals.sumOf { it.appends.get() },
    )

    fun counts(start: CounterSnapshot, simSteps: Long): StructuralCounts {
        val end = snapshot()
        return StructuralCounts(
            messages = end.messages - start.messages,
            messagesNotMeasured = null,
            crossHostFrames = end.frames - start.frames,
            wireBytes = end.bytes - start.bytes,
            journalRecords = end.journalRecords - start.journalRecords,
            simSteps = simSteps,
        )
    }

    companion object {
        /**
         * REAL sweep points report timing observations, not deterministic structural
         * counts. SIM points never use this reason: [LocalDeliveryCounter] measures them.
         */
        const val MESSAGES_NOT_MEASURED = "local deliveries are not recorded for REAL-drive timing points"

        fun installOn(
            world: DstWorld,
            journals: List<CountingJournal>,
            deliveries: List<LocalDeliveryCounter> = emptyList(),
        ): Pair<Counters, AutoCloseable> {
            val installed = world.edges.names().map { name ->
                val counter = FrameCounter()
                counter to world.edges.intercept(name, counter)
            }
            return Counters(deliveries, installed.map { it.first }, journals) to AutoCloseable {
                installed.forEach { it.second.close() }
            }
        }
    }
}
