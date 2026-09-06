package civictech.concord.driver.kernel

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.Replicable
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.replication.Replication
import civictech.concord.driver.CellId
import civictech.concord.driver.HostId
import civictech.concord.driver.LinkResult
import civictech.concord.value.Value
import java.util.UUID

/**
 * The `dist`-profile capability of the kernel driver (CONCORD-PLAN §3
 * "41/42/33 — Distribution", W4-A). It is **composed into** [KernelDriver]
 * (`driver.dist`), not a subclass — so the `dur` capability (W4-B,
 * `KernelDriverDur`) can extend the same base without a subclass collision
 * (CONCORD-PLAN §4 seam rule). [KernelDriver] defers to it at exactly two
 * additive hooks: `spawn` of a `replica-of` cell ([spawnReplica]) and `connect`
 * of a cross-host link ([connectCrossHost]).
 *
 * The binding leans on facts established by the kernel's own distribution tests
 * (`DistributedCollaborativeAppTest`, `ReplicationTest`, `BridgedGraphTest`):
 *
 * - **One controller, N hosts.** [KernelDriver] already spawns each named host
 *   on its own `SimulationController.scheduler()`, all sharing one
 *   `LocationRegistry`. Cells on different hosts are therefore genuine peers —
 *   a cross-host send is a real scheduler-queue hop (spec 33/41 P2 "queue hop"
 *   tier), not a same-thread call.
 * - **Replication is dataflow, not a second protocol** (spec 42): two
 *   `SetCell`s sharing a logical id, each handed to `Replication.replicate`,
 *   gossip their effective deltas over registry-routed links and converge to
 *   equal folds regardless of which replica accepted each write (`42-REPL-04`).
 * - **Cross-host links are routed streams** (spec 41 §Transport, M5.7): the
 *   local `connect` resolves the target inlet against *its own* host's cell
 *   table, so it cannot reach a cell on another host; a routed
 *   [HostedCellProxy] + `streamTo` installs the edge over the registry instead
 *   (host queue in-process; wire frames across a bridge — the same call).
 */
internal class KernelDriverDist(private val driver: KernelDriver) {

    /** The mergeable-set replication mesh over the driver's single shared registry (spec 42, one mesh). */
    private val replication by lazy { Replication(driver.registry) }

    /** Stable logical id per `replica-of` group; instance ids counted within a group. */
    private val logicalIds = LinkedHashMap<String, UUID>()
    private val instanceCounters = LinkedHashMap<String, Long>()

