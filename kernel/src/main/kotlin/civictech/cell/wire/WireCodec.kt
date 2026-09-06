package civictech.cell.wire

import civictech.cell.Borrowed
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Timestamp
import civictech.cell.UuidSerializer
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.data.delta.ListDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.data.delta.TaggedMapDeltaSerializer
import civictech.cell.replication.Stamped
import civictech.cell.port.PortRef
import civictech.cell.protocol.ProtocolId
import civictech.cell.control.Attention
import civictech.cell.control.StallNotice
import civictech.cell.control.Progress
import civictech.cell.host.SaturationSignal
import civictech.cell.host.TopologyLink
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.nature.ContractRegistry
import civictech.nature.JvmDescriptors
import civictech.nature.natureVectorFromWire
import civictech.nature.toWire
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
     * [civictech.cell.partition.RoutedCommand] (a `PartitionedCell` shard route),
     * `null` otherwise, so the encoding stays backward-compatible with no
     * version bump. The receiving shard reads the same epoch in-band from the
     * command payload; the frame field makes the routing epoch observable at
     * the transport boundary (a stale-epoch command a shard no longer owns is
     * dropped/re-routed, so an in-flight command crossing a repartition flip
     * neither loses nor double-counts).
     */
    val routingEpoch: Long? = null,
    /**
     * The sending endpoint's declared [civictech.nature.NatureVector], sparse
     * (CP-G2, spec §Nature typing): only populated on a link-establishing
     * `PORT_PROTOCOL` frame (the `EdgeOpen` a producer's `bridgeTo` fires), and
     * only for its *declared* axes — a fully-default vector is the empty list,
     * which `encodeDefaults=false` omits entirely (zero bytes, no version bump).
     * Absent ⇒ [civictech.nature.NatureVector.DEFAULT] ⇒ today's behavior, so
     * a peer that predates this field still links (additive default). The
     * receiver reconstructs it onto the decoded [WireEdgeLink] so the bridged
     * handshake can reconcile the real cross-host natures instead of DEFAULT.
     */
    val natures: List<Int> = emptyList(),
    /**
     * Announcement signing (DSC1-ANN, epic computenet-ssa.4, [DSC1-WIRE-01]):
     * additive fields, absent (`null`) whenever signing is disabled, following
     * exactly the [routingEpoch]/[protocolId] precedent — no version bump,
     * encoding stays backward-compatible with peers that predate these fields.
     * Populated by [WireCodec.encode] when it is handed an
     * [AnnouncementSigner] and the invocation is a [RegistryAnnounce] call
     * (computenet-ssa.4.2), and by nothing else. Nothing in this module
     * *verifies* them: the single ingress admission gate is the successor
     * task's, so today a receiver reads them only through
     * [WireCodec.decodeFrame].
     *
     * [signature] is the base64url encoding (no padding) of the raw signature
     * bytes over the canonical announcement encoding, chosen over a raw
     * `ByteArray` field so [WireFrame]'s generated `equals`/`hashCode` compare
     * by content rather than by array reference (`ByteArray` breaks
     * structural equality in a data class) — a populated frame still
     * round-trips byte-exact, since the caller decodes the string back to the
     * same bytes it encoded.
     */
    val signature: String? = null,
    /** Identifies the signing key among the signer's published keys ([DSC1-WIRE-01]). */
    val signerKeyId: String? = null,
    /** Per-minting-peer strictly-increasing replay counter ([DSC1-WIRE-01]). */
    val sigCounter: Long? = null,
    /** Expiry, epoch millis, checked against the receiver's clock plus skew ([DSC1-WIRE-01]). */
    val notAfter: Long? = null,
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

/**
 * [WireCodec.decodeFrame]'s result: the [HostedPortInvocation] that
 * [WireCodec.decode] alone would have returned, alongside the [WireFrame] it
 * was parsed from — the only way to reach frame-only fields like
 * [WireFrame.signature] once decoding has happened.
 */
data class DecodedWireFrame(val invocation: HostedPortInvocation, val frame: WireFrame)

object WireCodec {
    const val VERSION = 2 // v2 (M7.1): CellRef carries instanceId (G-8)

