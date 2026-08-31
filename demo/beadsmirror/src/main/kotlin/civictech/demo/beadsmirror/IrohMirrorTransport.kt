package civictech.demo.beadsmirror

import civictech.cell.wire.Peering
import civictech.iroh.IrohTransport
import java.nio.file.Path

/**
 * The iroh [MirrorTransport] binding (DSC0, epic `computenet-egl`, feature
 * `computenet-egl.4`, task `computenet-egl.4.1`): the same rig, the same
 * seeded schedules, the same equal-fold assertions — carried over an iroh
 * QUIC link instead of a WebSocket.
 *
 * The whole point of the [MirrorTransport] seam is that this class is the
 * *only* thing that differs between the two runs (see [MirrorTransport]'s
 * KDoc: the suite receives its wiring instead of naming it). So this file
 * names `civictech.iroh` types and nothing else in the module does, exactly as
 * [WsMirrorTransport] is the only file that names a `:wire` type.
 *
 * ## Placement: main sources, beside [WsMirrorTransport] (egl.4-D1)
 *
 * D1 named both main and test sources acceptable and made the choice
 * conditional on whether `implementation(project(":iroh"))` drags cargo onto
 * the default compile path. It does not: `iroh/build.gradle.kts` registers
 * `cargoBuild`/`cargoTest` and sets the `iroh.sidecar.binary` system property
 * **only** inside `if (project.hasProperty("iroh.enabled"))`, so on the
 * default path `:iroh` is an ordinary pure-JVM module and `:demo:beadsmirror`
 * compiles against it with no Rust toolchain consulted. Main sources it is —
 * which keeps the binding symmetric with [WsMirrorTransport] and available to
 * anything that later wants to *run* the mirror over iroh rather than only
 * test it.
 *
 * ## The address model: satisfied, not replaced (egl.4-D2)
 *
 * [MirrorTransport]'s vocabulary is WebSocket-shaped and its KDoc pre-answers
 * what a binding whose addresses are neither ports nor URIs should do: return
 * *some* non-null integer from [MirrorLink.boundWsPort] on the listening end
 * (`TwoNodeRig` `checkNotNull`s it, then only formats it into a `ws://` URI
 * this binding ignores), and treat [dial]'s [String] as an opaque token.
 *
 * The synthetic number here is the UDP port out of the sidecar's first
 * `LISTENING` address (`iroh/sidecar/PROTOCOL.md` §3 — comma-separated
 * `ip:port` strings), which makes it at least *related* to the endpoint a
 * reader is looking at rather than a bare constant. It is nonetheless
 * decorative: nothing dials it, and when no address parses as `host:port` the
 * fallback [SYNTHETIC_PORT] is returned, because the contract is only
 * "non-null".
 *
 * egl.4-D2's own text called the sidecar's loopback control port "a natural
 * candidate"; that was drift — `IrohTransport.IrohListener` does not expose
 * its `SidecarProcess`, the process is private, and widening `:iroh` to expose
 * it is out of this task's scope. The first `addresses` entry carries the same
 * information and is already public.
 *
 * ## dial() ignores its argument, and that is sound here
 *
 * A rig shares ONE transport instance between its two nodes, so by the time
 * [dial] is called this instance is *already holding* the [IrohTransport.IrohListener]
 * the other node opened — its `nodeId` and `addresses` are exactly
 * [IrohTransport.connect]'s first two arguments. There is no discovery service
 * and no name resolution to do; the `ws://` string the rig built out of
 * [MirrorLink.boundWsPort] carries no information this side needs. A [dial]
 * before a [listen] on the same instance is a loud `check` failure rather than
 * a silent default, because it means the caller assumed a topology this
 * binding cannot serve.
 *
 * ## partition/heal sever the DIALLING end (egl.4-D3)
 *
 * Same shape as [WsMirrorTransport], for the same reason: the listener stays
 * up throughout, and [partition] takes the dialled link down through
 * [IrohTransport.IrohConnection.sever], which sets the connection's
 * `closeRequested` flag *before* asking — so the re-dial loop consumes the
 * flag instead of re-dialling and **the partition stays severed** until
 * [heal]. [heal] re-dials one new link (a new Session, a fresh mirror, a fresh
 * hello, a full `localRefs` re-announcement) and then waits, bounded, for
 * `peered` — [MirrorTransport.heal]'s KDoc promises the *link* is carrying on
 * return, and `IrohConnection.heal` returns once the local hello has been
 * *sent*, which is one step short of that. The wait closes the gap.
 *
 * [partition] on a transport that has not dialled fails loudly, mirroring
 * `WsMirrorTransport.dialingEnd()`.
 *
 * @param binary the sidecar executable. The caller supplies it — in tests,
 *   `IrohSidecarGate.orSkip()`, which reads the `iroh.sidecar.binary` system
 *   property `demo/beadsmirror/build.gradle.kts` sets under `-Piroh.enabled`.
 * @param reconnectBackoff the re-dial delay schedule for *unplanned* drops,
 *   the same T12 seam `WsMirrorTransport` takes: a rig drives it near zero so
 *   a reconnect costs scheduling rather than wall clock.
 * @param healTimeoutMillis how long [heal] waits for the re-established link
 *   to carry before failing. A timeout here is a real failure — the peering
 *   did not come back — not a flake to absorb.
 */
