package civictech.demo.studio

import civictech.testkit.awaitUntil
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Timeout(120)
class StudioBeadsIntegrationTest {
    @TempDir lateinit var temp: Path
    private fun node(r: StudioRuntime, kind: String, config: JsonObject = JsonObject(emptyMap())) = r.addNode(obj("kind" to JsonPrimitive(kind), "config" to config))
    private fun source(r: StudioRuntime, ws: StudioBdWorkspace, interval: Int = 3600) = node(r, "beads", buildJsonObject {
        put("workspace", ws.root.toString()); put("intervalSec", interval)
    })
    private fun baseline(r: StudioRuntime, id: String) = awaitUntil("silent Beads baseline") { r.state(id).beads != null }
    private fun poll(r: StudioRuntime, id: String) { r.poll(id); r.settlePoll(id); r.settleInputs() }
    private fun idle(r: StudioRuntime, id: String, runs: Int) = awaitUntil("$runs completed downstream runs") {
        r.state(id).let { it.runCount == runs && it.status == "idle" }
    }
    private fun ids(value: JsonObject, key: String) = value.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test fun `scheduled first poll silent then creation and closing each emit once and fire reemits`() {
        val ws = StudioBdWorkspace(temp.resolve("workspace"))
        StudioRuntime(temp.resolve("runtime")).use { r ->
            val a = source(r, ws, interval = 1)
            val b = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{x}}")))
            r.addEdge(a.id, b.id, "x"); baseline(r, a.id); r.settleInputs()
            assertNull(r.state(a.id).output); assertEquals(0, r.state(b.id).runCount)
            val id = ws.create()
            idle(r, b.id, 1)
            val added = r.state(a.id).output!!
            assertEquals(listOf(id), ids(added, "added")); assertEquals("[]", added.string("old"))
            assertEquals(ws.root.toString(), added.string("workspace"))
            poll(r, a.id); assertEquals(1, r.state(b.id).runCount)
            ws.run("close", id)
            idle(r, b.id, 2)
            val removed = r.state(a.id).output!!
            assertEquals(listOf(id), ids(removed, "removed")); assertEquals("[]", removed.string("new"))
            poll(r, a.id); assertEquals(2, r.state(b.id).runCount)
            r.fire(a.id); r.settlePoll(a.id); idle(r, b.id, 3)
            val fired = r.state(a.id).output!!
            Instant.parse(fired.string("fired"))
            assertEquals(removed, JsonObject(fired - "fired"))
        }
    }

    @Test fun `two observed changes while downstream blocked compose into one followup run`() {
        val ws = StudioBdWorkspace(temp.resolve("workspace"))
        val release = temp.resolve("release"); val started = temp.resolve("started")
        val script = temp.resolve("slow.sh")
        Files.writeString(script, """
            touch "$started"
            while [ ! -f "$release" ]; do sleep 0.02; done
            printf %s "${'$'}1"
        """.trimIndent())
        StudioRuntime(temp.resolve("runtime")).use { r ->
            val a = source(r, ws)
            val b = node(r, "exec", obj("command" to JsonPrimitive("sh $script {{x}}")))
            r.addEdge(a.id, b.id, "x"); baseline(r, a.id)
            r.fire(a.id); r.settlePoll(a.id)
            awaitUntil("downstream blocked") { Files.exists(started) }
            val one = ws.create("one"); poll(r, a.id)
            val two = ws.create("two"); poll(r, a.id)
            assertEquals("queued", r.state(b.id).status)
            Files.writeString(release, "go"); idle(r, b.id, 2)
            val detail = r.runDetail(b.id, r.state(b.id).lastRun!!.runId)
            val combined = detail.inputs.getValue("x")
            assertEquals("[]", combined.string("old")); assertEquals(r.state(a.id).beads!!.set, combined.string("new"))
            assertEquals(listOf(one, two).sorted(), ids(combined, "added"))
            assertEquals(2, r.runs(b.id).size)
        }
    }

    @Test fun `silent baseline and output survive journal recovery and relink dedupes`() {
        val ws = StudioBdWorkspace(temp.resolve("workspace")); val data = temp.resolve("runtime")
        var a = ""; var b = ""
        StudioRuntime(data).use { r ->
            a = source(r, ws).id; b = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{x}}"))).id
            r.addEdge(a, b, "x"); baseline(r, a); assertNull(r.state(a).output)
            r.checkpoint()
        }
        val issue = ws.create("while stopped")
        lateinit var held: JsonObject
        StudioRuntime(data).use { r ->
            idle(r, b, 1); held = r.state(a).output!!
            assertEquals(listOf(issue), ids(held, "added"))
            r.checkpoint()
        }
        StudioRuntime(data).use { r ->
            poll(r, a); assertEquals(held, r.state(a).output); assertEquals(1, r.state(b).runCount)
            val edge = r.graph().edges.single(); r.deleteEdge(edge.id); r.addEdge(a, b, "x"); r.settleInputs()
            assertEquals(1, r.state(b).runCount)
        }
    }

    @Test fun `poll errors keep retrying and recover when workspace appears`() {
        val root = temp.resolve("workspace/studio")
        StudioRuntime(temp.resolve("runtime")).use { r ->
            val a = node(r, "beads", buildJsonObject { put("workspace", root.toString()); put("intervalSec", 1) })
            awaitUntil("invalid workspace poll error") { r.state(a.id).error != null }
            val prepared = StudioBdWorkspace(temp.resolve("prepared"))
            Files.createDirectories(root.parent)
            Files.move(prepared.root, root) // Publish a fully initialized workspace atomically.
            baseline(r, a.id); assertNull(r.state(a.id).error); assertNull(r.state(a.id).output)
            r.fire(a.id); r.settlePoll(a.id)
            assertEquals(JsonNull, r.state(a.id).output!!["old"]); assertEquals("[]", r.state(a.id).output!!.string("new"))
        }
    }

    @Test fun `list filters argv and changed rows use the real CLI and query edits reset the baseline`() {
        val ws = StudioBdWorkspace(temp.resolve("workspace")); val issue = ws.create("before")
        ws.run("label", "add", issue, "studio")
        StudioRuntime(temp.resolve("runtime")).use { r ->
            val a = node(r, "beads", buildJsonObject {
                put("workspace", ws.root.toString()); put("query", "list"); put("status", "open"); put("label", "studio"); put("type", "task"); put("intervalSec", 3600)
            })
            baseline(r, a.id)
            val argv = beadsReadArgv(a.config)
            assertTrue(argv.containsAll(listOf("--readonly", "--json", "--limit=0", "--status=open", "--label=studio", "--type=task")))
            assertEquals(listOf(issue), json.parseToJsonElement(r.state(a.id).beads!!.set).jsonArray.map { it.jsonObject.string("id") })
            ws.run("update", issue, "--title=after"); poll(r, a.id)
            assertEquals(listOf(issue), ids(r.state(a.id).output!!, "changed"))
            assertTrue(ids(r.state(a.id).output!!, "added").isEmpty())
            val held = r.state(a.id).output
            ws.run("update", issue, "--description=irrelevant edit"); poll(r, a.id)
            assertEquals(held, r.state(a.id).output)
            r.patchNode(a.id, obj("config" to obj("status" to JsonPrimitive("closed"))))
            r.settlePoll(a.id)
            awaitUntil("query edit silently resets baseline") { r.state(a.id).beads?.set == "[]" && r.state(a.id).output == null }
        }
    }

    @Test fun `both write presets are manual run argv only and create stays unparented`() {
        val ws = StudioBdWorkspace(temp.resolve("workspace")); val canary = temp.resolve("canary")
        val attack = "title ; touch $canary"
        StudioRuntime(temp.resolve("runtime")).use { r ->
            val text = node(r, "text")
            val create = node(r, "exec", buildJsonObject {
                put("preset", "bd-create"); put("workspace", ws.root.toString()); put("title", "{{x}}")
                put("description", "description {{x.old}}"); put("labels", "studio,scratch"); put("parent", "must-not-be-used")
            })
            r.addEdge(text.id, create.id, "x"); r.submit(text.id, attack); r.settleInputs()
            assertFalse(create.config.bool("auto")); assertEquals(0, r.state(create.id).runCount)
            assertEquals("stale", r.state(create.id).status)
            r.run(create.id); idle(r, create.id, 1)
            val detail = r.runDetail(create.id, r.state(create.id).lastRun!!.runId)
            assertEquals(listOf("bd", "--directory", ws.root.toRealPath().toString(), "--sandbox", "create", "--json",
                "--title=$attack", "--description=description ", "--type=task", "--priority=2", "--labels=studio,scratch"), detail.argv)
            assertEquals(ws.root.toRealPath().toString(), detail.cwd); assertFalse(Files.exists(canary))
            val created = json.parseToJsonElement(detail.stdout).jsonObject
            assertEquals(attack, created.string("title")); val id = created.string("id")
            assertFalse(id.contains('.')); assertFalse(detail.argv.any { it.startsWith("--parent") })
            val comment = node(r, "exec", buildJsonObject {
                put("preset", "bd-comment"); put("workspace", ws.root.toString()); put("issue", id); put("text", "{{x}}")
            })
            r.addEdge(text.id, comment.id, "x"); r.settleInputs()
            assertFalse(comment.config.bool("auto")); assertEquals(0, r.state(comment.id).runCount)
            val body = "--file=not-a-file ; touch $canary"
            r.submit(text.id, body); r.settleInputs()
            assertEquals(0, r.state(comment.id).runCount)
            r.run(comment.id); idle(r, comment.id, 1)
            val commented = r.runDetail(comment.id, r.state(comment.id).lastRun!!.runId)
            assertEquals(listOf("bd", "--directory", ws.root.toRealPath().toString(), "--sandbox", "comment", "--", id, body), commented.argv)
            assertTrue(ws.run("comments", id, "--json").contains(body)); assertFalse(Files.exists(canary))
            // Switching an automatic custom command to a writer also defaults to manual.
            val custom = node(r, "exec")
            val switched = r.patchNode(custom.id, obj("config" to buildJsonObject { put("preset", "bd-create"); put("workspace", ws.root.toString()) }))
            assertFalse(switched.config.bool("auto"))
        }
    }

    @Test fun `completed sink ledger and manual setting recover without another write`() {
        val ws = StudioBdWorkspace(temp.resolve("workspace")); val data = temp.resolve("runtime")
        var sink = ""
        lateinit var state: NodeState
        StudioRuntime(data).use { r ->
            sink = node(r, "exec", buildJsonObject {
                put("preset", "bd-create"); put("workspace", ws.root.toString()); put("title", "created once")
            }).id
            // A literal title leaves this manual writer with no input ports.
            r.run(sink); idle(r, sink, 1)
            state = r.state(sink)
            val checkpoint = json.decodeFromString<StudioCheckpoint>(r.context.host.snapshotOf(r.context.handles.getValue(sink))
                .get(5, java.util.concurrent.TimeUnit.SECONDS) as String)
            assertEquals("completed", checkpoint.job!!.phase)
            assertEquals(state.lastRun, checkpoint.job.result!!.summary())
        }
        StudioRuntime(data).use { r ->
            r.settleInputs(); assertEquals(state, r.state(sink)); assertEquals(1, r.runs(sink).size)
            assertFalse(r.graph().nodes.single().config.bool("auto")); assertTrue(r.activeProcesses.isEmpty())
            assertEquals(1, json.parseToJsonElement(ws.run("list", "--json", "--limit=0")).jsonArray.size)
        }
    }
}
