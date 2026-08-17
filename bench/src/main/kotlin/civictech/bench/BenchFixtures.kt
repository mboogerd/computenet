package civictech.bench

/**
 * Shared benchmark fixtures [BEN1-08].
 *
 * Everything in `civictech.bench` lives in `bench/src/main/kotlin` precisely so that
 * BOTH the `jmh` source set (the `@Benchmark` bodies, `civictech.bench.micro`) and the
 * `test` source set (fast unit tests) can consume it. The `jmh` source set is compiled
 * against this module's `main` output, so a fixture referenced from a benchmark is the
 * cheapest possible proof that the main -> jmh wiring is real rather than nominal.
 *
 * Real fixtures — graph builders, deterministic generators, workload shapes — land here
 * in the later BEN1 features. Today the only inhabitant is the sentinel's mixer.
 */
object BenchFixtures {

    /**
     * A deterministic, dependency-free bit mixer (SplitMix64's finalizer).
     *
     * Two properties matter for the discovery sentinel and neither is decorative:
     * the result depends on the argument, so JIT cannot constant-fold the call away
     * and leave an empty benchmark body; and it touches nothing outside this object,
     * so the sentinel measures the harness rather than the runtime under test.
     */
    fun mix(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
