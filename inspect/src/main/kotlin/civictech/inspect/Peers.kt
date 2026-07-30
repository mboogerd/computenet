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
 * [localNet]; remote refs report the announcing peer's name when it has one,
 * and otherwise a label derived from the egress they are routed through. Either
 * way all of one peer connection's refs share one network host.
 *
 * ### A named peer answers with its own name (V4-PEERID)
 *
 * A peer's own name (`civictech.cell.link.PeerId`, exchanged in the transport
 * hello) now reaches the registry: the per-connection `RegistryMirrorCell` that
 * serves a peer's announcements knows whose they are and records it on every
 * [LocationRegistry.Remote] it installs. So a named peer's cells report the
 * name that peer configured for itself (`--net-name` on the other JVM) — peer
 * A's inspector shows B's cells under `jvm-b`, in the same register as
 * [localNet], which is what makes two side-by-side inspectors legible.
 *
 * That name is **stable across a reconnect**, which the derived label is not: a
 * reconnect builds a new `BridgeEgressCell` (`WsTransport.Session`, and a
 * listener builds a whole new `Session` per connection), so anything keyed on
 * the egress renames the peer's hull every time it drops and returns. The name
 * survives because the peer re-asserts the same one in its re-hello.
 *
 * It is **not authenticated**. `PeerId` is transport-vouched by design
 * (`civictech.cell.link.PeerId`, spec 43 defers authentication): it says "the
 * same connection identity as before", never "the same principal". A peer that
 * reaches the socket can claim any name, including [localNet] itself — a
 * collision this class deliberately does not disambiguate (see [netOf]).
 *
 * ### Why an anonymous peer's label is still derived
 *
 * Naming a `Peering.Side` is optional and unnamed peerings are the historical
 * default, so a peer may still announce anonymously. For those the label stays
 * exactly what M5-NET shipped: derived from the bridge egress cell's own ref —
 * stable for the life of a connection, distinct per peer, honest about being an
 * inspector-side identity rather than the peer's chosen name, and (deliberately
 * not papered over) *renamed by every reconnect*. The refs an anonymous peer
 * re-announces are the same; only the hull they group under is relabelled.
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
     *
     * A mirrored location prefers the announcing peer's own name (V4-PEERID)
     * and falls back to the derived per-connection label when that peer is
     * anonymous — an unnamed peer keeps the old, honest, unstable label rather
     * than degrading to a blank, a throw, or (worse) [localNet], which would
     * silently place a remote cell in this JVM's hull.
     *
     * **Collision is not disambiguated, deliberately.** A peer that names
     * itself [localNet] renders inside the local hull. Reporting it as-is is
     * the honest answer to "which network host does this cell live on": the
     * name is the peer's own unauthenticated claim, and rewriting it here
     * would invent an identity the inspector has no better source for. The
     * cell is still distinguishable — a mirrored ref has no process host
     * (`Node.host` is null) and no descriptor — and a deployment that wants
     * distinct hulls gives its JVMs distinct `--net-name`s.
     */
    fun netOf(ref: CellRef): String? = when (val location = registry.location(ref)) {
        is LocationRegistry.Local -> localNet
        is LocationRegistry.Remote -> location.peer?.name ?: labelOf(location.sink)
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
