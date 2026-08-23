package civictech.dialogue

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.view.SetView
import civictech.cell.port.streamTo
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractionGate
import civictech.dialogue.extract.SegmentStatus
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Late-join catch-up against a **non-empty** input state — the third
 * re-application path [ExtractionGate]'s KDoc claims its per-segment
 * accounting guard covers ("del-side re-application, late-join catch-up and
 * a re-admission all replay the cached result without growing
 * [ExtractionAccounting.rejected] or [ExtractionAccounting.failed]"), and the
 * one `doc/demo-findings.md` F-13 names as the silent failure mode ("the
 * output set still looks right while the failure ledger inflates on every
 * retraction").
 *
 * `ExtractionPipelineTest` cannot reach this path: every Rig there subscribes
 * before any admission, so `FlatMapSetCell`'s `catchUpOnLinked` hook fires
 * against empty state and re-applies the mapper to nothing. Two mechanics
 * make the difference here:
 *
 * - the stage-3 cell is constructed **directly**, not looked up through
 *   `ManagedHost`, whose `lookupOrThrow` hands back a JDK proxy that is not a
 *   `FanOutlet` and so cannot be `streamTo`'d;
 * - the late consumer attaches with `streamTo`, not `subscribe`: a bare
 *   `subscribe` installs a consumer **without** firing the outlet's on-link
 *   multicast (`doc/demo-findings.md` F-10), so catch-up would never run and
 *   the assertions below would hold vacuously. The
 *   `assertTrue(lateView.current().isNotEmpty())` line is the guard that this
 *   test is not measuring that vacuum.
 */
class ExtractionCatchUpTest {

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    private fun cassette(name: String): CassetteExtractor {
        val stream = requireNotNull(javaClass.getResourceAsStream("/cassette/$name")) {
            "test resource not found: cassette/$name"
        }
        return stream.bufferedReader().use { CassetteExtractor.load(it) }
    }

    /** Stage 3 alone, over the same gate the pipeline builds. */
    private fun stage3(cassetteName: String): Pair<FlatMapSetCell<Segment, ExtractedItem>, ExtractionGate> {
        val gate = ExtractionGate(cassette(cassetteName))
        return FlatMapSetCell<Segment, ExtractedItem>(f = gate::extract) to gate
    }

    private fun segmentsOf(text: String, utteranceId: String = "u1") =
        segment(Utterance(id = utteranceId, turn = 1, speaker = "alice", tsMillis = 1000L, text = text))

    @Test
    fun `a consumer joining a non-empty stage-3 catches up without re-accounting rejections`() {
        val (cell, gate) = stage3("bs12-malformed.json")
        val segments = segmentsOf("The sky is blue. Rain follows clouds. A stance appears here. A self relation follows.")

        val eager = SetView<ExtractedItem>()
        cell.outlet.streamTo(Propagate<SetDelta<ExtractedItem>> { delta -> eager.apply(delta) })
        cell.inlet.call.propagate(
            SetDelta(adds = segments.withIndex().associate { (i, s) -> s to setOf(tag(i.toLong() + 1)) }),
        )

        val itemsBefore = eager.current()
        val rejectedBefore = gate.accounting.rejected
        val failedBefore = gate.accounting.failed
        val statusesBefore = segments.associate { it.id to gate.accounting.status(it.id) }
        assertTrue(rejectedBefore.isNotEmpty(), "fixture must reject something for this test to constrain anything")

        // The late join: catch-up re-applies the mapper over every live segment.
        val lateView = SetView<ExtractedItem>()
        cell.outlet.streamTo(Propagate<SetDelta<ExtractedItem>> { delta -> lateView.apply(delta) })

        assertTrue(lateView.current().isNotEmpty(), "catch-up delivered nothing — this test would be vacuous")
        assertEquals(itemsBefore, lateView.current(), "the late joiner did not catch up to the live item set")
        assertEquals(rejectedBefore, gate.accounting.rejected, "late-join catch-up re-recorded rejections")
        assertEquals(failedBefore, gate.accounting.failed, "late-join catch-up re-recorded failures")
        assertEquals(
            statusesBefore,
            segments.associate { it.id to gate.accounting.status(it.id) },
            "late-join catch-up changed a segment's status",
        )
        assertEquals(itemsBefore, eager.current(), "the original consumer's view changed on someone else's join")
    }

    @Test
    fun `a consumer joining after a failed segment catches up without re-recording the failure`() {
        val (cell, gate) = stage3("bs15-missing.json")
        val recorded = segmentsOf("Recorded sentence.", utteranceId = "u1")
        val missing = segmentsOf("Unrecorded sentence.", utteranceId = "u2")

        val eager = SetView<ExtractedItem>()
        cell.outlet.streamTo(Propagate<SetDelta<ExtractedItem>> { delta -> eager.apply(delta) })
        cell.inlet.call.propagate(SetDelta(adds = mapOf(recorded.single() to setOf(tag(1)))))
        cell.inlet.call.propagate(SetDelta(adds = mapOf(missing.single() to setOf(tag(2)))))

        val itemsBefore = eager.current()
        val failedBefore = gate.accounting.failed
        assertEquals(1, failedBefore.size, "the missing segment must have failed once")

        val lateView = SetView<ExtractedItem>()
        cell.outlet.streamTo(Propagate<SetDelta<ExtractedItem>> { delta -> lateView.apply(delta) })

        assertTrue(lateView.current().isNotEmpty(), "catch-up delivered nothing — this test would be vacuous")
        assertEquals(itemsBefore, lateView.current())
        assertEquals(failedBefore, gate.accounting.failed, "late-join catch-up re-recorded the failed segment")
        assertIs<SegmentStatus.Failed>(gate.accounting.status("u2#0"))
    }
}
