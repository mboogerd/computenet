package civictech.demo.studio

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StudioSchemasTest {
    private fun schema(text: String) = json.parseToJsonElement(text).jsonObject
    @Test fun `fixed dialect validates nullable nested numeric enum and array constraints`() {
        val s = schema("""{"type":"object","properties":{"value":{"type":["integer","null"],"enum":[null,1,2]},"rows":{"type":"array","items":{"type":"string"},"minItems":1}},"required":["value","rows"],"additionalProperties":false}""")
        StudioSchemas.validate(s, """{"value":null,"rows":["x"]}""")
        StudioSchemas.validate(s, """{"value":1.0,"rows":["x"]}""")
        for (bad in listOf("""{"value":1.5,"rows":["x"]}""", """{"value":3,"rows":[]}""", """{"rows":[1]}"""))
            assertThrows(IllegalStateException::class.java) { StudioSchemas.validate(s, bad) }
        assertSame(StudioSchemas.compile(s), StudioSchemas.compile(s))
    }
    @Test fun `only bounded offline nonrecursive definitions resolve and unknown keywords fail at config time`() {
        val s = schema("""{"type":"object","properties":{"n":{"${'$'}ref":"#/${'$'}defs/count"}},"${'$'}defs":{"count":{"type":"integer","minimum":0}},"required":["n"]}""")
        StudioSchemas.validate(s, """{"n":2}""")
        assertThrows(IllegalStateException::class.java) { StudioSchemas.validate(s, """{"n":-1}""") }
        val invalid = listOf(
            """{"${'$'}defs":{"a%62":{"type":"integer"},"ab":{"type":"string"}},"${'$'}ref":"#/${'$'}defs/a%62"}""",
            """{"format":"date"}""", """{"unknown":true}""", """{"type":"typo"}""",
            """{"required":"x"}""", """{"properties":{"n":{"minimum":"bad"}}}""",
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#"}""",
            """{"${'$'}ref":"file:///tmp/schema.json"}""",
            """{"${'$'}ref":"https://example.invalid/schema"}""",
            """{"${'$'}ref":"#/${'$'}defs/missing"}""",
            """{"${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/a"}}}"""
        )
        invalid.forEach { text -> assertEquals(409, assertThrows(StudioException::class.java) {
            nodeConfig("program", obj("outputSchema" to schema(text)))
        }.status, text) }
    }
    @Test fun `schema and value size and depth limits fail before validation`() {
        assertThrows(StudioException::class.java) { StudioSchemas.compile(obj("description" to JsonPrimitive("x".repeat(65_536)))) }
        var deep: JsonObject = obj("type" to JsonPrimitive("string"))
        repeat(34) { deep = obj("items" to deep) }
        assertThrows(StudioException::class.java) { StudioSchemas.compile(deep) }
        assertThrows(IllegalStateException::class.java) { StudioSchemas.parseValue("[".repeat(65) + "0" + "]".repeat(65)) }
        assertThrows(IllegalStateException::class.java) { StudioSchemas.parseValue("\"" + "x".repeat(1_048_576) + "\"") }
    }
    @Test fun `artifact hash includes schema with canonical keys and keeps legacy hash`() {
        val a = schema("""{"type":"string","maxLength":5}""")
        val b = schema("""{"maxLength":5,"type":"string"}""")
        assertEquals(sha256("code"), artifactHash("code", null))
        assertEquals(artifactHash("code", a), artifactHash("code", b))
        assertNotEquals(artifactHash("code", null), artifactHash("code", a))
        assertNotEquals(artifactHash("other", a), artifactHash("code", a))
    }
}
