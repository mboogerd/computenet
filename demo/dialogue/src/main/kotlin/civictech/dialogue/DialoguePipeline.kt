package civictech.dialogue

import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graphOf
import civictech.cell.graph.lookupOrThrow
import civictech.cell.graph.refAs
import civictech.cell.host.ManagedHost

/**
 * The AGO1 dialogue pipeline's graph spec (epic computenet-2aw §2.2 stage 1).
 *
 * At this feature's stage the graph is exactly one cell: the transcript
 * ingress. Segmentation and extraction (F2), minting (F3) and the applier
 * (F4) extend this spec by spawning further cells and linking them off
 * [Refs.utterances]' outlet; nothing here is meant to stay a single node.
 *
 * The pipeline owns the graph; [TranscriptSource] owns the *drive* and is
 * deliberately not a cell (2aw.F1-D1 / epic 2aw-D2). Its only contact with
 * this graph is the [SetOps] handle [utteranceOps] hands back.
 *
 * ### 2aw.F1-D2 — why `SetCell<Utterance>`, not `KeyedSetCell<String, Utterance>`
 *
 * The decision the feature left open is which ingress primitive carries the
 * admitted transcript. `SetCell<Utterance>`, because:
 *
 * 1. **A transcript accumulates; it does not upsert.** `KeyedSetCell`'s
 *    contract is that "the latest element under a key replaces the previous
 *    one" — a re-`put` retracts the old element in the same delta. Keyed by
 *    *speaker* (the AGO2-ready shape the decision names) that is actively
 *    wrong: admitting alice's turn 3 would retract her turn 1, so the
 *    admitted set would hold at most one utterance per speaker and the
 *    transcript would never exist downstream. Keyed by *utterance id* the
 *    retraction never fires (ids are unique), which buys the upsert machinery
 *    and uses none of it.
 * 2. **The driver already owns the memory `KeyedSetCell` exists to own.**
 *    `KeyedSetCell`'s stated value is owning the "what element was under this
 *    key" shadow index so demos need not keep it. [TranscriptSource] must
 *    keep an admitted-utterance ledger anyway — for the turn-order check
 *    ([AGO1-SRC-04]) and for reset's retraction sweep — so the shadow index
 *    would be a second copy of state that already exists one layer up.
 * 3. **Reset needs explicit retraction, which `SetOps.remove` gives
 *    directly.** `KeyedSetOps.remove(key)` would work too, but only by
 *    reintroducing the id→element mapping point 2 makes redundant.
 * 4. **Speaker-keying is not lost, it is moved downstream.** `Utterance`
 *    carries `speaker` as an attribute, so an AGO2 per-speaker view is a
 *    `GroupByCell { it.speaker }` over this outlet — a derived view, rather
 *    than a constraint baked into the ingress where it would cost the
 *    transcript.
 *
 * [AGO1-SRC-02]'s "exactly one effective add per id" comes from the tagged-set
 * algebra ([24-SET-01]) either way; it is not what decides this.
 */
object DialoguePipeline {

    /** Handles onto the pipeline's cells, minted by [build]. */
    data class Refs(
        /** The transcript ingress: the admitted utterance set. */
        val utterances: TypedRef<SetApi<Utterance>>,
    )

    /** Spawn the pipeline into [host] and return handles onto its cells. */
    fun build(host: ManagedHost): Refs {
        // Factories stay pure (replay-safe): each takes the resolved ref and
        // captures no instance. graphOf returns the block's result directly.
        val (refs, _) = graphOf(host.managementInlet) {
            val utterances = spawn("utterances") { ref -> SetCell<Utterance>(ref = ref) }
            Refs(utterances = utterances.refAs())
        }
        return refs
    }

    /**
     * The driving thread's one point of contact with the graph: the ingress
     * cell's [SetOps] inlet. [TranscriptSource] holds this and nothing else.
     */
    fun utteranceOps(host: ManagedHost, refs: Refs): SetOps<Utterance> =
        host.lookupOrThrow(refs.utterances).inlet.call
}
