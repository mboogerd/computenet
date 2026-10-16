package civictech.bench.tpch

import civictech.bench.tpch.queries.MaintainedQuery
import civictech.bench.tpch.queries.Queries
import civictech.bench.tpch.queries.QueryGraphBuilder
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyCarryingRowsTest {

    @Test
    fun `every registered query has a source key specification`() {
        assertTrue(
            SOURCE_KEYS.keys.containsAll(TpchQuery.entries.filter { Queries.builderFor(it) != null }),
            "every registered query needs a source-key specification",
        )
    }

    @Test
    fun `every registered fold input carries every source primary key`() {
        val failures = TpchQuery.entries.mapNotNull { query ->
            val builder = Queries.builderFor(query) ?: return@mapNotNull null
            val missing = missingKeys(builder.foldInputTypes(), SOURCE_KEYS.getValue(query))
            missing.takeIf { it.isNotEmpty() }?.let { query.name to it }
        }

        assertEquals(emptyList<Pair<String, Set<String>>>(), failures)
    }

    @Test
    fun `guard rejects a fold input that drops line number`() {
        val missing = missingKeys(
            FakeKeyDroppingBuilder().foldInputTypes(),
            listOf(required("LINEITEM.orderkey", "orderKey"), required("LINEITEM.linenumber", "lineNumber")),
        )

        assertEquals(setOf("LINEITEM.linenumber"), missing)
    }

    private fun missingKeys(
        foldInputTypes: List<KClass<*>>,
        requiredKeys: List<RequiredKey>,
    ): Set<String> = foldInputTypes.flatMapTo(linkedSetOf()) { inputType ->
        val fields = inputType.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .mapTo(linkedSetOf()) { it.name }
        requiredKeys.filterNot { key -> key.acceptedNames.any(fields::contains) }
            .map { key -> key.label }
    }

    private data class RequiredKey(
        val label: String,
        val acceptedNames: Set<String>,
    )

    private data class KeyDroppingFoldRow(val orderKey: Long)

    private class FakeKeyDroppingBuilder : QueryGraphBuilder(KeyDroppingFoldRow::class.java) {
        override val query: TpchQuery = TpchQuery.Q01
        override val referenceParams: Any = Q1Params()

        override fun attach(host: Use<HostManagementApi>, base: BaseRelations): MaintainedQuery =
            error("the reflection guard never attaches its fake graph")
    }

    companion object {
        private fun required(label: String, vararg acceptedNames: String): RequiredKey =
            RequiredKey(label, acceptedNames.toSet())

        /** The SQL source lists pinned by each maintained query's query-plan KDoc. */
        private val SOURCE_KEYS: Map<TpchQuery, List<RequiredKey>> = mapOf(
            TpchQuery.Q01 to listOf(
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
            TpchQuery.Q03 to listOf(
                required("CUSTOMER.custkey", "custKey"),
                required("ORDERS.orderkey", "orderKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
            TpchQuery.Q04 to listOf(
                required("ORDERS.orderkey", "orderKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
            TpchQuery.Q05 to listOf(
                required("REGION.regionkey", "regionKey"),
                required("NATION.nationkey", "nationKey"),
                required("CUSTOMER.custkey", "custKey"),
                required("ORDERS.orderkey", "orderKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
                required("SUPPLIER.suppkey", "suppKey"),
            ),
            TpchQuery.Q06 to listOf(
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
            TpchQuery.Q07 to listOf(
                required("SUPPLIER.suppkey", "suppKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
                required("ORDERS.orderkey", "orderKey"),
                required("CUSTOMER.custkey", "custKey"),
                required("NATION.nationkey", "nationKey", "supplierNationKey", "custNationKey"),
            ),
            TpchQuery.Q08 to listOf(
                required("PART.partkey", "partKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
                required("SUPPLIER.suppkey", "suppKey"),
                required("ORDERS.orderkey", "orderKey"),
                required("CUSTOMER.custkey", "custKey"),
                required("NATION.nationkey", "nationKey", "supplierNationKey"),
                // Q08 currently carries the customer region key in this reviewed legacy field name.
                required("REGION.regionkey", "regionKey", "custNationKey"),
            ),
            TpchQuery.Q09 to listOf(
                required("PART.partkey", "partKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
                required("PARTSUPP.partkey", "partKey"),
                required("PARTSUPP.suppkey", "suppKey"),
                required("SUPPLIER.suppkey", "suppKey"),
                required("ORDERS.orderkey", "orderKey"),
                required("NATION.nationkey", "nationKey"),
            ),
            TpchQuery.Q10 to listOf(
                required("CUSTOMER.custkey", "custKey"),
                required("ORDERS.orderkey", "orderKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
                required("NATION.nationkey", "nationKey"),
            ),
            TpchQuery.Q12 to listOf(
                required("ORDERS.orderkey", "orderKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
            TpchQuery.Q14 to listOf(
                required("PART.partkey", "partKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
            TpchQuery.Q16 to listOf(
                required("PART.partkey", "partKey"),
                required("PARTSUPP.partkey", "partKey"),
                required("PARTSUPP.suppkey", "suppKey"),
                required("SUPPLIER.suppkey", "suppKey"),
            ),
            TpchQuery.Q19 to listOf(
                required("PART.partkey", "partKey"),
                required("LINEITEM.orderkey", "orderKey"),
                required("LINEITEM.linenumber", "lineNumber"),
            ),
        )

    }
}
