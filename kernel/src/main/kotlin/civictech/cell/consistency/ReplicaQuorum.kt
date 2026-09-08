package civictech.cell.consistency

import civictech.cell.CellRef
import civictech.cell.data.WatermarkCell
import civictech.cell.link.Interest
import java.util.UUID

/**
 * The cross-replica settlement predicate behind [ReplicaFrontier] (T11-D
 * extraction from `civictech.cell.replication.Replication.replicaFrontier`):
 * a *consistency* concern — same package as [ReplicaFrontier] itself and its
 * consumer [WaveFrontier] — moved out of the replica-wiring class it used to
 * live in. This is a **move**, not a redesign: the four documented policy
 * switches ([creationFence], [degrade], [membershipBarrier], the null-`key`
 * path) and their KDoc below are carried over verbatim; FU-2 owns any future
 * redesign.
 *
 * Constructed from exactly what the predicate reads — a watermark-cell
 * reader, a membership reader, and an interest reader — the same three
 * injected reads [civictech.cell.replication.Replication] already had
 * (`watermarks[logicalId]`, `registry.instances.instancesOf`, `registry.instances.interestOf`),
 * now passed in explicitly instead of captured from the replica-wiring
 * class's own fields. [watermarkRefOf] is the fourth read: the data-ref ->
 * watermark-ref derivation ([civictech.cell.replication.Replication.watermarkRef])
 * every member slot is keyed by.
 */
