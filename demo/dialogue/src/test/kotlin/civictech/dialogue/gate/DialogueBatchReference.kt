package civictech.dialogue.gate

import civictech.agora.BatchReference
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import civictech.dialogue.Utterance
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.Extractor
import civictech.dialogue.extract.ExtractionGate
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import civictech.dialogue.segment

/**
 * The **fold half** of BS-10's batch reference (2aw.F6-D8; the solve half is
 * `:demo:agora`'s [BatchReference], per 2aw.F6-D1): a single straight-line
 * pass from a set of live [Utterance]s to the argumentation topology the
 * pipeline should have produced from exactly that set.
 *
 * ### Independence is the whole point
 *
 * This file **must not** import `civictech.dialogue.DialoguePipeline`,
 * `civictech.dialogue.apply.GraphApplier`, or any `civictech.cell.*`
 * operator — no `FlatMapSetCell`, `SemiJoinCell`, `GroupByCell`,
 * `JoinSetCell`, no `SetDelta`/`MapDelta`, no host. A reference that reached
 * for the pipeline's own machinery would be a second copy of the
 * implementation under test and would prove nothing about it.
 *
 * The one `civictech.cell.*` import is [CellRef], which is the *identifier*
 * [BatchReference.solve] keys its topology by and [BindingTable.refFor]
 * returns — a name, not an operator, and unavoidable in the signature.
 *
 * What it *does* reuse is the named seam for each rule, so the reference
 * restates the rules rather than a second guess at them: [segment],
 * [ExtractionGate.malformedReason], [claimKey],
 * [RelationMint.candidates]/[RelationMint.relationKey], and
 * [BindingTable.refFor]. Those are the single blessed definitions of
 * "segment", "malformed", "claim identity", "relation identity" and "ref for
 * a key"; duplicating them here would make this a test of whether two copies
 * of a regex agree.
 *
 * ### The rules, restated in one pass
 *
 * For a live utterance set `L`:
 *
 * 1. **Segment** every utterance ([segment]) and **extract** each segment
 *    through [Extractor]. A segment whose extraction throws (a cassette
 *    miss) contributes nothing — the [ExtractionGate]'s failed-segment
 *    outcome, restated.
 * 2. **Drop malformed items** ([ExtractionGate.malformedReason] non-null).
 * 3. **Minted claim keys** = `claimKey(text)` of every surviving
 *    [ExtractedClaim]. Nothing else mints a claim key — the pipeline's
 *    `claimKeys` stage hangs off the claim leg alone.
 * 4. **Relations** = [RelationMint.candidates] of every surviving
 *    [ExtractedRelation], minus key-level self-relations
 *    ([civictech.dialogue.mint.RelationCandidate.isSelfRelation],
 *    [AGO1-REL-04]), minus any whose source or target key is not minted (the
 *    pending/resolvable semijoin pair, stages 5d/5e — a candidate held out
 *    there is pending, not dropped, and it enters as soon as both endpoints
 *    are minted). One canonical relation per [RelationMint.relationKey].
 * 5. **Stances** = per `(speaker, claimKey)`, the surviving [ExtractedStance]
 *    with the greatest `(turn, utteranceId)` — last writer wins by **event**
 *    order, where `turn` comes from the live utterance the item's
 *    `utteranceId` names (the pipeline gets it from a join against the
 *    utterance ingress, which is why an item whose utterance is not live
 *    contributes nothing). A stance on a key that was never minted is
 *    dropped here, because the applier cannot write it to a claim that does
 *    not exist.
 *
 * The emitted topology is `refFor(claimKey) -> NodeSpec(stances)` and
 * `refFor(relationKey) -> NodeSpec(polarity, source, target)`.
 */
object DialogueBatchReference {

    /** The fold's output: the canonical key sets plus the solvable topology. */
    data class Folded(
        val claimKeys: Set<ClaimKey>,
        val relationKeys: Set<RelationKey>,
        val nodes: Map<CellRef, BatchReference.NodeSpec>,
    )

    /**
     * The signature the task prescribes: the topology alone, for callers that
     * only want to hand it to [BatchReference.solve].
     */
    fun topology(extractor: Extractor, live: Collection<Utterance>): Map<CellRef, BatchReference.NodeSpec> =
        fold(extractor, live).nodes

    /** The full fold — the key sets are what BS-10 compares against `BindingTable`. */
    fun fold(extractor: Extractor, live: Collection<Utterance>): Folded {
        // (1)+(2) one pass over the live utterances: segment, extract, drop
        // malformed. `item to utterance` keeps the event order the stance
        // rule needs without a second traversal.
        val items = live.flatMap { utterance ->
            segment(utterance).flatMap { segmentOf ->
                val extracted = try {
                    extractor.extract(segmentOf)
                } catch (failure: Throwable) {
                    // A failed segment contributes nothing and never escapes.
                    emptyList()
                }
                extracted.filter { ExtractionGate.malformedReason(it) == null }.map { it to utterance }
            }
        }

        // (3) minted claim keys.
        val claimKeys = items.mapNotNull { (item, _) -> (item as? ExtractedClaim)?.let { claimKey(it.text) } }.toSet()

        // (4) relations: non-self, both endpoints minted, one per relation key.
        data class Relation(val source: ClaimKey, val target: ClaimKey, val polarity: Polarity)

        val relations = LinkedHashMap<RelationKey, Relation>()
        items.forEach { (item, _) ->
            if (item !is ExtractedRelation) return@forEach
            RelationMint.candidates(item).forEach { candidate ->
                if (candidate.isSelfRelation) return@forEach
                if (candidate.sourceKey !in claimKeys || candidate.targetKey !in claimKeys) return@forEach
                relations[RelationMint.relationKey(candidate.sourceKey, candidate.targetKey, candidate.polarity)] =
                    Relation(candidate.sourceKey, candidate.targetKey, candidate.polarity)
            }
        }

        // (5) stances: LWW by (turn, utteranceId) over the live contributions.
        data class Winner(val turn: Int, val utteranceId: String, val value: Double)

        val winners = mutableMapOf<Pair<String, ClaimKey>, Winner>()
        val byId = live.associateBy { it.id }
        items.forEach { (item, _) ->
            if (item !is ExtractedStance) return@forEach
            val key = claimKey(item.claimText)
            if (key !in claimKeys) return@forEach
            val carrier = byId[item.utteranceId] ?: return@forEach
            val entry = item.speaker to key
            val candidate = Winner(carrier.turn, item.utteranceId, item.value)
            val current = winners[entry]
            if (current == null ||
                candidate.turn > current.turn ||
                (candidate.turn == current.turn && candidate.utteranceId > current.utteranceId)
            ) {
                winners[entry] = candidate
            }
        }

        val stancesByClaim = winners.entries.groupBy({ it.key.second }, { it.key.first to it.value.value })

        val nodes = LinkedHashMap<CellRef, BatchReference.NodeSpec>()
        claimKeys.forEach { key ->
            nodes[BindingTable.refFor(key)] =
                BatchReference.NodeSpec(stances = stancesByClaim[key].orEmpty().toMap())
        }
        relations.forEach { (key, relation) ->
            nodes[BindingTable.refFor(key)] = BatchReference.NodeSpec(
                polarity = relation.polarity,
                source = BindingTable.refFor(relation.source),
                target = BindingTable.refFor(relation.target),
            )
        }

        return Folded(claimKeys = claimKeys, relationKeys = relations.keys.toSet(), nodes = nodes)
    }
}
