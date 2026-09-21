package civictech.demo.beadsmirror

import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.discover.DialPolicy
import civictech.iroh.discover.DiscoveredPeering
import civictech.iroh.discover.NodeKey
import civictech.iroh.discover.PeerView
import java.nio.file.Path

/**
 * The third [MirrorTransport] binding (DSC2, epic `computenet-aas`, feature
 * `computenet-63um5`, task `.2`; 63um5-D2): the same rig and the same seeded
 * schedules as [WsMirrorTransport] and [IrohMirrorTransport], with the peering
 * **formed by LAN discovery** — no NodeId and no address is handed from the
 * listening end to the dialling end ([DSC2-NEU-02]).
 *
 * Together with [IrohMirrorTransport] this is the only file in the module that
 * names `civictech.iroh` types.
 *
 * ## What differs from [IrohMirrorTransport]
 *
 * - **Nothing is handed over.** [IrohMirrorTransport.dial] reads the
 *   listener's `nodeId`/`addresses` off the SAME instance. This [dial]
 *   requires no prior [listen] on the instance at all — a [dial] on a fresh
 *   instance is the two-JVM shape and forms the peering the same way — and
 *   ignores its `uri`, which in `TwoNodeRig` is a `ws://localhost:<n>` string
 *   built from the listener's decorative [MirrorLink.boundWsPort].
 * - **Formation is by `PEER_DISCOVERED`.** Both ends run their sidecar with
 *   `--offline --mdns`. The listening end is a plain advertiser
 *   ([IrohTransport.listen]) that accepts and never watches; the dialling end
 *   is an [IrohNode] under a [DiscoveredPeering] policy, which watches, dials
 *   the key it hears about and records it `Peered(OUTBOUND)`. [dial] returns
 *   once exactly one retained entry is `Peered`, bounded by
 *   `formationTimeoutMillis`. Two `Peered` entries fail loudly: the binding
 *   refuses to guess which peer is "the" peer. With the dialling side's
 *   allowlist narrowed (below) that takes two advertisers answering to this
 *   rig's listener name.
 * - **Partition = detach + sever** (63um5-D1). A planned
 *   `IrohConnection.sever()` under a RUNNING policy is re-dialled within one
 *   pump (the table answers `Redial` for a discovered key with no other link
 *   up), so a partition could not hold. The first [partition] therefore calls
 *   [DiscoveredPeering.detach] — the policy stops, the node's gate returns to
 *   admit-all, and the policy's connections become this binding's — and only
 *   then severs the peered connection.
 *
 * ## The hand-held tail
 *
 * After the first [partition] (or an explicit [stopDiscovery]) the peering is
 * hand-held for good: discovery's job was formation, and nothing re-attaches a
 * policy. [heal] is `IrohConnection.heal()` on the same key — its addresses
 * are already in the sidecar's `MemoryLookup` from the discovery (aas-D8) —
 * then the bounded wait for `peered` copied from [IrohMirrorTransport]'s
 * reopen. An *unplanned* drop after detach is NOT re-dialled either: a
 * discovered connection is made with an empty unplanned-down delegate, so it
 * stays down until a caller heals it.
 *
 * Detach is only ever called once exactly one peer is `Peered` (formation has
 * returned by then). That matters because `DiscoveredPeering`'s stop does not
 * await an in-flight dial (computenet-iesmw): detaching while a dial is still
 * running could race a late `openLink` against this binding's own `heal()`.
 * A single formed peering with no second key in play gives the policy nothing
 * to be dialling at that moment — the precondition, not a fix of iesmw.
 *
 * ## What it cannot prove
 *
 * - **Loopback only** ([DSC2-NV-01]): both sidecars run on one host, so a pass
 *   shows discovery over the host's multicast loopback, not across a LAN
 *   segment with its own multicast filtering. On a host that delivers no
 *   multicast at all (macOS without Local Network permission, `ne2oh-B6`) the
 *   tests skip; the executed evidence is Linux CI.
 * - **A stranger of an unknown rig name.** [dial] admits only its own rig's
 *   listener (see "Admission" below) when the rig's side names itself the
 *   usual way; a side that does not ([admittingOnlyTheRigListener] leaves it
 *   open) still admits any `--mdns` sidecar on the segment, where a second
 *   `Peered` fails loudly and a first one races formation.
 *
 * ## Admission: only this rig's listener (computenet-63um5.5)
 *
 * Every `--mdns` sidecar on the segment is discovered, and the rig's side has
 * `allow = null`, so before this the dialling end admitted whichever
 * advertiser completed a hello first. On the `iroh-sidecar` CI lane two
 * discovery rigs run at once — `:demo:beadsmirror` tests run on parallel
 * forks — and run 35629640485's BS-09 rig formed its one `Peered` link to the
 * OTHER class's listener: both of its nodes then sat at `+0` for the whole
 * 30 s convergence window, because the rig name hashed into the shared
 * `CellRef`s differed and nothing either node published matched a ref at the
 * far end. [dial] therefore narrows the dialling side's allowlist to
 * `<rigName>-listener`, the name [MirrorPeering] gives the listening end of
 * the same rig: a foreign rig's advertiser is dialled, refused inside
 * `Session` and abandoned by the policy's refused-dial limit — the BS-05a
 * path `DiscoveredPeeringTest` pins — and only the rig's own listener can
 * become `Peered`. That is a logical name both ends already share (the rig
 * name is their entire coordination mechanism), not a NodeId or an address:
 * [DSC2-NEU-02] still holds.
 *
 * @param binary the sidecar executable (in tests, `IrohSidecarGate.orSkip()`).
 * @param reconnectBackoff the policy's dial retry schedule
 *   ([DialPolicy.schedule]) — the same T12 seam [IrohMirrorTransport] takes; a
 *   rig drives it near zero.
 * @param formationTimeoutMillis how long [dial] waits for the one `Peered`
 *   entry. A bound, not a measurement: `SidecarMdnsDiscoveryTest` bounds
 *   discovery alone at 30 s, and formation adds a dial and a hello exchange.
 * @param healTimeoutMillis how long [heal] waits for the re-established link to
 *   carry before failing.
 */