class ReplicaQuorum(
    private val watermarkOf: (UUID) -> WatermarkCell?,
    private val membersOf: (UUID) -> Set<CellRef>,
    private val interestOf: (CellRef) -> Interest,
    private val watermarkRefOf: (CellRef) -> CellRef,
) {

    /**
     * Cross-track settlement read (spec 20/22 §Completeness — cross-replica
     * extension, E3.4; interest-scoped in PN-7, plan §3 Rule of settlement,
     * spec 22 §Interest-scoped settlement): the JoinBarrier "has the covering
     * subset delivered origin wave `(source, counter)` touching `key`" answered
     * off the local merged [WatermarkCell].
     *
     * **Quorum = the covering subset** (PN-7, resolving F2): the live members
     * whose [Interest] ADMITS `key` — not every member. A disjoint-interest
     * instance never delivers waves outside its slice, so its row stays at
     * bottom forever; quantifying over it (the pre-PN-7 `members.all`) stalls a
     * WAIT consumer the moment shards join the mesh. Filtering to the covering
     * subset collapses to today's behavior when every interest is [Interest.Total]
     * (all members cover every key) and to the sharded-replication quorum under
     * overlap. A `null` `key` (a consumer that extracts no origin keys) is
     * unfiltered — every member — byte-identical to pre-PN-7.
     *
     * Membership is the [civictech.cell.host.InstanceIndex.instancesOf] fold;
     * a member's row is read at its derived [WatermarkCell.slotId]. A cleanly
     * departed replica is despawned and so leaves the fold — that is how a
     * `closed` slot stops constraining here (see the KE3-23 note below, which
     * settled that this read must NOT honour the marker itself). A member whose
     * row has not yet gossiped in holds the wave (WAIT), never releases it early.
     *
     * **R13 creation fence** ([creationFence], default on — promoted from optional
     * to blocking in PN-7). Because filtering *shrinks* the quorum, a joining
     * covering member that has not yet established its delivered-watermark row is
     * the premature-release hazard: dropping it from the quorum releases a wave it
     * has not delivered. The fence is the requirement that such a member **holds**
     * the wave — a covering member with no row for [source] reads as bottom
     * (`Long.MIN_VALUE`) and fails the predicate, exactly as a lagging member
     * does, so the wave waits until it catches up. With the fence OFF a covering
     * member is admitted to the quorum only once it has *some* row for [source] —
     * a rowless (freshly-joined) member is silently skipped, reproducing the
     * premature release. An empty covering subset always holds (never a vacuous
     * release).
     *
     * **FU-2 converged-membership barrier** ([membershipBarrier], default on).
     * Membership itself ([civictech.cell.host.InstanceIndex.instancesOf]) is
     * eventually consistent: a covering member the local view has not learned of
     * *at all* is absent from the quorum above, so the wave could release before
     * that member's data for the key arrives — the unknown-joiner premature
     * release (PN-7's residual; PN-19 closed only the known-suspended half). The
     * barrier closes it by reading the companion's [WatermarkCell.members] set —
     * announced on join and gossiped over the *transitively* converging companion
     * CRDT, which is more complete than the point-to-point topology announcements
     * feeding `instancesOf`. A member slot the companion knows but this node's
     * `instancesOf` view has not accounted for (nor `closed`, nor — under
     * DEGRADE — `suspended`) forces a conservative HOLD of every keyed wave,
     * never a premature release, and releases the moment the view converges. A
     * `null` [key] (no covering quorum) is never held, so the default settlement
     * stays byte-identical; a converged membership never holds, so a
     * covering-quorum graph settles exactly as it did once everyone is known.
     *
     * **KE3-23 — this read does NOT honour `closed`, and that is settled, not
     * assumed** (`computenet-s0tq`; disposition recorded in
     * `doc/kernel-lane-findings.md` §`KE3-23-QUORUMCLOSED`, sibling of
     * `computenet-07vb`'s §`KE3-23-CLOSEDPREMISE` on the stable-frontier read).
     * The settlement check used to read
     * `slot in closed || (rows[slot]?.get(source) ?: MIN) >= counter`, and the
     * `covering` filter carried the same disjunct. Because
     * [WatermarkCell.slotId] is derived from the [CellRef] and replay-stable
     * across a rejoin (M10.1) while [WatermarkCell.closed] is grow-only and
     * retracted by nothing, a replica evicted with `closeDepartedRow = true`
     * that re-replicates onto the same ref returns onto the slot already closed
     * — and it is then a LIVE instance, so it is in `members`, in `covering`,
     * and it satisfied the predicate VACUOUSLY, its row never consulted. That
     * defeats the R13 creation fence in exactly the case the fence exists for: a
     * rejoined member is rowless. It is a false certificate of the same family
     * `computenet-07vb` fixed on [CausalStability.stableFrontier], reached by
     * the per-wave read. Reachability is SETTLED against the real mesh by
     * `ReplicaQuorumTest`'s rejoin test, not read off the code.
     *
     * **The repair is `computenet-07vb`'s shape, and here it degenerates to
     * deletion.** That repair is "honour `closed` only where no live instance
     * contradicts it", i.e. subtract `closed - memberSlots` instead of `closed`.
     * `covering` is a filter over `members`, so every slot the arm could ever be
     * evaluated against is a member slot — `(closed - memberSlots)` and the
     * covering set are disjoint by construction, and the arm is unreachable
     * under the corrected term. So it is removed rather than written as a branch
     * that provably never fires. (The shape does real work in
     * [CausalStability.stableFrontier] because that read unions the announced
     * `members` set, which contains slots `instancesOf` does not; this read has
     * no such union.)
     *
     * The [membershipBarrier] above is deliberately UNCHANGED: its `accounted`
     * set is `known + closed + suspended`, and applying the same shape gives
     * `known + (closed - known) + suspended` — the same set. An announced slot
     * that is closed and not live still counts as accounted and still does not
     * hold a keyed wave.
     *
     * **Direction and cost.** The change is strictly CONSERVATIVE for
     * certification: a member that previously passed on the marker alone must
     * now show a row at or past the counter, so `covering.all` can only become
     * harder and no `(source, counter)` that this read previously refused
     * becomes certified. The price is the mirror image of the stability freeze
     * `computenet-07vb` accepted: while this node's `instancesOf` view still
     * lags a genuinely departed replica, the marker no longer excuses it and the
     * wave HOLDS until the view converges — a hold under WAIT semantics, never a
     * premature release, and self-healing, since a despawned replica leaves
     * `instancesOf`. Both halves are pinned in `ReplicaQuorumTest`.
     */
    fun frontier(
        logicalId: UUID,
        creationFence: Boolean = true,
        degrade: Boolean = false,
        membershipBarrier: Boolean = true,
    ): ReplicaFrontier =
        ReplicaFrontier { source, counter, key ->
            val companion = watermarkOf(logicalId)
            val members = membersOf(logicalId)
            if (companion == null || members.isEmpty()) return@ReplicaFrontier false
            val rows = companion.rows()
            val closed = companion.closed()
            // PN-19 DEGRADE quorum-shrink (closes PN-7's documented gap): a
            // recoverably-suspended covering member ([WatermarkCell.suspend], odd
            // epoch) is dropped from the quorum under DEGRADE — the resumable
            // analogue of a `closed` (terminal) departure — and restored on
            // [WatermarkCell.resume]. Under WAIT (the default) suspended members
            // stay required and hold the wave, exactly as pre-PN-19.
            val suspended = if (degrade) companion.suspended() else emptySet()
            // FU-2 converged-membership barrier (closes PN-7's unknown-joiner
            // residual; PN-19 closed only the known-suspended half). The covering
            // quorum above is read off `instancesOf`, which is eventually consistent:
            // a covering member this node has not learned of *at all* is absent from
            // the quorum, so the wave could release before that member's data for the
            // key arrives — a torn read. The delivered-watermark companion, being a
            // transitively-gossiped CRDT, converges membership more completely than
            // the point-to-point topology announcements that feed `instancesOf`: it
            // may list a member slot ([WatermarkCell.announceMember]) this node's
            // `instancesOf` view has not yet caught up to. If so — and only for keyed
            // (covering-quorum) waves — HOLD conservatively, never release early. A
            // slot already `closed` (cleanly departed, PN-0c) or (under DEGRADE)
            // `suspended` is accounted for and does not hold. Once `instancesOf`
            // converges the unaccounted set empties and the wave releases (liveness);
            // an unkeyed wave (`key == null`, no covering quorum) is never held, so
            // the default settlement is byte-identical.
            if (membershipBarrier && key != null) {
                val known = members.mapTo(mutableSetOf()) { WatermarkCell.slotId(watermarkRefOf(it)) }
                val accounted = known + closed + suspended
                if (companion.members().any { it !in accounted }) return@ReplicaFrontier false
            }
            val covering = members
                .filter { key == null || interestOf(it).admits(key) }
                .filter { WatermarkCell.slotId(watermarkRefOf(it)) !in suspended }
                .filter { ref ->
                    // R13: with the fence on, EVERY covering member is required (a
                    // rowless one holds on bottom); with it off, a member is only
                    // required once it has published a row for this source, so a
                    // freshly-joined covering member is skipped — the premature hazard.
                    // KE3-23 (computenet-s0tq): no `slot in closed` arm here — see
                    // the settlement note in this method's KDoc.
                    creationFence ||
                        rows[WatermarkCell.slotId(watermarkRefOf(ref))]?.containsKey(source) == true
                }
            // KE3-23 (computenet-s0tq): the settlement check consults the member's
            // ROW, with no `slot in closed ||` short circuit. Every member reaching
            // here is a LIVE instance, and `closed`'s premise ("this row can never
            // advance again") is false for a live instance — see the KDoc.
            covering.isNotEmpty() && covering.all { ref ->
                val slot = WatermarkCell.slotId(watermarkRefOf(ref))
                (rows[slot]?.get(source) ?: Long.MIN_VALUE) >= counter
            }
        }
}
