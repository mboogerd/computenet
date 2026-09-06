package civictech.cell.consistency

import civictech.cell.Timestamp
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import java.util.UUID

/**
 * The **stability-freeze diagnostic** (E3.5, `computenet-9sm.5`; spec
 * `doc/spec/40-distribution/42-replication.md` §"The stability read";
 * epic ids [KE3-24], [KE3-27], [KE3-28]; decisions 9sm.5-D2, D3, D6):
 * decides when to *say* that [CausalStability]'s pointwise MIN is frozen on
 * a particular replica slot, and emits a
 * [StallNotice.Stall] carrying [StallReason.STABILITY_FROZEN] for it.
 *
 * **It never changes the read and never unfreezes anything** ([KE3-28]).
 * This class calls no lattice mutator — no `advance`, `close`, `suspend`,
 * `resume` or `announceMember`, and it does not know how to reach one: it
 * takes snapshots in and returns events out. It holds only its own counters,
 * latches and previous-row copy. Whether the frozen member is actually gone,
 * and what to do about it, is an operator's decision (`Replication.evict`, a
 * clean departure) — there is no failure detector, lease or timeout here.
 *
 * **The predicate** (9sm.5-D6, D2 made operational). At each [evaluate]:
 *
 * - Slot `S` is **lagging** iff some source `s` has *every other* open slot's
 *   `rows[·][s]` strictly greater than `rows[S][s]`, where an absent entry
 *   reads as **bottom** (the same convention [CausalStability] uses). With a
 *   single open slot there is no "other", so nothing is ever lagging.
 * - Slot `S` is **unchanged** iff `rows[S]` equals its value at the previous
 *   [evaluate] — a rowless slot counts as unchanged against a rowless
 *   predecessor.
 * - A per-slot counter of *consecutive* (lagging ∧ unchanged) evaluations
 *   reaching [threshold] **latches** `S` and yields exactly ONE
 *   `Stall(STABILITY_FROZEN, timestamp, slot = S)`. The counter resets
 *   whenever the conjunction fails.
 * - The latch clears — yielding exactly ONE [StallNotice.Resume] — when
 *   `rows[S]` changes on any source, when `S` enters `closed`, or when `S`
 *   leaves the open set. While latched, `S` is not re-counted and yields no
 *   further stall.
 *
 * **Which source the timestamp names.** More than one source can witness the
 * lag. The witness reported is the one on which `S` sits *lowest* (absent
 * first), ties broken by source id, so the notice is deterministic across
 * runs and names the source the MIN is most obviously pinned on.
 *
 * `timestamp` is **null** whenever `S` has no row *for the reported witness*.
 * That is always so for an announced-but-rowless member (BS-7, the case
 * 9sm.5-D5 spells out), and — because "absent first" deliberately prefers the
 * source `S` lags most deeply on — it is also so for a slot that *does* hold
 * rows on other sources but is absent on the chosen one. The notice then
 * names the frozen slot with no wave position, which is what 9sm.5-D5 permits
 * in as many words: null "when the frozen slot has no row for that source".
 *
 * **[threshold] is ESTIMATED, not measured.** The default of 3 consecutive
 * evaluations is a guess at "long enough that this is not ordinary gossip
 * skew", chosen without a measurement of real inter-delta timing; it is a
 * constructor parameter precisely so a caller with evidence can override it.
 * Nothing about correctness depends on the value — it only trades how early
 * the diagnostic speaks against how often it speaks about a merely slow
 * member. A member that lags but *advances* every evaluation (slow, not
 * frozen) never trips at any threshold, because the counter resets on the
 * `unchanged` half.
 *
 * **An idle-but-alive member trips it too**, and that is honest (9sm.5-D2):
 * with heartbeat (`computenet-9sm.2`) on, an idle member's republish is a
 * fixpoint and does not advance its row, so the stability read really is
 * frozen on it. Hence "frozen", not "dead" — the [StallReason] KDoc says so.
 *
 * @param threshold consecutive (lagging ∧ unchanged) evaluations before a
 *   slot latches. Must be at least 1.
 */
