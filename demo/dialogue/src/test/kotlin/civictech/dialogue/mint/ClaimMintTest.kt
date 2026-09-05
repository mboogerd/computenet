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
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.RuleExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    // computenet-lv25 — the trailing-terminator rule lives in claimKey
    // ------------------------------------------------------------------

    /**
     * The defect computenet-lv25 fixes, end to end through the pipeline: one
     * proposition uttered once as a plain standalone segment (which keeps its
     * sentence-final full stop, since [civictech.dialogue.Segmentation] splits
     * on a lookbehind) and once as the reason endpoint of a "because" split
     * (which [civictech.dialogue.extract.RuleExtractor] strips, per
     * computenet-9bip) must mint ONE canonical claim naming both utterances.
     *
     * Before the fix the two minted `ClaimKey(travel costs increased.)` and
     * `ClaimKey(travel costs increased)` — two canonical claims for one
     * proposition, the residual computenet-9bip left open.
     */
    @Test
    fun `computenet-lv25 - a plain segment and a because-endpoint of the same proposition mint ONE claim naming both utterances`() {
        val rig = Rig()

        // u1: no " because ", so RuleExtractor emits the whole segment
        // verbatim — terminator included.
        rig.admit(utterance("u1", 1, "alice", "Travel costs increased."))
        // u2: the same proposition as the reason endpoint of a because split,
        // where RuleExtractor already strips the terminator.
        rig.admit(utterance("u2", 2, "bob", "The budget is too high because travel costs increased."))

        val sharedKey = claimKey("Travel costs increased")
        assertEquals(
            setOf(sharedKey, claimKey("The budget is too high")),
            rig.claimKeyView.current(),
            "the standalone claim and the because-endpoint of the same proposition must canonicalize to ONE key",
        )

        val shared = rig.canonicalClaims().single { it.key == sharedKey }
        assertEquals(
            setOf("u1", "u2"),
            shared.fromUtterances,
            "provenance must name both the standalone utterance and the because utterance",
        )
    }

    /**
     * The terminator class [claimKey] strips, and the one it deliberately
     * does not. Mirrors `RuleExtractor`'s "Trailing terminators" rule
     * (computenet-9bip as amended by computenet-qoei): `.`, `…` and `!` are
     * segmenter artifacts and fold; `?` marks a genuinely different
     * proposition and forks.
     */
    @Test
    fun `computenet-lv25 - claimKey strips trailing full stops ellipses and exclamations but preserves the question mark`() {
        assertEquals(
            claimKey("The budget is too high"),
            claimKey("The budget is too high."),
            "a trailing full stop is the segmenter's, not content",
        )
        assertEquals(
            claimKey("The budget is too high"),
            claimKey("The budget is too high…"),
            "a trailing ellipsis is the segmenter's, not content",
        )
        assertEquals(
            claimKey("The budget is too high"),
            claimKey("The budget is too high!"),
            "computenet-qoei: `!` is the same split-position accident as `.` and does not change the mood",
        )
        assertEquals(
            claimKey("The budget is too high"),
            claimKey("The budget is too high...!"),
            "runs of terminator punctuation strip as one",
        )
        assertNotEquals(
            claimKey("Is the budget too high"),
            claimKey("Is the budget too high?"),
            "`?` is preserved: an interrogative asserts something different from its declarative",
        )
    }

    /**
     * The guard the strip needs: a claim whose text is nothing but terminator
     * punctuation must not canonicalize to the empty key, which every other
     * such claim would then collide with.
     */
    @Test
    fun `computenet-lv25 - a text that is nothing but terminator punctuation is not emptied by canonicalization`() {
        assertEquals(ClaimKey("..."), claimKey("..."))
        assertEquals(ClaimKey("!"), claimKey(" ! "))
    }

    // ------------------------------------------------------------------
    // computenet-2qkn — RuleExtractor's and claimKey's trailing-terminator
    // classes are pinned to each other, not just held in step by KDoc prose
    // ------------------------------------------------------------------

    /**
     * `RuleExtractor`'s trailing-terminator class and `claimKey`'s own are
     * two independent, `private` regexes with identical source text, kept in
     * sync only by KDoc prose (computenet-2qkn found this, filed reviewing
     * computenet-lv25; computenet-8ojp moved the pin here, to the PUBLIC
     * surface, after computenet-if9j ruled out `internal`-for-a-direct-test
     * a commit earlier for the same module). The asymmetry between the two
     * classes is deliberate, per both KDocs: if `claimKey`'s class is WIDER
     * than the extractor's, only the KEY changes — the displayed text is
     * untouched, so it is harmless. If the EXTRACTOR's class is wider than
     * `claimKey`'s, the same proposition forks into two canonical claims
     * again — the computenet-9bip / computenet-qoei / computenet-lv25 defect,
     * for a fourth time.
     *
     * So this pin is deliberately directional, not a flat equality check.
     * For each candidate terminator it drives [RuleExtractor.extract] over a
     * "because" segment whose reason endpoint carries that terminator, and
     * reads the [ExtractedRelation.sourceText] the extractor actually
     * emitted — the same value `RelationMint` resolves a claim key against —
     * to see whether the extractor treats the character as a terminator at
     * all. Only when it does is anything asserted: that
     * `claimKey(body + terminator) == claimKey(body)`, i.e. that a plain
     * whole-segment claim of `"body" + terminator"` (uttered standalone,
     * keeping its own terminator per `Segmentation`'s lookbehind split) would
     * canonicalize to the SAME key the because-endpoint already collapses to.
     * If it does not, the same proposition forks into two canonical claims
     * depending on where it lands relative to a "because" split — exactly
     * the recurring defect above, caught this time through `extract` and
     * `claimKey` rather than by reaching into either `private` helper.
     *
     * A flat equality pin over the two regexes would also fail the moment
     * `claimKey` alone grows a new key-only normalization — exactly the
     * harmless direction the KDocs call out — which would make the pin a
     * nuisance rather than a guard.
     *
     * The candidate set below deliberately includes characters neither class
     * currently strips (`;`, `:`, `,`, `)`, `"`, `'`, `~`, `-`) alongside the
     * three the classes currently agree on (`.`, `…`, `!`) and the one both
     * deliberately preserve (`?`, computenet-qoei) — so a future change that
     * adds a character to the extractor's class alone, without touching
     * `claimKey`'s, is caught even though it names a character this test's
     * author never anticipated.
     *
     * **Non-vacuity guard:** the loop above `continue`s past any candidate
     * the extractor does not treat as a terminator, so if `RuleExtractor`'s
     * class were neutered to match nothing, every candidate would be skipped
     * and the test would pass having asserted zero times. The count below
     * requires the three currently-agreed terminators (`.`, `…`, `!`) to
     * have actually been exercised, so that mutation is caught here instead
     * of passing vacuously.
     */
    @Test
    fun `computenet-8ojp - every terminator RuleExtractor's extract strips is also stripped by claimKey's canonicalization`() {
        val target = "The budget is too high"
        val body = "Travel costs increased"
        val candidates = listOf(".", "…", "!", "?", ";", ":", ",", ")", "\"", "'", "~", "-")
        var exercised = 0
        for (terminator in candidates) {
            val rawSource = "$body$terminator"
            val segment = Segment(
                id = "s-$terminator",
                utteranceId = "u-$terminator",
                ordinal = 0,
                speaker = "alice",
                text = "$target because $rawSource",
            )
            val relation = RuleExtractor.extract(segment).filterIsInstance<ExtractedRelation>().single()
            val strippedByExtractor = relation.sourceText != rawSource
            if (!strippedByExtractor) continue
            exercised += 1

            assertEquals(
                claimKey(body),
                claimKey(rawSource),
                "RuleExtractor's extract strips trailing '$terminator' as a segmenter artifact (source " +
                    "endpoint became \"${relation.sourceText}\") but claimKey's canonicalization does not — " +
                    "the same proposition would fork into two canonical claims again depending on where it " +
                    "lands relative to a \"because\" split (the computenet-9bip/-qoei/-lv25 defect)",
            )
        }
        assertTrue(
            exercised >= 3,
            "non-vacuity guard: expected at least the three agreed terminators (., …, !) to have been " +
                "stripped by RuleExtractor.extract and exercised above, but only $exercised candidate(s) " +
                "were — a class neutered to strip nothing would otherwise leave this test vacuously green",
        )
    }

    // ------------------------------------------------------------------
    // BS-02 (canonical-claim half) — [AGO1-MINT-01]/[AGO1-MINT-02]
    // ------------------------------------------------------------------

    @Test
    fun `BS-02 AGO1-MINT-01 AGO1-MINT-02 - two utterances from different speakers whose claim texts canonicalize to the same key mint exactly one canonical claim naming both`() {
        val rig = Rig()

        // Same key modulo case and internal whitespace runs — exactly what
        // claimKey()'s canonicalization is defined to fold together. Both
        // texts keep the "." so this stays a case/whitespace pair and nothing
        // else: since computenet-lv25 the terminator would fold too, which the
        // dedicated tests above pin, so it must not be what carries this one.
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
    // AGO1-EXTR-03 — representative text is a function of the set, not order
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-EXTR-03 - representative text is the lexicographically least contributing text, independent of admission order`() {
        // Same key (case folds together), different raw contributing text —
        // exactly the pair ClaimAggregator.value()'s representative-text
        // choice discriminates between.
        val textA = "Rain follows clouds."
        val textB = "RAIN FOLLOWS CLOUDS."
        val expected = listOf(textA, textB).sorted().first()

        val forward = Rig()
        forward.admit(utterance("u1", 1, "alice", textA))
        forward.admit(utterance("u2", 2, "bob", textB))
        val forwardText = forward.canonicalClaims().single().text

        val backward = Rig()
        backward.admit(utterance("u2", 2, "bob", textB))
        backward.admit(utterance("u1", 1, "alice", textA))
        val backwardText = backward.canonicalClaims().single().text

        assertEquals(
            expected,
            forwardText,
            "representative text must be the lexicographically least contributing text",
        )
        assertEquals(
            forwardText,
            backwardText,
            "representative text must not depend on admission order [AGO1-EXTR-03]",
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
