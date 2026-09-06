package civictech.cell.data

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Propagate
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.data.delta.DeliveredFrontier
import civictech.cell.data.delta.DeliveryTracking
import civictech.cell.data.delta.SetDelta
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

@Contract
interface SetOps<E> {
    fun add(element: E)
    fun remove(element: E)
}

@CellBase
interface SetApi<E> {
    val inlet: Use<SetOps<E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

class SetCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    // BoundedStateful extends Stateful (V1C-KERNEL): the drain/migration/
    // promotion/durability seam this cell already had is untouched, and the
    // paged read is added beside it.
    SetCellBase<E>(ref), BoundedStateful, Replicable<SetDelta<E>>, DeliveryTracking {
    /**
     * Replica gossip intake (spec 42, M7.3): another replica's effective
     * deltas merge here; only *new* tag information re-emits (effective-only,
     * 21), so gossip echoes around any mesh topology die out.
     */
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<SetDelta<E>>>())

    // Full OR-set (M7.3): adds = every add-tag ever seen, dels = tombstones.
    // An element is present iff it has an add-tag without a matching del-tag.
    // Tombstones are what make multi-path gossip safe: a removed tag arriving
    // late over another path stays removed.
    // ponytail: tag sets grow monotonically; compaction is future work (G-25)
    private val adds = mutableMapOf<E, MutableSet<Timestamp>>()
    private val dels = mutableMapOf<E, MutableSet<Timestamp>>()

    /**
     * Guards **every** access to [adds], [dels], [tagCounter], [delivered] and
     * [deliveryListeners] — the read accessors as much as the writers. The
     * element-shaped twin of `OrMapCell.stateLock` (computenet-yk5r), taken
     * for the same reason and with the same discipline.
     *
     * The cell's writer runs on whichever thread delivers to `inlet` or
     * [deltaInlet], while [membership], [snapshot] and [readBounded] are
     * *host*-facing reads a caller makes from its own thread. Unguarded, those
     * accessors iterate the shared maps (and, in [readBounded]/[snapshot], the
     * per-element tag sets) and escape a
     * [java.util.ConcurrentModificationException] into the caller — the same
     * escape observed on CI out of `OrMapCell.membership`, reachable here
     * through `MirrorProjector.edgeView`, which reads a `SetCell`'s
     * [membership] from an `awaitUntil` thread while the beads mirror's poller
     * writes (computenet-bdth).
     *
     * **The monitor is never held across an outbound call**, and this cell has
     * two kinds where `OrMapCell` has one:
     *
     * - `add`/`remove` fold under it and propagate after; [applyRemote] folds
     *   under it and originates after; the pull reply is assembled under it
     *   and `baselineTo`-shipped after.
     * - **Delivery listeners are foreign code and fire outside it.** A
     *   registered listener is `WatermarkCell`'s `companion.advance(...)`
     *   (`Replication.kt`) — a *cell call*, not a callback into pure state. So
     *   the fold into [delivered] ([foldDelivered]) happens under the monitor
     *   and the notification ([notifyDelivered]) strictly after it is
     *   released. Holding the monitor across that call is exactly how a
     *   cross-cell lock cycle would form here, and it is the one place this
     *   cell's shape differs from `OrMapCell`'s.
     *
     * The only foreign code that can run under the monitor is an element's own
     * `hashCode`/`equals` (unavoidable — the elements are map keys) and an
     * [civictech.cell.link.Interest] predicate, both pure by contract and
     * neither reaching a cell, port or link.
     *
     * **What it costs.** Reads serialize against the single writer: a host
     * polling [membership] or [readBounded] over a large set delays the next
     * write by that scan. Nothing downstream is blocked, per the paragraph
     * above.
     *
     * **Why not the cheaper options.** Copying on read without a guard does
     * not help — the copy is itself an iteration and throws the same CME.
     * Concurrent maps would replace the maps' insertion-order iteration with
     * hash order (which [SetWalk] explicitly freezes an order against) and
     * would still let one accessor tear an `adds` read against a `dels` read.
     */
    private val stateLock = Any()

