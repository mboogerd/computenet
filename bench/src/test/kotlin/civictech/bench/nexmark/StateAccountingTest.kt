package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StateAccountingTest {
    @Test
    fun `peak takes per key maxima and the last ledger probe`() {
        val series = listOf(
            RetainedState(mapOf("a" to 2L, "b" to 7L), mapOf("fold" to 1L), "first"),
            RetainedState(mapOf("a" to 5L), mapOf("fold" to 3L, "other" to 2L), "last"),
        )
        assertEquals(
            RetainedState(
                liveRows = mapOf("a" to 5L, "b" to 7L),
                foldKeys = mapOf("fold" to 3L, "other" to 2L),
                ledgerProbe = "last",
            ),
            StateAccounting.peak(series),
        )
    }

    @Test
    fun `growth requires a non decreasing outlet ending above its first sample`() {
        val growing = listOf(1L, 1L, 3L).map { RetainedState(mapOf("q7" to it), emptyMap()) }
        val shrinking = listOf(1L, 3L, 2L).map { RetainedState(mapOf("q7" to it), emptyMap()) }
        val flat = listOf(1L, 1L, 1L).map { RetainedState(mapOf("q7" to it), emptyMap()) }

        assertEquals(GrowthNote.Growing("q7"), StateAccounting.growth(growing))
        assertEquals(GrowthNote.Flat, StateAccounting.growth(shrinking))
        assertEquals(GrowthNote.Flat, StateAccounting.growth(flat))
    }
}
