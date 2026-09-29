package civictech.cell

/**
 * The charge-and-account plumbing every budget call site shares (ECO1 F3,
 * `computenet-5o1rf`, decision `5o1rf-D3`). A sibling of [Budget.kt][BudgetLedger]
 * rather than part of it, so the vocabulary and the plumbing that uses it can
 * move independently. Imports only `civictech.cell` (and, through [BudgetClaim],
 * the already-pinned `cell -> link` edge): no new package edge (`66m-D4`).
 */

/**
 * Charges [claim] against this ledger, turning a throwing ledger into a
 * refusal (`[ECO1-CHG-10]`): a ledger that throws — a store that is down, a
 * bug in a phase-2 implementation — refuses the claim with
 * [DenialReason.LEDGER_FAILURE] at [scope], `shortfall = null`, and the
 * exception's simple class name as `detail`. The failure is **closed**: a
 * ledger that cannot answer never admits. [VirtualMachineError] is rethrown —
 * a dying JVM is not a budget decision.
 *
 * Every budget call site charges through this function, so the fail-closed
 * rule is uniform rather than re-decided per site.
 */
internal fun BudgetLedger.chargeOrFail(claim: BudgetClaim, scope: String): BudgetOutcome =
    try {
        charge(claim)
    } catch (t: VirtualMachineError) {
        throw t
    } catch (t: Throwable) {
        BudgetOutcome.Refused(DenialReason.LEDGER_FAILURE, scope, shortfall = null, detail = t.javaClass.simpleName)
    }

/**
 * Accounts one budget refusal at [seam] — **the one budget-denial helper**
 * (`[ECO1-DEN-02]`): nothing else in the kernel calls [BoundaryDenialSink.deny]
 * with [DenialReason.BUDGET_EXHAUSTED], [DenialReason.BUDGET_NOT_GRANTED] or
 * [DenialReason.LEDGER_FAILURE], so every such record has the same shape.
 *
 * The record's `principal` is the claim's own stamp id, and its `detail`
 * names the claim class, the refusing scope and the shortfall
 * (`[ECO1-DEN-01]`), followed by the ledger's own detail when it gave one.
 * It never names another principal's balance (`[ECO1-DEN-11]`): only
 * [BudgetOutcome.Refused.shortfall] — how far *this* claim fell short — is
 * rendered, and a ledger's `detail` is under the same rule
 * ([BudgetOutcome.Refused.detail]).
 *
 * [deniedArgs] follow [BoundaryDenialSink.deny]'s exactly-one-discharge rule.
 */
internal fun BoundaryDenialSink.denyBudget(
    refused: BudgetOutcome.Refused,
    claim: BudgetClaim,
    seam: BoundarySeam,
    subject: String?,
    deniedArgs: List<Any?> = emptyList(),
): BoundaryDenial =
    deny(
        seam = seam,
        reason = refused.reason,
        principal = claim.stamp.id,
        subject = subject,
        detail = budgetDetail(refused, claim) + (refused.detail?.let { " $it" } ?: ""),
        deniedArgs = deniedArgs,
    )

private fun budgetDetail(refused: BudgetOutcome.Refused, claim: BudgetClaim): String =
    "class=${claim.claimClass} scope=${refused.scope} shortfall=${refused.shortfall}"

/**
 * Thrown by a call site whose refusal must surface to its caller as a failure
 * — the host's spawn (`[ECO1-CHG-04]`). An [IllegalStateException], so every
 * caller that already handles a refused spawn (the G-28 quota `check`, the
 * `spawnBound` dead-lettering `catch`) handles this one unchanged.
 *
 * Carries the [refused] outcome and the [denial] record that was accounted
 * before the throw; its message is the reason plus that record's `detail`
 * ([denyBudget] built it), so it names class, scope and shortfall
 * (`[ECO1-DEN-01]`) and never another principal's balance (`[ECO1-DEN-11]`).
 */
class BudgetRefusedException(
    val refused: BudgetOutcome.Refused,
    val denial: BoundaryDenial,
) : IllegalStateException("budget refused: reason=${refused.reason} ${denial.detail}")
