package civictech.bench.micro

import civictech.bench.Drive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bound on `OperatorThroughputBenchmark`'s per-invocation live state, pinned
 * (computenet-y7hc, `[BEN1-28]`).
 *
 * ## What is being pinned, and why it is pinned here
 *
 * `bench/build.gradle.kts` compiles this source set against `main`'s output and **not**
 * against `src/jmh`, so no assertion here can see the `@Benchmark` class. That is why
 * [InvocationCycle] exists in `main` at all: the benchmark keeps the JMH wiring, the
 * invariant lives where a test can reach it, and the benchmark's `@Setup` and body are
 * one-line delegations to the two halves asserted below.
 *
 * The invariant: **every timed invocation starts from the same live state.** Under
 * [Direction.RETRACT] that always held. Under [Direction.INSERT] it did not — the setup
 * only generated a batch, so the operators' live membership grew by a batch per
 * invocation for a whole iteration and the timed body of invocation N ran against N-1
 * batches of accumulated state.
 *
 * ## Untagged on purpose
 *
 * Same reasoning as `GraphsTest`: `@Tag("bench")` is excluded from the default test task,
 * so tagging these would remove them from every required check while leaving them looking
 * green. They execute no benchmark and run under SIM on order-32 batches.
 *
 * ## What these tests deliberately do NOT claim
 *
 * That `[BEN1-28]`'s INSERT/RETRACT dispersion asymmetry is resolved. This is a
 * structural claim — the state the timed body runs against is bounded — checked without
 * measuring anything. The empirical claim needs a re-measured sweep on a quiesced host
 * (computenet-x9e.14) and no result here may be read as settling it.
 */
class BoundedInvocationStateTest {

    private val seed = 20260818L
    private val batchSize = 32
    private val invocations = 6

    /** Set-shaped, single-source subjects with an exact membership oracle; SIM, so fast. */
    private val subjects = listOf(Subject.TAGGED_SET, Subject.FILTER, Subject.UNION, Subject.COUNT)

    @Test
    fun `every timed INSERT invocation starts and ends at the same live state`() {
        subjects.forEach { subject ->
            Graphs.build(subject, Drive.SIM).use { graph ->
                val cycle = InvocationCycle(graph, DeltaStream(seed), Direction.INSERT, batchSize)

                val beforeBody = ArrayList<Int>(invocations)
                val afterBody = ArrayList<Int>(invocations)
                val expectedAfterBody = ArrayList<Int>(invocations)

                repeat(invocations) {
                    cycle.prepare()
                    // Elements differ per invocation — a stream never repeats one — so the
                    // oracle is evaluated against the batch this invocation actually applies
                    // rather than against a constant.
                    expectedAfterBody += subject.referenceLive(cycle.pending.elements)
                    beforeBody += graph.live
                    cycle.applyPending()
                    afterBody += graph.live
                }

                // The bound itself: every timed body starts from the empty baseline, and
                // ends holding exactly ONE batch's membership — never N batches'.
                assertEquals(
                    List(invocations) { 0 },
                    beforeBody,
                    "$subject: live state at the start of each timed INSERT invocation",
                )
                assertEquals(
                    expectedAfterBody,
                    afterBody,
                    "$subject: live state at the end of each timed INSERT invocation",
                )
            }
        }
    }

    @Test
    fun `every timed RETRACT invocation starts and ends at the same live state`() {
        subjects.forEach { subject ->
            Graphs.build(subject, Drive.SIM).use { graph ->
                val cycle = InvocationCycle(graph, DeltaStream(seed), Direction.RETRACT, batchSize)

                val beforeBody = ArrayList<Int>(invocations)
                val afterBody = ArrayList<Int>(invocations)
                val expectedBeforeBody = ArrayList<Int>(invocations)

                repeat(invocations) {
                    cycle.prepare()
                    // pending is the retraction; the covering insert it undoes carries the
                    // same elements, so the oracle applies to either.
                    expectedBeforeBody += subject.referenceLive(cycle.pending.elements)
                    beforeBody += graph.live
                    cycle.applyPending()
                    afterBody += graph.live
                }

                assertEquals(
                    expectedBeforeBody,
                    beforeBody,
                    "$subject: live state at the start of each timed RETRACT invocation",
                )
                assertEquals(
                    List(invocations) { 0 },
                    afterBody,
                    "$subject: live state at the end of each timed RETRACT invocation",
                )
            }
        }
    }

    @Test
    fun `the state-restoring work sits in the untimed half, not the timed one`() {
        // A bound obtained by moving the compensating retract INTO the measured body would
        // satisfy the two tests above and destroy the measurement. So: the arrivals the
        // timed half produces must be the same on every invocation — in particular the same
        // as on invocation 1, whose setup does no compensating work because there is nothing
        // yet to compensate for — while the setup's own arrivals go from zero to non-zero at
        // invocation 2, which is where the compensation appears.
        Graphs.build(Subject.UNION, Drive.SIM).use { graph ->
            val cycle = InvocationCycle(graph, DeltaStream(seed), Direction.INSERT, batchSize)

            val setupArrivals = ArrayList<Long>(invocations)
            val bodyArrivals = ArrayList<Long>(invocations)

            repeat(invocations) {
                val atStart = graph.arrivals
                cycle.prepare()
                val afterSetup = graph.arrivals
                cycle.applyPending()
                setupArrivals += afterSetup - atStart
                bodyArrivals += graph.arrivals - afterSetup
            }

            assertEquals(
                List(invocations) { bodyArrivals.first() },
                bodyArrivals,
                "the timed half's work must not vary across invocations",
            )
            assertEquals(0L, setupArrivals.first(), "invocation 1 has nothing to compensate for")
            assertTrue(
                setupArrivals.drop(1).all { it > 0L },
                "invocations 2..N must do their compensating retract in the untimed half: $setupArrivals",
            )
        }
    }

    @Test
    fun `the open-loop shape these tests replace does grow, so the bound above is not vacuous`() {
        // The control. Without compensation — the pre-computenet-y7hc INSERT shape, which is
        // just "generate a batch and apply it" — live membership grows by a batch per
        // invocation. If this ever stopped growing, the assertions above would be pinning a
        // property the graph has for some unrelated reason, and would pass against a
        // reintroduced regression.
        Graphs.build(Subject.UNION, Drive.SIM).use { graph ->
            val stream = DeltaStream(seed)
            val live = (1..invocations).map {
                graph.applyAndQuiesce(stream.insert(batchSize))
                graph.live
            }
            assertEquals((1..invocations).map { it * batchSize }, live)
        }
    }
}
