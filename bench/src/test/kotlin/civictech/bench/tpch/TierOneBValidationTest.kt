package civictech.bench.tpch

import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Default-lane validation for the five remaining maintained hand graphs. */
class TierOneBValidationTest {

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("queriesAndDrives")
    fun `twenty alternating refreshes equal the independent reference`(
        query: TpchQuery,
        drive: Drive,
    ) {
        val comparisons = mutableListOf<Pair<Int, Comparison>>()
        val record = TpchRun.run(
            query = query,
            seed = 7L,
            sf = 0.001,
            plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 20),
            drive = drive,
            env = tpchTestEnvironment(),
            hooks = TpchRunHooks(
                comparisonObserved = { index, comparison -> comparisons += index to comparison },
            ),
        )

        val verified = assertInstanceOf(TpchRecord.Verified::class.java, record)
        assertEquals(22, comparisons.size, "attach + 20 batches + final drift")
        assertTrue(comparisons.all { it.second == Comparison.Equal }) {
            "${query.name}/$drive differed at ${comparisons.filter { it.second != Comparison.Equal }.map { it.first }}"
        }
        val orderedFigures = (verified.rf1 + verified.rf2).sortedBy(BatchFigure::index)
        assertEquals(RefreshKind.RF1, orderedFigures.first().kind)
        assertEquals((1..20).toList(), orderedFigures.map(BatchFigure::index))
    }

    companion object {
        @JvmStatic
        fun queriesAndDrives(): Stream<Arguments> = Stream.of(
            TpchQuery.Q04,
            TpchQuery.Q10,
            TpchQuery.Q12,
            TpchQuery.Q14,
            TpchQuery.Q19,
        ).flatMap { query ->
            Stream.of(Drive.SIM, Drive.REAL).map { drive -> Arguments.of(query, drive) }
        }
    }
}
