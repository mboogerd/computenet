package civictech.dialogue.mint

import civictech.agora.cell.Polarity
import civictech.cell.data.Aggregator
import civictech.dialogue.CanonicalRelation
import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractionGate
import java.io.Serializable

/**
 * One extracted relation lifted into claim-key space (epic computenet-2aw
 * §2.2 stage 5): endpoints canonicalized through [claimKey] — the one
 * canonicalization seam (2aw.F3-D1) — and polarity parsed through
 * [ExtractionGate.parsePolarity].
 *
 * This is the element type of the whole relation leg: the rejected set, both
 * pending/resolvable semijoins, and the canonical fold all carry it.
 * [relationKey] is derived here rather than at the fold so that a candidate's
 * identity travels with it and the `GroupByCell`'s `keyFn` is a projection,
 * not a second derivation.
 *
 * `utteranceId` is deliberately part of the value: it is what makes two
 * utterances asserting the same relation two *contributions* to one canonical
 * relation (and so what [AGO1-REL-02] retracts one of), rather than one
 * deduplicated element.
 */
data class RelationCandidate(
    val relationKey: RelationKey,
    val sourceKey: ClaimKey,
    val targetKey: ClaimKey,
    val polarity: Polarity,
    val utteranceId: String,
) {
    /**
     * The key-level self-relation predicate ([AGO1-REL-04]).
     *
     * `ExtractionGate.malformedReason` already rejects *textual*
     * self-relations, and its KDoc explicitly leaves this case open: two
     * textually different strings can canonicalize to one [ClaimKey], and the
     * gate passes those through. Nothing else implements this rule.
     */
    val isSelfRelation: Boolean get() = sourceKey == targetKey
}

/**
 * The per-[RelationKey] aggregate [RelationMint.RelationAggregator] folds
 * [RelationCandidate]s into: the endpoint/polarity triple the key encodes,
 * plus the union of utterance ids that currently justify the relation.
 *
 * Carries the triple for the same reason [ClaimAggregate] carries a
 * representative text: `GroupByCell`'s `keyFn` and `aggregator` are
 * independent, so [Aggregator.value] never sees the group's key and the
 * key-bearing [CanonicalRelation] is assembled at read time by
 * [RelationMint.canonicalRelation].
 */
data class RelationAggregate(
    val source: ClaimKey,
    val target: ClaimKey,
    val polarity: Polarity,
    val fromUtterances: Set<String>,
)

/**
 * RelationMint (epic computenet-2aw §2.2 stage 5, [AGO1-REL-01]..[AGO1-REL-04]):
 * the derivation of [RelationCandidate]s from the extracted-relation leg, the
 * key-level self-relation rejection, and the canonical fold over the
 * *resolvable* candidates.
 *
 * The pending/resolvable split itself (2aw.F3-D3) is not here: it is held by
 * the graph, as two chained `SemiJoinCell`s in
 * [civictech.dialogue.DialoguePipeline] against the minted-claim-key set. A
 * pending relation is simply a candidate the semijoin holds out of its output;
 * when the endpoint key later appears on the right side the row enters with no
 * fresh left tag, which is precisely why `SemiJoinCell` mints output tags per
 * entry. Extraction is never re-invoked to resolve one.
 */
object RelationMint {

    /**
     * Lift one [ExtractedRelation] into claim-key space, or nothing if its
     * polarity does not parse.
     *
     * `ExtractionGate.malformedReason` guarantees every relation reaching the
     * graph has a parseable polarity, so the null branch is unreachable in the
     * wired pipeline. It is expressed as a `listOfNotNull` rather than a `!!`
     * because this function is a `FlatMapSetCell` mapper: it must be **total**
     * (the cell re-applies it to translate removals), and a mapper that threw
     * on a del translation would fault a wave that is not even the offending
     * segment's own arrival.
     */
    fun candidates(relation: ExtractedRelation): List<RelationCandidate> {
        val polarity = ExtractionGate.parsePolarity(relation.polarity) ?: return emptyList()
        val source = claimKey(relation.sourceText)
        val target = claimKey(relation.targetText)
        return listOf(
            RelationCandidate(
                relationKey = relationKey(source, target, polarity),
                sourceKey = source,
                targetKey = target,
                polarity = polarity,
                utteranceId = relation.utteranceId,
            ),
        )
    }

