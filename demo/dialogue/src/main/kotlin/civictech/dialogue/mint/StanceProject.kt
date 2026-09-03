package civictech.dialogue.mint

import civictech.cell.data.Aggregator
import civictech.dialogue.ClaimKey
import civictech.dialogue.ProjectedStance
import civictech.dialogue.Utterance
import civictech.dialogue.extract.ExtractedStance
import java.io.Serializable

/**
 * One [ExtractedStance] lifted into the (speaker, claim key) space `StanceProject`
 * folds over (epic computenet-2aw §2.2 stage 6): the claim text canonicalized
 * through [claimKey] — the one canonicalization seam (2aw.F3-D1) — joined
 * with the *event* order ([turn]) of the utterance that carried it.
 *
 * `[AGO1-STANCE-01]` requires last-writer-wins by **event** order, not
 * *arrival* order: `ExtractedStance` alone carries no turn, so this row is
 * produced by a `JoinSetCell` against the `utterances` ingress on
 * `utteranceId == Utterance.id` — the only place `turn` is available. That
 * join is why the projection is a pure function of the admitted utterance
 * set, independent of the order utterances were admitted in.
 */
data class StanceJoinRow(
    val speaker: String,
    val key: ClaimKey,
    val value: Double,
    val turn: Int,
    val utteranceId: String,
)

/**
 * The `GroupByCell` aggregate [StanceProject.StanceAggregator] folds
 * [StanceJoinRow]s into: the LWW-selected `(turn, utteranceId, value)`
 * triple's `value`, plus the winning `turn`/`utteranceId` for tie-break
 * transparency and testing.
 *
 * Not [ProjectedStance] itself, for the same reason [ClaimAggregate] is not
 * [civictech.dialogue.CanonicalClaim]: `GroupByCell`'s `keyFn` and
 * `aggregator` are independent, so [Aggregator.value] never sees the group's
 * `(speaker, ClaimKey)` key — [StanceProject.projectedStance] assembles the
 * key-bearing type at read time.
 */
data class StanceAggregate(val value: Double, val turn: Int, val utteranceId: String)

/**
 * StanceProject (epic computenet-2aw §2.2 stage 6, [AGO1-STANCE-01]/-02):
 * folds the stance leg, joined against the utterances ingress for event
 * order, into one last-writer-wins [ProjectedStance] per (speaker, claim
 * key).
 *
 * ### Group-death-as-clear (2aw.F3, [AGO1-STANCE-02] / BS-07 projection half)
 *
 * The decided encoding: when the last extraction supporting a (speaker,
 * key) pair is retracted, `GroupByCell`'s ordinary group death removes the
 * `(speaker, key)` entry from the `MapDelta` outlet — no
 * `ProjectedStance(value = null)` sentinel element is ever emitted onto this
 * stream. [ProjectedStance.value] stays nullable only for the read surface
 * an applier assembles from this map (present entry → non-null value, absent
 * entry → no stance); this fold's own outlet only ever carries live winners.
 * The removal delta itself IS the cleared-never-stale signal an applier
 * consumes: a stale value can never be observed because there is no delta at
 * all that would leave one behind.
 */
object StanceProject {

    /** The `JoinSetCell` combiner: lift one (stance, utterance) pair into event-order space. */
    fun joinRow(stance: ExtractedStance, utterance: Utterance): StanceJoinRow =
        StanceJoinRow(
            speaker = stance.speaker,
            key = claimKey(stance.claimText),
            value = stance.value,
            turn = utterance.turn,
            utteranceId = stance.utteranceId,
        )

    /** Assembles the key-bearing [ProjectedStance] from a `(speaker, key)` pair and its aggregate. */
    fun projectedStance(key: Pair<String, ClaimKey>, aggregate: StanceAggregate): ProjectedStance =
        ProjectedStance(claim = key.second, speaker = key.first, value = aggregate.value)

    /**
     * The `GroupByCell` [Aggregator] over [StanceJoinRow]s contributing to one
     * `(speaker, ClaimKey)` group.
     *
     * [value] is a pure function of the LIVE contributing set ([24-AGG-01]):
     * it selects the triple with the greatest `turn` — event order, per
     * `[AGO1-STANCE-01]` — tie-broken by the lexicographically greatest
     * `utteranceId` for a total, deterministic order. Because the selection
     * reads off the live *membership*, not off which contribution arrived
     * last, two admission orders of the same live set select the same
     * winner — this is what makes it last-writer-wins **by event order**
     * rather than by arrival order.
     */
    class StanceAggregator : Aggregator<StanceJoinRow, StanceAggregate, StanceAggregator.Acc> {
        /** One contributing stance's LWW-relevant triple. */
        data class Contribution(val turn: Int, val utteranceId: String, val value: Double) : Serializable

        /** The live contributing set — same idiom as [ClaimMint.ClaimAggregator.Acc]. */
        data class Acc(val contributions: Set<Contribution>) : Serializable

        private val winnerOrder = compareBy<Contribution> { it.turn }.thenBy { it.utteranceId }

        override fun empty(): Acc = Acc(emptySet())

        override fun insert(acc: Acc, element: StanceJoinRow): Acc =
            Acc(acc.contributions + Contribution(element.turn, element.utteranceId, element.value))

        override fun retract(acc: Acc, element: StanceJoinRow): Acc =
            Acc(acc.contributions - Contribution(element.turn, element.utteranceId, element.value))

        override fun value(acc: Acc): StanceAggregate {
            val winner = acc.contributions.maxWithOrNull(winnerOrder)
                ?: error("value() called on an empty group — GroupByCell must retract the group instead")
            return StanceAggregate(value = winner.value, turn = winner.turn, utteranceId = winner.utteranceId)
        }
    }
}
