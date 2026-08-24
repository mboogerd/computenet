package civictech.testkit.dst

import civictech.cell.host.DeadLetter

/**
 * Why a [DeadLetter] exists, decided from the record's **structural** fields ([CHA1-52]).
 *
 * The three cases are exhaustive over `civictech.cell.host.DeadLetter` as the kernel builds
 * it, and each is discriminated by a field rather than by a description prefix:
 *
 *  - [BOUNDARY_DENIAL] — `denial != null`. A `BoundaryPolicy` refusal, which the kernel is
 *    explicit is *not* a fault (`DeadLetters.boundaryDenial`): it carries no `cause` and is
 *    counted on its own channel.
 *  - [CELL_FAULT] — `cause != null`. Something threw and the invocation could not be applied.
 *  - [UNDELIVERABLE] — neither. A drop: no target, or an invocation the host could not route.
 *
 * **Why not the description.** `DeadLetter.description` is free text written at a dozen call
 * sites; classifying on its prefix is what the kernel added [DeadLetter.denial] to stop
 * subscribers doing (see that field's KDoc). The description is carried into the report for a
 * reader, never used to decide the reason.
 */
enum class DeadLetterReason {
    BOUNDARY_DENIAL,
    CELL_FAULT,
    UNDELIVERABLE,
}

/** One dead letter with its reason and, when a policy admitted it, the allowance that did. */
data class ClassifiedDeadLetter(
    val letter: DeadLetter,
    val reason: DeadLetterReason,
    val allowedBy: String? = null,
) {
    /** [CHA1-52]'s default: a dead letter no allowance names is *unexplained* and fails the run. */
    val unexplained: Boolean get() = allowedBy == null

    /** One line for the failure report: reason, allowance (if any), and the kernel's own words. */
    fun render(): String =
        "$reason${allowedBy?.let { " (allowed: $it)" } ?: " — UNEXPLAINED"}: ${letter.description}"
}

/**
 * One explicitly expected class of dead letter ([CHA1-52]'s per-run allow mechanism).
 *
 * An allowance is a **named** predicate. The name is not decoration: it is what the report
 * prints beside the letter it admitted, so a suite that allows something has to say, in the
 * report, why it expected it. An unnamed blanket "ignore dead letters" switch is exactly what
 * this type exists instead of.
 *
 * @param reason when non-null, the letter's reason must match.
 * @param descriptionContains when non-null, the letter's description must contain it. This is
 *   a *narrowing* filter written by the suite that expects the letter, not a classifier — the
 *   reason above is the classification, and it never reads text.
 */
data class DeadLetterAllowance(
    val label: String,
    val reason: DeadLetterReason? = null,
    val descriptionContains: String? = null,
) {
    init {
        require(label.isNotBlank()) { "an allowance is named so the report can print why a letter was expected" }
        require(reason != null || descriptionContains != null) {
            "an allowance that matches everything is not an allowance: give it a reason, a description " +
                "fragment, or both"
        }
    }

    fun admits(letter: DeadLetter, letterReason: DeadLetterReason): Boolean =
        (reason == null || reason == letterReason) &&
            (descriptionContains == null || descriptionContains in letter.description)
}

/**
 * What a run is allowed to dead-letter ([CHA1-52]).
 *
 * The default is [strict]: nothing is expected, so **every** dead letter is unexplained and
 * fails the run. That is the clause's "by default" made structural — a suite acquires
 * tolerance by writing an allowance down, never by omitting a policy.
 */
data class DeadLetterPolicy(val allowances: List<DeadLetterAllowance> = emptyList()) {

    fun allowing(vararg more: DeadLetterAllowance): DeadLetterPolicy =
        copy(allowances = allowances + more.toList())

    /** The first allowance admitting this letter, or null — the letter is then unexplained. */
    fun admittedBy(letter: DeadLetter, reason: DeadLetterReason): DeadLetterAllowance? =
        allowances.firstOrNull { it.admits(letter, reason) }

    /**
     * The [CHA1-52] check, ready to hand to a [DstRun]: reads [DstWorld.deadLetters] and fails
     * the run on the first unexplained letter.
     *
     * Registrable in [CheckRegistry] like any other check, so a failing run's artifact can name
     * it and replay it.
     */
    fun check(): DstCheck = DstCheck { world -> DeadLetterAccounting.of(world.deadLetters, this).verify() }

    companion object {
        /** Nothing is expected: any dead letter at all fails the run ([CHA1-52]'s default). */
        val strict: DeadLetterPolicy = DeadLetterPolicy()

        fun allowing(vararg allowances: DeadLetterAllowance): DeadLetterPolicy =
            DeadLetterPolicy(allowances.toList())
    }
}

