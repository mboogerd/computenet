package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.proxy.callback
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * T17 — [FanOutlet.observe], the payload-agnostic Observe-role attachment
 * (spec 20/23 §Taps, G-47; audit finding B2).
 *
 * The contract under test is that `observe` is a *tap*, differing from
 * [FanOutlet.tap] only in what the handler is handed: same `taps`/`tapOrder`
 * storage, same SPSC exemption, same taps-fire-first position, same detach.
 * The last test is the one this seam did not have before at all — the T04
 * finding-6 class (`ConcurrentHashMap` + `CopyOnWriteArrayList`, commit
 * `fae2ffa`) exercised against attach/detach racing a live broadcast.
 */
class FanOutletObserveTest {

    @Test
    fun `an observer is notified once per emission, with the wave the emission carries`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val seen = mutableListOf<MessageContext>()

        outlet.observe(PortRef.generate()) { seen += it }

        outlet.call.provide("first")
        outlet.call.provide("second")

        seen.size shouldBe 2
        // spontaneous emissions: this outlet's own counter, stamped with its ref
        seen.map { it.timestamp.counter } shouldBe listOf(1L, 2L)
        seen.map { it.sourcePort } shouldBe listOf(outlet.ref, outlet.ref)
        seen.map { it.hop } shouldBe listOf(0, 0)
    }

    @Test
    fun `the context handed to an observer is the one CurrentContext would have answered`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        var handed: MessageContext? = null
        var ambient: MessageContext? = null

        outlet.observe(PortRef.generate()) { context ->
            handed = context
            ambient = CurrentContext.get()
        }

        outlet.call.provide("x")

        // the whole point of passing it: no thread-local re-read needed
        (handed === ambient) shouldBe true
        handed shouldBe ambient
    }

    @Test
    fun `an observer never sees the payload - the handler takes no arguments at all`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val notifications = AtomicLong()

        // There is no overload that would hand this lambda the emitted value:
        // the seam is payload-agnostic by construction, not by discipline.
        outlet.observe(PortRef.generate()) { notifications.incrementAndGet() }

        outlet.call.provide("a payload no observer can reach")

        notifications.get() shouldBe 1L
    }

    @Test
    fun `observers fire before consumers, interleaved with typed taps in attachment order`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val order = mutableListOf<String>()

        outlet.tap(Use.fixed(callback<Consumer<String>> { order += "tap1" }, PortRef.generate()))
        outlet.observe(PortRef.generate()) { order += "observer1" }
        outlet.tap(Use.fixed(callback<Consumer<String>> { order += "tap2" }, PortRef.generate()))
        outlet.observe(PortRef.generate()) { order += "observer2" }
        outlet.subscribe(Use.fixed(callback<Consumer<String>> { order += "consumer" }, PortRef.generate()))

        outlet.call.provide("x")

        // one shared tapOrder: an observer takes its place among the taps by
        // attachment order, and every tap still precedes every consumer
        order shouldBe listOf("tap1", "observer1", "tap2", "observer2", "consumer")
    }

    @Test
    fun `an observer is uncounted by SPSC - admitted on an exclusive contract that already has its one consumer`() {
        val outlet = FanOutlet.create<TapOwnedPush>()
        val consumed = mutableListOf<String>()
        outlet.subscribe(
            Use.fixed(
                object : TapOwnedPush {
                    override fun push(buffer: Owned<String>) {
                        consumed += buffer.take()
                    }
                },
                PortRef.generate(),
            ),
        )

        val observed = AtomicLong()
        outlet.observe(PortRef.generate()) { observed.incrementAndGet() }

        outlet.call.push(Owned("payload"))

        observed.get() shouldBe 1L
        // the sole consumer's consume-once is unperturbed: the observer cannot
        // have taken the payload, it never had it
        consumed shouldBe listOf("payload")
    }

    @Test
    fun `observe answers a Connected Observe-role link, detachable by unlink or untap, both idempotent`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val byUnlink = AtomicLong()
        val byUntap = AtomicLong()
        val unlinkRef = PortRef.generate()
        val untapRef = PortRef.generate()

        val unlinkResult = outlet.observe(unlinkRef) { byUnlink.incrementAndGet() }
        val untapResult = outlet.observe(untapRef) { byUntap.incrementAndGet() }

        val link = (unlinkResult as LinkResult.Connected).link
        link.role shouldBe LinkRole.Observe
        link.from shouldBe outlet.ref
        link.to shouldBe unlinkRef
        (untapResult is LinkResult.Connected) shouldBe true

        outlet.call.provide("while both attached")

        link.unlink()
        outlet.untap(untapRef)
        outlet.call.provide("after both detached")

        link.unlink() // idempotent
        outlet.untap(untapRef) // idempotent
        outlet.untap(PortRef.generate()) // never attached
        outlet.call.provide("still detached")

        byUnlink.get() shouldBe 1L
        byUntap.get() shouldBe 1L
    }

    @Test
    fun `unsubscribe also detaches an observer, like any other Observe-role attachment`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val observed = AtomicLong()
        val observerRef = PortRef.generate()

        outlet.observe(observerRef) { observed.incrementAndGet() }
        outlet.call.provide("first")
        outlet.unsubscribe(observerRef)
        outlet.call.provide("second")

        observed.get() shouldBe 1L
    }

    @Test
    fun `a suppressing disclosureFilter suppresses the notification too - an observer never reports traffic nobody received`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val delivered = mutableListOf<String>()
        val observed = AtomicLong()

        outlet.subscribe(Use.fixed(callback<Consumer<String>> { delivered += it.args[0] as String }, PortRef.generate()))
        outlet.observe(PortRef.generate()) { observed.incrementAndGet() }
        outlet.disclosureFilter = { args -> if (args.firstOrNull() == "secret") null else args }

        outlet.call.provide("public")
        outlet.call.provide("secret")

        delivered shouldBe listOf("public")
        observed.get() shouldBe 1L
    }

    @Test
    fun `at() cannot target a payload-agnostic observer - it is a counted target miss, not a silent no-op`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val observerRef = PortRef.generate()
        val observed = AtomicLong()

        outlet.observe(observerRef) { observed.incrementAndGet() }

        outlet.at(observerRef).provide("targeted")

        // an observer has no Api to deliver into; the targeted path says so
        observed.get() shouldBe 0L
        outlet.targetMisses shouldBe 1L
    }

    @Test
    fun `a typed tap is still targetable by at(), unchanged by the shared storage`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val tapRef = PortRef.generate()
        val received = mutableListOf<String>()

        outlet.tap(Use.fixed(callback<Consumer<String>> { received += it.args[0] as String }, tapRef))
        outlet.observe(PortRef.generate()) { }

        outlet.at(tapRef).provide("targeted")

        received shouldBe listOf("targeted")
        outlet.targetMisses shouldBe 0L
    }

    @Test
    fun `T04 finding 6 - observe and detach racing a live broadcast neither lose nor double a delivery`() {
        val outlet = FanOutlet.create<Consumer<String>>()

        val emitterCount = 4
        val perEmitter = 5_000
        val total = (emitterCount * perEmitter).toLong()

        // Attached for the whole run: each of these must see every emission,
        // whatever the churn threads do to the shared taps/tapOrder pair.
        val stableObserver = AtomicLong()
        outlet.observe(PortRef.generate()) { stableObserver.incrementAndGet() }
        val stableTap = AtomicLong()
        outlet.tap(Use.fixed(callback<Consumer<String>> { stableTap.incrementAndGet() }, PortRef.generate()))
        val stableConsumer = AtomicLong()
        outlet.subscribe(Use.fixed(callback<Consumer<String>> { stableConsumer.incrementAndGet() }, PortRef.generate()))

        val failures = CopyOnWriteArrayList<Throwable>()
        val churnCounters = CopyOnWriteArrayList<AtomicLong>()
        val start = CountDownLatch(1)
        val emittersDone = AtomicBoolean(false)

        val emitters = (0 until emitterCount).map { e ->
            thread(name = "fanout-observe-emit-$e") {
                try {
                    start.await()
                    repeat(perEmitter) { i -> outlet.call.provide("$e-$i") }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }

        // Attach a fresh payload-agnostic observer and detach it again, over
        // and over, while the emitters are mid-broadcast. Distinct refs per
        // iteration, so this races the per-emission iteration rather than
        // racing itself. Bounded by both a flag and a hard cap — no sleeps, no
        // timing assumptions, and every interleaving satisfies the assertions
        // below.
        val churners = (0 until 2).map { c ->
            thread(name = "fanout-observe-churn-$c") {
                try {
                    start.await()
                    var iterations = 0
                    while (!emittersDone.get() && iterations < MAX_CHURN) {
                        iterations++
                        val counter = AtomicLong()
                        churnCounters += counter
                        val result = outlet.observe(PortRef.generate()) { counter.incrementAndGet() }
                        (result as LinkResult.Connected).link.unlink()
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }

        start.countDown()
        emitters.forEach { it.join() }
        emittersDone.set(true)
        churners.forEach { it.join() }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "emission/churn threads failed:\n" + failures.joinToString("\n") { it.stackTraceToString() },
                failures.first(),
            )
        }

        // No lost delivery: an attachment live for the whole run saw every
        // single emission — and the observer churn disturbed neither the typed
        // tap sharing its map and order list nor the consumer downstream of it.
        stableObserver.get() shouldBe total
        stableTap.get() shouldBe total
        stableConsumer.get() shouldBe total

        // No delivery after detach: every churned observer was unlinked before
        // its thread finished, so none of them can move again.
        val afterChurn = churnCounters.map { it.get() }
        outlet.call.provide("after the storm")
        churnCounters.map { it.get() } shouldBe afterChurn
        stableObserver.get() shouldBe total + 1
    }

    private companion object {
        /** Hard cap on churn iterations, so a slow emitter cannot make the loop unbounded. */
        const val MAX_CHURN = 20_000
    }
}
