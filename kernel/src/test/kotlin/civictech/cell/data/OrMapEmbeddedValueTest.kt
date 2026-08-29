package civictech.cell.data

import civictech.cell.EmbeddedMergeClass
import civictech.cell.NonIdempotentEmbeddedMerge
import civictech.cell.Timestamp
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.nature.MergeClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * 96 §E1.4 embedded-value folding, task computenet-j2x.1.1:
 *
 * - `[KE1-01]` folding restricted to [civictech.cell.MergeablePayload] values.
 * - `[KE1-02]` >1 live dot, `V` mergeable ⇒ `value(k)` is the `mergeWith`-fold
 *   of every live dot's value, folded in [TaggedMapDelta.DOT_ORDER].
 * - `[KE1-03]` exactly one live dot, or `V` not mergeable ⇒ `value(k)` is the
 *   greatest live dot under [TaggedMapDelta.DOT_ORDER], unchanged from
 *   shipped behaviour.
 * - `[KE1-05]` the fold reads no wall clock and does not depend on dot
 *   arrival order.
 * - `[KE1-06]`/`[KE1-07]` `values(k): Set<V>` — every live dot's value, empty
 *   when the key is absent.
 * - `[KE1-08]` `TaggedMapDelta.value(k)`/`values(k)` agree with
 *   `OrMapCell.value(k)`/`values(k)` on every dot state.
 *
 * `membership()` and the non-mergeable/single-dot paths are untouched
 * (j2x.1-D2) — [OrMapCellTest] already covers those and must stay green.
 *
 * Task computenet-j2x.1.2 adds the admission half:
 *
 * - `[KE1-04]` a *classified* non-idempotent embedded value ([CounterDelta],
 *   plain addition) is refused with a diagnostic naming the Riak
 *   embedded-counter anomaly, on `put` and on the remote path alike, and no
 *   fold happens.
 * - `[KE1-10]` the classification is a **first-encounter** one: `V` is erased
 *   at the ports and CP-F2 stamps `MERGE_IDEMPOTENCE` per *cell*, so the
 *   link-time form of the check is unreachable — the shortfall and the
 *   unclassified-value residual are recorded in `concord/corpus/DISPUTES.md`
 *   (j2x.1-D3), never restated here as "the cell rejects all non-idempotent
 *   values".
 */
class OrMapEmbeddedValueTest {

    // -----------------------------------------------------------------
    // BS-3 verbatim — multi-value read for the non-mergeable (String) case
    // -----------------------------------------------------------------

    @Test
    fun `BS-3 values returns every live concurrent dot, value stays the DOT_ORDER pick`() {
        val lo = UUID(0, 1)
        val hi = UUID(0, 2)
        val cell = OrMapCell<String, String>()
        cell.restore(
            HashMap(
                mapOf(
                    "puts" to HashMap(
                        mapOf("k" to LinkedHashMap(mapOf(Timestamp(lo, 1) to "from-lo", Timestamp(hi, 1) to "from-hi"))),
                    ),
                    "dels" to HashMap<String, Set<Timestamp>>(),
                    "counter" to 0L,
                ) as Map<String, Any>
            ) as Serializable
        )

        cell.values("k") shouldBe setOf("from-lo", "from-hi")
        // DOT_ORDER: (1, lo) < (1, hi) — hi wins the tie-break
        cell.value("k") shouldBe "from-hi"

        // boundary: absent key
        cell.values("absent").shouldBeEmpty()
        cell.value("absent") shouldBe null

        // the delta-level type agrees
        cell.state().values("k") shouldBe cell.values("k")
        cell.state().value("k") shouldBe cell.value("k")
        cell.state().values("absent").shouldBeEmpty()
        cell.state().value("absent") shouldBe null
    }

    // -----------------------------------------------------------------
    // single-replica fold — two concurrent live PnCounterDelta dots
    // -----------------------------------------------------------------

    @Test
    fun `two concurrent live PnCounterDelta dots at one key fold to their total`() {
        val s1 = UUID(1, 1)
        val s2 = UUID(2, 2)
        val plus3 = PnCounterDelta(incs = mapOf(s1 to 3L))
        val plus5 = PnCounterDelta(incs = mapOf(s2 to 5L))

        val cell = OrMapCell<String, PnCounterDelta>()
        cell.restore(
            HashMap(
                mapOf(
                    "puts" to HashMap(
                        mapOf("k" to LinkedHashMap(mapOf(Timestamp(s1, 1) to plus3, Timestamp(s2, 1) to plus5))),
                    ),
                    "dels" to HashMap<String, Set<Timestamp>>(),
                    "counter" to 0L,
                ) as Map<String, Any>
            ) as Serializable
        )

        val folded = cell.value("k")
        folded shouldBe PnCounterDelta(incs = mapOf(s1 to 3L, s2 to 5L))
        // the total, whichever pointwise-max path it took
        (folded!!.incs.values.sum()) shouldBe 8L

        cell.values("k") shouldBe setOf(plus3, plus5)
        // agreement with the delta-level implementation
        cell.state().value("k") shouldBe cell.value("k")
        cell.state().values("k") shouldBe cell.values("k")
    }

