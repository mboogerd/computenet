package civictech.demo.beadsmirror.projector

import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType

/**
 * Folds the feed's [ChangeRecord]s into the mirror's issue-field OR-map
 * (epic computenet-dqj §1; spec 20/24 §Tagged maps).
 *
 * **One record, one delta.** A single `bd update --status --priority` is one
 * commit and one record, and it lands as exactly one [TaggedMapDelta] touching
 * the keys that commit wrote. `ChangeRecord` already refuses to split it; this
 * projector refuses to re-split it downstream, so no observer sees half of a
 * commit.
 *
 * **Dots are minted outside the cell.** Every delta is built here, stamped by
 * [DotMinter] from the record's feed position, and injected through the
 * `Replicable` delta seam (`OrMapCell.deltaInlet`), where dots travel verbatim
 * (`[24-TAG-01]`). The cell's own `MapOps` inlet is deliberately never driven:
 * it mints from a cell-internal counter, which is not stable across a restart
 * that re-reads the feed. See [DotMinter] for why replay-identical dots are the
 * whole idempotence argument.
 *
 * **Removal is gated on the presence key**, not on the field keys. A remove is
 * tag-precise — it tombstones exactly the dots this projector observed live —
 * so a field put it never observed survives it (`[24-TMAP-04]`). Membership
 * therefore reads [MirrorKey.PRESENT] alone, and such a straggler shows up as a
 * dead field key rather than as a half-resurrected issue.
 *
 * **Scope.** Issue *fields* only. Dependency-edge projection (computenet-dqj.2.2)
 * and the `metadata.cn_dot` echo drop (computenet-dqj.2.3) extend [apply] at the
 * two seams marked in it; re-baseline (dqj.3) and HTTP serving (dqj.4) are later
 * features.
 */