    /**
     * The deterministic encoding of a relation's identity triple
     * ([AGO1-REL-01]: "one canonical relation per distinct (source key, target
     * key, polarity)").
     *
     * Length-prefixed rather than plain-delimited, so the encoding is
     * **injective**: claim keys are canonicalized free text and may contain any
     * delimiter one might pick, and a non-injective encoding would silently
     * merge two distinct relations into one group.
     */
    fun relationKey(source: ClaimKey, target: ClaimKey, polarity: Polarity): RelationKey =
        RelationKey(
            "${source.value.length}:${source.value}|" +
                "${target.value.length}:${target.value}|" +
                polarity.name,
        )

    /** Assembles the key-bearing [CanonicalRelation] from a [RelationKey] and its aggregate. */
    fun canonicalRelation(key: RelationKey, aggregate: RelationAggregate): CanonicalRelation =
        CanonicalRelation(
            key = key,
            source = aggregate.source,
            target = aggregate.target,
            polarity = aggregate.polarity,
            fromUtterances = aggregate.fromUtterances,
        )

    /**
     * The `GroupByCell` [Aggregator] over the [RelationCandidate]s contributing
     * to one [RelationKey] — the same aggregator family as
     * [ClaimMint.ClaimAggregator], and implemented here for the same reason
     * (`Aggregator` is a kernel interface meant to be implemented by callers).
     *
     * [value] is a pure function of the LIVE contributing set, never of
     * admission order: every contribution to one key carries the same triple by
     * construction (the key encodes it injectively), and the deterministic
     * `sorted().first()` makes that independent of which contribution arrived
     * first even if it did not. `GroupByCell` retracts the group when the last
     * contributing candidate is retracted — that group death IS [AGO1-REL-02]'s
     * *pipeline* half, kernel semantics rather than something reimplemented
     * here. (The applier-side influence retraction at the agora target is F4's
     * half of the same requirement.)
     */
    class RelationAggregator :
        Aggregator<RelationCandidate, RelationAggregate, RelationAggregator.Acc> {

        /**
         * One contributing candidate, flattened to plain [String]s: [Acc] is
         * `java.io.Serializable` (the kernel's snapshot/durability seam), and
         * `ClaimKey`/`Polarity` are not.
         */
        data class Contribution(
            val source: String,
            val target: String,
            val polarity: String,
            val utteranceId: String,
        ) : Serializable

        /** The live contributing set — a plain accumulator, as [ClaimMint.ClaimAggregator.Acc] is. */
        data class Acc(val contributions: Set<Contribution>) : Serializable

        override fun empty(): Acc = Acc(emptySet())

        override fun insert(acc: Acc, element: RelationCandidate): Acc =
            Acc(acc.contributions + contributionOf(element))

        override fun retract(acc: Acc, element: RelationCandidate): Acc =
            Acc(acc.contributions - contributionOf(element))

        override fun value(acc: Acc): RelationAggregate = RelationAggregate(
            source = ClaimKey(acc.contributions.map { it.source }.distinct().sorted().first()),
            target = ClaimKey(acc.contributions.map { it.target }.distinct().sorted().first()),
            polarity = Polarity.valueOf(acc.contributions.map { it.polarity }.distinct().sorted().first()),
            fromUtterances = acc.contributions.map { it.utteranceId }.toSet(),
        )

        private fun contributionOf(element: RelationCandidate) = Contribution(
            source = element.sourceKey.value,
            target = element.targetKey.value,
            polarity = element.polarity.name,
            utteranceId = element.utteranceId,
        )
    }
}
