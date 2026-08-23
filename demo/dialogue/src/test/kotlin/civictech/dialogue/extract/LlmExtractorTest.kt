package civictech.dialogue.extract

import civictech.dialogue.Segment
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `LlmExtractor` is the record-through wrapper around an injected model
 * function (`[AGO1-EXTR-07]`, epic computenet-2aw DESIGN 2aw-D4). Every
 * "model" here is a deterministic stub lambda — no live LLM anywhere in
 * this file, which is the acceptance clause itself (epic §8/R1, 2aw.F2-D1),
 * not a shortcut.
 */
class LlmExtractorTest {

    private fun segment(
        id: String = "s1",
        utteranceId: String = "u1",
        ordinal: Int = 0,
        speaker: String = "alice",
        text: String,
    ) = Segment(id = id, utteranceId = utteranceId, ordinal = ordinal, speaker = speaker, text = text)

    @Test
    fun `record-through - extracting two distinct segments records both content-hash to items pairs`() {
        val recorder = CassetteRecorder()
        val model: (Segment) -> List<ExtractedItem> = { s ->
            listOf(ExtractedClaim(text = s.text, speaker = s.speaker, utteranceId = s.utteranceId))
        }
        val extractor = LlmExtractor(model = model, recorder = recorder)

        val segmentA = segment(id = "sA", utteranceId = "uA", text = "The sky is blue")
        val segmentB = segment(id = "sB", utteranceId = "uB", speaker = "bob", text = "Grass is green")

        val itemsA = extractor.extract(segmentA)
        val itemsB = extractor.extract(segmentB)

        assertEquals(model(segmentA), itemsA)
        assertEquals(model(segmentB), itemsB)
        assertEquals(
            mapOf(
                segmentContentHash(segmentA) to itemsA,
                segmentContentHash(segmentB) to itemsB,
            ),
            recorder.recorded,
        )
    }

    @Test
    fun `AGO1-EXTR-07 - a cassette written by the recorder round-trips through CassetteExtractor`() {
        val recorder = CassetteRecorder()
        val model: (Segment) -> List<ExtractedItem> = { s ->
            when {
                s.text.contains(" because ", ignoreCase = true) -> {
                    val (claim, reason) = s.text.split(" because ", ignoreCase = true, limit = 2)
                    listOf(
                        ExtractedClaim(text = s.text, speaker = s.speaker, utteranceId = s.utteranceId),
                        ExtractedRelation(
                            sourceText = reason,
                            targetText = claim,
                            polarity = "SUPPORT",
                            utteranceId = s.utteranceId,
                        ),
                    )
                }
                else -> listOf(ExtractedClaim(text = s.text, speaker = s.speaker, utteranceId = s.utteranceId))
            }
        }
        val extractor = LlmExtractor(model = model, recorder = recorder)

        val segmentA = segment(id = "sA", utteranceId = "uA", text = "The sky is blue")
        val segmentB = segment(id = "sB", utteranceId = "uB", speaker = "bob", text = "It rains because clouds are heavy")

        val liveItemsA = extractor.extract(segmentA)
        val liveItemsB = extractor.extract(segmentB)

        val tempFile = createTempFile(prefix = "llm-extractor-cassette", suffix = ".json")
        try {
            recorder.write(tempFile.toFile())

            val loaded = CassetteExtractor.load(tempFile.toFile())

            // Same segments (by content) extract to the same item sets through the loaded cassette.
            assertEquals(liveItemsA, loaded.extract(segmentA))
            assertEquals(liveItemsB, loaded.extract(segmentB))
        } finally {
            tempFile.deleteIfExists()
        }
    }

    @Test
    fun `content-keyed, not utterance-id-keyed - identical text in a different utterance hits the same recorded entry`() {
        val recorder = CassetteRecorder()
        val model: (Segment) -> List<ExtractedItem> = { s ->
            listOf(ExtractedClaim(text = s.text, speaker = s.speaker, utteranceId = s.utteranceId))
        }
        val extractor = LlmExtractor(model = model, recorder = recorder)

        val original = segment(id = "s-orig", utteranceId = "u-orig", speaker = "alice", text = "Water boils at 100C")
        extractor.extract(original)

        val sameTextDifferentUtterance = segment(
            id = "s-other",
            utteranceId = "u-other",
            speaker = "someone-else",
            text = "Water boils at 100C",
        )

        assertEquals(segmentContentHash(original), segmentContentHash(sameTextDifferentUtterance))
        assertTrue(recorder.recorded.containsKey(segmentContentHash(sameTextDifferentUtterance)))
        assertEquals(1, recorder.recorded.size)
    }

    @Test
    fun `a model throw propagates and records nothing for that segment`() {
        val recorder = CassetteRecorder()
        val boom = RuntimeException("model unavailable")
        val model: (Segment) -> List<ExtractedItem> = { throw boom }
        val extractor = LlmExtractor(model = model, recorder = recorder)

        val failing = segment(text = "this segment fails")

        val thrown = assertFailsWith<RuntimeException> { extractor.extract(failing) }

        assertEquals(boom, thrown)
        assertTrue(recorder.recorded.isEmpty())
    }

    @Test
    fun `re-recording the same content hash with a different result is last-write-wins and flags a collision`() {
        val recorder = CassetteRecorder()
        val s = segment(text = "ambiguous segment")

        val firstResult = listOf(ExtractedClaim(text = "first take", speaker = "alice", utteranceId = "u1"))
        val secondResult = listOf(ExtractedClaim(text = "second take", speaker = "alice", utteranceId = "u1"))

        recorder.record(s, firstResult)
        recorder.record(s, secondResult)

        assertEquals(secondResult, recorder.recorded[segmentContentHash(s)])
        assertEquals(1, recorder.collisions.size)
        assertEquals(segmentContentHash(s), recorder.collisions.single().contentHash)
        assertEquals(firstResult, recorder.collisions.single().previous)
        assertEquals(secondResult, recorder.collisions.single().replacement)
    }
}
