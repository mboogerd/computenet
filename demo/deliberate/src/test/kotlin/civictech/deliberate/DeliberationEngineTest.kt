package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
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
    ) : Judge {
        val relevanceCalls = CopyOnWriteArrayList<String>()
        val plausibilityCalls = CopyOnWriteArrayList<Triple<String, List<String>, String>>()
        val relationCalls = CopyOnWriteArrayList<RelationCall>()
        val triageCalls = CopyOnWriteArrayList<Pair<ClaimContext, List<Candidate>>>()
        override fun plausibility(question: String, path: List<String>, claim: String): Double {
            plausibilityCalls += Triple(question, path, claim)
            return plausibility(claim)
        }
        override fun relationStrength(question: String, parent: String, child: String, side: Side): Double {
            relationCalls += RelationCall(question, parent, child, side)
            return strength(child)
        }
        override fun quality(question: String, parent: String, child: String, side: Side) = quality(child)
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>): List<Triage> {
            triageCalls += ctx to candidates
            return triage.invoke(ctx, candidates)
        }
        val saturationCalls = AtomicInteger()
        override fun saturation(ctx: ClaimContext, side: Side): Double {
            saturationCalls.incrementAndGet()
            return saturation.invoke(ctx, side)
        }
        override fun relevance(ctx: ClaimContext): Double {
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
    ) = DeliberationEngine(service, judge, proposers, config, merger).also { engines += it }

    private fun DeliberationEngine.idle() = assertTrue(awaitIdle(20.seconds), "engine did not go idle")
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
        assertEquals(0.10, config.minInfluence)
        assertEquals(DeliberationEngine.YieldStop(window = 8, ratio = 0.6, minClaims = 40), config.yieldStop)
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
        assertEquals(QuestionDto(root.id.toString(), "Should cities ban cars?", 9, false, cost = CostDto(rounds = 1)), q)
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
    fun `irrelevant claims are pruned and the root is never relevance-judged`() {
        // influence = relevance × reach(0.8): codex 0.16 < 0.3 ≤ 0.72 others
        val judge = FakeJudge(relevance = { if (it.claim.startsWith("codex")) 0.2 else 0.9 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1, minInfluence = 0.3))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(4, kids.size)
        kids.forEach { k ->
            if (k.proposer == "codex") {
                assertEquals(Status.PRUNED, k.status)
                assertEquals(0.2, k.relevance)
                assertTrue(g.childrenOf(CellRef(java.util.UUID.fromString(k.ref))).isEmpty())
            } else {
                assertEquals(Status.ROUND_LIMIT, k.status)
            }
        }
        assertTrue("Q?" !in judge.relevanceCalls)
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
        // The root explored before the budget ran out, so it is not BUDGET (EXP-06).
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)
        assertEquals(DeliberationEngine.Config.BUDGET_EXHAUSTED, g.node(root).error)
        assertTrue(g.claims().filter { it.depth == 1 }.all { it.status == Status.BUDGET && it.rounds == 0 })
        assertEquals(5, g.questions.single().claims)
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
            override fun plausibility(question: String, path: List<String>, claim: String): Double = error("429")
            override fun relationStrength(question: String, parent: String, child: String, side: Side) = 2.0
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
        // descendants are unaffected: they were still processed
        assertTrue(g.claims().filter { it.depth == 1 }.all { it.status == Status.DEPTH_LIMIT })

        // CTL-04: AUTO on a STOPPED claim re-queues it through the normal gates
        e.setOverride(root, Override.AUTO)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(Status.ROUND_LIMIT, g2.node(root).status)
        assertEquals(3, g2.node(root).rounds)
        assertEquals(12, g2.childrenOf(root).size)
    }

    @Test
    fun `EXPAND reruns a PRUNED claim past its gates`() {
        val judge = FakeJudge(relevance = { 0.1 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kid = g.childrenOf(root).first().source!!
        assertEquals(Status.PRUNED, g.claim(kid).status)
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
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1, minInfluence = 0.0),
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
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1, minInfluence = 0.5))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(2, kids.size)
        kids.forEach {
            assertEquals(DeliberationEngine.Config.FALLBACK_STRENGTH, it.reach)
            // relevance and quality fall back to 1: contribution = reach alone (0.5 ≥ 0.5, expanded)
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
    fun `contribution below the floor prunes an argument without asking anything at dequeue`() {
        val judge = FakeJudge(strength = { child -> if (child.contains("support")) 0.8 else 0.4 })
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, minInfluence = 0.5),
        )
        val root = e.ask("Q?")
        e.idle()
        val graph = e.snapshot()
        val kids = graph.childrenOf(root).map { graph.claim(it.source!!) }
        val reachable = kids.single { it.text!!.contains("support") }
        val decayed = kids.single { it.text!!.contains("attack") }
        assertEquals(Status.ROUND_LIMIT, reachable.status)
        assertEquals(Status.PRUNED, decayed.status)
        assertEquals(0.4, decayed.contribution)
        assertEquals(0, decayed.rounds)
    }

    @Test
    fun `influence gate expands relevant-and-reachable claims and prunes decayed ones`() {
        // relevance 0.5 everywhere, strength 0.8: influence 0.4 at depth 1, 0.32 at depth 2.
        val judge = FakeJudge(relevance = { 0.5 })
        val e = engine(
            judge = judge,
            proposers = listOf(FakeProposer("claude")),
            config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 3, argsPerCall = 1, minInfluence = 0.35),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertTrue(g.claims().filter { it.depth == 1 }.all { it.status == Status.ROUND_LIMIT && it.relevance == 0.5 })
        val d2 = g.claims().filter { it.depth == 2 }
        assertEquals(4, d2.size)
        assertTrue(d2.all { it.status == Status.PRUNED && it.relevance == 0.5 }, d2.toString())
        assertTrue(g.claims().none { it.depth == 3 })
        assertEquals(Status.ROUND_LIMIT, g.node(root).status)

        // CTL-02: EXPAND bypasses the influence gate for that claim only.
        val pruned = g.ref(d2.first())
        val relevanceBefore = judge.relevanceCalls.size
        e.setOverride(pruned, Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(Status.ROUND_LIMIT, g2.node(pruned).status)
        val grandkids = g2.childrenOf(pruned).map { g2.claim(it.source!!) }
        assertEquals(2, grandkids.size)
        // its depth-3 children face the gate again: 0.5 × 0.8³ = 0.256 < 0.35
        assertTrue(grandkids.all { it.status == Status.PRUNED })
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
    fun `the influence gate runs before the budget so rejected claims read PRUNED, not BUDGET`() {
        val judge = FakeJudge(relevance = { if (it.claim.startsWith("codex")) 0.1 else 1.0 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 3, argsPerCall = 1, maxClaims = 5))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val kids = g.childrenOf(root).map { g.claim(it.source!!) }
        assertEquals(4, kids.size)
        assertTrue(kids.filter { it.proposer == "codex" }.all { it.status == Status.PRUNED })
        assertTrue(kids.filter { it.proposer == "claude" }.all { it.status == Status.BUDGET })
    }

    @Test
    fun `exploration follows contribution and a strong claim's next round competes via roundDecay`() {
        // Root: 4 arguments per side in one round (its cap) → 8 depth-1 claims with
        // contribution = strength (relevance 1) × quality.
        val strength = mapOf("a1" to 0.95, "s1" to 0.9, "s4" to 0.8, "s3" to 0.7, "a2" to 0.6, "s2" to 0.5, "a3" to 0.42, "a4" to 0.85)
        val firstCalls = CopyOnWriteArrayList<String>()
        val n = AtomicInteger()
        val p = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.path.isEmpty() -> if (side == Polarity.SUPPORT) listOf("s1", "s2", "s3", "s4") else listOf("a1", "a2", "a3", "a4")
                else -> {
                    if (side == Polarity.SUPPORT) firstCalls += ctx.claim
                    listOf("x${n.incrementAndGet()}")
                }
            }
        }
        val judge = FakeJudge(
            strength = { strength[it] ?: 0.8 },
            quality = { if (it == "a4") 0.2 else 1.0 }, // a4: strong link, badly constructed
        )
        // Budget: 1 + 8 + 6 first rounds × 2 + 2 second rounds × 2 = 25.
        val e = engine(
            judge = judge,
            proposers = listOf(p),
            config = DeliberationEngine.Config(
                argsPerCall = 4, maxRounds = 2, maxDepth = 1, maxClaims = 25, minInfluence = 0.35,
                maxArgsPerSide = 4, maxArgsPerSideChild = 3, roundDecay = 0.5, workers = 1, exploreLinks = false,
            ),
        )
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(25, g.claims().size)
        assertEquals(1.0, g.node(root).contribution)
        // Firsts in contribution order; then a1 (0.95 × 0.5) and s1 (0.9 × 0.5) go before a3's first
        // round (0.42) — which then finds the budget spent.
        assertEquals(listOf("a1", "s1", "s4", "s3", "a2", "s2", "a1", "s1"), firstCalls.toList())
        val level1 = g.claims().filter { it.depth == 1 }.associateBy { it.text }
        assertEquals(0.2 * 0.85, level1.getValue("a4").contribution!!, 1e-9)
        assertEquals(0.2, level1.getValue("a4").quality)
        // Low quality: never expanded, whatever the budget.
        assertEquals(Status.PRUNED, level1.getValue("a4").status)
        assertEquals(0, level1.getValue("a4").rounds)
        // The weakest one that passed the floor never got the budget.
        assertEquals(Status.BUDGET, level1.getValue("a3").status)
        assertEquals(0, level1.getValue("a3").rounds)
        // The explored ones end ROUND_LIMIT: by their round limit, or on the budget.
        for (t in listOf("a1", "s1")) assertEquals(2, level1.getValue(t).rounds)
        for (t in listOf("s4", "s3", "a2", "s2")) {
            val k = level1.getValue(t)
            assertEquals(1, k.rounds)
            assertEquals(Status.ROUND_LIMIT, k.status)
            assertEquals(DeliberationEngine.Config.BUDGET_EXHAUSTED, k.error)
        }
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
            judge = FakeJudge(strength = { if (it == "strong child") 0.9 else 0.8 }),
            proposers = listOf(p),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, minInfluence = 0.0,
                maxArgsPerSide = 10, maxArgsPerSideChild = 10, roundDecay = 0.5, workers = 1,
            ),
        )
        e.ask("Q?")
        e.idle()

        // The child (0.9) joins the queue after assessment and beats the
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
        )
        val e = engine(
            judge = judge,
            proposers = listOf(claude, codex),
            // Root round two (priority 0.5) reaches P1 before the queued 0.4
            // child, keeping it eligible for REPLACE/REFINE.
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, minInfluence = 0.0, workers = 1,
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
        // REFINE attached the example under P1, as support.
        val refinement = g.edges().single { g.claim(it.source!!).text == "P1 example" }
        assertEquals(p1.ref, refinement.target)
        assertEquals("SUPPORT", refinement.polarity)
        assertEquals(2, g.claim(refinement.source!!).depth)
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
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, minInfluence = 0.0, workers = 1,
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
    fun `STOP on a parent does not cancel its already queued child`() {
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
                argsPerCall = 1, maxRounds = 2, maxDepth = 1, minInfluence = 0.0,
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
        assertEquals(Status.STOPPED, e.snapshot().node(q1).status)
        allowQ2.countDown()
        e.idle()
        val g = e.snapshot()
        assertEquals(1, g.node(q1).rounds)
        assertEquals(Status.ROUND_LIMIT, g.claim(child.ref).status)
        assertEquals(0L, childExplored.count)
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

        // The root (ROUND_LIMIT, budget exhausted) also gets its forced round.
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
            config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 2, minInfluence = 0.0),
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
    fun `a link is queued by its argument's contribution times the uncertainty of its strength and explored like a claim`() {
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
        // contribution(argument) x 4 s (1 - s): a decisive link is settled, an even one is wide open.
        assertEquals(0.0, strong.contribution!!, 1e-9)
        assertEquals(Status.PRUNED, strong.status)
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
                minInfluence = 0.0, workers = 1, yieldStop = null,
            ),
        )
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        val links = g.edges()

        // Every non-root claim creates exactly one link, so the claim ceiling
        // is also a structural ceiling on links. Disabling EXP-10 still cannot
        // produce an unbounded chain.
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
        val log = java.io.File(dir, "graph.jsonl")
        val store = InMemoryMetaStore()
        val judge = FakeJudge(strength = { when (it) { "Strong" -> 1.0; "Even" -> 0.5; else -> 0.8 } })
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1)
        fun proposer() = linkProposer(rootPros = listOf("Strong"), rootCons = listOf("Even")) { link, side, _ ->
            if (link.argument == "Even") listOf(if (side == Polarity.SUPPORT) "Even holds" else "Even fails") else emptyList()
        }
        val e1 = DeliberationEngine(CredenceGraph(host, registry, dfquad, structureLog = log), judge, listOf(proposer()), config, store = store)
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
            val e2 = DeliberationEngine(CredenceGraph(host2, registry2, dfquad, structureLog = log), judge, listOf(proposer()), config, store = store)
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
        val log = java.io.File(dir, "graph.jsonl")
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
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, minInfluence = 0.0, exploreLinks = false)
        val judge = FakeJudge(
            strength = { if (it == "claude-SUPPORT") 0.9 else 0.8 },
            triage = { _, cands -> cands.map { if (it.text == "codex-ATTACK") Triage(TriageAction.UNDERCUT, 0) else Triage(TriageAction.ADD) } },
        )
        val first = CredenceGraph(host, registry, dfquad, structureLog = log)
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
            val second = CredenceGraph(host2, registry2, dfquad, structureLog = log)
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
        // The structure log holds the text; the record holds it only after a rewrite.
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
        assertEquals(setOf("status", "override"), store.deltas.single().second.keys)

        store.deltas.clear()
        engine.setOverride(root, Override.AUTO)
        engine.idle()
        engine.persistNow()
        val reset = store.deltas.single().second
        assertEquals(setOf("status", "override"), reset.keys)
        assertNull(reset.getValue("override"), "AUTO is the default and must be persisted as a field removal")
    }

    @Test
    fun `restart rebuilds missing metadata, omits an unplaced claim, and does not attach a duplicate`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-torn-restore").toFile()
        val log = java.io.File(dir, "graph.jsonl")
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
                val logService = CredenceGraph(ManagedHost(scheduler = schedulerLog, registry = registryLog), registryLog, dfquad, structureLog = log)
                val loggedRoot = logService.createClaim("Q?", root, question = true)
                val loggedPlaced = logService.createClaim("P", placed)
                logService.createEdge(loggedPlaced, loggedRoot, Polarity.SUPPORT)
                logService.createClaim("orphan", orphan)
            } finally {
                schedulerLog.shutdown()
            }

            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2)
            val restoredService = CredenceGraph(host2, registry2, dfquad, structureLog = log)
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
    fun `contribution is reach x relevance x quality and the default floor is 0_10`() {
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
        val pruned = kids.single { it.text!!.contains("attack") }
        // 0.8 × 0.5 × 0.3 = 0.12 ≥ 0.10: explored; 0.8 × 0.5 × 0.2 = 0.08 < 0.10: PRUNED.
        assertEquals(0.12, kept.contribution!!, 1e-9)
        assertEquals(0.3, kept.quality)
        assertEquals(Status.ROUND_LIMIT, kept.status)
        assertEquals(0.08, pruned.contribution!!, 1e-9)
        assertEquals(Status.PRUNED, pruned.status)
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
            config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 1, maxDepth = 1, minInfluence = 0.0, exploreLinks = false),
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
    fun `a high-yield root followed by flat child yields does not stop`() {
        val proposer = FakeProposer("claude") { ctx, side, max ->
            when {
                ctx.path.isEmpty() -> List(max) { "root-${side.name.lowercase()}-$it" }
                ctx.path.size == 1 -> listOf("flat-${ctx.claim}-${side.name.lowercase()}")
                else -> emptyList()
            }
        }
        val judge = FakeJudge(strength = { if (it.startsWith("root-")) 1.0 else 0.1 })
        val e = engine(
            judge = judge,
            proposers = listOf(proposer),
            config = DeliberationEngine.Config(
                argsPerCall = 3, maxRounds = 1, maxDepth = 1, minInfluence = 0.0, workers = 1,
                yieldStop = DeliberationEngine.YieldStop(window = 2, ratio = 0.6, minClaims = 0), exploreLinks = false,
            ),
        )
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertEquals(6, q.yieldRounds, "the root's high-yield round must not be in the history")
        assertEquals(q.yieldEarlier!!, q.yieldRecent!!, 1e-12)
        assertNull(q.stoppedBy)
        assertTrue(g.claims().none { it.status == Status.DIMINISHING })
    }

    @Test
    fun `a decline after the question exhausts itself records no diminishing stop`() {
        val proposer = FakeProposer("claude") { ctx, side, _ ->
            when {
                ctx.path.isEmpty() -> listOf(if (side == Polarity.SUPPORT) "first" else "second")
                ctx.claim == "first" -> listOf("high-${side.name.lowercase()}")
                ctx.claim == "second" -> listOf("low-${side.name.lowercase()}")
                else -> emptyList()
            }
        }
        val judge = FakeJudge(
            strength = { 1.0 },
            triage = { ctx, c -> c.map { Triage(if (ctx.claim == "second") TriageAction.DROP else TriageAction.ADD) } },
        )
        val e = engine(
            judge = judge,
            proposers = listOf(proposer),
            config = DeliberationEngine.Config(
                argsPerCall = 1, maxRounds = 1, maxDepth = 1, minInfluence = 0.0, workers = 1,
                yieldStop = DeliberationEngine.YieldStop(window = 1, ratio = 0.6, minClaims = 0), exploreLinks = false,
            ),
        )
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertEquals(2, q.yieldRounds)
        assertTrue(q.yieldRecent!! < 0.6 * q.yieldEarlier!!, q.toString())
        assertNull(q.stoppedBy, "the decline halted no QUEUED claim")
        assertTrue(g.claims().none { it.status == Status.DIMINISHING })
    }

    @Test
    fun `yield stop needs the claim floor, twice the window of rounds, and a real decline`() {
        val stop = DeliberationEngine.YieldStop(window = 2, ratio = 0.6, minClaims = 10)
        val decaying = listOf(1.0, 1.0, 0.1, 0.1)
        assertTrue(stop.diminished(decaying, claims = 10))
        assertTrue(!stop.diminished(decaying, claims = 9), "below the claim floor")
        assertTrue(!stop.diminished(decaying.drop(1), claims = 50), "fewer than 2 × window rounds")
        assertTrue(!stop.diminished(listOf(0.5, 0.5, 0.5, 0.5, 0.5), claims = 50), "flat yields")
        // recent 0.6 is not below 0.6 × 1.0
        assertTrue(!stop.diminished(listOf(1.0, 1.0, 0.6, 0.6), claims = 50))
        assertTrue(!stop.diminished(listOf(0.0, 0.0, 0.0, 0.0), claims = 50), "nothing to decline from")
    }

    /** Triage keeps the first [keep] candidates and drops every later one as an untargeted DUPLICATE (yield 0). */
    private fun decayingJudge(keep: Int, allowAll: AtomicBoolean = AtomicBoolean(false)): FakeJudge {
        val seen = AtomicInteger()
        return FakeJudge(triage = { _, c ->
            c.map { if (allowAll.get() || seen.incrementAndGet() <= keep) Triage(TriageAction.ADD) else Triage(TriageAction.DUPLICATE) }
        })
    }

    private val deepConfig = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 3, maxDepth = 3, minInfluence = 0.0)

    @Test
    fun `a question whose yields decay stops and its queued claims end DIMINISHING`() {
        val e = engine(judge = decayingJudge(keep = 50), config = deepConfig)
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertEquals("diminishing", q.stoppedBy)
        assertEquals(51, q.claims)
        assertTrue(q.yieldRounds >= 16, q.toString())
        val diminishing = g.claims().filter { it.status == Status.DIMINISHING }
        assertTrue(diminishing.isNotEmpty())
        assertTrue(g.claims().none { it.status in setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING) })
        assertTrue(g.claims().none { it.status == Status.BUDGET }, "the budget was never reached")
        // The series froze at the stop, so it still shows the decline that caused it.
        assertTrue(q.yieldRecent!! < 0.6 * q.yieldEarlier!!, q.toString())
        assertTrue(g.node(root).rounds!! >= 1)
    }

    @Test
    fun `yields that collapse below the claim floor never stop the question`() {
        // 20 candidates kept: the tree never reaches 40 claims, so however low its yields, it just runs out.
        val e = engine(judge = decayingJudge(keep = 20), config = deepConfig)
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val q = g.questions.single()
        assertEquals(21, q.claims)
        assertTrue(q.yieldRounds >= 16, q.toString())
        assertNull(q.stoppedBy)
        assertTrue(g.claims().none { it.status == Status.DIMINISHING })
    }

    @Test
    fun `flat yields run into the budget, not the yield stop`() {
        val e = engine(config = deepConfig.copy(maxClaims = 120))
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals("budget", g.questions.single().stoppedBy)
        assertEquals(120, g.questions.single().claims)
        assertTrue(g.claims().none { it.status == Status.DIMINISHING })
    }

    @Test
    fun `yield stop off keeps exploring a decaying question`() {
        val e = engine(judge = decayingJudge(keep = 50), config = deepConfig.copy(yieldStop = null))
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertNull(g.questions.single().stoppedBy)
        assertTrue(g.claims().none { it.status == Status.DIMINISHING })
        // yields are still recorded for display
        assertTrue(g.questions.single().yieldEarlier != null)
    }

    @Test
    fun `EXPAND overrides DIMINISHING for one forced round whose arguments meet the stop`() {
        val allowAll = AtomicBoolean(false)
        val e = engine(judge = decayingJudge(keep = 50, allowAll = allowAll), config = deepConfig)
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val target = g.claims().first { it.status == Status.DIMINISHING && it.rounds == 0 && it.depth!! < 3 }
        val ref = g.ref(target)
        allowAll.set(true)
        e.setOverride(ref, Override.EXPAND)
        e.idle()
        val g2 = e.snapshot()
        assertEquals(1, g2.node(ref).rounds)
        // Rounds were left, but the question is stopped: back to DIMINISHING, not ROUND_LIMIT.
        assertEquals(Status.DIMINISHING, g2.node(ref).status)
        val kids = g2.childrenOf(ref).map { g2.claim(it.source!!) }
        assertEquals(4, kids.size) // 2 proposers × 1 per side
        assertTrue(kids.all { it.status == Status.DIMINISHING && it.rounds == 0 }, kids.toString())
        assertEquals("diminishing", g2.questions.single().stoppedBy)
    }

    @Test
    fun `budget gate wins over a prior diminishing stop for later forced arguments`() {
        val allowAll = AtomicBoolean(false)
        val e = engine(
            judge = decayingJudge(keep = 50, allowAll = allowAll),
            config = deepConfig.copy(maxClaims = 55, workers = 1),
        )
        e.ask("Q?")
        e.idle()
        val stopped = e.snapshot()
        assertEquals(51, stopped.questions.single().claims)
        assertEquals("diminishing", stopped.questions.single().stoppedBy)
        val target = stopped.claims().first { it.status == Status.DIMINISHING && it.rounds == 0 && it.depth!! < 3 }

        allowAll.set(true)
        e.setOverride(stopped.ref(target), Override.EXPAND)
        e.idle()

        val resumed = e.snapshot()
        val kids = resumed.childrenOf(stopped.ref(target)).map { resumed.claim(it.source!!) }
        assertEquals(4, kids.size)
        assertTrue(kids.all { it.status == Status.BUDGET }, kids.toString())
        assertEquals(55, resumed.questions.single().claims)
        assertEquals("budget", resumed.questions.single().stoppedBy)
    }

    @Test
    fun `a restart keeps the yield stop, its yields and the DIMINISHING statuses`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-yield").toFile()
        val log = java.io.File(dir, "graph.jsonl")
        val store = InMemoryMetaStore()
        val scheduler2 = VirtualThreadScheduler("deliberate-test-yield")
        try {
            val first = CredenceGraph(host, registry, dfquad, structureLog = log)
            val e1 = DeliberationEngine(first, decayingJudge(keep = 50),
                listOf(FakeProposer("claude"), FakeProposer("codex")), deepConfig, store = store)
                .also { engines += it }
            e1.ask("Q?")
            e1.idle()
            e1.close()
            val before = e1.snapshot()
            assertEquals("diminishing", before.questions.single().stoppedBy)

            val registry2 = LocationRegistry()
            val host2 = ManagedHost(scheduler = scheduler2, registry = registry2, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
            val second = CredenceGraph(host2, registry2, dfquad, structureLog = log)
            // A judge that would keep everything: nothing may run, since the question is stopped.
            val e2 = DeliberationEngine(second, FakeJudge(),
                listOf(FakeProposer("claude"), FakeProposer("codex")), deepConfig, store = store)
                .also { engines += it }
            e2.idle()
            val after = e2.snapshot()
            assertEquals(before.questions, after.questions)
            assertEquals(before.claims().map { it.ref to it.status }, after.claims().map { it.ref to it.status })
        } finally {
            scheduler2.shutdown()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ restart safety: DUR-05, DUR-06, CTL-05

    private val restartSchedulers = mutableListOf<VirtualThreadScheduler>()

    @AfterTest
    fun stopRestartSchedulers() = restartSchedulers.forEach { it.shutdown() }

    /** A fresh host over the same structure [log] and [store]: a process restart. */
    private fun restart(
        log: java.io.File,
        store: MetaStore,
        config: DeliberationEngine.Config,
        judge: Judge = FakeJudge(),
        proposers: List<Proposer> = listOf(FakeProposer("claude")),
    ): DeliberationEngine {
        val s = VirtualThreadScheduler("deliberate-restart").also { restartSchedulers += it }
        val r = LocationRegistry()
        val h = ManagedHost(scheduler = s, registry = r, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        return DeliberationEngine(CredenceGraph(h, r, dfquad, structureLog = log), judge, proposers, config, store = store)
            .also { engines += it }
    }

    /** Copies [from] into a new store, record by record, leaving out keys [drop] rejects. */
    private fun copyOf(from: MetaStore, drop: (String) -> Boolean = { false }) = InMemoryMetaStore().also { to ->
        from.load().forEach { (k, v) -> if (!drop(k)) to.put(k, v) }
    }

    /** A root with one pro "A" and one con "B"; every link explored once (it proposes nothing). */
    private fun twoLinkRun(dir: java.io.File, store: MetaStore): DeliberationEngine {
        val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = true)
        val e = DeliberationEngine(
            CredenceGraph(host, registry, dfquad, structureLog = java.io.File(dir, "graph.jsonl")),
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
            val e = restart(java.io.File(dir, "graph.jsonl"), store,
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
    fun `a link without a record restores PRUNED with a note instead of joining the queue`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-legacy-links").toFile()
        try {
            val store1 = InMemoryMetaStore()
            twoLinkRun(dir, store1)
            // Data written before links existed: claim and question records, no link records.
            val store = copyOf(store1) { it.startsWith("l:") }
            val p = LinkCounter()
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = true)
            val e = restart(java.io.File(dir, "graph.jsonl"), store, config, judge = FakeJudge(strength = { 0.5 }), proposers = listOf(p))
            e.idle()
            assertEquals(0, p.calls.get(), "a restart of settled legacy data asks nobody anything")
            val g = e.snapshot()
            assertEquals(2, g.edges().size)
            g.edges().forEach {
                assertEquals(Status.PRUNED, it.status)
                assertEquals(DeliberationEngine.Config.LEGACY_LINK, it.error)
                assertEquals(0, it.rounds)
            }
            assertTrue(g.questions.none { it.active })
            // Now recorded: a later restart keeps it PRUNED without the legacy rule.
            e.persistNow()
            assertTrue(store.load().keys.count { it.startsWith("l:") } == 2)
            // EXPAND explores it and clears the note.
            val link = g.edges().first()
            e.setOverride(g.ref(link), Override.EXPAND)
            e.idle()
            val after = e.snapshot().nodes.single { it.ref == link.ref }
            assertEquals(1, after.rounds)
            assertNull(after.error)
            assertEquals(listOf(link.ref), e.snapshot().edges().filter { (it.rounds ?: 0) > 0 }.map { it.ref }, "only the expanded link ran")
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
                argsPerCall = 1, maxRounds = 3, maxDepth = 2, minInfluence = 0.0,
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
        val log = java.io.File(dir, "graph.jsonl")
        val gate = CountDownLatch(1)
        try {
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 1, maxDepth = 1, exploreLinks = true, minInfluence = 0.0)
            val blocked = CountDownLatch(2)
            val gated = FakeProposer("claude") { ctx, _, _ ->
                if (ctx.path.size == 1 && ctx.link == null) {
                    blocked.countDown()
                    gate.await(20, TimeUnit.SECONDS)
                }
                null
            }
            val store1 = InMemoryMetaStore()
            val e1 = DeliberationEngine(CredenceGraph(host, registry, dfquad, structureLog = log), FakeJudge(strength = { 0.5 }),
                listOf(gated), config, store = store1).also { engines += it }
            val q1 = e1.ask("Q1?")
            assertTrue(blocked.await(20, TimeUnit.SECONDS))
            e1.persistNow()
            // The kill instant; one argument also lost its assessment (killed between attach and assess).
            val store = copyOf(store1)
            val kid = e1.snapshot().let { g -> g.childrenOf(q1).first().source!! }
            store.put("c:$kid", mapOf("plausibility" to null, "relevance" to null, "quality" to null, "edgeStrength" to null,
                "reach" to null, "contribution" to null, "status" to null))
            // A durable EXPAND was requested before this safe-upgrade boot.
            // DUR-06 still holds it: only an EXPAND issued in this process may
            // bypass the boot pause.
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
}
