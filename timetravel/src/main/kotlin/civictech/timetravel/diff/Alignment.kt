package civictech.timetravel.diff

import civictech.cell.CellRef
import civictech.timetravel.journal.BaselineDischargeRecord
import civictech.timetravel.journal.CheckpointRecord
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.FrontierRecord
import civictech.timetravel.journal.JournalRecord
import civictech.timetravel.journal.MalformedRecord
import civictech.timetravel.journal.OutletWaveRecord
import civictech.timetravel.journal.UnknownRecord
import civictech.timetravel.timeline.RunTimeline
import java.util.UUID

/**
 * How two runs' positions are keyed against each other (TTD1 F6, `si0tl-D7`, `[TTD1-43]`).
 * Encoded by name in the diff report.
 */
enum class AlignmentMode {
    /** The runs share at least one wave `sourceId`: waved frames key by wave coordinate. */
    WAVE,

    /** Neither run's waves can be matched, but at least one run has none: keyed, no wave overlap. */
    ORDINAL,

    /** Both runs carry waves and share no `sourceId`: every position keys by its index alone. */
    INDEX_ONLY,
}

/**
 * The class of a first divergence (TTD1 F6, `si0tl-D9`, `[TTD1-41]`). Exactly one is reported per
 * divergence. Encoded by name in the diff report. [STATE_DIFFERS] is never produced at the record
 * level (by [RecordAlignment]); it is the state comparison's verdict when records align fully.
 */
enum class DivergenceClass {
    ONLY_IN_A,
    ONLY_IN_B,
    FRAME_DIFFERS,
    ARGS_DIFFER,
    WAVE_ORDER_DIFFERS,
    STATE_DIFFERS,
}

/** The alignment key of one timeline position (`si0tl-D7`, `[TTD1-40]`). */
sealed interface RecordKey {

    /** `si0tl-D13`'s rendering of this key, for the diff report. */
    fun render(): String

    /** A frame carrying a `MessageContext`: its wave coordinate and target port. */
    data class Wave(val sourceId: UUID, val counter: Long, val cellRef: CellRef, val portName: String) : RecordKey {
        override fun render(): String =
            "wave(${sourceId.toString().take(8)}, $counter) ${cellRef.id.toString().take(8)}.$portName"
    }

    /**
     * Any other record: the [ordinal]-th (0-based) record of the same `(kind, cellRef, portName)`
     * in its timeline. [kind] is `"frame"` for a contextless frame, else the record class's simple
     * name; [cellRef] and [portName] are `null` for records that name no port.
     */
    data class Ordinal(val kind: String, val cellRef: CellRef?, val portName: String?, val ordinal: Int) : RecordKey {
        override fun render(): String =
            "$kind ${cellRef?.id?.toString()?.take(8) ?: "-"}.${portName ?: "-"} #$ordinal"
    }

    /** Only under [AlignmentMode.INDEX_ONLY]: the position's index. */
    data class Index(val index: Int) : RecordKey {
        override fun render(): String = "#$index"
    }
}

/**
 * The first record-level divergence between two runs.
 *
 * @property indexA the divergent position in run A, `null` when run A has ended there.
 * @property detailA the structural tuple ([DivergenceClass.FRAME_DIFFERS]) or the canonical args
 *   ([DivergenceClass.ARGS_DIFFER]) of run A's record; `null` for every other class.
 */
data class RecordDivergence(
    val kind: DivergenceClass,
    val indexA: Int?,
    val indexB: Int?,
    val keyA: RecordKey?,
    val keyB: RecordKey?,
    val detailA: String?,
    val detailB: String?,
)

/**
 * The record-level alignment of two runs.
 *
 * @property note [RecordAlignment.NOT_WAVE_ALIGNABLE] under [AlignmentMode.INDEX_ONLY], else `null`.
 * @property alignedPrefix the longest common prefix under `si0tl-D8`'s equality; also the first
 *   divergent index in both runs when [divergence] is non-null.
 */
data class Alignment(
    val mode: AlignmentMode,
    val note: String?,
    val alignedPrefix: Int,
    val divergence: RecordDivergence?,
)

/**
 * Record-level alignment of two [RunTimeline]s (TTD1 F6): keys and mode (`si0tl-D7`), equality and
 * the aligned prefix (`si0tl-D8`), and classification of the first divergence (`si0tl-D9`).
 *
 * Accepted limitation (`si0tl-D9`): an ordinal key cannot tell an inserted contextless frame from
 * a substituted one, so a mid-run insertion of a contextless frame classifies as
 * [DivergenceClass.ARGS_DIFFER] (or [DivergenceClass.FRAME_DIFFERS]) at that ordinal.
 */
object RecordAlignment {

    const val NOT_WAVE_ALIGNABLE: String = "runs are not wave-alignable: no shared sourceId"

    /**
     * [AlignmentMode.INDEX_ONLY] iff both runs' `sources` are non-empty and disjoint;
     * [AlignmentMode.WAVE] iff they intersect; [AlignmentMode.ORDINAL] otherwise.
     */
    fun mode(a: RunTimeline, b: RunTimeline): AlignmentMode = when {
        a.sources.isNotEmpty() && b.sources.isNotEmpty() && a.sources.none { it in b.sources } ->
            AlignmentMode.INDEX_ONLY

        a.sources.any { it in b.sources } -> AlignmentMode.WAVE
        else -> AlignmentMode.ORDINAL
    }

