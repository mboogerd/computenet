package civictech.cell

import civictech.cell.link.PeerStamp

/**
 * Budget vocabulary (ECO1, epic `computenet-66m`, decisions `66m-D4`/`66m-D5`)
 * — the shape of a remote-driven economic claim and the seam that charges it.
 *
 * **This file carries no policy, no clock, no persistence and no crypto**
 * (`[ECO1-BUD-03]`): it declares the claim shape, the outcome shape and the
 * charging seam only. Mechanism — buckets, refill, checkpointing — lives in
 * `:economy` (F2, `computenet-66m` D9); wiring a real ledger onto a host and
 * calling [BudgetLedger.charge] at a site is F3 (`computenet-5o1rf`). Nothing
 * in this file changes any runtime behaviour: [BudgetLedger.Unlimited] is the
 * only implementation here, and it is every host's default: the host's G-28
 * ancestor walk (`civictech.cell.host.ManagedHost`) recognises it by identity
 * and skips that scope without charging.
 *
 * **`Principal.LocalTrusted` is exempt by construction, not by a check**
 * (`[ECO1-BUD-05]`): [BudgetClaim] carries a [PeerStamp], and a `PeerStamp`
 * cannot name `LocalTrusted` — there is no constructor path from a local,
 * unstamped crossing into a claim at all. A call site charges only a
 * remote-driven crossing; a local one never constructs a [BudgetClaim] in the
 * first place.
 *
 * **Root placement mirrors [BoundaryDenials]** (`66m-D4`): every package that
 * needs to reach this seam — `membrane`, `port`, `protocol`, `link`, `host` —
 * already has a pinned edge to `civictech.cell`
 * (`kernel/src/test/resources/architecture/package-edges.txt`), so this file
 * adds no new package edge, and `cell -> link` (already pinned) is the only
 * one this file itself uses, for [PeerStamp].
 *
 * **The seam discipline is [civictech.cell.membrane.SignatureVerifier]'s**:
 * a phase-2 [BudgetLedger] replaces the *implementation* behind this
 * interface, never the shape of the seam itself.
 */

/**
 * The five classes of remote-driven claim a [BudgetLedger] can be asked to
 * charge (`66m-D5`). A call site names exactly one per claim; there is no
 * combined or "other" class.
 */
enum class ClaimClass {
    /** A remote-driven spawn admitted by the G-28 walk. */
    Spawn,

    /** A metadata-plane interest registration — a plain debit at formation (`66m-D7`). */
    Interest,

    /** A metadata-plane attention assertion. */
    Attention,

    /** A link (re-)authorization — reconnect re-link or promotion rebind (`[SEC1-10]`). */
    Link,

    /** A held reservation rather than a debit — counts toward capacity while held, released by [BudgetOutcome.Admitted.undo] (`66m-D7`). */
    Retention,
}

/**
 * One remote-driven claim against a [BudgetLedger].
 *
 * @property stamp the admission stamp this claim is attributed to — never
 *   re-resolved after construction. Carrying a [PeerStamp] rather than a
 *   membrane `Principal` is what makes `Principal.LocalTrusted` unrepresentable
 *   here (`[ECO1-BUD-05]`): there is no `PeerStamp` for a local crossing, so no
 *   constructor path can accept one.
 * @property claimClass which of the five [ClaimClass] buckets this claim charges.
 * @property key the site-derived idempotency key (`66m-D8`) — `null` where the
 *   site needs none (a spawn claim, guarded upstream by the `Cell already
 *   spawned` require). A ledger dedups by key within a bounded recent-key
 *   window per bucket.
 * @property hold `true` asks for a reservation ([ClaimClass.Retention]) rather
 *   than a debit: the price is reserved against the bucket and
 *   [BudgetOutcome.Admitted.undo] releases the hold rather than refunding a
 *   debit.
 */
data class BudgetClaim(
    val stamp: PeerStamp,
    val claimClass: ClaimClass,
    val key: Any? = null,
    val hold: Boolean = false,
)

/** The result of asking a [BudgetLedger] to [BudgetLedger.charge] a [BudgetClaim]. */
sealed interface BudgetOutcome {
    /**
     * The claim was admitted. [undo] reverses it — refunds a debit (used by
     * the ancestor walk on partial failure) or releases a hold — and must be
     * idempotent and must never throw, so a caller can invoke it more than
     * once without guarding.
     *
     * A plain `class`, not a `data class`: [BudgetLedger.Unlimited] returns
     * **one shared instance** for every claim (`[ECO1-BUD-04]`), and identity
     * equality is exactly what a caller checking "did I get the shared
     * unlimited admission" needs — a `data class`'s structural equality would
     * make two distinct `Admitted` instances with equivalent no-op `undo`s
     * compare equal, hiding the sharing rather than proving it.
     */
    class Admitted(val undo: () -> Unit) : BudgetOutcome

    /**
     * The claim was refused.
     *
     * @property reason one of [DenialReason.BUDGET_EXHAUSTED],
     *   [DenialReason.BUDGET_NOT_GRANTED] or [DenialReason.LEDGER_FAILURE] —
     *   no other [DenialReason] is a legal value here.
     * @property scope the ledger scope the claim was refused against, e.g. a
     *   host reference.
     * @property shortfall how far the claim exceeded the bucket, or `null` for
     *   [DenialReason.BUDGET_NOT_GRANTED] / [DenialReason.LEDGER_FAILURE],
     *   neither of which is a shortfall against a balance.
     * @property detail free-text specifics for the audit trail. Never another
     *   principal's balance (`[ECO1-DEN-11]`).
     */
    data class Refused(
        val reason: DenialReason,
        val scope: String,
        val shortfall: Long?,
        val detail: String? = null,
    ) : BudgetOutcome
}

/**
 * Charges one [BudgetClaim] against a budget. `fun interface` so a ledger can
 * be a lambda; the only implementation this file provides is [Unlimited].
 */
fun interface BudgetLedger {
    fun charge(claim: BudgetClaim): BudgetOutcome

    companion object {
        /**
         * Admits every claim with **one shared** [BudgetOutcome.Admitted]
         * instance whose `undo` is a no-op (`[ECO1-BUD-04]`): no allocation
         * per call, no clock read, no map. This is a host's default budget
         * — `ManagedHost(budget = …)` when none is given — and every runtime
         * behaviour stays byte-for-byte unchanged while it is in effect on
         * every scope of a chain.
         */
        val Unlimited: BudgetLedger = run {
            val admitted = BudgetOutcome.Admitted {}
            BudgetLedger { admitted }
        }
    }
}

/**
 * Implemented by cells that charge at their own seams (`CompositeCell`). The
 * host attaches its hierarchy-walking ledger at spawn, exactly as it attaches
 * the `BoundaryDenialReporter`; it attaches nothing when its ledger is
 * [BudgetLedger.Unlimited]. `civictech.cell.membrane.CompositeCell` is the
 * implementer (ECO1 F3, `computenet-5o1rf`): it charges remote [Attention
 * assertions][ClaimClass.Attention] at its `protocolAuthority` seam.
 */
interface BudgetCharging {
    fun attachBudget(ledger: BudgetLedger)
}
