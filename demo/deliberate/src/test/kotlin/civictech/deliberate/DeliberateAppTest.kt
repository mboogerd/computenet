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
        override fun plausibility(question: String, claim: String) = 0.6
        override fun assess(question: String, path: List<String>, child: String, side: Side) =
            Assessment(plausibility = 0.6, strength = 0.7, quality = 0.9, relevance = 1.0)
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = candidates.map { Triage(TriageAction.ADD) }
        override fun saturation(ctx: ClaimContext, side: Side) = 0.0
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
        config: DeliberationEngine.Config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, maxClaims = 40, exploreLinks = false),
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
        assertEquals(4, q.yieldRounds, "only the four non-root rounds contribute yields")
        assertNotNull(q.yieldRecent)
        assertNull(q.yieldEarlier)
        assertNull(q.stoppedBy)
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
        // Model C: over HTTP every node carries its sensitivity, and the question its top-3 cruxes.
        val settled = probe.awaitGraph { gr -> gr.nodes.all { it.sensitivity != null } && gr.questions.single().cruxes.size == 3 }
        assertTrue(settled.questions.single().cruxes.all { c -> c != root && settled.nodes.any { it.ref == c } })
        assertTrue(settled.nodes.single { it.ref == root }.sensitivity!! > 0)
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
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, voiEpsilon = 0.0, workers = 1,
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
    fun `a question is paused and resumed over HTTP (CTL-05)`() {
        val (_, probe) = app(delayMs = 200)
        val root = probe.ask("Pause me?")
        assertEquals(200, probe.postForm("root=$root&paused=true", "/question/pause").statusCode())
        val paused = probe.awaitGraph { g -> g.questions.single().paused }
        assertTrue(paused.questions.single().paused)
        // The round in flight attaches and finishes; its children remain queued.
        val held = probe.awaitGraph { g ->
            val rootNode = g.nodes.single { it.ref == root }
            val children = g.nodes.filter { it.kind == "CLAIM" && it.depth == 1 }
            rootNode.rounds == 1 && children.size == 4 && children.all { it.status == Status.QUEUED }
        }
        assertTrue(held.questions.single().active, "queued work keeps a paused question active")
        assertEquals(200, probe.postForm("root=$root&paused=false", "/question/pause").statusCode())
        val done = probe.awaitGraph { g -> !g.questions.single().paused && g.idle(root) }
        assertTrue(done.nodes.filter { it.kind == "CLAIM" && it.depth == 1 }.all { it.status == Status.ROUND_LIMIT })
        // Bad input.
        assertEquals(405, probe.get("/question/pause").statusCode())
        assertEquals(400, probe.postForm("root=nope&paused=true", "/question/pause").statusCode())
        assertEquals(400, probe.postForm("root=$root&paused=maybe", "/question/pause").statusCode())
        assertEquals(400, probe.postForm("root=$root", "/question/pause").statusCode())
        val claim = done.nodes.first { it.kind == "CLAIM" && it.depth == 1 }.ref
        assertEquals(404, probe.postForm("root=$claim&paused=true", "/question/pause").statusCode(), "a claim is not a question")
        assertEquals(404, probe.postForm("root=00000000-0000-0000-0000-000000000000&paused=true", "/question/pause").statusCode())
    }

    @Test
    fun `command line --start-paused takes no value`() {
        assertEquals(false, Options(emptyArray()).config.startPaused)
        val o = Options(arrayOf("--start-paused", "8205", "--explore-links", "off"))
        assertEquals(true, o.config.startPaused)
        assertEquals(8205, o.port)
        assertEquals(false, o.config.exploreLinks)
        assertTrue("--start-paused" in Options.USAGE)
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
    fun `command line pricing flags (SPEC 12)`() {
        assertEquals(Pricing(), Options(emptyArray()).pricing)
        assertEquals(Pricing(), Options(arrayOf("--codex-model", "gpt-5.6-sol")).pricing)
        assertEquals(null, Options(arrayOf("--codex-model", "gpt-9")).pricing.codex, "rate unknown without flags")
        val flagged = Options(arrayOf("--codex-model", "gpt-9", "--codex-input-rate", "1", "--codex-cached-rate", "0.1", "--codex-output-rate", "2")).pricing
        assertEquals(Rate(1.0, 0.1, 2.0, cacheWriteMultiplier = 1.25), flagged.codex)
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--codex-input-rate", "cheap")) }
        // The Jev rate is a code default, not a flag.
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--jev-input-rate", "0.5")) }
    }

    @Test
    fun `command line parses port, proposers and config knobs`() {
        assertEquals(17, (Options.FLAGS + Options.SWITCHES).size, "the public CLI is deliberately limited to 17 flags")
        assertTrue(Options.FLAGS.intersect(Options.SWITCHES).isEmpty())
        val o = Options(
            arrayOf(
                "--voi-eps", "0.02", "9000", "--max-claims", "20", "--proposers", "codex",
                "--max-processes", "2",
                "--max-args-per-side", "5", "--saturation", "0.4",
            ),
        )
        assertEquals(9000, o.port)
        assertEquals(listOf("codex"), o.proposers)
        assertEquals(2, o.maxProcesses)
        assertEquals(0.02, o.config.voiEpsilon)
        assertEquals(20, o.config.maxClaims)
        assertEquals(5, o.config.maxArgsPerSide)
        assertEquals(0.4, o.config.saturation)
        assertEquals(DeliberationEngine.Config().maxRounds, o.config.maxRounds)
        assertEquals(DeliberateApp.DEFAULT_PORT, Options(arrayOf("--voi-eps", "0.02")).port.takeIf { System.getenv("PORT") == null } ?: DeliberateApp.DEFAULT_PORT)
        assertEquals(8, Options(emptyArray()).maxProcesses)
        assertEquals(6, Options(emptyArray()).config.maxArgsPerSide)
        assertEquals(3, Options(emptyArray()).config.maxArgsPerSideChild)
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--relevance", "0.5")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--bogus", "1")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--voi-eps", "x")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--voi-eps", "-0.1")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--max-processes", "0")) }
        // LINK-04: link exploration is on by default and has an explicit,
        // fail-closed on|off command-line switch.
        assertEquals(true, Options(emptyArray()).config.exploreLinks)
        assertEquals(true, Options(arrayOf("--explore-links", "on")).config.exploreLinks)
        assertEquals(false, Options(arrayOf("--explore-links", "off")).config.exploreLinks)
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--explore-links", "maybe")) }
        // Model C: the value-of-information stop, and the hard cap beside it.
        assertEquals(DeliberationEngine.Config.DEFAULT_VOI_EPSILON, Options(emptyArray()).config.voiEpsilon)
        assertEquals(0.0, Options(arrayOf("--voi-eps", "0")).config.voiEpsilon)
        assertEquals(Int.MAX_VALUE, Options(emptyArray()).config.maxDepth, "the app sets no depth limit")
        assertTrue("--voi-eps" in Options.USAGE && "--max-claims" in Options.USAGE)
        // Knobs kept in code only (SPEC §3 defaults), no longer command-line flags.
        for (gone in listOf(
            "--args-per-call", "--max-args-per-side-child", "--round-decay", "--yield-window", "--yield-ratio",
            "--yield-min-claims", "--semantics-layers", "--consensus", "--wlo-k", "--wlo-p", "--wlo-gamma", "--wlo-alpha",
            "--jev-output-rate",
        )) {
            assertFailsWith<IllegalArgumentException>(gone) { Options(arrayOf(gone, "1")) }
            assertTrue(gone !in Options.USAGE, gone)
        }
        // Model C: the removed stop rules are rejected with a message naming what replaced them.
        for (removed in listOf("--max-depth", "--yield-stop", "--min-influence")) {
            val e = assertFailsWith<IllegalArgumentException>(removed) { Options(arrayOf(removed, "2")) }
            assertTrue(e.message!!.startsWith("$removed was removed") && "--voi-eps" in e.message!!, e.message)
            assertTrue(removed !in Options.USAGE, removed)
            assertTrue(removed !in Options.FLAGS, removed)
        }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--proposers", "other")) }
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("9000", "9001")) }
    }

    @Test
    fun `stop joins the SSE flusher and metadata compactor`() {
        val dir = Files.createTempDirectory("deliberate-stop").toFile()
        try {
            val (running, _) = app(dataDir = dir)
            running.stop()
            apps.remove(running)
            assertTrue(running.flusherTerminated)
            assertTrue(running.compactorTerminated)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `command line parses the durability and semantics flags`() {
        val o = Options(
            arrayOf(
                "--data", "/tmp/deliberate-x", "--semantics", "wlo",
            ),
        )
        assertEquals(File("/tmp/deliberate-x"), o.data)
        assertEquals("wlo", o.semantics.headline)
        assertEquals(SemanticsCatalog.IDS, o.semantics.running) // every layer always runs
        val d = Options(emptyArray())
        assertNull(d.data)
        assertEquals(180, d.config.maxClaims)
        assertEquals(SemanticsCatalog.IDS, d.semantics.running)
        assertEquals(LayerSet.CONSENSUS, d.semantics.headline) // credence shows the consensus by default
        val layers = LayerSet.of(d.semantics.running, d.semantics.consensus, d.semantics.headline)
        val values = List(layers.ids.size) { 0.1 + 0.1 * it }
        assertEquals(layers.consensus(values), layers.headlineOf(values, layers.consensus(values)))
        assertEquals("consensus", Options(arrayOf("--semantics", "consensus")).semantics.headline)
        assertEquals(listOf("wlo", "jnb", "woe"), d.semantics.consensus)
        assertFailsWith<IllegalArgumentException> { Options(arrayOf("--semantics", "nope")) }
    }

    @Test
    fun `graph carries every layer's credence, the consensus and the spread`() {
        val (_, probe) = app(config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0))
        val root = probe.ask("Layers?")
        val g = probe.awaitGraph { g -> g.idle(root) && g.nodes.all { it.credences.keys == SemanticsCatalog.IDS.toSet() } }
        g.nodes.forEach { n ->
            assertEquals(n.consensus, n.credence) // the default headline is the consensus
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
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, maxClaims = 40, exploreLinks = false)
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

    /** Judgments that differ per claim, so every layer, the consensus and the spread carry distinct values. */
    private class VariedJudge : Judge {
        private fun h(text: String, salt: Int) = 0.1 + 0.8 * (((text.hashCode() * 31 + salt) and 0x7fffffff) % 1000) / 1000.0
        override fun plausibility(question: String, claim: String) = h(claim, 1)
        override fun assess(question: String, path: List<String>, child: String, side: Side) =
            Assessment(plausibility = h(child, 1), strength = h(child, 2), quality = 1.0, relevance = 1.0)
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = candidates.map { Triage(TriageAction.ADD) }
        override fun saturation(ctx: ClaimContext, side: Side) = 0.0
    }

    /** The graph once two reads 100 ms apart agree (propagation settles asynchronously). */
    private fun HttpProbe.settled(): GraphDto {
        var before = graph()
        awaitUntil("credences settle") {
            Thread.sleep(100)
            val next = graph()
            (next == before).also { before = next }
        }
        return before
    }

    private fun dirBytes(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun assertSameCredences(before: GraphDto, after: GraphDto) {
        assertEquals(before.nodes.map { it.ref }, after.nodes.map { it.ref })
        before.nodes.zip(after.nodes).forEach { (b, a) ->
            assertEquals(b.credences.keys, a.credences.keys)
            b.credences.forEach { (id, c) -> assertEquals(c, a.credences.getValue(id), 1e-9, "${b.ref} $id") }
            assertEquals(b.credence, a.credence, 1e-9)
            assertEquals(b.consensus, a.consensus, 1e-9)
            assertEquals(b.spreadLow, a.spreadLow, 1e-9)
            assertEquals(b.spreadHigh, a.spreadHigh, 1e-9)
        }
    }

    /**
     * SPEC DUR-01: only inputs are durable, so a restart recomputes every
     * credence from them — and it must land exactly where it was, restart
     * after restart, in every layer and in the consensus.
     */
    @Test
    fun `every layer and the consensus survive repeated restarts unchanged`() {
        val dir = Files.createTempDirectory("deliberate-restarts").toFile()
        try {
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, maxClaims = 40)
            val (first, probe1) = app(config = config, judge = VariedJudge(), dataDir = dir)
            val root = probe1.ask("Restart twice?")
            probe1.awaitGraph { it.idle(root) }
            val before = probe1.settled()
            assertTrue(before.nodes.size > 20, "a tree with depth: ${before.nodes.size} nodes")
            assertTrue(before.nodes.any { it.spreadHigh - it.spreadLow > 1e-3 }, "the layers disagree somewhere")
            first.stop()
            apps.remove(first)
            val sizes = mutableListOf(dirBytes(dir))
            repeat(2) { restart ->
                val (app, probe) = app(config = config, judge = VariedJudge(), dataDir = dir)
                val after = probe.awaitGraph { g ->
                    g.nodes.size == before.nodes.size && g.nodes.zip(before.nodes).all { (a, b) ->
                        b.credences.all { (id, c) -> kotlin.math.abs(a.credences.getValue(id) - c) < 1e-9 }
                    }
                }
                assertSameCredences(before, after)
                assertSameCredences(before, probe.settled())
                app.stop()
                apps.remove(app)
                sizes += dirBytes(dir)
                println("restart ${restart + 1}: data dir ${sizes.last()} bytes")
            }
            assertEquals(sizes.first(), sizes.last(), "restarts of an idle deliberation must not grow the data: $sizes")
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * SPEC DUR-01/02: the data directory holds inputs only — one structure log
     * and a metadata journal compacted to a checkpoint — so a ~60-claim tree
     * costs a few KB per claim, and restarts do not grow it.
     */
    @Test
    fun `a sixty-claim tree stays small and restarts do not grow it`() {
        val dir = Files.createTempDirectory("deliberate-space").toFile()
        try {
            val config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 3, maxClaims = 60,
                maxArgsPerSide = 3, maxArgsPerSideChild = 2, voiEpsilon = 0.0,
            )
            val (first, probe1) = app(config = config, judge = VariedJudge(), dataDir = dir)
            val root = probe1.ask("How big is a deliberation on disk?")
            probe1.awaitGraph { it.idle(root) }
            val before = probe1.settled()
            val claims = before.nodes.count { it.kind == "CLAIM" }
            assertTrue(claims in 55..60, "claims: $claims")
            // While it runs, the journal holds metadata frames and nothing derived.
            val live = File(dir, "host.journal")
            val journal = live.readBytes().decodeToString()
            assertTrue("deliberate.MetaFields" in journal, "the running journal holds metadata frames")
            for (derived in listOf("deliberate.Credence", "deliberate.Influence", "deliberate.Stance", "agora.")) {
                assertTrue(derived !in journal, "the journal holds a derived frame ($derived)")
            }
            // What a kill -9 at this instant leaves behind: the structure log and a journal of
            // uncompacted frames (both written through, the journal synced per frame).
            val crashed = Files.createTempDirectory("deliberate-space-crash").toFile()
            dir.copyRecursively(crashed, overwrite = true)
            // A quiescent checkpoint compacts it while the app keeps running.
            val uncompacted = live.length()
            first.checkpointNow()
            assertTrue(live.length() < uncompacted, "checkpoint compacts: $uncompacted -> ${live.length()}")
            first.compactIfGrown() // nothing grew since: a no-op
            first.stop()
            apps.remove(first)
            val fresh = dirBytes(dir)
            println("deliberate space: running journal $uncompacted B before its checkpoint")
            val sizes = mutableListOf(fresh)
            repeat(3) {
                val (app, probe) = app(config = config, judge = VariedJudge(), dataDir = dir)
                probe.awaitGraph { g -> g.nodes.size == before.nodes.size && g.idle(root) }
                assertSameCredences(before, probe.settled())
                app.stop()
                apps.remove(app)
                sizes += dirBytes(dir)
            }
            println(
                "deliberate space: $claims claims, fresh $fresh B (${fresh / claims} B/claim: " +
                    "graph.jsonl ${File(dir, "graph.jsonl").length()} B, host.journal ${File(dir, "host.journal").length()} B); " +
                    "after restarts $sizes",
            )
            assertTrue(fresh / claims < 4_000, "fresh ${fresh / claims} B/claim")
            assertTrue(sizes.last() <= fresh * 1.05, "3 restarts grew the data dir: $sizes")

            // The crash copy replays its frames into the same trees and credences, then compacts.
            try {
                val (app, probe) = app(config = config, judge = VariedJudge(), dataDir = crashed)
                probe.awaitGraph { g -> g.nodes.size == before.nodes.size && g.idle(root) }
                assertSameCredences(before, probe.settled())
                assertTrue(File(crashed, "host.journal").length() < uncompacted, "boot compacts the replayed journal")
                app.stop()
                apps.remove(app)
            } finally {
                crashed.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