    // -----------------------------------------------------------------
    // agreement across dot states — TaggedMapDelta vs OrMapCell
    // -----------------------------------------------------------------

    @Test
    fun `TaggedMapDelta and OrMapCell agree on value and values across every dot state`() {
        val s1 = UUID(3, 1)
        val s2 = UUID(3, 2)

        fun cellWith(
            puts: Map<Timestamp, Any?>,
            dels: Set<Timestamp> = emptySet(),
        ): OrMapCell<String, Any?> {
            val cell = OrMapCell<String, Any?>()
            cell.restore(
                HashMap(
                    mapOf(
                        "puts" to HashMap(mapOf("k" to LinkedHashMap(puts))),
                        "dels" to HashMap(if (dels.isEmpty()) emptyMap() else mapOf("k" to LinkedHashSet(dels))),
                        "counter" to 0L,
                    ) as Map<String, Any>
                ) as Serializable
            )
            return cell
        }

        fun assertAgree(cell: OrMapCell<String, Any?>) {
            cell.state().value("k") shouldBe cell.value("k")
            cell.state().values("k") shouldBe cell.values("k")
        }

        // absent
        assertAgree(cellWith(emptyMap()))

        // single dot, mergeable
        assertAgree(cellWith(mapOf(Timestamp(s1, 1) to PnCounterDelta(incs = mapOf(s1 to 1L)))))

        // multi-dot mergeable
        assertAgree(
            cellWith(
                mapOf(
                    Timestamp(s1, 1) to PnCounterDelta(incs = mapOf(s1 to 2L)),
                    Timestamp(s2, 1) to PnCounterDelta(incs = mapOf(s2 to 4L)),
                ),
            ),
        )

        // multi-dot non-mergeable (plain String)
        assertAgree(cellWith(mapOf(Timestamp(s1, 1) to "a", Timestamp(s2, 1) to "b")))

        // all dots tombstoned
        assertAgree(
            cellWith(
                mapOf(Timestamp(s1, 1) to "a", Timestamp(s2, 1) to "b"),
                dels = setOf(Timestamp(s1, 1), Timestamp(s2, 1)),
            ),
        )
    }

    // -----------------------------------------------------------------
    // order-independence — [KE1-05]
    // -----------------------------------------------------------------

