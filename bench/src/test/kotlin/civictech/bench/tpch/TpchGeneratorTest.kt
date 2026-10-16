package civictech.bench.tpch

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TpchGeneratorTest {

    @Test
    fun `small scale factor has the decided cardinalities and key order`() {
        val tables = TpchGenerator.generate(seed = 7L, sf = 0.001)

        assertEquals(10, tables.supplier.size)
        assertEquals(200, tables.part.size)
        assertEquals(800, tables.partSupp.size)
        assertEquals(150, tables.customer.size)
        assertEquals(1_500, tables.orders.size)
        assertTrue(tables.lineitem.size in 1_500..10_500)
        assertEquals(25, tables.nation.size)
        assertEquals(5, tables.region.size)

        assertEquals(tables.part.map { it.partKey }, tables.part.map { it.partKey }.sorted())
        assertEquals(tables.orders.map { it.orderKey }, tables.orders.map { it.orderKey }.sorted())
        assertEquals(
            tables.lineitem.map { it.orderKey to it.lineNumber },
            tables.lineitem.map { it.orderKey to it.lineNumber }.sortedWith(compareBy({ it.first }, { it.second })),
        )
    }

    @Test
    fun `generated rows satisfy the section 4 2 3 numeric date and status rules`() {
        val tables = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val currentDate = LocalDate.parse("1995-06-17").toEpochDay().toInt()
        val scale = 10L

        assertEquals(1, tables.supplier.count { it.comment.contains("Customer") && it.comment.contains("Complaints") })
        assertEquals(1, tables.supplier.count { it.comment.contains("Customer") && it.comment.contains("Recommends") })

        tables.part.forEach { part ->
            assertEquals(5, part.name.split(' ').toSet().size)
            assertEquals(
                90_000L + ((part.partKey / 10L) % 20_001L) + 100L * (part.partKey % 1_000L),
                part.retailPrice,
            )
            val rows = tables.partSupp.filter { it.partKey == part.partKey }
            assertEquals(4, rows.size)
            assertEquals(
                (0..3).map { supplierFor(part.partKey, it, scale) }.toSet(),
                rows.map { it.suppKey }.toSet(),
            )
            rows.forEach { row ->
                assertTrue(row.availQty in 1..9_999)
                assertTrue(row.supplyCost in 100L..100_000L)
            }
        }

        tables.orders.forEach { order ->
            assertTrue((order.orderKey - 1L) % 32L in 0L..7L)
            assertNotEquals(0L, order.custKey % 3L)
            val lines = tables.lineitem.filter { it.orderKey == order.orderKey }
            assertTrue(lines.isNotEmpty())
            assertEquals(
                roundToCents(lines.sumOf { TpchDecimal.chargeS6(it.extendedPrice, it.discount, it.tax) }),
                order.totalPrice,
            )
            val expectedStatus = when {
                lines.all { it.lineStatus == "F" } -> "F"
                lines.all { it.lineStatus == "O" } -> "O"
                else -> "P"
            }
            assertEquals(expectedStatus, order.orderStatus)
        }

        tables.lineitem.forEach { line ->
            val order = tables.orders.single { it.orderKey == line.orderKey }
            val part = tables.part.single { it.partKey == line.partKey }
            assertTrue(line.lineNumber in 1..7)
            assertEquals(part.retailPrice * line.quantity, line.extendedPrice)
            assertTrue(line.quantity in 1L..50L)
            assertTrue(line.discount in 0..10)
            assertTrue(line.tax in 0..8)
            assertTrue(line.shipDate - order.orderDate in 1..121)
            assertTrue(line.commitDate - order.orderDate in 30..90)
            assertTrue(line.receiptDate - line.shipDate in 1..30)
            assertEquals(if (line.shipDate > currentDate) "O" else "F", line.lineStatus)
            if (line.receiptDate <= currentDate) {
                assertTrue(line.returnFlag == "R" || line.returnFlag == "A")
            } else {
                assertEquals("N", line.returnFlag)
            }
            assertTrue(
                line.suppKey in (0..3).map { supplierFor(line.partKey, it, scale) },
            )
        }
    }

    @Test
    fun `scaled long decimal helpers preserve exact graph arithmetic`() {
        assertEquals(9_000L, TpchDecimal.revenueS4(100L, 10))
        assertEquals(972_000L, TpchDecimal.chargeS6(100L, 10, 8))
        assertEquals(1_000L, TpchDecimal.discountedS4(100L, 10))
        assertEquals("1.23", TpchDecimal.render2(12_345L, 4))
        assertEquals("1.24", TpchDecimal.render2(12_350L, 4))
        assertEquals("-1.24", TpchDecimal.render2(-12_350L, 4))
    }

    @Test
    fun `default generation refuses scale factors above the long arithmetic bound`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            TpchGenerator.generate(seed = 7L, sf = 0.2)
        }
        assertTrue(failure.message!!.contains("allowOverflowRisk=true"))
    }

    private fun supplierFor(partKey: Long, supplierIndex: Int, scale: Long): Long {
        val stride = scale / 4L + (partKey - 1L) / scale
        return (partKey + supplierIndex * stride) % scale + 1L
    }

    private fun roundToCents(scale6Value: Long): Long = BigDecimal.valueOf(scale6Value)
        .movePointLeft(6)
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
}
