package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.port.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkResult
import civictech.cell.port.PortNatures
import civictech.cell.port.PortRef
import civictech.cell.port.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.InvocationSink
import civictech.gen.wire.MergeClass
import civictech.gen.wire.NatureAxis
import civictech.gen.wire.NatureMismatch
import civictech.gen.wire.NatureVector
import civictech.gen.wire.Ownership
import civictech.gen.wire.natureVectorFromWire
import civictech.gen.wire.toWire
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.test.Test

/**
 * CP-G2 — nature vectors cross the wire. `handshake()` already reconciles a
 * `counterpart` vector location-transparently (CP-F3), but every bridged caller
 * passed [NatureVector.DEFAULT], so a *genuine* cross-host nature mismatch went
 * undetected: both ends agreed only because each independently resolved the same
 * local descriptor. This test ships the endpoint's real vector — the producer's
 * across the wire in its `EdgeOpen` frame ([WireCodec]), the consumer's over the
 * reverse leg — and proves the mismatch is now a link-time [LinkResult.Rejected]
 * with the *same* [NatureMismatch] on both hosts, while the two controls diverge.
 */
class BridgedNatureRefusalTest {

    /** EdgeOpen for a connected bridged link has nowhere to land here; the verdict is what we assert. */
    private val nowhere = InvocationSink { }
    private val hostA = CellRef(UUID.randomUUID())
    private val hostB = CellRef(UUID.randomUUID())

    private fun outlet(natures: NatureVector) =
        FanOutlet.create<Propagate<Int>>().also { PortNatures.stamp(it, natures) }

    private fun inlet(natures: NatureVector) =
        FanInlet.create<Propagate<Int>>().also { PortNatures.stamp(it, natures) }

