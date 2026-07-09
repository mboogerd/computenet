package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation

/**
 * A failed or undeliverable invocation, emitted on the host's `deadLetterOutlet`
 * instead of being silently dropped (G-26 minimal; supervision policies are M3).
 *
 * @property cause null for drops (unknown target), the thrown exception otherwise
 * @property invocation the undeliverable hosted invocation, where one exists
 */
data class DeadLetter(
    val hostRef: CellRef,
    val cause: Throwable?,
    val description: String,
    val invocation: HostedPortInvocation? = null,
)
