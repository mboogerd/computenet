package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.graph.CellFactory
import civictech.cell.graph.IdentityBinding
import civictech.cell.port.LinkResult
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.gen.wire.Contract

/**
 * Interface for interacting with a [Host].
 *
 * A Host is a [Cell] that hosts other cells and manages their connections.
 */
@Contract(management = true)
interface HostManagementApi {
    /**
     * Spawns a cell in the host and activates it.
     * The cell acts as its own specification.
     */
    fun spawn(cell: Cell): CellRef

    /**
     * The wire-crossing construction form (50/51 §Graph construction DSL,
     * 93 I-21 §4.4): [spawn] takes a *live* [Cell] and MUST stay local-only —
     * a live cell never crosses the wire. `spawnBound` instead ships a
     * [CellFactory] (already [java.io.Serializable]) plus an [IdentityBinding]
     * choosing *which* [CellRef] the host mints/materializes, and an optional
     * [parent] for organelle nesting (G-28). The host resolves the binding to
     * a concrete ref, hands it to the factory, and admits the result through
     * the same admission path [spawn] uses — so re-`Exact`-spawning a live
     * ref hits the ordinary live-ref spawn guard and rejects loudly
     * (idempotent re-apply for free, G-51).
     */
    fun spawnBound(
        factory: CellFactory,
        identity: IdentityBinding = IdentityBinding.FreshLogical,
        parent: CellRef? = null,
    ): CellRef

    /**
     * Returns a managed reference to the API of a hosted cell.
     */
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T?

    /**
     * Unregisters a hosted cell and calls its [Cell.onDeactivate] on the
     * host's execution context. Subsequent invocations for the ref dead-letter.
     */
    fun despawn(ref: CellRef)

    /**
     * Sets the failure policy for a hosted cell (G-26). Default is
     * [SupervisionPolicy.PROPAGATE]; every policy still dead-letters.
     */
    fun supervise(ref: CellRef, policy: SupervisionPolicy)

    /**
     * Replays, in order, the invocations parked for a cell that a
     * [SupervisionPolicy.SUSPEND] failure sidelined, and resumes delivery.
     */
    fun resume(ref: CellRef)

    /**
     * Suspends (parks) a hosted cell directly — the same per-cell intake
     * closure [SupervisionPolicy.SUSPEND] applies on a failure, requested
     * on purpose instead of triggered by one. Idempotent. Ordinary data/
     * management traffic parks in arrival order until [resume]; management-
     * class protocol traffic (catch-up, resume itself) stays on the always-
     * open plane. The gossip-mesh eviction gate uses this to suspend a
     * replica that finds itself partitioned from every peer (42, G-45):
     * unique un-gossiped state must not be despawned away, only parked
     * pending heal.
     */
    fun suspend(ref: CellRef)

    /**
     * Drains this host (spec 33 steps 1–3): intake closes immediately (new
     * sends fail fast and park at the registry), everything already accepted
     * is processed, then cells are deactivated and [civictech.cell.Stateful]
     * snapshots captured. Fire-and-forget; the host is DRAINED once the queue
     * has flushed. Only legal while running.
     */
    fun drainHost()

    /**
     * Resumes a drained host (spec 33 step 6–7): cells re-activate, the intake
     * reopens, and locations republish — parked traffic replays in order
     * before new sends land. Only legal once DRAINED.
     */
    fun resumeHost()

    /**
     * Drains this host, then moves every cell to [to] (spec 33: the host is
     * the unit of mobility — for finer granularity, make hosts smaller).
     * [civictech.cell.Stateful] cells go through a forced snapshot →
     * serialize → restore round-trip. Target-side spawns publish each cell,
     * replaying its parked traffic there; color validation applies at the
     * target, so moving marked cells across colors is a caller error.
     */
    fun migrate(to: Use<HostManagementApi>)

    /**
     * Connects an outlet of one hosted cell to an inlet of another hosted cell.
     *
     * @param from The reference to the source cell.
     * @param outletName The name or identifier of the outlet on the source cell.
     * @param to The reference to the target cell.
     * @param inletName The name or identifier of the inlet on the target cell.
     * @return the handshake outcome ([LinkResult.Rejected] is returned, not thrown)
     */
    fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult

    /**
     * Connects an outlet of a hosted cell to a remote inlet (represented by a [Use] instance).
     */
    fun connect(from: CellRef, outletName: String, to: Use<*>)
}

/**
 * Shorthand for [HostManagementApi.lookup] using reified types.
 */
inline fun <reified T : Any> HostManagementApi.lookup(ref: CellRef): T? = lookup(ref, T::class.java)

/**
 * Interface for routing API calls to inlets of hosted cells.
 */
@Contract(management = true)
interface HostRoutingApi {
    /**
     * Routes an [Invocation] to a specific inlet of a hosted cell.
     *
     * @param target The reference to the target cell.
     * @param inletName The name of the inlet on the target cell.
     * @param invocation The [Invocation] to apply to the inlet.
     */
    fun route(target: CellRef, inletName: String, invocation: Invocation)
}

/**
 * A Host is a computelet (Cell) that can host and execute other cells.
 */
interface Host : Cell {
    val managementInlet: Use<HostManagementApi>
    val routerInlet: Use<HostRoutingApi>
}
