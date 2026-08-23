package civictech.dialogue.extract

import civictech.dialogue.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RuleExtractor` is the pure, zero-dependency demo `Extractor`
 * ([AGO1-EXTR-01]'s extractor half): deterministic, no clock/network/
 * randomness.
 */
class RuleExtractorTest {

    private fun segment(
        id: String = "s1",
        utteranceId: String = "u1",
        ordinal: Int = 0,
        speaker: String = "alice",
        text: String,
    ) = Segment(id = id, utteranceId = utteranceId, ordinal = ordinal, speaker = speaker, text = text)

    @Test
    fun `extracting the same segment twice yields equal lists`() {
        val s = segment(text = "It rains because clouds are heavy")

        val first = RuleExtractor.extract(s)
        val second = RuleExtractor.extract(s)

        assertEquals(first, second)
    }

    @Test
    fun `a plain segment yields just its claim`() {
        val s = segment(text = "The sky is blue", speaker = "alice", utteranceId = "u1")

        val items = RuleExtractor.extract(s)

        assertEquals(
            listOf(ExtractedClaim(text = "The sky is blue", speaker = "alice", utteranceId = "u1")),
            items,
        )
    }

    @Test
    fun `a because-segment yields its claim and a supporting relation`() {
        val s = segment(text = "It rains because clouds are heavy", speaker = "bob", utteranceId = "u2")

        val items = RuleExtractor.extract(s)

        assertEquals(
            listOf(
                ExtractedClaim(text = "It rains because clouds are heavy", speaker = "bob", utteranceId = "u2"),
                ExtractedRelation(
                    sourceText = "clouds are heavy",
                    targetText = "It rains",
                    polarity = "SUPPORT",
                    utteranceId = "u2",
                ),
            ),
            items,
        )
    }

    @Test
    fun `because-matching is case-insensitive`() {
        val s = segment(text = "It rains BECAUSE clouds are heavy")

        val items = RuleExtractor.extract(s)

        assertTrue(items.any { it is ExtractedRelation })
    }

    @Test
    fun `a disagreement-marker segment yields no relation, only its own claim`() {
        val s = segment(text = "No, that's wrong", speaker = "bob", utteranceId = "u3")

        val items = RuleExtractor.extract(s)

        assertEquals(
            listOf(ExtractedClaim(text = "No, that's wrong", speaker = "bob", utteranceId = "u3")),
            items,
        )
    }
}