class StabilityFreezeDetector(private val threshold: Int = 3) {

    init {
        require(threshold >= 1) { "threshold must be at least 1, was $threshold" }
    }

    /** `rows` as of the previous [evaluate] — the `unchanged` half of the predicate. */
    private var previousRows: Map<UUID, Map<UUID, Long>> = emptyMap()

    /** Consecutive (lagging ∧ unchanged) evaluations per not-yet-latched open slot. */
    private val counters = mutableMapOf<UUID, Int>()

    /** Slots already announced frozen; they neither re-count nor re-stall until retracted. */
    private val latched = mutableSetOf<UUID>()

    /**
     * One evaluation over ONE snapshot ([KE3-24]): the caller reads `rows`,
     * the open set and `closed` once each off the companion and hands them
     * here together, so a concurrent gossip delta cannot tear an evaluation
     * across two membership views.
     *
     * @param rows the companion's merged per-(slot, source) delivered rows.
     * @param open the WAIT open set — announced members ∪ instance-derived
     *   slots, minus `closed`. Suspended slots are deliberately INCLUDED: the
     *   WAIT read is frozen on them, and this notice says "frozen", not
     *   "dead" (9sm.5-D6).
     * @param closed the companion's closed slots — a slot arriving here
     *   retracts its latch.
     * @return the notices produced by this evaluation, in order: retractions
     *   ([StallNotice.Resume]) before new stalls. Empty on most evaluations.
     */
    fun evaluate(
        rows: Map<UUID, Map<UUID, Long>>,
        open: Set<UUID>,
        closed: Set<UUID>,
    ): List<StallNotice> {
        val notices = mutableListOf<StallNotice>()

        // Retractions first: a row that moved, a clean departure, or a slot
        // that left the open set. One Resume per cleared latch.
        for (slot in latched.toList()) {
            if (rows[slot] != previousRows[slot] || slot in closed || slot !in open) {
                latched -= slot
                counters -= slot
                notices += StallNotice.Resume
            }
        }

        for (slot in open) {
            if (slot in latched) continue
            val witness = lagWitness(slot, rows, open)
            val unchanged = rows[slot] == previousRows[slot]
            if (witness == null || !unchanged) {
                counters -= slot
                continue
            }
            val count = (counters[slot] ?: 0) + 1
            counters[slot] = count
            if (count >= threshold) {
                latched += slot
                counters -= slot
                notices += StallNotice.Stall(
                    reason = StallReason.STABILITY_FROZEN,
                    timestamp = rows[slot]?.get(witness)?.let { Timestamp(witness, it) },
                    slot = slot,
                )
            }
        }

        // A slot that left the open set without ever latching keeps no counter.
        counters.keys.retainAll(open)
        previousRows = rows.mapValues { (_, row) -> row.toMap() }
        return notices
    }

    /**
     * The source witnessing that [slot] lags every other open slot, or null
     * when it does not lag. Absent entries read as bottom, so a source is a
     * witness iff every other open slot has a value for it that is strictly
     * greater than [slot]'s (or [slot] has none at all). Ties are broken by
     * lowest value at [slot] (absent first) then by source id, for
     * determinism.
     */
    private fun lagWitness(
        slot: UUID,
        rows: Map<UUID, Map<UUID, Long>>,
        open: Set<UUID>,
    ): UUID? {
        val others = open.filterTo(mutableSetOf()) { it != slot }
        if (others.isEmpty()) return null
        val mine = rows[slot].orEmpty()
        val candidates = others.flatMapTo(mutableSetOf()) { rows[it]?.keys.orEmpty() }
        return candidates
            .filter { source ->
                val floor = mine[source]
                others.all { other ->
                    val theirs = rows[other]?.get(source)
                    theirs != null && (floor == null || theirs > floor)
                }
            }
            .minWithOrNull(compareBy({ mine[it] ?: Long.MIN_VALUE }, { it }))
    }
}
