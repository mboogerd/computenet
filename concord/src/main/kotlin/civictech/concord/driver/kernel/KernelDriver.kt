package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Stateful
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.observe.ObservationSink
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.concord.driver.Blob
import civictech.concord.driver.CellId
import civictech.concord.driver.DeadLetter
import civictech.concord.driver.Driver
import civictech.concord.driver.Effect
import civictech.concord.driver.HostId
import civictech.concord.driver.LinkRef
import civictech.concord.driver.LinkResult
import civictech.concord.driver.QuiesceReport
import civictech.concord.driver.ReadCursor
import civictech.concord.driver.ReadEntry
import civictech.concord.driver.ReadPage
import civictech.concord.driver.WavePlane
import civictech.concord.value.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID

/**
 * Binding #1 of the Concord [Driver] SPI (CONCORD-PLAN §1.4, W1-A): the
 * in-process kernel driver. This is the **only** package that imports
 * `civictech.cell.*`; the harness above it speaks the neutral SPI alone.
 *
 * Each driver instance owns one deterministic [SimulationController] seeded by an
 * opaque run index (the schedule-sweep mapping, §1.4): a fresh `KernelDriver(seed)`
 * per run reproduces one schedule locally. `spawn`/`connect` lower onto
 * `Use<HostManagementApi>` calls (which drive the controller to completion,
 * being awaited); `apply` routes an op through the host router; `quiesce` runs
 * the controller to idle within a step budget; `readView` reads an
 * [ObservationSink]. Views are bound as hosted `ObserveCell`s (see [KernelCatalog]).
 */
class KernelDriver(seed: Long? = null) : Driver {

    internal val controller = SimulationController(seed)
    internal val registry = LocationRegistry()

    private val hosts = LinkedHashMap<HostId, ManagedHost>()
    internal val cells = LinkedHashMap<CellId, Bound>()

    /**
     * The `dist`-profile capability (W4-A), composed in rather than inherited so
     * the `dur` capability (W4-B, `KernelDriverDur`) can extend the same base
     * without a subclass collision (CONCORD-PLAN §4 seam). Created lazily so a
     * pure `core` run never touches replication/bridge machinery. The additive
     * hooks below ([spawn]'s `replica-of` branch, [connect]'s cross-host branch)
     * are the only places this base defers to it.
     */
    internal val dist: KernelDriverDist by lazy { KernelDriverDist(this) }
    internal val linksByRef = LinkedHashMap<LinkRef, civictech.cell.link.Link>()
    /**
     * Endpoint → [LinkRef] map (W3-0): a scenario names a link by its endpoints
     * (`DisconnectStep`), but the SPI's [disconnect] takes an opaque ref, so the
     * driver records the mapping on every successful [connect] and resolves it in
     * [disconnectEndpoint]. Keyed by `from|to|inlet|outlet` (the same identity the
     * runner replays), so a later disconnect resolves the exact link.
     */
    internal val linksByEndpoint = LinkedHashMap<String, LinkRef>()
    /** Link refs incident to each cell (either endpoint), so a `despawn` can unlink them all (15-DESPAWN). */
    internal val linksByCell = LinkedHashMap<CellId, MutableSet<LinkRef>>()
    private val deadLetters = mutableListOf<DeadLetter>()

    /**
     * The `dur`-profile capability (W4-B), kept in its own file so W4-A's
     * `KernelDriverDist` and this durability code never edit the same source
     * (CONCORD-PLAN §4 seam). It owns the reserved durable host
     * ([KernelDriverDur.DUR_HOST]); every verb below delegates to it when the
     * target cell lives there. For a non-`dur` scenario nothing is spawned onto
     * the durable host, so it stays inert.
     */
    private val dur = KernelDriverDur(controller, registry) { deadLetters += it }

    /** Cell ids delegated to [dur] (durable-host members + `journal` controllers). */
    private val durCells = mutableSetOf<CellId>()

