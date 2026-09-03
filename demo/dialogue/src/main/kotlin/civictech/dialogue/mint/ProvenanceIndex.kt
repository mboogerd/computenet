package civictech.dialogue.mint

import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import civictech.dialogue.extract.ExtractedClaim
import java.io.Serializable

/**
 * One (claim key, justifying utterance) contribution — the element type
 * [ProvenanceIndex]'s claim-provenance `GroupByCell` folds with
 * `Aggregators.collectToSet` (epic computenet-2aw §2.2 stage 7,
 * [AGO1-PROV-01]).
 *
 * `key` is carried as a plain [String] (not [ClaimKey]) rather than
 * [ClaimKey] itself, for the same reason [RelationMint.RelationAggregator]'s
 * `Contribution` flattens its fields to plain `String`s: `Aggregators.collectToSet`
 * requires its element type to be a genuine `java.io.Serializable`, and a
 * `@JvmInline value class` like [ClaimKey] does not satisfy that once boxed
 * into a generic container — the accumulator (`HashSet<ClaimProvenanceEntry>`)
 * is exactly the kernel's snapshot/durability seam that requirement protects.
 * `key` is redundant with the fold's own group key once folded — it exists
 * only so `keyFn` has something to project *before* folding.
 */
data class ClaimProvenanceEntry(val key: String, val utteranceId: String) : Serializable

/** The relation-leg mirror of [ClaimProvenanceEntry]. */
data class RelationProvenanceEntry(val key: String, val utteranceId: String) : Serializable

/**
 * ProvenanceIndex (epic computenet-2aw §2.2 stage 7, [AGO1-PROV-01]/-03):
 * for every canonical claim and relation, the set of utterance ids currently
 * justifying it.
 *
 * ### 2aw.F3-D2 — a cell in the graph, not an applier-side map
 *
 * The epic's open question (b) asks whether the index should be a cell in
 * the graph or a map the applier maintains on the side. Decided: a cell.
 * The two `GroupByCell` folds below derive their per-key set from the SAME
 * tagged deltas [civictech.dialogue.mint.ClaimMint] and
 * [civictech.dialogue.mint.RelationMint] fold — so an utterance retraction
 * that removes a contribution from `canonicalClaims`/`canonicalRelations`
 * removes it from this index too, in the SAME reconciliation
 * ([AGO1-PROV-03]), by the kernel's ordinary tagged-set retraction rather
 * than by any code here re-deriving "what changed" from a diff. An
 * applier-side map would have to duplicate that derivation by hand for every
 * future write path; a cell gets it for free from the graph it already sits
 * in.
 *
 * This is deliberately redundant with the `fromUtterances` embedded in
 * [civictech.dialogue.CanonicalClaim]/[civictech.dialogue.CanonicalRelation]
 * themselves — accepted, per the task's decided direction: the index is the
 * *read-side* deliverable `[AGO1-PROV-01]` names, independent of how the
 * canonical types happen to be shaped, so a future change to either shape
 * does not have to also decide whether provenance still has a home.
 */
object ProvenanceIndex {

    /** The claim-leg `FlatMapSetCell` mapper: one entry per (extracted claim, its key). */
    fun claimEntry(claim: ExtractedClaim): ClaimProvenanceEntry =
        ClaimProvenanceEntry(key = claimKey(claim.text).value, utteranceId = claim.utteranceId)

    /** The relation-leg `FlatMapSetCell` mapper, over the *resolvable* candidate stream. */
    fun relationEntry(candidate: RelationCandidate): RelationProvenanceEntry =
        RelationProvenanceEntry(key = candidate.relationKey.value, utteranceId = candidate.utteranceId)

    /** Assembles a claim's justifying utterance ids from its fold's raw aggregate. */
    fun claimProvenance(aggregate: Set<ClaimProvenanceEntry>): Set<String> =
        aggregate.map { it.utteranceId }.toSet()

    /** Assembles a relation's justifying utterance ids from its fold's raw aggregate. */
    fun relationProvenance(aggregate: Set<RelationProvenanceEntry>): Set<String> =
        aggregate.map { it.utteranceId }.toSet()
}
