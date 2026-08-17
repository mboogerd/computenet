package civictech.cell.proxy

import civictech.cell.Leased
import civictech.cell.Owned
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * computenet-h6sf: the two defects the review of computenet-ulss found in
 * [Proxy.discharge]'s reflective object walk, and the decision recorded in that
 * function's KDoc.
 *
 * Both defects are violations of the same AGENTS.md invariant in opposite
 * directions — an exclusive silently dropped, and an exclusive consumed by
 * something that never owned it — so every test here is paired with its
 * opposite, and no fix may make one green by reddening the other.
 */
class ProxyDischargeReachTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class SharedRegistry(val held: Owned<String>)

    private class Cmd(val item: Owned<String>, val registry: SharedRegistry)

    private class WithFn(val f: () -> Unit)

    private class Carrier(val first: Owned<String>, val second: Owned<String>)

    private class LeaseCarrier(val first: Leased<String>, val second: Leased<String>)

    private fun isLive(owned: Owned<*>): Boolean = runCatching { owned.take() }.isSuccess

    // ------------------------------------------------------------------
    // Defect 1 — OVER-REACH.
    // ------------------------------------------------------------------

    /**
     * The reach the decision **keeps**: an exclusive the compile-time scan
     * (`ContractProcessor.carriesExclusive`) can see is discharged. Narrowing
     * below this line would re-open computenet-ulss's silent drop.
     */
    @Test
    fun `an Owned held in a payload field is still discharged`() {
        val mine = Owned("mine")
        val theirs = Owned("theirs")

        Proxy.discharge(Cmd(mine, SharedRegistry(theirs)))

        isLive(mine) shouldBe false
        // Decision: a declared `Owned` property reachable from an argument IS
        // payload — the KSP scan marks the method exclusive because of it, and
        // the SPSC handshake enforces sole consumption on it. Refusing to
        // discharge it here would be the silent drop, not a fix.
        isLive(theirs) shouldBe false
    }

    /**
     * The reach the decision **removes**: a value reached through a function
     * type. `kotlin.Function0` is a platform declaration, so
     * `ContractProcessor.carriesExclusive` stops there and can never mark a
     * method exclusive on account of a captured `Owned`. The runtime walk
     * descending into the lambda's *carrier class* therefore consumed an
     * exclusive no contract ever declared as payload.
     */
    @Test
    fun `an Owned captured by a lambda is not discharged`() {
        val captured = Owned("captured")
        val holder = WithFn { captured.take() }

        Proxy.discharge(holder)

        isLive(captured) shouldBe true
    }

    // ------------------------------------------------------------------
    // Defect 2 — THROW OUT OF CLEANUP.
    // ------------------------------------------------------------------

    @Test
    fun `an already-consumed Owned does not abort the walk and the rest is still discharged`() {
        val already = Owned("already")
        already.take()
        val rest = Owned("rest")

        Proxy.discharge(Carrier(already, rest))

        isLive(rest) shouldBe false
    }

    @Test
    fun `an already-released Leased does not abort the walk`() {
        var releases = 0
        val already = Leased("already") { releases++ }
        already.release()
        val rest = Leased("rest") { releases++ }

        Proxy.discharge(LeaseCarrier(already, rest))

        releases shouldBe 2
    }

    /**
     * ...and it is **not masked**: the occurrence is counted, so a real
     * `[SEC1-20]` double-discharge stays observable instead of being swallowed
     * by a blanket `runCatching`.
     */
    @Test
    fun `a double discharge is counted rather than swallowed`() {
        val before = Proxy.doubleDischarges

        val already = Owned("already")
        already.take()
        Proxy.discharge(Carrier(already, Owned("rest")))

        Proxy.doubleDischarges shouldBe before + 1
    }

    @Test
    fun `a clean discharge counts no double discharge`() {
        val before = Proxy.doubleDischarges

        Proxy.discharge(Carrier(Owned("a"), Owned("b")))

        Proxy.doubleDischarges shouldBe before
    }

    /**
     * The exception must not escape into the suppression / admit-drop path —
     * `Proxy.discharging`'s handler and `InletPolicy.offer` both iterate
     * `args.forEach(::discharge)` unguarded.
     */
    @Test
    fun `discharge does not throw on an already-consumed exclusive`() {
        val already = Owned("already")
        already.take()

        Proxy.discharge(already)
    }

    /** The exclusive-payload types themselves keep their exactly-once check. */
    @Test
    fun `Owned still refuses a second take`() {
        val owned = Owned("x")
        owned.take()
        assertThrows<IllegalStateException> { owned.take() }
    }
}
