package civictech.demo.studio

import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.testkit.awaitUntil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Timeout(60)
class StudioValidationTest {
    @TempDir lateinit var temp: Path
    private val schema = json.parseToJsonElement("""{"type":"object","properties":{"severity":{"type":"integer"}},"required":["severity"],"additionalProperties":false}""").jsonObject
    private fun node(r: StudioRuntime, kind: String, config: JsonObject = obj()) =
        r.addNode(obj("kind" to JsonPrimitive(kind), "config" to config))
    private fun command(r: StudioRuntime, text: String) = node(r, "exec", obj("command" to JsonPrimitive(text), "auto" to JsonPrimitive(false)))
    private fun patch(r: StudioRuntime, id: String, config: JsonObject) = r.patchNode(id, obj("config" to config))
    private fun checkpoint(r: StudioRuntime, id: String): StudioCheckpoint = json.decodeFromString(
        r.context.host.snapshotOf(r.context.live().spawns.values.single { !it.shadow && (it.handle == id || it.handle.startsWith("$id~candidate~")) }.ref).get(5, TimeUnit.SECONDS) as String)
    private fun done(r: StudioRuntime, id: String, runs: Int = 1) = awaitUntil("completed run $runs") {
        r.state(id).let { it.runCount == runs && it.lastRun != null && it.status !in setOf("running", "queued", "generating") }
    }
    private fun refused(r: StudioRuntime, action: () -> Unit): StudioException {
        r.settleInputs()
        val bytes = Files.readAllBytes(r.data.resolve("studio.journal"))
        val before = r.graph()
        val ex = assertThrows(StudioException::class.java, action)
        assertEquals(409, ex.status)
        assertArrayEquals(bytes, Files.readAllBytes(r.data.resolve("studio.journal")), "refusal must not append any record")
        assertEquals(before, r.graph())
        return ex
    }

    @Test fun `wire template and preset refusals leave exact journal bytes and recover unchanged`() {
        lateinit var expected: StudioGraph
        StudioRuntime(temp).use { r ->
            val text = node(r, "text")
            val target = command(r, "echo {{a}} {{b.repo}}")
            r.addEdge(text.id, target.id, "a")
            assertEquals("b: requires field 'repo'; text offers new, old", refused(r) { r.addEdge(text.id, target.id, "b") }.message)
            refused(r) { patch(r, target.id, obj("command" to JsonPrimitive("echo {{a.repo}} {{b.repo}}"))) }
            refused(r) { patch(r, target.id, obj("preset" to JsonPrimitive("claude"), "prompt" to JsonPrimitive("{{a.repo}}"))) }
            refused(r) { patch(r, target.id, obj("command" to JsonPrimitive("echo {{other}}"))) }
            r.submit(text.id, "retained"); r.settleInputs()
            expected = r.graph()
        }
        StudioRuntime(temp).use { r -> r.settleInputs(); assertEquals(expected, r.graph()) }
    }

    @Test fun `gate passPort and upstream template changes validate entire downstream chain`() {
        lateinit var expected: StudioGraph
        StudioRuntime(temp).use { r ->
            val upstream = node(r, "judge", obj("question" to JsonPrimitive("{{x}}"), "auto" to JsonPrimitive(false)))
            val text = node(r, "text")
            r.addEdge(text.id, upstream.id, "x")
            val first = node(r, "judge", obj("mode" to JsonPrimitive("gate"), "question" to JsonPrimitive("{{j}} {{t}}"), "passPort" to JsonPrimitive("j")))
            val second = node(r, "judge", obj("mode" to JsonPrimitive("gate")))
            val sink = command(r, "echo {{in.probability}}")
            r.addEdge(upstream.id, first.id, "j"); r.addEdge(text.id, first.id, "t")
            r.addEdge(first.id, second.id, "in"); r.addEdge(second.id, sink.id, "in")
            assertEquals("judgment", r.graph().nodes.single { it.id == second.id }.types.output.kind)
            refused(r) { patch(r, first.id, obj("passPort" to JsonPrimitive("t"))) }
            // Turning an upstream judgment into a text-carrying gate also breaks the final sink.
            refused(r) { patch(r, upstream.id, obj("mode" to JsonPrimitive("gate"))) }
            expected = r.graph()
        }
        StudioRuntime(temp).use { assertEquals(expected, it.graph()) }
    }

    @Test fun `schema removal and unsupported keywords are refused before storage`() {
        lateinit var expected: StudioGraph
        StudioRuntime(temp).use { r ->
            val agent = node(r, "exec", obj("preset" to JsonPrimitive("claude"), "outputSchema" to schema))
            val sink = command(r, "echo {{x:json}}")
            r.addEdge(agent.id, sink.id, "x")
            refused(r) { patch(r, agent.id, obj("outputSchema" to JsonNull)) }
            refused(r) { patch(r, agent.id, obj("outputSchema" to obj("pattern" to JsonPrimitive(".*")))) }
            refused(r) { patch(r, agent.id, obj("outputSchema" to obj("\$ref" to JsonPrimitive("https://example.invalid/schema")))) }
            expected = r.graph()
        }
        StudioRuntime(temp).use { assertEquals(expected, it.graph()) }
    }

