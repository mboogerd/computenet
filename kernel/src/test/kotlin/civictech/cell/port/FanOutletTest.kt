package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.link.LinkResult
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.DisclosurePolicy
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.callback
import civictech.cell.port.PortRef
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** A membrane whose only exposure is a `Deny`-disclosed outlet — the suppressing seam under test. */
private class DenyingOutletMembrane : CompositeCell() {
    private val organelleOutlet = FanOutlet.create<Propagate<String>>()
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelleOutlet,
        policy = BoundaryPolicy(disclosure = DisclosurePolicy.Deny),
    )
}

/**
 * computenet-oenm's fixture: a `Deny`-disclosed exposed outlet with two real
 * *linked* consumers, `a` and `b`, each recording the `Suspension` notices its
 * own link receives.
 *
 * Real links, not `Use.fixed` subscriptions: the classification walks
 * `linking.links`, so a subscriber that never handshook has no edge to be told
 * about and the test would pass vacuously.
 */
private class DenyingOutletFixture {
    val membrane = DenyingOutletMembrane()
    val a = inlet()
    val b = inlet()
    val stallsA = mutableListOf<StallNotice>()
    val stallsB = mutableListOf<StallNotice>()

    /** Run synchronously inside `a`'s `Suspension` handler — i.e. inside the classification walk itself. */
    var onStallA: (() -> Unit)? = null

    init {
        ProtocolSupport.of(a).handle(Protocols.Suspension) { _, m ->
            stallsA += m as StallNotice
            onStallA?.invoke()
        }
        ProtocolSupport.of(b).handle(Protocols.Suspension) { _, m -> stallsB += m as StallNotice }
        link(a)
        link(b)
    }

    @Suppress("UNCHECKED_CAST")
    private fun link(target: FanInlet<Propagate<String>>) {
        val result = membrane.exposedOutlet.linkTo(target as LinkFrom<Propagate<String>>)
        check(result is LinkResult.Connected) { "the edge must really open, else the walk has nothing to find: $result" }
    }

    private fun inlet() = FanInlet.create<Propagate<String>>().also {
        it.serve(object : Propagate<String> {
            override fun propagate(value: String) = Unit
        })
    }
}

class FanOutletTest {

    @Test
    fun `broadcasting on an empty port doesn't do anything`() {
        val fanOutlet = FanOutlet.create<Consumer<String>>()
        fanOutlet.call.provide("does not throw, but doesn't do anything either")
    }

    @Test
    fun `using a non-existing downstream api completes without error`() {
        val fanOutlet = FanOutlet.create<Consumer<String>>()
        fanOutlet.at(PortRef.generate()).provide("test")
    }

    @Test
    fun `T05 finding 7 - a target-miss on at() is counted, not silently answered into the void`() {
        val fanOutlet = FanOutlet.create<Consumer<String>>()
        assertEquals(0L, fanOutlet.targetMisses)

        val missingRef = PortRef.generate()
        fanOutlet.at(missingRef).provide("test") // no consumer/tap for missingRef
        assertEquals(1L, fanOutlet.targetMisses)

        fanOutlet.at(missingRef).provide("again") // same ref, second miss — still counted
        assertEquals(2L, fanOutlet.targetMisses)

        // a resolvable target is not a miss
        val (portRef, buffer) = fanOutlet.attachBufferingPort()
        fanOutlet.at(portRef).provide("resolved")
        assertEquals(listOf("resolved"), buffer)
        assertEquals(2L, fanOutlet.targetMisses)
    }

    @Test
    fun `retrieving an existing downstream api returns that entry`() {
        val fanOutlet = FanOutlet.create<Consumer<String>>()
        val (portRef1, buffer1) = fanOutlet.attachBufferingPort()
        val (_, buffer2) = fanOutlet.attachBufferingPort()

        fanOutlet.at(portRef1).provide("first")
        fanOutlet.at(portRef1).provide("second")

        assertEquals(listOf("first", "second"), buffer1)
        assertEquals(emptyList(), buffer2)
    }

    @Test
    fun `broadcasting reaches all active subscriptions`() {
        val fanOutlet = FanOutlet.create<Consumer<String>>()

        // first
        val (_, buffer1) = fanOutlet.attachBufferingPort()
        fanOutlet.call.provide("first")

        val (_, buffer2) = fanOutlet.attachBufferingPort()
        fanOutlet.call.provide("second")

        val (_, buffer3) = fanOutlet.attachBufferingPort()
        fanOutlet.call.provide("third")

        assertEquals(listOf("first", "second", "third"), buffer1)
        assertEquals(listOf("second", "third"), buffer2)
        assertEquals(listOf("third"), buffer3)
    }

    @Test
    fun `unsubscribed downstream api is no longer available`() {
        val fanOutlet = FanOutlet.create<Consumer<String>>()
        val (portRef1, buffer1) = fanOutlet.attachBufferingPort()

        fanOutlet.call.provide("first")
        fanOutlet.unsubscribe(portRef1)
        fanOutlet.call.provide("second")
        fanOutlet.at(portRef1).provide("third")

        assertEquals(listOf("first"), buffer1)
    }

