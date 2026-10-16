package civictech.bench.tpch

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fixed-point arithmetic shared by the generator and later TPC-H query work.
 *
 * Money values are Long cents, while discounts and taxes are Int hundredths.
 * Multiplying a money value by one percentage therefore produces a scale-4
 * integer; multiplying by both tax and discount produces a scale-6 integer.
 */
object TpchDecimal {
    /**
     * The default generator refuses larger scale factors because the worst-case
     * Q1 charge sum is approximately
     * `60_000 × 50 × 2_000_000 × 110 × 108 = 7.128e16`, below Long.MAX_VALUE;
     * scaling beyond SF 0.1 is an explicitly opt-in overflow risk.
     */
    const val MAX_SAFE_SCALE_FACTOR: Double = 0.1

    /** `extendedPrice × (100 − discount)` at scale 4 (§1.3 and §2.4.1). */
    fun revenueS4(extendedPrice: Long, discount: Int): Long {
        require(discount in 0..10) { "discount must be in [0, 10] hundredths" }
        return extendedPrice * (100L - discount)
    }

    /** `extendedPrice × (100 − discount) × (100 + tax)` at scale 6 (§2.4.1). */
    fun chargeS6(extendedPrice: Long, discount: Int, tax: Int): Long {
        require(discount in 0..10) { "discount must be in [0, 10] hundredths" }
        require(tax in 0..8) { "tax must be in [0, 8] hundredths" }
        return extendedPrice * (100L - discount) * (100L + tax)
    }

    /** `extendedPrice × discount` at scale 4, used by Q6 (§2.4.6). */
    fun discountedS4(extendedPrice: Long, discount: Int): Long {
        require(discount in 0..10) { "discount must be in [0, 10] hundredths" }
        return extendedPrice * discount
    }

    /**
     * Render an integer with [scale] decimal places to the two displayed decimal
     * places required by TPC-H, using half-up rounding. Rendering is never used
     * for graph comparison.
     */
    fun render2(value: Long, scale: Int): String {
        require(scale >= 0) { "scale must be non-negative" }
        return BigDecimal.valueOf(value)
            .movePointLeft(scale)
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
    }
}
