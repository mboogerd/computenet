package civictech.cell.data

import civictech.cell.Timestamp
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.TaggedMapDelta
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
}
