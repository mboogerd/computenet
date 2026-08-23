package civictech.dialogue

import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.FlatMapSetApi
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graphOf
import civictech.cell.graph.lookupOrThrow
import civictech.cell.graph.refAs
import civictech.cell.host.ManagedHost
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractionAccounting
import civictech.dialogue.extract.ExtractionGate
import civictech.dialogue.extract.Extractor

/**
 * The AGO1 dialogue pipeline's graph spec (epic computenet-2aw §2.2
 * stages 1–3).
 *
 * ```
 *   utterances (SetCell<Utterance>)          ── stage 1, transcript ingress
 *     └─► segments (FlatMapSetCell, ::segment)         ── stage 2
 *           └─► extractedItems (FlatMapSetCell, gate)  ── stage 3
 * ```
 *
 * Minting (F3) and the applier (F4) extend this spec by spawning further
 * cells and linking them off [Refs.extractedItems]' outlet; nothing here is
 * meant to stay a three-node chain.
 *
 * Stages 2 and 3 are both `FlatMapSetCell`s, so the tagged-set algebra —
 * add-tag pass-through, effective-only emission, diamond dedup, and above
 * all **retraction** ([24-SET-01]/[24-SET-03]) — is inherited, not
 * reimplemented: retracting an utterance retracts its segments, and their
 * extracted items with them. That inheritance is what buys `[AGO1-EXTR-02]`
 * for free at the set level too: two segments whose extraction yields the
 * same item contribute one element carrying both tag sets.
 *
 * Wiring uses the DSL's typed [civictech.cell.graph.GraphBuilder.link]
 * rather than agora's routed idiom: this is a static extraction pipeline
 * whose topology is fixed at build time, so a compile-checked port link is
 * both sufficient and preferable (epic design notes).
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
        /** Stage 2: the segments derived from the admitted utterances. */
        val segments: TypedRef<FlatMapSetApi<Utterance, Segment>>,
        /** Stage 3: the well-formed items extracted from those segments. */
        val extractedItems: TypedRef<FlatMapSetApi<Segment, ExtractedItem>>,
    )

    /**
     * What [build] hands back: the graph handles, plus the extraction
     * ledger the gate writes to.
     *
     * [accounting] is not a ref because it is not cell state — it is the
     * pipeline's status surface ([AGO1-EXTR-06] "visible on the status
     * surface"), read from outside the graph. F5 serves it over HTTP; the
     * tests read it directly.
     */
    data class Built(
        val refs: Refs,
        val accounting: ExtractionAccounting,
    )

    /**
     * Spawn the pipeline into [host] and return handles onto its cells.
     *
     * [extractor] is required rather than defaulted: it is the determinism
     * firewall ([AGO1-EXTR-01], epic 2aw-D4), and which extractor a pipeline
     * runs under is exactly the decision that must never be made silently by
     * a default. Callers pass
     * [civictech.dialogue.extract.RuleExtractor] for the zero-dependency
     * demo path, or a
     * [civictech.dialogue.extract.CassetteExtractor] for a gating test.
     *
     * The extractor is wrapped in an [ExtractionGate] before it reaches the
     * cell: `FlatMapSetCell` requires a pure, total mapper, and an
     * `Extractor` is neither. See [ExtractionGate]'s KDoc for the full
     * reconciliation.
     */
    fun build(host: ManagedHost, extractor: Extractor): Built {
        val gate = ExtractionGate(extractor)
        // Factories stay pure (replay-safe): each takes the resolved ref and
        // captures no instance. graphOf returns the block's result directly.
        // `gate` and `::segment` are pure functions, not cell state — the
        // same values on every replay of this spec.
        val (refs, _) = graphOf(host.managementInlet) {
            val utterances = spawn("utterances") { ref -> SetCell<Utterance>(ref = ref) }
            val segments = spawn("segments") { ref ->
                FlatMapSetCell<Utterance, Segment>(ref = ref, f = ::segment)
            }
            val extractedItems = spawn("extractedItems") { ref ->
                FlatMapSetCell<Segment, ExtractedItem>(ref = ref, f = gate::extract)
            }
            link(utterances.cell.outlet, segments.cell.inlet)
            link(segments.cell.outlet, extractedItems.cell.inlet)
            Refs(
                utterances = utterances.refAs(),
                segments = segments.refAs(),
                extractedItems = extractedItems.refAs(),
            )
        }
        return Built(refs, gate.accounting)
    }

    /**
     * The driving thread's one point of contact with the graph: the ingress
     * cell's [SetOps] inlet. [TranscriptSource] holds this and nothing else.
     */
    fun utteranceOps(host: ManagedHost, refs: Refs): SetOps<Utterance> =
        host.lookupOrThrow(refs.utterances).inlet.call
}