class MirrorProjector(
    private val minter: DotMinter,
    /** The projected map. Exposed so tests and later features can read/subscribe. */
    val cell: OrMapCell<MirrorKey, String> = OrMapCell(),
) {

    /**
     * The dots this projector has minted and still believes live, per key.
     *
     * This is the observed-remove set: a removal tombstones exactly these, and
     * nothing else. It is a pure function of the records applied so far, so a
     * replay of the same records reproduces the same tombstones — which is what
     * makes replay idempotent rather than merely convergent.
     */
    private val mintedLive = mutableMapOf<MirrorKey, MutableSet<Timestamp>>()

    /**
     * Apply one record. Returns the delta injected, or `null` when the record
     * was effective-nothing (an edge-only record, or a replay that re-minted
     * dots the map already holds and tombstoned nothing new).
     */
    fun apply(record: ChangeRecord): TaggedMapDelta<MirrorKey, String>? {
        // ---- pre-apply hook (computenet-dqj.2.3, cn_dot echo drop) ----------
        // A record whose metadata.cn_dot the mirror already holds is dropped
        // whole, before any dot is minted, so the drop leaves no trace in the
        // map or in `mintedLive`. `admits` is that decision point.
        if (!admits(record)) return null

        val delta = fieldDelta(record)
        if (delta != null) cell.deltaInlet.call.propagate(delta)

        // ---- edge seam (computenet-dqj.2.2) ---------------------------------
        // `record.edgeDiffs` is projected into the dependency SetCell here,
        // minting from the same [minter] so edge tags share the dot source.
        return delta
    }

    /** Apply records in order; convenience for a feed batch. */
    fun applyAll(records: Iterable<ChangeRecord>) = records.forEach(::apply)

    /**
     * Whether this record reaches the projection at all. Unconditionally `true`
     * until the echo-drop registry lands (computenet-dqj.2.3).
     */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    private fun admits(record: ChangeRecord): Boolean = true

    /**
     * The record's key list, in the order whose indices [DotMinter] packs.
     *
     * Deterministic and positionally stable: the presence key holds slot 0
     * whether or not this record touches it, and the record's field columns
     * follow in sorted order. A record replayed from the same commit therefore
     * re-derives the same index for the same key, and so the same dot.
     */
    private fun keysOf(record: ChangeRecord): List<MirrorKey> =
        listOf(MirrorKey.presence(record.issueId)) +
            record.fieldDiffs.map { it.column }.distinct().sorted()
                .map { MirrorKey(record.issueId, it) }

    /** The one delta this record's issue-field half contributes, or `null`. */
    private fun fieldDelta(record: ChangeRecord): TaggedMapDelta<MirrorKey, String>? {
        // An edge-carrying, field-quiet record has no `dolt_diff_issues` row at
        // all, so it touches no field key. Its edges are dqj.2.2's business.
        if (record.diffType == null) return null

        val keys = keysOf(record)
        val puts = LinkedHashMap<MirrorKey, Map<Timestamp, String>>()
        val dels = LinkedHashMap<MirrorKey, MutableSet<Timestamp>>()

        /** Tombstone every dot this projector holds live at [key] except [survivor]. */
        fun tombstone(key: MirrorKey, survivor: Timestamp? = null) {
            val live = mintedLive[key] ?: return
            val covered = live.filterTo(LinkedHashSet()) { it != survivor }
            if (covered.isNotEmpty()) dels.getOrPut(key) { LinkedHashSet() } += covered
            if (survivor == null) mintedLive.remove(key) else live.removeAll(covered)
        }

        /**
         * Put [value] at [key] with the dot its slot mints. The previously-live
         * dots die in the SAME delta (re-put atomicity, `OrMapCell`'s own rule
         * lifted here), so no observer ever sees two live values for one key.
         * The dot being minted is excluded from that set: on a replay it *is*
         * the live dot, and tombstoning it would turn a re-read commit into a
         * deletion.
         */
        fun put(key: MirrorKey, keyIndex: Int, value: String) {
            val dot = minter.dot(record.position, keyIndex)
            tombstone(key, survivor = dot)
            puts[key] = mapOf(dot to value)
            mintedLive.getOrPut(key) { LinkedHashSet() } += dot
        }

        when (record.diffType) {
            // Reset-remove, whole-issue: every key of this issue that the
            // projector holds live is tombstoned in this one delta — the
            // presence key with them, which is what makes the issue absent from
            // `view()` immediately.
            DiffType.REMOVED -> mintedLive.keys
                .filter { it.issueId == record.issueId }
                .sortedBy { it.field }
                .forEach { tombstone(it) }

            DiffType.ADDED, DiffType.MODIFIED -> {
                if (record.diffType == DiffType.ADDED) {
                    put(keys[0], 0, MirrorKey.PRESENT_VALUE)
                }
                keys.drop(1).forEachIndexed { i, key ->
                    val keyIndex = i + 1
                    val new = record.fieldDiff(key.field)?.new
                    // Values are stored in their bd-export JSON form — no typed
                    // per-field schema (BDS1) — as a String, because
                    // `JsonElement` is not `java.io.Serializable` and the cell's
                    // snapshot seam requires Serializable K/V.
                    if (new == null) tombstone(key) else put(key, keyIndex, new.toString())
                }
            }

            null -> Unit // unreachable: returned above
        }

        if (puts.isEmpty() && dels.isEmpty()) return null // effective-only
        return TaggedMapDelta(puts, dels)
    }

    // ---------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------

    /**
     * The materialized view: present issues to their live field values.
     *
     * Membership is the presence key and nothing else (`[24-TMAP-02]` add-wins
     * over that one key), so a stale field key left live by a tag-precise remove
     * cannot put its issue back into this map.
     */
    fun view(): Map<String, Map<String, String>> {
        val live = cell.membership()
        val present = live.filterTo(LinkedHashSet()) { it.field == MirrorKey.PRESENT }
            .mapTo(LinkedHashSet()) { it.issueId }
        val out = sortedMapOf<String, MutableMap<String, String>>()
        present.forEach { out[it] = sortedMapOf() }
        live.filter { it.field != MirrorKey.PRESENT && it.issueId in present }
            .forEach { key -> cell.value(key)?.let { out.getValue(key.issueId)[key.field] = it } }
        return out
    }

    /** The live value at one key, ungated by presence — the raw map read. */
    fun rawValue(key: MirrorKey): String? = cell.value(key)
}