    // Tags are minted locally, not taken from the wave's MessageContext:
    // observed-remove correctness needs a tag unique per add *instance*, and a
    // wave timestamp repeats across every cell the wave touches (22).
    // Replay-stable identity (M10.1): the source is DERIVED from the ref, so a
    // recovered instance replaying its journal re-mints the exact tags the
    // network already observed — random sources would resurrect removed
    // elements (a pre-crash remove can't cover a re-minted add). Uniqueness
    // across instances rides instanceId uniqueness (the replication contract).
    private val tagSource: UUID =
        UUID.nameUUIDFromBytes("set-tags:${ref.id}:${ref.instanceId}".toByteArray())
    private var tagCounter = 0L

    // Per-origin delivered frontier (spec 40/42 §Delivered watermarks, E3.3(a)):
    // add-tags this replica has durably absorbed, tracked as a max-contiguous
    // prefix per ORIGIN source (the tag's minting source, still visible here in
    // the fold). Listeners — the replica's WatermarkCell companion — advance on
    // each raised prefix, so the merged lattice answers "which origin waves has
    // the replica set delivered" (E3.4), not "how many did each replica re-emit".
    private val delivered = DeliveredFrontier()
    private val deliveryListeners = mutableListOf<(UUID, Long) -> Unit>()

    override fun onDeliver(listener: (source: UUID, thru: Long) -> Unit) = synchronized(stateLock) {
        deliveryListeners += listener
        Unit
    }

    /**
     * Fold [tags] into the delivered frontier and return each raised per-origin
     * prefix. Call under [stateLock]; hand the result to [notifyDelivered]
     * *after* releasing it.
     */
    private fun foldDelivered(tags: Iterable<Timestamp>): Map<UUID, Long> {
        if (deliveryListeners.isEmpty()) return emptyMap()
        val advanced = HashMap<UUID, Long>()
        for (tag in tags) delivered.deliver(tag.sourceId, tag.counter)?.let { advanced[tag.sourceId] = it }
        return advanced
    }

    /**
     * Notify listeners of each raised per-origin prefix. **Never called under
     * [stateLock]**: a listener is another cell's call (see [stateLock]).
     */
    private fun notifyDelivered(advanced: Map<UUID, Long>) {
        if (advanced.isEmpty()) return
        val listeners = synchronized(stateLock) { deliveryListeners.toList() }
        for ((source, thru) in advanced) listeners.forEach { it(source, thru) }
    }

    private fun liveTags(element: E): Set<Timestamp> = synchronized(stateLock) {
        (adds[element] ?: emptySet<Timestamp>()) - (dels[element] ?: emptySet())
    }