class IrohMirrorTransport(
    private val binary: Path,
    private val reconnectBackoff: (attempt: Int) -> Long = IrohTransport.DEFAULT_RECONNECT_BACKOFF,
    private val healTimeoutMillis: Long = 30_000,
) : MirrorTransport {

    companion object {

        /**
         * The [MirrorLink.boundWsPort] a listening end reports when no
         * `LISTENING` address parses as `host:port`. Positive and non-null is
         * the whole contract; see the class doc.
         */
        const val SYNTHETIC_PORT: Int = 1

        /**
         * The UDP port out of the first `ip:port` address, or [SYNTHETIC_PORT].
         *
         * Split on the LAST colon so an IPv6 literal (`[::1]:49812`) yields its
         * port rather than a fragment of its address.
         */
        internal fun syntheticPort(addresses: List<String>): Int = addresses
            .firstNotNullOfOrNull { it.substringAfterLast(':', "").toIntOrNull()?.takeIf { p -> p > 0 } }
            ?: SYNTHETIC_PORT
    }

    private val lock = Any()

    /** The listening end, once [listen] opened one — what [dial] dials at. */
    private var listener: IrohTransport.IrohListener? = null

    /** The dialled end, once [dial] opened one — what [partition]/[heal] act on. */
    private var dialled: DialLink? = null

    /** [requestedWsPort] is ignored: an iroh endpoint does not bind a TCP port. */
    override fun listen(requestedWsPort: Int, side: Peering.Side): MirrorLink = synchronized(lock) {
        check(listener == null) { "this transport is already listening" }
        val opened = IrohTransport.listen(side, binary)
        listener = opened
        ListenLink(opened)
    }

    /** [uri] is an opaque token and is ignored; see the class doc. */
    override fun dial(uri: String, side: Peering.Side): MirrorLink = synchronized(lock) {
        val serving = checkNotNull(listener) {
            "IrohMirrorTransport.dial needs the listening end's iroh NodeId, which only listen() can supply; " +
                "share one MirrorTransport between the two nodes of a rig and listen before you dial"
        }
        check(dialled == null) { "this transport has already dialled" }
        DialLink(serving, side).also { link ->
            dialled = link
            link.open()
        }
    }

    override fun partition() = synchronized(lock) { diallingEnd().sever() }

    override fun heal() = synchronized(lock) { diallingEnd().reopen() }

    private fun diallingEnd(): DialLink = checkNotNull(dialled) {
        "partition/heal sever the DIALLING end of the peering, and this transport has not dialled one; " +
            "share one MirrorTransport between the two nodes of a rig"
    }

    /** A serving listener. Nothing severs this end; see the class doc. */
    private class ListenLink(private val listener: IrohTransport.IrohListener) : MirrorLink {

        override val boundWsPort: Int = syntheticPort(listener.addresses)

        override fun close() {
            runCatching { listener.close() }
        }
    }

    /**
     * The dialled connection. Unlike `WsMirrorTransport.DialLink` this holds
     * ONE [IrohTransport.IrohConnection] across sever/heal rather than
     * replacing it: the connection is already the object that outlives its
     * links (each link gets its own Session and its own mirror — the
     * computenet-dqy.14 per-instance rule holds inside it), and `sever`/`heal`
     * are its own vocabulary.
     */
    private inner class DialLink(
        private val serving: IrohTransport.IrohListener,
        private val side: Peering.Side,
    ) : MirrorLink {

        private var connection: IrohTransport.IrohConnection? = null

        /**
         * Whether [sever] has been called and [reopen] has not. Held here
         * rather than read off `IrohConnection.peered`, which is false for a
         * moment during an ordinary dial as well and so cannot distinguish
         * "partitioned" from "still handshaking".
         */
        private var severed = false

        /** Null on a dialling end, as `WsMirrorTransport.DialLink`'s is. */
        override val boundWsPort: Int? get() = null

        fun open() {
            check(connection == null) { "this end is already connected" }
            connection = IrohTransport.connect(
                side = side,
                peerNodeId = serving.nodeId,
                peerAddresses = serving.addresses,
                binary = binary,
                backoff = reconnectBackoff,
            )
        }

        fun sever() {
            val live = checkNotNull(connection) { "this transport has not dialled" }
            check(!severed) { "the peering is already partitioned" }
            live.sever()
            severed = true
        }

        fun reopen() {
            val live = checkNotNull(connection) { "this transport has not dialled" }
            check(severed) { "the peering is not partitioned" }
            live.heal()
            severed = false
            // heal() returns once THIS side's hello has gone out; the peering is
            // "carrying" (MirrorTransport.heal's KDoc) once the peer's hello has
            // been admitted back. Bounded, because a peering that never returns
            // is a failure and not something to wait out.
            val deadline = System.currentTimeMillis() + healTimeoutMillis
            while (!live.peered) {
                check(System.currentTimeMillis() < deadline) {
                    "the iroh peering did not carry again within ${healTimeoutMillis}ms of heal()"
                }
                Thread.sleep(20)
            }
        }

        override fun close() {
            connection?.let { runCatching { it.close() } }
            connection = null
        }
    }
}
