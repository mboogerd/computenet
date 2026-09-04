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
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.mint.RelationAggregate
import civictech.dialogue.mint.RelationMint
import kotlin.test.Test
import kotlin.test.assertEquals
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

        init {
            host.lookupOrThrow(refs.canonicalRelations).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<RelationKey, RelationAggregate>> { delta -> aggregateView.apply(delta) },
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
}
