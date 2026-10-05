package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolId
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.BridgeIngressCell
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import civictech.cell.wire.defaultProtocolCapabilities
import civictech.testkit.awaitUntil
import civictech.testkit.dst.FrameInterposer
import civictech.testkit.dst.FrameInterposers
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random

/**
 * KE2-28/KE2-29 (spec 20/22): an [AlignedCompositeCell] whose `remote` arm
 * crosses the kernel's in-process frame bridge preserves the same frontier as
 * its local arm. The identity and absorbing-filter cases exercise the aligned
 * invariant, while point-consistent and no-`Progress` controls prove that the
 * harness can expose both tearing and a stranded remote wave.
 *
 * The Near-to-Far leg uses the protocol-only duplicator copied from
 * [civictech.cell.consistency.GlitchFreeBridgedDiamondTest]. It is a stressor,
 * not a discriminator: neutralising it can leave these invariants green. The
 * primitive's discriminating control remains `DuplicateFaultTest`; here the
 * invariant establishes idempotence when metadata redelivery does occur.
 */
class AlignedObserveBridgedTest {

    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private interface DeltaInlet {
        val inlet: Use<Propagate<SetDelta<Int>>>
    }

    private interface RemoteArm {
        val remote: Use<Propagate<SetDelta<Int>>>
    }

    private interface FrameInlet {
        val inlet: Use<Propagate<ByteArray>>
    }

    private enum class DuplicationScope { PROTOCOL_ONLY, ALL_FRAMES, NONE }

    /**
     * A private copy of `GlitchFreeBridgedDiamondTest.Net`, rather than an
     * extraction from that test. Its CHA1-61 outcome vector depends on the
     * exact RNG draw sequence, so sharing and extending the original harness
     * would silently change its pinned sample. This copy adds only [frames], a
     * record of frames actually delivered on either leg for the sibling
     * handle-parity proof.
     */
    private class Net(seed: Long, duplication: DuplicationScope = DuplicationScope.PROTOCOL_ONLY) {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val registryNear = LocationRegistry()
        val registryFar = LocationRegistry()
        val hostNear = ManagedHost(scheduler = controller.scheduler(), registry = registryNear)
        val hostC = ManagedHost(scheduler = controller.scheduler(), registry = registryNear)
        val hostFar = ManagedHost(scheduler = controller.scheduler(), registry = registryFar)

        val egressNF = BridgeEgressCell()
        val egressFN = BridgeEgressCell()

        /** `(frame type, protocol id)` in delivered order, across both legs. */
        val frames = mutableListOf<Pair<HostedPortInvocation.Type, ProtocolId?>>()

