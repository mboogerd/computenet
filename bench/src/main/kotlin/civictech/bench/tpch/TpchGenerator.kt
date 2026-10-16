package civictech.bench.tpch

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.SplittableRandom
import kotlin.math.ceil

/**
 * Deterministic small-scale population generator for the TPC-H §4.2.3 layout.
 *
 * This is intentionally a benchmark fixture rather than a byte-compatible DBGEN
 * implementation. The fixed split order and the emitted key order are part of its
 * reproducibility contract; the comment-column substitution is recorded in
 * [deviations].
 */
object TpchGenerator {
    /** The deliberate generator deviation copied into later run records. */
    val deviations: List<String> = listOf(
        "comment columns: v-strings, not §4.2.2.14 pseudo text",
        "supplier-key collisions: probe the next unused PARTSUPP/L_SUPPKEY key modulo the supplier scale when four keys fit",
    )

    private const val START_DATE = "1992-01-01"
    private const val CURRENT_DATE = "1995-06-17"
    private const val END_DATE = "1998-12-31"
    private const val V_STRING_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!@#$%^&*"

    private val typesSyllable1 = listOf("STANDARD", "SMALL", "MEDIUM", "LARGE", "ECONOMY", "PROMO")
    private val typesSyllable2 = listOf("ANODIZED", "BURNISHED", "PLATED", "POLISHED", "BRUSHED")
    private val typesSyllable3 = listOf("TIN", "NICKEL", "BRASS", "STEEL", "COPPER")
    private val types = typesSyllable1.flatMap { first ->
        typesSyllable2.flatMap { second ->
            typesSyllable3.map { third -> "$first $second $third" }
        }
    }

    private val containers = listOf("SM", "LG", "MED", "JUMBO", "WRAP").flatMap { first ->
        listOf("CASE", "BOX", "BAG", "JAR", "PKG", "PACK", "CAN", "DRUM")
            .map { second -> "$first $second" }
    }
    private val segments = listOf("AUTOMOBILE", "BUILDING", "FURNITURE", "MACHINERY", "HOUSEHOLD")
    private val priorities = listOf("1-URGENT", "2-HIGH", "3-MEDIUM", "4-NOT SPECIFIED", "5-LOW")
    /** §4.2.2.13 list Instructions. */
    private val instructions = listOf("DELIVER IN PERSON", "COLLECT COD", "NONE", "TAKE BACK RETURN")
    private val modes = listOf("REG AIR", "AIR", "RAIL", "SHIP", "TRUCK", "MAIL", "FOB")

    private val colours = listOf(
        "almond", "antique", "aquamarine", "azure", "beige", "bisque", "black", "blanched", "blue",
        "blush", "brown", "burlywood", "burnished", "chartreuse", "chiffon", "chocolate", "coral",
        "cornflower", "cornsilk", "cream", "cyan", "dark", "deep", "dim", "dodger", "drab", "firebrick",
        "floral", "forest", "frosted", "gainsboro", "ghost", "goldenrod", "green", "grey", "honeydew",
        "hot", "indian", "ivory", "khaki", "lace", "lavender", "lawn", "lemon", "light", "lime", "linen",
        "magenta", "maroon", "medium", "metallic", "midnight", "mint", "misty", "moccasin", "navajo",
        "navy", "olive", "orange", "orchid", "pale", "papaya", "peach", "peru", "pink", "plum", "powder",
        "puff", "purple", "red", "rose", "rosy", "royal", "saddle", "salmon", "sandy", "seashell", "sienna",
        "sky", "slate", "smoke", "snow", "spring", "steel", "tan", "thistle", "tomato", "turquoise", "violet",
        "wheat", "white", "yellow",
    )

