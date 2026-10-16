package civictech.bench.tpch

import java.time.LocalDate
import java.util.LinkedHashMap

/**
 * Independent, deliberately direct batch oracle for the hand-route TPC-H slice.
 *
 * Each query is written as the corresponding SQL joins and predicates over the
 * current table snapshot. It does not share maintained-query code or operator
 * state with the graph side. The returned columns are the contract consumed by
 * maintained views: money remains at its declared fixed scale and derived
 * averages/ratios remain exact pairs.
 */
object TpchReference {
    fun evaluate(query: TpchQuery, tables: TpchTables, params: Any = defaults(query)): ViewResult =
        when (query) {
            TpchQuery.Q01 -> q01(tables, params.requireType<Q1Params>(query))
            TpchQuery.Q03 -> q03(tables, params.requireType<Q3Params>(query))
            TpchQuery.Q04 -> q04(tables, params.requireType<Q4Params>(query))
            TpchQuery.Q05 -> q05(tables, params.requireType<Q5Params>(query))
            TpchQuery.Q06 -> q06(tables, params.requireType<Q6Params>(query))
            TpchQuery.Q07 -> q07(tables, params.requireType<Q7Params>(query))
            TpchQuery.Q08 -> q08(tables, params.requireType<Q8Params>(query))
            TpchQuery.Q09 -> q09(tables, params.requireType<Q9Params>(query))
            TpchQuery.Q10 -> q10(tables, params.requireType<Q10Params>(query))
            TpchQuery.Q12 -> q12(tables, params.requireType<Q12Params>(query))
            TpchQuery.Q14 -> q14(tables, params.requireType<Q14Params>(query))
            TpchQuery.Q16 -> q16(tables, params.requireType<Q16Params>(query))
            TpchQuery.Q19 -> q19(tables, params.requireType<Q19Params>(query))
            else -> throw UnsupportedOperationException(
                "${query.name} is not available in the hand-route reference: " +
                    query.blockedCause,
            )
        }

    fun defaults(query: TpchQuery): Any = when (query) {
        TpchQuery.Q01 -> Q1Params()
        TpchQuery.Q03 -> Q3Params()
        TpchQuery.Q04 -> Q4Params()
        TpchQuery.Q05 -> Q5Params()
        TpchQuery.Q06 -> Q6Params()
        TpchQuery.Q07 -> Q7Params()
        TpchQuery.Q08 -> Q8Params()
        TpchQuery.Q09 -> Q9Params()
        TpchQuery.Q10 -> Q10Params()
        TpchQuery.Q12 -> Q12Params()
        TpchQuery.Q14 -> Q14Params()
        TpchQuery.Q16 -> Q16Params()
        TpchQuery.Q19 -> Q19Params()
        else -> throw UnsupportedOperationException(
            "${query.name} is not available in the hand-route reference: ${query.blockedCause}",
        )
    }

    private fun q01(tables: TpchTables, params: Q1Params): ViewResult {
        val cutoff = tpchDate("1998-12-01") - params.delta
        data class Acc(
            var sumQty: Long = 0,
            var sumBasePrice: Long = 0,
            var sumDiscPrice: Long = 0,
            var sumCharge: Long = 0,
            var sumDiscount: Long = 0,
            var count: Long = 0,
        )
        val grouped = LinkedHashMap<List<Any>, Acc>()
        tables.lineitem.filter { it.shipDate <= cutoff }.forEach { line ->
            val key = listOf<Any>(line.returnFlag, line.lineStatus)
            val acc = grouped.getOrPut(key) { Acc() }
            acc.sumQty += line.quantity
            acc.sumBasePrice += line.extendedPrice
            acc.sumDiscPrice += TpchDecimal.revenueS4(line.extendedPrice, line.discount)
            acc.sumCharge += TpchDecimal.chargeS6(line.extendedPrice, line.discount, line.tax)
            acc.sumDiscount += line.discount
            acc.count++
        }
        val columns = listOf(
            "sum_qty", "sum_base_price", "sum_disc_price", "sum_charge",
            "avg_qty_sum", "avg_qty_count", "avg_price_sum", "avg_price_count",
            "avg_disc_sum", "avg_disc_count", "count_order",
        )
        val rows = grouped.mapValuesTo(LinkedHashMap()) { (_, acc) ->
            longArrayOf(
                acc.sumQty,
                acc.sumBasePrice,
                acc.sumDiscPrice,
                acc.sumCharge,
                acc.sumQty,
                acc.count,
                acc.sumBasePrice,
                acc.count,
                acc.sumDiscount,
                acc.count,
                acc.count,
            )
        }
        return ViewResult(columns, rows)
    }