    private val polyAny = PolymorphicSerializer(Any::class)

    /**
     * The kernel's own polymorphic registrations plus the process-start
     * `ServiceLoader` contributions — computed exactly once, at class init,
     * and the baseline every rebuild ([contribute]/[withdraw]) starts from.
     * With no dynamic contributions the resulting [Json] is configured
     * identically to the once-built codec it replaces, so wire framing for
     * every existing payload is byte-identical ([JAR1-REG-08] arm 1).
     */
    private val baselineModule: SerializersModule = run {
        SerializersModule {
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
                // tagged map / OR-map dots (spec 20/24 §Tagged maps, E1.2): an
                // ADDITIVE payload registered beside SetDelta — new @SerialName,
                // no frame-type change, every existing encoding untouched. Its
                // serializer groups dots by sourceId (decided point 4).
                @Suppress("UNCHECKED_CAST")
                subclass(
                    TaggedMapDelta::class,
                    TaggedMapDeltaSerializer(polyAny, polyAny) as KSerializer<TaggedMapDelta<*, *>>,
                )
                @Suppress("UNCHECKED_CAST")
                subclass(ListDelta::class, ListDelta.serializer(polyAny) as KSerializer<ListDelta<*>>)
                // PartitionedCell shard route (spec 20/24 §Partitioned state, CP-D3): epoch + delta
                @Suppress("UNCHECKED_CAST")
                subclass(
                    civictech.cell.partition.RoutedCommand::class,
                    civictech.cell.partition.RoutedCommand.serializer(polyAny) as KSerializer<civictech.cell.partition.RoutedCommand<*>>,
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
                subclass(civictech.cell.protocol.EdgeOpen::class)
                subclass(civictech.cell.protocol.EdgeClose::class)
                // on-demand pull request (spec 20/21 §Pull, 20/24 §Partitioned
                // state, PN-5): the scatter-gather router fans a StateRequest to
                // shards behind a bridge, so it crosses as a PORT_PROTOCOL message.
                subclass(civictech.cell.protocol.StateRequest::class)
                // interest reassignment (PN-6, spec 40/42 §Interest-scoped instance
                // sets): a journaled, ref-addressed hosted invocation to a shard's
                // assignInlet — so it rides the WAL and crosses a bridge.
                subclass(civictech.cell.replication.Assignment::class)
            }
            // the interest algebra crosses the wire as a polymorphic value inside
            // an Assignment (PN-6): every arm is a registered @Serializable subclass.
            polymorphic(civictech.cell.link.Interest::class) {
                subclass(civictech.cell.link.Interest.Total::class)
                subclass(civictech.cell.link.Interest.Empty::class)
                subclass(civictech.cell.link.Interest.Union::class)
                subclass(civictech.cell.link.Interest.Intersect::class)
                subclass(civictech.cell.link.Interest.Complement::class)
                subclass(civictech.cell.link.Interest.Ranges::class)
                subclass(civictech.cell.link.Interest.Slots::class)
            }
        }.let { kernelModule ->
            // app-contributed delta serializers (M17): ServiceLoader-discovered,
            // mirroring ContractRegistry's ContractModule discovery (C-5);
            // `plus` fails fast if a contribution collides with a kernel type
            java.util.ServiceLoader.load(WireSerializers::class.java, WireSerializers::class.java.classLoader)
                .fold(kernelModule) { acc, contribution -> acc + contribution.module }
        }
    }

    /** Serializes [contribute]/[withdraw]; readers never take it (see [json]). */
    private val registrationLock = Any()

    /**
     * Live contributions, in contribution order, guarded by [registrationLock].
     * Held by *identity* — a module withdraws exactly the instance it
     * contributed, even if two contributions compare equal.
     */
    private var contributions: List<WireSerializers> = emptyList()

    // ponytail: JSON as the M5 codec — kotlinx-serialization-json is already the
    // dependency and perf is out of scope; swap the format (CBOR) behind encode/decode
    // if profiling demands.
    //
    // @Volatile so encode/decode always read the *current* build: a rebuild
    // publishes a complete, immutable Json in one reference write, so a
    // concurrent encode sees either the whole old module or the whole new one,
    // never a partial one ([JAR1-REG-09] registration safety).
    @Volatile
    private var json: Json = build(emptyList())

