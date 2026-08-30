package civictech.concord.driver.kernel

import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The kernel binding of `Driver.driveStamped` (`computenet-8ohq`), at both ends
 * of the contract `concord/schema/scenario.md` §`drive-stamped` states.
 *
 * The corpus half (`DUR-STAMPED-01`) exercises the happy path. Three things a
 * scenario cannot reach are pinned here:
 *
 * - the **refusal sites**. A scenario naming an unbound target errors rather
 *   than asserting anything, so the guards are unobservable from the corpus:
 *   [KernelDriver]'s profile guard, and [KernelDriverDur]'s two target guards
 *   (effect-sink only, default inlet only);
 * - **lane identity**, which the corpus deliberately cannot name. Two drives on
 *   one handle are two arrivals on one lane, two handles are two lanes — the
 *   property the schema requires of a conforming driver and which a scenario can
 *   only assert the consequences of;
 * - **lane survival across a crash**, the second half of that requirement. A
 *   lane that reset would resume at a position the restored frontier has already
 *   acted on, so the post-recovery drive would be *suppressed* — and
 *   `DUR-STAMPED-01` would then read a driver defect as the property under test.
 *   The scenario's `key: g2, exactly: 1` does go red under that regression, so
 *   this is a second reading of it rather than its only one; what it adds is a
 *   failure message naming the lane rather than the frontier.
 */
class StampedDriveBindingTest {

    private fun s(v: String): Value = Value.StrVal(v)

    /** A journaled source feeding an `Effectful` sink — `DUR-STAMPED-01`'s graph. */
    private fun durGraph(): KernelDriver = KernelDriver(0L).also { d ->
        d.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "sink", "effect-sink", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
        d.connect("source", "sink")
    }

    @Test
    fun `a stamped drive is admitted at the Effectful inlet, acted on, and never counted as a refusal`() {
        val d = durGraph()
        d.driveStamped("sink", null, "a1", "add", s("g1"))
        d.quiesce(BUDGET)

        // ADMITTED — the exact inversion of `driveContextless` at the same inlet
        d.effectLog("sink").map { it.key } shouldBe listOf("g1")
        d.refusalCount("sink") shouldBe 0L
    }

    @Test
    fun `two drives on one handle are two arrivals on one lane`() {
        val d = durGraph()
        d.driveStamped("sink", null, "a1", "add", s("g1"))
        d.driveStamped("sink", null, "a1", "add", s("g2"))
        d.quiesce(BUDGET)

        // Both fire: the second carries the lane's NEXT position, ahead of the
        // frontier the first advanced. A binding that reused one position would
        // have the second suppressed as already-acted.
        d.effectLog("sink").map { it.key } shouldBe listOf("g1", "g2")
        d.refusalCount("sink") shouldBe 0L
    }

    @Test
    fun `distinct handles are distinct lanes`() {
        val d = durGraph()
        d.driveStamped("sink", null, "a1", "add", s("g1"))
        d.driveStamped("sink", null, "a2", "add", s("g2"))
        d.quiesce(BUDGET)

        d.effectLog("sink").map { it.key } shouldBe listOf("g1", "g2")
    }

    @Test
    fun `a lane continues across a crash rather than restarting`() {
        val d = durGraph()
        d.driveStamped("sink", null, "a1", "add", s("g1"))
        d.quiesce(BUDGET)
        d.effectLog("sink").map { it.key } shouldBe listOf("g1")

        // CRASH + recover from the journal: `g1` is re-presented and must be
        // recognised at the restored frontier — exactly once across the crash.
        d.despawn("ctl")
        d.quiesce(BUDGET)
        d.effectLog("sink").map { it.key } shouldBe listOf("g1")

        // ...and the SAME actor's next arrival is ahead of that frontier, so it
        // fires. This is what a reset lane would get wrong: it would re-present
        // `g1`'s position and be suppressed, leaving the log unchanged here.
        d.driveStamped("sink", null, "a1", "add", s("g2"))
        d.quiesce(BUDGET)
        d.effectLog("sink").map { it.key } shouldBe listOf("g1", "g2")
        d.refusalCount("sink") shouldBe 0L
    }

    @Test
    fun `driveStamped refuses loudly outside the dur profile`() {
        val d = KernelDriver(0L).also { it.spawn("h1", "a", "set-source", emptyMap()) }
        val e = assertThrows<UnsupportedCatalogBinding> { d.driveStamped("a", null, "a1", "add", s("x")) }
        // A core cell keeps no processed frontier, so a stamped external delivery
        // there is indistinguishable from ordinary traffic.
        e.message!! shouldContain "would assert nothing"
    }

    @Test
    fun `driveStamped refuses a non-effect-sink target rather than fabricating a tag`() {
        val d = durGraph()
        val e = assertThrows<UnsupportedCatalogBinding> { d.driveStamped("source", null, "a1", "add", s("x")) }
        e.message!! shouldContain "effect-sink"
    }

    @Test
    fun `driveStamped refuses an inlet it cannot resolve rather than using the default`() {
        val d = durGraph()
        val e = assertThrows<UnsupportedCatalogBinding> { d.driveStamped("sink", "other", "a1", "add", s("x")) }
        e.message!! shouldContain "names inlet 'other'"
    }

    private companion object {
        const val BUDGET = 5_000_000
    }
}
