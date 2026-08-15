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

    /**
     * **Faults only** — parks, crashes and undeliverable invocations (G-46).
     * A `BoundaryPolicy` refusal is deliberately **not** counted here; see
     * [boundaryDenial] and [boundaryDenialCount] for why the two channels are
     * separate, and read that separation as load-bearing: every existing reader
     * of this number (`ManagedHost.supervisionAccounting().deadLetters`, and
     * through it the Inspector's `counters.deadLetters` and a dozen kernel
     * assertions of the form `deadLetters shouldBe 0L`) reads it as a fault
     * count, and stays correct in the presence of a boundary policy only
     * because refusals are counted elsewhere.
     */
    val deadLetterCount: Long get() = count.get()

    /** Refusals reported through [boundaryDenial] at this host, all boundaries summed. Monotonic. */
    internal val boundaryDenialCount: Long get() = denials.get()

    /**
     * How many stderr lines [boundaryDenial] has actually written — the bounded
     * quantity, exposed so a test can assert the bound rather than the intent.
     * Grows as O(log n) in [boundaryDenialCount]; see [shouldLogDenial].
     */
    internal val boundaryDenialLogLines: Long get() = denialLogLines.get()

    private val denials = AtomicLong()
    private val denialLogLines = AtomicLong()
    private val lastLoggedDenial = AtomicLong()

    fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) {
        System.err.println("[ManagedHost ${hostRef.id}] dead letter: $description" + (cause?.let { " ($it)" } ?: ""))
        count.incrementAndGet()
        publish(cause, description, invocation)
    }

    /** Sanitize (spec 23 R8) and hand to the host's outlet. The one emission path; the one discharge site. */
    private fun publish(cause: Throwable?, description: String, invocation: HostedPortInvocation?) =
        emit(DeadLetter(hostRef, cause, description, invocation?.let(::sanitizeForDeadLetter)))

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
     *
     * ## The denial rate is set by a remote peer, so this path is metered
     *
     * Decided by `computenet-usd.6`; recorded here because this is where the
     * next reader hits it. A fault is rare *by construction* — a cell has to
     * crash — so [deadLetter] can afford one unconditional stderr line and one
     * host-wide counter increment each. A refusal is not rare by construction:
     * once `ProtocolAuthority.ratePerWindow` accounts refusals, **the rate is
     * whatever a remote peer chooses to send**. A refusal path that costs more
     * than the refused work is a denial-of-service amplifier, so this entry
     * point differs from [deadLetter] in exactly two ways:
     *
     * 1. **It counts on its own channel.** [boundaryDenialCount] moves;
     *    [deadLetterCount] does **not**. The bead offered a third option —
     *    redefine `deadLetterCount` as "faults plus refusals" and re-check its
     *    readers — and the readers refute it: `SupervisionTest`,
     *    `EffectfulInletGuardTest`, `BoundedStateReadTest`,
     *    `BoundedReadWaveNeutralityTest`, `InstanceSetBoundedReadTest`,
     *    `ShardCellBoundedReadTest`, `WatermarkCellBoundedReadTest`,
     *    `OperatorBoundedReadEdgesTest` and `InspectorObserveTest` all assert
     *    `supervisionAccounting().deadLetters` as a **fault** count (mostly
     *    `shouldBe 0L` / `shouldBe deadLettersBefore`), as does the Inspector's
     *    `counters.deadLetters` health pill. Under the merged meaning each of
     *    those becomes conditional on whether a boundary policy happened to
     *    refuse anything, which is the "silently means two things" the item was
     *    filed about. Per-boundary counting stays where `computenet-usd.1.1`
     *    put it, on [civictech.cell.BoundaryDenialSink.denialCount]; this
     *    counter is its host-wide sum, for the operator who has the host and
     *    not the membrane.
     * 2. **Its stderr line is metered, not per-refusal** ([shouldLogDenial]).
     *
     * What is deliberately **not** metered: sanitization and emission. Both are
     * O(1) per refusal and proportional to the refused work — and sanitization
     * is the *discharge* of the refused `Owned`/`Leased`, which may never be
     * skipped. Only the stderr line is the amplifier: a synchronized,
     * unbuffered write to a stream whose sink (a console, a captured log file)
     * the operator sized for faults.
     *
     * The consequence for a reader of the dead-letter **outlet** — the
     * Inspector's ring, `KernelDriver`, any subscribed cell: a denial still
     * arrives there as a `DeadLetter` with a null `cause` and a description
     * beginning `boundary denial at exposure`. That record is the audit trail;
     * the counter beside it is not. Making the record structurally
     * self-describing (a typed field on `DeadLetter`) would need
     * `host/DeadLetter.kt`, outside this item's file claim, and is left as
     * follow-up.
     */
    internal fun boundaryDenial(cellRef: CellRef, denial: BoundaryDenial, deniedArgs: List<Any?>) {
        val type = when (denial.seam) {
            BoundarySeam.LINK_AUTHORITY -> HostedPortInvocation.Type.PORT_MANAGEMENT
            BoundarySeam.PROTOCOL_AUTHORITY -> HostedPortInvocation.Type.PORT_PROTOCOL
            BoundarySeam.DISCLOSURE, BoundarySeam.INTEGRITY -> HostedPortInvocation.Type.PORT_API
        }
        val description = "boundary denial at exposure '${denial.exposure}' on $cellRef: " +
            "seam=${denial.seam}, reason=${denial.reason}, " +
            "principal=${denial.principal?.name ?: "LocalTrusted"}, " +
            "subject=${denial.subject ?: "-"}" +
            (denial.detail?.let { " ($it)" } ?: "") +
            " — refused by BoundaryPolicy (spec 40/43); a denial is not a cell fault, " +
            "no supervision policy was consulted and no wave was minted or advanced."

        val n = denials.incrementAndGet()
        if (shouldLogDenial(n)) {
            val suppressed = n - lastLoggedDenial.getAndSet(n) - 1
            denialLogLines.incrementAndGet()
            System.err.println(
                "[ManagedHost ${hostRef.id}] boundary denial #$n" +
                    (if (suppressed > 0) " ($suppressed since the previous line suppressed; " +
                        "denial logging is metered — see DeadLetters.shouldLogDenial)" else "") +
                    ": $description",
            )
        }

        publish(
            cause = null,
            description = description,
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
     * The meter: log the first [DENIAL_LOG_HEAD] refusals in full, then only
     * when the running count is a power of two. Chosen over a time-windowed
     * token bucket for two reasons — it needs **no clock**, so the bound is
     * exactly reproducible in a simulated-time test rather than asserted
     * against wall-clock flake; and it degrades in the right direction, since
     * the faster a peer sends the *smaller* the fraction of it that reaches
     * stderr, which is the property a rate-driven amplifier needs.
     *
     * The bound, stated as a number: `lines(n) = DENIAL_LOG_HEAD + floor(log2(n)) - 3`
     * for `n > DENIAL_LOG_HEAD` (the `- 3` drops the powers of two at or below
     * the head, already counted by it). A peer that lands 10^9 refusals writes
     * **34** lines, not 10^9. What the bound costs: after the head, the operator sees
     * a sample rather than every refusal — which is why each metered line
     * carries the running total and the number suppressed since the previous
     * one, and why the *unmetered* records still all reach the dead-letter
     * outlet and [boundaryDenialCount].
     */
    private fun shouldLogDenial(n: Long): Boolean = n <= DENIAL_LOG_HEAD || (n and (n - 1)) == 0L

    private companion object {
        /** Refusals logged in full before the power-of-two meter takes over. Small: enough to debug a policy, not enough to flood. */
        const val DENIAL_LOG_HEAD = 8L
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
