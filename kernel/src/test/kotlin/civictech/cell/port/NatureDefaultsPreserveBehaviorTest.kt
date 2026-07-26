package civictech.cell.port

import civictech.nature.Color
import civictech.nature.MergeClass
import civictech.nature.Monotonicity
import civictech.nature.NatureAxis
import civictech.nature.NatureMismatch
import civictech.nature.NatureVector
import civictech.nature.Ownership
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * CP-F1: the nature vocabulary is additive and the empty [NatureVector.DEFAULT]
 * *is* today's behavior; the typed [LinkResult.Rejected] stays byte-compatible
 * with every legacy `Rejected(reason)` call site and asserted string. No
 * negotiation is exercised here — that is CP-F3.
 */
class NatureDefaultsPreserveBehaviorTest {

    @Test
    fun `DEFAULT is the empty, fast-path, all-defaults vector`() {
        NatureVector.DEFAULT.isDefault.shouldBeTrue()
        NatureVector.DEFAULT.levels.isEmpty().shouldBeTrue()
        // Absent axis reads as its first-constant (today's-behavior) level.
        NatureVector.DEFAULT.level(NatureAxis.OWNERSHIP) shouldBe Ownership.SHARED
        NatureVector.DEFAULT.level(NatureAxis.MERGE_IDEMPOTENCE) shouldBe MergeClass.NON_IDEMPOTENT
        NatureVector.DEFAULT.level(NatureAxis.COLOR) shouldBe Color.PURE
        NatureVector.DEFAULT.level(NatureAxis.MONOTONICITY) shouldBe Monotonicity.NON_MONOTONE
    }

    @Test
    fun `of() with no levels returns the shared DEFAULT singleton`() {
        NatureVector.of().isDefault.shouldBeTrue()
        NatureVector.of() shouldBe NatureVector.DEFAULT
    }

    @Test
    fun `a declared axis overrides only that axis and others stay default`() {
        val v = NatureVector.of(MergeClass.IDEMPOTENT)
        v.isDefault.shouldBeFalse()
        v.level(NatureAxis.MERGE_IDEMPOTENCE) shouldBe MergeClass.IDEMPOTENT
        v.level(NatureAxis.OWNERSHIP) shouldBe Ownership.SHARED
    }

    @Test
    fun `with folds a default vector to a no-op and keeps the singleton`() {
        val v = NatureVector.of(Color.BLOCKING)
        v.with(NatureVector.DEFAULT) shouldBe v
        NatureVector.DEFAULT.with(v) shouldBe v
        // folding two axes keeps both
        val both = v.with(Ownership.EXCLUSIVE)
        both.level(NatureAxis.COLOR) shouldBe Color.BLOCKING
        both.level(NatureAxis.OWNERSHIP) shouldBe Ownership.EXCLUSIVE
    }

    @Test
    fun `level ranks are ordered so stronger subsumes weaker (default is lowest)`() {
        (MergeClass.IDEMPOTENT.rank > MergeClass.NON_IDEMPOTENT.rank).shouldBeTrue()
        (Monotonicity.MONOTONE.rank > Monotonicity.NON_MONOTONE.rank).shouldBeTrue()
        (Ownership.EXCLUSIVE.rank > Ownership.SHARED.rank).shouldBeTrue()
        NatureVector.defaultOf(NatureAxis.COLOR).rank shouldBe 0
    }

    @Test
    fun `legacy Rejected(reason) is preserved verbatim with a null mismatch`() {
        val rejected = LinkResult.Rejected("peer x is not on the allowlist (spec 43)")
        rejected.mismatch shouldBe null
        rejected.reason shouldBe "peer x is not on the allowlist (spec 43)"
        // structural equality with the explicit two-arg form
        rejected shouldBe LinkResult.Rejected(null, "peer x is not on the allowlist (spec 43)")
    }

    @Test
    fun `a typed Rejected carries the mismatch alongside a reason string`() {
        val mismatch = NatureMismatch(NatureAxis.MERGE_IDEMPOTENCE, MergeClass.NON_IDEMPOTENT, MergeClass.IDEMPOTENT)
        val rejected = LinkResult.Rejected(mismatch, "reason")
        rejected.mismatch shouldBe mismatch
        rejected.reason shouldBe "reason"
    }
}