    /** Current membership: elements with at least one un-tombstoned add-tag. */
    fun membership(): Set<E> = synchronized(stateLock) {
        adds.keys.filterTo(mutableSetOf()) { liveTags(it).isNotEmpty() }
    }

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): SetOps<E> = object : SetOps<E> {
        override fun add(element: E) {
            // the fold happens under `stateLock`; the listener notification and
            // the propagation after it, never under (see stateLock's KDoc).
            val (tag, advanced) = synchronized(stateLock) {
                val minted = Timestamp(tagSource, ++tagCounter)
                adds.getOrPut(element) { mutableSetOf() } += minted
                minted to foldDelivered(listOf(minted)) // a local mint is trivially contiguous
            }
            notifyDelivered(advanced)
            outlet.call.propagate(SetDelta(adds = mapOf(element to setOf(tag))))
        }

        override fun remove(element: E) {
            val observed = synchronized(stateLock) {
                // effective-only (21): removing an unobserved element is a no-op
                val seen = liveTags(element)
                if (seen.isEmpty()) return
                dels.getOrPut(element) { mutableSetOf() } += seen
                seen
            }
            outlet.call.propagate(SetDelta(dels = mapOf(element to observed)))
        }
    }

    /** Merge a peer replica's delta; re-emit exactly the new tag information. */
    private fun applyRemote(delta: SetDelta<E>) {
        // one atomic fold: the novelty computation and its absorption must not
        // straddle another writer, and no outbound call happens under the
        // monitor — neither the listener notification nor the re-emission.
        val (effective, advanced) = synchronized(stateLock) {
            val newAdds = delta.adds
                .mapValues { (e, tags) -> tags - (adds[e] ?: emptySet()) }
                .filterValues { it.isNotEmpty() }
            val newDels = delta.dels
                .mapValues { (e, tags) -> tags - (dels[e] ?: emptySet()) }
                .filterValues { it.isNotEmpty() }
            if (newAdds.isEmpty() && newDels.isEmpty()) return // echo terminates here
            newAdds.forEach { (e, tags) -> adds.getOrPut(e) { mutableSetOf() } += tags }
            newDels.forEach { (e, tags) -> dels.getOrPut(e) { mutableSetOf() } += tags }
            // advance the per-origin delivered frontier before re-emitting: membership
            // now reflects these tags, so a peer reading the watermark that this
            // advance gossips will also see the element live here (E3.3(a)/E3.4).
            SetDelta(newAdds, newDels) to foldDelivered(newAdds.values.flatten())
        }
        notifyDelivered(advanced)
        outlet.originate { propagate(effective) }
    }

    /**
     * Discard tags at or below [frontier], per source, and nothing else
     * (decision 9sm.4-D1/D2; the epic's §2 table names `TagState.compactBelow`
     * as the eventual OR-map home — this is the `SetCell` half only). The
     * minimal safe discard: for each element `e`, `D = dels[e] ∩ { t : t ≤
     * frontier }` is removed from both `dels[e]` and `adds[e]` (a del-tag IS
     * the add-tag it covers — same [Timestamp] — so "add-tags ≤ frontier that
     * a del-tag covered" is exactly `adds[e] ∩ D`), and an element key whose
     * set became empty is dropped from that map. A LIVE add-tag (present in
     * `adds`, absent from `dels`) is never a member of any `D` and is never
     * touched, even when it is ≤ frontier — so [membership] is unchanged by
     * construction: `D ⊆ dels[e]` is removed from both sides, never from one.
     * A tombstone with no matching add (`dels` holds a key `adds` lacks — the
     * remote-tombstone-before-add case [openWalk]'s KDoc names) is discarded
     * like any other.
     *
     * The `[KE3-30]` interlock / `[42-WM-05]` absent-row-is-bottom: a tag
     * source with no entry in [frontier] reads as bottom, so nothing of that
     * source is ever discarded.
     *
     * **This records nothing.** No floor, no fence: [delivered], [tagCounter]
     * and [deliveryListeners] are untouched, nothing is emitted, and a later
     * delta carrying a discarded tag is re-admitted as new information by
     * [applyRemote] (novelty there is `tags − adds[e]`, and a discarded tag is
     * absent from `adds[e]` again). **That re-admission is deliberate and
     * load-bearing** — it is what lets feature computenet-9sm.4's BS-13
     * control resurrect an element — **and it is why feature computenet-9sm.6
     * must add the re-admission fence (`[24-TAG-04]`'s second clause) before
     * anything wires this seam to a checkpoint.** No checkpoint wiring, no
     * floor/fence, no snapshot persistence, no `StateRequest(since)` fallback,
     * no `OrMapCell`/`TagState` reclamation belong here; those are out of
     * scope for this task.
     *
     * `internal`: reachable from `:kernel` tests only. `:testkit` must not see
     * this — it is a harness seam, not a public capability.
     *
     * Runs entirely under [stateLock] and makes no outbound call.
     *
     * @return the total number of tags discarded (from `dels` plus the
     *   matching tags also removed from `adds`).
     */
    internal fun compactBelow(frontier: TagFrontier): Int = synchronized(stateLock) {
        var discarded = 0
        val emptiedDels = mutableListOf<E>()
        for ((element, delTags) in dels) {
            val covered = delTags.filterTo(mutableSetOf()) { tag ->
                (frontier.perSource[tag.sourceId] ?: Long.MIN_VALUE) >= tag.counter
            }
            if (covered.isEmpty()) continue
            delTags -= covered
            discarded += covered.size
            adds[element]?.let { addTags ->
                val addCovered = addTags.intersect(covered)
                if (addCovered.isNotEmpty()) {
                    addTags -= addCovered
                    discarded += addCovered.size
                    if (addTags.isEmpty()) adds.remove(element)
                }
            }
            if (delTags.isEmpty()) emptiedDels += element
        }
        emptiedDels.forEach { dels.remove(it) }
        discarded
    }

    /**
     * Highest tag counter observed per tag source, restricted to the keys
     * [scope] admits (spec 20/21 §Pull, 93 I-24; PN-3c). `null`/[Interest.Total]
     * scope iterates every key — byte-identical to the pre-scope frontier — so a
     * scope-absent pull's reported currency is unchanged.
     */
    private fun currentFrontier(scope: civictech.cell.link.Interest? = null): TagFrontier = synchronized(stateLock) {
        val admit: (E) -> Boolean =
            if (scope == null || scope is civictech.cell.link.Interest.Total) { _ -> true }
            else { e -> scope.admits(e) }
        val frontier = mutableMapOf<UUID, Long>()
        val addSeq = adds.asSequence().filter { admit(it.key) }.map { it.value }
        val delSeq = dels.asSequence().filter { admit(it.key) }.map { it.value }
        (addSeq + delSeq).flatten().forEach { tag ->
            frontier.merge(tag.sourceId, tag.counter, ::maxOf)
        }
        TagFrontier(frontier)
    }

    /**
     * Restrict a since-filtered output map to the keys [scope] admits (PN-3c):
     * the per-element interest filter a partial-interest pull applies. Returns
     * the same map unchanged for `null`/[Interest.Total] scope — the scope-absent
     * reply is verbatim.
     */
    private fun scopedTo(
        source: Map<E, Set<Timestamp>>,
        scope: civictech.cell.link.Interest?,
    ): Map<E, Set<Timestamp>> =
        if (scope == null || scope is civictech.cell.link.Interest.Total) source
        else source.filterKeys { scope.admits(it) }

    /** Only the tags a [since] frontier has not yet observed; unfiltered when [since] is null. */
    private fun sinceFilter(
        source: Map<E, MutableSet<Timestamp>>,
        since: TagFrontier?,
    ): Map<E, Set<Timestamp>> = synchronized(stateLock) {
        source.mapValues { (_, tags) ->
            if (since == null) tags.toSet()
            else tags.filterTo(mutableSetOf()) { (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }
    }

    init {
        deltaInlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) = applyRemote(value)
        })
        // late-join catch-up (G-22) — and replica initial sync / anti-entropy
        // (M7.4): full tag state as one delta-from-empty, tombstones included,
        // to just the new subscriber; idempotence makes replays harmless
        outlet.catchUpOnLinked {
            synchronized(stateLock) {
                if (adds.isEmpty() && dels.isEmpty()) null
                else SetDelta(
                    adds = adds.mapValues { it.value.toSet() },
                    dels = dels.mapValues { it.value.toSet() },
                )
            }
        }
        // on-demand pull (spec 20/21 §Pull, G-18 residual, decided in 93
        // I-16/I-24): a single-wave state-as-delta reply, stamped as a catch-
        // up baseline (MessageContext.baseline) and delivered only to the
        // requester — never broadcast, never admitted to wave completeness.
        // PN-9: pull-serve is now an installable outlet policy (extracted from the
        // hand-rolled handler this cell carried) — it composes with catchUpOnLinked
        // rather than living as a one-off StateRequest handler.
        outlet.pullServe { request ->
            // scope filter (PN-3c): restrict the reply to the requester's
            // interest slice. scope absent/Total ⇒ the maps and the reported
            // frontier are the pre-scope values, so the reply is verbatim.
            // the three halves of a reply are one snapshot: taken together
            // under the monitor, shipped after it is released.
            val reply = synchronized(stateLock) {
                val addsOut = scopedTo(sinceFilter(adds, request.since), request.scope)
                val delsOut = scopedTo(sinceFilter(dels, request.since), request.scope)
                if (addsOut.isEmpty() && delsOut.isEmpty()) null
                else Triple(addsOut, delsOut, currentFrontier(request.scope))
            } ?: return@pullServe
            baselineTo(request.replyTo, reply.third) {
                propagate(SetDelta(reply.first, reply.second))
            }
        }
    }

    // snapshot/restore (G-25 seam): elements must be Serializable. The tag
    // counter is state too (M10.2): a checkpoint-restored instance must not
    // re-mint tags it already used — journal-tail replay continues the count.
    override fun snapshot(): Serializable = synchronized(stateLock) {
        HashMap(
            mapOf(
                "adds" to HashMap(adds.mapValues { HashSet(it.value) }),
                "dels" to HashMap(dels.mapValues { HashSet(it.value) }),
                "counter" to tagCounter,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) = synchronized(stateLock) {
        val maps = state as Map<String, Any>
        adds.clear()
        dels.clear()
        (maps.getValue("adds") as Map<E, Set<Timestamp>>).forEach { (e, tags) -> adds[e] = tags.toMutableSet() }
        (maps.getValue("dels") as Map<E, Set<Timestamp>>).forEach { (e, tags) -> dels[e] = tags.toMutableSet() }
        tagCounter = maps["counter"] as? Long ?: 0L
        Unit
    }

    // ---------------------------------------------------------------------
    // Bounded read (V1C-KERNEL) — the reference [BoundedStateful]
    // implementation the rest of the data-cell family copies. Purely additive:
    // nothing above this line changed, and `snapshot()`/`restore()` behave
    // exactly as they did, because drain, migration, promotion state transfer
    // and durability checkpoints all depend on that seam being untouched.
    // ---------------------------------------------------------------------

    /** One element's OR-set state — a whole entry, never split across pages (V1C-KERNEL). */
    data class SetStateEntry<E>(
        val element: E,
        val addTags: Set<Timestamp>,
        val delTags: Set<Timestamp>,
    ) : Serializable {
        /** Is the element currently a member — at least one un-tombstoned add-tag? */
        val present: Boolean get() = addTags.any { it !in delTags }
    }

    /**
     * `SetCell`'s cursor token (V1C-KERNEL) — opaque to the kernel, and the
     * encoding `V1C-CELLS`/`V1C-OPS` copy.
     *
     * **[order] is the walk's enumeration order, frozen at walk start.** The
     * two tag maps are `LinkedHashMap`s, so their live iteration order is not
     * stable — a remove-then-re-add moves a key to the tail (which could hand
     * one key to a walk twice) and [restore] rebuilds both from a `HashMap`
     * (which reorders wholesale). Freezing the sequence is how this cell
     * discharges [BoundedStateful]'s "impose an order" obligation.
     *
     * **Key-based, and O(page) to resume.** [next] indexes a list of *keys*
     * that no longer changes, not a position in live state: a removal earlier
     * in the enumeration shifts nothing, and a key that disappears entirely
     * (only [restore] can do that here — the OR-set's own `remove` tombstones
     * rather than deletes) is simply skipped when the walk reaches it. Resuming
     * costs one array index plus one map lookup per entry, so a walk's total
     * work is O(n) rather than the O(n²) a rescan-from-the-start cursor would
     * cost — the shape the C7 measurement gate ruled out, because the ~1.7–2.4×
     * paging premium it accepted was measured against an O(1) seek.
     *
     * The price is one O(n) pass over the tag maps at walk start, which also
     * computes [opening]; it copies key *references* only, never the tag sets,
     * so it is a small fraction of what one `snapshot()` costs.
     */
    private class SetWalk<E>(
        val order: List<E>,
        val next: Int,
        val opening: TagFrontier,
    ) : Serializable

    /** This cell carries a per-source-monotone tag clock, so `since` is honoured exactly. */
    override val supportsSince: Boolean get() = true

    /** Interest filtering is per element, the same predicate a scoped pull applies. */
    override val supportsScope: Boolean get() = true

    /**
     * One page of this set's OR-set state (V1C-KERNEL).
     *
     * Per page: at most [StateRead.limit] keys are examined and at most
     * [StateRead.limit] whole [SetStateEntry] entries are returned, so the work
     * is O(limit) — never a rescan of the tag maps. Keys skipped by
     * [StateRead.since] or [StateRead.scope] are consumed from the frozen order
     * and never revisited, so a heavily filtered walk yields short (possibly
     * empty) pages rather than long ones; only `next == null` ends a walk.
     *
     * **Frontier.** Exact on the first page (computed in the same pass that
     * freezes the enumeration order) and exact on the last (recomputed as the
     * walk closes). An intermediate page carries the opening frontier and says
     * so with [ReadCaveat.STALE_FRONTIER]: recomputing it per page means
     * rescanning every tag on every page — O(n²) over a walk, the exact cost
     * this design exists to avoid — and maintaining it incrementally would put
     * a secondary index on the fold path, which P2 forbids. Because a
     * [TagFrontier] is monotone, comparing the first page's stamp with the
     * last's is a complete check of whether the fold gained any tag during the
     * walk, which is what [StatePage]'s stability contract asks of a caller.
     *
     * **What that check does not catch, stated because it is this family's
     * limit and not the paging design's.** An observed-remove mints no tag: it
     * copies the add-tags it already holds into `dels` (effective-only removal,
     * 21), so `currentFrontier` — a max over `adds ∪ dels` — is unchanged by
     * it. A removal applied mid-walk to an element the walk has already paged
     * therefore leaves the opening and closing stamps equal while the union
     * still names that element present. Equal endpoint stamps are consequently
     * *necessary but not sufficient* for "the union is a snapshot" here, and
     * the same holds for the `since` escalation path, which filters the
     * tombstone's re-used tags out along with the adds they cover. This is a
     * pre-existing property of the family's tag algebra — the pull reply at
     * [currentFrontier]'s other call site has always reported currency the same
     * way — not something the bounded read introduced, and it is filed as
     * research rather than papered over here.
     *
     * **Ownership.** An element that is itself an `Owned`/`Leased` payload is
     * never copied into a page: it is replaced by an [ExclusiveEntry]
     * descriptor and counted in [StatePage.exclusivesElided]. Nothing is taken,
     * borrowed, released or unwrapped.
     *
     * [StatePage.attributes] carries `counter` — the tag-minting counter, which
     * is cell-level state rather than an entry, and rides every page so that a
     * caller joining a walk mid-way still sees it. With it, the union of a
     * walk's pages is exactly [snapshot]'s content.
     */
    override fun readBounded(request: StateRead): StatePage = synchronized(stateLock) {
        // one page is assembled under the monitor: it walks the frozen order but
        // reads the LIVE tag maps and the live per-element tag sets, so it races
        // the fold exactly as [membership] does. There is no outbound call in
        // here, so the monitor is only ever held across pure map work.
        val scope = request.scope
        @Suppress("UNCHECKED_CAST")
        val walk = (request.cursor?.token as? SetWalk<E>) ?: openWalk(scope)
        val order = walk.order

        val entries = ArrayList<Serializable>(minOf(request.limit, 64))
        var elided = 0
        var bytes = 0
        var index = walk.next
        val examineThrough = minOf(index + request.limit, order.size)
        while (index < examineThrough) {
            val element = order[index]
            index++
            val liveAdds = adds[element]
            val liveDels = dels[element]
            if (liveAdds == null && liveDels == null) continue // vanished since the walk opened
            if (ExclusiveEntry.isExclusive(element)) {
                // the element IS the exclusive value here, so there is no
                // separate key to report — see ExclusiveEntry.key
                entries += ExclusiveEntry.of(key = null, exclusive = element as Any)
                elided++
                bytes += EXCLUSIVE_ENTRY_BYTES
            } else {
                val addTags = tagsBeyond(liveAdds, request.since)
                val delTags = tagsBeyond(liveDels, request.since)
                if (addTags.isEmpty() && delTags.isEmpty()) continue // nothing beyond `since`
                entries += SetStateEntry(element, addTags, delTags)
                bytes += ENTRY_OVERHEAD_BYTES + TAG_BYTES * (addTags.size + delTags.size)
            }
            // advisory (StateRead.byteBudget): honoured only once the page
            // already carries an entry, so a walk always makes progress
            if (bytes >= request.byteBudget) break
        }

        val complete = index >= order.size
        val opening = walk.next == 0
        StatePage(
            entries = entries,
            next = if (complete) null else Cursor(SetWalk(order, index, walk.opening)),
            // exact at both ends of the walk; the opening stamp was computed in
            // this same invocation when this is the first page
            frontier = if (complete && !opening) currentFrontier(scope) else walk.opening,
            exclusivesElided = elided,
            attributes = mapOf("counter" to java.lang.Long.valueOf(tagCounter)),
            caveats = if (complete || opening) emptySet() else setOf(ReadCaveat.STALE_FRONTIER),
        )
    }

    /**
     * The walk's one O(n) pass (V1C-KERNEL): freeze the enumeration order and
     * compute the opening frontier together, so a walk pays for a full traversal
     * of the tag maps twice (here and at close) rather than once per page.
     *
     * `dels` may hold a key `adds` does not — a remote tombstone for an element
     * whose add never arrived — so both maps contribute keys, deduplicated
     * against `adds` rather than through a second hash set.
     */
    private fun openWalk(scope: civictech.cell.link.Interest?): SetWalk<E> = synchronized(stateLock) {
        val admit: (E) -> Boolean =
            if (scope == null || scope is civictech.cell.link.Interest.Total) { _ -> true }
            else { e -> scope.admits(e) }
        val order = ArrayList<E>(adds.size + dels.size)
        val frontier = HashMap<UUID, Long>()
        for ((element, tags) in adds) {
            if (!admit(element)) continue
            order += element
            for (tag in tags) frontier.merge(tag.sourceId, tag.counter, ::maxOf)
        }
        for ((element, tags) in dels) {
            if (!admit(element)) continue
            if (!adds.containsKey(element)) order += element
            for (tag in tags) frontier.merge(tag.sourceId, tag.counter, ::maxOf)
        }
        SetWalk(order, 0, TagFrontier(frontier))
    }

    /**
     * A page-owned copy of the tags [since] has not yet observed (V1C-KERNEL) —
     * a copy, never an alias of the fold's own mutable set, so a page can never
     * be mutated under its reader.
     *
     * [tags] may be the fold's live set, so this **must** be called under
     * [stateLock]; its only call site ([readBounded]) holds it.
     */
    private fun tagsBeyond(tags: Set<Timestamp>?, since: TagFrontier?): Set<Timestamp> = when {
        tags.isNullOrEmpty() -> emptySet()
        since == null -> HashSet(tags)
        else -> tags.filterTo(HashSet()) { (since.perSource[it.sourceId] ?: -1L) < it.counter }
    }

    companion object {
        fun <E> create(): SetApi<E> = SetCell()

        // Crude, deliberately: StateRead.byteBudget is advisory and
        // cell-estimated, and an estimate a cell cannot make it is free to
        // ignore. These are rough JVM object sizes for one entry and one
        // Timestamp, not an encoder's measurement.
        private const val ENTRY_OVERHEAD_BYTES = 64
        private const val TAG_BYTES = 48
        private const val EXCLUSIVE_ENTRY_BYTES = 64
    }
}
