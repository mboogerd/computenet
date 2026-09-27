package civictech.deliberate

import civictech.agora.AgoraService
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitUntil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** SPEC §12 Cost: pricing math, attribution to the right question, projection and durability. */
class CostTest {

    private fun near(expected: Double, actual: Double?, what: String = "") {
        assertNotNull(actual, what)
        assertTrue(kotlin.math.abs(expected - actual) < 1e-12, "$what: expected $expected, got $actual")
    }

    // ------------------------------------------------------------ pricing

    @Test
    fun `codex pricing splits cached, cache-write and uncached input and never adds reasoning twice`() {
        val p = Pricing()
        // 1M input of which 200K cached and 100K cache writes; 100K output of which 60K reasoning.
        val u = CallUsage(
            Pricing.CODEX, inputTokens = 1_000_000, cachedInputTokens = 200_000, cacheWriteTokens = 100_000,
            outputTokens = 100_000, reasoningTokens = 60_000, longestPromptTokens = 100_000,
        )
        // 700K × $4 + 200K × $0.40 + 100K × $4 × 1.25 + 100K × $20, per 1M
        near(2.80 + 0.08 + 0.50 + 2.00, p.price(u))
    }

    @Test
    fun `a codex prompt over 272K tokens pays 2x input and 1_5x output`() {
        val p = Pricing()
        val base = CallUsage(Pricing.CODEX, inputTokens = 300_000, cachedInputTokens = 100_000, outputTokens = 10_000)
        // longestPromptTokens defaults to inputTokens: 300K > 272K.
        near((200_000 * 4.0 + 100_000 * 0.40) * 2 / 1e6 + 10_000 * 20.0 * 1.5 / 1e6, p.price(base))
        val short = base.copy(longestPromptTokens = 272_000)
        near((200_000 * 4.0 + 100_000 * 0.40) / 1e6 + 10_000 * 20.0 / 1e6, p.price(short), "at the threshold")
    }

    @Test
    fun `jev is priced on input only and claude by its reported cost`() {
        val p = Pricing()
        near(0.042, p.price(CallUsage(Pricing.JEV, inputTokens = 1_000_000, outputTokens = 5_000_000)))
        near(0.0323, p.price(CallUsage(Pricing.CLAUDE, inputTokens = 11_301, outputTokens = 4, reportedUsd = 0.0323)))
        assertNull(p.price(CallUsage(Pricing.CLAUDE, inputTokens = 10)), "a Claude call without a reported cost is unpriced")
        assertTrue(p.info(Pricing.JEV).assumed)
        assertTrue(p.info(Pricing.CLAUDE).note!!.contains("subscription"))
        assertEquals(Pricing.PRICES_DATE, p.info(Pricing.CODEX).date)
        assertTrue(p.info(Pricing.CODEX).source.contains("gpt-5.6-sol"))
    }

    @Test
    fun `another codex model needs rate flags or its rate is unknown`() {
        val unknown = Pricing.of("gpt-9", null, null, null, null, null)
        assertNull(unknown.price(CallUsage(Pricing.CODEX, inputTokens = 1000)))
        assertTrue(unknown.info(Pricing.CODEX).rate.startsWith("rate unknown"))
        val flagged = Pricing.of("gpt-9", 1.0, 0.1, 2.0, null, null)
        near(0.9 + 0.01 + 2.0, flagged.price(CallUsage(Pricing.CODEX, inputTokens = 1_000_000, cachedInputTokens = 100_000, outputTokens = 1_000_000)))
        assertEquals("command-line rate flags", flagged.info(Pricing.CODEX).source)
        val same = Pricing.of(null, null, null, null, 0.1, null)
        near(0.1, same.price(CallUsage(Pricing.JEV, inputTokens = 1_000_000)))
        near(Pricing().price(CallUsage(Pricing.CODEX, inputTokens = 1000))!!, same.price(CallUsage(Pricing.CODEX, inputTokens = 1000)))
    }

