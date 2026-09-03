package civictech.dialogue.gate

import civictech.agora.AgoraService
import civictech.agora.BatchReference
import civictech.agora.cell.Polarity
import civictech.agora.cell.credenceOf
import civictech.cell.CellRef
import civictech.cell.data.SetOps
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.apply.ApplyOp
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.apply.GraphApplier
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.segmentContentHash
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import civictech.testkit.SimWorld
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.StringReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BS-11 — the **cyclic transcript step-budget probe** (epic computenet-2aw §4
 * BS-11, §3.4 [AGO1-APPLY-03], §3.8's G-19 caveat, §8 R4/R5; task
 * computenet-2aw.6.2).
 *
 * Two speakers attack each other's claim. The transcript is replayed **in turn
 * order, one utterance at a time**, each utterance quiesced under an explicit
 * [STEP_BUDGET] and reconciled at that quiescence (§8 R4's quiescence-scoped
 * apply). What the probe pins:
 *
 * 1. **The graph quiesces within budget after every step.** `runToIdle(budget)`
 *    throws when the budget is exhausted, so reaching the assertions below at
 *    all is the proof; the returned step counts are asserted against
 *    [STEP_BUDGET] as well so the margin is visible rather than implied.
 * 2. **Exactly one of the two mutual-attack edges is a head**, and it is the
 *    one created *second* — `AgoraService.createEdge` sets
 *    `head = reaches(target, source)` at creation time.
 * 3. **Both relations went through `createEdge`**: both appear as
 *    `CREATE_RELATION` ops in the applier's reports and both are
 *    `Kind.EDGE` agora nodes. A directly-spawned `EdgeCell` would be neither
 *    — that is exactly what [AGO1-APPLY-03] exists to forbid.
 * 4. **The attacked claims settle within `25 * 1e-3` of the batch reference**
 *    over the same topology (`AgoraExitTest`'s bound for a cyclic graph; the
 *    fixpoint of a graph with a head is path-dependent within the head's
 *    absorb threshold).
 *
 * ### The second world is the point, not a flourish
 *
 * "Head designation is creation-order dependent" is easy to assert as a KDoc
 * claim and prove nothing. So the same transcript is replayed a second time
 * with the two attack utterances' **turns swapped**, and the head is asserted
 * to have moved to the other edge. That turns the claim into a pinned
 * property, and it is the local, hand-built companion to
 * [OrderIndependenceTest]'s generative observation of the same mechanism
 * (2aw.F6-D5, G-19 — see `doc/demo-findings.md` F-17).
 *
 * ### No cycle machinery
 *
 * There is deliberately no retry, no threshold knob and no budget escalation
 * here. Per 2aw.F6-D3 a probe that fails to quiesce within [STEP_BUDGET] is a
 * `doc/demo-findings.md` entry under the G-19 residual — never a raised
 * budget.
 */
class CycleProbeTest {

    /**
     * The step budget every drain in this test runs under — `SimWorld`'s own
     * default, restated as a named constant so the assertion messages can
     * quote it and so raising it would be a visible edit.
     */
    private val STEP_BUDGET = 200_000

    /** `AgoraService`'s default head threshold; the cyclic bound is `25 * q`, as `AgoraExitTest` states. */
    private val q = 1e-3
    private val cyclicTolerance = 25 * q

    // ------------------------------------------------------------------
    // The transcript — hand-written, in DialogueRuntimeTest's cassette idiom
    // ------------------------------------------------------------------

    private val claimA = "Cats purr."
    private val claimB = "Dogs bark."
    private val claimC = "Birds sing."

    /** u1 alice: introduces A with a stance. */
    private val textIntroA = "Alice asserts that cats purr."

    /** u2 bob: introduces B with a stance. */
    private val textIntroB = "Bob asserts that dogs bark."

    /** u3 bob: B --ATTACK--> A. */
    private val textAttackBA = "Bob objects that the dogs make that impossible."

