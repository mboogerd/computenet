package civictech.query.diag

import java.io.Serializable

/**
 * One reason a query was rejected: the [code] naming the reason, the [locus] pointing at the
 * offending source span or plan node, and [specId] naming the spec section, gap marker, or
 * roadmap item that forbids the construct — the data shape [QRY1-REJECT-02] requires.
 *
 * `RejectionCode` is landed empty (cab.1-D1), so a `Rejection` cannot be instantiated by any
 * code in this module today — the first rejection-realizing feature that adds a
 * [RejectionCode] variant is also the first to construct one.
 */
data class Rejection(
    val code: RejectionCode,
    val locus: Locus,
    val specId: String,
) : Serializable
