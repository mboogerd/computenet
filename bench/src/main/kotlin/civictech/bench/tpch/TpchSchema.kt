package civictech.bench.tpch

import kotlinx.serialization.Serializable as KotlinSerializable
import java.io.Serializable

/** A row in PART; the primary key is [partKey]. */
@KotlinSerializable
data class Part(
    val partKey: Long,
    val name: String,
    val mfgr: String,
    val brand: String,
    val type: String,
    val size: Int,
    val container: String,
    val retailPrice: Long,
    val comment: String,
) : Serializable

/** A row in SUPPLIER; the primary key is [suppKey]. */
@KotlinSerializable
data class Supplier(
    val suppKey: Long,
    val name: String,
    val address: String,
    val nationKey: Int,
    val phone: String,
    val acctBal: Long,
    val comment: String,
) : Serializable

/** A row in PARTSUPP; the primary key is ([partKey], [suppKey]). */
@KotlinSerializable
data class PartSupp(
    val partKey: Long,
    val suppKey: Long,
    val availQty: Int,
    val supplyCost: Long,
    val comment: String,
) : Serializable

/** A row in CUSTOMER; the primary key is [custKey]. */
@KotlinSerializable
data class Customer(
    val custKey: Long,
    val name: String,
    val address: String,
    val nationKey: Int,
    val phone: String,
    val acctBal: Long,
    val mktSegment: String,
    val comment: String,
) : Serializable

/** A row in ORDERS; the primary key is [orderKey]. */
@KotlinSerializable
data class Orders(
    val orderKey: Long,
    val custKey: Long,
    val orderStatus: String,
    val totalPrice: Long,
    val orderDate: Int,
    val orderPriority: String,
    val clerk: String,
    val shipPriority: Int,
    val comment: String,
) : Serializable

/** A row in LINEITEM; the primary key is ([orderKey], [lineNumber]). */
@KotlinSerializable
data class Lineitem(
    val orderKey: Long,
    val partKey: Long,
    val suppKey: Long,
    val lineNumber: Int,
    val quantity: Long,
    val extendedPrice: Long,
    val discount: Int,
    val tax: Int,
    val returnFlag: String,
    val lineStatus: String,
    val shipDate: Int,
    val commitDate: Int,
    val receiptDate: Int,
    val shipInstruct: String,
    val shipMode: String,
    val comment: String,
) : Serializable

/** A row in NATION; the primary key is [nationKey]. */
@KotlinSerializable
data class Nation(
    val nationKey: Int,
    val name: String,
    val regionKey: Int,
    val comment: String,
) : Serializable

/** A row in REGION; the primary key is [regionKey]. */
@KotlinSerializable
data class Region(
    val regionKey: Int,
    val name: String,
    val comment: String,
) : Serializable

/** The fixed table order used by the deterministic generator's random splits. */
enum class TpchTable {
    REGION,
    NATION,
    SUPPLIER,
    PART,
    PARTSUPP,
    CUSTOMER,
    ORDERS,
    LINEITEM,
}

/** One generated TPC-H population, with every table emitted in primary-key order. */
@KotlinSerializable
data class TpchTables(
    val part: List<Part>,
    val supplier: List<Supplier>,
    val partSupp: List<PartSupp>,
    val customer: List<Customer>,
    val orders: List<Orders>,
    val lineitem: List<Lineitem>,
    val nation: List<Nation>,
    val region: List<Region>,
) : Serializable {
    /** Spelling retained for callers that use the SQL table name directly. */
    val partsupp: List<PartSupp>
        get() = partSupp

    /** Spelling retained for callers that use the SQL table name directly. */
    val lineItem: List<Lineitem>
        get() = lineitem
}