    @Test fun `promotion validates candidate schema before any promotion or ledger write`() {
        StudioRuntime(temp).use { r ->
            val program = node(r, "program", obj("outputSchema" to schema))
            r.installCode(program.id, "print('{\"severity\":1}')") // no inputs held: no preview process
            val sink = command(r, "echo {{x:text}}")
            r.addEdge(program.id, sink.id, "x") // current approved protocol is still legacy text
            refused(r) { r.approve(program.id, r.state(program.id).codeHash!!) }
            assertNull(r.state(program.id).approvedHash)
            assertTrue(r.context.live().spawns.values.any { it.shadow })
        }
    }

    /** Fixture replaces only the child CLI; extraction, validation, ledger and fan-out are real. */
    private class FixtureProcesses(private val preset: String, private val response: () -> String) : StudioProcesses() {
        override fun run(argv: List<String>, cwd: Path, dir: Path, timeoutSec: Long, stdin: String?, environment: Map<String, String>, fence: ProcessFence): ProcessResult {
            if (argv.first() != preset) return super.run(argv, cwd, dir, timeoutSec, stdin, environment, fence)
            Files.createDirectories(dir)
            return if (preset == "claude") {
                assertTrue("--json-schema" in argv)
                ProcessResult(0, response(), "", null)
            } else {
                assertTrue("--output-schema" in argv)
                val schemaFile = Path.of(argv[argv.indexOf("--output-schema") + 1])
                assertTrue(Files.readString(schemaFile).contains("severity"))
                Files.writeString(Path.of(argv[argv.indexOf("-o") + 1]), response())
                ProcessResult(0, "", "", null)
            }
        }
    }
    @Test fun `claude synthetic structured fixtures fail closed and codex bad file retains prior output without emission`() {
        for (preset in listOf("claude", "codex")) {
            var raw = if (preset == "claude") """{"subtype":"success","is_error":false,"result":"narrative","structured_output":{"severity":2}}""" else """{"severity":2}"""
            StudioRuntime(temp.resolve(preset), PythonSandbox(), processes = FixtureProcesses(preset) { raw }).use { r ->
                val source = node(r, "text")
                val agent = node(r, "exec", obj("preset" to JsonPrimitive(preset), "prompt" to JsonPrimitive("{{x}}"), "outputSchema" to schema, "auto" to JsonPrimitive(true)))
                val sink = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{x.new}}")))
                r.addEdge(source.id, agent.id, "x"); r.addEdge(agent.id, sink.id, "x")
                r.submit(source.id, "valid"); done(r, sink.id)
                assertEquals("""{"severity":2}""", r.state(sink.id).output!!.string("new"))
                val prior = r.state(agent.id).output
                val invalid = if (preset == "claude") listOf(
                    """{"subtype":"success","is_error":false,"result":"{\"severity\":2}"}""",
                    """{"subtype":"error_max_structured_output_retries","is_error":false,"structured_output":{"severity":2}}""",
                    """{"subtype":"success","is_error":true,"structured_output":{"severity":2}}""",
                    """{"subtype":"success","is_error":false,"structured_output":{"severity":"bad"}}""",
                    """{"subtype":"success","is_error":false,"structured_output":null}"""
                ) else listOf("""{"severity":"bad"}""", "not JSON")
                invalid.forEachIndexed { index, fixture ->
                    raw = fixture; r.submit(source.id, "invalid-$index"); done(r, agent.id, index + 2); r.settleInputs()
                    assertEquals("failed", r.state(agent.id).status)
                    assertNotNull(r.state(agent.id).error)
                    assertEquals(prior, r.state(agent.id).output); assertEquals(1, r.state(sink.id).runCount)
                    val ledger = checkpoint(r, agent.id).job!!
                    assertEquals("completed", ledger.phase); assertNotNull(ledger.result!!.error)
                    assertEquals("failed", ledger.result.outcome)
                }
            }
        }
    }

    @Test fun `loose json checked at arrival missing field is visible and no process launches`() {
        StudioRuntime(temp, PythonSandbox(), processes = FixtureProcesses("claude") {
            """{"subtype":"success","is_error":false,"structured_output":{"severity":1}}"""
        }).use { r ->
            val agent = node(r, "exec", obj("preset" to JsonPrimitive("claude"), "outputSchema" to schema))
            val sink = command(r, "echo {{x.repo}}")
            r.addEdge(agent.id, sink.id, "x") // json schema properties live inside new
            r.run(agent.id); done(r, agent.id); r.settleInputs()
            assertEquals("invalid input", r.state(sink.id).status)
            assertEquals("invalid input: x: missing field 'repo'", r.state(sink.id).error)
            assertEquals(0, r.state(sink.id).runCount); assertFalse("x" in r.state(sink.id).inputs)
            assertEquals(409, assertThrows(StudioException::class.java) { r.run(sink.id) }.status)
        }
    }

    @Test fun `legacy incompatible wire recovers quarantined ignores held value and other nodes still work`() {
        var target = ""; var source = ""; var healthy = ""
        StudioRuntime(temp).use { r ->
            source = node(r, "text").id; target = command(r, "echo {{x}}").id
            healthy = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{x}}"))).id
            r.addEdge(source, target, "x"); r.addEdge(source, healthy, "x")
            r.submit(source, "held"); done(r, healthy); r.settleInputs()
            // Produce the same additive legacy checkpoint a previous Studio version could save.
            val saved = checkpoint(r, target)
            val legacy = saved.copy(definition = saved.definition.copy(config = nodeConfig("exec", obj(
                "command" to JsonPrimitive("echo {{x.repo}}"), "auto" to JsonPrimitive(false)))))
            r.context.host.lookup(TypedRef<StudioCellApi>(r.context.handles.getValue(target)))!!.commit.call
                .propagate(json.encodeToString(StudioCommit(legacy)))
            r.context.host.quiescence().await(5_000)
        }
        StudioRuntime(temp).use { r ->
            r.settleInputs()
            val edge = r.graph().edges.single { it.to == target }
            assertEquals("quarantined", edge.status); assertTrue(edge.reason!!.contains("requires field 'repo'"))
            assertEquals(2, r.graph().edges.size); assertEquals("invalid input", r.state(target).status)
            assertEquals(409, assertThrows(StudioException::class.java) { r.run(target) }.status)
            r.submit(source, "new"); done(r, healthy, 2); r.settleInputs()
            assertEquals("held", r.state(target).inputs.getValue("x").string("new")); assertEquals(0, r.state(target).runCount)
            patch(r, target, obj("command" to JsonPrimitive("echo {{x}}")))
            assertEquals("ok", r.graph().edges.single { it.to == target }.status)
            assertNotEquals("invalid input", r.state(target).status)
            assertNull(r.state(target).error)
            r.run(target); done(r, target)
            assertEquals("held", r.state(target).output!!.string("new"))
            r.deleteEdge(edge.id)
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `program schema is part of preview approval promotion and restart and bad output retains prior`() {
        var id = ""; var approved = ""
        StudioRuntime(temp).use { r ->
            val source = node(r, "text")
            val p = node(r, "program", obj("outputSchema" to schema)); id = p.id
            val sink = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{x.new}}")))
            r.addEdge(source.id, p.id, "in"); r.addEdge(p.id, sink.id, "x")
            r.submit(source.id, "valid"); r.settleInputs()
            r.installCode(p.id, "import json,sys\nv=json.load(sys.stdin)['in']['new']\nprint(json.dumps({'severity': 2 if v == 'valid' else 'bad'}))")
            awaitUntil("valid preview") { r.state(p.id).preview?.status == "idle" }
            r.approve(p.id, r.state(p.id).codeHash!!); done(r, sink.id)
            approved = r.state(p.id).approvedHash!!
            val prior = r.state(p.id).output
            r.submit(source.id, "bad"); done(r, p.id, 2); r.settleInputs()
            assertEquals("failed", r.state(p.id).status); assertTrue(r.state(p.id).error!!.contains("validation failed"))
            assertEquals(prior, r.state(p.id).output); assertEquals(1, r.state(sink.id).runCount)
            assertNotNull(checkpoint(r, p.id).job!!.result!!.error)
            val revised = JsonObject(schema + ("description" to JsonPrimitive("reviewed schema revision")))
            patch(r, p.id, obj("outputSchema" to revised))
            assertNull(r.state(p.id).approvedHash); assertNotEquals(approved, r.state(p.id).codeHash)
            assertEquals(artifactHash(r.state(p.id).code!!, revised), r.state(p.id).codeHash)
            r.graph(); awaitUntil("schema-only preview failed") { r.state(p.id).preview?.status == "failed" }
            assertEquals(prior, r.state(p.id).output)
            r.approve(p.id, r.state(p.id).codeHash!!); done(r, p.id, 3)
            approved = r.state(p.id).approvedHash!!
            assertEquals(revised, checkpoint(r, p.id).liveSchema)
        }
        StudioRuntime(temp).use { r ->
            r.settleInputs(); assertEquals(approved, r.state(id).approvedHash)
            assertEquals("json", r.graph().nodes.single { it.id == id }.types.output.kind)
            assertTrue(r.activeProcesses.isEmpty())
        }
    }
}
