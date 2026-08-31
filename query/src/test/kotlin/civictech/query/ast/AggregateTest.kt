package civictech.query.ast

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `[QRY1-LANG-03]`: exactly count/sum/avg/min/max/topK/collectToSet, and `topK` carries `k`. */
class AggregateTest {

    @Test
    fun `every non-topK kind constructs with no k`() {
        listOf(
            AggregateKind.COUNT,
            AggregateKind.SUM,
            AggregateKind.AVG,
            AggregateKind.MIN,
            AggregateKind.MAX,
            AggregateKind.COLLECT_TO_SET,
        ).forEach { kind ->
            Aggregate(kind).k shouldBe null
        }
    }

    @Test
    fun `topK requires a positive k`() {
        Aggregate(AggregateKind.TOP_K, k = 5).k shouldBe 5
        shouldThrow<IllegalArgumentException> { Aggregate(AggregateKind.TOP_K) }
        shouldThrow<IllegalArgumentException> { Aggregate(AggregateKind.TOP_K, k = 0) }
        shouldThrow<IllegalArgumentException> { Aggregate(AggregateKind.TOP_K, k = -1) }
    }

    @Test
    fun `a non-topK kind carrying k is rejected`() {
        shouldThrow<IllegalArgumentException> { Aggregate(AggregateKind.COUNT, k = 3) }
    }
}
