package civictech.testkit.dst

import civictech.cell.host.DeadLetter

/**
 * How a run ended. `BUDGET_EXHAUSTED` is a **distinct outcome from `FAILED`** ([CHA1-03]): a
 * run that never quiesced did not disprove anything, and collapsing the two would let a
 * livelock read as a caught bug — or, worse, let a real bug hide behind "it was slow".
 */
enum class DstOutcome {
    /** Quiesced within budget and the check passed. */
    PASSED,

    /** Quiesced within budget and the check failed. */
    FAILED,

    /** Did not quiesce within the step budget. The check is not run and no verdict is claimed. */
    BUDGET_EXHAUSTED,
}

/** The check that failed, and where ([CHA1-50] will render this; here it is the data). */
data class FailingCheck(
    val message: String,
    val step: Int,
    val error: Throwable? = null,
)

/**
 * One fault as it was actually applied ([CHA1-24]): what it was, how often it fired, and at
 * which step indices.
 *
 * [inert] is the load-bearing field. A plan whose faults all fired is evidence the adversary
 * ran; a fault that fired zero times is a *silent* no-op — the plan looked adversarial, the
 * run passed, and nothing was tested. The rig records it rather than hiding it, and a
 * consumer suite is expected to assert on it.
 */
data class AppliedFault(
    val id: String,
    val description: String,
    val fired: Int,
    val activationSteps: List<Int>,
) {
    val inert: Boolean get() = fired == 0
}

/**
 * Everything one adversarial run produced ([CHA1-03], [CHA1-05], [CHA1-24], epic §2.2).
 *
 * This is the **data model only**. Rendering the human-readable failure report of epic §2.3,
 * the replay command line, and dead-letter classification are later tasks; they consume this
 * type and add nothing to a run.
 *
 * @property seed the run seed — the same value as `plan.seed`, surfaced here because it is
 *   the first thing a failure report prints and the one field a shrinker must never change.
 * @property deadLetters raw and unclassified, in arrival order.
 */
data class DstReport(
    val outcome: DstOutcome,
    val seed: Long,
    val graphId: String,
    val budget: Int,
    val steps: Int,
    val plan: FaultPlan,
    val appliedFaults: List<AppliedFault>,
    val traceDigest: TraceDigest,
    val failingCheck: FailingCheck? = null,
    val deadLetters: List<DeadLetter> = emptyList(),
    val trace: List<TraceEvent> = emptyList(),
) {
    val passed: Boolean get() = outcome == DstOutcome.PASSED

    /** Faults that were configured and applied but never fired ([CHA1-24], BS-13). */
    val inertFaults: List<AppliedFault> get() = appliedFaults.filter { it.inert }

    /** One line, enough to identify a run in a sweep. Not the failure report of epic §2.3. */
    fun summary(): String =
        "DST $outcome suite=$graphId seed=$seed steps=$steps/$budget faults=${appliedFaults.size}" +
            (if (inertFaults.isEmpty()) "" else " inert=${inertFaults.map { it.id }}") +
            " digest=$traceDigest" +
            (failingCheck?.let { " check=${it.message}" } ?: "")
}
