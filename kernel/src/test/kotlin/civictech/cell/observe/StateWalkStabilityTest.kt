package civictech.cell.observe

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.ListCell
import civictech.cell.data.MapCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetCell.SetStateEntry
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.observe.StateWalkOutcome.Stability
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.time.Instant
import java.time.InstantSource
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * computenet-t6b.3.4.1: [StateWalkOutcome.stability] — the [21-PULL-03] verdict
 * computed once from a completed walk's two endpoint frontier stamps. One test
 * per example of the parent feature (BS-30..BS-34) plus the KRD-14 corollary.
 *
 * Scaffolding follows [StateWalkTest]: a [LocationRegistry], a
 * [SimulationController], a [ManagedHost] over its scheduler, and
 * `controller.step()`/`runToIdle()` as the only clock.
 *
 * **Mid-walk mutation and interleaving.** The order in which the deterministic
 * scheduler runs an already-queued page request against a later-enqueued
 * mutation is not something these tests rely on. Every mid-walk example mutates
 * after page 1 of a walk that has at least four pages, then asserts the
 * mutation's effect from the cell itself (`membership()`) before reading the
 * verdict — so whichever of page 2 and the mutation runs first, the mutation
 * lands strictly between the opening and the closing page.
 *
 * **Union versus state.** Exactly one test here compares a walk's union with the
 * cell's membership — BS-30, under its quiescence premise ([24-BOUND-02]).
 * [Stability.QualifiedSnapshot] is necessary, not sufficient, and BS-33 is the
 * fixture showing it: no other test may assert union-equals-membership (KRD-29).
 */
class StateWalkStabilityTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private fun <C : Cell> spawn(cell: C): C {
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        return cell
    }

    private fun setCell(n: Int): SetCell<String> {
        val cell = SetCell<String>()
        repeat(n) { cell.inlet.call.add("k${"%02d".format(it)}") }
        return spawn(cell)
    }

    /** A remote delta merged the way the wire does — through `deltaInlet`. */
    private fun deliver(cell: SetCell<String>, delta: SetDelta<String>) {
        cell.deltaInlet.call.propagate(delta)
        controller.runToIdle()
    }

    private fun completed(walk: StateWalk): StateWalkOutcome {
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        outcome.isComplete.shouldBeTrue()
        return outcome
    }

    @Suppress("UNCHECKED_CAST")
    private fun setEntries(outcome: StateWalkOutcome): List<SetStateEntry<String>> =
        outcome.entries.map { it as SetStateEntry<String> }

    // --------------------------------------------------------------- BS-30

    /**
     * BS-30 / KRD-16. A quiescent walk over a tag-carrying family, at limits
     * from one entry per page to one page for the whole cell: the stamps are
     * equal and the verdict is [Stability.QualifiedSnapshot] carrying that
     * frontier — never an unqualified arm, because there is none.
     *
     * The union-equals-membership assertion is the **only** one in this file,
     * and it is licensed by the quiescence premise alone: nothing mutates the
     * cell while it is walked ([24-BOUND-02]). It is not a consequence of the
     * verdict — BS-33 is a walk with the same verdict whose union is wrong.
     */
    @Test
    fun `BS-30 a quiescent walk over a tag-carrying family completes as a qualified snapshot at its frontier`() {
        val cell = setCell(30)

        for (limit in listOf(1, 7, 10, 30, 200)) {
            val outcome = completed(walkRouted(registry, cell.ref, StateRead(limit = limit)))

            val opening = outcome.openingFrontier.shouldNotBeNull()
            outcome.closingFrontier shouldBe opening
            outcome.stability shouldBe Stability.QualifiedSnapshot(opening)

            // quiescence premise only — see this test's KDoc
            setEntries(outcome).filter { it.present }.map { it.element }.toSet() shouldBe cell.membership()
        }
    }

    // --------------------------------------------------------------- BS-31

    /**
     * BS-31 / KRD-17. An add lands mid-walk, after the walk has frozen its
     * enumeration order: the local add mints a tag, the closing stamp moves, and
     * the verdict is [Stability.Smeared] carrying both stamps — never a
     * snapshot. The smear itself is stated rather than asserted away: the cell
     * now holds "late", and the walk's union does not, because `SetCell` froze
     * its order at open. What the walk does promise still holds — no element
     * twice.
     */
    @Test
    fun `BS-31 an add landing mid-walk yields Smeared carrying both stamps, never a snapshot`() {
        val cell = setCell(6)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 1))
        controller.step().shouldBeTrue() // page 1 lands
        cell.inlet.call.add("late")
        val outcome = completed(walk)

        cell.membership() shouldContain "late" // the mutation really landed
        val opening = outcome.openingFrontier.shouldNotBeNull()
        val closing = outcome.closingFrontier.shouldNotBeNull()
        opening shouldNotBe closing
        outcome.stability shouldBe Stability.Smeared(opening, closing)

        val elements = setEntries(outcome).map { it.element }
        elements.toSet().size shouldBe elements.size
        elements shouldNotContain "late" // the smear, stated
    }

    // --------------------------------------------------------------- BS-32

    /**
     * BS-32 / KRD-18. Families whose pages carry no frontier have nothing to
     * compare: the walk is complete and its verdict is
     * [Stability.Undeterminable] — explicitly not a snapshot and not smeared.
     */
    @Test
    fun `BS-32 a MapCell walked to completion carries no frontier and is Undeterminable`() {
        val cell = MapCell<String, String>()
        repeat(12) { cell.inlet.call.put("k${"%02d".format(it)}", "v$it") }
        spawn(cell)

        val outcome = completed(walkRouted(registry, cell.ref, StateRead(limit = 5)))

        outcome.pages shouldBe 3
        outcome.openingFrontier.shouldBeNull()
        outcome.stability shouldBe Stability.Undeterminable
    }

    /**
     * BS-32 / KRD-18, the positional-cursor family: its pages carry
     * [ReadCaveat.POSITIONAL_CURSOR], and that caveat does not turn
     * [Stability.Undeterminable] into anything else (KRD-20's endpoint-only
     * rule, from the other side).
     */
    @Test
    fun `BS-32 a ListCell walked to completion is Undeterminable whatever its positional caveat`() {
        val cell = ListCell<String>()
        repeat(12) { cell.inlet.call.add("e${"%02d".format(it)}") }
        spawn(cell)

        val outcome = completed(walkRouted(registry, cell.ref, StateRead(limit = 5)))

        outcome.openingFrontier.shouldBeNull()
        outcome.caveats shouldContain ReadCaveat.POSITIONAL_CURSOR
        outcome.stability shouldBe Stability.Undeterminable
    }

    // --------------------------------------------------------------- BS-33

    /**
     * BS-33 / KRD-19 + KRD-29 — **the limit [Stability.QualifiedSnapshot]'s
     * qualification exists for.** `BoundedStateReadTest`'s ARM 2 fixture driven
     * through the routed walk: remote source `o` has delivered adds up to dot 22,
     * so this replica's per-source max for `o` is 22. After page 1 has returned
     * "a", the reordered del of "a" — covering (o,9), with its own dot (o,12) —
     * arrives. It removes "a" and moves no maximum.
     *
     * So both stamps are equal, the verdict is `QualifiedSnapshot`, and the
     * union names "a" present while the cell no longer holds it. This is
     * demonstrated as the limit, not softened: nothing in this file claims this
     * walk's union equals membership.
     */
    @Test
    fun `BS-33 a reordered remote del changes membership mid-walk without moving a stamp - QualifiedSnapshot is only qualified`() {
        val cell = spawn(SetCell<String>())
        val o = UUID.randomUUID()
        deliver(cell, SetDelta(adds = mapOf("a" to setOf(Timestamp(o, 9)))))
        deliver(cell, SetDelta(adds = mapOf("w" to setOf(Timestamp(o, 20)))))
        deliver(cell, SetDelta(adds = mapOf("x" to setOf(Timestamp(o, 21)))))
        deliver(cell, SetDelta(adds = mapOf("y" to setOf(Timestamp(o, 22)))))

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 1))
        controller.step().shouldBeTrue() // page 1 lands, carrying "a"
        deliver(cell, SetDelta(dels = mapOf("a" to setOf(Timestamp(o, 9), Timestamp(o, 12)))))
        val outcome = completed(walk)

        cell.membership() shouldNotContain "a" // the del really landed
        outcome.pages shouldBe 4
        outcome.openingFrontier shouldBe TagFrontier(mapOf(o to 22L))
        outcome.closingFrontier shouldBe outcome.openingFrontier
        setEntries(outcome).single { it.element == "a" }.present.shouldBeTrue()
        outcome.stability.shouldBeInstanceOf<Stability.QualifiedSnapshot>()
    }

    // --------------------------------------------------------------- BS-34

    /**
     * BS-34 / KRD-20. Thirty elements at limit 10 is three pages, and the middle
     * page carries [ReadCaveat.STALE_FRONTIER] (`SetCell.readBounded` stamps
     * only the walk's two ends exactly). The caveat is carried in the outcome;
     * the verdict, read from the endpoints only, is not downgraded.
     */
    @Test
    fun `BS-34 a stale intermediate frontier is carried but does not downgrade QualifiedSnapshot`() {
        val cell = setCell(30)

        val outcome = completed(walkRouted(registry, cell.ref, StateRead(limit = 10)))

        outcome.pages shouldBe 3
        outcome.caveats shouldContain ReadCaveat.STALE_FRONTIER
        outcome.stability.shouldBeInstanceOf<Stability.QualifiedSnapshot>()
    }

    /**
     * BS-34 / KRD-20, endpoints only: a middle page whose frontier differs from
     * both ends (and is declared stale) changes nothing — the verdict is the
     * comparison of the first and last stamps, `QualifiedSnapshot(F)`.
     */
    @Test
    fun `BS-34 a differing stale middle frontier is ignored - the verdict reads the endpoint stamps only`() {
        val f = TagFrontier(mapOf(SOURCE to 5L))
        val g = TagFrontier(mapOf(SOURCE to 2L))
        val cell = spawn(
            ScriptedCell(
                listOf(
                    StatePage(entries = listOf("a"), next = Cursor("c1"), frontier = f),
                    StatePage(
                        entries = listOf("b"),
                        next = Cursor("c2"),
                        frontier = g,
                        caveats = setOf(ReadCaveat.STALE_FRONTIER),
                    ),
                    StatePage(entries = listOf("c"), next = null, frontier = f),
                ),
            ),
        )

        val outcome = completed(walkRouted(registry, cell.ref, StateRead(limit = 1)))

        outcome.caveats shouldContain ReadCaveat.STALE_FRONTIER
        outcome.stability shouldBe Stability.QualifiedSnapshot(f)
    }

    // ------------------------------------------------- KRD-14 corollary

    /**
     * KRD-14 corollary. A walk that ran out of time has a non-null opening
     * stamp, but no whole walk for a verdict to describe: `stability` is null.
     */
    @Test
    fun `a deadline-exceeded walk carries no verdict even with an opening frontier`() {
        val cell = setCell(30)
        var now = Instant.EPOCH
        val deadline = Instant.EPOCH.plusMillis(5)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 5), deadline, InstantSource { now })
        controller.step().shouldBeTrue() // page 1 lands, page 2 requested
        now = deadline
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.DeadlineExceeded
        outcome.openingFrontier.shouldNotBeNull()
        outcome.stability.shouldBeNull()
    }

    /** KRD-14 corollary, refused arm: the ref leaves mid-walk, and the refused walk carries no verdict. */
    @Test
    fun `a refused walk carries no verdict even with an opening frontier`() {
        val cell = setCell(30)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 5))
        controller.step().shouldBeTrue() // page 1 lands, page 2 requested
        registry.hold(cell.ref)
        registry.unpublish(cell.ref)
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.MIGRATING)
        outcome.openingFrontier.shouldNotBeNull()
        outcome.stability.shouldBeNull()
    }

    // --------------------------------------------------------------- fixtures

    /**
     * Replays a scripted page sequence. A minimal copy of `StateWalkTest`'s
     * file-private `ScriptedBoundedCell`, duplicated rather than shared so this
     * task does not edit that file.
     */
    private class ScriptedCell(
        private val script: List<StatePage>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, BoundedStateful {
        private var served = 0

        override fun readBounded(request: StateRead): StatePage = script[served++]

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val SOURCE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b4")
    }
}