    private val nationSpecs = listOf(
        NationSpec("ALGERIA", 0),
        NationSpec("ARGENTINA", 1),
        NationSpec("BRAZIL", 1),
        NationSpec("CANADA", 1),
        NationSpec("EGYPT", 4),
        NationSpec("ETHIOPIA", 0),
        NationSpec("FRANCE", 3),
        NationSpec("GERMANY", 3),
        NationSpec("INDIA", 2),
        NationSpec("INDONESIA", 2),
        NationSpec("IRAN", 4),
        NationSpec("IRAQ", 4),
        NationSpec("JAPAN", 2),
        NationSpec("JORDAN", 4),
        NationSpec("KENYA", 0),
        NationSpec("MOROCCO", 0),
        NationSpec("MOZAMBIQUE", 0),
        NationSpec("PERU", 1),
        NationSpec("CHINA", 2),
        NationSpec("ROMANIA", 3),
        NationSpec("SAUDI ARABIA", 4),
        NationSpec("VIETNAM", 2),
        NationSpec("RUSSIA", 3),
        NationSpec("UNITED KINGDOM", 3),
        NationSpec("UNITED STATES", 1),
    )
    private val regionNames = listOf("AFRICA", "AMERICA", "ASIA", "EUROPE", "MIDDLE EAST")

    private val startDate = LocalDate.parse(START_DATE).toEpochDay().toInt()
    private val currentDate = LocalDate.parse(CURRENT_DATE).toEpochDay().toInt()
    private val endDate = LocalDate.parse(END_DATE).toEpochDay().toInt()

    /**
     * Generate one population. Cardinalities are `ceil(SF × base)`, with a minimum
     * of one for every scalable table; this keeps tiny deterministic tests useful.
     * The default guard is the D5 Long-arithmetic bound and can be explicitly
     * bypassed with [allowOverflowRisk].
     */
    fun generate(seed: Long, sf: Double, allowOverflowRisk: Boolean = false): TpchTables {
        require(sf.isFinite() && sf > 0.0) { "scale factor must be finite and positive" }
        if (!allowOverflowRisk && sf > TpchDecimal.MAX_SAFE_SCALE_FACTOR) {
            throw IllegalArgumentException(
                "scale factor $sf exceeds the default Long-arithmetic safety bound " +
                    "${TpchDecimal.MAX_SAFE_SCALE_FACTOR}; pass allowOverflowRisk=true to opt in",
            )
        }

        val supplierCount = scaledCardinality(sf, 10_000)
        val partCount = scaledCardinality(sf, 200_000)
        val customerCount = scaledCardinality(sf, 150_000)
        val clerkCount = scaledCardinality(sf, 1_000)
        val orderCount = Math.multiplyExact(customerCount, 10)
        val supplierScale = supplierCount.toLong()
        val specialSupplierCount = scaledCardinality(sf, 5)

        // One split per table, in the fixed TPC-H order. Do not use a map or a
        // table iteration here: the split schedule is the cross-process contract.
        val root = SplittableRandom(seed)
        val regionRandom = root.split()
        val nationRandom = root.split()
        val supplierRandom = root.split()
        val partRandom = root.split()
        val partSuppRandom = root.split()
        val customerRandom = root.split()
        val ordersRandom = root.split()
        val lineitemRandom = root.split()

        val region = generateRegions(regionRandom)
        val nation = generateNations(nationRandom)
        val supplier = generateSuppliers(supplierCount, specialSupplierCount, supplierRandom)
        val part = generateParts(partCount, partRandom)
        val partSupp = generatePartSupp(part, supplierScale, partSuppRandom)
        val customer = generateCustomers(customerCount, customerRandom)
        val orderAndLineitems = generateOrdersAndLineitems(
            orderCount = orderCount,
            customerCount = customerCount,
            part = part,
            supplierScale = supplierScale,
            clerkCount = clerkCount,
            ordersRandom = ordersRandom,
            lineitemRandom = lineitemRandom,
        )

        return TpchTables(
            part = part,
            supplier = supplier,
            partSupp = partSupp,
            customer = customer,
            orders = orderAndLineitems.first,
            lineitem = orderAndLineitems.second,
            nation = nation,
            region = region,
        )
    }

