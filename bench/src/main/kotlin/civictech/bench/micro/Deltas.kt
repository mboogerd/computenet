package civictech.bench.micro

import civictech.cell.Timestamp
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import java.util.Random
import java.util.UUID

/**
 * Which direction of the tag algebra a [DeltaBatch] drives [BEN1-18].
 *
 * The two are not symmetric work: an *insert* introduces an element and a
 * fresh add-tag, so every downstream operator sees a membership flip-ON; a
 * *retract* carries a `dels` entry covering exactly the add-tags of the batch
 * it retracts, which is what makes the element's last live tag disappear and
 * flips membership OFF. Anything else — a del of an unseen tag, a re-delivered
 * add — is *tag churn*, absorbed by every operator here (effective-only, spec
 * 21), and would measure the absorb path rather than the operator's work. The
 * generators below never produce churn by accident; a benchmark that wants it
 * has to ask for it deliberately.
 */
enum class Direction { INSERT, RETRACT }

/**
 * One pre-generated, immutable batch of `SetDelta<Int>` — the unit a benchmark
 * `@Setup` builds and the measured body only *applies* [BEN1-18].
 *
 * Nothing here is computed lazily: [elements] is folded at construction, so a
 * measured body that reads it pays nothing. Generation cost is deliberately
 * pushed entirely into the constructor for that reason.
 */
data class DeltaBatch(
    val direction: Direction,
    /** The [DeltaStream] seed this batch came from — carried so a result can name its input. */
    val seed: Long,
    val deltas: List<SetDelta<Int>>,
) {
    /** Number of deltas — the number of source-inlet invocations applying this batch costs. */
    val size: Int get() = deltas.size

    /** Every element this batch touches, in generation order. */
    val elements: Set<Int> = deltas.flatMapTo(LinkedHashSet()) { it.adds.keys + it.dels.keys }
}

/**
 * Deterministic, seeded generator of insert-direction batches [BEN1-18].
 *
 * **What the seed controls, exactly.** Elements are drawn from a strictly
 * increasing counter, so a stream never re-uses an element or a tag: applying
 * two batches from one stream is always genuinely new work rather than
 * dedup-absorbed churn, which is what a benchmark loop needs. The seed
 * therefore does *not* choose the elements — it chooses the **order** they
 * arrive in (a Fisher-Yates shuffle over each batch's element block) and the
 * tag source id. That is the whole of its influence, stated plainly because a
 * seed that is documented as "randomizing the workload" and in fact only
 * permutes it is exactly the kind of claim a later reader would over-trust.
 *
 * A stream is not thread-safe and is not meant to be: generate on the setup
 * thread, apply from anywhere.
 */
class DeltaStream(val seed: Long, val elementsPerDelta: Int = 1) {

    init {
        require(elementsPerDelta >= 1) { "elementsPerDelta must be >= 1, was $elementsPerDelta" }
    }

    /**
     * Tag source, derived from the seed rather than random: two runs of the
     * same seed mint byte-identical tags, so a measured difference between
     * runs cannot be an input difference.
     */
    private val tagSource: UUID = UUID.nameUUIDFromBytes("civictech.bench.micro/DeltaStream/$seed".toByteArray())

    private val order = Random(seed)
    private var tagCounter = 0L
    private var nextElement = 0

    /**
     * A batch of [deltas] deltas, each carrying [elementsPerDelta] freshly
     * minted elements with one fresh add-tag apiece.
     */
    fun insert(deltas: Int): DeltaBatch {
        require(deltas >= 0) { "deltas must be >= 0, was $deltas" }
        val block = IntArray(deltas * elementsPerDelta) { nextElement + it }
        nextElement += block.size
        // Fisher-Yates under the seeded RNG: the elements are fixed, their arrival order is not.
        for (i in block.indices.reversed()) {
            val j = order.nextInt(i + 1)
            val t = block[i]; block[i] = block[j]; block[j] = t
        }
        var cursor = 0
        val out = ArrayList<SetDelta<Int>>(deltas)
        repeat(deltas) {
            val adds = LinkedHashMap<Int, Set<Timestamp>>(elementsPerDelta)
            repeat(elementsPerDelta) { adds[block[cursor++]] = setOf(Timestamp(tagSource, ++tagCounter)) }
            out += SetDelta(adds = adds)
        }
        return DeltaBatch(Direction.INSERT, seed, out)
    }
}

