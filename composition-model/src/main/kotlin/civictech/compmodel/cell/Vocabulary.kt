package civictech.compmodel.cell

/**
 * The per-cell layers of COH §2.1 (`composite-obligation-holders.md`), named as there.
 * `Swap` and `Coupling` are not per-cell stack layers in this model: `Swap` is modelled by
 * [civictech.compmodel.composite.PromotionModel], `Coupling` is not modelled.
 *
 * Properties, from the COH §2.5 table (column "Holds custody" / "Monotone state"):
 *
 * | layer | custody | monotone state |
 * |---|---|---|
 * | D Durable | log, checkpoint | yes |
 * | O Outbox | retained outbound frames | yes |
 * | P Suspendable | park queue | yes |
 * | F Fence | – | epoch fence |
 * | A Align | partial waves | yes |
 * | X EffectDedup | – | disposed, acted, pullDischarged |
 * | S Supervised | – | identity tier, generation |
 */
enum class Layer(val code: Char, val custody: Boolean, val monotone: Boolean) {
    DURABLE('D', true, true),
    OUTBOX('O', true, true),
    SUSPENDABLE('P', true, true),
    FENCE('F', false, true),
    ALIGN('A', true, true),
    EFFECT_DEDUP('X', false, true),
    SUPERVISED('S', false, true);

    companion object {
        fun of(c: Char): Layer = entries.first { it.code == c }
        /** "DOPFAXS" -> canonical stack, outermost first. */
        fun parse(s: String): List<Layer> = s.map { of(it) }
        val CANONICAL: List<Layer> = parse("DOPFAXS")
    }
}

fun List<Layer>.code(): String = joinToString("") { it.code.toString() }

/**
 * A delivery position `(epoch, lane, seq)` (PLP §0, §5.1). `epoch` is the emitter's epoch:
 * an Int drawn from a world-global mint, which abstracts `UUID.randomUUID()` — a fresh epoch
 * never collides with an earlier one, which is the property the notes rely on.
 */
data class Pos(val epoch: Int, val lane: Int, val seq: Int)

/** A lane `(epoch, laneKey)` (PLP §3.2). */
data class LaneKey(val epoch: Int, val lane: Int)

/** Identity of one delivery for dedup and for the world's effect log: a live position or a pull id. */
data class Key(val epoch: Int, val lane: Int, val seq: Int) {
    override fun toString() = if (epoch < 0) "pull#$seq" else "($epoch,$lane,$seq)"
    companion object { fun pull(id: Int) = Key(-1, -1, id) }
}

/** A frame on a link. */
sealed interface Msg

/**
 * A data frame. Live frames carry [pos]; pull baselines carry [pull] and no seq (PLP §5.4).
 * [wave] is the wave counter of the frame's root source (the wave plane, PLP §5.9);
 * [writer] is the replica `(epoch, writer)` stamp the Fence layer checks (COH §3.1; 0 = not
 * stamped); [baseline] is the content a pull baseline carries (a mergeable state).
 */
data class Data(
    val pos: Pos?,
    val payload: Int,
    val pull: Int? = null,
    val wave: Int = 0,
    val writer: Int = 0,
    val owned: Boolean = false,
    val baseline: Set<Int> = emptySet(),
) : Msg {
    val key: Key get() = if (pull != null) Key.pull(pull) else Key(pos!!.epoch, pos.lane, pos.seq)
    override fun toString() = buildString {
        append(key); append(":p").append(payload)
        if (wave != 0) append(":w").append(wave)
        if (writer != 0) append(":wr").append(writer)
        if (owned) append(":owned")
        if (baseline.isNotEmpty()) append(":base").append(baseline)
    }
}

/** `ReBaseline(supersedes = epoch, supersede = true)` (PLP §5.7, COH §1 "Transitions"). */
data class ReBaseline(val epoch: Int) : Msg { override fun toString() = "ReBaseline(e$epoch)" }

/** Management-band control signals that layers handle (COH §2.4). */
sealed interface Ctl
data object Suspend : Ctl
data object Resume : Ctl
data class Designate(val writer: Int) : Ctl
/** The journaled epoch rotation of a succession RESTART (COH §2.4, migration step 5). */
data class Restart(val epoch: Int) : Ctl
