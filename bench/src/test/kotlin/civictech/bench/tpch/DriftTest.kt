package civictech.bench.tpch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class DriftTest {

    @Test
    fun `one hundred batches end with a distinct fresh reference evaluator`() {
        val evaluators = mutableListOf<TpchReferenceEvaluator>()
        val comparisonIndexes = mutableListOf<Int>()
        val record = TpchRun.run(
            query = TpchQuery.Q06,
            seed = 7L,
            sf = 0.001,
            plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 100),
            drive = Drive.SIM,
            env = tpchTestEnvironment(),
            hooks = TpchRunHooks(
                referenceFactory = {
                    object : TpchReferenceEvaluator {
                        override fun evaluate(
                            query: TpchQuery,
                            tables: TpchTables,
                            params: Any,
                        ): ViewResult = TpchReference.evaluate(query, tables, params)
                    }.also(evaluators::add)
                },
                comparisonObserved = { index, _ -> comparisonIndexes += index },
            ),
        )

        assertInstanceOf(TpchRecord.Verified::class.java, record)
        assertEquals(2, evaluators.size, "streaming and terminal evaluators")
        assertNotSame(evaluators[0], evaluators[1])
        assertEquals((0..101).toList(), comparisonIndexes)
    }
}