    /** The routed cross-host stream target: property `inlet` binds the target cell's `inlet` port. */
    private interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<Any?>>>
    }

    /**
     * The routed **gossip** target of [retransmit]: property `deltaInlet` binds the
     * target replica's [Replicable.deltaInlet] port, the port a peer's effective
     * deltas arrive on. Payload-agnostic (`Propagate<Any>`) because one binding
     * serves both mergeable families the mesh admits — `TaggedMapDelta` for
     * `ormap-source`, `SetDelta` for `set-source` — and the re-delivered payload is
     * an object this driver *recorded*, never one it constructs, so nothing here
     * needs its static type.
     */
    private interface GossipInletProxy {
        val deltaInlet: Use<Propagate<Any>>
    }

    /**
     * One recorded emission of a replica's delta outlet: the payload exactly as it
     * left, plus the wave coordinates it carried. [retransmit] re-delivers a
     * recorded [Emission] verbatim — it does not rebuild one from the scenario's
     * `op:`/`value:` — which is what makes a duplicate at a gossip inlet a genuine
     * *re-arrival of the same message* rather than a fabricated tag identity (the
     * objection `concord/schema/scenario.md` §`retransmit` records against
     * re-delivering to a tag-algebra fold).
     */
    private data class Emission(val position: Timestamp, val sourcePort: PortRef, val delta: Any)

    /** Replica cell ids, with their `replica-of` group: a re-delivery stays inside one mesh. */
    private val logicalOf = LinkedHashMap<CellId, String>()

    /**
     * Dot/tag source -> the replica that first emitted a dot stamped by it, i.e.
     * the replica that **minted** it. Attribution by first emission is exact rather
     * than heuristic: a dot leaves its minter's outlet before any peer can hold it,
     * so no peer can relay a dot this recorder has not already attributed. It is
     * how the binding learns a replica's own dot namespace without duplicating any
     * cell's private derivation of it.
     */
    private val dotOwner = LinkedHashMap<UUID, CellId>()

    /**
     * `(minting replica, dot counter)` -> the emission that first carried that dot.
     * This is the map a `retransmit` step's `(source:, counter:)` resolves through:
     * the Nth dot cell `source` minted, and the delta frame that carried it.
     */
    private val mintedDots = LinkedHashMap<Pair<CellId, Long>, Emission>()

    /**
     * Replica -> how many times its delta outlet has emitted, counted by the same
     * Observe-role tap [mintedDots] is filled from ([recordEmissionsOf]). The
     * counting unit is fixed by `concord/schema/scenario.md`'s "What a conforming
     * driver must observe" § `emission-count` (the `computenet-f94x` paragraph):
     * one increment per outlet emission event, whatever the frame carries — an
     * emission that relays a peer's dot, or one whose dots are all already
     * attributed, is still one emission, never zero and never one-per-delta. This
     * binding implements that unit rather than defining it; the `emission-count`
     * check asserts THAT an emission happened rather than what was in it. Counted
     * before the frame is inspected, so a frame that carries no wave context at
     * all is not silently uncounted.
     */
    private val emissionCounts = LinkedHashMap<CellId, Long>()

    /**
     * Place a `replica-of` cell into the replication mesh (spec 42): construct the
     * mergeable cell its `type:` names under the group's shared logical id with a fresh instance id,
     * hand it to [Replication.replicate] (which spawns it on [hostId] and links
     * it to every peer replica of the same logical id), and give it a co-hosted
     * read companion so `readView`/`replicas-converge` can fold it.
     *
     * Two catalog types bind honestly here, both members of the kernel's
     * mergeable (`Replicable`) family and each paired with the view that folds
     * its own delta shape: `set-source` → `SetCell` (OR-set, `SetDelta`) read
     * through a `set-view`, and `ormap-source` → `OrMapCell` (observed-remove
     * per-key map, `TaggedMapDelta`, 96 §E1.3) read through a `tagged-map-view`
     * (KE1-F4). Everything past the two is a real gap — single-writer and
     * pn-counter replication are decided-but-unbuilt on this seam — and is
     * surfaced loudly rather than mis-bound.
     *
     * [interest] (42-INTEREST-01, resolving the schema-gap in DISPUTES.md) is the
     * scenario's `interest:` descriptor, still in its neutral [Value] form; parsed
     * by [parseInterest] and staged on the shared [LocationRegistry][civictech.cell.host.LocationRegistry]
     * via `setInterest` **before** [Replication.replicate] — the gossip linker
     * ([civictech.cell.replication.Replication.maybeLink]) consults the interest
     * at link time, so it must already be recorded when `replicate` wires this
     * replica to its peers (`InterestScopedGossipTest`'s own `Mesh.start` does the
     * same "assign, then replicate" ordering). Absent/unparseable ⇒ no call is
     * made and the registry's own default (`Interest.Total`) applies — byte-
     * identical to a `replica-of` cell with no `interest:` (42-REPL-01/`42-REPL-LATE-01`
     * keep passing unchanged).
     */
    fun spawnReplica(hostId: HostId, cellId: CellId, type: String, logical: String, interest: Value? = null) {
        val host = driver.hostFor(if (hostId == "") null else hostId)
        val logicalId = logicalIds.getOrPut(logical) { UUID.randomUUID() }
        val instanceId = (instanceCounters[logical] ?: 0L).also { instanceCounters[logical] = it + 1 }
        val ref = CellRef(logicalId, instanceId)

        // The replica cell and the catalog id of the companion that folds ITS delta
        // shape — the two travel together, because a `tagged-map-view` cannot fold a
        // `SetDelta` stream and a `set-view` cannot fold a `TaggedMapDelta` one.
        val binding: Pair<Replicable<*>, String> = when (type) {
            "set-source" -> SetCell<Any?>(ref) to "set-view"
            "ormap-source" -> OrMapCell<Any?, Any?>(ref) to "tagged-map-view"
            else -> throw UnsupportedCatalogBinding(
                "replica-of is bound only for 'set-source' (the kernel's Replicable OR-set SetCell) " +
                    "and 'ormap-source' (the Replicable observed-remove OrMapCell); type '$type' has no " +
                    "honest replicated binding today (single-writer/pn-counter replication is " +
                    "decided-but-unbuilt — CONCORD-PLAN §5 / spec 42).",
            )
        }
        val (replica, companionType) = binding
        logicalOf[cellId] = logical
        // Record this replica's emissions BEFORE it joins the mesh, so the very
        // first frame it ever puts on the wire — including the catch-up a peer's
        // link install pulls out of it — is attributed. A dot minted before the
        // recorder existed would be un-retransmittable and, worse, could be
        // mis-attributed to whichever peer relayed it first.
        recordEmissionsOf(cellId, replica)

        // 42-INTEREST-01: stage the interest assignment BEFORE replicate — the
        // linker reads it at link time, so it must be recorded first.
        parseInterest(interest)?.let { driver.registry.setInterest(replica.ref, it) }
        // `replicate` spawns the replica on the host and wires the gossip mesh to
        // every peer already published under this logical id (and, via onPublish,
        // every peer that joins later).
        replication.replicate(replica, host)

        // A co-hosted read companion: the replica re-emits every effective delta
        // (local writes AND merged gossip — `applyRemote` → `outlet.originate`)
        // on its `outlet`, so a view cell folding that outlet reflects the
        // replica's converged membership — which is what readView(replicaId) reads.
        // Built through the catalog (`set-view` / `tagged-map-view`) rather than by hand, so the
        // companion's observation stream is recorded at the fold like every other
        // view's ([RecordedView]) instead of on the sink's own dispatcher thread,
        // which the runner never waits for before reading the log.
        val built = KernelCatalog.build(companionType, emptyMap())
        val companion = built.cell
        host.managementInlet.call.spawn(companion)
        host.managementInlet.call.connect(replica.ref, "outlet", companion.ref, "inlet")

        driver.cells[cellId] = KernelDriver.Bound(
            ref = replica.ref,
            type = type,
            host = host,
            cell = replica,
            sink = built.sink,
            viewKind = built.viewKind,
            log = built.observations ?: mutableListOf(),
        )
    }

    /**
     * Install a link whose endpoints live on different hosts (spec 41 §Transport).
     * The local `connect` resolves the target inlet against the source host's own
     * cell table and so cannot cross a host boundary; the kernel's answer is a
     * routed stream — a [HostedCellProxy] on the target's inlet (resolved through
     * the shared registry) fed by the source outlet's `streamTo`. Catch-up (spec
     * 21 `outlet.catchUpOnLinked`) rides the same routed path, so a cross-host
     * link installed mid-run brings its consumer current exactly as a co-hosted
     * one does.
     *
     * Only a target whose inlet is the primary `inlet` port carrying a set delta
     * stream is bound (the shape the dist corpus uses — pipelines split at a
     * set-view/union/passthrough edge). Other cross-host inlet shapes are a real
     * gap surfaced loudly rather than mis-wired.
     */
    fun connectCrossHost(
        from: CellId,
        src: KernelDriver.Bound,
        to: CellId,
        dst: KernelDriver.Bound,
        inlet: String?,
        outlet: String?,
    ): LinkResult {
        val inletName = KernelCatalog.inletName(dst.type, inlet, dst.waveAligned)
        if (inletName != "inlet") {
            throw UnsupportedCatalogBinding(
                "cross-host connect is bound only for targets whose inlet is the primary `inlet` port " +
                    "(set-view/union/passthrough); target '$to' (type ${dst.type}) resolves inlet '$inletName' — " +
                    "a two-input cross-host operator edge is not bound (CONCORD-PLAN §5).",
            )
        }
        val outletName = KernelCatalog.outletName(src.type, outlet)

        @Suppress("UNCHECKED_CAST")
        val srcOutlet = (PortRegistry.of(src.cell)[outletName]
            ?: throw UnsupportedCatalogBinding("source '$from' (type ${src.type}) has no outlet port '$outletName'"))
            as FanOutlet<Propagate<SetDelta<Any?>>>

        val routed = (HostedCellProxy.create(dst.ref, driver.registry, DeltaInletProxy::class.java)
            as DeltaInletProxy).inlet.call

        val link = srcOutlet.streamTo(routed)

        val linkRef = UUID.randomUUID().toString()
        driver.linksByRef[linkRef] = link
        driver.linksByEndpoint[driver.endpointKey(from, to, inlet, outlet)] = linkRef
        driver.linksByCell.getOrPut(from) { mutableSetOf() } += linkRef
        driver.linksByCell.getOrPut(to) { mutableSetOf() } += linkRef
        return LinkResult.Connected(linkRef)
    }

    /**
     * Migrate [cellId] to host [targetHostId] (spec 33). The kernel's unit of
     * mobility is the **host**, not the cell (`ManagedHost.migrate` drains the
     * whole host and moves every cell it holds), so a scenario migrating one cell
     * places it alone on its own host; migrate then carries just that cell. State
     * travels through a forced serialization round-trip and the cell keeps its
     * ref, so its routed edges (installed by [connectCrossHost]) re-resolve to the
     * new host and parked traffic replays in order — the spec-33 no-loss / FIFO
     * contract the kernel's `SubchainMigrationTest` proves over 100 seeds.
     *
     * Independent single-cell migration is **not** a kernel capability (there is
     * no per-cell migrate API); this binding drives it only in the honest
     * one-cell-per-host arrangement. The broader claim is filed in DISPUTES.md.
     */
    fun migrate(cellId: CellId, targetHostId: HostId) {
        val bound = driver.cells.getValue(cellId)
        val source = bound.host
        val target = driver.hostFor(targetHostId)
        val moving = driver.cells.entries.filter { it.value.host === source }.map { it.key }
        // `migrate` is an enqueued management op (the drain protocol runs across
        // several scheduler steps); drive it to completion here so it is a
        // migration barrier — the cell is republished on the target before any
        // later step routes to it. (A drain IS a quiescence, spec 33.)
        source.managementInlet.call.migrate(target.managementInlet)
        driver.quiesce(Int.MAX_VALUE)
        // Re-point the moved cells' bindings so later apply/readView route to the
        // target host (Bound is immutable — replace with a host-updated copy).
        moving.forEach { id -> driver.cells[id] = driver.cells.getValue(id).copy(host = target) }
    }

    /**
     * The kernel binding of the `retransmit` verb at a **replication mesh**
     * (`computenet-j2x.4.6`, retiring `[KE1-37]`): a duplicate delivery of a delta
     * the mesh has already gossiped, injected at [cellId]'s [Replicable.deltaInlet]
     * — the port a peer replica's effective deltas arrive on.
     *
     * The dur binding's target ([KernelDriverDur.retransmit]) decides a duplicate
     * with an `Effectful` processed-frontier. A replica decides it with the dot
     * algebra instead: `novelty` keeps only dot information the fold has never
     * held, so a re-delivered dot reduces to nothing and `absorb`/`originate`
     * re-emit nothing — echo termination (spec 40/42 §Design as implemented; 96
     * §E1.3). That is a second, equally real "something decides whether to act on
     * this twice", which is why this target is admitted where a plain core cell
     * still is not.
     *
     * **Nothing is fabricated.** `concord/schema/scenario.md` §`retransmit` refuses
     * re-delivery to a tag-algebra fold on the ground that it "would additionally
     * need the original delivery's tag identity — which a scenario does not name
     * and the binding will not fabricate". This binding does not fabricate one: it
     * replays the [Emission] the source replica actually made — same delta object,
     * same `(sourceId, counter)` wave position, same source port — recorded by an
     * Observe-role tap on that replica's outlet ([recordEmissionsOf]). The
     * scenario's `(source:, counter:)` selects **which** already-minted dot to
     * re-deliver (the `counter`th dot cell `source` minted), and its `op:`/`value:`
     * must *describe* that dot or the step is refused ([describes]) — so a step
     * cannot silently name a coordinate the mesh never produced.
     *
     * Every refusal below is loud, never a weaker delivery:
     *
     * - **`source` must be a replica of the same `replica-of` group**, and not the
     *   target itself: a gossip inlet receives a *peer's* delta, and a re-delivery
     *   from outside the mesh is not a duplicate of anything.
     * - **`(source, counter)` must name a dot that replica really minted.** A
     *   coordinate nothing emitted has no already-seen delta to re-deliver, and
     *   inventing one would make the scenario assert idempotence over a dot the
     *   fold has never held — the fresh-dot mistake this whole entry exists to
     *   avoid.
     * - **`op:`/`value:` must describe the recorded dot** — otherwise the step's
     *   own payload fields would be a lie about what was re-delivered.
     * - **`inlet:` must be the gossip port** (`deltaInlet`, this binding's default),
     *   not the write inlet: delivering a peer delta to `inlet` would be a new
     *   write, not a re-arrival.
     * - **`baseline:` is unbound here.** A catch-up anchor is read by an `Effectful`
     *   processed-frontier ([24-DUR-07]/[24-DUR-08]); a replica's dot algebra reads
     *   no such thing, so accepting one would stamp a frame nothing consults.
     */
    fun retransmit(
        cellId: CellId,
        inlet: String?,
        source: CellId,
        counter: Long,
        op: String,
        value: Value?,
        baseline: Map<CellId, Long>?,
    ) {
        val target = driver.cells.getValue(cellId)
        if (baseline != null) {
            throw UnsupportedCatalogBinding(
                "retransmit at replica '$cellId' states a baseline: anchor; a catch-up baseline is read by an " +
                    "Effectful processed-frontier ([24-DUR-07]/[24-DUR-08]), and a replica's gossip inlet decides " +
                    "a duplicate by the dot algebra instead — it would stamp a frame nothing consults",
            )
        }
        val inletName = inlet ?: DELTA_INLET
        if (inletName != DELTA_INLET) {
            throw UnsupportedCatalogBinding(
                "retransmit at replica '$cellId' names inlet '$inletName'; a peer replica's deltas arrive on " +
                    "'$DELTA_INLET' — delivering one to the write inlet would be a new write, not a re-arrival",
            )
        }
        if (source == cellId) {
            throw UnsupportedCatalogBinding(
                "retransmit at replica '$cellId' names itself as source; a gossip inlet receives a PEER's delta, " +
                    "so a self-addressed re-delivery duplicates nothing that ever crossed the mesh",
            )
        }
        val sourceGroup = logicalOf[source]
        if (sourceGroup == null || sourceGroup != logicalOf[cellId]) {
            throw UnsupportedCatalogBinding(
                "retransmit at replica '$cellId' (replica-of '${logicalOf[cellId]}') names source '$source', " +
                    "which is not a replica of the same logical cell; a duplicate at a gossip inlet is a " +
                    "re-arrival of a peer's delta, and only a peer of the same mesh ever sent one",
            )
        }
        val recorded = mintedDots[source to counter]
            ?: throw UnsupportedCatalogBinding(
                "retransmit at replica '$cellId' names position (source: '$source', counter: $counter), but " +
                    "'$source' has minted no such dot — this binding re-delivers a delta the mesh actually " +
                    "gossiped and will not fabricate a dot the fold has never held",
            )
        val dot = Timestamp(dotSourceOf(source), counter)
        if (!describes(op, value, dot, recorded.delta)) {
            throw UnsupportedCatalogBinding(
                "retransmit at replica '$cellId' states op '$op' value $value, which does not " +
                    "describe the dot '$source' minted at counter $counter (${recorded.delta}); the step's " +
                    "payload fields must name the delivery being duplicated",
            )
        }
        val routed = HostedCellProxy.create(target.ref, driver.registry, GossipInletProxy::class.java)
            as GossipInletProxy
        // Ride the target host's intake under the ORIGINAL wave coordinates, the way
        // the dur binding rides it: HostedCellProxy stamps CurrentContext into the
        // Invocation it enqueues, so the frame arrives indistinguishable from the
        // gossip delivery it duplicates.
        CurrentContext.with(MessageContext(recorded.position, recorded.sourcePort)) {
            routed.deltaInlet.call.propagate(recorded.delta)
        }
    }

    /**
     * Install the Observe-role tap that records [cellId]'s outlet emissions
     * ([Emission]) — an *observer*, never a consumer: it is uncounted by the SPSC
     * funnel, fires before consumers, and gates no wave (spec 20/23 §Taps). The
     * tap is un-negotiated because its target is an ad-hoc `Use.fixed` endpoint
     * with no handshake surface, which is the documented fall-through
     * ([FanOutlet.tap]'s PN-12 note).
     */
    private fun recordEmissionsOf(cellId: CellId, replica: Replicable<*>) {
        @Suppress("UNCHECKED_CAST")
        val outlet = (PortRegistry.of(replica)["outlet"]
            ?: throw UnsupportedCatalogBinding("replica '$cellId' registers no 'outlet' port"))
            as FanOutlet<Propagate<Any>>
        outlet.tap(
            Use.fixed(Propagate<Any> { delta -> record(cellId, delta) }, PortRef.generate()),
            negotiated = false,
        )
    }

    /** Attribute every dot in one emission, and index the minter's own dots by counter. */
    private fun record(cellId: CellId, delta: Any) {
        // The count first, and unconditionally: an emission is an emission whether
        // or not this recorder can attribute a dot in it.
        emissionCounts[cellId] = (emissionCounts[cellId] ?: 0L) + 1L
        val ctx = CurrentContext.get() ?: return
        val emission = Emission(ctx.timestamp, ctx.sourcePort, delta)
        dotsOf(delta).forEach { dot ->
            if (dotOwner.getOrPut(dot.sourceId) { cellId } == cellId) {
                // putIfAbsent: a later emission that merely RELAYS the minter's own
                // dot (a `dels` cover, a catch-up) must not displace the frame that
                // first carried it — that frame is the one a duplicate re-delivers.
                mintedDots.putIfAbsent(cellId to dot.counter, emission)
            }
        }
    }

    /**
     * How many times [cellId]'s delta outlet has emitted so far this run
     * ([emissionCounts]).
     *
     * A replica that has emitted nothing yet answers `0` **honestly**: the tap is
     * installed by [spawnReplica] before the replica joins the mesh, so a mesh
     * member is observed from its very first frame and an absent entry means "the
     * tap saw nothing", never "there is no tap". A cell this binding never placed
     * in a mesh has no tap at all and is refused loudly rather than answering 0 —
     * that 0 would be indistinguishable from a real quiet outlet.
     */
    fun emissionCount(cellId: CellId): Long {
        if (cellId !in logicalOf) {
            throw UnsupportedCatalogBinding(
                "emission-count at '$cellId': no Observe-role emission tap is installed on it — only a " +
                    "`replica-of` replica placed in a replication mesh by this binding is tapped, and " +
                    "answering 0 for an untapped outlet would be a vacuous pass",
            )
        }
        return emissionCounts[cellId] ?: 0L
    }

    /** The dot namespace [cellId] mints under, learned by first emission ([dotOwner]). */
    private fun dotSourceOf(cellId: CellId): UUID =
        dotOwner.entries.first { it.value == cellId }.key

    /** Every dot a mergeable delta carries, live or tombstoned, in both admitted families. */
    private fun dotsOf(delta: Any): Sequence<Timestamp> = when (delta) {
        is TaggedMapDelta<*, *> ->
            delta.puts.values.asSequence().flatMap { it.keys.asSequence() } +
                delta.dels.values.asSequence().flatMap { it.asSequence() }
        is SetDelta<*> ->
            delta.adds.values.asSequence().flatMap { it.asSequence() } +
                delta.dels.values.asSequence().flatMap { it.asSequence() }
        else -> emptySequence()
    }

    /**
     * Does the step's `op:`/`value:` describe [dot] as [delta] carries it? Only the
     * dot-minting ops are expressible: an OR-map `put` and an OR-set `add` mint a
     * dot. An OR-map `remove` still mints none — it tombstones the put-dots it
     * already observed live — so there is no `(source, counter)` position for it
     * to name. An OR-set `remove` is different since computenet-v2ka: it mints its
     * own del-dot into `SetDelta.dels`, so that dot DOES have a nameable
     * `(source, counter)` position; `describes` below has no case for it, which is
     * a real gap in what this driver can express rather than a property of the
     * delta shape.
     */
    @Suppress("UNCHECKED_CAST")
    private fun describes(op: String, value: Value?, dot: Timestamp, delta: Any): Boolean = when {
        delta is TaggedMapDelta<*, *> && op == "put" -> {
            val (key, expected) = keyValue(value) ?: return false
            (delta.puts as Map<Any?, Map<Timestamp, Any?>>)[key]?.let { dots ->
                dots.containsKey(dot) && dots[dot] == expected
            } == true
        }
        delta is SetDelta<*> && op == "add" ->
            (delta.adds as Map<Any?, Set<Timestamp>>)[KernelCatalog.unwrap(value)]?.contains(dot) == true
        else -> false
    }

    /** A `put`'s `[key, value]` (or `{key:, value:}`) payload, unwrapped; `null` when malformed. */
    private fun keyValue(value: Value?): Pair<Any?, Any?>? = when {
        value is Value.ListVal && value.items.size == 2 ->
            KernelCatalog.unwrap(value.items[0]) to KernelCatalog.unwrap(value.items[1])
        value is Value.MapVal && "key" in value.entries ->
            KernelCatalog.unwrap(value.entries["key"]) to KernelCatalog.unwrap(value.entries["value"])
        else -> null
    }

    /**
     * Parse a scenario's `interest:` [Value] (the [civictech.concord.schema.InterestSpec]
     * descriptor, lowered to the neutral value model by the runner) into a real
     * `civictech.cell.link.Interest` (42-INTEREST-01). `null`/unrecognized ⇒ `null`
     * (no [civictech.cell.host.LocationRegistry.setInterest] call — the registry's
     * own default, [civictech.cell.link.Interest.Total], applies). Precedence when
     * more than one field is present: `total` > `empty` > `slots` > `ranges`.
     */
    private fun parseInterest(value: Value?): civictech.cell.link.Interest? {
        val fields = (value as? Value.MapVal)?.entries ?: return null
        fun bool(key: String) = (fields[key] as? Value.BoolVal)?.value == true
        return when {
            bool("total") -> civictech.cell.link.Interest.Total
            bool("empty") -> civictech.cell.link.Interest.Empty
            fields["slots"] is Value.ListVal -> {
                val slots = (fields["slots"] as Value.ListVal).items.map { (it as Value.IntVal).value.toInt() }.toSet()
                val totalSlots = (fields["total-slots"] as? Value.IntVal)?.value?.toInt()
                    ?: error("interest: slots given without total-slots")
                civictech.cell.link.Interest.Slots(slots, totalSlots)
            }
            fields["ranges"] is Value.ListVal -> {
                val ranges = (fields["ranges"] as Value.ListVal).items.map { r ->
                    val bounds = (r as Value.ListVal).items
                    civictech.cell.link.Interest.Ranges.Range(
                        (bounds[0] as Value.IntVal).value,
                        (bounds[1] as Value.IntVal).value,
                    )
                }
                civictech.cell.link.Interest.Ranges(ranges)
            }
            else -> null
        }
    }

    private companion object {
        /** [Replicable.deltaInlet]'s port name — the gossip port, and `retransmit`'s default here. */
        const val DELTA_INLET = "deltaInlet"
    }
}
