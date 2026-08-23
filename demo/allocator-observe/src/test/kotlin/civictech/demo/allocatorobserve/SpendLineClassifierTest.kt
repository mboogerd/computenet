package civictech.demo.allocatorobserve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpendLineClassifierTest {

    @Test
    fun `valid v1 line yields Valid with all six fields`() {
        val line =
            """{"v":1,"project":"computenet","machine":"MacBoo","work_item":"fpml.1.1","started":"2026-08-23T09:00:00Z","ended":"2026-08-23T09:30:00Z"}"""

        val result = classifySpendLine(line)

        assertTrue(result is LineClassification.Valid)
        val record = (result as LineClassification.Valid).record
        assertEquals(1, record.v)
        assertEquals("computenet", record.project)
        assertEquals("MacBoo", record.machine)
        assertEquals("fpml.1.1", record.workItem)
        assertEquals("2026-08-23T09:00:00Z", record.started)
        assertEquals("2026-08-23T09:30:00Z", record.ended)
    }

    @Test
    fun `not json yields Malformed`() {
        assertEquals(LineClassification.Malformed, classifySpendLine("not json"))
    }

    @Test
    fun `unknown version yields UnknownVersion with the numeric v`() {
        val line =
            """{"v":99,"project":"computenet","machine":"MacBoo","work_item":"fpml.1.1","started":"x","ended":"y"}"""

        assertEquals(LineClassification.UnknownVersion(99), classifySpendLine(line))
    }

    @Test
    fun `v1 line missing a field yields Malformed`() {
        val line = """{"v":1,"project":"computenet","machine":"MacBoo","work_item":"fpml.1.1","started":"x"}"""

        assertEquals(LineClassification.Malformed, classifySpendLine(line))
    }

    @Test
    fun `v1 line with an extra unknown key yields Malformed`() {
        val line =
            """{"v":1,"project":"computenet","machine":"MacBoo","work_item":"fpml.1.1","started":"x","ended":"y","extra":"z"}"""

        assertEquals(LineClassification.Malformed, classifySpendLine(line))
    }

    @Test
    fun `empty string is malformed and does not throw`() {
        assertEquals(LineClassification.Malformed, classifySpendLine(""))
    }

    @Test
    fun `json array is malformed`() {
        assertEquals(LineClassification.Malformed, classifySpendLine("[1,2,3]"))
    }

    @Test
    fun `v as a string is malformed, not treated as an integer`() {
        val line = """{"v":"1","project":"computenet","machine":"MacBoo","work_item":"fpml.1.1","started":"x","ended":"y"}"""

        assertEquals(LineClassification.Malformed, classifySpendLine(line))
    }

    @Test
    fun `v1 line with a wrong-typed field yields Malformed`() {
        val line = """{"v":1,"project":123,"machine":"MacBoo","work_item":"fpml.1.1","started":"x","ended":"y"}"""

        assertEquals(LineClassification.Malformed, classifySpendLine(line))
    }
}
