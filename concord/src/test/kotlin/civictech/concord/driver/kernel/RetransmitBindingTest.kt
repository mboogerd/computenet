package civictech.concord.driver.kernel

import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * The `retransmit` binding's own tests (`computenet-yh6.1.8`) — the driver half
 * of the gated schema change `computenet-yh6.1.3.3` froze in
 * `concord/schema/scenario.md` (`#### retransmit`).
 *
 * Two things need pinning here rather than in the corpus. First, **which
 * counter a scenario must name**: a corpus scenario states an explicit
 * `(source, counter)` position, and if that literal were off by one the
 * scenario would still pass — a position *behind* the frontier is suppressed
 * just as one *at* it is — while duplicating a coordinate nothing ever emitted.
 * So the mapping "the Nth `add` on a journaled source mints counter N" is
 * asserted behaviourally: the position one past the last add is shown to
 * **fire**, which fixes the frontier's high-water exactly and grounds the
 * literals `DUR-LIVE-01` and `DUR-CKPT-FRONTIER-01` use.
 *
 * Second, the binding's **refusals**. Each is a loud failure by design (see
 * [KernelDriverDur.retransmit]); a corpus scenario cannot exercise one, because
 * a scenario that provoked it would simply error rather than assert anything.
 */
class RetransmitBindingTest {

    private val budget = 5_000_000

    private fun s(v: String): Value = Value.StrVal(v)

    /** The `dur` shape both corpus scenarios use: a journaled source into an effect sink, plus the crash handle. */
    private fun durGraph(): KernelDriver = KernelDriver(0L).also { d ->
        d.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "sink", "effect-sink", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
        d.connect("source", "sink", null, null, null)
    }

    private fun KernelDriver.keys(): List<String?> = effectLog("sink").map { it.key }

    @Test
    fun `a live duplicate at the restored frontier is suppressed, and the next position fires`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.apply("source", "add", s("k2"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2")

        // crash + recoverFrom: the replayed frames are suppressed at the restored
        // frontier (the half DUR-REPLAY-01 already covers)
        d.despawn("ctl")
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2")

        // LIVE duplicate at the frontier: suppressed by the same rule replay uses
        // ([24-DUR-05]'s "or post-recovery live delivery").
        d.retransmit("sink", null, "source", 2, "add", s("k2"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2")

        // One position PAST the frontier fires. This is what makes the literal
        // `counter: 2` above meaningful: the source's second add really did mint
        // counter 2, because counter 3 is not yet on the frontier.
        d.retransmit("sink", null, "source", 3, "add", s("ahead"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2", "ahead")

        // ...and re-delivering that position now finds it on the frontier, so the
        // live fire above genuinely ADVANCED it rather than dodging a stale check.
        d.retransmit("sink", null, "source", 3, "add", s("ahead"))
        d.quiesce(budget)
        d.keys() shouldBe listOf("k1", "k2", "ahead")
    }

    @Test
    fun `a retransmit at a core cell fails loudly rather than injecting an unobserved delivery`() {
        val d = KernelDriver(0L)
        d.spawn("", "a", "set-source", emptyMap())
        d.spawn("", "v", "set-view", emptyMap())
        d.connect("a", "v", null, null, null)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("v", null, "a", 1, "add", s("x"))
        }
        refused.message!! shouldContain "would assert nothing"
    }

    @Test
    fun `a retransmit at a durable cell that is not an effect sink is refused`() {
        val d = KernelDriver(0L)
        d.spawn(KernelDriverDur.DUR_HOST, "source", "journal-set-source", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "view", "journal-set-view", emptyMap())
        d.spawn(KernelDriverDur.DUR_HOST, "ctl", "journal", emptyMap())
        d.connect("source", "view", null, null, null)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("view", null, "source", 1, "add", s("k1"))
        }
        refused.message!! shouldContain "tag identity"
    }

    @Test
    fun `a retransmit naming an inlet the durable delta port is not called is refused`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("sink", "other", "source", 1, "add", s("k1"))
        }
        refused.message!! shouldContain "the durable delta port is 'inlet'"
    }

    @Test
    fun `a retransmit carrying an op an effect boundary cannot act on is refused`() {
        val d = durGraph()
        d.apply("source", "add", s("k1"))
        d.quiesce(budget)

        val refused = assertThrows<UnsupportedCatalogBinding> {
            d.retransmit("sink", null, "source", 1, "remove", s("k1"))
        }
        refused.message!! shouldContain "retransmit op 'remove' unbound"
    }
}