    /**
     * Cell ids placed into a replication mesh by [dist] (`replica-of`). Held HERE
     * rather than read back out of [dist] so a pure `core` run never forces the
     * lazy dist capability just to answer "is this a replica?".
     */
    private val distReplicas = mutableSetOf<CellId>()

    /** The implicit host single-host (non-`dist`) scenarios spawn onto. */
    private val defaultHostId: HostId = "\u0000default"

    internal data class Bound(
        val ref: CellRef,
        val type: String,
        val host: ManagedHost,
        val cell: Cell,
        val sink: ObservationSink<*>?,
        val viewKind: KernelCatalog.ViewKind,
        /**
         * Recorded observation stream (settled values), for [observationLog] — the
         * list [KernelCatalog.Built.observations] wired into the cell's own fold, so
         * every entry is appended synchronously on the folding (i.e. controller)
         * thread. See [RecordedView] for why it is not collected through
         * [ObservationSink.onChange].
         */
        val log: MutableList<Value> = mutableListOf(),
        /**
         * When the cell declared `glitch-free: true`, the downstream
         * [civictech.cell.consistency.GlitchFreeCell] its output is routed through
         * (spawned in [spawn]). A downstream [connect] reads *this* cell's outlet,
         * so the wave-aligned stream is what the consumer sees.
         */
        val outletDelegate: CellRef? = null,
        /**
         * The bound cell coalesces at the operator itself
         * ([KernelCatalog.Built.waveAligned]) — it needs no [outletDelegate]
         * wrapper, and its neutral `left`/`right` arms resolve onto a single
         * unrestricted `inlet` (see [KernelCatalog.inletName]).
         */
        val waveAligned: Boolean = false,
    )

    override fun createHost(hostId: HostId) {
        // The reserved durable host is owned by the dur capability, never a core host.
        if (hostId == KernelDriverDur.DUR_HOST) return
        hostFor(hostId)
    }

    internal fun hostFor(hostId: HostId?): ManagedHost {
        val key = hostId ?: defaultHostId
        return hosts.getOrPut(key) {
            ManagedHost(scheduler = controller.scheduler(), registry = registry).also { host ->
                host.deadLetterOutlet.subscribe(
                    Use.fixed(
                        Propagate<civictech.cell.host.DeadLetter> { dl ->
                            deadLetters += DeadLetter(host = key, cell = null, reason = dl.description)
                        },
                        PortRef.generate(),
                    ),
                )
            }
        }
    }

    override fun spawn(hostId: HostId, cellId: CellId, type: String, params: Map<String, Value>) {
        // dur hook (W4-B): a cell on the reserved durable host (or a `journal`
        // controller) is the dur capability's; short-circuit before any core or
        // dist placement. A durable cell is never also a dist replica.
        if (hostId == KernelDriverDur.DUR_HOST || type == "journal") {
            durCells += cellId
            dur.spawn(cellId, type, params)
            return
        }
        // dist hook (W4-A): a `replica-of` cell is placed into a replication mesh
        // (shared logical id across hosts) instead of spawned as a plain cell.
        // `interest` (42-INTEREST-01, W4-A followup) rides along unparsed — the
        // dist capability owns turning it into a real `Interest` and staging it on
        // the registry before the replica joins the mesh.
        (params["replica-of"] as? Value.StrVal)?.value?.let { logical ->
            dist.spawnReplica(hostId, cellId, type, logical, params["interest"])
            distReplicas += cellId
            return
        }
        val host = hostFor(if (hostId == "" ) defaultHostId else hostId)
        val built = KernelCatalog.build(type, params)
        val ref = host.managementInlet.call.spawn(built.cell)
        // `glitch-free: true` (spec 20/22, GlitchFreeOperatorSuiteTest construction):
        // spawn a downstream GlitchFreeCell and route the operator's outlet through
        // it over a real host link (so the frontier sees EdgeOpen/Progress). A
        // downstream connect then reads the gf's outlet via [outletDelegate].
        val delegate = if (built.glitchFree) {
            @Suppress("UNCHECKED_CAST")
            val gf = GlitchFreeCell(civictech.cell.Propagate::class.java as Class<civictech.cell.Propagate<Any>>)
            val gfRef = host.managementInlet.call.spawn(gf)
            host.managementInlet.call.connect(ref, "outlet", gfRef, "inlet")
            gfRef
        } else {
            null
        }
        val bound = Bound(
            ref, type, host, built.cell, built.sink, built.viewKind,
            // The observation stream is captured at the fold by the catalog
            // ([RecordedView]) — one entry per settled change, plus the initial
            // catch-up value — so it is complete and run-identical by the time
            // quiescence returns. Registering an `onChange` listener here instead
            // would hand the capture to the sink's own dispatcher thread, which the
            // runner never waits for.
            log = built.observations ?: mutableListOf(),
            outletDelegate = delegate,
            waveAligned = built.waveAligned,
        )
        cells[cellId] = bound
    }

