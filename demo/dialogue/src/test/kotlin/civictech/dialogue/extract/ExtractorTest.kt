package civictech.dialogue.extract

import civictech.dialogue.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `segmentContentHash` is the identity `Extractor`/`CassetteExtractor`
 * dispatch on ([AGO1-EXTR-02]): a pure function of a segment's `text` field
 * alone, JDK-only SHA-256 rendered lowercase hex.
 */
class ExtractorTest {

    private fun segment(
        id: String = "s1",
        utteranceId: String = "u1",
        ordinal: Int = 0,
        speaker: String = "alice",
        text: String,
    ) = Segment(id = id, utteranceId = utteranceId, ordinal = ordinal, speaker = speaker, text = text)

    @Test
    fun `hash depends on text only, not speaker, utteranceId, or id`() {
        val a = segment(id = "s1", utteranceId = "u1", speaker = "alice", text = "hello world")
        val b = segment(id = "s2", utteranceId = "u2", speaker = "bob", text = "hello world")

        assertEquals(segmentContentHash(a), segmentContentHash(b))
    }

    @Test
    fun `hash differs when text differs`() {
        val a = segment(text = "hello world")
        val b = segment(text = "goodbye world")

        assertNotEquals(segmentContentHash(a), segmentContentHash(b))
    }

    @Test
    fun `hash is lowercase hex SHA-256 of the UTF-8 text`() {
        // Independently computed: shasum -a 256 <<< "The sky is blue" (no trailing newline)
        val expected = "0669b4c1d5a6b14a0c301d536a517ac9285902564cdb9e3cc4daff76a50f37e1"
        val actual = segmentContentHash(segment(text = "The sky is blue"))

        assertEquals(expected, actual)
        assertEquals(64, actual.length)
        assertEquals(actual.lowercase(), actual)
    }
}
