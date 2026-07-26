package civictech.cell.verify

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.Replicable
import civictech.cell.host.LocationRegistry
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import java.util.UUID

/**
 * Replica-convergence invariant harness (spec 42 + 52, decided 93 I-3,
 * closes G-45's harness half). A convergence invariant over a replicated
 * cell links to **each replica's own delta outlet** — replicas enumerated
 * via [LocationRegistry.replicasOf] — folds every replica's stream
 * independently, and asserts the folds agree at quiescence (52 §Replica
 * convergence). Attaching in-process needs no proxy hop: [attach] links
 * directly to a local [Replicable.outlet], exactly like the routed
 * `deltaInlet` link `civictech.cell.replication.Replication` installs for
 * gossip itself, minus the wire crossing.
 *
 * **Departed-stream rule** (G-45's false-positive gap): a replica that
 * legitimately leaves the mesh — the gated eviction in `Replication.evict`
 * despawning it — drops out of [LocationRegistry.replicasOf] on its next
 * fold. [converged] only requires agreement among replicas **currently
 * counted as live membership**; a departed replica's frozen last fold is
 * simply excluded rather than treated as a stalled disagreement. This is
 * what keeps the invariant from false-positiving on an orderly departure,
 * as distinct from silent loss (which the eviction gate itself prevents by
 * suspending — never despawning — a partitioned replica).
 */
class ReplicaConvergence<D : Any, S>(
    private val registry: LocationRegistry,
    private val logicalId: UUID,
    private val initial: S,
    private val fold: (S, D) -> S,
) {
    private val folds = linkedMapOf<CellRef, S>()

    /** Attach to [replica]'s own delta outlet — one call per known local replica instance. */
    fun attach(replica: Replicable<D>) {
        val ref = replica.ref
        require(ref.sameLogical(CellRef(logicalId))) { "replica $ref is not of logical id $logicalId" }
        folds[ref] = initial
        replica.outlet.subscribe(
            Use.fixed(
                object : Propagate<D> {
                    override fun propagate(value: D) {
                        folds[ref] = fold(folds.getValue(ref), value)
                    }
                },
                PortRef.generate(),
            ),
        )
    }

    /** Attached replicas [registry] still counts as live membership of [logicalId]. */
    private fun liveRefs(): Set<CellRef> = registry.replicasOf(logicalId) intersect folds.keys

    /**
     * True once every still-live attached replica's fold agrees. A departed
     * replica (evicted — no longer in [LocationRegistry.replicasOf]) does
     * not have to keep agreeing with the survivors (the departed-stream
     * rule above); fewer than two live streams trivially converges.
     */
    fun converged(): Boolean {
        val live = liveRefs()
        if (live.size <= 1) return true
        return live.map { folds.getValue(it) }.toSet().size == 1
    }

    /** The fold observed so far for [ref] (live or departed), or null if never attached. */
    fun state(ref: CellRef): S? = folds[ref]

    /** Every attached replica's fold, live or departed, keyed by ref. */
    fun states(): Map<CellRef, S> = folds.toMap()
}
