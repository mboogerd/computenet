package civictech.deliberate

import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import civictech.testkit.awaitSseData
import civictech.testkit.boundedHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The HTTP surface (SPEC §6) over real sockets, with a fake judge and fake proposers. */
class DeliberateAppTest {

    private class FixedJudge : Judge {
        override fun plausibility(question: String, path: List<String>, claim: String) = 0.6
        override fun relationStrength(question: String, parent: String, child: String, side: Side) = 0.7
        override fun quality(question: String, parent: String, child: String, side: Side) = 0.9
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = candidates.map { Triage(TriageAction.ADD) }
        override fun saturation(ctx: ClaimContext, side: Side) = 0.0
        override fun relevance(ctx: ClaimContext) = 1.0
    }

    private class CountingProposer(override val id: String, val delayMs: Long = 0) : Proposer {
        private val n = AtomicInteger()
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            if (delayMs > 0) Thread.sleep(delayMs)
            return List(max) { "$id ${side.name.lowercase()} argument ${n.incrementAndGet()}." }
        }
    }

    private val apps = mutableListOf<DeliberateApp>()
    private val probes = mutableListOf<HttpProbe>()
    private val decoder = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        probes.forEach { it.close() }
        apps.forEach { it.stop() }
    }

    private fun app(
        config: DeliberationEngine.Config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, maxClaims = 40),
        delayMs: Long = 0,
        uiDir: File? = null,
        judge: Judge = FixedJudge(),
        proposers: List<Proposer> = listOf(CountingProposer("claude", delayMs), CountingProposer("codex", delayMs)),
        merger: Merger? = null,
        dataDir: File? = null,
        semantics: DeliberateApp.SemanticsConfig = DeliberateApp.SemanticsConfig(),
    ): Pair<DeliberateApp, HttpProbe> {
        val app = DeliberateApp(
            port = 0,
            judge = judge,
            proposers = proposers,
            config = config,
            uiDir = uiDir,
            merger = merger,
            dataDir = dataDir,
            semantics = semantics,
        ).start()
        apps += app
        val probe = HttpProbe("http://localhost:${app.boundPort}")
        probes += probe
        return app to probe
    }

    private fun HttpProbe.graph(): GraphDto = decoder.decodeFromString(GraphDto.serializer(), get("/graph").body())

    private fun HttpProbe.ask(text: String): String {
        val r = postForm("text=" + URLEncoder.encode(text, Charsets.UTF_8), "/question")
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject["root"]!!.jsonPrimitive.content
    }

    private fun HttpProbe.awaitGraph(predicate: (GraphDto) -> Boolean): GraphDto =
        decoder.decodeFromString(GraphDto.serializer(), await(timeoutMs = 20_000, path = "/graph") {
            predicate(decoder.decodeFromString(GraphDto.serializer(), it))
        })

    private fun GraphDto.idle(root: String) = questions.single { it.root == root }.let { !it.active }

    @Test
    fun `a question grows a tree visible in graph`() {
        val (_, probe) = app()
        val root = probe.ask("Should cities make public transit free?")
        val g = probe.awaitGraph { it.idle(root) }

        val q = g.questions.single()
        assertEquals("Should cities make public transit free?", q.text)
        val claims = g.nodes.filter { it.kind == "CLAIM" }
        // root + 4 children (2 proposers x 2 sides x 1) + 4 grandchildren each (depth 2 > maxDepth=1: DEPTH_LIMIT)
        assertEquals(21, claims.size)
        assertEquals(21, q.claims)
        val r = claims.single { it.ref == root }
        assertEquals(0, r.depth)
        assertEquals("question", r.proposer)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertEquals(setOf("claude", "codex"), claims.filter { it.depth == 1 }.map { it.proposer }.toSet())
        assertTrue(claims.filter { it.depth == 2 }.all { it.status == Status.DEPTH_LIMIT })
        val edges = g.nodes.filter { it.kind == "EDGE" }
        assertEquals(20, edges.size)
        assertEquals(setOf("SUPPORT", "ATTACK"), edges.map { it.polarity }.toSet())
        assertTrue(edges.all { it.strength == 0.7 })
        assertTrue(claims.all { it.credence in 0.0..1.0 })
    }

    @Test
    fun `graph exposes the rewritten text for REPLACE and MERGE`() {
        val replaceProposer = object : Proposer {
            override val id = "claude"
            override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> = when {
                side != civictech.agora.cell.Polarity.SUPPORT || ctx.path.isNotEmpty() -> emptyList()
                ctx.pros.isEmpty() -> listOf("Original argument.")
                else -> listOf("Clearer replacement argument.")
            }
        }
        val replaceJudge = object : Judge by FixedJudge() {
            override fun assess(question: String, path: List<String>, child: String, side: Side) =
                Assessment(plausibility = 0.6, strength = 0.4, quality = 0.9, relevance = 1.0)
            override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = candidates.map {
                if (it.text.startsWith("Clearer")) Triage(TriageAction.REPLACE, 0) else Triage(TriageAction.ADD)
            }
        }
        val (_, replaceProbe) = app(
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, minInfluence = 0.0, workers = 1,
            ),
            judge = replaceJudge,
            proposers = listOf(replaceProposer),
        )
        val replaceRoot = replaceProbe.ask("Replace?")
        val replaced = replaceProbe.awaitGraph { it.idle(replaceRoot) }
        val replacedTexts = replaced.nodes.filter { it.kind == "CLAIM" }.mapNotNull { it.text }
        assertTrue("Clearer replacement argument." in replacedTexts, replacedTexts.toString())
        assertTrue("Original argument." !in replacedTexts, replacedTexts.toString())

        fun fixed(id: String, text: String) = object : Proposer {
            override val id = id
            override fun propose(ctx: ClaimContext, side: Side, max: Int) =
                if (side == civictech.agora.cell.Polarity.SUPPORT && ctx.path.isEmpty()) listOf(text) else emptyList()
        }
        val mergeJudge = object : Judge by FixedJudge() {
            // Codex proposes after Claude (EXP-02), so B meets A as existing argument 0.
            override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = candidates.map {
                if (it.text == "Argument B.") Triage(TriageAction.MERGE, 0) else Triage(TriageAction.ADD)
            }
        }
        val (_, mergeProbe) = app(
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, workers = 1),
            judge = mergeJudge,
            proposers = listOf(fixed("claude", "Argument A."), fixed("codex", "Argument B.")),
            merger = { _, _, _, _ -> "Arguments A and B together." },
        )
        val mergeRoot = mergeProbe.ask("Merge?")
        val merged = mergeProbe.awaitGraph { it.idle(mergeRoot) }
        val mergedNode = merged.nodes.single { it.kind == "CLAIM" && it.depth == 1 }
        assertEquals("Arguments A and B together.", mergedNode.text)
        assertEquals(true, mergedNode.merged)
        assertTrue(merged.nodes.none { it.text == "Argument A." || it.text == "Argument B." })
    }

    @Test
    fun `events streams the initial graph and coalesced updates`() {
        val (app, probe) = app(delayMs = 200)
        val initial = awaitSseData("http://localhost:${app.boundPort}/events") { it.startsWith("data: {") }
        assertTrue("\"questions\":[]" in initial, initial)

        val frames = LinkedBlockingQueue<String>()
        val client = boundedHttpClient()
        val stream = client.send(
            HttpRequest.newBuilder(URI("http://localhost:${app.boundPort}/events")).build(),
            HttpResponse.BodyHandlers.ofLines(),
        )
        assertEquals(200, stream.statusCode())
        val reader = Thread.ofVirtual().start {
            try {
                stream.body().filter { it.startsWith("data:") }.forEach { frames.put(it) }
            } catch (_: Exception) {
            }
        }
        try {
            assertNotNull(frames.poll(5, TimeUnit.SECONDS), "no initial frame") // the connect-time frame
            val start = System.nanoTime()
            val root = probe.ask("Is remote work here to stay?")
            val sawRoot = generateSequence { frames.poll(10, TimeUnit.SECONDS) }
                .first { root in it }
            assertTrue("Is remote work here to stay?" in sawRoot)
            // Let the tree grow for a second and count frames: coalescing caps them near 10/s.
            val window = System.nanoTime()
            while (System.nanoTime() - window < TimeUnit.SECONDS.toNanos(1)) Thread.sleep(50)
            val elapsedS = (System.nanoTime() - start) / 1e9
            val count = frames.size + 1
            assertTrue(count <= (elapsedS * 10 + 3), "$count frames in ${"%.2f".format(elapsedS)} s — not coalesced")
            // The stream carries the finished tree eventually.
            probe.awaitGraph { it.idle(root) }
            // (the frame that first showed the root may already be the finished tree)
            val last = (sequenceOf(sawRoot) + generateSequence { frames.poll(5, TimeUnit.SECONDS) })
                .map { decoder.decodeFromString(GraphDto.serializer(), it.removePrefix("data:").trim()) }
                .first { it.idle(root) }
            assertEquals(21, last.nodes.count { it.kind == "CLAIM" })
        } finally {
            reader.interrupt()
            stream.body().close()
            client.shutdownNow()
        }
    }

    @Test
    fun `override STOP and EXPAND change a node's status`() {
        val (_, probe) = app()
        val root = probe.ask("Should we tax sugar?")
        val g = probe.awaitGraph { it.idle(root) }
        val leaf = g.nodes.first { it.kind == "CLAIM" && it.status == Status.DEPTH_LIMIT }
        val child = g.nodes.first { it.kind == "CLAIM" && it.depth == 1 }

        assertEquals(200, probe.postForm("id=${child.ref}&mode=STOP", "/override").statusCode())
        val stopped = probe.awaitGraph { gr -> gr.nodes.single { it.ref == child.ref }.status == Status.STOPPED }
        assertEquals(Override.STOP, stopped.nodes.single { it.ref == child.ref }.override)
        assertEquals(200, probe.postForm("id=${child.ref}&mode=AUTO", "/override").statusCode())
        val automatic = probe.awaitGraph { gr -> gr.nodes.single { it.ref == child.ref }.status == Status.ROUND_LIMIT }
        assertEquals(Override.AUTO, automatic.nodes.single { it.ref == child.ref }.override)

        // EXPAND bypasses the depth gate: the DEPTH_LIMIT leaf grows children.
        assertEquals(200, probe.postForm("id=${leaf.ref}&mode=expand", "/override").statusCode())
        val expanded = probe.awaitGraph { gr ->
            gr.idle(root) && gr.nodes.any { it.kind == "EDGE" && it.target == leaf.ref }
        }
        val l = expanded.nodes.single { it.ref == leaf.ref }
        assertEquals(Override.EXPAND, l.override)
        assertEquals(Status.ROUND_LIMIT, l.status)
        assertEquals(4, expanded.nodes.count { it.kind == "EDGE" && it.target == leaf.ref })
    }

    @Test
    fun `bad input is rejected`() {
        val (_, probe) = app()
        assertEquals(400, probe.postForm("text=", "/question").statusCode())
        assertEquals(400, probe.postForm("", "/question").statusCode())
        assertEquals(400, probe.postForm("text=%", "/question").statusCode())
        assertEquals(400, probe.postForm("text=" + "x".repeat(DeliberateApp.MAX_QUESTION + 1), "/question").statusCode())
        assertEquals(405, probe.get("/question").statusCode())
        val root = probe.ask("Q?")
        assertEquals(400, probe.postForm("id=not-a-uuid&mode=STOP", "/override").statusCode())
        assertEquals(400, probe.postForm("id=$root&mode=MAYBE", "/override").statusCode())
        assertEquals(400, probe.postForm("id=$root", "/override").statusCode())
        assertEquals(404, probe.postForm("id=00000000-0000-0000-0000-000000000000&mode=STOP", "/override").statusCode())
        assertEquals(1, probe.graph().questions.size)
    }

    @Test
    fun `serves the built UI, and a hint page when there is none`() {
        val dist = Files.createTempDirectory("deliberate-ui").toFile()
        try {
            File(dist, "index.html").writeText("<!doctype html><title>deliberate ui</title>")
            File(dist, "assets").mkdir()
            File(dist, "assets/app.js").writeText("console.log(1)")
            val secret = File(dist.parentFile, "${dist.name}-secret.txt").apply { writeText("secret") }
            Files.createSymbolicLink(File(dist, "assets/secret.txt").toPath(), secret.toPath())
            val (_, probe) = app(uiDir = dist)
            assertTrue("deliberate ui" in probe.get("/").body())
            val js = probe.get("/assets/app.js")
            assertEquals(200, js.statusCode())
            assertTrue(js.headers().firstValue("Content-Type").get().startsWith("text/javascript"))
            assertEquals(404, probe.get("/assets/missing.js").statusCode())
            assertEquals(404, probe.get("/../${dist.name}-secret.txt").statusCode())
            assertEquals(404, probe.get("/assets/secret.txt").statusCode())
            assertNull(DeliberateApp.resolveStaticFile(dist, "../${dist.name}-secret.txt"))
            assertNull(DeliberateApp.resolveStaticFile(dist, "assets/secret.txt"))
            assertEquals(
                File(dist, "assets/app.js").canonicalFile,
                DeliberateApp.resolveStaticFile(dist, "assets/../assets/app.js"),
            )

            val (_, bare) = app(uiDir = null)
            val hint = bare.get("/")
            assertEquals(200, hint.statusCode())
            assertTrue("npm run build" in hint.body())
        } finally {
            File(dist.parentFile, "${dist.name}-secret.txt").delete()
            dist.deleteRecursively()
        }
    }

    @Test
    fun `command line parses port, proposers and config knobs`() {
        val o = Options(
            arrayOf(
                "--max-depth", "2", "9000", "--max-claims", "20", "--proposers", "codex",
                "--args-per-call", "3", "--max-processes", "2",
                "--max-args-per-side", "5", "--max-args-per-side-child", "2", "--saturation", "0.4", "--min-influence", "0.25",
            ),
        )
        assertEquals(9000, o.port)
        assertEquals(listOf("codex"), o.proposers)
        assertEquals(2, o.maxProcesses)
        assertEquals(2, o.config.maxDepth)
        assertEquals(20, o.config.maxClaims)
        assertEquals(3, o.config.argsPerCall)
        assertEquals(5, o.config.maxArgsPerSide)
        assertEquals(2, o.config.maxArgsPerSideChild)
        assertEquals(0.4, o.config.saturation)
        assertEquals(0.25, o.config.minInfluence)
        assertEquals(DeliberationEngine.Config().maxRounds, o.config.maxRounds)
        assertEquals(DeliberateApp.DEFAULT_PORT, Options(arrayOf("--max-depth", "2")).port.takeIf { System.getenv("PORT") == null } ?: DeliberateApp.DEFAULT_PORT)
        assertEquals(8, Options(emptyArray()).maxProcesses)
        assertEquals(1, Options(emptyArray()).config.argsPerCall)
        assertEquals(6, Options(emptyArray()).config.maxArgsPerSide)
        assertEquals(3, Options(emptyArray()).config.maxArgsPerSideChild)
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--relevance", "0.5")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--bogus", "1")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--max-depth", "x")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--max-processes", "0")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--proposers", "other")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("9000", "9001")) }
    }

    @Test
    fun `stop joins the SSE flusher`() {
        val (running, _) = app()
        running.stop()
        apps.remove(running)
        assertTrue(running.flusherTerminated)
    }

    @Test
    fun `command line parses the durability and semantics flags`() {
        val o = Options(
            arrayOf(
                "--data", "/tmp/deliberate-x", "--semantics", "wlo", "--semantics-layers", "wlo,jnb", "--consensus", "wlo",
                "--wlo-k", "3", "--wlo-p", "1", "--wlo-gamma", "1.1", "--wlo-alpha", "0.9",
            ),
        )
        assertEquals(File("/tmp/deliberate-x"), o.data)
        assertEquals("wlo", o.semantics.headline)
        assertEquals(listOf("dfquad", "wlo", "jnb"), o.semantics.running) // dfquad always runs
        assertEquals(listOf("wlo"), o.semantics.consensus)
        assertEquals(listOf(0.9, 3.0, 1.0, 1.1), o.semantics.wlo.let { listOf(it.alpha, it.k, it.p, it.gamma) })
        val d = Options(emptyArray())
        assertNull(d.data)
        assertEquals(180, d.config.maxClaims)
        assertEquals(SemanticsCatalog.IDS, d.semantics.running)
        assertEquals("dfquad", d.semantics.headline)
        assertEquals(listOf("wlo", "jnb", "woe"), d.semantics.consensus)
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--semantics", "nope")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--semantics-layers", "wlo", "--semantics", "jnb")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--semantics-layers", "wlo", "--consensus", "mlp")) }
    }

    @Test
    fun `graph carries every layer's credence, the consensus and the spread`() {
        val (_, probe) = app(config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0))
        val root = probe.ask("Layers?")
        val g = probe.awaitGraph { g -> g.idle(root) && g.nodes.all { it.credences.keys == SemanticsCatalog.IDS.toSet() } }
        g.nodes.forEach { n ->
            assertEquals(n.credences.getValue("dfquad"), n.credence)
            assertEquals(Consensus.of(n.credences, Consensus.DEFAULT_MEMBERS), n.consensus, 1e-12)
            assertEquals(n.credences.values.min(), n.spreadLow)
            assertEquals(n.credences.values.max(), n.spreadHigh)
        }
    }

    /** Proposer whose round on [blockOn] waits for [gate]; every other call answers like [CountingProposer]. */
    private class GatedProposer(
        override val id: String,
        private val blockOn: (ClaimContext) -> Boolean,
        private val gate: java.util.concurrent.CountDownLatch,
        private val blocked: java.util.concurrent.CountDownLatch,
    ) : Proposer {
        private val inner = CountingProposer(id)
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            if (!blockOn(ctx)) return inner.propose(ctx, side, max)
            blocked.countDown()
            try {
                gate.await(20, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return emptyList()
            }
            return inner.propose(ctx, side, max).map { "resumed $it" }
        }
    }

    @Test
    fun `a deliberation survives a restart on the same data directory and resumes`() {
        val dir = Files.createTempDirectory("deliberate-data").toFile()
        try {
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, maxClaims = 40)
            val semantics = DeliberateApp.SemanticsConfig(layers = listOf("dfquad", "wlo"), consensus = listOf("wlo"))
            // Claude's one pro argument of the root (its number depends on which side's call ran first).
            fun isTarget(text: String?) = text != null && text.startsWith("claude support argument")
            fun gated(gate: java.util.concurrent.CountDownLatch, blocked: java.util.concurrent.CountDownLatch) =
                listOf("claude", "codex").map { GatedProposer(it, { ctx -> ctx.path.size == 1 && isTarget(ctx.claim) }, gate, blocked) }

            val gate1 = java.util.concurrent.CountDownLatch(1)
            val blocked1 = java.util.concurrent.CountDownLatch(1)
            val (first, probe1) = app(config = config, proposers = gated(gate1, blocked1), dataDir = dir, semantics = semantics)
            val root = probe1.ask("Durable?")
            assertTrue(blocked1.await(20, TimeUnit.SECONDS))
            val active = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)
            probe1.awaitGraph { g ->
                val claims = g.nodes.filter { it.kind == "CLAIM" }
                claims.size == 1 + 4 + 3 * 4 && claims.count { it.status in active } == 1
            }
            // Credence propagation settles asynchronously: take the graph once two reads agree.
            var before = probe1.graph()
            awaitUntil("credences settle before the restart") {
                Thread.sleep(100)
                val next = probe1.graph()
                (next == before).also { before = next }
            }
            first.stop() // persists, then interrupts the blocked round; a kill at this instant
            apps.remove(first)

            val gate2 = java.util.concurrent.CountDownLatch(1)
            val blocked2 = java.util.concurrent.CountDownLatch(1)
            val (_, probe2) = app(config = config, proposers = gated(gate2, blocked2), dataDir = dir, semantics = semantics)
            fun key(n: NodeDto) = listOf(n.ref, n.kind, n.root, n.text, n.depth, n.proposer, n.source, n.target, n.polarity,
                n.strength, n.plausibility, n.reach, n.contribution, n.rounds, n.triage, n.override)
            // The resumed claim is blocked again, so the rebuilt graph can be compared as it was.
            assertTrue(blocked2.await(20, TimeUnit.SECONDS))
            val after = probe2.awaitGraph { g ->
                g.nodes.size == before.nodes.size && g.nodes.zip(before.nodes).all { (a, b) ->
                    kotlin.math.abs(a.credence - b.credence) < 1e-9 &&
                        b.credences.all { (id, c) -> kotlin.math.abs(a.credences.getValue(id) - c) < 1e-9 }
                }
            }
            assertEquals(before.questions.map { it.root to it.text }, after.questions.map { it.root to it.text })
            assertEquals(before.nodes.map(::key), after.nodes.map(::key))
            before.nodes.filter { it.kind == "CLAIM" }.zip(after.nodes.filter { it.kind == "CLAIM" }).forEach { (b, a) ->
                if (b.status in active) assertTrue(a.status in active, "$b -> $a") else assertEquals(b.status, a.status)
            }
            // Exploration resumes where it stopped.
            gate2.countDown()
            val resumed = before.nodes.single { it.kind == "CLAIM" && it.depth == 1 && isTarget(it.text) }.ref
            val done = probe2.awaitGraph { g -> g.idle(root) }
            assertEquals(Status.ROUND_LIMIT, done.nodes.single { it.ref == resumed }.status)
            val kids = done.nodes.filter { it.kind == "EDGE" && it.target == resumed }
            assertEquals(4, kids.size)
            assertTrue(kids.all { k -> done.nodes.single { it.ref == k.source }.text!!.startsWith("resumed") })
            gate1.countDown()
        } finally {
            dir.deleteRecursively()
        }
    }
}
