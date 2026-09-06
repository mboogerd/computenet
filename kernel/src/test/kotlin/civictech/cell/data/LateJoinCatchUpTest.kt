package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta

/**
 * The G-22 claim: a subscriber that links mid-stream receives the current
 * state as a delta-from-empty (fired from the post-install onLinked hook) and
 * from then on the live stream — at idle it is indistinguishable from an early
 * joiner. Control proves the harness detects the missed prefix without
 * catch-up. Plus: Stateful data cells survive host migration with tags intact.
 */
class LateJoinCatchUpTest {

    /** Handshake link (the Use overload of linkTo is the ad-hoc, no-handshake path). */
    private fun link(outlet: FanOutlet<Propagate<SetDelta<String>>>, collector: CollectorCell): LinkResult =
        outlet.linkTo(collector.inlet as LinkFrom<Propagate<SetDelta<String>>>)

    /** Replace the handshake's direct subscription with delivery over the view host's queue. */
    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, host: ManagedHost, collector: CollectorCell) {
        val routed = host.lookup<DeltaInletProxy>(collector.ref)!!.inlet.call
        outlet.unsubscribe(collector.inlet.ref)
        outlet.subscribe(Use.fixed(routed, collector.inlet.ref))
    }

    private data class Run(val early: Set<String>, val late: Set<String>, val expected: Set<String>)

    private fun runLateJoin(seed: Long, ops: Int, catchUp: Boolean): Run {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())

        val writer = SetCell<String>()
        val early = CollectorCell().also { host.managementInlet.call.spawn(it) }
        val late = CollectorCell().also { host.managementInlet.call.spawn(it) }

        (link(writer.outlet, early) is LinkResult.Connected).shouldBeTrue()
        reroute(writer.outlet, host, early)

        val rnd = Random(seed)
        // wide domain: some elements must stay untouched after the late join,
        // or a re-add would mask a missed prefix from the control's view
        val domain = ('a'..'h').map { it.toString() }
        val held = mutableSetOf<String>()
        fun op() {
            val element = domain[rnd.nextInt(domain.size)]
            if (rnd.nextInt(10) < 7 || element !in held) {
                writer.inlet.call.add(element); held += element
            } else {
                writer.inlet.call.remove(element); held -= element
            }
            repeat(rnd.nextInt(4)) { controller.step() }
        }

        repeat(ops / 2) { op() }

        if (!catchUp) writer.outlet.linking.onLinkedListeners.clear() // control: no catch-up (PN-9: catch-up is an onLinkedListeners hook)
        (link(writer.outlet, late) is LinkResult.Connected).shouldBeTrue()
        reroute(writer.outlet, host, late)

        repeat(ops / 2) { op() }
        controller.runToIdle()

        return Run(tagFold(early.arrivals), tagFold(late.arrivals), held.toSet())
    }

    @Test
    fun `a late joiner equals an early joiner at idle on every seed`() {
        for (seed in 0L until 100L) {
            val run = runLateJoin(seed, ops = 40, catchUp = true)
            run.late shouldBe run.expected
            run.early shouldBe run.expected
        }
    }

    @Test
    fun `control - without catch-up the late joiner misses the prefix`() {
        var missed = 0
        for (seed in 0L until 50L) {
            val run = runLateJoin(seed, ops = 40, catchUp = false)
            if (run.late != run.expected) missed++
        }
        // if this fails the harness cannot detect a missed prefix — tune the op script
        (missed > 0).shouldBeTrue()
    }

    @Test
    fun `a Stateful SetCell migrates with its tags intact`() {
        val controller = SimulationController(seed = 11)
        val hostA = ManagedHost(scheduler = controller.scheduler())
        val hostB = ManagedHost(scheduler = controller.scheduler())

        val writer = SetCell<String>()
        hostA.managementInlet.call.spawn(writer)
        val observer = CollectorCell()
        (link(writer.outlet, observer) is LinkResult.Connected).shouldBeTrue()

        writer.inlet.call.add("x")
        writer.inlet.call.add("y")
        controller.runToIdle()

        hostA.managementInlet.call.migrate(hostB.managementInlet)
        controller.runToIdle()

        // the remove must emit the pre-migration tag: state round-tripped serialization
        writer.inlet.call.remove("x")
        controller.runToIdle()

        tagFold(observer.arrivals) shouldBe setOf("y")
        val added = observer.arrivals.first { "x" in it.adds }.adds.getValue("x")
        val removed = observer.arrivals.first { "x" in it.dels }.dels.getValue("x")
        // the pre-migration add-tag is covered: the tag maps round-tripped
        removed shouldContainAll added
        // ... and so did the MINTING COUNTER. The remove mints a del-dot from it
        // (`[24-TAG-04]`, computenet-v2ka), so the dot's counter is a direct read
        // of the restored counter: two adds before the migration means the dot
        // must be 3. A counter that reset on restore would mint 1 here and this
        // assertion — not the coverage one above — is what would catch it.
        val dot = (removed - added).single()
        dot.counter shouldBe 3L
        dot.sourceId shouldBe added.single().sourceId
    }
}
