package civictech.iroh.discover

import civictech.cell.link.PeerId
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.LinkDirection
import civictech.iroh.MulticastGate
import civictech.iroh.SidecarBinary
import civictech.iroh.SidecarClient
import civictech.iroh.SidecarProcess
import civictech.iroh.SidecarProtocol
import civictech.iroh.await
import civictech.iroh.quiesced
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * **BS-08 over REAL sidecars** (`computenet-md1dt`, epic `computenet-aas`
 * aas-D7, `[DSC2-DIAL-05]`): two `--offline --mdns` sidecar processes discover
 * each other over real mDNS, **both** dial, both QUIC connections come up, and
 * the two [DiscoveredPeering] policies end with exactly one live peering — the
 * smaller NodeId's OUTBOUND link, which is the larger one's INBOUND link — with
 * no blame on either side.
 *
 * [MutualDialTest] is this test's fake twin: it proves the POLICY over a
 * hand-relayed rig in three chosen orders. What it cannot prove is that two
 * real iroh endpoints racing each other produce the state that policy
 * arbitrates over — two live connections for one key — and that is this file.
 *
 * ## The race is FORCED, not sampled
 *
 * Left alone, the race is a coin flip in the wrong direction: the first node
 * to see a `PEER_DISCOVERED` dials, its link is usually up on the other node
 * before that node has discovered anything, and the late discovery is then
 * `Suppressed` (the key already holds a link) — so only ONE side ever dials
 * and a tie-break test would pass without a tie-break ever happening.
 *
 * So each node's [SidecarClient] talks to its sidecar through a
 * [DialHoldingProxy]: a byte-for-byte TCP relay on loopback that parses only
 * the host→sidecar framing (`PROTOCOL.md` §2: a 4-byte length, then kind,
 * link, payload) and **holds the first `DIAL` naming the other node** on a
 * shared two-party [DialBarrier]. Neither `DIAL` reaches its sidecar until
 * BOTH policies have issued one, and then both are released together. Since
 * no link exists anywhere until a `DIAL` reaches a sidecar, neither node can
 * have its late discovery suppressed: every trial is a genuine mutual dial,
 * by construction, whatever order mDNS delivers the sightings in. Everything
 * after the release — the two QUIC handshakes, which `LINK_UP` and which
 * hello lands first on each side — is the real, unordered race.
 *
 * Three release orders, one test each, the same end state asserted from all
 * three (the real-sidecar counterparts of [MutualDialTest]'s three relay
 * orders): [Release.TOGETHER], both `DIAL`s released at once; and
 * [Release.LO_FIRST] / [Release.HI_FIRST], where one `DIAL` is released,
 * its link is admitted at the far node, and only then is the other — already
 * issued, still held — let through. HI_FIRST is the hardest: the larger id's
 * link is already PEERED at the smaller id when the smaller id's outbound
 * link arrives and must displace it.
 *
 * Why the staggered orders are forced too: releasing both at once and letting
 * the network order them was observed to produce the staggered shapes only
 * sometimes, and it also produced — in one unforced sample in a Linux
 * container — a `send failed: connection lost` link error on the larger id's
 * losing link. See `doc/distribution/findings.md`, the 2026-09-22 DSC2 BS-08
 * entry.
 *
 * ## And both sides are PROVEN to have dialled, in every trial
 *
 * The construction is not taken on trust. Each trial asserts, from the proxy
 * that saw the bytes: the barrier paired (both `DIAL`s were held and released
 * together, never one on a timeout); exactly one `DIAL` to the peer crossed
 * each proxy, then and after quiescence (the loser is never re-dialled); and
 * each node counted exactly one tie-break close on
 * [DiscoveryCounters.tieBreakClosed] — which moves only when that node held
 * BOTH directions of the key, i.e. only when both dials really produced a
 * connection. A run in which one side never dialled fails the first of those;
 * one where iroh folded the two dials into one connection fails the last.
 *
 * Nothing sleeps on the policy: both nodes run a [ManualTimer] over a frozen
 * clock, so no retry is ever released and a second `DIAL` could only come from
 * a re-dial on link drop — which is exactly what the proxy count rules out.
 *
 * ## Platform
 *
 * Gated twice, in order: [SidecarBinary.orSkip] (no `-Piroh.enabled=true`,
 * so the default lanes report SKIPPED) then [MulticastGate.deliveryOrSkip]
 * (the host does not deliver multicast — true on macOS, `ne2oh-B6`). A
 * discovery timeout is a SKIP only when a sidecar's own stderr said mDNS could
 * not bind ([MulticastGate.reasonFromStderr]); anything else is a hard FAIL
 * (`[DSC2-NV-01]`). Loopback and `--offline` only: this proves the tie-break
 * between two real endpoints on ONE host, nothing about a real LAN.
 */
