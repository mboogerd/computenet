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
     * [WatermarkCell.closed] *that is not itself a live member slot*, minus
     * (under [degrade]) [WatermarkCell.suspended].
     * The union is the FU-2 asymmetry in its conservative direction: a slot
     * known ONLY through the announced `members` set still counts, has no
     * row, and therefore drags every source to bottom until its row gossips
     * in ([KE3-19]).
     *
     * **`closed` is honoured only where its premise holds** ([KE3-23],
     * `computenet-07vb`; disposition recorded in `doc/kernel-lane-findings.md`
     * §`KE3-23-CLOSEDPREMISE`). `closed` (PN-0c) means exactly one thing —
     * *this row can never advance again* ([WatermarkCell.close]'s KDoc: "its
     * row stops constraining reads") — and it is grow-only, while
     * [WatermarkCell.slotId] is DERIVED from the [CellRef] and therefore
     * **stable across a rejoin** (M10.1 replay-stability). So a replica
     * evicted with `closeDepartedRow = true` that later re-replicates onto the
     * same ref returns onto the slot already closed, and a plain
     * `removeAll(closed)` drops a live, unsuspended, state-retaining member
     * from the MIN forever: the frontier then certifies a del-dot "delivered
     * to every open member" when the only open member is the sender, and
     * `compactBelow` discards it. `computenet-typw` settled that this is the
     * excluding term; `computenet-r13k` measured it firing at 37.5% per
     * `GcSafetySweepTest` sweep.
     *
     * The repair subtracts `closed - memberSlots` rather than `closed`: a slot
     * this node's own [civictech.cell.host.InstanceIndex.instancesOf] view
     * reports as a LIVE instance contradicts `closed`'s premise, so the marker
     * does not apply to it. Note what is NOT done — the grow-only `closed` set
     * is not retracted, no epoch lane is added, no delta field changes and
     * [WatermarkCell.slotId] stays replay-stable; the lattice and the wire are
     * untouched and the repair is entirely at this read.
     *
     * **The correction is in the conservative direction**, which is what makes
     * it safe for every other consumer of `closed`: `open` can only GROW, so
     * the MIN runs over at least as many rows, so the frontier can only fall.
     * No `(source, counter)` that this read previously refused to certify
     * becomes certified. The cost is the mirror-image staleness the FU-2 union
     * already carries: while `instancesOf` still lags on a genuinely departed
     * replica, its `closed` marker is ignored and stability FREEZES on its row
     * until the view converges — a freeze, never a premature release, and
     * self-healing. A departed replica that does not return leaves
     * `instancesOf` on despawn and stays excluded, so PN-0c's own job is
     * unchanged (pinned by `StabilityOpenSetOnRejoinTest`'s control).
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

        val memberSlots = members.mapTo(mutableSetOf()) { WatermarkCell.slotId(watermarkRefOf(it)) }
        val open = buildSet {
            addAll(memberSlots)
            addAll(announced)
            removeAll(closed - memberSlots) // KE3-23, computenet-07vb — see below
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

    /**
     * A **protocol-inert diagnostic read** of the very set [stableFrontier]
     * computes its MIN over (`computenet-typw`, [KE3-23]).
     *
     * Nothing in [CausalStability], `Replication`, `SetCell` or any cell
     * consults this; it exists so a caught occurrence of the BS-12
     * fence-attributed divergence carries *which half of `open`* dropped a
     * live member, instead of leaving the reader to infer it. It mirrors the
     * reads `computenet-dwkp` landed on `SetCell`
     * ([civictech.cell.data.SetCell.liveTagsOf] / `fencedAmong` /
     * `fenceProvenance` / `fencesAny`): read-only, consulted by no protocol
     * path, never asserted on in the sweep.
     *
     * It recomputes `open` by the *same* expression [stableFrontier] uses
     * rather than sharing a helper, deliberately: a shared helper would make
     * this read a dependency of the protocol path, and the point of the read
     * is that removing it changes nothing. The duplication is three lines and
     * is pinned by `StabilityOpenSetOnRejoinTest`, the deterministic test that
     * settles which term excludes a rejoined replica.
     *
     * @param degrade the same [stableFrontier] switch, so the report describes
     *   the set the caller's own read would have used.
     */
    fun openSlots(logicalId: UUID, degrade: Boolean = false): OpenSlots {
        val companion = watermarkOf(logicalId) ?: return OpenSlots.NO_COMPANION
        val members = membersOf(logicalId)
        val rows = companion.rows()
        val closed = companion.closed()
        val suspendedAll = companion.suspended()
        val suspended = if (degrade) suspendedAll else emptySet()
        val announced = companion.members()
        val memberSlots = members.mapTo(mutableSetOf()) { WatermarkCell.slotId(watermarkRefOf(it)) }

        val open = buildSet {
            addAll(memberSlots)
            addAll(announced)
            removeAll(closed - memberSlots) // KE3-23, computenet-07vb (see [stableFrontier])
            removeAll(suspended)
        }
        return OpenSlots(
            open = open,
            memberSlots = memberSlots,
            announced = announced,
            closed = closed,
            suspended = suspendedAll,
            degrade = degrade,
            rows = open.associateWith { slot -> rows[slot] },
        )
    }

    /**
     * What [openSlots] reports for one logical id at one moment. Nested rather
     * than a top-level class on purpose: adding a top-level type to
     * `civictech.cell.consistency` puts it in front of every module that
     * enumerates the package (PR #544 on this epic went red in `:inspect` and
     * `:oracle` that way), and this type is a diagnostic, not vocabulary.
     *
     * [rows] carries the per-slot row actually consulted for the MIN — `null`
     * for an open slot that has no row at all, which is the bottom-reading
     * case [stableFrontier]'s KDoc describes.
     */
    data class OpenSlots(
        val open: Set<UUID>,
        val memberSlots: Set<UUID>,
        val announced: Set<UUID>,
        val closed: Set<UUID>,
        val suspended: Set<UUID>,
        val degrade: Boolean,
        val rows: Map<UUID, Map<UUID, Long>?>,
    ) {
        /**
         * Why [slot] is not in [open], named by the term that excluded it —
         * the question `computenet-typw` exists to answer. `null` when the
         * slot IS open.
         */
        fun exclusionOf(slot: UUID): String? = when {
            // A live member slot is never excluded BY `closed` (computenet-07vb),
            // so this arm reports the term that actually applied, not the marker
            // that happens to be present.
            slot in open -> null
            slot in closed -> "closed"
            slot in suspended && degrade -> "suspended(degrade)"
            slot !in memberSlots && slot !in announced -> "absent"
            else -> "unexplained"
        }

        /**
         * Where one open slot's row stands against ONE del-dot position — the
         * **row-content** read (`computenet-mahx`, [KE3-23] candidate (2)),
         * as opposed to [exclusionOf]'s set-membership read.
         *
         * `computenet-typw` settled which term of `open` drops a rejoined
         * replica, and could not touch candidate (2) because an open-set read
         * is about MEMBERSHIP: a slot that is present and open, but whose row
         * carries an entry at or above a del-dot that replica never applied,
         * is invisible to it. The two are different false certificates and this
         * is the read for the second one. Concretely, `compactBelow` discards a
         * `dels` entry when `frontier.perSource[tag.sourceId] >= tag.counter`
         * for its every tag, and [stableFrontier]'s MIN is taken over exactly
         * the rows reported here — so `COVERS` for a `(source, counter)` naming
         * a del-dot is this slot asserting it delivered that remove.
         *
         * The five answers separate the acceptance clause's two shapes:
         * [RowCoverage.NO_ROW] and [RowCoverage.ABSENT_SOURCE] are the row
         * MISSING an entry (bottom — [stableFrontier] freezes the source out of
         * the result entirely, the conservative direction), while
         * [RowCoverage.COVERS] is the row carrying an entry at or above the
         * dot. [RowCoverage.BELOW] is a present, honest, not-yet-covering
         * entry, and [RowCoverage.NOT_OPEN] says the slot never reached the MIN
         * at all — ask [exclusionOf] which term dropped it.
         *
         * **This read reports the CLAIM, not its truth.** Nothing in a
         * `WatermarkCell` row records which counters a replica actually
         * applied — the row is a per-source position — so `COVERS` cannot by
         * itself mean the delivery happened. Whether it did is established
         * against the replica's own state (`SetCell.liveTagsOf`/`membership`),
         * which is what `StabilityRowCoverageOnReincarnationTest` does.
         * Protocol-inert like the rest of [OpenSlots]: no path consults it.
         */
        fun coverageOf(slot: UUID, source: UUID, counter: Long): RowCoverage = when {
            slot !in open -> RowCoverage.NOT_OPEN
            else -> when (val thru = rows[slot]?.get(source)) {
                null -> if (rows[slot] == null) RowCoverage.NO_ROW else RowCoverage.ABSENT_SOURCE
                else -> if (thru >= counter) RowCoverage.COVERS else RowCoverage.BELOW
            }
        }

        /**
         * [coverageOf] for every slot in [open] — the whole certificate behind
         * one del-dot position in one read, so a caller need not re-enumerate
         * `open` itself.
         */
        fun coverageAt(source: UUID, counter: Long): Map<UUID, RowCoverage> =
            open.associateWith { coverageOf(it, source, counter) }

        /** What [coverageOf] can answer. */
        enum class RowCoverage {
            /** The slot is not in [open] at all; [exclusionOf] names the term. */
            NOT_OPEN,

            /** Open, but with no row whatsoever — reads as bottom, freezing the MIN. */
            NO_ROW,

            /** Open with a row that has no entry for this source — also bottom. */
            ABSENT_SOURCE,

            /** A present entry strictly below the dot: this slot has not certified it. */
            BELOW,

            /** A present entry at or above the dot: this slot certifies the dot delivered. */
            COVERS,
        }

        override fun toString(): String =
            "open=${open.short()} members=${memberSlots.short()} announced=${announced.short()} " +
                "closed=${closed.short()} suspended=${suspended.short()} degrade=$degrade " +
                "rows={${rows.entries.joinToString { (s, r) -> "${s.short()}->${r?.size ?: "none"}" }}}"

        companion object {
            /** No companion for the id here — [stableFrontier] returns an empty frontier. */
            val NO_COMPANION = OpenSlots(
                emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), false, emptyMap(),
            )

            private fun UUID.short(): String = toString().take(8)
            private fun Set<UUID>.short(): String = "[" + joinToString { it.short() } + "]"
        }
    }
}
