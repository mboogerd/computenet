package civictech.cell.host

import civictech.cell.BoundaryDenial
import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation

/**
 * A failed or undeliverable invocation, emitted on the host's `deadLetterOutlet`
 * instead of being silently dropped (G-26 minimal; supervision policies are M3).
 *
 * @property cause null for drops (unknown target) AND for a [denial] — a
 *   `BoundaryPolicy` refusal is not a fault, so it never carries a thrown
 *   exception either. Before [denial] existed, that made the two
 *   indistinguishable to a subscriber except by parsing [description]'s
 *   string prefix (follow-up from `computenet-usd.6`, filed as
 *   `computenet-usd.7`); this field is the structural discriminator.
 * @property invocation the undeliverable hosted invocation, where one exists
 * @property denial non-null exactly when this record reports a `BoundaryPolicy`
 *   refusal ([DeadLetters.boundaryDenial]); null for every other dead letter,
 *   fault or drop alike. Additive and default-valued, so every existing
 *   reader of this outlet (`civictech.inspect.Errors`, concord's
 *   `KernelDriver`, any subscribed cell) keeps compiling and behaving
 *   unchanged unless it opts in to reading this field.
 */
data class DeadLetter(
    val hostRef: CellRef,
    val cause: Throwable?,
    val description: String,
    val invocation: HostedPortInvocation? = null,
    val denial: BoundaryDenial? = null,
)
