package civictech.bench

import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runtime-readable companion to the build-time guard `verifyBenchmarkDiscovery` in
 * bench/build.gradle.kts [BEN1-07] — the BS-2 example's companion-test clause.
 *
 * The guard task fails `:bench:build`/`check` outright when JMH's bytecode
 * generator discovers zero `@Benchmark` methods; this test reads the same
 * generated artifact from a JUnit run, sub-second, so the same failure is visible
 * without invoking the build guard specifically. It is a companion, not a
 * replacement — both exist, both read the same file, and this task changes
 * neither the guard's logic nor its wiring into `check`.
 *
 * ## Why "non-empty", not "exists"
 *
 * The generated `META-INF/BenchmarkList` resource is written by the
 * `jmhRunBytecodeGenerator` task regardless of whether the `jmh` source set has
 * any `@Benchmark` methods in it — measured against this build (see the comment
 * above `verifyBenchmarkDiscovery` in bench/build.gradle.kts) with
 * `bench/src/jmh/kotlin` moved aside: the task still ran and produced the file,
 * just truncated to zero bytes. A test that only checked `file.exists()` would
 * therefore pass in exactly the silent-discovery-failure case it exists to
 * catch. So this asserts the file both exists AND contains at least one
 * non-blank, non-comment record — the same parsing the build guard applies.
 *
 * ## Why this never runs a benchmark
 *
 * This test only reads a text file JMH's generator already wrote during
 * configuration/compilation of the `jmh` source set. It never invokes `:bench:jmh`
 * or `:bench:jmhJar`, and per bench/build.gradle.kts neither of those tasks is
 * reachable from `:bench:build`, `:bench:test`, or `check` — only the generator
 * (`jmhRunBytecodeGenerator`) is wired as an input, via `dependsOn` on the `test`
 * task, so the file exists (even if it were empty) by the time this runs.
 */
class BenchmarkDiscoverySmokeTest {

    @Test
    fun `JMH generator discovered at least one benchmark`() {
        val property = System.getProperty("civictech.bench.jmhBenchmarkList")
            ?: error(
                "System property 'civictech.bench.jmhBenchmarkList' is not set. It must be " +
                    "wired in bench/build.gradle.kts on the :bench `test` task, pointing at " +
                    "the generated META-INF/BenchmarkList resource."
            )
        val benchmarkList = File(property)

        // BenchmarkList is a line-per-benchmark text file; `#` introduces a comment — the
        // same filter bench/build.gradle.kts's verifyBenchmarkDiscovery guard task applies.
        val records = if (benchmarkList.isFile) {
            benchmarkList.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        } else {
            emptyList()
        }

        records.shouldNotBeEmpty()
    }
}
