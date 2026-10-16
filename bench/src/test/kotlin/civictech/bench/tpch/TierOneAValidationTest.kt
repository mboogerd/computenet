package civictech.bench.tpch

import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Default-lane BS-2 validation for the first four maintained hand graphs. */
class TierOneAValidationTest {

    @ParameterizedTest(name = "{0} under {1}")
    @MethodSource("queriesAndDrives")
    fun `twenty alternating refreshes equal the independent reference`(
        query: TpchQuery,
        drive: Drive,
    ) {
        val scenario = validationScenario(query)
        val plan = scenario.plan()
        assertReferenceAnswerChangesForBothRefreshKinds(query, plan)
        val comparisons = mutableListOf<Pair<Int, Comparison>>()
        val record = TpchRun.run(
            query = query,
            seed = scenario.seed,
            sf = 0.001,
            plan = plan,
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
            TpchQuery.Q01,
            TpchQuery.Q03,
            TpchQuery.Q05,
            TpchQuery.Q06,
        ).flatMap { query ->
            Stream.of(Drive.SIM, Drive.REAL).map { drive -> Arguments.of(query, drive) }
        }
    }
}

internal data class ValidationScenario(
    val seed: Long,
    val ordersPerRefresh: Int,
) {
    fun plan(): RefreshPlan = RefreshPlan.alternating(seed = seed, sf = 0.001, batches = 20)
        .copy(ordersPerRefresh = ordersPerRefresh)
}

/**
 * The first seed in 1..256 at the smallest tested refresh size (2, 4, 8, ...)
 * whose 20-batch stream changes the reference after both RF1 and RF2. Two orders
 * is the SF 0.001 default. Q08 also keeps a non-zero share numerator, so it needs
 * seed 72 and eight orders; Q19 has no two-order match and first matches at seed
 * 220 with four. Q16 reads neither ORDERS nor LINEITEM and retains the old stream.
 */
internal fun validationScenario(query: TpchQuery): ValidationScenario = when (query) {
    TpchQuery.Q01 -> ValidationScenario(seed = 1L, ordersPerRefresh = 2)
    TpchQuery.Q03 -> ValidationScenario(seed = 4L, ordersPerRefresh = 2)
    TpchQuery.Q04 -> ValidationScenario(seed = 2L, ordersPerRefresh = 2)
    TpchQuery.Q05 -> ValidationScenario(seed = 1L, ordersPerRefresh = 2)
    TpchQuery.Q06 -> ValidationScenario(seed = 2L, ordersPerRefresh = 2)
    TpchQuery.Q07 -> ValidationScenario(seed = 2L, ordersPerRefresh = 2)
    TpchQuery.Q08 -> ValidationScenario(seed = 72L, ordersPerRefresh = 8)
    TpchQuery.Q09 -> ValidationScenario(seed = 1L, ordersPerRefresh = 2)
    TpchQuery.Q10 -> ValidationScenario(seed = 3L, ordersPerRefresh = 2)
    TpchQuery.Q12 -> ValidationScenario(seed = 4L, ordersPerRefresh = 2)
    TpchQuery.Q14 -> ValidationScenario(seed = 6L, ordersPerRefresh = 2)
    TpchQuery.Q16 -> ValidationScenario(seed = 7L, ordersPerRefresh = 2)
    TpchQuery.Q19 -> ValidationScenario(seed = 220L, ordersPerRefresh = 4)
    else -> error("${query.name} has no hand-route validation scenario")
}

internal fun assertReferenceAnswerChangesForBothRefreshKinds(
    query: TpchQuery,
    plan: RefreshPlan,
) {
    val changedKinds = referenceAnswerChangedKinds(query, plan)
    assertTrue(RefreshKind.RF1 in changedKinds) {
        "${query.name} reference answer never changed after RF1 in ${plan.batchCount} batches " +
            "(seed=${plan.seed}, ordersPerRefresh=${plan.ordersPerRefresh})"
    }
    assertTrue(RefreshKind.RF2 in changedKinds) {
        "${query.name} reference answer never changed after RF2 in ${plan.batchCount} batches " +
            "(seed=${plan.seed}, ordersPerRefresh=${plan.ordersPerRefresh})"
    }
}

private fun referenceAnswerChangedKinds(
    query: TpchQuery,
    plan: RefreshPlan,
): Set<RefreshKind> {
    val base = TpchGenerator.generate(seed = plan.seed, sf = plan.sf)
    val logical = LogicalTables(base)
    var previous = TpchReference.evaluate(query, logical.snapshot())
    val changedKinds = mutableSetOf<RefreshKind>()

    RefreshStream.generate(plan, base).forEach { batch ->
        logical.apply(batch)
        val current = TpchReference.evaluate(query, logical.snapshot())
        if (ViewComparison.compare(previous, current) is Comparison.Differs) {
            changedKinds += when (batch) {
                is RefreshBatch.Rf1 -> RefreshKind.RF1
                is RefreshBatch.Rf2 -> RefreshKind.RF2
            }
        }
        previous = current
    }
    return changedKinds
}
