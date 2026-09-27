package civictech.timetravel.timeline

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.timetravel.journal.BaselineDischargeRecord
import civictech.timetravel.journal.CheckpointRecord
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.FrontierRecord
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalRecord
import civictech.timetravel.journal.MalformedRecord
import civictech.timetravel.journal.OutletWaveRecord
import civictech.timetravel.journal.UnknownRecord
import java.util.UUID

/**
 * One addressable position in a [RunTimeline]: a record plus the coordinates derived from it
 * (TTD1 F2, feature D1).
 *
 * @property wave the record's wave coordinate — non-null only for a [FrameRecord] carrying a
 *   `MessageContext`; never synthesized.
 * @property touches the [CellRef]s the record names.
 * @property isAnchor true for a [CheckpointRecord] position (feature D2).
 */
data class TimelinePosition(
    val index: Int,
    val record: JournalRecord,
    val wave: Timestamp?,
    val touches: Set<CellRef>,
    val isAnchor: Boolean,
)

/**
 * One journal's records as addressable [Position]s (TTD1 F2): index resolution, per-source
 * frontier-cut resolution (feature D3), and checkpoint anchors (feature D2).
 *
 * @param records this journal's records, in journal order. Every record's `journalId` must equal
 *   [journalId], and `records[k].index` must equal `k`.
 */
class RunTimeline(val journalId: String, records: List<JournalRecord>) {

    init {
        require(records.all { it.journalId == journalId }) {
            "all records of journal '$journalId' must carry that journalId"
        }
        records.forEachIndexed { position, record ->
            require(record.index == position) {
                "record at list position $position has index ${record.index}, expected $position"
            }
        }
    }

    /** One position per record, in journal order. */
    val positions: List<TimelinePosition> = records.map { record ->
        TimelinePosition(
            index = record.index,
            record = record,
            wave = (record as? FrameRecord)?.context?.timestamp,
            touches = touchesOf(record),
            isAnchor = record is CheckpointRecord,
        )
    }

    val size: Int = positions.size

    /** Every source id any waved frame in this timeline carries. */
    val sources: Set<UUID> = positions.mapNotNullTo(mutableSetOf()) { it.wave?.sourceId }

    /**
     * The exclusive prefix end `[0, n)` [position] resolves to.
     *
     * @throws IndexOutOfBoundsException naming [size] when [position] is a [Position.Index]
     *   outside `[0, size)`.
     */
    fun resolve(position: Position): Int = when (position) {
        is Position.Index -> {
            checkIndex(position.index)
            position.index + 1
        }

        is Position.Cut -> {
            val beyond = positions.indexOfFirst { p ->
                val wave = p.wave
                wave != null && wave.counter > (position.perSource[wave.sourceId] ?: -1L)
            }
            if (beyond == -1) size else beyond
        }
    }

    /**
     * The per-source maximum counter over waved frames in `[0, index]`.
     *
     * @throws IndexOutOfBoundsException naming [size] when [index] is outside `[0, size)`.
     */
    fun frontierAt(index: Int): Map<UUID, Long> {
        checkIndex(index)
        val frontier = mutableMapOf<UUID, Long>()
        for (k in 0..index) {
            val wave = positions[k].wave ?: continue
            val current = frontier[wave.sourceId]
            if (current == null || wave.counter > current) frontier[wave.sourceId] = wave.counter
        }
        return frontier
    }

    /**
     * The greatest anchor index `<= index`, or 0 when no position at or before [index] is an
     * anchor.
     *
     * @throws IndexOutOfBoundsException naming [size] when [index] is outside `[0, size)`.
     */
    fun nearestAnchorAtOrBefore(index: Int): Int {
        checkIndex(index)
        for (k in index downTo 0) {
            if (positions[k].isAnchor) return k
        }
        return 0
    }

    /**
     * A human label for position [index]: always contains `"#<index>"`. A waved frame's label
     * also carries the first 8 hex characters of its source id, `"wave <counter>"`, and the port
     * name; a contextless frame's label carries the port name and no `"wave"` token. Every other
     * record kind names itself.
     *
     * @throws IndexOutOfBoundsException naming [size] when [index] is outside `[0, size)`.
     */
    fun labels(index: Int): String {
        checkIndex(index)
        val position = positions[index]
        val header = "#$index"
        return when (val record = position.record) {
            is FrameRecord -> {
                val wave = position.wave
                if (wave != null) {
                    "$header (${wave.sourceId.toString().take(8)}, wave ${wave.counter}) -> ${record.portName}"
                } else {
                    "$header -> ${record.portName}"
                }
            }

            is CheckpointRecord -> "$header checkpoint"
            is FrontierRecord -> "$header frontier ${record.portName}"
            is OutletWaveRecord -> "$header outlet-wave ${record.portName}"
            is BaselineDischargeRecord -> "$header baseline-discharge ${record.portName}"
            is UnknownRecord -> "$header unknown"
            is MalformedRecord -> "$header malformed"
        }
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("index $index out of bounds for timeline of size $size")
        }
    }

    companion object {
        /**
         * One [RunTimeline] per journal of [reading], keyed by `journalId` in [JournalReading]'s
         * summary order (feature D5). A refused summary (its records absent from
         * `reading.records`) yields an entry with zero positions, not a missing key.
         */
        fun of(reading: JournalReading): Map<String, RunTimeline> {
            val byJournal = reading.records.groupBy { it.journalId }
            return reading.journals.associate { summary ->
                summary.journalId to RunTimeline(summary.journalId, byJournal[summary.journalId] ?: emptyList())
            }
        }

        private fun touchesOf(record: JournalRecord): Set<CellRef> = when (record) {
            is FrameRecord -> setOf(record.cellRef)
            is CheckpointRecord -> record.restoredCells + record.frontier.keys.map { it.first }
            is FrontierRecord -> setOf(record.cellRef)
            is OutletWaveRecord -> setOf(record.cellRef)
            is BaselineDischargeRecord -> setOf(record.cellRef)
            is UnknownRecord -> emptySet()
            is MalformedRecord -> emptySet()
        }
    }
}
