package civictech.timetravel.fidelity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * TTD1 F3 (computenet-kxex2.1) D1: the total order `Faithful < Degraded < Unreconstructible`
 * and [worst]'s rank/merge behavior [TTD1-33].
 */
class FidelityOrderTest {

    @Test
    fun faithfulIsBelowDegradedIsBelowUnreconstructible() {
        val faithful: Fidelity = Fidelity.Faithful
        val degraded: Fidelity = Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
        val unreconstructible: Fidelity = Fidelity.Unreconstructible(setOf(Reason.VOLATILE_CELL))

        (faithful < degraded) shouldBe true
        (degraded < unreconstructible) shouldBe true
        (faithful < unreconstructible) shouldBe true
    }

    @Test
    fun worstIsCommutative() {
        val a: Fidelity = Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
        val b: Fidelity = Fidelity.Unreconstructible(setOf(Reason.VOLATILE_CELL))

        worst(a, b) shouldBe worst(b, a)
    }

    @Test
    fun worstMergesReasonsAcrossRanks() {
        val a: Fidelity = Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
        val b: Fidelity = Fidelity.Unreconstructible(setOf(Reason.VOLATILE_CELL))

        worst(a, b) shouldBe Fidelity.Unreconstructible(setOf(Reason.EFFECTFUL_CELL, Reason.VOLATILE_CELL))
    }

    @Test
    fun worstMergesReasonsOnTies() {
        val a: Fidelity = Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL))
        val b: Fidelity = Fidelity.Degraded(setOf(Reason.NOT_STATEFUL))

        worst(a, b) shouldBe Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL, Reason.NOT_STATEFUL))
    }

    @Test
    fun worstOfTwoFaithfulIsFaithful() {
        worst(Fidelity.Faithful, Fidelity.Faithful) shouldBe Fidelity.Faithful
    }

    @Test
    fun degradedRejectsEmptyReasons() {
        shouldThrow<IllegalArgumentException> { Fidelity.Degraded(emptySet()) }
    }

    @Test
    fun unreconstructibleRejectsEmptyReasons() {
        shouldThrow<IllegalArgumentException> { Fidelity.Unreconstructible(emptySet()) }
    }
}
