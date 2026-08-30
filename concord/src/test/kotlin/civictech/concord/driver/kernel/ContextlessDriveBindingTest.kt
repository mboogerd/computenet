package civictech.concord.driver.kernel

import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The kernel binding of `Driver.driveContextless` and `Driver.refusalCount`
 * (`computenet-em9i`), at both ends of the contract
 * `concord/schema/scenario.md` §`drive-contextless` / §`refusal-count` states.
 *
 * The corpus half (`DUR-CONTEXTLESS-01`) exercises the happy path, and it does so
 * through the same tally it asserts — so a binding whose count were structurally
 * always the number the scenario happens to name would pass for the wrong reason
 * with nothing going red. Two things a scenario cannot reach are pinned here:
 *
 * - the **refusal sites**. A scenario naming an unbound target errors rather than
 *   asserting anything, and the spec is explicit that a driver which does not
 *   observe the named cell MUST fail loudly rather than answer `0` — the answer
 *   `exactly: 0` accepts. There are two distinct guards ([KernelDriver] outside
 *   the `dur` profile, [KernelDriverDur] outside its own cells), plus the two
 *   target guards on the verb itself;
 * - the **zero reading is a reading**, not an absence: an observed dur cell that
 *   was never driven answers `0` rather than throwing, so `exactly: 0` means
 *   "observed none" there and only there.
 */
class ContextlessDriveBindingTest {

    private fun s(v: String): Value = Value.StrVal(v)

    /** A journaled source feeding an `Effectful` sink — `DUR-CONTEXTLESS-01`'s graph. */
    private fun durGraph(): KernelDriver = KernelDriver(0L).also { d ->
        d.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "sink", "effect-sink", emptyMap())
        d.connect("source", "sink")
    }

    @Test
    fun `a contextless drive is refused at the Effectful inlet, accounted, and never acted on`() {
        val d = durGraph()
        d.refusalCount("sink") shouldBe 0L

        d.driveContextless("sink", null, "add", s("ghost"))
        d.quiesce(BUDGET)

        // the sink never acted on it...
        d.effectLog("sink").map { it.key } shouldBe emptyList()
        // ...and the refusal was ACCOUNTED, which is the conjunct `[24-DUR-06]`
        // adds over "it did not fire": a silent drop would leave this at 0.
        d.refusalCount("sink") shouldBe 1L
    }

    @Test
    fun `ordinary stamped traffic is admitted and is not counted as a refusal`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.quiesce(BUDGET)

        d.effectLog("sink").map { it.key } shouldBe listOf("k1")
        // The control that makes the count mean something: the happy path does not
        // move it, so a non-zero reading is the refusal and not traffic volume.
        d.refusalCount("sink") shouldBe 0L
    }

    @Test
    fun `refusalCount answers zero for an observed dur cell that refused nothing`() {
        // A reading, not an absence — the one place `exactly: 0` is meaningful.
        durGraph().refusalCount("sink") shouldBe 0L
    }

    @Test
    fun `refusalCount refuses loudly outside the dur profile rather than answering zero`() {
        val d = KernelDriver(0L).also { it.spawn("h1", "a", "set-source", emptyMap()) }
        val e = assertThrows<UnsupportedCatalogBinding> { d.refusalCount("a") }
        e.message!! shouldContain "pass on a cell nothing"
    }

    @Test
    fun `refusalCount refuses loudly for a dur-profile handle that is not an observed cell`() {
        // The SECOND guard: `ctl` is routed to the dur capability (it is a durable
        // handle) but is a journal controller, not a cell whose refusals anything
        // watches. The outer guard passes it through; this one must not answer 0.
        val d = durGraph().also { it.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap()) }
        val e = assertThrows<UnsupportedCatalogBinding> { d.refusalCount("ctl") }
        e.message!! shouldContain "vacuously"
    }

    @Test
    fun `driveContextless refuses loudly outside the dur profile`() {
        val d = KernelDriver(0L).also { it.spawn("h1", "a", "set-source", emptyMap()) }
        val e = assertThrows<UnsupportedCatalogBinding> { d.driveContextless("a", null, "add", s("x")) }
        // A core cell admits a contextless delivery as ordinary traffic, so driving
        // one there would assert nothing about [24-DUR-06].
        e.message!! shouldContain "would assert nothing"
    }

    @Test
    fun `driveContextless refuses a non-effect-sink target rather than fabricating a tag`() {
        val d = durGraph()
        val e = assertThrows<UnsupportedCatalogBinding> { d.driveContextless("source", null, "add", s("x")) }
        e.message!! shouldContain "effect-sink"
    }

    @Test
    fun `driveContextless refuses an inlet it cannot resolve rather than using the default`() {
        val d = durGraph()
        val e = assertThrows<UnsupportedCatalogBinding> { d.driveContextless("sink", "other", "add", s("x")) }
        e.message!! shouldContain "names inlet 'other'"
    }

    private companion object {
        const val BUDGET = 5_000_000
    }
}
