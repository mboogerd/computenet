package civictech.cell.host

import civictech.cell.CellRef
import java.util.UUID

/**
 * One declared, host-local multi-cell write boundary.
 *
 * [actorId] is derived by the declaring host from its stable ref and [name]:
 * `UUID.nameUUIDFromBytes("computenet:declared-write:${'$'}{host.ref.id}:${'$'}name")`.
 * It is consequently stable across restart exactly when that host ref is
 * stable, and distinct for the same write name on different hosts.
 *
 * The wrapped [ActorIngress] counter is deliberately not persisted. A rebuilt
 * declaration starts again at position zero; an `Effectful` sink whose durable
 * processed frontier already covers those positions suppresses the re-drives.
 * A caller that needs post-restart effects to fire must resume the actor lane
 * from its previously persisted [position] through the connector-ingress seam.
 *
 * [cells] is the scope declared and validated when the write is created. It is
 * descriptive rather than an invocation-time allow-list: keyed cell families
 * choose concrete members per call, so enforcing only these exact refs inside
 * [invoke] would reject the primary dynamic-member use case. An empty scope is
 * therefore legal as well.
 */
class DeclaredWrite internal constructor(
    val name: String,
    val actorId: UUID,
    val cells: Set<CellRef>,
) {
    private val ingress = ActorIngress(actorId)

    /** Runs [block] as one wave on this declared write's actor lane. */
    fun <R> invoke(block: () -> R): R = ingress.drive(block)

    /** The last counter stamped by [invoke]. */
    val position: Long get() = ingress.position
}
