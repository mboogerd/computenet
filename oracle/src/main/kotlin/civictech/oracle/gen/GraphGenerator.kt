package civictech.oracle.gen

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.SpawnStep
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.ShapeRule
import civictech.oracle.model.ElementShape
import civictech.oracle.model.SourceId
import java.io.Serializable
import kotlin.random.Random

/**
 * One generated topology and the kernel graph it lowers to — [GraphGenerator]'s whole output.
 *
 * Kept apart from [GeneratedCase] on purpose: a case additionally carries a drive script and a
 * remove audit, which the sibling `ScriptGenerator` produces. This type is the graph half, so
 * the two generators compose without either depending on the other.
 */
data class GeneratedGraph(
    /** The catalog-id-level shape the graph was rendered from. */
    val topology: CaseTopology,
    /** The lowered, replayable kernel graph. */
    val spec: GraphSpec,
) : Serializable

/**
 * Seeded, **shape-typed** topology generation over [OperatorCatalog], rendered as a
 * `civictech.cell.graph.GraphSpec` that cannot fail to link (`[ORA1-GEN-02]`, epic decisions
 * D3/D4).
 *
 * ## Shape-typed, not generate-then-validate (D4, `[ORA1-GEN-02]`)
 *
 * The builder keeps a frontier of nodes whose output [ElementShape] it knows, and only ever
 * appends an operator whose `ShapeRule.inputs` it can satisfy **by shape equality** against
 * that frontier. Nothing is generated and then discarded for being unlinkable, and nothing is
 * validated after the fact: an edge exists only because the two shapes were equal when it was
 * drawn.
 *
 * Every one of those decisions reads `OperatorCatalog.entry(id).shape` — arity, input shapes,
 * output shape, and the port names to connect on. **There is deliberately no branch anywhere
 * in this file over an operator's id.** That is `[ORA1-API-03]` itself: an operator a consumer
 * registers (ORA2, QRY1, or a test's synthetic entry) is picked up by naming it in
 * [GeneratorConfig.vocabulary], with zero edits here. A `when (id)` over the catalog's names
 * would make every new registration a generator change, which is the outcome the requirement
 * exists to forbid.
 *
 * ## Vocabulary honesty
 *
 * Operators come exclusively from [OperatorCatalog] registrations named in
 * [GeneratorConfig.vocabulary]. Everything the `[ORA1-HONEST-02]` ledger excludes — `ListCell`,
 * `OrMapCell`, `MergeableGroupByCell`, `CoalescingCombineCell`, window close/eviction
 * (`civictech.oracle.model.MapCellModel`'s module KDoc) — is unregistered, and therefore
 * unemittable here by construction rather than by a filter that could be forgotten.
 *
 * ## Static topologies only
 *
 * The emitted [GraphSpec] carries spawn and connect steps and nothing else: no mid-script link
 * open or close, ever. That is what keeps a generated case inside what the reference models
 * define — `QuorumSetModel` (`civictech.oracle.model.SetOperatorModels`) equates the kernel's
 * live-arm count with the graph's *static* arm count, so a topology that changed mid-run would
 * be outside the model's own statement rather than a difference worth reporting. The only
 * dynamic element a case may carry is the late-joiner terminal, which is the sibling task's and
 * attaches to an existing node's output port without changing the graph.
 *
 * ## Determinism (`[ORA1-GEN-01]`)
 *
 * Every choice is drawn from the passed [Random] and every iteration runs over an ordered list
 * or a `LinkedHash*`. Handles are derived from the topology's own construction order
 * (`source-i`, `op-level-index`), never from a UUID, an identity hash, or hash-set iteration
 * order, so two generations from equal `(seed, config)` produce identical handle lists — and
 * the emitted factories are the catalog's own `Entry.kernel`, which capture nothing and are
 * `Serializable` (D3).
 *
 * ## `[ORA1-GEN-03]` and `[ORA1-GEN-05]`, by construction
 *
 * Sources are the only arity-0 nodes and are always consumed at level 1, so a terminal is
 * always an operator node and there is at least one operator between every source and every
 * terminal. Every node is reachable from a source (its inputs are existing nodes, rooted at
 * sources) and co-reachable to a terminal (a node is either consumed by a later node or is
 * itself terminal), so no island is emitted. Width steering towards
 * [GeneratorConfig.terminalCount] draws fan-in arms from the current frontier (fan-in), while a
 * multi-arity operator that cannot fill an arm from the frontier fills it from an *already
 * consumed* node — which is simultaneously fan-out on that node and, whenever it is an
 * ancestor of the frontier node, a diamond.
 *
 * ## Late joiner and multi-host placement (`[ORA1-GEN-09]`/`[ORA1-GEN-10]`)
 *
 * WHERE [GeneratorConfig.lateJoiner] is set, one extra [TerminalSpec] with `late = true` is
 * appended beyond the normal [GeneratorConfig.terminalCount] terminals, naming a uniformly
 * chosen **operator** node's handle — never a source. [TerminalSpec] carries only a node handle
 * (never a port), so "attaches to a node's outlet, never an operator input port" holds by the
 * type's own shape, not by a check this class performs. This generator only emits the
 * [TerminalSpec]; the mid-script [CaseStep.Barrier] it is linked behind is `ScriptGenerator`'s —
 * this class still applies and links nothing itself ("Static topologies only" above).
 *
 * WHERE [GeneratorConfig.hostCount] is greater than 1, [Builder.choosePlacement] assigns every
 * node handle a host ordinal in `0 until hostCount`, forcing one edge's endpoints onto two
 * distinct ordinals so the emitted placement always carries a genuinely cross-host
 * [ConnectStep] and always uses at least two ordinals — never left to the rng's luck.
 * `[ORA1-GEN-03]` already guarantees at least one edge exists (every source is consumed by an
 * operator), so the forced edge is always available. `hostCount == 1` places every handle at
 * ordinal `0`.
 */
