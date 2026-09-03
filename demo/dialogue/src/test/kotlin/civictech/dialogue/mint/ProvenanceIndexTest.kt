package civictech.dialogue.mint

import civictech.agora.cell.Polarity
import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.view.MapView
import civictech.cell.graph.lookupOrThrow
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.dialogue.ClaimKey
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.Utterance
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.Extractor
import civictech.dialogue.extract.RuleExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ProvenanceIndex (epic computenet-2aw §2.2 stage 7, feature computenet-2aw.3,
 * task computenet-2aw.3.3): [DialoguePipeline.Refs.claimProvenance] and
 * [DialoguePipeline.Refs.relationProvenance] end to end through
 * [DialoguePipeline.build].
 *
 * The claim-only fixture uses [RuleExtractor] (deterministic, no
 * cassette/JSON needed for a claim-only scenario, same as `ClaimMintTest`).
 * The relation fixture cannot: `RuleExtractor`'s relation endpoint texts are
 * substrings of the whole-segment claim text it emits for the same segment,
 * so they never canonicalize to a key that segment's own claim mints (see
 * `RelationMintTest`'s KDoc). It uses a plain in-memory `Extractor` lambda
 * keyed by exact segment text instead — every fixture utterance below has a
 * distinct text, so `ExtractionGate`'s content-hash memoization never
 * coalesces two different utterances' extractions.
 */
class ProvenanceIndexTest {

    private fun extractor(itemsByText: Map<String, List<ExtractedItem>>): Extractor =
        Extractor { segment -> itemsByText[segment.text] ?: emptyList() }

