package civictech.demo.studio.typespike

import civictech.demo.studio.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Timeout(30)
class BoundarySpikeTest {
    @TempDir lateinit var temp: Path

    // Synthetic fixtures following the documented contract; no CLI/model call.
    private fun claudeStructured(stdout: String): JsonElement {
        val envelope = json.parseToJsonElement(stdout).jsonObject
        require(!envelope.bool("is_error") && envelope.string("subtype") == "success")
        return requireNotNull(envelope["structured_output"]?.takeUnless { it == JsonNull })
    }
    @Test fun structuredClaudeOutputMustNotUseNarrativeResultOrFallbackOnMissingValue() {
        assertEquals(json.parseToJsonElement("""{"score":3}"""), claudeStructured(
            """{"subtype":"success","result":"Done","structured_output":{"score":3}}"""))
        assertThrows(IllegalArgumentException::class.java) { claudeStructured("""{"subtype":"success","result":"{}"}""") }
        assertThrows(IllegalArgumentException::class.java) { claudeStructured("""{"subtype":"error_max_structured_output_retries","structured_output":{"score":3}}""") }
    }
    @Test fun parsingCodexFinalFileIsNotSchemaValidation() {
        val file = temp.resolve("last.json")
        java.nio.file.Files.writeString(file, """{"score":"wrong type"}""")
        val parsed = json.parseToJsonElement(java.nio.file.Files.readString(file)).jsonObject
        assertTrue(parsed.getValue("score").jsonPrimitive.isString)
        // JSON serialization accepts this; declared integer schema would not.
        for (name in listOf("com.networknt.schema.JsonSchema", "org.everit.json.schema.Schema", "dev.harrel.jsonschema.Validator"))
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name) }
    }
    @Test fun legacyConfigFactoryAndCheckpointRoundTripWithoutAnyTypeField() {
        val config = nodeConfig("exec", obj("command" to JsonPrimitive("echo {{c.repo}}"), "auto" to JsonPrimitive(false)))
        val definition = StudioDefinition("legacy", "exec", config)
        val checkpoint = StudioCheckpoint(definition, NodeState("legacy", inputs = mapOf("c" to change(null, "text"))))
        val bytes = json.encodeToString(checkpoint)
        assertEquals(checkpoint, json.decodeFromString<StudioCheckpoint>(bytes))
        assertFalse("outputType" in bytes)
        assertEquals(setOf("repo"), infer(config.string("command")).getValue("c").fields)
        var source = ""; var target = ""
        StudioRuntime(temp.resolve("legacy")).use { runtime ->
            source = runtime.addNode(obj("kind" to JsonPrimitive("text"))).id
            target = runtime.addNode(obj("kind" to JsonPrimitive("exec"), "config" to config)).id
            runtime.addEdge(source, target, "c") // existing incompatible wire is durable today
            runtime.submit(source, "retained"); runtime.settleInputs()
        }
        StudioRuntime(temp.resolve("legacy")).use { runtime ->
            runtime.settleInputs()
            assertEquals("retained", runtime.state(source).output!!.string("new"))
            assertEquals("retained", runtime.state(target).inputs.getValue("c").string("new"))
            assertEquals(1, runtime.graph().edges.size)
            assertEquals(0, runtime.state(target).runCount)
        }
    }

    @Test fun logicalCanvasPortsShareOnePhysicalLinkSoEachCanvasEditNeedsValidation() {
        StudioRuntime(temp.resolve("multiplex")).use { runtime ->
            val source = runtime.addNode(obj("kind" to JsonPrimitive("text")))
            val target = runtime.addNode(obj("kind" to JsonPrimitive("exec"), "config" to obj(
                "command" to JsonPrimitive("echo {{a.new}} {{b.repo}}"), "auto" to JsonPrimitive(false))))
            runtime.addEdge(source.id, target.id, "a")
            runtime.addEdge(source.id, target.id, "b") // no second kernel handshake
            assertEquals(2, runtime.graph().edges.size)
            assertEquals(1, runtime.context.live().links.size)
            assertEquals(Admission.ACCEPT, satisfies(Output(Kind.text), infer(target.config.string("command")).getValue("a")).admission)
            assertEquals(Admission.REJECT, satisfies(Output(Kind.text), infer(target.config.string("command")).getValue("b")).admission)
        }
    }
}
