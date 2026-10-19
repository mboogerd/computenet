package civictech.demo.studio.typespike

import civictech.demo.studio.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InferenceSpikeTest {
    @Test fun barePlaceholderOnlyRequiresNew() {
        val r = infer("{{c}} {{c.new}}")
        assertEquals(mapOf("c" to Requirement(setOf("new"))), r)
        assertEquals(Admission.ACCEPT, satisfies(Output(Kind.text), r.getValue("c")).admission)
    }
    @Test fun gitFieldsUnifyInFirstAppearanceOrder() {
        val r = infer("{{c.repo}} {{log}} {{c.old}} {{c.new}}")
        assertEquals(listOf("c", "log"), r.keys.toList())
        assertEquals(setOf("repo", "old", "new"), r.getValue("c").fields)
        assertEquals(Admission.ACCEPT, satisfies(Output(Kind.gitRange), r.getValue("c")).admission)
    }
    @Test fun textToGitRequirementHasUiMessage() {
        assertEquals(Compatibility(Admission.REJECT,
            "c: requires field 'repo'; text offers new, old"),
            satisfies(Output(Kind.text), infer("{{c.repo}}").getValue("c")))
    }
    @Test fun issueDeltaExtrasAreGuaranteed() {
        val r = infer("{{issues.added}} {{issues.removed}} {{issues.changed}} {{issues.workspace}}")
        assertEquals(Admission.ACCEPT, satisfies(Output(Kind.issueSet), r.getValue("issues")).admission)
        assertEquals(Admission.REJECT, satisfies(Output(Kind.gitRange), r.getValue("issues")).admission)
    }
    @Test fun annotationAddsSemanticConstraintAndRejectsConflicts() {
        val r = infer("{{c:gitRange}} {{c.repo}} ").getValue("c")
        assertEquals(Kind.gitRange, r.kind)
        assertEquals(Admission.REJECT, satisfies(Output(Kind.issueSet), r).admission)
        assertThrows(IllegalArgumentException::class.java) { infer("{{c:text}} {{c:gitRange}}") }
        assertThrows(IllegalArgumentException::class.java) { infer("{{c:typo}}") }
        assertThrows(IllegalArgumentException::class.java) { infer("{{broken:") }
        // Current production syntax does not recognize the proposed annotation.
        assertEquals(emptyList<String>(), inputPorts("exec", nodeConfig("exec", obj("command" to JsonPrimitive("echo {{c:gitRange}}")))))
    }
    @Test fun looseProducersNeedRuntimeValidationNotUnconditionalApproval() {
        val r = infer("{{c.repo}}").getValue("c")
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(Output(Kind.any), r).admission)
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(Output(Kind.json), r).admission)
        assertEquals(Admission.ACCEPT, satisfies(Output(Kind.json, setOf("repo")), r).admission)
    }
    @Test fun judgmentUsesNewForAnswerAndNullableExtrasOnlyProvePresence() {
        assertEquals(Admission.ACCEPT, satisfies(Output(Kind.judgment), infer("{{j.new}} {{j.probability}} {{j.reason}}").getValue("j")).admission)
        assertEquals(Admission.REJECT, satisfies(Output(Kind.judgment), infer("{{j.answer}}").getValue("j")).admission)
        val value = judgedChange(nodeConfig("judge", obj()), emptyMap(), null, StudioJudgment("true", .9, null))!!
        assertEquals("true", value.string("new")); assertEquals(JsonNull, value["reason"])
    }
    @Test fun selectedGateTypePropagatesThroughTwoGatesAndChangesWithUpstream() {
        val first = gateOutput("diff", mapOf("diff" to Output(Kind.gitRange), "log" to Output(Kind.text)))
        val second = gateOutput("in", mapOf("in" to first))
        assertEquals(Kind.gitRange, second.kind); assertTrue("gate" in second.fields)
        val r = infer("{{c.repo}}").getValue("c")
        assertEquals(Admission.ACCEPT, satisfies(second, r).admission)
        assertEquals(Admission.REJECT, satisfies(gateOutput("diff", mapOf("diff" to Output(Kind.text))), r).admission)
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(gateOutput("diff", emptyMap()), r).admission)
        val input = change("a", "b", "/repo")
        val config = nodeConfig("judge", obj("question" to JsonPrimitive("{{diff}} {{log}}"), "mode" to JsonPrimitive("gate"), "passPort" to JsonPrimitive("diff")))
        val passed = judgedChange(config, mapOf("diff" to input, "log" to change(null, "log")), null, StudioJudgment("true", .9))!!
        assertEquals(input, JsonObject(passed - "gate"))
    }
    @Test fun fieldAccessIsFlatAndDoesNotParseJsonNew() {
        val input = change(null, """{"repo":"/nested"}""")
        assertEquals("", substitute("{{c.repo}}", mapOf("c" to input)))
        assertEquals("literal", substitute("{{c.author.name}}", mapOf("c" to JsonObject(input + ("author.name" to JsonPrimitive("literal"))))))
        assertEquals(setOf("author-name"), infer("{{c.author-name}}").getValue("c").fields)
        val schema = json.parseToJsonElement("""{"type":"object","properties":{"repo":{"type":"string"}},"required":["repo"]}""").jsonObject
        val typed = Output(Kind.json, newSchema = schema)
        assertEquals(Admission.CHECK_ON_ARRIVAL, satisfies(typed, infer("{{c.repo}}").getValue("c")).admission)
        assertEquals(schema, gateOutput("c", mapOf("c" to typed)).newSchema)
    }
}