class GraphGenerator(private val config: GeneratorConfig) {

    init {
        config.validateAgainstCatalog()
    }

    /** Generates the graph for [seed]. Equal `(seed, config)` pairs yield equal results. */
    fun generate(seed: Long): GeneratedGraph = generate(Random(seed))

    /**
     * Generates a graph, drawing every choice from [rng].
     *
     * @throws IllegalStateException if the configured vocabulary cannot express the configured
     *   topology — no consumable source shape, no operator, or a width that cannot converge to
     *   [GeneratorConfig.terminalCount]. Loud at generation time; a case violating an invariant
     *   is never emitted.
     */
    fun generate(rng: Random): GeneratedGraph {
        val vocabulary = config.vocabulary.map { id ->
            OperatorCatalog.entry(id)
                ?: error("GeneratorConfig.vocabulary names '$id', which is not registered in OperatorCatalog")
        }
        val sourceEntries = vocabulary.filter { it.shape.arity == 0 }
        val operatorEntries = vocabulary.filter { it.shape.arity > 0 }
        check(sourceEntries.isNotEmpty()) {
            "vocabulary ${config.vocabulary} holds no arity-0 entry; a case needs at least one source"
        }
        check(operatorEntries.isNotEmpty()) {
            "vocabulary ${config.vocabulary} holds no operator entry; [ORA1-GEN-03] requires at " +
                "least one operator between every source and every terminal"
        }

        val builder = Builder(config, operatorEntries, rng)
        return builder.build(sourceEntries)
    }