class MutualDialSidecarTest {

    /**
     * Two parties, one release. [arrive] blocks until both have arrived (or
     * [HOLD_SECONDS] pass) and says which. [open] releases a party still
     * waiting when a trial is torn down, so no relay thread outlives it.
     *
     * [heldBack], when set, names a party that — after both have arrived —
     * waits a second time, on [releaseHeldBack], so the test can let the
     * other party's link come up and be admitted first. Both `DIAL`s have
     * still been ISSUED before any link exists; only the order in which they
     * reach their sidecars is chosen.
     */
    private class DialBarrier(val heldBack: String? = null) {
        private val latch = CountDownLatch(2)
        private val second = CountDownLatch(1)
        val arrivals = CopyOnWriteArrayList<String>()

        fun arrive(label: String): Boolean {
            arrivals += label
            latch.countDown()
            val paired = latch.await(HOLD_SECONDS, TimeUnit.SECONDS)
            if (paired && label == heldBack) second.await(HOLD_SECONDS, TimeUnit.SECONDS)
            return paired
        }

        fun releaseHeldBack() = second.countDown()

        fun open() {
            while (latch.count > 0) latch.countDown()
            second.countDown()
        }
    }

    /** Which `DIAL` reaches its sidecar first, once both have been issued. */
    private enum class Release(val heldBack: String?) {
        /** Both released together; the QUIC handshakes race freely. */
        TOGETHER(null),

        /** The smaller id's link is up and admitted at the larger before the larger's `DIAL` leaves its proxy. */
        LO_FIRST("hi"),

        /** The mirror: the larger id's link is admitted first, and the smaller id's later OUTBOUND link must replace it. */
        HI_FIRST("lo"),
    }