    /** u4 alice: A --ATTACK--> B, closing the 2-cycle. */
    private val textAttackAB = "Alice objects right back at the dogs."

    /** u5 carol: introduces C with a stance AND C --SUPPORT--> A in one utterance. */
    private val textSupportCA = "Carol adds that birds sing, which backs the cats."

    /** u6 alice: a stance change on A, later turn, different value. */
    private val textStanceChange = "Alice lowers her confidence about the cats."

    private val cassetteEntries: Map<String, List<ExtractedItem>> = mapOf(
        textIntroA to listOf(
            ExtractedClaim(text = claimA, speaker = "alice", utteranceId = "u1"),
            ExtractedStance(claimText = claimA, speaker = "alice", value = 0.8, utteranceId = "u1"),
        ),
        textIntroB to listOf(
            ExtractedClaim(text = claimB, speaker = "bob", utteranceId = "u2"),
            ExtractedStance(claimText = claimB, speaker = "bob", value = 0.7, utteranceId = "u2"),
        ),
        textAttackBA to listOf(
            ExtractedRelation(sourceText = claimB, targetText = claimA, polarity = "ATTACK", utteranceId = "u3"),
        ),
        textAttackAB to listOf(
            ExtractedRelation(sourceText = claimA, targetText = claimB, polarity = "ATTACK", utteranceId = "u4"),
        ),
        textSupportCA to listOf(
            ExtractedClaim(text = claimC, speaker = "carol", utteranceId = "u5"),
            ExtractedStance(claimText = claimC, speaker = "carol", value = 0.6, utteranceId = "u5"),
            ExtractedRelation(sourceText = claimC, targetText = claimA, polarity = "SUPPORT", utteranceId = "u5"),
        ),
        textStanceChange to listOf(
            ExtractedStance(claimText = claimA, speaker = "alice", value = 0.4, utteranceId = "u6"),
        ),
    ).mapKeys { (text, _) -> segmentContentHash(hashSegment(text)) }

