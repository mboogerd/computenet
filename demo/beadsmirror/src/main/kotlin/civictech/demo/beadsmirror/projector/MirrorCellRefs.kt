package civictech.demo.beadsmirror.projector

import civictech.cell.CellRef
import java.util.UUID

/**
 * Deterministic shared logical [CellRef]s for one mirror rig's two
 * replicated cells (feature computenet-7em.1, task computenet-7em.1.1).
 *
 * **Why this exists.** [MirrorProjector]'s no-arg default builds its
 * [MirrorProjector.cell] and [MirrorProjector.edges] with `OrMapCell()` /
 * `SetCell()`, whose own no-arg constructors mint a fresh
 * `CellRef(UUID.randomUUID())` every time
 * ([civictech.cell.data.OrMapCell], [civictech.cell.data.SetCell]). Replication
 * links two cells as replicas of one logical cell only when their
 * [CellRef.id] is equal and their [CellRef.instanceId] differs (spec
 * `doc/spec/40-distribution/42-replication.md`'s local link rule), so two
 * mirror processes built from that default can never be replicas of each
 * other — each mints its own private logical identity. A rig that wants its
 * two nodes' OrMap and edge-set cells to actually gossip needs both sides to
 * derive the *same* [CellRef.id] with *different* [CellRef.instanceId], with
 * no discovery protocol run to agree on it.
 *
 * **The idiom.** Copied from demo/shopping's shared-replica pilot
 * (`civictech.demo.DemoApp.Companion.SHARED_ID` / `sharedInstance`,
 * demo/shopping/src/main/kotlin/civictech/demo/Main.kt): a fixed rig name is
 * hashed into a stable [UUID] with [UUID.nameUUIDFromBytes], so any process
 * that is told the same [rigName] derives the same logical id with zero
 * coordination. This type derives *two* such ids — one for the OrMap, one for
 * the dependency-edge SetCell — from the same [rigName], salted so the two
 * never collide with each other or with demo/shopping's own `"demo-replica:"`
 * namespace.
 *
 * **Role, not raw instance id.** [role] is the human-readable peering role
 * ([LISTENER] or [DIALER], matching the wire idiom's own vocabulary), and
 * [instanceId] derives from it: the listener (and, by the same rule, a
 * would-be solo caller) takes `0`, the dialer takes `1` — identical to
 * `DemoApp.sharedInstance`'s convention. Two [MirrorCellRefs] built from the
 * same [rigName] and different [role]s are therefore guaranteed distinct
 * [CellRef.instanceId]s, which the replication contract requires, and equal
 * [CellRef.id]s for each of [mapRef] and [edgeRef], which is the identity
 * precondition feature computenet-7em.1's rule 1 states directly.
 *
 * **Not wired to anything yet.** This type only mints refs; linking the
 * resulting cells through `cell.replication.Replication` and a real
 * `:wire` transport is task computenet-7em.1.2's job, not this one's — see
 * the parent feature's "out of scope" list (`Peering`, `:wire`, `ManagedHost`
 * wiring).
 *
 * @param rigName the rig's fixed, operator-chosen name. Both nodes of one rig
 *   must be given the identical string — it is the entire coordination
 *   mechanism, so a typo on one side silently mints an unrelated logical cell
 *   rather than failing loudly.
 * @param role one of [LISTENER] or [DIALER]. Any other string is accepted
 *   (only equality with [DIALER] is checked) and treated as [LISTENER]'s
 *   instance id, since a rig with more than two participants is out of scope
 *   here; a caller minting a third role should not rely on that fallback.
 */
data class MirrorCellRefs(val rigName: String, val role: String) {

    /** This rig's logical id for the mirror's OrMap cell, stable across [role]. */
    val mapId: UUID = UUID.nameUUIDFromBytes("$NAMESPACE:$rigName:map".toByteArray())

    /** This rig's logical id for the mirror's dependency-edge SetCell, stable across [role]. */
    val edgeId: UUID = UUID.nameUUIDFromBytes("$NAMESPACE:$rigName:edges".toByteArray())

    /**
     * This ref's replica instance id: [LISTENER] is `0`, anything else
     * (in practice only [DIALER]) is `1` — the same two-value convention
     * `DemoApp.sharedInstance` uses, so a listener and a dialer sharing one
     * [rigName] always mint distinct instance ids.
     */
    val instanceId: Long get() = if (role == DIALER) 1L else 0L

    /** The [CellRef] a projector for this rig/role builds its OrMap cell under. */
    val mapRef: CellRef get() = CellRef(mapId, instanceId)

    /** The [CellRef] a projector for this rig/role builds its edge SetCell under. */
    val edgeRef: CellRef get() = CellRef(edgeId, instanceId)

    companion object {
        /** The peering role that takes instance id `0`. */
        const val LISTENER: String = "listener"

        /** The peering role that takes instance id `1`. */
        const val DIALER: String = "dialer"

        /**
         * Namespace prefix salting every derived [UUID], so this type's ids
         * never collide with demo/shopping's own `"demo-replica:"` namespace
         * or with any other `nameUUIDFromBytes` caller in the repo.
         */
        private const val NAMESPACE: String = "beadsmirror-rig"
    }
}
