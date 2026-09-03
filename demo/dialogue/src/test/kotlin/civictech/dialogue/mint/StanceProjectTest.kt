package civictech.dialogue.mint

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
import civictech.dialogue.ProjectedStance
import civictech.dialogue.Utterance
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.Extractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * StanceProject (epic computenet-2aw §2.2 stage 6, feature computenet-2aw.3,
 * task computenet-2aw.3.3): the stance leg end to end through
 * [DialoguePipeline.build] — the join against the utterances ingress for
 * event order, and the last-writer-wins fold.
 *
 * `RuleExtractor` never emits an [ExtractedStance] (see its KDoc), and
 * `CassetteExtractor`'s JSON round-trip buys nothing a test-local [Extractor]
 * lambda over segment text doesn't already give more directly — so this test
 * uses a plain in-memory `Extractor { segment -> ... }` keyed by exact
 * (post-segmentation) segment text. Every utterance below is a single
 * sentence with a distinct text, so `ExtractionGate`'s content-hash
 * memoization never coalesces two different utterances' extractions.
 */
class StanceProjectTest {

    private fun extractor(itemsByText: Map<String, List<ExtractedItem>>): Extractor =
        Extractor { segment -> itemsByText[segment.text] ?: emptyList() }

    private inner class Rig(itemsByText: Map<String, List<ExtractedItem>>, seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        private val built = DialoguePipeline.build(host, extractor(itemsByText))
        val refs = built.refs

        /** The StanceProject fold's raw output: (speaker, claim key) -> aggregate. */
        val aggregateView = MapView<Pair<String, ClaimKey>, StanceAggregate>()

        init {
            host.lookupOrThrow(refs.projectedStances).outlet.subscribe(
                Use.fixed(
                    Propagate<MapDelta<Pair<String, ClaimKey>, StanceAggregate>> { delta -> aggregateView.apply(delta) },
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

        fun projectedStances(): List<ProjectedStance> =
            aggregateView.current().map { (key, aggregate) -> StanceProject.projectedStance(key, aggregate) }
    }

    private fun utterance(id: String, turn: Int, speaker: String, text: String) =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    // ------------------------------------------------------------------
    // STANCE-01 — [AGO1-STANCE-01]
    // ------------------------------------------------------------------

    /**
     * Same speaker, same claim key, two utterances whose EVENT order (turn)
     * and whose ADMISSION order are made to genuinely differ, so the test
     * can distinguish last-writer-wins-by-turn from
     * last-writer-wins-by-arrival — a test that only ever admits in turn
     * order cannot tell the two apart.
     */
    private val claimText = "The proposal helps."
    private val earlyText = "Early segment."
    private val lateText = "Late segment."

    // Deliberately NOT "u1"/"u2": their utteranceIds are chosen so that
    // lexicographic order DISAGREES with turn order (early="uz", late="ua",
    // and "ua" < "uz"). A fold that selected by utteranceId alone —
    // ignoring turn entirely — would pick the wrong (early, turn=1) winner
    // here, so this fixture is what makes STANCE-01 actually discriminate
    // "last-writer-wins by event order" from "last-writer-wins by
    // utteranceId": with monotonically-correlated ids (e.g. "u1"/"u2") the
    // two selections coincide and the assertions below pass either way
    // (found in review of computenet-2aw.3.3: dropping `turn` from the
    // aggregator's comparator entirely left this test green under the
    // original "u1"/"u2" fixture).
    private val items = mapOf(
        earlyText to listOf(ExtractedStance(claimText = claimText, speaker = "alice", value = 0.2, utteranceId = "uz")),
        lateText to listOf(ExtractedStance(claimText = claimText, speaker = "alice", value = 0.9, utteranceId = "ua")),
    )

    private val early = utterance("uz", turn = 1, speaker = "alice", text = earlyText)
    private val late = utterance("ua", turn = 5, speaker = "alice", text = lateText)

    @Test
    fun `STANCE-01 - the later-turn stance wins regardless of admission order`() {
        val key = claimKey(claimText)

        // Admission order MATCHES turn order.
        val forward = Rig(items)
        forward.admit(early)
        forward.admit(late)
        val forwardStance = forward.projectedStances().single()
        assertEquals("alice", forwardStance.speaker)
        assertEquals(key, forwardStance.claim)
        assertEquals(0.9, forwardStance.value, "the later-turn (turn 5) stance must win")

        // Admission order is the REVERSE of turn order: the later-turn
        // utterance is admitted FIRST. A last-writer-wins-by-ARRIVAL
        // implementation would pick the turn-1 value (0.2) here, since it
        // arrived last; event-order LWW picks the same turn-5 value (0.9)
        // as the forward run, because the winner is a function of the live
        // set alone [AGO1-STANCE-01].
        val backward = Rig(items)
        backward.admit(late)
        backward.admit(early)
        val backwardStance = backward.projectedStances().single()
        assertEquals(
            0.9,
            backwardStance.value,
            "admitting the later-turn utterance FIRST must still select it — LWW is by event order, not arrival order",
        )
        assertEquals(
            forwardStance.value,
            backwardStance.value,
            "the projected stance must be independent of admission order [AGO1-STANCE-01]",
        )
    }

    @Test
    fun `distinct speakers on the same claim key project independent stances`() {
        val bobItems = mapOf(
            earlyText to listOf(ExtractedStance(claimText = claimText, speaker = "alice", value = 0.2, utteranceId = "uz")),
            lateText to listOf(ExtractedStance(claimText = claimText, speaker = "bob", value = 0.7, utteranceId = "ua")),
        )
        val rig = Rig(bobItems)
        rig.admit(early)
        rig.admit(late)

        val stances = rig.projectedStances()
        assertEquals(2, stances.size, "each speaker gets its own (speaker, claim key) group")
        val bySpeaker = stances.associateBy { it.speaker }
        assertEquals(0.2, bySpeaker.getValue("alice").value)
        assertEquals(0.7, bySpeaker.getValue("bob").value)
    }

    // ------------------------------------------------------------------
    // BS-07 / STANCE-02 (projection half) — [AGO1-STANCE-02]
    // ------------------------------------------------------------------

    @Test
    fun `BS-07 AGO1-STANCE-02 - retracting the sole justifying utterance removes the entry rather than leaving it stale`() {
        val rig = Rig(items)

        rig.admit(early)
        assertEquals(1, rig.projectedStances().size, "precondition: one projected stance from the sole utterance")
        assertEquals(0.2, rig.projectedStances().single().value)

        rig.retract(early)
        assertTrue(
            rig.projectedStances().isEmpty(),
            "the (speaker, claim key) entry must be REMOVED, not left at its last value [AGO1-STANCE-02]",
        )
        assertTrue(
            rig.aggregateView.current().isEmpty(),
            "the raw fold itself must have no entry for this group — no null-valued sentinel is ever emitted",
        )

        // Re-admitting re-projects: this is not a permanently dead group.
        rig.admit(early)
        assertEquals(1, rig.projectedStances().size)
        assertEquals(0.2, rig.projectedStances().single().value)
    }

    @Test
    fun `retracting one of two contributors leaves the survivor's value projected, not a stale blend`() {
        val rig = Rig(items)

        rig.admit(early)
        rig.admit(late)
        assertEquals(0.9, rig.projectedStances().single().value, "precondition: turn-5 stance is winning")

        // Retract the WINNING (later-turn) utterance: the surviving
        // turn-1 contribution must now win, not a stale copy of 0.9.
        rig.retract(late)
        val remaining = rig.projectedStances()
        assertEquals(1, remaining.size, "the group must survive: one contributor (turn 1) is still live")
        assertEquals(0.2, remaining.single().value, "with the winner retracted the surviving contribution must be projected")
    }
}
