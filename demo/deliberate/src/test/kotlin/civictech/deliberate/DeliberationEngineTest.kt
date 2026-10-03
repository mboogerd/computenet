package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.durability.FileJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Engine behaviour (SPEC EXP-02..10, CTL-01..04) with a fake judge and fake proposers. */
class DeliberationEngineTest {

    private data class RelationCall(
        val question: String,
        val parent: String,
        val child: String,
        val side: Side,
    )

    private class FakeJudge(
        val plausibility: (String) -> Double = { 0.5 },
        val strength: (child: String) -> Double = { 0.8 },
        val triage: (ClaimContext, List<Candidate>) -> List<Triage> = { _, c -> c.map { Triage(TriageAction.ADD) } },
        val quality: (child: String) -> Double = { 1.0 },
        val saturation: (ClaimContext, Side) -> Double = { _, _ -> 0.0 },
        val relevance: (ClaimContext) -> Double = { 1.0 },
        /** Model B; the default is the interface's: every candidate disputes the claim. */
        val bearing: (String) -> Bearing = { Bearing.DISPUTES_CLAIM },
    ) : Judge {
        val bearingCalls = CopyOnWriteArrayList<Triple<ClaimContext, LinkContext, List<String>>>()
        override fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>): List<Bearing> {
            bearingCalls += Triple(ctx, link, candidates)
            return candidates.map(bearing)
        }
        val relevanceCalls = CopyOnWriteArrayList<String>()
        val plausibilityCalls = CopyOnWriteArrayList<Triple<String, List<String>, String>>()
        val relationCalls = CopyOnWriteArrayList<RelationCall>()
        val triageCalls = CopyOnWriteArrayList<Pair<ClaimContext, List<Candidate>>>()
        override fun plausibility(question: String, claim: String): Double = plausibility(question, emptyList(), claim)
        private fun plausibility(question: String, path: List<String>, claim: String): Double {
            plausibilityCalls += Triple(question, path, claim)
            return plausibility(claim)
        }
        fun relationStrength(question: String, parent: String, child: String, side: Side): Double {
            relationCalls += RelationCall(question, parent, child, side)
            return strength(child)
        }
        fun quality(question: String, parent: String, child: String, side: Side) = quality(child)
        /** The four single judgments, in the order the former default `Judge.assess` asked them. */
        override fun assess(question: String, path: List<String>, child: String, side: Side) = Assessment(
            plausibility = plausibility(question, path, child),
            strength = relationStrength(question, path.last(), child, side),
            quality = quality(question, path.last(), child, side),
            relevance = relevance(ClaimContext(question, path, child, emptyList(), emptyList())),
        )
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>): List<Triage> {
            triageCalls += ctx to candidates
            return triage.invoke(ctx, candidates)
        }
        val saturationCalls = AtomicInteger()
        override fun saturation(ctx: ClaimContext, side: Side): Double {
            saturationCalls.incrementAndGet()
            return saturation.invoke(ctx, side)
        }
        fun relevance(ctx: ClaimContext): Double {
            relevanceCalls += ctx.claim
            return relevance.invoke(ctx)
        }
    }

    /** Emits `<id>-<side>-<n>` (unique per proposer) unless [script] returns something. */
    private class FakeProposer(
        override val id: String,
        val script: (ClaimContext, Side, Int) -> List<String>? = { _, _, _ -> null },
    ) : Proposer {
        private val n = AtomicInteger()
        val contexts = CopyOnWriteArrayList<ClaimContext>()
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            contexts += ctx
            return script(ctx, side, max) ?: List(max) { "$id-${side.name.lowercase()}-${n.incrementAndGet()}" }
        }
    }

    private class RecordingMetaStore(initial: Map<String, Map<String, String>> = emptyMap()) : MetaStore {
        private val inner = InMemoryMetaStore().apply { initial.forEach { (k, v) -> put(k, v) } }
        val writes = AtomicInteger()
        val fieldsWritten = AtomicInteger()
        val deltas = CopyOnWriteArrayList<Pair<String, Map<String, String?>>>()

        override fun load() = inner.load()

        override fun put(key: String, fields: Map<String, String?>) {
            inner.put(key, fields)
            writes.incrementAndGet()
            fieldsWritten.addAndGet(fields.size)
            deltas += key to fields.toMap()
        }
    }

    private val dfquad = LayerSet.of(listOf("dfquad"))

    private val scheduler = VirtualThreadScheduler("deliberate-test")
    private val registry = LocationRegistry()
    private val host = ManagedHost(
        scheduler = scheduler,
        registry = registry,
        attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
    )
    private val service = CredenceGraph(host, registry, dfquad)
    private val engines = mutableListOf<DeliberationEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { it.close() }
        scheduler.shutdown()
    }

    private fun engine(
        judge: Judge = FakeJudge(),
        proposers: List<Proposer> = listOf(FakeProposer("claude"), FakeProposer("codex")),
        // Keep the original two-argument fixture explicit; maxArgsPerSide is above
        // the 4 arguments it produces per side, so ROUND_LIMIT (not the cap) ends it.
        config: DeliberationEngine.Config = DeliberationEngine.Config(
            argsPerCall = 2,
            maxRounds = 1,
            maxDepth = 0,
            maxArgsPerSide = 5,
        ),
        merger: Merger? = null,
        framer: Framer? = null,
    ) = DeliberationEngine(service, judge, proposers, config, merger, framer = framer).also { engines += it }

    private fun DeliberationEngine.idle() = assertTrue(awaitIdle(20.seconds), "engine did not go idle")

    private fun durableGraph(
        host: ManagedHost,
        registry: LocationRegistry,
        journalFile: java.io.File,
        layers: LayerSet = dfquad,
    ): CredenceGraph {
        val recover = journalFile.exists() && journalFile.length() > 0L
        val journal = FileJournal(journalFile)
        val context = ApplyContext(host, topology = journal)
        return CredenceGraph(host, registry, layers, context = context).also { graph ->
            if (recover) {
                context.recover(journal).awaitApplied(60_000)
                graph.rebuildIndex()
            }
        }
    }
    private fun GraphDto.node(ref: CellRef) = nodes.single { it.ref == ref.id.toString() }
    private fun GraphDto.claims() = nodes.filter { it.kind == "CLAIM" }
    private fun GraphDto.edges() = nodes.filter { it.kind == "EDGE" }
    private fun GraphDto.childrenOf(ref: CellRef) = edges().filter { it.target == ref.id.toString() }
    private fun GraphDto.claim(ref: String) = nodes.single { it.ref == ref }

    @Test
    fun `calibrated exploration defaults are stable`() {
        val config = DeliberationEngine.Config()
        assertEquals(1, config.argsPerCall)
        assertEquals(6, config.maxArgsPerSide)
        assertEquals(3, config.maxArgsPerSideChild)
        assertEquals(0.22, config.saturation)
        // Model C: the value-of-information stop replaced the relevance floor, maxDepth and the yield stop.
        assertEquals(0.01, config.voiEpsilon)
        assertEquals(Int.MAX_VALUE, config.maxDepth)
        assertEquals(180, config.maxClaims)
    }

    @Test
    fun `root expands and children attach with polarity, direction and provenance`() {
        val e = engine()
        val root = e.ask("Should cities ban cars?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals("question", r.proposer)
        assertEquals(0, r.depth)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertEquals(1, r.rounds)
        // 2 proposers x 2 sides x argsPerCall 2
        val edges = g.childrenOf(root)
        assertEquals(8, edges.size)
        assertEquals(4, edges.count { it.polarity == "SUPPORT" })
        assertEquals(4, edges.count { it.polarity == "ATTACK" })
        edges.forEach { edge ->
            val child = g.claim(edge.source!!)
            assertEquals(1, child.depth)
            assertEquals(root.id.toString(), child.root)
            assertEquals(root.id.toString(), edge.root)
            assertEquals(Status.DEPTH_LIMIT, child.status)
            // provenance and side both come from the generated text "<proposer>-<side>-n"
            assertTrue(child.text!!.startsWith(child.proposer!!), child.text)
            assertTrue(child.text!!.contains(if (edge.polarity == "SUPPORT") "-support-" else "-attack-"))
        }
        assertEquals(setOf("claude", "codex"), g.claims().filter { it.depth == 1 }.map { it.proposer }.toSet())
        // EXP-10: root rounds are excluded from per-question yield history.
        val q = g.questions.single()
        // Exact-VoI cruxes are checked on their own.
        // Model D: the neutral-prior verdict also follows the cells; with a first impression of ½ it never disagrees.
        assertEquals(
            QuestionDto(root.id.toString(), "Should cities ban cars?", 9, false, cost = CostDto(rounds = 1), firstImpression = 0.5),
            q.copy(cruxes = emptyList(), neutralCredence = null),
        )
        assertNull(q.yieldRecent)
    }

    @Test
    fun `proposers get question, path and existing arguments`() {
        val p = FakeProposer("claude")
        val e = engine(proposers = listOf(p), config = DeliberationEngine.Config(maxRounds = 2, maxDepth = 1, argsPerCall = 1))
        e.ask("Q?")
        e.idle()
        val secondRound = p.contexts.filter { it.claim == "Q?" }.drop(2)
        assertTrue(secondRound.all { it.pros.size == 1 && it.cons.size == 1 && it.path.isEmpty() && it.question == "Q?" })
        val child = p.contexts.first { it.claim != "Q?" }
        assertEquals(listOf("Q?"), child.path)
    }

    @Test
    fun `jev stances land and move the propagated credence`() {
        val judge = FakeJudge(plausibility = { if (it == "Q?") 0.9 else 0.5 }, strength = { 0.7 })
        val e = engine(judge = judge)
        val root = e.ask("Q?")
        e.idle()
        val g0 = e.snapshot()
        assertEquals(0.9, g0.node(root).plausibility)
        assertTrue(g0.edges().all { it.strength == 0.7 })
        assertTrue(g0.claims().all { it.plausibility != null })
        assertTrue(judge.plausibilityCalls.contains(Triple("Q?", emptyList(), "Q?")))
        assertTrue(judge.plausibilityCalls.filter { it.third != "Q?" }.all { it.first == "Q?" && it.second == listOf("Q?") })
        assertEquals(g0.edges().size, judge.relationCalls.size)
        assertTrue(judge.relationCalls.all { call ->
            call.question == "Q?" && call.parent == "Q?" && g0.edges().any { edge ->
                edge.polarity == call.side.name && g0.claim(edge.source!!).text == call.child
            }
        })
        awaitUntil("root credence moves away from 0.5") {
            e.snapshot().node(root).credence.let { it > 0.5 + 1e-6 || it < 0.5 - 1e-6 }
        }
    }

    @Test
    fun `saturation stops one side early and both sides end the expansion`() {
        // Jev calls the pro side saturated from the start; the con side never.
        val proSaturated = FakeJudge(saturation = { _, side -> if (side == Polarity.SUPPORT) 0.9 else 0.1 })
        val e = engine(judge = proSaturated, config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 3, maxDepth = 0, maxArgsPerSide = 100))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertEquals(3, r.rounds)
        assertEquals(0.1, r.conSaturation)
        // round 1: 4 pro / 4 con, pro saturated. Round 2 asks con only (8 con). Pro is now
        // behind, so it is no longer saturated (EXP-04 balance) and round 3 asks both.
        assertEquals(8, g.childrenOf(root).count { it.polarity == "SUPPORT" })
        assertEquals(12, g.childrenOf(root).count { it.polarity == "ATTACK" })

        val both = engine(judge = FakeJudge(saturation = { _, _ -> 0.7 }), config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0))
        val r2 = both.ask("Q2?")
        both.idle()
        val n2 = both.snapshot().node(r2)
        assertEquals(Status.SATURATED, n2.status)
        assertEquals(1, n2.rounds)
    }

    @Test
    fun `a side behind the other is never saturated by Jev alone`() {
        // Claude offers two pros but only one con per call; Jev calls both sides saturated at once.
        val asked = CopyOnWriteArrayList<Side>()
        val lopsided = FakeProposer("claude") { ctx, side, _ ->
            asked += side
            if (side == Polarity.SUPPORT) listOf("pro-${ctx.pros.size}-a", "pro-${ctx.pros.size}-b") else listOf("con-${ctx.cons.size}")
        }
        val e = engine(
            judge = FakeJudge(saturation = { _, _ -> 1.0 }),
            proposers = listOf(lopsided),
            config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 10, maxDepth = 0, maxArgsPerSide = 6),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        // Round 1: 2 pro / 1 con. The con side is behind, so it stays open although Jev
        // judged it saturated (EXP-04); round 2 asks it alone and the sides end level.
        assertEquals(2, g.node(root).rounds)
        assertEquals(mapOf(Polarity.SUPPORT to 1, Polarity.ATTACK to 2), asked.groupingBy { it }.eachCount())
        assertEquals(2, g.childrenOf(root).count { it.polarity == "SUPPORT" })
        assertEquals(2, g.childrenOf(root).count { it.polarity == "ATTACK" })
        assertEquals(Status.SATURATED, g.node(root).status)
    }

    @Test
    fun `duplicates are dropped within a round and against existing siblings`() {
        val a = FakeProposer("claude") { ctx, side, _ ->
            if (side == Polarity.ATTACK) emptyList()
            else if (ctx.pros.isEmpty()) listOf("Cars pollute.", "Streets get safer")
            else listOf("dup of cars", "Noise drops")
        }
        // Codex proposes after Claude (EXP-02), seeing Claude's arguments of the same round.
        val b = FakeProposer("codex") { ctx, side, _ ->
            if (side == Polarity.ATTACK) emptyList()
            else if (ctx.pros.size == 2) listOf("  cars   POLLUTE ") // exact duplicate of claude's
            else listOf("Land is freed")
        }
        val judge = FakeJudge(triage = { _, cands ->
            cands.map { if (it.text.startsWith("dup")) Triage(TriageAction.DUPLICATE, 0) else Triage(TriageAction.ADD) }
        })
        val e = engine(
            judge = judge,
            proposers = listOf(a, b),
            config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 2, maxDepth = 0),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val texts = g.childrenOf(root).map { g.claim(it.source!!).text }.toSet()
        assertEquals(setOf("Cars pollute.", "Streets get safer", "Noise drops", "Land is freed"), texts)
        assertEquals(2, g.node(root).duplicatesDropped)
        assertTrue(judge.triageCalls.any { (ctx, candidates) ->
            ctx.pros.isEmpty() && candidates.map { it.text } == listOf("Cars pollute.", "Streets get safer")
        })
        // The judged duplicate's proposer is recorded on the argument it duplicates.
        val cars = g.childrenOf(root).map { g.claim(it.source!!) }.single { it.text == "Cars pollute." }
        assertEquals(listOf("codex"), cars.alsoProposedBy)
        assertEquals(mapOf("ADD" to 4, "DUPLICATE" to 2), g.node(root).triage)
    }

    @Test
    fun `a question stops when its value of information falls below eps, with budget remaining`() {
        // Every argument's propagated credence is clamped to q = 0.99. With all four siblings in
        // place, each exact expected root movement is below ε = 0.01. The root always runs.
        val judge = FakeJudge(plausibility = { if (it == "Q?") 0.5 else 0.999 })
        val config = DeliberationEngine.Config(maxRounds = 1, argsPerCall = 1, exploreLinks = false)
        val e = engine(judge = judge, config = config)
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(4, kids.size)
        assertTrue(kids.all { it.status == Status.DIMINISHING && it.rounds == 0 && it.plausibility == 0.999 }, kids.toString())
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        // Budget remaining: 5 of 180 claims, no depth limit — the value of information stopped it.
        assertEquals(5, q.claims)
        assertTrue(q.claims < config.maxClaims)
        assertEquals("voi", q.stoppedBy)
        assertTrue(!q.active)
        // The same question with the stop off keeps growing below the root.
        val open = engine(judge = judge, config = config.copy(voiEpsilon = 0.0, maxDepth = 1))
        val root2 = open.ask("Q?")
        open.idle()
        val g2 = open.snapshot()
        assertTrue(g2.childrenOf(root2).map { g2.claim(it.source!!) }.all { it.status == Status.ROUND_LIMIT && it.rounds == 1 })
    }

    @Test
    fun `the hard cost cap still stops a question whose value of information stays high`() {
        // q = ½ everywhere: every argument's exact expected root movement stays far above ε.
        val config = DeliberationEngine.Config(maxRounds = 2, argsPerCall = 1, maxClaims = 7, exploreLinks = false)
        val e = engine(config = config)
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertEquals(7, q.claims)
        assertEquals("budget", q.stoppedBy)
        assertTrue(g.claims().none { it.status == Status.DIMINISHING }, g.claims().toString())
        val capped = g.claims().filter { it.status == Status.BUDGET }
        assertTrue(capped.isNotEmpty())
        assertEquals(Status.BUDGET, g.node(root).status)
        assertNull(g.node(root).error)
        awaitUntil("the capped arguments' exact sways are known") {
            e.snapshot().claims().filter { it.status == Status.BUDGET }.all { it.sensitivity != null }
        }
        for (n in e.snapshot().claims().filter { it.status == Status.BUDGET }) {
            val voi = service.exactValueOf(CellRef(java.util.UUID.fromString(n.ref)), listOf(root))!!.expectedRootChange
            assertTrue(voi >= 2 * config.voiEpsilon, "a capped argument was still worth a round (VoI above ε): $voi")
        }
    }

    @Test
    fun `each node carries its exact signed sway and the question its top-3 exact cruxes`() {
        val plausibility = mapOf("P1" to 0.5, "P2" to 0.9, "C1" to 0.6, "C2" to 0.99)
        val p = FakeProposer("claude") { ctx, side, _ ->
            if (ctx.path.isNotEmpty()) emptyList()
            else if (side == Polarity.SUPPORT) listOf("P1", "P2") else listOf("C1", "C2")
        }
        val judge = FakeJudge(plausibility = { plausibility[it] ?: 0.5 }, strength = { if (it == "P2") 0.6 else 0.8 })
        val e = engine(judge = judge, proposers = listOf(p), config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 1, maxDepth = 1))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertEquals(1.0, g.node(root).sensitivity!!, 1e-9)
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertTrue(kids.filter { it.text!!.startsWith("P") }.all { it.sensitivity!! > 0 }, kids.toString())
        assertTrue(kids.filter { it.text!!.startsWith("C") }.all { it.sensitivity!! < 0 }, kids.toString())
        // Every link carries its edge's exact signed sway too.
        assertTrue(g.edges().all { it.sensitivity != null })
        // Cruxes: the 3 highest exact q-weighted expected root movements, best first.
        fun score(n: NodeDto) = service.exactValueOf(g.ref(n), listOf(root))!!.expectedRootChange
        val expected = (kids + g.edges()).sortedByDescending(::score).take(3).map { it.ref }
        assertEquals(expected, q.cruxes)
        assertTrue(g.node(root).ref !in q.cruxes)
        // The near-certain con is no crux, whatever it could move.
        assertTrue(g.text("C2").ref !in q.cruxes)
    }
    @Test
    fun `claims beyond maxDepth are DEPTH_LIMIT and never explored`() {
        val judge = FakeJudge()
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1, exploreLinks = false))
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(1 + 4 + 16, g.claims().size)
        g.claims().filter { it.depth == 2 }.forEach {
            assertEquals(Status.DEPTH_LIMIT, it.status)
            assertEquals(0, it.rounds)
            assertEquals(1.0, it.relevance) // assessed when attached, like every argument
        }
        assertEquals(4 + 16, judge.relevanceCalls.size) // one assessment per argument, none at dequeue
    }

    @Test
    fun `maxClaims budget stops attaching and marks the rest BUDGET`() {
        val e = engine(config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 3, maxClaims = 5))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(5, g.claims().size)
        // The root explored before the budget ran out; `rounds` still shows that (EXP-06).
        assertEquals(Status.BUDGET, g.node(root).status)
        assertNull(g.node(root).error)
        assertTrue(g.node(root).rounds!! > 0)
        assertTrue(g.claims().filter { it.depth == 1 }.all { it.status == Status.BUDGET && it.rounds == 0 })
        assertEquals(5, g.questions.single().claims)
    }

    @Test
    fun `a legacy ROUND_LIMIT-with-budget-exhausted record restores as BUDGET with no error`() {
        val claim = Claim(
            ref = CellRef(java.util.UUID.randomUUID()), root = CellRef(java.util.UUID.randomUUID()),
            parent = null, side = null, text = "x", depth = 0, proposer = "claude", roundLimit = 3,
        )
        val legacy = EngineRecords.recordFrom(
            mapOf("question" to "\"q\"", "status" to "\"ROUND_LIMIT\"", "rounds" to "1", "error" to "\"budget exhausted\""),
        )
        EngineRecords.apply(claim, legacy)
        assertEquals(Status.BUDGET, claim.status)
        assertNull(claim.error)
        assertEquals(1, claim.rounds)

        // A ROUND_LIMIT record with any other error (or none) is left as written: the mapping
        // is keyed on the (status, error) pair, not on the status alone.
        val claim2 = Claim(
            ref = CellRef(java.util.UUID.randomUUID()), root = CellRef(java.util.UUID.randomUUID()),
            parent = null, side = null, text = "x", depth = 0, proposer = "claude", roundLimit = 3,
        )
        val other = EngineRecords.recordFrom(
            mapOf("question" to "\"q\"", "status" to "\"ROUND_LIMIT\"", "rounds" to "1", "error" to "\"claude: exit 1\""),
        )
        EngineRecords.apply(claim2, other)
        assertEquals(Status.ROUND_LIMIT, claim2.status)
        assertEquals("claude: exit 1", claim2.error)

        val claim3 = Claim(
            ref = CellRef(java.util.UUID.randomUUID()), root = CellRef(java.util.UUID.randomUUID()),
            parent = null, side = null, text = "x", depth = 0, proposer = "claude", roundLimit = 3,
        )
        val noError = EngineRecords.recordFrom(mapOf("question" to "\"q\"", "status" to "\"ROUND_LIMIT\"", "rounds" to "1"))
        EngineRecords.apply(claim3, noError)
        assertEquals(Status.ROUND_LIMIT, claim3.status)
        assertNull(claim3.error)
    }

    @Test
    fun `a throwing proposer does not stop the other`() {
        val bad = FakeProposer("codex") { _, _, _ -> error("codex exploded") }
        val e = engine(proposers = listOf(FakeProposer("claude"), bad))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertTrue(r.error!!.contains("codex exploded"), r.error)
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(4, kids.size)
        assertTrue(kids.all { it.proposer == "claude" })
    }

    @Test
    fun `a fully failed later round does not masquerade as the round limit`() {
        val calls = AtomicInteger()
        val flaky = FakeProposer("claude") { _, _, _ ->
            when (calls.incrementAndGet()) {
                1, 2 -> listOf("first-${calls.get()}")
                3, 4 -> error("temporary outage")
                else -> emptyList()
            }
        }
        val e = engine(
            proposers = listOf(flaky),
            config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0, argsPerCall = 1),
        )
        val root = e.ask("Q?")
        e.idle()
        val node = e.snapshot().node(root)
        assertEquals(Status.ROUND_LIMIT, node.status)
        assertEquals(3, node.rounds)
        assertEquals(6, calls.get())
        assertTrue(node.error!!.contains("temporary outage"), node.error)
    }

    @Test
    fun `a claim whose every call failed is FAILED`() {
        val e = engine(proposers = listOf(
            FakeProposer("claude") { _, _, _ -> error("no claude") },
            FakeProposer("codex") { _, _, _ -> throw java.io.IOException("no codex") },
        ))
        val root = e.ask("Q?")
        e.idle()
        val r = e.snapshot().node(root)
        assertEquals(Status.FAILED, r.status)
        assertNotNull(r.error)
        assertTrue(e.snapshot().edges().isEmpty())
    }

    @Test
    fun `a failing judge never kills the deliberation`() {
        val judge = object : Judge by FakeJudge() {
            override fun plausibility(question: String, claim: String): Double = error("429")
            override fun saturation(ctx: ClaimContext, side: Side): Double = error("529")
            override fun assess(question: String, path: List<String>, child: String, side: Side): Assessment = error("429")
        }
        val e = engine(judge = judge, config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 2, maxDepth = 0, maxArgsPerSide = 100))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertEquals(2, r.rounds)
        assertNull(r.plausibility)
        assertNotNull(r.error)
        assertEquals(16, g.childrenOf(root).size)
        assertTrue(g.edges().all { it.strength == null })
        assertTrue(g.childrenOf(root).all { g.claim(it.source!!).status == Status.DEPTH_LIMIT })
    }

    /** Proposer that blocks the root's first round until released. */
    private class GatedProposer(id: String) : Proposer {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val inner = FakeProposer(id)
        override val id = id
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            if (ctx.path.isEmpty()) {
                entered.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            return inner.propose(ctx, side, max)
        }
    }

    @Test
    fun `STOP mid-round keeps the in-flight results and prevents further rounds`() {
        val p = GatedProposer("claude")
        val e = engine(proposers = listOf(p), config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 3, maxDepth = 0, maxArgsPerSide = 100))
        val root = e.ask("Q?")
        assertTrue(p.entered.await(20, TimeUnit.SECONDS))
        assertEquals(Status.EXPLORING, e.snapshot().node(root).status)
        e.setOverride(root, Override.STOP)
        p.release.countDown()
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals(Status.STOPPED, r.status)
        assertEquals(Override.STOP, r.override)
        assertEquals(1, r.rounds)
        assertEquals(4, g.childrenOf(root).size) // the in-flight round's arguments were attached
        // CTL-03 on a question root: the whole question stopped, so the arguments the
        // in-flight round attached were assessed but queue no round of their own.
        assertTrue(g.claims().filter { it.depth == 1 }.all { it.status == Status.STOPPED && it.plausibility != null })
        assertEquals("human", g.questions.single().stoppedBy)

        // CTL-04: AUTO on the stopped root restarts the question through the normal gates
        e.setOverride(root, Override.AUTO)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(Status.ROUND_LIMIT, g2.node(root).status)
        assertEquals(3, g2.node(root).rounds)
        assertEquals(12, g2.childrenOf(root).size)
        assertTrue(g2.claims().filter { it.depth == 1 }.all { it.status == Status.DEPTH_LIMIT })
        assertNull(g2.questions.single().stoppedBy)
    }

    @Test
    fun `EXPAND reruns a DIMINISHING claim past its gates`() {
        // Changed by model C: the claim was PRUNED by the relevance floor (relevance 0.1); it is now
        // stopped by its value of information (a settled premise, p = 0.999).
        val judge = FakeJudge(plausibility = { if (it == "Q?") 0.5 else 0.999 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1, exploreLinks = false))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kid = g.childrenOf(root).first().source!!
        assertEquals(Status.DIMINISHING, g.claim(kid).status)
        val kidRef = CellRef(java.util.UUID.fromString(kid))
        val relevanceCallsBefore = judge.relevanceCalls.size
        e.setOverride(kidRef, Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(Status.ROUND_LIMIT, g2.claim(kid).status)
        assertEquals(1, g2.claim(kid).rounds)
        assertEquals(4, g2.childrenOf(kidRef).size)
        // gates skipped for the forced claim itself (no judgment at dequeue); only its 4 new
        // arguments are assessed, and those depth-2 children hit DEPTH_LIMIT
        assertEquals(relevanceCallsBefore + 4, judge.relevanceCalls.size)
        assertTrue(g2.childrenOf(kidRef).all { g2.claim(it.source!!).status == Status.DEPTH_LIMIT })
    }

    @Test
    fun `EXPAND on a SATURATED claim runs exactly one extra round with its own per-side allowance`() {
        val config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0, maxArgsPerSide = 3)
        val e = engine(judge = FakeJudge(saturation = { _, _ -> 1.0 }), config = config)
        val root = e.ask("Q?")
        e.idle()
        assertEquals(Status.SATURATED, e.snapshot().node(root).status)
        assertEquals(4, e.snapshot().childrenOf(root).size)
        e.setOverride(root, Override.EXPAND)
        e.idle()
        val g = e.snapshot()
        assertEquals(Status.SATURATED, g.node(root).status)
        assertEquals(2, g.node(root).rounds)
        assertEquals(8, g.childrenOf(root).size)
        assertTrue(g.childrenOf(root).groupingBy { it.polarity }.eachCount().values.all { it > config.maxArgsPerSide })
    }

    @Test
    fun `EXPAND racing an in-flight last round is applied to the next round`() {
        val p = GatedProposer("claude")
        val e = engine(
            judge = FakeJudge(saturation = { _, _ -> 1.0 }),
            proposers = listOf(p),
            config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 1, maxDepth = 0),
        )
        val root = e.ask("Q?")
        assertTrue(p.entered.await(20, TimeUnit.SECONDS))
        e.setOverride(root, Override.EXPAND)
        p.release.countDown()
        e.idle()
        val g = e.snapshot()
        assertEquals(Status.SATURATED, g.node(root).status)
        assertEquals(2, g.node(root).rounds)
        assertEquals(8, g.childrenOf(root).size)
    }

    @Test
    fun `STOP on a finished claim becomes STOPPED and AUTO requeues through normal limits`() {
        val e = engine()
        val root = e.ask("Q?")
        e.idle()
        val children = e.snapshot().childrenOf(root).size
        assertEquals(Status.ROUND_LIMIT, e.snapshot().node(root).status)

        e.setOverride(root, Override.STOP)
        assertEquals(Status.STOPPED, e.snapshot().node(root).status)
        e.setOverride(root, Override.AUTO)
        e.idle()

        val node = e.snapshot().node(root)
        assertEquals(Override.AUTO, node.override)
        assertEquals(Status.ROUND_LIMIT, node.status)
        assertEquals(children, e.snapshot().childrenOf(root).size)
    }

    /** Proposes `claude-<side>-<n>`; a call matching [hold] blocks until [release] (re-armable). */
    private class HoldingProposer : Proposer {
        override val id = "claude"
        val asked = CopyOnWriteArrayList<String>()
        @Volatile var hold: (ClaimContext) -> Boolean = { false }
        @Volatile var entered = CountDownLatch(1)
        @Volatile var release = CountDownLatch(1)
        private val n = AtomicInteger()
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            asked += ctx.claim
            if (hold(ctx)) {
                entered.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            return List(max) { "claude-${side.name.lowercase()}-${n.incrementAndGet()}" }
        }

        fun rearm(hold: (ClaimContext) -> Boolean) {
            entered = CountDownLatch(1)
            release = CountDownLatch(1)
            this.hold = hold
        }
    }

    /** One worker, two rounds per claim, depth 1: after the root's first round there is always queued work. */
    private val stopConfig = DeliberationEngine.Config(
        argsPerCall = 1, maxRounds = 2, maxDepth = 1, voiEpsilon = 0.0, exploreLinks = false,
        maxArgsPerSide = 10, maxArgsPerSideChild = 10, workers = 1,
    )

    /** Anything but the root's first round. */
    private val afterRootRound1: (ClaimContext) -> Boolean = { !(it.claim == "Q?" && it.pros.isEmpty() && it.cons.isEmpty()) }

    private val activeStatuses = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)

    @Test
    fun `STOP on a question root ends the whole question and AUTO restarts it`() {
        val p = HoldingProposer().apply { rearm(afterRootRound1) }
        val e = engine(proposers = listOf(p), config = stopConfig)
        val root = e.ask("Q?")
        assertTrue(p.entered.await(20, TimeUnit.SECONDS))
        val running = e.snapshot()
        val inFlight = running.claims().single { it.activity == "exploring" }
        val childrenBefore = running.childrenOf(running.ref(inFlight)).size
        val waiting = running.claims().filter { it.ref != inFlight.ref && it.status in activeStatuses }
        assertTrue(waiting.any { it.status == Status.QUEUED }, "queued work exists: ${running.claims()}")

        e.setOverride(root, Override.STOP)
        val stopping = e.snapshot()
        // Queued work is cancelled at once; only the round in flight is still active.
        waiting.forEach { assertEquals(Status.STOPPED, stopping.claim(it.ref).status, it.text) }
        assertEquals(listOf(inFlight.ref), stopping.claims().filter { it.status in activeStatuses }.map { it.ref })
        assertEquals("human", stopping.questions.single().stoppedBy)
        p.release.countDown()
        e.idle()

        val g = e.snapshot()
        // The in-flight round finished: its arguments are attached and assessed …
        val done = g.claim(inFlight.ref)
        assertEquals(inFlight.rounds!! + 1, done.rounds)
        val attached = g.childrenOf(g.ref(done)).map { g.claim(it.source!!) }
        assertEquals(childrenBefore + 2, attached.size)
        assertTrue(attached.all { it.plausibility != null }, "assessed: $attached")
        // … but no further round started for any claim of the question.
        assertEquals(4, p.asked.size, "the root's first round and the round in flight only: ${p.asked}")
        // Nothing is left QUEUED or EXPLORING-waiting: every claim of the question ended STOPPED.
        assertTrue(g.claims().all { it.status == Status.STOPPED }, "${g.claims().map { it.text to it.status }}")
        val q = g.questions.single()
        assertTrue(!q.active)
        assertEquals("human", q.stoppedBy)

        // AUTO on the root restarts the question: everything the stop cancelled re-enters the normal gates.
        p.rearm { true }
        e.setOverride(root, Override.AUTO)
        assertTrue(p.entered.await(20, TimeUnit.SECONDS))
        val restarted = e.snapshot().questions.single()
        assertTrue(restarted.active)
        assertNull(restarted.stoppedBy)
        p.release.countDown()
        e.idle()
        val g2 = e.snapshot()
        assertTrue(!g2.questions.single().active)
        assertNull(g2.questions.single().stoppedBy)
        assertEquals(Override.AUTO, g2.node(root).override)
        assertEquals(Status.ROUND_LIMIT, g2.node(root).status)
        assertEquals(2, g2.node(root).rounds)
        assertTrue(g2.claims().none { it.status == Status.STOPPED }, "${g2.claims().map { it.text to it.status }}")
        assertTrue(g2.claims().filter { it.depth == 1 }.all { it.status == Status.ROUND_LIMIT && it.rounds == 2 })
        assertTrue(g2.claims().filter { it.depth == 2 }.all { it.status == Status.DEPTH_LIMIT })
    }

    @Test
    fun `STOP on a non-root claim stops only that claim`() {
        val p = HoldingProposer().apply { rearm { it.claim != "Q?" } }
        val e = engine(proposers = listOf(p), config = stopConfig.copy(maxRounds = 1))
        val root = e.ask("Q?")
        assertTrue(p.entered.await(20, TimeUnit.SECONDS))
        val running = e.snapshot()
        val inFlight = running.claims().single { it.activity == "exploring" }
        val other = running.claims().single { it.depth == 1 && it.ref != inFlight.ref }
        assertEquals(Status.QUEUED, other.status)

        e.setOverride(running.ref(inFlight), Override.STOP)
        val stopping = e.snapshot()
        assertEquals(Status.QUEUED, stopping.claim(other.ref).status, "a sibling is not cancelled")
        assertNull(stopping.questions.single().stoppedBy)
        p.release.countDown()
        e.idle()

        val g = e.snapshot()
        assertEquals(Status.STOPPED, g.claim(inFlight.ref).status)
        assertEquals(1, g.claim(inFlight.ref).rounds)
        val kids = g.childrenOf(g.ref(inFlight)).map { g.claim(it.source!!) }
        assertEquals(2, kids.size)
        assertTrue(kids.all { it.status == Status.DEPTH_LIMIT }, "descendants are not affected: $kids")
        assertEquals(Status.ROUND_LIMIT, g.claim(other.ref).status)
        assertEquals(1, g.claim(other.ref).rounds)
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        assertNull(g.questions.single().stoppedBy)
        assertEquals(6, p.asked.size)
    }

    @Test
    fun `a stopped question restores stopped after a restart, nothing re-queued, and AUTO restarts it`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-question-stop").toFile()
        val journal = java.io.File(dir, "host.journal")
        try {
            val store = InMemoryMetaStore()
            val p = HoldingProposer().apply { rearm(afterRootRound1) }
            val e1 = DeliberationEngine(durableGraph(host, registry, journal), FakeJudge(), listOf(p), stopConfig, store = store)
                .also { engines += it }
            val root = e1.ask("Q?")
            assertTrue(p.entered.await(20, TimeUnit.SECONDS))
            e1.setOverride(root, Override.STOP)
            p.release.countDown()
            e1.idle()
            val before = e1.snapshot()
            assertEquals("human", before.questions.single().stoppedBy)
            e1.close()
            // As if the process had been killed mid-round: one claim recorded EXPLORING.
            val interrupted = before.claims().first { it.status == Status.STOPPED && it.ref != root.id.toString() }
            store.put("c:${interrupted.ref}", mapOf("status" to "\"EXPLORING\""))

            val counter = FakeProposer("claude")
            val e2 = restart(journal, store, stopConfig, proposers = listOf(counter))
            e2.idle()
            val after = e2.snapshot()
            assertEquals(emptyList(), counter.contexts.map { it.claim }, "a restored stopped question runs no round")
            assertEquals(before.claims().associate { it.ref to it.status }, after.claims().associate { it.ref to it.status })
            val q = after.questions.single()
            assertTrue(!q.active)
            assertEquals("human", q.stoppedBy)

            e2.setOverride(root, Override.AUTO)
            e2.idle()
            val resumed = e2.snapshot()
            assertTrue(counter.contexts.isNotEmpty())
            assertNull(resumed.questions.single().stoppedBy)
            assertTrue(resumed.claims().none { it.status == Status.STOPPED || it.status in activeStatuses })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `status changes broadcast the specified state machine`() {
        val seen = CopyOnWriteArrayList<Status>()
        lateinit var e: DeliberationEngine
        e = DeliberationEngine(
            service,
            FakeJudge(),
            listOf(FakeProposer("claude")),
            DeliberationEngine.Config(maxRounds = 1, maxDepth = 0),
        ) {
            e.snapshot().claims().firstOrNull { it.depth == 0 }?.status?.let(seen::add)
        }.also { engines += it }
        e.ask("Q?")
        e.idle()
        val ordered = seen.distinct()
        assertTrue(ordered.indexOf(Status.QUEUED) < ordered.indexOf(Status.JUDGING), ordered.toString())
        assertTrue(ordered.indexOf(Status.JUDGING) < ordered.indexOf(Status.EXPLORING), ordered.toString())
        assertTrue(ordered.indexOf(Status.EXPLORING) < ordered.indexOf(Status.ROUND_LIMIT), ordered.toString())
    }

    @Test
    fun `snapshot is safe while many workers expand`() {
        val e = engine(config = DeliberationEngine.Config(maxRounds = 2, maxDepth = 2, maxClaims = 60))
        val failure = AtomicReference<Throwable?>(null)
        val stop = AtomicBoolean(false)
        val reader = Thread {
            try {
                while (!stop.get()) e.snapshot()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }.apply { start() }
        e.ask("Q1?")
        e.ask("Q2?")
        e.idle()
        stop.set(true)
        reader.join(5_000)
        failure.get()?.let { throw AssertionError("snapshot raced expansion: $it", it) }
        val g = e.snapshot()
        assertEquals(2, g.questions.size)
        g.questions.forEach { assertEquals(60, it.claims); assertEquals(false, it.active) }
        assertEquals(120, g.claims().size)
        assertEquals(118, g.edges().size)
    }

    // ------------------------------------------------------------ iteration 2: reach, influence, cap

    private fun GraphDto.ref(n: NodeDto) = CellRef(java.util.UUID.fromString(n.ref))

    @Test
    fun `reach is the product of edge strengths from the root`() {
        val judge = FakeJudge(strength = { if (it.contains("support")) 0.8 else 0.5 })
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1, voiEpsilon = 0.0),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(1.0, g.node(root).reach)
        val d1 = g.claims().filter { it.depth == 1 }
        assertEquals(2, d1.size)
        d1.forEach { k ->
            val s = if (k.text!!.contains("support")) 0.8 else 0.5
            assertEquals(s, k.reach!!, 1e-9)
            g.childrenOf(g.ref(k)).map { g.claim(it.source!!) }.also { assertEquals(2, it.size) }.forEach { kk ->
                val ss = if (kk.text!!.contains("support")) 0.8 else 0.5
                assertEquals(s * ss, kk.reach!!, 1e-9)
            }
        }
    }

    @Test
    fun `a failed assessment falls back to the fallback strength and records the error`() {
        // JevJudge asks all four judgments in one request, so they fail together.
        val judge = object : Judge by FakeJudge() {
            override fun assess(question: String, path: List<String>, child: String, side: Side): Assessment = error("jev down")
        }
        val e = engine(judge = judge, proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(2, kids.size)
        kids.forEach {
            assertEquals(DeliberationEngine.Config.FALLBACK_STRENGTH, it.reach)
            // relevance and quality fall back to 1: contribution = reach alone (shown; exact VoI orders work)
            assertEquals(DeliberationEngine.Config.FALLBACK_STRENGTH, it.contribution)
            assertNull(it.relevance)
            assertNull(it.quality)
            assertEquals(Status.ROUND_LIMIT, it.status)
            assertEquals(0.5, it.plausibility) // judged on its own when the claim was started
        }
        assertTrue(g.claims().filter { it.depth == 2 }.all { it.error!!.contains("jev assess") })
        assertTrue(g.childrenOf(root).all { it.strength == null })
    }

    @Test
    fun `EXPAND bypasses the value-of-information gate for that claim only`() {
        // Every argument's premise is all but settled (the layer clamps 0.999 to q = 0.99):
        // none is worth a round at this fixture's explicit threshold.
        val judge = FakeJudge(plausibility = { if (it == "Q?") 0.5 else 0.999 })
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(
                maxRounds = 1,
                maxDepth = 3,
                argsPerCall = 1,
                voiEpsilon = 0.02,
                exploreLinks = false,
            ),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val d1 = g.claims().filter { it.depth == 1 }
        assertEquals(2, d1.size)
        assertTrue(d1.all { it.status == Status.DIMINISHING && it.rounds == 0 }, d1.toString())
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)

        // CTL-02: EXPAND bypasses the gate for that claim only.
        val stopped = g.ref(d1.first())
        val relevanceBefore = judge.relevanceCalls.size
        e.setOverride(stopped, Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(1, g2.node(stopped).rounds)
        val grandkids = g2.childrenOf(stopped).map { g2.claim(it.source!!) }
        assertEquals(2, grandkids.size)
        // its children face the gate again
        assertTrue(grandkids.all { it.status == Status.DIMINISHING && it.rounds == 0 })
        assertEquals(relevanceBefore + 2, judge.relevanceCalls.size)
    }
    @Test
    fun `a side at maxArgsPerSide is saturated without asking Jev and never overfilled in a round`() {
        val judge = FakeJudge(saturation = { _, _ -> 0.0 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 3, maxDepth = 0, maxArgsPerSide = 3))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        // round 1 offers 2 proposers × 2 = 4 per side; only 3 fit
        assertEquals(Status.SATURATED, r.status)
        assertEquals(1, r.rounds)
        assertEquals(3, g.childrenOf(root).count { it.polarity == "SUPPORT" })
        assertEquals(3, g.childrenOf(root).count { it.polarity == "ATTACK" })
        assertEquals(0, judge.saturationCalls.get())
    }

    @Test
    fun `proposers are asked only for the room left under the cap`() {
        val p = FakeProposer("claude")
        val asked = CopyOnWriteArrayList<Int>()
        val counting = object : Proposer by p {
            override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
                if (side == Polarity.SUPPORT) asked += max
                return p.propose(ctx, side, max)
            }
        }
        val e = engine(proposers = listOf(counting), config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 5, maxDepth = 0, maxArgsPerSide = 3))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(listOf(2, 1), asked.toList())
        assertEquals(3, g.childrenOf(root).count { it.polarity == "SUPPORT" })
        assertEquals(Status.SATURATED, g.node(root).status)
        assertEquals(2, g.node(root).rounds)
    }

    @Test
    fun `Jev saturation at the threshold saturates a side below the cap`() {
        val judge = FakeJudge(saturation = { ctx, side -> if (side == Polarity.SUPPORT && ctx.pros.size >= 2) 0.6 else 0.59 })
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0, argsPerCall = 1, maxArgsPerSide = 10, saturation = 0.6),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(2, g.childrenOf(root).count { it.polarity == "SUPPORT" })
        assertEquals(3, g.childrenOf(root).count { it.polarity == "ATTACK" })
        assertEquals(0.6, g.node(root).proSaturation)
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
    }

    @Test
    fun `the hard cap runs before the value-of-information gate so capped claims read BUDGET`() {
        // Changed by model C: this was "the influence gate runs before the budget" (PRUNED first);
        // the relevance floor is gone, and the hard cap now wins over a low value of information.
        val judge = FakeJudge(plausibility = { if (it.startsWith("codex")) 0.999 else 0.5 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 3, argsPerCall = 1, maxClaims = 5))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(4, kids.size)
        assertTrue(kids.all { it.status == Status.BUDGET }, kids.toString())
    }
    @Test
    fun `exploration follows exact q-weighted value of information`() {
        // Four pros of the root, all of strength 0.8. Under DF-QuAD (root base b = ½, no cons)
        // the exact secant R1-R0 = (1 − b)·s·∏_{j≠i}(1 − s·q_j): the likelier a sibling,
        // the less room it leaves the others. Their credences make the three orders all differ.
        val p = mapOf("s1" to 0.5, "s2" to 0.7, "s3" to 0.85, "s4" to 0.95)
        val firstCalls = CopyOnWriteArrayList<String>()
        val proposer = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.path.isEmpty() -> if (side == Polarity.SUPPORT) p.keys.toList() else emptyList()
                else -> {
                    if (side == Polarity.SUPPORT) firstCalls += ctx.claim
                    emptyList()
                }
            }
        }
        val judge = FakeJudge(
            plausibility = { p[it] ?: 0.5 },
            strength = { 0.8 },
            saturation = { _, _ -> 0.0 },
        )
        val e = engine(
            judge = judge,
            proposers = listOf(proposer),
            config = DeliberationEngine.Config(
                argsPerCall = 4, maxRounds = 1, maxDepth = 1, voiEpsilon = 0.0,
                maxArgsPerSide = 4, workers = 1, exploreLinks = false,
            ),
        )
        e.ask("Q?")
        e.idle()
        fun sway(i: String) = 0.5 * 0.8 * p.filterKeys { it != i }.values.fold(1.0) { acc, qj -> acc * (1 - 0.8 * qj) }
        fun voi(i: String) = sway(i) * 4 * p.getValue(i) * (1 - p.getValue(i))
        val expected = p.keys.sortedByDescending(::voi)
        assertEquals(listOf("s2", "s1", "s3", "s4"), expected, "the fixture's own arithmetic")
        assertTrue(p.keys.sortedByDescending { 4 * p.getValue(it) * (1 - p.getValue(it)) } != expected, "not uncertainty alone")
        assertTrue(p.keys.sortedByDescending(::sway) != expected, "not sway alone")
        assertEquals(expected, firstCalls.toList())
        // The wire-compatible DTO field carries the exact signed secant.
        val g = e.snapshot()
        for ((t, _) in p) assertEquals(sway(t), g.text(t).sensitivity!!, 1e-4, t)
    }
    @Test
    fun `a strong child runs before its still-exploring parent's decayed next round`() {
        val calls = CopyOnWriteArrayList<String>()
        val p = FakeProposer("claude") { ctx, side, _ ->
            if (side == Polarity.ATTACK) emptyList()
            else {
                calls += ctx.claim
                if (ctx.claim == "Q?" && ctx.pros.isEmpty()) listOf("strong child") else emptyList()
            }
        }
        val e = engine(
            // Root base 0.2, so the exact root secant is (1 − 0.2)·0.9 = 0.72 at q = ½ — above
            // the root's decayed second round (1 × 0.5).
            judge = FakeJudge(
                plausibility = { if (it == "Q?") 0.2 else 0.5 },
                strength = { if (it == "strong child") 0.9 else 0.8 },
                saturation = { _, _ -> 0.0 },
            ),
            proposers = listOf(p),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, voiEpsilon = 0.0,
                maxArgsPerSide = 10, maxArgsPerSideChild = 10, roundDecay = 0.5, workers = 1,
            ),
        )
        e.ask("Q?")
        e.idle()

        // The child (0.72) joins the queue after assessment and beats the
        // still-exploring root's second round (1.0 × 0.5).
        assertEquals(listOf("Q?", "strong child", "Q?"), calls.take(3))
    }

    @Test
    fun `triage adds, merges duplicates, drops, moves sides, replaces and refines`() {
        // Proposers alternate (EXP-02): each turn sees the arguments attached by the turn before.
        val claude = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.path.isNotEmpty() -> emptyList()
                ctx.pros.isEmpty() -> if (side == Polarity.SUPPORT) listOf("P1") else listOf("Off topic")
                else -> if (side == Polarity.SUPPORT) listOf("P1 better") else listOf("C1")
            }
        }
        val codex = FakeProposer("codex") { ctx, side, _ ->
            when {
                ctx.path.isNotEmpty() -> emptyList()
                ctx.pros == listOf("P1") -> if (side == Polarity.SUPPORT) listOf("P1 again") else listOf("Really pro")
                else -> if (side == Polarity.SUPPORT) listOf("P1 example") else emptyList()
            }
        }
        // Round 1: claude's turn triages P1, Off topic; codex's turn (existing pros [P1]) P1 again, Really pro.
        // Round 2: claude's turn (pros [P1, Really pro]) P1 better, C1; codex's turn P1 example (target 0 = P1 better).
        val verdicts = mapOf(
            "P1" to Triage(TriageAction.ADD),
            "P1 again" to Triage(TriageAction.DUPLICATE, 0),
            "Off topic" to Triage(TriageAction.DROP),
            "Really pro" to Triage(TriageAction.OTHER_SIDE),
            "P1 example" to Triage(TriageAction.REFINE, 0),
            "P1 better" to Triage(TriageAction.REPLACE, 0),
            "C1" to Triage(TriageAction.ADD),
        )
        val judge = FakeJudge(
            strength = { 0.4 },
            triage = { _, cands -> cands.map { verdicts.getValue(it.text) } },
            saturation = { _, _ -> 0.0 },
        )
        val e = engine(
            judge = judge,
            proposers = listOf(claude, codex),
            // Root round two (priority 0.5) reaches P1 before the queued 0.4
            // child, keeping it eligible for REPLACE/REFINE.
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, voiEpsilon = 0.0, workers = 1,
            ),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).associate { g.claim(it.source!!).text!! to it.polarity!! }
        assertEquals(mapOf("P1 better" to "SUPPORT", "Really pro" to "SUPPORT", "C1" to "ATTACK"), kids)
        // REPLACE swapped the unexplored P1's text; DUPLICATE had recorded codex on it.
        val p1 = g.claims().single { it.text == "P1 better" }
        assertEquals("claude", p1.proposer)
        assertEquals(listOf("codex"), p1.alsoProposedBy)
        assertTrue(judge.relationCalls.any { it.child == "P1 better" }, "a replaced argument is re-assessed")
        // Model B: REFINE recorded the example as evidence on P1 — no child claim.
        assertEquals(listOf("P1 example"), p1.evidence)
        assertTrue(g.claims().none { it.text == "P1 example" }, "a REFINE creates no claim")
        assertTrue(g.childrenOf(g.ref(p1)).isEmpty(), "a REFINE attaches nothing under its target")
        assertEquals(
            mapOf("ADD" to 2, "DUPLICATE" to 1, "REPLACE" to 1, "REFINE" to 1, "OTHER_SIDE" to 1, "DROP" to 1),
            g.node(root).triage,
        )
        assertEquals(1, g.node(root).duplicatesDropped)
        assertEquals(4, judge.triageCalls.size) // one request per proposer turn
    }

    /** Round 1: claude "A", codex "B" (B MERGEs into candidate 0 = A); ATTACK side empty. */
    private fun mergeRun(merger: Merger?): Pair<FakeJudge, GraphDto> {
        val claude = FakeProposer("claude") { _, side, _ -> if (side == Polarity.SUPPORT) listOf("A") else emptyList() }
        val codex = FakeProposer("codex") { _, side, _ -> if (side == Polarity.SUPPORT) listOf("B") else emptyList() }
        val judge = FakeJudge(triage = { _, cands -> cands.map { if (it.text == "B") Triage(TriageAction.MERGE, 0) else Triage(TriageAction.ADD) } })
        val e = engine(
            judge = judge, proposers = listOf(claude, codex), merger = merger,
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0),
        )
        e.ask("Q?")
        e.idle()
        return judge to e.snapshot()
    }

    @Test
    fun `MERGE rewrites the unexplored target as one argument and re-judges it`() {
        val asked = CopyOnWriteArrayList<List<String>>()
        val (judge, g) = mergeRun { claim, side, a, b ->
            asked += listOf(claim, side.name, a, b)
            "A and B."
        }
        assertEquals(listOf(listOf("Q?", "SUPPORT", "A", "B")), asked.toList())
        val node = g.claims().single { it.depth == 1 }
        assertEquals("A and B.", node.text)
        assertEquals(true, node.merged)
        assertEquals("claude", node.proposer)
        assertEquals(listOf("codex"), node.alsoProposedBy)
        assertEquals(mapOf("ADD" to 1, "MERGE" to 1), g.claims().single { it.depth == 0 }.triage)
        // plausibility and link strength are judged again on the merged text
        assertTrue(judge.plausibilityCalls.any { it.third == "A and B." })
        assertTrue(judge.relationCalls.any { it.child == "A and B." })
    }

    @Test
    fun `a failed merge falls back to DUPLICATE and records the error`() {
        val (_, g) = mergeRun { _, _, _, _ -> error("claude timed out") }
        val node = g.claims().single { it.depth == 1 }
        assertEquals("A", node.text)
        assertNull(node.merged)
        assertEquals(listOf("codex"), node.alsoProposedBy)
        val root = g.claims().single { it.depth == 0 }
        assertEquals(mapOf("ADD" to 1, "DUPLICATE" to 1), root.triage)
        assertEquals(1, root.duplicatesDropped)
        assertTrue(root.error!!.contains("merge") && root.error!!.contains("claude timed out"), root.error)
    }

    @Test
    fun `failed reassessment clears judgments made about replaced wording`() {
        val p = FakeProposer("claude") { ctx, side, _ ->
            if (side == Polarity.ATTACK || ctx.path.isNotEmpty()) emptyList()
            else if (ctx.pros.isEmpty()) listOf("A") else listOf("A, better")
        }
        val base = FakeJudge(
            plausibility = { if (it == "A") 0.9 else 0.6 },
            strength = { 0.4 },
            quality = { 0.7 },
            relevance = { 0.8 },
            triage = { _, cands -> cands.map { if (it.text == "A, better") Triage(TriageAction.REPLACE, 0) else Triage(TriageAction.ADD) } },
        )
        val judge = object : Judge by base {
            override fun assess(question: String, path: List<String>, child: String, side: Side): Assessment {
                if (child == "A, better") error("assessment unavailable")
                return base.assess(question, path, child, side)
            }
        }
        val e = engine(
            judge = judge,
            proposers = listOf(p),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, voiEpsilon = 0.0, workers = 1,
            ),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val rewritten = g.childrenOf(root).map { g.claim(it.source!!) }.single()
        assertEquals("A, better", rewritten.text)
        assertEquals(0.6, rewritten.plausibility) // retried when its expansion starts
        assertNull(rewritten.relevance)
        assertNull(rewritten.quality)
        assertEquals(DeliberationEngine.Config.FALLBACK_STRENGTH, rewritten.reach)
        assertEquals(DeliberationEngine.Config.FALLBACK_STRENGTH, rewritten.contribution)
        assertNull(g.edges().single { it.source == rewritten.ref }.strength)
        assertTrue(rewritten.error!!.contains("assessment unavailable"), rewritten.error)
    }

    @Test
    fun `REPLACE and MERGE cannot rewrite an argument that has children`() {
        for (action in listOf(TriageAction.REPLACE, TriageAction.MERGE)) {
            val original = "A-$action"
            val better = "$original, better"
            val p = FakeProposer("claude") { ctx, side, _ ->
                if (side == Polarity.ATTACK) emptyList()
                else when {
                    ctx.path.isEmpty() && ctx.pros.isEmpty() -> listOf(original)
                    ctx.path.isEmpty() -> listOf(better)
                    ctx.claim == original && ctx.pros.isEmpty() -> listOf("evidence-$action")
                    else -> emptyList()
                }
            }
            val judge = FakeJudge(triage = { _, cands ->
                cands.map { if (it.text == better) Triage(action, 0) else Triage(TriageAction.ADD) }
            })
            val merges = AtomicInteger()
            val e = engine(
                judge = judge,
                proposers = listOf(p),
                config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 2),
                merger = { _, _, _, _ -> merges.incrementAndGet(); "never" },
            )
            val root = e.ask("Q-$action?")
            e.idle()
            val before = e.snapshot()
            val target = before.claims().single { it.text == original }
            assertTrue(before.childrenOf(CellRef(java.util.UUID.fromString(target.ref))).isNotEmpty())

            // CTL-02 re-runs the root, but a target with children is immutable.
            e.setOverride(root, Override.EXPAND)
            e.idle()
            val g = e.snapshot()
            assertEquals(listOf(original), g.childrenOf(root).map { g.claim(it.source!!).text })
            assertEquals(mapOf("ADD" to 1, "DUPLICATE" to 1), g.node(root).triage)
            assertEquals(0, merges.get())
        }
    }

    @Test
    fun `claims below the root use the smaller per-side cap`() {
        val judge = FakeJudge(saturation = { _, _ -> 0.0 })
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(argsPerCall = 5, maxRounds = 1, maxDepth = 1, maxArgsPerSide = 5, maxArgsPerSideChild = 2),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(5, g.childrenOf(root).count { it.polarity == "SUPPORT" })
        g.claims().filter { it.depth == 1 }.forEach { k ->
            val ref = CellRef(java.util.UUID.fromString(k.ref))
            assertEquals(2, g.childrenOf(ref).count { it.polarity == "SUPPORT" })
            assertEquals(2, g.childrenOf(ref).count { it.polarity == "ATTACK" })
            assertEquals(Status.SATURATED, k.status)
        }
    }

    @Test
    fun `STOP on a question root cancels its already queued child`() {
        val q1Entered = CountDownLatch(1)
        val allowQ1 = CountDownLatch(1)
        val q2Entered = CountDownLatch(1)
        val allowQ2 = CountDownLatch(1)
        val childExplored = CountDownLatch(1)
        val p = FakeProposer("claude") { ctx, side, _ ->
            if (side == Polarity.ATTACK) emptyList()
            else when (ctx.claim) {
                "Q1?" -> if (ctx.pros.isEmpty()) {
                    q1Entered.countDown()
                    allowQ1.await(10, TimeUnit.SECONDS)
                    listOf("Q1 child")
                } else emptyList()
                "Q2?" -> {
                    q2Entered.countDown()
                    allowQ2.await(10, TimeUnit.SECONDS)
                    emptyList()
                }
                "Q1 child" -> emptyList<String>().also { childExplored.countDown() }
                else -> emptyList()
            }
        }
        val e = engine(
            judge = FakeJudge(strength = { 0.9 }),
            proposers = listOf(p),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, voiEpsilon = 0.0,
                maxArgsPerSide = 10, maxArgsPerSideChild = 10, workers = 1,
            ),
        )
        val q1 = e.ask("Q1?")
        assertTrue(q1Entered.await(10, TimeUnit.SECONDS))
        e.ask("Q2?")
        allowQ1.countDown()
        // Q2 (priority 1) occupies the only worker while Q1's assessed child
        // (0.9) and Q1's next round (0.5) wait in the queue.
        assertTrue(q2Entered.await(10, TimeUnit.SECONDS))
        val queued = e.snapshot()
        val child = queued.childrenOf(q1).map { queued.claim(it.source!!) }.single()
        assertEquals(Status.QUEUED, child.status)
        assertEquals(Status.EXPLORING, queued.node(q1).status)

        e.setOverride(q1, Override.STOP)
        val stopped = e.snapshot()
        assertEquals(Status.STOPPED, stopped.node(q1).status)
        // CTL-03 on a question root ends the whole question: its queued child is cancelled at once.
        assertEquals(Status.STOPPED, stopped.claim(child.ref).status)
        allowQ2.countDown()
        e.idle()
        val g = e.snapshot()
        assertEquals(1, g.node(q1).rounds)
        assertEquals(Status.STOPPED, g.claim(child.ref).status)
        assertEquals(1L, childExplored.count, "the cancelled child ran no round")
        val q = g.questions.single { it.root == q1.id.toString() }
        assertTrue(!q.active)
        assertEquals("human", q.stoppedBy)
        assertNull(g.questions.single { it.root != q1.id.toString() }.stoppedBy, "the other question is unaffected")
    }

    // ------------------------------------------------------------ iteration 4

    @Test
    fun `proposers take turns and the second sees what the first contributed`() {
        val order = CopyOnWriteArrayList<String>()
        val claude = FakeProposer("claude") { _, side, _ -> order += "claude"; listOf("claude-${side.name}") }
        val codex = FakeProposer("codex") { _, side, _ -> order += "codex"; listOf("codex-${side.name}") }
        val judge = FakeJudge()
        val e = engine(
            judge = judge,
            proposers = listOf(claude, codex),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0),
        )
        e.ask("Q?")
        e.idle()
        assertEquals(listOf("claude", "claude", "codex", "codex"), order.toList())
        // Codex's context holds Claude's arguments of the same round (after triage).
        assertTrue(codex.contexts.all { it.pros == listOf("claude-SUPPORT") && it.cons == listOf("claude-ATTACK") }, codex.contexts.toString())
        // One triage per turn, but saturation only once per side, after both turns.
        assertEquals(2, judge.triageCalls.size)
        assertEquals(listOf("claude-SUPPORT", "claude-ATTACK"), judge.triageCalls[1].first.let { it.pros + it.cons })
        assertEquals(2, judge.saturationCalls.get())
    }

    @Test
    fun `EXPAND explores a claim even when the tree is at maxClaims, BUDGET claims included`() {
        val e = engine(config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 3, maxClaims = 5))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(5, g.claims().size)
        val budget = g.claims().first { it.status == Status.BUDGET }
        val ref = g.ref(budget)

        e.setOverride(ref, Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(1, g2.node(ref).rounds)
        assertEquals(Status.ROUND_LIMIT, g2.node(ref).status)
        // Its own allowance: 2 proposers x 1 per side, within the child cap of 3 per side.
        assertEquals(4, g2.childrenOf(ref).size)
        assertEquals(9, g2.claims().size)
        // The new arguments meet the exhausted budget like any other claim.
        assertTrue(g2.childrenOf(ref).all { g2.claim(it.source!!).status == Status.BUDGET })

        // The root (BUDGET) also gets its forced round.
        e.setOverride(root, Override.EXPAND)
        e.idle()
        val g3 = e.snapshot()
        assertEquals(2, g3.node(root).rounds)
        assertEquals(8, g3.childrenOf(root).size)
    }

    @Test
    fun `an UNDERCUT attacks the argument's edge and is explored like a claim`() {
        val claude = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.claim == "Q?" && side == Polarity.SUPPORT -> listOf("P")
                ctx.claim == "U" && side == Polarity.SUPPORT -> listOf("U holds")
                else -> emptyList()
            }
        }
        val codex = FakeProposer("codex") { ctx, side, _ ->
            if (ctx.claim == "Q?" && side == Polarity.ATTACK) listOf("U") else emptyList()
        }
        val judge = FakeJudge(
            plausibility = { if (it == "U") 0.9 else 0.5 },
            strength = { if (it == "U") 0.6 else 0.8 },
            triage = { _, cands -> cands.map { if (it.text == "U") Triage(TriageAction.UNDERCUT, 0) else Triage(TriageAction.ADD) } },
        )
        val e = engine(
            judge = judge,
            proposers = listOf(claude, codex),
            // U sits one level below P's link, which is at P's depth (1).
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 2, voiEpsilon = 0.0),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val p = g.claims().single { it.text == "P" }
        val u = g.claims().single { it.text == "U" }
        val pEdge = g.edges().single { it.source == p.ref }
        val uEdge = g.edges().single { it.source == u.ref }
        // U attacks P's edge, not the root: the root holds one argument.
        assertEquals(pEdge.ref, uEdge.target)
        assertEquals("ATTACK", uEdge.polarity)
        assertEquals(pEdge.ref, u.undercuts)
        assertEquals(pEdge.ref, u.onLink)
        assertEquals(listOf(p.ref), g.childrenOf(root).map { it.source })
        // P's link is at P's depth; its arguments one below.
        assertEquals(1, pEdge.depth)
        assertEquals(2, u.depth)
        assertEquals(mapOf("ADD" to 1, "UNDERCUT" to 1), g.node(root).triage)
        // reach(root) x strength(U's edge) x strength(P's edge)
        assertEquals(0.6 * 0.8, u.reach!!, 1e-9)
        // Judged against the link it denies, not against the root claim.
        val call = judge.relationCalls.single { it.child == "U" }
        assertEquals("“P” is a reason for “Q?”", call.parent)
        // Explored like any claim: its own argument attaches to it, with the root question as its path.
        assertEquals(Status.ROUND_LIMIT, u.status)
        assertEquals(listOf("U holds"), g.childrenOf(g.ref(u)).map { g.claim(it.source!!).text })
        assertEquals(listOf("Q?", "“P” is a reason for “Q?”"), claude.contexts.first { it.claim == "U" }.path)
        // The undercut lowers the link's credence below its own strength stance.
        awaitUntil("the undercut edge loses credence") { e.snapshot().nodes.single { it.ref == pEdge.ref }.credence < 0.8 - 1e-6 }
    }

    // ------------------------------------------------------------ model B: premise vs bearing, evidence

    /**
     * Root "Q?" gets one pro "P" (plausibility [pPlausibility]); P's round gets the cons [cons].
     * Links are off, so only the triage routing is exercised.
     */
    private fun bearingRun(pPlausibility: Double, cons: List<String>, bearing: (String) -> Bearing): Pair<FakeJudge, GraphDto> {
        val claude = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.claim == "Q?" && side == Polarity.SUPPORT -> listOf("P")
                ctx.claim == "P" && side == Polarity.ATTACK -> cons
                else -> emptyList()
            }
        }
        val judge = FakeJudge(plausibility = { if (it == "P") pPlausibility else 0.5 }, bearing = bearing)
        val e = engine(
            judge = judge,
            proposers = listOf(claude),
            config = DeliberationEngine.Config(argsPerCall = 3, maxRounds = 1, maxDepth = 1, voiEpsilon = 0.0, exploreLinks = false),
        )
        e.ask("Q?")
        e.idle()
        return judge to e.snapshot()
    }

    @Test
    fun `a con against a well-believed claim is routed by what it denies - claim, bearing, or neither`() {
        val (judge, g) = bearingRun(0.9, listOf("C dispute", "C bearing", "C neither")) {
            when (it) {
                "C bearing" -> Bearing.DENIES_BEARING
                "C neither" -> Bearing.NEITHER
                else -> Bearing.DISPUTES_CLAIM
            }
        }
        val p = g.text("P")
        val pEdge = g.linkOf(p)
        // One request for the turn's ADD-bound cons, told P's connection to its parent. The root has no link: never asked.
        val (ctx, link, asked) = judge.bearingCalls.single()
        assertEquals("P", ctx.claim)
        assertEquals(LinkContext("P", "Q?", Polarity.SUPPORT), link)
        assertEquals(listOf("C dispute", "C bearing", "C neither"), asked)
        // "disputes claim": the existing con-child path.
        assertEquals(listOf("C dispute"), g.childrenOf(g.ref(p)).map { g.claim(it.source!!).text })
        assertEquals("ATTACK", g.childrenOf(g.ref(p)).single().polarity)
        // "denies bearing": an UNDERCUT of P's own link, not a con child of P.
        val u = g.text("C bearing")
        assertEquals(pEdge.ref, u.undercuts)
        assertEquals(pEdge.ref, g.linkOf(u).target)
        assertEquals("ATTACK", g.linkOf(u).polarity)
        // "neither": the reject path (DROP) — no claim.
        assertTrue(g.claims().none { it.text == "C neither" })
        assertEquals(mapOf("ADD" to 1, "UNDERCUT" to 1, "DROP" to 1), p.triage)
        // 4p(1-p): P's contribution is its strength (0.8) damped by 4 × 0.9 × 0.1; its link is
        // built from the undamped 0.8 (its bearing is still open): 0.8 × 4 × 0.8 × 0.2.
        assertEquals(0.8 * 4 * 0.9 * 0.1, p.contribution!!, 1e-9)
        assertEquals(0.8 * 4 * 0.8 * 0.2, pEdge.contribution!!, 1e-9)
    }

    @Test
    fun `below the bearing threshold a con against a claim is not asked and stays a con child`() {
        val (judge, g) = bearingRun(ExplorationPolicy.BEARING_PLAUSIBILITY - 0.01, listOf("C")) { Bearing.DENIES_BEARING }
        assertTrue(judge.bearingCalls.isEmpty(), "asked below the threshold: ${judge.bearingCalls}")
        val p = g.text("P")
        assertEquals(listOf("C"), g.childrenOf(g.ref(p)).map { g.claim(it.source!!).text })
        assertNull(g.text("C").undercuts)
        assertEquals(mapOf("ADD" to 1), p.triage)
    }

    @Test
    fun `at the bearing threshold a con against a claim is asked and a denied bearing undercuts its link`() {
        val (judge, g) = bearingRun(ExplorationPolicy.BEARING_PLAUSIBILITY, listOf("C")) { Bearing.DENIES_BEARING }
        assertEquals(listOf("C"), judge.bearingCalls.single().third)
        val p = g.text("P")
        assertTrue(g.childrenOf(g.ref(p)).isEmpty())
        assertEquals(g.linkOf(p).ref, g.text("C").undercuts)
        assertEquals(mapOf("UNDERCUT" to 1), p.triage)
    }

    @Test
    fun `a failed bearing call keeps the triage verdict and records the error`() {
        val claude = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.claim == "Q?" && side == Polarity.SUPPORT -> listOf("P")
                ctx.claim == "P" && side == Polarity.ATTACK -> listOf("C")
                else -> emptyList()
            }
        }
        val judge = object : Judge by FakeJudge(plausibility = { if (it == "P") 0.95 else 0.5 }) {
            override fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>): List<Bearing> =
                throw IllegalStateException("jev down")
        }
        val e = engine(
            judge = judge, proposers = listOf(claude),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, voiEpsilon = 0.0, exploreLinks = false),
        )
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val p = g.text("P")
        assertEquals(listOf("C"), g.childrenOf(g.ref(p)).map { g.claim(it.source!!).text })
        assertTrue(p.error!!.startsWith("jev bearing:"), p.error)
    }

    @Test
    fun `REFINE evidence survives a restart, and a record written before evidence existed still restores`() {
        // Durability: `evidence` is an optional ClaimRecord field with an empty default, not
        // encoded when empty — a pre-change record is exactly a record without it.
        val old = mapOf("question" to "\"q\"", "status" to "\"ROUND_LIMIT\"", "rounds" to "1", "alsoProposedBy" to "[\"codex\"]")
        val decoded = EngineRecords.recordFrom(old)
        assertEquals(emptyList(), decoded.evidence)
        assertEquals(old, EngineRecords.fieldsOf(decoded), "an empty evidence list is not stored")
        val withEvidence = decoded.copy(evidence = listOf("E1", "E2"))
        assertEquals(withEvidence, EngineRecords.recordFrom(EngineRecords.fieldsOf(withEvidence)))

        val dir = java.nio.file.Files.createTempDirectory("deliberate-evidence").toFile()
        try {
            val log = java.io.File(dir, "host.journal")
            val store = InMemoryMetaStore()
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0)
            val claude = FakeProposer("claude") { _, side, _ -> if (side == Polarity.SUPPORT) listOf("P") else emptyList() }
            val codex = FakeProposer("codex") { _, side, _ -> if (side == Polarity.SUPPORT) listOf("P example") else emptyList() }
            val judge = FakeJudge(triage = { _, cands -> cands.map { if (it.text == "P example") Triage(TriageAction.REFINE, 0) else Triage(TriageAction.ADD) } })
            val e1 = DeliberationEngine(durableGraph(host, registry, log), judge, listOf(claude, codex), config, store = store)
                .also { engines += it }
            e1.ask("Q?")
            e1.idle()
            e1.close()
            assertEquals(listOf("P example"), e1.snapshot().text("P").evidence)
            val after = restart(log, store, config).also { it.idle() }.snapshot()
            assertEquals(listOf("P example"), after.text("P").evidence)
            assertTrue(after.claims().none { it.text == "P example" })
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ SPEC §3 "Links as claims"

    private fun GraphDto.linkOf(arg: NodeDto) = edges().single { it.source == arg.ref }
    private fun GraphDto.text(t: String) = claims().single { it.text == t }

    /** Root: one pro per entry of [rootPros], one con per entry of [rootCons]; links: [onLink] (argument, side) → texts. */
    private fun linkProposer(
        rootPros: List<String> = emptyList(),
        rootCons: List<String> = emptyList(),
        onLink: (LinkContext, Side, Int) -> List<String> = { _, _, _ -> emptyList() },
    ) = FakeProposer("claude") { ctx, side, _ ->
        when {
            ctx.link != null -> onLink(ctx.link!!, side, 0)
            ctx.path.isEmpty() -> if (side == Polarity.SUPPORT) rootPros else rootCons
            else -> emptyList()
        }
    }

    @Test
    fun `a link is queued by its edge's exact value and explored like a claim`() {
        val p = linkProposer(rootPros = listOf("Strong"), rootCons = listOf("Even")) { link, side, _ ->
            if (link.argument == "Even") listOf(if (side == Polarity.SUPPORT) "Even holds" else "Even fails") else emptyList()
        }
        val judge = FakeJudge(strength = { when (it) { "Strong" -> 1.0; "Even" -> 0.5; else -> 0.8 } })
        val e = engine(judge = judge, proposers = listOf(p), config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val strong = g.linkOf(g.text("Strong"))
        val even = g.linkOf(g.text("Even"))
        // The legacy contribution display is contribution(argument) × 4s(1-s). Exact VoI reaches
        // the same decision here: the strength-1 link cannot move, while the even link can.
        assertEquals(0.0, strong.contribution!!, 1e-9)
        assertEquals(Status.DIMINISHING, strong.status)
        assertTrue(even.sensitivity != null && even.sensitivity!! < 0, "the con's link pulls the root down: $even")
        assertEquals(0, strong.rounds)
        assertEquals(0.5, even.contribution!!, 1e-9)
        assertEquals(0.5, even.reach!!, 1e-9)
        assertEquals(Status.ROUND_LIMIT, even.status)
        assertEquals(1, even.rounds)
        // The link is a claim: its text is built from its ends, at its argument's depth.
        assertEquals("“Even” is a reason against “Q?”", even.text)
        assertEquals(1, even.depth)
        assertEquals(Override.AUTO, even.override)
        // Proposers were asked about the connection, with the link's ends.
        val ctx = p.contexts.first { it.link != null }
        assertEquals(LinkContext("Even", "Q?", Polarity.ATTACK), ctx.link)
        assertEquals(even.text, ctx.claim)
        assertEquals(listOf("Q?"), ctx.path)
        // Its arguments attach to the edge node, one level below it, and are judged against the link.
        val holds = g.text("Even holds")
        val fails = g.text("Even fails")
        assertEquals(even.ref, g.linkOf(holds).target)
        assertEquals("SUPPORT", g.linkOf(holds).polarity)
        assertEquals(even.ref, holds.onLink)
        assertNull(holds.undercuts)
        assertEquals(even.ref, fails.onLink)
        assertEquals(even.ref, fails.undercuts)
        assertEquals(2, holds.depth)
        assertEquals(Status.DEPTH_LIMIT, holds.status)
        assertEquals(even.text, judge.relationCalls.single { it.child == "Even holds" }.parent)
        assertEquals(mapOf("ADD" to 2), even.triage)
        assertEquals(0.0, even.proSaturation)
        assertEquals(0.0, even.conSaturation)
        assertEquals(0, even.duplicatesDropped)
        assertNull(even.error)
        val arguedRefs = g.edges().mapNotNull { it.target }.toSet()
        val argued = g.nodes.filter { it.ref in arguedRefs }
        assertTrue(argued.any { it.kind == "CLAIM" } && argued.any { it.kind == "EDGE" })
        argued.forEach { n ->
            assertEquals(n.credences.keys, n.argumentsFirstCredences.keys, "${n.kind} ${n.ref} arguments-first layers")
            assertTrue(n.argumentsFirstCredences.values.all { it in 0.0..1.0 })
            assertTrue(n.argumentsFirstConsensus in 0.0..1.0)
        }
        // Link rounds are non-root work and therefore participate in the
        // question's diminishing-return yield series (EXP-10).
        assertEquals(3, g.questions.single().yieldRounds)
        // A link's arguments are claims of the question; links themselves are not.
        assertEquals(5, g.questions.single().claims)
        assertTrue(g.childrenOf(root).none { it.source == holds.ref })
    }

    @Test
    fun `a counter-argument found in link triage attacks the parent claim instead of the link`() {
        val p = linkProposer(rootPros = listOf("The path is wet.")) { link, side, _ ->
            if (link.argument == "The path is wet." && side == Polarity.ATTACK) {
                listOf("The forecast predicted dry weather.")
            } else {
                emptyList()
            }
        }
        val judge = FakeJudge(
            strength = { if (it == "The path is wet.") 0.5 else 0.8 },
            triage = { ctx, cands ->
                cands.map {
                    if (ctx.link != null && it.text == "The forecast predicted dry weather.") {
                        Triage(TriageAction.OTHER_SIDE)
                    } else {
                        Triage(TriageAction.ADD)
                    }
                }
            },
        )
        val e = engine(
            judge = judge, proposers = listOf(p),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, workers = 1),
        )
        val root = e.ask("It rained.")
        e.idle()
        val g = e.snapshot()
        val argument = g.text("The path is wet.")
        val link = g.linkOf(argument)
        val counter = g.text("The forecast predicted dry weather.")
        val counterEdge = g.linkOf(counter)

        assertEquals(root.id.toString(), counterEdge.target)
        assertEquals("ATTACK", counterEdge.polarity)
        assertNull(counter.onLink)
        assertNull(counter.undercuts)
        assertTrue(g.childrenOf(g.ref(link)).isEmpty(), "counter-argument must not become a link argument")
        assertEquals(mapOf("OTHER_SIDE" to 1), link.triage)
        assertEquals(setOf(argument.ref, counter.ref), g.childrenOf(root).mapNotNull { it.source }.toSet())
    }

    @Test
    fun `maxClaims also bounds automatic link exploration although links do not consume the budget`() {
        val p = linkProposer(rootPros = listOf("P")) { _, side, _ ->
            if (side == Polarity.SUPPORT) listOf("L") else emptyList()
        }
        val e = engine(
            judge = FakeJudge(strength = { 0.5 }), proposers = listOf(p),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 3, maxDepth = 8, maxClaims = 3,
                voiEpsilon = 0.0, workers = 1,
            ),
        )
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        val links = g.edges()

        // Every non-root claim creates exactly one link, so the claim ceiling
        // is also a structural ceiling on links. Disabling the value-of-information
        // stop still cannot produce an unbounded chain.
        assertEquals(3, q.claims)
        assertEquals(q.claims - 1, links.size)
        assertEquals(1, links.sumOf { it.rounds ?: 0 })
        assertTrue(links.any { it.status == Status.BUDGET }, "$links")
        assertTrue(g.nodes.none { it.status in setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING) })
    }

    @Test
    fun `an in-flight link exposes edge activity and keeps its question active and queued for cost`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inner = linkProposer(rootPros = listOf("P"))
        val gated = object : Proposer by inner {
            override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
                if (ctx.link?.argument == "P" && side == Polarity.SUPPORT) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
                return inner.propose(ctx, side, max)
            }
        }
        val e = engine(
            judge = FakeJudge(strength = { if (it == "P") 0.5 else 0.8 }), proposers = listOf(gated),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, workers = 1),
        )
        val root = e.ask("Q?")
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            val g = e.snapshot()
            val link = g.linkOf(g.text("P"))
            assertEquals("exploring", link.activity)
            assertEquals(true, g.questions.single { it.root == root.id.toString() }.active)
            assertEquals(1, g.questions.single { it.root == root.id.toString() }.cost.queued)
        } finally {
            release.countDown()
        }
        e.idle()
        assertNull(e.snapshot().linkOf(e.snapshot().text("P")).activity)
    }

    @Test
    fun `EXPAND on a link explores it and its arguments move the edge credence and hence the parent`() {
        val p = linkProposer(rootPros = listOf("P")) { link, side, _ ->
            if (link.argument == "P" && side == Polarity.ATTACK) listOf("P fails") else emptyList()
        }
        val judge = FakeJudge(
            plausibility = { if (it == "P fails") 0.9 else 0.5 },
            strength = { if (it == "P fails") 0.9 else 0.8 },
        )
        val e = engine(
            judge = judge, proposers = listOf(p),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = false),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val link = g.linkOf(g.text("P"))
        assertEquals(Status.PRUNED, link.status)
        awaitUntil("the root settles on its one argument") { e.snapshot().node(root).credence > 0.5 + 1e-6 }
        val rootBefore = e.snapshot().node(root).credence
        val edgeBefore = e.snapshot().nodes.single { it.ref == link.ref }.credence

        e.setOverride(g.ref(link), Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        val expanded = g2.nodes.single { it.ref == link.ref }
        assertEquals(Override.EXPAND, expanded.override)
        assertEquals(1, expanded.rounds)
        assertEquals(listOf("P fails"), g2.childrenOf(g2.ref(link)).map { g2.claim(it.source!!).text })
        // The undercutter lowers the link's credence, and with it the argument's pull on the root.
        awaitUntil("the undercut lowers the edge and the root") {
            val s = e.snapshot()
            s.nodes.single { it.ref == link.ref }.credence < edgeBefore - 1e-6 && s.node(root).credence < rootBefore - 1e-6
        }
    }

    @Test
    fun `triage on a link compares its candidates with the link's own arguments`() {
        val calls = AtomicInteger()
        val p = linkProposer(rootPros = listOf("P")) { _, side, _ ->
            if (side == Polarity.SUPPORT) listOf(if (calls.getAndIncrement() == 0) "L1" else "L1 again") else emptyList()
        }
        val judge = FakeJudge(
            strength = { if (it == "P") 0.5 else 0.8 },
            triage = { ctx, cands -> cands.map { if (ctx.link != null && ctx.pros.isNotEmpty()) Triage(TriageAction.DUPLICATE, 0) else Triage(TriageAction.ADD) } },
        )
        val e = engine(judge = judge, proposers = listOf(p), config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 2, maxDepth = 1))
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val link = g.linkOf(g.text("P"))
        val linkTriage = judge.triageCalls.filter { it.first.link != null }.map { it.first }
        assertEquals(2, linkTriage.size)
        assertTrue(linkTriage.all { it.claim == link.text }, "$linkTriage")
        assertEquals(listOf("L1"), linkTriage[1].pros)
        assertEquals(mapOf("ADD" to 1, "DUPLICATE" to 1), link.triage)
        assertEquals(1, link.duplicatesDropped)
        assertEquals(2, link.rounds)
        assertEquals(listOf("L1"), g.childrenOf(g.ref(link)).map { g.claim(it.source!!).text })
    }

    @Test
    fun `STOP on a queued link prevents its rounds and AUTO requeues it through the gates`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val inner = linkProposer(rootPros = listOf("P")) { _, side, _ -> if (side == Polarity.SUPPORT) listOf("L") else emptyList() }
        val gated = object : Proposer by inner {
            override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
                if (ctx.claim == "P") {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
                return inner.propose(ctx, side, max)
            }
        }
        // One worker: P (0.6) runs before its link (0.6 x 0.96) and blocks while the link waits in the queue.
        val e = engine(
            judge = FakeJudge(strength = { if (it == "P") 0.6 else 0.8 }), proposers = listOf(gated),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, workers = 1),
        )
        e.ask("Q?")
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            val link = e.snapshot().let { it.linkOf(it.text("P")) }
            assertEquals(Status.QUEUED, link.status)
            e.setOverride(e.snapshot().ref(link), Override.STOP)
            assertEquals(Status.STOPPED, e.snapshot().nodes.single { it.ref == link.ref }.status)
        } finally {
            release.countDown()
        }
        e.idle()
        val g = e.snapshot()
        val link = g.linkOf(g.text("P"))
        assertEquals(Status.STOPPED, link.status)
        assertEquals(0, link.rounds)
        assertTrue(g.childrenOf(g.ref(link)).isEmpty())
        assertTrue(inner.contexts.none { it.link != null })

        e.setOverride(g.ref(link), Override.AUTO)
        e.idle()
        val g2 = e.snapshot()
        val again = g2.nodes.single { it.ref == link.ref }
        assertEquals(Status.ROUND_LIMIT, again.status)
        assertEquals(listOf("L"), g2.childrenOf(g2.ref(link)).map { g2.claim(it.source!!).text })
    }

    @Test
    fun `link metadata survives a restart`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-link-restore").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        val judge = FakeJudge(strength = { when (it) { "Strong" -> 1.0; "Even" -> 0.5; else -> 0.8 } })
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1)
        fun proposer() = linkProposer(rootPros = listOf("Strong"), rootCons = listOf("Even")) { link, side, _ ->
            if (link.argument == "Even") listOf(if (side == Polarity.SUPPORT) "Even holds" else "Even fails") else emptyList()
        }
        val e1 = DeliberationEngine(durableGraph(host, registry, log), judge, listOf(proposer()), config, store = store)
            .also { engines += it }
        e1.ask("Q?")
        e1.idle()
        val strong = e1.snapshot().let { it.linkOf(it.text("Strong")) }
        e1.setOverride(e1.snapshot().ref(strong), Override.STOP)
        e1.idle()
        val before = e1.snapshot()
        e1.close()
        assertTrue(store.load().getValue("l:${strong.ref}").containsKey("override"))

        val scheduler2 = VirtualThreadScheduler("deliberate-link-restore-2")
        try {
            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val e2 = DeliberationEngine(durableGraph(host2, registry2, log), judge, listOf(proposer()), config, store = store)
                .also { engines += it }
            e2.idle()
            val after = e2.snapshot()
            fun key(n: NodeDto) = listOf(n.ref, n.kind, n.text, n.depth, n.status, n.override, n.rounds, n.triage,
                n.reach, n.contribution, n.duplicatesDropped, n.onLink, n.undercuts, n.strength)
            assertEquals(before.nodes.map(::key), after.nodes.map(::key))
            val restored = after.nodes.single { it.ref == strong.ref }
            assertEquals(Status.STOPPED, restored.status)
            assertEquals(Override.STOP, restored.override)
            assertEquals(1, after.linkOf(after.text("Even")).rounds)
        } finally {
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a rewritten argument re-gates its unexplored link with the new wording`() {
        val judge = FakeJudge(
            strength = { if (it == "Clearer P") 0.5 else 1.0 },
            // Below 1, so the root's second round (priority 1) runs before "P" does.
            quality = { if (it == "P") 0.9 else 1.0 },
            triage = { ctx, cands -> cands.map { if (ctx.path.isEmpty() && it.text == "Clearer P") Triage(TriageAction.REPLACE, 0) else Triage(TriageAction.ADD) } },
        )
        val first = AtomicBoolean(true)
        val p = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.path.isEmpty() && side == Polarity.SUPPORT -> listOf(if (first.getAndSet(false)) "P" else "Clearer P")
                else -> emptyList()
            }
        }
        // Two rounds of the root, one worker: "P" (link pruned, strength 1) is rewritten before anything below runs.
        val e = engine(
            judge = judge, proposers = listOf(p),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 2, maxDepth = 1, roundDecay = 1.0, workers = 1),
        )
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val arg = g.text("Clearer P")
        val link = g.linkOf(arg)
        assertEquals("“Clearer P” is a reason for “Q?”", link.text)
        assertEquals(0.5, link.contribution!!, 1e-9)
        assertEquals(Status.ROUND_LIMIT, link.status)
    }

    @Test
    fun `every semantics layer propagates the same stances and the consensus is their log-odds mean`() {
        val ids = listOf("wlo", "jnb", "woe", "mlp")
        val layers = CredenceGraph(host, registry, LayerSet.of(listOf("dfquad") + ids, listOf("wlo", "jnb", "woe")))
        val e = DeliberationEngine(
            layers,
            FakeJudge(plausibility = { if (it == "Q?") 0.7 else 0.9 }, strength = { if (it.contains("support")) 0.9 else 0.3 }),
            listOf(FakeProposer("claude")),
            DeliberationEngine.Config(argsPerCall = 2, maxRounds = 1, maxDepth = 0),
        ).also { engines += it }
        val root = e.ask("Q?")
        e.idle()
        awaitUntil("every layer has propagated the root") {
            e.snapshot().node(root).credences.let { c -> c.keys == setOf("dfquad") + ids && c.values.all { it != 0.5 } }
        }
        awaitUntil("the layers have settled on the root") {
            val n = e.snapshot().node(root)
            // mlp is additive: 2 x (0.9 x 0.9) support vs 2 x (0.3 x 0.9) attack from base 0.7
            val z = kotlin.math.ln(0.7 / 0.3) + 2 * 0.81 - 2 * 0.27
            kotlin.math.abs(n.credences.getValue("mlp") - 1 / (1 + kotlin.math.exp(-z))) < 1e-9
        }
        val n = e.snapshot().node(root)
        assertEquals(n.credences.getValue("dfquad"), n.credence)
        assertEquals(Consensus.of(n.credences, listOf("wlo", "jnb", "woe")), n.consensus, 1e-12)
        assertEquals(n.credences.values.min(), n.spreadLow)
        assertEquals(n.credences.values.max(), n.spreadHigh)
        assertTrue(n.spreadHigh > n.spreadLow, "the semantics disagree on this tree: $n")
        e.snapshot().nodes.forEach { assertEquals(setOf("dfquad") + ids, it.credences.keys) }
    }

    @Test
    fun `a new engine over the same structure and metadata rebuilds the trees and resumes active claims`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-restore").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        val gate = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        // The restarted engine resumes claude-SUPPORT immediately; `resume` holds that round
        // until the restored snapshot has been compared, or a fast worker bumps `rounds` first.
        val resume = CountDownLatch(1)
        fun proposer(id: String, gated: Boolean) = FakeProposer(id) { ctx, side, _ ->
            when {
                ctx.path.isEmpty() -> listOf("$id-${side.name}")
                ctx.claim == "claude-SUPPORT" && gated -> {
                    blocked.countDown()
                    gate.await(20, TimeUnit.SECONDS)
                    emptyList()
                }
                ctx.claim == "claude-SUPPORT" -> {
                    resume.await(20, TimeUnit.SECONDS)
                    emptyList()
                }
                else -> emptyList()
            }
        }
        // Links off: a running link round between persistNow() and the `before` snapshot would race
        // the comparison (CI 36341232982); link restore has its own durability test.
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, voiEpsilon = 0.0, exploreLinks = false)
        val judge = FakeJudge(
            strength = { if (it == "claude-SUPPORT") 0.9 else 0.8 },
            triage = { _, cands -> cands.map { if (it.text == "codex-ATTACK") Triage(TriageAction.UNDERCUT, 0) else Triage(TriageAction.ADD) } },
        )
        val first = durableGraph(host, registry, log)
        // e1 writes to a store of its own; the test copies it at the "kill" instant and leaves e1
        // blocked, standing in for the killed process.
        val store1 = InMemoryMetaStore()
        val e1 = DeliberationEngine(first, judge,
            listOf(proposer("claude", gated = true), proposer("codex", gated = true)), config, store = store1)
            .also { engines += it }
        val root = e1.ask("Q?")
        assertTrue(blocked.await(20, TimeUnit.SECONDS))
        awaitUntil("the other depth-1 claims settle") {
            e1.snapshot().claims().count { it.status !in setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING) } >= 4
        }
        e1.persistNow()
        val before = e1.snapshot()
        store1.load().forEach { (k, v) -> store.put(k, v) }

        val scheduler2 = VirtualThreadScheduler("deliberate-test-2")
        try {
            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val second = durableGraph(host2, registry2, log)
            val e2 = DeliberationEngine(second, judge,
                listOf(proposer("claude", gated = false), proposer("codex", gated = false)), config, store = store)
                .also { engines += it }
            val after = e2.snapshot()
            fun key(n: NodeDto) = listOf(n.ref, n.kind, n.text, n.depth, n.proposer, n.source, n.target, n.polarity, n.strength,
                n.plausibility, n.reach, n.contribution, n.undercuts, n.triage, n.rounds)
            assertEquals(before.nodes.map(::key), after.nodes.map(::key))
            assertEquals(before.questions.map { it.root to it.text }, after.questions.map { it.root to it.text })
            val active = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)
            before.claims().forEach { b ->
                val a = after.claim(b.ref)
                if (b.status in active) assertTrue(a.status in active, "$b -> $a") else assertEquals(b.status, a.status)
            }
            val resumed = before.claims().single { it.text == "claude-SUPPORT" }
            assertTrue(resumed.status in active)
            resume.countDown()
            e2.idle()
            val done = e2.snapshot()
            assertEquals(Status.ROUND_LIMIT, done.claim(resumed.ref).status)
            assertEquals(1, done.claim(resumed.ref).rounds)
            assertEquals(root.id.toString(), done.questions.single().root)
            // The undercutter was rebuilt as one.
            val u = done.claims().single { it.text == "codex-ATTACK" }
            assertEquals(done.edges().single { it.source == done.claims().single { c -> c.text == "claude-SUPPORT" }.ref }.ref, u.undercuts)
        } finally {
            gate.countDown()
            resume.countDown()
            scheduler2.shutdown()
        }
    }

    @Test
    fun `metadata write-behind flushes on close and a quiet restart rewrites nothing`() {
        val store = RecordingMetaStore()
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0)
        val first = DeliberationEngine(
            service, FakeJudge(), listOf(FakeProposer("claude")), config,
            store = store, persistEveryMs = 60_000,
        ).also { engines += it }
        first.ask("Durable?")
        first.idle()
        // The periodic writer cannot have run; close() must flush the question
        // and its root record before interrupting the worker.
        first.close()
        val saved = store.load()
        val root = saved.entries.single { (key, fields) -> fields["question"] == "\"${key.removePrefix("c:")}\"" }.value
        // The topology journal holds the text; the record holds it only after a rewrite.
        assertNull(root["text"], root.toString())
        // One record per claim, plus one per question (EXP-10: its round yields).
        // ... and one per link (SPEC §3 "Links as claims"), keyed by its edge ref.
        assertTrue(saved.keys.all { it.startsWith("c:") || it.startsWith("l:") || it.startsWith("q:") }, saved.keys.toString())
        assertTrue(saved.keys.any { it.startsWith("l:") }, saved.keys.toString())
        assertEquals(1, saved.keys.count { it.startsWith("q:") })

        val writesBeforeRestart = store.writes.get()
        val second = DeliberationEngine(
            service, FakeJudge(), listOf(FakeProposer("claude")), config,
            store = store, persistEveryMs = 60_000,
        ).also { engines += it }
        second.persistNow()
        assertEquals(writesBeforeRestart, store.writes.get(), "unchanged restored records must not be appended again")
    }

    @Test
    fun `metadata persistence writes field deltas and removes fields returned to defaults`() {
        val store = RecordingMetaStore()
        val engine = DeliberationEngine(
            service, FakeJudge(), emptyList(),
            DeliberationEngine.Config(maxRounds = 0),
            store = store, persistEveryMs = 60_000,
        ).also { engines += it }
        val root = engine.ask("Fields?")
        engine.idle()
        engine.persistNow()
        val key = "c:${root.id}"
        assertTrue(store.load().getValue(key).keys.containsAll(listOf("question", "proposer", "status", "roundLimit")))

        store.deltas.clear()
        engine.persistNow()
        assertTrue(store.deltas.isEmpty(), "an unchanged record must write no fields")

        engine.setOverride(root, Override.STOP)
        engine.persistNow()
        assertEquals(setOf("status", "override"), store.deltas.single { it.first == key }.second.keys)
        // CTL-03 on a question root: the question record carries the stop.
        assertEquals(mapOf("stopped" to "true"), store.deltas.single { it.first == "q:${root.id}" }.second)
        assertEquals(2, store.deltas.size)

        store.deltas.clear()
        engine.setOverride(root, Override.AUTO)
        engine.idle()
        engine.persistNow()
        val reset = store.deltas.single { it.first == key }.second
        assertEquals(setOf("status", "override"), reset.keys)
        assertNull(reset.getValue("override"), "AUTO is the default and must be persisted as a field removal")
        assertEquals(mapOf("stopped" to null), store.deltas.single { it.first == "q:${root.id}" }.second)
        assertEquals(2, store.deltas.size)
    }

    @Test
    fun `restart rebuilds missing metadata, omits an unplaced claim, and does not attach a duplicate`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-torn-restore").toFile()
        val log = java.io.File(dir, "host.journal")
        val root = service.createClaim("Q?")
        val placed = service.createClaim("P")
        service.createEdge(placed, root, Polarity.SUPPORT)
        val orphan = service.createClaim("orphan") // crash before its placing edge was written
        // No metadata reached the journal. The structure still identifies the
        // root, while the trailing claim without a placing edge remains an orphan.
        val store = RecordingMetaStore()

        val scheduler2 = VirtualThreadScheduler("deliberate-torn-restore-2")
        try {
            // Copy the existing primary structure into the log in creation order.
            val registryLog = LocationRegistry()
            val schedulerLog = VirtualThreadScheduler("deliberate-torn-restore-log")
            try {
                val logHost = ManagedHost(scheduler = schedulerLog, registry = registryLog)
                val logService = durableGraph(logHost, registryLog, log)
                val loggedRoot = logService.createClaim("Q?", root, question = true)
                val loggedPlaced = logService.createClaim("P", placed)
                logService.createEdge(loggedPlaced, loggedRoot, Polarity.SUPPORT)
                logService.createClaim("orphan", orphan)
            } finally {
                schedulerLog.shutdown()
            }

            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2)
            val restoredService = durableGraph(host2, registry2, log)
            val proposer = FakeProposer("claude") { ctx, side, _ ->
                if (ctx.claim == "Q?" && side == Polarity.SUPPORT) listOf("P") else emptyList()
            }
            val restored = DeliberationEngine(
                restoredService, FakeJudge(), listOf(proposer),
                DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0),
                store = store,
            ).also { engines += it }
            restored.idle()
            val graph = restored.snapshot()
            assertEquals(listOf("Q?", "P"), graph.claims().map { it.text })
            assertEquals(1, graph.edges().size)
            assertEquals(1, graph.node(root).duplicatesDropped)
            assertTrue(graph.nodes.none { it.ref == orphan.id.toString() })
            assertEquals(Status.DEPTH_LIMIT, graph.node(placed).status)
        } finally {
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ EXP-05 iteration 5, EXP-10

    @Test
    fun `contribution is reach x relevance x quality, shown but no longer a floor`() {
        // quality is Jev's construction Noul alone: no canonical-form factor scales or gates it.
        val judge = FakeJudge(
            strength = { 0.8 },
            relevance = { 0.5 },
            quality = { child -> if (child.contains("support")) 0.3 else 0.2 },
        )
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        val kept = kids.single { it.text!!.contains("support") }
        val low = kids.single { it.text!!.contains("attack") }
        assertEquals(0.12, kept.contribution!!, 1e-9)
        assertEquals(0.3, kept.quality)
        assertEquals(Status.ROUND_LIMIT, kept.status)
        // Changed by model C: 0.08 was below the 0.10 floor (PRUNED); the floor is gone.
        assertEquals(0.08, low.contribution!!, 1e-9)
        assertEquals(Status.ROUND_LIMIT, low.status)
    }
    @Test
    fun `round yield is value x novelty per argument asked`() {
        // The root attaches one child but records no yield. The child's round asks
        // for 4 (2 per side), then triage keeps 3 and drops 1: novelty 3/4.
        val judge = FakeJudge(
            strength = { 0.8 },
            relevance = { 0.5 },
            quality = { 0.9 },
            triage = { ctx, c ->
                c.mapIndexed { i, _ ->
                    Triage(if (ctx.claim == "seed" && i == 3) TriageAction.DROP else TriageAction.ADD)
                }
            },
        )
        val proposer = FakeProposer("claude") { ctx, side, max ->
            when {
                ctx.path.isEmpty() && side == Polarity.SUPPORT -> listOf("seed")
                ctx.path.isEmpty() -> emptyList()
                ctx.claim == "seed" -> List(max) { "${side.name.lowercase()}-$it" }
                else -> emptyList()
            }
        }
        val e = engine(
            judge = judge,
            proposers = listOf(proposer),
            config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 1, maxDepth = 1, voiEpsilon = 0.0, exploreLinks = false),
        )
        e.ask("Q?")
        e.idle()
        val q = e.snapshot().questions.single()
        assertEquals(1, q.yieldRounds)
        assertEquals(3 * 0.8 * 0.5 * 0.9 * 0.75 / 4, q.yieldRecent!!, 1e-12)
        assertNull(q.yieldEarlier)
        assertNull(q.stoppedBy)
    }

    @Test
    fun `exact value is derived - a restart recomputes it and a record from before model C still restores`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-voi").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        val scheduler2 = VirtualThreadScheduler("deliberate-test-voi")
        try {
            val judge = FakeJudge(plausibility = { if (it == "Q?") 0.5 else 0.999 })
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, exploreLinks = false)
            val first = durableGraph(host, registry, log)
            val e1 = DeliberationEngine(first, judge, listOf(FakeProposer("claude"), FakeProposer("codex")), config, store = store)
                .also { engines += it }
            val root = e1.ask("Q?")
            e1.idle()
            e1.close()
            val before = e1.snapshot()
            assertEquals("voi", before.questions.single().stoppedBy)
            // Nothing from exact evaluation is journaled.
            assertTrue(store.load().values.none { fields -> fields.keys.any { it.contains("sensitiv") } })
            // A question record written before model C held the yield stop's flag.
            val q = EngineRecords.QUESTION_KEY + root.id
            store.put(q, mapOf("diminished" to "true"))

            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val second = durableGraph(host2, registry2, log)
            val e2 = DeliberationEngine(second, FakeJudge(), listOf(FakeProposer("claude"), FakeProposer("codex")), config, store = store)
                .also { engines += it }
            e2.idle()
            val after = e2.snapshot()
            assertEquals(
                before.nodes.map { it.ref to it.sensitivity?.let { s -> Math.round(s * 1e9) } },
                after.nodes.map { it.ref to it.sensitivity?.let { s -> Math.round(s * 1e9) } },
            )
            assertEquals(before.claims().map { it.ref to it.status }, after.claims().map { it.ref to it.status })
            assertEquals(before.questions.map { it.stoppedBy to it.cruxes }, after.questions.map { it.stoppedBy to it.cruxes })
            e2.persistNow()
            assertNull(store.load().getValue(q)["diminished"], "the removed flag is dropped on the next write")
        } finally {
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ model D: first impression vs the arguments

    /** One argument on [side] of the root "[q]", strength 0.5 from a claim of plausibility 0.8; the root's is 0.9. */
    private fun modelDEngine(q: String, side: Side, graph: CredenceGraph = service, store: MetaStore = InMemoryMetaStore()) =
        DeliberationEngine(
            graph,
            FakeJudge(plausibility = { if (it == q) 0.9 else 0.8 }, strength = { 0.5 }),
            listOf(FakeProposer("claude") { ctx, s, _ -> if (ctx.path.isEmpty() && s == side) listOf("$q arg") else emptyList() }),
            DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false),
            store = store,
        ).also { engines += it }

    private fun DeliberationEngine.settledQuestion(expectedNeutral: Double): QuestionDto {
        awaitUntil("the neutral-prior root verdict settles on $expectedNeutral") {
            snapshot().questions.single().neutralCredence?.let { abs(it - expectedNeutral) < 1e-9 } == true
        }
        return snapshot().questions.single()
    }

    @Test
    fun `model D - an unargued question keeps its arguments-alone verdict at one half while its node keeps its prior`() {
        val e = DeliberationEngine(
            service,
            FakeJudge(plausibility = { 0.9 }, strength = { 0.5 }),
            listOf(FakeProposer("claude") { _, _, _ -> emptyList() }),
            DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false),
        ).also { engines += it }
        val root = e.ask("Unargued?")
        e.idle()
        // The question's "arguments alone" verdict is unchanged by the arguments-first view:
        // with no argument it weighs nothing from a neutral ½, as before.
        val q = e.settledQuestion(0.5)
        assertEquals(0.9, q.firstImpression)
        assertTrue(!q.verdictsDisagree)
        val n = e.snapshot().node(root)
        assertTrue(e.snapshot().edges().none { it.target == n.ref }, "the root has no argument")
        assertEquals(n.credences, n.argumentsFirstCredences, "an unargued root's arguments-first view keeps its prior")
        assertEquals(n.consensus, n.argumentsFirstConsensus)
    }

    @Test
    fun `model D - the first impression is kept and a neutral-prior verdict that disagrees is flagged`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-model-d").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        try {
            // DF-QuAD, one attack of energy 0.5 x 0.8 = 0.4: from Jev's 0.9 the root keeps 0.9 x 0.6 = 0.54,
            // from a neutral ½ the same argument leaves 0.5 x 0.6 = 0.3 — the first impression decides the side.
            val e = modelDEngine("Q?", Polarity.ATTACK, durableGraph(host, registry, log), store)
            val root = e.ask("Q?")
            e.idle()
            val q = e.settledQuestion(0.3)
            awaitUntil("the root settles on 0.54") { abs(e.snapshot().node(root).credence - 0.54) < 1e-9 }
            val before = e.snapshot()
            val rootNode = before.node(root)
            assertEquals(0.9, q.firstImpression)
            assertEquals(0.9, rootNode.plausibility, "the first impression stays the root's prior")
            assertEquals(q.neutralCredence!!, rootNode.argumentsFirstConsensus, 1e-12)
            assertEquals(rootNode.credences.keys, rootNode.argumentsFirstCredences.keys)
            assertTrue(rootNode.argumentsFirstCredences.values.all { it in 0.0..1.0 })
            before.nodes.filter { n -> before.edges().none { it.target == n.ref } }.forEach { unargued ->
                assertEquals(unargued.credences, unargued.argumentsFirstCredences, "unargued ${unargued.ref}")
                assertEquals(unargued.consensus, unargued.argumentsFirstConsensus, "unargued ${unargued.ref}")
            }
            assertTrue(before.questions.single().verdictsDisagree)
            e.close()
            val journalBytes = log.length()

            // Nothing of it is journaled: a restart recomputes both verdicts and the flag from the stances.
            assertTrue(store.load().values.none { f -> f.keys.any { it.contains("neutral") || it.contains("impression") || it.contains("argumentsFirst") } })
            val restartJudge = FakeJudge()
            val restartProposer = FakeProposer("claude") { _, _, _ -> emptyList() }
            val e2 = restart(
                log,
                store,
                DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false),
                restartJudge,
                listOf(restartProposer),
            )
            e2.idle()
            val after = e2.settledQuestion(0.3)
            assertEquals(0.9, after.firstImpression)
            val restored = e2.snapshot()
            awaitUntil("the restarted root flags the disagreement") { e2.snapshot().questions.single().verdictsDisagree }
            assertEquals(rootNode.argumentsFirstCredences, restored.node(root).argumentsFirstCredences)
            assertEquals(rootNode.argumentsFirstConsensus, restored.node(root).argumentsFirstConsensus)
            assertEquals(before.questions.single().cost, restored.questions.single().cost)
            assertEquals(journalBytes, log.length(), "recomputing arguments-first must add no topology journal record")
            assertTrue(restartJudge.plausibilityCalls.isEmpty() && restartJudge.relationCalls.isEmpty() && restartJudge.triageCalls.isEmpty())
            assertEquals(0, restartJudge.saturationCalls.get())
            assertTrue(restartProposer.contexts.isEmpty(), "restart recomputation must not ask a proposer")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model D - verdicts on the same side of one half are not flagged`() {
        // One support: 0.9 + 0.1 x 0.4 = 0.94 from Jev's first impression, 0.5 + 0.5 x 0.4 = 0.7 from ½.
        val e = modelDEngine("P?", Polarity.SUPPORT)
        val root = e.ask("P?")
        e.idle()
        val q = e.settledQuestion(0.7)
        awaitUntil("the root settles on 0.94") { abs(e.snapshot().node(root).credence - 0.94) < 1e-9 }
        assertEquals(0.9, q.firstImpression)
        assertTrue(!e.snapshot().questions.single().verdictsDisagree)
    }

    @Test
    fun `model D - a claim outside the judge's knowledge enters credence at one half`() {
        val all = CredenceGraph(host, registry, LayerSet.of(SemanticsCatalog.IDS, headline = LayerSet.CONSENSUS))
        val e = DeliberationEngine(
            all,
            FakeJudge(plausibility = { if (it == "Q?") 0.9 else Judge.OUTSIDE_KNOWLEDGE }, strength = { 0.5 }),
            listOf(FakeProposer("claude") { ctx, s, _ -> if (ctx.path.isEmpty() && s == Polarity.SUPPORT) listOf("unknown") else emptyList() }),
            DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false),
        ).also { engines += it }
        val root = e.ask("Q?")
        e.idle()
        awaitUntil("the unknown claim's credence reaches the hub") {
            e.snapshot().claims().any { it.text == "unknown" && it.credences.size == SemanticsCatalog.IDS.size }
        }
        val claim = e.snapshot().claims().single { it.text == "unknown" }
        assertEquals(0.5, claim.plausibility)
        claim.credences.values.forEach { assertEquals(0.5, it, 1e-12) }
        assertEquals(0.5, claim.consensus, 1e-12)
        assertEquals(0.5, claim.credence, 1e-12)
        assertTrue(e.snapshot().node(root).credence > 0.5)
    }

    // ------------------------------------------------------------ restart safety: DUR-06, CTL-05

    private val restartSchedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun stopRestartSchedulers() = restartSchedulers.forEach { it.shutdown() }

    /** A fresh host over the same topology [journalFile] and [store]: a process restart. */
    private fun restart(
        journalFile: java.io.File,
        store: MetaStore,
        config: DeliberationEngine.Config,
        judge: Judge = FakeJudge(),
        proposers: List<Proposer> = listOf(FakeProposer("claude")),
    ): DeliberationEngine {
        val s = VirtualThreadScheduler("deliberate-restart").also { restartSchedulers += it }
        val r = LocationRegistry()
        val h = ManagedHost(scheduler = s, registry = r, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        return DeliberationEngine(durableGraph(h, r, journalFile), judge, proposers, config, store = store)
            .also { engines += it }
    }

    /** Copies [from] into a new store, record by record. */
    private fun copyOf(from: MetaStore) = InMemoryMetaStore().also { to ->
        from.load().forEach { (k, v) -> to.put(k, v) }
    }

    /** A root with one pro "A" and one con "B"; every link explored once (it proposes nothing). */
    private fun twoLinkRun(dir: java.io.File, store: MetaStore): DeliberationEngine {
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = true)
        val e = DeliberationEngine(
            durableGraph(host, registry, java.io.File(dir, "host.journal")),
            FakeJudge(strength = { 0.5 }), listOf(linkProposer(rootPros = listOf("A"), rootCons = listOf("B"))), config, store = store,
        ).also { engines += it }
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertTrue(g.edges().all { it.rounds == 1 }, "both links explored in the first run: ${g.edges()}")
        e.close()
        return e
    }

    /** Records every proposer call made about a link. */
    private class LinkCounter : Proposer {
        override val id = "claude"
        val linkCalls = CopyOnWriteArrayList<String>()
        val calls = AtomicInteger()
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            calls.incrementAndGet()
            if (ctx.link != null) linkCalls += ctx.link!!.argument
            return emptyList()
        }
    }

    @Test
    fun `with link exploration off a restored link runs no round whatever state it was recorded in, until EXPANDed`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-links-off").toFile()
        try {
            val store1 = InMemoryMetaStore()
            twoLinkRun(dir, store1)
            val edges = store1.load().keys.filter { it.startsWith("l:") }
            assertEquals(2, edges.size)
            // As if the process had been killed mid-exploration: one link EXPLORING with rounds left, one QUEUED.
            val store = copyOf(store1)
            store.put(edges[0], mapOf("status" to "\"EXPLORING\"", "rounds" to "1", "roundLimit" to "3"))
            store.put(edges[1], mapOf("status" to null, "rounds" to null))
            val p = LinkCounter()
            val e = restart(java.io.File(dir, "host.journal"), store,
                DeliberationEngine.Config(argsPerCall = 1, maxRounds = 3, maxDepth = 1, exploreLinks = false), proposers = listOf(p))
            e.idle()
            assertEquals(emptyList(), p.linkCalls.toList())
            val g = e.snapshot()
            assertTrue(g.edges().all { it.status == Status.PRUNED }, "${g.edges().map { it.status }}")
            assertTrue(g.questions.none { it.active })
            // CTL-02: EXPAND is the one way to explore a link with exploration off.
            val queued = g.nodes.single { "l:${it.ref}" == edges[1] }
            e.setOverride(g.ref(queued), Override.EXPAND)
            e.idle()
            assertEquals(1, p.linkCalls.toSet().size, "only the expanded link ran")
            assertEquals(1, e.snapshot().nodes.single { it.ref == queued.ref }.rounds)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a restored link without a metadata record is rebuilt from structure and queued afresh`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-link-without-record").toFile()
        try {
            val firstStore = InMemoryMetaStore()
            twoLinkRun(dir, firstStore)
            val store = InMemoryMetaStore().also { copy ->
                firstStore.load().forEach { (key, fields) ->
                    if (!key.startsWith("l:")) copy.put(key, fields)
                }
            }
            val proposer = LinkCounter()
            val e = restart(
                java.io.File(dir, "host.journal"),
                store,
                DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = true),
                judge = FakeJudge(strength = { 0.5 }),
                proposers = listOf(proposer),
            )

            e.idle()

            val links = e.snapshot().edges()
            assertEquals(2, proposer.linkCalls.toSet().size, "both rebuilt links were explored")
            assertTrue(links.all { it.rounds == 1 && it.status == Status.ROUND_LIMIT && it.error == null }, links.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a paused question finishes its round in flight, starts no other, runs an EXPAND, and resumes`() {
        val gate = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val asked = CopyOnWriteArrayList<String>()
        val p = FakeProposer("claude") { ctx, _, _ ->
            asked += ctx.claim
            if (ctx.path.isEmpty()) {
                blocked.countDown()
                gate.await(20, TimeUnit.SECONDS)
            }
            null
        }
        val e = engine(proposers = listOf(p), config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = false))
        val root = e.ask("Q?")
        assertTrue(blocked.await(20, TimeUnit.SECONDS))
        e.setPaused(root, true)
        gate.countDown()
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertTrue(q.paused)
        assertTrue(q.active, "its claims are still queued")
        // The round in flight attached what it found; nothing else started.
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(2, kids.size)
        assertTrue(kids.all { it.status == Status.QUEUED && it.rounds == 0 }, "$kids")
        assertEquals(listOf("Q?", "Q?"), asked.toList())
        // CTL-02 in a paused question: the one expanded claim runs its forced round, nothing else.
        val first = kids.first()
        e.setOverride(g.ref(first), Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(1, g2.claim(first.ref).rounds)
        assertEquals(Status.QUEUED, g2.claim(kids.last().ref).status)
        assertTrue(asked.none { it == kids.last().text })
        assertTrue(g2.childrenOf(g2.ref(first)).all { g2.claim(it.source!!).status == Status.QUEUED })
        // Resume: back through the normal gates (depth 2 > maxDepth: DEPTH_LIMIT without a call).
        e.setPaused(root, false)
        e.idle()
        val g3 = e.snapshot()
        assertTrue(!g3.questions.single().paused && !g3.questions.single().active)
        assertEquals(Status.ROUND_LIMIT, g3.claim(kids.last().ref).status)
        assertEquals(1, g3.claim(kids.last().ref).rounds)
        assertTrue(g3.childrenOf(g3.ref(first)).all { g3.claim(it.source!!).status == Status.DEPTH_LIMIT })
    }

    @Test
    fun `a claim waiting for its next round is held by a pause and continues on resume`() {
        val gate = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val p = FakeProposer("claude") { _, _, _ ->
            if (first.getAndSet(false)) {
                blocked.countDown()
                gate.await(20, TimeUnit.SECONDS)
            }
            emptyList()
        }
        val e = engine(proposers = listOf(p),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 3, maxDepth = 0, exploreLinks = false, workers = 1))
        val root = e.ask("Q?")
        assertTrue(blocked.await(20, TimeUnit.SECONDS))
        e.setPaused(root, true)
        gate.countDown()
        e.idle()
        val held = e.snapshot().node(root)
        assertEquals(1, held.rounds, "the round in flight completed, the next did not start")
        assertEquals(Status.EXPLORING, held.status)
        e.setPaused(root, false)
        e.idle()
        assertEquals(3, e.snapshot().node(root).rounds)
        assertEquals(Status.ROUND_LIMIT, e.snapshot().node(root).status)
    }

    @Test
    fun `the pause gate covers every live requeue path and only EXPAND may spend`() {
        val firstRoundGate = CountDownLatch(1)
        val firstRoundBlocked = CountDownLatch(1)
        val forcedRoundGate = CountDownLatch(1)
        val forcedRoundBlocked = CountDownLatch(1)
        val triageTurns = AtomicInteger()
        val claude = FakeProposer("claude") { ctx, side, _ ->
            if (ctx.path.isEmpty() && side == Polarity.SUPPORT) {
                if (ctx.pros.isEmpty()) {
                    firstRoundBlocked.countDown()
                    firstRoundGate.await(20, TimeUnit.SECONDS)
                } else {
                    forcedRoundBlocked.countDown()
                    forcedRoundGate.await(20, TimeUnit.SECONDS)
                }
            }
            listOf("claude ${side.name.lowercase()} ${ctx.pros.size + ctx.cons.size}")
        }
        val codex = FakeProposer("codex") { ctx, side, _ ->
            listOf("codex ${side.name.lowercase()} ${ctx.pros.size + ctx.cons.size}")
        }
        val judge = FakeJudge(
            triage = { ctx, candidates ->
                val turn = triageTurns.incrementAndGet()
                candidates.map { candidate ->
                    if (ctx.path.isEmpty() && turn > 2) {
                        // The forced second round rewrites already-assessed arguments;
                        // their Jev assessment is allowed as part of that in-flight round.
                        val target = if (candidate.side == Polarity.SUPPORT) 0 else ctx.pros.size
                        Triage(TriageAction.REPLACE, target)
                    } else Triage(TriageAction.ADD)
                }
            },
        )
        val e = engine(
            judge = judge,
            proposers = listOf(claude, codex),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 3, maxDepth = 2, voiEpsilon = 0.0,
                maxArgsPerSide = 10, exploreLinks = false, workers = 1,
            ),
        )
        try {
            val root = e.ask("Q?")
            assertTrue(firstRoundBlocked.await(20, TimeUnit.SECONDS))
            e.setPaused(root, true)
            firstRoundGate.countDown()
            e.idle()

            val held = e.snapshot()
            val child = held.childrenOf(root).first().source!!
            val callsBefore = claude.contexts.size + codex.contexts.size +
                judge.plausibilityCalls.size + judge.relationCalls.size + judge.triageCalls.size +
                judge.relevanceCalls.size + judge.saturationCalls.get()
            val relationsBefore = judge.relationCalls.size

            // AUTO after STOP re-enters the normal gates and must remain held.
            e.setOverride(CellRef(java.util.UUID.fromString(child)), Override.STOP)
            e.setOverride(CellRef(java.util.UUID.fromString(child)), Override.AUTO)
            e.idle()
            val callsAfterAuto = claude.contexts.size + codex.contexts.size +
                judge.plausibilityCalls.size + judge.relationCalls.size + judge.triageCalls.size +
                judge.relevanceCalls.size + judge.saturationCalls.get()
            assertEquals(callsBefore, callsAfterAuto, "AUTO after STOP spent while paused")

            // EXPAND is the sole bypass. While its round owns the only worker,
            // resume then pause again: everything resume queued must meet held()
            // when the worker reaches it (the resume race).
            e.setOverride(root, Override.EXPAND)
            assertTrue(forcedRoundBlocked.await(20, TimeUnit.SECONDS))
            e.setPaused(root, false)
            e.setPaused(root, true)
            forcedRoundGate.countDown()
            e.idle()

            val after = e.snapshot()
            assertTrue(after.questions.single().paused)
            assertEquals(2, after.node(root).rounds, "only the in-flight and explicitly forced rounds ran")
            assertEquals(Status.EXPLORING, after.node(root).status, "the next round is held, not finished")
            assertTrue(after.claims().filter { it.depth == 1 }.all { it.rounds == 0 && it.status == Status.QUEUED })
            val laterContexts = (claude.contexts + codex.contexts).drop(4)
            assertTrue(laterContexts.isNotEmpty() && laterContexts.all { it.claim == "Q?" }, laterContexts.toString())
            assertTrue(judge.relationCalls.size > relationsBefore,
                "the forced rewrite was assessed again inside its allowed round")
        } finally {
            firstRoundGate.countDown()
            forcedRoundGate.countDown()
        }
    }

    @Test
    fun `start-paused restores every question paused and durable, runs no call, and new questions run`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-start-paused").toFile()
        val log = java.io.File(dir, "host.journal")
        val gate = CountDownLatch(1)
        try {
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = true, voiEpsilon = 0.0)
            val blocked = CountDownLatch(2)
            val gated = FakeProposer("claude") { ctx, _, _ ->
                if (ctx.path.size == 1 && ctx.link == null) {
                    blocked.countDown()
                    gate.await(20, TimeUnit.SECONDS)
                }
                null
            }
            val store1 = InMemoryMetaStore()
            val e1 = DeliberationEngine(durableGraph(host, registry, log), FakeJudge(strength = { 0.5 }),
                listOf(gated), config, store = store1).also { engines += it }
            val q1 = e1.ask("Q1?")
            assertTrue(blocked.await(20, TimeUnit.SECONDS))
            e1.persistNow()
            // The kill instant; one argument also lost its assessment (killed between attach and assess).
            val store = copyOf(store1)
            val kid = e1.snapshot().let { g -> g.childrenOf(q1).first().source!! }
            store.put("c:$kid", mapOf("plausibility" to null, "relevance" to null, "quality" to null, "edgeStrength" to null,
                "reach" to null, "contribution" to null, "status" to null))
            // An EXPAND was requested before the restart. Its forced round is not
            // resumed (DUR-06): the paused boot holds the claim like any other.
            store.put("c:${q1.id}", mapOf("override" to "\"EXPAND\"", "forceRound" to "true"))

            val judge = FakeJudge(strength = { 0.5 })
            val p = FakeProposer("claude") { _, _, _ -> emptyList() }
            val e2 = restart(log, store, config.copy(startPaused = true), judge, listOf(p))
            e2.idle()
            assertEquals(0, p.contexts.size, "no proposer call")
            assertEquals(0, judge.relationCalls.size + judge.plausibilityCalls.size + judge.triageCalls.size + judge.saturationCalls.get(),
                "no Jev call, not even the deferred assessment")
            val g2 = e2.snapshot()
            assertTrue(g2.questions.single().paused)
            assertTrue(g2.claims().filter { it.depth == 1 }.all { it.status == Status.QUEUED })
            // A question asked after the boot runs normally.
            val q2 = e2.ask("Q2?")
            e2.idle()
            val g2b = e2.snapshot()
            assertTrue(!g2b.questions.single { it.root == q2.id.toString() }.paused)
            assertEquals(1, g2b.node(q2).rounds)
            val callsBefore = p.contexts.size
            e2.close()

            // Durable: the next boot, without the flag, keeps Q1 paused.
            val judge3 = FakeJudge(strength = { 0.5 })
            val p3 = FakeProposer("claude") { _, _, _ -> emptyList() }
            val e3 = restart(log, store, config, judge3, listOf(p3))
            e3.idle()
            assertTrue(callsBefore > 0)
            assertEquals(0, p3.contexts.size)
            assertTrue(e3.snapshot().questions.single { it.root == q1.id.toString() }.paused)
            assertTrue(!e3.snapshot().questions.single { it.root == q2.id.toString() }.paused)
            // Resume assesses the argument that lost its assessment, then explores through the gates.
            e3.setPaused(q1, false)
            e3.idle()
            val g3 = e3.snapshot()
            assertTrue(judge3.relationCalls.any { it.child == g3.claim(kid).text })
            assertTrue(g3.claims().filter { it.depth == 1 && it.root == q1.id.toString() }.all { it.status == Status.ROUND_LIMIT })
            assertTrue(g3.questions.none { it.active || it.paused })
        } finally {
            gate.countDown()
            dir.deleteRecursively()
        }
    }
    // ---------------------------------------------------------------- model A: framing (computenet-dq2fy.29.2)

    /** Answers [result] and counts its calls in [counter]; [before] runs first (a latch, a usage report). */
    private class FakeFramer(
        val result: (String) -> Framing,
        val counter: AtomicInteger = AtomicInteger(),
        val before: () -> Unit = {},
    ) : Framer {
        val questions = CopyOnWriteArrayList<String>()
        override fun frame(question: String): Framing {
            counter.incrementAndGet()
            questions += question
            before()
            return result(question)
        }
    }

    private fun readings(vararg items: String) = Framing(FramingMode.READINGS, "sleep", items.toList())
    private fun positions(vararg items: String) = Framing(FramingMode.POSITIONS, null, items.toList())

    /** Child (text, polarity) pairs of [ref]. */
    private fun GraphDto.argumentsOf(ref: CellRef) =
        childrenOf(ref).map { claim(it.source!!).text!!.substringBeforeLast('-') to it.polarity }.sortedBy { it.toString() }

    @Test
    fun `model A - a NONE framing is asked once, before the root's plausibility, and changes nothing`() {
        val judge = FakeJudge()
        val judgedBeforeFraming = AtomicInteger(-1)
        val framer = FakeFramer({ Framing.NONE }, before = { judgedBeforeFraming.set(judge.plausibilityCalls.size) })
        val e = engine(judge = judge, framer = framer)
        val root = e.ask("Should cities ban cars?")
        e.idle()
        val plain = engine()
        val plainRoot = plain.ask("Should cities ban cars?")
        plain.idle()
        assertEquals(listOf("Should cities ban cars?"), framer.questions)
        assertEquals(0, judgedBeforeFraming.get(), "the framer runs before Judge.plausibility of the root")
        val g = e.snapshot()
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        assertEquals(plain.snapshot().argumentsOf(plainRoot), g.argumentsOf(root))
        val q = g.questions.single { it.root == root.id.toString() }
        assertNull(q.framing)
        assertNull(g.node(root).error)
    }

    @Test
    fun `model A - a failed framing is recorded and the question is explored as asked`() {
        val framer = FakeFramer({ error("cli down") })
        val e = engine(framer = framer)
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(1, framer.counter.get())
        assertTrue(g.node(root).error!!.startsWith("framing: "), g.node(root).error)
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        assertEquals(8, g.childrenOf(root).size)
        assertNull(g.questions.single().framing)
    }

    @Test
    fun `model A - a failed framing is not re-asked after a pause during the root's plausibility call`() {
        // computenet-3iu1k: a failed framing was not remembered, so a pause landing during the
        // plausibility call that follows it (parkIfHeld turns JUDGING back to QUEUED) led start()
        // to call the framer a second time on resume.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val framer = FakeFramer({ error("cli down") })
        val judge = FakeJudge(plausibility = { entered.countDown(); release.await(20, TimeUnit.SECONDS); 0.5 })
        val e = engine(judge = judge, framer = framer)
        val root = e.ask("Q?")
        assertTrue(entered.await(20, TimeUnit.SECONDS), "plausibility reached, so the failed framing already happened")
        e.setPaused(root, true)
        release.countDown()
        e.idle()
        assertTrue(e.snapshot().questions.single().paused, "the pause landed before the round started")
        e.setPaused(root, false)
        e.idle()
        val g = e.snapshot()
        assertEquals(1, framer.counter.get(), "the framer is asked once, even across the pause")
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        assertNull(g.questions.single().framing)
        assertTrue(g.node(root).error!!.startsWith("framing: "), g.node(root).error)
    }

    @Test
    fun `model A - a graph write failure on a READINGS answer is not re-asked after a pause during the root's plausibility call`() {
        // computenet-mnzog: a READINGS/POSITIONS answer whose graph write (service.frame)
        // throws only set c.error, leaving c.framing null, so a pause landing during the
        // plausibility call that follows it (parkIfHeld turns JUDGING back to QUEUED) led
        // start() to call the framer a second time on resume — the same shape computenet-3iu1k
        // fixed for a null tryCall result.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rootKnown = CountDownLatch(1)
        val rootRef = AtomicReference<CellRef>()
        val framer = FakeFramer({ readings("R1", "R2") }, before = {
            // Force service.frame(root, ...) to throw for real: give the root an argument
            // before the engine calls it, tripping CredenceGraph.frame's own
            // "root already has arguments" guard.
            assertTrue(rootKnown.await(20, TimeUnit.SECONDS), "root ref published before the framer ran")
            val bystander = service.createClaim("bystander")
            service.createEdge(bystander, rootRef.get(), Polarity.SUPPORT)
        })
        val judge = FakeJudge(plausibility = { entered.countDown(); release.await(20, TimeUnit.SECONDS); 0.5 })
        val e = engine(judge = judge, framer = framer)
        val root = e.ask("Q?")
        rootRef.set(root)
        rootKnown.countDown()
        assertTrue(entered.await(20, TimeUnit.SECONDS), "plausibility reached, so the graph write already failed")
        e.setPaused(root, true)
        release.countDown()
        e.idle()
        assertTrue(e.snapshot().questions.single().paused, "the pause landed before the round started")
        e.setPaused(root, false)
        e.idle()
        val g = e.snapshot()
        assertEquals(1, framer.counter.get(), "the framer is asked once, even across the graph-write failure and the pause")
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        assertNull(g.questions.single().framing)
        assertTrue(g.node(root).error!!.startsWith("framing: "), g.node(root).error)
    }

    @Test
    fun `model A - a NONE framing survives a restart without asking the framer again`() {
        // computenet-3iu1k: EngineRecords.recordOf wrote framingMode only when the mode was not
        // NONE, so a NONE outcome never reached the store; restoreFraming only restored framing
        // from the graph's structure (which a NONE framing never creates), so a restart re-asked.
        val dir = java.nio.file.Files.createTempDirectory("deliberate-framing-none-restore").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        val counter = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val judge = FakeJudge(plausibility = { entered.countDown(); release.await(20, TimeUnit.SECONDS); 0.5 })
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false)
        val e1 = DeliberationEngine(
            durableGraph(host, registry, log), judge, listOf(FakeProposer("claude")), config,
            store = store, framer = FakeFramer({ Framing.NONE }, counter),
        ).also { engines += it }
        val root = e1.ask("Q?")
        // The framing is decided (NONE) before the root's plausibility is judged (model A order);
        // blocking here and closing captures the persisted state before round 1 runs.
        assertTrue(entered.await(20, TimeUnit.SECONDS), "plausibility reached, so framing is already decided")
        e1.close()
        val scheduler2 = VirtualThreadScheduler("deliberate-framing-none-restore-2")
        try {
            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val e2 = DeliberationEngine(
                durableGraph(host2, registry2, log), FakeJudge(), listOf(FakeProposer("claude")), config,
                store = store, framer = FakeFramer({ Framing.NONE }, counter),
            ).also { engines += it }
            e2.idle()
            assertEquals(1, counter.get(), "a NONE framing is remembered across restart, not asked again")
            val g = e2.snapshot()
            assertEquals(Status.ROUND_LIMIT, g.node(root).status)
            assertNull(g.questions.single().framing)
        } finally {
            release.countDown()
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model A - READINGS frame the root and each reading is explored as a root`() {
        val judge = FakeJudge(plausibility = { when (it) { "R1" -> 0.9; "R2" -> 0.1; else -> 0.5 } })
        val framer = FakeFramer({ readings("R1", "R2") }, before = {
            Usage.report(CallUsage(Pricing.CLAUDE, listOf("claude-test"), inputTokens = 100, outputTokens = 10, reportedUsd = 0.02))
        })
        val claude = FakeProposer("claude")
        // A root's cap (3) differs from a child's (1): 2 per side can only come from the root cap.
        val config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 1, maxDepth = 0, maxArgsPerSide = 3, maxArgsPerSideChild = 1)
        val e = engine(judge = judge, proposers = listOf(claude), config = config, framer = framer)
        val root = e.ask("Is sleep good?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals(Status.FRAMED, r.status)
        assertEquals(0, r.rounds)
        assertEquals(1, framer.counter.get(), "only the question root is framed, never its readings")
        assertTrue(g.edges().none { it.target == root.id.toString() }, "nothing argues about a framed root")
        assertTrue(claude.contexts.none { it.claim == "Is sleep good?" }, "the framed root runs no round")
        val q = g.questions.single()
        val framing = assertNotNull(q.framing)
        assertEquals("READINGS", framing.mode)
        assertEquals("sleep", framing.term)
        assertEquals(listOf("R1", "R2"), framing.positions.map { it.text })
        for ((pos, p) in framing.positions.zip(listOf(0.9, 0.1))) {
            val n = g.claim(pos.ref)
            assertEquals("CLAIM", n.kind)
            assertEquals(0, n.depth)
            assertEquals(Claim.READING, n.proposer)
            assertEquals(root.id.toString(), n.positionOf)
            assertEquals(root.id.toString(), n.root)
            assertEquals(p, n.plausibility)
            assertEquals(p, pos.firstImpression)
            assertNull(pos.share, "readings have no shares")
            val kids = g.edges().filter { it.target == pos.ref }
            assertEquals(2, kids.count { it.polarity == "SUPPORT" }, "a reading is capped as a root")
            assertEquals(2, kids.count { it.polarity == "ATTACK" })
        }
        // A reading is judged against the question as asked.
        assertTrue(judge.plausibilityCalls.any { it.first == "Is sleep good?" && it.third == "R1" })
        assertEquals(1 + 2 + 2 * 4, q.claims)
        val billed = q.cost.backends.single { it.backend == Pricing.CLAUDE }
        assertEquals(1, billed.calls, "the framing call is billed to the question")
        assertEquals(0.02, billed.usd!!, 1e-12)
    }

    @Test
    fun `model A - POSITIONS carry shares that sum to one and follow credence`() {
        val judge = FakeJudge(plausibility = { when (it) { "P1" -> 0.8; "P2" -> 0.5; "P3" -> 0.2; else -> 0.5 } })
        val e = engine(judge = judge, proposers = listOf(FakeProposer("claude") { _, _, _ -> emptyList() }), framer = FakeFramer({ positions("P1", "P2", "P3") }))
        e.ask("Which P?")
        e.idle()
        awaitUntil("every position has its share") {
            e.snapshot().questions.single().framing?.positions?.all { it.share != null } == true
        }
        awaitUntil("the shares sum to one and the most credible position holds the largest") {
            val ps = e.snapshot().questions.single().framing!!.positions
            abs(ps.sumOf { it.share!! } - 1.0) < 1e-6 && ps.maxBy { it.credence } == ps.maxBy { it.share!! } &&
                ps.maxBy { it.credence }.text == "P1"
        }
        assertEquals("POSITIONS", e.snapshot().questions.single().framing!!.mode)
    }

    @Test
    fun `model A - EXPAND on a framed root runs no round and it stays FRAMED`() {
        val claude = FakeProposer("claude")
        val framer = FakeFramer({ readings("R1", "R2") })
        val e = engine(proposers = listOf(claude), framer = framer)
        val root = e.ask("Q?")
        e.idle()
        e.setOverride(root, Override.EXPAND)
        e.idle()
        assertEquals(Status.FRAMED, e.snapshot().node(root).status)
        assertTrue(claude.contexts.none { it.claim == "Q?" }, "no proposer call for the framed root")
        assertEquals(1, framer.counter.get(), "a framed root is never framed again")
    }

    @Test
    fun `model A - positions of a question paused during framing wait unstarted for the resume`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claude = FakeProposer("claude")
        val framer = FakeFramer({ readings("R1", "R2") }, before = { entered.countDown(); release.await(20, TimeUnit.SECONDS) })
        val e = engine(proposers = listOf(claude), framer = framer)
        val root = e.ask("Q?")
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        e.setPaused(root, true)
        release.countDown()
        e.idle()
        val g = e.snapshot()
        assertEquals(Status.FRAMED, g.node(root).status)
        val ps = g.questions.single().framing!!.positions
        assertTrue(ps.all { g.claim(it.ref).status == Status.QUEUED }, "positions stay QUEUED while paused")
        assertTrue(claude.contexts.isEmpty(), "nothing started in the paused question")
        e.setPaused(root, false)
        e.idle()
        val after = e.snapshot()
        assertTrue(ps.all { after.claim(it.ref).status == Status.ROUND_LIMIT }, "resumed positions are explored")
    }

    @Test
    fun `model A - each reading carries its own first impression and neutral-prior verdict`() {
        // DF-QuAD, one attack of energy 0.5 x 0.8 = 0.4 on R1: from 0.9 it keeps 0.54, from ½ it drops to 0.3.
        val judge = FakeJudge(plausibility = { when (it) { "R1" -> 0.9; else -> 0.5 } }, strength = { 0.8 })
        val proposer = FakeProposer("claude") { ctx, side, _ -> if (ctx.claim == "R1" && side == Polarity.ATTACK) listOf("Con") else emptyList() }
        val e = engine(
            judge = judge, proposers = listOf(proposer), framer = FakeFramer({ readings("R1", "R2") }),
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false),
        )
        val root = e.ask("Q?")
        e.idle()
        awaitUntil("R1's neutral-prior verdict settles on 0.3 and disagrees") {
            val r1 = e.snapshot().questions.single().framing!!.positions.first()
            r1.neutralCredence?.let { abs(it - 0.3) < 1e-9 } == true && abs(r1.credence - 0.54) < 1e-9 && r1.verdictsDisagree
        }
        val g = e.snapshot()
        val q = g.questions.single()
        assertNull(q.firstImpression)
        assertNull(q.neutralCredence)
        assertTrue(!q.verdictsDisagree)
        val (r1, r2) = q.framing!!.positions
        assertEquals(0.9, r1.firstImpression)
        assertTrue(!r2.verdictsDisagree)
        for (p in q.framing!!.positions) assertEquals(g.claim(p.ref).credence, p.credence)
        assertEquals(Status.FRAMED, g.node(root).status)
    }

    @Test
    fun `model A - a framing survives a restart without asking the framer again`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-framing-restore").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        val counter = AtomicInteger()
        val judge = FakeJudge(plausibility = { when (it) { "R1" -> 0.9; "R2" -> 0.1; else -> 0.5 } })
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false)
        val e1 = DeliberationEngine(
            durableGraph(host, registry, log), judge, listOf(FakeProposer("claude")), config,
            store = store, framer = FakeFramer({ readings("R1", "R2") }, counter),
        ).also { engines += it }
        val root = e1.ask("Q?")
        e1.idle()
        val before = e1.snapshot().questions.single().framing!!
        e1.close()
        val scheduler2 = VirtualThreadScheduler("deliberate-framing-restore-2")
        try {
            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val e2 = DeliberationEngine(
                durableGraph(host2, registry2, log), judge, listOf(FakeProposer("claude")), config,
                store = store, framer = FakeFramer({ readings("R1", "R2") }, counter),
            ).also { engines += it }
            e2.idle()
            val g = e2.snapshot()
            val after = g.questions.single().framing!!
            assertEquals(before.mode, after.mode)
            assertEquals(before.term, after.term)
            assertEquals(before.positions.map { it.ref to it.text }, after.positions.map { it.ref to it.text })
            assertEquals(before.positions.map { it.firstImpression }, after.positions.map { it.firstImpression })
            assertEquals(Status.FRAMED, g.node(root).status)
            assertEquals(1, counter.get())
            assertTrue(after.positions.all { g.claim(it.ref).proposer == Claim.READING && g.claim(it.ref).positionOf == root.id.toString() })
        } finally {
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model A - a landed framing topology record restores every position without reframing`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-framing-atomic").toFile()
        val log = java.io.File(dir, "host.journal")
        val store = InMemoryMetaStore()
        val counter = AtomicInteger()
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 0, exploreLinks = false)
        val e1 = DeliberationEngine(
            durableGraph(host, registry, log), FakeJudge(), listOf(FakeProposer("claude")), config,
            store = store, framer = FakeFramer({ positions("P1", "P2", "P3") }, counter),
        ).also { engines += it }
        val root = e1.ask("Q?")
        e1.idle()
        e1.close()
        assertTrue(store.load().getValue("c:${root.id}")["status"]!!.contains("FRAMED"))
        val scheduler2 = VirtualThreadScheduler("deliberate-framing-atomic-2")
        try {
            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val e2 = DeliberationEngine(
                durableGraph(host2, registry2, log), FakeJudge(), listOf(FakeProposer("claude")), config,
                store = store, framer = FakeFramer({ positions("P1", "P2", "P3") }, counter),
            ).also { engines += it }
            e2.idle()
            assertEquals(1, counter.get(), "a complete journaled framing is not asked again")
            val g = e2.snapshot()
            assertEquals(Status.FRAMED, g.node(root).status)
            assertEquals(listOf("P1", "P2", "P3"), g.questions.single().framing!!.positions.map { it.text })
        } finally {
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }
}
