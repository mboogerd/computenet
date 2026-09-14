package civictech.cell.wire

import civictech.cell.DenialReason
import civictech.cell.link.UnboundReason

/**
 * The **one table** from a binding's [UnboundReason] to the seam-1
 * [DenialReason] a transport accounts the refusal under (DSC4, epic
 * `computenet-5y8t` decision D8; feature `computenet-5y8t.3`, task
 * `computenet-5y8t.3.1`).
 *
 * Both transports — `:wire`'s `WsTransport` and `:iroh`'s `IrohTransport` —
 * read this function rather than classifying locally, so the two never
 * disagree on what a reason class is called:
 *
 * - `NO_BINDING`, `NO_STATEMENT`, `ISSUER_NOT_ACCEPTED`, `BAD_SIGNATURE`,
 *   `KEY_MISMATCH` -> [DenialReason.UNVOUCHED] — the key resolved to no
 *   identity at all;
 * - `EXPIRED`, `NOT_YET_VALID` -> [DenialReason.STATEMENT_EXPIRED] — a
 *   verifying statement for this key, outside its window at the receiver's
 *   clock.
 *
 * The finer [UnboundReason] belongs in the denial's detail; this function
 * only picks the machine-readable class.
 *
 * The `when` is exhaustive with **no `else`** on purpose: a future
 * [UnboundReason] entry is a compile failure here until someone classifies
 * it, never a silent default.
 */
fun denialReasonFor(reason: UnboundReason): DenialReason = when (reason) {
    UnboundReason.NO_BINDING,
    UnboundReason.NO_STATEMENT,
    UnboundReason.ISSUER_NOT_ACCEPTED,
    UnboundReason.BAD_SIGNATURE,
    UnboundReason.KEY_MISMATCH,
    -> DenialReason.UNVOUCHED

    UnboundReason.EXPIRED,
    UnboundReason.NOT_YET_VALID,
    -> DenialReason.STATEMENT_EXPIRED
}
