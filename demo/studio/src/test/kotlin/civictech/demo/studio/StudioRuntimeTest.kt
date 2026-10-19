package civictech.demo.studio

import civictech.testkit.awaitUntil
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

@Timeout(60)
class StudioRuntimeTest {
    @TempDir lateinit var temp: Path

    private fun node(r: StudioRuntime, kind: String, config: JsonObject = JsonObject(emptyMap())) = r.addNode(obj("kind" to JsonPrimitive(kind), "config" to config))
    private fun exec(r: StudioRuntime, command: String = "printf %s {{in}}", auto: Boolean = true) = node(r, "exec", buildJsonObject {
        put("command", command); put("auto", auto)
    })
    private fun output(r: StudioRuntime, id: String) = r.state(id).output?.string("new")
    private fun done(r: StudioRuntime, id: String, text: String) = awaitUntil("$id output=$text") { output(r, id) == text && r.state(id).status == "idle" }

    @Test fun `text to exec and two exec chain use live kernel links and dedupe`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text"); val b = exec(r); val c = exec(r, "printf 'result=%s' {{in}}")
            r.addEdge(a.id, b.id, "in"); r.addEdge(b.id, c.id, "in")
            r.submit(a.id, "hello ; rm -rf /")
            done(r, c.id, "result=hello ; rm -rf /")
            r.submit(a.id, "hello ; rm -rf /"); r.settleInputs()
            assertEquals(1, r.state(b.id).runCount); assertEquals(1, r.state(c.id).runCount)
            val run = r.runDetail(b.id, r.runs(b.id).single().runId)
            assertEquals(listOf("printf", "%s", "hello ; rm -rf /"), run.argv)
            assertEquals("hello ; rm -rf /", run.stdout)
            assertTrue(Files.isRegularFile(temp.resolve("nodes/${b.id}/runs/${run.runId}/stderr.txt")))
        }
    }

    @Test fun `in flight changes compose into one follow up preserving first pending old`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text")
            val b = exec(r, "sh -c 'sleep 1; printf %s \"\u00240\"' {{in}}")
            r.addEdge(a.id, b.id, "in")
            r.submit(a.id, "one")
            awaitUntil("first run running") { r.state(b.id).status == "running" }
            r.submit(a.id, "two"); r.settleInputs()
            r.submit(a.id, "three"); r.settleInputs()
            assertEquals("queued", r.state(b.id).status)
            done(r, b.id, "three")
            assertEquals(2, r.state(b.id).runCount)
            val last = r.runDetail(b.id, r.state(b.id).lastRun!!.runId)
            assertEquals("one", last.inputs.getValue("in").string("old"))
            assertEquals("three", last.inputs.getValue("in").string("new"))
        }
    }

    @Test fun `cycle is rejected by kernel admission`() {
        StudioRuntime(temp).use { r ->
            val a = exec(r); val b = exec(r)
            r.patchNode(a.id, obj("title" to JsonPrimitive("git log")))
            r.patchNode(b.id, obj("title" to JsonPrimitive("claude summary")))
            r.addEdge(a.id, b.id, "in")
            val e = assertThrows(StudioException::class.java) { r.addEdge(b.id, a.id, "in") }
            assertEquals(409, e.status); assertTrue(e.message!!.contains("CycleWithoutHead"))
            assertTrue(e.message!!.contains("\"claude summary\".outlet -> \"git log\".inlet"), e.message)
            assertTrue(e.message!!.contains("(spec 10/13, 20/21 §Cycles)"), e.message)
            assertFalse(e.message!!.contains("CellRef("), e.message)
            assertEquals(1, r.graph().edges.size)
        }
    }

    @Test fun `restart restores inputs and catch up does not rerun`() {
        var a = ""; var b = ""
        StudioRuntime(temp).use { r ->
            a = node(r, "text").id; b = exec(r).id
            r.addEdge(a, b, "in"); r.submit(a, "persisted"); done(r, b, "persisted")
        }
        StudioRuntime(temp).use { r ->
            r.settleInputs()
            assertEquals("persisted", output(r, b)); assertEquals(1, r.state(b).runCount)
            assertEquals("persisted", r.state(a).text)
            assertEquals(1, r.runs(b).size)
        }
    }

    @Test fun `patch removes vanished ports and last pair unlink permits reverse edge`() {
        StudioRuntime(temp).use { r ->
            val a = exec(r); val b = exec(r)
            r.addEdge(a.id, b.id, "in")
            val patched = r.patchNode(b.id, obj("config" to obj("command" to JsonPrimitive("printf done"))))
            assertTrue(patched.ports.isEmpty()); assertTrue(r.graph().edges.isEmpty())
            r.addEdge(b.id, a.id, "in") // Refused if the old kernel link was not removed.
        }
    }

    @Test fun `multiple canvas edges share a link and new port gets latest output`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text"); val b = exec(r, "printf '%s:%s' {{left}} {{right}}")
            r.submit(a.id, "A"); r.settleInputs()
            val left = r.addEdge(a.id, b.id, "left"); r.settleInputs()
            assertEquals(0, r.state(b.id).runCount)
            r.addEdge(a.id, b.id, "right"); done(r, b.id, "A:A")
            r.deleteEdge(left.id)
            r.submit(a.id, "B"); done(r, b.id, "A:B")
            assertEquals("B", r.state(b.id).inputs.getValue("right").string("new"))
            assertEquals("A", r.state(b.id).inputs.getValue("left").string("new"))
            assertEquals(2, r.state(b.id).runCount)
        }
    }

    @Test fun `equal catch up after unlink and relink does not rerun held inputs`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text"); val b = exec(r)
            val edge = r.addEdge(a.id, b.id, "in")
            r.submit(a.id, "held"); done(r, b.id, "held")
            r.deleteEdge(edge.id)
            assertEquals("held", r.state(b.id).inputs.getValue("in").string("new"))
            r.addEdge(a.id, b.id, "in"); r.settleInputs()
            assertEquals(1, r.state(b.id).runCount)
            r.submit(a.id, "changed"); done(r, b.id, "changed")
            assertEquals(2, r.state(b.id).runCount)
        }
    }

    @Test fun `auto false stays stale and manual run requires all inputs`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text"); val b = exec(r, auto = false)
            assertEquals(409, assertThrows(StudioException::class.java) { r.run(b.id) }.status)
            r.addEdge(a.id, b.id, "in"); r.submit(a.id, "manual"); r.settleInputs()
            assertEquals("stale", r.state(b.id).status); assertEquals(0, r.state(b.id).runCount)
            r.run(b.id); done(r, b.id, "manual")
        }
    }

    @Test fun `patch merges config and preserves existing options across preset changes`() {
        StudioRuntime(temp).use { r ->
            val b = exec(r)
            val patched = r.patchNode(b.id, obj("config" to buildJsonObject { put("preset", "claude"); put("prompt", "{{change}}") }))
            assertTrue(patched.config.bool("auto"))
            assertEquals(600L, patched.config.long("timeoutSec", 0))
            assertEquals(listOf("change"), patched.ports)
            assertEquals("readonly", patched.config.string("tools"))
            assertEquals(0, r.state(b.id).runCount) // Editing config never invokes an agent.
        }
    }

    @Test fun `failed process records stderr and emits nothing`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text"); val b = exec(r, "sh -c 'printf bad >&2; exit 7' {{in}}")
            r.addEdge(a.id, b.id, "in"); r.submit(a.id, "go")
            awaitUntil("failed process") { r.state(b.id).status == "failed" }
            assertEquals("bad", r.state(b.id).error); assertNull(r.state(b.id).output)
            val detail = r.runDetail(b.id, r.state(b.id).lastRun!!.runId)
            assertEquals(7, detail.exitCode); assertEquals("bad", detail.stderr)
        }
    }

    @Test fun `timeout kills process descendants and records failure`() {
        StudioRuntime(temp).use { r ->
            val b = exec(r, "sh -c 'sleep 30 & echo \u0024!; wait'")
            r.patchNode(b.id, obj("config" to obj("timeoutSec" to JsonPrimitive(1))))
            r.run(b.id)
            awaitUntil("timeout") { r.state(b.id).status == "failed" }
            assertTrue(r.state(b.id).error!!.contains("timed out"))
            val pid = r.runDetail(b.id, r.state(b.id).lastRun!!.runId).stdout.trim().toLong()
            awaitUntil("descendant killed") { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) }
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `program promotion checks code hash and regeneration retains the live implementation`() {
        StudioRuntime(temp).use { r ->
            val a = node(r, "text"); val p = node(r, "program"); val b = exec(r)
            r.addEdge(a.id, p.id, "in"); r.addEdge(p.id, b.id, "in")
            r.installCode(p.id, "```python\nimport json,sys\nprint(json.load(sys.stdin)['in']['new'].upper())\n```")
            r.submit(a.id, "python"); r.settleInputs()
            assertEquals("stale", r.state(p.id).status); assertEquals("code not approved — generate and approve", r.state(p.id).error)
            assertEquals(0, r.state(p.id).runCount); assertNull(r.state(p.id).lastRun)
            assertEquals(409, assertThrows(StudioException::class.java) { r.approve(p.id, "wrong") }.status)
            r.approve(p.id, r.state(p.id).codeHash!!); done(r, b.id, "PYTHON")
            assertEquals(1, r.state(p.id).runCount)
            assertEquals("PYTHON", output(r, p.id))
            assertEquals(listOf("python3", "cell.py"), r.runDetail(p.id, r.state(p.id).lastRun!!.runId).argv.takeLast(2))
            val approved = r.state(p.id).approvedHash
            r.installCode(p.id, "print('new')")
            assertEquals(approved, r.state(p.id).approvedHash)
            r.run(p.id)
            awaitUntil("approved implementation ran again") { r.state(p.id).runCount == 2 && r.state(p.id).status == "idle" }
            assertEquals("PYTHON", output(r, p.id))
        }
    }


    @Test fun `text fire reruns auto exec and persisted fire catch up stays silent`() {
        var source = ""; var target = ""
        var fired: JsonObject? = null
        StudioRuntime(temp).use { r ->
            source = node(r, "text").id; target = exec(r).id
            r.addEdge(source, target, "in")
            r.submit(source, "same"); done(r, target, "same")
            assertEquals(1, r.state(target).runCount)
            r.fire(source)
            awaitUntil("fired text run completed") { r.state(target).runCount == 2 && r.state(target).status == "idle" }
            fired = r.state(source).output!!
            java.time.Instant.parse(fired!!.string("fired"))
            assertEquals(fired, r.state(target).inputs["in"])
            val edge = r.graph().edges.single()
            r.deleteEdge(edge.id); r.addEdge(source, target, "in"); r.settleInputs()
            assertEquals(2, r.state(target).runCount)
        }
        StudioRuntime(temp).use { r ->
            r.settleInputs()
            assertEquals(fired, r.state(source).output)
            assertEquals(2, r.state(target).runCount)
            assertEquals(2, r.runs(target).size)
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `unchanged exec and program results record runs without publishing downstream`() {
        for (kind in listOf("exec", "program")) {
            val data = temp.resolve(kind)
            StudioRuntime(data).use { r ->
                val source = node(r, "text")
                val a = if (kind == "exec") exec(r, "sh -c 'printf %s fixed' {{in}}") else node(r, "program")
                val b = exec(r)
                r.addEdge(source.id, a.id, "in"); r.addEdge(a.id, b.id, "in")
                if (kind == "program") {
                    r.installCode(a.id, "print('fixed')")
                    r.approve(a.id, r.state(a.id).codeHash!!)
                }
                r.submit(source.id, "one"); done(r, b.id, "fixed")
                val held = r.state(a.id).output
                val firstRun = r.state(a.id).lastRun!!.runId
                r.submit(source.id, "two")
                awaitUntil("second $kind run completed") { r.state(a.id).runCount == 2 && r.state(a.id).status == "idle" }
                r.settleInputs()
                assertEquals(held, r.state(a.id).output)
                assertEquals(1, r.state(b.id).runCount)
                assertNotEquals(firstRun, r.state(a.id).lastRun!!.runId)
                assertEquals(2, r.runs(a.id).size)
                val detail = r.runDetail(a.id, r.state(a.id).lastRun!!.runId)
                assertEquals("two", detail.inputs.getValue("in").string("new"))
                assertEquals("fixed", detail.stdout.trimEnd())
                for (file in listOf("run.json", "stdout.txt", "stderr.txt"))
                    assertTrue(Files.isRegularFile(data.resolve("nodes/${a.id}/runs/${detail.runId}/$file")))
                assertNull(r.state(a.id).error)
            }
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `unapproved auto program is stale even before all inputs arrive`() {
        StudioRuntime(temp).use { r ->
            val source = node(r, "text")
            val p = node(r, "program", buildJsonObject { put("inputs", JsonArray(listOf(JsonPrimitive("in"), JsonPrimitive("other")))) })
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            assertEquals("stale", r.state(p.id).status)
            assertEquals("code not approved — generate and approve", r.state(p.id).error)
            r.installCode(p.id, "print('approved')"); r.approve(p.id, r.state(p.id).codeHash!!)
            assertEquals(0, r.state(p.id).runCount)
            r.addEdge(source.id, p.id, "other"); done(r, p.id, "approved")
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `promotion publishes once even for manual programs`() {
        StudioRuntime(temp).use { r ->
            val source = node(r, "text")
            val p = node(r, "program", buildJsonObject { put("auto", false) })
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, "print('manual')"); r.approve(p.id, r.state(p.id).codeHash!!)
            done(r, p.id, "manual")
            assertEquals(1, r.state(p.id).runCount)
            r.run(p.id)
            awaitUntil("explicit manual run") { r.state(p.id).runCount == 2 && r.state(p.id).status == "idle" }
        }
    }

    @Test fun `git fire on unchanged ref reruns auto exec and restart stays silent`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        git(repo, "init", "-b", "main"); git(repo, "config", "user.name", "Studio Test"); git(repo, "config", "user.email", "studio@example.invalid")
        commit(repo, "first")
        val data = temp.resolve("data")
        var source = ""; var target = ""
        var held: JsonObject? = null
        StudioRuntime(data).use { r ->
            source = node(r, "git", buildJsonObject { put("repo", repo.toString()); put("branch", "main"); put("fetch", false); put("intervalSec", 3600) }).id
            target = exec(r).id
            r.addEdge(source, target, "in")
            awaitUntil("initial ref") { r.state(source).ref != null }
            val second = commit(repo, "second")
            r.fire(source); done(r, target, second)
            assertEquals(1, r.state(target).runCount)
            r.fire(source)
            awaitUntil("unchanged ref fired run") { r.state(target).runCount == 2 && r.state(target).status == "idle" }
            held = r.state(source).output!!
            java.time.Instant.parse(held!!.string("fired"))
            assertEquals(second, held!!.string("new"))
        }
        StudioRuntime(data).use { r ->
            r.settleInputs()
            assertEquals(held, r.state(source).output)
            assertEquals(2, r.state(target).runCount)
            assertEquals(2, r.runs(target).size)
        }
    }

    private fun git(repo: Path, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.exitValue(), out)
        return out.trim()
    }
    private fun commit(repo: Path, text: String): String {
        Files.writeString(repo.resolve("file"), text)
        git(repo, "add", "file"); git(repo, "commit", "-qm", text)
        return git(repo, "rev-parse", "HEAD")
    }

    @Test fun `git first observation silent changes emit and fire reemits`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        git(repo, "init", "-b", "main"); git(repo, "config", "user.name", "Studio Test"); git(repo, "config", "user.email", "studio@example.invalid")
        val first = commit(repo, "first")
        StudioRuntime(temp.resolve("data")).use { r ->
            val g = node(r, "git", buildJsonObject { put("repo", repo.toString()); put("branch", "main"); put("fetch", false); put("intervalSec", 1) })
            awaitUntil("first git observation") { r.state(g.id).ref == first }
            assertNull(r.state(g.id).output)
            val second = commit(repo, "second")
            awaitUntil("git change") { output(r, g.id) == second }
            assertEquals(first, r.state(g.id).output!!.string("old"))
            val downstream = exec(r, "printf %s {{change.new}}", auto = false)
            r.addEdge(g.id, downstream.id, "change"); r.settleInputs()
            r.deleteEdge(r.graph().edges.single().id)
            val frames = LinkedBlockingQueue<String>()
            r.listen { frames.offer(it) }.use {
                frames.clear()
                r.fire(g.id)
                awaitUntil("git fire reemits") { frames.any { frame ->
                    val event = json.parseToJsonElement(frame).jsonObject
                    event.string("type") == "state" && event["node"]?.jsonObject?.let { state ->
                        state.string("id") == g.id && state["output"]?.jsonObject?.string("new") == second
                    } == true
                } }
            }
            r.addEdge(g.id, downstream.id, "change"); r.settleInputs()
            assertEquals(second, r.state(downstream.id).inputs.getValue("change").string("new"))
            assertEquals(first, r.state(downstream.id).inputs.getValue("change").string("old"))
        }
    }

    @Test fun `git error recovers on config change detects local main and initial fire emits`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        git(repo, "init", "-b", "main"); git(repo, "config", "user.name", "Studio Test"); git(repo, "config", "user.email", "studio@example.invalid")
        val first = commit(repo, "first")
        StudioRuntime(temp.resolve("data")).use { r ->
            val g = node(r, "git", buildJsonObject { put("repo", temp.resolve("missing").toString()); put("fetch", false) })
            awaitUntil("git poll error") { r.state(g.id).error != null }
            r.patchNode(g.id, obj("config" to obj("repo" to JsonPrimitive(repo.toString()))))
            awaitUntil("config reschedules git poll") { r.state(g.id).ref == first }
            assertNull(r.state(g.id).output); assertNull(r.state(g.id).error)
            r.fire(g.id)
            awaitUntil("initial git fire output") { output(r, g.id) == first }
            assertEquals(JsonNull, r.state(g.id).output!!["old"])
            assertEquals(repo.toString(), r.state(g.id).output!!.string("repo"))
        }
    }
}
