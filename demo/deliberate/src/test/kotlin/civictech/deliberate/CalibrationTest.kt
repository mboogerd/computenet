package civictech.deliberate

import civictech.agora.cell.Polarity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

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
}
