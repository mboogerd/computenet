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
        // Defaults BUILDING / 1995-03-15: orders 1 and 2 (customers 1, 2) qualify;
        // line 1 shipped 1994-01-10 (not after DATE) is excluded, so order 1 keeps
        // only line 2 = 2000*98 and order 2 keeps only line 3 = 3000*94.
        assertEquals(
            mapOf(
                listOf<Any>(1L, date("1993-07-01"), 0) to listOf(196_000L),
                listOf<Any>(2L, date("1994-06-01"), 0) to listOf(282_000L),
            ),
            q3.plain(),
        )

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
    fun `Q5 Q7 Q8 Q9 and Q14 equal hand-computed views`() {
        // Q5 ASIA, orders in 1993: only order 1 (customer 1, INDONESIA). Line 1's
        // supplier 1 is also INDONESIA: 1000*95. Line 2's supplier 2 is BRAZIL.
        assertEquals(
            mapOf(listOf<Any>("INDONESIA") to listOf(95_000L)),
            TpchReference.evaluate(TpchQuery.Q05, tables, Q5Params(region = "ASIA", date = date("1993-01-01"))).plain(),
        )
        // Q7 FRANCE/GERMANY, ship 1995..1996: line 3 supplier FRANCE -> customer 2
        // GERMANY = 3000*94; line 5 supplier GERMANY -> customer 3 FRANCE = 4000*90.
        // Lines 2 and 6 have a BRAZIL supplier.
        assertEquals(
            mapOf(
                listOf<Any>("FRANCE", "GERMANY", 1995) to listOf(282_000L),
                listOf<Any>("GERMANY", "FRANCE", 1995) to listOf(360_000L),
            ),
            TpchReference.evaluate(TpchQuery.Q07, tables).plain(),
        )
        // Q8 ECONOMY ANODIZED STEEL is part 2 (lines 2, 6). Line 2's order is from
        // 1993 (outside 1995..1996); line 6 (order 4, 1995, customer 1 in ASIA,
        // supplier 2 BRAZIL) = 5000*92. Defaults (AMERICA) select no customer.
        assertEquals(emptyMap<List<Any>, List<Long>>(), TpchReference.evaluate(TpchQuery.Q08, tables).plain())
        assertEquals(
            mapOf(listOf<Any>(1995) to listOf(460_000L, 460_000L)),
            TpchReference.evaluate(TpchQuery.Q08, tables, Q8Params(region = "ASIA")).plain(),
        )
        assertEquals(
            mapOf(listOf<Any>(1995) to listOf(0L, 460_000L)),
            TpchReference.evaluate(TpchQuery.Q08, tables, Q8Params(nation = "INDONESIA", region = "ASIA")).plain(),
        )
        // Q9 green parts 1..3; amount = ext*(100-disc) - supplycost*qty*100 (s4):
        // line 1 (s1 INDONESIA, 1993): 1000*95 - 50*5*100   = 70000
        // line 2 (s2 BRAZIL, 1993):    2000*98 - 70*15*100  = 91000
        // line 3 (s4 FRANCE, 1994):    3000*94 - 80*25*100  = 82000
        // line 4 (s1 INDONESIA, 1994): 1000*100 - 50*10*100 = 50000
        assertEquals(
            mapOf(
                listOf<Any>("INDONESIA", 1993) to listOf(70_000L),
                listOf<Any>("BRAZIL", 1993) to listOf(91_000L),
                listOf<Any>("FRANCE", 1994) to listOf(82_000L),
                listOf<Any>("INDONESIA", 1994) to listOf(50_000L),
            ),
            TpchReference.evaluate(TpchQuery.Q09, tables).plain(),
        )
        // Q14 September 1995: line 5 only, part 4 is PROMO = 4000*90 on both sides.
        // June 1995: line 3 only, part 3 is not PROMO = 0 / 3000*94.
        assertEquals(
            mapOf(emptyList<Any>() to listOf(360_000L, 360_000L)),
            TpchReference.evaluate(TpchQuery.Q14, tables).plain(),
        )
        assertEquals(
            mapOf(emptyList<Any>() to listOf(0L, 282_000L)),
            TpchReference.evaluate(TpchQuery.Q14, tables, Q14Params(date = date("1995-06-01"))).plain(),
        )
    }

    @Test
    fun `Q19 excludes a delivered-by-air row that passes no disjunct`() {
        // Part 1 matches disjunct 1 on brand, container and size, but quantity 30
        // is outside [1, 11]; it passes neither of the other brands either.
        val extra = Lineitem(3, 1, 1, 2, 30L, 7_000L, 0, 0, "N", "O", date("1995-01-10"), date("1995-01-20"), date("1995-01-21"), "DELIVER IN PERSON", "AIR", "")
        val q19 = TpchReference.evaluate(TpchQuery.Q19, tables.copy(lineitem = tables.lineitem + extra))
        assertEquals(573_000L, q19.rows[emptyList<Any>()]!![0])
    }

    @Test
    fun `Q16 counts distinct suppliers and matches Customer before Complaints`() {
        // Part 7 shares part 6's (brand, type, size) and supplier 2, so the group has
        // two partsupp rows from one supplier: count(distinct) = 1, count = 2.
        // Supplier 3's comment has the words in the wrong order for
        // '%Customer%Complaints%', so it is NOT excluded: suppliers {2, 3} -> 2.
        val withDuplicates = tables.copy(
            part = tables.part + Part(7, "plain", "Manufacturer#1", "Brand#11", "MEDIUM BRUSHED TIN", 49, "MED PACK", 800L, ""),
            partSupp = tables.partSupp + PartSupp(7, 2, 10, 130L, "") + PartSupp(7, 3, 10, 140L, ""),
            supplier = tables.supplier.map {
                if (it.suppKey == 3L) it.copy(comment = "Complaints about a Customer") else it
            },
        )
        val q16 = TpchReference.evaluate(TpchQuery.Q16, withDuplicates)
        assertEquals(listOf(2L), q16.rows[listOf<Any>("Brand#11", "MEDIUM BRUSHED TIN", 49)]!!.toList())
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

    private fun ViewResult.plain(): Map<List<Any>, List<Long>> = rows.mapValues { it.value.toList() }

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