    /**
     * The producer's declared natures genuinely crossing the wire: encode an
     * `EdgeOpen` `PORT_PROTOCOL` frame carrying [natures], decode it, and read
     * the vector back off the reconstructed [WireEdgeLink] — exactly what a
     * receiving host's consumer feeds `bridgeFrom` as its counterpart.
     */
    private fun acrossTheWire(natures: NatureVector): NatureVector {
        val link = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(hostA), to = PortRef.generate(hostB),
            fromAddr = PortAddress(hostA, "outlet"), toAddr = PortAddress(hostB, "inlet"),
            natures = natures,
        )
        val bytes = WireCodec.encode(
            HostedPortInvocation(
                cellRef = hostB, portName = "inlet",
                type = HostedPortInvocation.Type.PORT_PROTOCOL,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.TopologyOrder,
                protocolLink = link,
                protocolMessage = EdgeOpen,
            ),
        )
        return (WireCodec.decode(bytes).protocolLink as WireEdgeLink).natures
    }

    @Test
    fun `a genuine cross-host nature mismatch is refused with the same typed mismatch on both sides`() {
        val producerNatures = NatureVector.DEFAULT                        // non-idempotent producer
        val consumerNatures = NatureVector.of(MergeClass.IDEMPOTENT)      // gossip-merge consumer

        // Producer side (host A): counterpart is the consumer's requirement.
        val producerVerdict = outlet(producerNatures).bridgeTo(
            selfAddr = PortAddress(hostA, "outlet"), toAddr = PortAddress(hostB, "inlet"),
            sink = nowhere, counterpart = consumerNatures,
        )
        // Consumer side (host B): counterpart is the producer's vector, as it
        // actually arrived across the wire.
        val consumerVerdict = inlet(consumerNatures).bridgeFrom(
            selfAddr = PortAddress(hostB, "inlet"), fromAddr = PortAddress(hostA, "outlet"),
            sink = nowhere, counterpart = acrossTheWire(producerNatures),
        )

        val local = producerVerdict.shouldBeInstanceOf<LinkResult.Rejected>()
        val remote = consumerVerdict.shouldBeInstanceOf<LinkResult.Rejected>()
        val expected = NatureMismatch(NatureAxis.MERGE_IDEMPOTENCE, MergeClass.NON_IDEMPOTENT, MergeClass.IDEMPOTENT)
        local.mismatch shouldBe expected
        remote.mismatch shouldBe expected  // location transparency: identical typed mismatch on both hosts
    }

    @Test
    fun `the mirrored-compatible edge links on both sides`() {
        // Producer now offers the level the consumer requires; a non-default
        // vector genuinely survives the wire (sparse-encoded, non-empty).
        val producerNatures = NatureVector.of(MergeClass.IDEMPOTENT)
        val consumerNatures = NatureVector.of(MergeClass.IDEMPOTENT)

        val producerVerdict = outlet(producerNatures).bridgeTo(
            selfAddr = PortAddress(hostA, "outlet"), toAddr = PortAddress(hostB, "inlet"),
            sink = nowhere, counterpart = consumerNatures,
        )
        val consumerVerdict = inlet(consumerNatures).bridgeFrom(
            selfAddr = PortAddress(hostB, "inlet"), fromAddr = PortAddress(hostA, "outlet"),
            sink = nowhere, counterpart = acrossTheWire(producerNatures),
        )

        // proof the non-default producer vector really rode the wire, not a local read
        acrossTheWire(producerNatures) shouldBe producerNatures
        producerVerdict.shouldBeInstanceOf<LinkResult.Connected>()
        consumerVerdict.shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `control (a) — counterpart forced to DEFAULT accepts the bad link the real vector refuses`() {
        val producerNatures = NatureVector.DEFAULT                   // non-idempotent producer
        val consumerNatures = NatureVector.of(MergeClass.IDEMPOTENT) // idempotent-required consumer

        // Real counterpart (today's follow-on wired up): the producer refuses.
        val refused = outlet(producerNatures).bridgeTo(
            selfAddr = PortAddress(hostA, "outlet"), toAddr = PortAddress(hostB, "inlet"),
            sink = nowhere, counterpart = consumerNatures,
        )
        // DEFAULT counterpart (today's caller): the producer silently accepts the
        // bad link — the deltas it later emits are dropped at the consumer with no
        // link-time signal. This is exactly the divergence CP-G2 closes.
        val accepted = outlet(producerNatures).bridgeTo(
            selfAddr = PortAddress(hostA, "outlet"), toAddr = PortAddress(hostB, "inlet"),
            sink = nowhere, // counterpart defaults to DEFAULT
        )

        refused.shouldBeInstanceOf<LinkResult.Rejected>()
        accepted.shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `control (b) — a peer that omits the natures field still links (additive default)`() {
        // A DEFAULT producer encodes zero natures bytes — the field is absent,
        // exactly as it would be from a peer that predates CP-G2.
        val link = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(hostA), to = PortRef.generate(hostB),
            fromAddr = PortAddress(hostA, "outlet"), toAddr = PortAddress(hostB, "inlet"),
            natures = NatureVector.DEFAULT,
        )
        val bytes = WireCodec.encode(
            HostedPortInvocation(
                cellRef = hostB, portName = "inlet",
                type = HostedPortInvocation.Type.PORT_PROTOCOL,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.TopologyOrder, protocolLink = link, protocolMessage = EdgeOpen,
            ),
        )
        bytes.decodeToString() shouldNotContain "natures"                  // sparse: zero bytes
        val received = (WireCodec.decode(bytes).protocolLink as WireEdgeLink).natures
        received shouldBe NatureVector.DEFAULT                             // absent ⇒ DEFAULT

        // A default consumer links against that absent vector — today's behavior.
        val verdict = inlet(NatureVector.DEFAULT).bridgeFrom(
            selfAddr = PortAddress(hostB, "inlet"), fromAddr = PortAddress(hostA, "outlet"),
            sink = nowhere, counterpart = received,
        )
        verdict.shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `nature vectors round-trip sparsely and ignore an unknown axis from a newer peer`() {
        // A declared multi-axis vector survives the flat int-pair encoding verbatim.
        val declared = NatureVector.of(MergeClass.IDEMPOTENT, Ownership.EXCLUSIVE)
        natureVectorFromWire(declared.toWire()) shouldBe declared

        // Forward compatibility: a newer peer names an axis (ordinal 99) this
        // build cannot resolve — it is ignored, never a refusal; known axes stay.
        val fromNewerPeer = listOf(99, 0) + NatureVector.of(MergeClass.IDEMPOTENT).toWire()
        natureVectorFromWire(fromNewerPeer) shouldBe NatureVector.of(MergeClass.IDEMPOTENT)
    }
}
