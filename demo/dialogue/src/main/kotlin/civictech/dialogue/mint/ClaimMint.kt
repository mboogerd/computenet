package civictech.dialogue.mint

import civictech.cell.data.Aggregator
import civictech.dialogue.CanonicalClaim
import civictech.dialogue.ClaimKey
import civictech.dialogue.extract.ExtractedClaim
import java.io.Serializable

/**
 * The per-[ClaimKey] aggregate [ClaimMint.ClaimAggregator] folds
 * [ExtractedClaim]s into (epic computenet-2aw §2.2 stage 4): the union of
 * utterance ids that currently justify the claim, plus a deterministic
 * representative text.
 *
 * Not [CanonicalClaim] itself: `GroupByCell`'s `keyFn` and `aggregator` are
 * independent, so [Aggregator.value] never sees the group's key — the
 * key-bearing type is assembled from a [ClaimKey] and this aggregate at read
 * time, by [ClaimMint.canonicalClaim].
 */
data class ClaimAggregate(val text: String, val fromUtterances: Set<String>)

/**
 * ClaimMint (epic computenet-2aw §2.2 stage 4, [AGO1-MINT-01]/-02/-03): folds
 * the `extractedClaims` stream into one [ClaimAggregate] per distinct
 * [ClaimKey] via a kernel `GroupByCell`, keyed by [claimKey].
 */
object ClaimMint {
    /** Assembles the key-bearing [CanonicalClaim] from a [ClaimKey] and its [ClaimAggregate]. */
    fun canonicalClaim(key: ClaimKey, aggregate: ClaimAggregate): CanonicalClaim =
        CanonicalClaim(key = key, text = aggregate.text, fromUtterances = aggregate.fromUtterances)

    /**
     * The `GroupByCell` [Aggregator] over [ExtractedClaim] contributing to
     * one [ClaimKey]. Implemented here rather than in the kernel — an
     * `Aggregator` is a kernel interface meant to be implemented by callers,
     * as `WilsonAggregator` does in
     * `demo/backlog-triage/.../Ranking.kt` — so this is not a kernel change.
     *
     * [value] is a pure function of the LIVE contributing set, never of
     * admission order ([AGO1-EXTR-03] / [24-AGG-01]): the representative
     * text is the lexicographically least contributing text, so two
     * different arrival orders of the same live set produce the same
     * aggregate. `GroupByCell` retracts the group (and this aggregate stops
     * being emitted) exactly when the last contributing element is retracted
     * — [AGO1-MINT-03] is the kernel's group-death semantics, not
     * reimplemented here.
     */
    class ClaimAggregator : Aggregator<ExtractedClaim, ClaimAggregate, ClaimAggregator.Acc> {
        /** One contributing claim's text and the utterance it came from. */
        data class Contribution(val text: String, val utteranceId: String) : Serializable

        /** The live contributing set — a plain accumulator, not the multiset
         * discipline `Aggregators.minOf`/`topK` need: two elements sharing a
         * (text, utteranceId) pair are the same contribution, so set (not
         * multiset) membership is correct here. */
        data class Acc(val contributions: Set<Contribution>) : Serializable

        override fun empty(): Acc = Acc(emptySet())

        override fun insert(acc: Acc, element: ExtractedClaim): Acc =
            Acc(acc.contributions + Contribution(element.text, element.utteranceId))

        override fun retract(acc: Acc, element: ExtractedClaim): Acc =
            Acc(acc.contributions - Contribution(element.text, element.utteranceId))

        override fun value(acc: Acc): ClaimAggregate {
            val representative = acc.contributions.map { it.text }.distinct().sorted().first()
            val fromUtterances = acc.contributions.map { it.utteranceId }.toSet()
            return ClaimAggregate(text = representative, fromUtterances = fromUtterances)
        }
    }
}
