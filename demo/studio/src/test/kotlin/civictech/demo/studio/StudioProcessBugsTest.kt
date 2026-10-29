package civictech.demo.studio

import civictech.testkit.awaitUntil
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Timeout(60)
class StudioProcessBugsTest {
    @TempDir lateinit var temp: Path
    private fun node(r: StudioRuntime, kind: String = "exec", config: JsonObject) =
        r.addNode(obj("kind" to JsonPrimitive(kind), "config" to config))
    private fun done(r: StudioRuntime, id: String) = awaitUntil("run completed") { r.state(id).lastRun != null }
    private fun detail(r: StudioRuntime, id: String) = r.runDetail(id, r.state(id).lastRun!!.runId)

    @Test fun `timeout never resumes the parent's post sleep command or publishes stdout`() {
        val canary = temp.resolve("late-effect")
        StudioRuntime(temp.resolve("data")).use { r ->
            val n = node(r, config = buildJsonObject {
                put("command", """sh -c 'sleep 100; touch "$canary"; printf go2'""")
                put("timeoutSec", 1)
            })
            r.run(n.id); done(r, n.id)
            assertFalse(Files.exists(canary), "killing sleep must never resume the shell")
            assertEquals("failed", r.state(n.id).status)
            assertNotEquals(0, detail(r, n.id).exitCode)
            assertNull(r.state(n.id).output)
            assertEquals("", detail(r, n.id).stdout)
            assertFalse(Files.exists(canary))
        }
    }

    @Test fun `invalid UTF8 preserves stdout stderr exit and records decoding warning`() {
        val script = temp.resolve("bytes.py")
        Files.writeString(script, "import os\nos.write(1, bytes([255,254,0])+b'bad')\nos.write(2, bytes([255])+b'err')")
        StudioRuntime(temp.resolve("data")).use { r ->
            val n = node(r, config = obj("command" to JsonPrimitive("python3 $script")))
            r.run(n.id); done(r, n.id)
            val d = detail(r, n.id)
            assertEquals(0, d.exitCode)
            assertEquals("\uFFFD\uFFFD\u0000bad", d.stdout)
            assertEquals("\uFFFDerr", d.stderr)
            assertArrayEquals(byteArrayOf(-1, -2, 0, 98, 97, 100),
                Files.readAllBytes(temp.resolve("data/nodes/${n.id}/runs/${d.runId}/stdout.txt")))
            assertNull(d.error)
            val artifact = Files.readString(temp.resolve("data/nodes/${n.id}/runs/${d.runId}/run.json"))
            assertTrue(artifact.contains("not valid UTF-8"), artifact)
            assertEquals(d.stdout, r.state(n.id).output!!.string("new"))
        }
    }

    /** Replace only the paid CLI executable; execute a real Python stdin/argv probe. */
    private class PromptProbe(private val script: Path) : StudioProcesses() {
        override fun run(argv: List<String>, cwd: Path, dir: Path, timeoutSec: Long, stdin: String?, environment: Map<String, String>, fence: ProcessFence): ProcessResult {
            return super.run(listOf("python3", script.toString()) + argv, cwd, dir, timeoutSec, stdin, environment, fence)
        }
    }
    private fun promptProbe(): Path = temp.resolve("prompt.py").also { Files.writeString(it, """
        import sys,json,pathlib
        a=sys.argv[1:]
        prompt=sys.stdin.read()
        assert len(prompt.encode()) > 200000, 'prompt missing on stdin'
        assert all(len(x) < 10000 for x in a), 'prompt in argv'
        if a[0]=='claude':
            assert a[-1]=='--'
            print(json.dumps({'subtype':'success','is_error':False,'result':'print(42)'}))
        else:
            assert a[-2:]==['--','-']
            pathlib.Path(a[a.index('-o')+1]).write_text('print(42)')
    """.trimIndent()) }

