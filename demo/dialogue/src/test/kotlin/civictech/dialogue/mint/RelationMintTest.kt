package civictech.dialogue.mint

import civictech.agora.cell.Polarity
import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.graph.lookupOrThrow
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.dialogue.CanonicalRelation
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.Extractor
import civictech.dialogue.extract.segmentContentHash
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RelationMint (epic computenet-2aw §2.2 stage 5, feature computenet-2aw.3,
 * task computenet-2aw.3.2): the relation leg end to end through
 * [DialoguePipeline.build] — the key-level self-relation split
 * ([DialoguePipeline.Refs.rejectedRelations]), the pending/resolvable semijoin
 * split, and the canonical fold.
 *
 * The extractor is a [CassetteExtractor] over an in-test cassette (2aw.F2-D1:
 * a recorded extractor is what gating tests use) wrapped in a **counting**
 * delegate, because BS-05 has to assert not only *what* the pipeline emits but
 * that the extractor was invoked once per segment and never re-invoked to
 * resolve a pending relation (2aw.F3-D3).
 *
 * `RuleExtractor` cannot serve here, though the reason changed with
 * computenet-xwl0. It used to be that nothing would ever resolve: its
 * relation's endpoint texts are substrings of the segment while the claim it
 * emitted was the *whole* segment text, so the endpoints never canonicalized
 * to a claim key it minted. It now additionally mints each endpoint substring
 * as its own claim, so its relations *do* resolve — and that is exactly what
 * disqualifies it here: it mints both endpoints in the same extraction as the
 * relation, so it can never produce the initially-unminted endpoint BS-05
 * asserts is held pending and minted later by a separate utterance.
 */
class RelationMintTest {

    // ------------------------------------------------------------------
    // Fixture: one cassette entry per utterance text, keyed by content hash
    // ------------------------------------------------------------------

    private val catsPurr = "Cats purr."
    private val dogsBark = "Dogs bark."

    /** u3's segment: yields the relation "Dogs bark." --SUPPORT--> "Cats purr.". */
    private val relationText = "Therefore they are related."

    /** u4's segment: the SAME relation triple as [relationText], from a different utterance. */
    private val duplicateRelationText = "Which is also why."

    /**
     * u5's segment: a relation whose endpoint texts DIFFER textually — so
     * `ExtractionGate.malformedReason`'s textual self-relation check passes it
     * through — but canonicalize to one [civictech.dialogue.ClaimKey]. This is
     * exactly the case [AGO1-REL-04]'s key-level half owns.
     */
    private val keyLevelSelfText = "And it purrs for that reason."

    private fun relation(utteranceId: String) = ExtractedRelation(
        sourceText = dogsBark,
        targetText = catsPurr,
        polarity = "SUPPORT",
        utteranceId = utteranceId,
    )

    private val cassetteEntries: Map<String, List<ExtractedItem>> = mapOf(
        catsPurr to listOf(ExtractedClaim(text = catsPurr, speaker = "alice", utteranceId = "u1")),
        dogsBark to listOf(ExtractedClaim(text = dogsBark, speaker = "bob", utteranceId = "u2")),
        relationText to listOf(relation("u3")),
        duplicateRelationText to listOf(relation("u4")),
        keyLevelSelfText to listOf(
            ExtractedRelation(
                sourceText = catsPurr,
                targetText = "CATS   PURR.",
                polarity = "SUPPORT",
                utteranceId = "u5",
            ),
        ),
    ).mapKeys { (text, _) -> segmentContentHash(hashSegment(text)) }

