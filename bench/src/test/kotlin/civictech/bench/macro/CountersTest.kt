package civictech.bench.macro

import civictech.bench.cost.StructuralCounts
import civictech.cell.durability.InMemoryJournal
import civictech.testkit.dst.DstWorld
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CountersTest {
    @Test
    fun frameCounterCountsEachFrameAndItsBytes() {
        val world = DstWorld(1)
        world.edges.declare("e")
        val (counters, registration) = Counters.installOn(world, emptyList())
        val five = ByteArray(5)
        val seven = ByteArray(7)

        assertSame(five, world.edges.deliver("e", five).single())
        assertSame(seven, world.edges.deliver("e", seven).single())
        assertEquals(
            CounterSnapshot(messages = 0, frames = 2, bytes = 12, journalRecords = 0),
            counters.snapshot(),
        )

        registration.close()
        world.edges.deliver("e", ByteArray(11))
        assertEquals(
            CounterSnapshot(messages = 0, frames = 2, bytes = 12, journalRecords = 0),
            counters.snapshot(),
        )
    }

    @Test
    fun countingJournalCountsAppendsAndForwardsEverythingElse() {
        val inner = InMemoryJournal()
        val journal = CountingJournal(inner)
        val first = byteArrayOf(1)
        val second = byteArrayOf(2, 3)
        journal.append(first)
        journal.append(second)

        assertEquals(2, journal.appends.get())
        val replay = journal.replay()
        assertEquals(2, replay.size)
        assertArrayEquals(first, replay[0])
        assertArrayEquals(second, replay[1])
        assertEquals(inner.durability, journal.durability)
        assertEquals(inner.formatVersion, journal.formatVersion)

        val replacement = byteArrayOf(9)
        journal.reset(listOf(replacement))
        assertEquals(2, journal.appends.get())
        assertArrayEquals(replacement, journal.replay().single())
    }

    @Test
    fun countsAreDeltasAgainstTheStartSnapshot() {
        val world = DstWorld(1)
        world.edges.declare("e")
        val journal = CountingJournal(InMemoryJournal())
        val (counters, registration) = Counters.installOn(world, listOf(journal))
        val start = counters.snapshot()

        world.edges.deliver("e", ByteArray(3))
        journal.append(byteArrayOf(1))

        assertEquals(
            StructuralCounts(
                messages = 0,
                messagesNotMeasured = null,
                crossHostFrames = 1,
                wireBytes = 3,
                journalRecords = 1,
                simSteps = 9,
            ),
            counters.counts(start, simSteps = 9),
        )
        registration.close()
    }
}
