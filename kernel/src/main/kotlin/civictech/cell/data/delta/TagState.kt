package civictech.cell.data.delta

import civictech.cell.ReBaselineNotice
import civictech.cell.Timestamp
import java.io.Serializable
import java.util.UUID

/**
 * The shared state shape of tag-consuming cells (G-23): live add-tags per
 * element. [apply] folds a delta in and returns only the *new* tag
 * information, so duplicate deliveries across diamond fan-ins dedup.
 *
 * ponytail: no del tombstones — per-link FIFO means a tag's add precedes its
 * del on every stream, so a dropped del of an unseen tag recurs on the stream
 * that carries the add; diamond fan-ins may flicker transiently but converge
 * at idle. [retainTombstones] is the opt-out that argument does not cover
 * (D-UNION) — see its own doc.
 */
internal class TagState<E>(
    /**
     * Keep folded del-tags instead of discarding them (D-UNION). The default
     * `false` is the ledger described above — correct exactly while every del
     * a fold sees arrives on the same FIFO stream as the add it covers.
     *
     * A **union-scoped observed remove** ([removeObserved]) breaks that
     * premise: the del is minted at the merge point, while the add-tags it
     * covers live on in the originating writers' own `SetCell` state, and
     * those writers re-assert their full tag state on every late-join
     * catch-up and every anti-entropy replay — on a *different* stream than
     * the one that carried the del. Without retention the removed element
     * resurrects on the next catch-up. A retaining ledger is therefore a full
     * OR-set, exactly as `SetCell` is: an add-tag already covered by a
     * tombstone is inadmissible however it arrives, and the tombstones ride
     * this cell's own [asDelta] catch-up so a peer that missed the del still
     * converges.
     *
     * ponytail: tombstones grow monotonically, like `SetCell`'s — compaction
     * stays G-25.
     */
    private val retainTombstones: Boolean = false,
) {
    private val live = mutableMapOf<E, MutableSet<Timestamp>>()

    /** Tags covered by a folded del; populated only when [retainTombstones]. */
    private val tombstones = mutableMapOf<E, MutableSet<Timestamp>>()

    /**
     * Fenced source ids (spec 20/24 §Tag continuity, 93 I-22 R5c): every
     * source superseded by a `ReBaseline` this fold has processed. A tag
     * stamped by a dead source is rejected from then on — a stale
     * pre-restart delta arriving late via a longer fan-out path.
     * ponytail: unbounded — epoch-hygiene reclamation is research-gated
     * (G-42), implement unbounded-but-correct first.
     */
    private val deadSources = mutableSetOf<UUID>()

    val elements: Set<E> get() = live.keys
    val size: Int get() = live.size

    /** Nothing to replay: no live tags and (under [retainTombstones]) no tombstones either. */
    val isEmpty: Boolean get() = live.isEmpty() && tombstones.isEmpty()
    operator fun contains(element: E): Boolean = element in live
    fun tags(element: E): Set<Timestamp> = live[element]?.toSet() ?: emptySet()

    private fun tombstoned(element: E): Set<Timestamp> = tombstones[element] ?: emptySet()

    /**
     * Current state as a delta-from-empty — the catch-up emission (G-22).
     * Retained tombstones ride along ([retainTombstones]), the way
     * `SetCell`'s catch-up carries its `dels`: a subscriber that learned an
     * add from another path must learn its del from this one too. Without
     * retention `dels` is empty and this is the pre-D-UNION emission verbatim.
     */
    fun asDelta(): SetDelta<E> = SetDelta(
        adds = live.mapValues { it.value.toSet() },
        dels = tombstones.mapValues { it.value.toSet() },
    )

    /**
     * Union-scoped observed remove (D-UNION, spec 24 §Established pattern
     * `[24-SET-01]`/`[24-SET-03]`): tombstone every tag [element] is *currently
     * observed* to hold in this merged ledger, whichever writer minted it, and
     * return the covering delta. Effective-only: an unobserved element is a
     * no-op (empty delta).
     *
     * The add-wins boundary is intended, not a defect: a concurrent add whose
     * tag this ledger has not yet folded is not covered, so the element
     * re-enters when that tag arrives — `[24-SET-03]`, "a remove SHALL only
     * retract the tags it observed". Nothing here coordinates with other
     * nodes, and nothing should.
     */
    fun removeObserved(element: E): SetDelta<E> {
        val observed = live[element]?.toSet() ?: return SetDelta()
        if (observed.isEmpty()) return SetDelta()
        live.remove(element)
        if (retainTombstones) tombstones.getOrPut(element) { mutableSetOf() } += observed
        return SetDelta(dels = mapOf(element to observed))
    }

    /**
     * The del-fold shared by [apply] and (b)'s del pass in [applyReBaseline]
     * (T07 finding 4): identical tombstone logic — which tags counted as
     * "killed", whether an element's live-tag entry empties out — the ONE
     * declared difference being whether a killed tag REPLACES or ACCUMULATES
     * onto [into] ([accumulateKilled]). `applyReBaseline` needs accumulation
     * because its (a) pass may already have recorded a drop for the same
     * element before this (b) pass runs; `apply` never has a prior entry, so
     * replace and accumulate are byte-identical there. The documented
     * add-path divergence between the two callers is untouched — only the
     * del-fold is shared.
     */
    private fun foldDels(delta: SetDelta<E>, into: MutableMap<E, Set<Timestamp>>, accumulateKilled: Boolean) {
        delta.dels.forEach { (element, tags) ->
            // D-UNION: retain BEFORE the liveness check — a del whose add this
            // ledger has not seen yet (another path, a later catch-up) is
            // exactly the one retention exists to remember.
            if (retainTombstones && tags.isNotEmpty()) tombstones.getOrPut(element) { mutableSetOf() } += tags
            val had = live[element] ?: return@forEach
            val killed = tags intersect had
            if (killed.isNotEmpty()) {
                had -= killed
                if (had.isEmpty()) live.remove(element)
                into[element] = if (accumulateKilled) (into[element] ?: emptySet()) + killed else killed
            }
        }
    }

    fun apply(delta: SetDelta<E>): SetDelta<E> {
        val newAdds = mutableMapOf<E, Set<Timestamp>>()
        val newDels = mutableMapOf<E, Set<Timestamp>>()

        delta.adds.forEach { (element, tags) ->
            // dead-lane fence (93 I-22 R5c): a tag stamped by a superseded source never re-enters
            // tombstone fence (D-UNION): nor does a tag a retained del already covers
            val admissible = tags.filterNot { it.sourceId in deadSources || it in tombstoned(element) }.toSet()
            val fresh = admissible - (live[element] ?: emptySet())
            if (fresh.isNotEmpty()) {
                live.getOrPut(element) { mutableSetOf() } += fresh
                newAdds[element] = fresh
            }
        }
        foldDels(delta, newDels, accumulateKilled = false)
        return SetDelta(newAdds, newDels)
    }

    /**
     * The convergent-consumer half of a RESTART re-baseline (spec 20/24
     * §Tag continuity, 93 I-22 R5): on `ReBaselineNotice(supersedes,
     * supersede = true)`, (a) drop every live tag from the listed
     * superseded sources this delta does not re-assert, (b) apply [delta] by
     * ordinary tag-union merge, (c) fence the superseded sources as dead
     * lanes. `supersede = false` (pull-merge) retracts nothing — forward
     * idempotent merge only.
     */
    fun applyReBaseline(delta: SetDelta<E>, notice: ReBaselineNotice): SetDelta<E> {
        if (!notice.supersede) return apply(delta)

        val newDels = mutableMapOf<E, Set<Timestamp>>()
        live.keys.toList().forEach { element ->
            val current = live.getValue(element)
            val reasserted = delta.adds[element] ?: emptySet()
            val dropped = current.filter { it.sourceId in notice.supersedes && it !in reasserted }.toSet()
            if (dropped.isNotEmpty()) {
                current -= dropped
                if (current.isEmpty()) live.remove(element)
                newDels[element] = dropped
            }
        }

        // (b) union-merge the reasserted/fresh state — not through apply()'s
        // dead-lane filter, since the reassertion legitimately carries tags
        // from the sources being fenced by this very call (c, below)
        val newAdds = mutableMapOf<E, Set<Timestamp>>()
        delta.adds.forEach { (element, tags) ->
            // the tombstone fence still applies (D-UNION): a re-baseline is a
            // re-assertion of the producer's own state, which never learned of
            // a union-scoped del minted downstream of it
            val fresh = tags - (live[element] ?: emptySet()) - tombstoned(element)
            if (fresh.isNotEmpty()) {
                live.getOrPut(element) { mutableSetOf() } += fresh
                newAdds[element] = fresh
            }
        }
        foldDels(delta, newDels, accumulateKilled = true)

        // (c) fence the superseded sources — future ordinary deltas from them are rejected
        deadSources += notice.supersedes

        return SetDelta(newAdds, newDels)
    }

    /**
     * The live ledger as a plain map — the shape every consumer of a
     * `Stateful` set-operator snapshot already reads. With retained
     * tombstones to carry (D-UNION) it becomes the two-map list instead, so
     * a checkpoint-restored merge point keeps its tombstones and a
     * re-delivered writer catch-up still cannot resurrect a removed element.
     * Additive: the single-map form is emitted verbatim whenever there are no
     * tombstones, and [restore] accepts both.
     */
    fun snapshot(): Serializable {
        val liveOut = HashMap(live.mapValues { HashSet(it.value) })
        if (tombstones.isEmpty()) return liveOut
        return arrayListOf<Serializable>(liveOut, HashMap(tombstones.mapValues { HashSet(it.value) }))
    }

    fun restore(state: Serializable) {
        live.clear()
        tombstones.clear()
        if (state is List<*>) {
            restoreInto(live, state[0])
            restoreInto(tombstones, state.getOrNull(1))
        } else {
            restoreInto(live, state)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreInto(target: MutableMap<E, MutableSet<Timestamp>>, saved: Any?) {
        (saved as? Map<E, Set<Timestamp>> ?: return).forEach { (e, tags) -> target[e] = tags.toMutableSet() }
    }
}
