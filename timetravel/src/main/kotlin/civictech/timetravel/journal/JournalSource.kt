package civictech.timetravel.journal

import civictech.cell.durability.JOURNAL_FORMAT_VERSION
import civictech.cell.durability.Journal
import civictech.cell.durability.JournalFileScan
import civictech.timetravel.fidelity.Reason

/** Where [JournalReader.open] reads journal records from (TTD1 F1, feature D5). */
sealed interface JournalSource {
    /** One journal file ([civictech.cell.durability.FileJournal] / `BatchedFileJournal` encoding). */
    data class File(val file: java.io.File) : JournalSource

    /**
     * Every regular file directly inside [dir] (non-recursive), sorted by name, each read as an
     * independent journal attributed by its own path (`[TTD1-13]`).
     */
    data class Directory(val dir: java.io.File) : JournalSource

    /**
     * A live [Journal] instance, read through `replay()`. `replay()` hands back only intact
     * records, so no tear can be detected here and none is claimed.
     */
    data class InMemory(val journal: Journal, val label: String) : JournalSource
}

/** The source itself cannot be read at all — as opposed to a record inside it. */
class JournalUnreadable(val path: String, val reason: String) : Exception("$path: $reason")

/**
 * What one journal of a reading is, apart from its records.
 *
 * @property declaredFormatVersion the version the journal declares, or `null` when it declares
 *   none (an empty file, or a torn header).
 * @property recordCount how many records it yielded.
 * @property tear the torn tail a file scan found; always `null` for an in-memory source.
 * @property refusal why this journal of a directory was not read at all, or `null`.
 * @property reasons [Reason.FORMAT_VERSION_MISMATCH] and/or [Reason.JOURNAL_TORN] as they apply.
 */
data class JournalSummary(
    val journalId: String,
    val declaredFormatVersion: Int?,
    val expectedFormatVersion: Int = JOURNAL_FORMAT_VERSION,
    val recordCount: Int,
    val tear: JournalFileScan.Tear?,
    val refusal: String?,
    val reasons: Set<Reason>,
)

/**
 * The result of [JournalReader.open].
 *
 * @property records every journal's records, journals in [journals] order. Backed by a list
 *   built eagerly, so it is re-iterable; typed as a [Sequence] so a streaming implementation
 *   later is not an API break.
 * @property reasons the union of every journal's and every record's reasons.
 * @property rawRecords one entry per [JournalSummary] in [journals], keyed by
 *   [JournalSummary.journalId] — a refused summary maps to an empty list. `rawRecords[id][k]` is
 *   exactly the [ByteArray] the record with that `journalId` and `index == k` was classified
 *   from (same object or equal content); alignment holds because [JournalReader] derives both
 *   from one indexed pass over the same raw list. The reader neither copies nor modifies the
 *   bytes it hands out here.
 */
data class JournalReading(
    val journals: List<JournalSummary>,
    val records: Sequence<JournalRecord>,
    val reasons: Set<Reason>,
    val rawRecords: Map<String, List<ByteArray>> = emptyMap(),
)
