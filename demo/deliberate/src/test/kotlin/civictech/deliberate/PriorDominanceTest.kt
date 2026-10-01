package civictech.deliberate

import civictech.agora.cell.Polarity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in measurement for computenet-nxege. The proposer output is the same
 * cached live material as [CalibrationTest]; every additional Jev answer is
 * cached in `prior-dominance.json`, so a complete rerun makes no LLM calls.
 *
 * `DELIBERATE_CALIBRATE=1` runs the harness. Deleting the result cache
 * deliberately repeats the Jev judgments; the proposer material is never
 * regenerated here.
 */
class PriorDominanceTest {

    @Serializable
    private data class MaterialArg(val text: String, val proposer: String)

    @Serializable
    private data class MaterialNode(
        val depth: Int,
        val claim: String,
        val path: List<String>,
        val pros: List<MaterialArg>,
        val cons: List<MaterialArg>,
    )

    @Serializable
    private data class Material(val question: String, val chain: List<MaterialNode>)

    @Serializable
    private data class CachedPrior(val nodeId: String, val input: String, val value: Double)

    @Serializable
    private data class CachedStrength(val edgeId: String, val input: String, val value: Double)

    @Serializable
    private data class CachedUsage(
        val model: String? = null,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
    ) {
        fun callUsage() = CallUsage(
            backend = Pricing.JEV,
            models = listOfNotNull(model),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    @Serializable
    private data class CachedReassessment(
        val scenario: String,
        val nodeId: String,
        val input: String,
        val considered: Double,
        val argumentsOnly: Double,
        val usage: CachedUsage? = null,
    )

    @Serializable
    private data class Cache(
        val version: Int,
        val priors: MutableList<CachedPrior> = mutableListOf(),
        val strengths: MutableList<CachedStrength> = mutableListOf(),
        val reassessments: MutableList<CachedReassessment> = mutableListOf(),
    )

    private data class Edge(val id: String, val side: Side, val child: Node)

    private data class Node(
        val id: String,
        val text: String,
        val depth: Int,
        val edges: List<Edge>,
    )

    private data class Tree(val index: Int, val question: String, val root: Node) {
        val nodes: List<Node> by lazy {
            val seen = LinkedHashMap<String, Node>()
            fun visit(node: Node) {
                if (seen.putIfAbsent(node.id, node) == null) node.edges.forEach { visit(it.child) }
            }
            visit(root)
            seen.values.toList()
        }

        val edges: List<Edge> get() = nodes.flatMap { it.edges }
    }

    private data class NodeEvaluation(
        val values: List<Double>,
        val neutralValues: List<Double>,
        val priorValues: List<Double>,
    )

    private data class FormalMode(val id: String, val priorWeight: Double)

    private data class FormalRun(
        val mode: FormalMode,
        val evaluations: Map<String, Map<String, NodeEvaluation>>,
    )

    private data class ShownArgument(
        val edgeId: String,
        val side: Side,
        val text: String,
        val credence: Double,
        val strength: Double,
    )

    private class CacheStore(private val file: File, private val json: Json) {
        val value: Cache = if (file.isFile) json.decodeFromString(Cache.serializer(), file.readText()) else Cache(CACHE_VERSION)

        init {
            require(value.version == CACHE_VERSION) {
                "prior-dominance cache version ${value.version}, expected $CACHE_VERSION; delete ${file.path} to regenerate"
            }
        }

        fun save() {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(Cache.serializer(), value))
        }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val materialFile = File("src/test/resources/calibration/material.json")
    private val cacheFile = File("src/test/resources/calibration/prior-dominance.json")
    private var liveJev: JevJudge? = null

    @Test
    fun `measure prior dominance and candidate semantics on live material`() {
        assumeTrue(
            System.getenv("DELIBERATE_CALIBRATE") == "1",
            "set DELIBERATE_CALIBRATE=1 to run the prior-dominance harness",
        )
        val material = json.decodeFromString(ListSerializer(Material.serializer()), materialFile.readText())
        assertTrue(material.size >= 3, "the harness needs at least three live questions")
        val trees = material.mapIndexed(::treeOf)
        val cache = CacheStore(cacheFile, json)
        completeFormalJudgments(trees, cache)

        val layers = LayerSet.of(
            SemanticsCatalog.IDS,
            headline = LayerSet.CONSENSUS,
        )
        val modes = listOf(
            FormalMode("current-prior", 1.0),
            FormalMode("weak-prior-0.25", 0.25),
            FormalMode("arguments-first-neutral", 0.0),
        )
        val runs = modes.map { mode ->
            FormalRun(
                mode,
                trees.associate { tree -> tree.question to evaluate(tree, cache, layers, mode) },
            )
        }
        val current = runs.first()
        val strongParents = trees.associate { tree ->
            tree.question to strongUnrebuttedParents(tree, current.evaluations.getValue(tree.question), cache, layers)
        }

        val bottomUp = trees.associate { tree ->
            tree.question to bottomUp(tree, "actual", cache, emptySet(), layers)
        }
        for (depth in 1..3) {
            trees.forEach { tree -> bottomUp(tree, "freeze-depth-$depth", cache, setOf(depth), layers) }
        }

        // The target-property counterfactual for reassessment is local: remove
        // each qualifying strong argument and ask Jev for the parent again.
        trees.forEach { tree ->
            val actual = bottomUp.getValue(tree.question)
            for (parent in strongParents.getValue(tree.question)) {
                val shown = shownArguments(parent, actual, cache)
                for (edge in parent.edges.filter { qualifiesStrong(it, parent, current.evaluations.getValue(tree.question), cache, layers) }) {
                    reassess(tree, parent, "without-${edge.id}", shown.filterNot { it.edgeId == edge.id }, cache)
                }
            }
        }

        val report = report(trees, cache, layers, runs, strongParents, bottomUp)
        File("build/calibration").mkdirs()
        File("build/calibration/prior-dominance-report.txt").writeText(report)
        println(report)

        for (run in runs) {
            for (tree in trees) {
                val values = run.evaluations.getValue(tree.question)
                assertTrue(values.values.flatMap { it.values }.all { it in 0.0..1.0 }, "${run.mode.id} left [0,1]")
                tree.nodes.filter { it.edges.isEmpty() }.forEach { leaf ->
                    assertEquals(values.getValue(leaf.id).priorValues, values.getValue(leaf.id).values, "${run.mode.id}: no-argument ${leaf.id}")
                }
            }
        }
        bottomUp.values.forEach { values -> assertTrue(values.values.all { it in 0.0..1.0 }) }
    }

    // ---------------------------------------------------------------- trees and cached judgments

    private fun treeOf(index: Int, material: Material): Tree {
        fun chain(depth: Int): Node {
            val source = material.chain[depth]
            val next = material.chain.getOrNull(depth + 1)?.claim
            var usedNext = false
            lateinit var parentId: String
            parentId = "q$index/d$depth"
            fun edge(side: Side, arg: MaterialArg, i: Int): Edge {
                val isNext = !usedNext && next != null && arg.text == next
                val child = if (isNext) {
                    usedNext = true
                    chain(depth + 1)
                } else {
                    Node("$parentId/${side.name.lowercase()}-$i", arg.text, depth + 1, emptyList())
                }
                return Edge("$parentId/${side.name.lowercase()}-$i", side, child)
            }
            val edges = source.pros.mapIndexed { i, arg -> edge(Polarity.SUPPORT, arg, i) } +
                source.cons.mapIndexed { i, arg -> edge(Polarity.ATTACK, arg, i) }
            require(next == null || usedNext) { "chain d${depth + 1} is not a direct argument of d$depth" }
            return Node(parentId, source.claim, depth, edges)
        }
        return Tree(index, material.question, chain(0))
    }

    private fun completeFormalJudgments(trees: List<Tree>, cache: CacheStore) {
        val missingPriors = trees.flatMap { tree -> tree.nodes.map { tree to it } }.filter { (tree, node) ->
            cache.value.priors.none { it.nodeId == node.id && it.input == hash(tree.question, node.text) }
        }
        val missingStrengths = trees.flatMap { tree -> tree.nodes.flatMap { parent -> parent.edges.map { Triple(tree, parent, it) } } }
            .filter { (tree, parent, edge) ->
                cache.value.strengths.none {
                    it.edgeId == edge.id && it.input == hash(tree.question, parent.text, edge.child.text, edge.side.name)
                }
            }
        if (missingPriors.isEmpty() && missingStrengths.isEmpty()) return

        val jev = jev()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val priors = missingPriors.map { (tree, node) ->
                Triple(tree, node, pool.submit(Callable { jev.plausibility(tree.question, node.text) }))
            }
            for ((tree, node, future) in priors) {
                cache.value.priors.removeAll { it.nodeId == node.id }
                cache.value.priors += CachedPrior(node.id, hash(tree.question, node.text), future.get())
                cache.save()
            }
            val strengths = missingStrengths.map { (tree, parent, edge) ->
                Triple(tree, parent to edge, pool.submit(Callable {
                    jev.relationStrength(tree.question, parent.text, edge.child.text, edge.side)
                }))
            }
            for ((tree, pair, future) in strengths) {
                val (parent, edge) = pair
                cache.value.strengths.removeAll { it.edgeId == edge.id }
                cache.value.strengths += CachedStrength(
                    edge.id,
                    hash(tree.question, parent.text, edge.child.text, edge.side.name),
                    future.get(),
                )
                cache.save()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun prior(tree: Tree, node: Node, cache: CacheStore): Double = cache.value.priors.single {
        it.nodeId == node.id && it.input == hash(tree.question, node.text)
    }.value

    private fun strength(tree: Tree, parent: Node, edge: Edge, cache: CacheStore): Double = cache.value.strengths.single {
        it.edgeId == edge.id && it.input == hash(tree.question, parent.text, edge.child.text, edge.side.name)
    }.value

    // ---------------------------------------------------------------- formal candidates

    private fun evaluate(
        tree: Tree,
        cache: CacheStore,
        layers: LayerSet,
        mode: FormalMode,
        freezeDepths: Set<Int> = emptySet(),
        freezeNode: String? = null,
    ): Map<String, NodeEvaluation> {
        val result = LinkedHashMap<String, NodeEvaluation>()
        fun node(n: Node): NodeEvaluation {
            result[n.id]?.let { return it }
            val p = prior(tree, n, cache)
            val priorValues = layers.evaluate(listOf(p), emptyList(), emptyList())
            if (n.id == freezeNode || n.depth in freezeDepths || n.edges.isEmpty()) {
                return NodeEvaluation(priorValues, priorValues, priorValues).also { result[n.id] = it }
            }
            val childValues = n.edges.associateWith { node(it.child).values }
            fun args(side: Side) = n.edges.filter { it.side == side }.map { edge ->
                val s = strength(tree, n, edge, cache)
                childValues.getValue(edge).map { c -> Arg(s, c) }
            }
            val attacks = args(Polarity.ATTACK)
            val supports = args(Polarity.SUPPORT)
            val values = layers.evaluate(listOf(p), attacks, supports, mode.priorWeight)
            val neutral = layers.evaluate(listOf(p), attacks, supports, 0.0)
            return NodeEvaluation(values, neutral, priorValues).also { result[n.id] = it }
        }
        node(tree.root)
        return result
    }

    private fun strongUnrebuttedParents(
        tree: Tree,
        evaluations: Map<String, NodeEvaluation>,
        cache: CacheStore,
        layers: LayerSet,
    ): List<Node> = tree.nodes.filter { parent ->
        parent.edges.any { qualifiesStrong(it, parent, evaluations, cache, layers) }
    }

    private fun qualifiesStrong(
        edge: Edge,
        parent: Node,
        evaluations: Map<String, NodeEvaluation>,
        cache: CacheStore,
        layers: LayerSet,
    ): Boolean {
        fun strong(e: Edge) = headline(evaluations.getValue(e.child.id).values, layers) >= STRONG &&
            strengthFor(parent, e, cache) >= STRONG
        return strong(edge) && parent.edges.none { it.side != edge.side && strong(it) }
    }

    /** Edge ids include qN, so the owning tree is recoverable without another index. */
    private fun strengthFor(parent: Node, edge: Edge, cache: CacheStore): Double =
        cache.value.strengths.single { it.edgeId == edge.id }.value

    // ---------------------------------------------------------------- bottom-up Jev reassessment

    private fun bottomUp(
        tree: Tree,
        scenario: String,
        cache: CacheStore,
        freezeDepths: Set<Int>,
        layers: LayerSet,
    ): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        fun node(n: Node): Double {
            result[n.id]?.let { return it }
            val p = prior(tree, n, cache)
            if (n.depth in freezeDepths || n.edges.isEmpty()) return p.also { result[n.id] = it }
            n.edges.forEach { node(it.child) }
            val reassessed = reassess(tree, n, scenario, shownArguments(n, result, cache), cache)
            return reassessed.considered.also { result[n.id] = it }
        }
        node(tree.root)
        // The candidate has one scalar standing, but the same bounds as every layer.
        assertTrue(result.values.all { it in 0.0..1.0 })
        return result
    }

    private fun shownArguments(parent: Node, standings: Map<String, Double>, cache: CacheStore): List<ShownArgument> =
        parent.edges.map { edge ->
            ShownArgument(
                edge.id,
                edge.side,
                edge.child.text,
                standings.getValue(edge.child.id),
                strengthFor(parent, edge, cache),
            )
        }

    private fun reassess(
        tree: Tree,
        node: Node,
        scenario: String,
        arguments: List<ShownArgument>,
        cache: CacheStore,
    ): CachedReassessment {
        val p = prior(tree, node, cache)
        val signature = hash(
            tree.question,
            node.text,
            p.toString(),
            arguments.joinToString("|") { "${it.side}:${it.text}:${it.credence}:${it.strength}" },
        )
        cache.value.reassessments.singleOrNull {
            it.scenario == scenario && it.nodeId == node.id && it.input == signature
        }?.let { return it }

        val state = buildJsonObject {
            put("root_question", tree.question)
            put("claim", node.text)
            put("initial_plausibility", p)
            putArguments("arguments_for", arguments.filter { it.side == Polarity.SUPPORT })
            putArguments("arguments_against", arguments.filter { it.side == Polarity.ATTACK })
        }
        val questions = mapOf(
            "considered" to scoreQuestion(
                "Reassess how likely `claim` is after weighing every direct argument, its deliberated credence, " +
                    "and its relation strength. `initial_plausibility` is the earlier quick judgment, not an extra argument.",
            ),
            "arguments_only" to scoreQuestion(
                "Set `initial_plausibility` aside and judge what the direct arguments alone imply about `claim`, " +
                    "using each argument's deliberated credence and relation strength.",
            ),
        )
        val seen = mutableListOf<CallUsage>()
        val answers = Usage.within(UsageSink { seen += it }) { jev().evaluate(state, questions) }
        val usage = seen.singleOrNull()?.let {
            CachedUsage(it.models.singleOrNull(), it.inputTokens, it.outputTokens)
        }
        val measured = CachedReassessment(
            scenario,
            node.id,
            signature,
            answer(answers.getValue("considered")),
            answer(answers.getValue("arguments_only")),
            usage,
        )
        cache.value.reassessments += measured
        cache.save()
        return measured
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putArguments(key: String, args: List<ShownArgument>) {
        putJsonArray(key) {
            args.forEach { arg ->
                add(buildJsonObject {
                    put("claim", arg.text)
                    put("deliberated_credence", arg.credence)
                    put("relation_strength", arg.strength)
                })
            }
        }
    }

    private fun scoreQuestion(instructions: String) = buildJsonObject {
        put("type", "score")
        put("instructions", instructions)
        putJsonArray("criteria") { SCORE_LEVELS.forEach { add(it) } }
    }

    private fun answer(value: JsonElement): Double =
        ((value.jsonObject["score"]?.jsonPrimitive?.double ?: error("score answer without score")) / 4.0)
            .coerceIn(0.0, 1.0)

    // ---------------------------------------------------------------- report

    private fun report(
        trees: List<Tree>,
        cache: CacheStore,
        layers: LayerSet,
        runs: List<FormalRun>,
        strongParents: Map<String, List<Node>>,
        bottomUp: Map<String, Map<String, Double>>,
    ): String = buildString {
        fun f(x: Double) = String.format(Locale.ROOT, "%.3f", x)
        fun share(xs: List<Boolean>) = if (xs.isEmpty()) "n/a" else "${xs.count { it }}/${xs.size} (${f(xs.count { it }.toDouble() / xs.size)})"

        appendLine("PRIOR DOMINANCE — ${trees.size} cached live questions, ${trees.sumOf { it.nodes.size }} claims, ${trees.sumOf { it.edges.size }} links")
        appendLine("Definitions: freeze dK replaces every dK claim's deliberated standing by its Jev prior; an explored claim is a claim with direct arguments.")
        appendLine("Prior share = |actual - local-neutral-prior| / (that + |actual - arguments-removed|).")
        appendLine("Strong unrebutted = credence >= $STRONG and strength >= $STRONG, with no equally strong direct argument on the opposite side.")
        val strengths = cache.value.strengths.map { it.value }
        appendLine("Observed strong subset: ${strengths.count { it >= STRONG }} links at/above threshold; maximum strength=${f(strengths.max())} (the threshold is not relaxed).")

        for (run in runs) {
            appendLine()
            appendLine("CANDIDATE ${run.mode.id}")
            for (tree in trees) {
                val actual = run.evaluations.getValue(tree.question)
                val root = actual.getValue(tree.root.id)
                append("  q${tree.index} root=${f(headline(root.values, layers))}")
                for (depth in 1..3) {
                    val frozen = evaluate(tree, cache, layers, run.mode, freezeDepths = setOf(depth)).getValue(tree.root.id)
                    append(" freeze-d$depth=${f(abs(headline(root.values, layers) - headline(frozen.values, layers)))}")
                }
                appendLine()
            }
            appendLine("  freeze-depth mean root delta by layer:")
            for (l in layers.ids.indices) {
                val row = (1..3).joinToString(" ") { depth ->
                    val moves = trees.map { tree ->
                        val actual = run.evaluations.getValue(tree.question).getValue(tree.root.id)
                        val frozen = evaluate(tree, cache, layers, run.mode, freezeDepths = setOf(depth)).getValue(tree.root.id)
                        abs(actual.values[l] - frozen.values[l])
                    }
                    "d$depth=${f(moves.average())}"
                }
                appendLine("    ${layers.ids[l]} $row")
            }
            appendLine("  prior dominance by claim depth and layer (mean prior share; n excludes zero/zero):")
            for (depth in 0..3) {
                for (l in layers.ids.indices) {
                    val rows = trees.flatMap { tree ->
                        val e = run.evaluations.getValue(tree.question)
                        tree.nodes.filter { it.depth == depth && it.edges.isNotEmpty() }.mapNotNull { n ->
                            dominance(e.getValue(n.id), l)
                        }
                    }
                    if (rows.isNotEmpty()) appendLine("    d$depth ${layers.ids[l]} prior=${f(rows.map { it.first }.average())} argument=${f(rows.map { it.second }.average())} n=${rows.size}")
                }
            }
            val target = trees.flatMap { tree ->
                val e = run.evaluations.getValue(tree.question)
                strongParents.getValue(tree.question).mapNotNull { dominance(e.getValue(it.id), layers.ids.indexOf("wlo")) }
            }.map { (_, argument) -> argument > 0.5 }
            appendLine("  target on strong-unrebutted parents (wlo argument share > prior share): ${share(target)}")
        }

        val current = runs.first()
        appendLine()
        appendLine("CURRENT headline root movement when one explored claim is frozen:")
        val everyMove = mutableListOf<Pair<Int, Double>>()
        for (depth in 0..3) {
            val moves = trees.flatMap { tree ->
                val actual = current.evaluations.getValue(tree.question).getValue(tree.root.id)
                tree.nodes.filter { it.depth == depth && it.edges.isNotEmpty() }.map { n ->
                    val frozen = evaluate(tree, cache, layers, current.mode, freezeNode = n.id).getValue(tree.root.id)
                    abs(headline(actual.values, layers) - headline(frozen.values, layers))
                }
            }
            everyMove += moves.map { depth to it }
            if (moves.isNotEmpty()) appendLine("  d$depth immovable(<0.01)=${share(moves.map { it < 0.01 })} mean=${f(moves.average())} max=${f(moves.max())}")
        }
        appendLine("  all argued claims immovable(<0.01)=${share(everyMove.map { it.second < 0.01 })}")
        appendLine("  non-root argued claims immovable(<0.01)=${share(everyMove.filter { it.first > 0 }.map { it.second < 0.01 })}")
        appendLine("CURRENT per-level transmission (root move / local argument-driven move):")
        for (depth in 1..3) {
            val factors = trees.flatMap { tree ->
                val actual = current.evaluations.getValue(tree.question)
                tree.nodes.filter { it.depth == depth && it.edges.isNotEmpty() }.mapNotNull { n ->
                    val local = abs(headline(actual.getValue(n.id).values, layers) - headline(actual.getValue(n.id).priorValues, layers))
                    if (local < 1e-12) null else {
                        val frozen = evaluate(tree, cache, layers, current.mode, freezeNode = n.id).getValue(tree.root.id)
                        abs(headline(actual.getValue(tree.root.id).values, layers) - headline(frozen.values, layers)) / local
                    }
                }
            }
            if (factors.isNotEmpty()) appendLine("  d$depth mean=${f(factors.average())} min=${f(factors.min())} max=${f(factors.max())}")
        }
        appendLine("CURRENT per-level transmission by layer (mean root move / local move):")
        for (depth in 1..3) {
            for (l in layers.ids.indices) {
                val factors = trees.flatMap { tree ->
                    val actual = current.evaluations.getValue(tree.question)
                    tree.nodes.filter { it.depth == depth && it.edges.isNotEmpty() }.mapNotNull { n ->
                        val local = abs(actual.getValue(n.id).values[l] - actual.getValue(n.id).priorValues[l])
                        if (local < 1e-12) null else {
                            val frozen = evaluate(tree, cache, layers, current.mode, freezeNode = n.id).getValue(tree.root.id)
                            abs(actual.getValue(tree.root.id).values[l] - frozen.values[l]) / local
                        }
                    }
                }
                if (factors.isNotEmpty()) appendLine("  d$depth ${layers.ids[l]} mean=${f(factors.average())}")
            }
        }

        appendLine()
        appendLine("CANDIDATE bottom-up-jev-reassessment (replacement standing; formal aggregation is dropped, so no double count)")
        for (tree in trees) {
            val actual = bottomUp.getValue(tree.question)
            append("  q${tree.index} root=${f(actual.getValue(tree.root.id))}")
            for (depth in 1..3) {
                val frozen = bottomUp(tree, "freeze-depth-$depth", cache, setOf(depth), layers)
                append(" freeze-d$depth=${f(abs(actual.getValue(tree.root.id) - frozen.getValue(tree.root.id)))}")
            }
            appendLine()
        }
        val reassessmentTarget = trees.flatMap { tree ->
            strongParents.getValue(tree.question).map { parent ->
                val actual = cachedReassessment(tree, parent, "actual", bottomUp.getValue(tree.question), cache)
                val priorDriven = abs(actual.considered - actual.argumentsOnly)
                val argumentDriven = abs(actual.considered - prior(tree, parent, cache))
                argumentDriven > priorDriven
            }
        }
        appendLine("  target on strong-unrebutted parents (argument move > re-anchoring): ${share(reassessmentTarget)}")
        appendLine("  prior dominance by depth (mean prior and argument shares):")
        for (depth in 0..3) {
            val rows = trees.flatMap { tree ->
                tree.nodes.filter { it.depth == depth && it.edges.isNotEmpty() }.mapNotNull { node ->
                    val r = cachedReassessment(tree, node, "actual", bottomUp.getValue(tree.question), cache)
                    shares(abs(r.considered - r.argumentsOnly), abs(r.considered - prior(tree, node, cache)))
                }
            }
            if (rows.isNotEmpty()) appendLine("    d$depth prior=${f(rows.map { it.first }.average())} argument=${f(rows.map { it.second }.average())} n=${rows.size}")
        }

        val actualCalls = cache.value.reassessments.filter { it.scenario == "actual" }
        val pricing = Pricing()
        for (tree in trees) {
            val calls = actualCalls.filter { it.nodeId.startsWith("q${tree.index}/") }
            val usd = calls.mapNotNull { it.usage?.callUsage()?.let(pricing::price) }.sum()
            appendLine("  q${tree.index} production calls=${calls.size}, measured Jev cost=${String.format(Locale.ROOT, "%.6f", usd)} USD; cap-2 projection=${String.format(Locale.ROOT, "%.6f", usd * 2)} USD")
        }
        appendLine("  retrigger proposal: after subtree quiescence/VoI-stop, only when a shown child's credence changes >= 0.05; at most two reassessments per node per question.")
        appendLine("  restore proposal: journal the considered judgment and its shown-child fingerprint as an input; live Jev answers are not reproducible derived state.")
    }

    private fun cachedReassessment(
        tree: Tree,
        node: Node,
        scenario: String,
        standings: Map<String, Double>,
        cache: CacheStore,
    ): CachedReassessment {
        val args = shownArguments(node, standings, cache)
        val signature = hash(
            tree.question,
            node.text,
            prior(tree, node, cache).toString(),
            args.joinToString("|") { "${it.side}:${it.text}:${it.credence}:${it.strength}" },
        )
        return cache.value.reassessments.single {
            it.scenario == scenario && it.nodeId == node.id && it.input == signature
        }
    }

    private fun dominance(e: NodeEvaluation, layer: Int): Pair<Double, Double>? =
        shares(abs(e.values[layer] - e.neutralValues[layer]), abs(e.values[layer] - e.priorValues[layer]))

    private fun shares(prior: Double, argument: Double): Pair<Double, Double>? {
        val total = prior + argument
        return if (total < 1e-12) null else prior / total to argument / total
    }

    private fun headline(values: List<Double>, layers: LayerSet) = layers.consensus(values)

    private fun jev(): JevJudge = liveJev ?: JevJudge().also { liveJev = it }

    private fun hash(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000").toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CACHE_VERSION = 1
        const val STRONG = 0.8
        val SCORE_LEVELS = listOf(
            "Almost certainly false: the available considerations decisively weigh against the claim.",
            "Probably false: the considerations weigh against the claim, though it remains possible.",
            "Uncertain: the considerations are absent, balanced, or conflicting.",
            "Probably true: the considerations support the claim, though meaningful uncertainty remains.",
            "Almost certainly true: the available considerations decisively support the claim.",
        )
    }
}
