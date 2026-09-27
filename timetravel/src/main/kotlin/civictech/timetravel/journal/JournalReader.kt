package civictech.timetravel.journal

import civictech.cell.durability.JOURNAL_FORMAT_VERSION
import civictech.cell.durability.JournalFileScan
import civictech.cell.durability.JournalFormatMismatch
import civictech.cell.durability.scanJournalFile
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.timetravel.fidelity.Reason
import java.io.IOException

/**
 * Reads durability journals offline into typed, indexed [JournalRecord]s (TTD1 F1, feature D5)
 * — structurally without descriptors, hydrated with them — and constructs no host, scheduler or
 * cell while doing so (`[TTD1-14]`).
 *
 * Record typing goes through the kernel's one definition, `JournalRecords.decode`
 * (`[TTD1-02]`); file framing through the kernel's `scanJournalFile`. Nothing here re-reads the
 * framing or re-states the type bytes, with one narrow exception documented on
 * [CHECKPOINT_TYPE].
 */
object JournalReader {

    /**
     * The checkpoint record's type byte, used **only** to choose which reason a failed
     * `JournalRecords.decode` reports ([Reason.CHECKPOINT_UNDESERIALIZABLE] versus
     * [Reason.RECORD_UNDESERIALIZABLE]): the kernel's decode propagates the `readObject` failure
     * unwrapped, so the reader cannot learn the failing type from the result. Classification of
     * every *readable* record is the kernel's alone. If the kernel renumbered its checkpoint type,
     * a checkpoint failure would be mis-labelled `RECORD_UNDESERIALIZABLE` — still reported,
     * never dropped.
     */
    private const val CHECKPOINT_TYPE: Byte = 2

    /**
     * Open [source] and read every record it holds.
     *
     * @throws JournalUnreadable when a [JournalSource.File] is not a readable regular file or a
     *   [JournalSource.Directory] is not a readable directory — checked before any other
     *   filesystem call, so nothing is created — or when an in-memory journal's `replay()`
     *   refuses its format version.
     */
    fun open(source: JournalSource): JournalReading {
        val journals: List<Read> = when (source) {
            is JournalSource.File -> {
                val file = source.file
                if (!file.isFile || !file.canRead()) throw JournalUnreadable(file.path, "not a readable regular file")
                val scan = try {
                    scanJournalFile(file)
                } catch (e: IOException) {
                    throw JournalUnreadable(file.path, e.toString())
                }
                listOf(read(file.path, scan.declaredFormatVersion, scan.records, scan.tear))
            }

            is JournalSource.Directory -> {
                val dir = source.dir
                if (!dir.isDirectory || !dir.canRead()) throw JournalUnreadable(dir.path, "not a readable directory")
                (dir.listFiles() ?: throw JournalUnreadable(dir.path, "directory could not be listed"))
                    .filter { it.isFile }
                    .sortedBy { it.name }
                    .map { file -> readDirectoryEntry(file) }
            }

            is JournalSource.InMemory -> {
                val records = try {
                    source.journal.replay()
                } catch (e: JournalFormatMismatch) {
                    throw JournalUnreadable(source.label, e.message ?: e.toString())
                }
                listOf(read(source.label, source.journal.formatVersion, records, tear = null))
            }
        }
        val summaries = journals.map { it.summary }
        val records = journals.flatMap { it.records }
        val reasons = summaries.flatMapTo(mutableSetOf()) { it.reasons } +
            records.flatMap { it.reasons }
        val rawRecords = journals.associate { it.summary.journalId to it.raw }
        return JournalReading(summaries, records.asSequence(), reasons, rawRecords)
    }

    /** One journal's read outcome: its summary, its typed records, and the raw bytes each came from. */
    private class Read(val summary: JournalSummary, val records: List<JournalRecord>, val raw: List<ByteArray>)

