package civictech.cell.replication

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * computenet-7zssw (MEM1-52): the apply-time fence orders by the PAIR
 * `(epoch, writer)` — the same comparison
 * [civictech.cell.host.InstanceIndex]'s fold already uses for
 * `(epoch, leaderRef.instanceId)` — instead of by the counter alone.
 *
 * These are unit assertions on [Stamped.applyTo] itself, deliberately kept
 * away from any rig: the defect is a property of the fence rule, and the
 * rule has exactly one definition (f7h.3-D2). The end-to-end consequence at
 * the dual-claim rig is a separate question and is recorded on the bead —
 * see `LeaderElectionTest`'s MEM1-52 expected failure.
 */
class StampedFenceTest {

    private class Sink {
        var total = 0L
        var baselines = 0
        var deltas = 0
        fun onBaseline(v: Long) {
            baselines++
            total = v
        }

        fun onDelta(v: Long) {
            deltas++
            total += v
        }
    }

    private fun Stamped<Long>.apply(at: Fence, sink: Sink): Fence? =
        applyTo(at, sink::onBaseline, sink::onDelta)

    // ------------------------------------------------ the MEM1-52 behaviour

    @Test
    fun `at an equal counter a lesser writer's delta is fenced inert`() {
        val sink = Sink()
        val held = Fence(2L, writer = 9L)

        Stamped(2L, 7L, writer = 3L).apply(held, sink) shouldBe null

        sink.deltas shouldBe 0
        sink.baselines shouldBe 0
        sink.total shouldBe 0L
    }

    @Test
    fun `at an equal counter a greater writer's delta is admitted and moves the writer half`() {
        val sink = Sink()

        val next = Stamped(2L, 7L, writer = 9L).apply(Fence(2L, writer = 3L), sink)

        next shouldBe Fence(2L, writer = 9L)
        sink.deltas shouldBe 1
        sink.total shouldBe 7L
    }

    @Test
    fun `admitting a greater writer arms the fence against the superseded writer's next unit`() {
        val sink = Sink()
        var held = Fence(2L, writer = 3L)

        // the superseded leader's own delta, applied while it still believed
        held = Stamped(2L, 7L, writer = 3L).apply(held, sink)!!
        sink.total shouldBe 7L

        // the winner's promotion baseline: same counter, greater writer
        held = Stamped(2L, 0L, baseline = true, writer = 9L).apply(held, sink)!!
        held shouldBe Fence(2L, writer = 9L)
        sink.baselines shouldBe 1
        sink.total shouldBe 0L

        // and now the superseded leader's NEXT unit is inert — the case the
        // counter-only fence admitted
        Stamped(2L, 5L, writer = 3L).apply(held, sink) shouldBe null
        sink.total shouldBe 0L
        sink.deltas shouldBe 1
    }

    @Test
    fun `the same writer ships many units under one epoch - the fence is strict`() {
        val sink = Sink()
        var held = Fence(2L, writer = 9L)
        repeat(3) { held = Stamped(2L, 1L, writer = 9L).apply(held, sink)!! }
        sink.total shouldBe 3L
        held shouldBe Fence(2L, writer = 9L)
    }

    @Test
    fun `a lower counter is fenced regardless of writer`() {
        val sink = Sink()
        Stamped(1L, 7L, writer = 99L).apply(Fence(2L, writer = 3L), sink) shouldBe null
        sink.deltas shouldBe 0
    }

    @Test
    fun `a higher counter is admitted regardless of writer and replaces the writer half`() {
        val sink = Sink()
        Stamped(3L, 7L, writer = 1L).apply(Fence(2L, writer = 99L), sink) shouldBe Fence(3L, writer = 1L)
        sink.total shouldBe 7L
    }

    // ------------------------------------------------ identity-blind fallback

    @Test
    fun `an unidentified unit is never fenced by a tiebreak it cannot participate in`() {
        val sink = Sink()
        Stamped(2L, 7L).apply(Fence(2L, writer = 9L), sink) shouldBe Fence(2L, writer = 9L)
        sink.total shouldBe 7L
    }

    @Test
    fun `a replica with no held writer learns the incoming one`() {
        val sink = Sink()
        Stamped(2L, 7L, writer = 4L).apply(Fence(2L), sink) shouldBe Fence(2L, writer = 4L)
        sink.total shouldBe 7L
    }

    // ------------------------------------------------ the Long overload

    /**
     * The `Long`-typed [applyTo] is the pair rule with an unidentified
     * replica-side writer, so every pre-MEM1-52 call site behaves exactly as
     * it did: counter-only fence, `maxOf` high-water, `null` when fenced.
     */
    @Test
    fun `the Long overload is unchanged in behaviour`() {
        val sink = Sink()
        Stamped(1L, 7L).applyTo(2L, sink::onBaseline, sink::onDelta) shouldBe null
        Stamped(2L, 7L).applyTo(2L, sink::onBaseline, sink::onDelta) shouldBe 2L
        Stamped(3L, 7L).applyTo(2L, sink::onBaseline, sink::onDelta) shouldBe 3L
        // even a writer-carrying unit cannot be fenced by a Long-typed caller,
        // which holds no writer to compare against
        Stamped(2L, 7L, writer = 1L).applyTo(2L, sink::onBaseline, sink::onDelta) shouldBe 2L
        sink.total shouldBe 21L
    }

    @Test
    fun `baseline routing is unaffected by the writer half`() {
        val sink = Sink()
        Stamped(2L, 42L, baseline = true, writer = 5L).apply(Fence(2L, writer = 5L), sink)
        sink.baselines shouldBe 1
        sink.deltas shouldBe 0
        sink.total shouldBe 42L
    }
}
