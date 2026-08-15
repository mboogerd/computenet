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
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DespawnStep
import civictech.concord.schema.DisconnectStep
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
import civictech.concord.schema.RestartStep
import civictech.concord.schema.RestoreStep
import civictech.concord.schema.RetransmitStep
import civictech.concord.schema.SnapshotStep
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

    /** A source directly linked into an effect sink, fed k1/k2/k3 — the DUR corpus shape. */
    private val effectScenario = scenario(
        cells = listOf(cell("src", "set-source"), cell("sink", "effect-sink")),
        links = listOf(link("src", "sink")),
        script = listOf(
            apply("src", "add", s("k1")),
            apply("src", "add", s("k2")),
            apply("src", "add", s("k3")),
        ),
    )

    private fun effectCtx(vararg keys: String) = FakeContext(
        FakeDriver(effects = mapOf("sink" to keys.map { Effect(it, s(it)) })),
        effectScenario,
    )

    @Test
    fun `effect-count holds when each key fired exactly N times`() {
        pass(Checks.effectCount(EffectCount(sink = "sink", exactly = 1), effectCtx("k1", "k2", "k3")))
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

    /**
     * computenet-61w: the unkeyed form must see silent effect **loss**, not only
     * double-fires. The sink's log is missing `k3` entirely — an element the
     * script fed to its upstream source that fired zero times. Grouping only the
     * keys the sink *produced* leaves `k3` absent from the grouping, so the
     * pre-fix evaluator passed this vacuously.
     */
    @Test
    fun `effect-count without a key fails when a scripted element never fired`() {
        val r = Checks.effectCount(EffectCount(sink = "sink", exactly = 1), effectCtx("k1", "k2"))
        fail(r)
        (r as CheckResult.Failed).message shouldContain "key=k3"
        r.message shouldContain "observed 0"
    }

    /** Losing *every* effect is the same failure, not the "produced nothing" special case. */
    @Test
    fun `effect-count without a key fails when the sink fired for nothing at all`() {
        val r = Checks.effectCount(EffectCount(sink = "sink", exactly = 1), effectCtx())
        fail(r)
        (r as CheckResult.Failed).message shouldContain "expected 1 but observed 0"
    }

    /** The double-fire direction the pre-fix form already caught is still caught. */
    @Test
    fun `effect-count without a key still fails on a double-fired key`() {
        fail(Checks.effectCount(EffectCount(sink = "sink", exactly = 1), effectCtx("k1", "k2", "k3", "k3")))
    }

    /** `remove` drives no new effect, so it adds no expectation and cancels none. */
    @Test
    fun `effect-count without a key expects a key that was added and later removed`() {
        val scen = scenario(
            cells = listOf(cell("src", "set-source"), cell("sink", "effect-sink")),
            links = listOf(link("src", "sink")),
            script = listOf(apply("src", "add", s("k1")), apply("src", "remove", s("k1"))),
        )
        pass(
            Checks.effectCount(
                EffectCount(sink = "sink", exactly = 1),
                FakeContext(FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1"))))), scen),
            ),
        )
    }

    /**
     * When the expected key set cannot be derived from the scenario, the unkeyed
     * form refuses rather than falling back to the vacuous produced-keys grouping
     * — "the check had nothing to look at" must never read as "the property held"
     * (computenet-61w; same doctrine as [Checks] `nothingObserved`).
     */
    @Test
    fun `effect-count without a key refuses when the scenario names no scripted upstream`() {
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(
                FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1"))))),
                scenario(emptyList(), emptyList()),
            ),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /** `exactly: 0` needs no derivation: the assertion is that the log is empty. */
    @Test
    fun `effect-count without a key and exactly zero asserts an empty effect log`() {
        pass(
            Checks.effectCount(
                EffectCount(sink = "sink", exactly = 0),
                FakeContext(FakeDriver(), scenario(emptyList(), emptyList())),
            ),
        )
        fail(
            Checks.effectCount(
                EffectCount(sink = "sink", exactly = 0),
                FakeContext(
                    FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1"))))),
                    scenario(emptyList(), emptyList()),
                ),
            ),
        )
    }

    /**
     * A *partial* derivation is the vacuous pass again (review of computenet-61w).
     * `srcA` feeds the sink directly, so the derivation resolves; `srcB` feeds it
     * **through** `mid`, so its adds are not keys this derivation can name. Deriving
     * `{k1}` and unioning the log would pass while `k9` fired zero times, so the
     * refusal is stated over the sink's whole upstream cone, not the direct hop.
     */
    @Test
    fun `effect-count without a key refuses when a feed reaches the sink through an intermediate`() {
        val scen = scenario(
            cells = listOf(
                cell("srcA", "set-source"), cell("srcB", "set-source"),
                cell("mid", "set-view"), cell("sink", "effect-sink"),
            ),
            links = listOf(link("srcA", "sink"), link("srcB", "mid"), link("mid", "sink")),
            script = listOf(apply("srcA", "add", s("k1")), apply("srcB", "add", s("k9"))),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1"))))), scen),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * The same partiality reached by topology instead of by graph shape: the
     * `connect` targets an *upstream* rather than the sink, so a rule keyed on the
     * sink alone would let `srcB`'s adds go unnamed (review of computenet-61w).
     */
    @Test
    fun `effect-count without a key refuses when a mid-script connect feeds an upstream`() {
        val scen = scenario(
            cells = listOf(cell("srcA", "set-source"), cell("srcB", "set-source"), cell("sink", "effect-sink")),
            links = listOf(link("srcA", "sink")),
            script = listOf(
                apply("srcA", "add", s("k1")),
                ConnectStep(from = "srcB", to = "srcA"),
                apply("srcB", "add", s("k9")),
            ),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1"))))), scen),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * computenet-61w.1, the **vacuous** half: a diamond. `srcA` is a set-source
     * linked straight into the sink, so the one-hop derivation resolves `{k1}` — but
     * `mid` is *also* a direct upstream, and what it contributes is a
     * *transformation* of `srcA`'s adds that this derivation cannot name. The
     * "apply on an in-cone cell that is not a direct upstream" rule never fires (no
     * apply targets `mid` at all), so a key `mid` should have driven and did not is
     * in neither the derived set nor the log, and passes over. Checking every direct
     * upstream's declared `type:` is what refuses it.
     */
    @Test
    fun `effect-count without a key refuses a diamond whose second upstream transforms the feed`() {
        val scen = scenario(
            cells = listOf(cell("srcA", "set-source"), cell("mid", "set-view"), cell("sink", "effect-sink")),
            links = listOf(link("srcA", "sink"), link("srcA", "mid"), link("mid", "sink")),
            script = listOf(apply("srcA", "add", s("k1"))),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1"))))), scen),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * The over-strict half of the same missing `type:` check (computenet-61w.1): a
     * `map` directly upstream re-keys, so the script's adds are not the keys the
     * sink was fed. The author must get the "cannot be derived" refusal — which
     * tells them to name keys — rather than a per-key "expected 1 but observed 0"
     * that reads like a runtime defect.
     */
    @Test
    fun `effect-count without a key refuses when a direct upstream re-keys the feed`() {
        val scen = scenario(
            cells = listOf(cell("m", "map", fn = "inc"), cell("sink", "effect-sink")),
            links = listOf(link("m", "sink")),
            script = listOf(apply("m", "add", i(1)), apply("m", "add", i(2))),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(FakeDriver(effects = mapOf("sink" to listOf(Effect("2", i(2)), Effect("3", i(3))))), scen),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * The sink's declared type is checked too: only `effect-sink` has the
     * one-effect-per-added-element contract the derivation models.
     */
    @Test
    fun `effect-count without a key refuses when the named sink is not an effect-sink`() {
        val scen = scenario(
            cells = listOf(cell("src", "set-source"), cell("v", "set-view")),
            links = listOf(link("src", "v")),
            script = listOf(apply("src", "add", s("k1"))),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "v", exactly = 1),
            FakeContext(FakeDriver(effects = mapOf("v" to listOf(Effect("k1", s("k1"))))), scen),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * add → remove → re-add drives **two** effects (the element is newly added
     * twice; `EffectSinkCell` fires per delivered add), and the unkeyed form asserts
     * one uniform count for every derived key, so it cannot express "k1 twice, the
     * rest once". computenet-61w.1 permits either refusing or expecting 2; refusing
     * is the choice, because expecting 2 would mean expecting 2 of *every* key.
     */
    @Test
    fun `effect-count without a key refuses an add, remove and re-add of the same element`() {
        val scen = scenario(
            cells = listOf(cell("src", "set-source"), cell("sink", "effect-sink")),
            links = listOf(link("src", "sink")),
            script = listOf(
                apply("src", "add", s("k1")),
                apply("src", "remove", s("k1")),
                apply("src", "add", s("k1")),
            ),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(
                FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1")), Effect("k1", s("k1"))))),
                scen,
            ),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * A second **vacuous** shape, measured for computenet-61w.1: `restore` was not in
     * the refusal list although `restart` was. A restore re-materializes the cell's
     * contents from a blob captured elsewhere, so it can feed the sink an element no
     * apply on a direct upstream ever named — `k1` here comes from `seed`, which is
     * outside the sink's cone and therefore skipped. The derived set is `{k2}`, the
     * lost `k1` is absent from both it and the log, and the check passed. Refusing on
     * a restore anywhere in the cone closes it.
     */
    @Test
    fun `effect-count without a key refuses when a cone cell is restored from a snapshot`() {
        val scen = scenario(
            cells = listOf(cell("seed", "set-source"), cell("src", "set-source"), cell("sink", "effect-sink")),
            links = listOf(link("src", "sink")),
            script = listOf(
                apply("seed", "add", s("k1")),
                apply("src", "add", s("k2")),
                SnapshotStep(on = "seed", alias = "sd"),
                RestoreStep(on = "src", from = "sd"),
            ),
        )
        val r = Checks.effectCount(
            EffectCount(sink = "sink", exactly = 1),
            FakeContext(FakeDriver(effects = mapOf("sink" to listOf(Effect("k2", s("k2"))))), scen),
        )
        fail(r)
        (r as CheckResult.Failed).message shouldContain "cannot be derived"
    }

    /**
     * The asymmetry's other side, kept deliberately: a `snapshot` is a pure read of
     * the cell it names, so it cannot change which elements reached the sink. Two of
     * the six `15-durability` scenarios snapshot a direct upstream mid-script
     * (`DUR-SRCID-02`, `DUR-ATOMIC-01`), and they must keep resolving.
     */
    @Test
    fun `effect-count without a key still resolves across a snapshot of a direct upstream`() {
        val scen = scenario(
            cells = listOf(cell("src", "set-source"), cell("sink", "effect-sink")),
            links = listOf(link("src", "sink")),
            script = listOf(
                apply("src", "add", s("k1")),
                SnapshotStep(on = "src", alias = "ck"),
                apply("src", "add", s("k2")),
            ),
        )
        pass(
            Checks.effectCount(
                EffectCount(sink = "sink", exactly = 1),
                FakeContext(
                    FakeDriver(effects = mapOf("sink" to listOf(Effect("k1", s("k1")), Effect("k2", s("k2"))))),
                    scen,
                ),
            ),
        )
    }

    // --- expectedEffectKeys: the derivation itself (computenet-61w.1) --------

    /**
     * The checks above read the derivation through `effectCount`, which can only
     * report *that* it refused. These read it directly — `expectedEffectKeys` is
     * `internal` for exactly this — because the two rounds of this bug were both a
     * key set that *resolved* when it should not have, and a black-box pass cannot
     * tell "resolved and the log happened to match" from "resolved correctly". Each
     * row states the shape and the set it may claim, `null` being the refusal.
     */
    private fun derived(scen: civictech.concord.schema.Scenario, sink: String = "sink") =
        Checks.expectedEffectKeys(scen, sink)

    private val src = cell("src", "set-source")
    private val jsrc = cell("src", "journal-set-source")
    private val esink = cell("sink", "effect-sink")

    @Test
    fun `expectedEffectKeys resolves the corpus shape, set-source and journal-set-source alike`() {
        // DUR-REPLAY-01's effect arm (set-source) and DUR-SRCID/ATOMIC's (journal),
        // including the mid-script snapshot and the out-of-cone despawn of `ctl`.
        listOf(src, jsrc).forEach { source ->
            val scen = scenario(
                cells = listOf(source, esink, cell("ctl", "journal")),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("src", "add", s("k1")),
                    apply("src", "add", s("k2")),
                    SnapshotStep(on = "src", alias = "ck"),
                    apply("src", "add", s("k3")),
                    DespawnStep(on = "ctl"),
                    apply("src", "add", s("k4")),
                ),
            )
            derived(scen) shouldBe setOf("k1", "k2", "k3", "k4")
        }
    }

    @Test
    fun `expectedEffectKeys refuses every shape whose fed keys it cannot name`() {
        val shapes: List<Pair<String, civictech.concord.schema.Scenario>> = listOf(
            "the diamond: a second direct upstream transforms the feed" to scenario(
                cells = listOf(src, cell("mid", "set-view"), esink),
                links = listOf(link("src", "sink"), link("src", "mid"), link("mid", "sink")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "a direct upstream that re-keys (map)" to scenario(
                cells = listOf(cell("m", "map", fn = "inc"), esink),
                links = listOf(link("m", "sink")),
                script = listOf(apply("m", "add", i(1))),
            ),
            "a direct upstream that drops elements (filter)" to scenario(
                cells = listOf(cell("f", "filter", fn = "even"), esink),
                links = listOf(link("f", "sink")),
                script = listOf(apply("f", "add", i(1))),
            ),
            "a direct upstream that re-announces on recovery (rebaseline-source)" to scenario(
                cells = listOf(cell("rb", "rebaseline-source"), esink),
                links = listOf(link("rb", "sink")),
                script = listOf(apply("rb", "add", s("k1"))),
            ),
            "the named sink is not an effect-sink" to scenario(
                cells = listOf(src, cell("sink", "set-view")),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "the sink is not declared at all" to scenario(
                cells = listOf(src),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "a self-link makes the sink its own upstream" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink"), link("sink", "sink")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "the same feeder is linked in twice, so each add is delivered twice" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink"), link("src", "sink", inlet = "other")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "a direct upstream is itself fed, so its emissions are not its own adds" to scenario(
                cells = listOf(src, cell("up", "set-source"), esink),
                links = listOf(link("src", "sink"), link("up", "src")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "a cycle re-adds the source's own elements to it" to scenario(
                cells = listOf(src, cell("mid", "set-view"), esink),
                links = listOf(link("src", "sink"), link("src", "mid"), link("mid", "src")),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            "the same element added twice fires twice for one key" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1")), apply("src", "add", s("k1"))),
            ),
            "add, remove and re-add fires twice for one key" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("src", "add", s("k1")),
                    apply("src", "remove", s("k1")),
                    apply("src", "add", s("k1")),
                ),
            ),
            "a times multiplier fires once per add" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1"), times = 3)),
            ),
            "an add with no value" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", null)),
            ),
            "an op outside a set-source's vocabulary" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1")), apply("src", "put", s("k2"))),
            ),
            "a restore re-materializes a cone cell from elsewhere" to scenario(
                cells = listOf(cell("seed", "set-source"), src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("seed", "add", s("k1")),
                    apply("src", "add", s("k2")),
                    SnapshotStep(on = "seed", alias = "sd"),
                    RestoreStep(on = "src", from = "sd"),
                ),
            ),
            "a restart re-baselines a cone cell" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1")), RestartStep(on = "src")),
            ),
            "a despawn stops a cone cell's traffic" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1")), DespawnStep(on = "src")),
            ),
            "a mid-script connect into the sink" to scenario(
                cells = listOf(src, cell("srcB", "set-source"), esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1")), ConnectStep(from = "srcB", to = "sink")),
            ),
            "a mid-script disconnect from the sink" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "add", s("k1")), DisconnectStep(from = "src", to = "sink")),
            ),
            "the script fed the sink's upstreams nothing at all" to scenario(
                cells = listOf(src, esink),
                links = listOf(link("src", "sink")),
                script = listOf(apply("src", "remove", s("k1"))),
            ),
            "nothing links into the sink" to scenario(
                cells = listOf(src, esink),
                links = emptyList(),
                script = listOf(apply("src", "add", s("k1"))),
            ),
            // computenet-yh6.1.8 — the retransmit verb injects a delivery straight
            // at an inlet, so it is the one verb besides `apply` that can put an
            // element in front of the sink. Each shape below would leave a key the
            // derived set cannot name, which is the vacuous pass all over again.
            "a retransmit of an element no add names" to scenario(
                cells = listOf(jsrc, esink),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("src", "add", s("k1")),
                    RetransmitStep(on = "sink", source = "src", counter = 9, op = "add", value = s("ghost")),
                ),
            ),
            "a retransmit into an upstream rather than the sink itself" to scenario(
                cells = listOf(jsrc, esink),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("src", "add", s("k1")),
                    RetransmitStep(on = "src", source = "src", counter = 1, op = "add", value = s("k1")),
                ),
            ),
            "a retransmit carrying an op that is not an add" to scenario(
                cells = listOf(jsrc, esink),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("src", "add", s("k1")),
                    RetransmitStep(on = "sink", source = "src", counter = 1, op = "remove", value = s("k1")),
                ),
            ),
            "a retransmit with no value" to scenario(
                cells = listOf(jsrc, esink),
                links = listOf(link("src", "sink")),
                script = listOf(
                    apply("src", "add", s("k1")),
                    RetransmitStep(on = "sink", source = "src", counter = 1, op = "add", value = null),
                ),
            ),
        )
        // Collected rather than fail-fast on purpose: when a refusal rule is removed
        // this names *every* shape that starts resolving again, which is what makes
        // the rules individually mutation-checkable.
        val resolved = shapes.filter { (_, scen) -> derived(scen) != null }.map { (why, _) -> why }
        resolved shouldBe emptyList()
    }

    /**
     * The resolving neighbours of those refusals, so the gate is not passing by
     * refusing everything: a `remove` of an element added earlier, several distinct
     * elements over two direct set-sources, and applies on cells with no path to the
     * sink (which the derivation may ignore, since they cannot feed it).
     */
    @Test
    fun `expectedEffectKeys resolves the shapes it can name`() {
        val addThenRemove = scenario(
            cells = listOf(src, esink),
            links = listOf(link("src", "sink")),
            script = listOf(
                apply("src", "add", s("k1")),
                apply("src", "add", s("k2")),
                apply("src", "remove", s("k1")),
            ),
        )
        derived(addThenRemove) shouldBe setOf("k1", "k2")

        val twoFeeders = scenario(
            cells = listOf(src, cell("srcB", "journal-set-source"), esink),
            links = listOf(link("src", "sink"), link("srcB", "sink")),
            script = listOf(apply("src", "add", s("k1")), apply("srcB", "add", s("k2"))),
        )
        derived(twoFeeders) shouldBe setOf("k1", "k2")

        val disjointSubgraph = scenario(
            cells = listOf(src, esink, cell("other", "set-source"), cell("ov", "set-view")),
            links = listOf(link("src", "sink"), link("other", "ov")),
            script = listOf(
                apply("other", "add", s("x")),
                apply("src", "add", s("k1")),
                RestoreStep(on = "ov", from = "snap"),
                RestartStep(on = "other"),
            ),
        )
        derived(disjointSubgraph) shouldBe setOf("k1")

        // computenet-yh6.1.8, DUR-LIVE-01's own shape: a retransmit re-delivering an
        // element the scripted adds already name leaves the derived set complete —
        // the duplicate either fires nothing (which is what the scenario asserts) or
        // fires a second effect for a key already in the set, which the count reports.
        // Order is irrelevant, so a duplicate scripted BEFORE the add it duplicates
        // resolves too; what it would fire twice is a count question, not a naming one.
        val duplicateOfAnAddedElement = scenario(
            cells = listOf(jsrc, esink, cell("ctl", "journal")),
            links = listOf(link("src", "sink")),
            script = listOf(
                apply("src", "add", s("k1")),
                apply("src", "add", s("k2")),
                DespawnStep(on = "ctl"),
                RetransmitStep(on = "sink", source = "src", counter = 2, op = "add", value = s("k2")),
            ),
        )
        derived(duplicateOfAnAddedElement) shouldBe setOf("k1", "k2")

        val duplicateBeforeItsAdd = scenario(
            cells = listOf(jsrc, esink),
            links = listOf(link("src", "sink")),
            script = listOf(
                RetransmitStep(on = "sink", source = "src", counter = 1, op = "add", value = s("k1")),
                apply("src", "add", s("k1")),
            ),
        )
        derived(duplicateBeforeItsAdd) shouldBe setOf("k1")
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
