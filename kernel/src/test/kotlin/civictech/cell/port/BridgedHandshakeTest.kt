package civictech.cell.port

import civictech.cell.Consumer
import civictech.gen.wire.MergeClass
import civictech.gen.wire.NatureVector
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.test.Test

/**
 * CP-F3: the bridged handshake runs the *same* pure [NatureNegotiation.reconcile]
 * as a local link, so the verdict is **location-transparent** — the consumer's
 * host and the producer's host reach the identical result for one logical edge
 * (riding CP-A2's bridged handshake). Because reconcile is a pure total
 * function of `(offered, required)`, feeding both endpoints the same vector pair
 * makes `localVerdict == remoteVerdict` hold by construction.
 */
class BridgedHandshakeTest {

    private fun fakeLink(from: PortRef, to: PortRef): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from: PortRef = from
        override val to: PortRef = to
        override fun unlink() {}
    }

    @Test
    fun `both endpoints of one bridged edge reach the same refusal verdict`() {
        // One logical edge: an idempotent-required consumer fed by a
        // non-idempotent producer. The producer's host runs the producer side
        // of the handshake; the consumer's host runs the consumer side.
        val producerNatures = NatureVector.DEFAULT                       // non-idempotent
        val consumerNatures = NatureVector.of(MergeClass.IDEMPOTENT)      // gossip merge

        // Consumer side (fireEdgeOpen = false): local is the inlet (required),
        // the peer's vector arrives as counterpart (offered).
        val consumerPort = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(consumerPort, consumerNatures)
        val consumerVerdict = handshake(
            fakeLink(PortRef.generate(), consumerPort.ref),
            from = PortRef.generate(),
            targetRef = consumerPort.ref,
            local = consumerPort,
            fireEdgeOpen = false,
            counterpart = producerNatures,
        )

        // Producer side (fireEdgeOpen = true): local is the outlet (offered),
        // the peer's vector arrives as counterpart (required).
        val producerPort = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(producerPort, producerNatures)
        val producerVerdict = handshake(
            fakeLink(producerPort.ref, PortRef.generate()),
            from = producerPort.ref,
            targetRef = PortRef.generate(),
            local = producerPort,
            fireEdgeOpen = true,
            counterpart = consumerNatures,
        )

        val remote = consumerVerdict.shouldBeInstanceOf<LinkResult.Rejected>()
        val local = producerVerdict.shouldBeInstanceOf<LinkResult.Rejected>()
        // location transparency: identical typed mismatch on both hosts
        local.mismatch shouldBe remote.mismatch
    }

    @Test
    fun `both endpoints agree even when the two vectors genuinely differ`() {
        // CP-G2: pre-G2 both callers passed DEFAULT, so localVerdict == remoteVerdict
        // held trivially (identical inputs). Here the producer offers a *stronger*
        // level than the consumer requires — the vectors truly differ — yet the pure
        // reconcile still lands the same verdict (Connected) on both hosts.
        val producerNatures = NatureVector.of(MergeClass.IDEMPOTENT)  // stronger
        val consumerNatures = NatureVector.DEFAULT                    // non-idempotent requirement

        val consumerPort = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(consumerPort, consumerNatures)
        val consumerVerdict = handshake(
            fakeLink(PortRef.generate(), consumerPort.ref),
            from = PortRef.generate(),
            targetRef = consumerPort.ref,
            local = consumerPort,
            fireEdgeOpen = false,
            counterpart = producerNatures,
        )

        val producerPort = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(producerPort, producerNatures)
        val producerVerdict = handshake(
            fakeLink(producerPort.ref, PortRef.generate()),
            from = producerPort.ref,
            targetRef = PortRef.generate(),
            local = producerPort,
            fireEdgeOpen = true,
            counterpart = consumerNatures,
        )

        // location transparency with genuinely different vectors: both Connected
        consumerVerdict.shouldBeInstanceOf<LinkResult.Connected>()
        producerVerdict.shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `a satisfied bridged edge connects on both endpoints`() {
        val bothIdempotent = NatureVector.of(MergeClass.IDEMPOTENT)

        val consumerPort = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(consumerPort, bothIdempotent)
        val consumerVerdict = handshake(
            fakeLink(PortRef.generate(), consumerPort.ref),
            from = PortRef.generate(),
            targetRef = consumerPort.ref,
            local = consumerPort,
            fireEdgeOpen = false,
            counterpart = bothIdempotent,
        )

        consumerVerdict.shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `a default bridged edge is unaffected (zero behavior change)`() {
        val port = FanInlet.create<Consumer<String>>()
        val verdict = handshake(
            fakeLink(PortRef.generate(), port.ref),
            from = PortRef.generate(),
            targetRef = port.ref,
            local = port,
            fireEdgeOpen = false,
            // counterpart defaults to DEFAULT — today's bridged callers
        )
        verdict.shouldBeInstanceOf<LinkResult.Connected>()
    }
}