class DiscoveredIrohMirrorTransport(
    private val binary: Path,
    private val reconnectBackoff: (attempt: Int) -> Long = IrohTransport.DEFAULT_RECONNECT_BACKOFF,
    private val formationTimeoutMillis: Long = 60_000,
    private val healTimeoutMillis: Long = 30_000,
) : MirrorTransport {

    companion object {

        /** Both ends' sidecars: no relay, no DNS — the peer is found on the LAN or not at all. */
        private val SIDECAR_ARGS: List<String> = listOf("--offline", "--mdns")

        /**
         * The fixed prefix `iroh/sidecar/src/endpoint.rs` writes when its mDNS
         * lookup could not bind. A private copy of the test gate's marker: this
         * main-sources class cannot see `MulticastGate`.
         */
        private const val MDNS_UNAVAILABLE_MARKER = "mdns unavailable"

        /** How many recent stderr lines a formation or heal failure quotes. */
        private const val STDERR_TAIL = 50

        private const val PEERED_PREFIX = "Peered("
    }

    private val lock = Any()

    /** The listening end, once [listen] opened one. Nothing on this instance dials it by id. */
    private var listener: IrohTransport.IrohListener? = null

    /** The dialled end, once [dial] formed one — what [partition]/[heal] act on. */
    private var dialled: DialLink? = null

    /**
     * The policy that formed the dialled peering, or `null` before [dial].
     * Read-only for callers (task `.3` reads its `counters`); it stays readable,
     * and frozen, after [partition] or [stopDiscovery] detached it.
     */
    val discovery: DiscoveredPeering? get() = synchronized(lock) { dialled?.peering }

    /** The dialling end's node, or `null` before [dial] — for callers that read its `links()`. */
    val dialledNode: IrohNode? get() = synchronized(lock) { dialled?.node }

    /** [requestedWsPort] is ignored: an iroh endpoint does not bind a TCP port. */
    override fun listen(requestedWsPort: Int, side: Peering.Side): MirrorLink = synchronized(lock) {
        check(listener == null) { "this transport is already listening" }
        val opened = IrohTransport.listen(side, binary, sidecarArgs = SIDECAR_ARGS)
        listener = opened
        ListenLink(opened)
    }

    /** [uri] is an opaque token and is ignored; see the class doc. */
    override fun dial(uri: String, side: Peering.Side): MirrorLink = synchronized(lock) {
        check(dialled == null) { "this transport has already dialled" }
        val stderr = StderrTail()
        val node = IrohTransport.node(
            admittingOnlyTheRigListener(side),
            binary,
            stderrSink = stderr::add,
            sidecarArgs = SIDECAR_ARGS,
        )
        val peering = try {
            DiscoveredPeering.start(node, DialPolicy(schedule = reconnectBackoff))
        } catch (e: Throwable) {
            runCatching { node.close() }
            throw e
        }
        val peeredKey = try {
            awaitFormation(peering, stderr)
        } catch (e: Throwable) {
            runCatching { peering.close() }
            runCatching { node.close() }
            throw e
        }
        DialLink(node, peering, peeredKey, stderr).also { dialled = it }
    }

    /**
     * [side] with its allowlist narrowed to this rig's listening end (see the
     * class doc's "Admission"), or [side] itself when it already carries an
     * allowlist or does not name itself `<rigName>-dialer` the way
     * [MirrorPeering] does — then there is no rig name to derive a listener
     * from, and the binding does not guess one.
     */
    private fun admittingOnlyTheRigListener(side: Peering.Side): Peering.Side {
        if (side.allow != null) return side
        val dialerSuffix = "-${MirrorCellRefs.DIALER}"
        val name = side.peer?.name?.takeIf { it.endsWith(dialerSuffix) } ?: return side
        val listener = PeerId(name.removeSuffix(dialerSuffix) + "-" + MirrorCellRefs.LISTENER)
        return Peering.Side(
            registry = side.registry,
            bridgeHost = side.bridgeHost,
            peer = side.peer,
            allow = setOf(listener),
            onCatchUpWindowOpen = side.onCatchUpWindowOpen,
            auth = side.auth,
            credentials = side.credentials,
            announcementSigning = side.announcementSigning,
            announcementVerification = side.announcementVerification,
            identityBinding = side.identityBinding,
        )
    }

    /**
     * Poll [peering] until exactly one entry is `Peered`, and return its key.
     * Loud on a second `Peered` entry and on timeout; see the class doc.
     */
    private fun awaitFormation(peering: DiscoveredPeering, stderr: StderrTail): String {
        val deadline = System.currentTimeMillis() + formationTimeoutMillis
        while (true) {
            val views = peering.snapshot()
            val peered = views.filter { it.state.startsWith(PEERED_PREFIX) }
            check(peered.size <= 1) {
                "discovery peered ${peered.size} keys, expected exactly one: ${describe(peered)}. " +
                    "Another --mdns sidecar on this segment was admitted by the dialling side's allowlist; " +
                    "this binding will not guess which one is the mirror's peer"
            }
            if (peered.size == 1) return peered.single().keyHex
            check(System.currentTimeMillis() < deadline) {
                val mdns = stderr.mdnsUnavailable()
                if (mdns != null) {
                    "no discovered peer was peered within ${formationTimeoutMillis}ms; the sidecar reported: $mdns"
                } else {
                    "no discovered peer was peered within ${formationTimeoutMillis}ms and the sidecar reported no " +
                        "mDNS problem; retained views: ${describe(views)}; sidecar stderr tail: ${stderr.tail()}"
                }
            }
            Thread.sleep(50)
        }
    }

    override fun partition() = synchronized(lock) { diallingEnd().sever() }

    override fun heal() = synchronized(lock) { diallingEnd().reopen() }

    /**
     * Stop the discovery policy and keep the formed link (63um5-D1's
     * `detach()` step alone, beyond the [MirrorTransport] interface). The
     * peering stays up and is hand-held from here on: a later [partition]
     * severs without detaching again, and [heal] re-dials the same key.
     * Idempotent.
     */
    fun stopDiscovery() = synchronized(lock) { diallingEnd().detach() }

    private fun diallingEnd(): DialLink = checkNotNull(dialled) {
        "partition/heal/stopDiscovery act on the DIALLING end of the peering, and this transport has not " +
            "dialled one"
    }

    /** A serving advertiser. Nothing severs this end; the dialling end does. */
    private class ListenLink(private val listener: IrohTransport.IrohListener) : MirrorLink {

        override val boundWsPort: Int = IrohMirrorTransport.syntheticPort(listener.addresses)

        override fun close() {
            runCatching { listener.close() }
        }
    }

    /**
     * The dialling end: the node, the policy that formed its peering, and —
     * once detached — the one connection that carries it.
     */
    private inner class DialLink(
        val node: IrohNode,
        val peering: DiscoveredPeering,
        private val peeredKeyHex: String,
        private val stderr: StderrTail,
    ) : MirrorLink {

        /** The peered connection, once [detach] took it off the policy; null while the policy holds it. */
        private var connection: IrohTransport.IrohConnection? = null

        private var detached = false

        /** Whether [sever] has been called and [reopen] has not; see `IrohMirrorTransport.DialLink.severed`. */
        private var severed = false

        /** Null on a dialling end, as every binding's is. */
        override val boundWsPort: Int? get() = null

        fun detach() {
            if (detached) return
            detached = true
            val handed = peering.detach()
            val (mine, others) = handed.entries.partition { it.key.hex == peeredKeyHex }
            // Connections to keys that never peered (a stale advertisement's
            // failed dial) are nobody's now; close them rather than leak them.
            others.forEach { runCatching { it.value.close() } }
            connection = checkNotNull(mine.singleOrNull()?.value) {
                "detaching the discovery policy handed back no connection for the peered key " +
                    "${peeredKeyHex.take(8)} (handed: ${handed.keys.map(NodeKey::short)})"
            }
        }

        fun sever() {
            check(!severed) { "the peering is already partitioned" }
            detach()
            checkNotNull(connection).sever()
            severed = true
        }

        fun reopen() {
            check(severed) { "the peering is not partitioned" }
            val live = checkNotNull(connection)
            // First exercise of IrohConnection.heal() after a planned sever on
            // a dialDiscovered connection (the bead's `unverified:` clause):
            // a failure here carries the dialling sidecar's stderr with it.
            try {
                live.heal()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "heal() of the discovered connection failed: ${e.message}; dialling sidecar stderr tail: " +
                        stderr.tail(),
                    e,
                )
            }
            severed = false
            // Same gap IrohMirrorTransport closes: heal() returns once THIS
            // side's hello is out; "carrying" means the peer's hello was
            // admitted back.
            val deadline = System.currentTimeMillis() + healTimeoutMillis
            while (!live.peered) {
                check(System.currentTimeMillis() < deadline) {
                    "the discovered iroh peering did not carry again within ${healTimeoutMillis}ms of heal(); " +
                        "dialling sidecar stderr tail: ${stderr.tail()}"
                }
                Thread.sleep(20)
            }
        }

        override fun close() {
            connection?.let { runCatching { it.close() } }
            connection = null
            if (!detached) runCatching { peering.close() }
            runCatching { node.close() }
        }
    }

    /**
     * The last [STDERR_TAIL] lines a sidecar wrote, plus the first `mdns
     * unavailable` line if one ever appeared — bounded, because this binding
     * is main code and a long-lived process would otherwise keep every line.
     */
    private class StderrTail {
        private val lines = ArrayDeque<String>()
        private var mdnsLine: String? = null

        @Synchronized
        fun add(line: String) {
            if (mdnsLine == null && line.contains(MDNS_UNAVAILABLE_MARKER)) mdnsLine = line
            lines.addLast(line)
            while (lines.size > STDERR_TAIL) lines.removeFirst()
        }

        /** The first `mdns unavailable` line the sidecar wrote, or `null`. */
        @Synchronized
        fun mdnsUnavailable(): String? = mdnsLine

        /** The retained lines, oldest first, for a failure message. */
        @Synchronized
        fun tail(): String = if (lines.isEmpty()) "(none)" else lines.joinToString(" | ")
    }

    private fun describe(views: List<PeerView>): String =
        if (views.isEmpty()) "none" else views.joinToString { "${it.keyHex.take(8)}=${it.state}" }
}
