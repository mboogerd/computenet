package civictech.deliberate

/**
 * Model A: absolute shares for the listed positions of one issue. Credences
 * are used as weights and normalised only when their sum exceeds one. The
 * remainder `1 - shares.sum()` is the share that none of the listed positions
 * holds. This makes complementary binary positions reproduce their verdict,
 * preserves first impressions instead of sharpening their odds, and exposes
 * jointly implausible positions instead of forcing them to fill the frame.
 *
 * The object keeps its original name for source compatibility with the first
 * model-A implementation; the operation is deliberately no longer a softmax.
 */
object Softmax {
    fun shares(credences: List<Double>): List<Double> {
        if (credences.isEmpty()) return emptyList()
        val weights = credences.map { it.coerceIn(0.0, 1.0) }
        val total = maxOf(1.0, weights.sum())
        return weights.map { it / total }
    }
}