    /** One key per position of [t], in order (`si0tl-D7`). */
    fun keys(t: RunTimeline, mode: AlignmentMode): List<RecordKey> {
        if (mode == AlignmentMode.INDEX_ONLY) return t.positions.map { RecordKey.Index(it.index) }
        val ordinals = HashMap<Triple<String, CellRef?, String?>, Int>()
        return t.positions.map { position ->
            val record = position.record
            val context = (record as? FrameRecord)?.context
            if (record is FrameRecord && context != null) {
                RecordKey.Wave(context.timestamp.sourceId, context.timestamp.counter, record.cellRef, record.portName)
            } else {
                val shape = shapeOf(record)
                val k = ordinals.getOrDefault(shape, 0)
                ordinals[shape] = k + 1
                RecordKey.Ordinal(shape.first, shape.second, shape.third, k)
            }
        }
    }

    /**
     * The fields `si0tl-D8` compares besides key and args, rendered: a frame's
     * `(cellRef, portName, type, contractId, methodId)`, any other record's `(kind, cellRef, portName)`.
     * Never the frame's context or a non-frame record's wave payload.
     */
    fun structuralTuple(record: JournalRecord): String = when (record) {
        is FrameRecord ->
            "frame(${record.cellRef.id}, ${record.portName}, ${record.type}, ${record.contractId}, ${record.methodId})"

        else -> {
            val (kind, cellRef, portName) = shapeOf(record)
            "$kind(${cellRef?.id ?: "-"}, ${portName ?: "-"})"
        }
    }

    /** The aligned prefix of [a] and [b] and, when they diverge, the first divergence classified. */
    fun align(a: RunTimeline, b: RunTimeline): Alignment {
        val mode = mode(a, b)
        val note = if (mode == AlignmentMode.INDEX_ONLY) NOT_WAVE_ALIGNABLE else null
        val keysA = keys(a, mode)
        val keysB = keys(b, mode)

        var p = 0
        while (p < a.size && p < b.size && equal(keysA[p], keysB[p], a.positions[p].record, b.positions[p].record)) {
            p++
        }
        if (p == a.size && p == b.size) return Alignment(mode, note, p, null)

        return Alignment(mode, note, p, classify(p, a, b, keysA, keysB))
    }

    private fun classify(
        p: Int,
        a: RunTimeline,
        b: RunTimeline,
        keysA: List<RecordKey>,
        keysB: List<RecordKey>,
    ): RecordDivergence {
        if (p == a.size) return RecordDivergence(DivergenceClass.ONLY_IN_B, null, p, null, keysB[p], null, null)
        if (p == b.size) return RecordDivergence(DivergenceClass.ONLY_IN_A, p, null, keysA[p], null, null, null)

        val keyA = keysA[p]
        val keyB = keysB[p]
        val recordA = a.positions[p].record
        val recordB = b.positions[p].record
        if (keyA == keyB) {
            val tupleA = structuralTuple(recordA)
            val tupleB = structuralTuple(recordB)
            if (tupleA != tupleB) {
                return RecordDivergence(DivergenceClass.FRAME_DIFFERS, p, p, keyA, keyB, tupleA, tupleB)
            }
            // Equal keys and tuples, yet unequal: only frames compare args, so both are frames.
            return RecordDivergence(
                DivergenceClass.ARGS_DIFFER, p, p, keyA, keyB,
                CanonicalJson.canonical((recordA as FrameRecord).args),
                CanonicalJson.canonical((recordB as FrameRecord).args),
            )
        }

        val kind = when {
            keyB !in keysA.subList(p, keysA.size).toHashSet() -> DivergenceClass.ONLY_IN_B
            keyA !in keysB.subList(p, keysB.size).toHashSet() -> DivergenceClass.ONLY_IN_A
            else -> DivergenceClass.WAVE_ORDER_DIFFERS
        }
        return RecordDivergence(kind, p, p, keyA, keyB, null, null)
    }

    private fun equal(keyA: RecordKey, keyB: RecordKey, recordA: JournalRecord, recordB: JournalRecord): Boolean {
        if (keyA != keyB) return false
        if (structuralTuple(recordA) != structuralTuple(recordB)) return false
        if (recordA is FrameRecord && recordB is FrameRecord) {
            return CanonicalJson.canonical(recordA.args) == CanonicalJson.canonical(recordB.args)
        }
        return true
    }

    /** `(kind, cellRef?, portName?)` — the grouping of `si0tl-D7`'s ordinal keys. */
    private fun shapeOf(record: JournalRecord): Triple<String, CellRef?, String?> = when (record) {
        is FrameRecord -> Triple("frame", record.cellRef, record.portName)
        is FrontierRecord -> Triple(kindOf(record), record.cellRef, record.portName)
        is OutletWaveRecord -> Triple(kindOf(record), record.cellRef, record.portName)
        is BaselineDischargeRecord -> Triple(kindOf(record), record.cellRef, record.portName)
        is CheckpointRecord, is UnknownRecord, is MalformedRecord -> Triple(kindOf(record), null, null)
    }

    private fun kindOf(record: JournalRecord): String = record::class.java.simpleName
}
