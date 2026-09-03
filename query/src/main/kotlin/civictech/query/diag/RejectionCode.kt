package civictech.query.diag

/**
 * A closed enum naming the reason a query was rejected during compilation.
 *
 * Deliberately landed EMPTY (feature decision cab.1-D1, computenet-cab.1): each variant is
 * added by the feature that realizes the refusal it names, together with that refusal's own
 * named behaviour test ([QRY1-REJECT-05]). Pre-seeding a variant here — before any feature
 * exercises the rejection it stands for — would violate that discipline from day one: a
 * variant with no behaviour test is exactly the gap [QRY1-REJECT-05] exists to forbid.
 *
 * [Rejection] pairs a [RejectionCode] with a [Locus] (offending source span or plan node)
 * and a `specId` naming the spec text, gap marker, or roadmap item that forbids the
 * construct — the data shape [QRY1-REJECT-02] requires. `RejectionCode` being empty does not
 * block that shape from existing; it blocks a [Rejection] from being *instantiated* until a
 * rejection-realizing feature adds a variant, which is intentional (see
 * `civictech.query.diag.DiagSerializationTest`'s KDoc for what is proven in the meantime).
 *
 * [QRY1-REJECT-02], [QRY1-REJECT-05]
 */
enum class RejectionCode
