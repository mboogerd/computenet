package civictech.demo.studio

import civictech.testkit.awaitUntil
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Timeout(60)
class StudioWorkspaceTest {
    @TempDir lateinit var temp: Path
    private fun node(r: StudioRuntime, kind: String, config: JsonObject = JsonObject(emptyMap())) =
        r.addNode(obj("kind" to JsonPrimitive(kind), "config" to config))
    private fun pipeline(r: StudioRuntime): Pair<String, String> {
        val source = node(r, "text")
        val target = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{in}}")))
        r.addEdge(source.id, target.id, "in")
        return source.id to target.id
    }
    private fun done(r: StudioRuntime, id: String, output: String) = awaitUntil("output $output") {
        r.state(id).let { it.status == "idle" && it.output?.string("new") == output }
    }

    @Test fun `empty workspace stays empty and names are trimmed validated and unique`() {
        StudioWorkspace(temp).use { workspace ->
            assertTrue(workspace.list().isEmpty())
            for (name in listOf("", "  ", "x".repeat(81)))
                assertEquals(400, assertThrows(StudioException::class.java) { workspace.create(name) }.status)
            val workflow = workspace.create("  First  ")
            assertTrue(Regex("[a-z0-9]{8}").matches(workflow.id))
            assertEquals("First", workflow.name)
            java.time.Instant.parse(workflow.createdAt)
            assertEquals(409, assertThrows(StudioException::class.java) { workspace.create(" first ") }.status)
            assertEquals(404, assertThrows(StudioException::class.java) { workspace.remove("missing") }.status)
            workspace.remove(workflow.id)
        }
        StudioWorkspace(temp).use { assertTrue(it.list().isEmpty()) }
    }

    @Test fun `independent pipelines and registry restore exactly without reruns`() {
        lateinit var workflows: List<Workflow>
        lateinit var graphs: Map<String, StudioGraph>
        StudioWorkspace(temp).use { workspace ->
            val a = workspace.create("One"); val b = workspace.create("Two")
            val first = workspace.withRuntime(a.id) { it }; val second = workspace.withRuntime(b.id) { it }
            val (sourceA, targetA) = pipeline(first); val (sourceB, targetB) = pipeline(second)
            first.submit(sourceA, "only first"); done(first, targetA, "only first")
            second.settleInputs()
            assertEquals(0, second.state(targetB).runCount)
            assertNull(second.state(targetB).output)
            assertEquals(404, assertThrows(StudioException::class.java) { first.addEdge(sourceA, targetB, "in") }.status)
            second.submit(sourceB, "only second"); done(second, targetB, "only second")
            assertEquals(1, first.state(targetA).runCount)
            workflows = workspace.list()
            assertEquals(listOf(2, 2), workflows.map { it.nodeCount })
            assertTrue(workflows.all { it.status == "ok" && it.error == null })
            graphs = workflows.associate { it.id to workspace.withRuntime(it.id) { runtime -> runtime.graph() } }
            val registry = json.parseToJsonElement(Files.readString(temp.resolve("workflows.json"))).jsonArray
            assertEquals(setOf("id", "name", "createdAt"), registry.first().jsonObject.keys)
        }
        StudioWorkspace(temp).use { workspace ->
            assertEquals(workflows, workspace.list())
            workflows.forEach { workflow -> workspace.withRuntime(workflow.id) { r ->
                r.settleInputs()
                assertEquals(graphs[workflow.id], r.graph())
                r.graph().nodes.filter { it.kind == "exec" }.forEach { assertEquals(1, r.runs(it.id).size) }
                assertTrue(r.activeProcesses.isEmpty())
                assertTrue(Files.exists(r.data.resolve("python.sb")))
            } }
        }
    }

    private fun git(repo: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        return output.trim()
    }
    private fun commit(repo: Path, text: String): String {
        Files.writeString(repo.resolve("file"), text)
        git(repo, "add", "file"); git(repo, "commit", "-qm", text)
        return git(repo, "rev-parse", "HEAD")
    }

    @Test fun `removal moves intact data to trash stops processes and polling while survivor works`() {
        val repo = Files.createDirectory(temp.resolve("repo"))
        git(repo, "init", "-b", "main"); git(repo, "config", "user.name", "Studio Test")
        git(repo, "config", "user.email", "studio@example.invalid")
        val initial = commit(repo, "first")
        val data = temp.resolve("data")
        lateinit var survivor: Workflow
        lateinit var expected: StudioGraph
        StudioWorkspace(data).use { workspace ->
            val removed = workspace.create("Remove"); survivor = workspace.create("Keep")
            val first = workspace.withRuntime(removed.id) { it }; val second = workspace.withRuntime(survivor.id) { it }
            val config = buildJsonObject { put("repo", repo.toString()); put("fetch", false); put("branch", "main"); put("intervalSec", 1) }
            val gitA = node(first, "git", config); val gitB = node(second, "git", config)
            awaitUntil("both pollers observed initial commit") { first.state(gitA.id).ref == initial && second.state(gitB.id).ref == initial }
            val (source, target) = pipeline(second)
            val blocked = node(first, "exec", obj("command" to JsonPrimitive("sh -c 'sleep 60 & echo ${'$'}! > child.pid; wait'")))
            first.run(blocked.id)
            val childFile = first.data.resolve("nodes/${blocked.id}/child.pid")
            awaitUntil("child started") { Files.exists(childFile) && Files.readString(childFile).trim().isNotEmpty() }
            val child = Files.readString(childFile).trim().toLong()
            val pids = first.activeProcesses + child
            assertTrue(pids.size >= 2)
            workspace.remove(removed.id)
            awaitUntil("process tree gone") { pids.all { pid -> ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) } }
            assertTrue(first.activeProcesses.isEmpty())
            assertFalse(Files.exists(first.data))
            val trash = Files.list(data.resolve("trash")).use { it.toList().single() }
            assertTrue(trash.fileName.toString().startsWith("${removed.id}-"))
            assertTrue(Files.isRegularFile(trash.resolve("studio.journal")))
            assertTrue(Files.isRegularFile(trash.resolve("nodes/${blocked.id}/child.pid")))
            repeat(2) { index ->
                val next = commit(repo, "after removal $index")
                awaitUntil("surviving background poller") { second.state(gitB.id).ref == next }
                assertEquals(initial, first.state(gitA.id).ref)
                assertFalse(Files.exists(first.data), "closed poller must not recreate the moved directory")
            }
            second.submit(source, "still works"); done(second, target, "still works")
            expected = second.graph()
            assertEquals(listOf(survivor.id), workspace.list().map { it.id })
        }
        StudioWorkspace(data).use { workspace ->
            assertEquals(listOf(survivor.id), workspace.list().map { it.id })
            workspace.withRuntime(survivor.id) { r -> r.settleInputs(); assertEquals(expected, r.graph()) }
        }
    }

    @Test fun `v2 root migration preserves graph artifacts and is idempotent`() {
        lateinit var expected: StudioGraph
        StudioRuntime(temp).use { runtime ->
            val (source, target) = pipeline(runtime)
            runtime.submit(source, "legacy"); done(runtime, target, "legacy")
            expected = runtime.graph()
        }
        lateinit var workflow: Workflow
        StudioWorkspace(temp).use { workspace ->
            workflow = workspace.list().single()
            assertEquals("default", workflow.id); assertEquals("Default", workflow.name)
            workspace.withRuntime("default") { r ->
                r.settleInputs(); assertEquals(expected, r.graph())
                assertEquals(1, r.runs(expected.nodes.single { it.kind == "exec" }.id).size)
            }
            listOf("studio.journal", "graph.json", "nodes", "python.sb").forEach { assertFalse(Files.exists(temp.resolve(it))) }
        }
        val registry = Files.readString(temp.resolve("workflows.json"))
        StudioWorkspace(temp).use { workspace ->
            assertEquals(listOf(workflow), workspace.list())
            assertEquals(registry, Files.readString(temp.resolve("workflows.json")))
            // Opening/listing alone neither migrates again nor launches jobs.
            workspace.withRuntime("default") { r -> r.settleInputs(); assertEquals(expected, r.graph()) }
        }
    }

    @Test fun `listing restored workflows counts live cells without recreating previews or rerunning`() {
        lateinit var workflow: Workflow
        lateinit var programId: String
        StudioWorkspace(temp).use { workspace ->
            workflow = workspace.create("Preview")
            workspace.withRuntime(workflow.id) { r ->
                val source = node(r, "text")
                programId = node(r, "program").id
                r.addEdge(source.id, programId, "in")
                r.submit(source.id, "held"); r.settleInputs()
                // Deliberately fail closed, rather than requiring a platform sandbox here.
                r.installCode(programId, "raise RuntimeError('unpromoted')")
                awaitUntil("preview finished") { r.state(programId).preview?.status == "failed" }
            }
            assertEquals(2, workspace.list().single().nodeCount) // excludes membrane and shadow
        }
        StudioWorkspace(temp).use { workspace ->
            repeat(2) { assertEquals(2, workspace.list().single().nodeCount) }
            workspace.withRuntime(workflow.id) { r ->
                assertNull(r.state(programId).preview)
                assertEquals(0, r.state(programId).runCount)
                assertTrue(r.activeProcesses.isEmpty())
                assertTrue(r.context.live().spawns.values.none { it.shadow })
            }
        }
    }

    @Test fun `partially moved migration resumes before registration`() {
        StudioRuntime(temp).use { node(it, "text") }
        val destination = Files.createDirectories(temp.resolve("workflows/default"))
        Files.move(temp.resolve("studio.journal"), destination.resolve("studio.journal"))
        StudioWorkspace(temp).use { workspace ->
            assertEquals("default", workspace.list().single().id)
            assertEquals(1, workspace.list().single().nodeCount)
            assertFalse(Files.exists(temp.resolve("graph.json")))
        }
        StudioWorkspace(temp).use { assertEquals(1, it.list().size) }
    }

    @Test fun `corrupt journal fails only its workflow and failed workflow can be removed`() {
        lateinit var broken: Workflow
        lateinit var good: Workflow
        StudioWorkspace(temp).use { workspace ->
            broken = workspace.create("Broken"); good = workspace.create("Healthy")
            workspace.withRuntime(good.id) { node(it, "text") }
        }
        Files.write(temp.resolve("workflows/${broken.id}/studio.journal"), ByteBuffer.allocate(8).put(byteArrayOf(0x43, 0x4e, 0x4a, 0x4c)).putInt(Int.MAX_VALUE).array())
        StudioWorkspace(temp).use { workspace ->
            val failed = workspace.list().first()
            assertEquals("failed", failed.status); assertFalse(failed.error.isNullOrBlank())
            assertEquals("ok", workspace.list().last().status)
            assertEquals(409, assertThrows(StudioException::class.java) { workspace.withRuntime(broken.id) {} }.status)
            workspace.withRuntime(good.id) { r -> assertEquals(1, r.graph().nodes.size); node(r, "text") }
            workspace.remove(broken.id)
            assertEquals(2, workspace.list().single().nodeCount)
        }
    }
}
