package civictech.dialogue.gate

import civictech.agora.AgoraService
import civictech.agora.BatchReference
import civictech.agora.cell.Polarity
import civictech.agora.cell.credenceOf
import civictech.cell.CellRef
import civictech.cell.data.SetOps
import civictech.cell.host.SimulationController
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.DialogueRuntime
import civictech.dialogue.TranscriptLoadResult
import civictech.dialogue.TranscriptLoader
import civictech.dialogue.Utterance
import civictech.dialogue.apply.ApplyOp
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.apply.ReconcileReport
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checked-in demo fixture's gate (epic computenet-2aw §3.6
 * [AGO1-REPLAY-01]/[AGO1-REPLAY-02], §3.8, §4 BS-08, §7 (manual run), §8
 * R3/R5/R6; task computenet-2aw.6.3).
 *
 * Drives `demo/dialogue/src/main/resources/demo/dialogue.jsonl` (a 40-turn
 * town debate over a Main Street bike lane, 4 speakers) through its
 * companion cassette (`dialogue.cassette.json`) on an ephemeral, sim-scheduled
 * [DialogueRuntime] (journalDir = null — no file touched anywhere), the same
 * composition root F5 will wire an HTTP surface onto. [DialogueRuntime] is
 * used **read-only** here; this task does not touch its own file.
 *
 * ### Manual run
 *
 * ```
 * ./gradlew :demo:dialogue:run --args="8090 --transcript demo/dialogue/src/main/resources/demo/dialogue.jsonl --extractor cassette --cassette demo/dialogue/src/main/resources/demo/dialogue.cassette.json"
 * ```
 * (the `--extractor`/`--cassette` flags are F5 T2's, predicted — F5 is not
 * yet landed as this task is written).
 *
 * ### What the fixture exercises, by utterance id (doc/demo-findings.md F-18
 * names the same list)
 *
 * (i) plain claims with the author's stance — every claim-introducing
 *     utterance (u1, u2, u3, u5, u6, u7, u8, u9, u10, u11, u13, u30, u34).
 * (ii) a claim restated verbatim-modulo-case/whitespace by a second speaker —
 *      u12 (bob), a case/whitespace variant of u1's claim text; same
 *      [civictech.dialogue.mint.claimKey], one canonical node, provenance
 *      from two utterance ids.
 * (iii) the SAME idea paraphrased by a THIRD speaker — u13 (carol): a
 *       genuinely different string that means the same thing as u1/u12's
 *       claim, canonicalizing to a DIFFERENT key. R3's identity weakness,
 *       made visible on purpose (asserted below, never hidden).
 * (iv) attacks/supports: u2 (C2 ATTACK C1), u4 (C1 ATTACK C2 — together with
 *      u2 these close the transcript's one 2-cycle), u3 (C3 SUPPORT C1, a
 *      SUPPORT landing on a claim already under attack), plus u6/u7/u9/u11/u16
 *      (further ATTACKs on C1), u8 (ATTACK on C2), u14/u15/u30 (further
 *      SUPPORTs), u34 (further ATTACK on C2).
 * (v) a stance change: u24 (bob) revises his own u2 stance on C2 at a later
 *     turn to a different value — LWW by event order, restated again at u26
 *     (alice on C1) and u32 (dave on C9) for extra coverage.
 * (vi) the utterance that is the SOLE contributor of one relation: u16 (dave,
 *      C9 ATTACK C1) — no other utterance ever asserts that triple. The
 *      completeness test below retracts it directly (the JSONL carries no
 *      retraction records, so retraction is driven through
 *      `DialoguePipeline.utteranceOps(...).remove`, mirroring
 *      [civictech.dialogue.TranscriptSource.reset]'s own mechanism).
 * (vii) a two-sentence utterance: u5 (dave) — "Dave raises a separate safety
 *       issue." (an empty-item segment) then the sentence that introduces C4 —
 *       two [civictech.dialogue.Segment]s, two cassette entries.
 */
class DemoFixtureTest {

    // Canonical claim texts, restated here for the assertions below — the
    // single source of truth is the checked-in cassette; these strings exist
    // so the test can name keys without re-parsing the fixture.
    private val C1 = "A protected bike lane should be installed on Main Street."
    private val C2 = "Removing on-street parking on Main Street will hurt local businesses."
    private val C9 = "Removing a lane on Main Street will slow emergency vehicle response times."
    private val C1B_PARAPHRASE = "Main Street needs dedicated, physically separated space for cyclists."

    /** `AgoraService`'s default head threshold; the cyclic bound is `25 * q`, as `AgoraExitTest`/siblings state. */
    private val q = 1e-3
    private val cyclicTolerance = 25 * q

    // ------------------------------------------------------------------
    // Loading the checked-in fixture from the classpath (main resources are
    // on the test classpath — the same files a `--transcript`/`--cassette`
    // manual run reads from disk).
    // ------------------------------------------------------------------

    private fun loadTranscript(): TranscriptLoadResult =
        TranscriptLoader.load(
            javaClass.getResourceAsStream("/demo/dialogue.jsonl")!!.bufferedReader(),
        )

    private fun loadCassette(): CassetteExtractor =
        CassetteExtractor.load(
            javaClass.getResourceAsStream("/demo/dialogue.cassette.json")!!.bufferedReader(),
        )

    // ------------------------------------------------------------------
    // The rig — DialogueRuntime as the ephemeral composition root
    // (journalDir = null), driven by a SimulationController the test owns
    // directly so it can drain and inspect between steps.
    // ------------------------------------------------------------------

    private class Rig(seed: Long, cassette: CassetteExtractor, transcript: List<Utterance>) {
        private val controller = SimulationController(seed)
        val runtime = DialogueRuntime(
            extractor = cassette,
            transcript = transcript,
            journalDir = null,
            scheduler = controller.scheduler(),
            quiescence = 1e-3,
        )

        /** A second, independent handle onto the SAME ingress cell — the sole way to retract one utterance (vi). */
        private val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(runtime.host, runtime.refs)

        fun drain(budget: Int = 200_000) = controller.runToIdle(budget)

        /** `source.replay(from = 1)` — the bead's own idiom; a no-op re-admission the second time it is called. */
        fun replayAll() = runtime.source.replay(from = 1)

        fun retract(utterance: Utterance) = ops.remove(utterance)

        fun reconcile(): ReconcileReport = runtime.reconcile()

        fun credenceOf(ref: CellRef): Double = runtime.service.hub.credenceOf(ref) ?: 0.5

        val bindings: BindingTable get() = runtime.bindings
        val service: AgoraService get() = runtime.service
    }

    private fun settle(rig: Rig): ReconcileReport {
        rig.drain()
        val report = rig.reconcile()
        rig.drain()
        return report
    }

    // ------------------------------------------------------------------
    // Fixture completeness — no cassette miss, no apply failure, R3 visible.
    // ------------------------------------------------------------------

    @Test
    fun `demo fixture is complete - loads cleanly, extracts cleanly, and makes R3 visible`() {
        val loadResult = loadTranscript()
        assertEquals(emptyList(), loadResult.report.issues, "the checked-in transcript must have zero rejected lines")
        assertTrue(
            loadResult.utterances.size in 40..48,
            "transcript must be 40-48 utterances, was ${loadResult.utterances.size}",
        )

        val rig = Rig(seed = 1L, cassette = loadCassette(), transcript = loadResult.utterances)
        rig.replayAll()
        val report = settle(rig)

        assertEquals(
            emptyList(),
            rig.runtime.accounting.failed,
            "no segment may fail extraction — a cassette miss would show up here (AGO1-EXTR-08)",
        )
        assertEquals(emptyList(), report.failures, "the final reconcile must have no failures")
        assertTrue(
            rig.bindings.boundClaims().size >= 8,
            "expected >= 8 bound claims, got ${rig.bindings.boundClaims().size}",
        )
        assertTrue(
            rig.bindings.boundRelations().size >= 4,
            "expected >= 4 bound relations, got ${rig.bindings.boundRelations().size}",
        )
        assertTrue(
            rig.service.graph().any { it.info.kind == AgoraService.Kind.EDGE && it.info.head },
            "the transcript's 2-cycle (u2/u4) must designate at least one head edge",
        )

        // R3 made visible: the verbatim restatement (u12) shares C1's key; the
        // third speaker's paraphrase (u13) mints a SEPARATE key. Both are bound.
        val keyC1 = claimKey(C1)
        val keyC1b = claimKey(C1B_PARAPHRASE)
        assertTrue(keyC1 != keyC1b, "the paraphrase must canonicalize to a DIFFERENT key than C1 — R3's weakness")
        assertTrue(keyC1 in rig.bindings.boundClaims(), "C1's key (u1 + u12's restatement) must be bound")
        assertTrue(keyC1b in rig.bindings.boundClaims(), "the paraphrase's key (u13) must be bound as its own node")

        println(
            "DemoFixtureTest completeness: ${rig.bindings.boundClaims().size} claims, " +
                "${rig.bindings.boundRelations().size} relations bound",
        )
    }

    // ------------------------------------------------------------------
    // BS-08 / [AGO1-REPLAY-02] — replay idempotence, bit-identical credences.
    // ------------------------------------------------------------------

    @Test
    fun `BS-08 AGO1-REPLAY-02 - replaying the fixture from turn 1 again changes nothing and credences are bit-identical`() {
        val transcript = loadTranscript().utterances
        val rig = Rig(seed = 2L, cassette = loadCassette(), transcript = transcript)
        rig.replayAll()
        settle(rig)

        val claimsBefore = rig.bindings.boundClaims()
        val relationsBefore = rig.bindings.boundRelations()
        val refsBefore = claimsBefore.map { BindingTable.refFor(it) } + relationsBefore.map { BindingTable.refFor(it) }
        val credencesBefore = refsBefore.associateWith { rig.credenceOf(it) }
        val admittedBefore = rig.runtime.source.admitted

        rig.replayAll()
        val report = settle(rig)

        assertEquals(
            emptyList(),
            report.ops,
            "re-replaying an already-admitted transcript must issue zero structure ops",
        )
        assertEquals(claimsBefore, rig.bindings.boundClaims(), "bound claim set must be unchanged")
        assertEquals(relationsBefore, rig.bindings.boundRelations(), "bound relation set must be unchanged")
        assertEquals(admittedBefore, rig.runtime.source.admitted, "the admitted ledger must be unchanged")
        refsBefore.forEach { ref ->
            assertTrue(
                rig.credenceOf(ref) == credencesBefore.getValue(ref),
                "credence for $ref must be bit-identical after replay: ${rig.credenceOf(ref)} vs ${credencesBefore.getValue(ref)}",
            )
        }
    }

    // ------------------------------------------------------------------
    // [AGO1-REPLAY-01] — two fresh pipelines, different world seeds.
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-REPLAY-01 - a second fresh pipeline on a different seed agrees by key, and by credence outside the 2-cycle`() {
        val transcript = loadTranscript().utterances
        val first = Rig(seed = 10L, cassette = loadCassette(), transcript = transcript)
        first.replayAll()
        settle(first)

        val second = Rig(seed = 99L, cassette = loadCassette(), transcript = transcript)
        second.replayAll()
        settle(second)

        assertEquals(first.bindings.boundClaims(), second.bindings.boundClaims(), "bound claim keys must agree")
        assertEquals(first.bindings.boundRelations(), second.bindings.boundRelations(), "bound relation keys must agree")

        val headKeysFirst = first.service.graph()
            .filter { it.info.kind == AgoraService.Kind.EDGE && it.info.head }
            .mapNotNull { first.bindings.keyOf(it.ref) }
            .toSet()
        val headKeysSecond = second.service.graph()
            .filter { it.info.kind == AgoraService.Kind.EDGE && it.info.head }
            .mapNotNull { second.bindings.keyOf(it.ref) }
            .toSet()
        assertEquals(
            headKeysFirst,
            headKeysSecond,
            "the head SET (by key, never by which specific edge) must agree even when arrival order differs (2aw.F6-D5)",
        )

        // The transcript's one 2-cycle: claims C1/C2 and the two edges between
        // them. Everything else must be bit-identical across world seeds;
        // these four alone get the cyclic tolerance (2aw.F6-D5).
        val keyC1 = claimKey(C1)
        val keyC2 = claimKey(C2)
        val relC2AttacksC1 = RelationMint.relationKey(keyC2, keyC1, Polarity.ATTACK)
        val relC1AttacksC2 = RelationMint.relationKey(keyC1, keyC2, Polarity.ATTACK)
        val cyclicRefs = setOf(
            BindingTable.refFor(keyC1),
            BindingTable.refFor(keyC2),
            BindingTable.refFor(relC2AttacksC1),
            BindingTable.refFor(relC1AttacksC2),
        )

        val refs = first.bindings.boundClaims().map { BindingTable.refFor(it) } +
            first.bindings.boundRelations().map { BindingTable.refFor(it) }
        var worstNonCycleGap = 0.0
        var worstCyclicGap = 0.0
        refs.forEach { ref ->
            val a = first.credenceOf(ref)
            val b = second.credenceOf(ref)
            val gap = abs(a - b)
            if (ref in cyclicRefs) {
                worstCyclicGap = maxOf(worstCyclicGap, gap)
                assertTrue(gap <= cyclicTolerance, "cyclic node $ref: $a vs $b beyond the cyclic bound $cyclicTolerance")
            } else {
                worstNonCycleGap = maxOf(worstNonCycleGap, gap)
                assertTrue(a == b, "non-cycle node $ref must be bit-identical across world seeds: $a vs $b")
            }
        }
        println(
            "AGO1-REPLAY-01: worst non-cycle gap $worstNonCycleGap (must be 0.0), " +
                "worst cyclic gap $worstCyclicGap (tol $cyclicTolerance)",
        )
    }

    // ------------------------------------------------------------------
    // Retraction visible — the sole-contributor relation utterance (vi).
    // ------------------------------------------------------------------

    @Test
    fun `retracting the sole-contributor relation utterance unbinds it, removes the EDGE, and the target's credence recovers`() {
        val transcript = loadTranscript().utterances
        val cassette = loadCassette()
        val rig = Rig(seed = 5L, cassette = cassette, transcript = transcript)
        rig.replayAll()
        settle(rig)

        val u16 = transcript.first { it.id == "u16" }
        val keyC1 = claimKey(C1)
        val keyC9 = claimKey(C9)
        val relC9AttacksC1 = RelationMint.relationKey(keyC9, keyC1, Polarity.ATTACK)

        assertTrue(
            relC9AttacksC1 in rig.bindings.boundRelations(),
            "precondition: u16's relation must be bound before retraction",
        )
        val edgeRef = BindingTable.refFor(relC9AttacksC1)
        assertTrue(rig.service.nodeInfo(edgeRef) != null, "precondition: the EDGE node must exist before retraction")

        rig.retract(u16)
        val report = settle(rig)

        assertEquals(
            1,
            report.ops.count { it.kind == ApplyOp.OpKind.REMOVE_RELATION },
            "retracting u16 must issue exactly one REMOVE_RELATION op (ops: ${report.ops})",
        )
        assertTrue(
            relC9AttacksC1 !in rig.bindings.boundRelations(),
            "u16's relation must be unbound after retraction — it was the SOLE contributor",
        )
        assertEquals(null, rig.service.nodeInfo(edgeRef), "the EDGE node must be gone")
        // C9's own claim survives — only its relation to C1 dies with u16.
        assertTrue(keyC9 in rig.bindings.boundClaims(), "C9's claim (introduced separately at u10) must still be bound")

        // C1's credence should now match the batch reference recomputed over
        // the live set WITHOUT u16 — the edge's removal, not merely its
        // absence from a fresh fold.
        val live = transcript.filterNot { it.id == "u16" }
        val reference = BatchReference.solve(DialogueBatchReference.topology(cassette, live))
        val refC1 = BindingTable.refFor(keyC1)
        val expected = reference.getValue(refC1)
        val actual = rig.credenceOf(refC1)
        assertTrue(
            abs(actual - expected) <= cyclicTolerance,
            "C1's credence after retraction ($actual) must match the reference recomputed without u16 ($expected)",
        )
    }
}
