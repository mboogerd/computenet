package civictech.cell.host

import civictech.cell.BoundaryDenial
import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.DenialReason
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Redacted
import civictech.cell.link.PeerId
import civictech.cell.port.Port
import civictech.cell.port.PortDelegateProvider
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class LifecycleAndDeadLetterTest {

    class ThrowingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = throw IllegalStateException("boom: $input")
            })
        }
    }

    class TrackingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        var activations = 0
        var deactivations = 0
        override fun onActivate(ctx: CellContext) {
            activations++
        }

        override fun onDeactivate(ctx: CellContext) {
            deactivations++
        }
    }

    /**
     * computenet-weo8: a port that is registered but is not a [Use], so
     * `HostRoutingApi.route`'s `as? Use<*>` cast fails on it — the third
     * route-failure kind. No kernel port type is Port-but-not-Use today (every
     * one of them implements [Use]), so the only way to reach that branch is a
     * bare [Port] like this one; it exists to cover a defensive branch, not to
     * model anything a graph builds.
     */
    class NotUsablePort(override val ref: PortRef = PortRef.generate()) : Port

    class NotUsablePortCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("unused")
        val plain by PortDelegateProvider { NotUsablePort() }
    }

    /** An exclusive-carrying api, so a route's [Invocation] holds real `Owned`/`Leased` args. */
    interface ExclusiveConsumer {
        fun accept(owned: Owned<String>, leased: Leased<String>)
    }

    private val accept = ExclusiveConsumer::class.java.methods.find { it.name == "accept" }

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, PortRef.generate()))
        return letters
    }

    private val provide = Consumer::class.java.methods.find { it.name == "provide" }

    @Test
    fun `a throwing cell dead-letters and the host continues`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val thrower = ThrowingCell()
        val collector = civictech.cell.HostTest.CollectingConsumerCell()
        host.managementInlet.call.spawn(thrower)
        host.managementInlet.call.spawn(collector)

        host.routerInlet.call.route(thrower.ref, "inlet", Invocation.of(provide, arrayOf("x")))
        host.routerInlet.call.route(collector.ref, "inlet", Invocation.of(provide, arrayOf("still-alive")))
        controller.runToIdle()

        letters.size shouldBe 1
        letters[0].cause!!.message shouldContain "boom"
        collector.received shouldBe listOf("still-alive")
    }

    @Test
    fun `routing to an unknown port dead-letters instead of silence`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val cell = TrackingCell()
        host.managementInlet.call.spawn(cell)
        host.routerInlet.call.route(cell.ref, "nope", Invocation.of(provide, arrayOf("x")))
        controller.runToIdle()

        letters.size shouldBe 1
        letters[0].description shouldContain "nope"
    }

    @Test
    fun `despawn runs onDeactivate exactly once and later routes dead-letter`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val hostApi = host.managementInlet.call

        val cell = TrackingCell()
        hostApi.spawn(cell)
        cell.activations shouldBe 1

        hostApi.despawn(cell.ref)
        controller.runToIdle()
        cell.deactivations shouldBe 1

        // second despawn dead-letters (unknown ref), does not re-deactivate
        hostApi.despawn(cell.ref)
        // routing to the despawned cell dead-letters too
        host.routerInlet.call.route(cell.ref, "inlet", Invocation.of(provide, arrayOf("late")))
        controller.runToIdle()

        cell.deactivations shouldBe 1
        letters.size shouldBe 2
    }

    /**
     * computenet-usd.7: a boundary refusal and a plain host-level drop both
     * carry `cause == null`, so before this test's subject existed the only
     * discriminator on the dead-letter outlet was a string prefix on
     * [DeadLetter.description]. [DeadLetter.denial] is the structural fix —
     * drives [DeadLetters] directly (this test lives in its own package,
     * `internal` is visible) rather than through a hosted `BoundaryPolicy`
     * seam, because what is under test is the record shape [DeadLetters]
     * hands to the outlet, not any one adopter of it.
     */
    @Test
    fun `a boundary denial's dead letter carries a typed denial distinct from a plain drop`() {
        val letters = mutableListOf<DeadLetter>()
        val hostRef = CellRef(UUID.randomUUID())
        val deadLetters = DeadLetters(hostRef) { letters += it }

        // a plain fault/drop: no denial attached
        deadLetters.deadLetter(cause = null, description = "routing to an unknown port")

        val denial = BoundaryDenial(
            seam = BoundarySeam.INTEGRITY,
            exposure = "exposedInlet",
            principal = PeerId("mallory"),
            subject = "Consumer#provide",
            reason = DenialReason.REPLAY,
            detail = "counter=7 not > last accepted 7",
        )
        deadLetters.boundaryDenial(CellRef(UUID.randomUUID()), denial, deniedArgs = emptyList())

        letters.size shouldBe 2
        val (drop, refusal) = letters

        // both share the fault-vs-denial ambiguity this item closes: null cause
        drop.cause shouldBe null
        refusal.cause shouldBe null

        // the discriminator: only the refusal carries a denial record
        drop.denial shouldBe null
        refusal.denial shouldBe denial
        refusal.description shouldContain "boundary denial at exposure"
    }

    /**
     * computenet-mouq — the vacuity pin. `routerInlet.call.route(...)` is the
     * idiom this file made popular, and it is the WRONG instrument for any
     * assertion about dead-letter *arguments*: the routing handler throws
     * inside [ManagedHost]'s private `enqueue` helper, whose fault path calls
     * `deadLetter(e, ...)` with no `invocation`, so the emitted [DeadLetter]
     * carries `invocation == null` and
     * `DeadLetters.sanitizeForDeadLetter` — which keys off exactly that
     * invocation — never runs. A test that drives a fault through `route` and
     * then asserts Frozen/Redacted forms or per-argument discharge accounting
     * asserts over a record whose argument capture was never populated: the
     * assertions can pass because the shape they check is *absent* rather than
     * wrong.
     *
     * Use [ManagedHost.enqueueHostedInvocation] for those — it is the path
     * that carries a `HostedPortInvocation` into the fault catch. This test
     * holds both halves side by side so the difference is executable rather
     * than folklore; if `route` is ever changed to carry an invocation, the
     * first half goes red and points the changer here.
     *
     * The second half records the exclusive consequence, and computenet-weo8
     * flipped it: the `Owned` handed to a *failing* `route` is now
     * **discharged** (consumed — no longer takeable) at the route fault site
     * itself, while the one that reaches the capture path is frozen by
     * `sanitizeForDeadLetter`. Two different mechanisms, both satisfying
     * AGENTS.md's no-silent-drop invariant; only the second one leaves a
     * per-argument record on the dead letter, which is exactly what the first
     * half of this test still pins.
     */
    @Test
    fun `a route-driven dead letter carries no invocation, so per-argument capture is unreachable through it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val cell = TrackingCell()
        host.managementInlet.call.spawn(cell)

        val viaRoute = Owned("via-route")
        host.routerInlet.call.route(cell.ref, "nope", Invocation.of(provide, arrayOf(viaRoute)))
        controller.runToIdle()

        letters.size shouldBe 1
        // the pin: no invocation reached the dead letter, so there is no
        // per-argument capture on this record to assert anything about
        letters[0].invocation shouldBe null
        // ...and consequently nothing SANITIZED the argument. computenet-weo8:
        // it is nonetheless discharged — consumed explicitly at the route fault
        // site, not frozen into a capture — so it is no longer takeable.
        assertThrows<IllegalStateException> { viaRoute.take() }

        // the instrument that does exercise the capture path
        val viaIntake = Owned("via-intake")
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = cell.ref,
                portName = "nope",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(provide, arrayOf(viaIntake)),
            ),
        )
        controller.runToIdle()

        letters.size shouldBe 2
        val captured = letters[1].invocation.shouldNotBeNull()
        captured.invocation.args.single().shouldBeInstanceOf<Frozen<*>>()
    }

    /**
     * computenet-weo8 — the three route-failure kinds, each with an exclusive
     * argument. `HostRoutingApi.route` can fail in exactly three ways before
     * the target inlet is ever reached: the cell is unknown, the named port is
     * unknown, or the named port exists but is not a [Use]. All three throw out
     * of the routing handler into [ManagedHost]'s private `enqueue` fault
     * catch, whose `deadLetter(e, …)` carries no `HostedPortInvocation` — so
     * `DeadLetters.sanitizeForDeadLetter` never runs and cannot be what
     * discharges the caller's `Owned`/`Leased`. AGENTS.md's core invariant
     * ("no failure, suppression, shadow, park, or dead-letter path may silently
     * drop an exclusive payload") therefore has to be satisfied at the route
     * fault site itself, by explicit consume/release — which is what these
     * assert, one per kind.
     */
    private fun assertRouteFailureDischarges(
        spawn: (ManagedHost) -> Unit = {},
        target: (ManagedHost) -> CellRef,
        portName: String,
        expectedMessage: String,
    ) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        spawn(host)

        val owned = Owned("exclusive")
        var releasedTo: String? = null
        val leased = Leased("leased") { releasedTo = it }
        val doubleDischargesBefore = Proxy.doubleDischarges

        host.routerInlet.call.route(
            target(host),
            portName,
            Invocation.of(accept, arrayOf(owned, leased)),
        )
        controller.runToIdle()

        letters.size shouldBe 1
        letters[0].cause!!.message shouldContain expectedMessage

        // the invariant: the caller's exclusives are discharged, not dropped live
        assertThrows<IllegalStateException> { owned.take() }
        releasedTo shouldBe "leased"
        // discharged exactly once — the route fault site must not double-discharge
        Proxy.doubleDischarges shouldBe doubleDischargesBefore
    }

    @Test
    fun `a route to an unknown cell discharges the exclusive arguments`() {
        assertRouteFailureDischarges(
            target = { CellRef(UUID.randomUUID()) },
            portName = "inlet",
            expectedMessage = "Target cell not found",
        )
    }

    @Test
    fun `a route to an unknown port discharges the exclusive arguments`() {
        val cell = TrackingCell()
        assertRouteFailureDischarges(
            spawn = { it.managementInlet.call.spawn(cell) },
            target = { cell.ref },
            portName = "nope",
            expectedMessage = "Inlet not found",
        )
    }

    @Test
    fun `a route to a port that is not usable discharges the exclusive arguments`() {
        val cell = NotUsablePortCell()
        assertRouteFailureDischarges(
            spawn = { it.managementInlet.call.spawn(cell) },
            // a registered port that is not a `Use`, so the cast in `route` fails
            target = { cell.ref },
            portName = "plain",
            expectedMessage = "not usable",
        )
    }

    @Test
    fun `re-spawning a live ref is rejected`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val cell = TrackingCell()
        hostApi.spawn(cell)
        assertThrows<IllegalArgumentException> { hostApi.spawn(cell) }
        cell.activations shouldBe 1
    }

    /** computenet-c0gz: an envelope holding a nested [Owned]. */
    class NestedEnvelope(val inner: Owned<String>)

    /**
     * The nested shape [sanitizeForDeadLetter][DeadLetters] has to reach: an
     * exclusive is the payload's *field*, not a top-level argument.
     */
    interface NestedConsumer {
        fun accept(leased: Leased<NestedEnvelope>, owned: Owned<NestedEnvelope>)
    }

    private val acceptNested = NestedConsumer::class.java.methods.find { it.name == "accept" }

    /**
     * computenet-c0gz — an exclusive nested inside a dead-lettered
     * `Owned`/`Leased` must still get a consumer.
     *
     * Both shapes go through `DeadLetters.sanitizeForDeadLetter` in one
     * capture, via [ManagedHost.enqueueHostedInvocation] (the instrument
     * `routerInlet.call.route` is not — see the computenet-mouq test above):
     *
     * - arg 0 is a `Leased` whose value holds an `Owned`. The lease is
     *   released and stands in as a [civictech.cell.Redacted] marker, so the
     *   inner `Owned` is not reachable through the record at all; if the
     *   sanitizer does not consume it, nothing ever does — the silent drop
     *   AGENTS.md's core invariant forbids.
     * - arg 1 is an `Owned` whose value holds an `Owned`. The outer is frozen,
     *   and the inner rides into the fan-out outlet *inside* that `Frozen`;
     *   [DeadLetters]' own KDoc says a live `Owned` MUST NOT enter it, so the
     *   inner must be consumed even though the object graph is unchanged.
     *
     * Measured against the unfixed sanitizer (2026-09-05): both inners were
     * still takeable. Exactly-once is asserted through
     * [Proxy.doubleDischarges], so a fix that discharges twice is not mistaken
     * for one that discharges once.
     */
    @Test
    fun `an exclusive nested inside a dead-lettered Owned or Leased is consumed exactly once`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val cell = TrackingCell()
        host.managementInlet.call.spawn(cell)

        val innerInLease = Owned("inner-in-lease")
        var leaseReturned: NestedEnvelope? = null
        val leased = Leased(NestedEnvelope(innerInLease)) { leaseReturned = it }

        val innerInOwned = Owned("inner-in-owned")
        val owned = Owned(NestedEnvelope(innerInOwned))

        val doubleDischargesBefore = Proxy.doubleDischarges

        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = cell.ref,
                portName = "nope",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(acceptNested, arrayOf(leased, owned)),
            ),
        )
        controller.runToIdle()

        // the capture path really ran: the top-level substitutions happened
        val captured = letters.single().invocation.shouldNotBeNull()
        captured.invocation.args[0].shouldBeInstanceOf<Redacted>()
        captured.invocation.args[1].shouldBeInstanceOf<Frozen<*>>()
        leaseReturned.shouldNotBeNull()

        // the pin: neither nested exclusive is left live
        assertThrows<IllegalStateException>("Owned nested inside a released Leased was left live") {
            innerInLease.take()
        }
        assertThrows<IllegalStateException>("Owned nested inside a Frozen entering the fan-out was left live") {
            innerInOwned.take()
        }
        // ...and each got its consumer exactly once, not twice
        Proxy.doubleDischarges shouldBe doubleDischargesBefore
    }

    /**
     * computenet-c0gz — the ordering interaction, measured rather than assumed.
     * `Proxy.discharge` (the suppression/denial walk) and
     * `DeadLetters.sanitizeForDeadLetter` can both meet the same wrapper, and
     * both now descend into its value. Neither coordinates with the other:
     * each descends only when its own `take()`/`release()` succeeded, so the
     * second one to arrive declines and the nested exclusive still gets
     * exactly one consumer.
     *
     * The discriminator is [Proxy.doubleDischarges] measured over the *whole*
     * sequence. A sanitizer that descended unconditionally would re-walk an
     * already-consumed inner handle and book a second occurrence here, which
     * is invisible in the "is it still takeable" assertion the test above
     * makes.
     */
    @Test
    fun `a wrapper already walked by Proxy discharge is not walked again by dead-letter capture`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val cell = TrackingCell()
        host.managementInlet.call.spawn(cell)

        val innerInLease = Owned("inner-in-lease")
        val leased = Leased(NestedEnvelope(innerInLease))
        val innerInOwned = Owned("inner-in-owned")
        val owned = Owned(NestedEnvelope(innerInOwned))

        // the suppression/denial walk gets there first and consumes everything
        Proxy.discharge(leased)
        Proxy.discharge(owned)
        assertThrows<IllegalStateException> { innerInLease.take() }
        assertThrows<IllegalStateException> { innerInOwned.take() }

        val doubleDischargesBefore = Proxy.doubleDischarges

        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = cell.ref,
                portName = "nope",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(acceptNested, arrayOf(leased, owned)),
            ),
        )
        controller.runToIdle()

        // capture still succeeds and still admits no live handle: a pre-consumed
        // `Owned` degrades to a marker rather than crashing the capture
        val captured = letters.single().invocation.shouldNotBeNull()
        captured.invocation.args[0].shouldBeInstanceOf<Redacted>()
        captured.invocation.args[1].shouldBeInstanceOf<Redacted>()

        // the pin: the sanitizer declined to descend, so nothing was consumed twice
        Proxy.doubleDischarges shouldBe doubleDischargesBefore
    }

    /**
     * computenet-1ffh — the OTHER arrival order, and the measurement behind the
     * decision recorded in [Proxy.doubleDischarges]' KDoc.
     *
     * The test above drives `Proxy.discharge` first and capture second, and
     * pins a delta of **0**. This one drives the same two paths in the opposite
     * order over the same shape and pins a delta of **2** — one per outer
     * wrapper, booked by `Proxy.discharge`'s own already-consumed/
     * already-released branches when the sanitizer got there first.
     *
     * The asymmetry is the point: the same event (two arrivals at one wrapper)
     * is counted 2 in one order and 0 in the other, because
     * `sanitizeForDeadLetter` swallows its own `freeze()`/`release()` failure
     * in `runCatching` and books nothing. Symmetrizing it — counting the
     * sanitizer's arrival on the same tripwire — was considered under
     * computenet-1ffh and **rejected**; the reason, and what the counter
     * therefore does not see, is stated next to the number in
     * [Proxy.doubleDischarges]. This test exists so those two numbers are
     * executable rather than folklore: if either order's delta changes, that
     * KDoc is stale and this goes red.
     */
    @Test
    fun `dead-letter capture arriving first leaves the second walk to book both wrappers`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val cell = TrackingCell()
        host.managementInlet.call.spawn(cell)

        val innerInLease = Owned("inner-in-lease")
        val leased = Leased(NestedEnvelope(innerInLease))
        val innerInOwned = Owned("inner-in-owned")
        val owned = Owned(NestedEnvelope(innerInOwned))

        val doubleDischargesBefore = Proxy.doubleDischarges

        // capture gets there FIRST this time
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = cell.ref,
                portName = "nope",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(acceptNested, arrayOf(leased, owned)),
            ),
        )
        controller.runToIdle()

        // both wrappers were live when the sanitizer met them, so it substituted
        // normally and descended: Leased -> marker, Owned -> Frozen
        val captured = letters.single().invocation.shouldNotBeNull()
        captured.invocation.args[0].shouldBeInstanceOf<Redacted>()
        captured.invocation.args[1].shouldBeInstanceOf<Frozen<*>>()
        assertThrows<IllegalStateException> { innerInLease.take() }
        assertThrows<IllegalStateException> { innerInOwned.take() }

        // the sanitizer alone books nothing
        Proxy.doubleDischarges shouldBe doubleDischargesBefore

        // ...and the walk arriving SECOND books one per outer wrapper and
        // declines to descend, where the reverse order booked zero
        Proxy.discharge(leased)
        Proxy.discharge(owned)
        Proxy.doubleDischarges shouldBe doubleDischargesBefore + 2
    }
}
