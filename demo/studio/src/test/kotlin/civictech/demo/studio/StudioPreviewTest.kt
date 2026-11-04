package civictech.demo.studio

import civictech.cell.graph.TopoEvent
import civictech.cell.durability.FileJournal
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
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

/** Real python3 and real sandbox-exec: no fake process or sandbox success. */
@Timeout(60)
class StudioPreviewTest {
    @TempDir lateinit var temp: Path
    private fun node(r: StudioRuntime, kind: String, auto: Boolean = true) = r.addNode(buildJsonObject {
        put("kind", kind); put("config", buildJsonObject { put("auto", auto) })
    })
    private fun code(prefix: String) = "import json,sys\nprint('$prefix' + json.load(sys.stdin)['in']['new'])"
    private fun preview(r: StudioRuntime, id: String, output: String) = awaitUntil("preview $output: ${r.state(id)}") {
        r.state(id).preview.let { it?.status == "idle" && it.output?.string("new") == output }
    }
    private fun live(r: StudioRuntime, id: String, output: String) = awaitUntil("live $output: ${r.state(id)}") {
        r.state(id).let { it.status == "idle" && it.output?.string("new") == output }
    }
    private fun promote(r: StudioRuntime, id: String) = r.approve(id, r.state(id).codeHash!!)
    private fun image(r: StudioRuntime, name: String): Path {
        r.context.host.quiescence().await(5_000)
        val target = Files.createDirectory(temp.resolve(name))
        for (file in listOf("studio.journal", "graph.json")) Files.copy(r.data.resolve(file), target.resolve(file))
        return target
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `sandbox denies outside writes and networking in preview and live`() {
        val canary = temp.resolve("harmless-canary")
        Files.writeString(canary, "unchanged")
        StudioRuntime(temp.resolve("denied")).use { r ->
            val source = node(r, "text"); val p = node(r, "program")
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, """
                import socket
                denied = []
                try:
                    open(${JsonPrimitive(canary.toString())}, 'w').write('changed')
                except PermissionError:
                    denied.append('write-denied')
                try:
                    socket.socket().connect(('127.0.0.1', 9))
                except PermissionError:
                    denied.append('network-denied')
                raise RuntimeError(' '.join(denied))
            """.trimIndent())
            awaitUntil("sandbox preview failure") { r.state(p.id).preview?.status == "failed" }
            assertTrue(r.state(p.id).preview!!.error!!.contains("write-denied network-denied"), r.state(p.id).toString())
            assertNull(r.state(p.id).output)
            promote(r, p.id) // API permits an explicitly requested failed candidate
            awaitUntil("sandbox live failure") { r.state(p.id).status == "failed" }
            assertTrue(r.state(p.id).error!!.contains("write-denied network-denied"), r.state(p.id).toString())
            assertEquals("unchanged", Files.readString(canary))
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `sandbox permits work writes and TMPDIR is the real scratch directory`() {
        StudioRuntime(temp.resolve("allowed")).use { r ->
            val source = node(r, "text"); val p = node(r, "program")
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, """
                import os, pathlib, tempfile
                assert pathlib.Path(os.environ['TMPDIR']).resolve() == pathlib.Path.cwd().resolve()
                pathlib.Path('inside').write_text('safe')
                with tempfile.NamedTemporaryFile() as f:
                    f.write(b'allowed')
                print(pathlib.Path('inside').read_text())
            """.trimIndent())
            preview(r, p.id, "safe")
            promote(r, p.id); live(r, p.id, "safe")
            val detail = r.runDetail(p.id, r.state(p.id).lastRun!!.runId)
            assertEquals("safe", Files.readString(Path.of(detail.cwd).resolve("inside")))
            assertEquals("/usr/bin/sandbox-exec", detail.argv.first())
            assertTrue(detail.argv.contains("WORK=${Path.of(detail.cwd).toRealPath()}"))
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `preview uses held and new live inputs but only promotion reaches downstream once`() {
        StudioRuntime(temp.resolve("fanout")).use { r ->
            val source = node(r, "text"); val p = node(r, "program"); val downstream = node(r, "exec")
            r.patchNode(downstream.id, obj("config" to obj("command" to JsonPrimitive("printf %s {{in}}"))))
            r.addEdge(source.id, p.id, "in"); r.addEdge(p.id, downstream.id, "in")
            r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, code("old:")); preview(r, p.id, "old:held")
            assertNull(r.state(p.id).output); assertEquals(0, r.state(downstream.id).runCount)
            promote(r, p.id); live(r, downstream.id, "old:held")
            val firstHash = r.state(p.id).approvedHash
            val oldRef = r.context.live().spawns.values.single { it.handle.contains("~candidate~") }.ref
            r.installCode(p.id, code("new:")); preview(r, p.id, "new:held")
            assertEquals(firstHash, r.state(p.id).approvedHash)
            assertEquals("old:held", r.state(downstream.id).output?.string("new"))
            r.submit(source.id, "next"); preview(r, p.id, "new:next"); live(r, downstream.id, "old:next")
            val count = r.state(downstream.id).runCount
            assertEquals(409, assertThrows(StudioException::class.java) { r.approve(p.id, "wrong") }.status)
            promote(r, p.id); live(r, downstream.id, "new:next"); r.settleInputs()
            assertEquals(count + 1, r.state(downstream.id).runCount)
            assertFalse(r.context.live().spawns.containsKey(oldRef))
            assertNull(r.state(p.id).preview)
            r.submit(source.id, "after"); live(r, downstream.id, "new:after")
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `manual live setting does not disable shadow computation`() {
        StudioRuntime(temp.resolve("manual")).use { r ->
            val source = node(r, "text"); val p = node(r, "program", auto = false)
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "one"); r.settleInputs()
            r.installCode(p.id, code("preview:")); preview(r, p.id, "preview:one")
            r.submit(source.id, "two"); preview(r, p.id, "preview:two")
            assertNull(r.state(p.id).output)
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `regeneration fences and despawns running shadow without orphan processes`() {
        StudioRuntime(temp.resolve("replace")).use { r ->
            val source = node(r, "text"); val p = node(r, "program")
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, "import time\ntime.sleep(30)\nprint('obsolete')")
            awaitUntil("real shadow process") { r.activeProcesses.isNotEmpty() }
            val pids = r.activeProcesses
            val old = r.context.live().spawns.values.single { it.shadow }.ref
            r.installCode(p.id, code("replacement:")); preview(r, p.id, "replacement:held")
            assertFalse(r.context.live().spawns.containsKey(old))
            awaitUntil("old process tree gone") { pids.all { pid -> ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) } }
            assertNull(r.state(p.id).output)
            assertEquals(1, r.context.live().spawns.values.count { it.shadow })
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `promotion fences detached incumbent worker before swapping`() {
        StudioRuntime(temp.resolve("fence")).use { r ->
            val source = node(r, "text"); val p = node(r, "program")
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, "import json,sys,time\nv=json.load(sys.stdin)['in']['new']\nif v == 'slow': time.sleep(30)\nprint('old:'+v)")
            preview(r, p.id, "old:held"); promote(r, p.id); live(r, p.id, "old:held")
            r.submit(source.id, "slow")
            awaitUntil("incumbent child running") { r.activeProcesses.isNotEmpty() }
            val pids = r.activeProcesses
            r.installCode(p.id, code("fast:")); preview(r, p.id, "fast:slow")
            promote(r, p.id); live(r, p.id, "fast:slow")
            awaitUntil("incumbent child gone") { pids.all { pid -> ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) } }
            assertEquals("fast:slow", r.state(p.id).output?.string("new"))
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `promoted state survives compacted restart and post-COMMIT crash image without rerun`() {
        val data = temp.resolve("restart")
        var p = ""; var source = ""
        lateinit var expected: StudioGraph
        lateinit var commitImage: Path
        StudioRuntime(data).use { r ->
            source = node(r, "text").id; p = node(r, "program").id
            r.addEdge(source, p, "in"); r.submit(source, "held"); r.settleInputs()
            r.installCode(p, code("promoted:")); preview(r, p, "promoted:held")
            val hash = r.state(p).codeHash
            r.afterPromotion = { commitImage = image(r, "committed-before-publish") }
            promote(r, p); live(r, p, "promoted:held")
            StudioRuntime(commitImage).use { recovered ->
                recovered.settleInputs()
                assertEquals(hash, recovered.state(p).approvedHash)
                assertEquals(hash, recovered.state(p).codeHash)
                assertNull(recovered.state(p).preview)
                assertEquals(0, recovered.state(p).runCount) // swap durable; publish not yet requested
                assertNull(recovered.state(p).output)
                recovered.run(p); live(recovered, p, "promoted:held")
            }
            val records = FileJournal(commitImage.resolve("studio.journal").toFile()).replay().map(JournalRecords::decode)
            assertTrue(records.filterIsInstance<DecodedJournalRecord.Topology>().flatMap { it.events }.any { it is TopoEvent.Promote })
            r.settleInputs(); expected = r.graph()
        }
        StudioRuntime(data).use { r ->
            r.settleInputs(); assertEquals(expected, r.graph()); assertTrue(r.activeProcesses.isEmpty())
            r.submit(source, "next"); live(r, p, "promoted:next")
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `unpromoted restart preserves live code and recreates preview on input or approve`() {
        val data = temp.resolve("unpromoted")
        var p = ""; var source = ""; var approved = ""
        StudioRuntime(data).use { r ->
            source = node(r, "text").id; p = node(r, "program").id
            r.addEdge(source, p, "in"); r.submit(source, "held"); r.settleInputs()
            r.installCode(p, code("live:")); preview(r, p, "live:held"); promote(r, p); live(r, p, "live:held")
            approved = r.state(p).approvedHash!!
            r.installCode(p, code("candidate:")); preview(r, p, "candidate:held")
        }
        StudioRuntime(data).use { r ->
            r.settleInputs()
            assertEquals(approved, r.state(p).approvedHash); assertNull(r.state(p).preview)
            assertEquals("live:held", r.state(p).output?.string("new")); assertTrue(r.activeProcesses.isEmpty())
            r.graph() // first UI snapshot request lazily reconstitutes the sandboxed preview
            preview(r, p, "candidate:held")
            assertEquals("live:held", r.state(p).output?.string("new"))
            r.submit(source, "next"); preview(r, p, "candidate:next"); live(r, p, "live:next")
        }
        StudioRuntime(data).use { r ->
            assertNull(r.state(p).preview)
            promote(r, p) // no in-memory candidate: reconstruct then promote
            live(r, p, "candidate:next")
            assertEquals(r.state(p).codeHash, r.state(p).approvedHash)
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `promotion folds an accepted delivery before the incumbent intake callback runs`() {
        StudioRuntime(temp.resolve("promotion-inbox")).use { r ->
            val source = node(r, "text"); val p = node(r, "program"); val downstream = node(r, "exec")
            r.addEdge(source.id, p.id, "in"); r.addEdge(p.id, downstream.id, "in")
            r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, code("old:")); preview(r, p.id, "old:held")
            promote(r, p.id); live(r, downstream.id, "old:held")
            val count = r.state(downstream.id).runCount
            var promoted = false
            r.listen { frame ->
                val event = json.parseToJsonElement(frame).jsonObject
                if (event.string("type") == "state" && event["node"]!!.jsonObject.let {
                        it.string("id") == source.id && it.string("text") == "latest"
                    }) {
                    // Source delivery has settled, but this reentrant listener
                    // still owns the lock ahead of both runtime intake callbacks.
                    assertEquals("held", r.state(p.id).inputs.getValue("in").string("new"))
                    r.installCode(p.id, code("new:"))
                    promote(r, p.id)
                    promoted = true
                }
            }.use { r.submit(source.id, "latest") }
            assertTrue(promoted)
            live(r, downstream.id, "new:latest"); r.settleInputs()
            assertEquals("latest", r.state(p.id).inputs.getValue("in").string("new"))
            assertEquals(count + 1, r.state(downstream.id).runCount)
        }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `promoted canvas identity supports new wires deletion and restart`() {
        val data = temp.resolve("editing")
        var deleted = ""
        StudioRuntime(data).use { r ->
            val source = node(r, "text"); val p = node(r, "program"); val downstream = node(r, "exec")
            deleted = p.id
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, code("promoted:")); preview(r, p.id, "promoted:held")
            promote(r, p.id); live(r, p.id, "promoted:held")
            r.addEdge(p.id, downstream.id, "in"); live(r, downstream.id, "promoted:held")
            r.deleteNode(p.id)
            assertTrue(r.graph().edges.isEmpty())
            assertFalse(r.context.handles.keys.any { it.startsWith(p.id) })
        }
        StudioRuntime(data).use { r ->
            r.settleInputs()
            assertFalse(r.graph().nodes.any { it.id == deleted })
            assertTrue(r.graph().edges.isEmpty())
            assertTrue(r.activeProcesses.isEmpty())
        }
    }

    @Test fun `missing sandbox binary refuses preview and live without unsandboxed fallback`() {
        StudioRuntime(temp.resolve("missing"), PythonSandbox(temp.resolve("no-sandbox-exec"))).use { r ->
            val source = node(r, "text"); val p = node(r, "program")
            r.addEdge(source.id, p.id, "in"); r.submit(source.id, "held"); r.settleInputs()
            r.installCode(p.id, code("unsafe:"))
            awaitUntil("missing sandbox preview failure") { r.state(p.id).preview?.status == "failed" }
            assertTrue(r.state(p.id).preview!!.error!!.contains("refusing to run programmable Python"))
            promote(r, p.id)
            awaitUntil("missing sandbox live failure") { r.state(p.id).status == "failed" }
            assertTrue(r.state(p.id).error!!.contains("sandbox-exec unavailable"))
            assertNull(r.state(p.id).output); assertTrue(r.activeProcesses.isEmpty())
        }
    }
}