    /** Stable row rendering used by the fresh-JVM determinism test. */
    fun canonicalRendering(tables: TpchTables): String = buildString {
        appendRows("REGION", tables.region)
        appendRows("NATION", tables.nation)
        appendRows("SUPPLIER", tables.supplier)
        appendRows("PART", tables.part)
        appendRows("PARTSUPP", tables.partSupp)
        appendRows("CUSTOMER", tables.customer)
        appendRows("ORDERS", tables.orders)
        appendRows("LINEITEM", tables.lineitem)
    }

    /** SHA-256 of [canonicalRendering], encoded as lower-case hexadecimal. */
    fun digest(tables: TpchTables): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalRendering(tables).toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    /** Alias named after the digest algorithm for callers that prefer that vocabulary. */
    fun sha256(tables: TpchTables): String = digest(tables)

    private fun scaledCardinality(sf: Double, base: Int): Int {
        val count = ceil(sf * base.toDouble())
        require(count <= Int.MAX_VALUE) { "scaled cardinality exceeds Int.MAX_VALUE: $count" }
        return count.toInt().coerceAtLeast(1)
    }

    private fun generateRegions(random: SplittableRandom): List<Region> =
        regionNames.mapIndexed { key, name ->
            Region(key, name, randomVString(random, 31, 115))
        }

    private fun generateNations(random: SplittableRandom): List<Nation> =
        nationSpecs.mapIndexed { key, spec ->
            Nation(key, spec.name, spec.regionKey, randomVString(random, 31, 114))
        }

    private fun generateSuppliers(
        count: Int,
        specialCount: Int,
        random: SplittableRandom,
    ): List<Supplier> {
        val complaints = minOf(specialCount, count)
        val recommends = minOf(specialCount, (count - complaints).coerceAtLeast(0))
        return (1..count).map { key ->
            val index = key - 1
            val marker = when {
                index == count - 1 && recommends < specialCount ->
                    "Customer${randomVString(random, 1, 12)}Complaints " +
                        "Customer${randomVString(random, 1, 12)}Recommends"
                index < complaints -> "Customer${randomVString(random, 1, 12)}Complaints"
                index < complaints + recommends -> "Customer${randomVString(random, 1, 12)}Recommends"
                else -> null
            }
            val comment = randomVString(random, 25, 100).let { base ->
                if (marker == null) base else "$marker $base"
            }
            val nationKey = random.nextInt(0, nationSpecs.size)
            Supplier(
                suppKey = key.toLong(),
                name = "Supplier#${key.toString().padStart(9, '0')}",
                address = randomVString(random, 10, 40),
                nationKey = nationKey,
                phone = phone(random, nationKey),
                acctBal = random.nextLong(-99_999L, 999_999L + 1L),
                comment = comment,
            )
        }
    }

    private fun generateParts(count: Int, random: SplittableRandom): List<Part> =
        (1..count).map { key ->
            val manufacturer = random.nextInt(1, 6)
            Part(
                partKey = key.toLong(),
                name = randomDistinct(random, colours, 5).joinToString(" "),
                mfgr = "Manufacturer#$manufacturer",
                brand = "Brand#${manufacturer}${random.nextInt(1, 6)}",
                type = types[random.nextInt(types.size)],
                size = random.nextInt(1, 51),
                container = containers[random.nextInt(containers.size)],
                retailPrice = retailPrice(key.toLong()),
                comment = randomVString(random, 5, 22),
            )
        }

    private fun generatePartSupp(
        parts: List<Part>,
        supplierScale: Long,
        random: SplittableRandom,
    ): List<PartSupp> = parts.flatMap { part ->
        (0..3).map { i ->
            PartSupp(
                partKey = part.partKey,
                suppKey = supplierFor(part.partKey, i, supplierScale),
                availQty = random.nextInt(1, 10_000),
                supplyCost = random.nextLong(100L, 100_000L + 1L),
                comment = randomVString(random, 49, 198),
            )
        }
    }.sortedWith(compareBy<PartSupp> { it.partKey }.thenBy { it.suppKey })

