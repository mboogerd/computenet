package civictech.bench.nexmark

import civictech.bench.HostFacts
import civictech.bench.micro.ThroughputReport
import civictech.bench.graphalytics.LoadModes
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.Blackhole
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

/** REAL-drive Nexmark timing: one prepared insert or retraction batch and its drain. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(1)
@Fork(ThroughputReport.FORKS)
@Warmup(
    iterations = ThroughputReport.WARMUP_ITERATIONS,
    time = ThroughputReport.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@Measurement(
    iterations = ThroughputReport.MEASUREMENT_ITERATIONS,
    time = ThroughputReport.ITERATION_SECONDS,
    timeUnit = TimeUnit.SECONDS,
)
@State(Scope.Benchmark)
open class NexmarkBenchmark {

    @Param("Q1", "Q2", "Q3", "Q7", "Q8")
    @JvmField
    var query: String = "Q1"

    @Param("10000", "100000")
    @JvmField
    var scale: String = "10000"

    @Param("volatile", "wal-bound", "batched-8", "batched-ingress-64")
    @JvmField
    var loadMode: String = LoadModes.VOLATILE

    @Param("none", "lateness-5000")
    @JvmField
    var policy: String = "none"

    private lateinit var trialDirectory: Path
    private lateinit var session: NexmarkSession

    @Setup(Level.Trial)
    fun openTrial() {
        HostFacts.captureCurrent().bannerLines().forEach(::println)
        trialDirectory = Files.createTempDirectory("computenet-nexmark-benchmark-")
        val (journal, dispatchBatch) = LoadModes.resolve(loadMode, trialDirectory)
        val resolvedQuery = NexmarkBenchmarkParameters.query(query)
        session = NexmarkRun.openSession(
            query = resolvedQuery,
            seed = SEED,
            scale = NexmarkBenchmarkParameters.scale(scale),
            policy = NexmarkBenchmarkParameters.policyFor(resolvedQuery, policy),
            drive = Drive.REAL,
            journal = journal,
            dispatchBatch = dispatchBatch,
            batchSize = BATCH_SIZE,
        )
    }

    /** Generate/select the next batch outside the timed apply and drain body. */
    @Setup(Level.Invocation)
    fun prepareInvocation(params: BenchmarkParams) {
        session.prepare(
            when (params.benchmark.substringAfterLast('.')) {
                "insert" -> Phase.INSERT
                "retract" -> Phase.RETRACT
                else -> error("unexpected Nexmark benchmark method '${params.benchmark}'")
            },
        )
    }

    @TearDown(Level.Trial)
    fun closeTrial() {
        if (::session.isInitialized) session.close()
        if (::trialDirectory.isInitialized) deleteTree(trialDirectory)
    }

    /** The JVM name is the requirement's exact `insert`; the Kotlin name states REAL. */
    @Benchmark
    @JvmName("insert")
    fun realInsert(rows: Events, blackhole: Blackhole) {
        val applied = session.applyPrepared(Phase.INSERT)
        rows.record(applied)
        blackhole.consume(applied)
    }

    /** Retract one prepared driving-relation batch and drain it to quiescence. */
    @Benchmark
    @JvmName("retract")
    fun realRetract(rows: Events, blackhole: Blackhole) {
        val applied = session.applyPrepared(Phase.RETRACT)
        rows.record(applied)
        blackhole.consume(applied)
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    open class Events {
        @JvmField
        var eventsApplied: Long = 0

        @Setup(Level.Trial)
        fun announceHost() {
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Setup(Level.Iteration)
        fun reset() {
            eventsApplied = 0
        }

        internal fun record(applied: Int) {
            eventsApplied += applied.toLong()
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val BATCH_SIZE: Int = 100
        const val SEED: Long = 20261016L
    }
}
