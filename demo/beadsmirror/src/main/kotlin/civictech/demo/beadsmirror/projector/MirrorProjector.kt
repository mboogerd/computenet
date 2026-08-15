package civictech.demo.beadsmirror.projector

import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import kotlinx.serialization.json.JsonPrimitive

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
 * **Edges share the dot source but never a key-index slot.** Dependency-edge
 * diffs (computenet-dqj.2.2) mint from the same [minter], reusing
 * [DotMinter.dot]'s `(position, keyIndex)` packing rather than forking it, but
 * a record's edge tags always sit at key indices *above* every field key index
 * that same record reserves ([edgeDelta]) — so a field put and an edge add
 * minted by the same record never collide on one dot, even though the two
 * live in unrelated cells and a collision would cause no cell-level harm on
 * its own. [heldDots] is what makes that matter: it is one registry shared by
 * both halves, and two different puts rendering to the same cn_dot would
 * defeat "a dot identifies exactly one put".
 *
 * **Scope.** Issue fields and dependency edges. The `metadata.cn_dot` echo
 * drop (computenet-dqj.2.3) gates both, before either mints anything
 * ([admits]); re-baseline (dqj.3) and HTTP serving (dqj.4) are later features.
 */
class MirrorProjector(
    private val minter: DotMinter,
    /** The projected map. Exposed so tests and later features can read/subscribe. */
    val cell: OrMapCell<MirrorKey, String> = OrMapCell(),
    /** The projected dependency-edge set. Exposed so tests and later features can read/subscribe. */
    val edges: SetCell<MirrorEdge> = SetCell(),
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
     * [mintedLive]'s counterpart for the dependency-edge set (computenet-dqj.2.2):
     * the add-tags this projector has minted per [MirrorEdge] and still
     * believes live. A removal tombstones exactly these — restricted to the
     * ones minted before the removing record, the same floor [fieldDelta]
     * applies, and for the same reason: an unbounded cover would let one
     * record's replay bury a *later* record's live tag (see [edgeDelta]).
     */
    private val mintedLiveEdges = mutableMapOf<MirrorEdge, MutableSet<Timestamp>>()

    /**
     * The held-dot registry (computenet-dqj.2.3): the cn_dot renderings of
     * every dot this projector has itself minted, plus the cn_dot of every
     * record it has applied. Consulted by [admits] before any minting.
     */
    private val heldDots = CnDotRegistry()

    /**
     * How many inbound records have been dropped whole because their
     * `metadata.cn_dot` was already held — an observable counter so the drop
     * is externally verifiable rather than only internally consistent.
     */
    var echoDropCount: Int = 0
        private set

    /**
     * Apply one record. Returns the delta injected, or `null` when the record
     * was effective-nothing (an edge-only record, a dropped echo, or a replay
     * that re-minted dots the map already holds and tombstoned nothing new).
     */
    fun apply(record: ChangeRecord): TaggedMapDelta<MirrorKey, String>? {
        // ---- pre-apply hook (computenet-dqj.2.3, cn_dot echo drop) ----------
        // A record whose metadata.cn_dot the mirror already holds is dropped
        // whole, before any dot is minted, so the drop leaves no trace in the
        // map or in `mintedLive`. `admits` is that decision point.
        if (!admits(record)) return null

        val delta = fieldDelta(record)
        if (delta != null) cell.deltaInlet.call.propagate(delta)

        val edgeChange = edgeDelta(record)
        if (edgeChange != null) edges.deltaInlet.call.propagate(edgeChange)

        cnDotOf(record)?.let(heldDots::add)

        return delta
    }

    /** Apply records in order; convenience for a feed batch. */
    fun applyAll(records: Iterable<ChangeRecord>) = records.forEach(::apply)

    /**
     * The record's `metadata.cn_dot`, or `null` when it carries none.
     *
     * Read from `newMetadata`; a [DiffType.REMOVED] row has no `to_` side, so
     * its provenance is read off `oldMetadata` instead. Anything other than a
     * JSON string is not a shape this envelope can carry, so it is treated as
     * absent rather than coerced.
     */
    private fun cnDotOf(record: ChangeRecord): String? {
        val source = record.newMetadata
            ?: if (record.diffType == DiffType.REMOVED) record.oldMetadata else null
        val element = source?.get(CN_DOT_FIELD)
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * Whether this record reaches the projection at all.
     *
     * A record without a `metadata.cn_dot` (the normal single-node case) is
     * always admitted. One carrying a cn_dot this projector already holds —
     * because it minted the dot itself, or because it applied this exact
     * record before — is dropped whole and counted; anything else is admitted
     * and its cn_dot is recorded as held once [apply] commits it.
     */
    private fun admits(record: ChangeRecord): Boolean {
        val cnDot = cnDotOf(record) ?: return true
        if (heldDots.holds(cnDot)) {
            echoDropCount++
            return false
        }
        return true
    }

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

        // Every dot this record mints has a counter at or above this floor, and
        // every dot minted by a *strictly earlier* record is below it: the
        // packing puts (commitHeight, ordinal) above keyIndex, so the floor is
        // exactly the boundary between "the past" and "this record and after".
        val floor = DotMinter.counter(record.position, 0)

        /**
         * Tombstone the dots this projector holds live at [key] that were minted
         * **before this record** — and only those.
         *
         * The bound is what makes replay idempotent rather than destructive.
         * `dels` are permanent in `TaggedMapDelta`'s merge (a covered dot can
         * never come back live), so a tombstone this record has no business
         * emitting is not merely redundant on a replay: it kills a value a
         * *later* record already wrote and will re-mint under the very dot just
         * buried. Tombstoning "everything except the dot minted now" is enough
         * for a single record replayed twice, but re-reading a two-commit edit
         * sequence then deletes the field — the earlier record's replay covers
         * the later record's live dot, and the later record's own replay
         * re-mints a dot that is already tombstoned (regression tests
         * `replaying an edit sequence` / `replaying a clear-then-reset
         * sequence`). Restricting the cover to the past keeps re-put atomicity
         * exactly as strong going forward, where every live dot *is* in the
         * past.
         */
        fun tombstone(key: MirrorKey) {
            val live = mintedLive[key] ?: return
            val covered = live.filterTo(LinkedHashSet()) { it.counter < floor }
            if (covered.isNotEmpty()) dels.getOrPut(key) { LinkedHashSet() } += covered
            live.removeAll(covered)
            if (live.isEmpty()) mintedLive.remove(key)
        }

        /**
         * Put [value] at [key] with the dot its slot mints. The previously-live
         * dots die in the SAME delta (re-put atomicity, `OrMapCell`'s own rule
         * lifted here), so no observer ever sees two live values for one key.
         * The dot being minted is never in that set — it is not in the past —
         * so a replay re-mints the live dot instead of burying it.
         */
        fun put(key: MirrorKey, keyIndex: Int, value: String) {
            val dot = minter.dot(record.position, keyIndex)
            tombstone(key)
            puts[key] = mapOf(dot to value)
            mintedLive.getOrPut(key) { LinkedHashSet() } += dot
            heldDots.addMinted(dot)
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

    /**
     * This record's [EdgeDiff]s, in the deterministic order [edgeDelta] packs
     * them into key indices. Sorted on the triple itself — not on arrival
     * order in `record.edgeDiffs`, which reflects `dolt_diff_dependencies`' own
     * row order and is not something a replay can be relied on to reproduce.
     */
    private fun sortedEdgeDiffs(record: ChangeRecord): List<EdgeDiff> =
        record.edgeDiffs.sortedWith(
            compareBy({ it.issueId }, { it.dependsOnIssueId }, { it.type })
        )

    /**
     * The one [SetDelta] this record's dependency-edge half contributes, or
     * `null` when the record carries no edge diffs at all (the common case —
     * most records are field-only).
     *
     * **Key indices never collide with [fieldDelta]'s.** [keysOf] gives the
     * exact count of key indices this same record's field half reserves
     * (`0` when the record is edge-only, since [keysOf] is a pure function of
     * `record.fieldDiffs` regardless of `diffType`); edge tags start one past
     * that, in [sortedEdgeDiffs] order. A record's edges therefore never mint
     * under a dot [fieldDelta] also minted, even though the two would land in
     * unrelated cells and a collision would cause no cell-level harm on its
     * own — see the class doc's note on why [heldDots] makes it matter anyway.
     *
     * **ADD and MODIFIED both mint an add-tag.** [EdgeDiff] carries only the
     * *current* `type` (the diff row's `to_` side; see
     * `DoltCommitFeed.edgeDiff`), never the prior one, so a `MODIFIED` edge —
     * the dependency's relation `type` changed while the pair of issue ids
     * stayed the same — cannot be told apart here from a fresh `ADDED` one,
     * and a stale `(issueId, dependsOnIssueId, oldType)` triple this projector
     * minted earlier has no observed removal to retract it. This is a known
     * gap in what the current envelope can express, not something silently
     * papered over: filing a fix would mean widening [EdgeDiff] upstream
     * (computenet-dqj.1 territory), which is out of this task's claim.
     *
     * **REMOVED tombstones exactly this edge's live tags minted before this
     * record** ([tombstoneEdge]) — the same past-only bound [fieldDelta]'s
     * `tombstone` applies, and for the identical reason: replaying a
     * multi-record sequence must not let an earlier record's replayed removal
     * bury a later record's replayed (re-)add.
     */
    private fun edgeDelta(record: ChangeRecord): SetDelta<MirrorEdge>? {
        if (record.edgeDiffs.isEmpty()) return null

        val fieldKeyCount = keysOf(record).size
        val floor = DotMinter.counter(record.position, 0)
        val adds = LinkedHashMap<MirrorEdge, Set<Timestamp>>()
        val dels = LinkedHashMap<MirrorEdge, MutableSet<Timestamp>>()

        fun tombstoneEdge(edge: MirrorEdge) {
            val live = mintedLiveEdges[edge] ?: return
            val covered = live.filterTo(LinkedHashSet()) { it.counter < floor }
            if (covered.isNotEmpty()) dels.getOrPut(edge) { LinkedHashSet() } += covered
            live.removeAll(covered)
            if (live.isEmpty()) mintedLiveEdges.remove(edge)
        }

        sortedEdgeDiffs(record).forEachIndexed { i, diff ->
            val edge = MirrorEdge(diff.issueId, diff.dependsOnIssueId, diff.type)
            val keyIndex = fieldKeyCount + i
            when (diff.diffType) {
                DiffType.REMOVED -> tombstoneEdge(edge)
                DiffType.ADDED, DiffType.MODIFIED -> {
                    val tag = minter.dot(record.position, keyIndex)
                    adds[edge] = setOf(tag)
                    mintedLiveEdges.getOrPut(edge) { LinkedHashSet() } += tag
                    heldDots.addMinted(tag)
                }
            }
        }

        if (adds.isEmpty() && dels.isEmpty()) return null // effective-only
        return SetDelta(adds, dels)
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

    /** The materialized dependency set: the workspace's current edges. */
    fun edgeView(): Set<MirrorEdge> = edges.membership()
}

/** The `metadata` JSON column's provenance field (epic computenet-dqj acceptance rule 5). */
private const val CN_DOT_FIELD: String = "cn_dot"