    override fun connect(
        from: CellId,
        to: CellId,
        inlet: String?,
        outlet: String?,
        role: String?,
    ): LinkResult {
        // dur hook (W4-B): an edge touching a durable-host cell is resolved by the
        // dur capability (its endpoints are not in the core `cells` map), so it must
        // short-circuit before the getValue lookups below.
        if (from in durCells || to in durCells) return dur.connect(from, to, inlet, outlet, role)
        val src = cells.getValue(from)
        val dst = cells.getValue(to)
        // dist hook (W4-A): a link whose endpoints live on different hosts cannot
        // use the local `connect` (its target-inlet resolution is host-local); it
        // is installed as a routed streaming edge over the registry instead.
        if (src.host !== dst.host) {
            return dist.connectCrossHost(from, src, to, dst, inlet, outlet)
        }
        val inletName = KernelCatalog.inletName(dst.type, inlet, dst.waveAligned)
        // A glitch-free source exposes its wave-aligned stream on the downstream
        // GlitchFreeCell's `outlet`; read from the delegate so consumers see the
        // aligned deltas, not the operator's raw (torn) output.
        val (srcRef, outletName) = if (src.outletDelegate != null) {
            src.outletDelegate to "outlet"
        } else {
            src.ref to KernelCatalog.outletName(src.type, outlet)
        }
        val result = src.host.managementInlet.call.connect(srcRef, outletName, dst.ref, inletName)
        return when (result) {
            is civictech.cell.link.LinkResult.Connected -> {
                val linkRef = UUID.randomUUID().toString()
                linksByRef[linkRef] = result.link
                linksByEndpoint[endpointKey(from, to, inlet, outlet)] = linkRef
                linksByCell.getOrPut(from) { mutableSetOf() } += linkRef
                linksByCell.getOrPut(to) { mutableSetOf() } += linkRef
                LinkResult.Connected(linkRef)
            }
            is civictech.cell.link.LinkResult.Rejected -> LinkResult.Rejected(result.reason)
            civictech.cell.link.LinkResult.Deferred ->
                LinkResult.Rejected("deferred (cross-host handshake; not observable in-process)")
        }
    }

    override fun disconnect(linkRef: LinkRef): LinkResult {
        val link = linksByRef.remove(linkRef)
            ?: return LinkResult.Rejected("unknown link ref '$linkRef'")
        linksByEndpoint.values.remove(linkRef)
        linksByCell.values.forEach { it.remove(linkRef) }
        link.unlink()
        return LinkResult.Connected(linkRef)
    }

    /**
     * Disconnect the link a `DisconnectStep` names by its endpoints (W3-0). The
     * driver holds the endpoint → [LinkRef] map (populated on [connect]), so this
     * resolves the ref and delegates to [disconnect]. Unknown endpoints resolve to
     * [LinkResult.Rejected] so a `disconnect … expect: rejected` is expressible.
     */
    fun disconnectEndpoint(from: CellId, to: CellId, inlet: String?, outlet: String?): LinkResult {
        val ref = linksByEndpoint[endpointKey(from, to, inlet, outlet)]
            ?: return LinkResult.Rejected("no link $from->$to (inlet=$inlet, outlet=$outlet) to disconnect")
        return disconnect(ref)
    }

