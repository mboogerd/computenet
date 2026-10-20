package civictech.demo.studio

import civictech.cell.durability.FileJournal
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.graph.TopoEvent
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

@Timeout(60)
class StudioDurabilityTest {
    @TempDir lateinit var temp: Path

    private fun node(r: StudioRuntime, kind: String, command: String? = null) = r.addNode(buildJsonObject {
        put("kind", kind)
        if (command != null) put("config", buildJsonObject { put("command", command) })
    })
    private fun done(r: StudioRuntime, id: String, value: String) = awaitUntil("completed $value") {
        r.state(id).let { it.status == "idle" && it.output?.string("new") == value }
    }
    private fun durable(r: StudioRuntime, id: String): StudioCheckpoint = json.decodeFromString(
        r.context.host.snapshotOf(r.context.handles.getValue(id)).get(5, java.util.concurrent.TimeUnit.SECONDS) as String)

    /** Copy only durable authority and canvas metadata, while the original process stays alive. */
    private fun crashImage(r: StudioRuntime, name: String): Path {
        r.context.host.quiescence().await()
        val image = Files.createDirectory(temp.resolve(name))
        for (file in listOf("studio.journal", "graph.json")) Files.copy(r.data.resolve(file), image.resolve(file))
        return image
    }

    @Test fun `edit restart preserves exactly live refs links held values and configuration`() {
        val data = temp.resolve("edits")
        lateinit var expected: StudioGraph
        lateinit var refs: Map<String, civictech.cell.CellRef>
        StudioRuntime(data).use { r ->
            val source = node(r, "text")
            val target = node(r, "exec", "printf %s {{left}}")
            val removed = node(r, "exec", "printf removed")
            val edge = r.addEdge(source.id, target.id, "left")
            r.submit(source.id, "durable"); done(r, target.id, "durable")
            r.deleteEdge(edge.id)
            r.addEdge(source.id, target.id, "left"); r.settleInputs()
            r.deleteNode(removed.id)
            val replacement = node(r, "exec", "printf replacement")
            r.patchNode(replacement.id, buildJsonObject {
                put("title", "replacement")
                put("x", 123); put("y", 456)
                put("config", buildJsonObject { put("command", "printf changed"); put("auto", false) })
            })
            // The refused edit must leave no poison connect in the durable topology.
            r.patchNode(source.id, buildJsonObject { put("title", "source title") })
            val cycleA = node(r, "exec", "printf %s {{in}}")
            val cycleB = node(r, "exec", "printf %s {{in}}")
            r.addEdge(cycleA.id, cycleB.id, "in")
            val refusal = assertThrows(StudioException::class.java) { r.addEdge(cycleB.id, cycleA.id, "in") }
            assertEquals(409, refusal.status)
            assertTrue(refusal.message!!.contains("CycleWithoutHead"))
            expected = r.graph(); refs = r.context.handles.toMap()
            val crash = crashImage(r, "edits-crash")
            StudioRuntime(crash).use { restored ->
                restored.settleInputs()
                assertEquals(expected, restored.graph())
                assertEquals(refs, restored.context.handles)
                assertEquals(2, restored.context.live().links.size)
            }
        }
        StudioRuntime(data).use { r ->
            r.settleInputs()
            assertEquals(expected, r.graph())
            assertEquals(refs, r.context.handles)
            assertEquals(2, r.context.live().links.size)
            Files.walk(data).use { files -> assertFalse(files.anyMatch { it.fileName.toString() == "state.json" }) }
            val metadata = json.parseToJsonElement(Files.readString(data.resolve("graph.json"))).jsonObject
            assertFalse(metadata.toString().contains("config")); assertNull(metadata["state"])
        }
    }

