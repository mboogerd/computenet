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
    fun `every registered query has a joined-row key rule`() {
        val registered = TpchQuery.entries.filter { Queries.builderFor(it) != null }.toSet()

        assertEquals(
            registered,
            FOLD_INPUT_RULES.keys,
            "every registered query needs an explicit joined-row key or duplicate-insensitive exemption",
        )
    }

    @Test
    fun `every registered fold input carries a key of its joined row`() {
        val failures = TpchQuery.entries.mapNotNull { query ->
            val builder = Queries.builderFor(query) ?: return@mapNotNull null
            when (val rule = FOLD_INPUT_RULES.getValue(query)) {
                is FoldInputRule.JoinedRowKey -> {
                    val missing = missingKeys(builder.foldInputTypes(), rule.requiredKeys)
                    missing.takeIf { it.isNotEmpty() }?.let {
                        MissingJoinedRowKey(query.name, rule.keySource, it)
                    }
                }
                is FoldInputRule.DuplicateInsensitive -> null
            }
        }

        assertEquals(emptyList<MissingJoinedRowKey>(), failures)
    }

    @Test
    fun `duplicate-insensitive exemptions are explicit and limited to Q16`() {
        val exemptions = FOLD_INPUT_RULES.filterValues {
            it is FoldInputRule.DuplicateInsensitive
        }

        assertEquals(setOf(TpchQuery.Q16), exemptions.keys)
        val q16 = exemptions.getValue(TpchQuery.Q16) as FoldInputRule.DuplicateInsensitive
        assertTrue(q16.reason.contains("countDistinct"), q16.reason)
        assertTrue(q16.reason.contains("existence-only"), q16.reason)
    }

    @Test
    fun `guard rejects a fold input that drops line number`() {
        val missing = missingKeys(
            FakeKeyDroppingBuilder().foldInputTypes(),
            LINEITEM_KEY,
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

    private sealed interface FoldInputRule {
        data class JoinedRowKey(
            val keySource: String,
            val requiredKeys: List<RequiredKey>,
            val semijoinExemption: String? = null,
        ) : FoldInputRule

        data class DuplicateInsensitive(val reason: String) : FoldInputRule
    }

    private data class MissingJoinedRowKey(
        val query: String,
        val keySource: String,
        val missing: Set<String>,
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

        private val LINEITEM_KEY = listOf(
            required("LINEITEM.orderkey", "orderKey"),
            required("LINEITEM.linenumber", "lineNumber"),
        )

        /**
         * The key-determining source for every registered query. A finer key covers rows reached
         * through full-primary-key FK joins; existence-only semijoin right sides add no tuple.
         */
        private val FOLD_INPUT_RULES: Map<TpchQuery, FoldInputRule> = mapOf(
            TpchQuery.Q01 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q03 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q04 to FoldInputRule.JoinedRowKey(
                keySource = "ORDERS",
                requiredKeys = listOf(required("ORDERS.orderkey", "orderKey")),
                semijoinExemption = "LINEITEM is the existence-only right side of a positive semijoin",
            ),
            TpchQuery.Q05 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q06 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q07 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q08 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q09 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q10 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q12 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q14 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
            TpchQuery.Q16 to FoldInputRule.DuplicateInsensitive(
                "Q16's only fold is countDistinct(suppKey), so duplicates cannot affect it; " +
                    "the complaint SUPPLIER is an existence-only negated-semijoin source",
            ),
            TpchQuery.Q19 to FoldInputRule.JoinedRowKey("LINEITEM", LINEITEM_KEY),
        )

    }
}