    private fun q03(tables: TpchTables, params: Q3Params): ViewResult {
        val customers = tables.customer.filter { it.mktSegment == params.segment }
            .associateBy { it.custKey }
        val orders = tables.orders.asSequence()
            .filter { it.orderDate < params.date }
            .filter { customers.containsKey(it.custKey) }
            .associateBy { it.orderKey }
        val revenue = LinkedHashMap<List<Any>, Long>()
        tables.lineitem.filter { it.shipDate > params.date }.forEach { line ->
            val order = orders[line.orderKey] ?: return@forEach
            val key = listOf<Any>(order.orderKey, order.orderDate, order.shipPriority)
            revenue[key] = (revenue[key] ?: 0L) + TpchDecimal.revenueS4(line.extendedPrice, line.discount)
        }
        return ViewResult(
            columns = listOf("revenue"),
            rows = revenue.mapValuesTo(LinkedHashMap()) { (_, value) -> longArrayOf(value) },
        )
    }

    private fun q04(tables: TpchTables, params: Q4Params): ViewResult {
        val end = plusMonths(params.date, 3)
        val lateOrders = tables.lineitem.asSequence()
            .filter { it.commitDate < it.receiptDate }
            .map { it.orderKey }
            .toSet()
        val counts = LinkedHashMap<List<Any>, Long>()
        tables.orders.filter { it.orderDate >= params.date && it.orderDate < end }
            .filter { it.orderKey in lateOrders }
            .forEach { order ->
                val key = listOf<Any>(order.orderPriority)
                counts[key] = (counts[key] ?: 0L) + 1L
            }
        return globalRows(listOf("order_count"), counts)
    }

    private fun q05(tables: TpchTables, params: Q5Params): ViewResult {
        val end = plusYears(params.date, 1)
        val regions = tables.region.filter { it.name == params.region }.map { it.regionKey }.toSet()
        val nations = tables.nation.filter { it.regionKey in regions }
        val nationsByKey = nations.associateBy { it.nationKey }
        val customers = tables.customer.associateBy { it.custKey }
        val suppliers = tables.supplier.associateBy { it.suppKey }
        val orders = tables.orders.filter { it.orderDate >= params.date && it.orderDate < end }
            .associateBy { it.orderKey }
        val revenue = LinkedHashMap<List<Any>, Long>()
        tables.lineitem.forEach { line ->
            val order = orders[line.orderKey] ?: return@forEach
            val customer = customers[order.custKey] ?: return@forEach
            val supplier = suppliers[line.suppKey] ?: return@forEach
            if (customer.nationKey != supplier.nationKey) return@forEach
            val nation = nationsByKey[customer.nationKey] ?: return@forEach
            val key = listOf<Any>(nation.name)
            revenue[key] = (revenue[key] ?: 0L) + TpchDecimal.revenueS4(line.extendedPrice, line.discount)
        }
        return ViewResult(
            columns = listOf("revenue"),
            rows = revenue.mapValuesTo(LinkedHashMap()) { (_, value) -> longArrayOf(value) },
        )
    }

    private fun q06(tables: TpchTables, params: Q6Params): ViewResult {
        val end = plusYears(params.date, 1)
        val total = tables.lineitem.filter { line ->
            line.shipDate >= params.date && line.shipDate < end &&
                line.discount in (params.discount - 1)..(params.discount + 1) &&
                line.quantity < params.quantity
        }.sumOf { TpchDecimal.discountedS4(it.extendedPrice, it.discount) }
        return ViewResult(listOf("revenue"), mapOf(emptyList<Any>() to longArrayOf(total)))
    }

