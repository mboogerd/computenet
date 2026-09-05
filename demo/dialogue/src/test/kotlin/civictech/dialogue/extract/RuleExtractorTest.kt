package civictech.dialogue.extract

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
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.mint.ClaimAggregate
import civictech.dialogue.mint.ClaimProvenanceEntry
import civictech.dialogue.mint.ProvenanceIndex
import civictech.dialogue.mint.RelationAggregate
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `RuleExtractor` is the pure, zero-dependency demo `Extractor`
 * ([AGO1-EXTR-01]'s extractor half): deterministic, no clock/network/
 * randomness.
 */
class RuleExtractorTest {

    private fun segment(
        id: String = "s1",
        utteranceId: String = "u1",
        ordinal: Int = 0,
        speaker: String = "alice",
        text: String,
    ) = Segment(id = id, utteranceId = utteranceId, ordinal = ordinal, speaker = speaker, text = text)

    @Test
    fun `extracting the same segment twice yields equal lists`() {
        val s = segment(text = "It rains because clouds are heavy")

        val first = RuleExtractor.extract(s)
        val second = RuleExtractor.extract(s)

        assertEquals(first, second)
    }

    @Test
    fun `a plain segment yields just its claim`() {
        val s = segment(text = "The sky is blue", speaker = "alice", utteranceId = "u1")

        val items = RuleExtractor.extract(s)

        assertEquals(
            listOf(ExtractedClaim(text = "The sky is blue", speaker = "alice", utteranceId = "u1")),
            items,
        )
    }

    @Test
    fun `a because-segment yields only the two endpoint claims and a supporting relation, not the whole-segment claim`() {
        val s = segment(text = "It rains because clouds are heavy", speaker = "bob", utteranceId = "u2")

        val items = RuleExtractor.extract(s)

        // Endpoint claims (targetClaim then sourceClaim) are minted INSTEAD
        // OF the whole-segment claim, so the relation's endpoints
        // canonicalize to a claim key this same segment mints, without also
        // minting a redundant orphan node for the whole sentence — see
        // RuleExtractor's KDoc "Why only the endpoint claims"
        // (computenet-xwl0, then computenet-i6hp).
        assertEquals(
            listOf(
                ExtractedClaim(text = "It rains", speaker = "bob", utteranceId = "u2"),
                ExtractedClaim(text = "clouds are heavy", speaker = "bob", utteranceId = "u2"),
                ExtractedRelation(
                    sourceText = "clouds are heavy",
                    targetText = "It rains",
                    polarity = "SUPPORT",
                    utteranceId = "u2",
                ),
            ),
            items,
        )
        assertTrue(
            items.none { it is ExtractedClaim && it.text == "It rains because clouds are heavy" },
            "the whole-segment claim must not be minted once the because-split succeeds",
        )
    }

    @Test
    fun `because-matching is case-insensitive`() {
        val s = segment(text = "It rains BECAUSE clouds are heavy")

        val items = RuleExtractor.extract(s)

        assertTrue(items.any { it is ExtractedRelation })
    }

    @Test
    fun `a disagreement-marker segment yields no relation, only its own claim`() {
        val s = segment(text = "No, that's wrong", speaker = "bob", utteranceId = "u3")

        val items = RuleExtractor.extract(s)

        assertEquals(
            listOf(ExtractedClaim(text = "No, that's wrong", speaker = "bob", utteranceId = "u3")),
            items,
        )
    }

    // ------------------------------------------------------------------
    // computenet-xwl0: RuleExtractor driven end to end through
    // DialoguePipeline.build, asserting what the relation leg produces.
    // ------------------------------------------------------------------

    private class Rig(seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        private val built = DialoguePipeline.build(host, RuleExtractor)
        val refs = built.refs

        /** The canonical relation fold's raw output: relation key -> aggregate. */
        val aggregateView = MapView<RelationKey, RelationAggregate>()

        /** The canonical claim fold's raw output: claim key -> aggregate. */
        val claimView = MapView<ClaimKey, ClaimAggregate>()

        /** ProvenanceIndex's claim leg: claim key -> the entries justifying it. */
        val claimProvenanceView = MapView<ClaimKey, Set<ClaimProvenanceEntry>>()

        init {
            host.lookupOrThrow(refs.canonicalRelations).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<RelationKey, RelationAggregate>> { delta -> aggregateView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.canonicalClaims).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<ClaimKey, ClaimAggregate>> { delta -> claimView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.claimProvenance).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<ClaimKey, Set<ClaimProvenanceEntry>>> { delta ->
                        claimProvenanceView.apply(delta)
                    },
                    PortRef.generate(),
                ),
            )
        }

        val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(host, refs)

        fun admit(utterance: Utterance) {
            ops.add(utterance)
            controller.runToIdle()
        }

        fun canonicalRelationCount(): Int = aggregateView.current().size
    }

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    @Test
    fun `driven through DialoguePipeline a single because-utterance mints a non-empty canonical relation set`() {
        // This is the discriminating regression for computenet-xwl0: before
        // the endpoint claims were added, RuleExtractor's relation endpoints
        // never canonicalized to a claim key it minted, so this stayed
        // PENDING and canonicalRelations() stayed empty forever — one
        // utterance, admitted once, with no second utterance supplying the
        // endpoints separately.
        val rig = Rig()

        rig.admit(utterance("u1", 1, "alice", "It rains because clouds are heavy."))

        assertTrue(
            rig.canonicalRelationCount() > 0,
            "RuleExtractor's own segment must resolve its relation without a second, separate utterance " +
                "asserting its endpoints as standalone claims",
        )
        assertEquals(1, rig.canonicalRelationCount())
    }

    // ------------------------------------------------------------------
    // computenet-9bip: the trailing full stop the segmenter leaves on a
    // sentence-final endpoint must not fork the claim key.
    //
    // Segmentation splits on `(?<=[.!?])\s+`, which KEEPS the terminator on
    // the sentence, so the endpoint AFTER "because" ends in "." while the
    // same proposition used as a conclusion (the endpoint BEFORE "because")
    // does not. `claimKey` already lowercases, so sentence-initial case is
    // NOT what forks these — the full stop alone is.
    // ------------------------------------------------------------------

    @Test
    fun `a sentence-final endpoint drops its trailing full stop, so it keys equal to the same proposition used as a conclusion`() {
        val reason = RuleExtractor.extract(
            segment(text = "The budget is too high because travel costs increased.", utteranceId = "u1"),
        )
        val conclusion = RuleExtractor.extract(
            segment(text = "Travel costs increased because flights got more expensive.", utteranceId = "u2"),
        )

        val reasonText = (reason[1] as ExtractedClaim).text
        val conclusionText = (conclusion[0] as ExtractedClaim).text

        assertEquals("travel costs increased", reasonText, "the endpoint keeps no sentence-final full stop")
        assertEquals("Travel costs increased", conclusionText, "the conclusion endpoint is untouched")
        assertEquals(
            claimKey(conclusionText),
            claimKey(reasonText),
            "the same proposition uttered as a reason and as a conclusion must canonicalize to ONE claim key",
        )
        // The relation's endpoint texts are the SAME strings as the claims',
        // so RelationMint canonicalizes against a key these claims mint.
        assertEquals(reasonText, (reason[2] as ExtractedRelation).sourceText)
    }

    // ------------------------------------------------------------------
    // computenet-qoei: unbundling 9bip's "`?` and `!` are preserved" into
    // two separate decisions. `?` is kept preserved — an interrogative is a
    // different speech act, not the declarative with different terminal
    // punctuation. `!` is reclassified: it is exactly the segmenter-supplied
    // terminator 9bip's "Trailing full stops" already strips for `.`/`…` —
    // present on an endpoint only because of which side of the because-split
    // it fell on — so it is stripped alongside them.
    // ------------------------------------------------------------------

    @Test
    fun `a question mark is preserved, since it changes what the claim asserts`() {
        val items = RuleExtractor.extract(segment(text = "We should panic because the budget is too high?"))

        assertEquals("the budget is too high?", (items[1] as ExtractedClaim).text)
    }

    @Test
    fun `an exclamation is stripped from a because-endpoint, like a full stop, since it is the segmenter's terminator artifact`() {
        val items = RuleExtractor.extract(segment(text = "We should panic because the budget is too high!"))

        assertEquals("the budget is too high", (items[1] as ExtractedClaim).text)
    }

    @Test
    fun `driven through DialoguePipeline, a proposition uttered once as a reason and once as a conclusion mints ONE claim with both utterance ids`() {
        // The discriminating regression for computenet-9bip. Before the
        // trailing full stop was dropped, u1's reason endpoint keyed as
        // "travel costs increased." and u2's conclusion endpoint as
        // "travel costs increased" — two canonical claims for one
        // proposition, each with a single-utterance provenance set, which is
        // exactly the merge the demo transcript was authored to show and did
        // not get (measured in demo/agora/ui/test/fixtures/dialogue-graph.json).
        val rig = Rig()

        rig.admit(utterance("u1", 1, "alice", "The budget is too high because travel costs increased."))
        rig.admit(utterance("u2", 2, "carol", "Travel costs increased because flights got more expensive."))

        val shared = claimKey("Travel costs increased")

        val provenance = rig.claimProvenanceView.current()[shared]
        assertNotNull(provenance, "the shared proposition must have a canonical claim key")
        assertEquals(
            setOf("u1", "u2"),
            ProvenanceIndex.claimProvenance(provenance),
            "one claim key, justified by BOTH the utterance that used it as a reason and the one that used it " +
                "as a conclusion",
        )

        val claims = rig.claimView.current()
        assertEquals(
            setOf("u1", "u2"),
            claims[shared]?.fromUtterances,
            "the canonical claim fold must see both contributions under the one key",
        )
        assertEquals(
            emptyList(),
            claims.keys.filter { it != shared && it.value.trimEnd('.') == shared.value },
            "no second claim key may differ from the shared one only by trailing punctuation",
        )
        // 3 propositions across the two utterances: the budget claim, the
        // shared travel-costs claim, and the flights claim.
        assertEquals(3, claims.size, "the two utterances mint three distinct claims, not four")
    }

    @Test
    fun `driven through DialoguePipeline, a proposition uttered once as an exclamation-terminated reason and once as a conclusion mints ONE claim with both utterance ids`() {
        // The discriminating regression for computenet-qoei: before `!` was
        // stripped, u1's reason endpoint keyed as "travel costs increased!"
        // and u2's conclusion endpoint as "travel costs increased" — two
        // canonical claims for one proposition, exactly the defect shape
        // computenet-9bip fixed for `.`, left standing for `!`.
        val rig = Rig()

        rig.admit(utterance("u1", 1, "alice", "The budget is too high because travel costs increased!"))
        rig.admit(utterance("u2", 2, "carol", "Travel costs increased because flights got more expensive."))

        val shared = claimKey("Travel costs increased")

        val provenance = rig.claimProvenanceView.current()[shared]
        assertNotNull(provenance, "the shared proposition must have a canonical claim key")
        assertEquals(
            setOf("u1", "u2"),
            ProvenanceIndex.claimProvenance(provenance),
            "one claim key, justified by BOTH the utterance that used it as an exclamation-terminated reason " +
                "and the one that used it as a conclusion",
        )

        val claims = rig.claimView.current()
        assertEquals(
            setOf("u1", "u2"),
            claims[shared]?.fromUtterances,
            "the canonical claim fold must see both contributions under the one key",
        )
        assertEquals(3, claims.size, "the two utterances mint three distinct claims, not four")
    }
}