    @Test fun `large live and generation prompts use stdin for both CLIs`() {
        val script = promptProbe()
        for (preset in listOf("claude", "codex")) for (kind in listOf("exec", "program")) {
            StudioRuntime(temp.resolve("$preset-$kind"), PythonSandbox(), processes = PromptProbe(script)).use { r ->
                val n = node(r, kind, buildJsonObject {
                    put(if (kind == "exec") "preset" else "generator", preset)
                    put("prompt", "é".repeat(150000)); put("auto", false)
                    if (kind == "program") put("inputs", JsonArray(listOf(JsonPrimitive("in"))))
                })
                if (kind == "exec") r.run(n.id) else r.generate(n.id)
                done(r, n.id)
                assertNull(detail(r, n.id).error, detail(r, n.id).error)
                assertEquals(0, detail(r, n.id).exitCode)
                if (kind == "exec") assertEquals("print(42)", r.state(n.id).output!!.string("new"))
                else assertEquals("print(42)", r.state(n.id).code)
            }
        }
    }

    @Test fun `oversize prompt fails clearly before launch including generation instructions`() {
        for (kind in listOf("exec", "program")) {
            StudioRuntime(temp.resolve(kind), PythonSandbox(), processes = PromptProbe(promptProbe())).use { r ->
                val n = node(r, kind, buildJsonObject {
                    put(if (kind == "exec") "preset" else "generator", "claude")
                    put("prompt", "é".repeat(100)); put("maxPromptBytes", 100); put("auto", false)
                })
                if (kind == "exec") r.run(n.id) else r.generate(n.id)
                done(r, n.id)
                assertTrue(detail(r, n.id).error.orEmpty().contains("maxPromptBytes=100"), detail(r, n.id).error)
                assertEquals(-1, detail(r, n.id).exitCode)
                assertTrue(r.activeProcesses.isEmpty())
            }
        }
    }

