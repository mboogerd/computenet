package civictech.bench.macro

import civictech.bench.HostFacts
import civictech.bench.macro.journal.JournalPlan
import civictech.bench.macro.journal.JournalRealRig
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * REAL wall time for the durable DIAMOND point at one host ([BEN2-24], 9f7x5-D11).
 *
 * Every method is a single-shot invocation in milliseconds. The rig and graph are
 * prepared outside the timed region; file and memory points time only the durable
 * drive through [JournalRealRig], replay times a fresh [FileJournal] over the one
 * file written during trial setup, and checkpoint times compaction after a completed
 * drive. H=1 is intentional: the G-25 unit is one host journal, while a multi-host
 * file would mix local ingress with cross-host frames. JMH's 99.9% error over the
 * declared three forks and five measured single shots is the dispersion reported by
 * the render step. An OOM kills a fork and therefore leaves an absent row for the
 * render to disclose rather than inventing a successful result. REAL C=1e6 is omitted
 * because three forks × seven invocations × one million fsyncs is roughly eight minutes
 * per method at the current ~24 microsecond sync cost, before propagation overhead.
 */
@Fork(JournalCostBenchmark.FORKS)
@Warmup(iterations = JournalCostBenchmark.WARMUP)
@Measurement(iterations = JournalCostBenchmark.MEASUREMENT)
open class JournalCostBenchmark {

    /** A fresh durable graph for each timed file-journal drive. */
    @State(Scope.Thread)
    open class FileState {
        @Param("1000", "10000", "100000")
        @JvmField
        var cardinality: Long = 1_000

        private lateinit var directory: File
        private lateinit var file: File
        private lateinit var journal: CountingJournal
        private var rig: JournalRealRig? = null
        private var invocation: Long = 0

        @Setup(Level.Trial)
        fun announceHost() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
            directory = Files.createTempDirectory("computenet-journal-file-").toFile()
        }

        @Setup(Level.Invocation)
        fun openRig() {
            file = File(directory, "invocation-${invocation++}.journal")
            journal = CountingJournal(FileJournal(file))
            rig = JournalRealRig(JournalPlan.coordinate(cardinality, BATCH_SIZE), journal)
                .also { it.applyGraph() }
        }

        @TearDown(Level.Invocation)
        fun closeRig() {
            rig?.close()
            rig = null
            file.delete()
        }

        @TearDown(Level.Trial)
        fun deleteDirectory() {
            directory.deleteRecursively()
        }

        fun drive(): Long = checkNotNull(rig).drive(DRIVE_TIMEOUT_MS)
    }

    /** The same graph and drive with an in-memory journal, for the fsync comparison. */
    @State(Scope.Thread)
    open class MemoryState {
        @Param("1000", "10000", "100000")
        @JvmField
        var cardinality: Long = 1_000

        private lateinit var journal: InMemoryJournal
        private var rig: JournalRealRig? = null

        @Setup(Level.Trial)
        fun announceHost() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Setup(Level.Invocation)
        fun openRig() {
            journal = InMemoryJournal()
            rig = JournalRealRig(JournalPlan.coordinate(cardinality, BATCH_SIZE), journal)
                .also { it.applyGraph() }
        }

        @TearDown(Level.Invocation)
        fun closeRig() {
            rig?.close()
            rig = null
        }

        fun drive(): Long = checkNotNull(rig).drive(DRIVE_TIMEOUT_MS)
    }

    /** One durable file per trial, replayed afresh for every measured invocation. */
    @State(Scope.Thread)
    open class ReplayState {
        @Param("1000", "10000", "100000")
        @JvmField
        var cardinality: Long = 1_000

        private lateinit var directory: File
        private lateinit var trialFile: File
        private var checkpointBytes: Long = 0
        private var records: Long = 0

        @Setup(Level.Trial)
        fun prepareTrial() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
            directory = Files.createTempDirectory("computenet-journal-replay-").toFile()
            trialFile = File(directory, "trial.journal")
            driveAndClose(trialFile)

            val checkpointFile = File(directory, "checkpoint.journal")
            driveAndClose(checkpointFile, checkpoint = true)
            checkpointBytes = checkpointFile.length()
            records = FileJournal(trialFile).replay().size.toLong()
            check(records > 0) { "journal trial produced no records" }
            println(
                "# Macro journal: cardinality=$cardinality records=$records " +
                    "fileBytes=${trialFile.length()} checkpointBytes=$checkpointBytes",
            )
        }

        @TearDown(Level.Trial)
        fun deleteTrial() {
            directory.deleteRecursively()
        }

        fun replay(): Int = FileJournal(trialFile).replay().size

        private fun driveAndClose(file: File, checkpoint: Boolean = false) {
            val journal = CountingJournal(FileJournal(file))
            JournalRealRig(JournalPlan.coordinate(cardinality, BATCH_SIZE), journal).use { rig ->
                rig.applyGraph()
                rig.drive(DRIVE_TIMEOUT_MS)
                if (checkpoint) rig.checkpoint(journal)
            }
        }
    }

    /** The durable drive is setup work; only host checkpoint compaction is timed. */
    @State(Scope.Thread)
    open class CheckpointState {
        @Param("1000", "10000", "100000")
        @JvmField
        var cardinality: Long = 1_000

        private lateinit var directory: File
        private lateinit var file: File
        private lateinit var journal: CountingJournal
        private var rig: JournalRealRig? = null
        private var invocation: Long = 0

        @Setup(Level.Trial)
        fun announceHost() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
            directory = Files.createTempDirectory("computenet-journal-checkpoint-").toFile()
        }

        @Setup(Level.Invocation)
        fun openRig() {
            file = File(directory, "invocation-${invocation++}.journal")
            journal = CountingJournal(FileJournal(file))
            rig = JournalRealRig(JournalPlan.coordinate(cardinality, BATCH_SIZE), journal)
                .also {
                    it.applyGraph()
                    it.drive(DRIVE_TIMEOUT_MS)
                }
        }

        @TearDown(Level.Invocation)
        fun closeRig() {
            rig?.close()
            rig = null
            file.delete()
        }

        @TearDown(Level.Trial)
        fun deleteDirectory() {
            directory.deleteRecursively()
        }

        fun checkpoint() {
            checkNotNull(rig).checkpoint(journal)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun realFileJournalPoint(state: FileState, blackhole: Blackhole) {
        blackhole.consume(state.drive())
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun realMemoryJournalPoint(state: MemoryState, blackhole: Blackhole) {
        blackhole.consume(state.drive())
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun realReplay(state: ReplayState, blackhole: Blackhole) {
        blackhole.consume(state.replay())
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun realCheckpoint(state: CheckpointState, blackhole: Blackhole) {
        state.checkpoint()
        blackhole.consume(state.cardinality)
    }

    companion object {
        const val FORKS: Int = 3
        const val WARMUP: Int = 2
        const val MEASUREMENT: Int = 5
        const val DRIVE_TIMEOUT_MS: Long = 600_000L
        const val BATCH_SIZE: Int = 10
    }
}
