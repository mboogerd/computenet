package civictech.dialogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The segmentation rule (epic computenet-2aw §2.2 stage 2, §8/R6) as a plain
 * function — no host or rig is needed at this layer; the cell that maps with
 * it is exercised by `ExtractionPipelineTest`.
 */
class SegmentationTest {

    private fun utterance(text: String, id: String = "u1", speaker: String = "alice") =
        Utterance(id = id, turn = 1, speaker = speaker, tsMillis = 1000, text = text)

    @Test
    fun `splits on sentence-ending punctuation followed by whitespace, keeping the punctuation`() {
        val segments = segment(utterance("The sky is blue. Is it? Yes! Definitely."))

        assertEquals(
            listOf("The sky is blue.", "Is it?", "Yes!", "Definitely."),
            segments.map { it.text },
        )
    }

    @Test
    fun `ordinals are 0-based and ids are utteranceId hash ordinal`() {
        val segments = segment(utterance("One. Two. Three.", id = "u7"))

        assertEquals(listOf(0, 1, 2), segments.map { it.ordinal })
        assertEquals(listOf("u7#0", "u7#1", "u7#2"), segments.map { it.id })
    }

    @Test
    fun `speaker and utteranceId are carried from the utterance`() {
        val segments = segment(utterance("One. Two.", id = "u3", speaker = "bob"))

        assertTrue(segments.all { it.speaker == "bob" })
        assertTrue(segments.all { it.utteranceId == "u3" })
    }

    @Test
    fun `pieces are trimmed and blank pieces are dropped`() {
        // Repeated separators and a trailing run of whitespace would otherwise
        // yield empty segments, which must not consume an ordinal.
        val segments = segment(utterance("  Leading space.   Middle.  \n  "))

        assertEquals(listOf("Leading space.", "Middle."), segments.map { it.text })
        assertEquals(listOf(0, 1), segments.map { it.ordinal })
    }

    @Test
    fun `an utterance with no sentence-ending punctuation is one segment`() {
        assertEquals(listOf("no punctuation at all"), segment(utterance("no punctuation at all")).map { it.text })
    }

    @Test
    fun `a blank utterance yields no segments`() {
        assertEquals(emptyList(), segment(utterance("   \n  ")))
    }

    @Test
    fun `a decimal point is not a sentence boundary because no whitespace follows it`() {
        assertEquals(listOf("Inflation hit 3.5 percent."), segment(utterance("Inflation hit 3.5 percent.")).map { it.text })
    }

    @Test
    fun `the rule is pure - the same utterance segments identically every time`() {
        // Load-bearing: FlatMapSetCell re-applies this mapper to translate
        // removals and to recompute output on late-join catch-up, so an
        // impure rule would strand derived state.
        val u = utterance("First. Second. Third.")

        assertEquals(segment(u), segment(u))
        assertEquals(segment(u), segment(u.copy()))
    }
}
