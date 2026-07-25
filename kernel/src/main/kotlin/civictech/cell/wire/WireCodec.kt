package civictech.cell.wire

import civictech.cell.Borrowed
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Timestamp
import civictech.cell.data.CounterDelta
import civictech.cell.data.PnCounterDelta
import civictech.cell.data.WatermarkDelta
import civictech.cell.data.ListDelta
import civictech.cell.data.MapDelta
import civictech.cell.data.SetDelta
import civictech.cell.replication.Stamped
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolId
import civictech.cell.attention.Attention
import civictech.cell.attention.StallNotice
import civictech.cell.attention.Progress
import civictech.cell.host.SaturationSignal
import civictech.cell.host.TopologyLink
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.gen.wire.ContractRegistry
import civictech.gen.wire.JvmDescriptors
import civictech.gen.wire.natureVectorFromWire
import civictech.gen.wire.toWire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.util.UUID

/**
 * The serialized invocation envelope (spec 41 point 1, G-15). Identity is
 * ids-only: no `Method`, no argument class names — arguments travel as
 * polymorphic values whose discriminators are `@SerialName`-pinned stable
 * names, decoupled from packages and reflection (P9).
 */
@Serializable
data class WireFrame(
    /** Format version — bump on layout change; reserves the G-8 CellRef retrofit. */
    val version: Int = WireCodec.VERSION,
    val contractId: Long,
    val methodId: Long,
    val cellRef: CellRef,
    val portName: String,
    val type: HostedPortInvocation.Type,
    /** Wave context survives the wire (G-4). */
    val context: MessageContext? = null,
    val args: List<@Polymorphic Any?> = emptyList(),
    /**
     * Generic-protocol crossing (spec 41 point 4, G-35/G-39 phase B):
     * additive fields, populated only when [type] is `PORT_PROTOCOL` — no
     * version bump, the encoding stays backward-compatible. [protocolId]
     * names the well-known protocol (`ProtocolRegistry`); [edge] identifies
     * the logical link on each side's own bookkeeping (the id need not
     * match cross-host — every consumer keys off its own copy,
     * `Attention.linkLevels`/`GlitchFreeCell.edges`) and carries both
     * endpoints' addressable (cell, port name) so a reply can route back
     * over the reverse bridge path (`PortRef` alone has no port name).
     */
    val protocolId: String? = null,
    val protocolMessage: @Polymorphic Any? = null,
    val edge: WireEdge? = null,
    /**
     * The versioned interest-assignment epoch (spec 20/24 §Partitioned state,
     * 40/42 §Interest-scoped instance sets, CP-D3): the routing table's
     * `routingEpoch` crossing the wire alongside a routed command. Additive —
     * populated only when the invocation carries a
     * [civictech.cell.data.RoutedCommand] (a `PartitionedCell` shard route),
     * `null` otherwise, so the encoding stays backward-compatible with no
     * version bump. The receiving shard reads the same epoch in-band from the
     * command payload; the frame field makes the routing epoch observable at
     * the transport boundary (a stale-epoch command a shard no longer owns is
     * dropped/re-routed, so an in-flight command crossing a repartition flip
     * neither loses nor double-counts).
     */
    val routingEpoch: Long? = null,
    /**
     * The sending endpoint's declared [civictech.gen.wire.NatureVector], sparse
     * (CP-G2, spec §Nature typing): only populated on a link-establishing
     * `PORT_PROTOCOL` frame (the `EdgeOpen` a producer's `bridgeTo` fires), and
     * only for its *declared* axes — a fully-default vector is the empty list,
     * which `encodeDefaults=false` omits entirely (zero bytes, no version bump).
     * Absent ⇒ [civictech.gen.wire.NatureVector.DEFAULT] ⇒ today's behavior, so
     * a peer that predates this field still links (additive default). The
     * receiver reconstructs it onto the decoded [WireEdgeLink] so the bridged
     * handshake can reconcile the real cross-host natures instead of DEFAULT.
     */
    val natures: List<Int> = emptyList(),
)