    @Test
    fun `a tally sums priced calls and counts the unpriced ones`() {
        val t = BackendTally()
            .plus(CallUsage(Pricing.CODEX, listOf("m"), inputTokens = 10, outputTokens = 2), 0.5)
            .plus(CallUsage(Pricing.CODEX, listOf("m"), inputTokens = 5, outputTokens = 1), null)
        assertEquals(BackendTally(calls = 2, inputTokens = 15, outputTokens = 3, usd = 0.5, unpricedCalls = 1, models = listOf("m")), t)
    }

    @Test
    fun `usage reports reach only the bound sink and a failing sink never fails the call`() {
        val seen = CopyOnWriteArrayList<CallUsage>()
        Usage.report(CallUsage(Pricing.JEV)) // unbound: dropped
        Usage.within({ seen += it }) { Usage.report(CallUsage(Pricing.JEV, inputTokens = 3)) }
        assertEquals(listOf(3L), seen.map { it.inputTokens })
        Usage.within({ error("boom") }) { Usage.report(CallUsage(Pricing.JEV)) }
        Usage.within({ seen += it }) { Usage.capture("x") { error("unreadable") } }
        assertEquals(1, seen.size)
    }

    // ------------------------------------------------------------ engine

    private val schedulers = mutableListOf<VirtualThreadScheduler>()
    private val engines = mutableListOf<DeliberationEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { it.close() }
        schedulers.forEach { it.shutdown() }
    }

    private fun graph(log: java.io.File? = null): CredenceGraph {
        val s = VirtualThreadScheduler("deliberate-cost-${schedulers.size}").also { schedulers += it }
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = s, registry = registry, attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS))
        return CredenceGraph(host, registry, LayerSet.of(listOf("dfquad")), structureLog = log)
    }

    /** A proposer that reports a Claude call of [usd] (by question) and `id-side-n` arguments. */
    private class PricedProposer(
        val usdFor: (question: String) -> Double,
        val block: (ClaimContext) -> Unit = {},
    ) : Proposer {
        override val id = "claude"
        private val n = AtomicInteger()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        override fun propose(ctx: ClaimContext, side: Side, max: Int): List<String> {
            block(ctx)
            calls.computeIfAbsent(ctx.question) { AtomicInteger() }.incrementAndGet()
            Usage.report(CallUsage(Pricing.CLAUDE, listOf("claude-test"), inputTokens = 100, outputTokens = 10, reportedUsd = usdFor(ctx.question)))
            return List(max) { "$id-${side.name.lowercase()}-${n.incrementAndGet()}" }
        }
    }

    /** Every judgment is one Jev request of 1M input tokens ($0.042 at the default rate). */
    private class PricedJudge : Judge {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        private fun bill(question: String) {
            calls.computeIfAbsent(question) { AtomicInteger() }.incrementAndGet()
            Usage.report(CallUsage(Pricing.JEV, listOf("jev-test"), inputTokens = 1_000_000, outputTokens = 3))
        }
        override fun plausibility(question: String, path: List<String>, claim: String) = 0.5.also { bill(question) }
        override fun relationStrength(question: String, parent: String, child: String, side: Side) = 0.8.also { bill(question) }
        override fun quality(question: String, parent: String, child: String, side: Side) = 1.0.also { bill(question) }
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>) =
            candidates.map { Triage(TriageAction.ADD) }.also { bill(ctx.question) }
        override fun saturation(ctx: ClaimContext, side: Side) = 0.0.also { bill(ctx.question) }
        override fun relevance(ctx: ClaimContext) = 1.0.also { bill(ctx.question) }
    }

    private fun DeliberationEngine.idle() = assertTrue(awaitIdle(20.seconds), "engine did not go idle")

    @Test
    fun `every call is attributed to the question it serves`() {
        val proposer = PricedProposer({ if (it == "One?") 0.01 else 0.02 })
        val judge = PricedJudge()
        val e = DeliberationEngine(
            graph(), judge, listOf(proposer),
            DeliberationEngine.Config(argsPerCall = 1, maxRounds = 2, maxDepth = 0, maxArgsPerSide = 10),
        ).also { engines += it }
        e.ask("One?")
        e.ask("Two?")
        e.idle()
        val g = e.snapshot()
        for ((text, usd) in listOf("One?" to 0.01, "Two?" to 0.02)) {
            val q = g.questions.single { it.text == text }
            val claude = q.cost.backends.single { it.backend == "claude" }
            val jev = q.cost.backends.single { it.backend == "jev" }
            val proposals = proposer.calls.getValue(text).get()
            val judgments = judge.calls.getValue(text).get()
            assertEquals(proposals, claude.calls, "$text claude calls")
            assertEquals(judgments, jev.calls, "$text jev calls")
            near(proposals * usd, claude.usd, "$text claude usd")
            near(judgments * 0.042, jev.usd, "$text jev usd")
            near(proposals * usd + judgments * 0.042, q.costUsd, "$text total")
            assertEquals(listOf("claude-test"), claude.models)
            assertEquals(100L * proposals, claude.inputTokens)
            assertEquals(2, q.cost.rounds)
            // Fewer than 3 rounds: no projection yet.
            assertNull(q.projectedUsd)
            assertNull(q.cost.perRoundUsd)
        }
    }

    @Test
    fun `the projection is spent plus the claims to explore times the mean cost per round`() {
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val proposer = PricedProposer({ 0.01 }) { ctx ->
            if (ctx.path.isNotEmpty()) {
                blocked.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        // One worker; roundDecay 1 keeps the root's three rounds (priority 1) ahead of its children (0.8).
        val e = DeliberationEngine(
            graph(), PricedJudge(), listOf(proposer),
            DeliberationEngine.Config(argsPerCall = 1, maxRounds = 3, maxDepth = 1, maxArgsPerSide = 10, roundDecay = 1.0, workers = 1, minInfluence = 0.0),
        ).also { engines += it }
        try {
            e.ask("Grow?")
            assertTrue(blocked.await(20, TimeUnit.SECONDS))
            awaitUntil("the root finished its three rounds") {
                e.snapshot().nodes.any { it.depth == 0 && it.status == Status.ROUND_LIMIT }
            }
            val q = e.snapshot().questions.single()
            assertEquals(3, q.cost.rounds)
            // 3 rounds × 2 sides = 6 children, all still to explore.
            assertEquals(6, q.cost.queued)
            near(q.costUsd / 3, q.cost.perRoundUsd, "per round")
            near(q.costUsd + 6 * q.costUsd / 3, q.projectedUsd, "projection")
        } finally {
            release.countDown()
        }
        e.idle()
        val done = e.snapshot().questions.single()
        assertEquals(0, done.cost.queued)
        near(done.costUsd, done.projectedUsd, "nothing left to explore")
    }

    @Test
    fun `cost survives a restart as per-backend field deltas`() {
        val dir = java.nio.file.Files.createTempDirectory("deliberate-cost").toFile()
        try {
            val log = java.io.File(dir, "graph.jsonl")
            val store = InMemoryMetaStore()
            val config = DeliberationEngine.Config(argsPerCall = 1, maxRounds = 3, maxDepth = 0, maxArgsPerSide = 10)
            val e1 = DeliberationEngine(graph(log), PricedJudge(), listOf(PricedProposer({ 0.01 })), config, store = store)
                .also { engines += it }
            val root = e1.ask("Durable?")
            e1.idle()
            e1.close()
            val before = e1.snapshot().questions.single()
            assertTrue(before.costUsd > 0)
            assertNotNull(before.projectedUsd)
            val record = store.load().getValue("q:${root.id}")
            assertTrue("cost.claude" in record && "cost.jev" in record, record.keys.toString())

            val e2 = DeliberationEngine(graph(log), PricedJudge(), listOf(PricedProposer({ 0.01 })), config, store = store)
                .also { engines += it }
            e2.idle()
            assertEquals(before, e2.snapshot().questions.single())
        } finally {
            dir.deleteRecursively()
        }
    }
}
