package civictech.concord.check

import civictech.concord.driver.DeadLetter
import civictech.concord.driver.Effect
import civictech.concord.oracle.Fx.apply
import civictech.concord.oracle.Fx.cell
import civictech.concord.oracle.Fx.i
import civictech.concord.oracle.Fx.link
import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.s
import civictech.concord.oracle.Fx.scenario
import civictech.concord.schema.EffectCount
import civictech.concord.schema.FinalView
import civictech.concord.schema.IncrementalEqualsBatch
import civictech.concord.schema.LateJoinEqualsEarly
import civictech.concord.schema.NoDeadLetters
import civictech.concord.schema.ObservationsAllSatisfy
import civictech.concord.schema.ObservationsMonotone
import civictech.concord.schema.ReplicasConverge
import civictech.concord.schema.ViewsConverge
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/** A passing and a failing case for each check evaluator (§1.4). Pure — FakeDriver only. */
class ChecksTest {

    private fun pass(r: CheckResult) = r shouldBe CheckResult.Passed
    private fun fail(r: CheckResult) = r.shouldBeInstanceOf<CheckResult.Failed>()

    private val setViewScenario = scenario(
        cells = listOf(cell("a", "set-source"), cell("v", "set-view")),
        links = listOf(link("a", "v")),
        script = listOf(apply("a", "add", s("apple")), apply("a", "add", s("plum"))),
    )

    // --- final-view ---------------------------------------------------------

    @Test
    fun `final-view holds when the read matches the golden (order-insensitive for a set)`() {
        // Driver returns the set in a different order; set-view compares order-insensitively.
        val ctx = FakeContext(FakeDriver(views = mapOf("v" to list(s("plum"), s("apple")))), setViewScenario)
        pass(Checks.finalView(FinalView("v", list(s("apple"), s("plum"))), ctx))
    }

    @Test
    fun `final-view fails on a wrong golden`() {
        val ctx = FakeContext(FakeDriver(views = mapOf("v" to list(s("apple"), s("plum")))), setViewScenario)
        fail(Checks.finalView(FinalView("v", list(s("apple"))), ctx))
    }

    // --- views-converge -----------------------------------------------------

    @Test
    fun `views-converge holds when all views are equal`() {
        val ctx = FakeContext(
            FakeDriver(views = mapOf("v" to list(s("a")), "w" to list(s("a")))),
            scenario(listOf(cell("v", "set-view"), cell("w", "set-view")), emptyList()),
        )
        pass(Checks.viewsConverge(ViewsConverge(listOf("v", "w")), ctx))
    }

    @Test
    fun `views-converge fails when views differ`() {
        val ctx = FakeContext(
            FakeDriver(views = mapOf("v" to list(s("a")), "w" to list(s("b")))),
            scenario(listOf(cell("v", "set-view"), cell("w", "set-view")), emptyList()),
        )
        fail(Checks.viewsConverge(ViewsConverge(listOf("v", "w")), ctx))
    }

    // --- incremental-equals-batch -------------------------------------------

    @Test
    fun `incremental-equals-batch holds when the driver matches the oracle`() {
        // Oracle folds {apple, plum}; driver returns the same (shuffled).
        val ctx = FakeContext(FakeDriver(views = mapOf("v" to list(s("plum"), s("apple")))), setViewScenario)
        pass(Checks.incrementalEqualsBatch(IncrementalEqualsBatch("v"), ctx))
    }

    @Test
    fun `incremental-equals-batch fails when the driver diverges from the oracle`() {
        val ctx = FakeContext(FakeDriver(views = mapOf("v" to list(s("apple")))), setViewScenario)
        fail(Checks.incrementalEqualsBatch(IncrementalEqualsBatch("v"), ctx))
    }

    // --- late-join-equals-early ---------------------------------------------

    @Test
    fun `late-join-equals-early holds when both views hold equal folds`() {
        val sc = scenario(listOf(cell("early", "set-view"), cell("late", "set-view")), emptyList())
        val ctx = FakeContext(FakeDriver(views = mapOf("early" to list(s("x")), "late" to list(s("x")))), sc)
        pass(Checks.lateJoinEqualsEarly(LateJoinEqualsEarly(), ctx))
    }

    @Test
    fun `late-join-equals-early fails when the late view lags`() {
        val sc = scenario(listOf(cell("early", "set-view"), cell("late", "set-view")), emptyList())
        val ctx = FakeContext(FakeDriver(views = mapOf("early" to list(s("x"), s("y")), "late" to list(s("x")))), sc)
        fail(Checks.lateJoinEqualsEarly(LateJoinEqualsEarly(early = "early", late = "late"), ctx))
    }

    // --- observations-all-satisfy -------------------------------------------

