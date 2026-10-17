package civictech.bench.nexmark

data class NexmarkResult(
    val columns: List<String>,
    val rows: Set<List<Any>>,
) {
    init {
        require(columns.isNotEmpty()) { "result columns must be non-empty" }
        require(rows.all { it.size == columns.size }) {
            "every result row must have exactly ${columns.size} columns"
        }
    }
}

sealed interface Comparison {
    data object Equal : Comparison

    data class Differs(val rows: List<RowDiff>) : Comparison
}

data class RowDiff(val row: List<Any>, val side: Side)

enum class Side {
    EXPECTED_ONLY,
    ACTUAL_ONLY,
}

object ResultComparison {
    fun compare(expected: NexmarkResult, actual: NexmarkResult): Comparison {
        require(expected.columns == actual.columns) {
            "result columns differ: expected=${expected.columns}, actual=${actual.columns}"
        }
        if (expected.rows == actual.rows) return Comparison.Equal

        val differences = buildList {
            (expected.rows - actual.rows).forEach { add(RowDiff(it, Side.EXPECTED_ONLY)) }
            (actual.rows - expected.rows).forEach { add(RowDiff(it, Side.ACTUAL_ONLY)) }
        }.sortedWith(compareBy<RowDiff>({ it.row.toString() }, { it.side }))
        return Comparison.Differs(differences)
    }
}
