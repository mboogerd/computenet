package civictech.cell.data.delta

import civictech.cell.MergeablePayload
import civictech.cell.Timestamp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.Serializable
import java.util.UUID

/**
 * Tagged-map delta (spec 20/24 §Tagged maps, G-23 for keyed structures; 96
 * §E1.2) — [SetDelta]'s observed-remove idiom lifted one level, from a
 * live/tombstoned tag per *element* to a live/tombstoned **dot** per *key*.
 *
 * Each `put` mints a unique [Timestamp] **dot** carrying that put's value;
 * [dels] holds the dots a remove observed live and tombstoned. A key's live
 * dots are its [puts] entries not covered by [dels]. All dots of a whole map
 * share **one causal namespace** — there is deliberately no per-key context
 * (decided point 1: per-key contexts re-admit stale values on key re-creation).
 *
 * The four normative laws this type realizes:
 *
 * - `[24-TMAP-01]` [merge] is pointwise dot union — commutative, associative
 *   and idempotent, because a dot is unique to one put and its value is
 *   immutable, so a key's presence and value converge regardless of delivery
 *   order.
 * - `[24-TMAP-02]` [membership] is add-wins: a key is present iff it has at
 *   least one live dot.
 * - `[24-TMAP-03]` [value] is last-writer-wins **by dot order**
 *   ([DOT_ORDER] = `(counter, sourceId)`) over the key's live dots — never by
 *   wall-clock time. No clock is read anywhere in this file.
 * - `[24-TMAP-04]` reset-remove is a property of how a remover builds its
 *   delta (it tombstones exactly the dots it observed live — see
 *   `civictech.cell.data.OrMapCell`), and of this merge: a concurrent put's
 *   dot, unobserved by that remove, is simply not in [dels] and survives.
 *
 * **Tombstoned dels subsume deferred context ops** (decided point 2): a
 * remove's dots arriving before the put that minted them sit in [dels] and
 * cover that put on arrival, exactly as `SetCell.applyRemote` already behaves
 * for elements — so no Riak-style deferred-operations list is needed.
 *
 * **Embedded values are restricted to the idempotent-mergeable class**
 * ([MergeablePayload], decided point 3). This type does *not* fold embedded
 * values: concurrent live dots resolve by [DOT_ORDER] here. Folding a
 * `V : MergeablePayload` instead of picking the LWW dot is 96 §E1.4 and is
 * not implemented yet; Riak's embedded-counter anomaly (a non-idempotent
 * embedded CRDT cannot get full reset-remove without a dot per increment) is
 * why the class is restricted rather than open.
 *
 * **Dot-metadata bloat is a codec-layer concern from day one** (decided point
 * 4): the serialized form groups dots by `sourceId` behind a source
 * dictionary ([TaggedMapDeltaSerializer]) — never in these merge semantics,
 * which stay a plain pointwise union over whole [Timestamp]s.
 *
 * **Excluded**: the tombstone-free (context-only) wire form — [dels] shipped
 * as causal context with no tombstone payload — is deliberately not part of
 * this type; it needs the causal-merging condition whose delivered-watermark
 * prerequisite lands with E3 (95 §R10).
 */