    @Test
    fun `observations-all-satisfy holds when every event passes the predicate`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to listOf(i(2), i(4), i(100)))),
            scenario(listOf(cell("v", "value-view")), emptyList()),
        )
        pass(Checks.observationsAllSatisfy(ObservationsAllSatisfy("v", "even"), ctx))
    }

    @Test
    fun `observations-all-satisfy fails on a single offending event`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to listOf(i(2), i(3), i(4)))),
            scenario(listOf(cell("v", "value-view")), emptyList()),
        )
        fail(Checks.observationsAllSatisfy(ObservationsAllSatisfy("v", "even"), ctx))
    }

    // --- observations-monotone ----------------------------------------------

    @Test
    fun `observations-monotone holds on a non-decreasing stream`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to listOf(i(1), i(1), i(3), i(7)))),
            scenario(listOf(cell("v", "value-view")), emptyList()),
        )
        pass(Checks.observationsMonotone(ObservationsMonotone("v"), ctx))
    }

    @Test
    fun `observations-monotone fails on a regression`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to listOf(i(1), i(3), i(2)))),
            scenario(listOf(cell("v", "value-view")), emptyList()),
        )
        fail(Checks.observationsMonotone(ObservationsMonotone("v"), ctx))
    }

    // --- replicas-converge --------------------------------------------------

    @Test
    fun `replicas-converge holds when replicas of the logical id are equal`() {
        val sc = scenario(
            listOf(
                cell("r1", "set-source").copy(replicaOf = "shared"),
                cell("r2", "set-source").copy(replicaOf = "shared"),
            ),
            emptyList(),
        )
        val ctx = FakeContext(FakeDriver(views = mapOf("r1" to list(s("a")), "r2" to list(s("a")))), sc)
        pass(Checks.replicasConverge(ReplicasConverge("shared"), ctx))
    }

    @Test
    fun `replicas-converge fails when replicas diverge`() {
        val sc = scenario(
            listOf(
                cell("r1", "set-source").copy(replicaOf = "shared"),
                cell("r2", "set-source").copy(replicaOf = "shared"),
            ),
            emptyList(),
        )
        val ctx = FakeContext(FakeDriver(views = mapOf("r1" to list(s("a")), "r2" to list(s("b")))), sc)
        fail(Checks.replicasConverge(ReplicasConverge("shared"), ctx))
    }

    // --- no-dead-letters ----------------------------------------------------

    @Test
    fun `no-dead-letters holds when the dead-letter list is empty`() {
        val ctx = FakeContext(FakeDriver(), scenario(emptyList(), emptyList()))
        pass(Checks.noDeadLetters(ctx))
    }

    @Test
    fun `no-dead-letters fails when a dead letter exists`() {
        val ctx = FakeContext(
            FakeDriver(deadLetters = listOf(DeadLetter(host = "h1", cell = "c", reason = "undeliverable"))),
            scenario(emptyList(), emptyList()),
        )
        fail(Checks.noDeadLetters(ctx))
    }

    // --- effect-count -------------------------------------------------------

    @Test
    fun `effect-count holds when each key fired exactly N times`() {
        val ctx = FakeContext(
            FakeDriver(
                effects = mapOf(
                    "sink" to listOf(
                        Effect("k1", i(1)), Effect("k2", i(2)),
                    ),
                ),
            ),
            scenario(emptyList(), emptyList()),
        )
        pass(Checks.effectCount(EffectCount(sink = "sink", exactly = 1), ctx))
    }

    @Test
    fun `effect-count fails on a duplicated effect for a key`() {
        val ctx = FakeContext(
            FakeDriver(
                effects = mapOf(
                    "sink" to listOf(
                        Effect("k1", i(1)), Effect("k1", i(1)),
                    ),
                ),
            ),
            scenario(emptyList(), emptyList()),
        )
        fail(Checks.effectCount(EffectCount(sink = "sink", key = "k1", exactly = 1), ctx))
    }

    // --- dispatch -----------------------------------------------------------

    @Test
    fun `evaluate dispatches each check to its evaluator`() {
        val ctx = FakeContext(FakeDriver(views = mapOf("v" to list(s("apple"), s("plum")))), setViewScenario)
        val r: CheckResult = Checks.evaluate(FinalView("v", list(s("apple"), s("plum"))), ctx)
        pass(r)
    }

    @Test
    fun `no check evaluator returns NotImplemented`() {
        // W1-B fills every stub; a NotImplemented would mean a regression.
        val ctx = FakeContext(
            FakeDriver(
                views = mapOf("v" to list(s("apple"), s("plum")), "w" to list(s("apple"), s("plum"))),
                observations = mapOf("v" to listOf(i(2))),
                effects = mapOf("s" to listOf(Effect("k", i(1)))),
            ),
            setViewScenario,
        )
        val checks = listOf(
            FinalView("v", list(s("apple"), s("plum"))),
            ViewsConverge(listOf("v", "w")),
            IncrementalEqualsBatch("v"),
            LateJoinEqualsEarly(early = "v", late = "w"),
            ObservationsAllSatisfy("v", "even"),
            ObservationsMonotone("v"),
            ReplicasConverge("none"),
            NoDeadLetters,
            EffectCount(sink = "s", exactly = 1),
        )
        checks.forEach { c ->
            (Checks.evaluate(c, ctx) is CheckResult.NotImplemented) shouldBe false
        }
    }
}
