package civictech.dialogue

import civictech.cell.data.SetOps

/**
 * How fast a replay admits utterances (epic computenet-2aw §2.2 stage 1).
 *
 * Pacing is a property of the *driver*, never of the graph: nothing
 * downstream of the ingress can observe which pace produced an admission,
 * which is what makes [AGO1-SRC-07] true by construction rather than by test
 * (2aw.F1-D1).
 */
sealed interface Pace {

    /** Test mode: admit as fast as the calling thread can call the inlet. */
    data object AsFastAsPossible : Pace

    /**
     * Demo mode: sleep between admissions in proportion to the gap between
     * their `tsMillis` event-time attributes, divided by [factor]. A
     * [factor] of `1.0` replays at recorded speed; `10.0` is ten times
     * faster.
     *
     * The wait is derived from the utterances' explicit `tsMillis`
     * attributes, not from a clock reading — the kernel's Windows convention
     * ("there is no wall clock (P1)") governs *semantics*, and this sleep is
     * outside them. The driver thread is the only thing that blocks.
     */
    data class Wallclock(val factor: Double) : Pace {
        init {
            require(factor > 0.0) { "Pace.Wallclock factor must be > 0, was $factor" }
        }
    }
}

/**
 * The named error [AGO1-SRC-04]/BS-17 requires: an utterance was offered
 * whose turn ordinal is not greater than the last admitted one. The offer is
 * rejected whole — no `SetOps` call is made — so the admitted set is
 * unchanged.
 */
class OutOfOrderTurnException(
    val utteranceId: String,
    val offeredTurn: Int,
    val lastAdmittedTurn: Int,
) : IllegalStateException(
    "TranscriptSource rejected utterance '$utteranceId': turn $offeredTurn is not greater " +
        "than the last admitted turn $lastAdmittedTurn",
)

/**
 * The named error for the id-collision case computenet-gkol closes: an
 * offer re-uses an id already admitted with *different* content.
 * [AGO1-SRC-01] states id uniqueness as a property of the accepted input, so
 * such an offer is malformed input, and — mirroring [OutOfOrderTurnException]
 * for [AGO1-SRC-04]/BS-17 — is rejected whole rather than silently dropped or
 * treated as a replacement. No `SetOps` call is made, so the admitted set is
 * unchanged. (The identical-content case stays the [AGO1-SRC-02] silent
 * no-op; this exception is for a genuine content mismatch only.)
 */
class DuplicateUtteranceIdException(
    val utteranceId: String,
    val admitted: Utterance,
    val offered: Utterance,
) : IllegalStateException(
    "TranscriptSource rejected utterance '$utteranceId': an utterance with this id is " +
        "already admitted with different content (admitted=$admitted, offered=$offered)",
)

/**
 * The replayable transcript drive (epic computenet-2aw §2.2 stage 1).
 *
 * **A driver, not a cell** (2aw.F1-D1 / epic 2aw-D2). It owns turn ordering,
 * admission, range selection, pacing, stepping and reset; its only contact
 * with the dataflow graph is [SetOps.add]/[SetOps.remove] on the ingress
 * cell's inlet ([DialoguePipeline.utteranceOps]). Keeping all of that outside
 * the graph is what makes [AGO1-SRC-07] structural: the graph cannot tell a
 * paced replay from an unpaced one, because pacing never reaches it.
 *
 * **Admission rules**, in the order [offer] applies them:
 *
 * 1. **Identical re-admission is a no-op** [AGO1-SRC-02]. An utterance whose
 *    id is already admitted with byte-identical content is dropped by the
 *    driver — no `SetOps.add`, so no delta, so exactly one effective add ever
 *    reaches the graph for that id. The tagged set ([24-SET-01]) would keep
 *    *membership* right without this, but it would still mint a second
 *    add-tag and propagate a delta; the requirement is about effective adds,
 *    so the driver is the honest place to stop it.
 *
 *    This check runs **before** the turn check deliberately: a duplicate
 *    necessarily carries a turn ordinal equal to one already admitted, so
 *    rule 3 (the turn check) would otherwise reject it with an error where
 *    the specification asks for a silent no-op. The two clauses agree on the
 *    observable outcome (admitted set unchanged) and differ only in whether
 *    the caller is told; [AGO1-SRC-02]'s "no-op" wins for the exact-duplicate
 *    case, and [AGO1-SRC-04]'s error covers every other non-advancing turn
 *    *whose id is not already admitted* — an id already admitted is rule 2's,
 *    whatever its turn.
 *
 * 2. **Id reuse with different content is rejected** (computenet-gkol). An
 *    offer whose id is already admitted but whose content differs throws
 *    [DuplicateUtteranceIdException] and touches nothing — no `SetOps` call,
 *    admitted set unchanged. This runs in the same step as rule 1, before
 *    the turn check, so it also catches an id collision that happens to
 *    carry a strictly greater turn (which would otherwise pass rule 3
 *    unnoticed). [AGO1-SRC-01] states id uniqueness as a property of the
 *    accepted input, so a same-id/different-content offer is malformed
 *    input; this mirrors [AGO1-SRC-04]/BS-17's existing "reject with a named
 *    error, leave state unchanged" idiom rather than silently dropping the
 *    offer or replacing the admitted element (replacement is
 *    `KeyedSetCell`'s contract and would reopen 2aw.F1-D2, which F1 settled
 *    on `SetCell`).
 * 3. **Turns must strictly ascend** [AGO1-SRC-03]/[AGO1-SRC-04]. An offer
 *    whose turn is not greater than the last admitted turn throws
 *    [OutOfOrderTurnException] and touches nothing.
 *
 * **Incremental feeding** [AGO1-SRC-03]: [offer] is the whole admission
 * primitive and takes one utterance, so a caller can drive the source from a
 * stream that has no end yet. The [transcript] constructor argument is a
 * convenience for [replay] and [step] over an already-loaded file, not a
 * precondition — it defaults to empty.
 *
 * Not thread-safe: drive it from one thread, like the cells it feeds.
 */
