package civictech.dialogue

import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.SetView
import civictech.cell.graph.lookupOrThrow
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractionAccounting
import civictech.dialogue.extract.Extractor
import civictech.dialogue.extract.RuleExtractor
import civictech.dialogue.extract.SegmentStatus
import civictech.dialogue.extract.segmentContentHash
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pipeline stages 2–3 wired into [DialoguePipeline]: segmentation, the
 * extraction gate, and the rejection/failure accounting (epic
 * computenet-2aw §2.2, §3.2, §4 BS-12..BS-15).
 *
 * Assertions are made against what the *graph* observed — each stage's
 * outlet folded through [SetView] — and against the accounting's public
 * query surface. Nothing here reaches into the gate's caches, and nothing
 * here gates on extraction *quality* (epic §3.7).
 *
 * `[AGO1-EXTR-01]` is made true structurally rather than by an assertion:
 * neither `Segmentation.kt` nor `ExtractionGate.kt` nor the pipeline's new
 * wiring touches a clock, a network or a random source, and every test here
 * runs deterministically under a [SimulationController]. A "no network"
 * assertion would be vacuous, so none is written.
 */
class ExtractionPipelineTest {

    /**
     * One host, the three-stage pipeline, and observation sinks on the
     * segment and extracted-item outlets. Copied from `TranscriptSourceTest`'s
     * rig and extended one stage at a time.
     */
    private class Rig(extractor: Extractor, seed: Long = 1L) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        private val built = DialoguePipeline.build(host, extractor)
        val refs = built.refs
        val accounting: ExtractionAccounting = built.accounting

        /** Derived segments, as the graph's consumers see them. */
        val segmentView = SetView<Segment>()

        /** Derived extracted items, as the graph's consumers see them. */
        val itemView = SetView<ExtractedItem>()

        /** Deltas that effectively changed the extracted-item membership. */
        var effectiveItemChanges = 0
            private set

