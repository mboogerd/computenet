package civictech.dialogue.extract

import civictech.dialogue.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `CassetteExtractor` is 2aw.F2-D1's "the only extractor gating tests may
 * use": recorded, content-hash-keyed results loaded from the checked-in
 * fixture `src/test/resources/cassette/basic.json`.
 */
class CassetteExtractorTest {

    private fun loadBasicCassette(): CassetteExtractor {
        val stream = requireNotNull(javaClass.getResourceAsStream("/cassette/basic.json")) {
            "test resource not found: cassette/basic.json"
        }
        return stream.bufferedReader().use { CassetteExtractor.load(it) }
    }

    private fun segment(
        id: String = "s1",
        utteranceId: String = "u1",
        ordinal: Int = 0,
        speaker: String = "alice",
        text: String,
    ) = Segment(id = id, utteranceId = utteranceId, ordinal = ordinal, speaker = speaker, text = text)

    @Test
    fun `a hit returns the recorded items verbatim`() {
        val cassette = loadBasicCassette()
        val s = segment(id = "s-live", utteranceId = "u-live", text = "The sky is blue")

        val items = cassette.extract(s)

        assertEquals(
            listOf(ExtractedClaim(text = "The sky is blue", speaker = "alice", utteranceId = "u-sky-recorded")),
            items,
        )
    }

    @Test
    fun `AGO1-EXTR-02 - equal segment text in two different utterances returns the same item set`() {
        val cassette = loadBasicCassette()
        val inUtteranceA = segment(id = "sA", utteranceId = "uA", speaker = "alice", text = "The sky is blue")
        val inUtteranceB = segment(id = "sB", utteranceId = "uB", speaker = "someone-else", text = "The sky is blue")

        val itemsA = cassette.extract(inUtteranceA)
        val itemsB = cassette.extract(inUtteranceB)

        assertEquals(itemsA, itemsB)
    }

    @Test
    fun `a hit for a claim-plus-relation segment returns both items verbatim, in order`() {
        val cassette = loadBasicCassette()
        val s = segment(text = "It rains because clouds are heavy")

        val items = cassette.extract(s)

        assertEquals(
            listOf(
                ExtractedClaim(
                    text = "It rains because clouds are heavy",
                    speaker = "bob",
                    utteranceId = "u-rain-recorded",
                ),
                ExtractedRelation(
                    sourceText = "clouds are heavy",
                    targetText = "It rains",
                    polarity = "SUPPORT",
                    utteranceId = "u-rain-recorded",
                ),
            ),
            items,
        )
    }

    @Test
    fun `a hit for a stance entry returns it verbatim`() {
        val cassette = loadBasicCassette()
        val s = segment(text = "Alice seems confident about that")

        val items = cassette.extract(s)

        assertEquals(
            listOf(
                ExtractedStance(claimText = "that", speaker = "alice", value = 0.8, utteranceId = "u-stance-recorded"),
            ),
            items,
        )
    }

    @Test
    fun `BS-15 AGO1-EXTR-08 - a miss throws CassetteMissException naming the segment id and content hash`() {
        val cassette = loadBasicCassette()
        val s = segment(id = "s-unrecorded", utteranceId = "u-unrecorded", text = "this text was never recorded")

        val exception = assertFailsWith<CassetteMissException> { cassette.extract(s) }

        assertEquals("s-unrecorded", exception.segmentId)
        assertEquals(segmentContentHash(s), exception.contentHash)
        assertTrue(exception.message!!.contains("s-unrecorded"))
        assertTrue(exception.message!!.contains(exception.contentHash))
    }

    @Test
    fun `a cassette entry with an unparseable-polarity relation loads fine - validation is not this layer's job`() {
        val cassette = loadBasicCassette()
        val s = segment(text = "The moon landing was faked, obviously")

        val items = cassette.extract(s)

        val relation = items.single() as ExtractedRelation
        assertEquals("SKEPTICAL", relation.polarity)
    }
}
