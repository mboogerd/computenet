package civictech.cell.wire

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ReconnectPolicy] reproduces the schedule and the refused-dial rule
 * `WsTransport` carried (`DEFAULT_RECONNECT_BACKOFF`, `WsConnection.onClose`,
 * `WsConnection.heal`) and adds the intent flags of gyvli-D2/D4.
 */
class ReconnectPolicyTest {

    private val ms = 1_000_000L

    /** One open that the peer refuses [lifetimeMs] later. */
    private fun ReconnectPolicy.refusedOpen(at: Long, lifetimeMs: Long = 1): Long {
        onOpened(at)
        val closedAt = at + lifetimeMs * ms
        onClosed(closedAt)
        return closedAt
    }

    @Test
    fun `the default schedule is 1s doubling, capped at 30s, and never overflows`() {
        val p = ReconnectPolicy()
        (0..6).map(p::nextDelayMs) shouldBe listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        p.nextDelayMs(20) shouldBe 30_000L
        p.nextDelayMs(63) shouldBe 30_000L
        p.nextDelayMs(Int.MAX_VALUE) shouldBe 30_000L
    }

    @Test
    fun `defaults are the transports' limit and window`() {
        val p = ReconnectPolicy()
        p.refusedDialLimit shouldBe 5
        p.refusalWindowMs shouldBe 2_000L
        p.shouldRedial() shouldBe true
    }

    @Test
    fun `five refused opens abandon, four do not`() {
        val p = ReconnectPolicy()
        var t = 0L
        repeat(4) { t = p.refusedOpen(t + ms) }
        p.unadmittedOpens shouldBe 4
        p.abandoned shouldBe false
        p.shouldRedial() shouldBe true
        p.refusedOpen(t + ms)
        p.unadmittedOpens shouldBe 5
        p.abandoned shouldBe true
        p.shouldRedial() shouldBe false
    }

    @Test
    fun `an open that outlives the refusal window clears the run`() {
        val p = ReconnectPolicy()
        var t = 0L
        repeat(4) { t = p.refusedOpen(t + ms) }
        t = p.refusedOpen(t + ms, lifetimeMs = 2_000) // exactly the window: not a refusal
        p.unadmittedOpens shouldBe 0
        p.abandoned shouldBe false
        p.refusedOpen(t + ms, lifetimeMs = 1_999)
        p.unadmittedOpens shouldBe 1
    }

    @Test
    fun `a dial that never opened neither counts nor clears the run`() {
        val p = ReconnectPolicy()
        var t = 0L
        repeat(3) { t = p.refusedOpen(t + ms) }
        repeat(100) { p.onClosed(t + 10_000 * ms) } // failed connects, however late
        p.unadmittedOpens shouldBe 3
        p.abandoned shouldBe false
        p.shouldRedial() shouldBe true
    }

    @Test
    fun `admission clears the run`() {
        val p = ReconnectPolicy()
        var t = 0L
        repeat(4) { t = p.refusedOpen(t + ms) }
        p.onOpened(t + ms)
        p.onAdmitted()
        p.onClosed(t + 2 * ms) // short-lived but admitted: not a refusal
        p.unadmittedOpens shouldBe 0
        p.shouldRedial() shouldBe true
    }

    @Test
    fun `a disabled window leaves only admission to clear the run (the iroh shape)`() {
        val p = ReconnectPolicy(refusalWindowMs = Long.MAX_VALUE)
        var t = 0L
        repeat(5) { t = p.refusedOpen(t + ms, lifetimeMs = 3_600_000) }
        p.abandoned shouldBe true
    }

    @Test
    fun `a maxed-out run stops redial even before a close marks it abandoned`() {
        val p = ReconnectPolicy()
        repeat(5) { p.onOpened(it * ms) }
        p.abandoned shouldBe false
        p.shouldRedial() shouldBe false
    }

    @Test
    fun `heal clears abandoned and the run`() {
        val p = ReconnectPolicy()
        var t = 0L
        repeat(5) { t = p.refusedOpen(t + ms) }
        p.shouldRedial() shouldBe false
        p.heal()
        p.abandoned shouldBe false
        p.unadmittedOpens shouldBe 0
        p.shouldRedial() shouldBe true
    }

    @Test
    fun `sever holds shouldRedial false until heal`() {
        val p = ReconnectPolicy()
        p.sever()
        p.severed shouldBe true
        p.shouldRedial() shouldBe false
        p.onClosed(ms)
        p.shouldRedial() shouldBe false
        p.admitDial(2 * ms) shouldBe false // a dial that opened after the sever is not wanted
        p.unadmittedOpens shouldBe 0 // and is not charged
        p.heal()
        p.severed shouldBe false
        p.shouldRedial() shouldBe true
        p.admitDial(3 * ms) shouldBe true
        p.unadmittedOpens shouldBe 1
    }

    @Test
    fun `a deliberate close is permanent, heal does not undo it`() {
        val p = ReconnectPolicy()
        p.closeDeliberately()
        p.deliberateClose shouldBe true
        p.shouldRedial() shouldBe false
        p.heal()
        p.shouldRedial() shouldBe false
        p.admitDial(ms) shouldBe false
    }

    @Test
    fun `an injected schedule and limit are honoured`() {
        val p = ReconnectPolicy(backoff = { 7L * (it + 1) }, refusedDialLimit = 2, refusalWindowMs = 10)
        p.nextDelayMs(0) shouldBe 7L
        p.nextDelayMs(2) shouldBe 21L
        p.refusedOpen(0, lifetimeMs = 9)
        p.shouldRedial() shouldBe true
        p.refusedOpen(100 * ms, lifetimeMs = 9)
        p.abandoned shouldBe true
    }
}
