package civictech.demo.beadsmirror.e2e

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.demo.beadsmirror.DiscoveredIrohMirrorTransport
import civictech.demo.beadsmirror.IrohSidecarGate
import civictech.demo.beadsmirror.MulticastGate
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.iroh.IrohTransport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BS-09 of feature `computenet-63um5` (DSC2, epic `computenet-aas`; 63um5-D1,
 * 63um5-D4): "discovery killed after formation does not disconnect the
 * mesh" — SOC3 E1's precondition.
 *
 * [DiscoveredIrohMirrorTransport.stopDiscovery] detaches the discovery
 * policy but keeps the formed link (63um5-D1). There is no `UNWATCH` in the
 * sidecar protocol, so after the stop the sidecar keeps emitting
 * `PEER_DISCOVERED` for anything it still sees on the segment — this test
 * proves the JVM side of "stopped" holds anyway: a third `--offline --mdns`
 * advertiser appears on the same segment *after* the stop, and mutations on
 * both nodes still converge over the pre-existing link, with no new dial and
 * no new link.
 *
 * **A red run means:** a rig timeout naming the dialer, or non-convergence,
 * means [DiscoveredIrohMirrorTransport.stopDiscovery] did not actually keep
 * the link up (or closed it outright — the shape mutation (a) of this task's
 * bead produces); a `dialsAttempted`/link-count assertion failing without a
 * convergence failure means the detached policy re-dialled the stranger it
 * was handed after the stop, i.e. the detach did not truly stop the policy.
 * The admitted-link check right after formation failing with another rig's
 * `-listener` name means the binding peered across rigs — computenet-63um5.5's
 * defect, the cause of run 35629640485's timeout.
 *
 * **Loopback only, [DSC2-NV-01].** Both sidecars — the rig's two and the
 * stranger — run on one host, so a pass shows discovery over the host's
 * multicast loopback, not across a LAN segment with its own multicast
 * filtering. On a host that delivers no multicast at all the test SKIPs
 * ([MulticastGate]); the executed evidence is the `iroh-sidecar` CI lane.
 *
 * **The negative half (unchanged `dialsAttempted`/`eventsReceived`/link
 * count) is bounded by the convergence window below, stated as such per the
 * bead's design.** A `PEER_DISCOVERED` for the stranger that lands after that
 * window is not caught by these assertions; the positive convergence check is
 * what BS-09 actually asserts.
 */
class DiscoveryStoppedAfterFormationTest {

    private var rig: TwoNodeRig? = null

    @BeforeEach
    fun setUp() {
        IrohSidecarGate.orSkip()
        MulticastGate.deliveryOrSkip()
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    @AfterEach
    fun tearDown() {
        rig?.close()
    }

    @Test
    fun `BS-09 mutations converge over the links that outlived a stopped discovery policy`() {
        val transport = DiscoveredIrohMirrorTransport(binary = IrohSidecarGate.orSkip(), reconnectBackoff = { 10L })
        val theRig = TwoNodeRig.create("bds2-discovery-stopped", transport = transport)
        rig = theRig
        theRig.startListener()
        theRig.startDialer()

        // Formation by discovery: exactly one peered key, one admitted link,
        // and it is THIS rig's listener. Admitted links only: another
        // discovery rig on the segment (a parallel fork on CI) dials this
        // node too, and its refused, never-admitted link is briefly in
        // links() — run 35629640485 formed to that other rig's listener,
        // which the attribution check below names directly.
        val discovery = checkNotNull(transport.discovery) { "dial() must have formed a DiscoveredPeering by now" }
        val dialledNode = checkNotNull(transport.dialledNode) { "dial() must have a node by now" }
        val ownListener = PeerId("${theRig.rigName}-${MirrorCellRefs.LISTENER}")
        dialledNode.links().filter { it.peered }.map { it.attributedPeer } shouldBe listOf(ownListener)

        // Stop discovery — 63um5-D1's detach step. The link must survive.
        transport.stopDiscovery()
        // Read AFTER the stop (computenet-63um5.5): the dialling side admits
        // only this rig's listener, so another discovery rig's advertiser on
        // the segment (a parallel fork on CI) can still be in its
        // dial-refuse-abandon loop up to the moment of the stop. A dial the
        // running policy made before the stop is not what this asserts; a
        // dial after it is.
        val dialsBefore = discovery.counters.dialsAttempted.count
        val peeredAfterStop = discovery.snapshot().count { it.state.startsWith("Peered(") }
        peeredAfterStop shouldBe 1
        val eventsRightAfterStop = discovery.counters.eventsReceived.count

        // A third --offline --mdns advertiser appears on the segment during
        // the window that follows, so a real PEER_DISCOVERED reaches the
        // detached policy — the mechanism BS-09 pins from the outside (no
        // UNWATCH exists; DiscoveredPeering.post drops it because !running).
        var stranger: IrohTransport.IrohListener? = null
        try {
            val strangerRegistry = LocationRegistry()
            val strangerHost = ManagedHost(registry = strangerRegistry)
            val strangerSide = Peering.Side(
                strangerRegistry,
                strangerHost,
                peer = PeerId("bds2-discovery-stopped-stranger"),
            )
            stranger = IrohTransport.listen(
                strangerSide,
                IrohSidecarGate.orSkip(),
                sidecarArgs = listOf("--offline", "--mdns"),
            )

            // Mutate on BOTH nodes after the stop.
            val listenerIssue = theRig.createIssue(theRig.listener, "listener issue after discovery stopped")
            val dialerIssue = theRig.createIssue(theRig.dialer, "dialer issue after discovery stopped")
            theRig.mutate(theRig.listener, "update", listenerIssue, "--priority", "1")
            theRig.mutate(theRig.dialer, "update", dialerIssue, "--priority", "2")
            theRig.listener.quiesce()
            theRig.dialer.quiesce()

            // ---- the positive evidence: convergence over the surviving link.
            theRig.await("BS-09: folds converge over links that outlived discovery") {
                theRig.listener.view() == theRig.dialer.view() &&
                    theRig.listener.edgeView() == theRig.dialer.edgeView() &&
                    listenerIssue in theRig.listener.view() && listenerIssue in theRig.dialer.view() &&
                    dialerIssue in theRig.listener.view() && dialerIssue in theRig.dialer.view()
            }
        } finally {
            stranger?.let { runCatching { it.close() } }
        }

        // ---- the window-bounded negative: no re-dial, no new link, and no
        // discovery event was ever acted on after the stop.
        discovery.counters.dialsAttempted.count shouldBe dialsBefore
        discovery.counters.eventsReceived.count shouldBe eventsRightAfterStop
        dialledNode.links().filter { it.peered }.map { it.attributedPeer } shouldBe listOf(ownListener)
    }

    /** Copied from [ConvergenceSuite.checkPrerequisites]; private there. */
    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
