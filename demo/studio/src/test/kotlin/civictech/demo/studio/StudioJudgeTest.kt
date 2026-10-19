package civictech.demo.studio

import civictech.testkit.awaitUntil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(60)
class StudioJudgeTest {
    @TempDir lateinit var temp: Path
    private val key = "studio-secret-canary-DO-NOT-PERSIST"
    private fun config(vararg entries: Pair<String, JsonElement>) = nodeConfig("judge", obj(*entries))
    private fun client(http: JevHttp) = TypeSafeStudioJudge(environment = { key }, http = http, sleeper = {})
    private fun response(p: Double, reason: String? = null, cost: Double? = null) = JevHttpResponse(200, buildJsonObject {
        put("answers", obj("judgment" to buildJsonObject {
            put("type", "noul"); put("noul", p); reason?.let { put("reason", it) }
        }))
        cost?.let { put("cost_usd", it) }
    }.toString())
    private fun node(r: StudioRuntime, kind: String, config: JsonObject = obj()) = r.addNode(buildJsonObject {
        put("kind", kind); put("config", config)
    })
    private fun runtime(data: Path = temp, http: JevHttp) = StudioRuntime(data, PythonSandbox(), client(http))
    private fun finished(r: StudioRuntime, id: String, count: Int = 1) = awaitUntil("completed judgment $count") {
        r.state(id).let { it.lastRun != null && it.runCount == count && it.status !in setOf("running", "queued") }
    }
    private fun checkpoint(r: StudioRuntime, id: String): StudioCheckpoint = json.decodeFromString(
        r.context.host.snapshotOf(r.context.handles.getValue(id)).get(5, TimeUnit.SECONDS) as String)
    private fun body(request: HttpRequest): JsonObject {
        val bytes = java.io.ByteArrayOutputStream()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(value: java.nio.ByteBuffer) { val b = ByteArray(value.remaining()); value.get(b); bytes.write(b) }
            override fun onError(error: Throwable) { throw AssertionError(error) }
            override fun onComplete() {}
        })
        return json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    @Test fun `config defaults ports validation and credential refusal precede persistence`() {
        val c = config("question" to JsonPrimitive("{{diff.old}} {{log}} {{diff}}"))
        assertEquals(listOf("diff", "log"), inputPorts("judge", c))
        assertTrue(c.bool("auto")); assertEquals(0.5, c["threshold"]!!.jsonPrimitive.double)
        val invalid = listOf(
            obj("question" to JsonPrimitive("")), obj("output" to JsonPrimitive("text")),
            obj("mode" to JsonPrimitive("route")), obj("threshold" to JsonPrimitive(1.1)),
            obj("threshold" to JsonPrimitive(-0.1)), obj("timeoutSec" to JsonPrimitive(0)),
            obj("output" to JsonPrimitive("choice")), obj("choices" to JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("x")))),
            obj("passChoices" to JsonArray(listOf(JsonPrimitive("unknown")))),
            obj("mode" to JsonPrimitive("gate"), "passPort" to JsonPrimitive("missing")),
            obj("mode" to JsonPrimitive("gate"), "question" to JsonPrimitive("no input")),
            obj("apiKey" to JsonPrimitive(key)), obj("TYPESAFE_API_KEY" to JsonPrimitive(key)),
        )
        StudioRuntime(temp).use { r ->
            invalid.forEach { patch -> assertEquals(400, assertThrows(StudioException::class.java) { node(r, "judge", patch) }.status) }
            assertTrue(r.graph().nodes.isEmpty())
        }
        assertSecretAbsent()
    }

    @Test fun `HTTP contract uses bearer environment key structured state typed questions and configured model`() {
        val inputs = mapOf("diff" to change("before", "after", "/repo"))
        val judge = client { req ->
            assertEquals("https://api.typesafe.ai/v1/systemone", req.uri().toString())
            assertEquals("Bearer $key", req.headers().firstValue("Authorization").get())
            assertEquals("application/json", req.headers().firstValue("Content-Type").get())
            assertTrue(req.timeout().get().seconds in 6..7)
            val b = body(req)
            assertEquals("pinned-model", b.string("model")); assertEquals(JsonObject(inputs), b["state"])
            val q = b["questions"]!!.jsonObject.getValue("judgment").jsonObject
            assertEquals("noul", q.string("type")); assertEquals("Compare before to after", q.string("instructions"))
            assertFalse(b.toString().contains(key))
            response(0.75, "evidence", 0.0002)
        }
        val got = judge.judge(config("question" to JsonPrimitive("Compare {{diff.old}} to {{diff}}"),
            "model" to JsonPrimitive("pinned-model"), "timeoutSec" to JsonPrimitive(7)), inputs)
        assertEquals(StudioJudgment("true", 0.75, "evidence"), got.judgment)
        assertEquals(0.0002, got.costUsd)
    }

    @Test fun `boolean probability and choice parse real System One shapes without invented explanations or cost`() {
        for ((output, text) in listOf("boolean" to "false", "probability" to "0.49")) {
            val got = client { req -> assertEquals("jev-latest", body(req).string("model")); response(0.49) }
                .judge(config("output" to JsonPrimitive(output)), emptyMap())
            assertEquals(StudioJudgment(text, 0.49), got.judgment); assertNull(got.costUsd)
        }
        val choices = JsonArray(listOf(JsonPrimitive("pass"), JsonPrimitive("stop")))
        val got = client { req ->
            val q = body(req)["questions"]!!.jsonObject.getValue("judgment").jsonObject
            assertEquals("choice", q.string("type")); assertEquals(obj("pass" to JsonNull, "stop" to JsonNull), q["criteria"])
            JevHttpResponse(200, """{"answers":{"judgment":{"type":"choice","choice":"stop","probabilities":{"pass":0.2,"stop":0.8},"confidence":0.6}}}""")
        }.judge(config("output" to JsonPrimitive("choice"), "choices" to choices), emptyMap())
        assertEquals(StudioJudgment("stop", 0.8), got.judgment)
    }

    @Test fun `retry only rate limits and overload within one timeout budget`() {
        val attempts = AtomicInteger()
        val delays = mutableListOf<Long>()
        var now = 0L
        val timeouts = mutableListOf<Long>()
        val judge = TypeSafeStudioJudge({ key }, { req ->
            timeouts += req.timeout().get().toMillis()
            when (attempts.incrementAndGet()) { 1 -> JevHttpResponse(429, key); 2 -> JevHttpResponse(529, key); else -> response(1.0) }
        }, { ms -> delays += ms; now += TimeUnit.MILLISECONDS.toNanos(ms) }, { now })
        assertEquals("true", judge.judge(config(), emptyMap()).judgment.answer)
        assertEquals(listOf(500L, 1000L), delays)
        assertEquals(listOf(60000L, 59500L, 58500L), timeouts)
        val exhausted = AtomicInteger()
        assertEquals("Jev HTTP 529", assertThrows(IOException::class.java) {
            client { exhausted.incrementAndGet(); JevHttpResponse(529, key) }.judge(config(), emptyMap())
        }.message)
        assertEquals(4, exhausted.get())
    }

    @Test fun `deadline expiry stops retries and timeout exceptions carry a safe clear diagnostic`() {
        var now = 0L
        val judge = TypeSafeStudioJudge({ key }, { JevHttpResponse(429, key) },
            { now += TimeUnit.SECONDS.toNanos(2) }, { now })
        assertEquals("Jev timed out after 1 seconds", assertThrows(IOException::class.java) {
            judge.judge(config("timeoutSec" to JsonPrimitive(1)), emptyMap())
        }.message)
        assertTrue(assertThrows(IOException::class.java) {
            client { throw HttpTimeoutException(key) }.judge(config(), emptyMap())
        }.message!!.contains("timed out"))
        now = 0L
        val late = TypeSafeStudioJudge({ key }, { now = TimeUnit.SECONDS.toNanos(2); response(1.0) }, {}, { now })
        assertTrue(assertThrows(IOException::class.java) {
            late.judge(config("timeoutSec" to JsonPrimitive(1)), emptyMap())
        }.message!!.contains("timed out"))
    }

    @Test fun `invalid response cannot produce a successful judgment`() {
        val invalid = listOf("not JSON $key", "{}", """{"answers":{"judgment":{"noul":1.1}}}""",
            """{"answers":{"judgment":{"noul":"0.5"}}}""", """{"answers":{"judgment":{"type":"choice","noul":0.7}}}""")
        invalid.forEach { raw ->
            val error = assertThrows(IOException::class.java) { client { JevHttpResponse(200, raw) }.judge(config(), emptyMap()) }
            assertFalse(error.toString().contains(key))
        }
        assertThrows(IOException::class.java) {
            client { JevHttpResponse(200, """{"answers":{"judgment":{"choice":"unknown"}}}""") }.judge(
                config("output" to JsonPrimitive("choice"), "choices" to JsonArray(listOf(JsonPrimitive("known")))), emptyMap())
        }
    }

    @Test fun `judge emits text of each typed answer and suppresses unchanged answer downstream`() {
        for ((type, answer) in listOf("boolean" to "true", "probability" to "0.8", "choice" to "yes")) {
            runtime(temp.resolve(type)) {
                if (type == "choice") JevHttpResponse(200, """{"answers":{"judgment":{"choice":"yes","probabilities":{"yes":0.8,"no":0.2}}}}""")
                else response(0.8)
            }.use { r ->
                val a = node(r, "text")
                val j = node(r, "judge", obj("output" to JsonPrimitive(type), "choices" to JsonArray(listOf(JsonPrimitive("yes"), JsonPrimitive("no")))))
                val downstream = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{in}}")))
                r.addEdge(a.id, j.id, "in"); r.addEdge(j.id, downstream.id, "in")
                r.submit(a.id, "first"); finished(r, downstream.id)
                assertEquals(obj("old" to JsonNull, "new" to JsonPrimitive(answer), "probability" to JsonPrimitive(0.8), "reason" to JsonNull), r.state(j.id).output)
                r.submit(a.id, "second"); finished(r, j.id, 2); r.settleInputs()
                assertEquals(1, r.state(downstream.id).runCount)
                val detail = r.runDetail(j.id, r.state(j.id).lastRun!!.runId)
                assertEquals("judged", detail.outcome); assertEquals(answer, detail.judgment!!.answer)
                assertEquals("completed", checkpoint(r, j.id).job!!.phase)
            }
        }
    }

    @Test fun `gate threshold inclusive passes original change metadata and only passes run downstream`() {
        val responses = java.util.concurrent.LinkedBlockingQueue(listOf(response(0.5, "pass", 0.001), response(0.49), response(0.9)))
        runtime { responses.remove() }.use { r ->
            val a = node(r, "text")
            val j = node(r, "judge", obj("mode" to JsonPrimitive("gate"), "output" to JsonPrimitive("probability")))
            val downstream = node(r, "exec", obj("command" to JsonPrimitive("printf %s {{in}}")))
            r.addEdge(a.id, j.id, "in"); r.addEdge(j.id, downstream.id, "in")
            r.submit(a.id, "allowed"); finished(r, downstream.id)
            val source = r.state(a.id).output!!
            val passed = r.state(j.id).output!!
            assertEquals(source, JsonObject(passed - "gate"))
            assertEquals(obj("probability" to JsonPrimitive(0.5), "reason" to JsonPrimitive("pass")), passed["gate"])
            assertEquals(passed, r.state(downstream.id).inputs["in"])
            assertEquals(0.001, r.state(j.id).lastRun!!.costUsd)
            r.submit(a.id, "blocked"); finished(r, j.id, 2); r.settleInputs()
            assertEquals("gated", r.state(j.id).status); assertEquals(passed, r.state(j.id).output)
            assertEquals("blocked", r.state(j.id).inputs.getValue("in").string("new"))
            assertEquals("gated", r.runDetail(j.id, r.state(j.id).lastRun!!.runId).outcome)
            assertEquals(1, r.state(downstream.id).runCount)
            r.submit(a.id, "next"); finished(r, downstream.id, 2)
            assertEquals("next", r.state(downstream.id).output!!.string("new"))
            assertEquals(2, r.state(j.id).judgment!!.passed); assertEquals(1, r.state(j.id).judgment!!.gated)
            assertEquals("completed", checkpoint(r, j.id).job!!.phase)
        }
    }

    @Test fun `choice and boolean gate rules and selected pass port preserve all original fields`() {
        val cfg = config("question" to JsonPrimitive("{{log}} {{diff}}"), "mode" to JsonPrimitive("gate"),
            "passPort" to JsonPrimitive("diff"), "output" to JsonPrimitive("choice"),
            "choices" to JsonArray(listOf(JsonPrimitive("real"), JsonPrimitive("flake"))),
            "passChoices" to JsonArray(listOf(JsonPrimitive("real"))))
        val original = JsonObject(change("before", "after", "/repo") + obj("custom" to obj("nested" to JsonPrimitive(true))))
        val inputs = mapOf("log" to change(null, "log"), "diff" to original)
        assertEquals(original, JsonObject(judgedChange(cfg, inputs, null, StudioJudgment("real", 0.7))!! - "gate"))
        assertNull(judgedChange(cfg, inputs, null, StudioJudgment("flake", 0.9)))
        val boolean = config("mode" to JsonPrimitive("gate"), "threshold" to JsonPrimitive(0.99))
        assertTrue(judgePasses(boolean, StudioJudgment("true", 0.6))) // boolean threshold stays 0.5 at the HTTP boundary
        assertFalse(judgePasses(boolean, StudioJudgment("false", 0.4)))
    }

    @Test fun `judge old tracks previous answer including a switch from gate mode and manual auto false`() {
        val replies = java.util.concurrent.LinkedBlockingQueue(listOf(response(0.8), response(0.2), response(0.9)))
        runtime { replies.remove() }.use { r ->
            val a = node(r, "text")
            val j = node(r, "judge", obj("mode" to JsonPrimitive("gate"), "auto" to JsonPrimitive(false)))
            r.addEdge(a.id, j.id, "in"); r.submit(a.id, "original payload"); r.settleInputs()
            assertEquals(0, r.state(j.id).runCount); assertEquals("stale", r.state(j.id).status)
            r.run(j.id); finished(r, j.id)
            assertEquals("original payload", r.state(j.id).output!!.string("new"))
            r.patchNode(j.id, obj("config" to obj("mode" to JsonPrimitive("judge"))))
            r.run(j.id); finished(r, j.id, 2)
            assertEquals("true", r.state(j.id).output!!.string("old"))
            assertEquals("false", r.state(j.id).output!!.string("new"))
            r.run(j.id); finished(r, j.id, 3)
            assertEquals("false", r.state(j.id).output!!.string("old"))
            assertEquals("true", r.state(j.id).output!!.string("new"))
        }
    }

    @Test fun `busy burst becomes one follow up judgment with first old and latest new`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        runtime {
            if (calls.incrementAndGet() == 1) { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            response(0.8)
        }.use { r ->
            val a = node(r, "text"); val j = node(r, "judge")
            r.addEdge(a.id, j.id, "in"); r.submit(a.id, "one")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            try {
                r.submit(a.id, "two"); r.settleInputs(); r.submit(a.id, "three"); r.settleInputs()
                assertEquals(1, calls.get()); assertEquals("queued", r.state(j.id).status)
            } finally { release.countDown() }
            finished(r, j.id, 2); r.settleInputs()
            assertEquals(2, calls.get())
            val detail = r.runDetail(j.id, r.state(j.id).lastRun!!.runId)
            assertEquals(change("one", "three"), detail.inputs["in"])
            r.deleteEdge(r.graph().edges.single().id); r.addEdge(a.id, j.id, "in"); r.settleInputs()
            assertEquals(2, calls.get())
        }
    }

    @Test fun `API errors and timeouts finish ledger with error and never emit or persist secrets`() {
        for ((name, http) in listOf("api" to JevHttp { JevHttpResponse(401, "Authorization: Bearer $key") },
            "timeout" to JevHttp { throw HttpTimeoutException(key) },
            "transport" to JevHttp { throw IOException("Authorization: Bearer $key") })) {
            runtime(temp.resolve(name), http).use { r ->
                val a = node(r, "text"); val j = node(r, "judge")
                val b = node(r, "exec")
                r.addEdge(a.id, j.id, "in"); r.addEdge(j.id, b.id, "in")
                r.submit(a.id, "held"); finished(r, j.id); r.settleInputs()
                assertEquals("failed", r.state(j.id).status); assertNull(r.state(j.id).output)
                assertEquals(0, r.state(b.id).runCount)
                val detail = r.runDetail(j.id, r.state(j.id).lastRun!!.runId)
                assertNotNull(detail.error); assertEquals("failed", detail.outcome)
                if (name == "api") assertEquals("Jev HTTP 401", detail.error)
                if (name == "timeout") assertTrue(detail.error!!.contains("timed out"))
                assertEquals("completed", checkpoint(r, j.id).job!!.phase)
                assertEquals(detail, checkpoint(r, j.id).job!!.result)
                assertEquals("held", r.state(j.id).inputs.getValue("in").string("new"))
                assertFalse(json.encodeToString(r.graph()).contains(key))
            }
        }
        assertSecretAbsent()
    }

    @Test fun `choice gate failures recover on fresh input and equal passing output stays suppressed`() {
        val replies = java.util.concurrent.LinkedBlockingQueue(listOf(
            JevHttpResponse(503, key),
            JevHttpResponse(200, """{"answers":{"judgment":{"choice":"stop","probabilities":{"go":0.1,"stop":0.9}}}}"""),
            JevHttpResponse(200, """{"answers":{"judgment":{"choice":"go","probabilities":{"go":0.9,"stop":0.1}}}}"""),
            JevHttpResponse(200, """{"answers":{"judgment":{"choice":"go","probabilities":{"go":0.8,"stop":0.2}}}}""")))
        runtime { replies.remove() }.use { r ->
            val source = node(r, "text")
            val gate = node(r, "judge", obj("mode" to JsonPrimitive("gate"), "output" to JsonPrimitive("choice"),
                "choices" to JsonArray(listOf(JsonPrimitive("go"), JsonPrimitive("stop"))),
                "passChoices" to JsonArray(listOf(JsonPrimitive("go")))))
            val downstream = node(r, "exec")
            r.addEdge(source.id, gate.id, "in"); r.addEdge(gate.id, downstream.id, "in")
            r.submit(source.id, "first"); finished(r, gate.id)
            assertEquals("failed", r.state(gate.id).status)
            r.submit(source.id, "second"); finished(r, gate.id, 2)
            assertEquals("gated", r.state(gate.id).status); assertNull(r.state(gate.id).error)
            assertEquals(0, r.state(downstream.id).runCount)
            r.submit(source.id, "third"); finished(r, downstream.id)
            val prior = r.state(gate.id).output
            r.run(gate.id); finished(r, gate.id, 4); r.settleInputs()
            assertEquals(prior, r.state(gate.id).output)
            assertEquals(1, r.state(downstream.id).runCount)
            assertEquals(2, r.state(gate.id).judgment!!.passed)
            assertEquals(1, r.state(gate.id).judgment!!.gated)
        }
    }

    @Test fun `missing key fails clearly without making HTTP request`() {
        StudioRuntime(temp, PythonSandbox(), TypeSafeStudioJudge({ " " }, { error("must not call HTTP") })).use { r ->
            val j = node(r, "judge", obj("question" to JsonPrimitive("Is this true?")))
            r.run(j.id); finished(r, j.id)
            assertEquals("failed", r.state(j.id).status)
            assertEquals("Jev requires TYPESAFE_API_KEY in the server environment", r.state(j.id).error)
            assertNull(r.state(j.id).output)
            assertEquals("completed", checkpoint(r, j.id).job!!.phase)
        }
    }

    @Test fun `secret echoed in successful reason is redacted in state files ledger and history`() {
        runtime { response(0.8, "echo $key") }.use { r ->
            val j = node(r, "judge", obj("question" to JsonPrimitive("Is this true?")))
            r.run(j.id); finished(r, j.id)
            assertEquals("echo [redacted]", r.state(j.id).judgment!!.last.reason)
            assertFalse(json.encodeToString(r.graph()).contains(key))
            assertFalse(json.encodeToString(checkpoint(r, j.id)).contains(key))
            assertFalse(json.encodeToString(r.runDetail(j.id, r.state(j.id).lastRun!!.runId)).contains(key))
        }
        assertSecretAbsent()
    }

    @Test fun `gated state config counters held input and completed job survive checkpoint restart without requests`() {
        var source = ""; var target = ""
        lateinit var expected: StudioGraph
        lateinit var job: JobRecord
        runtime { response(0.1) }.use { r ->
            source = node(r, "text").id
            target = node(r, "judge", obj("mode" to JsonPrimitive("gate"), "output" to JsonPrimitive("probability"))).id
            r.addEdge(source, target, "in"); r.submit(source, "blocked"); finished(r, target)
            r.settleInputs(); r.checkpoint(); expected = r.graph(); job = checkpoint(r, target).job!!
        }
        runtime { error("recovery must not call HTTP") }.use { r ->
            r.settleInputs(); assertEquals(expected, r.graph()); assertEquals(job, checkpoint(r, target).job)
            assertEquals("gated", r.state(target).status)
            assertEquals(1, r.state(target).judgment!!.gated)
            r.deleteEdge(r.graph().edges.single().id); r.addEdge(source, target, "in"); r.settleInputs()
            assertEquals(1, r.state(target).runCount)
        }
    }

    @Test fun `in flight judgment crash image recovers interrupted without HTTP retry`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        runtime(temp.resolve("original")) {
            started.countDown(); check(release.await(10, TimeUnit.SECONDS)); response(1.0)
        }.use { r ->
            val j = node(r, "judge", obj("question" to JsonPrimitive("Is this true?")))
            r.run(j.id); assertTrue(started.await(5, TimeUnit.SECONDS))
            try {
                val image = Files.createDirectory(temp.resolve("image"))
                r.context.host.quiescence().await(5000)
                for (f in listOf("studio.journal", "graph.json")) Files.copy(r.data.resolve(f), image.resolve(f))
                runtime(image) { error("must not retry interrupted judgment") }.use { recovered ->
                    recovered.settleInputs()
                    assertEquals("interrupted", checkpoint(recovered, j.id).job!!.phase)
                    assertEquals("previous run interrupted", recovered.state(j.id).error)
                    assertEquals(1, recovered.state(j.id).runCount); assertNull(recovered.state(j.id).output)
                }
            } finally { release.countDown() }
            finished(r, j.id)
        }
    }

    private fun assertSecretAbsent() {
        Files.walk(temp).use { files -> files.filter { Files.isRegularFile(it) }.forEach { file ->
            assertFalse(Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains(key), "key leaked into $file")
        } }
    }
}
