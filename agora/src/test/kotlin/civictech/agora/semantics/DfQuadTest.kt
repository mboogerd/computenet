package civictech.agora.semantics

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DfQuadTest {

    @Test
    fun `no stances and no edges is neutral`() {
        DfQuad.combine(DfQuad.base(emptyList()), emptyList(), emptyList()) shouldBe 0.5
    }

    @Test
    fun `base is the stance mean, clamped away from the extremes`() {
        DfQuad.base(listOf(0.2, 0.4)) shouldBe (0.3 plusOrMinus 1e-12)
        DfQuad.base(listOf(0.0)) shouldBe DfQuad.BASE_FLOOR
        DfQuad.base(listOf(1.0, 1.0)) shouldBe 1 - DfQuad.BASE_FLOOR
    }

    @Test
    fun `attack lowers, support raises, both stay in bounds`() {
        val base = 0.5
        DfQuad.combine(base, listOf(0.8), emptyList()) shouldBeLessThan base
        DfQuad.combine(base, emptyList(), listOf(0.8)) shouldBeGreaterThan base
        DfQuad.combine(0.99, emptyList(), listOf(1.0)) shouldBe (1.0 plusOrMinus 1e-12)
        DfQuad.combine(0.01, listOf(1.0), emptyList()) shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `continuous at the attack-support tie`() {
        val below = DfQuad.combine(0.3, listOf(0.5), listOf(0.5 - 1e-9))
        val above = DfQuad.combine(0.3, listOf(0.5), listOf(0.5 + 1e-9))
        below shouldBe (above plusOrMinus 1e-6)
        DfQuad.combine(0.3, listOf(0.5), listOf(0.5)) shouldBe (0.3 plusOrMinus 1e-12)
    }

    @Test
    fun `probabilistic sum is order-free and monotone`() {
        DfQuad.combine(0.5, listOf(0.3, 0.7, 0.2), emptyList()) shouldBe
            (DfQuad.combine(0.5, listOf(0.7, 0.2, 0.3), emptyList()) plusOrMinus 1e-12)
        DfQuad.combine(0.5, emptyList(), listOf(0.3, 0.4)) shouldBeGreaterThan
            DfQuad.combine(0.5, emptyList(), listOf(0.3))
    }

    @Test
    fun `single-cycle loop gain stays below one`() {
        // |∂combine/∂energy| ≤ max(base, 1-base) ≤ 1 - BASE_FLOOR: a disturbance
        // through one lap of any cycle shrinks. Probe numerically at the floor.
        val base = 1 - DfQuad.BASE_FLOOR
        val d = 1e-6
        val gain = (DfQuad.combine(base, listOf(0.5 + d), emptyList()) -
            DfQuad.combine(base, listOf(0.5), emptyList())) / d
        kotlin.math.abs(gain) shouldBeLessThan 1.0
    }
}
