package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowGrowthTest {
    @Test
    fun `Q7 grows without a policy and stays within the lateness envelope with one`() {
        val unbounded = runWindowed(NexmarkQuery.Q7, policy = null)
        val policy = LatenessPolicy(5_000L)
        val evicting = runWindowed(NexmarkQuery.Q7, policy = policy)

        val unboundedSeries = seriesFrom(unbounded.header)
        val evictingSeries = seriesFrom(evicting.header)
        assertTrue(
            unboundedSeries.zipWithNext().all { (previous, current) ->
                current.liveWindows >= previous.liveWindows
            },
        )
        assertEquals(4L, unboundedSeries.last().liveWindows)

        val foldOnlyState = unbounded.header.stateSeries.map { state ->
            state.copy(liveRows = mapOf(Q7_WINDOW_OUTLET to state.liveRows.getValue(Q7_WINDOW_OUTLET)))
        }
        assertEquals(
            GrowthNote.Growing(Q7_WINDOW_OUTLET),
            StateAccounting.growth(foldOnlyState),
        )

        val bound = policy.lateness / NexmarkSpec.WINDOW_MS + 2L
        assertTrue(evictingSeries.all { it.liveWindows <= bound }) {
            "evicting series exceeded bound $bound: $evictingSeries"
        }
        assertTrue(evictingSeries.last().liveWindows < 4L)

        val finding = windowGrowthFinding(unboundedSeries, evictingSeries, policy)
        assertEquals(GrowthVerdict.RETIRES, finding.verdict)
        assertTrue(finding.note.contains("evicting series"))
    }

    @Test
    fun `growth finding distinguishes a breach and insufficient evidence`() {
        val policy = LatenessPolicy(5_000L)
        val unbounded = listOf(0L, 1L, 2L).mapIndexed { index, windows ->
            WindowGrowthSample(index, windows, null)
        }

        val fires = windowGrowthFinding(
            unbounded = unbounded,
            evicting = listOf(0L, 1L, 3L).mapIndexed { index, windows ->
                WindowGrowthSample(index, windows, 0L)
            },
            policy = policy,
        )
        assertEquals(GrowthVerdict.FIRES, fires.verdict)
        assertTrue(fires.note.contains("exceeds"))

        val short = windowGrowthFinding(unbounded.take(2), unbounded, policy)
        assertEquals(GrowthVerdict.INCONCLUSIVE, short.verdict)
        assertTrue(short.note.contains("three"))

        val flat = windowGrowthFinding(
            unbounded = listOf(1L, 1L, 1L).mapIndexed { index, windows ->
                WindowGrowthSample(index, windows, null)
            },
            evicting = unbounded,
            policy = policy,
        )
        assertEquals(GrowthVerdict.INCONCLUSIVE, flat.verdict)
        assertTrue(flat.note.contains("not non-decreasing growth"))
    }
}