    private inner class Rig(extractor: Extractor, seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        private val built = DialoguePipeline.build(host, extractor)
        val refs = built.refs

        val claimAggregateView = MapView<ClaimKey, ClaimAggregate>()
        val claimProvenanceView = MapView<ClaimKey, Set<ClaimProvenanceEntry>>()
        val relationAggregateView = MapView<RelationKey, RelationAggregate>()
        val relationProvenanceView = MapView<RelationKey, Set<RelationProvenanceEntry>>()

        init {
            host.lookupOrThrow(refs.canonicalClaims).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<ClaimKey, ClaimAggregate>> { delta -> claimAggregateView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.claimProvenance).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<ClaimKey, Set<ClaimProvenanceEntry>>> { delta -> claimProvenanceView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.canonicalRelations).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<RelationKey, RelationAggregate>> { delta -> relationAggregateView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.relationProvenance).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<RelationKey, Set<RelationProvenanceEntry>>> { delta -> relationProvenanceView.apply(delta) },
                    PortRef.generate(),
                ),
            )
        }

        val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(host, refs)

        fun admit(utterance: Utterance) {
            ops.add(utterance)
            controller.runToIdle()
        }

        fun retract(utterance: Utterance) {
            ops.remove(utterance)
            controller.runToIdle()
        }

        /** [claimProvenanceView], assembled through [ProvenanceIndex.claimProvenance]. */
        fun claimProvenance(key: ClaimKey): Set<String>? =
            claimProvenanceView.current()[key]?.let { ProvenanceIndex.claimProvenance(it) }

        fun relationProvenance(key: RelationKey): Set<String>? =
            relationProvenanceView.current()[key]?.let { ProvenanceIndex.relationProvenance(it) }
    }

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    // ------------------------------------------------------------------
    // PROV-01 (claim half) — [AGO1-PROV-01]
    // ------------------------------------------------------------------

    @Test
    fun `PROV-01 - every canonical claim has a provenance entry naming exactly its contributing utterances`() {
        val rig = Rig(RuleExtractor)

        val u1 = utterance("u1", 1, "alice", "The sky is blue.")
        val u2 = utterance("u2", 2, "bob", "THE   SKY IS BLUE.")
        val u3 = utterance("u3", 3, "carol", "Rain follows clouds.")
        rig.admit(u1)
        rig.admit(u2)
        rig.admit(u3)

        val skyKey = claimKey("The sky is blue.")
        val rainKey = claimKey("Rain follows clouds.")

        // Every entry in the canonical-claim map must have a matching
        // provenance entry naming exactly the same utterances.
        val claims = rig.claimAggregateView.current()
        assertEquals(setOf(skyKey, rainKey), claims.keys)
        for ((key, aggregate) in claims) {
            assertEquals(
                aggregate.fromUtterances,
                rig.claimProvenance(key),
                "provenance for $key must name exactly the utterances its canonical claim's own fromUtterances names",
            )
        }
        assertEquals(setOf("u1", "u2"), rig.claimProvenance(skyKey))
        assertEquals(setOf("u3"), rig.claimProvenance(rainKey))
    }

    // ------------------------------------------------------------------
    // MINT-03 provenance half — [AGO1-PROV-03]
    // ------------------------------------------------------------------

    @Test
    fun `MINT-03 provenance half - when a canonical claim dies its provenance entry is gone too`() {
        val rig = Rig(RuleExtractor)

        val u1 = utterance("u1", 1, "alice", "The sky is blue.")
        rig.admit(u1)

        val key = claimKey("The sky is blue.")
        assertEquals(setOf("u1"), rig.claimProvenance(key), "precondition: the claim's sole contributor")

        rig.retract(u1)
        assertFalse(key in rig.claimAggregateView.current(), "precondition: the canonical claim itself must be gone")
        assertFalse(
            key in rig.claimProvenanceView.current(),
            "the provenance ENTRY must be gone too, not present with an empty set [AGO1-PROV-03]/MINT-03",
        )
    }

    // ------------------------------------------------------------------
    // PROV-01 (relation half) + PROV-03 — one retraction, two provenance sets
    // ------------------------------------------------------------------

    /**
     * `u3`'s segment yields BOTH a second contribution to the "Dogs bark."
     * claim's provenance AND the sole contribution to the relation's
     * provenance, so retracting `u3` exercises PROV-03's "removes it from
     * EVERY provenance set within the same reconciliation" with one
     * `ops.remove` / `runToIdle()` step, rather than two separate ones that
     * could each pass on their own.
     */
    private val catsPurr = "Cats purr."
    private val dogsBark = "Dogs bark."
    private val jointSegment = "Joint segment."

    private val relationItems = mapOf(
        catsPurr to listOf(ExtractedClaim(text = catsPurr, speaker = "alice", utteranceId = "u1")),
        dogsBark to listOf(ExtractedClaim(text = dogsBark, speaker = "bob", utteranceId = "u2")),
        jointSegment to listOf(
            ExtractedClaim(text = dogsBark, speaker = "carol", utteranceId = "u3"),
            ExtractedRelation(sourceText = dogsBark, targetText = catsPurr, polarity = "SUPPORT", utteranceId = "u3"),
        ),
    )

    private val u1 = utterance("u1", 1, "alice", catsPurr)
    private val u2 = utterance("u2", 2, "bob", dogsBark)
    private val u3 = utterance("u3", 3, "carol", jointSegment)

    @Test
    fun `PROV-01 relation half - a canonical relation has a provenance entry naming its justifying utterance`() {
        val rig = Rig(extractor(relationItems))

        rig.admit(u1)
        rig.admit(u2)
        rig.admit(u3)

        val relationKey = RelationMint.relationKey(claimKey(dogsBark), claimKey(catsPurr), Polarity.SUPPORT)
        assertEquals(1, rig.relationAggregateView.current().size, "precondition: one canonical relation minted")
        assertEquals(setOf("u3"), rig.relationProvenance(relationKey))
    }

    @Test
    fun `PROV-03 - retracting one utterance removes it from every provenance set in the same reconciliation`() {
        val rig = Rig(extractor(relationItems))

        rig.admit(u1)
        rig.admit(u2)
        rig.admit(u3)

        val dogsBarkKey = claimKey(dogsBark)
        val relationKey = RelationMint.relationKey(claimKey(dogsBark), claimKey(catsPurr), Polarity.SUPPORT)

        assertEquals(setOf("u2", "u3"), rig.claimProvenance(dogsBarkKey), "precondition: u3 contributes to the claim too")
        assertEquals(setOf("u3"), rig.relationProvenance(relationKey), "precondition: u3 is the relation's sole contributor")

        // One retraction, one reconciliation: both sets must reflect it.
        rig.retract(u3)

        assertEquals(
            setOf("u2"),
            rig.claimProvenance(dogsBarkKey),
            "the claim survives (u2 remains) but must lose u3 from its provenance [AGO1-PROV-03]",
        )
        assertTrue(
            rig.relationAggregateView.current().isEmpty(),
            "the relation loses its sole contributor and must be retracted",
        )
        assertFalse(
            relationKey in rig.relationProvenanceView.current(),
            "the relation's provenance entry must be gone in the SAME reconciliation, not lag behind",
        )
    }
}
