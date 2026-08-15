package civictech.cell.host

import civictech.cell.BoundaryDenial
import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Redacted
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import java.util.concurrent.atomic.AtomicLong

/**
 * Dead-letter emission (G-26), extracted from [ManagedHost] (RS-8.3): failed
 * or undeliverable invocations are reported here instead of being silently
 * dropped, sanitized against the boundary rules (spec 23 R8, G-46) so a live
 * [Owned]/[Leased] reference never enters the fan-out [emit] outlet.
 *
 * Owned 1:1 by a `ManagedHost`. The only external coupling is [emit] —
 * `ManagedHost`'s own `deadLetterOutlet.call.propagate(...)`, since a
 * registered port belongs to the owning cell/host, not to this collaborator.
 */
internal class DeadLetters(
    private val hostRef: CellRef,
    private val emit: (DeadLetter) -> Unit,
) {

    /** Park/crash accounting (G-46): observability for exclusive payloads off the happy path. */
    private val count = AtomicLong()

    val deadLetterCount: Long get() = count.get()

    fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) {
        System.err.println("[ManagedHost ${hostRef.id}] dead letter: $description" + (cause?.let { " ($it)" } ?: ""))
        count.incrementAndGet()
        emit(DeadLetter(hostRef, cause, description, invocation?.let(::sanitizeForDeadLetter)))
    }

    /**
     * The `BoundaryPolicy` denial-accounting entry point (spec 40/43,
     * `[SEC1-26]`): the widening this collaborator offers so a membrane's
     * [civictech.cell.BoundaryDenialSink] can report a refusal **through**
     * [deadLetter] and thereby **inherit** [sanitizeForDeadLetter]'s spec-23-R8
     * rule. That inheritance is the whole point of routing denials here: there
     * is exactly one sanitizer in the process, and a second one — however
     * faithful at the time it is written — is the thing this seam exists to
     * prevent.
     *
     * A denial is not a fault (`[SEC1-29]`, BS-14): [cause] is null, no
     * supervision policy is consulted (that decision belongs to
     * [ManagedHost]'s catch around a *thrown* failure, which this path never
     * enters), and the synthesized [Invocation] carries a **null**
     * `MessageContext` — no wave is minted or advanced by reporting a refusal.
     *
     * The [Invocation] is synthetic and terminal: a dead letter is a report,
     * never re-dispatched or replayed, so `parameterTypes` is left empty
     * rather than fabricating a reflective signature for arguments that
     * sanitization is about to retype (`Owned` -> `Frozen`, `Leased` ->
     * [Redacted]).
     */
    internal fun boundaryDenial(cellRef: CellRef, denial: BoundaryDenial, deniedArgs: List<Any?>) {
        val type = when (denial.seam) {
            BoundarySeam.LINK_AUTHORITY -> HostedPortInvocation.Type.PORT_MANAGEMENT
            BoundarySeam.PROTOCOL_AUTHORITY -> HostedPortInvocation.Type.PORT_PROTOCOL
            BoundarySeam.DISCLOSURE, BoundarySeam.INTEGRITY -> HostedPortInvocation.Type.PORT_API
        }
        deadLetter(
            cause = null,
            description = "boundary denial at exposure '${denial.exposure}' on $cellRef: " +
                "seam=${denial.seam}, reason=${denial.reason}, " +
                "principal=${denial.principal?.name ?: "LocalTrusted"}, " +
                "subject=${denial.subject ?: "-"}" +
                (denial.detail?.let { " ($it)" } ?: "") +
                " — refused by BoundaryPolicy (spec 40/43); a denial is not a cell fault, " +
                "no supervision policy was consulted and no wave was minted or advanced.",
            invocation = HostedPortInvocation(
                cellRef = cellRef,
                portName = denial.exposure,
                type = type,
                invocation = Invocation(
                    methodName = denial.subject ?: denial.seam.name,
                    parameterTypes = emptyList(),
                    args = deniedArgs,
                    context = null,
                ),
            ),
        )
    }

    /**
     * Dead-letter capture applies the boundary rules (spec 23 R8, G-46): the
     * dead-letter outlet is a fan-out, so a live [Owned]/[Leased] reference
     * MUST NOT enter it. `Owned` degenerates to move-by-serialize — frozen,
     * exactly as at the bridge egress — and `Leased` is released and stands
     * in as a [Redacted] marker; the outlet only ever fans `Frozen`/redacted/
     * ordinary values, never a live exclusive handle. A wrapper the failing
     * invocation had already taken/released before throwing has nothing left
     * to capture; it is redacted with no value rather than crashing capture.
     */
    private fun sanitizeForDeadLetter(hostedInvocation: HostedPortInvocation): HostedPortInvocation {
        val args = hostedInvocation.invocation.args
        if (args.none { it is Owned<*> || it is Leased<*> }) return hostedInvocation
        val sanitized = args.map { arg ->
            when (arg) {
                is Owned<*> -> runCatching { arg.freeze() }
                    .getOrElse { Redacted("Owned payload already consumed before capture") }
                is Leased<*> -> Redacted("Leased payload released at dead-letter capture")
                    .also { runCatching { arg.release() } }
                else -> arg
            }
        }
        return hostedInvocation.copy(invocation = hostedInvocation.invocation.copy(args = sanitized))
    }
}
