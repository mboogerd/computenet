package civictech.demo.beadsmirror.ready

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.catchUpOnLinked
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import java.util.UUID

/**
 * The mirror's **derived ready set** (task computenet-98u.1.2; feature
 * computenet-98u.1; epic computenet-98u/BDS3): the set of issue ids that
 * satisfy beads' ready predicate, joined incrementally from the two cells
 * [MirrorProjector] already publishes and from nothing else.
 *
 * ```
 *   MirrorProjector.cell  : OrMapCell<MirrorKey, String>  --TaggedMapDelta-->  fieldInlet
 *                                                                                 \
 *                                                                                  >-- outlet: SetDelta<String>
 *                                                                                 /
 *   MirrorProjector.edges : SetCell<MirrorEdge>           --SetDelta------->  edgeInlet
 * ```
 *
 * **`is_blocked` is never consumed.** Beads persists blockedness as a
 * denormalized `is_blocked` column maintained across a dozen write paths and
 * recomputed wholesale after a merge; that column reaches this mirror like
 * every other field, and this cell deliberately ignores it. Blockedness is
 * derived live from [MirrorProjector.edges] plus the *blocker's* mirrored
 * status ([isOpenBlocker]) — deriving it is the point of BDS3, and consuming
 * the column would inherit its staleness. The `lying is_blocked` test in
 * `ReadySetCellTest` is the standing proof: an otherwise-ready issue carrying
 * a mirrored `is_blocked=1` is still ready.
 *
 * **No `bd`, no `dolt`, no subprocess.** Nothing on the derivation path — this
 * file, [ReadyPredicate], or `civictech.demo.beadsmirror.projector` — names
 * `ProcessBuilder`, `Runtime` or `ProcessHandle`; `ReadySetCellTest`'s
 * `NoSubprocessProbe` asserts that structurally against the compiled classes
 * (see that probe's KDoc for exactly what it does and does not establish).
 *
 * **Semantics are consumed, not re-derived.** Which clauses the per-issue
 * predicate models is [ReadyPredicate]'s; which `dep_type` values block, what
 * an open blocker is, and how a dangling/foreign target behaves are
 * `demo/beadsmirror/READY-COVERAGE.md` §2, established there from beads
 * source at a pinned commit. This cell implements §2's *modelled* half:
 * [BLOCKING_TYPES] (§2.1), [OPEN_BLOCKER_EXCLUDED_STATUSES] (§2.2), and the
 * dangling rule (§2.3: an edge whose target is not an issue this mirror holds
 * does not block). §2's excluded half — `waits-for` gate metadata and
 * `parent-child` propagation — is out of computenet-98u's scope and is
 * therefore *not* implemented here either; every other edge `type` is
 * non-blocking.
 *
 * ## Why this is hand-written rather than composed
 *
 * The join is `SemiJoinCell` twice over (`edges ⋉ openIssues`, then
 * `candidates ▷ blocked`), and it is still written out here because neither
 * input can reach that operator: nothing in `civictech.cell.data.op` consumes
 * a [TaggedMapDelta] at all, and even untagged there is no operator that
 * regroups a composite-keyed map stream into the per-issue field record
 * [ReadyPredicate] needs. Both gaps are recorded in `doc/demo-findings.md`
 * (F-9), with the adapter/regroup shapes that would close them; the private
 * re-fold of `OrMapCell`'s dot algebra below ([putDots]/[deadDots]) is the
 * cost of the first one. F-10 records the second workaround, the attach-order
 * precondition at the bottom of this doc.
 *
 * ## Incrementality
 *
 * One delta re-evaluates the issues it can possibly have moved, never the
 * workspace:
 *
 * - a **field** delta re-evaluates the issues whose keys it touched, plus —
 *   only when a touched issue's own *open-blocker* state flipped — that
 *   issue's dependents, read off the reverse index [dependentsOf]
 *   (`blocker id -> dependent issue ids`), maintained under the edge deltas;
 * - an **edge** delta re-evaluates the owning side of each blocking-typed
 *   edge it touched.
 *
 * [evaluationCount] instruments exactly that: it counts per-issue predicate
 * re-evaluations, so incrementality is evidenced by a *count* and never by
 * wall-clock timing (AGENTS.md forbids scheduling-timing assertions). The
 * bound one delta is held to is "the touched issues plus their dependents",
 * which is what `ReadySetCellTest`'s 500-issue fixture pins.
 *
 * Note what the bound is **not**: the reverse index is walked one hop, not
 * transitively, because the one transitive blocking rule beads has
 * (`parent-child` propagation) is excluded above. Were it ever modelled, this
 * would have to walk the closure and the bound would widen with it.
 *
 * ## Emission, and what a mid-record observation can see
 *
 * The outlet emits an effective-only [SetDelta] of issue ids: a fresh tag is
 * minted per entry and exactly that tag is deleted on exit (the non-monotone
 * operator's tag-hygiene rule — an id can re-enter with no fresh input tag to
 * ride, so borrowing input tags would leave it dead forever under a
 * tombstone-folding consumer). Late joiners catch up through
 * [catchUpOnLinked].
 *
 * One [ChangeRecord][civictech.demo.beadsmirror.feed.ChangeRecord] that edits
 * both fields and edges reaches this cell as **two** deltas on two inlets,
 * because `MirrorProjector.apply` propagates into two independent cells. Both
 * propagations complete before `apply` returns, so any observation made
 * between records is whole; an observation interleaved *within* one record can
 * still see the field half applied and the edge half not. That is the
 * glitch-freedom question sibling feature computenet-98u.3 owns and tests —
 * this cell deliberately keeps it *observable* (the intermediate state is a
 * real emission, not something swallowed) rather than papering over it here,
 * which is what a wave-gated wrapper would do.
 *
 * ## Attach before you feed
 *
 * [derivedFrom] installs plain subscriptions, and `subscribe` does not fire
 * the outlet's on-link catch-up hook (only `streamTo`/a negotiated handshake
 * does). `SetCell` also publishes no `state()` accessor a caller could replay
 * by hand with the real tags. So a cell attached to a projector that has
 * already applied records starts from that point, not from the projector's
 * accumulated state. Attach it before the first record — every call site and
 * every test does — and, if a later feature needs late attachment (the
 * re-baseline path is the obvious candidate), that is its own item, not a
 * silent partial fold here.
 */
class ReadySetCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {

    /** The mirror's issue-field OR-map stream ([MirrorProjector.cell]'s outlet). */
    val fieldInlet: FanInlet<Propagate<TaggedMapDelta<MirrorKey, String>>> =
        registerPort("fieldInlet", FanInlet.create())

    /** The mirror's dependency-edge set stream ([MirrorProjector.edges]' outlet). */
    val edgeInlet: FanInlet<Propagate<SetDelta<MirrorEdge>>> =
        registerPort("edgeInlet", FanInlet.create())

    /** Effective-only ready-set membership changes, as ids entering and leaving. */
    val outlet: FanOutlet<Propagate<SetDelta<String>>> =
        registerPort("outlet", FanOutlet.create())

    init {
        fieldInlet.onEach(::onFields)
        edgeInlet.onEach(::onEdges)
        outlet.catchUpOnLinked { if (advertised.isEmpty()) null else advertisedDelta() }
    }

    // ------------------------------------------------------------------
    // the OR-map fold: dots per (issue, field) key
    // ------------------------------------------------------------------

    /** Every put dot this cell has seen at a key, with the value that put wrote. */
    private val putDots = mutableMapOf<MirrorKey, MutableMap<Timestamp, String>>()

    /** The dots a remove covered. A key's live dots are [putDots] minus these. */
    private val deadDots = mutableMapOf<MirrorKey, MutableSet<Timestamp>>()

    /** Which keys each issue owns — what makes [fieldsOf] an index read, not a map scan. */
    private val keysByIssue = mutableMapOf<String, MutableSet<MirrorKey>>()

    /**
     * The key's exposed value: the live dot with the greatest
     * [TaggedMapDelta.DOT_ORDER] — `OrMapCell`'s own `[24-TMAP-03]` read rule,
     * applied to this cell's copy of the fold. `null` when the key is absent.
     */
    private fun fieldValue(key: MirrorKey): String? {
        val dots = putDots[key] ?: return null
        val covered = deadDots[key]
        val live = if (covered == null) dots else dots.filterKeys { it !in covered }
        return live.entries.maxWithOrNull(compareBy(TaggedMapDelta.DOT_ORDER) { it.key })?.value
    }

    /**
     * `[24-TMAP-02]` add-wins presence, read off the presence key alone —
     * `MirrorProjector.view`'s rule, for the same reason: a tag-precise remove
     * cannot tombstone a put it never observed, so a straggling field key must
     * not resurrect its issue.
     */
    private fun isPresent(issueId: String): Boolean =
        fieldValue(MirrorKey.presence(issueId)) != null

    /** One issue's mirrored fields, presence key excluded — [ReadyPredicate]'s input. */
    private fun fieldsOf(issueId: String): Map<String, String> {
        val keys = keysByIssue[issueId] ?: return emptyMap()
        val out = LinkedHashMap<String, String>(keys.size)
        keys.forEach { key ->
            if (key.field == MirrorKey.PRESENT) return@forEach
            fieldValue(key)?.let { out[key.field] = it }
        }
        return out
    }

    // ------------------------------------------------------------------
    // the edge fold, and the reverse index over it
    // ------------------------------------------------------------------

    /** Add-tags seen per edge triple. */
    private val edgeAdds = mutableMapOf<MirrorEdge, MutableSet<Timestamp>>()

