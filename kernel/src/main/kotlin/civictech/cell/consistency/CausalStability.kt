package civictech.cell.consistency

import civictech.cell.CellRef
import civictech.cell.TagFrontier
import civictech.cell.data.WatermarkCell
import java.util.UUID

/**
 * The **causal-stability** read (E3.5, `computenet-9sm.3`; spec
 * `doc/spec/40-distribution/42-replication.md` §"The stability read"
 * [42-WM-05] and §"Membership is one snapshot per read" [42-WM-07];
 * epic ids [KE3-16], [KE3-19], [KE3-20], [KE3-24]; research 03 §3 Def 5.1):
 * per source `s`, the pointwise **minimum** of `row[s]` over every *open*
 * membership row, with an absent row reading as bottom.
 *
 * A timestamp is causally stable once no concurrent operation for it can
 * still arrive anywhere — the trigger a compaction or GC pass waits on.
 * Reading an absent row as bottom **freezes** the minimum; that is the
 * conservative direction and the point (no local read may run ahead of the
 * true global frontier).
 *
 * **Why a sibling class and not a method on [ReplicaQuorum]** (decision
 * 9sm.3-D1, epic R7). The two reads are the same primitive at two freshness
 * levels — one wave against all covering members ([ReplicaQuorum.frontier],
 * a per-wave hot predicate) versus all sources against all open members
 * (this class, a checkpoint-time GC trigger). They have different consumers,
 * different call frequencies and different policy surfaces, so they get one
 * class each over the same injected reads rather than one class with two
 * moods.
 *
 * **Three injected reads, not [ReplicaQuorum]'s four** (decision 9sm.3-D4).
 * Interest is deliberately **not** applied: stability is per logical id, not
 * per key, so there is no key to scope an [civictech.cell.link.Interest]
 * against. A disjoint-interest member's absent column correctly reads as
 * bottom for the sources it never delivers — which means a sharded (PN-6)
 * instance set freezes stability for cross-slice sources. That is the
 * conservative direction and a known limitation (recorded in
 * `doc/kernel-lane-findings.md`); an interest-scoped stability read is a
 * later design question, not this class. `interestOf` is therefore absent
 * from the constructor rather than carried unused.
 *
 * **R14 — a superseded column stays in the MIN** (decision 9sm.3-D3, branch
 * settled by `computenet-9sm.1`). A `ReBaseline`-superseded source's column
 * is **not** excluded from the minimum, so one supersession freezes
 * stability for every replica of the id. This branch was taken because the
 * supersession fence is **replica-local**: `cell.data.delta.TagState`
 * records `notice.supersedes` into its own `deadSources` set, binding only
 * the replicas that saw the notice, so no replica may safely conclude that a
 * superseded source's column is globally dead. Excluding it would let this
 * read run ahead of the true global frontier — the one thing [42-WM-05]
 * forbids. See spec 42 §"Open interactions" ("R14 — superseded columns and
 * the stability MIN", recorded UNPINNED) and `concord/corpus/DISPUTES.md`
 * entry `42-WM-R14`. Consequently this class reads **no** `deadSources`
 * anywhere; `computenet-9sm.6` gates reclamation on "no superseded column
 * present" instead, which is a decision at the consumer, not here.
 *
 * **Inert by construction** ([KE3-22], `[24-BOUND-01]`): this class only
 * *reads*. It never calls `advance`/`close`/`suspend`/`resume`/
 * `announceMember` on the companion, never emits on an outlet, and never
 * enters [civictech.cell.CurrentContext]. It is safe to call from a
 * checkpoint or GC pass outside any wave.
 */
class CausalStability(
    private val watermarkOf: (UUID) -> WatermarkCell?,
    private val membersOf: (UUID) -> Set<CellRef>,
    private val watermarkRefOf: (CellRef) -> CellRef,
) {

    /**
     * The stable frontier for `logicalId` ([42-WM-05]).
     *
     * **One snapshot per read** ([42-WM-07], [KE3-24]): [watermarkOf] and
     * [membersOf] are each invoked exactly once, and `rows`/`closed`/
     * `suspended`/`members` are each taken once off that one companion.
     * Everything below is evaluated against those copies, so a concurrent
     * gossip delta cannot tear one evaluation across two membership views.
     * Membership itself is only eventually consistent (R13): the
     * `instancesOf` fold this node sees may lag, which is why the open set
     * unions it with the companion's announced [WatermarkCell.members] set
     * rather than trusting the fold alone.
     *
     * **Open slots** = (`membersOf` mapped through [WatermarkCell.slotId] of
     * [watermarkRefOf]) ∪ companion [WatermarkCell.members], minus
     * [WatermarkCell.closed], minus (under [degrade]) [WatermarkCell.suspended].
     * The union is the FU-2 asymmetry in its conservative direction: a slot
     * known ONLY through the announced `members` set still counts, has no
     * row, and therefore drags every source to bottom until its row gossips
     * in ([KE3-19]).
     *
     * **Bottom is represented by ABSENCE.** An open slot with no entry for a
     * source reads as bottom, so the result contains exactly the sources
     * that *every* open slot has a row for; a bottom source is absent from
     * [TagFrontier.perSource], never present as `Long.MIN_VALUE` and never
     * as `0`. The degenerate cases — no companion, empty open slot set — are
     * therefore `TagFrontier(emptyMap())`, mirroring [ReplicaQuorum]'s "an
     * empty covering subset always holds (never a vacuous release)".
     *
     * @param degrade when true, a recoverably-suspended slot (odd epoch) is
     *   dropped from the open set — the same PN-19 quorum-shrink switch
     *   [ReplicaQuorum.frontier] carries, spelled the same way.
     */
    fun stableFrontier(logicalId: UUID, degrade: Boolean = false): TagFrontier {
        val companion = watermarkOf(logicalId) ?: return TagFrontier(emptyMap())
        val members = membersOf(logicalId)
        val rows = companion.rows()
        val closed = companion.closed()
        val suspended = if (degrade) companion.suspended() else emptySet()
        val announced = companion.members()

        val open = buildSet {
            members.mapTo(this) { WatermarkCell.slotId(watermarkRefOf(it)) }
            addAll(announced)
            removeAll(closed)
            removeAll(suspended)
        }
        if (open.isEmpty()) return TagFrontier(emptyMap())

        // R14: the MIN runs over every column present in any open row — a
        // superseded source's column is NOT excluded (see the class KDoc).
        val candidates = open.flatMapTo(mutableSetOf()) { slot -> rows[slot]?.keys.orEmpty() }
        val stable = HashMap<UUID, Long>(candidates.size)
        for (source in candidates) {
            val perSlot = open.map { slot -> rows[slot]?.get(source) }
            // An open slot with no entry for this source reads as bottom, and
            // bottom is represented by ABSENCE from the result.
            if (perSlot.any { it == null }) continue
            stable[source] = perSlot.filterNotNull().min()
        }
        return TagFrontier(stable)
    }
}
