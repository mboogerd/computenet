package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * SPEC §10 calibration harness: real Jev on real proposer output. Runs only
 * with `DELIBERATE_CALIBRATE=1` (plus `TYPESAFE_API_KEY`, and logged-in
 * `claude`/`codex` CLIs when the material cache is missing).
 *
 * 1. **Material** (cached in `src/test/resources/calibration/material.json`):
 *    for each question a chain root → d1 → d2 → d3, each node holding ≥ 6 pro
 *    and ≥ 6 con arguments generated the way the engine does it (both
 *    proposers per round, existing arguments shown, Jev-deduped).
 *    Delete the file (or set `DELIBERATE_CALIBRATE_REGEN=1`) to regenerate.
 * 2. **Measurement** (Jev only, every run): saturation per node/side at
 *    n = 0,1,2,3,4,6 arguments ([SAMPLES] samples each), and for every
 *    argument of nodes d0–d2 (claims at depth 1–3) relation strength,
 *    relevance, reach and influence = relevance × reach. Raw results go to
 *    `build/calibration/results.json`; summary tables are printed.
 * 3. **Exact-VoI stop calibration** (fresh every run): grow one bounded live
 *    tree per question with current Jev and Claude Sonnet, recording every
 *    response. Replay those recordings without model calls at each candidate
 *    epsilon, through the real engine and exact evaluator. Raw stop-point VoI,
 *    tree sizes and costs go to `build/calibration/voi-results.json`.
 *
 * `demo/deliberate/CALIBRATION.md` records the outcome and the chosen defaults.
 */
class CalibrationTest {

    @Serializable
    data class Arg(val text: String, val proposer: String)

    @Serializable
    data class Node(val depth: Int, val claim: String, val path: List<String>, val pros: List<Arg>, val cons: List<Arg>)

    @Serializable
    data class Material(val question: String, val chain: List<Node>)

    @Serializable
    data class SatSample(val question: Int, val depth: Int, val side: String, val n: Int, val saturation: List<Double>)

    @Serializable
    data class ArgSample(
        val question: Int,
        val depth: Int,
        val side: String,
        val index: Int,
        val parentReach: Double,
        val strength: Double,
        val relevance: Double,
    ) {
        val reach get() = parentReach * strength
        val influence get() = relevance * reach
    }

    @Serializable
    data class Results(val saturation: List<SatSample>, val args: List<ArgSample>)

    @Serializable
    data class StopSample(
        val kind: String,
        val depth: Int?,
        val status: String,
        val reason: String?,
        val rounds: Int?,
        /** Exact propagated-q-weighted expected root movement when this node first became terminal. */
        val voi: Double,
    )

    @Serializable
    data class VoiTree(
        val question: String,
        val epsilon: Double,
        val claims: Int,
        val exploredClaims: Int,
        val exploredLinks: Int,
        /** `budget`, `voi`, or `round/depth` when neither early question stop fired. */
        val stoppedBy: String,
        val stops: List<StopSample>,
        /** Non-zero only for the fresh live run; replays make no external calls. */
        val cost: CostDto,
    )

    @Serializable
    data class VoiResults(
        val generatedWithEpsilon: Double,
        val generatedMaxClaims: Int,
        val replayMaxClaims: Int,
        val maxDepth: Int,
        val maxRounds: Int,
        val proposer: String,
        val candidates: List<Double>,
        val live: List<VoiTree>,
        val replays: List<VoiTree>,
    )

    private val json = Json { prettyPrint = true }
    private val cache = File("src/test/resources/calibration/material.json")

    private companion object {
        const val SAMPLES = 2
        const val PER_SIDE = 6
        val COUNTS = listOf(0, 1, 2, 3, 4, 6)
        val QUESTIONS = listOf(
            "Should cities make public transport free?", // policy
            "Does regular moderate coffee consumption lower the risk of type 2 diabetes?", // empirical
            "Should I rent rather than buy a home if I expect to move within five years?", // personal/practical
        )
        /** Which side of node d the chain continues through to reach node d+1. */
        val CHAIN = listOf(Polarity.SUPPORT, Polarity.ATTACK, Polarity.SUPPORT)
    }