    /**
     * ## Additive fields are forward-compatible only, not both ways (KE3-39)
     *
     * A new nullable field on a `@Serializable` payload (e.g.
     * [civictech.cell.control.StallNotice.Stall.slot]) needs no [VERSION]
     * bump when it defaults to `null`: this [Json] never sets
     * `encodeDefaults`, so kotlinx.serialization's default (`false`) omits an
     * unset field entirely, and an OLDER reader decoding a NEWER writer's
     * bytes — where the newer writer left the field unset — decodes
     * unchanged.
     *
     * The reverse is **not** safe. This [Json] never sets
     * `ignoreUnknownKeys` either, so it stands on kotlinx.serialization's
     * default of `false`. Once an upgraded peer emits a frame that actually
     * populates a field an older peer's build predates, the older peer's
     * decode throws [kotlinx.serialization.SerializationException] on the
     * unknown key rather than ignoring it — pinned concretely in
     * `kernel/src/test/kotlin/civictech/cell/wire/StallNoticeWireCompatTest.kt`.
     * (A *genuinely* older peer receiving a `STABILITY_FROZEN` `Stall` fails
     * earlier still, on the reason itself — the second hazard below; the
     * unknown-key failure is the one that fires when the reason is a constant
     * the older peer already knows.)
     * Consequence: a mixed-version mesh MUST NOT populate a newly-added
     * optional field until every peer has upgraded past the version that
     * introduced it (`doc/spec/40-distribution/42-replication.md`
     * §"Wire compatibility of additive fields (KE3-39)").
     *
     * Setting `ignoreUnknownKeys = true` would close **this** asymmetry — the
     * unknown-*key* one — but silences every unknown key on every decode, not
     * just a deliberately additive one, trading a loud failure on a malformed
     * or version-mismatched frame for a silent one everywhere. That is a
     * broader wire-behaviour change than this note authorizes; not done here.
     * It also would not touch the second hazard below.
     *
     * ### A second, independent hazard: an unknown enum CONSTANT
     *
     * Adding a case to an existing `@Serializable` enum is exactly as much a
     * wire change as adding a field, and `ignoreUnknownKeys` does **not**
     * cover it — that setting governs unrecognised JSON object *keys*, not an
     * unrecognised *value* of a key the reader already knows.
     * [civictech.cell.control.StallReason.STABILITY_FROZEN] is the concrete
     * case: it was added by the same change that added
     * [civictech.cell.control.StallNotice.Stall.slot], so a genuinely pre-KE3
     * peer decoding a `Stall(STABILITY_FROZEN)` frame throws on the
     * unrecognised `"reason":"STABILITY_FROZEN"` value before it ever reaches
     * the `slot` key — the failure fires even when `slot` is `null` and
     * therefore absent from the encoded object entirely, so this hazard is
     * independent of the additive-field one above. Pinned concretely in
     * `kernel/src/test/kotlin/civictech/cell/wire/StallNoticeWireCompatTest.kt`'s
     * "unknown enum constant hazard" test.
     *
     * Closing this would need `coerceInputValues = true` **plus a change at
     * the use site** — that setting substitutes the *declaring property's*
     * default value (or `null`, for a nullable property), not anything
     * belonging to the enum, so on its own it is inert here: `Stall.reason`
     * is neither nullable nor default-valued, and this frame still throws
     * under `coerceInputValues = true` (measured 2026-09-06) — or a dedicated
     * fallback constant with a custom serializer. Either
     * is a separate, deliberate wire-behaviour decision — trading a loud
     * rolling-upgrade failure for a peer that keeps running while quietly
     * misreading the reason on a control-plane notice — named here only as
     * the option, not taken.
     *
     * **Consequence: the additive-field constraint above covers newly-added
     * enum constants as well** (`doc/spec/40-distribution/42-replication.md`
     * §"Wire compatibility of additive fields (KE3-39)"): a mixed-version
     * mesh must not emit a newly-added enum constant, any more than it may
     * populate a newly-added optional field, until every peer has upgraded
     * past the version that introduced it.
     */
    private fun build(live: List<WireSerializers>): Json = Json {
        // `plus` fails fast if a contribution collides with a kernel type (or
        // with an earlier contribution) — unchanged from the once-built codec.
        serializersModule = live.fold(baselineModule) { acc, contribution -> acc + contribution.module }
        allowStructuredMapKeys = true // polymorphic delta keys encode as [k, v] arrays
        useArrayPolymorphism = true // ["SerialName", value] — works for primitive args too
    }

