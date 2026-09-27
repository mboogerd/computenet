package civictech.cell.data.delta

import civictech.cell.ReBaselineNotice
import civictech.cell.Timestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `TagState.evictBelow` (spec 24 §Lateness and waterlines, `[24-WL-08]`/
 * `[24-WL-09]`): watermark-driven eviction kills the live tags of every
 * element a predicate admits and returns them as `dels`, so an evicted
 * element has no live tag afterwards and a later ordinary del for it is a
 * no-op through [TagState.apply].
 */
class TagStateEvictTest {

    private fun tag(n: Long, source: Long = 0L) = Timestamp(UUID(0, source), n)

    @Test
    fun `non-retaining eviction removes tags, is a no-op on later del, and re-admits on later add`() {
        val state = TagState<String>()
        val t1 = tag(1)
        val t2 = tag(2)
        val t3 = tag(3)
        state.apply(SetDelta(adds = mapOf("a" to setOf(t1, t2), "b" to setOf(t3))))

        val evicted = state.evictBelow { it == "a" }

        assertEquals(mapOf("a" to setOf(t1, t2)), evicted.dels)
        assertFalse("a" in state)
        assertEquals(setOf(t3), state.tags("b"))

        // a later ordinary del for an evicted tag is a no-op (B4: evicted state has no live tag)
        val before = state.asDelta()
        val delResult = state.apply(SetDelta(dels = mapOf("a" to setOf(t1))))
        assertEquals(SetDelta<String>(), delResult, "a del of an already-evicted tag must be a no-op")
        assertEquals(before, state.asDelta(), "state must be unchanged by the no-op del")

        // without retention, no tombstone fences a later add: the element re-admits
        val addResult = state.apply(SetDelta(adds = mapOf("a" to setOf(t1))))
        assertEquals(mapOf("a" to setOf(t1)), addResult.adds, "no tombstone without retention: re-add is admitted")
        assertEquals(setOf(t1), state.tags("a"))
    }

    @Test
    fun `retaining eviction tombstones the killed tags and fences a later re-add of the same tag`() {
        val state = TagState<String>(retainTombstones = true)
        val t1 = tag(1)
        val t2 = tag(2)
        val t9 = tag(9)
        state.apply(SetDelta(adds = mapOf("a" to setOf(t1, t2))))

        val evicted = state.evictBelow { it == "a" }
        assertEquals(mapOf("a" to setOf(t1, t2)), evicted.dels)
        assertEquals(mapOf("a" to setOf(t1, t2)), state.asDelta().dels)

        // the same tombstoned tag is refused on re-add ...
        val refused = state.apply(SetDelta(adds = mapOf("a" to setOf(t1))))
        assertEquals(SetDelta<String>(), refused, "a tombstoned tag must be refused on re-add")
        assertFalse("a" in state)

        // ... while a fresh, never-tombstoned tag is admitted
        val admitted = state.apply(SetDelta(adds = mapOf("a" to setOf(t9))))
        assertEquals(mapOf("a" to setOf(t9)), admitted.adds)
        assertEquals(setOf(t9), state.tags("a"))
    }

    @Test
    fun `deadSources fenced before eviction stays fenced afterwards`() {
        val state = TagState<String>()
        val src = UUID(0, 42)
        val t1 = Timestamp(src, 1)
        state.apply(SetDelta(adds = mapOf("a" to setOf(t1))))

        state.applyReBaseline(SetDelta(), ReBaselineNotice(supersedes = setOf(src), supersede = true))
        state.evictBelow { it == "a" }

        // a fresh tag from the same now-dead source is still rejected after eviction
        val rejected = state.apply(SetDelta(adds = mapOf("a" to setOf(Timestamp(src, 2)))))
        assertEquals(SetDelta<String>(), rejected, "a fenced source must stay fenced after an unrelated eviction")
    }

    @Test
    fun `a predicate matching nothing returns an empty delta and leaves state identical`() {
        val state = TagState<String>()
        val t1 = tag(1)
        state.apply(SetDelta(adds = mapOf("a" to setOf(t1))))

        val before = state.asDelta()
        val evicted = state.evictBelow { it == "nonexistent" }

        assertEquals(SetDelta<String>(), evicted)
        assertEquals(before, state.asDelta())
    }

    @Test
    fun `post-eviction snapshot restores into an equivalent fresh instance, both retention modes`() {
        val nonRetaining = TagState<String>()
        val t1 = tag(1)
        val t2 = tag(2)
        val t3 = tag(3)
        nonRetaining.apply(SetDelta(adds = mapOf("a" to setOf(t1, t2), "b" to setOf(t3))))
        nonRetaining.evictBelow { it == "a" }

        val restoredNonRetaining = TagState<String>()
        restoredNonRetaining.restore(nonRetaining.snapshot())
        assertEquals(nonRetaining.asDelta(), restoredNonRetaining.asDelta())

        val retaining = TagState<String>(retainTombstones = true)
        retaining.apply(SetDelta(adds = mapOf("a" to setOf(t1, t2), "b" to setOf(t3))))
        retaining.evictBelow { it == "a" }

        val restoredRetaining = TagState<String>(retainTombstones = true)
        restoredRetaining.restore(retaining.snapshot())
        assertEquals(retaining.asDelta(), restoredRetaining.asDelta())
    }
}
