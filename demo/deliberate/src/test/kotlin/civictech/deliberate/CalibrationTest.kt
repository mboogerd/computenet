package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * 3. **Exact-VoI stop calibration**: grow one production-shaped live tree per
 *    question with current Jev and Claude Sonnet, recording every response to
 *    `src/test/resources/calibration/voi-tape/voi-tape.json`. Replay that tape
 *    without model calls at each candidate epsilon, through the real engine
 *    and exact evaluator. Raw stop-point VoI, tree sizes and costs go to
 *    `build/calibration/voi-results.json`. Run [renderVoiFromPersistedTape]
 *    to re-render those outputs from the committed tape without live calls.
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
    data class MeasurementCost(
        val calls: Int = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val usd: Double = 0.0,
    )

    @Serializable
    data class Results(
        val saturation: List<SatSample>,
        val args: List<ArgSample>,
        val saturationCost: MeasurementCost = MeasurementCost(),
        val totalCost: MeasurementCost = MeasurementCost(),
    )

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
        val maxClaims: Int = -1,
        val maxDepth: Int = -1,
        val maxRounds: Int = -1,
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
        /** Why this tape cannot support candidate replay; null for a complete sequential tape. */
        val replayFailure: String? = null,
    )

    @Serializable
    data class TapeCall(
        val name: String,
        val key: String,
        val value: String? = null,
        val failure: String? = null,
        /** Response number for repeated identical requests. Old tapes collapsed these and omit it. */
        val occurrence: Int = 0,
    )

    @Serializable
    data class VoiTapeTree(
        val question: String,
        val live: VoiTree,
        val calls: List<TapeCall>,
        /** Missing in the first checkpoint written by this calibration; then [VoiTape.maxClaims] applies. */
        val maxClaims: Int? = null,
    )

    @Serializable
    data class VoiTape(
        val generatedWithEpsilon: Double,
        val maxClaims: Int,
        val maxDepth: Int,
        val maxRounds: Int,
        val argsPerCall: Int,
        val proposer: String,
        val trees: List<VoiTapeTree>,
        /** False for the interrupted 2026-10-04 run, whose first writer collapsed repeated requests. */
        val sequentialCalls: Boolean = false,
    )

    @Serializable
    private data class TapeLink(val argument: String, val parent: String, val side: String)

    @Serializable
    private data class TapeContext(
        val question: String,
        val path: List<String>,
        val claim: String,
        val pros: List<String>,
        val cons: List<String>,
        val link: TapeLink? = null,
    )

    @Serializable
    private data class TapeCandidate(val text: String, val side: String)

    @Serializable
    private data class TapeKey(
        val kind: String,
        val context: TapeContext? = null,
        val question: String? = null,
        val claim: String? = null,
        val path: List<String> = emptyList(),
        val child: String? = null,
        val side: String? = null,
        val max: Int? = null,
        val candidates: List<TapeCandidate> = emptyList(),
        val link: TapeLink? = null,
        val texts: List<String> = emptyList(),
    )

    @Serializable
    private data class TapeAssessment(
        val plausibility: Double,
        val strength: Double,
        val quality: Double,
        val relevance: Double,
    )

    @Serializable
    private data class TapeTriage(val action: String, val target: Int? = null)

    private val json = Json { prettyPrint = true }
    private val cache = File("src/test/resources/calibration/material.json")
    private val voiTapeFile = File("src/test/resources/calibration/voi-tape/voi-tape.json")

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
        val VOI_CANDIDATES = listOf(0.0, 0.005, 0.01, 0.02, 0.04, 0.06, 0.08, 0.10, 0.12, 0.16)
        /** Three equal caps projected at $4.75 from the first two completed trees, leaving $0.25 headroom. */
        val VOI_MAX_CLAIMS = listOf(110, 110, 110)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun calibrate() {
        assumeTrue(System.getenv("DELIBERATE_CALIBRATE") == "1", "set DELIBERATE_CALIBRATE=1 to run the calibration")
        val jev = JevJudge()
        val material = loadOrGenerate(jev)
        val resultsFile = File("build/calibration/results.json")
        if (resultsFile.isFile && System.getenv("DELIBERATE_CALIBRATE_REGEN") != "1") {
            println(report(json.decodeFromString(Results.serializer(), resultsFile.readText())))
        } else {
            val pool = Executors.newFixedThreadPool(8)
            try {
                val results = measure(jev, material, pool)
                File("build/calibration").mkdirs()
                resultsFile.writeText(json.encodeToString(Results.serializer(), results))
                val text = report(results)
                File("build/calibration/report.txt").writeText(text)
                println(text)
            } finally {
                pool.shutdownNow()
            }
        }

        val tape = recordVoiTape()
        writeVoiOutputs(replayVoi(tape))
    }

    @Test
    fun renderVoiFromPersistedTape() {
        writeVoiOutputs(loadVoiTape())
    }

    @Test
    fun `persistent tape preserves repeated responses to an identical request`() {
        val response = AtomicInteger()
        val proposer = object : Proposer {
            override val id = "recorded"
            override fun propose(ctx: ClaimContext, side: Side, max: Int) =
                listOf("response ${response.incrementAndGet()}")
        }
        val context = ClaimContext("question", emptyList(), "claim", emptyList(), emptyList())
        val recording = CallTape(proposer = proposer)
        assertEquals(listOf("response 1"), recording.recordingProposer.propose(context, Polarity.SUPPORT, 1))
        assertEquals(listOf("response 2"), recording.recordingProposer.propose(context, Polarity.SUPPORT, 1))

        val saved = recording.snapshot()
        assertEquals(listOf(0, 1), saved.filter { it.name == "proposer" }.map { it.occurrence })
        val replay = CallTape(saved = saved, proposerId = proposer.id)
        assertEquals(listOf("response 1"), replay.replayProposer.propose(context, Polarity.SUPPORT, 1))
        assertEquals(listOf("response 2"), replay.replayProposer.propose(context, Polarity.SUPPORT, 1))
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
        val allUsage = UsageCollector()
        val saturationUsage = UsageCollector()
        val saturationSink = UsageSink { usage ->
            allUsage.record(usage)
            saturationUsage.record(usage)
        }
        fun <T> measured(sink: UsageSink = allUsage, call: () -> T) =
            pool.submit(Callable { Usage.within(sink, call) })

        val sat = material.flatMapIndexed { qi, m ->
            m.chain.flatMap { node ->
                listOf(Polarity.SUPPORT to node.pros, Polarity.ATTACK to node.cons).flatMap { (side, args) ->
                    COUNTS.filter { it <= args.size }.map { n ->
                        val ctx = ClaimContext(m.question, node.path, node.claim,
                            if (side == Polarity.SUPPORT) args.take(n).map { it.text } else emptyList(),
                            if (side == Polarity.ATTACK) args.take(n).map { it.text } else emptyList())
                        val fs = List(SAMPLES) { measured(saturationSink) { jev.saturation(ctx, side) } }
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
                            measured { jev.relationStrength(m.question, node.claim, a.text, side) },
                            measured {
                                jev.relevance(ClaimContext(m.question, node.path + node.claim, a.text, emptyList(), emptyList()))
                            })
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
        return Results(sat, args, saturationUsage.summary(), allUsage.summary())
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
        appendLine(
            "CALIBRATION saturation external calls=${r.saturationCost.calls}, " +
                "costUsd=${"%.6f".format(r.saturationCost.usd)} " +
                "(${r.saturationCost.inputTokens} input / ${r.saturationCost.outputTokens} output tokens)",
        )
        appendLine(
            "CALIBRATION all Jev measurement calls=${r.totalCost.calls}, " +
                "costUsd=${"%.6f".format(r.totalCost.usd)}",
        )
        appendLine("CALIBRATION per argument depth: strength / relevance / reach / influence")
        for (d in 1..3) {
            val a = r.args.filter { it.depth == d }
            appendLine("  depth $d (${a.size}): strength ${q(a.map { it.strength })}")
            appendLine("            relevance ${q(a.map { it.relevance })}")
            appendLine("            reach     ${q(a.map { it.reach })}")
            appendLine("            influence ${q(a.map { it.influence })}")
        }
    }

    private class UsageCollector : UsageSink {
        private val usage = ConcurrentLinkedQueue<CallUsage>()

        override fun record(usage: CallUsage) {
            this.usage += usage
        }

        fun summary(): MeasurementCost {
            val calls = usage.toList()
            val pricing = Pricing()
            return MeasurementCost(
                calls = calls.size,
                inputTokens = calls.sumOf { it.inputTokens },
                outputTokens = calls.sumOf { it.outputTokens },
                usd = calls.sumOf { pricing.price(it) ?: 0.0 },
            )
        }
    }

    // ------------------------------------------------------------ exact VoI

    /**
     * Production rounds and depth, with the largest practical claim cap that
     * leaves headroom under this run's $5 live-call ceiling. The epsilon-zero
     * recording is a superset of every positive-epsilon replay.
     */
    private fun recordVoiTape(): VoiTape {
        val base = DeliberationEngine.Config(
            argsPerCall = 1,
            maxRounds = 3,
            workers = 1,
            voiEpsilon = 0.0,
            exploreLinks = true,
        )
        val gate = ProcessGate(1)
        val previous = if (voiTapeFile.isFile && System.getenv("DELIBERATE_CALIBRATE_REGEN") != "1") {
            loadVoiTape()
        } else {
            null
        }
        // The first persistent writer keyed calls as a map. Its two useful live
        // trees remain evidence, but appending to that tape would falsely imply
        // that repeated equal requests can be replayed in their original order.
        if (previous != null && !previous.sequentialCalls) return previous
        val trees = previous?.trees
            ?.map { if (it.maxClaims == null) it.copy(maxClaims = previous.maxClaims) else it }
            ?.toMutableList()
            ?: mutableListOf()

        for ((index, question) in QUESTIONS.withIndex().drop(trees.size)) {
            val generated = base.copy(maxClaims = VOI_MAX_CLAIMS[index])
            val tape = CallTape(JevJudge(), CliProposer.claude(gate, model = "sonnet"))
            val live = runTree(question, generated, tape.recordingJudge, tape.recordingProposer)
            trees += VoiTapeTree(question, live, tape.snapshot(), generated.maxClaims)
            persistVoiTape(generated, trees, sequentialCalls = true)
        }
        return persistVoiTape(base.copy(maxClaims = VOI_MAX_CLAIMS.max()), trees, sequentialCalls = true)
    }

    private fun persistVoiTape(
        config: DeliberationEngine.Config,
        trees: List<VoiTapeTree>,
        sequentialCalls: Boolean,
    ): VoiTape {
        val tape = VoiTape(
            generatedWithEpsilon = config.voiEpsilon,
            maxClaims = config.maxClaims,
            maxDepth = config.maxDepth,
            maxRounds = config.maxRounds,
            argsPerCall = config.argsPerCall,
            proposer = "claude/sonnet",
            trees = trees,
            sequentialCalls = sequentialCalls,
        )
        voiTapeFile.parentFile.mkdirs()
        voiTapeFile.writeText(json.encodeToString(VoiTape.serializer(), tape))
        return tape
    }

    private fun loadVoiTape(): VoiTape {
        check(voiTapeFile.isFile) { "persisted VoI tape is missing: $voiTapeFile" }
        return json.decodeFromString(VoiTape.serializer(), voiTapeFile.readText())
    }

    private fun replayVoi(tape: VoiTape): VoiResults {
        check(tape.trees.isNotEmpty() && tape.trees.map { it.question } == QUESTIONS.take(tape.trees.size)) {
            "VoI tape questions are not a prefix of the calibration questions"
        }
        val live = tape.trees.map { tree ->
            tree.live.copy(
                maxClaims = tree.maxClaims ?: tape.maxClaims,
                maxDepth = tape.maxDepth,
                maxRounds = tape.maxRounds,
            )
        }
        if (!tape.sequentialCalls) {
            return VoiResults(
                generatedWithEpsilon = tape.generatedWithEpsilon,
                generatedMaxClaims = live.maxOf { it.maxClaims },
                replayMaxClaims = live.maxOf { it.maxClaims },
                maxDepth = tape.maxDepth,
                maxRounds = tape.maxRounds,
                proposer = tape.proposer,
                candidates = VOI_CANDIDATES,
                live = live,
                replays = emptyList(),
                replayFailure = "the interrupted run's tape collapsed repeated identical requests; " +
                    "its live outcomes are reproducible as data, but epsilon counterfactuals are not",
            )
        }
        val replay = DeliberationEngine.Config(
            argsPerCall = tape.argsPerCall,
            maxRounds = tape.maxRounds,
            maxDepth = tape.maxDepth,
            maxClaims = tape.maxClaims,
            workers = 1,
            voiEpsilon = tape.generatedWithEpsilon,
            exploreLinks = true,
        )
        val replays = mutableListOf<VoiTree>()
        for (tree in tape.trees) {
            val maxClaims = tree.maxClaims ?: tape.maxClaims
            for (epsilon in VOI_CANDIDATES) {
                val calls = CallTape(saved = tree.calls, proposerId = tape.proposer.substringBefore('/'))
                replays += runTree(
                    tree.question,
                    replay.copy(maxClaims = maxClaims, voiEpsilon = epsilon),
                    calls.replayJudge,
                    calls.replayProposer,
                )
                check(calls.misses.isEmpty()) {
                    "candidate epsilon $epsilon asked for calls absent from the persisted tape: ${calls.misses.joinToString()}"
                }
            }
        }
        return VoiResults(
            generatedWithEpsilon = tape.generatedWithEpsilon,
            generatedMaxClaims = tape.trees.maxOf { it.maxClaims ?: tape.maxClaims },
            replayMaxClaims = tape.trees.maxOf { it.maxClaims ?: tape.maxClaims },
            maxDepth = tape.maxDepth,
            maxRounds = tape.maxRounds,
            proposer = tape.proposer,
            candidates = VOI_CANDIDATES,
            live = live,
            replays = replays,
        )
    }

    private fun writeVoiOutputs(tape: VoiTape) = writeVoiOutputs(replayVoi(tape))

    private fun writeVoiOutputs(results: VoiResults) {
        File("build/calibration").mkdirs()
        File("build/calibration/voi-results.json").writeText(json.encodeToString(VoiResults.serializer(), results))
        val report = reportVoi(results)
        File("build/calibration/voi-report.txt").writeText(report)
        println(report)
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
                maxClaims = config.maxClaims,
                maxDepth = config.maxDepth,
                maxRounds = config.maxRounds,
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
        appendLine("CALIBRATION exact q-weighted VoI on recorded live trees")
        val caps = r.live.joinToString { "${it.question.take(12)}:${it.maxClaims}" }
        appendLine(
            "  questions=${r.live.size}; proposer=${r.proposer}; maxDepth=${r.maxDepth}; maxRounds=${r.maxRounds}; " +
                "maxClaims=$caps",
        )
        appendLine("  live costUsd=${"%.6f".format(r.live.sumOf { it.cost.backends.sumOf { b -> b.usd ?: 0.0 } })}")
        for (tree in r.live) {
            val calls = tree.cost.backends.sumOf { it.calls }
            appendLine("  live ${tree.question}: claims=${tree.claims}, explored claims=${tree.exploredClaims}, " +
                "explored links=${tree.exploredLinks}, calls=$calls, costUsd=${"%.6f".format(tree.cost.backends.sumOf { it.usd ?: 0.0 })}")
        }
        if (r.replayFailure != null) {
            appendLine("  candidate replay unavailable: ${r.replayFailure}")
        }
        for (epsilon in r.candidates.takeIf { r.replayFailure == null } ?: emptyList()) {
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

    /** Persisted live-call tape: candidate runs replay the same material with no external call. */
    private inner class CallTape(
        private val judge: Judge? = null,
        private val proposer: Proposer? = null,
        private val saved: List<TapeCall> = emptyList(),
        private val proposerId: String = proposer?.id ?: "claude",
    ) {
        private inner class Calls<V>(
            private val name: String,
            private val encode: (V) -> String,
            private val decode: (String) -> V,
        ) {
            private val calls = ConcurrentLinkedQueue<TapeCall>().apply { addAll(saved.filter { it.name == name }) }
            private val recordedOccurrences = ConcurrentHashMap<String, AtomicInteger>()
            private val replayOccurrences = ConcurrentHashMap<String, AtomicInteger>()
            private val savedByKey = saved.filter { it.name == name }.groupBy { it.key }

            fun record(key: String, call: () -> V): V {
                // Number at invocation, not completion: concurrent responses may
                // finish out of order, while replay must follow request order.
                val occurrence = recordedOccurrences.computeIfAbsent(key) { AtomicInteger() }.getAndIncrement()
                return try {
                    call().also {
                        calls += TapeCall(name, key, value = encode(it), occurrence = occurrence)
                    }
                } catch (t: Throwable) {
                    calls += TapeCall(name, key, failure = t.toString(), occurrence = occurrence)
                    throw t
                }
            }

            fun replay(key: String): V {
                val occurrence = replayOccurrences.computeIfAbsent(key) { AtomicInteger() }.getAndIncrement()
                val result = savedByKey[key]?.singleOrNull { it.occurrence == occurrence }
                if (result == null) {
                    synchronized(misses) { misses += "$name[$occurrence]: $key" }
                    error("no recorded $name call for $key")
                }
                result.failure?.let { error("recorded $name call failed: $it") }
                return decode(checkNotNull(result.value) { "recorded $name call has no value" })
            }

            fun snapshot(): List<TapeCall> = calls.sortedWith(compareBy(TapeCall::key, TapeCall::occurrence))
        }

        val misses = mutableListOf<String>()
        private val strings = ListSerializer(String.serializer())
        private val triageValues = ListSerializer(TapeTriage.serializer())
        private val proposals = Calls("proposer", { json.encodeToString(strings, it) }, { json.decodeFromString(strings, it) })
        private val plausibilities = Calls("plausibility", Double::toString, String::toDouble)
        private val assessments = Calls(
            "assessment",
            { json.encodeToString(TapeAssessment.serializer(), it.toTape()) },
            { json.decodeFromString(TapeAssessment.serializer(), it).toAssessment() },
        )
        private val triages = Calls(
            "triage",
            { values -> json.encodeToString(triageValues, values.map { TapeTriage(it.action.name, it.target) }) },
            { value -> json.decodeFromString(triageValues, value).map { Triage(TriageAction.valueOf(it.action), it.target) } },
        )
        private val saturations = Calls("saturation", Double::toString, String::toDouble)
        private val bearings = Calls(
            "bearing",
            { values -> json.encodeToString(strings, values.map { it.name }) },
            { value -> json.decodeFromString(strings, value).map(Bearing::valueOf) },
        )

        val recordingProposer: Proposer by lazy {
            val delegate = checkNotNull(proposer) { "recording tape has no proposer" }
            object : Proposer {
                override val id = delegate.id
                override fun propose(ctx: ClaimContext, side: Side, max: Int) =
                    proposals.record(key(TapeKey("proposer", context = ctx.toTape(), side = side.name, max = max))) {
                        delegate.propose(ctx, side, max)
                    }
            }
        }
        val replayProposer = object : Proposer {
            override val id = proposerId
            override fun propose(ctx: ClaimContext, side: Side, max: Int) =
                proposals.replay(key(TapeKey("proposer", context = ctx.toTape(), side = side.name, max = max)))
        }
        val recordingJudge: Judge by lazy {
            val delegate = checkNotNull(judge) { "recording tape has no judge" }
            object : Judge {
                override fun plausibility(question: String, claim: String) =
                    plausibilities.record(key(TapeKey("plausibility", question = question, claim = claim))) {
                        delegate.plausibility(question, claim)
                    }
                override fun assess(question: String, path: List<String>, child: String, side: Side) =
                    assessments.record(key(TapeKey("assessment", question = question, path = path, child = child, side = side.name))) {
                        delegate.assess(question, path, child, side)
                    }
                override fun triage(ctx: ClaimContext, candidates: List<Candidate>) =
                    triages.record(
                        key(TapeKey("triage", context = ctx.toTape(), candidates = candidates.map { it.toTape() })),
                    ) { delegate.triage(ctx, candidates) }
                override fun saturation(ctx: ClaimContext, side: Side) =
                    saturations.record(key(TapeKey("saturation", context = ctx.toTape(), side = side.name))) {
                        delegate.saturation(ctx, side)
                    }
                override fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>) =
                    bearings.record(
                        key(TapeKey("bearing", context = ctx.toTape(), link = link.toTape(), texts = candidates)),
                    ) { delegate.bearing(ctx, link, candidates) }
            }
        }
        val replayJudge = object : Judge {
            override fun plausibility(question: String, claim: String) =
                plausibilities.replay(key(TapeKey("plausibility", question = question, claim = claim)))
            override fun assess(question: String, path: List<String>, child: String, side: Side) =
                assessments.replay(key(TapeKey("assessment", question = question, path = path, child = child, side = side.name)))
            override fun triage(ctx: ClaimContext, candidates: List<Candidate>) =
                triages.replay(key(TapeKey("triage", context = ctx.toTape(), candidates = candidates.map { it.toTape() })))
            override fun saturation(ctx: ClaimContext, side: Side) =
                saturations.replay(key(TapeKey("saturation", context = ctx.toTape(), side = side.name)))
            override fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>) =
                bearings.replay(key(TapeKey("bearing", context = ctx.toTape(), link = link.toTape(), texts = candidates)))
        }

        fun snapshot(): List<TapeCall> = listOf(
            proposals,
            plausibilities,
            assessments,
            triages,
            saturations,
            bearings,
        ).flatMap { it.snapshot() }.sortedWith(compareBy(TapeCall::name, TapeCall::key))

        private fun key(value: TapeKey) = json.encodeToString(TapeKey.serializer(), value)
        private fun ClaimContext.toTape() = TapeContext(
            question,
            path,
            claim,
            pros,
            cons,
            link?.toTape(),
        )
        private fun LinkContext.toTape() = TapeLink(argument, parent, side.name)
        private fun Candidate.toTape() = TapeCandidate(text, side.name)
        private fun Assessment.toTape() = TapeAssessment(plausibility, strength, quality, relevance)
        private fun TapeAssessment.toAssessment() = Assessment(plausibility, strength, quality, relevance)
    }
}
