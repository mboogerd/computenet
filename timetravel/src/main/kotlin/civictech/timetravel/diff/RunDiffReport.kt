package civictech.timetravel.diff

import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.reconstruct.CellStateView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Which run a report field belongs to (`si0tl-D11`). */
@Serializable
enum class RunSide { A, B }

/** Whether a journal name occurs in both readings, or only one of them (`si0tl-D12`). */
@Serializable
enum class JournalPresence { BOTH, ONLY_IN_A, ONLY_IN_B }

/** The serializable mirror of [Fidelity]'s three verdicts (`si0tl-D11`). */
@Serializable
enum class Verdict { FAITHFUL, DEGRADED, UNRECONSTRUCTIBLE }

/**
 * The serializable mirror of [Fidelity] (`si0tl-D11`): [reasons] sorted by name. [Fidelity] itself
 * is never a DTO property — it is not `@Serializable` and carries no stable JSON shape.
 */
@Serializable
data class FidelityDto(val verdict: Verdict, val reasons: List<Reason>) {
    companion object {
        fun of(fidelity: Fidelity): FidelityDto = when (fidelity) {
            is Fidelity.Faithful -> FidelityDto(Verdict.FAITHFUL, emptyList())
            is Fidelity.Degraded -> FidelityDto(Verdict.DEGRADED, fidelity.reasons.sortedBy { it.name })
            is Fidelity.Unreconstructible -> FidelityDto(Verdict.UNRECONSTRUCTIBLE, fidelity.reasons.sortedBy { it.name })
        }
    }
}

/** One [CellStateView] entry, copied verbatim (`si0tl-D11`). */
@Serializable
data class StateViewEntry(val key: String, val value: String)

/**
 * The serializable mirror of [CellStateView] (`si0tl-D11`): a copy, never an annotation of the
 * `reconstruct`-owned type. [entries] keeps [CellStateView.entries]'s order (`6tm33-D13`: already
 * deterministic).
 */
@Serializable
data class StateView(val kind: String, val summary: String, val entries: List<StateViewEntry>) {
    companion object {
        fun of(view: CellStateView): StateView =
            StateView(view.kind, view.summary, view.entries.map { (key, value) -> StateViewEntry(key, value) })
    }
}

/**
 * One cell whose reconstructed state differs between run A and run B, or is present on only one
 * side (`si0tl-D10`). `viewX`/`fidelityX` are `null` when the side has no entry for [cellRef];
 * [viewA]/[viewB] are additionally `null` for a side whose cell is
 * [Fidelity.Unreconstructible] — its `fidelityX` is still carried.
 */
@Serializable
data class CellDelta(
    val cellRef: String,
    val viewA: StateView?,
    val viewB: StateView?,
    val fidelityA: FidelityDto?,
    val fidelityB: FidelityDto?,
)

/** Why one side's run state could not be compared at all (`si0tl-D10`). */
@Serializable
data class RunUnavailable(val run: RunSide, val reasons: List<Reason>, val message: String)

/**
 * The state-level comparison of two runs (`si0tl-D10`): [Compared] when both sides reconstructed
 * at their compared positions, [Unavailable] when at least one side could not be. Record-level
 * fields on [RunDiffReport] are always present regardless of which variant this is ([TTD1-44]).
 */
@Serializable
sealed interface StateDiff {

    /** [deltas] sorted by [CellDelta.cellRef]'s string. */
    @Serializable
    @SerialName("compared")
    data class Compared(
        val positionA: Int,
        val positionB: Int,
        val runA: FidelityDto,
        val runB: FidelityDto,
        val deltas: List<CellDelta>,
    ) : StateDiff

    /** [runs] carries one entry per unavailable side, A before B. */
    @Serializable
    @SerialName("unavailable")
    data class Unavailable(val runs: List<RunUnavailable>) : StateDiff
}

/**
 * The rendered first record-level divergence (`si0tl-D9`, `si0tl-D11`): [kind] names the
 * [DivergenceClass]; `indexX`/`labelX` are `null` for the side that ended there; `keyX` is
 * [RecordKey.render] of that side's key at the divergence (`null` when the side has no position
 * there); `detailX` is the structural tuple for [DivergenceClass.FRAME_DIFFERS], the canonical
 * args for [DivergenceClass.ARGS_DIFFER], and `null` for every other class.
 */
@Serializable
data class Divergence(
    val kind: DivergenceClass,
    val indexA: Int?,
    val indexB: Int?,
    val labelA: String?,
    val labelB: String?,
    val keyA: String?,
    val keyB: String?,
    val detailA: String?,
    val detailB: String?,
)

/**
 * The full diff of two runs (`si0tl-D6`, `si0tl-D11`): [alignment]/[alignmentNote]/[alignedPrefix]/
 * [divergence] are the record-level result (`si0tl-D7`-`si0tl-D9`); [stateDiff] is the state-level
 * comparison (`si0tl-D10`), including [DivergenceClass.STATE_DIFFERS], which carries a non-null
 * [divergence] whose `indexA`/`indexB` are the compared positions.
 *
 * Every property is `String`/`Int`/`Long`/`Boolean`/an enum/a nullable of those/a `List` of those,
 * or another DTO of this file — no `Map`, `UUID`, `CellRef`, [Fidelity], or [CellStateView]
 * ([TTD1-45]).
 */
@Serializable
data class RunDiffReport(
    val journalA: String,
    val journalB: String,
    val sizeA: Int,
    val sizeB: Int,
    val alignment: AlignmentMode,
    val alignmentNote: String?,
    val alignedPrefix: Int,
    val divergence: Divergence?,
    val stateDiff: StateDiff,
) {
    /** Compact JSON through [CANONICAL_JSON]: defaults and nulls emitted (`si0tl-D11`). */
    fun toJson(): String = CANONICAL_JSON.encodeToString(this)

    /** [TextRenderer]'s rendering of this report (`si0tl-D13`). */
    fun toText(): String = TextRenderer.render(this)
}

/** One journal's presence across two readings, and its diff when matched on both sides (`si0tl-D12`). */
@Serializable
data class JournalDiff(val journal: String, val presence: JournalPresence, val report: RunDiffReport?)

/** The diff of every journal matched by name across two readings, sorted by name (`si0tl-D12`). */
@Serializable
data class MultiJournalDiffReport(val journals: List<JournalDiff>) {
    /** Compact JSON through [CANONICAL_JSON]: defaults and nulls emitted (`si0tl-D11`). */
    fun toJson(): String = CANONICAL_JSON.encodeToString(this)

    /** [TextRenderer]'s rendering of this report (`si0tl-D13`). */
    fun toText(): String = TextRenderer.render(this)
}

/**
 * The one `Json` instance for `diff/`'s reports (`si0tl-D11`): compact, and — the opposite of
 * `civictech.cell.wire.WireCodec`'s `Json`, which deliberately omits defaults — every default and
 * every `null` is emitted, so a report's JSON is a complete, order-independent description of its
 * DTO tree.
 */
internal val CANONICAL_JSON: Json = Json {
    prettyPrint = false
    encodeDefaults = true
}
