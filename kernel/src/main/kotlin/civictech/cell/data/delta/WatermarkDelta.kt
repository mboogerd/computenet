package civictech.cell.data.delta

import civictech.cell.MergeablePayload
import civictech.cell.UuidSerializer
import java.io.Serializable
import java.util.UUID

/**
 * Delivered-watermark delta (spec 40/42 §Delivered watermarks, E3.2). A row
 * per replica maps each wave **source id** to the highest delivered wave
 * counter that replica has durably absorbed from that source — the same
 * `(sourceId, counter)` clock shape [civictech.cell.Timestamp] uses, one column
 * per source. Merge is **pointwise max per (replica, source)** with a grow-only
 * union of [closed] (a cleanly departed replica's row stops constraining reads).
 *
 * Pointwise max is a join-semilattice: commutative, associative, and
 * **idempotent** — so a re-delivered, already-absorbed delta is a fixpoint and
 * gossip echoes die out exactly as tagged-set and [PnCounterDelta] deltas do.
 * The Naiad-verbatim signed-delta accumulator this replaces (raw addition) is
 * NOT idempotent and double-counts under gossip redelivery — the documented
 * reject; here the watermark can only rise.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("WatermarkDelta")
data class WatermarkDelta(
    val rows: Map<
        @kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID,
        Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long>,
        > = emptyMap(),
    val closed: Set<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID> = emptySet(),
    /**
     * PN-19 (spec 34 decision 3, 40/42 §Delivered watermarks): the resumable
     * analogue of [closed] — a per-slot **suspend epoch**, merged pointwise by
     * `max`. A slot is currently suspended iff its epoch is odd (each
     * [civictech.cell.data.WatermarkCell.suspend] bumps to the next odd, each
     * [civictech.cell.data.WatermarkCell.resume] to the next even). Only a
     * slot's own owner writes it, so the max-merge is a genuine monotone join
     * (single-writer, alternating) that converges over the gossip mesh exactly
     * as [rows]/[closed] do — the covering-quorum read
     * ([civictech.cell.replication.Replication.replicaFrontier] `degrade`) drops
     * an odd-epoch member from the quorum and restores it when the epoch turns
     * even. `closed` is the degenerate terminal case (an epoch that never turns
     * even again). Defaulted empty ⇒ pre-PN-19 deltas round-trip unchanged.
     */
    val suspended: Map<
        @kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID,
        Long,
        > = emptyMap(),
    /**
     * FU-2 (spec 22 §Converged-membership barrier): a **grow-only set of covering
     * member slots** — the [civictech.cell.data.WatermarkCell.slotId] each
     * replica announces on join ([civictech.cell.data.WatermarkCell.announceMember]).
     * It is the coverage-completeness half of the delivered-watermark companion: a
     * member's *existence* rides the same transitively-gossiped, idempotent CRDT
     * as its delivered frontier, so it converges more completely than the
     * point-to-point topology announcements that feed
     * [civictech.cell.host.InstanceIndex.instancesOf] (which mirror only
     * direct peers). A settling node whose companion lists a member slot its own
     * `instancesOf` view has not accounted for holds every keyed wave
     * ([civictech.cell.replication.Replication.replicaFrontier] `membershipBarrier`)
     * — the same conservative asymmetry the R13 creation fence gives a *known*
     * rowless member, extended to an *unknown* one. Union-merged (grow-only), so it
     * gossips over the mesh with no second protocol; defaulted empty ⇒ pre-FU-2
     * deltas round-trip unchanged.
     */
    val members: Set<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID> = emptySet(),
) : Serializable, MergeablePayload {

    // T07 finding 4: both folds route through the shared [mergeMax] — identity
    // `Long.MIN_VALUE` (bottom), unlike [PnCounterDelta]'s `0L`, because an
    // absent row/epoch here must never look "caught up"/"never suspended" by
    // coincidence with a real zero value (see [Lattices.kt]).
    fun merge(other: WatermarkDelta): WatermarkDelta =
        WatermarkDelta(
            mergeRows(rows, other.rows),
            closed + other.closed,
            mergeMax(suspended, other.suspended, identity = Long.MIN_VALUE),
            members + other.members,
        )

    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as WatermarkDelta)

    /**
     * Lattice order: `this ⊒ other` iff every counter in `other` is met or
     * exceeded here, `other`'s closed replicas are all closed here, every suspend
     * epoch here is at least `other`'s, and `other`'s announced members are all
     * announced here. Used by the monotonicity law and to reason about echo
     * termination.
     */
    fun dominates(other: WatermarkDelta): Boolean {
        if (!closed.containsAll(other.closed)) return false
        if (!members.containsAll(other.members)) return false
        if (other.suspended.any { (slot, epoch) -> (suspended[slot] ?: Long.MIN_VALUE) < epoch }) return false
        return other.rows.all { (replica, cols) ->
            val mine = rows[replica] ?: emptyMap()
            cols.all { (source, thru) -> (mine[source] ?: Long.MIN_VALUE) >= thru }
        }
    }

    companion object {
        private fun mergeRows(
            a: Map<UUID, Map<UUID, Long>>,
            b: Map<UUID, Map<UUID, Long>>,
        ): Map<UUID, Map<UUID, Long>> =
            (a.keys + b.keys).associateWith { replica ->
                mergeMax(a[replica] ?: emptyMap(), b[replica] ?: emptyMap(), identity = Long.MIN_VALUE)
            }
    }
}