    companion object {

        /**
         * The generator's shape-satisfiability rule, as one named predicate: whether every
         * input port of [rule] could be filled from a graph whose nodes carry the shapes in
         * [available].
         *
         * This is the shape half of D4/`[ORA1-GEN-02]` — the rule [Builder.fillPorts] enforces
         * port by port, hoisted so it can be *asked* rather than only obeyed. `fillPorts` calls
         * it as its own precondition (a pure fast path: a port whose shape no node carries has
         * an empty pool and returns `null` anyway), so there is exactly one statement of the
         * rule and a consumer of it cannot drift from the generator.
         *
         * It is a necessary condition, not a sufficient one: `fillPorts` additionally requires
         * a **distinct** node per port, so a binary operator over one shape needs two nodes of
         * that shape, not one. Reachability answers computed from this predicate are therefore
         * upper bounds — an entry it calls unsatisfiable is genuinely unemittable, while one it
         * calls satisfiable may still be blocked by distinctness or by the convergence steering
         * in [Builder.attach]. `CatalogReachabilityTest` pins the closure it induces over
         * `OperatorCatalog`, and reads it with exactly that asymmetry.
         */
        fun satisfiedBy(rule: ShapeRule, available: Set<ElementShape>): Boolean =
            rule.inputs.all { it in available }

        /**
         * Lowers a [CaseTopology] to kernel steps: one [SpawnStep] per node carrying the
         * catalog's own `Entry.kernel` factory, then one [ConnectStep] per edge using the port
         * names the catalog declares. Spawns precede connects so `GraphSpec.applyTo` has every
         * endpoint resolved when it reaches a link.
         *
         * ## Why this is public rather than the generator's private business
         *
         * `GraphSpec` carries factory *lambdas*, which are opaque — nothing can read a spec back
         * into the topology it came from, so a component that wants to change a topology and get
         * the matching spec has to lower it again. The shrinker
         * (`civictech.oracle.shrink.Shrinker`, `[ORA1-SHRINK-01]`) is exactly that component: it
         * reduces at [CaseTopology] + [CaseScript] level and re-lowers every candidate. This
         * function is that seam, and it is the same code the generated path runs — a candidate
         * spec is therefore lowered by the identical rule a generated one is, not by a second
         * implementation that could drift.
         *
         * Determinism is a property of the *input*: equal [CaseTopology]s (node order included)
         * lower to equal [GraphSpec]s, because every iteration here runs over
         * [CaseTopology.nodes] in list order and every value drawn comes from the catalog entry
         * the node's id resolves to. Nothing here reads [Random], a clock or a hash.
         *
         * @throws IllegalStateException naming the catalog id, if a node's [TopologyNode.catalogId]
         *   is not registered, or naming the handle, if a node's [TopologyNode.inputs] name a
         *   handle the topology does not declare. Loud rather than silent: a spec lowered from a
         *   topology with a dangling edge would fail later, at apply time, with the cause several
         *   steps removed.
         */
        fun lower(topology: CaseTopology): GraphSpec {
            val entries = topology.nodes.associate { node ->
                node.handle to (
                    OperatorCatalog.entry(node.catalogId)
                        ?: error(
                            "Topology node '${node.handle}' names catalog id '${node.catalogId}', " +
                                "which is not registered in OperatorCatalog; registered ids are " +
                                "${OperatorCatalog.ids().sorted()}.",
                        )
                    )
            }
            val spawns: List<GraphStep> = topology.nodes.map { node ->
                SpawnStep(node.handle, entries.getValue(node.handle).kernel)
            }
            val connects: List<GraphStep> = topology.nodes.flatMap { node ->
                val entry = entries.getValue(node.handle)
                node.inputs.mapIndexed { port, from ->
                    val upstream = entries[from]
                        ?: error(
                            "Topology node '${node.handle}' takes input '$from' on port $port, " +
                                "which the topology does not declare; it declares " +
                                "${topology.nodes.map { it.handle }}.",
                        )
                    ConnectStep(
                        from = from,
                        outlet = upstream.shape.outputPort,
                        to = node.handle,
                        inlet = entry.shape.inputPorts[port],
                    )
                }
            }
            return GraphSpec(spawns + connects)
        }
    }

    /** One node of the graph under construction. */
    private class Node(
        val handle: String,
        val entry: OperatorCatalog.Entry,
        val inputs: List<String>,
        val source: SourceId?,
    ) {
        val shape: ElementShape get() = entry.shape.output
    }