/**
 * A run's dead letters, classified by reason and graded against a [DeadLetterPolicy]
 * ([CHA1-52]).
 *
 * ## What this can and cannot see
 *
 * It sees exactly what [DstWorld.deadLetters] captured: the records a **declared** host
 * emitted on its `deadLetterOutlet`. Two consequences, stated here because the report renders
 * a count and a count invites the reading that it is complete:
 *
 *  - A host the graph builder created directly, rather than through [HostSlots.declare], is
 *    not subscribed and its dead letters are invisible to this accounting. The count is
 *    "dead letters from declared hosts", not "dead letters in this JVM".
 *  - A payload lost *without* a dead letter is by definition not here. That is
 *    [ExclusiveLedger]'s question, not this one, and the two checks are deliberately separate:
 *    "everything that was dead-lettered was expected" and "nothing was lost silently" are
 *    different properties and a suite usually wants both.
 */
data class DeadLetterAccounting(
    val classified: List<ClassifiedDeadLetter>,
    val policy: DeadLetterPolicy,
) {
    val total: Int get() = classified.size

    /** Count by reason, in [DeadLetterReason] declaration order; reasons with no letters are omitted. */
    val countsByReason: Map<DeadLetterReason, Int>
        get() = DeadLetterReason.entries
            .mapNotNull { reason -> classified.count { it.reason == reason }.takeIf { it > 0 }?.let { reason to it } }
            .toMap()

    val unexplained: List<ClassifiedDeadLetter> get() = classified.filter { it.unexplained }

    /** [CHA1-50]'s "dead-letter count by reason", one line. */
    fun renderCounts(): String = when {
        classified.isEmpty() -> "0"
        else -> "$total total: " + countsByReason.entries.joinToString(", ") { "${it.key}=${it.value}" } +
            (if (unexplained.isEmpty()) "" else " (unexplained: ${unexplained.size})")
    }

    /**
     * [CHA1-52]: fail the run if any dead letter is unexplained.
     *
     * **The message is deliberately free of run-varying numbers.** A shrinker accepts a
     * reduction only when the failing check's *message* still matches
     * (`FailurePredicate.sameFailingCheck`), so a message carrying "3 unexplained dead letters"
     * would make a legitimately reduced plan look like a different failure and be discarded —
     * measured on computenet-umx.3.7. The counts and the letters themselves live in
     * [UnexplainedDeadLetters.detail], which the failure report renders and the predicate never
     * reads.
     */
    fun verify() {
        if (unexplained.isEmpty()) return
        throw UnexplainedDeadLetters(this)
    }

    companion object {

        /**
         * Classify [letters] and grade them against [policy].
         *
         * Classification is [classifyDeadLetter]'s — structural fields only — and is
         * independent of the policy: a letter is classified first and admitted second, so the
         * report's counts by reason mean the same thing whatever a suite chose to allow.
         */
        fun of(letters: List<DeadLetter>, policy: DeadLetterPolicy = DeadLetterPolicy.strict): DeadLetterAccounting =
            DeadLetterAccounting(
                letters.map { letter ->
                    val reason = classifyDeadLetter(letter)
                    ClassifiedDeadLetter(letter, reason, policy.admittedBy(letter, reason)?.label)
                },
                policy,
            )

        /** The same, from a finished run's report. */
        fun of(report: DstReport, policy: DeadLetterPolicy = DeadLetterPolicy.strict): DeadLetterAccounting =
            of(report.deadLetters, policy)
    }
}

/**
 * [CHA1-52]'s failure: at least one dead letter no allowance explains.
 *
 * [message] is stable across runs of the same failure mode (see [DeadLetterAccounting.verify]);
 * everything run-varying is in [detail].
 */
class UnexplainedDeadLetters(val accounting: DeadLetterAccounting) :
    AssertionError("unexplained dead letter ([CHA1-52]): the run dead-lettered something no allowance names"),
    DstFailureDetail {

    override fun detail(): String = buildString {
        append("dead letters ${accounting.renderCounts()}")
        accounting.unexplained.forEach { append("\n  ").append(it.render()) }
        if (accounting.policy.allowances.isEmpty()) {
            append("\n  policy: strict — no allowance was declared, so every dead letter is unexplained")
        } else {
            append("\n  policy allowances: ")
            append(accounting.policy.allowances.joinToString(", ") { it.label })
        }
    }
}

/**
 * The one classification rule ([CHA1-52]): structural fields, never the description text.
 * See [DeadLetterReason] for why each discriminator is the field it is.
 */
fun classifyDeadLetter(letter: DeadLetter): DeadLetterReason = when {
    letter.denial != null -> DeadLetterReason.BOUNDARY_DENIAL
    letter.cause != null -> DeadLetterReason.CELL_FAULT
    else -> DeadLetterReason.UNDELIVERABLE
}
