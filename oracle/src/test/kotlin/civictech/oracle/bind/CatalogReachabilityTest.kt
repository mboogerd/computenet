package civictech.oracle.bind

import civictech.cell.graph.CellFactory
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.gen.GraphGenerator
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ModelState
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The **reachable-shape closure** of [OperatorCatalog]'s registrations, pinned by equality —
 * which registered entries a shape-typed generation can actually emit, and which are dead
 * weight in the vocabulary (computenet-6xhh, split out of computenet-4ru.16).
 *
 * ## What "reachable" means here
 *
 * The arity-0 entries are the roots: a generated case starts from sources, so their output
 * shapes are available by definition. From there the closure is a fixpoint under
 * [GraphGenerator.satisfiedBy] — the generator's **own** shape-satisfiability rule, which
 * `GraphGenerator.Builder.fillPorts` calls as its precondition. That shared call is the point:
 * a reimplementation of the rule here would pass this test forever while the generator drifted
 * away from it.
 *
 * An entry is reachable when its `ShapeRule.inputs` are all in that closure.
 *
 * ## The closure is an upper bound, and the pin is read accordingly
 *
 * [GraphGenerator.satisfiedBy] states a *necessary* condition, not a sufficient one — the
 * generator additionally needs a **distinct** node per port, and while the frontier is still
 * converging `Builder.attach` admits only shape-preserving operators (computenet-b9x7). So an
 * entry this test calls unreachable is genuinely unemittable — no sequence of registrations
 * currently in the catalog produces the shape it wants — while one it calls reachable is
 * *not* thereby proven to appear in any particular sweep. The load-bearing half of the pin is
 * the unreachable set; the reachable set is pinned alongside it so that an entry silently
 * *leaving* the emittable vocabulary fails a test too.
 *
 * The pin does not model `Builder.chooseRootShape`'s restriction that all sources of one case
 * share a shape. Under today's registrations that costs nothing — the union of the per-root
 * closures equals the global one, because `presenceCount` bridges the set family to the map
 * family — but a future registration could separate them, in which case the global closure is
 * the looser of the two and still an upper bound.
 *
 * ## Why the unreachable set is empty, and what would refill it
 *
 * It was not always. Every entry in [pairShapedConsumers] wants `SetOf(Tuple(2))` on at least
 * one port, and until computenet-4ru.16 **no registered entry produced that shape without
 * already consuming it** — the set family is `SetOf(Scalar)`, the map family is
 * `MapOf(Scalar, Scalar)`, and the three pair-set joins that do emit pairs are the same three
 * that need them. Nothing bootstrapped a pair-shaped stream from an arity-0 source, so the
 * whole pair family — eleven of the twenty-eight registrations — was cut off from the roots.
 * That was computenet-4ru.16's finding; `keyBy`, the `SetOf(Scalar)` -> `SetOf(Tuple(2))`
 * relabelling registered in [CoreOperators], is the bootstrap that closed it.
 *
 * So the pin today is that **every** registered entry is reachable. The eleven are kept named
 * in [pairShapedConsumers] rather than deleted, and
 * `unregistering the pair-shaped bootstrap re-blocks exactly the eleven entries it unblocked`
 * below removes `keyBy` from the catalog and checks that precisely those eleven fall back out
 * of the closure — which is what keeps this file a computation over the registrations rather
 * than a list of names that happens to match one.
 *
 * A reachable set that shrinks is the regression this file exists to catch: it means an entry
 * has silently left the emittable vocabulary. Update the pins deliberately, having read what
 * moved.
 */
class CatalogReachabilityTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    // --- the pins ---------------------------------------------------------

    /**
     * Every entry a shape-typed generation can emit today: all twenty-eight of them. The
     * pair-shaped eleven joined the set when `keyBy` bootstrapped `SetOf(Tuple(2))` from the
     * set family (computenet-4ru.16); before that they were the pinned unreachable set this
     * file was written to record.
     */
    private val reachablePin: Set<String> = CoreOperators.Ids.ALL.toSet()

    /**
     * The eleven entries that consume `SetOf(Tuple(2))`: the three pair-set joins, the seven
     * `GroupByCell` aggregates, and `groupByGlobal`. All eleven are reachable now; they are
     * named here because they are exactly the set `keyBy` unblocked, and
     * `unregistering the pair-shaped bootstrap re-blocks exactly the eleven entries it unblocked`
     * below re-derives that fact from the catalog rather than trusting this list.
     */
    private val pairShapedConsumers: Set<String> = setOf(
        CoreOperators.Ids.JOIN_SET,
        CoreOperators.Ids.SEMI_JOIN,
        CoreOperators.Ids.ANTI_JOIN,
        CoreOperators.Ids.GROUP_BY_GLOBAL,
    ) + CoreOperators.Ids.GROUP_BY_AGGREGATES

    /**
     * The two registered sources no case can spawn: `counter` and `pnCounter` emit bare
     * [ElementShape.Scalar], and no registered operator consumes a bare scalar on any port, so
     * `Builder.chooseRootShape` — which draws only among source shapes something can consume —
     * can never select them, and `[ORA1-GEN-03]` forbids them standing as terminals themselves.
     *
     * They are nonetheless in [reachablePin]: an arity-0 entry has no ports to fill, so the
     * closure rule calls it reachable unconditionally. The two facts are different questions
     * and are pinned separately rather than folded together.
     *
     * ## computenet-gff7's verdict: DECIDED, and this set is expected to stay non-empty
     *
     * computenet-gff7 offered the fork "register a `Scalar`-consuming operator, or record the
     * decision not to". The decision is **not to**, and the reason is structural rather than
     * one of effort. A scalar edge in this catalog carries `Propagate<CounterDelta>`, and
     * exactly one cell in the whole kernel serves that type on an inlet:
     * `CoalescingCombineCell` (`kernel/src/main/kotlin/civictech/cell/data/op/`,
     * `inlet: Serve<Propagate<CounterDelta>>`) — every other operator inlet under
     * `civictech.cell.data.op` serves `SetDelta` or `MapDelta`. That one cell is excluded by
     * name from the vocabulary by the `[ORA1-HONEST-02]` ledger in
     * `civictech.oracle.model.MapCellModel`'s file KDoc: its observable is a wave-completion
     * fold the script vocabulary cannot name, so a batch model of it would assume the very
     * completeness condition it cannot check.
     *
     * Unlike the pair-shaped hole — repairable inside the catalog, because `keyBy` reuses an
     * already-registered cell and model and adds only a function — this one has no in-catalog
     * repair: it would take a NEW kernel cell existing only to give the oracle something to
     * link a counter into. So [sourcesNoOperatorConsumes] is a **standing, documented** limit,
     * and the two tests below pin both halves of it: that the set is exactly these two today,
     * and that a scalar consumer is precisely and solely what is missing.
     */
    private val sourcesNoOperatorConsumes: Set<String> = setOf(
        CoreOperators.Ids.COUNTER,
        CoreOperators.Ids.PN_COUNTER,
    )

    private val whyPinned =
        "The reachable set is the whole catalog because `keyBy` bootstraps SetOf(Tuple(2)) from " +
            "the set family (computenet-4ru.16). An entry appearing in the unreachable set means " +
            "something has silently left the emittable vocabulary — most likely a shape change, " +
            "or `keyBy` being unregistered or reshaped. A new id in neither pin is simply a new " +
            "registration: add it to the pin deliberately, having read which side it lands on."

    @Test
    fun `the reachable and unreachable entry sets are exactly the pinned ones`() {
        val entries = OperatorCatalog.all()
        val reachable = reachableIds(entries)
        val unreachable = entries.map { it.id }.toSet() - reachable

        withClue(whyPinned) {
            reachable shouldBe reachablePin
            unreachable.shouldBeEmpty()
        }
        withClue("the two sets partition the catalog") {
            (reachable + unreachable) shouldBe entries.map { it.id }.toSet()
            (reachable intersect unreachable).shouldBeEmpty()
            reachable.size shouldBe entries.size
        }
    }

    @Test
    fun `exactly one registered entry produces SetOf(Tuple(2)) without consuming it`() {
        val entries = OperatorCatalog.all()
        val pairSet = ElementShape.SetOf(ElementShape.Tuple(2))

        withClue("each pair-shaped consumer wants $pairSet on at least one port") {
            pairShapedConsumers.filterNot { id ->
                pairSet in OperatorCatalog.entry(id)!!.shape.inputs
            }.shouldBeEmpty()
        }
        // The hole computenet-4ru.16 closed was a bootstrap failure, not an absence: `joinSet`,
        // `semiJoin` and `antiJoin` always DID emit SetOf(Tuple(2)) — they each consume it as
        // well, so the shape could only ever be produced by something that already had it. What
        // was missing was an entry producing it WITHOUT consuming it, and `keyBy` is exactly
        // that entry. Asserting "something emits the shape" instead would have been true before
        // the fix too, and would stay true if `keyBy` were reshaped back into a pair-consumer.
        withClue("the bootstrap: entries producing $pairSet without already consuming it") {
            entries.filter { it.shape.output == pairSet && pairSet !in it.shape.inputs }
                .map { it.id } shouldBe listOf(CoreOperators.Ids.KEY_BY)
        }
        withClue("the entries that emit $pairSet are the bootstrap plus the three pair-set joins") {
            entries.filter { it.shape.output == pairSet }.map { it.id }.toSet() shouldBe setOf(
                CoreOperators.Ids.KEY_BY,
                CoreOperators.Ids.JOIN_SET,
                CoreOperators.Ids.SEMI_JOIN,
                CoreOperators.Ids.ANTI_JOIN,
            )
        }
    }

    @Test
    fun `unregistering the pair-shaped bootstrap re-blocks exactly the eleven entries it unblocked`() {
        // The instrument's own check, and the historical record in executable form: the closure
        // is not a constant dressed up as a computation. Dropping the one entry that produces
        // SetOf(Tuple(2)) without consuming it must move all eleven pair-shaped consumers — and
        // nothing else — back across the line, reproducing the catalog computenet-4ru.16 found.
        OperatorCatalog.unregister(CoreOperators.Ids.KEY_BY) shouldBe true
        val entries = OperatorCatalog.all()
        val reachable = reachableIds(entries)
        val unreachable = entries.map { it.id }.toSet() - reachable

        withClue("without `keyBy`, the pair family is cut off from every root again") {
            unreachable shouldBe pairShapedConsumers
            unreachable.size shouldBe 11
            reachable shouldBe reachablePin - pairShapedConsumers - CoreOperators.Ids.KEY_BY
        }
    }

    @Test
    fun `a pair-producing source would keep every entry reachable`() {
        // The other direction: `keyBy` is not privileged as a *unary* entry. Any registration
        // emitting SetOf(Tuple(2)) from nothing keeps the same closure, which is the sense in
        // which [ORA1-API-03]'s seam is about shapes and not about ids.
        val fakeId = "syntheticPairSetSource"
        OperatorCatalog.register(
            id = fakeId,
            shape = ShapeRule.source(ElementShape.SetOf(ElementShape.Tuple(2))),
            kernel = CellFactory { _ -> error("never spawned: this entry exists only for the closure computation") },
            model = NeverRun,
        )
        try {
            val reachable = reachableIds(OperatorCatalog.all())
            reachable shouldBe reachablePin + fakeId
        } finally {
            OperatorCatalog.unregister(fakeId)
        }
    }

    @Test
    fun `two registered sources emit a shape no operator consumes, so no case can spawn them`() {
        val entries = OperatorCatalog.all()
        val consumed = entries.flatMap { it.shape.inputs }.toSet()
        val unconsumableSources = entries
            .filter { it.shape.arity == 0 && it.shape.output !in consumed }
            .map { it.id }
            .toSet()

        withClue(
            "counter/pnCounter emit bare Scalar, which no registered operator consumes, so " +
                "Builder.chooseRootShape can never root a case at them. This is a second, " +
                "independent coverage hole from the SetOf(Tuple(2)) one computenet-4ru.16 " +
                "closed. computenet-gff7 DECIDED not to close it: the only kernel cell that " +
                "serves Propagate<CounterDelta> on an inlet is CoalescingCombineCell, which " +
                "the [ORA1-HONEST-02] ledger excludes by name. A change here is a change to " +
                "that decision — read this file's KDoc before moving the pin.",
        ) {
            unconsumableSources shouldBe sourcesNoOperatorConsumes
        }
    }

    @Test
    fun `a scalar-consuming operator is precisely and solely what would make the two spawnable`() {
        // The counterfactual, computed rather than argued — the same instrument
        // `a pair-producing source would keep every entry reachable` uses for the other hole.
        // It is what makes the recorded decision checkable: the diagnosis "nothing consumes
        // bare Scalar" is only worth writing down if adding a bare-Scalar consumer, and
        // nothing else, removes the symptom.
        OperatorCatalog.register(
            id = SYNTHETIC_SCALAR_FAN_IN,
            shape = COALESCING_COMBINE_SHAPE,
            kernel = CellFactory { _ -> error("never spawned: this entry exists only to test the counterfactual") },
            model = NeverRunOperator,
        )
        try {
            val entries = OperatorCatalog.all()
            val consumed = entries.flatMap { it.shape.inputs }.toSet()
            val unconsumableSources = entries
                .filter { it.shape.arity == 0 && it.shape.output !in consumed }
                .map { it.id }
                .toSet()

            withClue(
                "with a bare-Scalar consumer registered, NO source is left emitting a shape " +
                    "nothing consumes — so the scalar hole is exactly the absence of such an " +
                    "operator, not a second defect elsewhere in the catalog or the generator",
            ) {
                unconsumableSources.shouldBeEmpty()
            }
        } finally {
            OperatorCatalog.unregister(SYNTHETIC_SCALAR_FAN_IN)
        }
    }

    @Test
    fun `with a scalar consumer the generator really does root cases at counter and pnCounter`() {
        // The closure is only an upper bound (this file's KDoc), so the counterfactual above
        // does not by itself establish that a sweep WOULD emit counter-rooted cases. This test
        // draws real topologies from the real `GraphGenerator` and reads their source nodes —
        // the same instrument `PairShapeBootstrapTest` uses for the pair-shaped family, and the
        // evidence for the "if registered" half of computenet-gff7's fork.
        //
        // The synthetic entry's shape is `CoalescingCombineCell`'s own: a two-arm fan-in over
        // Scalar producing Scalar, on one dynamic `inlet`, exactly as the registered fan-in
        // family advertises. So what this measures is what registering THAT cell would buy —
        // which is what makes the decision not to register it a decision about honesty rather
        // than about reachability.
        OperatorCatalog.register(
            id = SYNTHETIC_SCALAR_FAN_IN,
            shape = COALESCING_COMBINE_SHAPE,
            kernel = CellFactory { _ -> error("never spawned: only the topology is read here") },
            model = NeverRunOperator,
        )
        try {
            val config = GeneratorConfig(
                depthRange = 2..4,
                sourceCount = 3,
                // Only the scalar slice: with the whole catalog in scope `chooseRootShape` would
                // draw a root shape at random and most seeds would be set-rooted, which measures
                // the draw rather than the reachability.
                vocabulary = listOf(
                    CoreOperators.Ids.COUNTER,
                    CoreOperators.Ids.PN_COUNTER,
                    SYNTHETIC_SCALAR_FAN_IN,
                ),
                elementDomainSize = 6,
                scriptLength = 40,
                addRemoveRatio = 0.6,
                unobservedRemoveRatio = 0.25,
                terminalCount = 1,
            ).validated()

            val generator = GraphGenerator(config)
            val sourceIds = (0L until SWEEP_SEEDS).flatMap { seed ->
                generator.generate(seed).topology.nodes.filter { it.inputs.isEmpty() }.map { it.catalogId }
            }

            // Printed, not merely asserted, for the reason `PairShapeBootstrapTest` prints its
            // own counts: a margin a reviewer can read beats a bare green tick, and a future
            // change that leaves the slice technically reachable but vanishingly rare shows up
            // as a collapsing number rather than a still-passing test.
            println("scalar-rooted sources over $SWEEP_SEEDS seeds: ${sourceIds.groupingBy { it }.eachCount()}")

            withClue(
                "source catalog ids over $SWEEP_SEEDS generated topologies: " +
                    "${sourceIds.groupingBy { it }.eachCount()}",
            ) {
                sourceIds.toSet() shouldBe setOf(CoreOperators.Ids.COUNTER, CoreOperators.Ids.PN_COUNTER)
            }
        } finally {
            OperatorCatalog.unregister(SYNTHETIC_SCALAR_FAN_IN)
        }
    }

    // --- the closure ------------------------------------------------------

    /**
     * The shapes a generated graph can carry: the arity-0 outputs, closed under
     * [GraphGenerator.satisfiedBy]. Iterates to a fixpoint; terminates because the shape set
     * only grows and is bounded by the registered outputs.
     */
    private fun reachableShapeClosure(entries: List<OperatorCatalog.Entry>): Set<ElementShape> {
        val available = entries.filter { it.shape.arity == 0 }
            .mapTo(LinkedHashSet()) { it.shape.output }
        val operators = entries.filter { it.shape.arity > 0 }
        do {
            var grew = false
            operators.forEach { entry ->
                if (GraphGenerator.satisfiedBy(entry.shape, available) && available.add(entry.shape.output)) {
                    grew = true
                }
            }
        } while (grew)
        return available
    }

    /** Every entry whose ports the closure fills — arity-0 entries included, vacuously. */
    private fun reachableIds(entries: List<OperatorCatalog.Entry>): Set<String> {
        val closure = reachableShapeClosure(entries)
        return entries.filter { GraphGenerator.satisfiedBy(it.shape, closure) }
            .mapTo(LinkedHashSet()) { it.id }
    }

    /** A [SourceModel] that exists only to satisfy paired registration; never evaluated. */
    private object NeverRun : SourceModel {
        override fun evaluate(slice: SourceScript): ModelState =
            error("never evaluated: this model exists only so a synthetic shape can be registered")
    }

    /** An [OperatorModel] that exists only to satisfy paired registration; never evaluated. */
    private object NeverRunOperator : OperatorModel {
        override fun evaluate(inputs: List<ModelState>): ModelState =
            error("never evaluated: this model exists only so a synthetic shape can be registered")
    }

    private companion object {
        /**
         * 200 seeds, the population size `PairShapeBootstrapTest` and `GraphGeneratorTest`'s
         * convergence sweep both draw, so the scalar slice is measured on the same scale the
         * generator's other structural claims are.
         */
        const val SWEEP_SEEDS = 200L

        const val SYNTHETIC_SCALAR_FAN_IN = "syntheticScalarFanIn"

        /**
         * `CoalescingCombineCell`'s advertised shape: a two-arm fan-in over bare `Scalar`
         * producing `Scalar`, on the one dynamic `inlet` the kernel's fan-in cells expose —
         * the same construction `CoreOperators` uses for `union`/`presenceCount`/`quorumSet`.
         * Written here rather than imported because the point is what registering that cell
         * WOULD do; the cell itself stays excluded.
         */
        val COALESCING_COMBINE_SHAPE = ShapeRule(
            inputs = List(2) { ElementShape.Scalar },
            output = ElementShape.Scalar,
            inputPorts = List(2) { "inlet" },
        )
    }
}