    @Test
    fun `folded value does not depend on delta application order or duplication`() {
        val s1 = UUID(4, 1)
        val s2 = UUID(4, 2)
        val s3 = UUID(4, 3)
        val parts = listOf(
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(s1, 1) to PnCounterDelta(incs = mapOf(s1 to 1L))))),
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(s2, 1) to PnCounterDelta(incs = mapOf(s2 to 2L))))),
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(s3, 1) to PnCounterDelta(incs = mapOf(s3 to 3L))))),
        )
        val expected = PnCounterDelta(incs = mapOf(s1 to 1L, s2 to 2L, s3 to 3L))

        fun <T> permutations(items: List<T>): List<List<T>> =
            if (items.size <= 1) listOf(items)
            else items.flatMapIndexed { i, item ->
                permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(item) + it }
            }

        permutations(parts).forEach { order ->
            val folded = order.fold(TaggedMapDelta<String, PnCounterDelta>()) { acc, d -> acc.merge(d) }
            folded.value("k") shouldBe expected
        }

        // duplicated delivery — idempotent merge, same fold result
        val duplicated = (parts + parts).fold(TaggedMapDelta<String, PnCounterDelta>()) { acc, d -> acc.merge(d) }
        duplicated.value("k") shouldBe expected
    }

    // -----------------------------------------------------------------
    // boundary — KE1-07
    // -----------------------------------------------------------------

    @Test
    fun `no live dot - values empty, value null, on both TaggedMapDelta and OrMapCell`() {
        TaggedMapDelta<String, PnCounterDelta>().values("k").shouldBeEmpty()
        TaggedMapDelta<String, PnCounterDelta>().value("k") shouldBe null

        val cell = OrMapCell<String, PnCounterDelta>()
        cell.values("k").shouldBeEmpty()
        cell.value("k") shouldBe null
    }

    // -----------------------------------------------------------------
    // BS-2 verbatim — a non-idempotent embedded value is refused, not folded
    // -----------------------------------------------------------------

    @Test
    fun `BS-2 a CounterDelta value is refused on first put, naming the Riak embedded-counter anomaly`() {
        val cell = OrMapCell<String, CounterDelta>()

        val refusal = shouldThrow<NonIdempotentEmbeddedMerge> {
            cell.inlet.call.put("k", CounterDelta(3))
        }
        refusal.message!! shouldContain "Riak embedded-counter anomaly"
        refusal.valueType shouldBe "civictech.cell.data.delta.CounterDelta"

        // no fold, and no state at all: the refusal fired before a dot was minted
        cell.value("k") shouldBe null
        cell.values("k").shouldBeEmpty()
        cell.membership().shouldBeEmpty()
        cell.state() shouldBe TaggedMapDelta<String, CounterDelta>()
    }

    @Test
    fun `BS-2 remote half - a CounterDelta arriving via the delta inlet is refused the same way`() {
        val peer = UUID(9, 1)
        val cell = OrMapCell<String, CounterDelta>()

        val refusal = shouldThrow<NonIdempotentEmbeddedMerge> {
            cell.deltaInlet.call.propagate(
                TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(peer, 1) to CounterDelta(5)))),
            )
        }
        refusal.message!! shouldContain "Riak embedded-counter anomaly"

        // refused loudly, not dropped: nothing of the delta was absorbed, so no
        // dot and no value went missing without the diagnostic.
        cell.value("k") shouldBe null
        cell.values("k").shouldBeEmpty()
        cell.state() shouldBe TaggedMapDelta<String, CounterDelta>()
    }

    // -----------------------------------------------------------------
    // acceptance path — the check must not over-refuse
    // -----------------------------------------------------------------

    @Test
    fun `PnCounterDelta values stay admitted and still fold on both the local and remote path`() {
        val a = UUID(9, 2)
        val b = UUID(9, 3)
        val cell = OrMapCell<String, PnCounterDelta>()

        // local put — admitted
        cell.inlet.call.put("k", PnCounterDelta(incs = mapOf(a to 3L)))
        cell.value("k") shouldBe PnCounterDelta(incs = mapOf(a to 3L))

        // a concurrent peer dot the local writer never observed — admitted and folded
        cell.deltaInlet.call.propagate(
            TaggedMapDelta(puts = mapOf("k" to mapOf(Timestamp(b, 1) to PnCounterDelta(incs = mapOf(b to 5L))))),
        )
        val folded = cell.value("k")!!
        folded.incs.values.sum() shouldBe 8L
        cell.values("k").size shouldBe 2

        // a plain non-mergeable value is likewise unaffected by the check
        val strings = OrMapCell<String, String>()
        strings.inlet.call.put("k", "v")
        strings.value("k") shouldBe "v"
    }

    // -----------------------------------------------------------------
    // the classification itself — MergeClass vocabulary, j2x.1-D1
    // -----------------------------------------------------------------

    @Test
    fun `EmbeddedMergeClass classifies the repo's MergeablePayload implementations in MergeClass terms`() {
        EmbeddedMergeClass.classify(CounterDelta(1)) shouldBe MergeClass.NON_IDEMPOTENT
        EmbeddedMergeClass.classify(PnCounterDelta()) shouldBe MergeClass.IDEMPOTENT
        EmbeddedMergeClass.classify(SetDelta<String>()) shouldBe MergeClass.IDEMPOTENT
        EmbeddedMergeClass.classify(WatermarkDelta()) shouldBe MergeClass.IDEMPOTENT
        EmbeddedMergeClass.classify(TaggedMapDelta<String, String>()) shouldBe MergeClass.IDEMPOTENT

        // not a MergeablePayload at all — nothing folds it, so no merge class
        EmbeddedMergeClass.classify("plain") shouldBe null
        EmbeddedMergeClass.classify(null) shouldBe null
    }

    /**
     * The honest limit of `[KE1-04]` (j2x.1-D3): the refusal is stated over the
     * *classified* set. An unnamed [civictech.cell.MergeablePayload] whose merge
     * is silently non-idempotent is **admitted**, and this test pins that as the
     * measured behaviour rather than leaving the guarantee sounding universal.
     * Recorded in `concord/corpus/DISPUTES.md`.
     */
    @Test
    fun `an unclassified MergeablePayload is admitted - the recorded residual, not a universal refusal`() {
        val unnamed = UnclassifiedAdditionDelta(1)
        EmbeddedMergeClass.classify(unnamed) shouldBe null

        val cell = OrMapCell<String, UnclassifiedAdditionDelta>()
        cell.inlet.call.put("k", unnamed)
        cell.value("k") shouldBe unnamed
        // it is genuinely non-idempotent — merging it twice does not fix-point
        unnamed.mergeWith(unnamed) shouldNotBe unnamed
    }

    /** A non-idempotent merge the nominated table does not name — the residual. */
    data class UnclassifiedAdditionDelta(val amount: Long) : civictech.cell.MergeablePayload {
        override fun mergeWith(other: civictech.cell.MergeablePayload) =
            UnclassifiedAdditionDelta(amount + (other as UnclassifiedAdditionDelta).amount)
    }
}