    @Test fun `started process crash becomes durably interrupted and never automatically retries`() {
        val marker = temp.resolve("launches")
        val release = temp.resolve("release")
        val script = temp.resolve("blocked.sh")
        Files.writeString(script, """
            echo launch >> "$marker"
            while [ ! -f "$release" ]; do sleep 0.02; done
            printf %s "${'$'}1"
        """.trimIndent())
        StudioRuntime(temp.resolve("original")).use { r ->
            val source = node(r, "text")
            val target = node(r, "exec", "sh $script {{in}}")
            r.addEdge(source.id, target.id, "in")
            r.submit(source.id, "held")
            awaitUntil("external process started") { Files.exists(marker) }
            val started = durable(r, target.id).job!!
            assertEquals("started", started.phase)
            assertEquals(64, started.inputsHash.length)
            val image = crashImage(r, "started-crash")
            // This journal-only image tests interrupted state independently of orphan
            // cleanup. Let the original finish before opening its copied PID ledger;
            // StudioProcessBugsTest covers recovery killing a still-live orphan tree.
            Files.writeString(release, "go")
            done(r, target.id, "held")
            StudioRuntime(image).use { restored ->
                restored.settleInputs()
                val state = restored.state(target.id)
                assertEquals("stale", state.status)
                assertEquals("previous run interrupted", state.error)
                assertEquals(1, state.runCount); assertNull(state.output); assertNull(state.lastRun)
                assertEquals("held", state.inputs.getValue("in").string("new"))
                assertEquals(started.runId, durable(restored, target.id).job!!.runId)
                assertEquals("interrupted", durable(restored, target.id).job!!.phase)
                assertEquals(listOf("launch"), Files.readAllLines(marker))
            }
            StudioRuntime(image).use { restored ->
                restored.settleInputs()
                assertEquals("interrupted", durable(restored, target.id).job!!.phase)
                assertEquals("previous run interrupted", restored.state(target.id).error)
                assertEquals(listOf("launch"), Files.readAllLines(marker))
            }
        }
    }

    @Test fun `completed uncheckpointed crash restores result downstream without another process`() {
        val marker = temp.resolve("completed-launches")
        val script = temp.resolve("count.sh")
        Files.writeString(script, "echo launch >> '$marker'\nprintf %s \"${'$'}1\"\n")
        StudioRuntime(temp.resolve("complete")).use { r ->
            val a = node(r, "text"); val b = node(r, "exec", "sh $script {{in}}")
            val c = node(r, "exec", "printf 'downstream:%s' {{in}}")
            r.addEdge(a.id, b.id, "in"); r.addEdge(b.id, c.id, "in")
            r.submit(a.id, "result"); done(r, c.id, "downstream:result")
            val job = durable(r, b.id).job!!
            assertEquals("completed", job.phase)
            assertEquals("result", job.result!!.stdout)
            assertEquals(r.state(b.id).lastRun, job.result.summary())
            val image = crashImage(r, "completed-crash")
            StudioRuntime(image).use { restored ->
                restored.settleInputs()
                assertEquals(r.graph(), restored.graph())
                assertEquals(job, durable(restored, b.id).job)
                assertEquals(1, restored.state(b.id).runCount)
                assertEquals(1, restored.state(c.id).runCount)
                assertEquals(listOf("launch"), Files.readAllLines(marker))
                assertEquals(r.state(b.id).output, restored.state(c.id).inputs["in"])
            }
        }
    }

    @Test fun `fresh checkpoint baseline actually refires and equal held input absorbs it`() {
        val data = temp.resolve("checkpoint")
        var target = ""
        StudioRuntime(data).use { r ->
            val a = node(r, "text"); val b = node(r, "exec", "printf %s {{in}}")
            target = b.id
            r.addEdge(a.id, b.id, "in"); r.submit(a.id, "same"); done(r, b.id, "same")
            r.settleInputs()
            assertTrue(durable(r, b.id).inbox.isEmpty())
            r.checkpoint()
            val image = crashImage(r, "checkpoint-crash")
            StudioRuntime(image).use { restored ->
                restored.settleInputs()
                assertTrue(restored.recoveredInputs > 0, "must observe actual checkpoint catch-up delivery")
                assertEquals(r.graph(), restored.graph())
                assertEquals(1, restored.state(target).runCount)
            }
        }
        StudioRuntime(data).use { restored ->
            restored.settleInputs()
            assertTrue(restored.recoveredInputs > 0)
            assertEquals(1, restored.state(target).runCount)
        }
    }

    @Test fun `shutdown checkpoints live topology without journaling despawns`() {
        val data = temp.resolve("shutdown")
        val r = StudioRuntime(data)
        val a = node(r, "text"); val b = node(r, "exec", "printf %s {{in}}")
        r.addEdge(a.id, b.id, "in")
        val refs = r.context.handles.toMap()
        r.close(); r.close()
        val records = FileJournal(data.resolve("studio.journal").toFile()).replay().map(JournalRecords::decode)
        val topology = records.filterIsInstance<DecodedJournalRecord.Topology>().flatMap { it.events }
        assertEquals(refs.values.toSet(), topology.filterIsInstance<TopoEvent.Spawn>().map { it.ref }.toSet())
        assertEquals(1, topology.filterIsInstance<TopoEvent.Connect>().size)
        assertTrue(topology.none { it is TopoEvent.Despawn || it is TopoEvent.Unlink })
        StudioRuntime(data).use { restored -> assertEquals(refs, restored.context.handles) }
    }

