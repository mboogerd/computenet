package civictech.cell.host

import civictech.cell.BoundaryDenial
import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Redacted
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
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
    private fun publish(
        cause: Throwable?,
        description: String,
        invocation: HostedPortInvocation?,
        denial: BoundaryDenial? = null,
    ) = emit(DeadLetter(hostRef, cause, description, invocation?.let(::sanitizeForDeadLetter), denial))

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
     * Inspector's ring, `KernelDriver`, any subscribed cell: a denial arrives
     * there as a `DeadLetter` with a null `cause`, a description beginning
     * `boundary denial at exposure`, **and** [DeadLetter.denial] populated with
     * this very [BoundaryDenial] — the structurally self-describing
     * discriminator that was left as follow-up here (`computenet-usd.6`) and
     * closed by `computenet-usd.7`. That record is the audit trail; the
     * counter beside it is not.
     */
    internal fun boundaryDenial(cellRef: CellRef, denial: BoundaryDenial, deniedArgs: List<Any?>) {
        val type = when (denial.seam) {
            // computenet-usd.4.1: seam 1 refuses the raw frame before
            // WireCodec.decode runs, so — unlike every other seam below —
            // there is no real Invocation to classify; what kind of port
            // call it would have been is unknowable by construction. Of the
            // three Types, PORT_MANAGEMENT is the deliberate pick, not a
            // confident fit: it groups ADMISSION with LINK_AUTHORITY as the
            // same *kind* of refusal ("can this peer touch this cell/link at
            // all", spec 43 mechanisms 2 vs. `linkAuthority` — a
            // connection-admission gate, not a data (PORT_API) or metadata
            // (PORT_PROTOCOL) delivery). PORT_API and PORT_PROTOCOL are
            // wrong on their face: both name a specific decoded call shape
            // this record never has. Nothing downstream branches on this
            // field to decide behavior (`sanitizeForDeadLetter` treats all
            // three identically); it is read-only app-visible classification
            // on the dead-letter's `invocation.type`, so an imprecise label
            // here costs an operator's audit reading, not correctness.
            BoundarySeam.ADMISSION -> HostedPortInvocation.Type.PORT_MANAGEMENT
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
            denial = denial,
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
     *
     * ## Exclusives *nested inside* a captured wrapper (computenet-c0gz)
     *
     * The top-level substitution is not the whole obligation. An `Owned` held
     * in a field of the captured value is reached by neither branch on its
     * own, and the two shapes fail differently:
     *
     * - A `Leased` whose value holds an `Owned`: the lease is released and the
     *   value is replaced by a marker, so the inner handle becomes unreachable
     *   through the record — nothing downstream can ever consume it. That is
     *   the silent drop AGENTS.md forbids, and is the sanitizer's counterpart
     *   to the one `Proxy.discharge` closed for its own walk (computenet-zyg1).
     * - An `Owned` whose value holds an `Owned`: [Owned.freeze] consumes only
     *   the outer handle and wraps the *same object graph*, so the inner
     *   handle rides into the fan-out inside the `Frozen`, contradicting this
     *   doc's own MUST NOT sentence.
     *
     * Both are closed by handing the captured value to `Proxy.discharge`,
     * whose walk is the repository's single definition of an exclusive's
     * reach — so the sanitizer consumes exactly what the compile-time
     * `carriesExclusive` scan marked the method exclusive for, no more.
     * What this does **not** do is substitute inside the graph: the inner
     * handle is *consumed*, and the (now dead) `Owned` object still travels
     * inside the `Frozen`. Rebuilding an arbitrary user type with `Frozen`
     * fields is not possible here; "no live exclusive handle" is the property
     * that holds, and it is the one the fan-out needs.
     *
     * Ordering with `Proxy.discharge` is symmetric and needs no coordination,
     * because each walk is gated on its own consumption succeeding: whichever
     * runs second finds `take()`/`release()` already done, declines to descend,
     * and books the occurrence on `Proxy.doubleDischarges` — so a nested
     * exclusive gets exactly one consumer either way. Measured both ways
     * (`LifecycleAndDeadLetterTest`, computenet-c0gz).
     */
    private fun sanitizeForDeadLetter(hostedInvocation: HostedPortInvocation): HostedPortInvocation {
        val args = hostedInvocation.invocation.args
        if (args.none { it is Owned<*> || it is Leased<*> }) return hostedInvocation
        val sanitized = args.map { arg ->
            when (arg) {
                // freeze() consumes only the OUTER handle; the frozen value's own
                // exclusives are given their consumer here (computenet-c0gz).
                is Owned<*> -> runCatching { arg.freeze() }
                    .onSuccess { frozen -> runCatching { Proxy.discharge(frozen.value) } }
                    .getOrElse { Redacted("Owned payload already consumed before capture") }
                // the released value is replaced by a marker, so an exclusive inside
                // it would have no consumer at all unless discharged here. Gated on
                // the release succeeding, exactly as `Proxy.discharge`'s own Leased
                // branch is, so an already-released lease is not walked twice.
                is Leased<*> -> Redacted("Leased payload released at dead-letter capture")
                    .also { runCatching { arg.release().also { _ -> Proxy.discharge(arg.value) } } }
                else -> arg
            }
        }
        return hostedInvocation.copy(invocation = hostedInvocation.invocation.copy(args = sanitized))
    }
}