    /** Tombstoned tags per edge triple. An edge is live while some add-tag is uncovered. */
    private val edgeDels = mutableMapOf<MirrorEdge, MutableSet<Timestamp>>()

    /** Live blocking-typed edges by their *owning* side — "what could block me". */
    private val blockingEdgesOf = mutableMapOf<String, MutableSet<MirrorEdge>>()

    /**
     * The reverse index the incrementality bound rests on: blocker id ->
     * the issue ids that hold a live blocking-typed edge onto it. Maintained
     * under the edge deltas ([reindex]), so a status change on one issue can
     * reach exactly the issues it can unblock without a workspace scan.
     */
    private val dependentsOf = mutableMapOf<String, MutableSet<String>>()

    private fun isEdgeLive(edge: MirrorEdge): Boolean {
        val adds = edgeAdds[edge] ?: return false
        val covered = edgeDels[edge] ?: return adds.isNotEmpty()
        return adds.any { it !in covered }
    }

    /**
     * Fold one touched edge into [blockingEdgesOf]/[dependentsOf]. Returns
     * whether the edge is blocking-typed at all — a non-blocking type can
     * never move a membership, so its owner is not re-evaluated.
     */
    private fun reindex(edge: MirrorEdge): Boolean {
        if (edge.type !in BLOCKING_TYPES) return false
        val held = blockingEdgesOf[edge.issueId]
        val wasLive = held != null && edge in held
        val nowLive = isEdgeLive(edge)
        if (nowLive == wasLive) return true

        if (nowLive) {
            blockingEdgesOf.getOrPut(edge.issueId) { LinkedHashSet() } += edge
            dependentsOf.getOrPut(edge.dependsOnIssueId) { LinkedHashSet() } += edge.issueId
        } else {
            held?.remove(edge)
            if (held != null && held.isEmpty()) blockingEdgesOf.remove(edge.issueId)
            // The pair may still be joined by a second live blocking-typed
            // triple (a `blocks` and a `conditional-blocks` between the same
            // two ids), so the reverse-index entry drops only when no live
            // blocking edge from this issue onto this target remains.
            val stillJoined = blockingEdgesOf[edge.issueId]
                ?.any { it.dependsOnIssueId == edge.dependsOnIssueId } == true
            if (!stillJoined) {
                val dependents = dependentsOf[edge.dependsOnIssueId]
                dependents?.remove(edge.issueId)
                if (dependents != null && dependents.isEmpty()) dependentsOf.remove(edge.dependsOnIssueId)
            }
        }
        return true
    }

    /**
     * Whether [issueId] counts as an **open blocker** for anything that
     * depends on it (READY-COVERAGE §2.2/§2.3): a mirrored issue whose status
     * is neither `closed` nor `pinned`.
     *
     * An id this mirror does not hold — the dangling/foreign
     * `dependsOnIssueId` case — is **not** an open blocker, matching beads'
     * own `INNER JOIN` against `issues`/`wisps` (READY-COVERAGE §2.3). Nor is
     * a present issue with no mirrored status, matching the SQL's `NULL`
     * comparison, which is false rather than blocking.
     *
     * The `pinned` here is beads' issue *status* enum value, unrelated to the
     * boolean `pinned` column [ReadyPredicate] tests on the ready issue
     * itself — READY-COVERAGE §2.2 spells out why the two never interact.
     */
    private fun isOpenBlocker(issueId: String): Boolean {
        if (!isPresent(issueId)) return false
        val status = ReadyPredicate.stringField(fieldsOf(issueId), "status") ?: return false
        return status !in OPEN_BLOCKER_EXCLUDED_STATUSES
    }

    /** `is_blocked`, derived: some live blocking edge of this issue targets an open blocker. */
    private fun isBlocked(issueId: String): Boolean =
        blockingEdgesOf[issueId]?.any { isOpenBlocker(it.dependsOnIssueId) } == true

    // ------------------------------------------------------------------
    // inlets
    // ------------------------------------------------------------------

    private fun onFields(delta: TaggedMapDelta<MirrorKey, String>) {
        val touched = delta.keys().mapTo(LinkedHashSet()) { it.issueId }
        // Captured BEFORE the fold: a dependent is re-evaluated only when the
        // issue it depends on actually crossed the open-blocker line, which is
        // what keeps an ordinary field edit (a priority bump, a description
        // rewrite) from fanning out to dependents at all.
        val wasOpenBlocker = touched.associateWith(::isOpenBlocker)

        delta.puts.forEach { (key, dots) ->
            putDots.getOrPut(key) { LinkedHashMap() }.putAll(dots)
            keysByIssue.getOrPut(key.issueId) { LinkedHashSet() } += key
        }
        delta.dels.forEach { (key, dots) ->
            deadDots.getOrPut(key) { LinkedHashSet() } += dots
            keysByIssue.getOrPut(key.issueId) { LinkedHashSet() } += key
        }

        val affected = LinkedHashSet(touched)
        touched.forEach { issueId ->
            if (isOpenBlocker(issueId) != wasOpenBlocker[issueId]) {
                affected += dependentsOf[issueId].orEmpty()
            }
        }
        reconcile(affected)
    }

