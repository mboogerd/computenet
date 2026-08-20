package civictech.oracle.gen

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.ShapeRule
import civictech.oracle.model.ElementShape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

/**
 * [GraphGenerator]'s structural contract: `[ORA1-GEN-02]`'s shape-typed construction,
 * `[ORA1-GEN-03]`'s "an operator between every source and every terminal, and no islands",
 * `[ORA1-GEN-05]`'s fan-in/fan-out/diamond coverage, `[ORA1-API-03]`'s zero-edit pickup of a
 * consumer-registered operator, and the D3 rendering rules (catalog factories, deterministic
 * handles, a `Serializable` spec).
 *
 * Structural and generative only. No reference-model evaluation and no differential run happens
 * here — those are the runner feature's (computenet-4ru.8); the live-host half of
 * `[ORA1-GEN-02]` is `GraphSpecLinkSweepTest`.
 *
 * [OperatorCatalog] is a process-wide mutable singleton, so every test here registers in
 * [BeforeEach] and empties it in [AfterEach] — the sibling generator tasks' tests share this
 * JVM.
 */
class GraphGeneratorTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    // -- [ORA1-GEN-05]: fan-in, fan-out and diamonds in a default sweep ------

    /**
     * Ex/diamond, as the feature writes it: depth 3..5, a vocabulary including `union`, 100
     * seeds, at least one spec holding a source S and a terminal T joined by two *distinct*
     * paths — found by reachability over the emitted `ConnectStep`s, not by asking the
     * generator what it thinks it built.
     *
     * The same population carries the other two `[ORA1-GEN-05]` shapes: a fan-out node (one
     * node feeding two or more consumers) and a 2-arity fan-in whose two arms are distinct
     * upstream nodes.
     *
     * The floors below are the criterion's own ("at least one"), deliberately not tightened to
     * the rates this population actually shows — measured 2026-08-18 on this config as 73/100
     * seeds with a diamond, 73/100 with a fan-out and 100/100 with a genuine fan-in. The
     * measurement is what says the assertion is not vacuous; the floor stays at the criterion so
     * a legitimate change to how depth or width is steered does not redden a test that was only
     * ever asked to prove these shapes are reachable.
     */
    @Test
    fun `a default sweep contains a diamond, a fan-out, and a genuine fan-in`() {
        val config = defaultConfig()
        val generator = GraphGenerator(config)

        var diamonds = 0
        var fanOuts = 0
        var fanIns = 0
        (0L until 100L).forEach { seed ->
            val graph = generator.generate(seed)
            if (diamondSourceTerminalPairs(graph).isNotEmpty()) diamonds++
            if (fanOutNodes(graph.spec).isNotEmpty()) fanOuts++
            if (fanInNodes(graph.topology).isNotEmpty()) fanIns++
        }

        withClue("seeds whose spec holds two distinct source→terminal paths") { diamonds shouldBeGreaterThanOrEqual 1 }
        withClue("seeds whose spec holds a node with >= 2 consumers") { fanOuts shouldBeGreaterThanOrEqual 1 }
        withClue("seeds whose topology holds a 2-arity node over two distinct upstreams") {
            fanIns shouldBeGreaterThanOrEqual 1
        }
    }

    // -- [ORA1-GEN-03]: an operator between every source and every terminal --

    @Test
    fun `no terminal is a source and every node lies on a source-to-terminal path`() {
        val generator = GraphGenerator(defaultConfig())
        (0L until 60L).forEach { seed ->
            val graph = generator.generate(seed)
            val topology = graph.topology
            val sources = topology.nodes.filter { it.source != null }.map { it.handle }.toSet()
            val terminals = topology.terminals.map { it.handle }.toSet()

            withClue("seed $seed: a terminal is an arity-0 node") {
                terminals.intersect(sources).shouldBeEmpty()
            }

            val downstream = topology.nodes.associate { node ->
                node.handle to topology.nodes.filter { node.handle in it.inputs }.map { it.handle }
            }
            val upstream = topology.nodes.associate { it.handle to it.inputs }

            val reachableFromSource = closure(sources, downstream)
            val coReachableToTerminal = closure(terminals, upstream)
            topology.nodes.forEach { node ->
                withClue("seed $seed: node ${node.handle} (${node.catalogId}) is not reachable from a source") {
                    (node.handle in reachableFromSource) shouldBe true
                }
                withClue("seed $seed: node ${node.handle} (${node.catalogId}) reaches no terminal") {
                    (node.handle in coReachableToTerminal) shouldBe true
                }
            }
        }
    }

    // -- [ORA1-GEN-04]: the three topology knobs this task owns --------------

    @Test
    fun `sourceCount is honoured exactly`() {
        (1..4).forEach { sourceCount ->
            val generator = GraphGenerator(defaultConfig(sourceCount = sourceCount))
            (0L until 20L).forEach { seed ->
                val topology = generator.generate(seed).topology
                withClue("sourceCount=$sourceCount seed=$seed") {
                    topology.nodes.count { it.source != null } shouldBe sourceCount
                }
                withClue("sourceCount=$sourceCount seed=$seed: source ids are distinct") {
                    topology.nodes.mapNotNull { it.source }.distinct().size shouldBe sourceCount
                }
            }
        }
    }

    @Test
    fun `terminalCount is honoured exactly`() {
        (1..3).forEach { terminalCount ->
            val generator = GraphGenerator(defaultConfig(sourceCount = 4, terminalCount = terminalCount))
            (0L until 20L).forEach { seed ->
                val topology = generator.generate(seed).topology
                withClue("terminalCount=$terminalCount seed=$seed") {
                    topology.terminals.size shouldBe terminalCount
                    topology.terminals.map { it.handle }.distinct().size shouldBe terminalCount
                }
            }
        }
    }

    @Test
    fun `depthRange bounds the longest source-to-terminal operator path`() {
        listOf(2..2, 3..3, 3..5, 6..6).forEach { depthRange ->
            val generator = GraphGenerator(defaultConfig(depthRange = depthRange))
            (0L until 20L).forEach { seed ->
                val graph = generator.generate(seed)
                withClue("depthRange=$depthRange seed=$seed") {
                    (longestOperatorDepth(graph.topology) in depthRange) shouldBe true
                }
            }
        }
    }

    // -- D4 / [ORA1-API-03]: a consumer-registered operator, zero edits ------

    /**
     * The shape rules the generator links by are **data it reads from the catalog**, so an
     * operator nobody anticipated is emitted purely by being registered and named in the
     * vocabulary. The synthetic entry below reuses an existing kernel factory and reference
     * model under a fresh id with a valid [ShapeRule]; no line of `GraphGenerator.kt` mentions
     * it, and the companion assertion — that the file branches on no catalog id at all — is
     * `the generator never branches on an operator id`.
     */
    @Test
    fun `an operator registered after the generator was written is emitted when the vocabulary names it`() {
        val borrowed = OperatorCatalog.entry(CoreOperators.Ids.FILTER)!!
        OperatorCatalog.register(
            id = SYNTHETIC_ID,
            shape = ShapeRule.unary(
                ElementShape.SetOf(ElementShape.Scalar),
                ElementShape.SetOf(ElementShape.Scalar),
            ),
            kernel = borrowed.kernel,
            model = borrowed.model,
        )

        val config = GeneratorConfig(
            depthRange = 3..4,
            sourceCount = 2,
            vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.UNION, SYNTHETIC_ID),
            elementDomainSize = 4,
            scriptLength = 10,
            addRemoveRatio = 0.5,
            unobservedRemoveRatio = 0.2,
            terminalCount = 1,
        ).validated()

        val emitted = (0L until 40L).flatMap { seed ->
            GraphGenerator(config).generate(seed).topology.nodes.map { it.catalogId }
        }.distinct()

        emitted shouldContain SYNTHETIC_ID
    }

    /**
     * D4 as a property of the source text: the generator decides linkability from
     * [ShapeRule]s alone, so no catalog id may appear in it. A `when`/`if` over ids is exactly
     * the generator edit `[ORA1-API-03]` forbids, and grepping is the only way to assert its
     * absence rather than its current unexercised-ness.
     */
    @Test
    fun `the generator never branches on an operator id`() {
        val relative = "src/main/kotlin/civictech/oracle/gen/GraphGenerator.kt"
        val roots = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }.toList()
        val source = roots.map { java.io.File(it, relative) }
            .plus(roots.map { java.io.File(it, "oracle/$relative") })
            .firstOrNull { it.isFile }
        withClue("could not locate GraphGenerator.kt from ${java.io.File(".").absolutePath}") {
            (source != null) shouldBe true
        }
        val text = source!!.readText()
        val quoted = Regex("\"([^\"\\\\\n]*)\"").findAll(text).map { it.groupValues[1] }.toSet()
        val mentioned = CoreOperators.Ids.ALL.filter { it in quoted }
        withClue("GraphGenerator.kt quotes catalog ids, so it decides by name rather than by shape") {
            mentioned.shouldBeEmpty()
        }
    }

    // -- D3: catalog factories, deterministic handles, a serializable spec ---

    @Test
    fun `every spawn factory is a catalog entry's own kernel factory for a vocabulary id`() {
        val config = defaultConfig()
        val allowed = config.vocabulary.map { OperatorCatalog.entry(it)!!.kernel }
        (0L until 30L).forEach { seed ->
            val spec = GraphGenerator(config).generate(seed).spec
            spec.steps.filterIsInstance<SpawnStep>().forEach { step ->
                withClue("seed $seed: ${step.handle} was spawned with a factory the catalog does not hold") {
                    allowed.any { it === step.factory } shouldBe true
                }
            }
        }
    }

    @Test
    fun `two generations from equal seed and config are identical`() {
        val config = defaultConfig()
        (0L until 30L).forEach { seed ->
            val left = GraphGenerator(config).generate(seed)
            val right = GraphGenerator(config).generate(seed)
            withClue("seed $seed") {
                left.topology shouldBe right.topology
                left.spec shouldBe right.spec
                left.spec.steps.filterIsInstance<SpawnStep>().map { it.handle } shouldBe
                    right.spec.steps.filterIsInstance<SpawnStep>().map { it.handle }
            }
        }
    }

    @Test
    fun `handles are derived from the topology, never from a UUID or hash order`() {
        val graph = GraphGenerator(defaultConfig()).generate(7L)
        val handles = graph.topology.nodes.map { it.handle }
        withClue("handles: $handles") {
            handles.all { it.matches(Regex("(source|op)(-\\d+)+")) } shouldBe true
        }
        handles.distinct().size shouldBe handles.size
    }

    @Test
    fun `a generated spec Java-serializes`() {
        (0L until 10L).forEach { seed ->
            val graph = GraphGenerator(defaultConfig()).generate(seed)
            val bytes = ByteArrayOutputStream()
            ObjectOutputStream(bytes).use { it.writeObject(graph) }
            withClue("seed $seed") { (bytes.size() > 0) shouldBe true }
        }
    }

    // -- static topology only ------------------------------------------------

    /**
     * The emitted spec is spawns and connects and nothing else, and no terminal is a late
     * joiner: `QuorumSetModel` equates the kernel's live arm count with the graph's *static*
     * arm count (`civictech.oracle.model.SetOperatorModels`), so a mid-run link change would
     * put a case outside what the reference defines. The late-joiner terminal belongs to the
     * sibling task and attaches to an existing node's output port.
     */
    @Test
    fun `the emitted spec holds only spawn and connect steps, and no late terminal`() {
        val config = defaultConfig()
        (0L until 30L).forEach { seed ->
            val graph = GraphGenerator(config).generate(seed)
            val spawns = graph.spec.steps.filterIsInstance<SpawnStep>()
            val connects = graph.spec.steps.filterIsInstance<ConnectStep>()
            withClue("seed $seed") {
                spawns.size + connects.size shouldBe graph.spec.steps.size
                spawns.size shouldBe graph.topology.nodes.size
                connects.size shouldBe graph.topology.nodes.sumOf { it.inputs.size }
                graph.topology.terminals.none { it.late } shouldBe true
                graph.topology.placement.values.toSet() shouldBe setOf(0)
            }
        }
    }

    @Test
    fun `connect steps use the port names the catalog declares`() {
        val config = defaultConfig()
        (0L until 20L).forEach { seed ->
            val graph = GraphGenerator(config).generate(seed)
            val byHandle = graph.topology.nodes.associateBy { it.handle }
            val expected = graph.topology.nodes.flatMap { node ->
                val rule = OperatorCatalog.shapeOf(node.catalogId)!!
                node.inputs.mapIndexed { port, from ->
                    ConnectStep(
                        from,
                        OperatorCatalog.shapeOf(byHandle.getValue(from).catalogId)!!.outputPort,
                        node.handle,
                        rule.inputPorts[port],
                    )
                }
            }
            withClue("seed $seed") {
                graph.spec.steps.filterIsInstance<ConnectStep>() shouldBe expected
            }
        }
    }

    // -- computenet-b9x7: a wide vocabulary converges on terminalCount ------

    /**
     * Generation over the **whole** core vocabulary converges on [GeneratorConfig.terminalCount]
     * for every seed, at every shape of the topology knobs this sweep varies.
     *
     * The regression this pins: `attach` used to be free to plant a shape-diverging node before
     * the last level (`presenceCount` emits `MapOf` in a set-rooted graph, `join` emits
     * `MapOf(Scalar, Tuple(2))` in a map-rooted one), and such a node is unconsumable in fact —
     * no second node of its shape exists to fill the other port of anything that consumes it —
     * so it sat on the frontier for every remaining level. `chooseTerminals` then threw
     * `the generated frontier holds 2 unconsumed nodes but terminalCount is 1` on 7 of 50 seeds
     * (measured 2026-08-18 and re-measured 2026-08-20, both 43/50).
     *
     * `GraphSpecLinkSweepTest` is the live-host half of the same criterion; this one is cheap
     * enough to sweep 200 seeds across five configurations, which is what makes it a guard on
     * the *rule* rather than on one set of knobs. It states nothing about a vocabulary outside
     * `CoreOperators`: a vocabulary offering no fan-in over its own root shape genuinely cannot
     * converge, and `chooseTerminals` still throws for it — see
     * `a vocabulary that cannot converge to terminalCount fails loudly` below, which is the
     * assertion that the throw survived this fix.
     */
    @Test
    fun `every seed over the whole core vocabulary converges on terminalCount`() {
        val configs = listOf(
            defaultConfig().copy(vocabulary = CoreOperators.Ids.ALL).validated(),
            defaultConfig(sourceCount = 6, depthRange = 4..8)
                .copy(vocabulary = CoreOperators.Ids.ALL).validated(),
            defaultConfig(sourceCount = 5, depthRange = 2..6, terminalCount = 2)
                .copy(vocabulary = CoreOperators.Ids.ALL).validated(),
            defaultConfig(terminalCount = 3).copy(vocabulary = CoreOperators.Ids.ALL).validated(),
            defaultConfig().copy(
                vocabulary = listOf(
                    CoreOperators.Ids.MAP,
                    CoreOperators.Ids.JOIN,
                    CoreOperators.Ids.COMBINE_LATEST,
                    CoreOperators.Ids.LOOKUP_JOIN,
                ),
            ).validated(),
        )

        configs.forEach { config ->
            val generator = GraphGenerator(config)
            (0L until 200L).forEach { seed ->
                val graph = withClue("seed $seed, vocabulary ${config.vocabulary}") {
                    generator.generate(seed)
                }
                // The terminal list is the frontier the throw was counting, so asserting its
                // size is asserting convergence itself, not a proxy for it.
                withClue("seed $seed: terminals ${graph.topology.terminals.map { it.handle }}") {
                    graph.topology.terminals.count { !it.late } shouldBe config.terminalCount
                }
            }
        }
    }

    /**
     * The other half of computenet-b9x7: the fix above is a *steering* rule, and the loud
     * failure it made unreachable for `CoreOperators` is still reachable — and still loud — for
     * a vocabulary that genuinely cannot converge.
     *
     * `set` + `filter` offers no fan-in over `SetOf(Scalar)` at all, so three sources can only
     * ever remain three frontier nodes. `chooseTerminals` names the count, the configured
     * `terminalCount` and the vocabulary, so a caller reading the message can tell a
     * misconfiguration from a generator defect without a debugger.
     */
    @Test
    fun `a vocabulary that cannot converge to terminalCount fails loudly`() {
        val config = defaultConfig()
            .copy(vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.FILTER))
            .validated()

        val thrown = shouldThrow<IllegalStateException> { GraphGenerator(config).generate(0L) }

        thrown.message!! shouldContain "the generated frontier holds 3 unconsumed nodes but terminalCount is 1"
        thrown.message!! shouldContain "offers no fan-in operator able to converge"
    }

    private companion object {
        const val SYNTHETIC_ID = "syntheticSetPassThrough"

        /**
         * The default sweep configuration: the **set-rooted** slice of the core vocabulary.
         *
         * Two different reasons keep the rest of the catalog out, and they are not
         * interchangeable:
         *
         * - **Reachable only through the pair bootstrap.** The pair-shaped entries (`joinSet`,
         *   `semiJoin`, `antiJoin` and the whole `groupBy*` family) consume `SetOf(Tuple(2))`.
         *   Until computenet-4ru.16 nothing produced that shape without already consuming it,
         *   so shape-typed generation could never reach them and naming them here would have
         *   tested nothing; `keyBy` now bridges `SetOf(Scalar)` to it, and the sweep over the
         *   whole of `Ids.ALL` below does emit the family. This default slice still leaves
         *   `keyBy` and its consumers out, so it stays a single-shape-family configuration.
         * - **Reachable, simply not swept here.** The map-rooted slice (`map` as the source,
         *   with `join`, `combineLatest` and `lookupJoin` over `MapOf(Scalar, Scalar)`) *is*
         *   fully generable and links cleanly. This suite is set-rooted only; the assertion
         *   that covers that slice — and the one that covers the whole of `Ids.ALL` — is
         *   `GraphSpecLinkSweepTest` (computenet-b9x7).
         */
        fun defaultConfig(
            depthRange: IntRange = 3..5,
            sourceCount: Int = 3,
            terminalCount: Int = 1,
        ) = GeneratorConfig(
            depthRange = depthRange,
            sourceCount = sourceCount,
            vocabulary = listOf(
                CoreOperators.Ids.SET,
                CoreOperators.Ids.KEYED_SET,
                CoreOperators.Ids.FILTER,
                CoreOperators.Ids.FLAT_MAP_SET,
                CoreOperators.Ids.MAP_SET,
                CoreOperators.Ids.COUNT,
                CoreOperators.Ids.UNION,
                CoreOperators.Ids.INTERSECT,
                CoreOperators.Ids.PRESENCE_COUNT,
                CoreOperators.Ids.QUORUM_SET,
            ),
            elementDomainSize = 6,
            scriptLength = 40,
            addRemoveRatio = 0.6,
            unobservedRemoveRatio = 0.25,
            terminalCount = terminalCount,
        ).validated()

        fun closure(roots: Set<String>, edges: Map<String, List<String>>): Set<String> {
            val seen = LinkedHashSet(roots)
            val queue = ArrayDeque(roots)
            while (queue.isNotEmpty()) {
                edges[queue.removeFirst()].orEmpty().forEach { if (seen.add(it)) queue += it }
            }
            return seen
        }

        fun fanOutNodes(spec: GraphSpec): List<String> =
            spec.steps.filterIsInstance<ConnectStep>()
                .groupBy { it.from }
                .filterValues { it.map { step -> step.to }.distinct().size >= 2 }
                .keys.toList()

        fun fanInNodes(topology: CaseTopology): List<String> =
            topology.nodes.filter { it.inputs.size >= 2 && it.inputs.distinct().size >= 2 }.map { it.handle }

        /** Number of distinct paths `from → to` over the spec's connect steps (the graph is a DAG). */
        fun pathCount(spec: GraphSpec, from: String, to: String): Long {
            val out = spec.steps.filterIsInstance<ConnectStep>().groupBy({ it.from }, { it.to })
            val memo = HashMap<String, Long>()
            fun count(node: String): Long = memo.getOrPut(node) {
                if (node == to) 1L else out[node].orEmpty().sumOf { count(it) }
            }
            return if (from == to) 1L else out[from].orEmpty().sumOf { count(it) }
        }

        /** Source/terminal pairs joined by two or more distinct paths — the Ex/diamond witness. */
        fun diamondSourceTerminalPairs(graph: GeneratedGraph): List<Pair<String, String>> {
            val sources = graph.topology.nodes.filter { it.source != null }.map { it.handle }
            val terminals = graph.topology.terminals.map { it.handle }
            return sources.flatMap { s -> terminals.map { t -> s to t } }
                .filter { (s, t) -> pathCount(graph.spec, s, t) >= 2L }
        }

        /** Longest source→node operator path, i.e. the case's realized depth. */
        fun longestOperatorDepth(topology: CaseTopology): Int {
            val byHandle = topology.nodes.associateBy { it.handle }
            val depths = HashMap<String, Int>()
            fun depth(handle: String): Int = depths.getOrPut(handle) {
                val node = byHandle.getValue(handle)
                if (node.inputs.isEmpty()) 0 else 1 + node.inputs.maxOf { depth(it) }
            }
            return topology.terminals.maxOf { depth(it.handle) }
        }
    }
}