@kotlinx.serialization.Serializable(with = TaggedMapDeltaSerializer::class)
data class TaggedMapDelta<K, V>(
    /** Live dots carrying values: `key -> (dot -> the value that put wrote)`. */
    val puts: Map<K, Map<Timestamp, V>> = emptyMap(),
    /** Tombstoned observed-remove dots: `key -> the dots a remove covered`. */
    val dels: Map<K, Set<Timestamp>> = emptyMap(),
) : Serializable, MergeablePayload {

    /**
     * `[24-TMAP-01]` — pointwise dot union. Commutative and associative
     * because set/map union is; idempotent because a dot identifies exactly
     * one put and therefore always carries the same value, so re-merging a
     * dot that is already present adds nothing.
     */
    fun merge(other: TaggedMapDelta<K, V>): TaggedMapDelta<K, V> =
        TaggedMapDelta(mergeDots(puts, other.puts), mergeTombstones(dels, other.dels))

    @Suppress("UNCHECKED_CAST")
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as TaggedMapDelta<K, V>)

    /** The dots at [key] that no tombstone covers — the key's live dots. */
    fun liveDots(key: K): Map<Timestamp, V> {
        val dots = puts[key] ?: return emptyMap()
        val covered = dels[key] ?: return dots
        if (covered.isEmpty()) return dots
        return dots.filterKeys { it !in covered }
    }

    /** `[24-TMAP-02]` add-wins presence: the keys with at least one live dot. */
    fun membership(): Set<K> = puts.keys.filterTo(LinkedHashSet()) { liveDots(it).isNotEmpty() }

    /**
     * `[24-TMAP-03]` the exposed value at [key]: the value of the live dot
     * with the greatest [DOT_ORDER] — `(counter, sourceId)`, never wall clock.
     * `null` when the key has no live dot (i.e. it is absent).
     */
    fun value(key: K): V? =
        liveDots(key).entries.maxWithOrNull(compareBy(DOT_ORDER) { it.key })?.value

    /** Every key this delta carries information about, live or tombstoned. */
    fun keys(): Set<K> = LinkedHashSet<K>(puts.keys).also { it += dels.keys }

    companion object {
        /**
         * `[24-TMAP-03]`'s total order over dots: counter first, then
         * `sourceId` as the tie-break. Deterministic and replica-independent
         * — the point of the law is that no wall clock and no arrival order
         * participates in picking a key's value.
         */
        val DOT_ORDER: Comparator<Timestamp> =
            compareBy<Timestamp> { it.counter }.thenBy { it.sourceId }

        private fun <K, V> mergeDots(
            a: Map<K, Map<Timestamp, V>>,
            b: Map<K, Map<Timestamp, V>>,
        ): Map<K, Map<Timestamp, V>> = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            // a dot is unique to one put, so a key present on both sides can
            // only agree on the dots they share — plain map union is the union
            // of two disjoint-on-collision dot sets.
            else -> (a.keys + b.keys).associateWith { key ->
                val left = a[key]
                val right = b[key]
                when {
                    left == null -> right!!
                    right == null -> left
                    else -> LinkedHashMap(left).also { it.putAll(right) }
                }
            }
        }

        private fun <K> mergeTombstones(
            a: Map<K, Set<Timestamp>>,
            b: Map<K, Set<Timestamp>>,
        ): Map<K, Set<Timestamp>> = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> (a.keys + b.keys).associateWith { key ->
                val left = a[key]
                val right = b[key]
                when {
                    left == null -> right!!
                    right == null -> left
                    else -> left + right
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compact dot encoding (spec 20/24 §Tagged maps, decided point 4). Riak names
// actor-metadata repetition "a serious issue" for size: the naive encoding of
// `Map<K, Map<Timestamp, V>>` repeats a 36-char sourceId once per dot. The
// surrogate below hoists every distinct sourceId into one dictionary and
// groups each key's dots by their source ordinal, so a source is written once
// per delta rather than once per dot. This is *only* an encoding: the merge
// semantics above never see it, and decode is exact (identity round-trip).
// ---------------------------------------------------------------------------

/** One source's dots under one key: parallel `counters`/`values` (encoding only). */
@kotlinx.serialization.Serializable
internal data class WireSourcePuts<V>(
    val source: Int,
    val counters: List<Long>,
    val values: List<V>,
)

/** One source's tombstoned dot counters under one key (encoding only). */
@kotlinx.serialization.Serializable
internal data class WireSourceDels(
    val source: Int,
    val counters: List<Long>,
)

/** One key's live dots, grouped by source (encoding only). */
@kotlinx.serialization.Serializable
internal data class WireKeyPuts<K, V>(val key: K, val groups: List<WireSourcePuts<V>>)

/** One key's tombstoned dots, grouped by source (encoding only). */
@kotlinx.serialization.Serializable
internal data class WireKeyDels<K>(val key: K, val groups: List<WireSourceDels>)

/**
 * The wire shape of a [TaggedMapDelta]: a `sourceId` dictionary plus per-key
 * dot groups referencing it by ordinal. Carries the `@SerialName` the
 * polymorphic registration in `WireCodec` keys on.
 */
@kotlinx.serialization.Serializable
@SerialName("TaggedMapDelta")
internal data class TaggedMapWire<K, V>(
    /** Distinct dot sources, in first-seen order; groups index into this. */
    val sources: List<String> = emptyList(),
    val puts: List<WireKeyPuts<K, V>> = emptyList(),
    val dels: List<WireKeyDels<K>> = emptyList(),
)

/**
 * [TaggedMapDelta]'s codec: a surrogate serializer that groups dots by
 * `sourceId` (spec 20/24 §Tagged maps, decided point 4). Registered
 * polymorphically in `civictech.cell.wire.WireCodec` beside `SetDelta` — a
 * new `@SerialName` payload, additive, with no frame-type change.
 */
class TaggedMapDeltaSerializer<K, V>(
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
) : KSerializer<TaggedMapDelta<K, V>> {

    private val surrogate: KSerializer<TaggedMapWire<K, V>> =
        TaggedMapWire.serializer(keySerializer, valueSerializer)

    override val descriptor: SerialDescriptor get() = surrogate.descriptor

    override fun serialize(encoder: Encoder, value: TaggedMapDelta<K, V>) =
        encoder.encodeSerializableValue(surrogate, toWire(value))

    override fun deserialize(decoder: Decoder): TaggedMapDelta<K, V> =
        fromWire(decoder.decodeSerializableValue(surrogate))

    private fun toWire(delta: TaggedMapDelta<K, V>): TaggedMapWire<K, V> {
        val ordinals = LinkedHashMap<UUID, Int>()
        fun ordinal(source: UUID): Int = ordinals.getOrPut(source) { ordinals.size }

        val puts = delta.puts.map { (key, dots) ->
            val grouped = LinkedHashMap<Int, Pair<MutableList<Long>, MutableList<V>>>()
            dots.forEach { (dot, value) ->
                val group = grouped.getOrPut(ordinal(dot.sourceId)) { mutableListOf<Long>() to mutableListOf() }
                group.first += dot.counter
                group.second += value
            }
            WireKeyPuts(key, grouped.map { (source, group) -> WireSourcePuts(source, group.first, group.second) })
        }
        val dels = delta.dels.map { (key, dots) ->
            val grouped = LinkedHashMap<Int, MutableList<Long>>()
            dots.forEach { dot -> grouped.getOrPut(ordinal(dot.sourceId)) { mutableListOf() } += dot.counter }
            WireKeyDels(key, grouped.map { (source, counters) -> WireSourceDels(source, counters) })
        }
        // `ordinals` is complete only after both passes ran (both are eager).
        return TaggedMapWire(ordinals.keys.map(UUID::toString), puts, dels)
    }

    private fun fromWire(wire: TaggedMapWire<K, V>): TaggedMapDelta<K, V> {
        val sources = wire.sources.map(UUID::fromString)
        fun dot(source: Int, counter: Long): Timestamp {
            require(source in sources.indices) { "TaggedMapDelta: dot source ordinal $source out of range" }
            return Timestamp(sources[source], counter)
        }

        val puts = LinkedHashMap<K, Map<Timestamp, V>>(wire.puts.size)
        wire.puts.forEach { row ->
            val dots = LinkedHashMap<Timestamp, V>()
            row.groups.forEach { group ->
                require(group.counters.size == group.values.size) {
                    "TaggedMapDelta: ${group.counters.size} dot counters against ${group.values.size} values"
                }
                group.counters.forEachIndexed { i, counter -> dots[dot(group.source, counter)] = group.values[i] }
            }
            puts[row.key] = dots
        }

        val dels = LinkedHashMap<K, Set<Timestamp>>(wire.dels.size)
        wire.dels.forEach { row ->
            val dots = LinkedHashSet<Timestamp>()
            row.groups.forEach { group -> group.counters.forEach { dots += dot(group.source, it) } }
            dels[row.key] = dots
        }

        return TaggedMapDelta(puts, dels)
    }
}
