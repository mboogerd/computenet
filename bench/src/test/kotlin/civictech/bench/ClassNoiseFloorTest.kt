package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Every branch of the per-class noise-floor machinery (`computenet-cm4w`).
 *
 * **Deliberately untagged**, so it runs on every `:bench:test` — the same reason
 * `IterationLengthCriterionTest` (computenet-bzwx) is untagged. A resolution rule that
 * only executes when someone runs a sweep is auditable but not tested: nothing in the six
 * required checks would catch a refactor that inverted the fallback, and the live table
 * being empty means the class-floor branch would otherwise never execute at all. The
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
        observed: Double = 0.03,
        runs: Int = CLASS_FLOOR_MIN_RUNS,
        hostState: String = QUIESCED_HOST_STATE,
    ): ClassNoiseFloor = ClassNoiseFloor(
        benchmarkClass = benchmarkClass,
        observedMaxRelativeDispersion = observed,
        runs = runs,
        derivedOn = "2026-09-01",
        harnessCommitSha = "deadbeef",
        hostState = hostState,
        jmhConfig = "mode=Throughput forks=2 warmup=5 iters=10",
    )

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

    @Test
    fun `no per-class floor has been derived yet — every class falls back today`() {
        CLASS_NOISE_FLOOR_DERIVATIONS.isEmpty() shouldBe true
        CLASS_NOISE_FLOOR_TABLE.isEmpty() shouldBe true
        noiseFloorFor("OperatorThroughputBenchmark") shouldBe NOISE_FLOOR
        noiseFloorFor("FanOutScalingBenchmark") shouldBe NOISE_FLOOR
        hasClassFloor("OperatorThroughputBenchmark") shouldBe false
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
                observedMaxRelativeDispersion = 0.01,
                runs = 3,
                derivedOn = "",
                harnessCommitSha = "abc",
                hostState = QUIESCED_HOST_STATE,
                jmhConfig = "c",
            )
        }
        shouldThrow<IllegalArgumentException> {
            ClassNoiseFloor(
                benchmarkClass = "X",
                observedMaxRelativeDispersion = 0.01,
                runs = 3,
                derivedOn = "2026-09-01",
                harnessCommitSha = "abc",
                hostState = QUIESCED_HOST_STATE,
                jmhConfig = " ",
            )
        }
    }

    // ---- The table ---------------------------------------------------------------------

    @Test
    fun `floorTable indexes derivations by class`() {
        val table = floorTable(
            listOf(
                derivation(benchmarkClass = "A", observed = 0.03),
                derivation(benchmarkClass = "B", observed = 0.075),
            )
        )
        table shouldBe mapOf("A" to 0.06, "B" to 0.15)
    }

    @Test
    fun `floorTable refuses two derivations naming one class`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            floorTable(
                listOf(
                    derivation(benchmarkClass = "A", observed = 0.03),
                    derivation(benchmarkClass = "A", observed = 0.09),
                )
            )
        }
        thrown.message!! shouldContain "'A' x 2"
    }

    // ---- Resolution: the class floor, and the fallback ----------------------------------

    @Test
    fun `a class with a derived floor resolves to it, not to the global bound`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        noiseFloorFor("Hosted", floors) shouldBe 0.06
        hasClassFloor("Hosted", floors) shouldBe true
    }

    @Test
    fun `a class with no derived floor falls back to the global bound`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        noiseFloorFor("Unlisted", floors) shouldBe NOISE_FLOOR
        hasClassFloor("Unlisted", floors) shouldBe false
    }

    @Test
    fun `a null or blank class is the fallback case, not an error`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        noiseFloorFor(null, floors) shouldBe NOISE_FLOOR
        noiseFloorFor("", floors) shouldBe NOISE_FLOOR
        noiseFloorFor("   ", floors) shouldBe NOISE_FLOOR
        hasClassFloor(null, floors) shouldBe false
        hasClassFloor("", floors) shouldBe false
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
    fun `where the class floor and the global bound disagree, the class floor decides`() {
        val looser = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        val tighter = floorTable(listOf(derivation(benchmarkClass = "Quiet", observed = 0.001)))

        // Looser class floor (0.06): a row at 0.03 is above the global 0.005 and under its
        // own class's floor. Global bound says Unreportable, class floor says Reportable.
        val dispersed = rowAt(0.03)
        classify(dispersed) shouldBe Reportability.Unreportable
        classify(dispersed, "Hosted", looser) shouldBe Reportability.Reportable

        // Tighter class floor (0.002): a row at 0.004 is under the global 0.005 and above
        // its own class's floor. The verdicts swap sides.
        val tight = rowAt(0.004)
        classify(tight) shouldBe Reportability.Reportable
        classify(tight, "Quiet", tighter) shouldBe Reportability.Unreportable
    }

    @Test
    fun `hasClassFloor is about provenance, not about the number coinciding with NOISE_FLOOR`() {
        // 2 x 0.0025 = 0.005, exactly NOISE_FLOOR — yet this class HAS a floor of its own,
        // and a message calling it a fallback would misstate where the bound came from.
        val floors = floorTable(listOf(derivation(benchmarkClass = "Coincident", observed = 0.0025)))
        noiseFloorFor("Coincident", floors) shouldBe NOISE_FLOOR
        hasClassFloor("Coincident", floors) shouldBe true
        describeFloor("Coincident", floors) shouldContain "Coincident class floor"
        describeFloor("Elsewhere", floors) shouldContain "NOISE_FLOOR"
    }

    // ---- The shared arithmetic ---------------------------------------------------------

    @Test
    fun `classifyAgainst compares the MAGNITUDE, so a negative value cannot walk around it`() {
        // relativeDispersion == -0.5; "-0.5 > floor" is false, which would pass it.
        val negative = result(value = -100.0, dispersion = 50.0)
        classifyAgainst(negative, NOISE_FLOOR) shouldBe Reportability.Unreportable
        classify(negative, "Anything") shouldBe Reportability.Unreportable
    }

    @Test
    fun `classifyAgainst refuses a non-finite magnitude explicitly`() {
        // value == 0.0 and dispersion == 0.0 make the ratio NaN, and "NaN > floor" is false.
        val nan = result(value = 0.0, dispersion = 0.0)
        classifyAgainst(nan, NOISE_FLOOR) shouldBe Reportability.Unreportable
        classify(nan, "Anything") shouldBe Reportability.Unreportable
    }

    @Test
    fun `a row exactly AT its floor is Reportable, strictly above is not`() {
        val floors = floorTable(listOf(derivation(benchmarkClass = "Hosted", observed = 0.03)))
        classify(rowAt(0.06), "Hosted", floors) shouldBe Reportability.Reportable
        classify(rowAt(0.0600001), "Hosted", floors) shouldBe Reportability.Unreportable
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
     * observation, the pre-fixed margin, the derived floor, the run count, the host state,
     * and both halves of the "what it does / does not establish" pair — and that the floor
     * it prints is the record's computed one rather than a second, hand-entered copy.
     */
    @Test
    fun `renderDerivation states the observation, the margin, the floor and its limits`() {
        val d = derivation(benchmarkClass = "OperatorThroughputBenchmark", observed = 0.03)
        val text = renderDerivation(d)

        text shouldContain "## 2026-09-01 — per-class noise floor for `OperatorThroughputBenchmark`"
        text shouldContain "host state quiesced"
        text shouldContain "3 sequential repeat runs"
        text shouldContain "the class's own annotation configuration"
        text shouldContain "max observed relative dispersion"
        text shouldContain "0.03"
        text shouldContain "margin, fixed before the runs (CLASS_FLOOR_MARGIN) | 2.0"
        text shouldContain "derived floor = margin x observed, rounded up to three decimals | 0.06"
        text shouldContain "Derivation: forward."
        text shouldContain "What it does NOT establish"
        text shouldContain "another benchmark class"
    }

    @Test
    fun `the rendered floor is the record's computed floor, so the two cannot drift`() {
        val d = derivation(observed = 0.0765)
        d.floor shouldBe 0.153
        renderDerivation(d) shouldContain "three decimals | 0.153"
    }
}
