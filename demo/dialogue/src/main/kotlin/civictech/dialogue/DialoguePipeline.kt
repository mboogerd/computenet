package civictech.dialogue

import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.FlatMapSetApi
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.JoinSetApi
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinApi
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graphOf
import civictech.cell.graph.lookupOrThrow
import civictech.cell.graph.refAs
import civictech.cell.host.ManagedHost
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.ExtractionAccounting
import civictech.dialogue.extract.ExtractionGate
import civictech.dialogue.extract.Extractor
import civictech.dialogue.mint.ClaimAggregate
import civictech.dialogue.mint.ClaimMint
import civictech.dialogue.mint.ClaimProvenanceEntry
import civictech.dialogue.mint.ProvenanceIndex
import civictech.dialogue.mint.RelationAggregate
import civictech.dialogue.mint.RelationCandidate
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.RelationProvenanceEntry
import civictech.dialogue.mint.StanceAggregate
import civictech.dialogue.mint.StanceJoinRow
import civictech.dialogue.mint.StanceProject
import civictech.dialogue.mint.claimKey

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
        /**
         * Stage 4a (F3, item-kind split — claim leg): the claim-kind subset
         * of [extractedItems]. The relation and stance legs belong to F3's
         * sibling tasks and are not built here.
         */
        val extractedClaims: TypedRef<FlatMapSetApi<ExtractedItem, ExtractedClaim>>,
        /**
         * Stage 4b (F3, ClaimMint): the [civictech.dialogue.ClaimKey] of
         * every currently-live [extractedClaims] element, deduplicated by
         * the tagged-set algebra — equivalently, the set of keys ClaimMint
         * currently has a canonical claim for (2aw.F3-D1). This is the
         * `SetDelta<ClaimKey>` the relation sibling's `SemiJoinCell`s
         * consume as their right side.
         */
        val claimKeys: TypedRef<FlatMapSetApi<ExtractedClaim, ClaimKey>>,
        /**
         * Stage 4c (F3, ClaimMint): one [ClaimAggregate] per distinct
         * [civictech.dialogue.ClaimKey] ([AGO1-MINT-01]/-02/-03). Pair a
         * `MapDelta` key with its aggregate through
         * [ClaimMint.canonicalClaim] to assemble a
         * [civictech.dialogue.CanonicalClaim].
         */
        val canonicalClaims: TypedRef<GroupByApi<ExtractedClaim, ClaimKey, ClaimAggregate>>,
        /**
         * Stage 5a (F3, item-kind split — relation leg): the relation-kind
         * subset of [extractedItems], the mirror of [extractedClaims].
         */
        val extractedRelations: TypedRef<FlatMapSetApi<ExtractedItem, ExtractedRelation>>,
        /**
         * Stage 5b (F3, RelationMint): every live relation lifted into
         * claim-key space by [RelationMint.candidates] — endpoints through
         * [civictech.dialogue.mint.claimKey], polarity parsed.
         */
        val relationCandidates: TypedRef<FlatMapSetApi<ExtractedRelation, RelationCandidate>>,
        /**
         * Stage 5c (F3, [AGO1-REL-04] KEY-LEVEL half): the candidates whose
         * source and target canonicalize to the same
         * [civictech.dialogue.ClaimKey] — a *derived* set, so retracting the
         * utterance behind a rejected relation retracts the rejection too. The
         * textual half of the same rule lives in
         * `ExtractionGate.malformedReason` and its ledger; this set is
         * deliberately not written into `ExtractionAccounting`, because a
         * mapper-side ledger write is exactly the seam `doc/demo-findings.md`
         * F-13 records as absent.
         */
        val rejectedRelations: TypedRef<FilterSetApi<RelationCandidate>>,
        /**
         * Stage 5d (F3, 2aw.F3-D3, [AGO1-REL-03] / BS-05): the non-self
         * candidates whose *source* key is a currently-minted claim key. A
         * candidate held out here is PENDING, not dropped — it enters as soon
         * as its endpoint is minted, with no re-extraction.
         */
        val sourceResolvedRelations: TypedRef<SemiJoinApi<RelationCandidate, ClaimKey>>,
        /**
         * Stage 5e (F3, 2aw.F3-D3): the second half of the pending/resolvable
         * split — [sourceResolvedRelations] narrowed to candidates whose
         * *target* key is also minted. This is the resolvable stream.
         */
        val resolvableRelations: TypedRef<SemiJoinApi<RelationCandidate, ClaimKey>>,
        /**
         * Stage 5f (F3, RelationMint): one [RelationAggregate] per distinct
         * [civictech.dialogue.RelationKey] over the resolvable stream
         * ([AGO1-REL-01]); group death when the last contributing utterance is
         * retracted is [AGO1-REL-02]'s pipeline half. Pair a `MapDelta` key
         * with its aggregate through [RelationMint.canonicalRelation] to
         * assemble a [civictech.dialogue.CanonicalRelation].
         */
        val canonicalRelations: TypedRef<GroupByApi<RelationCandidate, RelationKey, RelationAggregate>>,
        /**
         * Stage 6a (F3, item-kind split — stance leg): the stance-kind
         * subset of [extractedItems], the third mirror of [extractedClaims]
         * / [extractedRelations].
         */
        val extractedStances: TypedRef<FlatMapSetApi<ExtractedItem, ExtractedStance>>,
        /**
         * Stage 6b (F3, StanceProject, [AGO1-STANCE-01]): [extractedStances]
         * joined against the [utterances] ingress on `utteranceId ==
         * Utterance.id`, lifting each stance into event-order space (the
         * `turn` an `ExtractedStance` alone does not carry). This is what
         * makes the projection below last-writer-wins by EVENT order rather
         * than by admission order.
         */
        val stanceJoinRows: TypedRef<JoinSetApi<ExtractedStance, Utterance, StanceJoinRow>>,
        /**
         * Stage 6c (F3, StanceProject): one [StanceAggregate] per distinct
         * (speaker, [civictech.dialogue.ClaimKey]) pair
         * ([AGO1-STANCE-01]/-02). Pair a `MapDelta` key with its aggregate
         * through [StanceProject.projectedStance] to assemble a
         * [civictech.dialogue.ProjectedStance]. Group death — the last
         * supporting extraction retracted — removes the entry; see
         * [StanceProject]'s KDoc for why that removal itself is the
         * cleared-never-stale signal ([AGO1-STANCE-02]).
         */
        val projectedStances: TypedRef<GroupByApi<StanceJoinRow, Pair<String, ClaimKey>, StanceAggregate>>,
        /**
         * Stage 7a (F3, ProvenanceIndex, 2aw.F3-D2, [AGO1-PROV-01]): for
         * every currently-live [civictech.dialogue.ClaimKey], the set of
         * utterance ids justifying it — folded from the SAME
         * [extractedClaims] stream [canonicalClaims] folds, so an utterance
         * retraction that shrinks a canonical claim's provenance shrinks
         * this index's entry in the same reconciliation ([AGO1-PROV-03]).
         */
        val claimProvenance: TypedRef<GroupByApi<ClaimProvenanceEntry, ClaimKey, Set<ClaimProvenanceEntry>>>,
        /**
         * Stage 7b (F3, ProvenanceIndex): the relation-leg mirror of
         * [claimProvenance], folded from the *resolvable* candidate stream
         * [canonicalRelations] itself folds — so a relation returning to
         * PENDING (its endpoint's last claim retracted) removes its
         * provenance entry exactly when [canonicalRelations] removes the
         * relation.
         */
        val relationProvenance: TypedRef<GroupByApi<RelationProvenanceEntry, RelationKey, Set<RelationProvenanceEntry>>>,
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
     *
     * [namespace] (computenet-2aw.4.1, [AGO1-DUR-01]'s pipeline half): `null`
     * (the default) is byte-identical to today's behaviour — every spawn
     * below gets [IdentityBinding.FreshLogical], a random ref per build.
     * Non-null makes every pipeline cell's ref a pure function of
     * `"$namespace:$handle"` ([IdentityBinding.Exact], the same
     * `nameUUIDFromBytes` idiom [civictech.cell.host.KeyedCells] and
     * [civictech.dialogue.apply.BindingTable] use), so a WAL-recovered host
     * rebuilding this pipeline under the same namespace gets the same refs
     * its journal's frames were written against — the host's `cells[cellRef]`
     * lookup (`ManagedHost`) finds a live cell instead of dead-lettering.
     * Callers that never recover a journal (every test today) omit it.
     */
    fun build(host: ManagedHost, extractor: Extractor, namespace: String? = null): Built {
        val gate = ExtractionGate(extractor)
        // One seam for every spawn below: null namespace reproduces today's
        // FreshLogical default byte-for-byte; a namespace makes every handle's
        // ref `nameUUIDFromBytes("$namespace:$handle")` — deterministic and
        // restart-stable, never colliding across handles (the handle name is
        // part of the seed) or with BindingTable's `dialogue:claim:`/
        // `dialogue:relation:` prefixes or agora's `agora:hub` (disjoint
        // prefix families).
        fun identityFor(handle: String): IdentityBinding =
            if (namespace == null) {
                IdentityBinding.FreshLogical
            } else {
                IdentityBinding.Exact(CellRef(java.util.UUID.nameUUIDFromBytes("$namespace:$handle".toByteArray())))
            }
        // graphOf returns the block's result directly. The ingress and
        // segmentation factories still meet F1's strict bar — each takes the
        // resolved ref and captures no instance; `::segment` is a top-level
        // pure function.
        //
        // Stage 3's factory does NOT, and cannot: `gate::extract` is bound to
        // one ExtractionGate instance, and `GraphBuilder.spawn` records the
        // factory into the GraphSpec, so every re-lowering of this spec
        // re-spawns a cell over the SAME gate — sharing its outcome memo and
        // its accounting ledger. That sharing is deliberate and load-bearing:
        // the ledger is the status surface `build` hands back, so it must
        // outlive any one cell instance, and the shared memo is what keeps the
        // mapper observably pure across a del translation or a catch-up.
        // The caveat it buys: replaying this spec as a *second* graph in one
        // process would pool both graphs' extractions into one ledger. Nothing
        // in F2 replays it, and F5 — which serves the ledger — is where that
        // has to be decided.
        val (refs, _) = graphOf(host.managementInlet) {
            val utterances = spawn("utterances", identity = identityFor("utterances")) { ref -> SetCell<Utterance>(ref = ref) }
            val segments = spawn("segments", identity = identityFor("segments")) { ref ->
                FlatMapSetCell<Utterance, Segment>(ref = ref, f = ::segment)
            }
            val extractedItems = spawn("extractedItems", identity = identityFor("extractedItems")) { ref ->
                FlatMapSetCell<Segment, ExtractedItem>(ref = ref, f = gate::extract)
            }
            // Stage 4a (F3 item-kind split, claim leg): FlatMapSetCell with
            // listOfNotNull(item as? ExtractedClaim) rather than a FilterCell
            // + cast — FilterCell preserves the element type, so a cast hop
            // would still be needed after it. One hop per item kind; the
            // relation and stance legs are the sibling tasks'.
            val extractedClaims = spawn("extractedClaims", identity = identityFor("extractedClaims")) { ref ->
                FlatMapSetCell<ExtractedItem, ExtractedClaim>(ref = ref) { item ->
                    listOfNotNull(item as? ExtractedClaim)
                }
            }
            // Stage 4b (F3, 2aw.F3-D1): claimKey is the ONE canonicalization
            // seam — every claim-key derivation, here and in ClaimMint below,
            // goes through it.
            val claimKeys = spawn("claimKeys", identity = identityFor("claimKeys")) { ref ->
                FlatMapSetCell<ExtractedClaim, ClaimKey>(ref = ref) { claim ->
                    listOf(claimKey(claim.text))
                }
            }
            // Stage 4c (F3, ClaimMint): keyed fold over the SAME
            // extractedClaims stream as claimKeys above — both are pure hops
            // off one outlet, not a chain, so neither depends on the other's
            // liveness.
            val canonicalClaims = spawn("canonicalClaims", identity = identityFor("canonicalClaims")) { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { claim: ExtractedClaim -> claimKey(claim.text) },
                    aggregator = ClaimMint.ClaimAggregator(),
                )
            }
            // Stage 5a (F3 item-kind split, relation leg): the mirror of the
            // claim leg above — one pure hop per item kind off the SAME
            // extractedItems outlet, never a chain off the claim leg.
            val extractedRelations = spawn("extractedRelations", identity = identityFor("extractedRelations")) { ref ->
                FlatMapSetCell<ExtractedItem, ExtractedRelation>(ref = ref) { item ->
                    listOfNotNull(item as? ExtractedRelation)
                }
            }
            // Stage 5b: lift into claim-key space (endpoints through the one
            // claimKey seam, polarity parsed). RelationMint::candidates is a
            // top-level-equivalent pure function, so this factory captures
            // nothing.
            val relationCandidates = spawn("relationCandidates", identity = identityFor("relationCandidates")) { ref ->
                FlatMapSetCell<ExtractedRelation, RelationCandidate>(ref = ref, f = RelationMint::candidates)
            }
            // Stage 5c ([AGO1-REL-04] key-level): the self-relation split, as
            // two complementary FilterCells off one outlet. Both are derived
            // sets, so a retraction upstream retracts the rejection as well as
            // the candidate — which is why the rejected relations are recorded
            // HERE and not by a side effect into ExtractionAccounting (a
            // mapper-side ledger write is doc/demo-findings.md F-13's absent
            // seam, and no per-segment identity survives this far anyway).
            val rejectedRelations = spawn("rejectedRelations", identity = identityFor("rejectedRelations")) { ref ->
                FilterCell<RelationCandidate>(ref = ref) { it.isSelfRelation }
            }
            val nonSelfRelations = spawn("nonSelfRelations", identity = identityFor("nonSelfRelations")) { ref ->
                FilterCell<RelationCandidate>(ref = ref) { !it.isSelfRelation }
            }
            // Stages 5d/5e (2aw.F3-D3, [AGO1-REL-03] / BS-05): the
            // pending/resolvable split, held by the GRAPH. Two chained
            // semijoins against the minted-claim-key set — source first, then
            // target. A candidate whose endpoint is not yet minted is simply
            // held out of the semijoin's output; when the key later appears on
            // the right side the row enters with no fresh left tag (SemiJoin
            // mints output tags per entry by design), so nothing re-invokes
            // extraction to resolve it.
            //
            // emitOnFrontier stays at its ungated DEFAULT (false), against the
            // task's provisional direction, because this topology hits
            // WaveGate's phantom-expected-edge caveat (G-13) after all —
            // MEASURED, not argued.
            //
            // The shared-source premise does hold structurally: every inlet
            // here descends from `utterances` (left: extractedItems ->
            // extractedRelations -> relationCandidates -> nonSelfRelations;
            // right: extractedItems -> extractedClaims -> claimKeys), so this
            // reads like the shared-source diamond SemiJoinCell's KDoc scopes
            // the gate to. What the KDoc's diamond additionally assumes, and
            // this graph breaks, is that both arms CARRY the root's waves. The
            // item-kind split partitions each utterance's items across the two
            // arms: a claim-only utterance is a real delta on the right arm and
            // nothing at all on the left, a relation-only utterance the mirror
            // image. Each arm is therefore an expected edge for waves it
            // structurally never delivers — G-13's phantom expected edge,
            // arising here from the item-kind split rather than from two
            // independent roots.
            //
            // The kernel would normally rescue that with CP-A3's absorb-ack;
            // computenet-23bf established why it does not here. The ack is
            // EDGE-LOCAL and no plain operator relays it, so it survives only
            // when the absorbing cell links DIRECTLY into the gated inlet.
            // Both arms above are two hops deep (extractedRelations and
            // extractedClaims are the absorbers; relationCandidates ->
            // nonSelfRelations and claimKeys are pure hops below them), so
            // each ack dies before reaching the semijoin. Reproduced
            // minimally in :kernel's FrontierGatedEmissionTest (the
            // one-hop/two-hop disjoint-wave-arm pair) and written up as
            // doc/demo-findings.md F-15.
            //
            // Observed (task computenet-2aw.3.2, RelationMintTest): with
            // `emitOnFrontier = true` on BOTH semijoins, or on the first alone,
            // 4 of that test's 5 cases fail with an EMPTY canonical set at
            // quiescence — the gate holds the waves and the resolvable stream
            // never emits. (REL-04 asserts that nothing is minted, so a wedged
            // pipeline satisfies it vacuously.) Re-measured at 915d574a9 by
            // computenet-23bf. All five pass ungated. A gate that
            // withholds output at rest is disqualifying, so the default stands.
            //
            // What the ungated default leaves open is the transient the gate
            // exists for: admitting the utterance that mints a relation's last
            // endpoint can flicker the relation into and out of the canonical
            // fold within one wave, and F4's applier sits downstream of exactly
            // that. Filed as its own item rather than papered over here.
            val sourceResolvedRelations = spawn("sourceResolvedRelations", identity = identityFor("sourceResolvedRelations")) { ref ->
                SemiJoinCell<RelationCandidate, ClaimKey, ClaimKey>(
                    ref = ref,
                    leftKey = { it.sourceKey },
                    rightKey = { it },
                )
            }
            val resolvableRelations = spawn("resolvableRelations", identity = identityFor("resolvableRelations")) { ref ->
                SemiJoinCell<RelationCandidate, ClaimKey, ClaimKey>(
                    ref = ref,
                    leftKey = { it.targetKey },
                    rightKey = { it },
                )
            }
            // Stage 5f (RelationMint fold): one aggregate per distinct
            // (source, target, polarity) [AGO1-REL-01]; the kernel's group
            // death is [AGO1-REL-02]'s pipeline half.
            val canonicalRelations = spawn("canonicalRelations", identity = identityFor("canonicalRelations")) { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { candidate: RelationCandidate -> candidate.relationKey },
                    aggregator = RelationMint.RelationAggregator(),
                )
            }
            // Stage 6a (F3 item-kind split, stance leg): the third mirror of
            // the claim/relation legs — one pure hop off the SAME
            // extractedItems outlet.
            val extractedStances = spawn("extractedStances", identity = identityFor("extractedStances")) { ref ->
                FlatMapSetCell<ExtractedItem, ExtractedStance>(ref = ref) { item ->
                    listOfNotNull(item as? ExtractedStance)
                }
            }
            // Stage 6b (F3, StanceProject, [AGO1-STANCE-01]): join the stance
            // leg against the utterances ingress for event order (`turn`),
            // which ExtractedStance alone does not carry.
            val stanceJoinRows = spawn("stanceJoinRows", identity = identityFor("stanceJoinRows")) { ref ->
                JoinSetCell<ExtractedStance, Utterance, String, StanceJoinRow>(
                    ref = ref,
                    leftKey = { it.utteranceId },
                    rightKey = { it.id },
                    combine = StanceProject::joinRow,
                )
            }
            // Stage 6c (F3, StanceProject fold): keyed by (speaker, claim
            // key); LWW-by-turn selection lives in StanceAggregator.value().
            val projectedStances = spawn("projectedStances", identity = identityFor("projectedStances")) { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { row: StanceJoinRow -> row.speaker to row.key },
                    aggregator = StanceProject.StanceAggregator(),
                )
            }
            // Stage 7a (F3, ProvenanceIndex, 2aw.F3-D2): a pure hop off the
            // SAME extractedClaims outlet canonicalClaims folds, then folded
            // by the plain-String key (see ClaimProvenanceEntry's KDoc for
            // why the key is flattened to a String rather than carried as
            // ClaimKey).
            val claimProvenanceEntries = spawn("claimProvenanceEntries", identity = identityFor("claimProvenanceEntries")) { ref ->
                FlatMapSetCell<ExtractedClaim, ClaimProvenanceEntry>(ref = ref) { claim ->
                    listOf(ProvenanceIndex.claimEntry(claim))
                }
            }
            val claimProvenance = spawn("claimProvenance", identity = identityFor("claimProvenance")) { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { entry: ClaimProvenanceEntry -> ClaimKey(entry.key) },
                    aggregator = Aggregators.collectToSet<ClaimProvenanceEntry>(),
                )
            }
            // Stage 7b (F3, ProvenanceIndex): the relation-leg mirror, off
            // the SAME resolvableRelations outlet canonicalRelations folds.
            val relationProvenanceEntries = spawn("relationProvenanceEntries", identity = identityFor("relationProvenanceEntries")) { ref ->
                FlatMapSetCell<RelationCandidate, RelationProvenanceEntry>(ref = ref) { candidate ->
                    listOf(ProvenanceIndex.relationEntry(candidate))
                }
            }
            val relationProvenance = spawn("relationProvenance", identity = identityFor("relationProvenance")) { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { entry: RelationProvenanceEntry -> RelationKey(entry.key) },
                    aggregator = Aggregators.collectToSet<RelationProvenanceEntry>(),
                )
            }
            link(utterances.cell.outlet, segments.cell.inlet)
            link(segments.cell.outlet, extractedItems.cell.inlet)
            link(extractedItems.cell.outlet, extractedClaims.cell.inlet)
            link(extractedClaims.cell.outlet, claimKeys.cell.inlet)
            link(extractedClaims.cell.outlet, canonicalClaims.cell.inlet)
            link(extractedItems.cell.outlet, extractedRelations.cell.inlet)
            link(extractedRelations.cell.outlet, relationCandidates.cell.inlet)
            link(relationCandidates.cell.outlet, rejectedRelations.cell.inlet)
            link(relationCandidates.cell.outlet, nonSelfRelations.cell.inlet)
            link(nonSelfRelations.cell.outlet, sourceResolvedRelations.cell.left)
            link(claimKeys.cell.outlet, sourceResolvedRelations.cell.right)
            link(sourceResolvedRelations.cell.outlet, resolvableRelations.cell.left)
            link(claimKeys.cell.outlet, resolvableRelations.cell.right)
            link(resolvableRelations.cell.outlet, canonicalRelations.cell.inlet)
            link(extractedItems.cell.outlet, extractedStances.cell.inlet)
            link(extractedStances.cell.outlet, stanceJoinRows.cell.left)
            link(utterances.cell.outlet, stanceJoinRows.cell.right)
            link(stanceJoinRows.cell.outlet, projectedStances.cell.inlet)
            link(extractedClaims.cell.outlet, claimProvenanceEntries.cell.inlet)
            link(claimProvenanceEntries.cell.outlet, claimProvenance.cell.inlet)
            link(resolvableRelations.cell.outlet, relationProvenanceEntries.cell.inlet)
            link(relationProvenanceEntries.cell.outlet, relationProvenance.cell.inlet)
            Refs(
                utterances = utterances.refAs(),
                segments = segments.refAs(),
                extractedItems = extractedItems.refAs(),
                extractedClaims = extractedClaims.refAs(),
                claimKeys = claimKeys.refAs(),
                canonicalClaims = canonicalClaims.refAs(),
                extractedRelations = extractedRelations.refAs(),
                relationCandidates = relationCandidates.refAs(),
                rejectedRelations = rejectedRelations.refAs(),
                sourceResolvedRelations = sourceResolvedRelations.refAs(),
                resolvableRelations = resolvableRelations.refAs(),
                canonicalRelations = canonicalRelations.refAs(),
                extractedStances = extractedStances.refAs(),
                stanceJoinRows = stanceJoinRows.refAs(),
                projectedStances = projectedStances.refAs(),
                claimProvenance = claimProvenance.refAs(),
                relationProvenance = relationProvenance.refAs(),
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