    @Test
    fun calibrate() {
        assumeTrue(System.getenv("DELIBERATE_CALIBRATE") == "1", "set DELIBERATE_CALIBRATE=1 to run the calibration")
        val jev = JevJudge()
        val material = loadOrGenerate(jev)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = measure(jev, material, pool)
            File("build/calibration").mkdirs()
            File("build/calibration/results.json").writeText(json.encodeToString(Results.serializer(), results))
            val text = report(results)
            File("build/calibration/report.txt").writeText(text)
            println(text)
        } finally {
            pool.shutdownNow()
        }

        val voi = calibrateVoi()
        File("build/calibration/voi-results.json").writeText(json.encodeToString(VoiResults.serializer(), voi))
        val voiText = reportVoi(voi)
        File("build/calibration/voi-report.txt").writeText(voiText)
        println(voiText)
    }

    // ------------------------------------------------------------ material

    private fun loadOrGenerate(jev: JevJudge): List<Material> {
        if (cache.isFile && System.getenv("DELIBERATE_CALIBRATE_REGEN") != "1") {
            return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Material.serializer()), cache.readText())
        }
        val gate = ProcessGate(8)
        val proposers = listOf(CliProposer.claude(gate), CliProposer.codex(gate))
        val pool = Executors.newFixedThreadPool(QUESTIONS.size)
        val material = try {
            QUESTIONS.map { q -> pool.submit(Callable { chain(q, jev, proposers) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        cache.parentFile.mkdirs()
        cache.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Material.serializer()), material))
        return material
    }

    private fun chain(question: String, jev: JevJudge, proposers: List<Proposer>): Material {
        val nodes = mutableListOf<Node>()
        var claim = question
        var path = emptyList<String>()
        for (depth in 0..3) {
            val node = grow(question, depth, path, claim, jev, proposers)
            nodes += node
            System.err.println("calibration: ${question.take(30)} d$depth: ${node.pros.size} pro / ${node.cons.size} con")
            if (depth < 3) {
                val next = (if (CHAIN[depth] == Polarity.SUPPORT) node.pros else node.cons).first().text
                path = path + claim
                claim = next
            }
        }
        return Material(question, nodes)
    }

    /** Rounds as the engine runs them, until both sides hold [PER_SIDE] arguments (≤ 4 rounds). */
    private fun grow(question: String, depth: Int, path: List<String>, claim: String, jev: JevJudge, proposers: List<Proposer>): Node {
        val pros = mutableListOf<Arg>()
        val cons = mutableListOf<Arg>()
        val calls = Executors.newVirtualThreadPerTaskExecutor()
        try {
            repeat(4) {
                val sides = listOf(Polarity.SUPPORT to pros, Polarity.ATTACK to cons).filter { it.second.size < PER_SIDE }
                if (sides.isEmpty()) return@repeat
                val ctx = ClaimContext(question, path, claim, pros.map { it.text }, cons.map { it.text })
                val futures = sides.flatMap { (side, list) ->
                    proposers.map { p -> Triple(side, list, p.id to calls.submit(Callable { p.propose(ctx, side, 3) })) }
                }
                // Interleave proposers so the first n arguments are a fair mix.
                for ((side, list) in sides) {
                    val got = futures.filter { it.first == side }.map { (_, _, pf) ->
                        pf.first to runCatching { pf.second.get() }.onFailure { System.err.println("calibration: ${pf.first} failed: $it") }.getOrDefault(emptyList())
                    }
                    val candidates = (0 until (got.maxOfOrNull { it.second.size } ?: 0)).flatMap { i ->
                        got.mapNotNull { (id, texts) -> texts.getOrNull(i)?.let { Arg(it, id) } }
                    }
                    for (c in candidates) {
                        if (list.size >= PER_SIDE + 2) break
                        val existing = list.map { it.text }
                        val now = ClaimContext(question, path, claim, pros.map { it.text }, cons.map { it.text })
                        val verdict = runCatching { jev.triage(now, listOf(Candidate(c.text, side))).single().action }.getOrNull()
                        val dup = verdict == TriageAction.DUPLICATE || verdict == TriageAction.REPLACE
                        if (!dup && existing.none { it.equals(c.text, ignoreCase = true) }) list += c
                    }
                }
            }
        } finally {
            calls.shutdownNow()
        }
        return Node(depth, claim, path, pros, cons)
    }

    // ------------------------------------------------------------ measurement

    private fun measure(jev: JevJudge, material: List<Material>, pool: java.util.concurrent.ExecutorService): Results {
        val sat = material.flatMapIndexed { qi, m ->
            m.chain.flatMap { node ->
                listOf(Polarity.SUPPORT to node.pros, Polarity.ATTACK to node.cons).flatMap { (side, args) ->
                    COUNTS.filter { it <= args.size }.map { n ->
                        val ctx = ClaimContext(m.question, node.path, node.claim,
                            if (side == Polarity.SUPPORT) args.take(n).map { it.text } else emptyList(),
                            if (side == Polarity.ATTACK) args.take(n).map { it.text } else emptyList())
                        val fs = List(SAMPLES) { pool.submit(Callable { jev.saturation(ctx, side) }) }
                        Triple(qi, node, Triple(side, n, fs))
                    }
                }
            }
        }.map { (qi, node, t) -> SatSample(qi, node.depth, t.first.name, t.second, t.third.map { it.get() }) }

        // Strength + relevance for every argument of chain nodes d0..d2 (claims at depth 1..3).
        data class Pending(val qi: Int, val node: Node, val side: Side, val index: Int,
                           val strength: java.util.concurrent.Future<Double>, val relevance: java.util.concurrent.Future<Double>)
        val pending = material.flatMapIndexed { qi, m ->
            m.chain.filter { it.depth <= 2 }.flatMap { node ->
                listOf(Polarity.SUPPORT to node.pros, Polarity.ATTACK to node.cons).flatMap { (side, args) ->
                    args.take(PER_SIDE).mapIndexed { i, a ->
                        Pending(qi, node, side, i,
                            pool.submit(Callable { jev.relationStrength(m.question, node.claim, a.text, side) }),
                            pool.submit(Callable {
                                jev.relevance(ClaimContext(m.question, node.path + node.claim, a.text, emptyList(), emptyList()))
                            }))
                    }
                }
            }
        }
        val measured = pending.map { Triple(it, it.strength.get(), it.relevance.get()) }
        // Reach of chain node d = product of the strengths of the chain edges above it.
        val args = measured.map { (p, s, r) ->
            var reach = 1.0
            for (d in 0 until p.node.depth) {
                val chainSide = CHAIN[d]
                reach *= measured.single { (q, _, _) -> q.qi == p.qi && q.node.depth == d && q.side == chainSide && q.index == 0 }.second
            }
            ArgSample(p.qi, p.node.depth + 1, p.side.name, p.index, reach, s, r)
        }
        return Results(sat, args)
    }

    private fun report(r: Results): String = buildString {
        fun f(x: Double) = "%.2f".format(x)
        fun q(xs: List<Double>): String {
            val s = xs.sorted()
            fun at(p: Double) = s[((s.size - 1) * p).toInt()]
            return "min ${f(s.first())} p25 ${f(at(.25))} med ${f(at(.5))} p75 ${f(at(.75))} max ${f(s.last())}"
        }
        appendLine("CALIBRATION saturation (mean over sides/questions/samples) by claim depth × n arguments")
        for (d in 0..3) {
            val row = COUNTS.joinToString("  ") { n ->
                val xs = r.saturation.filter { it.depth == d && it.n == n }.flatMap { it.saturation }
                if (xs.isEmpty()) "n$n=–" else "n$n=${f(xs.average())}"
            }
            appendLine("  d$d  $row")
        }
        for (n in COUNTS) {
            val xs = r.saturation.filter { it.n == n }.flatMap { it.saturation }
            if (xs.isNotEmpty()) appendLine("  n=$n  ${q(xs)}")
        }
        appendLine("CALIBRATION per argument depth: strength / relevance / reach / influence")
        for (d in 1..3) {
            val a = r.args.filter { it.depth == d }
            appendLine("  depth $d (${a.size}): strength ${q(a.map { it.strength })}")
            appendLine("            relevance ${q(a.map { it.relevance })}")
            appendLine("            reach     ${q(a.map { it.reach })}")
            appendLine("            influence ${q(a.map { it.influence })}")
        }
    }

    // ------------------------------------------------------------ exact VoI

    /**
     * The live sample is intentionally small and sequential: three questions,
     * one proposer, one worker and one round per node. Depth 1 still exercises
     * both claims and links; the full live recording contains every call a
     * candidate replay can make. The replay cap is one claim below the live
     * cap so a weak epsilon can visibly hand the stop to the budget.
     */
    private fun calibrateVoi(): VoiResults {
        val candidates = listOf(0.0, 0.0025, 0.005, 0.01, 0.02, 0.04)
        val generated = DeliberationEngine.Config(
            argsPerCall = 1,
            maxRounds = 1,
            maxDepth = 1,
            maxClaims = 12,
            workers = 1,
            voiEpsilon = 0.0,
            exploreLinks = true,
        )
        val replay = generated.copy(maxClaims = 11)
        val gate = ProcessGate(1)
        val live = mutableListOf<VoiTree>()
        val replays = mutableListOf<VoiTree>()

        for (question in QUESTIONS) {
            val tape = CallTape(JevJudge(), CliProposer.claude(gate, model = "sonnet"))
            live += runTree(question, generated, tape.recordingJudge, tape.recordingProposer)
            for (epsilon in candidates) {
                replays += runTree(question, replay.copy(voiEpsilon = epsilon), tape.replayJudge, tape.replayProposer)
                check(tape.misses.isEmpty()) {
                    "candidate epsilon $epsilon asked for calls absent from the full live recording: ${tape.misses.joinToString()}"
                }
            }
        }
        return VoiResults(
            generatedWithEpsilon = generated.voiEpsilon,
            generatedMaxClaims = generated.maxClaims,
            replayMaxClaims = replay.maxClaims,
            maxDepth = generated.maxDepth,
            maxRounds = generated.maxRounds,
            proposer = "claude/sonnet",
            candidates = candidates,
            live = live,
            replays = replays,
        )
    }

    /** Run one real engine tree and capture each non-root node's exact VoI on its first terminal transition. */
    private fun runTree(question: String, config: DeliberationEngine.Config, judge: Judge, proposer: Proposer): VoiTree {
        val scheduler = VirtualThreadScheduler("deliberate-calibration")
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = scheduler,
            registry = registry,
            attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
        )
        val semantics = DeliberateApp.SemanticsConfig()
        val graph = CredenceGraph(
            host,
            registry,
            LayerSet.of(semantics.running, semantics.consensus, semantics.headline, semantics.wlo),
        )
        val engineRef = AtomicReference<DeliberationEngine?>()
        val rootRef = AtomicReference<CellRef?>()
        val stops = linkedMapOf<String, StopSample>()
        val capture: () -> Unit = capture@{
            val engine = engineRef.get() ?: return@capture
            val root = rootRef.get() ?: return@capture
            val snapshot = engine.snapshot()
            synchronized(stops) {
                snapshot.nodes.asSequence()
                    .filter { it.ref != root.id.toString() && it.status in ExplorationPolicy.FINISHED && it.ref !in stops }
                    .forEach { node ->
                        val ref = CellRef(UUID.fromString(node.ref))
                        val value = graph.exactValueOf(ref, listOf(root))?.expectedRootChange ?: return@forEach
                        stops[node.ref] = StopSample(
                            kind = node.kind,
                            depth = node.depth,
                            status = node.status!!.name,
                            reason = node.reason?.name,
                            rounds = node.rounds,
                            voi = value,
                        )
                    }
            }
        }
        val engine = DeliberationEngine(graph, judge, listOf(proposer), config, onChange = capture)
        engineRef.set(engine)
        return try {
            rootRef.set(engine.ask(question))
            check(engine.awaitIdle(20.minutes)) { "calibration tree did not become idle: $question" }
            capture()
            val snapshot = engine.snapshot()
            val q = snapshot.questions.single()
            val claims = snapshot.nodes.filter { it.kind == "CLAIM" }
            val links = snapshot.nodes.filter { it.kind == "EDGE" }
            VoiTree(
                question = question,
                epsilon = config.voiEpsilon,
                claims = q.claims,
                exploredClaims = claims.count { (it.rounds ?: 0) > 0 },
                exploredLinks = links.count { (it.rounds ?: 0) > 0 },
                stoppedBy = q.stoppedBy ?: "round/depth",
                stops = synchronized(stops) { stops.values.toList() },
                cost = q.cost,
            )
        } finally {
            engine.close()
            scheduler.shutdown()
        }
    }

    private fun reportVoi(r: VoiResults): String = buildString {
        fun f(x: Double) = "%.4f".format(x)
        fun distribution(xs: List<Double>): String {
            if (xs.isEmpty()) return "n=0"
            val s = xs.sorted()
            fun at(p: Double) = s[((s.size - 1) * p).toInt()]
            return "n=${s.size} min=${f(s.first())} p25=${f(at(.25))} med=${f(at(.5))} p75=${f(at(.75))} max=${f(s.last())}"
        }
        appendLine("CALIBRATION exact q-weighted VoI on fresh bounded live trees")
        appendLine("  questions=${r.live.size}; proposer=${r.proposer}; maxDepth=${r.maxDepth}; maxRounds=${r.maxRounds}; " +
            "live maxClaims=${r.generatedMaxClaims}; replay maxClaims=${r.replayMaxClaims}")
        appendLine("  live costUsd=${"%.6f".format(r.live.sumOf { it.cost.backends.sumOf { b -> b.usd ?: 0.0 } })}")
        for (tree in r.live) {
            val calls = tree.cost.backends.sumOf { it.calls }
            appendLine("  live ${tree.question}: claims=${tree.claims}, explored claims=${tree.exploredClaims}, " +
                "explored links=${tree.exploredLinks}, calls=$calls, costUsd=${"%.6f".format(tree.cost.backends.sumOf { it.usd ?: 0.0 })}")
        }
        for (epsilon in r.candidates) {
            val trees = r.replays.filter { it.epsilon == epsilon }
            val byStop = trees.groupingBy { it.stoppedBy }.eachCount().toSortedMap()
            appendLine("  eps=${f(epsilon)} stop=$byStop")
            for (tree in trees) {
                appendLine("    ${tree.question}: claims=${tree.claims}, explored claims=${tree.exploredClaims}, " +
                    "explored links=${tree.exploredLinks}")
            }
            val stopValues = trees.flatMap { it.stops }.map { it.voi }
            appendLine("    stop-point VoI ${distribution(stopValues)}")
            trees.flatMap { it.stops }.groupBy { it.reason ?: it.status }.toSortedMap().forEach { (reason, samples) ->
                appendLine("      $reason ${distribution(samples.map { it.voi })}")
            }
        }
    }

    /** Live-call tape: candidate runs replay the same material with no new external call. */
    private class CallTape(private val judge: Judge, private val proposer: Proposer) {
        private data class Outcome<T>(val value: T? = null, val failure: String? = null) {
            fun get(): T = value ?: error("recorded call failed: $failure")
        }

        private class Calls<K, V>(private val misses: MutableList<String>, private val name: String) {
            private val calls = ConcurrentHashMap<K, Outcome<V>>()

            fun record(key: K, call: () -> V): V = try {
                call().also { calls[key] = Outcome(value = it) }
            } catch (t: Throwable) {
                calls[key] = Outcome(failure = t.toString())
                throw t
            }

            fun replay(key: K): V {
                val result = calls[key]
                if (result == null) {
                    synchronized(misses) { misses += "$name: $key" }
                    error("no recorded $name call for $key")
                }
                return result.get()
            }
        }

        private data class ProposeKey(val ctx: ClaimContext, val side: Side, val max: Int)
        private data class PlausibilityKey(val question: String, val claim: String)
        private data class AssessKey(val question: String, val path: List<String>, val child: String, val side: Side)
        private data class TriageKey(val ctx: ClaimContext, val candidates: List<Candidate>)
        private data class SaturationKey(val ctx: ClaimContext, val side: Side)
        private data class BearingKey(val ctx: ClaimContext, val link: LinkContext, val candidates: List<String>)

        val misses = mutableListOf<String>()
        private val proposals = Calls<ProposeKey, List<String>>(misses, "proposer")
        private val plausibilities = Calls<PlausibilityKey, Double>(misses, "plausibility")
        private val assessments = Calls<AssessKey, Assessment>(misses, "assessment")
        private val triages = Calls<TriageKey, List<Triage>>(misses, "triage")
        private val saturations = Calls<SaturationKey, Double>(misses, "saturation")
        private val bearings = Calls<BearingKey, List<Bearing>>(misses, "bearing")

        val recordingProposer = object : Proposer {
            override val id = proposer.id
            override fun propose(ctx: ClaimContext, side: Side, max: Int) =
                proposals.record(ProposeKey(ctx, side, max)) { proposer.propose(ctx, side, max) }
        }
        val replayProposer = object : Proposer {
            override val id = proposer.id
            override fun propose(ctx: ClaimContext, side: Side, max: Int) = proposals.replay(ProposeKey(ctx, side, max))
        }
        val recordingJudge = object : Judge {
            override fun plausibility(question: String, claim: String) =
                plausibilities.record(PlausibilityKey(question, claim)) { judge.plausibility(question, claim) }
            override fun assess(question: String, path: List<String>, child: String, side: Side) =
                assessments.record(AssessKey(question, path, child, side)) { judge.assess(question, path, child, side) }
            override fun triage(ctx: ClaimContext, candidates: List<Candidate>) =
                triages.record(TriageKey(ctx, candidates)) { judge.triage(ctx, candidates) }
            override fun saturation(ctx: ClaimContext, side: Side) =
                saturations.record(SaturationKey(ctx, side)) { judge.saturation(ctx, side) }
            override fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>) =
                bearings.record(BearingKey(ctx, link, candidates)) { judge.bearing(ctx, link, candidates) }
        }
        val replayJudge = object : Judge {
            override fun plausibility(question: String, claim: String) = plausibilities.replay(PlausibilityKey(question, claim))
            override fun assess(question: String, path: List<String>, child: String, side: Side) =
                assessments.replay(AssessKey(question, path, child, side))
            override fun triage(ctx: ClaimContext, candidates: List<Candidate>) = triages.replay(TriageKey(ctx, candidates))
            override fun saturation(ctx: ClaimContext, side: Side) = saturations.replay(SaturationKey(ctx, side))
            override fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>) =
                bearings.replay(BearingKey(ctx, link, candidates))
        }
    }
}
