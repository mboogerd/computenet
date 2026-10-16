package civictech.bench.tpch

import java.util.LinkedHashMap

/**
 * Mutable, keyed reference state for the eight base TPC-H relations.
 *
 * The maps are deliberately keyed and the read views are sorted by primary key.
 * This keeps refresh generation and comparison independent of hash iteration order
 * while retaining the cheap exact-row lookup needed by RF2.
 */
class LogicalTables(base: TpchTables) {

    private val parts = keyed(base.part, Part::partKey, compareBy { it.partKey })
    private val suppliers = keyed(base.supplier, Supplier::suppKey, compareBy { it.suppKey })
    // The landed small-SF generator can emit the same (partKey, suppKey)
    // pair more than once because its scaled supplier formula wraps. Retain
    // every row while still indexing the relation by its declared key; a
    // SetCell also distinguishes these rows by their complete value.
    private val partSuppByKey = grouped(
        rows = base.partSupp,
        key = { it.partKey to it.suppKey },
        order = compareBy<PartSupp> { it.partKey }.thenBy { it.suppKey },
    )
    private val customers = keyed(base.customer, Customer::custKey, compareBy { it.custKey })
    private val ordersByKey = keyed(base.orders, Orders::orderKey, compareBy { it.orderKey })
    private val lineitemsByKey = keyed(
        rows = base.lineitem,
        key = { it.orderKey to it.lineNumber },
        order = compareBy<Lineitem> { it.orderKey }.thenBy { it.lineNumber },
    )
    private val nations = keyed(base.nation, Nation::nationKey, compareBy { it.nationKey })
    private val regions = keyed(base.region, Region::regionKey, compareBy { it.regionKey })

    /** Current rows in primary-key order. */
    val part: List<Part>
        get() = parts.values.toList()

    val supplier: List<Supplier>
        get() = suppliers.values.toList()

    val partSupp: List<PartSupp>
        get() = partSuppByKey.values.flatten()

    val partsupp: List<PartSupp>
        get() = partSupp

    val customer: List<Customer>
        get() = customers.values.toList()

    val orders: List<Orders>
        get() = ordersByKey.values.toList()

    val lineitem: List<Lineitem>
        get() = lineitemsByKey.values.toList()

    val lineItem: List<Lineitem>
        get() = lineitem

    val nation: List<Nation>
        get() = nations.values.toList()

    val region: List<Region>
        get() = regions.values.toList()

    fun order(orderKey: Long): Orders? = ordersByKey[orderKey]

    fun lineitem(orderKey: Long, lineNumber: Int): Lineitem? = lineitemsByKey[orderKey to lineNumber]

    fun lineitemsFor(orderKey: Long): List<Lineitem> =
        lineitemsByKey.values.filter { it.orderKey == orderKey }

    /** Apply an RF1 insert atomically with respect to this single-threaded reference. */
    fun applyRf1(batch: RefreshBatch.Rf1) {
        val orderKeys = batch.orders.map { it.orderKey }
        require(orderKeys.size == orderKeys.toSet().size) { "RF1 contains duplicate order keys" }
        require(orderKeys.none { it in ordersByKey }) { "RF1 reuses an order key" }
        require(batch.lineitems.all { it.orderKey in orderKeys }) {
            "RF1 lineitem refers to an order outside its batch"
        }
        val lineKeys = batch.lineitems.map { it.orderKey to it.lineNumber }
        require(lineKeys.size == lineKeys.toSet().size) { "RF1 contains duplicate lineitem keys" }
        require(lineKeys.none { it in lineitemsByKey }) { "RF1 reuses a lineitem key" }

        batch.orders.forEach { ordersByKey[it.orderKey] = it }
        batch.lineitems.forEach { lineitemsByKey[it.orderKey to it.lineNumber] = it }
    }

    /** Remove an order and all of its lineitems, in ascending batch order. */
    fun applyRf2(batch: RefreshBatch.Rf2) {
        batch.orderkeys.forEach { orderKey ->
            ordersByKey.remove(orderKey)
            lineitemsByKey.keys
                .filter { it.first == orderKey }
                .toList()
                .forEach(lineitemsByKey::remove)
        }
    }

    fun apply(batch: RefreshBatch) {
        when (batch) {
            is RefreshBatch.Rf1 -> applyRf1(batch)
            is RefreshBatch.Rf2 -> applyRf2(batch)
        }
    }

    /** A detached, key-ordered population suitable for equality comparison. */
    fun snapshot(): TpchTables = TpchTables(
        part = part,
        supplier = supplier,
        partSupp = partSupp,
        customer = customer,
        orders = orders,
        lineitem = lineitem,
        nation = nation,
        region = region,
    )

    fun counts(): Map<String, Int> = linkedMapOf(
        "part" to parts.size,
        "supplier" to suppliers.size,
        "partsupp" to partSuppByKey.values.sumOf { it.size },
        "customer" to customers.size,
        "orders" to ordersByKey.size,
        "lineitem" to lineitemsByKey.size,
        "nation" to nations.size,
        "region" to regions.size,
    )

    private fun <K, V> keyed(
        rows: List<V>,
        key: (V) -> K,
        order: Comparator<V>,
    ): LinkedHashMap<K, V> {
        val result = LinkedHashMap<K, V>(rows.size)
        rows.sortedWith(order).forEach { row ->
            check(result.put(key(row), row) == null) { "duplicate logical-table key ${key(row)}" }
        }
        return result
    }

    private fun <K, V> grouped(
        rows: List<V>,
        key: (V) -> K,
        order: Comparator<V>,
    ): LinkedHashMap<K, MutableList<V>> {
        val result = LinkedHashMap<K, MutableList<V>>()
        rows.sortedWith(order).forEach { row ->
            result.getOrPut(key(row), ::mutableListOf).add(row)
        }
        return result
    }
}