    /** One file of a directory: an unreadable one is a refused summary, not an exception. */
    private fun readDirectoryEntry(file: java.io.File): Read {
        fun refused(why: String) = Read(
            JournalSummary(
                journalId = file.path,
                declaredFormatVersion = null,
                recordCount = 0,
                tear = null,
                refusal = why,
                reasons = emptySet(),
            ),
            emptyList(),
            emptyList(),
        )

        if (!file.canRead()) return refused("not a readable regular file")
        val scan = try {
            scanJournalFile(file)
        } catch (e: IOException) {
            return refused(e.toString())
        } catch (e: IllegalArgumentException) {
            return refused(e.message ?: e.toString())
        }
        return read(file.path, scan.declaredFormatVersion, scan.records, scan.tear)
    }

    private fun read(
        journalId: String,
        declaredFormatVersion: Int?,
        raw: List<ByteArray>,
        tear: JournalFileScan.Tear?,
    ): Read {
        val journalReasons = mutableSetOf<Reason>()
        val versionMismatch = declaredFormatVersion != null && declaredFormatVersion != JOURNAL_FORMAT_VERSION
        if (versionMismatch) journalReasons += Reason.FORMAT_VERSION_MISMATCH
        if (tear != null) journalReasons += Reason.JOURNAL_TORN

        val records = raw.mapIndexed { index, bytes ->
            val record = classify(index, journalId, bytes)
            // The records are still length-prefixed bytes under a foreign version, so they are
            // classified as usual — but none of them is trusted at full fidelity.
            if (versionMismatch) record.withReason(Reason.FORMAT_VERSION_MISMATCH) else record
        }
        val summary = JournalSummary(
            journalId = journalId,
            declaredFormatVersion = declaredFormatVersion,
            recordCount = records.size,
            tear = tear,
            refusal = null,
            reasons = journalReasons,
        )
        return Read(summary, records, raw)
    }

    private fun classify(index: Int, journalId: String, bytes: ByteArray): JournalRecord {
        if (bytes.isEmpty()) return UnknownRecord(index, journalId, typeByte = null)
        val decoded = try {
            JournalRecords.decode(bytes)
        } catch (e: Exception) {
            val reason = if (bytes[0] == CHECKPOINT_TYPE) {
                Reason.CHECKPOINT_UNDESERIALIZABLE
            } else {
                Reason.RECORD_UNDESERIALIZABLE
            }
            return MalformedRecord(index, journalId, bytes[0], reason, e.toString())
        }
        return when (decoded) {
            is DecodedJournalRecord.Frame -> FrameJson.read(index, journalId, bytes[0], decoded.payload)
            is DecodedJournalRecord.Checkpoint ->
                CheckpointRecord(index, journalId, decoded.state.keys.toSet(), decoded.frontier, emptySet())
            is DecodedJournalRecord.Frontier ->
                FrontierRecord(index, journalId, decoded.cellRef, decoded.portName, decoded.timestamp, emptySet())
            is DecodedJournalRecord.OutletWave -> OutletWaveRecord(
                index, journalId, decoded.cellRef, decoded.portName, decoded.sourceId, decoded.highWater, emptySet(),
            )
            is DecodedJournalRecord.BaselineDischarge -> BaselineDischargeRecord(
                index, journalId, decoded.cellRef, decoded.portName, decoded.timestamp, emptySet(),
            )
            is DecodedJournalRecord.Unknown -> UnknownRecord(index, journalId, decoded.typeByte)
        }
    }

    private fun JournalRecord.withReason(reason: Reason): JournalRecord {
        val widened = reasons + reason
        return when (this) {
            is FrameRecord -> copy(reasons = widened)
            is CheckpointRecord -> copy(reasons = widened)
            is FrontierRecord -> copy(reasons = widened)
            is OutletWaveRecord -> copy(reasons = widened)
            is BaselineDischargeRecord -> copy(reasons = widened)
            is UnknownRecord -> copy(reasons = widened)
            is MalformedRecord -> copy(reasons = widened)
        }
    }
}
