package civictech.cell.host

import civictech.cell.CurrentContext
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-KERNEL: wave-neutrality of the bounded read, **asserted rather than
 * claimed** — against the full observer list of a wave `(sourceId, counter)`.
 *
 * The design note's headline correction is that a `StateRequest` pull reply is
 * the wrong primitive for an instrument not because it wrecks the wave plane
 * (it does not), but because it is a *message*: it needs topology to be
 * received at all (P6). Both halves are pinned here in one test class, so the
 * KDoc that used to state the wrong mechanism cannot go stale again:
 *
 * - a full `readState` walk over a pull-serving, tap-observed, watermark-tracked
 *   `SetCell` moves **nothing** — not the outlet's wave counter, not a tap, not
 *   a delivered-watermark row, not a dead letter;
 * - the same state fetched via `StateRequest` moves `waveState().highWater` by
 *   **exactly one** and still moves no watermark row, because `FanOutlet.at`
 *   resolves a single target and fires no taps.
 */
class BoundedReadWaveNeutralityTest {

    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler())

    /** A raw tap on the producer's outlet: the thing a broadcast fires and a targeted `at` does not. */
    private fun countingTap(cell: SetCell<String>): AtomicInteger {
        val fired = AtomicInteger()
        cell.outlet.tap(
            Use.fixed(
                Propagate<SetDelta<String>> { fired.incrementAndGet() },
                PortRef.generate(),
            )
        )
        return fired
    }

    @Test
    fun `a full bounded-read walk moves no wave counter, no tap, no watermark row and no dead letter`() {
        val cell = SetCell<String>()
        repeat(40) { cell.inlet.call.add("k$it") }
        host.managementInlet.call.spawn(cell)

        // the three observers of a wave, all attached to this one producer
        val tapFired = countingTap(cell)
        val watermark = WatermarkCell().also { it.trackDeliveriesOf(cell.outlet) }
        val observed = host.observe(cell.ref, View.set<String>())
        controller.runToIdle()

        val waveBefore = cell.outlet.waveState()
        val rowsBefore = watermark.rows()
        val observedBefore = observed.current()
        val deadLettersBefore = host.supervisionAccounting().deadLetters
        val tapBefore = tapFired.get()

        var cursor: Cursor? = null
        var pages = 0
        do {
            val pending = host.readState(cell.ref, StateRead(cursor = cursor, limit = 7))
            controller.runToIdle()
            val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.shouldBeInstanceOf<StateReadResult.Page>()
            cursor = result.page.next
            pages++
            // checked *per page*, not only at the end: a walk is many scheduler
            // tasks, and each one must be neutral
            cell.outlet.waveState() shouldBe waveBefore
            watermark.rows() shouldBe rowsBefore
            tapFired.get() shouldBe tapBefore
        } while (cursor != null)

        pages shouldBeGreaterThan 1
        cell.outlet.waveState() shouldBe waveBefore
        watermark.rows() shouldBe rowsBefore
        observed.current() shouldBe observedBefore
        host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
        // the outlet never answered into the void either — no `at`, no target miss
        cell.outlet.targetMisses shouldBe 0L
    }

    @Test
    fun `the contrast case - the same state via StateRequest moves highWater by exactly one and still moves no watermark row`() {
        val cell = SetCell<String>()
        // isolate the pull path from the co-hosted onLinked push, as StatePullTest does
        cell.outlet.linking.onLinkedListeners.clear()
        repeat(40) { cell.inlet.call.add("k$it") }
        host.managementInlet.call.spawn(cell)

        val watermark = WatermarkCell().also { it.trackDeliveriesOf(cell.outlet) }
        val tapFired = countingTap(cell)
        controller.runToIdle()

        val waveBefore = cell.outlet.waveState()
        val rowsBefore = watermark.rows()

        // a requester must first install topology to receive a reply at all —
        // which is the P6 objection, and the reason an instrument cannot use this
        @Suppress("UNCHECKED_CAST")
        val probe = FanInlet(Propagate::class.java as Class<Propagate<SetDelta<String>>>)
        var replies = 0
        var baselineStamped = false
        probe.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                // a pull reply IS an emission: stamped, baseline-tagged, counted
                baselineStamped = CurrentContext.get()?.baseline != null
                replies++
            }
        })
        @Suppress("UNCHECKED_CAST")
        val link = (cell.outlet.linkTo(probe as LinkFrom<Propagate<SetDelta<String>>>) as LinkResult.Connected).link

        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, null))
        controller.runToIdle()

        replies shouldBe 1
        baselineStamped.shouldBeTrue()
        val waveAfter = cell.outlet.waveState()
        waveAfter.sourceId shouldBe waveBefore.sourceId
        waveAfter.highWater shouldBe waveBefore.highWater + 1
        // ... and still no tap and no delivered-watermark row: `at` resolves one
        // target and never iterates tapOrder
        tapFired.get() shouldBe 0
        watermark.rows() shouldBe rowsBefore
    }

    @Test
    fun `a bounded read is not a back door around a non-serving producer`() {
        // A cell with no pull-serve handler at all still answers a bounded read —
        // the read is not a StateRequest, so it neither needs nor bypasses the
        // PULL_SERVICE handshake. Asserted so the two seams stay distinguishable.
        val cell = SetCell<String>()
        cell.outlet.linking.onLinkedListeners.clear()
        repeat(5) { cell.inlet.call.add("k$it") }
        host.managementInlet.call.spawn(cell)

        val pending = host.readState(cell.ref, StateRead(limit = 100))
        controller.runToIdle()
        val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        result.shouldBeInstanceOf<StateReadResult.Page>()
        (result.page.entries.size == 5).shouldBeTrue()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
