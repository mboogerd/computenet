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

    private class WithRunnable(val r: Runnable)

    private class Carrier(val first: Owned<String>, val second: Owned<String>)

    private class LeaseCarrier(val first: Leased<String>, val second: Leased<String>)

    private class WithPair(val p: Pair<Owned<String>, Int>)

    private class WithTriple(val t: Triple<Int, Owned<String>, Leased<String>>)

    private class WithResult(val r: Result<Owned<String>>)

    private class WithBoxedResult(val r: List<Result<Owned<String>>>)

    private class WithOptional(val o: java.util.Optional<Owned<String>>)

    private class Envelope(val inner: Owned<String>)

    private class LeasedEnvelope(val inner: Owned<String>)

    private class LeaseValueCarrier(val leased: Leased<LeasedEnvelope>)

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

    /**
     * The same over-reach one SAM flavour over, found under review of computenet-h6sf: a
     * Java functional interface is **not** a `kotlin.Function`, so `is Function<*>` does not
     * stop it, and the walk opened the lambda's hidden carrier class and consumed the
     * capture. `java.lang.Runnable` is a platform type to
     * `ContractProcessor.carriesExclusive`, so the scan can no more see this capture than it
     * can a `Function0`'s — the divergence, and the invariant violation, are identical.
     */
    @Test
    fun `an Owned captured behind a Java functional interface is not discharged`() {
        val captured = Owned("captured")
        val holder = WithRunnable(Runnable { captured.take() })

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

    // ------------------------------------------------------------------
    // Defect 3 — UNDER-REACH through platform containers (computenet-woto).
    //
    // `ContractProcessor.carriesExclusive` tests `type.arguments` *before* its
    // platform-declaration stop, so `Pair<Owned<T>, _>`, `Triple`, `Result` and
    // `java.util.Optional` all mark their method exclusive. The runtime walk's
    // platform stop had no such precedence, so the descriptor asserted the
    // method was discharged while the exclusive stayed live — the silent drop
    // again, which is precisely the divergence [Proxy.discharge]'s KDoc calls
    // "the bug, in whichever direction it points". Each test asserts consumed
    // (`isLive == false`) AND exactly once (`doubleDischarges` unmoved).
    // ------------------------------------------------------------------

    @Test
    fun `an Owned inside a kotlin Pair is discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val owned = Owned("pair")

        Proxy.discharge(WithPair(owned to 1))

        isLive(owned) shouldBe false
        Proxy.doubleDischarges shouldBe before
    }

    @Test
    fun `an Owned and a Leased inside a kotlin Triple are discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val owned = Owned("triple")
        var releases = 0
        val leased = Leased("triple") { releases++ }

        Proxy.discharge(WithTriple(Triple(1, owned, leased)))

        isLive(owned) shouldBe false
        releases shouldBe 1
        Proxy.doubleDischarges shouldBe before
    }

    /**
     * `Result` is a value class, so in a **field** position the compiler unboxes
     * a success into the field itself (`WithResult.r` is `java.lang.Object`
     * holding the `Owned`, not a `kotlin.Result`). This shape therefore already
     * passed against the unfixed walk — no `kotlin.Result` instance exists for
     * the platform stop to refuse. Kept as the regression pin for that erasure,
     * with the *boxed* form below carrying the actual defect. Recorded on
     * computenet-woto: the bead's prescribed `Result` reproduction does not
     * discriminate for this reason, and the boxed one is its substitute.
     */
    @Test
    fun `an Owned inside a kotlin Result field is discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val owned = Owned("result")

        Proxy.discharge(WithResult(Result.success(owned)))

        isLive(owned) shouldBe false
        Proxy.doubleDischarges shouldBe before
    }

    /**
     * ...and where `Result` *is* boxed — as a generic type argument, the one
     * position the value class survives into — the platform stop did refuse it.
     */
    @Test
    fun `an Owned inside a boxed kotlin Result is discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val owned = Owned("boxed-result")

        Proxy.discharge(WithBoxedResult(listOf(Result.success(owned))))

        isLive(owned) shouldBe false
        Proxy.doubleDischarges shouldBe before
    }

    @Test
    fun `an Owned inside a java util Optional is discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val owned = Owned("optional")

        Proxy.discharge(WithOptional(java.util.Optional.of(owned)))

        isLive(owned) shouldBe false
        Proxy.doubleDischarges shouldBe before
    }

    /**
     * The second shape computenet-woto names: `is Owned<*> -> value.take()` used
     * to discard the taken value, so an exclusive held by the *payload of an
     * outer exclusive* was never reached. The KSP scan reaches it (it recurses
     * through `Owned`'s type argument and then that declaration's properties),
     * so this was the same descriptor-asserts-discharged divergence.
     */
    @Test
    fun `an Owned nested inside the value of an outer Owned is discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val inner = Owned("inner")
        val outer = Owned(Envelope(inner))

        Proxy.discharge(outer)

        isLive(inner) shouldBe false
        Proxy.doubleDischarges shouldBe before
    }

    /**
     * ...and the outer's own consume-once state is unaffected: the walk takes it
     * exactly once, so a later take is still the use-after-move error.
     */
    @Test
    fun `discharging a nested outer Owned still consumes the outer exactly once`() {
        val outer = Owned(Envelope(Owned("inner")))

        Proxy.discharge(outer)

        isLive(outer) shouldBe false
    }

    // ------------------------------------------------------------------
    // Defect 4 — UNDER-REACH through a Leased's value (computenet-zyg1).
    //
    // `carriesExclusive` returns true at EXCLUSIVE_MARKERS on the `Leased`
    // itself, so a method carrying one IS marked exclusive and the suppression
    // proxy selects `Proxy.discharging`. The walk stopped at `release()`, whose
    // stated justification was that the pool becomes the value's owner — but
    // `Leased.returnToPool` defaults to `{}`, no production code constructs a
    // `Leased` with a real pool callback, and pooling is G-21 phase 3, unbuilt.
    // So nothing received the value and an `Owned` inside it had no consumer at
    // all: the same descriptor-asserts-discharged silent drop, one shape over.
    // ------------------------------------------------------------------

    @Test
    fun `an Owned inside a Leased's value is discharged exactly once`() {
        val before = Proxy.doubleDischarges
        val inner = Owned("leased-inner")
        var releases = 0

        Proxy.discharge(LeaseValueCarrier(Leased(LeasedEnvelope(inner)) { releases++ }))

        releases shouldBe 1
        isLive(inner) shouldBe false
        Proxy.doubleDischarges shouldBe before
    }

    /**
     * The lease obligation itself is unchanged by the widening: still released
     * exactly once, and still refuses a second release.
     */
    @Test
    fun `discharging a Leased carrying an exclusive still releases the lease exactly once`() {
        val leased = Leased(LeasedEnvelope(Owned("inner"))) { }

        Proxy.discharge(LeaseValueCarrier(leased))

        assertThrows<IllegalStateException> { leased.release() }
    }

    /**
     * ...and an *already*-released lease is not walked, mirroring
     * `Owned`'s already-taken branch: the release failed, so this walk is not
     * the one that discharged the lease and owes nothing reachable through it.
     * Without that symmetry, a lease released by its real consumer and then met
     * by a cleanup walk would have its value consumed underneath that consumer —
     * the over-reach direction of the same invariant.
     */
    @Test
    fun `an already-released Leased's value is not walked`() {
        val inner = Owned("inner")
        val leased = Leased(LeasedEnvelope(inner)) { }
        leased.release()

        Proxy.discharge(LeaseValueCarrier(leased))

        isLive(inner) shouldBe true
    }

    /** The exclusive-payload types themselves keep their exactly-once check. */
    @Test
    fun `Owned still refuses a second take`() {
        val owned = Owned("x")
        owned.take()
        assertThrows<IllegalStateException> { owned.take() }
    }
}
