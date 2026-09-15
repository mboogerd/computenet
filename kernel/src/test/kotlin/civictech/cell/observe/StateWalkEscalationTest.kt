package civictech.cell.observe

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.MapCell
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.time.Instant
import java.time.InstantSource
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * computenet-t6b.3.4.2: [escalateRouted] — the `since` escalation as a
 * supported routed operation (t6b.3.4-D4/D5; KRD-22, KRD-23, and the
 * escalation half of KRD-29, BS-35).
 *
 * Scaffolding follows [StateWalkTest]: a [LocationRegistry], a
 * [SimulationController], a [ManagedHost] over its scheduler, and
 * `controller.step()`/`runToIdle()` as the only clock. The sibling task
 * (computenet-t6b.3.4.1) owns [StateWalkOutcome.stability]; this file compares
 * [StateWalkOutcome.openingFrontier]/[StateWalkOutcome.closingFrontier]
 * directly rather than reading that field.
 *
 * The order in which the scheduler runs an already-queued page request versus
 * a later-enqueued mutation is UNVERIFIED, so every mid-walk mutation below
 * happens after page 1 of a walk of at least six pages (limit 1 over six
 * elements), and its effect is asserted from [SetCell.membership] before the
 * escalation is read.
 */
class StateWalkEscalationTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private fun setCellOf(vararg elements: String): SetCell<String> {
        val cell = SetCell<String>()
        host.managementInlet.call.spawn(cell)
        controller.runToIdle() // spawn leaves nothing queued; drained so step counts below are the walk's
        elements.forEach { cell.inlet.call.add(it) }
        controller.runToIdle()
        return cell
    }

    private fun mapCell(): MapCell<String, String> {
        val cell = MapCell<String, String>()
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        return cell
    }

    @Suppress("UNCHECKED_CAST")
    private fun entriesOf(outcome: StateWalkOutcome): List<SetCell.SetStateEntry<String>> =
        outcome.entries.map { it as SetCell.SetStateEntry<String> }

    /**
     * Per-element union of add/del tag sets over every [SetCell.SetStateEntry]
     * naming that element, across both the base and the delta walk — the
     * caller-side fold [civictech.cell.BoundedRead]'s KDoc describes.
     * Deliberately test-local (t6b.3.4-D4's KDoc: "the fold helper lives in the
     * TEST only").
     */
    private fun fold(entries: List<SetCell.SetStateEntry<String>>): Set<String> {
        val adds = HashMap<String, MutableSet<Timestamp>>()
        val dels = HashMap<String, MutableSet<Timestamp>>()
        for (entry in entries) {
            adds.getOrPut(entry.element) { mutableSetOf() } += entry.addTags
            dels.getOrPut(entry.element) { mutableSetOf() } += entry.delTags
        }
        return adds.keys.filterTo(mutableSetOf()) { element ->
            val a = adds[element].orEmpty()
            val d = dels[element].orEmpty()
            a.any { it !in d }
        }
    }

    // --------------------------------------------------------------- BS-35 arm 1

    /**
     * KRD-22, the repair. Two elements added after the base walk's page 1
     * lands are never named by the base walk (frozen enumeration order), but
     * the delta names both with add-tags beyond the base's opening frontier —
     * and folding base entries with delta entries recovers exactly the cell's
     * live membership.
     */
    @Test
    fun `the delta repairs elements added after the base walk opened`() {
        val cell = setCellOf("e1", "e2", "e3", "e4", "e5", "e6")

        val base = walkRouted(registry, cell.ref, StateRead(limit = 1))
        controller.step().shouldBeTrue() // page 1 lands ("e1"); page 2 is queued

        cell.inlet.call.add("late1")
        cell.inlet.call.add("late2")
        controller.runToIdle()

        val baseOutcome = base.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        baseOutcome.isComplete.shouldBeTrue()
        cell.membership() shouldBe setOf("e1", "e2", "e3", "e4", "e5", "e6", "late1", "late2")
        baseOutcome.openingFrontier shouldNotBe baseOutcome.closingFrontier
        entriesOf(baseOutcome).map { it.element } shouldContainExactlyInAnyOrder
            listOf("e1", "e2", "e3", "e4", "e5", "e6")

        val delta = escalateRouted(registry, cell.ref, StateRead(limit = 1), baseOutcome)
        controller.runToIdle()
        val deltaOutcome = delta.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        deltaOutcome.isComplete.shouldBeTrue()
        val deltaEntries = entriesOf(deltaOutcome)
        deltaEntries.map { it.element } shouldContainExactlyInAnyOrder listOf("late1", "late2")
        val openingCounter = baseOutcome.openingFrontier!!.perSource.values.firstOrNull() ?: 0L
        deltaEntries.forEach { entry ->
            entry.addTags.shouldNotBeEmpty()
            entry.addTags.forEach { tag -> (tag.counter > openingCounter).shouldBeTrue() }
        }

        val folded = fold(entriesOf(baseOutcome) + deltaEntries)
        folded shouldBe cell.membership()
    }

    // --------------------------------------------------------------- BS-35 arm 2

    /**
     * KRD-23, refusal passthrough for a null-frontier family. The refusal is
     * decided on the caller's thread before any task reaches the cell's host:
     * `outcome.isDone` is already true, `pages == 0`, and no further
     * `controller.step()` finds anything queued for it.
     */
    @Test
    fun `escalation over a null-frontier family is refused SINCE_UNSUPPORTED synchronously`() {
        val cell = mapCell()

        val base = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.runToIdle()
        val baseOutcome = base.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        baseOutcome.isComplete.shouldBeTrue()
        baseOutcome.openingFrontier shouldBe null

        val stepsBefore = controller.runToIdle() // drain to a known-quiescent point

        val delta = escalateRouted(registry, cell.ref, StateRead(limit = 10), baseOutcome)

        delta.outcome.isDone.shouldBeTrue()
        val deltaOutcome = delta.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        deltaOutcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.SINCE_UNSUPPORTED)
        deltaOutcome.isComplete.shouldBeFalse()
        deltaOutcome.pages shouldBe 0
        deltaOutcome.entries.shouldBeEmpty()
        controller.runToIdle() shouldBe 0 // nothing was ever queued for it
        stepsBefore shouldBe 0
    }

    /**
     * KRD-23, second arm: the refusal is `readState`'s own, not a
     * null-frontier shortcut inside [escalateRouted]. A minimal test-local
     * [BoundedStateful] whose pages carry a real, non-null frontier but whose
     * `supportsSince` is the default `false` is refused exactly the same way —
     * proving the check is `supportsSince`, not "was the base frontier null".
     */
    @Test
    fun `escalation over a non-null-frontier family that does not support since is refused the same way`() {
        val cell = FrontierCarryingCell().also { host.managementInlet.call.spawn(it) }
        controller.runToIdle()

        val base = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.runToIdle()
        val baseOutcome = base.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        baseOutcome.isComplete.shouldBeTrue()
        baseOutcome.openingFrontier shouldNotBe null

        val delta = escalateRouted(registry, cell.ref, StateRead(limit = 10), baseOutcome)

        delta.outcome.isDone.shouldBeTrue()
        val deltaOutcome = delta.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        deltaOutcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.SINCE_UNSUPPORTED)
        deltaOutcome.pages shouldBe 0
        controller.runToIdle() shouldBe 0
    }

    // --------------------------------------------------------------- BS-35 arm 3

    /**
     * KRD-29, the inherited limit demonstrated as a limit. A mid-walk local
     * remove mints its own del-dot (moving the closing stamp), but the delta
     * carries only that dot with no add-tags — the naive fold from arm 1
     * still names the removed element present while `membership()` lacks it.
     * Asserted as exactly that inequality; never softened into equality.
     */
    @Test
    fun `the delta inherits the covered-adds limit — the naive fold still names a removed element present`() {
        val cell = setCellOf("a", "b", "c", "d", "e", "f")

        val base = walkRouted(registry, cell.ref, StateRead(limit = 1))
        controller.step().shouldBeTrue() // page 1 lands ("a"); page 2 is queued

        cell.inlet.call.remove("a")
        controller.runToIdle()

        val baseOutcome = base.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        baseOutcome.isComplete.shouldBeTrue()
        cell.membership() shouldBe setOf("b", "c", "d", "e", "f")
        baseOutcome.openingFrontier shouldNotBe baseOutcome.closingFrontier // the del-dot moved the stamp

        val delta = escalateRouted(registry, cell.ref, StateRead(limit = 1), baseOutcome)
        controller.runToIdle()
        val deltaOutcome = delta.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        deltaOutcome.isComplete.shouldBeTrue()

        val deltaEntries = entriesOf(deltaOutcome)
        val aEntry = deltaEntries.single { it.element == "a" }
        aEntry.addTags.shouldBeEmpty()
        aEntry.delTags.size shouldBe 1

        val folded = fold(entriesOf(baseOutcome) + deltaEntries)
        folded shouldNotBe cell.membership() // the documented limit: fold still names "a" present
        folded.contains("a").shouldBeTrue()
        cell.membership().contains("a").shouldBeFalse()
    }

    // --------------------------------------------------------------- precondition arm

    /**
     * The caller-thread precondition (mirroring [walkRouted]'s own `cursor`
     * check): escalating a walk that did not complete is a programming error,
     * not a read outcome. No read is issued — `controller.step()` finds
     * nothing queued.
     */
    @Test
    fun `escalateRouted rejects a non-complete base walk before issuing any read`() {
        val cell = setCellOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        var now = Instant.EPOCH
        val deadline = Instant.EPOCH.plusMillis(5)

        val base = walkRouted(registry, cell.ref, StateRead(limit = 1), deadline, InstantSource { now })
        controller.step().shouldBeTrue() // page 1 lands, deadline not yet passed, page 2 requested

        now = deadline // at the deadline counts as passed, not before it
        controller.step().shouldBeTrue() // page 2 lands, and the boundary check stops the walk

        val baseOutcome = base.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        baseOutcome.termination shouldBe StateWalkOutcome.Termination.DeadlineExceeded
        baseOutcome.isComplete.shouldBeFalse()

        shouldThrow<IllegalArgumentException> {
            escalateRouted(registry, cell.ref, StateRead(limit = 1), baseOutcome)
        }

        controller.runToIdle() shouldBe 0 // no read was ever issued by the rejected call
    }

    @Test
    fun `escalateRouted rejects a request that already carries a cursor`() {
        val cell = setCellOf("a")

        val base = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.runToIdle()
        val baseOutcome = base.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        baseOutcome.isComplete.shouldBeTrue()

        shouldThrow<IllegalArgumentException> {
            escalateRouted(registry, cell.ref, StateRead(cursor = civictech.cell.Cursor("borrowed")), baseOutcome)
        }
    }

    // --------------------------------------------------------------- fixtures

    /**
     * A minimal [BoundedStateful] whose single, complete page carries a real,
     * non-null frontier while `supportsSince` stays the default `false` — the
     * "non-null frontier but unsupported since" arm the parent design allows
     * as a substitute for standing up an operator cell (e.g. `GroupByCell`).
     */
    private class FrontierCarryingCell(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, BoundedStateful {
        override fun readBounded(request: StateRead): StatePage =
            StatePage(entries = emptyList(), next = null, frontier = TagFrontier(mapOf(SOURCE to 1L)))

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val SOURCE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    }
}