    private fun q07(tables: TpchTables, params: Q7Params): ViewResult {
        val nations = tables.nation.associateBy { it.nationKey }
        val customers = tables.customer.associateBy { it.custKey }
        val suppliers = tables.supplier.associateBy { it.suppKey }
        val orders = tables.orders.associateBy { it.orderKey }
        val allowed = setOf(params.nation1 to params.nation2, params.nation2 to params.nation1)
        val revenue = LinkedHashMap<List<Any>, Long>()
        tables.lineitem.filter { it.shipDate in tpchDate("1995-01-01")..tpchDate("1996-12-31") }
            .forEach { line ->
                val supplier = suppliers[line.suppKey] ?: return@forEach
                val order = orders[line.orderKey] ?: return@forEach
                val customer = customers[order.custKey] ?: return@forEach
                val supplierNation = nations[supplier.nationKey]?.name ?: return@forEach
                val customerNation = nations[customer.nationKey]?.name ?: return@forEach
                if (supplierNation to customerNation !in allowed) return@forEach
                val key = listOf<Any>(supplierNation, customerNation, year(line.shipDate))
                revenue[key] = (revenue[key] ?: 0L) + TpchDecimal.revenueS4(line.extendedPrice, line.discount)
            }
        return ViewResult(
            columns = listOf("revenue"),
            rows = revenue.mapValuesTo(LinkedHashMap()) { (_, value) -> longArrayOf(value) },
        )
    }

    private fun q08(tables: TpchTables, params: Q8Params): ViewResult {
        val regions = tables.region.filter { it.name == params.region }.map { it.regionKey }.toSet()
        val nations = tables.nation.associateBy { it.nationKey }
        val parts = tables.part.filter { it.type == params.type }.associateBy { it.partKey }
        val suppliers = tables.supplier.associateBy { it.suppKey }
        val customers = tables.customer.associateBy { it.custKey }
        val orders = tables.orders.filter {
            it.orderDate in tpchDate("1995-01-01")..tpchDate("1996-12-31")
        }.associateBy { it.orderKey }
        val sums = LinkedHashMap<Int, LongArray>()
        tables.lineitem.forEach { line ->
            if (line.partKey !in parts) return@forEach
            val supplierNation = nations[suppliers[line.suppKey]?.nationKey] ?: return@forEach
            val order = orders[line.orderKey] ?: return@forEach
            val customer = customers[order.custKey] ?: return@forEach
            val customerNation = nations[customer.nationKey] ?: return@forEach
            if (customerNation.regionKey !in regions) return@forEach
            val volume = TpchDecimal.revenueS4(line.extendedPrice, line.discount)
            val values = sums.getOrPut(year(order.orderDate)) { longArrayOf(0L, 0L) }
            if (supplierNation.name == params.nation) values[0] += volume
            values[1] += volume
        }
        return ViewResult(
            columns = listOf("share_num", "share_den"),
            rows = sums.mapKeysTo(LinkedHashMap()) { listOf<Any>(it.key) }
                .mapValuesTo(LinkedHashMap()) { (_, value) -> value },
        )
    }

    private fun q09(tables: TpchTables, params: Q9Params): ViewResult {
        val parts = tables.part.filter { params.color in it.name }.associateBy { it.partKey }
        val suppliers = tables.supplier.associateBy { it.suppKey }
        val nations = tables.nation.associateBy { it.nationKey }
        val orders = tables.orders.associateBy { it.orderKey }
        val partSupp = tables.partSupp.associateBy { it.partKey to it.suppKey }
        val profit = LinkedHashMap<List<Any>, Long>()
        tables.lineitem.forEach { line ->
            if (line.partKey !in parts) return@forEach
            val partSupply = partSupp[line.partKey to line.suppKey] ?: return@forEach
            val supplier = suppliers[line.suppKey] ?: return@forEach
            val nation = nations[supplier.nationKey] ?: return@forEach
            val order = orders[line.orderKey] ?: return@forEach
            val amount = TpchDecimal.revenueS4(line.extendedPrice, line.discount) -
                partSupply.supplyCost * line.quantity * 100L
            val key = listOf<Any>(nation.name, year(order.orderDate))
            profit[key] = (profit[key] ?: 0L) + amount
        }
        return ViewResult(
            columns = listOf("sum_profit"),
            rows = profit.mapValuesTo(LinkedHashMap()) { (_, value) -> longArrayOf(value) },
        )
    }

