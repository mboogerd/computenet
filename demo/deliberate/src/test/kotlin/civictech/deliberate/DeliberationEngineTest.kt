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

    private class FakeJudge(
        val plausibility: (String) -> Double = { 0.5 },
        val strength: Double = 0.8,
        val duplicates: (existing: List<String>, candidates: List<String>) -> List<Int?> = { _, c -> c.map { null } },
        val saturation: (ClaimContext, Side) -> Double = { _, _ -> 0.0 },
        val relevance: (ClaimContext) -> Double = { 1.0 },
    ) : Judge {
        val relevanceCalls = CopyOnWriteArrayList<String>()
        override fun plausibility(question: String, path: List<String>, claim: String) = plausibility(claim)
        override fun relationStrength(question: String, parent: String, child: String, side: Side) = strength
        override fun duplicates(claim: String, side: Side, existing: List<String>, candidates: List<String>) =
            duplicates(existing, candidates)
        override fun saturation(ctx: ClaimContext, side: Side) = saturation.invoke(ctx, side)
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
        config: DeliberationEngine.Config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 0),
    ) = DeliberationEngine(service, judge, proposers, config).also { engines += it }

    private fun DeliberationEngine.idle() = assertTrue(awaitIdle(20.seconds), "engine did not go idle")
    private fun GraphDto.node(ref: CellRef) = nodes.single { it.ref == ref.id.toString() }
    private fun GraphDto.claims() = nodes.filter { it.kind == "CLAIM" }
    private fun GraphDto.edges() = nodes.filter { it.kind == "EDGE" }
    private fun GraphDto.childrenOf(ref: CellRef) = edges().filter { it.target == ref.id.toString() }
    private fun GraphDto.claim(ref: String) = nodes.single { it.ref == ref }

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
        val e = engine(judge = FakeJudge(plausibility = { if (it == "Q?") 0.9 else 0.5 }, strength = 0.7))
        val root = e.ask("Q?")
        e.idle()
        val g0 = e.snapshot()
        assertEquals(0.9, g0.node(root).plausibility)
        assertTrue(g0.edges().all { it.strength == 0.7 })
        assertTrue(g0.claims().all { it.plausibility != null })
        awaitUntil("root credence moves away from 0.5") {
            e.snapshot().node(root).credence.let { it > 0.5 + 1e-6 || it < 0.5 - 1e-6 }
        }
    }

    @Test
    fun `saturation stops one side early and both sides end the expansion`() {
        val conSaturated = FakeJudge(saturation = { _, side -> if (side == Polarity.ATTACK) 0.9 else 0.1 })
        val e = engine(judge = conSaturated, config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0))
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
        val judge = FakeJudge(duplicates = { _, cands -> cands.map { if (it.startsWith("dup")) 0 else null } })
        val e = engine(judge = judge, proposers = listOf(a, b), config = DeliberationEngine.Config(maxRounds = 2, maxDepth = 0))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        val texts = g.childrenOf(root).map { g.claim(it.source!!).text }.toSet()
        assertEquals(setOf("Cars pollute.", "Streets get safer", "Noise drops", "Land is freed"), texts)
        assertEquals(2, g.node(root).duplicatesDropped)
    }

    @Test
    fun `irrelevant claims are pruned and the root is never relevance-judged`() {
        val judge = FakeJudge(relevance = { if (it.claim.startsWith("codex")) 0.2 else 0.9 })
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1))
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
    fun `claims beyond maxDepth are DEPTH_LIMIT without a relevance call`() {
        val judge = FakeJudge()
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 1, maxDepth = 1, argsPerCall = 1))
        e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(1 + 4 + 16, g.claims().size)
        g.claims().filter { it.depth == 2 }.forEach { assertEquals(Status.DEPTH_LIMIT, it.status); assertNull(it.relevance) }
        assertEquals(4, judge.relevanceCalls.size) // depth-1 claims only
    }

    @Test
    fun `maxClaims budget stops attaching and marks the rest BUDGET`() {
        val e = engine(config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 3, maxClaims = 5))
        val root = e.ask("Q?")
        e.idle()
        val g = e.snapshot()
        assertEquals(5, g.claims().size)
        assertEquals(Status.BUDGET, g.node(root).status)
        assertTrue(g.claims().filter { it.depth == 1 }.all { it.status == Status.BUDGET })
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
            override fun saturation(ctx: ClaimContext, side: Side): Double = error("529")
        }
        val e = engine(judge = judge, config = DeliberationEngine.Config(maxRounds = 2, maxDepth = 0))
        val root = e.ask("Q?")
        e.idle()
        val r = e.snapshot().node(root)
        assertEquals(Status.ROUND_LIMIT, r.status)
        assertEquals(2, r.rounds)
        assertNull(r.plausibility)
        assertNotNull(r.error)
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
        val e = engine(proposers = listOf(p), config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0))
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
        // gates skipped for the forced claim itself; its depth-2 children hit DEPTH_LIMIT
        assertEquals(relevanceCallsBefore, judge.relevanceCalls.size)
        assertTrue(g2.childrenOf(kidRef).all { g2.claim(it.source!!).status == Status.DEPTH_LIMIT })
    }

    @Test
    fun `EXPAND on a SATURATED claim runs exactly one extra round ignoring saturation`() {
        val e = engine(judge = FakeJudge(saturation = { _, _ -> 1.0 }), config = DeliberationEngine.Config(maxRounds = 3, maxDepth = 0))
        val root = e.ask("Q?")
        e.idle()
        assertEquals(Status.SATURATED, e.snapshot().node(root).status)
        assertEquals(8, e.snapshot().childrenOf(root).size)
        e.setOverride(root, Override.EXPAND)
        e.idle()
        val g = e.snapshot()
        assertEquals(Status.SATURATED, g.node(root).status)
        assertEquals(2, g.node(root).rounds)
        assertEquals(16, g.childrenOf(root).size)
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
}
