package civictech.concord.generator

import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.Check
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.Generator
import civictech.concord.schema.Graph
import civictech.concord.schema.IncrementalEqualsBatch
import civictech.concord.schema.Kind
import civictech.concord.schema.LateJoinEqualsEarly
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.NoDeadLetters
import civictech.concord.schema.QuiesceStep
import civictech.concord.schema.Scenario
import civictech.concord.schema.Step
import civictech.concord.schema.ViewsConverge
import civictech.concord.value.Value
import kotlin.random.Random

/**
 * The generative layer (CONCORD-PLAN §0, §1.2 exemplar (f), 24-GEN-01): turns a
 * declarative [Generator] block plus an instance index into a concrete, fully
 * shaped [Scenario] — a random pipeline drawn from the operator vocabulary, a
 * random op script, an optional late joiner, and the four standard property
 * checks. The emitted scenario is **corpus-shaped** (a real `graph` + `script` +
 * `checks`), so the existing runner drives it and the batch oracle folds it
 * *exactly like a hand-authored example*. Nothing here re-implements the driver,
 * the checks, or the oracle — this file only decides the topology and the ops;
 * execution and assertion are the shared harness.
 *
 * ## How the [Generator] block maps to a driven graph
 * - **`pipeline-depth: [min, max]`** — the number of operator cells between the
 *   sources and the terminal view is drawn from `[min, max]` (inclusive). A depth
 *   ≥ 1 guarantees a real operator, never a bare source→view pass-through, so
 *   every instance exercises genuine derivation (honesty rule: no trivial graphs).
 * - **`vocabulary`** — the palette of source/operator catalog ids the builder may
 *   draw from. Only ids the W3-0 kernel driver actually binds appear here; the
 *   **observation views** (`set-view`/`count-view`/`value-view`) are chosen by the
 *   harness to match each terminal's element *shape*, not drawn from the palette.
 * - **`ops`** — the total number of `apply` steps driven into the sources, split
 *   around the late-joiner barrier and distributed randomly across the graph's
 *   sources (adds biased over removes; a remove only retracts a previously-added
 *   element, so the fold stays meaningful).
 * - **`late-joiner: true`** — a second view is spawned but linked only *after* a
 *   `quiesce` barrier midway through the op stream; catch-up must make its fold
 *   indistinguishable from the early view's (`late-join-equals-early`).
 * - **`instances`** — how many distinct pipelines the sweep visits; the runner
 *   maps instance index → both the generation seed *and* the `SimulationController`
 *   schedule seed, so each instance is one reproducible (graph, schedule) pair.
 *
 * ## Shape discipline (why generated graphs stay non-trivial *and* pass)
 * Every generated edge is element-shape-consistent by construction, and the
 * kernel binding and the batch oracle agree element-for-element on that shape (a
 * disagreement would be a catalog-definition bug, P5). Three shape regimes:
 * - `INT_SET` — a set of integers; `filter`/`map`/`union`/`intersect` keep it a set
 *   of integers, `group-by` folds it to per-key counts, `count` to a cardinality.
 * - `PAIR_SET` — a set of `[key, value]` pairs; `join` matches on key.
 * - `TRIPLE_SET` — a `join`'s `[key, leftValue, rightValue]` output.
 * The builder only appends an operator whose input shape it can satisfy, so no
 * generated pipeline ever feeds (say) a numeric `filter` a non-numeric element and
 * silently collapses to the empty set.
 */
object ScenarioGenerator {

    /** The catalog ids this generator knows how to place; a scenario's `vocabulary` is intersected with these. */
    val KNOWN_OPERATORS: Set<String> =
        setOf("set-source", "filter", "map", "union", "intersect", "join", "group-by", "count")

    /** Integer element domain (INT_SET regime) — small, so adds collide and removes bite. */
    private val INT_DOMAIN: List<Long> = (0L..9L).toList()

    /** Pair-key domain (PAIR_SET regime) — small, so joins and group-bys find matches. */
    private val KEY_DOMAIN: List<String> = listOf("k0", "k1", "k2", "k3", "k4")