/** Entry points for the two delta directions [BEN1-18]. */
object Deltas {

    /**
     * A single insert batch from a fresh stream — the one-shot form. A
     * benchmark that applies several batches to one graph wants a
     * [DeltaStream] instead, so that the batches carry disjoint elements.
     */
    fun insert(seed: Long, deltas: Int, elementsPerDelta: Int = 1): DeltaBatch =
        DeltaStream(seed, elementsPerDelta).insert(deltas)

    /**
     * The retract batch that undoes [batch]: for each of its deltas, a delta
     * whose `dels` is that delta's `adds` map verbatim — every add-tag covered
     * by a del of the same tag, which is what "remove what was added" *is*
     * under the observed-remove tag algebra (`tagFold` in the kernel's
     * `DataTestSupport`, spec 24 `[24-SET-01]`).
     *
     * Pure in [batch] and free of stream state on purpose: a retraction is
     * fully determined by the inserts it covers, so it cannot drift out of
     * step with them.
     */
    fun retract(batch: DeltaBatch): DeltaBatch {
        require(batch.direction == Direction.INSERT) {
            "retract() covers an INSERT batch's add-tags; got ${batch.direction}"
        }
        return DeltaBatch(
            Direction.RETRACT,
            batch.seed,
            batch.deltas.map { SetDelta(dels = it.adds) },
        )
    }

    // -------------------------------------------------------------------------------
    // Payload adaptation [BEN1-18] — the keyed and counter shapes.
    //
    // The join/group-by/combine subjects do not all serve `SetDelta`: `LookupJoinCell`
    // and `CombineLatestCell` serve `MapDelta`, `CoalescingCombineCell` serves
    // `CounterDelta`. Rather than a second generator family per shape, one seeded
    // `SetDelta` stream feeds every subject and its source cell re-originates the batch
    // in the shape the operator serves (`MapSourceCell`, `CounterSourceCell`).
    //
    // Two reasons, both about what the numbers mean. A keyed subject's input is then
    // provably the same stream as a set-shaped subject's — same seed, same elements,
    // same arrival order — so the two are comparable at all. And the retract direction
    // stays one definition: `retract` covers an insert batch's add-tags, and both
    // adapters map that to their own shape's removal, so "insert then retract leaves
    // nothing live" is one fact checked once rather than three.
    //
    // Both functions are pure and total, which is what makes them testable without a
    // graph — `GraphsExtendedTest` asserts the round trip on each before any cell is
    // involved.
    // -------------------------------------------------------------------------------

    /**
     * The `MapDelta<Int, Long>` view of a set delta: every added element becomes a put of
     * `element -> element.toLong()`, every deleted element a removal of that key.
     *
     * The value is derived from the key rather than random on purpose — a `MapDelta` put
     * is last-write-wins and untagged, so a value that varied per delivery would make the
     * combined output depend on arrival order and the oracle unstateable.
     */
    fun asMap(delta: SetDelta<Int>): MapDelta<Int, Long> =
        MapDelta(delta.adds.keys.associateWith { it.toLong() }, delta.dels.keys.toSet())

    /**
     * The `CounterDelta` view of a set delta: `+1` per added element, `-1` per deleted
     * one, so a batch and its retraction sum to zero.
     */
    fun asCounter(delta: SetDelta<Int>): CounterDelta =
        CounterDelta((delta.adds.size - delta.dels.size).toLong())
}
