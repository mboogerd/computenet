package civictech.demo.studio

import civictech.demo.studio.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StudioTypesTest {
    @Test fun barePlaceholderOnlyRequiresNew() {
        val r = infer("{{c}} {{c.new}}")
        assertEquals(mapOf("c" to InputRequirement(setOf("new"))), r)
        assertEquals(Admission.ACCEPT, satisfies(StudioType("text"), r.getValue("c")).admission)
    }
    @Test fun gitFieldsUnifyInFirstAppearanceOrder() {
        val r = infer("{{c.repo}} {{log}} {{c.old}} {{c.new}}")
        assertEquals(listOf("c", "log"), r.keys.toList())
        assertEquals(setOf("repo", "old", "new"), r.getValue("c").fields)
        assertEquals(Admission.ACCEPT, satisfies(StudioType("gitRange"), r.getValue("c")).admission)
    }
    @Test fun textToGitRequirementHasUiMessage() {
        assertEquals(Compatibility(Admission.REJECT,
            "c: requires field 'repo'; text offers new, old"),
            satisfies(StudioType("text"), infer("{{c.repo}}").getValue("c")))
    }
    @Test fun issueDeltaExtrasAreGuaranteed() {
        val r = infer("{{issues.added}} {{issues.removed}} {{issues.changed}} {{issues.workspace}}")
        assertEquals(Admission.ACCEPT, satisfies(StudioType("issueSet"), r.getValue("issues")).admission)
        assertEquals(Admission.REJECT, satisfies(StudioType("gitRange"), r.getValue("issues")).admission)
    }
    @Test fun annotationAddsSemanticConstraintAndRejectsConflicts() {
        val r = infer("{{c:gitRange}} {{c.repo}} ").getValue("c")
        assertEquals("gitRange", r.kind)
        assertEquals(Admission.REJECT, satisfies(StudioType("issueSet"), r).admission)
        assertThrows(StudioException::class.java) { infer("{{c:text}} {{c:gitRange}}") }
        assertThrows(StudioException::class.java) { infer("{{c:typo}}") }
        assertThrows(StudioException::class.java) { infer("{{broken:") }
        // Discovery uses the very same parser as substitution.
        assertEquals(listOf("c"), inputPorts("exec", nodeConfig("exec", obj("command" to JsonPrimitive("echo {{c:gitRange}}")))))
        assertEquals("ref", substitute("{{c:gitRange}}", mapOf("c" to change(null, "ref", "/repo"))))
    }
    @Test fun looseProducersNeedRuntimeValidationNotUnconditionalApproval() {
        val r = infer("{{c.repo}}").getValue("c")
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(StudioType("any"), r).admission)
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(StudioType("json"), r).admission)
        assertEquals(Admission.ACCEPT, satisfies(StudioType("json", setOf("repo")), r).admission)
    }
    @Test fun judgmentUsesNewForAnswerAndNullableExtrasOnlyProvePresence() {
        assertEquals(Admission.ACCEPT, satisfies(StudioType("judgment"), infer("{{j.new}} {{j.probability}} {{j.reason}}").getValue("j")).admission)
        assertEquals(Admission.REJECT, satisfies(StudioType("judgment"), infer("{{j.answer}}").getValue("j")).admission)
        val value = judgedChange(nodeConfig("judge", obj()), emptyMap(), null, StudioJudgment("true", .9, null))!!
        assertEquals("true", value.string("new")); assertEquals(JsonNull, value["reason"])
    }
    @Test fun selectedGateTypePropagatesThroughTwoGatesAndChangesWithUpstream() {
        val first = gateOutput("diff", mapOf("diff" to StudioType("gitRange"), "log" to StudioType("text")))
        val second = gateOutput("in", mapOf("in" to first))
        assertEquals("gitRange", second.kind); assertTrue("gate" in second.fields)
        val r = infer("{{c.repo}}").getValue("c")
        assertEquals(Admission.ACCEPT, satisfies(second, r).admission)
        assertEquals(Admission.REJECT, satisfies(gateOutput("diff", mapOf("diff" to StudioType("text"))), r).admission)
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(gateOutput("diff", emptyMap()), r).admission)
        val input = change("a", "b", "/repo")
        val config = nodeConfig("judge", obj("question" to JsonPrimitive("{{diff}} {{log}}"), "mode" to JsonPrimitive("gate"), "passPort" to JsonPrimitive("diff")))
        val passed = judgedChange(config, mapOf("diff" to input, "log" to change(null, "log")), null, StudioJudgment("true", .9))!!
        assertEquals(input, JsonObject(passed - "gate"))
    }
    @Test fun fieldAccessIsFlatAndDoesNotParseJsonNew() {
        val input = change(null, """{"repo":"/nested"}""")
        assertThrows(IllegalStateException::class.java) { substitute("{{c.repo}}", mapOf("c" to input)) }
        assertThrows(IllegalStateException::class.java) { substitute("{{c.new.repo}}", mapOf("c" to input)) }
        assertEquals(setOf("new.repo"), infer("{{c.new.repo}}").getValue("c").fields)
        assertEquals("literal", substitute("{{c.author.name}}", mapOf("c" to JsonObject(input + ("author.name" to JsonPrimitive("literal"))))))
        assertEquals(setOf("author-name"), infer("{{c.author-name}}").getValue("c").fields)
        val schema = json.parseToJsonElement("""{"type":"object","properties":{"repo":{"type":"string"}},"required":["repo"]}""").jsonObject
        val typed = StudioType("json", schema = schema)
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(typed, infer("{{c.repo}}").getValue("c")).admission)
        assertEquals(schema, gateOutput("c", mapOf("c" to typed)).schema)
    }
}
