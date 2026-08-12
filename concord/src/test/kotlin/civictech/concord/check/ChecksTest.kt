package civictech.concord.check

import civictech.concord.driver.DeadLetter
import civictech.concord.driver.Effect
import civictech.concord.driver.ReadCursor
import civictech.concord.driver.ReadEntry
import civictech.concord.driver.ReadPage
import civictech.concord.driver.WavePlane
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
import civictech.concord.schema.ObservationsWholeWaves
import civictech.concord.schema.PagesEqualView
import civictech.concord.schema.ReplicasConverge
import civictech.concord.schema.ViewsConverge
import civictech.concord.schema.WavePlaneUnchanged
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    // --- observations-whole-waves --------------------------------------------

    @Test
    fun `observations-whole-waves holds when every observed set is a whole prefix`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to listOf(list(), list(s("apple")), list(s("apple"), s("plum"))))),
            setViewScenario,
        )
        pass(Checks.observationsWholeWaves(ObservationsWholeWaves("v", "a"), ctx))
    }

    @Test
    fun `observations-whole-waves fails on a torn fork-join delivery`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to listOf(list(s("plum"))))),
            setViewScenario,
        )
        fail(Checks.observationsWholeWaves(ObservationsWholeWaves("v", "a"), ctx))
    }

    // --- the empty observation log is never a pass (computenet-qaz) ----------
    //
    // All three observations-* checks quantify over the events of a stream, so
    // all three are vacuously true on an empty one. "Nothing was observed" must
    // read as a failure, exactly as it already does for wave-plane-unchanged and
    // pages-equal-view — otherwise any defect that empties a log silently
    // disarms the arbiter (CTL-GF-01 passed on 17 of 20 runs that way).

    @Test
    fun `observations-all-satisfy fails when the view observed nothing at all`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to emptyList())),
            scenario(listOf(cell("v", "value-view")), emptyList()),
        )
        val r = Checks.observationsAllSatisfy(ObservationsAllSatisfy("v", "even"), ctx)
        fail(r)
        (r as CheckResult.Failed).message shouldContain "observations-all-satisfy(v, even)"
        r.message shouldContain "nothing was observed"
        r.message shouldContain "'v'"
    }

    @Test
    fun `observations-monotone fails when the view observed nothing at all`() {
        val ctx = FakeContext(
            FakeDriver(observations = mapOf("v" to emptyList())),
            scenario(listOf(cell("v", "value-view")), emptyList()),
        )
        val r = Checks.observationsMonotone(ObservationsMonotone("v"), ctx)
        fail(r)
        (r as CheckResult.Failed).message shouldContain "observations-monotone(v)"
        r.message shouldContain "nothing was observed"
        r.message shouldContain "'v'"
    }

    @Test
    fun `observations-whole-waves fails when the view observed nothing at all`() {
        val ctx = FakeContext(FakeDriver(observations = mapOf("v" to emptyList())), setViewScenario)
        val r = Checks.observationsWholeWaves(ObservationsWholeWaves("v", "a"), ctx)
        fail(r)
        (r as CheckResult.Failed).message shouldContain "observations-whole-waves(v)"
        r.message shouldContain "nothing was observed"
        r.message shouldContain "'v'"
    }

    @Test
    fun `whole-waves refuses the empty log before it consults the op prefixes`() {
        // Ordering matters: observationsWholeWaves computes the source's prefix
        // folds *before* it reads the log, and that computation has its own
        // early returns. This pins that an empty log still reaches the guard and
        // reports "nothing was observed" rather than a prefix-shaped verdict —
        // and, above all, that no route through this check returns Passed on an
        // empty log.
        val ctx = FakeContext(FakeDriver(observations = mapOf("v" to emptyList())), setViewScenario)
        val r = Checks.observationsWholeWaves(ObservationsWholeWaves("v", "a"), ctx)
        (r as CheckResult.Failed).message shouldContain "nothing was observed"
    }

    @Test
    fun `a view the driver never recorded at all is a failure, not a pass`() {
        // No fixture for 'v' whatsoever. This driver answers an unknown cell with
        // an empty log (the in-process KernelDriver instead throws
        // NoSuchElementException for a cell it never spawned, which is loud on
        // its own). Whichever way a binding renders "absent", the check must not
        // pass: the SPI cannot distinguish "recorded nothing" from "never
        // recorded", so the guard treats both as nothing observed.
        val ctx = FakeContext(FakeDriver(), scenario(listOf(cell("v", "value-view")), emptyList()))
        fail(Checks.observationsAllSatisfy(ObservationsAllSatisfy("v", "even"), ctx))
        fail(Checks.observationsMonotone(ObservationsMonotone("v"), ctx))
        fail(Checks.observationsWholeWaves(ObservationsWholeWaves("v", "a"), ctx))
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

    // --- wave-plane-unchanged / pages-equal-view (V1C-CONCORD) ---------------

    private val boundedScenario = scenario(
        cells = listOf(cell("s", "set-source"), cell("v", "set-view")),
        links = listOf(link("s", "v")),
        script = listOf(apply("s", "add", s("apple")), apply("s", "add", s("plum"))),
    )

    /** A page of key-only (set-shaped) entries, all live unless named in [retracted]. */
    private fun page(
        vararg keys: String,
        next: ReadCursor? = null,
        frontier: String? = "F0",
        retracted: Set<String> = emptySet(),
    ) = ReadPage(
        entries = keys.map { ReadEntry(key = s(it), value = null, present = it !in retracted) },
        next = next,
        frontier = frontier,
    )

    private fun walk(
        vararg pages: ReadPage,
        cell: String = "s",
        limit: Int = 2,
        before: Map<String, Long> = mapOf("outlet#src" to 4L),
        after: Map<String, Long> = mapOf("outlet#src" to 4L),
    ) = ReadWalk(
        cell = cell,
        limit = limit,
        pages = pages.toList(),
        waveBefore = WavePlane(before),
        waveAfter = WavePlane(after),
    )

    private fun boundedCtx(vararg reads: ReadWalk, view: Value = list(s("apple"), s("plum"))) =
        FakeContext(FakeDriver(views = mapOf("v" to view)), boundedScenario, reads.toList())

    @Test
    fun `wave-plane-unchanged holds when a walk moved no wave position`() {
        pass(Checks.wavePlaneUnchanged(WavePlaneUnchanged("s"), boundedCtx(walk(page("apple", next = "c1"), page("plum")))))
    }

    @Test
    fun `wave-plane-unchanged fails when the read advanced a wave position`() {
        val moved = walk(page("apple"), after = mapOf("outlet#src" to 5L))
        fail(Checks.wavePlaneUnchanged(WavePlaneUnchanged("s"), boundedCtx(moved)))
    }

    @Test
    fun `wave-plane-unchanged fails when the scenario performed no read at all`() {
        // The vacuity guard: "nothing was observed" must never read as "the property held".
        fail(Checks.wavePlaneUnchanged(WavePlaneUnchanged("s"), boundedCtx()))
    }

    @Test
    fun `wave-plane-unchanged fails when a recorded walk returned no page`() {
        fail(Checks.wavePlaneUnchanged(WavePlaneUnchanged("s"), boundedCtx(walk())))
    }

    @Test
    fun `wave-plane-unchanged checks every recorded walk, not just the first`() {
        val clean = walk(page("apple"))
        val moved = walk(page("apple"), limit = 4, after = mapOf("outlet#src" to 5L))
        fail(Checks.wavePlaneUnchanged(WavePlaneUnchanged("s"), boundedCtx(clean, moved)))
    }

    @Test
    fun `pages-equal-view holds when the walk unions to the view`() {
        val w = walk(page("apple", next = "c1"), page("plum"))
        pass(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(w)))
    }

    @Test
    fun `pages-equal-view drops entries the cell's own algebra has retracted`() {
        // A tombstoned set element is a real paged entry; the view never held it.
        val w = walk(page("apple", "gone", next = "c1", retracted = setOf("gone")), page("plum"))
        pass(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(w)))
    }

    @Test
    fun `pages-equal-view fails when the walk dropped an entry`() {
        fail(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(walk(page("apple")))))
    }

    @Test
    fun `pages-equal-view fails when one entry is returned twice in one walk`() {
        // The union would still equal the view — set union hides a duplicate — so
        // duplicate detection has to be its own assertion over the page sequence.
        val w = walk(page("apple", next = "c1"), page("apple", "plum"))
        fail(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(w)))
    }

    @Test
    fun `pages-equal-view fails when a page carries no frontier stamp`() {
        val w = walk(page("apple", next = "c1", frontier = null), page("plum"))
        fail(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(w)))
    }

    @Test
    fun `pages-equal-view fails when the walk's frontier stamps disagree`() {
        // 21-PULL-03's antecedent is false: the union is a smeared read, and the
        // check reports that rather than passing on a vacuously false antecedent.
        val w = walk(page("apple", next = "c1"), page("plum", frontier = "F1"))
        fail(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(w)))
    }

    @Test
    fun `pages-equal-view fails when the scenario performed no read at all`() {
        fail(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx()))
    }

    @Test
    fun `pages-equal-view checks every recorded walk of a limit sweep`() {
        val good = walk(page("apple", next = "c1"), page("plum"))
        val short = walk(page("apple"), limit = 1)
        fail(Checks.pagesEqualView(PagesEqualView("s", "v"), boundedCtx(good, short)))
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
            ObservationsWholeWaves("v", "a"),
            ReplicasConverge("none"),
            NoDeadLetters,
            EffectCount(sink = "s", exactly = 1),
            WavePlaneUnchanged("s"),
            PagesEqualView("s", "v"),
        )
        checks.forEach { c ->
            (Checks.evaluate(c, ctx) is CheckResult.NotImplemented) shouldBe false
        }
    }
}