    /**
     * A loopback relay between one [SidecarClient] and one sidecar's host
     * socket. Sidecar→host bytes are copied untouched. Host→sidecar bytes are
     * read frame by frame; the first `DIAL` whose payload is [peer] waits on
     * [barrier] before it is written, and every `DIAL` to [peer] is counted.
     * Every other frame, including a `DIAL` to anyone else, passes straight
     * through.
     */
    private class DialHoldingProxy(
        private val label: String,
        private val sidecarPort: Int,
        private val peer: ByteArray,
        private val barrier: DialBarrier,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        /** `DIAL`s naming [peer] that this relay forwarded. */
        val dialsToPeer = AtomicInteger()

        /** `DIAL`s naming anyone else — a stray advertiser on the segment. Reported, not held. */
        val otherDials = AtomicInteger()

        /** Null until the held `DIAL` is released; then whether it was released as a pair. */
        @Volatile
        var releasedPaired: Boolean? = null

        private val sockets = CopyOnWriteArrayList<Socket>()
        private val closed = AtomicBoolean(false)

        init {
            thread("accept") {
                val host = server.accept().also { sockets += it; it.tcpNoDelay = true }
                val sidecar = Socket(InetAddress.getLoopbackAddress(), sidecarPort).also { sockets += it; it.tcpNoDelay = true }
                thread("down") { copy(sidecar.getInputStream(), host.getOutputStream()) }
                thread("up") { relayUp(DataInputStream(host.getInputStream()), sidecar.getOutputStream()) }
            }
        }

        private fun thread(what: String, body: () -> Unit) {
            Thread({ runCatching(body) }, "md1dt-proxy-$label-$what").apply { isDaemon = true }.start()
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
            runCatching { output.close() }
        }

        private fun relayUp(input: DataInputStream, output: OutputStream) {
            val prefix = ByteArray(SidecarProtocol.LENGTH_PREFIX_LEN)
            while (true) {
                input.readFully(prefix)
                val bodyLen = ((prefix[0].toInt() and 0xff) shl 24) or ((prefix[1].toInt() and 0xff) shl 16) or
                    ((prefix[2].toInt() and 0xff) shl 8) or (prefix[3].toInt() and 0xff)
                check(bodyLen in SidecarProtocol.MSG_HEADER_LEN..SidecarProtocol.MAX_MESSAGE_LEN) {
                    "$label: host wrote an out-of-range length $bodyLen"
                }
                val body = ByteArray(bodyLen)
                input.readFully(body)
                if (body[0] == SidecarProtocol.Kind.DIAL) {
                    val target = body.copyOfRange(SidecarProtocol.MSG_HEADER_LEN, bodyLen)
                    if (target.contentEquals(peer)) {
                        if (dialsToPeer.get() == 0) releasedPaired = barrier.arrive(label)
                        dialsToPeer.incrementAndGet()
                    } else {
                        otherDials.incrementAndGet()
                    }
                }
                output.write(prefix)
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            barrier.open()
            sockets.forEach { runCatching { it.close() } }
            runCatching { server.close() }
        }
    }

    /** One endpoint: a real sidecar process, reached through its proxy, with a policy on a frozen clock. */
    private class Endpoint(
        val label: String,
        val process: SidecarProcess,
        val proxy: DialHoldingProxy,
        val node: IrohNode,
        val peering: DiscoveredPeering,
        val timer: ManualTimer,
        val side: civictech.cell.wire.Peering.Side,
    ) : AutoCloseable {
        val own: ByteArray get() = process.nodeId

        fun viewOf(key: ByteArray): PeerView? = peering.snapshot().firstOrNull { it.keyHex == NodeKey(key).hex }

        override fun close() {
            runCatching { peering.close() }
            runCatching { node.close() }
            runCatching { proxy.close() }
            runCatching { process.close() }
        }
    }

    private val stderr = CopyOnWriteArrayList<String>()

    private fun sink(label: String): (String) -> Unit = { line ->
        stderr += line
        println("[iroh-stderr $label] $line")
    }

    private fun endpoint(label: String, process: SidecarProcess, peer: ByteArray, barrier: DialBarrier): Endpoint {
        val proxy = DialHoldingProxy(label, process.port, peer, barrier)
        val side = sideWith(peer = PeerId(label))
        val client = SidecarClient.connect(proxy.port)
        val sidecar = object : IrohTransport.Sidecar {
            override val nodeId: ByteArray get() = process.nodeId
            override fun close() = process.close()
        }
        val node = IrohNode(sidecar, client, side)
        node.start(30.seconds)
        val timer = ManualTimer { 0L }
        // A dial is held at the proxy for up to HOLD_SECONDS; its own timeout
        // must outlast that, or the hold would read as a failed dial.
        val peering = DiscoveredPeering.start(
            node,
            DialPolicy(dialTimeout = (HOLD_SECONDS + 30).seconds),
            clock = { 0L },
            timer = timer,
        )
        return Endpoint(label, process, proxy, node, peering, timer, side)
    }

    /** Wait for [label]'s policy to have issued its `DIAL` to the peer; a timeout is a SKIP only on the sidecar's own mDNS report. */
    private fun awaitDialOrSkip(barrier: DialBarrier, label: String, what: String) {
        val deadline = System.currentTimeMillis() + (HOLD_SECONDS + 15) * 1_000
        while (label !in barrier.arrivals) {
            if (System.currentTimeMillis() >= deadline) {
                MulticastGate.reasonFromStderr(stderr)?.let { reason -> assumeTrue(false) { reason } }
                fail("$what within ${HOLD_SECONDS + 15}s and no sidecar reported an mDNS problem")
            }
            Thread.sleep(50)
        }
    }

    private fun trial(binary: java.nio.file.Path, release: Release, n: Int) {
        val t = "$release trial $n"
        val args = listOf("--offline", "--mdns")
        val p1 = SidecarProcess.spawn(binary, stderrSink = sink("$n-1"), args = args)
        val p2 = try {
            SidecarProcess.spawn(binary, stderrSink = sink("$n-2"), args = args)
        } catch (e: Throwable) {
            p1.close()
            throw e
        }
        // `lo` is the endpoint with the smaller NodeId (unsigned), so aas-D7
        // gives it the OUTBOUND link and `hi` the INBOUND one — stated as facts
        // below rather than as a case analysis.
        val (loP, hiP) = if (Arrays.compareUnsigned(p1.nodeId, p2.nodeId) < 0) p1 to p2 else p2 to p1
        val barrier = DialBarrier(heldBack = release.heldBack)
        val opened = mutableListOf<AutoCloseable>(loP, hiP)
        try {
            val lo = endpoint("lo", loP, hiP.nodeId, barrier).also { opened.add(0, it) }
            val hi = endpoint("hi", hiP, loP.nodeId, barrier).also { opened.add(0, it) }

            // ---- both sides dialled: the forced race, and its proof.
            awaitDialOrSkip(barrier, "lo", "$t: lo never dialled hi")
            awaitDialOrSkip(barrier, "hi", "$t: hi never dialled lo")
            when (release) {
                Release.TOGETHER -> Unit
                Release.LO_FIRST -> {
                    await("$t: lo's link to be admitted at hi before hi's DIAL is released") {
                        hi.node.links(lo.own).any { it.peered && it.direction == LinkDirection.INBOUND }
                    }
                    barrier.releaseHeldBack()
                }
                Release.HI_FIRST -> {
                    await("$t: hi's link to be admitted at lo before lo's DIAL is released") {
                        lo.node.links(hi.own).any { it.peered && it.direction == LinkDirection.INBOUND }
                    }
                    barrier.releaseHeldBack()
                }
            }
            await("$t: both DIALs to have crossed their proxies") {
                lo.proxy.dialsToPeer.get() >= 1 && hi.proxy.dialsToPeer.get() >= 1
            }
            assertEquals(true, lo.proxy.releasedPaired, "$t: lo's DIAL was released as one of a pair (arrivals ${barrier.arrivals})")
            assertEquals(true, hi.proxy.releasedPaired, "$t: hi's DIAL was released as one of a pair (arrivals ${barrier.arrivals})")

            // ---- one peering each way, on the same physical link.
            await("$t: lo to count its tie-break close") { lo.peering.counters.tieBreakClosed.count >= 1L }
            await("$t: hi to count its tie-break close") { hi.peering.counters.tieBreakClosed.count >= 1L }
            await("$t: lo to hold exactly one link, peered") { lo.node.links(hi.own).let { it.size == 1 && it.single().peered } }
            await("$t: hi to hold exactly one link, peered") { hi.node.links(lo.own).let { it.size == 1 && it.single().peered } }

            // Settle, then read everything at rest.
            assertEquals(1L, quiesced { lo.proxy.dialsToPeer.get().toLong() }, "$t: lo dialled hi once and never re-dialled the loser")
            assertEquals(1L, quiesced { hi.proxy.dialsToPeer.get().toLong() }, "$t: hi dialled lo once and never re-dialled the loser")

            val loLinks = lo.node.links(hi.own)
            val hiLinks = hi.node.links(lo.own)
            assertEquals(1, loLinks.size, "$t: lo holds only the survivor: $loLinks")
            assertEquals(1, hiLinks.size, "$t: hi holds only the survivor: $hiLinks")
            // aas-D7: lo keeps OUTBOUND, hi keeps INBOUND. With one link each
            // and only two connections in existence, lo's outbound link to hi
            // and hi's inbound link from lo are the same QUIC connection.
            assertEquals(LinkDirection.OUTBOUND, loLinks.single().direction, "$t: the smaller id kept its OUTBOUND link")
            assertEquals(LinkDirection.INBOUND, hiLinks.single().direction, "$t: the larger id kept its INBOUND link")
            assertTrue(loLinks.single().peered && hiLinks.single().peered, "$t: the survivor is peered on both sides")

            // Exactly one closed link per node, and no blame anywhere.
            assertEquals(1L, lo.peering.counters.tieBreakClosed.count, "$t: lo counted one tie-break close")
            assertEquals(1L, hi.peering.counters.tieBreakClosed.count, "$t: hi counted one tie-break close")
            assertEquals(0L, lo.node.admissionDenialCount, "$t: lo refused nothing")
            assertEquals(0L, hi.node.admissionDenialCount, "$t: hi refused nothing")
            assertEquals(0L, lo.node.preHelloDrops, "$t: nothing was dropped on lo")
            assertEquals(0L, hi.node.preHelloDrops, "$t: nothing was dropped on hi")
            assertTrue(lo.peering.counters.refusedBy().isEmpty(), "$t: no refusal attributed on lo: ${lo.peering.counters.refusedBy()}")
            assertTrue(hi.peering.counters.refusedBy().isEmpty(), "$t: no refusal attributed on hi: ${hi.peering.counters.refusedBy()}")
            assertEquals(0, lo.peering.connectionFor(NodeKey(hi.own))?.unadmittedOpens ?: 0, "$t: lo charged no unadmitted open")
            assertEquals(0, hi.peering.connectionFor(NodeKey(lo.own))?.unadmittedOpens ?: 0, "$t: hi charged no unadmitted open")
            assertEquals(0, lo.timer.pending(), "$t: lo armed no retry")
            assertEquals(0, hi.timer.pending(), "$t: hi armed no retry")

            // Both sides agree which direction survived, on their own surfaces.
            assertEquals("Peered(OUTBOUND)", lo.viewOf(hi.own)?.state, "$t: lo's snapshot")
            assertEquals("Peered(INBOUND)", hi.viewOf(lo.own)?.state, "$t: hi's snapshot")
            assertEquals(resolvedBy(lo.side, hi.own).name, lo.viewOf(hi.own)?.attributedPeer, "$t: lo attributes the survivor")
            assertEquals(resolvedBy(hi.side, lo.own).name, hi.viewOf(lo.own)?.attributedPeer, "$t: hi attributes the survivor")
            assertTrue(lo.node.linkErrors.isEmpty(), "$t: lo logged no link error: ${lo.node.linkErrors}")
            assertTrue(hi.node.linkErrors.isEmpty(), "$t: hi logged no link error: ${hi.node.linkErrors}")

            println(
                "[md1dt] $t PASSED: both dialled (barrier arrivals ${barrier.arrivals}), " +
                    "stray dials lo=${lo.proxy.otherDials.get()} hi=${hi.proxy.otherDials.get()}",
            )
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    private fun trials(release: Release) {
        val binary = SidecarBinary.orSkip()
        MulticastGate.deliveryOrSkip()
        for (n in 1..TRIALS) trial(binary, release, n)
    }

    @Test
    fun `two real mdns sidecars that both dial end with one peering, the smaller id's outbound link, on both sides`() =
        trials(Release.TOGETHER)

    @Test
    fun `the same mutual dial with the smaller id's link admitted first ends with the same one peering`() =
        trials(Release.LO_FIRST)

    @Test
    fun `the same mutual dial with the larger id's link admitted first ends with the smaller id's outbound link replacing it`() =
        trials(Release.HI_FIRST)

    private companion object {
        /** Independent trials per run, each with fresh keys and fresh processes. */
        const val TRIALS = 3

        /** How long the first held `DIAL` waits for the second before it is released alone (and the trial fails). */
        const val HOLD_SECONDS = 45L
    }
}