    internal fun endpointKey(from: CellId, to: CellId, inlet: String?, outlet: String?): String =
        "$from|$to|${inlet ?: ""}|${outlet ?: ""}"

    override fun apply(cellId: CellId, op: String, value: Value?) {
        if (cellId in durCells) return dur.apply(cellId, op, value)
        val bound = cells.getValue(cellId)
        val call = KernelCatalog.op(bound.type, op, value)
        val invocation = Invocation(call.methodName, call.parameterTypes, call.args)
        bound.host.routerInlet.call.route(bound.ref, "inlet", invocation)
    }

    override fun quiesce(budget: Int): QuiesceReport {
        var steps = 0
        while (steps < budget) {
            if (!controller.step()) return QuiesceReport(settled = true, steps = steps)
            steps++
        }
        // budget exhausted with work still pending: report unsettled (diagnostic, never a golden — P1)
        return QuiesceReport(settled = false, steps = steps)
    }

    override fun readView(cellId: CellId): Value {
        if (cellId in durCells) return dur.readView(cellId)
        val bound = cells.getValue(cellId)
        val sink = bound.sink ?: error("cell '$cellId' (type ${bound.type}) is not a view")
        return KernelCatalog.readView(bound.viewKind, sink.current())
    }

    override fun observationLog(cellId: CellId): List<Value> =
        if (cellId in durCells) dur.observationLog(cellId) else cells.getValue(cellId).log.toList()

    /**
     * The kernel binding of the bounded read (V1C-CONCORD): one
     * `ManagedHost.readState` per page, each answered on the cell's own
     * execution context as one scheduler task, driven to completion here.
     *
     * The pending answer is a future, so the controller is stepped until it
     * completes — the same shape [quiesce] uses, bounded so a wedged read
     * fails loudly instead of hanging the sweep. Every non-page answer is a
     * *decided* refusal the kernel names, and every one of them is an
     * authoring error at this seam (reading a cell that is not pageable, or
     * asking for a bound the family refuses), so each surfaces as a loud
     * failure rather than a silently weaker answer — which is the whole point
     * of the primitive.
     */
    override fun readState(cellId: CellId, cursor: ReadCursor?, limit: Int): ReadPage {
        val bound = cells.getValue(cellId)
        val pending = bound.host.readState(bound.ref, StateRead(cursor = cursor as Cursor?, limit = limit))
        var steps = 0
        while (!pending.isDone && steps < READ_STEP_BUDGET) {
            if (!controller.step()) break
            steps++
        }
        val result = pending.getNow(null)
            ?: error("read-state on '$cellId' did not complete within $READ_STEP_BUDGET scheduler step(s)")
        return when (result) {
            is StateReadResult.Page -> ReadPage(
                entries = result.page.entries.map { readEntry(cellId, it) },
                next = result.page.next,
                frontier = result.page.frontier?.toString(),
                exclusivesElided = result.page.exclusivesElided,
            )
            is StateReadResult.Unbounded ->
                error("read-state on '$cellId' answered a whole, unbounded copy — the harness never asks for one")
            is StateReadResult.Unavailable ->
                error("read-state on '$cellId' (type ${bound.type}) is unavailable: ${result.reason}")
        }
    }

