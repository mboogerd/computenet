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

/** Engine behaviour (SPEC EXP-02..08, CTL-01..04) with a fake judge and fake proposers. */
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

    private val scheduler = VirtualThreadScheduler("deliberate-test")
    private val registry = LocationRegistry()
    private val host = ManagedHost(
        scheduler = scheduler,
        registry = registry,
        attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
    )
    private val service = AgoraService(host, registry)
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
        assertEquals(0.35, config.minInfluence)
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
        assertEquals(listOf(QuestionDto(root.id.toString(), "Should cities ban cars?", 9, false)), g.questions)
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
        val conSaturated = FakeJudge(saturation = { _, side -> if (side == Polarity.ATTACK) 0.9 else 0.1 })
        val e = engine(judge = conSaturated, config = DeliberationEngine.Config(argsPerCall = 2, maxRounds = 3, maxDepth = 0, maxArgsPerSide = 100))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val r = g.node(root)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertEquals(3, r.rounds)
        assertEquals(0.9, r.conSaturation)
        assertEquals(0.1, r.proSaturation)
        assertEquals(4, g.childrenOf(root).count { it.polarity == "ATTACK" }) // round 1 only
        assertEquals(12, g.childrenOf(root).count { it.polarity == "SUPPORT" }) // all three rounds

        val both = engine(judge = FakeJudge(saturation = { _, _ -> 0.7 }), config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0))
        val r2 = both.ask("Q2?")
        both.idle()
        val n2 = both.snapshot().node(r2)
        assertEquals(Status.SATURATED, n2.status)
        assertEquals(1, n2.rounds)
    }

    @Test
    fun `duplicates are dropped within a round and against existing siblings`() {
        val a = FakeProposer("claude") { ctx, side, _ ->
            if (side == Polarity.ATTACK) emptyList()
            else if (ctx.pros.isEmpty()) listOf("Cars pollute.", "Streets get safer")
            else listOf("dup of cars", "Noise drops")
        }
        val b = FakeProposer("codex") { ctx, side, _ ->
            if (side == Polarity.ATTACK) emptyList()
            else if (ctx.pros.isEmpty()) listOf("  cars   POLLUTE ") // intra-round duplicate of claude's
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
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1))
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
    fun `EXPAND on a SATURATED claim runs exactly one extra round ignoring saturation and the cap`() {
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
                argsPerCall = 4, maxRounds = 2, maxDepth = 1, maxClaims = 25,
                maxArgsPerSide = 4, maxArgsPerSideChild = 3, roundDecay = 0.5, workers = 1,
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
                ctx.pros.isEmpty() -> if (side == Polarity.SUPPORT) listOf("P1 again") else listOf("Really pro")
                else -> if (side == Polarity.SUPPORT) listOf("P1 example") else emptyList()
            }
        }
        // Round 1 candidates: P1, P1 again, Off topic, Really pro (no existing: candidate j is index j).
        // Round 2: existing pros [P1, Really pro], cons [] ; candidates P1 better, P1 example, C1.
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
        assertEquals(2, judge.triageCalls.size) // one request per round
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
}