    @EnabledOnOs(OS.MAC)
    @Test fun `configuration code hashes and approval recover independently of canvas and program files`() {
        val data = temp.resolve("program")
        var id = ""
        lateinit var expected: NodeState
        lateinit var config: JsonObject
        StudioRuntime(data).use { r ->
            val p = node(r, "program"); id = p.id
            r.patchNode(id, buildJsonObject { put("config", buildJsonObject {
                put("inputs", JsonArray(emptyList())); put("auto", false); put("prompt", "retained prompt")
            }) })
            r.installCode(id, "print('stored code')")
            r.approve(id, r.state(id).codeHash!!)
            done(r, id, "stored code")
            expected = r.state(id); config = r.graph().nodes.single().config
        }
        Files.delete(data.resolve("graph.json"))
        // Scratch program files are disposable; journaled bytes are authoritative.
        data.resolve("nodes/$id/runs").toFile().deleteRecursively()
        StudioRuntime(data).use { r ->
            assertEquals(expected, r.state(id))
            assertEquals(config, r.graph().nodes.single().config)
            r.run(id)
            awaitUntil("explicit run materializes stored approved bytes") { r.state(id).runCount == 2 && r.state(id).status == "idle" }
            val run = r.runDetail(id, r.state(id).lastRun!!.runId)
            assertEquals(expected.code, Files.readString(Path.of(run.cwd).resolve("cell.py")))
        }
    }

    @Test fun `periodic checkpoints bound the tail and preserve state after further edits`() {
        val data = temp.resolve("cadence")
        var id = ""
        StudioRuntime(data).use { r ->
            id = node(r, "text").id
            repeat(110) { r.submit(id, "text-$it") }
            val records = FileJournal(data.resolve("studio.journal").toFile()).replay().map(JournalRecords::decode)
            assertTrue(records.any { it is DecodedJournalRecord.Checkpoint })
            val image = crashImage(r, "cadence-crash")
            StudioRuntime(image).use { restored -> assertEquals(r.state(id), restored.state(id)) }
        }
    }
    @Test fun `delivered input survives a crash before runtime intake acknowledges it`() {
        StudioRuntime(temp.resolve("inbox")).use { r ->
            val source = node(r, "text"); val target = node(r, "exec", "printf %s {{in}}")
            r.addEdge(source.id, target.id, "in")
            var image: Path? = null
            // State listeners run under the writer lock after kernel delivery settles,
            // before the intake can acquire that lock and start the target's process.
            r.listen { frame ->
                val event = json.parseToJsonElement(frame).jsonObject
                if (event.string("type") == "state" && event["node"]!!.jsonObject.let {
                        it.string("id") == source.id && it.string("text") == "unacknowledged"
                    }) {
                    assertEquals(1, durable(r, target.id).inbox.size)
                    assertEquals(0, r.state(target.id).runCount)
                    image = crashImage(r, "inbox-crash")
                }
            }.use { r.submit(source.id, "unacknowledged") }
            StudioRuntime(checkNotNull(image)).use { restored ->
                restored.settleInputs()
                assertEquals("unacknowledged", restored.state(target.id).inputs.getValue("in").string("new"))
                assertEquals("stale", restored.state(target.id).status)
                assertEquals(0, restored.state(target.id).runCount)
                assertTrue(durable(restored, target.id).inbox.isEmpty())
            }
        }
    }

    @Test fun `deleting every node leaves an empty graph recoverable without an empty checkpoint`() {
        val data = temp.resolve("empty")
        StudioRuntime(data).use { r ->
            val a = node(r, "text")
            r.submit(a.id, "removed")
            r.deleteNode(a.id)
            r.checkpoint()
        }
        StudioRuntime(data).use { r ->
            assertEquals(StudioGraph(emptyList(), emptyList()), r.graph())
            assertTrue(r.context.handles.isEmpty())
        }
    }

}
