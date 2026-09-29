package civictech.cell

import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import civictech.cell.membrane.Principal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `Budget.kt` (ECO1, epic `computenet-66m`, decisions `66m-D4`/`66m-D5`) — an
 * inert seam with one implementation, [BudgetLedger.Unlimited]. Nothing here
 * charges anything; F3 (`computenet-5o1rf`) is where a real ledger is wired
 * to a call site.
 */
class BudgetSeamTest {

    private fun claim(claimClass: ClaimClass, key: Any? = null, hold: Boolean = false) = BudgetClaim(
        stamp = PeerStamp(PeerId("peer-${claimClass.name}")),
        claimClass = claimClass,
        key = key,
        hold = hold,
    )

    @Test
    fun `Unlimited returns one shared Admitted instance across distinct claims`() {
        val a = BudgetLedger.Unlimited.charge(claim(ClaimClass.Spawn))
        val b = BudgetLedger.Unlimited.charge(claim(ClaimClass.Retention, key = "some-key", hold = true))

        a.shouldBeInstanceOfAdmitted()
        (a === b) shouldBe true
    }

    @Test
    fun `Unlimited's undo is a no-op and never throws, called twice`() {
        val outcome = BudgetLedger.Unlimited.charge(claim(ClaimClass.Attention))
        val admitted = outcome.shouldBeInstanceOfAdmitted()

        admitted.undo()
        admitted.undo()
    }

    @Test
    fun `ClaimClass is exactly the five 66m-D5 arms, in order`() {
        ClaimClass.entries shouldBe listOf(
            ClaimClass.Spawn,
            ClaimClass.Interest,
            ClaimClass.Attention,
            ClaimClass.Link,
            ClaimClass.Retention,
        )
    }

    @Test
    fun `DenialReason ends with the three ECO1 budget arms`() {
        DenialReason.entries.takeLast(3) shouldBe listOf(
            DenialReason.BUDGET_EXHAUSTED,
            DenialReason.BUDGET_NOT_GRANTED,
            DenialReason.LEDGER_FAILURE,
        )
    }

    @Test
    fun `BoundarySeam ends with HOST_ADMISSION`() {
        BoundarySeam.entries.last() shouldBe BoundarySeam.HOST_ADMISSION
    }

    /**
     * `BudgetClaim` carries a [PeerStamp], never a
     * [civictech.cell.membrane.Principal] — `Principal.LocalTrusted` is
     * therefore unrepresentable, not merely disallowed by a runtime check
     * (`[ECO1-BUD-05]`). Reflective rather than a compile check because the
     * property under test IS the absence of a constructor shape; this test
     * is allowed to import `Principal` (`Budget.kt` main code may not, per
     * `66m-D4`'s package-edge constraint) precisely to look for it and fail
     * if it ever appears.
     *
     * Plain `java.lang.reflect`, not `KClass.constructors`: `kotlin-reflect`
     * is not on this module's classpath (`DurabilityBackdoorFenceTest`'s
     * documented reason), and `KClass.constructors` throws
     * `KotlinReflectionNotSupportedError` without it.
     */
    @Test
    fun `no BudgetClaim constructor accepts a Principal, and every one carries a PeerStamp`() {
        val constructors = BudgetClaim::class.java.declaredConstructors
        constructors.isEmpty() shouldBe false

        constructors.forEach { ctor ->
            val paramTypes = ctor.parameterTypes.toList()
            paramTypes.contains(PeerStamp::class.java) shouldBe true
            paramTypes.contains(Principal::class.java) shouldBe false
        }
    }
}

/** Asserts [BudgetOutcome] is [BudgetOutcome.Admitted] and returns it, cast. */
private fun BudgetOutcome.shouldBeInstanceOfAdmitted(): BudgetOutcome.Admitted {
    check(this is BudgetOutcome.Admitted) { "expected Admitted, was $this" }
    return this
}