    /**
     * Deterministically synthesize the [Scenario] for one instance of [spec]'s
     * [Generator] block, seeded by [instance]. The returned scenario is a plain
     * `kind: example` graph the harness runs verbatim; its `id` carries the
     * instance index for failure reporting.
     */
    fun generate(spec: Scenario, instance: Int): Scenario {
        val gen = spec.generator ?: error("scenario ${spec.id} is generative but has no generator: block")
        val rnd = Random(instance.toLong())
        val vocab = resolveVocabulary(gen)
        val builder = Builder(rnd, vocab)

        val terminal = builder.buildPipeline(depthOf(gen, rnd))
        val viewType = viewFor(terminal.shape)

        // Early view: linked in the base graph. Late view (when requested): spawned
        // in the graph but connected only after a mid-script quiesce barrier.
        val earlyId = builder.fresh("early")
        builder.cells += CellSpec(id = earlyId, type = viewType)
        builder.links += LinkSpec(from = terminal.id, to = earlyId)

        val lateId = if (gen.lateJoiner == true) builder.fresh("late").also {
            builder.cells += CellSpec(id = it, type = viewType)
        } else null

        val script = builder.opScript(gen.ops ?: 200, lateTerminal = terminal.id, lateView = lateId)

        val checks = buildList {
            add(IncrementalEqualsBatch(view = "*"))
            if (lateId != null) {
                add(ViewsConverge(views = listOf(earlyId, lateId)))
                add(LateJoinEqualsEarly(early = earlyId, late = lateId))
            }
            add(NoDeadLetters)
        }

        return Scenario(
            id = "${spec.id}#$instance",
            title = "${spec.title} (instance $instance)",
            covers = spec.covers,
            profile = spec.profile,
            kind = Kind.EXAMPLE,
            graph = Graph(cells = builder.cells.toList(), links = builder.links.toList()),
            script = script,
            checks = checks,
        )
    }

    /** The `vocabulary` intersected with what this generator can place; always includes `set-source`. */
    private fun resolveVocabulary(gen: Generator): Set<String> {
        val requested = gen.vocabulary.toSet().ifEmpty { KNOWN_OPERATORS }
        val usable = (requested intersect KNOWN_OPERATORS).toMutableSet()
        usable += "set-source" // a pipeline always needs a source
        return usable
    }

    private fun depthOf(gen: Generator, rnd: Random): Int {
        val range = gen.pipelineDepth
        val min = (range?.getOrNull(0) ?: 1).coerceAtLeast(1)
        val max = (range?.getOrNull(1) ?: min).coerceAtLeast(min)
        return if (max == min) min else min + rnd.nextInt(max - min + 1)
    }

    /** The observation-view catalog id that renders a terminal of the given [shape]. */
    private fun viewFor(shape: Shape): String = when (shape) {
        Shape.INT_SET, Shape.PAIR_SET, Shape.TRIPLE_SET -> "set-view"
        Shape.MAP -> "count-view"
        Shape.SCALAR -> "value-view"
    }

    /** The element shape flowing on an edge — constrains which operator may consume it. */
    private enum class Shape { INT_SET, PAIR_SET, TRIPLE_SET, MAP, SCALAR }

    /** A pipeline frontier: the id of the cell producing a stream of the given [shape]. */
    private data class Frontier(val id: String, val shape: Shape)

    /**
     * Accumulates a random pipeline. Cells/links are appended as they are placed;
     * sources are remembered so [Builder.opScript] can drive them.
     */
    private class Builder(private val rnd: Random, private val vocab: Set<String>) {
        val cells = mutableListOf<CellSpec>()
        val links = mutableListOf<LinkSpec>()

        private val sources = mutableListOf<Source>()
        private var counter = 0

        fun fresh(prefix: String): String = "$prefix${counter++}"

        private enum class SourceKind { INT, PAIR }
        private data class Source(val id: String, val kind: SourceKind, val held: MutableSet<Value> = LinkedHashSet())

        private fun intSource(): Frontier {
            val id = fresh("s")
            cells += CellSpec(id = id, type = "set-source", of = "int")
            sources += Source(id, SourceKind.INT)
            return Frontier(id, Shape.INT_SET)
        }

        private fun pairSource(): Frontier {
            val id = fresh("s")
            cells += CellSpec(id = id, type = "set-source")
            sources += Source(id, SourceKind.PAIR)
            return Frontier(id, Shape.PAIR_SET)
        }

        /**
         * Build a producer chain with [depth] operator cells and return its
         * terminal frontier. Picks one of the shape-consistent families the
         * vocabulary supports; families differ in which operators they place, but
         * all honour [depth] and stay within one shape regime.
         */
        fun buildPipeline(depth: Int): Frontier {
            val family = buildList {
                add(Family.UNARY)
                if ("union" in vocab || "intersect" in vocab) add(Family.FAN_IN)
                if ("join" in vocab) add(Family.JOIN)
            }.random(rnd)
            return when (family) {
                Family.UNARY -> unaryChain(intSource(), depth)
                Family.FAN_IN -> fanInChain(depth)
                Family.JOIN -> joinChain(depth)
            }
        }

        private enum class Family { UNARY, FAN_IN, JOIN }

        /** source → (filter|map){depth} → optional terminal fold (group-by/count). */
        private fun unaryChain(start: Frontier, depth: Int): Frontier {
            var f = start
            // Reserve the final step for an optional set→fold terminal (group-by/count).
            val terminalFold = depth > 0 && foldable() != null && rnd.nextInt(3) == 0
            val unarySteps = if (terminalFold) depth - 1 else depth
            repeat(unarySteps) { f = applyUnary(f) }
            if (terminalFold) f = applyFold(f)
            return f
        }

