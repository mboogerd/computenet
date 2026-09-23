package civictech.timetravel.fidelity

import civictech.timetravel.journal.JournalRecord
import civictech.timetravel.journal.JournalSummary

/**
 * Reasons a reconstruction whose prefix ends at [position] (inclusive record index) inherits
 * from [journal] (TTD1 F3, decision kxex2-D6, [TTD1-36]).
 *
 * Per-journal: journals restart record indices at 0 (CP-C1 per-cell journals), so a caller with
 * several journals asks once per journal and folds the results (typically via [rollUp]) — this
 * function does not accept a [civictech.timetravel.journal.JournalReading] on purpose.
 *
 * The union, in order:
 * 1. Every reason in [JournalSummary.reasons] except [Reason.JOURNAL_TORN] (that one is
 *    positional, handled by the tear rule below) — today that is only
 *    [Reason.FORMAT_VERSION_MISMATCH]. These apply at every legal position.
 * 2. The reasons of every record in [records] whose `journalId` matches [journal]'s and whose
 *    `index` is `<= position`; records of other journals are ignored, and a record whose index is
 *    strictly greater than [position] contributes nothing.
 * 3. [Reason.JOURNAL_TORN] iff [JournalSummary.tear] is non-null and
 *    `position >= tear.lastIntactIndex` — the torn record is the one that would have followed the
 *    last intact record, so a prefix ending at the last intact record already ends at the tear.
 *
 * @throws IllegalArgumentException if [journal] was refused (it has no records and no position to
 *   ask about — the reconstructor never asks about a refused journal), or if [position] is outside
 *   `-1 until journal.recordCount` (`-1` denotes the empty prefix, legal so a reconstructor can ask
 *   about the predecessor of record 0).
 */
fun journalDefectsUpTo(journal: JournalSummary, records: Iterable<JournalRecord>, position: Int): Set<Reason> {
    require(journal.refusal == null) {
        "journal ${journal.journalId} was refused (${journal.refusal}); it has no records to ask about"
    }
    require(position >= -1 && position < journal.recordCount) {
        "position $position out of range for journal ${journal.journalId} " +
            "with ${journal.recordCount} records (legal range is -1 until ${journal.recordCount})"
    }

    val result = mutableSetOf<Reason>()

    for (reason in journal.reasons) {
        if (reason != Reason.JOURNAL_TORN) {
            result += reason
        }
    }

    for (record in records) {
        if (record.journalId == journal.journalId && record.index <= position) {
            result += record.reasons
        }
    }

    val tear = journal.tear
    if (tear != null && position >= tear.lastIntactIndex) {
        result += Reason.JOURNAL_TORN
    }

    return result
}