        init {
            val ingressNF = BridgeIngressCell(
                deliverTo = InvocationSink(registryFar::deliver),
                replySink = InvocationSink { egressFN.deliver(it) },
            )
            val ingressFN = BridgeIngressCell(
                deliverTo = InvocationSink(registryNear::deliver),
                replySink = InvocationSink { egressNF.deliver(it) },
            )
            hostFar.managementInlet.call.spawn(ingressNF)
            hostNear.managementInlet.call.spawn(ingressFN)

            val ingressNFApi = (HostedCellProxy.create(ingressNF.ref, registryFar, FrameInlet::class.java)
                as FrameInlet).inlet.call
            val duplicateProtocolFrame: FrameInterposer = FrameInterposers.duplicating(
                copies = 1,
                probability = 0.5,
                rng = rnd,
            )
            val protocolDuplicator = FrameInterposer { frame, step ->
                val duplicable = when (duplication) {
                    DuplicationScope.NONE -> false
                    DuplicationScope.ALL_FRAMES -> true
                    DuplicationScope.PROTOCOL_ONLY ->
                        WireCodec.decode(frame).type == HostedPortInvocation.Type.PORT_PROTOCOL
                }
                if (duplicable) duplicateProtocolFrame.apply(frame, step) else listOf(frame)
            }
            egressNF.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) {
                    protocolDuplicator.apply(value, 0).forEach { frame ->
                        record(frame)
                        ingressNFApi.propagate(frame)
                    }
                }
            }, PortRef.generate()))

            val ingressFNApi = (HostedCellProxy.create(ingressFN.ref, registryNear, FrameInlet::class.java)
                as FrameInlet).inlet.call
            egressFN.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) {
                    record(value)
                    ingressFNApi.propagate(value)
                }
            }, PortRef.generate()))
        }

        private fun record(frame: ByteArray) {
            val decoded = WireCodec.decode(frame)
            frames += decoded.type to decoded.protocolId
        }

        fun deltaProxyFor(ref: CellRef): DeltaInlet =
            HostedCellProxy.create(ref, InvocationSink(egressNF::deliver), DeltaInlet::class.java) as DeltaInlet

        fun remoteArmProxyFor(ref: CellRef): RemoteArm =
            HostedCellProxy.create(ref, InvocationSink(egressNF::deliver), RemoteArm::class.java) as RemoteArm

        fun nearDeltaProxy(ref: CellRef): DeltaInlet =
            HostedCellProxy.create(ref, registryNear, DeltaInlet::class.java) as DeltaInlet
    }

    private data class Recorded(
        val composites: List<Map<String, Any?>>,
        val unmatchedDeltas: Long,
        val bufferedWaves: Int,
        val heldWaves: Map<Timestamp, Set<DroppedEdge>>,
        val current: Map<String, Any?>,
        val frames: List<Pair<HostedPortInvocation.Type, ProtocolId?>>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun localArm(sink: AlignedCompositeCell): LinkFrom<Propagate<SetDelta<Int>>> =
        sink.inlets.getValue("local") as LinkFrom<Propagate<SetDelta<Int>>>

    private fun runAligned(
        seed: Long,
        waves: Int,
        remoteEven: Boolean,
        progressCapable: Boolean = true,
        duplication: DuplicationScope = DuplicationScope.PROTOCOL_ONLY,
    ): Recorded {
        val net = Net(seed, duplication)
        val source = SetCell<Int>()
        val local = FilterCell<Int> { true }
        val remote = FilterCell<Int> { value -> !remoteEven || value % 2 == 0 }
        val sink = AlignedCompositeCell(
            views = mapOf("local" to View.set<Int>(), "remote" to View.set<Int>()),
            registeredAs = mapOf("local" to "set", "remote" to "set"),
        )

        net.hostNear.managementInlet.call.spawn(source)
        net.hostFar.managementInlet.call.spawn(local)
        net.hostC.managementInlet.call.spawn(remote)
        net.hostFar.managementInlet.call.spawn(sink)
        net.controller.runToIdle()

        // Preserve the reference harness's A -> C, then A -> B subscription
        // order: C is queued on its independent near-side host, while B crosses
        // into the Far-host local arm.
        source.outlet.subscribe(Use.fixed(net.nearDeltaProxy(remote.ref).inlet.call, PortRef.generate()))
        source.outlet.subscribe(Use.fixed(net.deltaProxyFor(local.ref).inlet.call, PortRef.generate()))

        // Local arm -> aligned sink through an ordinary in-process handshake.
        (local.outlet.linkTo(localArm(sink)) is LinkResult.Connected).shouldBeTrue()

        // Remote arm -> named `remote` inlet: data and frontier metadata share
        // the full-duplex bridge, as they do in the bridged diamond harness.
        remote.outlet.subscribe(Use.fixed(net.remoteArmProxyFor(sink.ref).remote.call, PortRef.generate()))
        val capabilities = if (progressCapable) {
            defaultProtocolCapabilities()
        } else {
            defaultProtocolCapabilities() - Protocols.Progress
        }
        (remote.outlet.bridgeTo(
            selfAddr = PortAddress(remote.ref, "outlet"),
            toAddr = PortAddress(sink.ref, "remote"),
            sink = InvocationSink(net.egressNF::deliver),
            capabilities = capabilities,
        ) is LinkResult.Connected).shouldBeTrue()
        (sink.inlets.getValue("remote").bridgeFrom(
            selfAddr = PortAddress(sink.ref, "remote"),
            fromAddr = PortAddress(remote.ref, "outlet"),
            sink = InvocationSink(net.egressFN::deliver),
            capabilities = capabilities,
        ) is LinkResult.Connected).shouldBeTrue()
        net.controller.runToIdle()

        val recorded = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { recorded += it }

        val ops = net.hostNear.lookup<IntSetInlet>(source.ref)!!.inlet.call
        val rnd = Random(seed xor 0x5eed)
        for (n in 1..waves) {
            ops.add(n)
            repeat(rnd.nextInt(4)) { net.controller.step() }
        }
        net.controller.runToIdle()

        val expectedPublications = if (!progressCapable && remoteEven && waves % 2 == 1) waves else waves + 1
        awaitUntil("aligned composites delivered (seed $seed)") { recorded.size >= expectedPublications }
        val result = Recorded(
            composites = recorded.toList(),
            unmatchedDeltas = sink.unmatchedDeltas,
            bufferedWaves = sink.bufferedWaves,
            heldWaves = sink.heldWaves(),
            current = sink.current(),
            frames = net.frames.toList(),
        )
        sink.close()
        return result
    }

    /** The point-consistent control over the same local/remote arm topology. */
    private fun runControl(seed: Long, waves: Int): List<Map<String, Any?>> {
        val net = Net(seed)
        val source = SetCell<Int>()
        val local = FilterCell<Int> { true }
        val remote = FilterCell<Int> { true }
        val localView = ObserveCell(View.set<Int>())
        val remoteView = ObserveCell(View.set<Int>())

        net.hostNear.managementInlet.call.spawn(source)
        net.hostFar.managementInlet.call.spawn(local)
        net.hostC.managementInlet.call.spawn(remote)
        net.hostFar.managementInlet.call.spawn(localView)
        net.hostFar.managementInlet.call.spawn(remoteView)
        net.controller.runToIdle()

        source.outlet.subscribe(Use.fixed(net.nearDeltaProxy(remote.ref).inlet.call, PortRef.generate()))
        source.outlet.subscribe(Use.fixed(net.deltaProxyFor(local.ref).inlet.call, PortRef.generate()))
        @Suppress("UNCHECKED_CAST")
        val localViewInlet = localView.inlet as LinkFrom<Propagate<SetDelta<Int>>>
        (local.outlet.linkTo(localViewInlet) is LinkResult.Connected).shouldBeTrue()

        remote.outlet.subscribe(Use.fixed(net.deltaProxyFor(remoteView.ref).inlet.call, PortRef.generate()))
        (remote.outlet.bridgeTo(
            selfAddr = PortAddress(remote.ref, "outlet"),
            toAddr = PortAddress(remoteView.ref, "inlet"),
            sink = InvocationSink(net.egressNF::deliver),
        ) is LinkResult.Connected).shouldBeTrue()
        (remoteView.inlet.bridgeFrom(
            selfAddr = PortAddress(remoteView.ref, "inlet"),
            fromAddr = PortAddress(remote.ref, "outlet"),
            sink = InvocationSink(net.egressFN::deliver),
        ) is LinkResult.Connected).shouldBeTrue()
        net.controller.runToIdle()

        val view = CompositeSink(
            sinks = mapOf("local" to localView, "remote" to remoteView),
            registeredAs = mapOf("local" to "set", "remote" to "set"),
        )
        val recorded = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        view.onChange { recorded += it }

        val ops = net.hostNear.lookup<IntSetInlet>(source.ref)!!.inlet.call
        val rnd = Random(seed xor 0x5eed)
        for (n in 1..waves) {
            ops.add(n)
            repeat(rnd.nextInt(4)) { net.controller.step() }
        }
        net.controller.runToIdle()

        val settled = mapOf<String, Any?>("local" to (1..waves).toSet(), "remote" to (1..waves).toSet())
        awaitUntil("point-consistent control settles (seed $seed)") { view.current() == settled }
        val result = recorded.toList()
        view.close()
        localView.close()
        remoteView.close()
        return result
    }

    private fun evens(upTo: Int): Set<Int> = (1..upTo).filter { it % 2 == 0 }.toSet()

    private fun tears(composite: Map<String, Any?>): Boolean = composite["remote"] != composite["local"]

    @Test
    fun `KE2-28 - an identity remote arm publishes exactly the local prefix for every wave`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val run = runAligned(seed, waves, remoteEven = false)
            withClue(seed, run.current) {
                run.composites.size shouldBe waves + 1
                run.composites.forEachIndexed { i, composite ->
                    val prefix = (1..i).toSet()
                    composite["local"] shouldBe prefix
                    composite["remote"] shouldBe prefix
                }
                run.unmatchedDeltas shouldBe 0L
                run.bufferedWaves shouldBe 0
            }
        }
    }

    @Test
    fun `KE2-28 - an even-filtering remote arm publishes exactly the filtered local prefix`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val run = runAligned(seed, waves, remoteEven = true)
            withClue(
                seed,
                run.current,
                "unmatched=${run.unmatchedDeltas}",
                "buffered=${run.bufferedWaves}",
                "held=${run.heldWaves}",
            ) {
                run.composites.size shouldBe waves + 1
                run.composites.forEachIndexed { i, composite ->
                    composite["local"] shouldBe (1..i).toSet()
                    composite["remote"] shouldBe evens(i)
                }
                run.unmatchedDeltas shouldBe 0L
                run.bufferedWaves shouldBe 0
            }
        }
    }

    @Test
    fun `control - the same bridged topology tears under point-consistent observation`() {
        val mixedSeed = (0L until 50L).firstOrNull { seed ->
            runControl(seed, waves = 30).any(::tears)
        }
        (mixedSeed != null).shouldBeTrue()
    }

    @Test
    fun `KE2-29 - a final wave swallowed remotely settles through bridged Progress`() {
        val expected = mapOf<String, Any?>(
            "local" to (1..9).toSet(),
            "remote" to setOf(2, 4, 6, 8),
        )
        for (seed in 0L until 30L) {
            val run = runAligned(seed, waves = 9, remoteEven = true)
            withClue(seed, run.current) {
                run.current shouldBe expected
                run.bufferedWaves shouldBe 0
            }
        }
    }

    @Test
    fun `control - without negotiated Progress the final remote wave remains buffered`() {
        val run = runAligned(seed = 0, waves = 9, remoteEven = true, progressCapable = false)
        withClue(run.current) {
            (run.bufferedWaves >= 1).shouldBeTrue()
            ((run.current.getValue("local") as Set<*>).contains(9)) shouldBe false
        }
    }

    private inline fun <T> withClue(vararg clue: Any?, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("clue=${clue.toList()} :: ${e.message}", e)
        }
}