    /**
     * Fold a late [WireSerializers] contribution into the codec — the
     * registration seam for a module loaded after `WireCodec` was first
     * touched (JAR1 [JAR1-REG-08], arm 1). The codec's module is rebuilt from
     * the [baselineModule] plus every live contribution, so the delta types
     * this contribution registers encode and decode from the next
     * [encode]/[decode] onwards.
     *
     * Collisions fail fast: if [serializers] re-registers a type the kernel
     * (or an earlier contribution) already registered, the rebuild throws and
     * the codec is left on its previous module, unchanged.
     *
     * ## Contribution is PER PROCESS, and that is the host's job
     *
     * `WireCodec` is an `object`: this registry is process-global and process-
     * *local*. A peer in another JVM has its own, so a module type is decodable
     * there only if that process has itself loaded the module and called this
     * method — `:loader` hands its host the discovered [WireSerializers]
     * through `ModuleLoader`'s `onWireSerializers` seam and does nothing
     * further, having no transport dependency and no knowledge of any peer.
     * Wire identity travels as the `@SerialName` in the bytes; the `Class`
     * behind it does not travel at all, and each side decodes into its own
     * classloader's version of the type.
     *
     * A process that has not contributed refuses such bytes **loudly**
     * ([kotlinx.serialization.SerializationException] from [decode]), never silently — decided and
     * pinned by bug computenet-bb5b in
     * `civictech.loader.B13CrossLoaderWireIdentityTest`.
     *
     * @throws IllegalArgumentException when the contribution collides.
     */
    fun contribute(serializers: WireSerializers) {
        synchronized(registrationLock) {
            val next = contributions + serializers
            // build BEFORE publishing: a colliding contribution throws here,
            // leaving both `json` and `contributions` on the previous build.
            val rebuilt = build(next)
            json = rebuilt
            contributions = next
        }
    }

    /**
     * Remove a contribution previously passed to [contribute], by identity,
     * and rebuild the codec without it. Its delta types stop being encodable —
     * loudly, exactly as before the contribution. Withdrawing something that
     * was never contributed is a no-op (module unload, feature .4, consumes
     * this).
     */
    fun withdraw(serializers: WireSerializers) {
        synchronized(registrationLock) {
            val index = contributions.indexOfFirst { it === serializers }
            if (index < 0) return
            val next = contributions.filterIndexed { i, _ -> i != index }
            json = build(next)
            contributions = next
        }
    }

    /**
     * The [RegistryAnnounce] contract's generated id — the one contract whose
     * frames [encode] signs ([DSC1-ANN-01]: announcements only; data-plane
     * deltas are explicitly out of scope, SEC1/G-54). Resolved once, lazily,
     * because `ContractRegistry` is ServiceLoader-populated and this object
     * initializes early. Null only if the generated descriptor is missing, in
     * which case nothing is ever recognized as an announcement and nothing is
     * signed — fail *closed* on the emit side, since an unsigned announcement
     * is refused by a receiver that requires signing rather than trusted.
     */
    private val announceContractId: Long? by lazy {
        ContractRegistry.descriptor(RegistryAnnounce::class.java)?.contractId
    }

