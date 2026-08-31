package civictech.query.ast

import java.io.Serializable

/**
 * Closed aggregate-function vocabulary for a rule head annotation (`[QRY1-LANG-03]` fixes
 * this exact list — count, sum, avg, min, max, topK, collectToSet — and no other). Surface
 * acceptance of aggregate syntax is the parser feature's; this is only the data vocabulary.
 */
enum class AggregateKind {
    COUNT, SUM, AVG, MIN, MAX, TOP_K, COLLECT_TO_SET
}

/**
 * A rule head's aggregate annotation. [kind] is one of the closed [AggregateKind] set; [k]
 * is populated only for [AggregateKind.TOP_K] (its `k` parameter) and must be `null` for
 * every other kind — validated in the constructor so the two can never drift apart.
 */
data class Aggregate(val kind: AggregateKind, val k: Int? = null) : Serializable {
    init {
        if (kind == AggregateKind.TOP_K) {
            require(k != null && k > 0) { "Aggregate(TOP_K) requires a positive k, got $k" }
        } else {
            require(k == null) { "Aggregate($kind) must not carry k, got $k" }
        }
    }
}
