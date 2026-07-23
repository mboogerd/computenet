package civictech.cell.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MapDiffPublisherTest {

    @Test
    fun `publish emits adds, changes, removals - and nothing when effective state holds`() {
        val pub = MapDiffPublisher<String, Int>()
        val source = mutableMapOf("a" to 1, "b" to 2)

        assertEquals(MapDelta(mapOf("a" to 1, "b" to 2), emptySet()), pub.publish(source.keys.toList()) { source[it] })
        // restating the same values: no delta
        assertNull(pub.publish(source.keys.toList()) { source[it] })

        source["a"] = 9
        assertEquals(MapDelta(mapOf("a" to 9), emptySet()), pub.publish(listOf("a")) { source[it] })

        source.remove("b")
        assertEquals(MapDelta(emptyMap<String, Int>(), setOf("b")), pub.publish(listOf("b")) { source[it] })
        // removing an unknown key: no delta
        assertNull(pub.publish(listOf("zzz")) { source[it] })

        assertEquals(mapOf("a" to 9), pub.current())
    }

    @Test
    fun `publishAll removes keys absent from the recomputed map`() {
        val pub = MapDiffPublisher<String, Int>()
        pub.publishAll(mapOf("a" to 1, "b" to 2))
        assertEquals(MapDelta(mapOf("c" to 3), setOf("b")), pub.publishAll(mapOf("a" to 1, "c" to 3)))
        assertNull(pub.publishAll(mapOf("a" to 1, "c" to 3)))
    }

    @Test
    fun `epsilon comparator suppresses sub-threshold churn`() {
        val pub = MapDiffPublisher<String, Double>(changed = { a, b -> abs(a - b) > 0.01 })
        pub.publish(listOf("x")) { 1.0 }
        assertNull(pub.publish(listOf("x")) { 1.005 })
        assertEquals(MapDelta(mapOf("x" to 1.1), emptySet()), pub.publish(listOf("x")) { 1.1 })
    }

    @Test
    fun `catchUpDelta is the published state or null when empty`() {
        val pub = MapDiffPublisher<String, Int>()
        assertNull(pub.catchUpDelta())
        pub.publish(listOf("a")) { 1 }
        assertEquals(MapDelta(mapOf("a" to 1), emptySet()), pub.catchUpDelta())
        pub.reset(mapOf("b" to 2))
        assertEquals(MapDelta(mapOf("b" to 2), emptySet()), pub.catchUpDelta())
    }
}