    private class Builder(
        private val config: GeneratorConfig,
        private val operators: List<OperatorCatalog.Entry>,
        private val rng: Random,
    ) {
        /** Every node in creation order; `LinkedHashMap` so iteration order is construction order. */
        private val nodes = LinkedHashMap<String, Node>()

        private fun shapeOf(handle: String): ElementShape = nodes.getValue(handle).shape

        /** Operators that consume [shape] on at least one port — the linkability question, asked of the catalog. */
        private fun consumersOf(shape: ElementShape): List<OperatorCatalog.Entry> =
            operators.filter { shape in it.shape.inputs }

        /** Whether anything in the vocabulary can consume [shape]; a non-extendable node can only be a terminal. */
        private fun extendable(shape: ElementShape): Boolean = consumersOf(shape).isNotEmpty()

        fun build(sourceEntries: List<OperatorCatalog.Entry>): GeneratedGraph {
            val rootShape = chooseRootShape(sourceEntries)
            val rootEntries = sourceEntries.filter { it.shape.output == rootShape }

            repeat(config.sourceCount) { i ->
                val entry = rootEntries[rng.nextInt(rootEntries.size)]
                add(Node("source-$i", entry, emptyList(), SourceId("source-$i")))
            }

            val depth = config.depthRange.first + rng.nextInt(config.depthRange.last - config.depthRange.first + 1)
            var open: List<String> = nodes.keys.toList()
            for (level in 1..depth) {
                open = growOneLevel(level, open, isLast = level == depth)
            }

            val terminals = chooseTerminals(open)
            val allTerminals = if (config.lateJoiner) terminals + chooseLateTerminal() else terminals
            // Built before the spec, and the placement draw stays inside the constructor call, so
            // the rng is consumed in exactly the order it was before `lower` moved out of this
            // class: choosePlacement() then a lowering that draws nothing at all.
            val topology = CaseTopology(
                nodes = nodes.values.map { TopologyNode(it.handle, it.entry.id, it.inputs, it.source) },
                terminals = allTerminals,
                placement = choosePlacement(),
            )
            return GeneratedGraph(topology = topology, spec = GraphGenerator.lower(topology))
        }

        /**
         * The `[ORA1-GEN-09]` late terminal: one extra [TerminalSpec] with `late = true`,
         * naming a uniformly chosen **operator** node handle — a node with a null [Node.source]
         * so a source handle can never be picked, and never one of the [chooseTerminals] result
         * itself (though nothing forbids the two from coinciding by chance; both simply name a
         * node's outlet). `[ORA1-GEN-03]`'s own check already proved at least one operator node
         * exists before this is called.
         */
        private fun chooseLateTerminal(): TerminalSpec {
            val operatorHandles = nodes.values.filter { it.source == null }.map { it.handle }
            check(operatorHandles.isNotEmpty()) {
                "lateJoiner requires at least one operator node to attach the late terminal to, " +
                    "but this topology has none"
            }
            val handle = operatorHandles[rng.nextInt(operatorHandles.size)]
            return TerminalSpec("terminal-late", handle, late = true)
        }

        /**
         * `[ORA1-GEN-10]`: every node handle's host ordinal. `hostCount == 1` places
         * everything at ordinal `0`. For `hostCount > 1`, one edge (an (upstream, downstream)
         * handle pair drawn from every node's [Node.inputs]) is chosen first and its two
         * endpoints are forced onto **different** ordinals — guaranteeing both a genuinely
         * cross-host [ConnectStep] and at least two ordinals actually used — before every other
         * node independently draws an ordinal from [rng]. `[ORA1-GEN-03]` guarantees at least
         * one edge exists (every source is consumed by an operator), so the forced edge is
         * always available; no self-loop can be drawn since an input always names an
         * already-existing, distinct node.
         */
        private fun choosePlacement(): Map<String, Int> {
            if (config.hostCount <= 1) return nodes.keys.associateWith { 0 }

            val edges = nodes.values.flatMap { node -> node.inputs.map { from -> from to node.handle } }
            check(edges.isNotEmpty()) {
                "hostCount ${config.hostCount} > 1 needs at least one edge to place across hosts, " +
                    "but this topology has none"
            }
            val (from, to) = edges[rng.nextInt(edges.size)]

            val placement = LinkedHashMap<String, Int>()
            nodes.keys.forEach { handle -> placement[handle] = rng.nextInt(config.hostCount) }

            val fromOrdinal = placement.getValue(from)
            var toOrdinal = rng.nextInt(config.hostCount - 1)
            if (toOrdinal >= fromOrdinal) toOrdinal += 1
            placement[to] = toOrdinal

            return placement
        }

        /**
         * All sources of one case share an output shape, drawn from the arity-0 entries whose
         * output something in the vocabulary can actually consume. A source whose shape no
         * operator accepts would be a terminal itself, which `[ORA1-GEN-03]` forbids; a case
         * mixing two source shapes could not be converged to a single terminal by any fan-in
         * operator, since fan-in is shape-equal by definition. The shape is drawn per case, so
         * a sweep still covers every consumable source shape the vocabulary offers.
         */
        private fun chooseRootShape(sourceEntries: List<OperatorCatalog.Entry>): ElementShape {
            val candidates = sourceEntries.map { it.shape.output }.distinct().filter { extendable(it) }
            check(candidates.isNotEmpty()) {
                "no source in the vocabulary produces a shape any operator in the vocabulary consumes: " +
                    "sources emit ${sourceEntries.map { it.shape.output }.distinct()}, operators consume " +
                    "${operators.flatMap { it.shape.inputs }.distinct()} — [ORA1-GEN-03] cannot be satisfied"
            }
            return candidates[rng.nextInt(candidates.size)]
        }

        /**
         * Appends one level of operators over [open], returning the new frontier.
         *
         * Every extendable frontier node is consumed here — either as the node an operator was
         * chosen *for*, or as another operator's arm — which is what keeps sources off the
         * terminal list and every node on a source-to-terminal path. A frontier node no
         * operator can take stays open and becomes a terminal.
         */
        private fun growOneLevel(level: Int, open: List<String>, isLast: Boolean): List<String> {
            val frozen = open.filterNot { extendable(shapeOf(it)) }.toMutableList()
            val pending = ArrayDeque(open.filter { extendable(shapeOf(it)) })
            val produced = mutableListOf<String>()

            while (pending.isNotEmpty()) {
                val head = pending.removeFirst()
                // Width steering: the frontier has to converge on terminalCount, so while it is
                // wider, arms are drawn from the frontier itself (each such draw is one fan-in
                // that removes a node from it); once it is not, arms come from already-consumed
                // nodes instead, which preserves width and is where fan-out and diamonds arise.
                val width = frozen.size + produced.size + pending.size + 1
                val merging = width > config.terminalCount

                // `merging` is the port-filling steering and is dropped on the retry below;
                // `mustConverge` is the *accounting* question — is the frontier still wider than
                // terminalCount — and therefore holds across both attempts. See [attach].
                val built = attach(head, pending, merging, mustConverge = merging, isLast, level, produced.size)
                    ?: if (merging) {
                        attach(head, pending, merging = false, mustConverge = true, isLast, level, produced.size)
                    } else {
                        null
                    }

                if (built == null) frozen += head else produced += built
            }
            return frozen + produced
        }

        /**
         * Tries to append one operator consuming [head], returning the new node's handle or
         * `null` if no operator in the vocabulary can be satisfied from the current graph.
         *
         * [mustConverge] is the frontier-accounting flag: `true` while the frontier is still
         * wider than [GeneratorConfig.terminalCount], so shape divergence is forbidden (below).
         * It is deliberately separate from [merging], which is the *port-filling* steering and
         * is dropped on `growOneLevel`'s retry — the accounting question does not change just
         * because the first attempt could not fill a fan-in from the frontier.
         */
        private fun attach(
            head: String,
            pending: ArrayDeque<String>,
            merging: Boolean,
            mustConverge: Boolean,
            isLast: Boolean,
            level: Int,
            index: Int,
        ): String? {
            val headShape = shapeOf(head)
            // Before the last level, only operators whose own output something can consume are
            // eligible: a node the vocabulary cannot extend can only ever be a terminal, and one
            // produced mid-graph would sit on the frontier for every remaining level and push the
            // terminal count past what was configured.
            //
            // `extendable` is a question about the VOCABULARY, not about this graph, and that is
            // not enough on its own: `presenceCount` emits `MapOf`, which `join` consumes, so it
            // passes the filter — but a set-rooted graph holds no *second* `MapOf` node to fill
            // `join`'s other port, so the node is unextendable in fact and sits on the frontier
            // for every remaining level. That is why a wide vocabulary failed to converge on ~14%
            // of seeds (computenet-b9x7): one such node plus the real frontier node is two
            // unconsumed nodes against `terminalCount = 1`.
            //
            // So while the frontier still has to converge, an operator must also keep the
            // frontier's shape: `output == headShape`. The frontier starts homogeneous (all
            // sources share `chooseRootShape`'s shape), so this keeps it homogeneous until width
            // reaches terminalCount, which is exactly the condition under which the vocabulary's
            // fan-in over that shape can merge any two of its members. Once the frontier is no
            // longer over budget, shape-changing operators (`count`, `presenceCount`, `join`) are
            // eligible again — width cannot grow, so a dead end planted there is affordable.
            //
            // Yielding no candidate here is not a failure: `head` is extendable by vocabulary, so
            // it returns to the frontier and is retried at the next level, and the last level
            // admits every consumer unconditionally.
            //
            // This is a steering rule, not a proof of convergence — `chooseTerminals`' check
            // still stands, and a vocabulary with no fan-in over its own root shape still throws
            // there, correctly. What was measured (2026-08-20, macOS/arm64) is that the rule
            // clears the failures this generator actually had: over `Ids.ALL` at the wide sweep's
            // knobs the rate went 43/50 -> 50/50, and six configurations x 500 seeds — `Ids.ALL`
            // at terminalCount 1/2/3, sourceCount 3/5/6, depth 2..8, with and without
            // lateJoiner/hostCount 3, plus the map-rooted slice — generated 3000/3000. Nothing
            // here bounds the rate for a vocabulary outside that sample.
            val eligible = consumersOf(headShape).let { all ->
                when {
                    isLast -> all
                    mustConverge -> all.filter { it.shape.output == headShape }
                    else -> all.filter { extendable(it.shape.output) }.ifEmpty { all }
                }
            }
            val shuffled = eligible.shuffled(rng)
            // Stable sort, so within each arity band the shuffled order (and thus the draw) stands.
            val candidates = if (merging) shuffled.sortedByDescending { it.shape.arity } else shuffled

            candidates.forEach { entry ->
                val inputs = fillPorts(entry, head, pending, merging)
                if (inputs != null) {
                    inputs.forEach { pending.remove(it) }
                    val handle = "op-$level-$index"
                    add(Node(handle, entry, inputs, source = null))
                    return handle
                }
            }
            return null
        }

        /**
         * Assigns an upstream handle to every input port of [entry], with [head] on one of the
         * ports whose declared shape it matches. Returns `null` when some port cannot be filled
         * with a **distinct** node of the shape the catalog declares for it — distinct because
         * two arms from one node would be the same link twice, and because a fan-in of a node
         * with itself is not a fan-in.
         *
         * The [satisfiedBy] precondition is the shape half of that question, asked once instead
         * of port by port. It is here so the rule `CatalogReachabilityTest` computes its closure
         * from is the rule this generator runs, not a copy of it.
         *
         * It never changes this function's **answer**: a port whose shape no node in the graph
         * carries has an empty pool below and returns `null` there, so the precondition rejects
         * exactly the entries the port-by-port path would have rejected. What it can change is
         * **when** the rejection happens, and therefore the [rng] stream: it returns before the
         * head-port draw, so a rejection it takes early skips draws the port-by-port path would
         * have made. That only arises for a rule whose input shapes are *not* all present —
         * which, under every registration in `CoreOperators` today, cannot happen for a
         * candidate: every registered `ShapeRule` wants one shape on all its ports, and
         * candidates come from [consumersOf] of the head's shape, so the head itself already
         * witnesses every wanted shape and the predicate is always true here. Measured
         * 2026-08-20 (macOS/arm64): 1500 topologies over five core-vocabulary configurations
         * are byte-identical with and without the precondition, while a synthetic *heterogeneous*
         * binary rule (`SetOf(Scalar)` x `MapOf(Scalar, Scalar)`) does generate differently.
         * A consumer registering such a rule (`[ORA1-API-03]` allows it) gets deterministic
         * output for its own `(seed, config)`, but not the same output this generator would have
         * produced before the precondition existed.
         */
        private fun fillPorts(
            entry: OperatorCatalog.Entry,
            head: String,
            pending: ArrayDeque<String>,
            merging: Boolean,
        ): List<String>? {
            val wanted = entry.shape.inputs
            if (!satisfiedBy(entry.shape, nodes.values.mapTo(mutableSetOf()) { it.shape })) return null
            val headPorts = wanted.indices.filter { wanted[it] == shapeOf(head) }
            if (headPorts.isEmpty()) return null

            val assigned = arrayOfNulls<String>(wanted.size)
            assigned[headPorts[rng.nextInt(headPorts.size)]] = head
            val used = mutableSetOf(head)

            wanted.indices.forEach { port ->
                if (assigned[port] == null) {
                    val fromFrontier = pending.filter { it !in used && shapeOf(it) == wanted[port] }
                    val fromSettled = nodes.keys.filter {
                        it !in used && it !in pending && shapeOf(it) == wanted[port]
                    }
                    val pool = when {
                        merging -> fromFrontier
                        fromSettled.isNotEmpty() -> fromSettled
                        else -> fromFrontier
                    }
                    if (pool.isEmpty()) return null
                    val pick = pool[rng.nextInt(pool.size)]
                    assigned[port] = pick
                    used += pick
                }
            }
            return assigned.map { it!! }
        }

        /**
         * Every node left on the frontier must be a terminal — otherwise it is an island no
         * script ever observes (`[ORA1-GEN-03]`). If the configuration asks for more terminals
         * than that, the remainder observe interior operator nodes, which is sound: an interior
         * node is on a source-to-terminal path either way.
         */
        private fun chooseTerminals(open: List<String>): List<TerminalSpec> {
            val sourceHandles = nodes.values.filter { it.source != null }.map { it.handle }.toSet()
            val stranded = open.filter { it in sourceHandles }
            check(stranded.isEmpty()) {
                "sources $stranded were never consumed: no operator in the vocabulary " +
                    "${config.vocabulary} could take them, so they would be terminals — " +
                    "[ORA1-GEN-03] requires at least one operator between every source and every terminal"
            }
            check(open.size <= config.terminalCount) {
                "the generated frontier holds ${open.size} unconsumed nodes but terminalCount is " +
                    "${config.terminalCount}: the vocabulary ${config.vocabulary} offers no fan-in " +
                    "operator able to converge a width-${config.sourceCount} case within depth " +
                    "${config.depthRange}"
            }

            val extraPool = nodes.keys.filter { it !in open && it !in sourceHandles }
            check(open.size + extraPool.size >= config.terminalCount) {
                "terminalCount ${config.terminalCount} exceeds the ${open.size + extraPool.size} " +
                    "operator nodes this topology holds; widen depthRange or sourceCount"
            }

            val extras = extraPool.shuffled(rng).take(config.terminalCount - open.size)
            return (open + extras).mapIndexed { i, handle -> TerminalSpec("terminal-$i", handle) }
        }

        private fun add(node: Node) {
            nodes[node.handle] = node
        }
    }
}
