package civictech.demo.beadsmirror.writeback

import kotlinx.serialization.json.JsonObject

/**
 * Why one row's write-back was skipped without an import ever running.
 *
 * A typed enum rather than a string: clause 6 requires every outcome to be
 * machine-readable, and "the reason is free text" is exactly the shape that
 * makes an outcome unusable to anything but a human reading a log.
 */
enum class SkipReason {

    /**
     * The winner already agrees with the destination on every allowlisted
     * field ([PlanOutcome.NoOp]), so no import is run at all — feature
     * computenet-6wc.1 clause 5.
     */
    Equal,

    /**
     * This exact imposition (same issue, same row) already failed in an
     * earlier `applyOnce`, and the winner has not changed since. Clause 6
     * forbids a retry loop that re-adjudicates: a failed row waits for a NEW
     * winner, not for another attempt at the old one.
     */
    PreviouslyFailed,
}

/**
 * Why one row's write-back failed, in a form a caller can branch on.
 *
 * Every variant carries the evidence for its own verdict; none is a
 * free-text-only case.
 */
sealed interface WriteBackFailure {

    /**
     * `bd import` exited non-zero. [stdout] is bd's own report — carried
     * verbatim as evidence, never parsed (see [BdImport]).
     */
    data class ImportExited(val exitCode: Int, val stdout: String, val stderr: String) : WriteBackFailure

    /**
     * The import exited zero, but the post-import re-read disagrees with what
     * was imposed on [fields] — the mechanism clause 4 requires, since bd's
     * own report cannot be trusted to say whether a row landed.
     *
     * Each [FieldLoss] here reads `old` = the imposed value, `new` = what the
     * re-read actually holds. `updated_at` is never among them: E4's
     * round-half-up makes its stored value bd's business, so it is excluded
     * from the comparison and reported inside [WriteBackEvent.Imposed.observed]
     * instead of adjudicated.
     */
    data class ReadBackMismatch(val fields: List<FieldLoss>) : WriteBackFailure

    /** The import exited zero and the post-import re-read holds no row for the issue at all. */
    data object ReadBackMissing : WriteBackFailure

    /**
     * The fold's rendering of [field] is not JSON, so no row could be built
     * for this issue ([PlanOutcome.Unrenderable]). No import was attempted.
     */
    data class Unrenderable(val field: String, val rendering: String) : WriteBackFailure
}

/**
 * One observable step of a write-back pass — the applier's machine-readable
 * account of what it did to each issue.
 *
 * Deliberately **not** a `MirrorEvent` subtype: `MirrorEvent`
 * (`baseline/Rebaseline.kt`) is another feature's surface, and write-back
 * outcomes ride their own sink `(WriteBackEvent) -> Unit` so the two vocabularies
 * stay separable (breakdown decision on feature computenet-6wc.1).
 */
sealed interface WriteBackEvent {

    /** The issue this event is about. */
    val issueId: String

    /**
     * The pre-flight loss record for an imposition that is ABOUT to run —
     * feature computenet-6wc.1 clause 2. Emitted strictly BEFORE the importer
     * is invoked, so an observer that sees an [Imposed]/[Failed] for an issue
     * has necessarily already seen what that import would overwrite.
     *
     * [losses] may be empty, and an empty list is emitted rather than
     * suppressed: it is a positive statement ("this imposition overwrites
     * nothing"), and an observer that has to distinguish "no losses" from "no
     * pre-flight ran" cannot do so if the event is conditional. In practice
     * the planner only produces an `Impose` when at least one field differs,
     * so an empty list here means the destination moved between planning and
     * this record — which is itself worth seeing.
     */
    data class PreFlight(override val issueId: String, val losses: List<FieldLoss>) : WriteBackEvent

    /**
     * The row landed: the import exited zero and the post-import re-read
     * agrees with it on every imposed field except `updated_at`.
     *
     * [observed] is that re-read export row itself — not the imposed row and
     * not bd's report — so `updated_at`'s stored value (E4 rounding included)
     * is reported rather than adjudicated.
     */
    data class Imposed(override val issueId: String, val observed: JsonObject) : WriteBackEvent

    /** No import was run for this issue; [reason] says which of the two decided cases applied. */
    data class Skipped(override val issueId: String, val reason: SkipReason) : WriteBackEvent

    /** This issue's write-back failed; the queue continues with the next issue (clause 6). */
    data class Failed(override val issueId: String, val failure: WriteBackFailure) : WriteBackEvent
}