    private fun hashSegment(text: String) =
        Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text)

    private fun cassette(): CassetteExtractor {
        val json = Json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
            cassetteEntries,
        )
        return CassetteExtractor.load(StringReader(json))
    }

    /**
     * Counts delegate invocations. `ExtractionGate` memoizes by content hash,
     * so a second call for the same segment content is a regression — which is
     * precisely what BS-05 / `[AGO1-REL-03]` forbids ("minted WITHOUT
     * re-extraction").
     */
    private class CountingExtractor(private val delegate: Extractor) : Extractor {
        var calls = 0
            private set

        override fun extract(segment: Segment): List<ExtractedItem> {
            calls++
            return delegate.extract(segment)
        }
    }

    private inner class Rig(seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val extractor = CountingExtractor(cassette())
        private val built = DialoguePipeline.build(host, extractor)
        val refs = built.refs

        /** [AGO1-REL-04]'s key-level rejections, as a derived set. */
        val rejectedView = SetView<RelationCandidate>()

        /** The resolvable stream: candidates both of whose endpoints are minted. */
        val resolvableView = SetView<RelationCandidate>()

        /** The canonical fold's raw output: relation key -> aggregate. */
        val aggregateView = MapView<RelationKey, RelationAggregate>()

        init {
            host.lookupOrThrow(refs.rejectedRelations).outlet.subscribe(
                Use.fixed(
                    Propagate<SetDelta<RelationCandidate>> { delta -> rejectedView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.resolvableRelations).outlet.subscribe(
                Use.fixed(
                    Propagate<SetDelta<RelationCandidate>> { delta -> resolvableView.apply(delta) },
                    PortRef.generate(),
                ),
            )
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

        fun retract(utterance: Utterance) {
            ops.remove(utterance)
            controller.runToIdle()
        }

        fun canonicalRelations(): List<CanonicalRelation> =
            aggregateView.current().map { (key, aggregate) -> RelationMint.canonicalRelation(key, aggregate) }
    }

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    private val u1 = utterance("u1", 1, "alice", catsPurr)
    private val u2 = utterance("u2", 2, "bob", dogsBark)
    private val u3 = utterance("u3", 3, "bob", relationText)
    private val u4 = utterance("u4", 4, "carol", duplicateRelationText)
    private val u5 = utterance("u5", 5, "alice", keyLevelSelfText)

    /** The one relation triple the fixture asserts: "Dogs bark." supports "Cats purr.". */
    private val expectedKey = RelationMint.relationKey(
        source = claimKey(dogsBark),
        target = claimKey(catsPurr),
        polarity = Polarity.SUPPORT,
    )

    // ------------------------------------------------------------------
    // REL-01 — [AGO1-REL-01]
    // ------------------------------------------------------------------

    @Test
    fun `REL-01 - once both endpoints are minted exactly one canonical relation exists per distinct source target polarity`() {
        val rig = Rig()

        rig.admit(u1)
        rig.admit(u2)
        rig.admit(u3)

        val minted = rig.canonicalRelations()
        assertEquals(1, minted.size, "exactly one canonical relation per distinct triple [AGO1-REL-01]")
        val relation = minted.single()
        assertEquals(expectedKey, relation.key)
        assertEquals(claimKey(dogsBark), relation.source)
        assertEquals(claimKey(catsPurr), relation.target)
        assertEquals(Polarity.SUPPORT, relation.polarity)
        assertEquals(setOf("u3"), relation.fromUtterances)

        // A second utterance asserting the SAME triple must fold into the same
        // canonical relation, not mint a second one.
        rig.admit(u4)
        val afterDuplicate = rig.canonicalRelations()
        assertEquals(
            1,
            afterDuplicate.size,
            "a second utterance asserting the same (source, target, polarity) must not mint a second relation",
        )
        assertEquals(
            setOf("u3", "u4"),
            afterDuplicate.single().fromUtterances,
            "both contributing utterances must justify the one canonical relation",
        )
    }

    // ------------------------------------------------------------------
    // BS-05 / REL-03 — [AGO1-REL-03], 2aw.F3-D3
    // ------------------------------------------------------------------

    @Test
    fun `BS-05 AGO1-REL-03 - a relation naming an unminted endpoint is held pending and minted without re-extraction when the endpoint appears`() {
        val rig = Rig()

        // The relation arrives FIRST: neither endpoint is a minted claim.
        rig.admit(u3)
        assertTrue(
            rig.canonicalRelations().isEmpty(),
            "no canonical relation may exist after the first quiescence: neither endpoint is minted [AGO1-REL-03]",
        )
        assertTrue(rig.resolvableView.current().isEmpty(), "the candidate must be held out of the resolvable stream")

        // The TARGET endpoint is minted; the source is still absent, so the
        // relation stays pending — this is the second semijoin discriminating,
        // not the first.
        rig.admit(u1)
        assertTrue(
            rig.canonicalRelations().isEmpty(),
            "one minted endpoint is not enough: the relation must stay pending",
        )

        // The source endpoint is minted. The pending candidate now enters with
        // no fresh left tag of its own — SemiJoinCell mints per entry.
        rig.admit(u2)
        val minted = rig.canonicalRelations()
        assertEquals(1, minted.size, "the pending relation must be minted as soon as its last endpoint is [AGO1-REL-03]")
        assertEquals(setOf("u3"), minted.single().fromUtterances)

        // 2aw.F3-D3: pending is held by the GRAPH. Three utterances, one
        // segment each, three delegate calls — the pending candidate was never
        // re-extracted to resolve it. (ExtractionGate memoizes by content hash,
        // so any re-invocation would show up here as a fourth call.)
        assertEquals(
            3,
            rig.extractor.calls,
            "the extractor must be invoked once per segment and never re-invoked to resolve a pending relation",
        )
    }

    // ------------------------------------------------------------------
    // REL-04 (key-level half) — [AGO1-REL-04]
    // ------------------------------------------------------------------

    @Test
    fun `REL-04 - a relation whose textually distinct endpoints canonicalize to one claim key is rejected and mints nothing`() {
        val rig = Rig()

        // Mint the claim key both endpoints canonicalize to, so that a missing
        // canonical relation can only be the REJECTION and not an unresolved
        // endpoint.
        rig.admit(u1)
        rig.admit(u5)

        val selfKey = claimKey(catsPurr)
        assertEquals(claimKey("CATS   PURR."), selfKey, "fixture precondition: the two endpoint texts share one key")

        val rejected = rig.rejectedView.current()
        assertEquals(1, rejected.size, "the key-level self-relation must land in the rejected set [AGO1-REL-04]")
        val candidate = rejected.single()
        assertEquals(selfKey, candidate.sourceKey)
        assertEquals(selfKey, candidate.targetKey)
        assertEquals("u5", candidate.utteranceId)

        assertTrue(
            rig.canonicalRelations().isEmpty(),
            "a key-level self-relation must mint no canonical relation [AGO1-REL-04]",
        )
        assertTrue(rig.resolvableView.current().isEmpty(), "a rejected candidate must never reach the resolvable stream")

        // Derived, not ledgered: retracting the utterance retracts the rejection.
        rig.retract(u5)
        assertTrue(
            rig.rejectedView.current().isEmpty(),
            "the rejected set is a derived set, so it must be retraction-correct",
        )
    }

    // ------------------------------------------------------------------
    // REL-02 (pipeline half) — [AGO1-REL-02]
    // ------------------------------------------------------------------

    @Test
    fun `REL-02 pipeline half - losing the last contributing utterance retracts the canonical relation`() {
        // The applier-side half of [AGO1-REL-02] — retracting the relation's
        // influence at the agora target — is F4's; this asserts only that the
        // PIPELINE's canonical relation dies with its last contributor.
        val rig = Rig()

        rig.admit(u1)
        rig.admit(u2)
        rig.admit(u3)
        rig.admit(u4)
        assertEquals(1, rig.canonicalRelations().size, "precondition: one canonical relation from two utterances")

        rig.retract(u3)
        val surviving = rig.canonicalRelations()
        assertEquals(1, surviving.size, "the relation must survive while another contributing utterance is live")
        assertEquals(setOf("u4"), surviving.single().fromUtterances)

        rig.retract(u4)
        assertTrue(
            rig.canonicalRelations().isEmpty(),
            "the canonical relation must be retracted once its last contributing utterance is [AGO1-REL-02]",
        )
    }

    // ------------------------------------------------------------------
    // Endpoint retraction — the pending/resolvable split runs backwards too
    // ------------------------------------------------------------------

    @Test
    fun `retracting an endpoint's claim returns a minted relation to pending rather than dropping it`() {
        val rig = Rig()

        rig.admit(u1)
        rig.admit(u2)
        rig.admit(u3)
        assertEquals(1, rig.canonicalRelations().size)

        rig.retract(u2)
        assertTrue(
            rig.canonicalRelations().isEmpty(),
            "with its source endpoint no longer minted the relation must leave the canonical set",
        )

        rig.admit(u2)
        assertEquals(
            1,
            rig.canonicalRelations().size,
            "re-minting the endpoint must re-mint the relation, still without re-extraction",
        )
        assertEquals(3, rig.extractor.calls, "no re-extraction on either the retraction or the re-admission")
    }
}
