package civictech.bench.macro

import civictech.bench.HostFacts
import civictech.bench.macro.rebaseline.ReBaselineSourceCell
import civictech.bench.macro.rebaseline.ReBaselineSourceOps
import civictech.bench.macro.rebaseline.ReBaselineTapCell
import civictech.cell.CellRef
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import civictech.testkit.awaitDrained
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
import java.util.UUID
import java.util.concurrent.TimeUnit

private interface ReBaselineBenchmarkSourceProxy {
    val inlet: Use<ReBaselineSourceOps>
}

/**
 * REAL-drive wall time of one supervision-RESTART re-baseline at one (cardinality,
 * fan-out) coordinate ([BEN2-24], BS-12, j27rc-D16).
 *
 * One single-shot sample starts with the poison dispatch and ends at the scheduler's
 * drain fence. It therefore includes the poison dead letter, the RESTART branch and its
 * checkpoint round-trip deserialization of [RigState.cardinality] elements, re-baseline
 * emission, [RigState.fanOut] local kernel folds, and the drain fence. Rig construction,
 * initial emission, and verification of the pre-loaded taps happen in invocation setup
 * and are outside the timer.
 *
 * REAL is intentionally measured only at one host: the mesh harness is SIM-only and
 * `SweepRunner` refuses REAL at `hostCount != 1` ([BEN2-13]). Dispersion is JMH's 99.9%
 * error over three forks times five measured single shots ([BEN2-24]). Forks use the JVM
 * default heap; an OOM kills that fork, leaving an absent CSV row that the render step
 * reports with the run log as its evidence rather than inventing an `Unschedulable`
 * outcome (the latter is a SIM-only cost-model classification).
 *
 * Heap (computenet-p2h90): the taps are built with `mirrorMembership = false`. Each
 * [ReBaselineTapCell] otherwise keeps a SECOND cardinality-sized element-to-tag map beside
 * the kernel `UnionSetCell`'s own; at (1e5, 256) that mirror was about half of a ~13.4 GB
 * live set and, with the default 12 GiB cap, OOM'd every fork. The mirror is touched only by
 * deltas the union emits, which an idempotent re-baseline does not, so dropping it does not
 * change the timed work. The rig checks seeding with `netElements()` instead. Rows measured
 * before this change (computenet-j27rc.5) used the mirror; their timed region is unaffected.
 */
@Fork(ReBaselineFanOutBenchmark.FORKS)
@Warmup(iterations = ReBaselineFanOutBenchmark.WARMUP)
@Measurement(iterations = ReBaselineFanOutBenchmark.MEASUREMENT)
open class ReBaselineFanOutBenchmark {

    /** A fresh, fully seeded single-host graph for each timed invocation. */
    @State(Scope.Thread)
    open class RigState {
        @Param("1000", "10000", "100000")
        @JvmField
        var cardinality: Long = 1_000

        @Param("1", "4", "16", "64", "256")
        @JvmField
        var fanOut: Int = 1

        private var scheduler: VirtualThreadScheduler? = null
        private lateinit var sourceOps: ReBaselineSourceOps
        private var taps: List<ReBaselineTapCell> = emptyList()

        @Setup(Level.Trial)
        fun announceHost() {
            println()
            HostFacts.captureCurrent().bannerLines().forEach(::println)
        }

        @Setup(Level.Invocation)
        fun openRig() {
            val openedScheduler = VirtualThreadScheduler("bench-rebaseline-$fanOut")
            scheduler = openedScheduler
            try {
                val host = ManagedHost(scheduler = openedScheduler)
                val source = ReBaselineSourceCell(
                    ref = CellRef(UUID.randomUUID()),
                    preload = cardinality,
                )
                val openedTaps = List(fanOut) { ReBaselineTapCell(mirrorMembership = false) }

                host.managementInlet.call.spawn(source)
                openedTaps.forEach { host.managementInlet.call.spawn(it) }
                openedTaps.forEach { tap ->
                    val result = host.managementInlet.call.connect(
                        source.ref,
                        "outlet",
                        tap.ref,
                        "inlet",
                        LinkOptions.DEFAULT,
                    )
                    check(result is LinkResult.Connected) {
                        "re-baseline source -> ${tap.ref} link was $result"
                    }
                }
                host.managementInlet.call.supervise(source.ref, SupervisionPolicy.RESTART)
                sourceOps = (
                    HostedCellProxy.create(
                        source.ref,
                        host,
                        ReBaselineBenchmarkSourceProxy::class.java,
                    ) as ReBaselineBenchmarkSourceProxy
                ).inlet.call
                taps = openedTaps
                sourceOps.emitAll()
                openedScheduler.awaitDrained("re-baseline rig seed", DRAIN_TIMEOUT_MS)
                taps.forEachIndexed { index, tap ->
                    check(tap.netElements() == cardinality) {
                        "re-baseline tap $index seeded ${tap.netElements()} of " +
                            "$cardinality elements"
                    }
                }
            } catch (failure: Throwable) {
                openedScheduler.shutdown()
                scheduler = null
                throw failure
            }
        }

        @TearDown(Level.Invocation)
        fun closeRig() {
            scheduler?.shutdown()
            scheduler = null
            taps = emptyList()
        }

        /** Dispatches the poison, waits for the complete re-baseline, and proves every tap saw it. */
        fun restartAndQuiesce(): Int {
            sourceOps.poison()
            scheduler!!.awaitDrained("re-baseline restart", DRAIN_TIMEOUT_MS)
            val notices = taps.count { it.noticeDeliveries.size == 1 }
            check(notices == fanOut) { "re-baseline reached $notices of $fanOut taps" }
            return notices
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun realRestart(state: RigState, blackhole: Blackhole) {
        blackhole.consume(state.restartAndQuiesce())
    }

    companion object {
        const val FORKS: Int = 3
        const val WARMUP: Int = 2
        const val MEASUREMENT: Int = 5
        const val DRAIN_TIMEOUT_MS: Long = 600_000
    }
}
