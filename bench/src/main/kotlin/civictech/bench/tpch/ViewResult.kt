package civictech.bench.tpch

/**
 * A materialized grouped view. Values are exact scaled-Long aggregates; averages
 * and ratios are represented by their sum/count or numerator/denominator pairs.
 */
data class ViewResult(
    val columns: List<String>,
    val rows: Map<List<Any>, LongArray>,
) {
    init {
        require(columns.isNotEmpty()) { "a view must expose at least one column" }
        require(columns.all { it.isNotBlank() }) { "view column names must not be blank" }
        require(columns.toSet().size == columns.size) { "view column names must be unique" }
        rows.forEach { (key, values) ->
            require(values.size == columns.size) {
                "row $key has ${values.size} values for ${columns.size} columns"
            }
        }
    }

    /** A row wrapper that makes ordering criteria usable without exposing the map entry type. */
    data class Row(val key: List<Any>, val values: LongArray)

    /**
     * Apply presentation ordering to a view. Equal ordering values are resolved
     * by the full group key, so top-N is deterministic and comparable on both sides.
     */
    fun topN(n: Int, ordering: Comparator<Row>): ViewResult {
        require(n >= 0) { "topN requires a non-negative limit" }
        val sorted = rows.entries
            .map { Row(it.key, it.value) }
            .sortedWith(ordering.thenComparing(KEY_COMPARATOR))
            .take(n)
        return copy(rows = linkedMapOf<List<Any>, LongArray>().apply {
            sorted.forEach { row -> put(row.key, row.values) }
        })
    }

    private companion object {
        val KEY_COMPARATOR = Comparator<Row> { left, right ->
            compareKeys(left.key, right.key)
        }

        @Suppress("UNCHECKED_CAST")
        fun compareKeys(left: List<Any>, right: List<Any>): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val l = left[index]
                val r = right[index]
                val result = when {
                    l === r -> 0
                    l is Comparable<*> && l::class == r::class ->
                        (l as Comparable<Any>).compareTo(r)
                    else -> l.toString().compareTo(r.toString())
                }
                if (result != 0) return result
            }
            return left.size.compareTo(right.size)
        }
    }
}

/** Exact comparison: key sets, column names, and every Long must match. */
object ViewComparison {
    fun compare(expected: ViewResult, actual: ViewResult): Comparison {
        val differences = mutableListOf<RowDiff>()
        if (expected.columns != actual.columns) {
            val width = maxOf(expected.columns.size, actual.columns.size)
            repeat(width) { index ->
                val expectedName = expected.columns.getOrNull(index)
                val actualName = actual.columns.getOrNull(index)
                if (expectedName != actualName) {
                    differences += RowDiff(
                        key = emptyList(),
                        column = actualName ?: expectedName ?: "column[$index]",
                        expected = null,
                        actual = null,
                    )
                }
            }
        }

        val keys = LinkedHashSet<List<Any>>()
        keys.addAll(expected.rows.keys)
        keys.addAll(actual.rows.keys)
        keys.forEach { key ->
            val expectedValues = expected.rows[key]
            val actualValues = actual.rows[key]
            val width = maxOf(expected.columns.size, actual.columns.size)
            repeat(width) { index ->
                val expectedValue = expectedValues?.getOrNull(index)
                val actualValue = actualValues?.getOrNull(index)
                if (expectedValue != actualValue) {
                    differences += RowDiff(
                        key = key,
                        column = actual.columns.getOrNull(index)
                            ?: expected.columns.getOrNull(index)
                            ?: "column[$index]",
                        expected = expectedValue,
                        actual = actualValue,
                    )
                }
            }
        }
        return if (differences.isEmpty()) Comparison.Equal else Comparison.Differs(differences)
    }
}
