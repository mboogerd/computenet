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
 *
 * **Both structural guards can be seeded away — for tests only.** The two
 * paragraphs above describe guards, and a guard nothing can switch off is a
 * guard nobody has ever seen work. [SeededDefects] is that switch
 * (computenet-dqj.5.3): reachable only from this module's test source set,
 * never from [civictech.demo.beadsmirror.BeadsMirrorConfig] or `main`, and
 * defaulting to [SeededDefects.NONE] on the public constructor, so shipped
 * behaviour is exactly what it was before the switch existed.
 */
class MirrorProjector(
    private val minter: DotMinter,
    /** The projected map. Exposed so tests and later features can read/subscribe. */
    val cell: OrMapCell<MirrorKey, String> = OrMapCell(),
    /** The projected dependency-edge set. Exposed so tests and later features can read/subscribe. */
    val edges: SetCell<MirrorEdge> = SetCell(),
) {

    /**
     * Which structural guards are seeded away. **Always [SeededDefects.NONE]
     * for a projector built through the public constructor** — only the
     * `internal` constructor below can set it, and only this module's tests
     * call that one.
     *
     * A `var` set from a secondary constructor rather than a primary-constructor
     * parameter for a visibility reason, not a stylistic one: a public
     * constructor may not expose an `internal` parameter type, and making the
     * primary constructor `internal` instead would narrow a type other code in
     * this module legitimately constructs.
     */
    private var defects: SeededDefects = SeededDefects.NONE

    /**
     * **Test-only.** A projector with [defects] seeded, for the divergence
     * controls of computenet-dqj.5.3 (feature computenet-dqj.5 rules 3 and 4):
     * each control seeds one guard away and shows the mirror-vs-export
     * equality check goes red, which is what makes the guard demonstrably
     * load-bearing rather than decorative.
     */
    internal constructor(
        minter: DotMinter,
        defects: SeededDefects,
        cell: OrMapCell<MirrorKey, String> = OrMapCell(),
        edges: SetCell<MirrorEdge> = SetCell(),
    ) : this(minter, cell, edges) {
        this.defects = defects
    }

    /**
     * A projector whose [cell] and [edges] are built under [refs]' deterministic
     * shared logical [CellRef][civictech.cell.CellRef]s (task computenet-7em.1.1)
     * instead of the primary constructor's random-ref defaults. Two projectors
     * built with the same [MirrorCellRefs.rigName] and different
     * [MirrorCellRefs.role]s yield cells whose `CellRef.id` agrees and whose
     * `CellRef.instanceId` differs — the identity precondition feature
     * computenet-7em.1's rule 1 states directly.
     *
     * The no-arg/default construction path above is untouched by this
     * overload: every existing single-node caller keeps minting random refs
     * exactly as before.
     */
    constructor(minter: DotMinter, refs: MirrorCellRefs) : this(
        minter,
        cell = OrMapCell(refs.mapRef),
        edges = SetCell(refs.edgeRef),
    )

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
     * The record's field columns, in the deterministic order [keysOf] and
     * [fieldDelta] assign key indices from.
     */
    private fun columnsOf(record: ChangeRecord): List<String> =
        record.fieldDiffs.map { it.column }.distinct().sorted()

    /**
     * The map key one field column of one issue lands on: **one key per
     * (issue, field)** ([MirrorKey]) — unless [SeededDefects.wholeIssueKeying]
     * is seeded, which collapses every column of an issue onto the single
     * [SeededDefects.WHOLE_ISSUE_FIELD] key so two commits editing different
     * fields compete for it.
     */
    private fun keyFor(issueId: String, column: String): MirrorKey =
        if (defects.wholeIssueKeying) MirrorKey(issueId, SeededDefects.WHOLE_ISSUE_FIELD)
        else MirrorKey(issueId, column)

    /**
     * Slot 0: the presence key, or nothing when
     * [SeededDefects.dropPresenceKey] is seeded. Its *size* is what the field
     * key indices are offset by, so dropping it shifts every field index down
     * by one rather than leaving a hole.
     */
    private fun presenceSlot(record: ChangeRecord): List<MirrorKey> =
        if (defects.dropPresenceKey) emptyList() else listOf(MirrorKey.presence(record.issueId))

    /**
     * The record's key list, in the order whose indices [DotMinter] packs.
     *
     * Deterministic and positionally stable: the presence key holds slot 0
     * whether or not this record touches it, and the record's field columns
     * follow in sorted order. A record replayed from the same commit therefore
     * re-derives the same index for the same key, and so the same dot.
     */
    private fun keysOf(record: ChangeRecord): List<MirrorKey> =
        presenceSlot(record) + columnsOf(record).map { keyFor(record.issueId, it) }

    /** The one delta this record's issue-field half contributes, or `null`. */
    private fun fieldDelta(record: ChangeRecord): TaggedMapDelta<MirrorKey, String>? {
        // An edge-carrying, field-quiet record has no `dolt_diff_issues` row at
        // all, so it touches no field key. Its edges are dqj.2.2's business.
        if (record.diffType == null) return null

        val presenceSlot = presenceSlot(record)
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
                    // Nothing to mint when the presence key is seeded away:
                    // membership then falls back to the field keys, which is
                    // the whole point of that control.
                    presenceSlot.forEach { put(it, 0, MirrorKey.PRESENT_VALUE) }
                }
                columnsOf(record).forEachIndexed { i, column ->
                    val key = keyFor(record.issueId, column)
                    val keyIndex = i + presenceSlot.size
                    val new = record.fieldDiff(column)?.new
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
     * **Key indices never collide with [fieldDelta]'s.** [keysOf] always
     * includes the presence key, so its size is never `0` — even for an
     * edge-only, `diffType == null` record it is `1` — but it is still an
     * upper bound on every key index [fieldDelta] can ever mint for this
     * record: [fieldDelta] only mints at indices `0` until `keysOf.size - 1`
     * (and mints none at all for `REMOVED` or `diffType == null`). Edge tags
     * start one index past that bound ([keysOf]`.size`), in [sortedEdgeDiffs]
     * order, which is why they never land inside the range [fieldDelta] might
     * use — occasionally one slot more conservative than strictly necessary
     * (e.g. `MODIFIED`, which reserves index `0` for the presence key without
     * ever minting it), never less. A record's edges therefore never mint
     * under a dot [fieldDelta] also minted, even though the two would land in
     * unrelated cells and a collision would cause no cell-level harm on its
     * own — see the class doc's note on why [heldDots] makes it matter anyway.
     *
     * **ADD and MODIFIED both mint an add-tag; MODIFIED also retracts the
     * stale old-type triple first (computenet-dqj.7).** `bd`'s own schema
     * (`dependencies`' `UNIQUE KEY uk_dep_issue_target (issue_id,
     * depends_on_issue_id)`) and `bd dep add`'s own refusal to add a second
     * type over an existing pair without a `dep remove` prove a pair holds at
     * most one live type at a time — so a `MODIFIED` row is a type
     * *replacement*, never a second live type. [EdgeDiff.oldType] carries the
     * diff row's `from_` side for exactly this diff type, which is what lets
     * [tombstoneEdge] retract the `(issueId, dependsOnIssueId, oldType)`
     * triple this projector minted earlier in the SAME delta that adds the
     * new-type triple, so no observer ever sees both live at once.
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
                DiffType.ADDED -> {
                    val tag = minter.dot(record.position, keyIndex)
                    adds[edge] = setOf(tag)
                    mintedLiveEdges.getOrPut(edge) { LinkedHashSet() } += tag
                    heldDots.addMinted(tag)
                }
                DiffType.MODIFIED -> {
                    // Retract the stale old-type triple first, in the same
                    // delta as the new-type add, so no observer ever sees
                    // both (issueId, dependsOnIssueId) triples live at once.
                    diff.oldType
                        ?.takeIf { it != diff.type }
                        ?.let { old -> tombstoneEdge(MirrorEdge(diff.issueId, diff.dependsOnIssueId, old)) }
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
        val present = if (defects.dropPresenceKey) {
            // The seeded defect: membership derived from *any* live key of the
            // issue, so a field put no removal could tombstone resurrects it.
            live.mapTo(LinkedHashSet()) { it.issueId }
        } else {
            live.filterTo(LinkedHashSet()) { it.field == MirrorKey.PRESENT }
                .mapTo(LinkedHashSet()) { it.issueId }
        }
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

/**
 * Which of [MirrorProjector]'s two structural guards are **seeded away** —
 * the divergence controls of computenet-dqj.5.3 (feature computenet-dqj.5,
 * acceptance rules 3 and 4).
 *
 * **This is a test-only switch, deliberately not shipped configuration**
 * (feature design: "divergence controls are implemented as test-only
 * switches, not shipped configuration"). It is `internal`, so it exists for
 * this module's own test source set and for nothing else;
 * [civictech.demo.beadsmirror.BeadsMirrorConfig] carries no counterpart, and
 * neither `main` nor any production call site can reach it. A projector built
 * through [MirrorProjector]'s public constructor is always [NONE].
 *
 * Why it exists at all: each guard's KDoc argues that dropping it would break
 * a specific property, and until something drops it that argument is
 * untested. `civictech.demo.beadsmirror.e2e.DivergenceControlTest` seeds each
 * defect against a real `bd` workspace and shows the mirror-vs-export
 * equality check turns red — and clean again with the defect off.
 *
 * @param dropPresenceKey omit the presence key entirely: nothing is minted at
 *   slot 0 on an `ADDED` record, and `MirrorProjector.view` derives membership
 *   from any live field key instead of from [MirrorKey.PRESENT] alone. The
 *   removal path is untouched (a remove stays tag-precise), so a field put the
 *   projector never observed — a foreign-sourced straggler arriving through
 *   the replicated-delta seam — is enough to resurrect a removed issue as a
 *   partial record.
 * @param wholeIssueKeying replace the composite (issue, field) key with one
 *   key per issue ([WHOLE_ISSUE_FIELD]). Two commits editing *different*
 *   fields of one issue then compete for that key, and the later put's
 *   floor-bounded tombstone buries the earlier one's value.
 */
internal data class SeededDefects(
    val dropPresenceKey: Boolean = false,
    val wholeIssueKeying: Boolean = false,
) {
    companion object {
        /** No defect seeded: exactly the shipped behaviour. */
        val NONE: SeededDefects = SeededDefects()

        /**
         * The single field name every column collapses onto under
         * [wholeIssueKeying]. Double-underscore-prefixed for the same reason
         * [MirrorKey.PRESENT] is: `bd`'s own diff columns are plain SQL
         * identifiers, so no real column can collide with it.
         */
        const val WHOLE_ISSUE_FIELD: String = "__whole"
    }
}
