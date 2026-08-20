package civictech.oracle.bind

import civictech.cell.graph.CellFactory
import civictech.oracle.gen.GraphGenerator
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ModelState
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
 * ## Why the unreachable set is non-empty, and what changes it
 *
 * Every entry in [blockedOnPairSet] wants `SetOf(Tuple(2))` on at least one port, and **no
 * registered entry produces that shape without already consuming it** — the set family is
 * `SetOf(Scalar)`, the map family is `MapOf(Scalar, Scalar)`, and the three pair-set joins
 * that do emit pairs are the same three that need them. Nothing bootstraps a pair-shaped
 * stream from an arity-0 source, so the whole pair family is cut off from the roots. That is
 * computenet-4ru.16's finding, and closing it (by registering a pair-producing entry) is
 * computenet-4ru.16's work, deliberately not this test's.
 *
 * **When computenet-4ru.16 lands, this test goes red — that is the design.** Update the two
 * pinned sets in the same change, deliberately, having read what moved. A silent widening or
 * narrowing of what the sweep can emit is exactly what this test exists to make impossible.
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
     * Every entry a shape-typed generation can emit today: the five sources, plus the eleven
     * operators whose ports the set/scalar/map closure fills.
     */
    private val reachablePin: Set<String> = setOf(
        CoreOperators.Ids.SET,
        CoreOperators.Ids.KEYED_SET,
        CoreOperators.Ids.MAP,
        CoreOperators.Ids.COUNTER,
        CoreOperators.Ids.PN_COUNTER,
        CoreOperators.Ids.FILTER,
        CoreOperators.Ids.FLAT_MAP_SET,
        CoreOperators.Ids.MAP_SET,
        CoreOperators.Ids.COUNT,
        CoreOperators.Ids.UNION,
        CoreOperators.Ids.PRESENCE_COUNT,
        CoreOperators.Ids.QUORUM_SET,
        CoreOperators.Ids.INTERSECT,
        CoreOperators.Ids.JOIN,
        CoreOperators.Ids.COMBINE_LATEST,
        CoreOperators.Ids.LOOKUP_JOIN,
    )

    /**
     * The eleven entries no generated case can reach: the three pair-set joins, the seven
     * `GroupByCell` aggregates, and `groupByGlobal`. All eleven consume `SetOf(Tuple(2))`,
     * which nothing produces (computenet-4ru.16).
     */
    private val blockedOnPairSet: Set<String> = setOf(
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
     */
    private val sourcesNoOperatorConsumes: Set<String> = setOf(
        CoreOperators.Ids.COUNTER,
        CoreOperators.Ids.PN_COUNTER,
    )

    private val whyBlocked =
        "The unreachable set is non-empty because nothing registered produces SetOf(Tuple(2)); " +
            "see computenet-4ru.16. If that bead's pair-producing bootstrap entry has just " +
            "landed, this failure is expected — update both pinned sets in that same change, " +
            "deliberately. Otherwise a catalog or shape change has silently moved what the " +
            "sweep can emit, which is what this test exists to catch."

    @Test
    fun `the reachable and unreachable entry sets are exactly the pinned ones`() {
        val entries = OperatorCatalog.all()
        val reachable = reachableIds(entries)
        val unreachable = entries.map { it.id }.toSet() - reachable

        withClue(whyBlocked) {
            reachable shouldBe reachablePin
            unreachable shouldBe blockedOnPairSet
        }
        withClue("the two sets partition the catalog") {
            (reachable + unreachable) shouldBe entries.map { it.id }.toSet()
            (reachable intersect unreachable).shouldBeEmpty()
            unreachable.size shouldBe 11
        }
    }

    @Test
    fun `every unreachable entry is blocked on SetOf(Tuple(2)), which nothing bootstraps`() {
        val entries = OperatorCatalog.all()
        val pairSet = ElementShape.SetOf(ElementShape.Tuple(2))

        withClue("each unreachable entry wants $pairSet on at least one port") {
            blockedOnPairSet.filterNot { id ->
                pairSet in OperatorCatalog.entry(id)!!.shape.inputs
            }.shouldBeEmpty()
        }
        // The mechanism is a bootstrap failure, not an absence: `joinSet`, `semiJoin` and
        // `antiJoin` DO emit SetOf(Tuple(2)) — they each consume it as well, so the shape can
        // only ever be produced by something that already has it. What is missing is an entry
        // that produces it WITHOUT consuming it; computenet-4ru.16 option (a) is exactly that
        // entry. Asserting "nothing emits the shape" instead would be false today (it failed
        // on `joinSet` when this test was written) and would keep passing after a pair-shaped
        // operator that still could not bootstrap was registered.
        withClue("no registered entry produces $pairSet without already consuming it") {
            entries.filter { it.shape.output == pairSet && pairSet !in it.shape.inputs }
                .map { it.id }
                .shouldBeEmpty()
        }
        withClue("the entries that do emit $pairSet are exactly the three pair-set joins") {
            entries.filter { it.shape.output == pairSet }.map { it.id }.toSet() shouldBe setOf(
                CoreOperators.Ids.JOIN_SET,
                CoreOperators.Ids.SEMI_JOIN,
                CoreOperators.Ids.ANTI_JOIN,
            )
        }
    }

    @Test
    fun `a pair-producing source would make exactly the blocked entries reachable`() {
        // The instrument's own check: the closure is not a constant dressed up as a
        // computation. Registering one synthetic SetOf(Tuple(2)) source must move all eleven —
        // and nothing else — across the line. This is the shape computenet-4ru.16 option (a)
        // will take, rehearsed here without touching the real catalog.
        val fakeId = "syntheticPairSetSource"
        OperatorCatalog.register(
            id = fakeId,
            shape = ShapeRule.source(ElementShape.SetOf(ElementShape.Tuple(2))),
            kernel = CellFactory { _ -> error("never spawned: this entry exists only for the closure computation") },
            model = NeverRun,
        )
        try {
            val reachable = reachableIds(OperatorCatalog.all())
            reachable shouldBe reachablePin + blockedOnPairSet + fakeId
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
                "independent coverage hole from the SetOf(Tuple(2)) one; it is pinned, not " +
                "fixed, here — a catalog change is out of computenet-6xhh's scope.",
        ) {
            unconsumableSources shouldBe sourcesNoOperatorConsumes
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
}
