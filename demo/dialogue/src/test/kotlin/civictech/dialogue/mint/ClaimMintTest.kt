package civictech.dialogue.mint

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
import civictech.dialogue.CanonicalClaim
import civictech.dialogue.ClaimKey
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.Utterance
import civictech.dialogue.extract.RuleExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ClaimMint (epic computenet-2aw §2.2 stage 4, feature computenet-2aw.3,
 * task computenet-2aw.3.1). Exercises [DialoguePipeline.Refs.claimKeys] and
 * [DialoguePipeline.Refs.canonicalClaims] end to end through
 * [DialoguePipeline.build] rather than unit-testing [ClaimMint.ClaimAggregator]
 * in isolation, so the assertions cover the actual wiring
 * ([claimKey] → the item-kind split → `GroupByCell`), not just the
 * aggregator's arithmetic.
 *
 * `RuleExtractor` (deterministic, no clock/randomness/I/O — [AGO1-EXTR-01])
 * is the extractor throughout: every segment yields one [civictech.dialogue.extract.ExtractedClaim]
 * of its trimmed text, so a single-sentence utterance's claim text is that
 * sentence verbatim (segmentation keeps the terminating punctuation, per
 * `Segmentation.kt`).
 */
class ClaimMintTest {

    private class Rig(seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        private val built = DialoguePipeline.build(host, RuleExtractor)
        val refs = built.refs

        /** Currently-minted claim keys, as [DialoguePipeline.Refs.claimKeys] emits them. */
        val claimKeyView = SetView<ClaimKey>()

        /** The ClaimMint fold's raw output: key -> aggregate. */
        val aggregateView = MapView<ClaimKey, ClaimAggregate>()

        init {
            host.lookupOrThrow(refs.claimKeys).outlet.subscribe(
                Use.fixed(
                    Propagate<SetDelta<ClaimKey>> { delta -> claimKeyView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.canonicalClaims).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<ClaimKey, ClaimAggregate>> { delta -> aggregateView.apply(delta) },
                    PortRef.generate(),
                ),
            )
        }

        val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(host, refs)

        fun admit(utterance: Utterance) {
            ops.add(utterance)
            quiesce()
        }

        fun retract(utterance: Utterance) {
            ops.remove(utterance)
            quiesce()
        }

        fun quiesce() = controller.runToIdle()

        /** [aggregateView]'s entries assembled into [CanonicalClaim]s (mirrors [ClaimMint.canonicalClaim]). */
        fun canonicalClaims(): List<CanonicalClaim> =
            aggregateView.current().map { (key, aggregate) -> ClaimMint.canonicalClaim(key, aggregate) }
    }

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    // ------------------------------------------------------------------
    // BS-02 (canonical-claim half) — [AGO1-MINT-01]/[AGO1-MINT-02]
    // ------------------------------------------------------------------

    @Test
    fun `BS-02 AGO1-MINT-01 AGO1-MINT-02 - two utterances from different speakers whose claim texts canonicalize to the same key mint exactly one canonical claim naming both`() {
        val rig = Rig()

        // Same key modulo case and internal whitespace runs — exactly what
        // claimKey()'s canonicalization (trim, collapse whitespace, lowercase)
        // is defined to fold together. Different terminating punctuation would
        // NOT fold (deliberately weak identity, 2aw.F3-D1), so both keep the
        // same "." to stay a same-key pair.
        rig.admit(utterance("u1", 1, "alice", "The sky is blue."))
        rig.admit(utterance("u2", 2, "bob", "THE   SKY IS BLUE."))

        val key = claimKey("The sky is blue.")
        assertEquals(setOf(key), rig.claimKeyView.current(), "claimKeys did not dedup the two same-key contributions")

        val claims = rig.canonicalClaims()
        assertEquals(1, claims.size, "exactly one canonical claim should be minted per distinct key [AGO1-MINT-01]")
        val claim = claims.single()
        assertEquals(key, claim.key)
        assertEquals(setOf("u1", "u2"), claim.fromUtterances, "provenance must name both contributing utterances")
    }

    @Test
    fun `claim keys with genuinely different canonicalized text mint separate canonical claims`() {
        val rig = Rig()

        rig.admit(utterance("u1", 1, "alice", "The sky is blue."))
        rig.admit(utterance("u2", 2, "bob", "Rain follows clouds."))

        assertEquals(2, rig.canonicalClaims().size)
        assertEquals(
            setOf(claimKey("The sky is blue."), claimKey("Rain follows clouds.")),
            rig.claimKeyView.current(),
        )
    }

    // ------------------------------------------------------------------
    // MINT-03 — [AGO1-MINT-03]
    // ------------------------------------------------------------------

    @Test
    fun `MINT-03 - when every contributing utterance is retracted the canonical claim's group is removed`() {
        val rig = Rig()

        val u1 = utterance("u1", 1, "alice", "The sky is blue.")
        val u2 = utterance("u2", 2, "bob", "THE SKY IS BLUE.")
        rig.admit(u1)
        rig.admit(u2)
        assertEquals(1, rig.canonicalClaims().size, "precondition: one canonical claim minted from two utterances")

        rig.retract(u1)
        assertEquals(
            1,
            rig.canonicalClaims().size,
            "the claim must survive while at least one contributing utterance remains live",
        )
        assertEquals(setOf("u2"), rig.canonicalClaims().single().fromUtterances)

        rig.retract(u2)
        assertTrue(rig.canonicalClaims().isEmpty(), "the canonical claim's group must be removed once its last contributor is retracted")
        assertTrue(rig.claimKeyView.current().isEmpty(), "the claimKeys stream must retract the key alongside the group")
    }
}