    private fun q10(tables: TpchTables, params: Q10Params): ViewResult {
        val end = plusMonths(params.date, 3)
        val customers = tables.customer.associateBy { it.custKey }
        val nations = tables.nation.associateBy { it.nationKey }
        val orders = tables.orders.filter { it.orderDate >= params.date && it.orderDate < end }
            .associateBy { it.orderKey }
        val revenue = LinkedHashMap<List<Any>, Long>()
        tables.lineitem.filter { it.returnFlag == "R" }.forEach { line ->
            val order = orders[line.orderKey] ?: return@forEach
            val customer = customers[order.custKey] ?: return@forEach
            val nation = nations[customer.nationKey] ?: return@forEach
            val key = listOf<Any>(
                customer.custKey, customer.name, customer.acctBal, customer.phone,
                nation.name, customer.address, customer.comment,
            )
            revenue[key] = (revenue[key] ?: 0L) + TpchDecimal.revenueS4(line.extendedPrice, line.discount)
        }
        return ViewResult(
            columns = listOf("revenue"),
            rows = revenue.mapValuesTo(LinkedHashMap()) { (_, value) -> longArrayOf(value) },
        )
    }

    private fun q12(tables: TpchTables, params: Q12Params): ViewResult {
        val end = plusYears(params.date, 1)
        val orders = tables.orders.associateBy { it.orderKey }
        val counts = LinkedHashMap<List<Any>, LongArray>()
        tables.lineitem.filter { line ->
            line.shipMode == params.shipMode1 || line.shipMode == params.shipMode2
        }.filter { it.commitDate < it.receiptDate && it.shipDate < it.commitDate }
            .filter { it.receiptDate >= params.date && it.receiptDate < end }
            .forEach { line ->
                val order = orders[line.orderKey] ?: return@forEach
                val values = counts.getOrPut(listOf<Any>(line.shipMode)) { longArrayOf(0L, 0L) }
                if (order.orderPriority == "1-URGENT" || order.orderPriority == "2-HIGH") {
                    values[0]++
                } else {
                    values[1]++
                }
            }
        return ViewResult(
            columns = listOf("high_line_count", "low_line_count"),
            rows = counts,
        )
    }

    private fun q14(tables: TpchTables, params: Q14Params): ViewResult {
        val end = plusMonths(params.date, 1)
        val parts = tables.part.associateBy { it.partKey }
        var promo = 0L
        var total = 0L
        tables.lineitem.filter { it.shipDate >= params.date && it.shipDate < end }.forEach { line ->
            val revenue = TpchDecimal.revenueS4(line.extendedPrice, line.discount)
            total += revenue
            if (parts[line.partKey]?.type?.startsWith("PROMO") == true) promo += revenue
        }
        return ViewResult(
            columns = listOf("promo_num", "promo_den"),
            rows = mapOf(emptyList<Any>() to longArrayOf(promo, total)),
        )
    }

