package civictech.demo.beadsmirror.e2e

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.demo.beadsmirror.DiscoveredIrohMirrorTransport
import civictech.demo.beadsmirror.IrohSidecarGate
import civictech.demo.beadsmirror.MulticastGate
import civictech.iroh.IrohTransport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
 *
 * **One discovery rig on the segment at a time ([DiscoverySegmentLock]).**
 * The class holds the segment lock from its first test to its last, as every
 * discovery-rig class must; see the lock's KDoc for the failure it prevents.
 */
class DiscoveryStoppedAfterFormationTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun lockSegment() = DiscoverySegmentLock.acquire()

        @JvmStatic
        @AfterAll
        fun unlockSegment() = DiscoverySegmentLock.release()
    }

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

        // Formation by discovery: exactly one peered key, one link.
        val discovery = checkNotNull(transport.discovery) { "dial() must have formed a DiscoveredPeering by now" }
        val dialledNode = checkNotNull(transport.dialledNode) { "dial() must have a node by now" }
        val dialsBefore = discovery.counters.dialsAttempted.count
        dialledNode.links().size shouldBe 1

        // Stop discovery — 63um5-D1's detach step. The link must survive.
        transport.stopDiscovery()
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

            // Mutate on BOTH nodes after the stop. Neither priority may be 2,
            // bd's default (computenet-63um5.5): `update --priority 2` on a
            // fresh issue changes no `issues` row when it lands in the same
            // second as the create, so its commit touches only `events`. The
            // poller advances its checkpoint only past commits that carry an
            // issue or edge record, so `quiesce()` (checkpoint == head) then
            // waits out its whole window on a head it can never reach.
            val listenerIssue = theRig.createIssue(theRig.listener, "listener issue after discovery stopped")
            val dialerIssue = theRig.createIssue(theRig.dialer, "dialer issue after discovery stopped")
            theRig.mutate(theRig.listener, "update", listenerIssue, "--priority", "1")
            theRig.mutate(theRig.dialer, "update", dialerIssue, "--priority", "3")
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
        dialledNode.links().size shouldBe 1
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

/**
 * A cross-process lock on "the multicast segment", held class-wide by every
 * `:demo:beadsmirror` test class that forms a rig by mDNS discovery
 * ([DiscoveryStoppedAfterFormationTest], [DiscoveredIrohConvergenceSuiteTest]),
 * so that no two of them run at once (computenet-63um5.5).
 *
 * **Why.** This module's tests run on parallel forks (`maxParallelForks` in
 * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`: 2 on a 4-vCPU CI
 * runner), and every `--offline --mdns` sidecar on the host is discovered by
 * every other. [DiscoveredIrohMirrorTransport]'s dialling end admits any
 * advertiser — the rig's side has `allow = null`, and the one allowlist that
 * would narrow it names the listener's key, which [DSC2-NEU-02] forbids
 * handing over — so two rigs running at once cross-peer. The rig names
 * differ, a cross-rig link carries no matching `CellRef`, and the rig sits at
 * `+0` for its whole convergence window: iroh-sidecar run 35629640485 (both
 * attempts) failed BS-09 exactly so, with the suite's partition case running
 * alongside in the other fork. Reproduced in a Linux container with both
 * classes and no lock: 5 of 5 runs red, both classes.
 *
 * **Scope of the guarantee.** A [FileChannel.lock] serialises holders across
 * JVMs through the file under `java.io.tmpdir`, which is one directory for
 * every fork of one test task. It does not serialise against a discovery
 * sidecar that takes no lock (another module's test, another task with a
 * different `java.io.tmpdir`); within one JVM a second [acquire] before
 * [release] fails loudly rather than deadlocking.
 */
internal object DiscoverySegmentLock {

    private val path: Path =
        Path.of(System.getProperty("java.io.tmpdir"), "computenet-beadsmirror-mdns-segment.lock")

    private var held: Pair<FileChannel, FileLock>? = null

    /** Blocks until no other process holds the segment. */
    @Synchronized
    fun acquire() {
        check(held == null) { "the mDNS segment lock is already held in this JVM" }
        val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        held = try {
            channel to channel.lock()
        } catch (e: Throwable) {
            runCatching { channel.close() }
            throw e
        }
    }

    /** Idempotent; closing the channel releases the lock even if [FileLock.release] failed. */
    @Synchronized
    fun release() {
        held?.let { (channel, lock) ->
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
        held = null
    }
}
