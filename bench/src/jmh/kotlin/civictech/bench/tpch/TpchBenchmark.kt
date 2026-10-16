package civictech.bench.tpch

import civictech.bench.HostFacts
import civictech.bench.micro.ThroughputReport
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

/** REAL-drive TPC-H refresh timing: one RF1 or RF2 batch and its quiescence drain. */
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
open class TpchBenchmark {

    @Param("Q01", "Q03", "Q04", "Q05", "Q06", "Q07", "Q08", "Q09", "Q10", "Q12", "Q14", "Q16", "Q19")
    @JvmField
    var query: String = "Q01"

    @Param("0.001", "0.01")
    @JvmField
    var sf: Double = 0.001

    @Param("volatile", "wal-bound", "batched-8", "batched-ingress-64")
    @JvmField
    var loadMode: String = LoadModes.VOLATILE

    private lateinit var trialDirectory: Path
    private lateinit var session: TpchRefreshSession

    @Setup(Level.Trial)
    fun openTrial() {
        HostFacts.captureCurrent().bannerLines().forEach(::println)
        trialDirectory = Files.createTempDirectory("computenet-tpch-benchmark-")
        val (journal, dispatchBatch) = LoadModes.resolve(loadMode, trialDirectory)
        session = TpchRun.openRefreshSession(
            query = TpchBenchmarkParameters.query(query),
            seed = SEED,
            sf = sf,
            drive = Drive.REAL,
            journal = journal,
            dispatchBatch = dispatchBatch,
        )
    }

    /** Restore the preceding invocation and prepare the next batch outside timing. */
    @Setup(Level.Invocation)
    fun prepareInvocation(params: BenchmarkParams) {
        val method = params.benchmark.substringAfterLast('.')
        session.prepare(
            when (method) {
                "rf1" -> RefreshKind.RF1
                "rf2" -> RefreshKind.RF2
                else -> error("unexpected TPC-H benchmark method '$method'")
            },
        )
    }

    @TearDown(Level.Trial)
    fun closeTrial() {
        if (::session.isInitialized) session.close()
        if (::trialDirectory.isInitialized) deleteTree(trialDirectory)
    }

    /**
     * The Kotlin name carries REAL for the shared report guard; the JVM/JMH name is the
     * requirement's exact `rf1`, kept explicit by [JvmName].
     */
    @Benchmark
    @JvmName("rf1")
    fun realRf1(rows: Rows, blackhole: Blackhole) {
        val refreshed = session.applyPrepared(RefreshKind.RF1)
        rows.record(refreshed)
        blackhole.consume(refreshed)
    }

    /** REAL RF2 is a separate benchmark body; it is never blended with RF1. */
    @Benchmark
    @JvmName("rf2")
    fun realRf2(rows: Rows, blackhole: Blackhole) {
        val refreshed = session.applyPrepared(RefreshKind.RF2)
        rows.record(refreshed)
        blackhole.consume(refreshed)
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    open class Rows {
        @JvmField
        var rowsInserted: Long = 0

        @JvmField
        var rowsDeleted: Long = 0

        @Setup(Level.Trial)
        fun announceHost() {
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Setup(Level.Iteration)
        fun reset() {
            rowsInserted = 0
            rowsDeleted = 0
        }

        internal fun record(rows: RefreshRows) {
            rowsInserted += rows.inserted
            rowsDeleted += rows.deleted
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val SEED: Long = 20261016L
    }
}