    /**
     * Lower one paged entry into the neutral value model, the read-side
     * counterpart of [KernelCatalog.readView]'s fold lowering.
     *
     * Membership is *derived here* rather than asked of the entry's own type:
     * an OR-set pages its raw tag algebra (add-tags and del-tags per element),
     * which is the honest thing for it to page — the tag sets are the state —
     * but a check comparing a walk against a fold needs to know which entries
     * that algebra currently admits. An entry type with no honest neutral
     * lowering is a loud failure, not a guess.
     */
    private fun readEntry(cellId: CellId, entry: java.io.Serializable): ReadEntry = when (entry) {
        is SetCell.SetStateEntry<*> -> ReadEntry(
            key = Value.of(entry.element),
            value = null,
            present = entry.present,
        )
        is ExclusiveEntry -> error(
            "read-state on '$cellId' paged an exclusive-payload descriptor; no scenario models ownership " +
                "through a bounded read yet, and inventing a neutral rendering for one would be a guess",
        )
        else -> error(
            "read-state on '$cellId' returned an entry of type ${entry.javaClass.name}, which has no neutral " +
                "lowering in this binding — bind it here rather than comparing an unlowered entry",
        )
    }

    /**
     * The wave plane a cell has reached (V1C-CONCORD): every [FanOutlet] the
     * cell registered, and the `(sourceId, highWater)` it has minted there.
     *
     * Read off the cell's own port registry rather than a per-type accessor, so
     * it works for every catalog cell without a binding table. Keys are opaque
     * — the check only ever compares two readings of the same cell.
     */
    override fun wavePlane(cellId: CellId): WavePlane {
        val ports = PortRegistry.of(cells.getValue(cellId).cell)
        val positions = LinkedHashMap<String, Long>()
        ports.names().forEach { name ->
            val state = (ports[name] as? FanOutlet<*>)?.waveState() ?: return@forEach
            positions["$name#${state.sourceId}"] = state.highWater
        }
        return WavePlane(positions)
    }

