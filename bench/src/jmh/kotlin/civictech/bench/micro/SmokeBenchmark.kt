package civictech.bench.micro

import civictech.bench.BenchFixtures
import civictech.bench.HostFacts
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * The permanent discovery sentinel [BEN1-06], [BEN1-07].
 *
 * This is not a measurement and is not meant to become one. It exists to make the
 * Kotlin -> JMH pipeline observable: if the plugin ever stops discovering `@Benchmark`
 * methods in Kotlin sources — the classic silent failure, a green build that generated
 * zero benchmarks — this class is what stops appearing in the generated
 * `META-INF/BenchmarkList`, and `:bench:verifyBenchmarkDiscovery` turns that into a
 * build failure instead of a successful-looking no-op.
 *
 * KEEP IT. The zero-benchmark guard makes an empty `jmh` source set a build failure, so
 * deleting the sentinel without leaving another `@Benchmark` behind breaks `:bench:build`
 * by design. It also has to stay `open` and public: JMH's generator emits `_jmhType`
 * subclasses of the benchmark class, which a Kotlin `class` (final by default) forbids.
 *
 * It consumes [BenchFixtures] from `bench/src/main/kotlin` on purpose — that reference is
 * the compile-time proof of the main -> jmh source-set wiring [BEN1-08].
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class SmokeBenchmark {

    private var state: Long = 1L

    /**
     * States the host this fork is measuring on, into the run's own log
     * (`computenet-b7k4`).
     *
     * Same hook, same reason and same leading-newline caveat as
     * [OperatorThroughputBenchmark.GraphState.announceHost] — see that method for the full
     * account. What is specific to the sentinel is WHY it needs one at all, since it is
     * explicitly not a measurement:
     *
     * `NOISE_FLOOR`'s KDoc says the constant is kept as "a sanity bound on the harness
     * itself — the quantity `SmokeBenchmark.baseline` would be re-measured against to
     * detect drift in the discovery sentinel". The regression-tracking series
     * (`civictech.bench.series`, `doc/bench/regression-series.md`) is where that
     * re-measurement is meant to happen, and `SeriesIngest` refuses any run whose log
     * carries no host banner — because the host is part of the fingerprint deciding which
     * past runs a fresh one may be compared against, not decoration. Without this hook
     * the one benchmark the drift check exists for is the one benchmark that cannot enter
     * the series.
     *
     * This does not make the sentinel a measurement. Its job is still to be discovered;
     * printing three lines once per fork costs nothing measurable and is outside the
     * timed method entirely.
     */
    @Setup(Level.Trial)
    fun announceHost() {
        // Load-bearing leading newline: JMH relays fork stdout onto its own unterminated
        // progress line, so without it the first fact lands mid-line.
        println()
        HostFacts.captureCurrent().bannerLines().forEach(::println)
    }

    @Benchmark
    fun baseline(blackhole: Blackhole) {
        state = BenchFixtures.mix(state)
        blackhole.consume(state)
    }
}