    private fun hashSegment(text: String) =
        Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text)

    private fun cassette(): CassetteExtractor = CassetteExtractor.load(
        StringReader(
            Json.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
                cassetteEntries,
            ),
        ),
    )

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    private val u1 = utterance("u1", 1, "alice", textIntroA)
    private val u2 = utterance("u2", 2, "bob", textIntroB)
    private val u3 = utterance("u3", 3, "bob", textAttackBA)
    private val u4 = utterance("u4", 4, "alice", textAttackAB)
    private val u5 = utterance("u5", 5, "carol", textSupportCA)
    private val u6 = utterance("u6", 6, "alice", textStanceChange)

    private val keyA = claimKey(claimA)
    private val keyB = claimKey(claimB)
    private val keyC = claimKey(claimC)

    private val attackBA: RelationKey = RelationMint.relationKey(keyB, keyA, Polarity.ATTACK)
    private val attackAB: RelationKey = RelationMint.relationKey(keyA, keyB, Polarity.ATTACK)
    private val supportCA: RelationKey = RelationMint.relationKey(keyC, keyA, Polarity.SUPPORT)

    private val refA get() = BindingTable.refFor(keyA)
    private val refB get() = BindingTable.refFor(keyB)
    private val refAttackBA get() = BindingTable.refFor(attackBA)
    private val refAttackAB get() = BindingTable.refFor(attackAB)

    // ------------------------------------------------------------------
    // The rig
    // ------------------------------------------------------------------

    private inner class Rig(seed: Long) {
        val world = SimWorld(seed = seed)
        private val built = DialoguePipeline.build(world.host, cassette())
        val service = AgoraService(world.host, world.registry)
        val bindings = BindingTable(journalDir = null)
        val applier = GraphApplier(world.host, built.refs, service, bindings)
        private val ops: SetOps<Utterance> = DialoguePipeline.utteranceOps(world.host, built.refs)

        /** Every structure op the applier issued across the whole replay. */
        val issued = mutableListOf<ApplyOp>()

        /** The step counts each budgeted drain reported, so the margin is a number, not a hope. */
        val drains = mutableListOf<Int>()

        /**
         * Admit one utterance, quiesce under [STEP_BUDGET], reconcile at that
         * quiescence, quiesce again. `runToIdle` throws when the budget is
         * exhausted, so a non-quiescing graph fails here rather than hanging.
         */
        fun step(utterance: Utterance) {
            ops.add(utterance)
            drains += world.runToIdle(budget = STEP_BUDGET)
            val report = applier.reconcile()
            assertEquals(
                emptyList(),
                report.failures,
                "turn ${utterance.turn}: no agora write may be rejected during the probe",
            )
            issued += report.ops
            drains += world.runToIdle(budget = STEP_BUDGET)
        }

        fun credenceOf(ref: CellRef): Double = service.hub.credenceOf(ref) ?: 0.5
    }

    /** Replay [transcript] in turn order through a fresh rig. */
    private fun probe(transcript: List<Utterance>, seed: Long): Rig {
        val rig = Rig(seed)
        transcript.sortedBy { it.turn }.forEach { rig.step(it) }
        return rig
    }

    /** The batch reference over the whole transcript — the same topology in both worlds. */
    private fun batch(): Map<CellRef, Double> =
        BatchReference.solve(DialogueBatchReference.topology(cassette(), listOf(u1, u2, u3, u4, u5, u6)))

    // ------------------------------------------------------------------
    // BS-11
    // ------------------------------------------------------------------

    @Test
    fun `BS-11 AGO1-APPLY-03 - a mutual attack quiesces within budget with one head and both edges via createEdge`() {
        val rig = probe(listOf(u1, u2, u3, u4, u5, u6), seed = 11L)

        // (1) Quiescence within budget. Reaching this line already proves it —
        //     runToIdle throws on exhaustion — but the recorded counts make the
        //     margin visible instead of implied.
        assertTrue(rig.drains.isNotEmpty(), "the probe must have drained at least once")
        rig.drains.forEach { steps ->
            assertTrue(
                steps < STEP_BUDGET,
                "BS-11: every drain must reach idle within the step budget $STEP_BUDGET (a drain took $steps)",
            )
        }

        // (2) Exactly one head, and it is the edge created SECOND — u4's
        //     A -> B, which closed the 2-cycle u3's B -> A had left open.
        val edgeBA = assertNotNull(rig.service.nodeInfo(refAttackBA), "B -> A must be an agora node")
        val edgeAB = assertNotNull(rig.service.nodeInfo(refAttackAB), "A -> B must be an agora node")
        assertEquals(
            false,
            edgeBA.head,
            "the edge created FIRST closed no cycle: reaches(A -> B) was false when B -> A was created",
        )
        assertEquals(
            true,
            edgeAB.head,
            "the edge created SECOND closes the 2-cycle and must be designated head [AGO1-APPLY-03]",
        )
        assertEquals(
            1,
            rig.service.graph().count { it.info.kind == AgoraService.Kind.EDGE && it.info.head },
            "BS-11: exactly one of the two mutual-attack edges is a head",
        )

        // (3) Both went through createEdge: a CREATE_RELATION op each, and each
        //     is a Kind.EDGE agora node. A directly-spawned EdgeCell would be
        //     registered as no node at all and would have produced no op.
        val created = rig.issued.filter { it.kind == ApplyOp.OpKind.CREATE_RELATION }.map { it.ref }.toSet()
        assertTrue(refAttackBA in created, "[AGO1-APPLY-03] B -> A must be a CREATE_RELATION op (ops: ${rig.issued})")
        assertTrue(refAttackAB in created, "[AGO1-APPLY-03] A -> B must be a CREATE_RELATION op (ops: ${rig.issued})")
        assertEquals(AgoraService.Kind.EDGE, edgeBA.kind)
        assertEquals(AgoraService.Kind.EDGE, edgeAB.kind)
        assertEquals(Polarity.ATTACK, edgeBA.polarity)
        assertEquals(Polarity.ATTACK, edgeAB.polarity)
        assertEquals(refB, edgeBA.source)
        assertEquals(refA, edgeBA.target)
        assertEquals(refA, edgeAB.source)
        assertEquals(refB, edgeAB.target)

        // The third speaker's SUPPORT is in the graph too, so the cycle is not
        // the only influence on A.
        assertNotNull(rig.service.nodeInfo(BindingTable.refFor(supportCA)), "C -> A SUPPORT must exist")

        // (4) The attacked claims against the batch reference over the same
        //     topology, within the cyclic bound (2aw.F6-D5).
        val reference = batch()
        listOf("A" to refA, "B" to refB).forEach { (name, ref) ->
            val expected = reference.getValue(ref)
            val actual = rig.credenceOf(ref)
            assertTrue(
                abs(actual - expected) <= cyclicTolerance,
                "BS-11: claim $name settled at $actual, batch reference $expected, beyond $cyclicTolerance",
            )
        }
        println(
            "BS-11 turn order: A ${rig.credenceOf(refA)} vs batch ${reference.getValue(refA)}, " +
                "B ${rig.credenceOf(refB)} vs batch ${reference.getValue(refB)}; " +
                "max drain ${rig.drains.max()} of budget $STEP_BUDGET",
        )
    }

    @Test
    fun `BS-11 2aw-F6-D5 - swapping the two attack turns moves the head to the other edge`() {
        // The same six utterances and the same cassette; only the two attack
        // utterances' turns are exchanged, so A -> B is now created first and
        // B -> A is the edge that closes the cycle. Nothing else about the
        // scenario changes — same texts, same ids, same extraction, therefore
        // the same canonical topology and the same batch reference.
        val swapped = listOf(u1, u2, u4.copy(turn = 3, tsMillis = 3000L), u3.copy(turn = 4, tsMillis = 4000L), u5, u6)
        val rig = probe(swapped, seed = 11L)

        val edgeAB = assertNotNull(rig.service.nodeInfo(refAttackAB), "A -> B must be an agora node")
        val edgeBA = assertNotNull(rig.service.nodeInfo(refAttackBA), "B -> A must be an agora node")
        assertEquals(
            false,
            edgeAB.head,
            "with the turns swapped, A -> B is created FIRST and closes no cycle",
        )
        assertEquals(
            true,
            edgeBA.head,
            "with the turns swapped, B -> A is the edge that closes the 2-cycle and must be the head — head " +
                "designation is CREATION-ORDER dependent (2aw.F6-D5), not a property of the canonical relation set",
        )
        assertEquals(
            1,
            rig.service.graph().count { it.info.kind == AgoraService.Kind.EDGE && it.info.head },
            "still exactly one head",
        )

        // Same canonical topology, so the same batch reference — and the
        // incremental result stays within the cyclic bound despite the head
        // having moved. That the bound is needed at all is the G-19 residual.
        val reference = batch()
        listOf("A" to refA, "B" to refB).forEach { (name, ref) ->
            val expected = reference.getValue(ref)
            val actual = rig.credenceOf(ref)
            assertTrue(
                abs(actual - expected) <= cyclicTolerance,
                "BS-11 swapped: claim $name settled at $actual, batch reference $expected, beyond $cyclicTolerance",
            )
        }
        assertEquals(
            setOf(keyA, keyB, keyC),
            rig.bindings.boundClaims(),
            "the swap must not change the canonical claim set",
        )
        assertEquals(
            setOf(attackBA, attackAB, supportCA),
            rig.bindings.boundRelations(),
            "the swap must not change the canonical relation set — only which edge is head",
        )
        println(
            "BS-11 swapped order: A ${rig.credenceOf(refA)} vs batch ${reference.getValue(refA)}, " +
                "B ${rig.credenceOf(refB)} vs batch ${reference.getValue(refB)}",
        )
    }
}
