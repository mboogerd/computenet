package civictech.cell.observe

import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.control.AttentionPolicy
import civictech.cell.data.SetCell
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.PullOnOpen
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * computenet-t6b.3.3.1, BS-10 / [KRD-06]: a full multi-page [walkRouted] over a
 * quiescent, wired [SetCell] perturbs nothing it walks past. The routed-walk
 * analogue of `BoundedReadWaveNeutralityTest`'s single-request neutrality
 * proof — here re-checked after *every* `controller.step()`, because a walk is
 * many scheduler tasks (one per page) and each one must be neutral, not only
 * the walk as a whole.
 *
 * D-BS10-completeness (feature design): the kernel has no public
 * completeness-set accessor (`git grep -iE completeness` over
 * `kernel/src/main` finds none). That clause of [KRD-06] is discharged by
 * entailment from [21-PULL-02]'s own text: a completeness set advances only on
 * a delivery, an absorb-ack, or a later wave — each of which is visible here
 * as an advanced [civictech.cell.port.FanOutlet.waveState] or a moved
 * [WatermarkCell] row, both asserted unchanged below. No kernel accessor is
 * added for this — that is outside this task's claim.
 *
 * "No `PullOnOpen` fired" is made observable, not merely assumed, by linking a
 * `PullOnOpen`-armed [FanInlet] downstream of the cell's outlet before the
 * walk starts: a fired pull reply would move `waveState().highWater` by
 * exactly one (`BoundedReadWaveNeutralityTest`'s contrast case), which the
 * per-step recheck below would catch.
 */
class RoutedWalkNeutralityTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()

    // D1: attention = AttentionPolicy() (all-default parameters) is enough to
    // make attentionOf non-null for a hosted cell — verified against
    // ManagedHost.attentionOf: `attention == null` is the only null-producing
    // gate ahead of the `cells[ref]` lookup, and a host with a policy calls
    // AttentionSupport.of for every cell at spawn, so a spawned cell's band is
    // never null once wired here.
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, attention = AttentionPolicy())

    /** A raw tap on the producer's outlet, copied from `BoundedReadWaveNeutralityTest`. */
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
    fun `a full routed walk moves no wave state, link, watermark row, tap, observed view, attention or accounting — checked after every step`() {
        val cell = SetCell<String>()
        repeat(ELEMENT_COUNT) { cell.inlet.call.add("k$it") }
        host.managementInlet.call.spawn(cell)

        // the three observers of a wave, plus a linked pull-on-open consumer
        // (D1): a fired pull is the one thing that would move highWater
        // without a tap or a watermark row moving too.
        val tapFired = countingTap(cell)
        val watermark = WatermarkCell().also { it.trackDeliveriesOf(cell.outlet) }
        val observed = host.observe(cell.ref, View.set<String>())

        val pullConsumer = FanInlet.create<Propagate<SetDelta<String>>>()
        pullConsumer.install(PullOnOpen())
        pullConsumer.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) = Unit
        })
        @Suppress("UNCHECKED_CAST")
        val linkFrom = pullConsumer as LinkFrom<Propagate<SetDelta<String>>>
        cell.outlet.linkTo(linkFrom).shouldBeInstanceOf<LinkResult.Connected>()

        controller.runToIdle()

        val waveBefore = cell.outlet.waveState()
        val linksBefore = cell.outlet.linking.links.toSet()
        val rowsBefore = watermark.rows()
        val tapBefore = tapFired.get()
        val observedBefore = observed.current()
        val attentionBefore = host.attentionOf(cell.ref)
        attentionBefore.shouldNotBeNull() // D1 hypothesis, settled: non-null once wired
        val accountingBefore = host.supervisionAccounting()
        val targetMissesBefore = cell.outlet.targetMisses

        fun assertUnchanged() {
            cell.outlet.waveState() shouldBe waveBefore
            cell.outlet.linking.links.toSet() shouldBe linksBefore
            watermark.rows() shouldBe rowsBefore
            tapFired.get() shouldBe tapBefore
            observed.current() shouldBe observedBefore
            host.attentionOf(cell.ref) shouldBe attentionBefore
            host.supervisionAccounting() shouldBe accountingBefore
            cell.outlet.targetMisses shouldBe targetMissesBefore
        }

        val walk = walkRouted(registry, cell.ref, StateRead(limit = PAGE_LIMIT))

        var steps = 0
        while (!walk.outcome.isDone) {
            steps++
            check(steps <= STEP_GUARD) { "walk did not complete within $STEP_GUARD scheduler steps" }
            controller.step().shouldBeTrue()
            // re-checked after EVERY scheduler step: one page is one task, and
            // each one must be neutral, not only the completed walk
            assertUnchanged()
        }

        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        outcome.isComplete.shouldBeTrue()
        outcome.pages shouldBeGreaterThan 1
        outcome.entries.size shouldBe ELEMENT_COUNT
        outcome.exclusivesElided shouldBe 0
        assertUnchanged()

        // D4 — the contrast, so the assertion above is not vacuous on a system
        // where nothing ever moves: one ordinary delivery, after the walk,
        // must move both the tap and waveState's highWater.
        cell.inlet.call.add("late")
        controller.runToIdle()

        tapFired.get() shouldBeGreaterThan tapBefore
        cell.outlet.waveState().highWater shouldBeGreaterThan waveBefore.highWater
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val ELEMENT_COUNT = 200
        const val PAGE_LIMIT = 7
        const val STEP_GUARD = 10_000
    }
}