    /**
     * Whether [frame] is a [RegistryAnnounce] call — the one contract [encode]
     * signs and therefore the only one the ingress admission gate
     * ([AnnouncementAdmission]) judges ([DSC1-ANN-01]). Recognized by the same
     * [announceContractId] the emit side uses, so the two halves cannot drift
     * into disagreeing about what an announcement is.
     *
     * Fails **closed on the receive side too**: if the generated descriptor is
     * missing, [announceContractId] is null and nothing is recognized as an
     * announcement — which on this side means nothing is *verified*, so a side
     * that configured verification would silently stop verifying. That is the
     * same failure the emit side has (nothing gets signed), it is not
     * reachable in a build whose KSP output exists, and `ManifestDriftTest`
     * is what would notice a missing descriptor.
     */
    internal fun isAnnouncement(frame: WireFrame): Boolean {
        val id = announceContractId ?: return false
        return frame.type != HostedPortInvocation.Type.PORT_PROTOCOL && frame.contractId == id
    }

    /** @throws IllegalStateException when the invocation's contract has no `@Contract` ids (not wire-capable). */
    fun encode(invocation: HostedPortInvocation): ByteArray = encode(invocation, signer = null)

    /**
     * [encode], with announcement signing ([DSC1-ANN-01, 04],
     * [DSC1-WIRE-01..02]).
     *
     * When [signer] is non-null **and** the invocation is a [RegistryAnnounce]
     * call, the four optional signing fields are populated: a fresh counter
     * strictly greater than every counter that signer has assigned, an expiry
     * from its injected clock, the signer's key id, and the signature over the
     * injected canonical encoding of the announcement.
     *
     * Every other frame — a data-plane invocation, a `PORT_PROTOCOL` crossing,
     * anything at all from a `signer == null` side — takes exactly the path it
     * took before this feature and encodes to the same bytes: the four fields
     * default to `null` and `encodeDefaults` is off, so they contribute zero
     * bytes ([DSC1-WIRE-06]).
     */
    fun encode(invocation: HostedPortInvocation, signer: AnnouncementSigner?): ByteArray {
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
            val methodId = checkNotNull(inv.methodId)
            // Announcements only, and only from a signing side. `sign` assigns
            // the counter, so it must be called exactly once per outgoing
            // announcement — sign-at-send, never a cached or re-sent frame.
            val signed = if (signer != null && contractId == announceContractId) {
                signer.sign(contractId, methodId, invocation.cellRef, invocation.portName, inv.args)
            } else {
                null
            }
            WireFrame(
                contractId = contractId,
                methodId = methodId,
                cellRef = invocation.cellRef,
                portName = invocation.portName,
                type = invocation.type,
                context = inv.context,
                args = inv.args,
                signature = signed?.signature,
                signerKeyId = signed?.signerKeyId,
                sigCounter = signed?.counter,
                notAfter = signed?.notAfter,
                // PN-6: no longer sniff a routed command's epoch onto the frame.
                // The epoch was decorative at the point of use — admission checks
                // the shard's CURRENT interest, never the payload epoch (which the
                // journaled Assignment now carries authoritatively). The frame
                // field stays in the schema and decode keeps reading old frames for
                // one release; encode simply stops populating it.
                routingEpoch = null,
            )
        }
        return json.encodeToString(WireFrame.serializer(), frame).toByteArray()
    }

    /** @throws IllegalStateException on unknown version or ids (caller dead-letters). */
    fun decode(bytes: ByteArray): HostedPortInvocation = decodeFrame(bytes).invocation

    /**
     * Same decode as [decode], but also returns the parsed [WireFrame] —
     * the entry point the announcement-verification ingress gate
     * (computenet-ssa.4's successor task) needs to reach [WireFrame.signature]
     * and friends, which [decode] alone discards. [decode] is defined in
     * terms of this function precisely so its behavior (exceptions, the
     * resulting [HostedPortInvocation]) is unchanged for every existing
     * caller — this is purely an additional entry point, not a replacement.
     *
     * @throws IllegalStateException on unknown version or ids (caller dead-letters).
     */
    fun decodeFrame(bytes: ByteArray): DecodedWireFrame {
        val frame = json.decodeFromString(WireFrame.serializer(), bytes.decodeToString())
        check(frame.version == VERSION) { "unsupported wire version ${frame.version}" }
        return DecodedWireFrame(invocation(frame), frame)
    }

    private fun invocation(frame: WireFrame): HostedPortInvocation {
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
