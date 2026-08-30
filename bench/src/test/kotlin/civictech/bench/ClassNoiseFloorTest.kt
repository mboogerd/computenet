package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Every branch of the per-`@Benchmark`-method noise-floor machinery (`computenet-cm4w`;
 * the fold's grain moved from the class to the method in `computenet-x9e.18`).
 *
 * **Deliberately untagged**, so it runs on every `:bench:test` — the same reason
 * `IterationLengthCriterionTest` (computenet-bzwx) is untagged. A resolution rule that
 * only executes when someone runs a sweep is auditable but not tested: nothing in the six
 * required checks would catch a refactor that inverted the fallback, and the live table
 * being empty means the derived-floor branch would otherwise never execute at all. The
 * resolution functions take their floor table as a defaulted parameter precisely so this
 * suite can drive both branches against synthetic tables today.
 */
class ClassNoiseFloorTest {

    private fun env(): RunEnvironment = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.11+10-LTS",
        heapSettings = "defaults",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 26.6.1",
        jmhMode = "thrpt",
        forkCount = 5,
        warmupIterations = 5,
        measurementIterations = 5,
        harnessCommitSha = "cbea0290",
    )

    private fun result(value: Double, dispersion: Double): BenchResult = BenchResult(
        value = value,
        unit = "ops/s",
        dispersion = dispersion,
        drive = Drive.REAL,
        env = env(),
    )

    /** A row whose relative dispersion is exactly [relative]. */
    private fun rowAt(relative: Double): BenchResult = result(1000.0, 1000.0 * relative)

    private fun derivation(
        benchmarkClass: String = "OperatorThroughputBenchmark",
        benchmarkMethod: String = "m",
        observed: Double = 0.03,
        runs: Int = CLASS_FLOOR_MIN_RUNS,
        hostState: String = QUIESCED_HOST_STATE,
        measuringJvm: String = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS",
        assembly: DerivationAssembly? = null,
    ): ClassNoiseFloor = ClassNoiseFloor(
        benchmarkClass = benchmarkClass,
        benchmarkMethod = benchmarkMethod,
        observedRobustDispersion = observed,
        runs = runs,
        derivedOn = "2026-09-01",
        harnessCommitSha = "deadbeef",
        hostState = hostState,
        jmhConfig = "mode=Throughput forks=2 warmup=5 iters=10",
        measuringJvm = measuringJvm,
        assembly = assembly,
    )

    // ---- classFloorStatistic: the estimator (computenet-3sua) ------------------------

    /**
     * The estimator is a MEDIAN within a row and a MAXIMUM across rows, and this pins both
     * halves at once on a grid where the two answers differ.
     *
     * Row A is a quiet row with one contaminated repeat (`9.0`); row B is reproducibly
     * dispersed. Under the OLD estimator — a plain maximum over every observation — the
     * class's statistic would be 9.0, set by a single observation. Under
     * `classFloorStatistic` it is 0.20: row A's median discards its outlier, and the
     * across-row maximum still picks the genuinely worse row rather than averaging it away.
     */
    @Test
    fun `the statistic is the median within a row and the maximum across rows`() {
        val rowA = listOf(0.01, 0.02, 9.0)
        val rowB = listOf(0.19, 0.20, 0.21)

        classFloorStatistic(listOf(rowA, rowB)) shouldBe 0.20
        // The old estimator, stated explicitly so the difference is the test's subject and
        // not an accident of the fixture.
        (rowA + rowB).max() shouldBe 9.0
    }

    /**
     * Breakdown point, asserted rather than only argued: at three observations per row,
     * ONE contaminated repeat cannot move the statistic at all, and TWO can. That is the
     * property `classFloorStatistic`'s KDoc chooses the median for, and the reason a
     * two-of-three shift is treated as a real property of the row rather than an event.
     */
    @Test
    fun `one contaminated observation per row cannot move the statistic, two can`() {
        val clean = listOf(listOf(0.10, 0.11, 0.12))
        classFloorStatistic(clean) shouldBe 0.11

        classFloorStatistic(listOf(listOf(0.10, 0.11, 50.0))) shouldBe 0.11
        classFloorStatistic(listOf(listOf(0.10, 50.0, 50.0))) shouldBe 50.0
    }

    /**
     * The decomposition claim `ClassFloorDerivation`'s "Decomposition" section rests on:
     * the statistic is invariant to how the rows were partitioned into measuring units,
     * because a row's median uses only that row's observations and the across-row fold is
     * a `max`.
     */
    @Test
    fun `the statistic does not depend on row order or on how rows were partitioned`() {
        val rows = listOf(
            listOf(0.30, 0.05, 0.07),
            listOf(0.12, 0.11, 0.13),
            listOf(0.02, 0.90, 0.03),
        )
        val expected = 0.12
        classFloorStatistic(rows) shouldBe expected
        classFloorStatistic(rows.reversed()) shouldBe expected
        // Two "units", folded separately and combined by the same max the ledger uses.
        maxOf(
            classFloorStatistic(rows.take(1)),
            classFloorStatistic(rows.drop(1)),
        ) shouldBe expected
    }

    /**
     * The even-size median rule is pre-registered, not left to the first caller that hits
     * an even sample: `CLASS_FLOOR_MIN_RUNS` is not fixed forever, and a derivation resting
     * on four observations per row must not get to choose between two defensible medians
     * after seeing which it prefers.
     */
    @Test
    fun `medianOf averages the two middle values at even size`() {
        medianOf(listOf(0.1, 0.2, 0.3)) shouldBe 0.2
        medianOf(listOf(0.1, 0.3)) shouldBe 0.2
        medianOf(listOf(4.0, 1.0, 3.0, 2.0)) shouldBe 2.5
        medianOf(listOf(0.7)) shouldBe 0.7
    }

    /**
     * The refusals. A row carrying no observations has no median, and an empty grid has no
     * statistic; both are refused rather than folded into a neutral element, because a
     * neutral element here would publish a floor derived from nothing.
     */
    @Test
    fun `the statistic refuses an empty grid, an empty row, and a non-finite observation`() {
        shouldThrow<IllegalArgumentException> { classFloorStatistic(emptyList()) }
            .message!! shouldContain "at least one row"

        shouldThrow<IllegalArgumentException> {
            classFloorStatistic(listOf(listOf(0.1, 0.2, 0.3), emptyList()))
        }.message!! shouldContain "at least one observation"

        shouldThrow<IllegalArgumentException> { medianOf(emptyList()) }
            .message!! shouldContain "empty sample"

        shouldThrow<IllegalArgumentException> {
            medianOf(listOf(0.1, Double.NaN, 0.3))
        }.message!! shouldContain "must be finite"
    }

    // ---- The pre-registration itself -------------------------------------------------

    /**
     * The margin is the load-bearing pre-registered number: it was fixed before any
     * per-class measurement existed, and a later session moving it to make a derived floor
     * admit a row it would otherwise refuse is exactly the reverse-engineering the forward
     * discipline exists to prevent. This test does not prove the ordering; it makes the
     * value impossible to change silently.
     */
    @Test
    fun `the class-floor margin is 2x, fixed before any per-class number exists`() {
        CLASS_FLOOR_MARGIN shouldBe 2.0
    }

    /**
     * The row-set decomposition amendment (`computenet-3omz`) is anchored in the same
     * object as the procedure it amends.
     *
     * A constant, not prose, for the same reason [ClassFloorDerivation.PROCEDURE_OWNER]
     * is one: the amendment's whole standing rests on having been committed BEFORE any
     * number derived under it exists, and a KDoc paragraph alone leaves nothing a diff of
     * this file's tests can point at. What it pins is that step 1 no longer reads
     * "three **sequential** executions" as the only admissible shape, and that the thing
     * decomposed is scheduling — never a fork count, an iteration count, a threshold or
     * [CLASS_FLOOR_MARGIN], all of which this suite pins unchanged above and below.
     */
    @Test
    fun `the procedure carries the row-set decomposition amendment, anchored to its work item`() {
        ClassFloorDerivation.PROCEDURE_OWNER shouldBe "computenet-cm4w"
        ClassFloorDerivation.DECOMPOSITION_OWNER shouldBe "computenet-3omz"
        // The amendment changes scheduling only: a row is still measured exactly as many
        // times as an undecomposed derivation measured it.
        CLASS_FLOOR_OBSERVATIONS_PER_ROW shouldBe CLASS_FLOOR_MIN_RUNS
        CLASS_FLOOR_MIN_RUNS shouldBe 3
    }

    /**
     * The live table, pinned entry by entry (`computenet-ahn0`, re-grained by
     * `computenet-x9e.18`). This replaces the
     * "nothing is derived yet" tripwire the machinery landed with: that assertion was
     * true only while [CLASS_NOISE_FLOOR_DERIVATIONS] was empty, and it went red by
     * design the moment a real derivation landed. What has to stay pinned is not the
     * emptiness but the *resolution* — which BENCHMARK METHODS carry a floor of their own,
     * what that floor is, and that everything else still falls back — so a later change
     * that populated, dropped or altered an entry cannot pass unnoticed.
     *
     * Every floor is asserted as the arithmetic of its record, never as a hand-typed
     * literal: `2.0 x 0.19864889236475775 = 0.3972977847…`, rounded UP to 0.398.
     *
     * **The table is keyed by (class, `@Benchmark` method) since 2026-08-30.** Its eleven
     * entries were RE-DERIVED under that grain by recomputation from the same retained
     * observations the superseded per-class entries came from — no benchmark was
     * re-measured. The confirmation that each class's input set was the right one is
     * arithmetic and is asserted below in the shape it was actually performed: the LARGEST
     * per-method statistic within a class reconstructs that class's retired per-class
     * statistic exactly, because the method partition is a partition and the across-row
     * fold is a `max`. A wrong input set — such as `computenet-3omz.4`'s ledger exercise
     * sitting under `floor-derivations/CellFootprintBenchmark/`, which folds to 0.485 —
     * would not satisfy it.
     *
     * `CellFootprintBenchmark` declares one `@Benchmark`, `realSnapshot`, so the grain
     * change moved nothing on it: still 0.19864889236475775 / 0.398 from
     * `computenet-7v7m`'s three toolchain-JDK-21 runs, as `computenet-3sua` re-derived it.
     * `computenet-ahn0`'s 0.2961501149112133 / 0.593 was measured under JBR 25.0.2 and is
     * superseded, not retained as a second entry — so the pin on the measuring JVM below
     * is part of what this test protects.
     *
     * `FanOutScalingBenchmark` is the class the grain change was noticed on, and the pins
     * on its six methods are the ones nobody should be tempted to tidy. Its retired single
     * floor of 0.953 now belongs to `simBatchFixedState` alone, while the
     * `Mode.AverageTime` fan-out curve the class exists to draw — `sim` and `real` — sits
     * at 0.148 and 0.107, roughly nine times tighter than the bound those rows used to be
     * measured against.
     *
     * The fallback half names `SmokeBenchmark` — the real, live instance, from which
     * `NOISE_FLOOR` was derived and which the procedure deliberately does not name — plus
     * an UNDERIVED METHOD of a derived class, which is a fallback case the per-class grain
     * could not express at all.
     */
    @Test
    fun `eleven per-method floors are derived, and everything else still falls back`() {
        CLASS_NOISE_FLOOR_DERIVATIONS.map { it.key.describe() } shouldBe
            listOf(
                "CellFootprintBenchmark.realSnapshot",
                "BoundedReadBenchmark.realDirect",
                "BoundedReadBenchmark.realHostedSnapshotOf",
                "FanOutScalingBenchmark.real",
                "FanOutScalingBenchmark.realBatchFixedState",
                "FanOutScalingBenchmark.realFixedState",
                "FanOutScalingBenchmark.sim",
                "FanOutScalingBenchmark.simBatchFixedState",
                "FanOutScalingBenchmark.simFixedState",
                "OperatorThroughputBenchmark.real",
                "OperatorThroughputBenchmark.sim",
            )
        CLASS_NOISE_FLOOR_TABLE.keys shouldBe
            CLASS_NOISE_FLOOR_DERIVATIONS.map { it.key }.toSet()

        // Everything true of EVERY derived entry, asserted over the whole list rather
        // than over one of them: a rule stated about the table must not quietly become a
        // rule about whichever entry the test happened to name.
        CLASS_NOISE_FLOOR_DERIVATIONS.forEach { derived ->
            derived.runs shouldBe CLASS_FLOOR_MIN_RUNS
            derived.hostState shouldBe QUIESCED_HOST_STATE
            derived.floor shouldBe
                roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * derived.observedRobustDispersion)
            // The measuring JVM is pinned as JDK 21, the module's declared toolchain
            // major. This is the assertion `computenet-ahn0` had no way to make: its
            // three runs were JBR 25.0.2, and neither the record nor the rendered block
            // could say so.
            derived.measuringJvm shouldContain "21.0.5"
            derived.measuringJvm shouldContain "Amazon Corretto"
            hasClassFloor(derived.benchmarkClass, derived.benchmarkMethod) shouldBe true
            noiseFloorFor(derived.benchmarkClass, derived.benchmarkMethod) shouldBe derived.floor
        }

        // --- The eleven statistics and floors, per method.
        val expected = mapOf(
            FloorKey("CellFootprintBenchmark", "realSnapshot") to
                (0.19864889236475775 to 0.398),
            FloorKey("BoundedReadBenchmark", "realDirect") to
                (0.02552534622609911 to 0.052),
            FloorKey("BoundedReadBenchmark", "realHostedSnapshotOf") to
                (0.028527147482145923 to 0.058),
            FloorKey("FanOutScalingBenchmark", "real") to
                (0.05344856341208027 to 0.107),
            FloorKey("FanOutScalingBenchmark", "realBatchFixedState") to
                (0.3465268573449362 to 0.694),
            FloorKey("FanOutScalingBenchmark", "realFixedState") to
                (0.23553487816595828 to 0.472),
            FloorKey("FanOutScalingBenchmark", "sim") to
                (0.07360411934818362 to 0.148),
            FloorKey("FanOutScalingBenchmark", "simBatchFixedState") to
                (0.4762179191123049 to 0.953),
            FloorKey("FanOutScalingBenchmark", "simFixedState") to
                (0.3082968565114873 to 0.617),
            FloorKey("OperatorThroughputBenchmark", "real") to
                (0.04671765550789494 to 0.094),
            FloorKey("OperatorThroughputBenchmark", "sim") to
                (0.05106599919551368 to 0.103),
        )
        CLASS_NOISE_FLOOR_DERIVATIONS.forEach { derived ->
            val (statistic, floor) = expected.getValue(derived.key)
            derived.observedRobustDispersion shouldBe statistic
            noiseFloorFor(derived.benchmarkClass, derived.benchmarkMethod) shouldBe floor
            // Every one of these floors is an order of magnitude or more above the global
            // bound, which is exactly the "distinguishes nothing" failure the derived
            // floors exist to fix.
            noiseFloorFor(derived.benchmarkClass, derived.benchmarkMethod) shouldNotBe NOISE_FLOOR
        }

        // --- The re-derivation's own confirmation, asserted rather than described.
        //
        // A method partition of a class's rows cannot move a `max`-across-rows fold, so
        // the largest per-method statistic within a class IS that class's retired
        // per-class statistic. Each of these four numbers is the one the superseded entry
        // published, which is how the input set behind each class was confirmed to be the
        // one its published entry came from — and, for CellFootprintBenchmark, how the
        // `floor-derivations/CellFootprintBenchmark/` trap (which folds to 0.485) was
        // shown NOT to be the set used.
        mapOf(
            "CellFootprintBenchmark" to 0.19864889236475775,
            "BoundedReadBenchmark" to 0.028527147482145923,
            "FanOutScalingBenchmark" to 0.4762179191123049,
            "OperatorThroughputBenchmark" to 0.05106599919551368,
        ).forEach { (benchmarkClass, retiredStatistic) ->
            CLASS_NOISE_FLOOR_DERIVATIONS
                .filter { it.benchmarkClass == benchmarkClass }
                .maxOf { it.observedRobustDispersion } shouldBe retiredStatistic
        }

        // --- The finer grain, stated as the property it was adopted for.
        //
        // FanOutScalingBenchmark's `Mode.AverageTime` sub-family and its
        // `@OperationsPerInvocation` batch sub-family no longer share one floor. This is
        // the shape `computenet-x9e.18`'s acceptance names: an AverageTime sub-family
        // whose statistics sit at or under 0.074 alongside a simBatchFixedState row near
        // 0.476.
        val fanOut = CLASS_NOISE_FLOOR_DERIVATIONS.filter {
            it.benchmarkClass == "FanOutScalingBenchmark"
        }
        fanOut.size shouldBe 6
        val averageTime = fanOut.filter { it.benchmarkMethod in setOf("sim", "real") }
        averageTime.forEach { it.observedRobustDispersion shouldBeLessThanOrEqual 0.074 }
        val batch = fanOut.single { it.benchmarkMethod == "simBatchFixedState" }
        batch.observedRobustDispersion shouldBe 0.4762179191123049
        averageTime.forEach { it.floor shouldNotBe batch.floor }
        // And the tightening is real, not cosmetic: every AverageTime row is measured
        // against a bound at least six times tighter than the retired class floor.
        averageTime.forEach { (batch.floor / it.floor) shouldBeGreaterThan 6.0 }

        // The fallback path is NOT dead now that every class the procedure names is
        // derived: something outside the table still resolves globally, which is what
        // keeps `hasClassFloor` a statement about provenance rather than about membership
        // of a list that happens to be complete today.
        hasClassFloor("SmokeBenchmark", "baseline") shouldBe false
        noiseFloorFor("SmokeBenchmark", "baseline") shouldBe NOISE_FLOOR
        // A DERIVED class, and a method of it that carries no floor — a fallback case the
        // retired per-class key could not express at all.
        hasClassFloor("FanOutScalingBenchmark", "notAMethodOfThisClass") shouldBe false
        noiseFloorFor("FanOutScalingBenchmark", "notAMethodOfThisClass") shouldBe NOISE_FLOOR
    }

    // ---- The derivation record ---------------------------------------------------------

    @Test
    fun `the floor is computed from the observation and the margin, never stored`() {
        // 2 x 0.03 = 0.06 exactly.
        derivation(observed = 0.03).floor shouldBe 0.06
    }

    @Test
    fun `the floor rounds UP to three decimals, as NOISE_FLOOR's own derivation did`() {
        // The global derivation: 2 x 0.0024683 = 0.0049366, recorded as 0.005.
        derivation(observed = 0.0024683).floor shouldBe 0.005
        // Rounding DOWN here would publish a bound the runs do not support.
        derivation(observed = 0.01005).floor shouldBe 0.021
    }

    @Test
    fun `roundUpToThreeDecimals leaves an already-exact value alone`() {
        roundUpToThreeDecimals(0.005) shouldBe 0.005
        roundUpToThreeDecimals(0.0) shouldBe 0.0
    }

    @Test
    fun `roundUpToThreeDecimals refuses a negative or non-finite value`() {
        shouldThrow<IllegalArgumentException> { roundUpToThreeDecimals(-0.001) }
        shouldThrow<IllegalArgumentException> { roundUpToThreeDecimals(Double.NaN) }
    }

    @Test
    fun `a derivation on a shared host cannot be represented at all`() {
        val thrown = shouldThrow<IllegalArgumentException> { derivation(hostState = "shared") }
        thrown.message!! shouldContain "measuring the interference"
    }

    @Test
    fun `a derivation resting on fewer than three sequential runs is refused`() {
        shouldThrow<IllegalArgumentException> { derivation(runs = 2) }
        derivation(runs = 3).runs shouldBe 3
    }

    @Test
    fun `a zero or negative observed dispersion is refused, not treated as a perfect run`() {
        shouldThrow<IllegalArgumentException> { derivation(observed = 0.0) }
        shouldThrow<IllegalArgumentException> { derivation(observed = -0.01) }
        shouldThrow<IllegalArgumentException> { derivation(observed = Double.NaN) }
    }

    @Test
    fun `blank provenance fields are refused`() {
        shouldThrow<IllegalArgumentException> { derivation(benchmarkClass = "  ") }
        shouldThrow<IllegalArgumentException> {
            ClassNoiseFloor(
                benchmarkClass = "X",
                benchmarkMethod = "m",
                observedRobustDispersion = 0.01,
                runs = 3,
                derivedOn = "",
                harnessCommitSha = "abc",
                hostState = QUIESCED_HOST_STATE,
                jmhConfig = "c",
                measuringJvm = "JDK 21.0.5",
            )
        }
        shouldThrow<IllegalArgumentException> {
            ClassNoiseFloor(
                benchmarkClass = "X",
                benchmarkMethod = "m",
                observedRobustDispersion = 0.01,
                runs = 3,
                derivedOn = "2026-09-01",
                harnessCommitSha = "abc",
                hostState = QUIESCED_HOST_STATE,
                jmhConfig = " ",
                measuringJvm = "JDK 21.0.5",
            )
        }
    }

    /**
     * A derivation that cannot name the JVM it measured under is refused outright, rather
     * than rendered as a block with a silent gap where the runtime should be. That gap is
     * the whole mechanism of `computenet-7v7m`: `computenet-ahn0`'s floor was derived
     * under JBR 25.0.2 against a toolchain of 21, and nothing in the record or the
     * published entry could have said so.
     */
    @Test
    fun `a derivation that cannot name its measuring JVM is refused`() {
        val thrown = shouldThrow<IllegalArgumentException> { derivation(measuringJvm = "  ") }
        thrown.message!! shouldContain "which JVM it measured under"
    }

    // ---- The table ---------------------------------------------------------------------

    @Test
    fun `floorTable indexes derivations by class AND @Benchmark method`() {
        val table = floorTable(
            listOf(
                derivation(benchmarkClass = "A", benchmarkMethod = "one", observed = 0.03),
                derivation(benchmarkClass = "B", benchmarkMethod = "one", observed = 0.075),
            )
        )
        table shouldBe mapOf(
            FloorKey("A", "one") to 0.06,
            FloorKey("B", "one") to 0.15,
        )
    }

    /**
     * Two methods of ONE class are ordinary since `computenet-x9e.18` — that is what a
     * class of several `@Benchmark` methods looks like — and they keep separate floors
     * rather than collapsing into one entry.
     */
    @Test
    fun `floorTable admits two methods of one class and keeps their floors apart`() {
        val table = floorTable(
            listOf(
                derivation(benchmarkClass = "A", benchmarkMethod = "quiet", observed = 0.03),
                derivation(benchmarkClass = "A", benchmarkMethod = "noisy", observed = 0.4),
            )
        )
        table shouldBe mapOf(
            FloorKey("A", "quiet") to 0.06,
            FloorKey("A", "noisy") to 0.8,
        )
    }

    @Test
    fun `floorTable refuses two derivations naming one class AND method`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            floorTable(
                listOf(
                    derivation(benchmarkClass = "A", benchmarkMethod = "one", observed = 0.03),
                    derivation(benchmarkClass = "A", benchmarkMethod = "one", observed = 0.09),
                )
            )
        }
        thrown.message!! shouldContain "'A.one' x 2"
    }

    @Test
    fun `a record cannot be built without naming its @Benchmark method`() {
        val thrown = shouldThrow<IllegalArgumentException> { derivation(benchmarkMethod = "  ") }
        thrown.message!! shouldContain "benchmarkMethod must not be blank"
    }

    // ---- Resolution: the class floor, and the fallback ----------------------------------

    @Test
    fun `a method with a derived floor resolves to it, not to the global bound`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        noiseFloorFor("Hosted", "m", floors) shouldBe 0.06
        hasClassFloor("Hosted", "m", floors) shouldBe true
    }

    @Test
    fun `a method with no derived floor falls back to the global bound`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        noiseFloorFor("Unlisted", "m", floors) shouldBe NOISE_FLOOR
        hasClassFloor("Unlisted", "m", floors) shouldBe false
        // A DERIVED class, but a method of it that carries no floor: the class half of
        // the key matching is not enough, which is the whole content of the finer grain.
        noiseFloorFor("Hosted", "unmeasured", floors) shouldBe NOISE_FLOOR
        hasClassFloor("Hosted", "unmeasured", floors) shouldBe false
    }

    /**
     * A caller that knows only the class gets the FALLBACK, never a summary of the
     * class's floors (`computenet-x9e.18`).
     *
     * This is the refusal that keeps the grain change real. A class of several methods
     * holds several floors, and answering a class-only caller with the largest of them —
     * or with any of them — would silently restore the retired per-class bound at exactly
     * the call sites the change was made for.
     */
    @Test
    fun `a null or blank class OR method is the fallback case, not an error`() {
        val floors = floorTable(
            listOf(
                derivation(benchmarkClass = "Hosted", benchmarkMethod = "quiet", observed = 0.03),
                derivation(benchmarkClass = "Hosted", benchmarkMethod = "noisy", observed = 0.4),
            )
        )
        noiseFloorFor(null, "quiet", floors) shouldBe NOISE_FLOOR
        noiseFloorFor("", "quiet", floors) shouldBe NOISE_FLOOR
        noiseFloorFor("   ", "quiet", floors) shouldBe NOISE_FLOOR
        // The class is derived and carries two floors; naming no method resolves to
        // neither of them and to no fold of them.
        noiseFloorFor("Hosted", null, floors) shouldBe NOISE_FLOOR
        noiseFloorFor("Hosted", "", floors) shouldBe NOISE_FLOOR
        noiseFloorFor("Hosted", "  ", floors) shouldBe NOISE_FLOOR
        noiseFloorFor("Hosted", null, floors) shouldNotBe 0.8
        hasClassFloor(null, "quiet", floors) shouldBe false
        hasClassFloor("", "quiet", floors) shouldBe false
        hasClassFloor("Hosted", null, floors) shouldBe false
        hasClassFloor("Hosted", "", floors) shouldBe false
    }

    /**
     * The boundary the two bounds disagree on, in BOTH directions.
     *
     * A class floor is not required to be looser than the global one — a benchmark quieter
     * than `SmokeBenchmark` would derive a tighter floor — and a resolution that quietly
     * took `max(classFloor, NOISE_FLOOR)` would be wrong in exactly that case, while
     * looking correct on every hosted-graph class. The class floor WINS, both ways.
     */
    @Test
    fun `where the derived floor and the global bound disagree, the derived floor decides`() {
        val looser = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        val tighter = floorTable(listOf(derivation(benchmarkClass = "Quiet", observed = 0.001)))

        // Looser class floor (0.06): a row at 0.03 is above the global 0.005 and under its
        // own class's floor. Global bound says Unreportable, class floor says Reportable.
        val dispersed = rowAt(0.03)
        classify(dispersed) shouldBe Reportability.Unreportable
        classify(dispersed, "Hosted", "m", looser) shouldBe Reportability.Reportable

        // Tighter class floor (0.002): a row at 0.004 is under the global 0.005 and above
        // its own class's floor. The verdicts swap sides.
        val tight = rowAt(0.004)
        classify(tight) shouldBe Reportability.Reportable
        classify(tight, "Quiet", "m", tighter) shouldBe Reportability.Unreportable
    }

    @Test
    fun `hasClassFloor is about provenance, not about the number coinciding with NOISE_FLOOR`() {
        // 2 x 0.0025 = 0.005, exactly NOISE_FLOOR — yet this class HAS a floor of its own,
        // and a message calling it a fallback would misstate where the bound came from.
        val floors = floorTable(listOf(derivation(benchmarkClass = "Coincident", observed = 0.0025)))
        noiseFloorFor("Coincident", "m", floors) shouldBe NOISE_FLOOR
        hasClassFloor("Coincident", "m", floors) shouldBe true
        describeFloor("Coincident", "m", floors) shouldContain "Coincident.m method floor"
        describeFloor("Elsewhere", "m", floors) shouldContain "NOISE_FLOOR"
    }

    /**
     * The rendered note names `Class.method`, because the class alone does not identify
     * the bound any more: `FanOutScalingBenchmark.sim` and
     * `FanOutScalingBenchmark.simBatchFixedState` sit at 0.148 and 0.953.
     */
    @Test
    fun `describeFloor names the method, so two floors of one class cannot be confused`() {
        val floors = floorTable(
            listOf(
                derivation(benchmarkClass = "Fan", benchmarkMethod = "sim", observed = 0.03),
                derivation(benchmarkClass = "Fan", benchmarkMethod = "batch", observed = 0.4),
            )
        )
        describeFloor("Fan", "sim", floors) shouldContain "Fan.sim method floor 0.06"
        describeFloor("Fan", "batch", floors) shouldContain "Fan.batch method floor 0.8"
    }

    // ---- The shared arithmetic ---------------------------------------------------------

    @Test
    fun `classifyAgainst compares the MAGNITUDE, so a negative value cannot walk around it`() {
        // relativeDispersion == -0.5; "-0.5 > floor" is false, which would pass it.
        val negative = result(value = -100.0, dispersion = 50.0)
        classifyAgainst(negative, NOISE_FLOOR) shouldBe Reportability.Unreportable
        classify(negative, "Anything", "m") shouldBe Reportability.Unreportable
    }

    @Test
    fun `classifyAgainst refuses a non-finite magnitude explicitly`() {
        // value == 0.0 and dispersion == 0.0 make the ratio NaN, and "NaN > floor" is false.
        val nan = result(value = 0.0, dispersion = 0.0)
        classifyAgainst(nan, NOISE_FLOOR) shouldBe Reportability.Unreportable
        classify(nan, "Anything", "m") shouldBe Reportability.Unreportable
    }

    @Test
    fun `a row exactly AT its floor is Reportable, strictly above is not`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        classify(rowAt(0.06), "Hosted", "m", floors) shouldBe Reportability.Reportable
        classify(rowAt(0.0600001), "Hosted", "m", floors) shouldBe Reportability.Unreportable
    }

    @Test
    fun `classifyAgainst refuses a floor that is not a positive finite number`() {
        shouldThrow<IllegalArgumentException> { classifyAgainst(rowAt(0.01), 0.0) }
        shouldThrow<IllegalArgumentException> { classifyAgainst(rowAt(0.01), -0.005) }
        shouldThrow<IllegalArgumentException> { classifyAgainst(rowAt(0.01), Double.NaN) }
    }

    @Test
    fun `the single-argument classify is unchanged — still the global bound`() {
        classify(rowAt(NOISE_FLOOR)) shouldBe Reportability.Reportable
        classify(rowAt(NOISE_FLOOR + 0.001)) shouldBe Reportability.Unreportable
    }

    // ---- The findings-side format ------------------------------------------------------

    /**
     * The published block is pinned NOW, before any derivation exists, so the entry cannot
     * be composed to suit its numbers later. What is checked is that the block states the
     * observation, the pre-fixed margin, the derived floor, the observation count, the host state,
     * and both halves of the "what it does / does not establish" pair — and that the floor
     * it prints is the record's computed one rather than a second, hand-entered copy.
     */
    @Test
    fun `renderDerivation states the observation, the margin, the floor and its limits`() {
        val d = derivation(benchmarkClass = "OperatorThroughputBenchmark", observed = 0.03)
        val text = renderDerivation(d)

        text shouldContain
            "## 2026-09-01 — per-method noise floor for `OperatorThroughputBenchmark.m`"
        text shouldContain "host state quiesced"
        // This asserted "3 sequential repeat runs" until computenet-71hu. The fixture
        // states no assembly, and the block no longer claims one on its behalf; the three
        // assembly cases each have their own test below.
        text shouldContain "3 observations of every row"
        text shouldContain "this @Benchmark method's own annotation configuration"
        // The block NAMES the estimator, both in the table row and in its own paragraph:
        // a findings table that carries one statistic has to say which one, and a reader
        // must not have to open the source to find out (`computenet-3sua`).
        text shouldContain
            "statistic (max over this METHOD's rows of the per-row MEDIAN relative dispersion)"
        text shouldContain "0.03"
        text shouldContain "margin, fixed before the runs (CLASS_FLOOR_MARGIN) | 2.0"
        text shouldContain "derived floor = margin x statistic, rounded up to three decimals | 0.06"
        text shouldContain "Estimator: `classFloorStatistic`"
        text shouldContain "Derivation: forward."
        // The limits clause states what a median does NOT bound, because the previous
        // wording ("rows ... stayed at or under X") became false the moment the estimator
        // stopped being a maximum over every observation.
        text shouldContain "a median does not bound the sample it is drawn from"
        text shouldContain "What it does NOT establish"
        text shouldContain "another benchmark class or another `@Benchmark` method of this one"
        // The limits clause names the SIBLING-METHOD case explicitly: since
        // `computenet-x9e.18` two methods of one class carry separately derived floors,
        // and a reader who took one entry as covering its whole class would be wrong by
        // an order of magnitude on `FanOutScalingBenchmark`.
        text shouldContain "sibling method of the same class"
    }

    /**
     * The rendered block names the measuring JVM, in its own line and in the limits
     * clause. This is the machinery half of `computenet-7v7m`: the first derivation of
     * `CellFootprintBenchmark` was measured under JBR 25.0.2 against a declared toolchain
     * of 21, and it shipped invisibly *because the rendered block had nowhere to say
     * which runtime produced the number* — the evidence existed only in a run log that
     * nothing obliged anyone to keep. A block that states its JVM makes the next
     * wrong-JDK derivation legible on the page.
     */
    @Test
    fun `renderDerivation names the JVM the runs measured under`() {
        val text = renderDerivation(
            derivation(measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS"),
        )

        text shouldContain "Measured under: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS"
        // And in the limits clause, so a reader who takes only the caveat sentence still
        // learns the floor is a claim about ONE runtime.
        text shouldContain "under a JVM other than JDK 21.0.5"

        // A different JVM renders differently — the field is printed, not a constant.
        renderDerivation(derivation(measuringJvm = "JDK 25.0.2 (JBR)")) shouldContain
            "Measured under: JDK 25.0.2 (JBR)"
    }

    // -----------------------------------------------------------------------------------
    // How the observations were gathered — the sentence, not the number (computenet-71hu).
    // -----------------------------------------------------------------------------------

    @Test
    fun `whole-class runs render as sequential repeat runs`() {
        val text = renderDerivation(
            derivation(assembly = DerivationAssembly.WholeClassRuns(runs = CLASS_FLOOR_MIN_RUNS)),
        )

        text shouldContain "3 sequential repeat runs"
        text shouldContain "over all rows of all 3 runs"
    }

    @Test
    fun `a unit-assembled derivation is never called sequential repeat runs`() {
        val text = renderDerivation(
            derivation(assembly = DerivationAssembly.UnitAssembled(units = 9)),
        )

        text shouldNotContain "sequential repeat runs"
        text shouldContain "3 observations of every row, assembled from 9 measuring units " +
            "in 9 separate processes"
        text shouldContain "over all rows, 3 observations each"
    }

    @Test
    fun `an unstated assembly claims only what is certainly true`() {
        val text = renderDerivation(derivation(assembly = null))

        // The absent value must not assert a method. A construction site that forgets the
        // field says "3 observations of every row" — true under both shapes — rather than
        // inheriting the sequential-runs claim this vocabulary was added to stop.
        text shouldNotContain "sequential repeat runs"
        text shouldNotContain "measuring units"
        text shouldContain "3 observations of every row"
    }

    @Test
    fun `the assembly changes no number the block publishes`() {
        val numbers = { text: String ->
            Regex("[0-9]+\\.[0-9]+").findAll(text).map { it.value }.toList()
        }
        val unstated = renderDerivation(derivation(assembly = null))

        numbers(renderDerivation(derivation(assembly = DerivationAssembly.UnitAssembled(9)))) shouldBe
            numbers(unstated)
        numbers(renderDerivation(derivation(assembly = DerivationAssembly.WholeClassRuns(3)))) shouldBe
            numbers(unstated)
    }

    @Test
    fun `a whole-class-runs assembly disagreeing with the observation count is refused`() {
        val refusal = shouldThrow<IllegalArgumentException> {
            derivation(runs = 4, assembly = DerivationAssembly.WholeClassRuns(runs = 3))
        }
        refusal.message!! shouldContain "cannot produce 4 observations of every row"
    }

    @Test
    fun `the rendered floor is the record's computed floor, so the two cannot drift`() {
        val d = derivation(observed = 0.0765)
        d.floor shouldBe 0.153
        renderDerivation(d) shouldContain "three decimals | 0.153"
    }
}
