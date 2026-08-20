package civictech.oracle.bind

import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.gen.GraphGenerator
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * computenet-4ru.16's acceptance, measured on a **generated population** rather than argued
 * from the shape graph: with `keyBy` registered, a sweep over the whole core vocabulary really
 * does emit `joinSet`/`semiJoin`/`antiJoin` nodes and `groupBy*` nodes.
 *
 * ## Why this test exists beside `CatalogReachabilityTest`
 *
 * The two answer different questions and neither implies the other.
 * [CatalogReachabilityTest] computes the reachable-shape *closure* — a necessary condition,
 * and an upper bound on what a sweep can emit (its own KDoc says so). An entry it calls
 * reachable is not thereby proven to appear in any particular population: `GraphGenerator`
 * additionally demands a **distinct** node per port, and steers the frontier towards
 * `terminalCount` by admitting only shape-preserving operators while it is still converging
 * (computenet-b9x7). `joinSet` in particular needs *two* distinct `SetOf(Tuple(2))` nodes, and
 * nothing in the closure argument says a run ever holds two at once.
 *
 * So this test draws real topologies and counts the operator ids that appear in them. It is
 * the bead's acceptance clause in its own terms: "a default-config generated population
 * contains at least one `joinSet`/`semiJoin`/`antiJoin` node and at least one `groupBy*` node".
 *
 * ## What "default config" means here
 *
 * The knobs `GraphGeneratorTest.defaultConfig` and `GraphSpecLinkSweepTest.sweepConfig` both
 * use — depth 3..5, three sources, element domain 6 — over the **whole** registered vocabulary
 * ([CoreOperators.Ids.ALL]), at each of the three terminal counts those suites sweep. The
 * vocabulary is `Ids.ALL` rather than a hand-picked slice on purpose: the hole this bead closes
 * was that naming the pair family in a vocabulary changed nothing, and the honest check is that
 * naming it now does.
 *
 * The per-config counts are printed, not just asserted, so a reviewer can see the margin rather
 * than a bare green tick — and so a future change that leaves the family technically reachable
 * but vanishingly rare is visible as a collapsing number rather than a still-passing test.
 */
class PairShapeBootstrapTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    /** How many topologies of the [SWEEP_SEEDS] drawn contain at least one id from [family]. */
    private fun seedsContaining(config: GeneratorConfig, family: Collection<String>): Int {
        val generator = GraphGenerator(config)
        return (0L until SWEEP_SEEDS).count { seed ->
            generator.generate(seed).topology.nodes.any { it.catalogId in family }
        }
    }

    @Test
    fun `a default-config generated population contains pair-set joins and groupBy nodes`() {
        val report = StringBuilder("pair-shaped coverage over $SWEEP_SEEDS seeds, vocabulary Ids.ALL\n")
        var totalJoins = 0
        var totalGroupBys = 0
        var totalKeyBys = 0

        wideConfigs().forEach { (label, config) ->
            val joins = seedsContaining(config, PAIR_SET_JOINS)
            val groupBys = seedsContaining(config, GROUP_BY_FAMILY)
            val keyBys = seedsContaining(config, setOf(CoreOperators.Ids.KEY_BY))
            totalJoins += joins
            totalGroupBys += groupBys
            totalKeyBys += keyBys
            report.append("  $label: keyBy=$keyBys joins=$joins groupBy=$groupBys\n")
        }
        println(report)

        withClue(
            "$report\nNo generated topology holds a pair-set join or a groupBy node. Before " +
                "computenet-4ru.16 every one of these counts was 0, because nothing in the " +
                "catalog produced SetOf(Tuple(2)) without already consuming it; `keyBy` is the " +
                "bootstrap that changed that. A zero here means the bootstrap entry has been " +
                "unregistered, reshaped, or steered out of reach by a generator change.",
        ) {
            totalKeyBys shouldBeGreaterThan 0
            totalJoins shouldBeGreaterThan 0
            totalGroupBys shouldBeGreaterThan 0
        }
    }

    /**
     * The whole eleven, not just the two families in aggregate: an entry that stayed
     * unemittable while a sibling carried the count would satisfy the assertion above and
     * still leave a hole. Every one of the eleven entries `CatalogReachabilityTest` used to pin
     * as unreachable is emitted by some seed of some swept configuration.
     */
    @Test
    fun `every formerly unreachable entry is emitted by some seed`() {
        val emitted = wideConfigs().flatMapTo(mutableSetOf()) { (_, config) ->
            val generator = GraphGenerator(config)
            (0L until SWEEP_SEEDS).flatMap { seed ->
                generator.generate(seed).topology.nodes.map { it.catalogId }
            }
        }
        val formerlyUnreachable = PAIR_SET_JOINS + GROUP_BY_FAMILY
        withClue("emitted ids across every swept configuration: ${emitted.sorted()}") {
            (formerlyUnreachable - emitted) shouldBe emptySet()
        }
    }

    private companion object {
        /**
         * 200 seeds per configuration — the same population size `GraphGeneratorTest`'s
         * convergence sweep draws, so a rare-but-present family is measured on the same scale
         * the generator's other structural claims are.
         */
        const val SWEEP_SEEDS = 200L

        val PAIR_SET_JOINS: Set<String> = setOf(
            CoreOperators.Ids.JOIN_SET,
            CoreOperators.Ids.SEMI_JOIN,
            CoreOperators.Ids.ANTI_JOIN,
        )

        val GROUP_BY_FAMILY: Set<String> =
            (CoreOperators.Ids.GROUP_BY_AGGREGATES + CoreOperators.Ids.GROUP_BY_GLOBAL).toSet()

        /**
         * The default knobs at each terminal count `GraphGeneratorTest`'s own sweep uses. A
         * wider frontier is what lets two pair-shaped nodes coexist, so the three counts are
         * not redundant: they are the axis the join family is most sensitive to.
         */
        fun wideConfigs(): List<Pair<String, GeneratorConfig>> = listOf(
            "terminalCount=1" to wideConfig(terminalCount = 1),
            "terminalCount=2" to wideConfig(terminalCount = 2),
            "terminalCount=3" to wideConfig(terminalCount = 3),
        )

        fun wideConfig(terminalCount: Int) = GeneratorConfig(
            depthRange = 3..5,
            sourceCount = 3,
            vocabulary = CoreOperators.Ids.ALL,
            elementDomainSize = 6,
            scriptLength = 40,
            addRemoveRatio = 0.6,
            unobservedRemoveRatio = 0.25,
            terminalCount = terminalCount,
        ).validated()
    }
}
