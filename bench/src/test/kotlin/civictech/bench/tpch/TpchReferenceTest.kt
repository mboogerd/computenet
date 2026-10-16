package civictech.bench.tpch

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TpchReferenceTest {

    @Test
    fun `Q1 hand fixture keeps every aggregate at its declared scale`() {
        val result = TpchReference.evaluate(TpchQuery.Q01, tables)

        // The R/F group is line 1 + line 3: qty 5+25, base 1000+3000,
        // discounted price 1000*95 + 3000*94, charge 1000*95*102 + 3000*94*103.
        assertEquals(
            longArrayOf(30L, 4_000L, 377_000L, 38_736_000L, 30L, 2L, 4_000L, 2L, 11L, 2L, 2L).toList(),
            result.rows[listOf<Any>("R", "F")]!!.toList(),
        )
        assertEquals(
            longArrayOf(50L, 9_000L, 820_000L, 83_440_000L, 50L, 2L, 9_000L, 2L, 18L, 2L, 2L).toList(),
            result.rows[listOf<Any>("N", "O")]!!.toList(),
        )
    }

    @Test
    fun `Q6 matches an independently written discounted revenue sum`() {
        val params = Q6Params(date = date("1994-01-01"), discount = 6, quantity = 24L)
        val expected = tables.lineitem
            .filter { it.shipDate >= params.date && it.shipDate < date("1995-01-01") }
            .filter { it.discount in 5..7 && it.quantity < 24L }
            .sumOf { it.extendedPrice * it.discount.toLong() }

        val result = TpchReference.evaluate(TpchQuery.Q06, tables, params)
        assertEquals(expected, result.rows[emptyList<Any>()]!![0])
        assertEquals(5_000L, expected)
    }

    @Test
    fun `Q3 and Q10 group joins without losing the order or customer key`() {
        val q3 = TpchReference.evaluate(TpchQuery.Q03, tables)
        assertEquals(196_000L, q3.rows[listOf<Any>(1L, date("1993-07-01"), 0)]!![0])

        val q10 = TpchReference.evaluate(TpchQuery.Q10, tables, Q10Params(date = date("1993-05-01")))
        val customerKey = listOf<Any>(1L, "Customer#000000001", 10_000L, "phone-1", "INDONESIA", "customer-address", "customer-comment")
        assertEquals(95_000L, q10.rows[customerKey]!![0])
    }

    @Test
    fun `Q4 exists counts an order once and Q12 keeps high and low splits`() {
        val q4 = TpchReference.evaluate(TpchQuery.Q04, tables)
        assertEquals(1L, q4.rows[listOf<Any>("1-URGENT")]!![0])

        val q12 = TpchReference.evaluate(
            TpchQuery.Q12,
            tables,
            Q12Params(shipMode1 = "AIR", shipMode2 = "SHIP", date = date("1994-01-01")),
        )
        assertEquals(listOf(1L, 0L), q12.rows[listOf<Any>("AIR")]!!.toList())
        assertEquals(listOf(0L, 1L), q12.rows[listOf<Any>("SHIP")]!!.toList())
    }

    @Test
    fun `Q16 count distinct excludes complaint suppliers and Q19 accepts each disjunct`() {
        val q16 = TpchReference.evaluate(TpchQuery.Q16, tables)
        assertEquals(1L, q16.rows[listOf<Any>("Brand#11", "MEDIUM BRUSHED TIN", 49)]!![0])

        val q19 = TpchReference.evaluate(TpchQuery.Q19, tables)
        // The first three lineitems pass disjuncts 1, 2, and 3 respectively.
        assertEquals(573_000L, q19.rows[emptyList<Any>()]!![0])
    }

    @Test
    fun `the remaining hand queries return the declared view shapes`() {
        val expectedColumns = mapOf(
            TpchQuery.Q05 to listOf("revenue"),
            TpchQuery.Q07 to listOf("revenue"),
            TpchQuery.Q08 to listOf("share_num", "share_den"),
            TpchQuery.Q09 to listOf("sum_profit"),
            TpchQuery.Q14 to listOf("promo_num", "promo_den"),
        )
        expectedColumns.forEach { (query, columns) ->
            val params = if (query == TpchQuery.Q05) {
                Q5Params(region = "ASIA", date = date("1993-01-01"))
            } else {
                TpchReference.defaults(query)
            }
            assertEquals(columns, TpchReference.evaluate(query, tables, params).columns, query.name)
        }
    }

    @Test
    fun `blocked queries name their aggregate relation cause`() {
        TpchQuery.entries.filter { it.tier == TpchTier.BLOCKED_2 }.forEach { query ->
            val failure = assertThrows(UnsupportedOperationException::class.java) {
                TpchReference.evaluate(query, tables)
            }
            assertTrue(failure.message!!.contains(query.blockedCause!!), query.name)
        }
    }

    private fun date(value: String): Int = LocalDate.parse(value).toEpochDay().toInt()

    private val tables = TpchTables(
        part = listOf(
            Part(1, "green red blue white black", "Manufacturer#1", "Brand#12", "STANDARD ANODIZED STEEL", 3, "SM CASE", 200L, ""),
            Part(2, "green orange purple yellow brown", "Manufacturer#2", "Brand#23", "ECONOMY ANODIZED STEEL", 7, "MED BOX", 300L, ""),
            Part(3, "green cyan beige ivory navy", "Manufacturer#3", "Brand#34", "LARGE BURNISHED TIN", 12, "LG CASE", 400L, ""),
            Part(4, "red orange pink tan white", "Manufacturer#4", "Brand#45", "PROMO POLISHED STEEL", 20, "MED BAG", 500L, ""),
            Part(5, "black grey blue gold red", "Manufacturer#5", "Brand#11", "SMALL PLATED COPPER", 2, "SM PACK", 600L, ""),
            Part(6, "violet yellow lime coral peach", "Manufacturer#1", "Brand#11", "MEDIUM BRUSHED TIN", 49, "MED PACK", 700L, ""),
        ),
        supplier = listOf(
            Supplier(1, "Supplier#1", "supplier-address-1", 9, "supplier-phone-1", 1_000L, "Customer says Complaints here"),
            Supplier(2, "Supplier#2", "supplier-address-2", 2, "supplier-phone-2", 2_000L, "ordinary supplier"),
            Supplier(3, "Supplier#3", "supplier-address-3", 7, "supplier-phone-3", 3_000L, "ordinary supplier"),
            Supplier(4, "Supplier#4", "supplier-address-4", 6, "supplier-phone-4", 4_000L, "ordinary supplier"),
        ),
        partSupp = listOf(
            PartSupp(1, 1, 10, 50L, ""), PartSupp(1, 2, 10, 60L, ""),
            PartSupp(2, 2, 10, 70L, ""), PartSupp(3, 4, 10, 80L, ""),
            PartSupp(4, 3, 10, 90L, ""), PartSupp(5, 2, 10, 100L, ""),
            PartSupp(6, 1, 10, 110L, ""), PartSupp(6, 2, 10, 120L, ""),
        ),
        customer = listOf(
            Customer(1, "Customer#000000001", "customer-address", 9, "phone-1", 10_000L, "BUILDING", "customer-comment"),
            Customer(2, "Customer#000000002", "customer-address-2", 7, "phone-2", 20_000L, "BUILDING", "customer-comment-2"),
            Customer(3, "Customer#000000003", "customer-address-3", 6, "phone-3", 30_000L, "FURNITURE", "customer-comment-3"),
        ),
        orders = listOf(
            Orders(1, 1, "F", 0L, date("1993-07-01"), "1-URGENT", "clerk-1", 0, ""),
            Orders(2, 2, "F", 0L, date("1994-06-01"), "3-MEDIUM", "clerk-2", 0, ""),
            Orders(3, 3, "F", 0L, date("1995-01-01"), "2-HIGH", "clerk-3", 0, ""),
            Orders(4, 1, "F", 0L, date("1995-09-01"), "5-LOW", "clerk-4", 0, ""),
        ),
        lineitem = listOf(
            Lineitem(1, 1, 1, 1, 5L, 1_000L, 5, 2, "R", "F", date("1994-01-10"), date("1994-01-20"), date("1994-01-21"), "DELIVER IN PERSON", "AIR", ""),
            Lineitem(1, 2, 2, 2, 15L, 2_000L, 2, 1, "A", "O", date("1995-03-16"), date("1995-03-20"), date("1995-03-21"), "DELIVER IN PERSON", "AIR", ""),
            Lineitem(2, 3, 4, 1, 25L, 3_000L, 6, 3, "R", "F", date("1995-06-01"), date("1995-06-15"), date("1995-06-16"), "DELIVER IN PERSON", "AIR", ""),
            Lineitem(2, 1, 1, 2, 10L, 1_000L, 0, 0, "A", "F", date("1994-06-02"), date("1994-06-15"), date("1994-06-16"), "NONE", "SHIP", ""),
            Lineitem(3, 4, 3, 1, 20L, 4_000L, 10, 4, "N", "O", date("1995-09-05"), date("1995-09-10"), date("1995-09-11"), "DELIVER IN PERSON", "MAIL", ""),
            Lineitem(4, 5, 2, 1, 30L, 5_000L, 8, 0, "N", "O", date("1996-12-31"), date("1996-12-01"), date("1997-01-01"), "NONE", "FOB", ""),
        ),
        nation = listOf(
            Nation(2, "BRAZIL", 1, ""), Nation(6, "FRANCE", 3, ""), Nation(7, "GERMANY", 3, ""),
            Nation(9, "INDONESIA", 2, ""),
        ),
        region = listOf(Region(1, "AMERICA", ""), Region(2, "ASIA", ""), Region(3, "EUROPE", "")),
    )
}
