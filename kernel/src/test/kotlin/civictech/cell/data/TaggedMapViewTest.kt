package civictech.cell.data

import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.data.view.TaggedMapView
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `TaggedMapView` — the view half of feature computenet-j2x.2 (KE1 E1.5),
 * exercised by the epic's behaviour specifications:
 *
 * - BS-4 order-insensitivity ([KE1-11], [KE1-13]).
 * - BS-5 honest effective-change, including the last-live-dot tombstone
 *   boundary ([KE1-12]).
 * - BS-6 snapshot/restore ([KE1-14]).
 */
class TaggedMapViewTest {

    private val s1 = UUID(1, 1)
    private val s2 = UUID(2, 2)

    /**
     * A fixed multiset of deltas from a seeded write schedule:
     * - two concurrent puts at key "a" (s1's dot loses DOT_ORDER to s2's on
     *   tie-break, but that's irrelevant here — both dots stay live until
     *   the tombstone below covers only s1's),
     * - one put at "b",
     * - a tombstone covering exactly the s1 dot at "a" (s2's concurrent put
     *   survives — reset-remove only kills the dot the remove observed),
     * - one put at "c".
     *
     * Expected converged state: a -> "a2" (only s2's dot is live), b -> "b1",
     * c -> "c1".
     */
    private fun seededDeltas(): List<TaggedMapDelta<String, String>> = listOf(
        TaggedMapDelta(puts = mapOf("a" to mapOf(Timestamp(s1, 1) to "a1"))),
        TaggedMapDelta(puts = mapOf("a" to mapOf(Timestamp(s2, 1) to "a2"))),
        TaggedMapDelta(puts = mapOf("b" to mapOf(Timestamp(s1, 2) to "b1"))),
        TaggedMapDelta(dels = mapOf("a" to setOf(Timestamp(s1, 1)))),
        TaggedMapDelta(puts = mapOf("c" to mapOf(Timestamp(s2, 5) to "c1"))),
    )

    private fun batchFold(deltas: List<TaggedMapDelta<String, String>>): Map<String, String> {
        val merged = deltas.reduce { a, b -> a.merge(b) }
        return merged.membership().associateWith { merged.value(it)!! }
    }

    @Test
    fun `BS-4 order-insensitive fold matches the batch fold under permutation and duplication`() {
        val deltas = seededDeltas()
        val expected = batchFold(deltas)

        val orders = listOf(
            deltas,
            deltas.reversed(),
            listOf(deltas[4], deltas[0], deltas[3], deltas[1], deltas[2]),
            listOf(deltas[1], deltas[3], deltas[0], deltas[2], deltas[4]),
            // duplication: the whole schedule applied twice in a row
            deltas + deltas,
            // interleaved duplication
            listOf(deltas[0], deltas[0], deltas[1], deltas[2], deltas[3], deltas[3], deltas[4], deltas[4]),
        )

        orders.forEach { order ->
            val view = TaggedMapView<String, String>()
            order.forEach { view.apply(it) }
            view.current() shouldBe expected
        }
    }

    @Test
    fun `BS-5 apply reports effective change honestly, including the last-live-dot tombstone boundary`() {
        val view = TaggedMapView<String, String>()
        val dot = Timestamp(s1, 1)
        val put = TaggedMapDelta(puts = mapOf("a" to mapOf(dot to "1")))

        // first delivery: a genuinely appears
        view.apply(put).shouldBeTrue()
        view.current() shouldBe mapOf("a" to "1")

        // re-delivered dot: no change
        view.apply(put).shouldBeFalse()
        view.current() shouldBe mapOf("a" to "1")

        val tombstone = TaggedMapDelta<String, String>(dels = mapOf("a" to setOf(dot)))

        // tombstoning the last (only) live dot at "a": effective change, key disappears
        view.apply(tombstone).shouldBeTrue()
        view.current().shouldBe(emptyMap())
        ("a" in view).shouldBeFalse()

        // tombstone of an already-covered dot: no further change
        view.apply(tombstone).shouldBeFalse()
        view.current().shouldBe(emptyMap())
    }

    @Test
    fun `BS-5 boundary holds even with a concurrent surviving dot`() {
        val view = TaggedMapView<String, String>()
        val dot1 = Timestamp(s1, 1)
        val dot2 = Timestamp(s2, 1)

        view.apply(TaggedMapDelta(puts = mapOf("a" to mapOf(dot1 to "a1")))).shouldBeTrue()
        view.apply(TaggedMapDelta(puts = mapOf("a" to mapOf(dot2 to "a2")))).shouldBeTrue()

        // tombstoning dot1 alone: "a" survives via dot2, but its exposed value
        // may change depending on DOT_ORDER — either way this is an
        // effective change to record honestly, unless the surviving dot was
        // already the winner. Assert against the delta's own oracle rather
        // than re-deriving DOT_ORDER here.
        val before = view["a"]
        val changed = view.apply(TaggedMapDelta(dels = mapOf("a" to setOf(dot1))))
        val after = view["a"]
        changed shouldBe (before != after)
        after shouldBe "a2"
    }

    @Test
    fun `BS-6 survives snapshot and restore, including subsequent apply behaviour`() {
        val deltas = seededDeltas()
        val original = TaggedMapView<String, String>()
        deltas.forEach { original.apply(it) }

        val snapshot = original.snapshot()
        val restored = TaggedMapView<String, String>()
        restored.restore(snapshot)

        restored.current() shouldBe original.current()

        // a subsequent delta produces the same apply result and same current()
        // on both the original and the restored twin
        val follow = TaggedMapDelta(puts = mapOf("d" to mapOf(Timestamp(s1, 9) to "d1")))
        val originalChanged = original.apply(follow)
        val restoredChanged = restored.apply(follow)

        restoredChanged shouldBe originalChanged
        restored.current() shouldBe original.current()
    }
}
