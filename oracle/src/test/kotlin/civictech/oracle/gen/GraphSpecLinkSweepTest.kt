package civictech.oracle.gen

import civictech.cell.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `[ORA1-GEN-02]` on a live host: a sweep-sized batch of generated `GraphSpec`s is applied to a
 * simulated `ManagedHost` and run to idle, and **no link is rejected and no dead letter is
 * emitted**.
 *
 * This is the half of `[ORA1-GEN-02]` that structural assertions cannot reach.
 * `GraphGeneratorTest` checks that the generator only ever drew shape-equal edges; this checks
 * that shape equality as the catalog declares it is the same thing the kernel's linker accepts —
 * a `ShapeRule` claiming a port name or an element shape the cell does not actually have would
 * pass every structural test in the suite and reject here.
 *
 * `GraphSpec.applyTo` throws on the first rejected link (`GraphDsl.kt`), so a link failure fails
 * the test by escaping; the dead-letter subscription follows `GenerativeGraphTest`'s pattern
 * (`kernel/src/test/kotlin/civictech/cell/verify/GenerativeGraphTest.kt`), which is the repo's
 * established way of catching the failures the host swallows rather than throws.
 *
 * Three vocabularies are swept, all at `terminalCount = 1`: the set-rooted slice, the map-rooted
 * slice, and the whole of `CoreOperators.Ids.ALL` — the last of which failed to *generate* on
 * 7/50 seeds before computenet-b9x7 (see [wideConfig]).
 *
 * No values are asserted: driving the graph is the script generator's and the runner's
 * (computenet-4ru.8). This is a topology-linkability probe and states nothing more.
 */
class GraphSpecLinkSweepTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    @Test
    fun `every spec in a sweep links cleanly and quiesces with no dead letters`() {
        sweep(sweepConfig())
    }

    /**
     * `[ORA1-GEN-02]` over the map-rooted slice: `map` sources with the three map-shaped joins.
     *
     * The slice is generable — it was measured 50/50 clean during the review of
     * computenet-4ru.6.2 — but nothing asserted it until computenet-b9x7, so this is the named
     * test that criterion asked for. It is a genuinely different linker surface from the
     * set-rooted sweep: `lookupJoin` declares non-default inlet names (`fact`/`dimension`) and
     * `join` declares an output shape (`MapOf(Scalar, Tuple(2))`) no set-rooted spec ever emits.
     */
    @Test
    fun `every spec in a map-rooted sweep links cleanly and quiesces with no dead letters`() {
        val specs = sweep(mapRootedConfig())

        // Not a vacuous sweep: the slice's own operators have to actually appear in it, or this
        // would pass over 50 specs made of nothing but `map` sources.
        val ids = specs.flatMap { it.nodes.map { node -> node.catalogId } }.distinct()
        withClue("catalog ids across the map-rooted sweep: $ids") {
            ids shouldContain CoreOperators.Ids.MAP
            ids shouldContain CoreOperators.Ids.COMBINE_LATEST
            ids shouldContain CoreOperators.Ids.LOOKUP_JOIN
            ids shouldContain CoreOperators.Ids.JOIN
        }
    }

    /**
     * `[ORA1-GEN-02]` over the **whole** core vocabulary — computenet-b9x7's first criterion,
     * and the regression test for its fix.
     *
     * Against the unfixed generator this sweep did not reach `applyTo` at all: 7 of these 50
     * seeds threw at generation time with `IllegalStateException: the generated frontier holds 2
     * unconsumed nodes but terminalCount is 1`, because a shape-diverging node (`presenceCount`
     * in a set-rooted graph, `join` in a map-rooted one) could be planted before the last level
     * and then never consumed. `GraphGenerator.attach`'s `mustConverge` filter is what makes it
     * 50/50; see its comment for the mechanism.
     */
    @Test
    fun `every spec in a wide-vocabulary sweep links cleanly and quiesces with no dead letters`() {
        sweep(wideConfig())
    }

    /**
     * Generates [SWEEP_SEEDS] specs from [config], applies each to a fresh [SimWorld] host and
     * runs it to idle, failing on a rejected link (which escapes from `applyTo`) or any dead
     * letter. Returns the topologies, so a caller can additionally assert what was in them.
     */
    private fun sweep(config: GeneratorConfig): List<CaseTopology> {
        val generator = GraphGenerator(config)
        val topologies = mutableListOf<CaseTopology>()

        (0L until SWEEP_SEEDS).forEach { seed ->
            val graph = generator.generate(seed)
            val world = SimWorld(seed = seed)
            val letters = mutableListOf<DeadLetter>()
            world.host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            letters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )

            val refs = withClue("seed $seed: ${graph.topology.nodes.map { it.handle to it.catalogId }}") {
                graph.spec.applyTo(world.host.managementInlet)
            }
            world.runToIdle()

            withClue("seed $seed: every node was spawned") {
                refs.keys shouldBe graph.topology.nodes.map { it.handle }.toSet()
            }
            withClue("seed $seed: dead letters ${letters.map { it.toString() }}") {
                letters.shouldBeEmpty()
            }
            topologies += graph.topology
        }

        withClue("the sweep must actually have applied its whole batch") {
            topologies.size shouldBe SWEEP_SEEDS.toInt()
        }
        return topologies
    }

    private companion object {
        /** The acceptance criterion's floor is 50 specs; every sweep here runs that many. */
        const val SWEEP_SEEDS = 50L

        /**
         * The **set-rooted** slice of the core vocabulary: the entries reachable from a
         * `SetOf(Scalar)` root without leaving that shape family.
         *
         * The pair-shaped entries (`joinSet`, `semiJoin`, `antiJoin`, the `groupBy*` family)
         * are deliberately out of this slice, not out of reach: since computenet-4ru.16 the
         * `keyBy` bootstrap takes `SetOf(Scalar)` into `SetOf(Tuple(2))`, so [wideConfig] —
         * which names the whole catalog — really does emit them (measured in
         * `civictech.oracle.bind.PairShapeBootstrapTest`). They are omitted here to keep this
         * sweep a single-family one; [wideConfig] below is the slice that covers them.
         */
        fun sweepConfig() = GeneratorConfig(
            depthRange = 3..5,
            sourceCount = 3,
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
            terminalCount = 1,
        ).validated()

        /** The **map-rooted** slice: `map` sources and the three map-shaped joins. */
        fun mapRootedConfig() = sweepConfig().copy(
            vocabulary = listOf(
                CoreOperators.Ids.MAP,
                CoreOperators.Ids.JOIN,
                CoreOperators.Ids.COMBINE_LATEST,
                CoreOperators.Ids.LOOKUP_JOIN,
            ),
        ).validated()

        /** The whole registered core vocabulary, at the same topology knobs. */
        fun wideConfig() = sweepConfig().copy(vocabulary = CoreOperators.Ids.ALL).validated()
    }
}