        init {
            host.lookupOrThrow(refs.segments).outlet.subscribe(
                Use.fixed(
                    Propagate<SetDelta<Segment>> { delta -> segmentView.apply(delta) },
                    PortRef.generate(),
                ),
            )
            host.lookupOrThrow(refs.extractedItems).outlet.subscribe(
                Use.fixed(
                    Propagate<SetDelta<ExtractedItem>> { delta ->
                        if (itemView.apply(delta)) effectiveItemChanges++
                    },
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

        fun segmentIds(): List<String> = segmentView.current().map { it.id }.sorted()

        fun claimTexts(): List<String> =
            itemView.current().filterIsInstance<ExtractedClaim>().map { it.text }.sorted()
    }

    private fun cassette(name: String): CassetteExtractor {
        val stream = requireNotNull(javaClass.getResourceAsStream("/cassette/$name")) {
            "test resource not found: cassette/$name"
        }
        return stream.bufferedReader().use { CassetteExtractor.load(it) }
    }

    private fun utterance(id: String, turn: Int, text: String, speaker: String = "alice") =
        Utterance(id = id, turn = turn, speaker = speaker, tsMillis = 1000L * turn, text = text)

    // ------------------------------------------------------------------
    // Stage 2: segmentation as a cell
    // ------------------------------------------------------------------

    @Test
    fun `an admitted utterance derives its segments through the segmentation cell`() {
        val rig = Rig(RuleExtractor)

        rig.admit(utterance("u1", 1, "First point. Second point."))

        assertEquals(listOf("u1#0", "u1#1"), rig.segmentIds())
        assertEquals(listOf("First point.", "Second point."), rig.claimTexts())
    }

    // ------------------------------------------------------------------
    // BS-12 — malformed extraction is rejected, not fatal
    // ------------------------------------------------------------------

    @Test
    fun `BS-12 AGO1-EXTR-05 - blank claim text, an out-of-range stance and a self-relation are each rejected while well-formed items flow`() {
        val rig = Rig(cassette("bs12-malformed.json"))
        val u1 = utterance(
            "u1",
            1,
            "The sky is blue. Rain follows clouds. A stance appears here. A self relation follows.",
        )

        rig.admit(u1)

        // Every well-formed item in the same transcript flowed through —
        // including the one that shared a segment with the blank claim, so
        // "continue processing the remaining items" is exercised.
        assertEquals(listOf("Rain follows clouds", "The sky is blue"), rig.claimTexts())
        assertEquals(2, rig.itemView.current().size, "only the well-formed items are derived")

        val rejected = rig.accounting.rejected
        assertEquals(3, rejected.size, "three malformed items were rejected")

        val blank = rejected.single { it.reason.contains("blank claim text") }
        assertEquals("u1#1", blank.segmentId)

        val stance = rejected.single { it.reason.contains("outside [0.0, 1.0]") }
        assertEquals("u1#2", stance.segmentId)
        assertContains(stance.reason, "1.7")

        val selfRelation = rejected.single { it.reason.contains("self-relation") }
        assertEquals("u1#3", selfRelation.segmentId)
        assertContains(selfRelation.reason, "taxes are high")

        // A rejection is not a failure: each of those segments still extracted.
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u1#0"))
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u1#1"))
        assertEquals(SegmentStatus.Extracted(0), rig.accounting.status("u1#2"))
        assertEquals(SegmentStatus.Extracted(0), rig.accounting.status("u1#3"))
        assertEquals(emptyList(), rig.accounting.failed)

        // The ledger does not grow when the mapper is re-applied — the whole
        // point of the memoized gate (FlatMapSetCell's purity requirement).
        rig.retract(u1)
        assertEquals(emptySet(), rig.itemView.current())
        assertEquals(3, rig.accounting.rejected.size, "retraction re-applied the mapper without re-recording")

        rig.admit(u1)
        assertEquals(2, rig.itemView.current().size)
        assertEquals(3, rig.accounting.rejected.size, "re-admission re-applied the mapper without re-recording")
        assertEquals(emptyList(), rig.accounting.failed)
    }

    // ------------------------------------------------------------------
    // BS-13 — empty extraction changes nothing
    // ------------------------------------------------------------------

    @Test
    fun `BS-13 AGO1-EXTR-04 - an utterance whose extraction is empty leaves the derived set untouched and shows Extracted with zero items`() {
        val rig = Rig(cassette("bs13-empty.json"))
        rig.admit(utterance("u1", 1, "Coffee is good."))

        val before = rig.itemView.current()
        val changesBefore = rig.effectiveItemChanges
        assertEquals(1, before.size)

        rig.admit(utterance("u2", 2, "This says nothing."))

        assertEquals(before, rig.itemView.current(), "an empty extraction altered the derived item set")
        assertEquals(changesBefore, rig.effectiveItemChanges, "an empty extraction produced an effective change")
        // The segment itself exists — it is the extraction that is empty.
        assertEquals(listOf("u1#0", "u2#0"), rig.segmentIds())
        assertEquals(SegmentStatus.Extracted(0), rig.accounting.status("u2#0"))
        assertEquals(emptyList(), rig.accounting.failed)
        assertEquals(emptyList(), rig.accounting.rejected)
    }

    // ------------------------------------------------------------------
    // BS-14 — extractor failure is contained
    // ------------------------------------------------------------------

    @Test
    fun `BS-14 AGO1-EXTR-06 - an extractor that throws for one segment fails only that segment and the pipeline stays live`() {
        val exploding = Extractor { segment ->
            if (segment.id == "u1#1") throw IllegalStateException("extractor exploded") else RuleExtractor.extract(segment)
        }
        val rig = Rig(exploding)

        rig.admit(utterance("u1", 1, "First point stands. Second point explodes. Third point stands."))

        assertEquals(listOf("First point stands.", "Third point stands."), rig.claimTexts())

        val failure = rig.accounting.failed.single()
        assertEquals("u1#1", failure.segmentId)
        assertContains(failure.reason, "IllegalStateException")
        assertContains(failure.reason, "extractor exploded")

        val status = rig.accounting.status("u1#1")
        assertIs<SegmentStatus.Failed>(status)
        assertContains(status.reason, "extractor exploded")
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u1#0"))
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u1#2"))

        // The pipeline stays live: a later admission still derives.
        rig.admit(utterance("u2", 2, "A later point stands."))
        assertContains(rig.claimTexts(), "A later point stands.")
        assertEquals(1, rig.accounting.failed.size, "no further segment failed")
    }

    // ------------------------------------------------------------------
    // BS-15 — cassette miss fails loudly
    // ------------------------------------------------------------------

    @Test
    fun `BS-15 AGO1-EXTR-08 - a cassette miss is a named failure carrying the content hash, not an empty extraction`() {
        val rig = Rig(cassette("bs15-missing.json"))

        rig.admit(utterance("u1", 1, "Recorded sentence."))
        rig.admit(utterance("u2", 2, "Unrecorded sentence."))

        val missed = rig.segmentView.current().single { it.id == "u2#0" }
        val failure = rig.accounting.failed.single()
        assertEquals("u2#0", failure.segmentId)
        assertEquals(segmentContentHash(missed), failure.contentHash)
        assertContains(failure.reason, "CassetteMissException")
        assertContains(failure.reason, segmentContentHash(missed))

        // The distinction BS-15 exists for: a miss is Failed, never Extracted(0).
        val status = rig.accounting.status("u2#0")
        assertIs<SegmentStatus.Failed>(status)
        assertTrue(status != SegmentStatus.Extracted(0), "a miss must be distinguishable from an empty extraction")

        // The rest of the graph is unaffected.
        assertEquals(listOf("Recorded sentence"), rig.claimTexts())
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u1#0"))
    }

    // ------------------------------------------------------------------
    // EXTR-02 — same content, same items
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-EXTR-02 - the same segment content in two different utterances derives the same item set`() {
        val rig = Rig(cassette("extr02-shared-content.json"))
        val shared = "Taxes should be simpler."

        rig.admit(utterance("u1", 1, shared, speaker = "alice"))
        val afterFirst = rig.itemView.current()
        val changesAfterFirst = rig.effectiveItemChanges
        assertEquals(1, afterFirst.size)

        rig.admit(utterance("u2", 2, shared, speaker = "bob"))

        // Both segments derived the same item, so the tagged set holds one
        // element carrying both preimages' tags: no new effective change.
        assertEquals(afterFirst, rig.itemView.current())
        assertEquals(changesAfterFirst, rig.effectiveItemChanges)
        assertEquals(listOf("u1#0", "u2#0"), rig.segmentIds())
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u1#0"))
        assertEquals(SegmentStatus.Extracted(1), rig.accounting.status("u2#0"))

        // Retracting one preimage leaves the item live under the other's tags.
        rig.retract(utterance("u1", 1, shared, speaker = "alice"))
        assertEquals(afterFirst, rig.itemView.current(), "the shared item died with only one of its two preimages")
        assertEquals(listOf("u2#0"), rig.segmentIds())
    }

    // ------------------------------------------------------------------
    // Retraction — [24-SET-01]/[24-SET-03] realized in this module
    // ------------------------------------------------------------------

    @Test
    fun `retracting an utterance retracts its segments and its extracted items`() {
        val rig = Rig(RuleExtractor)
        val u1 = utterance("u1", 1, "Alpha stands. Beta stands.")
        val u2 = utterance("u2", 2, "Gamma stands.")

        rig.admit(u1)
        rig.admit(u2)
        assertEquals(listOf("u1#0", "u1#1", "u2#0"), rig.segmentIds())
        assertEquals(listOf("Alpha stands.", "Beta stands.", "Gamma stands."), rig.claimTexts())

        rig.retract(u1)

        assertEquals(listOf("u2#0"), rig.segmentIds())
        assertEquals(listOf("Gamma stands."), rig.claimTexts())
        assertEquals(emptyList(), rig.accounting.rejected, "retraction recorded no rejection")
        assertEquals(emptyList(), rig.accounting.failed, "retraction recorded no failure")
    }

    // ------------------------------------------------------------------
    // computenet-2aw.4.1 — replay-stable pipeline refs ([AGO1-DUR-01]'s
    // pipeline half)
    // ------------------------------------------------------------------

    /** One independent host+pipeline, built with the given [namespace]. */
    private fun buildOn(namespace: String?): DialoguePipeline.Refs {
        val host = ManagedHost(scheduler = SimulationController(1L).scheduler())
        return DialoguePipeline.build(host, RuleExtractor, namespace = namespace).refs
    }

    @Test
    fun `computenet-2aw_4_1 - same namespace on two separate hosts yields identical pipeline refs`() {
        val a = buildOn(namespace = "recovery-x")
        val b = buildOn(namespace = "recovery-x")

        assertEquals(a.utterances.ref, b.utterances.ref, "utterances ref is stable across builds under the same namespace")
        assertEquals(a.canonicalClaims.ref, b.canonicalClaims.ref, "canonicalClaims ref is stable")
        assertEquals(a.canonicalRelations.ref, b.canonicalRelations.ref, "canonicalRelations ref is stable")
        assertEquals(a.projectedStances.ref, b.projectedStances.ref, "projectedStances ref is stable")

        // Distinct handles under the same namespace still get distinct refs
        // (the handle name is part of the ref seed, not just the namespace).
        assertNotEquals(a.utterances.ref, a.canonicalClaims.ref)
    }

    @Test
    fun `computenet-2aw_4_1 - omitting namespace reproduces today's random-per-build refs`() {
        val a = buildOn(namespace = null)
        val b = buildOn(namespace = null)

        assertNotEquals(a.utterances.ref, b.utterances.ref, "no namespace: refs are fresh (random) per build, as before")
        assertNotEquals(a.canonicalClaims.ref, b.canonicalClaims.ref)
        assertNotEquals(a.canonicalRelations.ref, b.canonicalRelations.ref)
        assertNotEquals(a.projectedStances.ref, b.projectedStances.ref)
    }

    @Test
    fun `computenet-2aw_4_1 - different namespaces on the same handle yield different refs`() {
        val a = buildOn(namespace = "ns-1")
        val b = buildOn(namespace = "ns-2")

        assertNotEquals(a.utterances.ref, b.utterances.ref)
    }
}
