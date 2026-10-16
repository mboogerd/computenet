package civictech.bench.tpch

import kotlin.math.round
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshStreamTest {

    @Test
    fun `refresh stream is deterministic and follows sparse RF1 RF2 rules`() {
        val base = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val plan = RefreshPlan.alternating(seed = 17L, sf = 0.001, batches = 6)
        val first = RefreshStream.generate(plan, base)

        assertEquals(first, RefreshStream.generate(plan, base))
        assertNotEquals(first, RefreshStream.generate(plan.copy(seed = 18L), base))

        val baseKeys = base.orders.map { it.orderKey }.toSet()
        val allocated = baseKeys.toMutableSet()
        val live = baseKeys.toSortedSet()
        first.forEach { batch ->
            when (batch) {
                is RefreshBatch.Rf1 -> {
                    assertEquals(plan.ordersPerRefresh, batch.orders.size)
                    assertTrue(batch.lineitems.all { it.lineNumber in 1..7 })
                    assertTrue(batch.orders.all { order ->
                        val slot = ((order.orderKey - 1L) % 32L) + 1L
                        slot in 9L..32L && allocated.add(order.orderKey)
                    })
                    assertTrue(batch.lineitems.groupBy { it.orderKey }.values.all { it.size in 1..7 })
                    batch.orders.forEach { live += it.orderKey }
                }

                is RefreshBatch.Rf2 -> {
                    assertEquals(batch.orderkeys.sorted(), batch.orderkeys)
                    assertEquals(batch.orderkeys, live.take(plan.ordersPerRefresh))
                    batch.orderkeys.forEach { live.remove(it) }
                }
            }
        }
    }

    @Test
    fun `refresh plan computes the specified rounded order count`() {
        assertEquals(1, RefreshPlan.alternating(1L, 0.0001, 0).ordersPerRefresh)
        assertEquals(round(0.01 * 1_500.0).toInt(), RefreshPlan.alternating(1L, 0.01, 0).ordersPerRefresh)
    }

    @Test
    fun `logical tables remove every lineitem when an order is removed`() {
        val base = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val logical = LogicalTables(base)
        val rf1 = RefreshStream.generate(
            RefreshPlan(seed = 9L, sf = 0.001, batches = listOf(RefreshKind.RF1)),
            base,
        ).single() as RefreshBatch.Rf1

        logical.applyRf1(rf1)
        val afterRf1 = logical.snapshot()
        assertEquals(afterRf1.orders.sortedBy { it.orderKey }, afterRf1.orders)
        assertEquals(
            afterRf1.lineitem.sortedWith(compareBy<Lineitem> { it.orderKey }.thenBy { it.lineNumber }),
            afterRf1.lineitem,
        )
        assertEquals(base.orders.size + rf1.orders.size, afterRf1.orders.size)
        logical.applyRf2(RefreshBatch.Rf2(rf1.orders.map { it.orderKey }.sorted()))

        assertEquals(base, logical.snapshot())
    }
}
