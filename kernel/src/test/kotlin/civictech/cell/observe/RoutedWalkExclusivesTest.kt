package civictech.cell.observe

import civictech.cell.ExclusiveEntry
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * computenet-t6b.3.3.4, KRD-24 (BS-40): [walkRouted]/[readRouted] elide every
 * `Owned`/`Leased` element of a real cell as an [ExclusiveEntry], sum the
 * count into the outcome, and never take, release or copy one.
 *
 * `BoundedStateReadTest`'s "an exclusive element is described, never paged,
 * and never consumed" proves this at the host seam, on a single page holding
 * one `Owned`. This test proves the same contract at the routed-walk seam
 * ([StateWalkTest]'s own package), on a real [SetCell] holding both `Owned`
 * and `Leased` elements spread across several pages — so the walk's summed
 * [StateWalkOutcome.exclusivesElided] is what is exercised, not a scripted
 * page's declared count ([StateWalkTest]'s `ScriptedBoundedCell` rig).
 *
 * Scaffolding follows `StateWalkTest`/`RoutedReadTest` in this package: a
 * [LocationRegistry], a [SimulationController], a [ManagedHost] over its
 * scheduler, and `controller.runToIdle()` as the only clock.
 */
class RoutedWalkExclusivesTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private val plainElements = (0 until PLAIN_COUNT).map { "plain-%02d".format(it) }

    private val releaseCount = AtomicInteger(0)
    private val ownedElements = (0 until OWNED_COUNT).map { Owned("secret-$it") }
    private val leasedElements = (0 until LEASED_COUNT).map {
        Leased("lease-$it") { releaseCount.incrementAndGet() }
    }

    /** A `SetCell<Any>` holding [plainElements] plus [ownedElements] and [leasedElements]. */
    private fun fixture(): SetCell<Any> {
        val cell = SetCell<Any>()
        plainElements.forEach { cell.inlet.call.add(it) }
        ownedElements.forEach { cell.inlet.call.add(it) }
        leasedElements.forEach { cell.inlet.call.add(it) }
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        return cell
    }

    @Test
    fun `a routed walk elides every Owned and Leased element, sums the count, and never touches one`() {
        val cell = fixture()
        val plantedIdentities = (ownedElements.map { System.identityHashCode(it) } +
            leasedElements.map { System.identityHashCode(it) }).toSet()

        // D2 — walked to completion at a page limit far smaller than the state,
        // so elisions are spread across several pages.
        val walk = walkRouted(registry, cell.ref, StateRead(limit = PAGE_LIMIT))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        outcome.isComplete.shouldBeTrue()
        outcome.pages shouldBeGreaterThan 1
        outcome.exclusivesElided shouldBe EXCLUSIVE_COUNT

        val exclusiveEntries = outcome.entries.filterIsInstance<ExclusiveEntry>()
        exclusiveEntries.size shouldBe EXCLUSIVE_COUNT
        exclusiveEntries.forEach { entry ->
            entry.key.shouldBeNull() // the element IS the exclusive value, in a set
            entry.disposition shouldBe ExclusiveEntry.Disposition.HELD
        }
        exclusiveEntries.count { it.typeName == Owned::class.java.name } shouldBe OWNED_COUNT
        exclusiveEntries.count { it.typeName == Leased::class.java.name } shouldBe LEASED_COUNT
        exclusiveEntries.map { it.identity }.toSet() shouldBe plantedIdentities

        // never paged: no live Owned/Leased reference, and no copy of a wrapped
        // exclusive payload, appears anywhere in the walk's union
        outcome.entries.none { it is Owned<*> || it is Leased<*> }.shouldBeTrue()

        val plainEntries = outcome.entries.filterIsInstance<SetCell.SetStateEntry<*>>()
        plainEntries.map { it.element } shouldContainExactlyInAnyOrder plainElements
        outcome.entries.size shouldBe PLAIN_COUNT + EXCLUSIVE_COUNT

        for (i in 0 until OWNED_COUNT) {
            outcome.entries.none { it == "secret-$i" }.shouldBeTrue()
        }
        for (i in 0 until LEASED_COUNT) {
            outcome.entries.none { it == "lease-$i" }.shouldBeTrue()
        }

        // D3 — never borrowed, taken, released, or reflected into: a `take()`
        // after the walk still succeeds, which it could not have done had the
        // read consumed the payload (Ownership.kt's `take` throws on a second
        // call via `check(!consumed)`, it does not return null).
        ownedElements.forEachIndexed { i, owned -> owned.take() shouldBe "secret-$i" }
        releaseCount.get() shouldBe 0
        host.supervisionAccounting().deadLetters shouldBe 0L
        host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 0L
    }

    // D4 — a single routed page over the whole state agrees with the walk's sum:
    // the walk's total is the seam's count, not a driver artefact.
    @Test
    fun `a single routed page over the whole state answers the same exclusivesElided as the walk's sum`() {
        val cell = fixture()

        val pending = readRouted(registry, cell.ref, StateRead(limit = WHOLE_STATE_LIMIT))
        controller.runToIdle()
        val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        result.shouldBeInstanceOf<StateReadResult.Page>()
        val page = (result as StateReadResult.Page).page
        page.next.shouldBeNull() // one page covers the whole state
        page.exclusivesElided shouldBe EXCLUSIVE_COUNT
        page.entries.filterIsInstance<ExclusiveEntry>().size shouldBe EXCLUSIVE_COUNT
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val PLAIN_COUNT = 30
        const val OWNED_COUNT = 3
        const val LEASED_COUNT = 3
        const val EXCLUSIVE_COUNT = OWNED_COUNT + LEASED_COUNT
        const val PAGE_LIMIT = 5
        const val WHOLE_STATE_LIMIT = 100
    }
}
