package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

/**
 * The tagged map's port surface (spec 20/24 §Tagged maps, 96 §E1.2). The
 * inlet reuses the **existing** [MapOps] `@Contract` verbatim — the tagged map
 * is a new convergence semantics for the same keyed-write vocabulary, not a
 * new vocabulary — so there is no new contract interface and no `gen/`
 * descriptor work. Only the outlet payload differs from [MapApi]: a
 * [TaggedMapDelta] instead of an untagged
 * [civictech.cell.data.delta.MapDelta].
 */
@CellBase
interface OrMapApi<K, V> {
    val inlet: Use<MapOps<K, V>>
    val outlet: Subscribe<Propagate<TaggedMapDelta<K, V>>>
}

/**
 * An **OR-map**: the keyed structure whose per-key *value* converges under
 * concurrent multi-writer puts and removes (G-23 for keyed structures; spec
 * 20/24 §Tagged maps, 96 §E1.2). Where [MapCell]'s untagged [MapDelta]
 * resolves concurrent same-key puts by arrival order — fine inside one FIFO
 * stream, not replica-stable — this cell mints a **dot** per put and lets the
 * dot algebra decide, so every observer of the same dot set agrees.
 *
 * It is [SetCell]'s observed-remove idiom lifted one level (live/tombstoned
 * *dot per key* rather than live/tombstoned *tag per element*), with
 * [KeyedSetCell]'s atomic retract-then-add lifted with it.
 *
 * **The four laws** ([TaggedMapDelta] carries their merge/read side):
 *
 * - `[24-TMAP-01]` merge is pointwise dot union — commutative, associative,
 *   idempotent.
 * - `[24-TMAP-02]` [membership] is add-wins: a key is present iff it has at
 *   least one live dot.
 * - `[24-TMAP-03]` [value] is the value of the live dot with the greatest
 *   `(counter, sourceId)` order. **No wall clock participates**, here or in
 *   the delta.
 * - `[24-TMAP-04]` [MapOps.remove] is reset-remove: it tombstones exactly the
 *   dots it observed live at the key, so a concurrent put's dot — which this
 *   remove never observed — survives the merge as the key's remaining value.
 *
 * **Re-put atomicity.** A [MapOps.put] over an existing key ships the previous
 * dots' tombstones and the new dot in ONE [TaggedMapDelta], so a downstream
 * fold never observes two live values for the key, nor a windowed zero — the
 * [KeyedSetCell] invariant, lifted to dots. A put always mints, even when the
 * value is unchanged: the fresh dot is the evidence that wins a later
 * `[24-TMAP-03]` comparison, so short-circuiting an equal-value re-put (as
 * [KeyedSetCell] does for an identical element) would silently drop a
 * last-writer-wins claim.
 *
 * **Determinism caveat (spec 20/24 §Tagged maps, decided point 5 — normative
 * for adopters).** State convergence alone does not make *value-keyed*
 * derivation deterministic: whether a concurrent remove cancels a concurrent
 * put can depend on the merge schedule for an operator that reads a value
 * rather than mere presence. What keeps derivation deterministic here is that
 * removes are **tag-precise** — a remove carries exactly the dots it observed,
 * never a value-level predicate. An operator deriving from [value] inherits
 * this caveat and must not assume a wall-clock or arrival-order resolution.
 *
 * **Single-instance until E1-REPL (96 §E1.3).** This cell deliberately has no
 * replication surface: no `Replicable`, no `deltaInlet`, no `applyRemote`, no
 * pull/`StateRequest` handler, no re-origination, no `ReBaseline` fencing.
 * The state layout below (`puts`/`dels`/`dotCounter`) is what E1-REPL's
 * `applyRemote` will merge remote dots into; the dot source is already
 * replay-stable so that seam needs no change here. Until then, the only
 * writer of these dots is this instance's own [MapOps] inlet, and convergence
 * is exercised by merging its delta stream in any order.
 *
 * Embedded mergeable values (96 §E1.4), `TaggedMapView`/`UntagCell` adapters
 * (§E1.5) and multi-value reads are likewise not here.
 */
class OrMapCell<K, V>(ref: CellRef = CellRef(UUID.randomUUID())) : OrMapCellBase<K, V>(ref), Stateful {

    // One causal namespace for the whole map (decided point 1) — dots are NOT
    // partitioned per key, because a per-key context re-admits stale values on
    // key re-creation. `puts` holds every dot ever minted here with the value
    // that put wrote; `dels` holds the dots a remove observed live and covered.
    // A key's live dots are `puts[key]` minus `dels[key]`.
    // ponytail: dot sets grow monotonically; compaction is future work (G-25),
    // exactly as for SetCell's tag sets.
    private val puts = mutableMapOf<K, MutableMap<Timestamp, V>>()
    private val dels = mutableMapOf<K, MutableSet<Timestamp>>()

