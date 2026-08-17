package civictech.bench.micro

import civictech.bench.BenchFixtures
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * The permanent discovery sentinel [BEN1-08].
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
 * the compile-time proof of the main -> jmh source-set wiring.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class SmokeBenchmark {

    private var state: Long = 1L

    @Benchmark
    fun baseline(blackhole: Blackhole) {
        state = BenchFixtures.mix(state)
        blackhole.consume(state)
    }
}
