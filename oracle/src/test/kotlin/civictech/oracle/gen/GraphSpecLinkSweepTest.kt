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
        val generator = GraphGenerator(sweepConfig())
        var applied = 0

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
            applied++
        }

        withClue("the sweep must actually have applied its whole batch") {
            applied shouldBe SWEEP_SEEDS.toInt()
        }
    }

    private companion object {
        /** The acceptance criterion's floor is 50 specs; this sweep runs that many. */
        const val SWEEP_SEEDS = 50L

        /**
         * The set-rooted slice of the core vocabulary — every id a shape-typed walk can actually
         * reach from a registered source. The pair-shaped entries (`joinSet`, `semiJoin`, the
         * `groupBy*` family) consume `SetOf(Tuple(2))`, which no arity-0 entry produces, so no
         * generated case can contain them and naming them here would test nothing.
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
    }
}
