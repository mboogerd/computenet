package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KE2-34]/BS-19: `ManagedHost.readState` beside an `observeAligned` sink that
 * is attached to, and holding a wave on, one of the read's own arms —
 * `[21-PULL-02]`'s wave-neutrality made falsifiable in exactly the situation
 * KE2 composes with the bounded read (KRD/V1C-KERNEL), not merely a bare
 * producer as in [civictech.cell.host.BoundedReadWaveNeutralityTest].
 *
 * The held-wave rig is [AlignedObserveTest]'s G-13 WAIT-shape device: two
 * independent `SetCell`s `a`/`b` fed to one `host.observeAligned { ... }`
 * sink. `b`'s edge is open and an expected sibling for `a`'s wave, but `b`'s
 * root never mints a matching wave, so the sink buffers `a`'s delta
 * (`sink.bufferedWaves == 1`) rather than publishing or dropping it. That held
 * wave is the mid-wave state `readState` must be indifferent to.
 */
class AlignedBesideReadStateTest {

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

    /** Only the element identity, never the tag sets: [SetCell.SetStateEntry.addTags]/`delTags`
     *  carry `SetCell.tagSource`, which is derived from the cell's own (random) [CellRef] — two
     *  independently constructed cells never share it even when fed the identical add sequence,
     *  so raw entry equality would fail for a reason that has nothing to do with [KE2-34]. */
    @Suppress("UNCHECKED_CAST")
    private fun elementsOf(pages: List<StatePage>): List<String> =
        pages.flatMap { it.entries }.filterIsInstance<SetCell.SetStateEntry<*>>()
            .map { it.element as String }

    /** Walk a `readState` cursor to completion, one page per `runToIdle()`, as
     *  [civictech.cell.host.BoundedReadWaveNeutralityTest] drives it — the established idiom for
     *  a bare `readState` future (as opposed to [walkRouted]'s own step-advanced outcome). */
    private fun walk(
        host: ManagedHost,
        controller: SimulationController,
        ref: CellRef,
        limit: Int = 7,
        between: () -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var steps = 0
        do {
            val pending = host.readState(ref, StateRead(cursor = cursor, limit = limit))
            controller.runToIdle()
            steps++
            check(steps <= WALK_GUARD) { "walk did not terminate within $WALK_GUARD pages" }
            val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.shouldBeInstanceOf<StateReadResult.Page>()
            pages += result.page
            cursor = result.page.next
            between()
        } while (cursor != null)
        return pages
    }

    @Test
    fun `a bounded read of a source mid-wave answers between invocations and moves nothing`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call
        val a = SetCell<String>()
        val b = SetCell<String>()

        // pre-existing elements: arm state, installed before the sink ever attaches
        repeat(ELEMENT_COUNT) { a.inlet.call.add("k%04d".format(it)) }
        mgmt.spawn(a)
        mgmt.spawn(b)

        val sink = host.observeAligned {
            set("a", a.ref)
            set("b", b.ref)
        }
        controller.runToIdle() // late-join catch-up (AlignedObserveTest): arm state, no wave admitted
        sink.bufferedWaves shouldBe 0

        // one more delivery, held: b's edge is open and an expected sibling,
        // but b's root never mints a matching wave (G-13 WAIT shape)
        a.inlet.call.add("held")
        controller.runToIdle()
        sink.bufferedWaves shouldBe 1

        // the observers of the held wave's own producer, snapshotted before the read walk
        val tapFired = countingTap(a)
        val watermark = WatermarkCell().also { it.trackDeliveriesOf(a.outlet) }
        val waveBefore = a.outlet.waveState()
        val linksBefore = a.outlet.linking.links.toSet()
        val rowsBefore = watermark.rows()
        val bufferedBefore = sink.bufferedWaves
        val currentBefore = sink.current()
        val tapBefore = tapFired.get()

        fun assertUnchanged() {
            a.outlet.waveState() shouldBe waveBefore
            a.outlet.linking.links.toSet() shouldBe linksBefore
            watermark.rows() shouldBe rowsBefore
            tapFired.get() shouldBe tapBefore
            sink.bufferedWaves shouldBe bufferedBefore
            sink.current() shouldBe currentBefore
        }

        // checked after EVERY page, not only at the end: a walk is many
        // scheduler tasks and each one must be neutral (RoutedWalkNeutralityTest
        // discipline) — and this is the "answers between invocations" half of
        // [KE2-34]: pages complete across several later `runToIdle()`s while
        // the held wave stays held throughout.
        val pages = walk(host, controller, a.ref, between = ::assertUnchanged)

        pages.size shouldBeGreaterThan 1
        // the held wave's element IS in a's own fold state already — the sink
        // holds the aligned *composite* publication, never the producer's data.
        elementsOf(pages).size shouldBe ELEMENT_COUNT + 1
        assertUnchanged()

        // the contrast (non-vacuity): releasing the held wave, and a further
        // ordinary delivery, both move what the read walk did not.
        sink.inlets.getValue("b").linking.links.single().unlink()
        sink.bufferedWaves shouldBe 0

        a.inlet.call.add("late")
        controller.runToIdle()
        tapFired.get() shouldBeGreaterThan tapBefore
        a.outlet.waveState().highWater shouldBeGreaterThan waveBefore.highWater

        sink.close()
    }

    @Test
    fun `pages are identical with and without an aligned sink attached`() {
        val elements = (0 until ELEMENT_COUNT).map { "k%04d".format(it) } + "held"

        // graph 1: the aligned sink attached, holding a wave (same rig as the first test)
        val controller1 = SimulationController()
        val host1 = ManagedHost(scheduler = controller1.scheduler())
        val a1 = SetCell<String>()
        val b1 = SetCell<String>()
        elements.dropLast(1).forEach { a1.inlet.call.add(it) }
        host1.managementInlet.call.spawn(a1)
        host1.managementInlet.call.spawn(b1)
        val sink = host1.observeAligned {
            set("a", a1.ref)
            set("b", b1.ref)
        }
        controller1.runToIdle()
        a1.inlet.call.add(elements.last())
        controller1.runToIdle()
        sink.bufferedWaves shouldBe 1

        // graph 2: the identical add sequence, no sink at all
        val controller2 = SimulationController()
        val host2 = ManagedHost(scheduler = controller2.scheduler())
        val a2 = SetCell<String>()
        elements.forEach { a2.inlet.call.add(it) }
        host2.managementInlet.call.spawn(a2)
        controller2.runToIdle()

        val pagesWithSink = walk(host1, controller1, a1.ref)
        val pagesWithoutSink = walk(host2, controller2, a2.ref)

        // page *count* and the *entries'* elements are compared, never whole
        // StatePage objects: entries legitimately differ in addTags/delTags
        // (see elementsOf's KDoc) and a page's own cursor is per-walk opaque
        // provenance — neither is part of [KE2-34]'s claim.
        pagesWithSink.size shouldBe pagesWithoutSink.size
        elementsOf(pagesWithSink) shouldBe elementsOf(pagesWithoutSink)

        sink.close()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val ELEMENT_COUNT = 40
        const val WALK_GUARD = 10_000
    }
}
