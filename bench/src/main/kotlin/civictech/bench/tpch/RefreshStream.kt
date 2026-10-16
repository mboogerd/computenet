package civictech.bench.tpch

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.SplittableRandom

/** One pure refresh batch; RF1 and RF2 never interleave within a batch. */
sealed interface RefreshBatch {
    data class Rf1(
        val orders: List<Orders>,
        val lineitems: List<Lineitem>,
    ) : RefreshBatch {
        init {
            require(orders.map { it.orderKey }.distinct().size == orders.size) {
                "RF1 orders must have distinct order keys"
            }
            require(lineitems.all { line -> orders.any { it.orderKey == line.orderKey } }) {
                "RF1 lineitems must refer to an order in the same batch"
            }
        }

        val lineItems: List<Lineitem>
            get() = lineitems
    }

    data class Rf2(
        val orderkeys: List<Long>,
    ) : RefreshBatch {
        init {
            require(orderkeys.zipWithNext().all { (left, right) -> left < right }) {
                "RF2 order keys must be strictly ascending"
            }
        }

        val orderKeys: List<Long>
            get() = orderkeys
    }
}

/** Deterministic TPC-H §2.6/§2.7 refresh stream generation. */
object RefreshStream {

    private val currentDate = LocalDate.parse("1995-06-17").toEpochDay().toInt()

    /**
     * Generate the requested refresh batches from [base]. The generator keeps a
     * monotonic set of allocated keys, so deleting a row never makes its sparse
     * order-key slot eligible for reuse later in the same stream.
     */
    fun generate(plan: RefreshPlan, base: TpchTables): List<RefreshBatch> {
        require(plan.sf.isFinite() && plan.sf > 0.0) { "refresh scale factor must be positive" }
        val random = SplittableRandom(plan.seed)
        val allocator = OrderKeyAllocator(base.orders.map { it.orderKey })
        val liveKeys = base.orders.mapTo(sortedSetOf()) { it.orderKey }
        val batches = ArrayList<RefreshBatch>(plan.batches.size)

        plan.batches.forEach { kind ->
            val batchRandom = random.split()
            when (kind) {
                RefreshKind.RF1 -> {
                    val orders = ArrayList<Orders>(plan.ordersPerRefresh)
                    val lineitems = ArrayList<Lineitem>(plan.ordersPerRefresh * 4)
                    repeat(plan.ordersPerRefresh) {
                        val orderKey = allocator.next()
                        val orderAndLines = newOrder(orderKey, base, batchRandom)
                        orders += orderAndLines.first
                        lineitems += orderAndLines.second
                        liveKeys += orderKey
                    }
                    batches += RefreshBatch.Rf1(orders, lineitems)
                }

                RefreshKind.RF2 -> {
                    val orderKeys = liveKeys.take(plan.ordersPerRefresh)
                    orderKeys.forEach(liveKeys::remove)
                    batches += RefreshBatch.Rf2(orderKeys)
                }
            }
        }
        return batches
    }

    private fun newOrder(
        orderKey: Long,
        base: TpchTables,
        random: SplittableRandom,
    ): Pair<Orders, List<Lineitem>> {
        require(base.customer.isNotEmpty()) { "RF1 requires at least one customer" }
        require(base.part.isNotEmpty()) { "RF1 requires at least one part" }
        require(base.supplier.isNotEmpty()) { "RF1 requires at least one supplier" }

        val customer = base.customer[random.nextInt(base.customer.size)]
        val orderDate = base.orders.minOfOrNull { it.orderDate }?.let { min ->
            val max = base.orders.maxOfOrNull { it.orderDate } ?: min
            if (max == min) min else random.nextInt(min, max + 1)
        } ?: LocalDate.parse("1992-01-01").toEpochDay().toInt()
        val lineCount = random.nextInt(1, 8)
        val lines = (1..lineCount).map { lineNumber ->
            val part = base.part[random.nextInt(base.part.size)]
            val suppliers = base.partSupp.filter { it.partKey == part.partKey }
            val suppKey = if (suppliers.isEmpty()) {
                base.supplier[random.nextInt(base.supplier.size)].suppKey
            } else {
                suppliers[random.nextInt(suppliers.size)].suppKey
            }
            val quantity = random.nextLong(1L, 51L)
            val shipDate = orderDate + random.nextInt(1, 122)
            val commitDate = orderDate + random.nextInt(30, 91)
            val receiptDate = shipDate + random.nextInt(1, 31)
            val discount = random.nextInt(0, 11)
            val tax = random.nextInt(0, 9)
            Lineitem(
                orderKey = orderKey,
                partKey = part.partKey,
                suppKey = suppKey,
                lineNumber = lineNumber,
                quantity = quantity,
                extendedPrice = part.retailPrice * quantity,
                discount = discount,
                tax = tax,
                returnFlag = if (receiptDate <= currentDate) {
                    if (random.nextBoolean()) "R" else "A"
                } else {
                    "N"
                },
                lineStatus = if (shipDate > currentDate) "O" else "F",
                shipDate = shipDate,
                commitDate = commitDate,
                receiptDate = receiptDate,
                shipInstruct = listOf("DELIVER IN PERSON", "COLLECT COD", "NONE", "TAKE BACK RETURN")[
                    random.nextInt(4)
                ],
                shipMode = listOf("REG AIR", "AIR", "RAIL", "SHIP", "TRUCK", "MAIL", "FOB")[
                    random.nextInt(7)
                ],
                comment = "refresh-${random.nextLong().toULong().toString(16)}",
            )
        }
        val totalPrice = roundToCents(lines.sumOf { TpchDecimal.chargeS6(it.extendedPrice, it.discount, it.tax) })
        val status = when {
            lines.all { it.lineStatus == "F" } -> "F"
            lines.all { it.lineStatus == "O" } -> "O"
            else -> "P"
        }
        return Orders(
            orderKey = orderKey,
            custKey = customer.custKey,
            orderStatus = status,
            totalPrice = totalPrice,
            orderDate = orderDate,
            orderPriority = listOf("1-URGENT", "2-HIGH", "3-MEDIUM", "4-NOT SPECIFIED", "5-LOW")[
                random.nextInt(5)
            ],
            clerk = "Clerk#${random.nextInt(1, 1_001).toString().padStart(9, '0')}",
            shipPriority = 0,
            comment = "refresh-${random.nextLong().toULong().toString(16)}",
        ) to lines
    }

    private fun roundToCents(scale6Value: Long): Long = BigDecimal.valueOf(scale6Value)
        .movePointLeft(6)
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()

    private class OrderKeyAllocator(baseKeys: Collection<Long>) {
        private val used = baseKeys.toMutableSet()
        private var block = 0L
        private var slot = 9

        fun next(): Long {
            while (true) {
                val candidate = Math.addExact(Math.multiplyExact(block, 32L), slot.toLong())
                slot++
                if (slot == 33) {
                    slot = 9
                    block++
                }
                if (used.add(candidate)) return candidate
            }
        }
    }
}