    private fun q16(tables: TpchTables, params: Q16Params): ViewResult {
        val complaintSuppliers = tables.supplier
            .filter { COMPLAINTS.containsMatchIn(it.comment) }
            .map { it.suppKey }
            .toSet()
        val parts = tables.part.filter {
            it.brand != params.brand && !it.type.startsWith(params.type) && it.size in params.sizes
        }.associateBy { it.partKey }
        val supplierSets = LinkedHashMap<List<Any>, MutableSet<Long>>()
        tables.partSupp.filter { it.partKey in parts && it.suppKey !in complaintSuppliers }
            .forEach { partSupp ->
                val part = parts.getValue(partSupp.partKey)
                supplierSets.getOrPut(listOf<Any>(part.brand, part.type, part.size)) { linkedSetOf() }
                    .add(partSupp.suppKey)
            }
        return ViewResult(
            columns = listOf("supplier_cnt"),
            rows = supplierSets.mapValuesTo(LinkedHashMap()) { (_, suppliers) ->
                longArrayOf(suppliers.size.toLong())
            },
        )
    }

    private fun q19(tables: TpchTables, params: Q19Params): ViewResult {
        val parts = tables.part.associateBy { it.partKey }
        val total = tables.lineitem.sumOf { line ->
            val part = parts[line.partKey] ?: return@sumOf 0L
            val matches = listOf(
                part.brand == params.brand1 && part.container in setOf("SM CASE", "SM BOX", "SM PACK", "SM PKG") &&
                    line.quantity in params.quantity1..params.quantity1 + 10L && part.size in 1..5,
                part.brand == params.brand2 && part.container in setOf("MED BAG", "MED BOX", "MED PKG", "MED PACK") &&
                    line.quantity in params.quantity2..params.quantity2 + 10L && part.size in 1..10,
                part.brand == params.brand3 && part.container in setOf("LG CASE", "LG BOX", "LG PACK", "LG PKG") &&
                    line.quantity in params.quantity3..params.quantity3 + 10L && part.size in 1..15,
            ).any { it }
            if (matches && line.shipMode in setOf("AIR", "AIR REG") && line.shipInstruct == "DELIVER IN PERSON") {
                TpchDecimal.revenueS4(line.extendedPrice, line.discount)
            } else {
                0L
            }
        }
        return ViewResult(listOf("revenue"), mapOf(emptyList<Any>() to longArrayOf(total)))
    }

    private inline fun <reified T : Any> Any.requireType(query: TpchQuery): T {
        if (this is T) return this
        throw IllegalArgumentException(
            "${query.name} reference parameters must be ${when (query) {
                TpchQuery.Q01 -> Q1Params::class.simpleName
                TpchQuery.Q03 -> Q3Params::class.simpleName
                TpchQuery.Q04 -> Q4Params::class.simpleName
                TpchQuery.Q05 -> Q5Params::class.simpleName
                TpchQuery.Q06 -> Q6Params::class.simpleName
                TpchQuery.Q07 -> Q7Params::class.simpleName
                TpchQuery.Q08 -> Q8Params::class.simpleName
                TpchQuery.Q09 -> Q9Params::class.simpleName
                TpchQuery.Q10 -> Q10Params::class.simpleName
                TpchQuery.Q12 -> Q12Params::class.simpleName
                TpchQuery.Q14 -> Q14Params::class.simpleName
                TpchQuery.Q16 -> Q16Params::class.simpleName
                TpchQuery.Q19 -> Q19Params::class.simpleName
                else -> "query-specific parameters"
            }}"
        )
    }

    /** `s_comment like '%Customer%Complaints%'` (§2.4.16.1): ordered, any text between. */
    private val COMPLAINTS = Regex("Customer.*Complaints", RegexOption.DOT_MATCHES_ALL)

    private fun globalRows(columns: List<String>, rows: Map<List<Any>, Long>): ViewResult =
        ViewResult(
            columns = columns,
            rows = rows.mapValuesTo(LinkedHashMap()) { (_, value) -> longArrayOf(value) },
        )

    private fun plusMonths(epochDay: Int, months: Long): Int =
        LocalDate.ofEpochDay(epochDay.toLong()).plusMonths(months).toEpochDay().toInt()

    private fun plusYears(epochDay: Int, years: Long): Int =
        LocalDate.ofEpochDay(epochDay.toLong()).plusYears(years).toEpochDay().toInt()

    private fun year(epochDay: Int): Int = LocalDate.ofEpochDay(epochDay.toLong()).year
}
