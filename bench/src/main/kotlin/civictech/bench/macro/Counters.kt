package civictech.bench.macro

import civictech.bench.cost.StructuralCounts
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.Journal
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

data class CounterSnapshot(
    val frames: Long,
    val bytes: Long,
    val journalRecords: Long,
)

/** Installs and reads the structural counters used by a macro sweep point. */
class Counters(
    private val frames: List<FrameCounter>,
    private val journals: List<CountingJournal>,
) {
    fun snapshot(): CounterSnapshot = CounterSnapshot(
        frames = frames.sumOf { it.frames.get() },
        bytes = frames.sumOf { it.bytes.get() },
        journalRecords = journals.sumOf { it.appends.get() },
    )

    fun counts(start: CounterSnapshot, simSteps: Long): StructuralCounts {
        val end = snapshot()
        return StructuralCounts(
            messages = null,
            messagesNotMeasured = MESSAGES_NOT_MEASURED,
            crossHostFrames = end.frames - start.frames,
            wireBytes = end.bytes - start.bytes,
            journalRecords = end.journalRecords - start.journalRecords,
            simSteps = simSteps,
        )
    }

    companion object {
        /**
         * Local dispatches are not measured through any harness seam. The evaluated
         * alternatives were `FanOutlet.targetMisses`, which counts misses rather than
         * dispatches; tapping every outlet, which changes the fan-out set being measured;
         * and `simSteps`, which also counts management tasks. The candidate G-item is a
         * per-host dispatch counter, and under SIM `simSteps` is only an upper bound.
         *
         * The runner records this exact reason instead of manufacturing a local-message
         * count; a kernel counter is forbidden by [BEN2-51], and the sweep findings entry
         * carries the candidate G-item per [BEN2-50].
         */
        const val MESSAGES_NOT_MEASURED =
            "local dispatches are not observable harness-side: ManagedHost submits each delivery to its scheduler internally (ManagedHost.kt:1335) and the mesh owns the schedulers; a kernel counter is forbidden by [BEN2-51]. Candidate G-item: a per-host dispatch counter. simSteps is an upper bound under SIM."

        fun installOn(
            world: DstWorld,
            journals: List<CountingJournal>,
        ): Pair<Counters, AutoCloseable> {
            val installed = world.edges.names().map { name ->
                val counter = FrameCounter()
                counter to world.edges.intercept(name, counter)
            }
            return Counters(installed.map { it.first }, journals) to AutoCloseable {
                installed.forEach { it.second.close() }
            }
        }
    }
}