    private fun onEdges(delta: SetDelta<MirrorEdge>) {
        val touched = LinkedHashSet<MirrorEdge>(delta.adds.keys)
        touched += delta.dels.keys

        delta.adds.forEach { (edge, tags) -> edgeAdds.getOrPut(edge) { LinkedHashSet() } += tags }
        delta.dels.forEach { (edge, tags) -> edgeDels.getOrPut(edge) { LinkedHashSet() } += tags }

        val affected = LinkedHashSet<String>()
        touched.forEach { edge -> if (reindex(edge)) affected += edge.issueId }
        reconcile(affected)
    }

    // ------------------------------------------------------------------
    // reconciliation and emission
    // ------------------------------------------------------------------

    /**
     * How many times a single issue's readiness has been re-evaluated since
     * this cell was built — the instrument incrementality is argued from.
     *
     * It counts **reconsiderations**, one per issue per [reconcile] pass,
     * including the ones [evaluate] short-circuits on absence before calling
     * [ReadyPredicate]; that is the conservative direction (it can only
     * overstate the work done). Reading a *blocker's* status inside
     * [isBlocked] is not a reconsideration of that blocker and is not counted:
     * the blocker's own membership is not being decided.
     */
    var evaluationCount: Long = 0L
        private set

    private fun evaluate(issueId: String): Boolean {
        evaluationCount++
        if (!isPresent(issueId)) return false
        return ReadyPredicate.isReady(fieldsOf(issueId), blocked = isBlocked(issueId))
    }

    /**
     * Output tags, minted per entry and deleted exactly on exit. A ready-set
     * membership can flip ON with no fresh input tag to ride (the last blocker
     * closing is a *removal* upstream), so borrowing input tags would be the
     * non-monotone operator's classic tag-hygiene bug.
     */
    private val tagSource: UUID =
        UUID.nameUUIDFromBytes("beadsmirror-ready:${ref.id}:${ref.instanceId}".toByteArray())
    private var tagCounter = 0L
    private val advertised = LinkedHashMap<String, Timestamp>()

    private fun advertisedDelta(): SetDelta<String> =
        SetDelta(adds = advertised.mapValues { setOf(it.value) })

    private fun reconcile(affected: Set<String>) {
        if (affected.isEmpty()) return
        val adds = LinkedHashMap<String, Set<Timestamp>>()
        val dels = LinkedHashMap<String, Set<Timestamp>>()
        affected.forEach { issueId ->
            val ready = evaluate(issueId)
            val tag = advertised[issueId]
            when {
                ready && tag == null -> {
                    val minted = Timestamp(tagSource, ++tagCounter)
                    advertised[issueId] = minted
                    adds[issueId] = setOf(minted)
                }

                !ready && tag != null -> {
                    advertised.remove(issueId)
                    dels[issueId] = setOf(tag)
                }
            }
        }
        if (adds.isNotEmpty() || dels.isNotEmpty()) outlet.call.propagate(SetDelta(adds, dels))
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The derived value: the ids currently in the ready set. A set — ordering is out of scope. */
    fun readySet(): Set<String> = LinkedHashSet(advertised.keys)

    companion object {

        /**
         * The `dep_type` values that block, per READY-COVERAGE §2.1 — beads'
         * own `type IN ('blocks', 'conditional-blocks')` in the SQL that
         * maintains `is_blocked`. `waits-for` and `parent-child` propagation
         * are the section's excluded half and are deliberately absent.
         */
        val BLOCKING_TYPES: Set<String> = setOf("blocks", "conditional-blocks")

        /**
         * A blocker with one of these statuses no longer blocks, per
         * READY-COVERAGE §2.2 (`t.status <> 'closed' AND t.status <>
         * 'pinned'`).
         */
        val OPEN_BLOCKER_EXCLUDED_STATUSES: Set<String> = setOf("closed", "pinned")

        /**
         * A ready cell subscribed to [projector]'s two outlets — the only
         * wiring this derivation needs, and the only two inputs it has.
         *
         * Attach before the projector's first record; see the class KDoc's
         * "Attach before you feed".
         */
        fun derivedFrom(
            projector: MirrorProjector,
            ref: CellRef = CellRef(UUID.randomUUID()),
        ): ReadySetCell = ReadySetCell(ref).also { ready ->
            projector.cell.outlet.subscribe(ready.fieldInlet)
            projector.edges.outlet.subscribe(ready.edgeInlet)
        }
    }
}