    private fun generateCustomers(count: Int, random: SplittableRandom): List<Customer> =
        (1..count).map { key ->
            val nationKey = random.nextInt(0, nationSpecs.size)
            Customer(
                custKey = key.toLong(),
                name = "Customer#${key.toString().padStart(9, '0')}",
                address = randomVString(random, 10, 40),
                nationKey = nationKey,
                phone = phone(random, nationKey),
                acctBal = random.nextLong(-99_999L, 999_999L + 1L),
                mktSegment = segments[random.nextInt(segments.size)],
                comment = randomVString(random, 29, 116),
            )
        }

    private fun generateOrdersAndLineitems(
        orderCount: Int,
        customerCount: Int,
        part: List<Part>,
        supplierScale: Long,
        clerkCount: Int,
        ordersRandom: SplittableRandom,
        lineitemRandom: SplittableRandom,
    ): Pair<List<Orders>, List<Lineitem>> {
        val orders = ArrayList<Orders>(orderCount)
        val lineitems = ArrayList<Lineitem>()
        // §4.2.3: O_ORDERDATE uniformly distributed between STARTDATE and (ENDDATE - 151 days).
        val maxOrderDate = endDate - 151

        (0 until orderCount).forEach { index ->
            val orderKey = sparseOrderKey(index)
            val custKey = randomCustomerKey(ordersRandom, customerCount)
            val orderDate = ordersRandom.nextInt(startDate, maxOrderDate + 1)
            // §4.2.3: a random number of rows within [1 .. 7] in the LINEITEM table.
            val lineCount = lineitemRandom.nextInt(1, 8)
            val lines = (1..lineCount).map { lineNumber ->
                val partKey = lineitemRandom.nextLong(1L, part.size.toLong() + 1L)
                val partRow = part[partKey.toInt() - 1]
                val supplierIndex = lineitemRandom.nextInt(4)
                val quantity = lineitemRandom.nextLong(1L, 51L)
                // §4.2.3: L_SHIPDATE = O_ORDERDATE + random value [1 .. 121];
                // L_COMMITDATE = O_ORDERDATE + random value [30 .. 90];
                // L_RECEIPTDATE = L_SHIPDATE + random value [1 .. 30].
                val shipDate = orderDate + lineitemRandom.nextInt(1, 122)
                val commitDate = orderDate + lineitemRandom.nextInt(30, 91)
                val receiptDate = shipDate + lineitemRandom.nextInt(1, 31)
                // §4.2.3: L_RETURNFLAG is "R" or "A" at random if L_RECEIPTDATE <= CURRENTDATE, else "N".
                val returnFlag = if (receiptDate <= currentDate) {
                    if (lineitemRandom.nextBoolean()) "R" else "A"
                } else {
                    "N"
                }
                Lineitem(
                    orderKey = orderKey,
                    partKey = partKey,
                    suppKey = supplierFor(partKey, supplierIndex, supplierScale),
                    lineNumber = lineNumber,
                    quantity = quantity,
                    extendedPrice = partRow.retailPrice * quantity,
                    discount = lineitemRandom.nextInt(0, 11),
                    tax = lineitemRandom.nextInt(0, 9),
                    returnFlag = returnFlag,
                    // §4.2.3: L_LINESTATUS is "O" if L_SHIPDATE > CURRENTDATE, "F" otherwise.
                    lineStatus = if (shipDate > currentDate) "O" else "F",
                    shipDate = shipDate,
                    commitDate = commitDate,
                    receiptDate = receiptDate,
                    shipInstruct = instructions[lineitemRandom.nextInt(instructions.size)],
                    shipMode = modes[lineitemRandom.nextInt(modes.size)],
                    comment = randomVString(lineitemRandom, 10, 43),
                )
            }
            // §4.2.3: O_TOTALPRICE = sum (L_EXTENDEDPRICE * (1+L_TAX) * (1-L_DISCOUNT)), rounded half-up to cents (3xi.3-D6).
            val totalPrice = roundToCents(lines.sumOf { TpchDecimal.chargeS6(it.extendedPrice, it.discount, it.tax) })
            // §4.2.3: O_ORDERSTATUS is "F" if all lineitems are "F", "O" if all are "O", "P" otherwise.
            val status = when {
                lines.all { it.lineStatus == "F" } -> "F"
                lines.all { it.lineStatus == "O" } -> "O"
                else -> "P"
            }
            orders += Orders(
                orderKey = orderKey,
                custKey = custKey,
                orderStatus = status,
                totalPrice = totalPrice,
                orderDate = orderDate,
                orderPriority = priorities[ordersRandom.nextInt(priorities.size)],
                clerk = "Clerk#${ordersRandom.nextLong(1L, clerkCount.toLong() + 1L).toString().padStart(9, '0')}",
                shipPriority = 0,
                comment = randomVString(ordersRandom, 19, 78),
            )
            lineitems += lines
        }
        return orders to lineitems
    }

