package civictech.concord.driver.kernel

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.cell.port.FanOutlet
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
     * Place a `replica-of` cell into the replication mesh (spec 42): construct a
     * [SetCell] under the group's shared logical id with a fresh instance id,
     * hand it to [Replication.replicate] (which spawns it on [hostId] and links
     * it to every peer replica of the same logical id), and give it a co-hosted
     * read companion so `readView`/`replicas-converge` can fold it.
     *
     * Only the mergeable set family binds honestly here — `SetCell` is the
     * kernel's `Replicable` OR-set. A non-set `replica-of` type is a real gap
     * (single-writer/pn-counter replication is decided-but-unbuilt or out of the
     * corpus's set-shaped checks), surfaced loudly.
     */
    fun spawnReplica(hostId: HostId, cellId: CellId, type: String, logical: String) {
        if (type != "set-source") {
            throw UnsupportedCatalogBinding(
                "replica-of is only bound for 'set-source' (the kernel's Replicable OR-set SetCell); " +
                    "type '$type' has no honest replicated binding today (single-writer/pn-counter " +
                    "replication is decided-but-unbuilt — CONCORD-PLAN §5 / spec 42).",
            )
        }
        val host = driver.hostFor(if (hostId == "") null else hostId)
        val logicalId = logicalIds.getOrPut(logical) { UUID.randomUUID() }
        val instanceId = (instanceCounters[logical] ?: 0L).also { instanceCounters[logical] = it + 1 }

        val replica = SetCell<Any?>(CellRef(logicalId, instanceId))
        // `replicate` spawns the replica on the host and wires the gossip mesh to
        // every peer already published under this logical id (and, via onPublish,
        // every peer that joins later).
        replication.replicate(replica, host)

        // A co-hosted read companion: the replica re-emits every effective delta
        // (local writes AND merged gossip, SetCell.applyRemote → outlet.originate)
        // on its `outlet`, so an ObserveCell folding that outlet reflects the
        // replica's converged membership — which is what readView(replicaId) reads.
        val companion = ObserveCell(View.set<Any?>())
        host.managementInlet.call.spawn(companion)
        host.managementInlet.call.connect(replica.ref, "outlet", companion.ref, "inlet")

        val bound = KernelDriver.Bound(
            ref = replica.ref,
            type = type,
            host = host,
            cell = replica,
            sink = companion,
            viewKind = KernelCatalog.ViewKind.SET,
        )
        driver.cells[cellId] = bound
        companion.onChange { snapshot -> bound.log += KernelCatalog.readView(bound.viewKind, snapshot) }
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
        val inletName = KernelCatalog.inletName(dst.type, inlet)
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
}