        /** (source, source) → union|intersect → (filter|map){depth-1} → optional fold. */
        private fun fanInChain(depth: Int): Frontier {
            val opType = listOfNotNull(
                "union".takeIf { it in vocab },
                "intersect".takeIf { it in vocab },
            ).random(rnd)
            val left = intSource()
            val right = intSource()
            val id = fresh("op")
            cells += CellSpec(id = id, type = opType)
            links += LinkSpec(from = left.id, to = id, inlet = "left")
            links += LinkSpec(from = right.id, to = id, inlet = "right")
            return unaryChain(Frontier(id, Shape.INT_SET), (depth - 1).coerceAtLeast(0))
        }

        /** (pairSource, pairSource) → join → optional group-by fold. */
        private fun joinChain(depth: Int): Frontier {
            val left = pairSource()
            val right = pairSource()
            val id = fresh("op")
            cells += CellSpec(id = id, type = "join", fn = "key-of")
            links += LinkSpec(from = left.id, to = id, inlet = "left")
            links += LinkSpec(from = right.id, to = id, inlet = "right")
            var f = Frontier(id, Shape.TRIPLE_SET)
            // Depth beyond the join is spent on a group-by fold when available.
            if (depth > 1 && "group-by" in vocab && rnd.nextBoolean()) f = groupBy(f)
            return f
        }

        private fun applyUnary(f: Frontier): Frontier {
            val choices = listOfNotNull(
                "filter".takeIf { it in vocab },
                "map".takeIf { it in vocab },
            )
            if (choices.isEmpty()) return f // vocabulary has no unary op; leave the frontier as-is
            val id = fresh("op")
            return when (choices.random(rnd)) {
                "filter" -> {
                    cells += CellSpec(id = id, type = "filter", fn = randomPredicate())
                    links += LinkSpec(from = f.id, to = id)
                    Frontier(id, Shape.INT_SET)
                }
                else -> {
                    cells += CellSpec(id = id, type = "map", fn = randomTransform())
                    links += LinkSpec(from = f.id, to = id)
                    Frontier(id, Shape.INT_SET)
                }
            }
        }

        /** An available terminal fold id (`group-by` or `count`), or null if the vocabulary has neither. */
        private fun foldable(): String? = listOfNotNull(
            "group-by".takeIf { it in vocab },
            "count".takeIf { it in vocab },
        ).randomOrNull(rnd)

        private fun applyFold(f: Frontier): Frontier = when (foldable()) {
            "group-by" -> groupBy(f)
            "count" -> count(f)
            else -> f
        }

        private fun groupBy(f: Frontier): Frontier {
            val id = fresh("op")
            cells += CellSpec(id = id, type = "group-by", fn = "key-of")
            links += LinkSpec(from = f.id, to = id)
            return Frontier(id, Shape.MAP)
        }

        private fun count(f: Frontier): Frontier {
            val id = fresh("op")
            cells += CellSpec(id = id, type = "count")
            links += LinkSpec(from = f.id, to = id)
            return Frontier(id, Shape.SCALAR)
        }

        private fun randomPredicate(): String = when (rnd.nextInt(5)) {
            0 -> "even"
            1 -> "odd"
            2 -> "gt(${1 + rnd.nextInt(7)})"
            3 -> "lt(${2 + rnd.nextInt(7)})"
            else -> "mod-eq(${2 + rnd.nextInt(3)},${rnd.nextInt(2)})"
        }

        private fun randomTransform(): String = when (rnd.nextInt(3)) {
            0 -> "identity"
            else -> "add(${1 + rnd.nextInt(5)})"
        }

        /**
         * Distribute [ops] `apply` steps across the sources. When [lateView] is
         * given, half the ops run, then a `quiesce` barrier, then the late view is
         * connected to [lateTerminal], then the remaining ops run — so catch-up is
         * genuinely exercised mid-stream.
         */
        fun opScript(ops: Int, lateTerminal: String, lateView: String?): List<Step> {
            if (sources.isEmpty()) return emptyList()
            val steps = mutableListOf<Step>()
            val total = ops.coerceAtLeast(1)
            val breakAt = if (lateView != null) total / 2 else total
            repeat(total) { n ->
                if (n == breakAt && lateView != null) {
                    steps += QuiesceStep()
                    steps += ConnectStep(from = lateTerminal, to = lateView)
                }
                steps += randomOp()
            }
            return steps
        }

        private fun randomOp(): ApplyStep {
            val src = sources.random(rnd)
            val remove = src.held.isNotEmpty() && rnd.nextInt(10) < 3
            return if (remove) {
                val victim = src.held.random(rnd)
                src.held -= victim
                ApplyStep(on = src.id, op = "remove", value = victim)
            } else {
                val element = when (src.kind) {
                    SourceKind.INT -> Value.IntVal(INT_DOMAIN.random(rnd))
                    SourceKind.PAIR -> Value.ListVal(
                        listOf(Value.StrVal(KEY_DOMAIN.random(rnd)), Value.IntVal(INT_DOMAIN.random(rnd))),
                    )
                }
                src.held += element
                ApplyStep(on = src.id, op = "add", value = element)
            }
        }
    }
}