    /** Sparse order keys use the first eight positions of each 32-key block (§4.2.3). */
    private fun sparseOrderKey(index: Int): Long =
        ((index / 8) * 32L) + (index % 8) + 1L

    /** The §4.2.3 customer rule rejects every third customer key. */
    private fun randomCustomerKey(random: SplittableRandom, customerCount: Int): Long {
        var key: Long
        do {
            key = random.nextLong(1L, customerCount.toLong() + 1L)
        } while (key % 3L == 0L)
        return key
    }

    /** The §4.2.3 supplier mapping shared by PARTSUPP and LINEITEM. */
    private fun supplierFor(partKey: Long, supplierIndex: Int, supplierScale: Long): Long {
        require(supplierIndex in 0..3) { "supplier index must be in [0, 3]" }
        return supplierKeysFor(partKey, supplierScale)[supplierIndex]
    }

    /**
     * Applies the §4.2.3 mapping in supplier-index order, probing forward only
     * when its candidate repeats an earlier key for this part. The smallest
     * scale-factor floor can have fewer than four suppliers, so it retains the
     * formula's duplicate once every supplier key is already occupied.
     */
    private fun supplierKeysFor(partKey: Long, supplierScale: Long): List<Long> {
        require(supplierScale > 0L) { "supplier scale must be positive" }
        val used = ArrayList<Long>(4)
        return (0..3).map { supplierIndex ->
            var candidate = formulaSupplierFor(partKey, supplierIndex, supplierScale)
            while (candidate in used && used.size.toLong() < supplierScale) {
                candidate = candidate % supplierScale + 1L
            }
            used += candidate
            candidate
        }
    }

    /** The unmodified §4.2.3 PS_SUPPKEY/L_SUPPKEY formula. */
    private fun formulaSupplierFor(partKey: Long, supplierIndex: Int, supplierScale: Long): Long {
        val stride = supplierScale / 4L + (partKey - 1L) / supplierScale
        return (partKey + supplierIndex * stride) % supplierScale + 1L
    }

    /** The §4.2.3 retail-price formula, represented directly in cents. */
    private fun retailPrice(partKey: Long): Long =
        90_000L + ((partKey / 10L) % 20_001L) + 100L * (partKey % 1_000L)

    private fun roundToCents(scale6Value: Long): Long = BigDecimal.valueOf(scale6Value)
        .movePointLeft(6)
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()

    private fun phone(random: SplittableRandom, nationKey: Int): String =
        "${nationKey + 10}-${random.nextInt(100, 1_000)}-${random.nextInt(100, 1_000)}-" +
            random.nextInt(1_000, 10_000)

    private fun randomVString(random: SplittableRandom, min: Int, max: Int): String = buildString {
        repeat(random.nextInt(min, max + 1)) {
            append(V_STRING_ALPHABET[random.nextInt(V_STRING_ALPHABET.length)])
        }
    }

    private fun <T> randomDistinct(random: SplittableRandom, values: List<T>, count: Int): List<T> {
        require(count in 0..values.size) { "cannot choose $count distinct values from ${values.size}" }
        val available = values.toMutableList()
        return buildList(count) {
            repeat(count) {
                add(available.removeAt(random.nextInt(available.size)))
            }
        }
    }

    private fun <T> StringBuilder.appendRows(label: String, rows: List<T>) {
        append(label).append('\n')
        rows.forEach { append(it).append('\n') }
    }

    private data class NationSpec(val name: String, val regionKey: Int)
}