    override fun snapshot(cellId: CellId): Blob {
        if (cellId in durCells) return dur.snapshot(cellId)
        val cell = cells.getValue(cellId).cell
        val state = (cell as? Stateful)?.snapshot()
            ?: error("cell '$cellId' is not Stateful; cannot snapshot")
        return ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(state) } }.toByteArray()
    }

    override fun restore(hostId: HostId, cellId: CellId, blob: Blob) {
        // dur hook (W4-B): a durable-host cell restores through the dur capability.
        // A cell is dur XOR being migrated, so the dur route takes precedence over
        // the dist host-migrate branch below (durable cells are not in `cells`).
        if (cellId in durCells) return dur.restore(cellId, blob)
        // dist hook (W4-A): a `restore … host:` that names a *different* host than
        // the cell currently lives on is a migration (spec 33 — "restore … host:
        // re-materializes on another host"). The kernel's host-granular migrate
        // moves the cell (with a real serialization round-trip) and re-resolves
        // its routed edges, so the blob is superseded by that transfer.
        val bound = cells.getValue(cellId)
        if (hostId.isNotEmpty() && hostFor(hostId) !== bound.host) {
            dist.migrate(cellId, hostId)
            return
        }
        val cell = bound.cell as? Stateful
            ?: error("cell '$cellId' is not Stateful; cannot restore")
        val state = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as java.io.Serializable
        cell.restore(state)
    }

    /**
     * The kernel binding of the restart verb (D-C12): the cell is supervised
     * `RESTART` and then handed a failing invocation, so **`ManagedHost`'s own**
     * supervision path is what runs — the generation bump, the per-outlet
     * `mintFreshEpoch()` collecting the superseded source ids, the checkpoint
     * restore, and the `ReBaselineEmitting.reBaseline(supersedes, supersede =
     * true)` that reconciles downstream. Nothing here rolls a cell back by hand:
     * a driver-side rollback would be exactly the unannounced local restore this
     * verb exists to distinguish itself from (see [Driver.restart]).
     *
     * Both calls are staged rather than performed inline, and their relative
     * order is fixed by the host's own scheduler bands: `supervise` rides the
     * management band and the trigger stages behind it on the data band, so the
     * policy is always in place before the invocation that fails under it —
     * under every schedule of the sweep.
     */
    override fun restart(cellId: CellId) {
        val bound = cells.getValue(cellId)
        // resolve the trigger BEFORE supervising, so an unrestartable catalog
        // type fails loudly without leaving a policy behind on the cell
        val trigger = KernelCatalog.restartTrigger(bound.type)
        bound.host.managementInlet.call.supervise(bound.ref, SupervisionPolicy.RESTART)
        trigger(bound.host, bound.ref)
    }

    /**
     * The kernel binding of the retransmit verb, delegated to whichever capability
     * owns a **decision about a duplicate**.
     *
     * A duplicate live delivery is only observable where something decides whether
     * to act on it twice, and this driver has exactly two such decisions:
     *
     * - the `dur` profile's `Effectful` **processed-frontier**
     *   ([KernelDriverDur.retransmit]) — the same reason [effectLog] is empty on
     *   core; and
     * - the `dist` profile's **dot algebra** at a replica's gossip inlet
     *   ([KernelDriverDist.retransmit], `computenet-j2x.4.6`) — `novelty` keeps
     *   only dot information the fold has never held, so a re-delivered dot
     *   re-emits nothing (echo termination, spec 40/42).
     *
     * Everything else fails loudly here. A plain core cell — including a
     * `Replicable` one that was never placed in a replication mesh — has neither
     * decision and no already-gossiped delta to re-deliver, so the injection would
     * assert nothing: exactly the scenario-shaped hole this verb was added to
     * close.
     */
    override fun retransmit(
        cellId: CellId,
        inlet: String?,
        source: CellId,
        counter: Long,
        op: String,
        value: Value?,
        baseline: Map<CellId, Long>?,
    ) {
        if (cellId in durCells) return dur.retransmit(cellId, inlet, source, counter, op, value, baseline)
        if (cellId in distReplicas) return dist.retransmit(cellId, inlet, source, counter, op, value, baseline)
        throw UnsupportedCatalogBinding(
            "retransmit at '$cellId': this binding injects an explicit wave position at a durable " +
                "effect-boundary sink (host: dur), where a processed-frontier decides whether the duplicate " +
                "acts again, or at a `replica-of` replica's gossip inlet (profile: dist), where the dot " +
                "algebra does — a core cell has neither decision, so the injection would assert nothing",
        )
    }

    /**
     * The kernel binding of the `drive-contextless` verb, delegated to the one
     * profile where a contextless delivery is *decided* rather than merely
     * accepted: the `dur` profile's `Effectful` admission guard
     * ([KernelDriverDur.driveContextless]).
     *
     * Everything else refuses loudly, for the same reason [retransmit] does. A
     * core or `dist` cell has no admission rule keyed on the message context —
     * a contextless delivery there is ordinary, legitimate traffic — so the
     * injection would assert nothing at all, and a scenario resting on it would
     * read as coverage of `[24-DUR-06]` that it does not have. Widening the
     * binding to a non-`Effectful` control target is deliberate future scope,
     * not something to approximate here.
     */
    override fun driveContextless(cellId: CellId, inlet: String?, op: String, value: Value?) {
        if (cellId in durCells) return dur.driveContextless(cellId, inlet, op, value)
        throw UnsupportedCatalogBinding(
            "drive-contextless at '$cellId': this binding drives a contextless PORT_API delivery only at a " +
                "durable effect-boundary sink (host: dur), where an `Effectful` admission guard decides a " +
                "frame carrying no MessageContext (spec 24 §Effectful [24-DUR-06]) — every other cell admits " +
                "one as ordinary traffic, so the delivery would assert nothing",
        )
    }

    /**
     * The kernel binding of the `drive-stamped` verb, delegated to the same
     * profile [driveContextless] is, and for the mirrored reason: the `dur`
     * profile's `Effectful` admission guard is the only place where a frame's
     * message context is *decided* rather than merely carried
     * ([KernelDriverDur.driveStamped]).
     *
     * Everything else refuses loudly. A core or `dist` cell keeps no
     * processed-frontier, so an externally-stamped delivery there is
     * indistinguishable from ordinary traffic and would assert nothing about
     * `[24-DUR-05]`'s externally-driven arm — while reading, in the corpus, as
     * coverage of it.
     */
    override fun driveStamped(cellId: CellId, inlet: String?, actor: String, op: String, value: Value?) {
        if (cellId in durCells) return dur.driveStamped(cellId, inlet, actor, op, value)
        throw UnsupportedCatalogBinding(
            "drive-stamped at '$cellId': this binding drives an externally-stamped PORT_API delivery only at a " +
                "durable effect-boundary sink (host: dur), whose `Effectful` processed frontier is what judges " +
                "the actor lane's position (spec 24 §Effectful [24-DUR-05]) — every other cell keeps no such " +
                "frontier, so the delivery would assert nothing",
        )
    }

    override fun despawn(cellId: CellId) {
        if (cellId in durCells) return dur.despawn(cellId)
        val bound = cells.remove(cellId) ?: return
        // graceful unlink first (15-DESPAWN): drop every link incident to the cell
        // so upstream producers stop routing to a gone cell (no dead letters), then
        // retire it from its host.
        linksByCell.remove(cellId)?.forEach { ref ->
            linksByRef.remove(ref)?.unlink()
            linksByEndpoint.values.remove(ref)
            linksByCell.values.forEach { it.remove(ref) }
        }
        bound.host.managementInlet.call.despawn(bound.ref)
    }

    override fun deadLetters(): List<DeadLetter> = deadLetters.toList()

    /** Effectful sinks bind only in the `dur` profile (W4-B); core has none. */
    override fun effectLog(cellId: CellId): List<Effect> =
        if (cellId in durCells) dur.effectLog(cellId) else emptyList()

    /**
     * The kernel binding of the emission-count verb, delegated to the one
     * capability that already observes an outlet: the `dist` profile's
     * Observe-role tap on a `replica-of` replica's delta outlet
     * ([KernelDriverDist.emissionCount], installed by `recordEmissionsOf` at
     * spawn — the very observation `retransmit` replays a recorded emission
     * from).
     *
     * Every other cell **refuses loudly**, and the refusal is the point:
     * `emissionCount` is differenced against a baseline and compared to an
     * `exactly:`, so answering `0` for a cell this binding never taps would let
     * `exactly: 0` pass on an unobserved outlet — a vacuous green, not a
     * conservative one. Widening the tap to `dur`/`core` outlets is deliberate
     * future scope, not something to approximate here.
     */
    override fun emissionCount(cellId: CellId): Long {
        if (cellId in distReplicas) return dist.emissionCount(cellId)
        throw UnsupportedCatalogBinding(
            "emission-count at '$cellId': this binding counts outlet emissions only at a `replica-of` " +
                "replica (profile: dist), where an Observe-role tap records every delta the replica emits; " +
                "no other cell's outlet is observed, and answering 0 for one would make an `exactly: 0` " +
                "check pass on an outlet nothing was watching",
        )
    }

    /**
     * The kernel binding of the refusal-count observation, delegated to the
     * profile that owns the refusal: the `dur` host's dead-letter outlet, which
     * [KernelDriverDur] subscribes to and classifies per cell.
     *
     * Every other cell **refuses loudly**, and the refusal is the point: a
     * `refusal-count` is compared to an `exactly:`, so answering `0` for a cell
     * whose refusals this binding never observes would let `exactly: 0` pass on
     * an unwatched cell — a vacuous green, not a conservative one.
     */
    override fun refusalCount(cellId: CellId): Long {
        if (cellId in durCells) return dur.refusalCount(cellId)
        throw UnsupportedCatalogBinding(
            "refusal-count at '$cellId': this binding observes refused deliveries only on the durable host " +
                "(host: dur), whose dead-letter outlet it subscribes to; no other host's refusals are " +
                "observed, and answering 0 for one would make an `exactly: 0` check pass on a cell nothing " +
                "was watching",
        )
    }

    private companion object {
        /** Scheduler steps one page of a bounded read may take before it is declared wedged. */
        const val READ_STEP_BUDGET = 100_000
    }
}
