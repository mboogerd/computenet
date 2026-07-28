package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.BridgeEgressCell

/**
 * M5 — the network-host half of placement
 * (`doc/spec/90-roadmap/97-inspector-plan/tickets/M5-NET.md`): what the
 * contract's `Node.net` answers for a ref.
 *
 * ### Where a network host comes from
 *
 * [LocationRegistry.location] is the whole seam. It distinguishes
 * [LocationRegistry.Local] — a [civictech.cell.host.ManagedHost] in this JVM,
 * whose *process* host is the contract's `Node.host` — from
 * [LocationRegistry.Remote], a ref a peer announced (`cell.wire.Peering`)
 * whose invocations leave through a bridge egress. Local refs report
 * [localNet]; remote refs report a label derived from the egress they are
 * routed through, so all of one peer connection's refs share one network host
 * and two peers never collide.
 *
 * ### Why the label is derived, not configured
 *
 * A peer's own name (`civictech.cell.link.PeerId`, exchanged in the transport
 * hello) reaches the *ingress* side of a connection — it stamps deliveries
 * arriving from that peer — and never reaches the registry, which only stores
 * the egress [InvocationSink] a remote ref is reachable through. Reading it
 * would mean a peering-protocol change, which M5-NET explicitly excludes. So
 * the label is derived from the bridge egress cell's own ref: stable for the
 * life of a connection, distinct per peer, and honest about being an
 * inspector-side identity rather than the peer's chosen name.
 *
 * Consequence, deliberately not papered over: a reconnect builds a new
 * `BridgeEgressCell` (`WsTransport.Session`), so a peer that drops and returns
 * comes back under a *new* peer label. The refs it re-announces are the same;
 * only the hull they group under is renamed. A stable cross-reconnect identity
 * needs `PeerId` to reach the registry — a kernel/peering change this ticket
 * does not own.
 *
 * [localNet] is the launcher's `--net-name`, defaulting to the contract's
 * `"local"`, so an inspector nobody told about the wider network keeps
 * emitting exactly what M0–M4 emitted.
 */
internal class Peers(
    private val registry: LocationRegistry,
    /** This JVM's network-host label — the contract's `"local"` unless configured. */
    val localNet: String,
) {

    /**
     * The contract's `Node.net` for [ref], or null when the registry has no
     * location for it at all (an unpublished ref — the caller keeps whatever
     * it already knew rather than inventing a network host).
     */
    fun netOf(ref: CellRef): String? = when (val location = registry.location(ref)) {
        is LocationRegistry.Local -> localNet
        is LocationRegistry.Remote -> labelOf(location.sink)
        null -> null
    }

    /** Is [ref] currently a peer-announced (mirrored) location? */
    fun isRemote(ref: CellRef): Boolean = registry.location(ref) is LocationRegistry.Remote

    private companion object {
        const val PREFIX = "peer-"

        /**
         * One peer connection's label. Every peering path — `Peering.loopback`
         * and `:wire`'s `WsTransport.Session` alike — routes a peer's refs
         * through a [BridgeEgressCell], so its ref is the stable handle;
         * anything else (a hand-built sink in a test) falls back to identity,
         * which is stable within a JVM run and still distinct per peer.
         *
         * Built with the same [labelFor] [InspectorModel]'s own default host
         * name uses (T24): the prefix plus the first uuid segment, short
         * enough to fit a hull label — one shared expression, not two copies
         * that happen to match today.
         */
        fun labelOf(sink: InvocationSink): String = when (sink) {
            is BridgeEgressCell -> labelFor(PREFIX, sink.ref.id)
            else -> PREFIX + Integer.toHexString(System.identityHashCode(sink))
        }
    }
}