    // Dots are minted locally, not taken from the wave's MessageContext:
    // observed-remove correctness needs a dot unique per put *instance*, and a
    // wave timestamp repeats across every cell the wave touches (22).
    // Replay-stable identity (M10.1, the SetCell/MintedTags pattern): the
    // source is DERIVED from the ref, so a recovered instance replaying its
    // journal re-mints the exact dots the network already observed — a random
    // source would resurrect removed keys, because a pre-crash remove cannot
    // cover a re-minted dot.
    private val dotSource: UUID =
        UUID.nameUUIDFromBytes("or-map-tags:${ref.id}:${ref.instanceId}".toByteArray())
    private var dotCounter = 0L

    /** The dots at [key] no tombstone covers. */
    private fun liveDots(key: K): Map<Timestamp, V> {
        val dots = puts[key] ?: return emptyMap()
        val covered = dels[key] ?: return dots
        return dots.filterKeys { it !in covered }
    }

    /** `[24-TMAP-02]` add-wins presence: keys with at least one live dot. */
    fun membership(): Set<K> = puts.keys.filterTo(LinkedHashSet()) { liveDots(it).isNotEmpty() }

    /**
     * `[24-TMAP-03]` the key's exposed value: the live dot with the greatest
     * `(counter, sourceId)` order ([TaggedMapDelta.DOT_ORDER]) — never wall
     * clock. `null` when the key is absent.
     */
    fun value(key: K): V? =
        liveDots(key).entries.maxWithOrNull(compareBy(TaggedMapDelta.DOT_ORDER) { it.key })?.value

    /**
     * This cell's whole dot state as one delta-from-empty, tombstones
     * included — the catch-up emission (G-22, `[24-CATCHUP-01]`) and the
     * read-view any consumer can fold. Copies out; never aliases the fold's
     * mutable maps.
     */
    fun state(): TaggedMapDelta<K, V> = TaggedMapDelta(
        puts = puts.mapValues { LinkedHashMap(it.value) },
        dels = dels.mapValues { LinkedHashSet(it.value) },
    )

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): MapOps<K, V> = object : MapOps<K, V> {
        override fun put(key: K, value: V) {
            // reset-remove's local half: everything this writer currently sees
            // live at the key dies in the SAME delta that carries the fresh dot
            // (KeyedSetCell's atomic retract+add, lifted to dots).
            val observed = LinkedHashSet(liveDots(key).keys)
            val dot = Timestamp(dotSource, ++dotCounter)
            puts.getOrPut(key) { LinkedHashMap() }[dot] = value
            if (observed.isNotEmpty()) dels.getOrPut(key) { LinkedHashSet() } += observed
            outlet.call.propagate(
                TaggedMapDelta(
                    puts = mapOf(key to mapOf(dot to value)),
                    dels = if (observed.isEmpty()) emptyMap() else mapOf(key to observed),
                )
            )
        }

        override fun remove(key: K) {
            // `[24-TMAP-04]` reset-remove, tag-precise: tombstone exactly the
            // dots observed live here and now. A concurrent put's dot is not in
            // this set and therefore survives the merge.
            val observed = LinkedHashSet(liveDots(key).keys)
            // effective-only (21): removing a key with no live dot is a no-op
            if (observed.isEmpty()) return
            dels.getOrPut(key) { LinkedHashSet() } += observed
            outlet.call.propagate(TaggedMapDelta(dels = mapOf(key to observed)))
        }
    }

    init {
        // late-join catch-up (G-22): full dot state as one delta-from-empty,
        // tombstones included, to just the new subscriber — idempotent merge
        // ([24-TMAP-01]) makes replays harmless, and shipping the tombstones is
        // what stops a late joiner resurrecting a removed key.
        outlet.catchUpOnLinked { if (puts.isEmpty() && dels.isEmpty()) null else state() }
    }

    // snapshot/restore (G-25 seam): keys and values must be Serializable. The
    // dot counter is state too (M10.2) — a checkpoint-restored instance must
    // not re-mint a spent dot, or a post-restore put could collide with a dot
    // the network still remembers (and a tombstone for the old one would then
    // cover the new value).
    override fun snapshot(): Serializable =
        HashMap(
            mapOf(
                "puts" to HashMap(puts.mapValues { LinkedHashMap(it.value) }),
                "dels" to HashMap(dels.mapValues { LinkedHashSet(it.value) }),
                "counter" to dotCounter,
            )
        )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val maps = state as Map<String, Any>
        puts.clear()
        dels.clear()
        (maps.getValue("puts") as Map<K, Map<Timestamp, V>>)
            .forEach { (key, dots) -> puts[key] = LinkedHashMap(dots) }
        (maps.getValue("dels") as Map<K, Set<Timestamp>>)
            .forEach { (key, dots) -> dels[key] = LinkedHashSet(dots) }
        dotCounter = maps["counter"] as? Long ?: 0L
    }

    companion object {
        fun <K, V> create(): OrMapApi<K, V> = OrMapCell()
    }
}