    @Test
    fun `re-subscribing a PortRef overwrites the previous handler`() {
        val port = FanOutlet.create<Consumer<String>>()
        val buffer1 = mutableListOf<String>()
        val buffer2 = mutableListOf<String>()

        val fixedPortRef = PortRef.generate()

        val proxy1 = callback<Consumer<String>> { buffer1 += it.args[0] as String }
        port.subscribe(Use.fixed(proxy1, fixedPortRef))
        port.call.provide("first")
        assertEquals(listOf("first"), buffer1)

        val proxy2 = callback<Consumer<String>> { buffer2 += it.args[0] as String }
        port.subscribe(Use.fixed(proxy2, fixedPortRef))
        port.call.provide("second")

        assertEquals(listOf("first"), buffer1)
        assertEquals(listOf("second"), buffer2)
    }

    @Test
    fun `multiple unsubscribe calls do not crash`() {
        val port = FanOutlet.create<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash or side effect
    }

    /**
     * computenet-oenm: a suppressed **targeted** delivery ([FanOutlet.at])
     * starves exactly one consumer, so the I-18 "edge that will not deliver"
     * classification a `Deny` disclosure emits
     * (`CompositeCell.stallDeniedEdges`) must reach that consumer's link and no
     * other. Before the fix the walk covered every `Consume` link of the
     * outlet, so a denial aimed at one peer advanced watermarks and surfaced a
     * `GlitchViolation` on edges nothing had been withheld from.
     *
     * The context is deliberately non-null and **baseline-free**: that is the
     * one shape the walk's own guard (`context == null || context.baseline !=
     * null`, `computenet-usd.3.1`) lets through, and therefore the whole
     * reachable corner. It is driven through [FanOutlet.at] — `Use`'s public
     * targeted-delivery method on the very outlet object `mediateOutlet`
     * exposes — not through a kernel back door.
     */
    @Test
    fun `computenet-oenm - a suppressed targeted delivery classifies only the target's link`() {
        val fixture = DenyingOutletFixture()

        val ctx = MessageContext(Timestamp(UUID.randomUUID(), 7), PortRef.generate())
        CurrentContext.with(ctx) {
            fixture.membrane.exposedOutlet.at(fixture.a.ref).propagate("targeted")
        }

        assertEquals(listOf<StallNotice>(expectedStall(ctx)), fixture.stallsA, "the target's own link is starved and must be classified")
        assertEquals(emptyList<StallNotice>(), fixture.stallsB, "a non-target link was not starved and must NOT be classified")
    }

    /**
     * The regression guard beside it: a **broadcast** suppression really does
     * starve every consumer, so the walk stays whole-fan-out there. Scoping the
     * targeted case must not narrow this one.
     */
    @Test
    fun `computenet-oenm - a suppressed broadcast still classifies every Consume link`() {
        val fixture = DenyingOutletFixture()

        fixture.membrane.exposedOutlet.call.propagate("broadcast")

        // `call` stamps its own wave from the outlet's counter, so read the
        // timestamp off the notice rather than predicting it.
        assertEquals(1, fixture.stallsA.size, "a broadcast starves every consumer, including A")
        assertEquals(1, fixture.stallsB.size, "a broadcast starves every consumer, including B")
        assertEquals(fixture.stallsA, fixture.stallsB, "one emission, one wave — both edges carry the same notice")
    }

    /**
     * computenet-oenm: `TargetedDelivery.take()` reads **and clears**, and this
     * is what that clearing buys. The classification walk of a suppressed
     * targeted delivery hands `Suspension` frames to downstream handlers while
     * the targeted frame's scope is still on the stack, so anything one of
     * those handlers synchronously emits is a *fresh* emission that must not
     * inherit the recipient. Here `a`'s handler answers with a **broadcast**,
     * itself suppressed: its own classification must reach every `Consume`
     * link, `b` included. Without the clearing read, the nested broadcast would
     * be scoped to `a` and `b`'s notice would be silently dropped.
     */
    @Test
    fun `computenet-oenm - a nested emission does not inherit the targeted frame's recipient`() {
        val fixture = DenyingOutletFixture()
        var handlerCalls = 0
        var emitted = 0
        fixture.onStallA = {
            // Emit once only: the nested broadcast classifies `a` again, which
            // re-enters this handler (hence handlerCalls == 2 below).
            if (handlerCalls++ == 0) {
                emitted++
                fixture.membrane.exposedOutlet.call.propagate("nested")
            }
        }

        val ctx = MessageContext(Timestamp(UUID.randomUUID(), 11), PortRef.generate())
        CurrentContext.with(ctx) {
            fixture.membrane.exposedOutlet.at(fixture.a.ref).propagate("targeted")
        }

        assertEquals(1, emitted, "the nested broadcast must have been emitted exactly once")
        assertEquals(2, handlerCalls, "a is classified by the targeted frame and again by the nested broadcast")
        assertEquals(
            1,
            fixture.stallsB.size,
            "the nested broadcast is its own emission: it must classify b's link too, not inherit the targeted frame's single recipient",
        )
    }

    private fun expectedStall(ctx: MessageContext) =
        StallNotice.Stall(StallReason.DEAD_LETTERED, ctx.timestamp)

    fun FanOutlet<Consumer<String>>.attachBufferingPort(): Pair<PortRef, List<String>> {
        val portRef = PortRef.generate()
        val buffer = mutableListOf<String>()
        val proxy = callback<Consumer<String>> {
            buffer += it.args[0] as String
        }
        subscribe(Use.fixed(proxy, portRef))
        return portRef to buffer
    }
}

