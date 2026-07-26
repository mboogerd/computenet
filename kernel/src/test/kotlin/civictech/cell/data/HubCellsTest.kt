package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HubCellsTest {

    @Test
    fun `SetHubCell folds a set stream and fires on effective membership change only`() {
        val updates = mutableListOf<Set<String>>()
        val writer = SetCell<String>()
        val hub = SetHubCell<String>(onUpdate = { updates += it })

        @Suppress("UNCHECKED_CAST")
        val result = writer.outlet.linkTo(hub.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        assertTrue(result is LinkResult.Connected)

        writer.inlet.call.add("a")
        writer.inlet.call.add("b")
        writer.inlet.call.remove("a")
        assertEquals(listOf(setOf("a"), setOf("a", "b"), setOf("b")), updates)

        // removing an absent element is tag-only churn: no update
        writer.inlet.call.remove("zzz")
        assertEquals(3, updates.size)
    }

    @Test
    fun `MapHubCell folds a map stream and suppresses restated puts`() {
        val updates = mutableListOf<Map<String, Long>>()
        val writer = MapCell<String, Long>()
        val hub = MapHubCell<String, Long>(onUpdate = { updates += it })

        @Suppress("UNCHECKED_CAST")
        val result = writer.outlet.linkTo(hub.inlet as LinkFrom<Propagate<MapDelta<String, Long>>>)
        assertTrue(result is LinkResult.Connected)

        writer.inlet.call.put("a", 1L)
        writer.inlet.call.put("a", 1L) // restated: silent
        writer.inlet.call.put("a", 2L)
        writer.inlet.call.remove("a")
        assertEquals(listOf(mapOf("a" to 1L), mapOf("a" to 2L), emptyMap()), updates)
    }
}
