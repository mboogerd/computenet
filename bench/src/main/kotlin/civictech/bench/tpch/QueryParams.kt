package civictech.bench.tpch

import java.time.LocalDate

/** Validation substitution values from TPC-H Revision 3.0.1 §2.4.N.4. */
data class Q1Params(
    /** §2.4.1.4: the cutoff is 90 days before 1998-12-01. */
    val delta: Int = 90,
) {
    init {
        require(delta in 60..120) { "Q1 DELTA must be in [60, 120]" }
    }
}

data class Q3Params(
    /** §2.4.3.4. */
    val segment: String = "BUILDING",
    /** §2.4.3.4, stored as an epoch day like the generated rows. */
    val date: Int = tpchDate("1995-03-15"),
) {
    init {
        require(segment.isNotBlank()) { "Q3 SEGMENT must not be blank" }
    }
}

data class Q4Params(
    /** §2.4.4.4, stored as an epoch day like the generated rows. */
    val date: Int = tpchDate("1993-07-01"),
)

data class Q5Params(
    /** §2.4.5.4. */
    val region: String = "ASIA",
    /** §2.4.5.4, stored as an epoch day like the generated rows. */
    val date: Int = tpchDate("1994-01-01"),
) {
    init {
        require(region.isNotBlank()) { "Q5 REGION must not be blank" }
    }
}

data class Q6Params(
    /** §2.4.6.4: six hundredths, not a binary floating-point percentage. */
    val date: Int = tpchDate("1994-01-01"),
    val discount: Int = 6,
    val quantity: Long = 24L,
) {
    init {
        require(discount in 2..9) { "Q6 DISCOUNT must be in [2, 9] hundredths" }
        require(quantity in 24L..25L) { "Q6 QUANTITY must be in [24, 25]" }
    }
}

data class Q7Params(
    /** §2.4.7.4. */
    val nation1: String = "FRANCE",
    val nation2: String = "GERMANY",
) {
    init {
        require(nation1.isNotBlank() && nation2.isNotBlank()) { "Q7 nations must not be blank" }
        require(nation1 != nation2) { "Q7 nations must differ" }
    }
}

data class Q8Params(
    /** §2.4.8.4. */
    val nation: String = "BRAZIL",
    val region: String = "AMERICA",
    val type: String = "ECONOMY ANODIZED STEEL",
) {
    init {
        require(nation.isNotBlank() && region.isNotBlank() && type.isNotBlank()) {
            "Q8 substitution values must not be blank"
        }
    }
}

data class Q9Params(
    /** §2.4.9.4. */
    val color: String = "green",
) {
    init {
        require(color.isNotBlank()) { "Q9 COLOR must not be blank" }
    }
}

data class Q10Params(
    /** §2.4.10.4, stored as an epoch day like the generated rows. */
    val date: Int = tpchDate("1993-10-01"),
)

data class Q12Params(
    /** §2.4.12.4. */
    val shipMode1: String = "MAIL",
    val shipMode2: String = "SHIP",
    val date: Int = tpchDate("1994-01-01"),
) {
    init {
        require(shipMode1.isNotBlank() && shipMode2.isNotBlank()) {
            "Q12 ship modes must not be blank"
        }
        require(shipMode1 != shipMode2) { "Q12 ship modes must differ" }
    }
}

data class Q14Params(
    /** §2.4.14.4, stored as an epoch day like the generated rows. */
    val date: Int = tpchDate("1995-09-01"),
)

data class Q16Params(
    /** §2.4.16.4. */
    val brand: String = "Brand#45",
    /** The first two syllables from §2.4.16.4. */
    val type: String = "MEDIUM POLISHED",
    val sizes: List<Int> = listOf(49, 14, 23, 45, 19, 3, 36, 9),
) {
    init {
        require(brand.isNotBlank() && type.isNotBlank()) { "Q16 values must not be blank" }
        require(sizes.size == 8 && sizes.all { it in 1..50 } && sizes.toSet().size == sizes.size) {
            "Q16 SIZE1..SIZE8 must be eight distinct values in [1, 50]"
        }
    }

    /** Name used by the query-plan descriptions for the same substitution. */
    val typePrefix: String
        get() = type
}

data class Q19Params(
    /** §2.4.19.4. */
    val quantity1: Long = 1L,
    val quantity2: Long = 10L,
    val quantity3: Long = 20L,
    val brand1: String = "Brand#12",
    val brand2: String = "Brand#23",
    val brand3: String = "Brand#34",
) {
    init {
        require(quantity1 in 1L..10L) { "Q19 QUANTITY1 must be in [1, 10]" }
        require(quantity2 in 10L..20L) { "Q19 QUANTITY2 must be in [10, 20]" }
        require(quantity3 in 20L..30L) { "Q19 QUANTITY3 must be in [20, 30]" }
        require(listOf(brand1, brand2, brand3).all(String::isNotBlank)) {
            "Q19 brands must not be blank"
        }
    }
}

/** Zero-padded aliases match the query enum names while the short names stay ergonomic. */
typealias Q01Params = Q1Params
typealias Q03Params = Q3Params
typealias Q04Params = Q4Params
typealias Q05Params = Q5Params
typealias Q06Params = Q6Params
typealias Q07Params = Q7Params
typealias Q08Params = Q8Params
typealias Q09Params = Q9Params

internal fun tpchDate(isoDate: String): Int = LocalDate.parse(isoDate).toEpochDay().toInt()