    @Test fun `crash image lists interrupted run kills recorded orphan tree and ignores late writes`() {
        val canary = temp.resolve("orphan-effect")
        val script = temp.resolve("orphan.sh")
        Files.writeString(script, "sleep 100; touch '$canary'; printf late")
        StudioRuntime(temp.resolve("original")).use { r ->
            val n = node(r, config = obj("command" to JsonPrimitive("sh $script")))
            r.run(n.id)
            awaitUntil("parent and child launched") { r.activeProcesses.any { pid ->
                ProcessHandle.of(pid).map { it.descendants().use { s -> s.count() > 0 } }.orElse(false)
            } }
            awaitUntil("running artifact with recorded descendants") {
                r.runs(n.id).firstOrNull()?.let { run ->
                    val f = r.data.resolve("nodes/${n.id}/runs/${run.runId}/run.json")
                    json.parseToJsonElement(Files.readString(f)).jsonObject["processes"]?.jsonArray?.size?.let { it >= 2 } == true
                } == true
            }
            val run = r.runs(n.id).single()
            val before = r.runDetail(n.id, run.runId)
            val raw = json.parseToJsonElement(Files.readString(r.data.resolve("nodes/${n.id}/runs/${run.runId}/run.json"))).jsonObject
            assertEquals("running", raw.string("status")); assertTrue(raw["pid"]!!.jsonPrimitive.long > 0)
            assertEquals(listOf("sh", script.toString()), before.argv)
            val image = temp.resolve("crash")
            r.context.host.quiescence().await()
            Files.walk(r.data).use { paths -> paths.forEach { src ->
                val dst = image.resolve(r.data.relativize(src))
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            } }
            StudioRuntime(image).use { restored ->
                assertEquals("stale", restored.state(n.id).status)
                assertEquals("previous run interrupted", restored.state(n.id).error)
                val interrupted = restored.runDetail(n.id, run.runId)
                assertEquals("interrupted", interrupted.outcome)
                assertNotEquals(0, interrupted.exitCode)
                assertEquals(run.runId, restored.runs(n.id).single().runId)
                raw["processes"]!!.jsonArray.forEach { record ->
                    val pid = record.jsonObject["pid"]!!.jsonPrimitive.long
                    awaitUntil("orphan killed $pid") { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) }
                }
                Files.writeString(image.resolve("nodes/${n.id}/runs/${run.runId}/stdout.txt"), "late write")
                assertEquals("interrupted", restored.runDetail(n.id, run.runId).outcome)
                assertNull(restored.state(n.id).output)
                assertFalse(Files.exists(canary))
            }
            StudioRuntime(image).use { restored ->
                assertEquals("interrupted", restored.runDetail(n.id, run.runId).outcome)
                assertNull(restored.state(n.id).output)
            }
        }
    }

    @Test fun `started ledger restores missing placeholder and overrides premature success artifact`() {
        var id = ""
        var runId = ""
        lateinit var running: RunDetail
        val images = listOf(temp.resolve("missing-artifact"), temp.resolve("premature-success"))
        StudioRuntime(temp.resolve("original")).use { r ->
            id = node(r, config = obj("command" to JsonPrimitive("sleep 100"))).id
            r.run(id)
            awaitUntil("process recorded") {
                r.runs(id).singleOrNull()?.let { r.runDetail(id, it.runId).pid != null } == true
            }
            runId = r.runs(id).single().runId
            running = r.runDetail(id, runId)
            r.context.host.quiescence().await()
            images.forEach { image ->
                Files.createDirectories(image)
                for (file in listOf("studio.journal", "graph.json")) Files.copy(r.data.resolve(file), image.resolve(file))
            }
        } // no clean-shutdown checkpoint is copied into either crash image
        val falseSuccess = running.copy(status = "completed", exitCode = 0, stdout = "uncommitted success")
        atomicWrite(images[1].resolve("nodes/$id/runs/$runId/run.json"), json.encodeToString(RunDetail.serializer(), falseSuccess))
        images.forEach { image ->
            StudioRuntime(image).use { r ->
                val recovered = r.runDetail(id, runId)
                assertEquals("interrupted", recovered.status)
                assertEquals("interrupted", recovered.outcome)
                assertEquals(-1, recovered.exitCode)
                assertEquals(running.argv, recovered.argv)
                assertEquals(running.pid, recovered.pid)
                assertNull(r.state(id).output)
                assertEquals("stale", r.state(id).status)
                assertEquals(runId, r.runs(id).single().runId)
            }
        }
    }

    @Test fun `orphan cleanup refuses reused pid start time command and arguments mismatches`() {
        val p = ProcessBuilder("sleep", "100").start()
        try {
            val recorded = checkNotNull(recordProcess(p.toHandle()))
            for (mismatch in listOf(recorded.copy(startedAt = "1970-01-01T00:00:00Z"),
                recorded.copy(command = "/not/the/recorded/command"), recorded.copy(arguments = listOf("different")))) {
                destroyRecorded(listOf(mismatch))
                assertTrue(p.isAlive, "identity mismatch must never kill a live reused PID")
            }
            destroyRecorded(listOf(recorded))
            assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        } finally { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
    }

    @Test fun `prompt limit measures substituted UTF8 bytes and can be configured`() {
        val prompt = substitute("{{in.new}}", mapOf("in" to change(null, "é".repeat(100))))
        assertThrows(IllegalStateException::class.java) { checkPrompt(obj("maxPromptBytes" to JsonPrimitive(199)), prompt) }
        checkPrompt(obj("maxPromptBytes" to JsonPrimitive(200)), prompt)
        assertThrows(StudioException::class.java) { nodeConfig("exec", obj("maxPromptBytes" to JsonPrimitive(0))) }
    }

    @Test fun `git poll failure names repository and ref and success returns idle`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        fun git(vararg args: String) {
            val p = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).start()
            assertTrue(p.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, p.exitValue())
        }
        git("init", "-b", "main"); git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-qm", "first")
        StudioRuntime(temp.resolve("data")).use { r ->
            for (path in listOf(temp.resolve("missing"), temp, repo)) {
                val n = node(r, "git", buildJsonObject { put("repo", path.toString()); put("branch", "bad-ref"); put("fetch", false); put("intervalSec", 3600) })
                awaitUntil("git failure") { r.state(n.id).error != null }
                assertEquals("failed", r.state(n.id).status)
                assertTrue(r.state(n.id).error!!.contains(path.toString()))
                assertTrue(r.state(n.id).error!!.contains("bad-ref"))
                r.patchNode(n.id, obj("config" to buildJsonObject { put("repo", repo.toString()); put("branch", "main") }))
                awaitUntil("git recovered") { r.state(n.id).ref != null }
                assertEquals("idle", r.state(n.id).status); assertNull(r.state(n.id).error)
            }
        }
    }
}
