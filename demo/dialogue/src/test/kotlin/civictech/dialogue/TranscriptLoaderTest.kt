package civictech.dialogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests are named for the BS/requirement ids they realize (epic
 * computenet-2aw §3.1, §4).
 */
class TranscriptLoaderTest {

    private fun resourceLines(name: String): Sequence<String> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/$name")) {
            "test resource not found: $name"
        }
        return stream.bufferedReader().readLines().asSequence()
    }

    @Test
    fun `BS-16 AGO1-SRC-05 - one unparseable line among 20 is named in the report, the other 19 utterances load`() {
        val result = TranscriptLoader.load(resourceLines("bs16-one-bad-line.jsonl"))

        assertEquals(19, result.utterances.size)
        assertEquals(19, result.report.parsedCount)
        assertEquals(1, result.report.rejectedCount)

        val issue = result.report.issues.single()
        assertEquals(10, issue.lineNumber)
        assertNotNull(issue.reason)
        assertTrue(issue.reason.isNotBlank())

        // The 19 admitted utterances are exactly the well-formed lines,
        // in file order, none of them the rejected line-10 record.
        assertEquals((1..20).filter { it != 10 }.map { "u$it" }, result.utterances.map { it.id })
    }

    @Test
    fun `AGO1-SRC-01 - a well-formed fixture yields utterances each carrying unique id, turn ordinal, speaker, tsMillis, text`() {
        val result = TranscriptLoader.load(resourceLines("well-formed.jsonl"))

        assertEquals(0, result.report.rejectedCount)
        assertEquals(3, result.utterances.size)

        val ids = result.utterances.map { it.id }
        assertEquals(ids.distinct(), ids, "ids must be unique")

        result.utterances.forEach { u ->
            assertTrue(u.id.isNotBlank())
            assertTrue(u.turn > 0)
            assertTrue(u.speaker.isNotBlank())
            assertTrue(u.tsMillis > 0)
            assertTrue(u.text.isNotBlank())
        }

        assertEquals(
            listOf(
                Utterance("u1", 1, "alice", 1000, "Good morning everyone."),
                Utterance("u2", 2, "bob", 1500, "Morning, Alice."),
                Utterance("u3", 3, "alice", 2000, "Shall we start with the agenda?"),
            ),
            result.utterances,
        )
    }

    @Test
    fun `a duplicate-id but otherwise parseable line is returned as parsed - the loader does not police semantics`() {
        val result = TranscriptLoader.load(resourceLines("duplicate-id-parseable.jsonl"))

        assertEquals(0, result.report.rejectedCount)
        assertEquals(2, result.utterances.size)
        assertEquals(listOf("u1", "u1"), result.utterances.map { it.id })
    }

    @Test
    fun `blank lines are skipped and not reported as issues`() {
        val result = TranscriptLoader.load(
            sequenceOf(
                """{"id":"u1","turn":1,"speaker":"alice","tsMillis":1000,"text":"hi"}""",
                "",
                "   ",
                """{"id":"u2","turn":2,"speaker":"bob","tsMillis":2000,"text":"hey"}""",
            ),
        )

        assertEquals(0, result.report.rejectedCount)
        assertEquals(2, result.utterances.size)
        assertEquals(listOf("u1", "u2"), result.utterances.map { it.id })
    }

    @Test
    fun `a bad line does not throw - loading continues past it`() {
        val result = TranscriptLoader.load(
            sequenceOf(
                "not json at all",
                """{"id":"u2","turn":2,"speaker":"bob","tsMillis":2000,"text":"hey"}""",
            ),
        )

        assertEquals(1, result.utterances.size)
        assertEquals("u2", result.utterances.single().id)
        assertEquals(1, result.report.rejectedCount)
        assertEquals(1, result.report.issues.single().lineNumber)
    }
}
