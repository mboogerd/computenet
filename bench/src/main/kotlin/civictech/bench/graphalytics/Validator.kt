package civictech.bench.graphalytics

import kotlin.math.abs

/** The exact specification identified by every Graphalytics run record. */
const val SPEC_VERSION = "LDBC Graphalytics Benchmark specification v1.0.6-draft"

/** The fixed relative tolerance required by Graphalytics output validation. */
const val EPSILON = 1.0e-4

/** Comparison rules from Graphalytics specification section 2.4. */
sealed interface ComparisonRule {
    /** Integer outputs must match vertex by vertex. */
    data object ExactLong : ComparisonRule

    /** Component labels may be renamed, but must induce the same partition. */
    data object Equivalence : ComparisonRule

    /** Floating-point outputs use the fixed [EPSILON] relative tolerance. */
    data object Epsilon : ComparisonRule

    /** An infinity output is valid only when the reference is the same infinity. */
    data object Infinity : ComparisonRule
}

/** A single vertex whose output differs from the reference value. */
data class VertexDiff(
    val vertex: Long,
    val expected: Any?,
    val actual: Any?,
)

/** The result of comparing one algorithm's output with its reference output. */
sealed interface Validation {
    val rule: ComparisonRule
    val epsilonUsed: Double?

    data class Passed(
        override val rule: ComparisonRule,
        override val epsilonUsed: Double?,
    ) : Validation

    data class Failed(
        override val rule: ComparisonRule,
        override val epsilonUsed: Double?,
        val differing: List<VertexDiff>,
        val missing: Set<Long>,
        val extra: Set<Long>,
    ) : Validation
}

/** Select the specification's comparison rule for an algorithm. */
fun ruleFor(algorithm: Algorithm): ComparisonRule = when (algorithm) {
    Algorithm.BFS, Algorithm.CDLP -> ComparisonRule.ExactLong
    Algorithm.WCC -> ComparisonRule.Equivalence
    Algorithm.PR, Algorithm.LCC, Algorithm.SSSP -> ComparisonRule.Epsilon
}

/**
 * Compare raw reference tokens with the typed output of an algorithm.
 *
 * Reference tokens deliberately remain raw at the dataset boundary. Parsing here keeps
 * the comparison rule, rather than the loader, responsible for deciding whether an
 * output is integer, floating point, or a component label.
 */
fun validate(
    algorithm: Algorithm,
    expected: Map<Long, String>,
    actual: Map<Long, *>,
): Validation {
    val rule = ruleFor(algorithm)
    val missing = (expected.keys - actual.keys).toSortedSet()
    val extra = (actual.keys - expected.keys).toSortedSet()
    val commonVertices = expected.keys.intersect(actual.keys).sorted()

    val differing = when (rule) {
        ComparisonRule.ExactLong -> commonVertices.mapNotNull { vertex ->
            val reference = expected[vertex]!!.trim().toLong()
            val observed = actual[vertex]
            if (observed is Long && observed == reference) {
                null
            } else {
                VertexDiff(vertex, reference, observed)
            }
        }

        ComparisonRule.Equivalence -> equivalenceDifferences(expected, actual, commonVertices)

        ComparisonRule.Epsilon -> commonVertices.mapNotNull { vertex ->
            val reference = parseDouble(expected[vertex]!!)
            val observed = actual[vertex].asDoubleOrNull()
            if (observed != null && epsilonMatches(reference, observed)) {
                null
            } else {
                VertexDiff(vertex, reference, observed ?: actual[vertex])
            }
        }

        // Infinity is a marker used by the floating-point rule, not a rule selected for
        // an algorithm. Keeping it in the sealed hierarchy makes that exception
        // explicit and prevents a future comparison rule from silently treating it as
        // an ordinary finite value.
        ComparisonRule.Infinity -> error("Infinity is not an algorithm comparison rule")
    }

    val epsilonUsed = if (rule === ComparisonRule.Epsilon) EPSILON else null
    return if (missing.isEmpty() && extra.isEmpty() && differing.isEmpty()) {
        Validation.Passed(rule, epsilonUsed)
    } else {
        Validation.Failed(rule, epsilonUsed, differing, missing, extra)
    }
}

private fun equivalenceDifferences(
    expected: Map<Long, String>,
    actual: Map<Long, *>,
    commonVertices: List<Long>,
): List<VertexDiff> {
    val expectedLabels = commonVertices.associateWith { expected[it]!!.trim().toLong() }
    val actualLabels = commonVertices.associateWith { actual[it] as? Long }

    val expectedToActual = expectedLabels.entries
        .groupBy({ it.value }, { actualLabels[it.key] })
        .mapValues { (_, labels) -> labels.toSet() }
    val actualToExpected = actualLabels.entries
        .filter { it.value != null }
        .groupBy({ it.value!! }, { expectedLabels[it.key] })
        .mapValues { (_, labels) -> labels.toSet() }

    return commonVertices.mapNotNull { vertex ->
        val reference = expectedLabels[vertex]!!
        val observed = actualLabels[vertex]
        val forward = expectedToActual[reference]
        val reverse = observed?.let { actualToExpected[it] }
        if (observed != null && forward == setOf(observed) && reverse == setOf(reference)) {
            null
        } else {
            VertexDiff(vertex, reference, actual[vertex])
        }
    }
}

private fun parseDouble(token: String): Double = when (token.trim()) {
    "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
    "-Infinity" -> Double.NEGATIVE_INFINITY
    else -> token.trim().toDouble()
}

private fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Double -> this
    is Long -> toDouble()
    else -> null
}

private fun epsilonMatches(reference: Double, observed: Double): Boolean = when {
    reference.isInfinite() || observed.isInfinite() ->
        reference.isInfinite() && observed.isInfinite() && reference == observed
    reference == 0.0 -> observed == 0.0
    else -> abs(reference - observed) <= EPSILON * abs(reference)
}
