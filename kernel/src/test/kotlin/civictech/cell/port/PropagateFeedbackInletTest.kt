package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Magnitude
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.math.abs

/**
 * D10 — the Propagate-shaped feedback head shares the Consumer-shaped
 * [FeedbackInlet] core while retaining the cycle admission, quiescence,
 * fresh-wave and fusion-barrier semantics of spec [21-CYCLE-01].
 */
class PropagateFeedbackInletTest {

    private data class Delta(val value: Double) : Magnitude {
        override fun size() = abs(value)
    }

    private class HeadCell(
        quiescence: Double,
        private val factor: Double,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val outlet by output<Propagate<Delta>>()
        val laps = mutableListOf<Double>()

        val feedbackInput by propagateFeedbackInlet<Delta>(quiescence) { delta ->
            laps += delta.value
            outlet.call.propagate(Delta(delta.value * factor))
        }
    }

    private class RelayCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Propagate<Delta>>()
        val outlet by output<Propagate<Delta>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(Propagate { outlet.call.propagate(it) })
        }
    }

    private class ConsumerSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Consumer<Delta>>()
    }

    private class ConsumerHead(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val feedbackInput by feedbackInlet<Delta>(0.0) { }
    }

    private class RegisteredFeedbackCell(
        consumerRef: PortRef = PortRef.generate(),
        propagateRef: PortRef = PortRef.generate(),
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val consumer = registerPort("consumer", FeedbackInlet<Delta>(consumerRef) { })
        val propagate = registerPort("propagate", PropagateFeedbackInlet<Delta>(propagateRef) { })
    }

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(Propagate { letter -> letters += letter }, PortRef.generate()),
        )
        return letters
    }

    @Test
    fun `a registered Consumer feedback inlet derives its ref from its owner and name`() {
        val cell = RegisteredFeedbackCell()

        cell.consumer.ref shouldBe PortRef.of(cell.ref, "consumer")
    }

    @Test
    fun `a registered Propagate feedback inlet derives its ref from its owner and name`() {
        val cell = RegisteredFeedbackCell()

        cell.propagate.ref shouldBe PortRef.of(cell.ref, "propagate")
    }

    @Test
    fun `feedback inlets preserve their constructor refs when derivation is disabled`() {
        val consumerRef = PortRef.generate()
        val propagateRef = PortRef.generate()
        val previousDerive = PortIdentities.deriveRefs
        PortIdentities.deriveRefs = false
        try {
            val cell = RegisteredFeedbackCell(consumerRef, propagateRef)

            cell.consumer.ref shouldBe consumerRef
            cell.propagate.ref shouldBe propagateRef
        } finally {
            PortIdentities.deriveRefs = previousDerive
        }
    }

    @Test
    fun `a Propagate-headed loop queues each lap, absorbs below threshold, and quiesces`() {
        val world = SimWorld()
        val letters = collectDeadLetters(world.host)
        val head = HeadCell(quiescence = 0.01, factor = 0.4)
        val relay = RelayCell()
        world.host.managementInlet.call.spawn(head)
        world.host.managementInlet.call.spawn(relay)
        world.host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        world.host.managementInlet.call
            .connect(relay.ref, "outlet", head.ref, "feedbackInput")
            .shouldBeInstanceOf<LinkResult.Connected>()

        head.outlet.originate { propagate(Delta(1.0)) }

        // A hosted feedback head is a fusion barrier: the first returning lap
        // has queued its re-origination rather than recursively running inline.
        head.laps shouldBe emptyList()
        val steps = world.runToIdle()
        (steps > 0) shouldBe true

        head.laps.isNotEmpty() shouldBe true
        head.laps.size shouldBe head.laps.count { it > 0.01 }
        (head.laps.size < 10) shouldBe true
        letters shouldBe emptyList()
    }

    @Test
    fun `a Propagate cycle closing on a plain inlet is rejected without a head`() {
        val world = SimWorld()
        val a = RelayCell()
        val b = RelayCell()
        world.host.managementInlet.call.spawn(a)
        world.host.managementInlet.call.spawn(b)
        world.host.managementInlet.call.connect(a.ref, "outlet", b.ref, "inlet")

        val rejected = world.host.managementInlet.call
            .connect(b.ref, "outlet", a.ref, "inlet")
            .shouldBeInstanceOf<LinkResult.Rejected>()

        rejected.reason shouldStartWith "CycleWithoutHead:"
    }

    @Test
    fun `Consumer and Propagate feedback shapes reject each other before install`() {
        val world = SimWorld()
        val consumerSource = ConsumerSource()
        val propagateSource = RelayCell()
        val propagateHead = HeadCell(quiescence = 0.0, factor = 1.0)
        val consumerHead = ConsumerHead()
        listOf(consumerSource, propagateSource, propagateHead, consumerHead).forEach {
            world.host.managementInlet.call.spawn(it)
        }

        val consumerToPropagate = world.host.managementInlet.call
            .connect(consumerSource.ref, "outlet", propagateHead.ref, "feedbackInput")
            .shouldBeInstanceOf<LinkResult.Rejected>()
        consumerToPropagate.reason shouldStartWith "payload mismatch:"
        consumerSource.outlet.linking.links.isEmpty() shouldBe true
        propagateHead.feedbackInput.linking.links.isEmpty() shouldBe true

        val propagateToConsumer = world.host.managementInlet.call
            .connect(propagateSource.ref, "outlet", consumerHead.ref, "feedbackInput")
            .shouldBeInstanceOf<LinkResult.Rejected>()
        propagateToConsumer.reason shouldStartWith "payload mismatch:"
        propagateSource.outlet.linking.links.isEmpty() shouldBe true
        consumerHead.feedbackInput.linking.links.isEmpty() shouldBe true
    }

    @Test
    fun `Propagate absorption mints a fresh wave and resets hop`() {
        var seenContext: MessageContext? = null
        val head = PropagateFeedbackInlet<Delta>(ref = PortRef.generate(), quiescence = 0.0) {
            seenContext = CurrentContext.get()
        }
        val incoming = MessageContext(Timestamp(UUID.randomUUID(), 5L), PortRef.generate(), hop = 7)

        CurrentContext.with(incoming) {
            head.call.propagate(Delta(1.0))
        }

        val seen = seenContext!!
        seen.hop shouldBe 0
        seen.sourcePort shouldBe head.ref
        (seen.timestamp.sourceId != incoming.timestamp.sourceId) shouldBe true
    }

    @Test
    fun `a Leased payload on a Propagate feedback inlet is rejected`() {
        val head = PropagateFeedbackInlet<Any>(quiescence = 0.0) { }

        val error = assertThrows<CycleError> { head.call.propagate(Leased(value = "x")) }

        error.message shouldBe
            "CycleRejectsLeased: Leased is forbidden on cycle edges (spec 20/23, 93 I-6); freeze() or copy first"
    }
}