/** See [WireFrame.edge]. */
@Serializable
@kotlinx.serialization.SerialName("WireEdge")
data class WireEdge(
    val id: String,
    val fromRef: PortRef,
    val fromCell: CellRef,
    val fromPortName: String,
    val toRef: PortRef,
    val toCell: CellRef,
    val toPortName: String,
)

object WireCodec {
    const val VERSION = 2 // v2 (M7.1): CellRef carries instanceId (G-8)

    private val polyAny = PolymorphicSerializer(Any::class)

    // ponytail: JSON as the M5 codec — kotlinx-serialization-json is already the
    // dependency and perf is out of scope; swap the format (CBOR) behind encode/decode
    // if profiling demands.
    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(Any::class) {
                subclass(String::class, String.serializer())
                subclass(Long::class, Long.serializer())
                subclass(Int::class, Int.serializer())
                subclass(Boolean::class, Boolean.serializer())
                subclass(Double::class, Double.serializer())
                subclass(UUID::class, UuidSerializer)
                subclass(Timestamp::class)
                subclass(CellRef::class)
                subclass(PortRef::class)
                subclass(TopologyLink::class)
                subclass(MessageContext::class)
                subclass(CounterDelta::class)
                subclass(PnCounterDelta::class)
                // delivered-watermark lattice (spec 40/42 §Delivered watermarks, E3.2)
                subclass(WatermarkDelta::class)
                @Suppress("UNCHECKED_CAST")
                subclass(SetDelta::class, SetDelta.serializer(polyAny) as KSerializer<SetDelta<*>>)
                @Suppress("UNCHECKED_CAST")
                subclass(MapDelta::class, MapDelta.serializer(polyAny, polyAny) as KSerializer<MapDelta<*, *>>)
                @Suppress("UNCHECKED_CAST")
                subclass(ListDelta::class, ListDelta.serializer(polyAny) as KSerializer<ListDelta<*>>)
                // PartitionedCell shard route (spec 20/24 §Partitioned state, CP-D3): epoch + delta
                @Suppress("UNCHECKED_CAST")
                subclass(
                    civictech.cell.data.RoutedCommand::class,
                    civictech.cell.data.RoutedCommand.serializer(polyAny) as KSerializer<civictech.cell.data.RoutedCommand<*>>,
                )
                // single-writer leader→follower log unit (spec 42 §Single-writer replication, W4.3)
                @Suppress("UNCHECKED_CAST")
                subclass(Stamped::class, Stamped.serializer(polyAny) as KSerializer<Stamped<*>>)
                // ownership wrappers (spec 23): Owned moves, Frozen/Borrowed copy; Leased never crosses
                @Suppress("UNCHECKED_CAST")
                subclass(Owned::class, Owned.serializer(polyAny) as KSerializer<Owned<*>>)
                @Suppress("UNCHECKED_CAST")
                subclass(Frozen::class, Frozen.serializer(polyAny) as KSerializer<Frozen<*>>)
                @Suppress("UNCHECKED_CAST")
                subclass(Borrowed::class, Borrowed.serializer(polyAny) as KSerializer<Borrowed<*>>)
                // generic-protocol payloads (spec 41 point 4, G-35/G-39 phase B)
                subclass(Attention::class)
                subclass(StallNotice.Stall::class)
                subclass(StallNotice.Resume::class)
                subclass(Progress::class)
                subclass(SaturationSignal::class)
                subclass(civictech.cell.port.EdgeOpen::class)
                subclass(civictech.cell.port.EdgeClose::class)
            }
        }.let { kernelModule ->
            // app-contributed delta serializers (M17): ServiceLoader-discovered,
            // mirroring ContractRegistry's ContractModule discovery (C-5);
            // `plus` fails fast if a contribution collides with a kernel type
            java.util.ServiceLoader.load(WireSerializers::class.java, WireSerializers::class.java.classLoader)
                .fold(kernelModule) { acc, contribution -> acc + contribution.module }
        }
        allowStructuredMapKeys = true // polymorphic delta keys encode as [k, v] arrays
        useArrayPolymorphism = true // ["SerialName", value] — works for primitive args too
    }

    /** @throws IllegalStateException when the invocation's contract has no `@Contract` ids (not wire-capable). */
    fun encode(invocation: HostedPortInvocation): ByteArray {
        val frame = if (invocation.type == HostedPortInvocation.Type.PORT_PROTOCOL) {
            val id = checkNotNull(invocation.protocolId) { "PORT_PROTOCOL requires protocolId" }
            val link = invocation.protocolLink as? WireEdgeLink
                ?: error("PORT_PROTOCOL requires a WireEdgeLink protocolLink to cross the wire")
            WireFrame(
                contractId = 0L,
                methodId = 0L,
                cellRef = invocation.cellRef,
                portName = invocation.portName,
                type = invocation.type,
                protocolId = id.name,
                protocolMessage = invocation.protocolMessage,
                // CP-G2: the sending endpoint's natures ride the link-open frame,
                // sparse (DEFAULT ⇒ empty ⇒ omitted). The consumer reconciles them.
                natures = link.natures.toWire(),
                edge = WireEdge(
                    id = link.id.toString(),
                    fromRef = link.from, fromCell = link.fromAddr.cell, fromPortName = link.fromAddr.port,
                    toRef = link.to, toCell = link.toAddr.cell, toPortName = link.toAddr.port,
                ),
            )
        } else {
            val inv = invocation.invocation
            val contractId = checkNotNull(inv.contractId) {
                "not wire-capable: '${inv.methodName}' was not captured from a @Contract interface"
            }
            WireFrame(
                contractId = contractId,
                methodId = checkNotNull(inv.methodId),
                cellRef = invocation.cellRef,
                portName = invocation.portName,
                type = invocation.type,
                context = inv.context,
                args = inv.args,
                // additive: lift a routed command's epoch to the frame boundary (CP-D3)
                routingEpoch = inv.args.firstNotNullOfOrNull { (it as? civictech.cell.data.RoutedCommand<*>)?.epoch },
            )
        }
        return json.encodeToString(WireFrame.serializer(), frame).toByteArray()
    }

    /** @throws IllegalStateException on unknown version or ids (caller dead-letters). */
    fun decode(bytes: ByteArray): HostedPortInvocation {
        val frame = json.decodeFromString(WireFrame.serializer(), bytes.decodeToString())
        check(frame.version == VERSION) { "unsupported wire version ${frame.version}" }
        if (frame.type == HostedPortInvocation.Type.PORT_PROTOCOL) {
            val id = checkNotNull(frame.protocolId) { "PORT_PROTOCOL frame missing protocolId" }
            val edge = checkNotNull(frame.edge) { "PORT_PROTOCOL frame missing edge" }
            // No sink yet (bare, transport-neutral reconstruction) — the
            // receiving BridgeIngressCell attaches the reverse-path sink and
            // negotiated capabilities before delivery (spec 41 point 4).
            return HostedPortInvocation(
                cellRef = frame.cellRef,
                portName = frame.portName,
                type = frame.type,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = ProtocolId(id),
                protocolLink = WireEdgeLink(
                    id = UUID.fromString(edge.id),
                    from = edge.fromRef, to = edge.toRef,
                    fromAddr = PortAddress(edge.fromCell, edge.fromPortName),
                    toAddr = PortAddress(edge.toCell, edge.toPortName),
                    // CP-G2: reconstruct the peer's natures (forward-compatible —
                    // an unknown axis from a newer peer is ignored, never refused).
                    natures = natureVectorFromWire(frame.natures),
                ),
                protocolMessage = frame.protocolMessage,
            )
        }
        val method = checkNotNull(ContractRegistry.method(frame.contractId, frame.methodId)) {
            "unknown contract/method ids ${frame.contractId}/${frame.methodId} — no local descriptor"
        }
        return HostedPortInvocation(
            cellRef = frame.cellRef,
            portName = frame.portName,
            type = frame.type,
            invocation = Invocation(
                methodName = method.name,
                parameterTypes = JvmDescriptors.parameterTypeNames(method.jvmDescriptor),
                args = frame.args,
                context = frame.context,
                contractId = frame.contractId,
                methodId = frame.methodId,
            ),
        )
    }
}
