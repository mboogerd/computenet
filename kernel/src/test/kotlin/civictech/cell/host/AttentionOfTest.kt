package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * V2-KERNEL — [ManagedHost.attentionOf], the read-only attention-band accessor.
 *
 * The band is the one piece of a cell's scheduling state that had no reader at
 * all: it lives on the cell object, behind the host's private `cells` map, and
 * the host consulted it internally (`bandOf`) while exposing nothing. An
 * out-of-kernel observer therefore had to report "unknown" permanently.
 *
 * The delicate half is that reading it must stay a *read* (P6 — observation is
 * causal, and browsing is not an observation). [AttentionSupport.of] lazily
 * **creates and wires** a support object for a cell that has none, installing
 * protocol handlers and link listeners as a side effect; [AttentionSupport.refresh]
 * and [AttentionSupport.attend] recompute, which can change the band, fire
 * listeners, and push attention up the whole cone. This accessor must do none
 * of that, and these tests assert it rather than trusting the KDoc.
 */
class AttentionOfTest {

    /** One linkable hop: `outlet` → the next stage's `inlet`. */
    private class Stage(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.serve(object : Propagate<String> {
                override fun propagate(value: String) = outlet.call.propagate(value)
            })
        }
    }

    private class Fixture(policy: AttentionPolicy?) {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler(), attention = policy)
        val producer = Stage()
        val consumer = Stage()

        init {
            host.managementInlet.call.spawn(producer)
            host.managementInlet.call.spawn(consumer)
            controller.runToIdle()
        }

        fun link() {
            host.managementInlet.call.connect(producer.ref, "outlet", consumer.ref, "inlet")
            controller.runToIdle()
        }

        /**
         * Count `Attention` protocol messages arriving at the producer's outlet —
         * i.e. every band the consumer pushes up the cone. Installed on the
         * producer's own [ProtocolSupport], which is where
         * `AttentionSupport.wire()` would deliver them.
         */
        fun countUpstreamAttention(): () -> Int {
            var count = 0
            ProtocolSupport.of(producer.outlet).handle(Protocols.Attention) { _, _ -> count++ }
            return { count }
        }
    }

    // --------------------------------------------------------------- the value

    @Test
    fun `with an attention policy the accessor reports the cell's current band`() {
        val f = Fixture(AttentionPolicy())

        // neutral: "nobody said anything" is a real band, not a missing one
        f.host.attentionOf(f.consumer.ref) shouldBe AttentionBand.NORMAL
    }

    @Test
    fun `the accessor follows the band as it changes`() {
        val f = Fixture(AttentionPolicy())
        f.link()

        AttentionSupport.of(f.consumer).attend(1f)
        f.host.attentionOf(f.consumer.ref) shouldBe AttentionBand.HIGH
        // and it follows the propagated band upstream, not only the declared one
        f.host.attentionOf(f.producer.ref) shouldBe AttentionBand.HIGH

        AttentionSupport.of(f.consumer).attend(0f)
        f.host.attentionOf(f.consumer.ref) shouldBe AttentionBand.NONE
        f.host.attentionOf(f.producer.ref) shouldBe AttentionBand.NONE

        AttentionSupport.of(f.consumer).attend(0.25f)
        f.host.attentionOf(f.consumer.ref) shouldBe AttentionBand.LOW
    }

    // ------------------------------------------------------ null, never a guess

    @Test
    fun `a ref this host does not hold reads null`() {
        val f = Fixture(AttentionPolicy())

        f.host.attentionOf(CellRef(UUID.randomUUID())).shouldBeNull()

        // including a cell that is real, and hosted — elsewhere
        val elsewhere = Fixture(AttentionPolicy())
        f.host.attentionOf(elsewhere.consumer.ref).shouldBeNull()
        elsewhere.host.attentionOf(elsewhere.consumer.ref) shouldBe AttentionBand.NORMAL
    }

    @Test
    fun `a host without an attention policy reads null, rather than inventing NORMAL`() {
        val f = Fixture(policy = null)

        // scheduling here is plain FIFO: no band is in effect for this cell at
        // all, so NORMAL would be a scheduling claim the host cannot make
        f.host.attentionOf(f.consumer.ref).shouldBeNull()
        f.host.attentionOf(f.producer.ref).shouldBeNull()
    }

    // ------------------------------------------------------------- P6: the read

    /**
     * The lazy-wiring trap. Without the `attention == null` gate the accessor
     * would call [AttentionSupport.of], which **creates and wires** support for a
     * cell that has none: protocol handlers on every linked port, an unlink
     * listener, and a link listener that emits this cell's band upstream the
     * moment an inbound link forms. A read would then have permanently changed
     * how the cell behaves.
     *
     * [ProtocolSupport.handles] is the direct observable — `wire()` is the only
     * thing in the kernel that installs an `Attention` handler on a plain cell's
     * port — and the link is the causal one: had the read wired the consumer,
     * connecting to it would push a band up to the producer.
     */
    @Test
    fun `reading on a policy-less host never lazily wires attention support`() {
        val f = Fixture(policy = null)
        val upstreamAttention = f.countUpstreamAttention()
        ProtocolSupport.of(f.consumer.inlet).handles(Protocols.Attention) shouldBe false

        repeat(10) { f.host.attentionOf(f.consumer.ref).shouldBeNull() }

        // no support object was created, so nothing was wired onto the cell
        ProtocolSupport.of(f.consumer.inlet).handles(Protocols.Attention) shouldBe false
        // and the cone stays silent when a link forms afterwards
        f.link()
        upstreamAttention() shouldBe 0
    }

    /**
     * P6 on the path where a support object *does* exist (the host wired one at
     * spawn): repeated reads must leave the band, its listeners and its upstream
     * links exactly as they were. This is what a browsing observer does — it
     * reads the band of every cell on screen — and it must never be the thing
     * that raises attention on the graph it is looking at.
     */
    @Test
    fun `repeated reads raise no attention, change no band, and emit nothing upstream`() {
        val f = Fixture(AttentionPolicy())
        f.link()
        val upstreamAttention = f.countUpstreamAttention()
        var bandChanges = 0
        AttentionSupport.of(f.consumer).onBandChange { bandChanges++ }
        val before = f.host.attentionOf(f.consumer.ref)

        repeat(50) {
            f.host.attentionOf(f.consumer.ref)
            f.host.attentionOf(f.producer.ref)
        }
        f.controller.runToIdle()

        f.host.attentionOf(f.consumer.ref) shouldBe before
        f.host.attentionOf(f.producer.ref) shouldBe AttentionBand.NORMAL
        bandChanges shouldBe 0
        upstreamAttention() shouldBe 0
        // and nothing was enqueued on the host by the reads
        f.controller.step() shouldBe false
    }

    /**
     * The same guarantee stated as a control: the instruments above *do* fire
     * when attention is genuinely raised. Without this, the P6 test could pass
     * because the observables are dead.
     */
    @Test
    fun `control - genuinely raising attention does fire what the reads leave alone`() {
        val f = Fixture(AttentionPolicy())
        f.link()
        val upstreamAttention = f.countUpstreamAttention()
        var bandChanges = 0
        AttentionSupport.of(f.consumer).onBandChange { bandChanges++ }

        AttentionSupport.of(f.consumer).attend(1f)

        bandChanges shouldBe 1
        upstreamAttention() shouldBe 1
        f.host.attentionOf(f.consumer.ref) shouldBe AttentionBand.HIGH
    }
}