class TranscriptSource(
    private val ops: SetOps<Utterance>,
    /**
     * The loaded transcript [replay] and [step] draw from, in file order.
     * Empty when the source is fed incrementally through [offer].
     *
     * A `var` because [load] replaces it on a running source (2aw.5-D11);
     * every read of it is inside this class.
     */
    private var transcript: List<Utterance> = emptyList(),
    /**
     * How [Pace.Wallclock] waits. Injectable so a test can assert pacing
     * behaviour without spending the wall-clock time; the default sleeps the
     * driving thread, which is legal precisely because that thread is
     * outside the graph.
     */
    private val sleeper: (Long) -> Unit = { millis -> if (millis > 0) Thread.sleep(millis) },
    /**
     * Utterances already admitted by a prior process, WITHOUT going through
     * [ops] (computenet-2aw.4.1, [AGO1-DUR-01] "admitted-utterance set
     * recovers"). After a WAL recovery the `utterances` `SetCell` already
     * holds these — a caller replaying a journal spawns the pipeline under
     * the same [DialoguePipeline.build] `namespace` and recovers the host
     * BEFORE constructing this source — but the driver's own ledger
     * ([admitted], [lastAdmittedTurn], the [offer] dedup/turn-order checks)
     * starts empty every time, since it lives here and not in the cell.
     * Seeding it from [recovered] re-synchronizes the driver with the cell
     * it is about to keep driving, with zero calls on [ops]: a second
     * `ops.add` for an utterance the cell already holds would mint a second
     * add-tag for it, which is exactly the divergence the ordering rule in
     * [civictech.cell.host.KeyedCells]'s KDoc (M10.1) warns a recovering
     * driver into.
     */
    recovered: Collection<Utterance> = emptyList(),
) {

    private val admittedInOrder = mutableListOf<Utterance>()
    private val admittedById = mutableMapOf<String, Utterance>()
    private var lastTurn: Int? = null

    /** Index into [transcript] that [step] reads next; moved by [replay]. */
    private var cursor: Int = 0

    init {
        // Seed the ledger from the recovered set, sorted into event order —
        // [recovered] carries no ordering guarantee of its own (it is likely
        // a SetCell's membership, unordered). admittedById/lastTurn end
        // exactly as if every recovered utterance had been offer()'d in turn
        // order, without the ops.add side effect offer() would otherwise make.
        recovered.sortedBy { it.turn }.forEach { utterance ->
            admittedInOrder += utterance
            admittedById[utterance.id] = utterance
            lastTurn = utterance.turn
        }
        // Advance the cursor past every recovered turn present in
        // [transcript], so a subsequent replay()/step() resumes after what
        // was already admitted rather than re-offering it (which offer()
        // would reject outright once it hits a non-advancing turn, or accept
        // as a same-id/same-content no-op if it got that far — neither is
        // "resume").
        seekPastAdmitted()
    }

    /**
     * Point [cursor] at the first transcript entry whose turn advances past
     * [lastTurn] — the seek both the constructor and [load] need, spelled
     * once so the two cannot drift apart.
     */
    private fun seekPastAdmitted() {
        cursor = transcript.indexOfFirst { it.turn > (lastTurn ?: Int.MIN_VALUE) }
            .let { if (it < 0) transcript.size else it }
    }

    /**
     * Replace the drawable transcript on a running source (2aw.5-D11).
     *
     * The admitted ledger is **untouched**: [admitted], [lastAdmittedTurn]
     * and the [offer] dedup/turn-order checks all survive, and the cursor is
     * re-seeked exactly as the constructor seeks it, so a subsequent
     * [step]/[replay] resumes after what was already admitted rather than
     * re-offering it. Loading is therefore not a reset — a caller that wants
     * the graph emptied calls [reset] first (and, at the runtime level,
     * reconciles).
     *
     * No `SetOps` call is made here: loading changes what the driver *can*
     * admit, never what it *has* admitted.
     */
    fun load(transcript: List<Utterance>) {
        this.transcript = transcript
        seekPastAdmitted()
    }

    /** The utterances admitted so far, in admission order. */
    val admitted: List<Utterance> get() = admittedInOrder.toList()

    /** The turn ordinal of the last admitted utterance, or `null` if none. */
    val lastAdmittedTurn: Int? get() = lastTurn

    /**
     * Offer one utterance for admission — the single admission primitive
     * every other drive method funnels through.
     *
     * @return `true` if it was admitted (one effective add reached the
     *   graph), `false` if it was an identical re-admission of an already
     *   admitted id (rule 1 above — a no-op, not an error).
     * @throws DuplicateUtteranceIdException if its id is already admitted
     *   with *different* content. Pipeline state is unchanged when this
     *   throws.
     * @throws OutOfOrderTurnException if its turn does not advance past the
     *   last admitted one. Pipeline state is unchanged when this throws.
     */
    fun offer(utterance: Utterance): Boolean {
        val existing = admittedById[utterance.id]
        if (existing != null) {
            if (existing == utterance) return false
            throw DuplicateUtteranceIdException(
                utteranceId = utterance.id,
                admitted = existing,
                offered = utterance,
            )
        }

        val last = lastTurn
        if (last != null && utterance.turn <= last) {
            throw OutOfOrderTurnException(
                utteranceId = utterance.id,
                offeredTurn = utterance.turn,
                lastAdmittedTurn = last,
            )
        }

        ops.add(utterance)
        admittedInOrder += utterance
        admittedById[utterance.id] = utterance
        lastTurn = utterance.turn
        return true
    }

    /**
     * Admit the next utterance from [transcript], advancing the cursor.
     *
     * @return the utterance admitted, or `null` when the transcript is
     *   exhausted.
     */
    fun step(): Utterance? {
        if (cursor >= transcript.size) return null
        val next = transcript[cursor++]
        return if (offer(next)) next else null
    }

    /**
     * Replay a turn range from [transcript] [AGO1-SRC-06].
     *
     * @param from the first turn ordinal to admit, inclusive.
     * @param to the last turn ordinal to admit, inclusive; `null` replays to
     *   the end of the transcript.
     * @param pace how to space the admissions; see [Pace]. The set admitted
     *   is identical whichever pace is used [AGO1-SRC-07].
     * @param afterAdmit invoked on the driving thread immediately after each
     *   [offer] that actually admitted — once per *effective* admission, in
     *   turn order, never for an identical re-admission (which is a no-op,
     *   rule 1) and never when [offer] throws. This is how a caller
     *   reconciles per utterance while pacing stays in the driver (epic
     *   §8/R4: quiescence-scoped per utterance); it runs *inside* the paced
     *   loop, so the next admission waits for it. [step] needs no equivalent
     *   — it already returns the admitted utterance (2aw.5-D5).
     */
    fun replay(
        from: Int,
        to: Int? = null,
        pace: Pace = Pace.AsFastAsPossible,
        afterAdmit: (Utterance) -> Unit = {},
    ) {
        cursor = transcript.indexOfFirst { it.turn >= from }.let { if (it < 0) transcript.size else it }

        var previousTs: Long? = null
        while (cursor < transcript.size) {
            val next = transcript[cursor]
            if (to != null && next.turn > to) break

            if (pace is Pace.Wallclock) {
                previousTs?.let { previous ->
                    sleeper(((next.tsMillis - previous).coerceAtLeast(0) / pace.factor).toLong())
                }
                previousTs = next.tsMillis
            }

            cursor++
            if (offer(next)) afterAdmit(next)
        }
    }

    /**
     * Retract every admitted utterance and clear driver state, so a fresh
     * replay can start from any turn.
     *
     * Scope note: this empties the *admitted set* only. Full graph emptiness
     * after reset ([AGO1-REPLAY-03] — the derived claims, relations and
     * stances too) is the applier feature's (F4); at this stage the ingress
     * set is the whole graph.
     */
    fun reset() {
        // Retract in reverse admission order so an observer watching the
        // retraction sweep sees the transcript unwind rather than shuffle.
        admittedInOrder.asReversed().forEach { ops.remove(it) }
        admittedInOrder.clear()
        admittedById.clear()
        lastTurn = null
        cursor = 0
    }
}
