package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Every refusal and the fold-equivalence property of the partial-derivation ledger
 * (`computenet-3omz`).
 *
 * **No measurement happens here, and none is needed.** The property the decomposition
 * rests on — that a maximum folded over any partition of a row set equals the maximum
 * over the whole set — is arithmetic, not an empirical claim, so it is checked against
 * synthetic rows. What the suite is really pinning is the set of REFUSALS: an incomplete
 * row set, a plan enumerated under a filter, a second measuring JVM, a fourth observation
 * of a row, a half-parsed file. Each of those, allowed through, produces a floor that is
 * wrong in the direction that admits rows it should refuse.
 *
 * Deliberately untagged, like [ClassNoiseFloorTest], so it runs on every `:bench:test`.
 */
class FloorDerivationLedgerTest {

    // -----------------------------------------------------------------------------------
    // Fixtures. A synthetic three-row class, with its own expected-count table so the live
    // tripwire table is never a test fixture — the same reason `noiseFloorFor` takes its
    // floor table as a defaulted parameter.
    // -----------------------------------------------------------------------------------

    private val syntheticClass = "SyntheticBenchmark"

    private val syntheticRows = listOf(
        RowKey.of("alpha", mapOf("scale" to "N1E3")),
        RowKey.of("alpha", mapOf("scale" to "N1E4")),
        RowKey.of("beta", mapOf("scale" to "N1E3")),
    )

    private val syntheticCounts = mapOf(syntheticClass to syntheticRows.size)

    private val jdk21 = "# VM version: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS"
    private val jbr25 = "# VM version: JDK 25.0.2, OpenJDK 64-Bit Server VM, 25.0.2+12-b1073.1"

    private fun plan(
        rows: List<RowKey> = syntheticRows,
        benchmarkClass: String = syntheticClass,
    ): DerivationPlan = DerivationPlan.of(
        benchmarkClass = benchmarkClass,
        rows = rows,
        enumerationProvenance = "java -jar bench-jmh.jar -lp 'civictech\\.bench\\.micro\\.$benchmarkClass'",
        expectedRowCounts = syntheticCounts,
    )

    private fun gate(): GateReading =
        GateReading(oneMinuteLoad = 1.2, cores = 16, attestedThreshold = 4.00)

    /**
     * The harness sha every fixture unit measures at unless a case varies it.
     *
     * Deliberately the same string the render cases pass as `--harness-sha`, so the
     * default path through this suite is the one the mixed-sha refusal has to leave
     * alone: a caller naming the sha the units actually attest.
     */
    private val harnessSha = "abcdef012"

    private fun unit(
        id: String,
        jvm: String = jdk21,
        harnessSha: String? = this.harnessSha,
        timestamp: String = "2026-08-27T09:14:02Z",
    ): UnitAttestation = UnitAttestation(
        unitId = id,
        measuringJvm = jvm,
        gate = gate(),
        timestamp = timestamp,
        harnessSha = harnessSha,
    )

    /**
     * A JMH `-rf csv` file carrying exactly [rows], each at the relative dispersion the
     * pair names. Score is fixed at 1000, so `scoreError` IS the dispersion x 1000 and a
     * test can name the number it expects to see folded.
     */
    private fun csv(
        rows: List<Pair<RowKey, Double>>,
        benchmarkClass: String = syntheticClass,
        paramColumns: List<String> = listOf("scale"),
    ): String = buildString {
        appendLine(
            (listOf("Benchmark", "Mode", "Threads", "Samples", "Score", "Score Error (99.9%)", "Unit") +
                paramColumns.map { "Param: $it" })
                .joinToString(",") { "\"$it\"" }
        )
        rows.forEach { (row, dispersion) ->
            appendLine(
                (listOf(
                    "civictech.bench.micro.$benchmarkClass.${row.method}",
                    "avgt",
                    "1",
                    "5",
                    "1000.0",
                    (1000.0 * dispersion).toString(),
                    "us/op",
                ) + paramColumns.map { row.params[it] ?: "" })
                    .joinToString(",") { "\"$it\"" }
            )
        }
    }

    private fun allRowsAt(dispersion: Double): List<Pair<RowKey, Double>> =
        syntheticRows.map { it to dispersion }

    /** A ledger with every row measured [CLASS_FLOOR_OBSERVATIONS_PER_ROW] times. */
    private fun completeLedger(dir: File, dispersions: List<Double> = listOf(0.01, 0.02, 0.03)):
        FloorDerivationLedger {
        val ledger = FloorDerivationLedger.start(dir, plan())
        dispersions.forEachIndexed { index, dispersion ->
            ledger.ingest(unit("run-${index + 1}"), csv(allRowsAt(dispersion)))
        }
        return ledger
    }

    // -----------------------------------------------------------------------------------
    // The pre-registered row-count tripwire.
    // -----------------------------------------------------------------------------------

    /**
     * The counts, verified against the enums on 2026-08-26. They are asserted here so a
     * `@Benchmark` method or an enum constant added without updating the table fails a
     * test rather than silently widening the universe a completeness check runs over —
     * which is precisely how a floor gets derived from a fraction of a class.
     */
    @Test
    fun `the pre-registered row counts are the ones the classes' own annotations imply`() {
        EXPECTED_PLAN_ROW_COUNTS shouldBe mapOf(
            // 1 method (realSnapshot) x 7 CellFamily x 3 Scale
            "CellFootprintBenchmark" to 21,
            // 2 methods (sim, real) x 18 Subject x 2 Direction
            "OperatorThroughputBenchmark" to 72,
            // 6 methods x 5 FanDegree
            "FanOutScalingBenchmark" to 30,
            // 2 methods (realDirect, realHostedSnapshotOf) x 3 SetScale
            "BoundedReadBenchmark" to 6,
            // SmokeBenchmark is deliberately absent: it relies on JMH's own @Fork/@Warmup/
            // @Measurement defaults, so UnitSizing.estimateRowSeconds can never size a unit
            // for it and `next` can never advance it (computenet-epxt). Pre-registering a
            // count would let `plan` accept a class `next` can never serve.
        )
    }

    /**
     * `computenet-epxt`: `plan` and `next` must agree about which classes the tool
     * serves. `SmokeBenchmark` relies on JMH's own `@Fork`/`@Warmup`/`@Measurement`
     * defaults (no annotations of its own), so [UnitSizing.estimateRowSeconds] can never
     * size a unit for it — `next` would always refuse. Before the fix,
     * `EXPECTED_PLAN_ROW_COUNTS` still pre-registered it, so `plan` accepted a class `next`
     * could never advance. Excluding it from the table makes `plan` refuse it too, for the
     * same reason any other unregistered class is refused.
     */
    @Test
    fun `SmokeBenchmark is excluded from the pre-registered table, so plan refuses it like next would`() {
        EXPECTED_PLAN_ROW_COUNTS shouldNotContainKey "SmokeBenchmark"
        val refusal = shouldThrow<FloorLedgerException> {
            DerivationPlan.of(
                benchmarkClass = "SmokeBenchmark",
                rows = listOf(RowKey.of("baseline", emptyMap())),
                enumerationProvenance = "cmd",
            )
        }
        refusal.message!! shouldContain "no pre-registered row count exists for 'SmokeBenchmark'"
    }

    @Test
    fun `a plan whose enumeration is short of the pre-registered count is refused`() {
        val refusal = shouldThrow<FloorLedgerException> {
            plan(rows = syntheticRows.dropLast(1))
        }
        refusal.message!! shouldContain "has 2 rows, but 3 are pre-registered"
        // The refusal has to say WHY a short enumeration matters, not merely that it is
        // short: this is the vacuous-completeness case.
        refusal.message!! shouldContain "ran under a row filter"
        refusal.message!! shouldContain "maximum over a fraction of the class"
    }

    @Test
    fun `a plan longer than the pre-registered count is refused just as firmly`() {
        val refusal = shouldThrow<FloorLedgerException> {
            plan(rows = syntheticRows + RowKey.of("gamma", mapOf("scale" to "N1E5")))
        }
        refusal.message!! shouldContain "has 4 rows, but 3 are pre-registered"
    }

    @Test
    fun `a class nothing pre-registers has no checkable universe, so its plan is refused`() {
        val refusal = shouldThrow<FloorLedgerException> {
            DerivationPlan.of(
                benchmarkClass = "TypoedBenchmarkk",
                rows = syntheticRows,
                enumerationProvenance = "-lp",
                expectedRowCounts = syntheticCounts,
            )
        }
        refusal.message!! shouldContain "no pre-registered row count exists"
    }

    @Test
    fun `the live table's classes are the module's benchmark classes, and a plan for one is checkable`() {
        // Not a measurement — just proof that the live default table is what a real
        // derivation would be checked against, with no fixture table substituted.
        val rows = (1..21).map { RowKey.of("realSnapshot", mapOf("n" to "$it")) }
        DerivationPlan.of("CellFootprintBenchmark", rows, "-lp").rows.size shouldBe 21
    }

    // -----------------------------------------------------------------------------------
    // Fold equivalence: the arithmetic the whole decomposition rests on.
    // -----------------------------------------------------------------------------------

    /**
     * For arbitrary partitions of a synthetic row set into units, the rendered
     * `observedRobustDispersion` equals the whole-set statistic, bit for bit.
     *
     * The statistic is `classFloorStatistic` (`computenet-3sua`): the median of each row's
     * observations, then the maximum of those medians. Its partition-invariance is a
     * slightly stronger claim than the plain maximum's was — a row's median needs ALL of
     * that row's observations, so it is only well defined once the ledger is complete —
     * and this test drives units that cut across rows and runs both, which is the shape
     * that would break it if the ledger folded per unit rather than per row.
     *
     * The partitions are generated from a FIXED seed, so a failure is reproducible and is
     * not replaced by a friendlier seed.
     */
    @Test
    fun `folding over any unit partition yields the whole-set statistic`(@TempDir dir: File) {
        val random = Random(20260826)
        // One dispersion per (row, run) cell, so the whole-set maximum is known up front.
        repeat(12) { trial ->
            val cells: Map<Pair<RowKey, Int>, Double> = syntheticRows.flatMap { row ->
                (0 until CLASS_FLOOR_OBSERVATIONS_PER_ROW).map { run ->
                    (row to run) to random.nextDouble(0.001, 0.9)
                }
            }.toMap()
            val wholeSetStatistic = classFloorStatistic(
                syntheticRows.map { row ->
                    (0 until CLASS_FLOOR_OBSERVATIONS_PER_ROW).map { cells.getValue(row to it) }
                }
            )

            val trialDir = File(dir, "trial-$trial")
            val ledger = FloorDerivationLedger.start(trialDir, plan())

            // Partition each run's rows into a random number of units. Unit boundaries
            // cut across runs as well as across rows, which is the shape a real
            // decomposition takes: window 1 measures some rows of run 1, window 2 the
            // rest of run 1 plus some of run 2.
            var unitIndex = 0
            (0 until CLASS_FLOOR_OBSERVATIONS_PER_ROW).forEach { run ->
                val shuffled = syntheticRows.shuffled(random)
                var offset = 0
                while (offset < shuffled.size) {
                    val take = random.nextInt(1, shuffled.size - offset + 1)
                    val slice = shuffled.subList(offset, offset + take)
                    ledger.ingest(
                        unit("unit-${unitIndex++}"),
                        csv(slice.map { it to cells.getValue(it to run) }),
                    )
                    offset += take
                }
            }

            val rendered = ledger.render(
                derivedOn = "2026-08-27",
                harnessCommitSha = "abcdef012",
                jmhConfig = "mode=AverageTime forks=1 warmup=3x1s measurement=5x1s",
            )
            // Bit-identical, not approximately equal.
            rendered.observedRobustDispersion shouldBe wholeSetStatistic
            rendered.runs shouldBe CLASS_FLOOR_OBSERVATIONS_PER_ROW
            rendered.hostState shouldBe QUIESCED_HOST_STATE
            rendered.measuringJvm shouldBe jdk21
        }
    }

    @Test
    fun `one unit carrying the whole class three times renders the same floor as many units do`(
        @TempDir dir: File,
    ) {
        val dispersions = listOf(0.011, 0.047, 0.023)

        val wholeDir = File(dir, "whole")
        val whole = completeLedger(wholeDir, dispersions)

        val splitDir = File(dir, "split")
        val split = FloorDerivationLedger.start(splitDir, plan())
        var index = 0
        dispersions.forEach { dispersion ->
            syntheticRows.forEach { row ->
                split.ingest(unit("row-unit-${index++}"), csv(listOf(row to dispersion)))
            }
        }

        val args = Triple("2026-08-27", "abcdef012", "mode=AverageTime forks=1")
        split.render(args.first, args.second, args.third) shouldBe
            whole.render(args.first, args.second, args.third)
        // Every row carries {0.011, 0.047, 0.023}; its median is 0.023, and the across-row
        // maximum of identical medians is that. 0.047 is the row's worst repeat and no
        // longer sets the floor (`computenet-3sua`).
        split.render(args.first, args.second, args.third).floor shouldBe
            roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * 0.023)
    }

    // -----------------------------------------------------------------------------------
    // Completeness — the safety-critical refusal.
    // -----------------------------------------------------------------------------------

    @Test
    fun `an incomplete row set is refused, and the refusal names each row and its count`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        // Every row twice, one row three times: complete-minus-one, twice over.
        ledger.ingest(unit("u1"), csv(allRowsAt(0.01)))
        ledger.ingest(unit("u2"), csv(allRowsAt(0.02)))
        ledger.ingest(unit("u3"), csv(listOf(syntheticRows.first() to 0.03)))

        val refusal = shouldThrow<FloorLedgerException> {
            ledger.render("2026-08-27", "abcdef012", "mode=AverageTime forks=1")
        }
        // Each outstanding row NAMED, with its own count — not "incomplete".
        refusal.message!! shouldContain "alpha[scale=N1E4] = 2/3"
        refusal.message!! shouldContain "beta[scale=N1E3] = 2/3"
        // The row that IS complete is not listed as outstanding.
        refusal.message!!.contains("alpha[scale=N1E3] =") shouldBe false
        // And the direction of the error is stated, because that is why this refuses.
        refusal.message!! shouldContain "can only be SMALLER"
        refusal.message!! shouldContain "too low is the direction"

        ledger.outstanding().keys.map { it.describe() }.sorted() shouldContainExactly listOf(
            "alpha[scale=N1E4]",
            "beta[scale=N1E3]",
        )
        ledger.isComplete() shouldBe false
    }

    @Test
    fun `the refusal disappears exactly at three-of-three, not before`(@TempDir dir: File) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        val last = syntheticRows.last()
        val others = syntheticRows.dropLast(1)

        repeat(CLASS_FLOOR_OBSERVATIONS_PER_ROW) { run ->
            ledger.ingest(unit("others-$run"), csv(others.map { it to 0.01 }))
        }
        repeat(CLASS_FLOOR_OBSERVATIONS_PER_ROW - 1) { run ->
            ledger.ingest(unit("last-$run"), csv(listOf(last to 0.01)))
            ledger.isComplete() shouldBe false
            shouldThrow<FloorLedgerException> {
                ledger.render("2026-08-27", "abcdef012", "cfg")
            }
        }

        ledger.ingest(unit("last-final"), csv(listOf(last to 0.01)))
        ledger.isComplete() shouldBe true
        ledger.render("2026-08-27", "abcdef012", "cfg").observedRobustDispersion shouldBe 0.01
    }

    @Test
    fun `a row never measured at all is reported as zero of three, not omitted`(@TempDir dir: File) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1"), csv(syntheticRows.dropLast(1).map { it to 0.01 }))

        val refusal = shouldThrow<FloorLedgerException> {
            ledger.render("2026-08-27", "abcdef012", "cfg")
        }
        refusal.message!! shouldContain "beta[scale=N1E3] = 0/3"
    }

    // -----------------------------------------------------------------------------------
    // The fourth observation, and rows outside the plan.
    // -----------------------------------------------------------------------------------

    @Test
    fun `an ingest that would take a row past three observations is refused`(@TempDir dir: File) {
        val ledger = completeLedger(dir)
        val refusal = shouldThrow<FloorLedgerException> {
            ledger.ingest(unit("run-4"), csv(allRowsAt(0.9)))
        }
        refusal.message!! shouldContain "past 3 observations"
        refusal.message!! shouldContain "alpha[scale=N1E3] (already 3)"
        // Nothing was taken in: the ledger still renders the pre-refusal statistic. Every
        // row carries {0.01, 0.02, 0.03}, so the per-row median is 0.02 — and the refused
        // fourth observation of 0.9 is absent from it in both senses, having been rejected
        // AND being the kind of single high value the median would have discarded anyway.
        ledger.render("2026-08-27", "abcdef012", "cfg")
            .observedRobustDispersion shouldBe 0.02
    }

    @Test
    fun `the fourth-observation refusal names the rows still outstanding, so the operator can narrow the filter`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        val done = syntheticRows.first()
        val short = syntheticRows.drop(1)
        repeat(CLASS_FLOOR_OBSERVATIONS_PER_ROW) { ledger.ingest(unit("done-$it"), csv(listOf(done to 0.01))) }

        // The operator re-runs the class unfiltered, which would be a fourth observation
        // of the finished row.
        val refusal = shouldThrow<FloorLedgerException> {
            ledger.ingest(unit("wide"), csv(allRowsAt(0.02)))
        }
        short.forEach { refusal.message!! shouldContain it.describe() }
        refusal.message!! shouldContain "Re-run this unit with a filter"
    }

    @Test
    fun `a row the plan does not name is refused rather than accumulated`(@TempDir dir: File) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        val refusal = shouldThrow<FloorLedgerException> {
            ledger.ingest(
                unit("u1"),
                csv(listOf(RowKey.of("gamma", mapOf("scale" to "N1E9")) to 0.01)),
            )
        }
        refusal.message!! shouldContain "gamma[scale=N1E9]"
        refusal.message!! shouldContain "plan does not name"
    }

    @Test
    fun `a results file carrying another class's rows is refused, not silently filtered`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        val refusal = shouldThrow<FloorLedgerException> {
            ledger.ingest(
                unit("u1"),
                csv(allRowsAt(0.01), benchmarkClass = "OtherBenchmark"),
            )
        }
        refusal.message!! shouldContain "OtherBenchmark"
        refusal.message!! shouldContain "benchmark filter was wrong"
    }

    @Test
    fun `re-ingesting a unit id is refused, since a replay would double-count its rows`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1"), csv(allRowsAt(0.01)))
        val refusal = shouldThrow<FloorLedgerException> {
            ledger.ingest(unit("u1"), csv(allRowsAt(0.02)))
        }
        refusal.message!! shouldContain "already been ingested"
    }

    // -----------------------------------------------------------------------------------
    // The single-JVM refusal.
    // -----------------------------------------------------------------------------------

    @Test
    fun `a complete row set spanning two measuring JVMs is refused at render`(@TempDir dir: File) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1", jdk21), csv(allRowsAt(0.01)))
        ledger.ingest(unit("u2", jdk21), csv(allRowsAt(0.02)))
        ledger.ingest(unit("u3", jbr25), csv(allRowsAt(0.03)))

        ledger.isComplete() shouldBe true
        val refusal = shouldThrow<FloorLedgerException> {
            ledger.render("2026-08-27", "abcdef012", "cfg")
        }
        refusal.message!! shouldContain "span 2 measuring JVMs"
        refusal.message!! shouldContain "25.0.2"
        refusal.message!! shouldContain "21.0.5"
        // The refusal cites the defect that actually happened, so a reader cannot dismiss
        // it as defensive programming.
        refusal.message!! shouldContain "computenet-ahn0"
    }

    @Test
    fun `the second JVM is warned about at ingest, while there is still time to re-run it`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1", jdk21), csv(allRowsAt(0.01))) shouldBe emptyList()

        val warnings = ledger.ingest(unit("u2", jbr25), csv(allRowsAt(0.02)))
        warnings.size shouldBe 1
        warnings.single() shouldContain "REFUSED at render time"
        warnings.single() shouldContain "absolute path"
    }

    @Test
    fun `a derivation whose every unit names one JVM renders that JVM verbatim`(@TempDir dir: File) {
        completeLedger(dir).render("2026-08-27", "abcdef012", "cfg").measuringJvm shouldBe jdk21
    }

    // -----------------------------------------------------------------------------------
    // The harness sha — the same refusal, argued from the same premise (`computenet-tdby`).
    //
    // Before this, `render` took a sha from its caller and published it unchecked, and
    // `derive-class-floor.sh` supplied `git rev-parse --short HEAD` taken at RENDER time
    // — the last window's checkout. Measured against the unfixed code: three units
    // timestamped 2026-08-20, -08-23 and -08-26 rendered at exit 0 under
    // `harnessCommitSha = "deadbeef"`, a sha no unit had measured under, with no span
    // anywhere in the block.
    // -----------------------------------------------------------------------------------

    /** A complete ledger whose three units measured at the shas given, in order. */
    private fun ledgerAtShas(dir: File, shas: List<String?>): FloorDerivationLedger {
        val ledger = FloorDerivationLedger.start(dir, plan())
        shas.forEachIndexed { index, sha ->
            ledger.ingest(
                unit("run-${index + 1}", harnessSha = sha),
                csv(allRowsAt(0.01 * (index + 1))),
            )
        }
        return ledger
    }

    @Test
    fun `a row set spanning two harness shas is refused, and the refusal names both`(
        @TempDir dir: File,
    ) {
        val ledger = ledgerAtShas(dir, listOf("aaaa111", "aaaa111", "bbbb222"))

        val refusal = shouldThrow<FloorLedgerException> { ledger.render("2026-08-27", null, "cfg") }
        refusal.message!! shouldContain "span 2 harness shas"
        refusal.message!! shouldContain "aaaa111"
        refusal.message!! shouldContain "bbbb222"
        // The same specificity as the mixed-JVM refusal: which units to re-run, not only
        // that something is wrong.
        refusal.message!! shouldContain "run-3"
        refusal.message!! shouldContain "re-derive"
    }

    @Test
    fun `a supplied harness sha that no unit measured at is refused rather than published`(
        @TempDir dir: File,
    ) {
        val ledger = ledgerAtShas(dir, listOf("aaaa111", "aaaa111", "aaaa111"))

        val refusal = shouldThrow<FloorLedgerException> {
            ledger.render("2026-08-27", "deadbeef", "cfg")
        }
        refusal.message!! shouldContain "deadbeef"
        refusal.message!! shouldContain "aaaa111"
        refusal.message!! shouldContain "LAST window's checkout"
    }

    @Test
    fun `a single-sha derivation publishes the sha its units attest, with no caller needed`(
        @TempDir dir: File,
    ) {
        val ledger = ledgerAtShas(dir, listOf("aaaa111", "aaaa111", "aaaa111"))

        ledger.render("2026-08-27", null, "cfg").harnessCommitSha shouldBe "aaaa111"
        // Agreeing with it is not an error — only disagreeing is.
        ledger.render("2026-08-27", "aaaa111", "cfg").harnessCommitSha shouldBe "aaaa111"
        ledger.renderWarnings() shouldBe emptyList()
    }

    @Test
    fun `the second harness sha is warned about at ingest, while re-running the unit is cheap`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1", harnessSha = "aaaa111"), csv(allRowsAt(0.01))) shouldBe emptyList()

        val warnings = ledger.ingest(unit("u2", harnessSha = "bbbb222"), csv(allRowsAt(0.02)))
        warnings.size shouldBe 1
        warnings.single() shouldContain "REFUSED at render time"
        warnings.single() shouldContain "aaaa111"
        warnings.single() shouldContain "bbbb222"
    }

    @Test
    fun `a blank harness sha cannot be represented at all, so an unrecorded one stays visible`() {
        shouldThrow<IllegalArgumentException> { unit("u1", harnessSha = "") }
            .message!! shouldContain "must be null"
    }

    // -----------------------------------------------------------------------------------
    // Format v1 -> v2: an in-flight derivation must survive the bump.
    // -----------------------------------------------------------------------------------

    /** Rewrites [ledger]'s file as the v1 format wrote it: no seventh `unit` field. */
    private fun downgradeToV1(ledger: FloorDerivationLedger) {
        ledger.file.writeText(
            ledger.file.readLines().joinToString("\n") { line ->
                when {
                    line == "floor-derivation-ledger v2" -> "floor-derivation-ledger v1"
                    line.startsWith("unit ") -> line.substringBeforeLast('|')
                    else -> line
                }
            } + "\n"
        )
    }

    @Test
    fun `a v1 ledger still loads, with its units carrying no harness sha`(@TempDir dir: File) {
        val ledger = completeLedger(dir)
        downgradeToV1(ledger)

        val reloaded = FloorDerivationLedger.load(dir, syntheticCounts)
        reloaded.units.size shouldBe 3
        reloaded.units.all { it.harnessSha == null } shouldBe true
        reloaded.harnessShas() shouldBe emptyList()
        reloaded.unattestedHarnessUnits() shouldBe listOf("run-1", "run-2", "run-3")
        // Everything the v1 file DID record survives the downgrade-and-reload verbatim.
        reloaded.measuringJvms() shouldBe listOf(jdk21)
        reloaded.isComplete() shouldBe true
    }

    @Test
    fun `a v1 ledger's render is refused whether or not the operator supplies a sha`(
        @TempDir dir: File,
    ) {
        val ledger = completeLedger(dir)
        downgradeToV1(ledger)
        val reloaded = FloorDerivationLedger.load(dir, syntheticCounts)

        shouldThrow<FloorLedgerException> { reloaded.render("2026-08-27", null, "cfg") }
            .message!! shouldContain "recorded a harness sha"
        // Supplying one does not help: this is the computenet-8rel fix. A caller-typed
        // --harness-sha for an all-v1 ledger is not attestation — nothing in the ledger
        // corroborates it — so render() refuses outright rather than publish it, naming the
        // remedy.
        val refused = shouldThrow<FloorLedgerException> {
            reloaded.render("2026-08-27", "abcdef012", "cfg")
        }
        refused.message!! shouldContain "recorded a harness sha"
        refused.message!! shouldContain "Re-measure"
        refused.message!! shouldContain "re-append"
    }

    /**
     * `computenet-eo9m` tried a WARNING here — [FloorDerivationLedger.render] published a
     * caller-typed `--harness-sha` for an all-v1 ledger unchecked, and
     * [FloorDerivationLedger.renderWarnings] flagged the publication after the fact.
     * `computenet-8rel` supersedes that: a warning next to a still-published number does not
     * stop the number from being published, and that is exactly how a superseded
     * measurement set (`computenet-3omz.4`'s `CellFootprintBenchmark` ledger, folding to
     * 0.485 against a published 0.398 — `computenet-xppx`) can acquire a current-looking
     * provenance line. `render` now refuses instead (the previous test), so there is no
     * successful render left here for `renderWarnings()` to warn about.
     */
    @Test
    fun `an all-v1 ledger leaves nothing for renderWarnings to warn about, because render refused`(
        @TempDir dir: File,
    ) {
        val ledger = completeLedger(dir)
        downgradeToV1(ledger)
        val reloaded = FloorDerivationLedger.load(dir, syntheticCounts)

        shouldThrow<FloorLedgerException> { reloaded.render("2026-08-27", "deadbeef", "cfg") }

        reloaded.renderWarnings() shouldBe emptyList()
    }

    /**
     * `computenet-wymi`: the ordering hazard the eo9m review found but did not have a
     * ticket for yet. [FloorDerivationLedger.render] resolved the sha and recorded
     * [FloorDerivationLedger] state describing it as published *before* constructing the
     * [ClassNoiseFloor] that actually publishes it — and that constructor's own
     * `require()` checks can still refuse. A render that throws there must leave nothing
     * recorded: no [ClassNoiseFloor] exists, so [FloorDerivationLedger.renderWarnings]
     * must not warn about a sha that was never published.
     *
     * **What this test can and cannot still detect (`computenet-8rel`).** The state wymi
     * guarded — `lastPublishedUnattestedSha`, the only thing [render] ever recorded about
     * its own outcome — is deleted, and the all-`v1` ledger that reached it is now refused
     * before [ClassNoiseFloor] is constructed at all. So the scenario needs an ATTESTED
     * ledger (one whose sha resolves cleanly, leaving `derivedOn` as the only thing left
     * to refuse on), and on that ledger [FloorDerivationLedger.renderWarnings] is empty
     * whether or not [render] ran: with no render-recorded state, wymi's invariant now
     * holds by construction rather than by a guard, and the `renderWarnings()` assertion
     * below is VACUOUS on today's code. It is kept deliberately, as a tripwire: any future
     * reintroduction of state that [render] records about its own outcome must keep this
     * green, and the assertion stops being vacuous the moment such state exists. Do not
     * read it as evidence that an ordering guard is being exercised — there is none left
     * to exercise.
     */
    @Test
    fun `a render refused by the record's own require() leaves nothing for renderWarnings to warn about`(
        @TempDir dir: File,
    ) {
        // A v1 ledger no longer reaches ClassNoiseFloor's own checks at all — render()
        // refuses at resolveHarnessSha first (computenet-8rel) — so this scenario now needs
        // an ATTESTED ledger: one whose sha resolves cleanly, leaving derivedOn as the only
        // thing left to refuse on.
        val ledger = completeLedger(dir)
        val reloaded = FloorDerivationLedger.load(dir, syntheticCounts)

        // A blank derivedOn passes every one of render()'s own checks — the row set is
        // complete, one JVM, and the sha resolves to the units' own attestation — and is
        // refused only inside ClassNoiseFloor's constructor.
        shouldThrow<IllegalArgumentException> { reloaded.render("", null, "cfg") }
            .message shouldContain "derivedOn must not be blank"

        reloaded.renderWarnings() shouldBe emptyList()
    }

    @Test
    fun `a v1 ledger resumed under v2 keeps its old units and says how far the check reaches`(
        @TempDir dir: File,
    ) {
        // Two v1 units, then the format bump, then a third measured under v2.
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("run-1"), csv(allRowsAt(0.01)))
        ledger.ingest(unit("run-2"), csv(allRowsAt(0.02)))
        downgradeToV1(ledger)

        val resumed = FloorDerivationLedger.load(dir, syntheticCounts)
        resumed.ingest(unit("run-3", harnessSha = "cccc333"), csv(allRowsAt(0.03)))

        // The rewritten file is v2 and carries the two old units with an empty sha field.
        resumed.file.readLines().first() shouldBe "floor-derivation-ledger v2"
        FloorDerivationLedger.load(dir, syntheticCounts).unattestedHarnessUnits() shouldBe
            listOf("run-1", "run-2")

        resumed.render("2026-08-27", null, "cfg").harnessCommitSha shouldBe "cccc333"
        val warning = resumed.renderWarnings().single()
        warning shouldContain "2 of 3 unit(s)"
        warning shouldContain "run-1"
        warning shouldContain "checked across 1 unit(s) only"
    }

    @Test
    fun `a v1 unit line in a v2 file is a parse refusal, not a unit with no sha`(
        @TempDir dir: File,
    ) {
        val ledger = completeLedger(dir)
        // v2 header, v1 unit lines: the shape a hand-merge of two ledgers leaves.
        ledger.file.writeText(
            ledger.file.readLines().joinToString("\n") { line ->
                if (line.startsWith("unit ")) line.substringBeforeLast('|') else line
            } + "\n"
        )
        shouldThrow<FloorLedgerException> { FloorDerivationLedger.load(dir, syntheticCounts) }
            .message!! shouldContain "6 fields, expected 7 for a v2 ledger"
    }

    // -----------------------------------------------------------------------------------
    // The gathering window — a single derivedOn date cannot pass for the span.
    // -----------------------------------------------------------------------------------

    @Test
    fun `a single-day and a multi-day set describe their window differently`(@TempDir dir: File) {
        val sameDay = FloorDerivationLedger.start(File(dir, "same"), plan())
        listOf("2026-08-27T09:00:00Z", "2026-08-27T13:30:00Z", "2026-08-27T22:10:00Z")
            .forEachIndexed { index, stamp ->
                sameDay.ingest(unit("u$index", timestamp = stamp), csv(allRowsAt(0.01)))
            }

        val spread = FloorDerivationLedger.start(File(dir, "spread"), plan())
        listOf("2026-08-20T09:00:00Z", "2026-08-23T09:00:00Z", "2026-08-26T09:00:00Z")
            .forEachIndexed { index, stamp ->
                spread.ingest(unit("u$index", timestamp = stamp), csv(allRowsAt(0.01)))
            }

        val one = sameDay.gatheringWindow()!!
        one.calendarDays shouldBe 1
        one.singleDay shouldBe true
        one.describe() shouldContain "ONE UTC day"
        one.describe() shouldContain "2026-08-27T09:00:00Z"
        one.describe() shouldContain "2026-08-27T22:10:00Z"

        val many = spread.gatheringWindow()!!
        many.calendarDays shouldBe 3
        many.singleDay shouldBe false
        many.describe() shouldContain "spread over 3 UTC calendar days"
        many.describe() shouldContain "2026-08-20T09:00:00Z"
        many.describe() shouldContain "2026-08-26T09:00:00Z"
        many.describe() shouldContain "is NOT the span"

        // The two sentences are not the same sentence with different numbers: a reader
        // skimming for "was this one sitting" must not have to compare timestamps.
        one.describe() shouldNotBe many.describe()
    }

    @Test
    fun `an unparseable timestamp reports the span as uncomputable rather than as one day`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1", timestamp = "yesterday afternoon"), csv(allRowsAt(0.01)))

        val window = ledger.gatheringWindow()!!
        window.calendarDays shouldBe null
        window.singleDay shouldBe false
        window.describe() shouldContain "could not be computed"
    }

    @Test
    fun `an empty ledger has no gathering window at all`(@TempDir dir: File) {
        FloorDerivationLedger.start(dir, plan()).gatheringWindow() shouldBe null
    }

    // -----------------------------------------------------------------------------------
    // Persistence.
    // -----------------------------------------------------------------------------------

    @Test
    fun `a ledger reloaded in a fresh instance carries identical state`(@TempDir dir: File) {
        val original = FloorDerivationLedger.start(dir, plan())
        original.ingest(unit("u1"), csv(allRowsAt(0.011)))
        original.ingest(unit("u2"), csv(syntheticRows.take(2).map { it to 0.4711 }))

        val reloaded = FloorDerivationLedger.load(dir, syntheticCounts)
        reloaded.plan shouldBe original.plan
        reloaded.units shouldContainExactly original.units
        reloaded.observations shouldContainExactly original.observations
        reloaded.outstanding() shouldBe original.outstanding()
        reloaded.describeProgress() shouldBe original.describeProgress()
    }

    @Test
    fun `a derivation finished after a reload folds to the same maximum as one that never exited`(
        @TempDir dir: File,
    ) {
        val resumedDir = File(dir, "resumed")
        FloorDerivationLedger.start(resumedDir, plan()).ingest(unit("u1"), csv(allRowsAt(0.31)))
        // Process exit.
        val second = FloorDerivationLedger.load(resumedDir, syntheticCounts)
        second.ingest(unit("u2"), csv(allRowsAt(0.12)))
        // Process exit again.
        val third = FloorDerivationLedger.load(resumedDir, syntheticCounts)
        third.ingest(unit("u3"), csv(allRowsAt(0.07)))

        val straight = completeLedger(File(dir, "straight"), listOf(0.31, 0.12, 0.07))
        third.render("2026-08-27", "abcdef012", "cfg") shouldBe
            straight.render("2026-08-27", "abcdef012", "cfg")
    }

    @Test
    fun `a JVM banner with spaces, commas and separators survives the round trip verbatim`(
        @TempDir dir: File,
    ) {
        val awkward = "# VM version: JDK 21.0.5, OpenJDK 64-Bit Server VM, " +
            "21.0.5+11-LTS | a=b; c\\d"
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1", awkward), csv(allRowsAt(0.01)))
        FloorDerivationLedger.load(dir, syntheticCounts).units.single().measuringJvm shouldBe awkward
    }

    @Test
    fun `a truncated ledger is refused on load, never partially applied`(@TempDir dir: File) {
        val ledger = completeLedger(dir)
        val file = ledger.file
        // Cut the file off MID-LINE — the shape a process killed mid-write would leave if
        // the write were not atomic. (It is: `persist` writes a temporary and renames. The
        // refusal still has to exist, because the file lives outside the repository where
        // anything may happen to it.)
        val text = file.readText()
        file.writeText(text.dropLast(11))

        val refusal = shouldThrow<FloorLedgerException> {
            FloorDerivationLedger.load(dir, syntheticCounts)
        }
        // The point: it does NOT load as an incomplete-but-valid ledger that would then
        // silently want four more observations.
        refusal.message!! shouldContain "malformed"
    }

    @Test
    fun `a ledger whose observation lines were dropped wholesale is still refused, not silently short`(
        @TempDir dir: File,
    ) {
        val ledger = completeLedger(dir)
        val file = ledger.file
        // Delete whole `obs` lines: syntactically a perfectly valid ledger, which is why
        // this case needs the plan's own row count to catch it at RENDER, not at load.
        file.writeText(file.readLines().filterNot { it.startsWith("obs ") }.joinToString("\n") + "\n")

        val reloaded = FloorDerivationLedger.load(dir, syntheticCounts)
        reloaded.isComplete() shouldBe false
        shouldThrow<FloorLedgerException> { reloaded.render("2026-08-27", "abcdef012", "cfg") }
            .message!! shouldContain "= 0/3"
    }

    @Test
    fun `a ledger with an unknown keyword is refused rather than skipped`(@TempDir dir: File) {
        val ledger = completeLedger(dir)
        ledger.file.appendText("mystery something\n")
        shouldThrow<FloorLedgerException> { FloorDerivationLedger.load(dir, syntheticCounts) }
            .message!! shouldContain "unknown keyword 'mystery'"
    }

    @Test
    fun `a ledger with the wrong format marker is refused`(@TempDir dir: File) {
        val ledger = completeLedger(dir)
        val lines = ledger.file.readLines()
        // FIXTURE UPDATED by computenet-tdby: this case used to write `v2`, which was a
        // version no build wrote or read. `v2` is now the version this build writes, so
        // it would be accepted and the case would assert nothing. `v3` is the same shape
        // of wrongness against the same list — a ledger from a NEWER build.
        ledger.file.writeText((listOf("floor-derivation-ledger v3") + lines.drop(1)).joinToString("\n"))
        shouldThrow<FloorLedgerException> { FloorDerivationLedger.load(dir, syntheticCounts) }
            .message!! shouldContain "does not start with"
    }

    @Test
    fun `a ledger carrying an observation from a unit it never attests is refused`(
        @TempDir dir: File,
    ) {
        val ledger = FloorDerivationLedger.start(dir, plan())
        ledger.ingest(unit("u1"), csv(allRowsAt(0.01)))
        ledger.file.writeText(
            ledger.file.readLines().filterNot { it.startsWith("unit ") }.joinToString("\n") + "\n"
        )
        shouldThrow<FloorLedgerException> { FloorDerivationLedger.load(dir, syntheticCounts) }
            .message!! shouldContain "no attestation for"
    }

    @Test
    fun `a ledger written before the class grew is refused on load, not rendered`(
        @TempDir dir: File,
    ) {
        val ledger = completeLedger(dir)
        // The class gained a row since the plan was captured.
        val refusal = shouldThrow<FloorLedgerException> {
            FloorDerivationLedger.load(dir, mapOf(syntheticClass to syntheticRows.size + 1))
        }
        refusal.message!! shouldContain "3 rows, but 4 are pre-registered"
    }

    @Test
    fun `starting over an existing ledger is refused rather than discarding its observations`(
        @TempDir dir: File,
    ) {
        FloorDerivationLedger.start(dir, plan()).ingest(unit("u1"), csv(allRowsAt(0.01)))
        shouldThrow<FloorLedgerException> { FloorDerivationLedger.start(dir, plan()) }
            .message!! shouldContain "would discard its observations"
    }

    @Test
    fun `loading a directory with no ledger is a refusal, not an empty derivation`(
        @TempDir dir: File,
    ) {
        shouldThrow<FloorLedgerException> { FloorDerivationLedger.load(dir, syntheticCounts) }
            .message!! shouldContain "no ledger at"
    }

    // -----------------------------------------------------------------------------------
    // The per-unit host gate.
    // -----------------------------------------------------------------------------------

    @Test
    fun `a unit measured above the quiesced load threshold cannot be represented at all`() {
        val refusal = shouldThrow<IllegalArgumentException> {
            GateReading(oneMinuteLoad = 4.01, cores = 16, attestedThreshold = 4.00)
        }
        refusal.message!! shouldContain "REFUSED"
        refusal.message!! shouldContain "measuring the interference"
    }

    @Test
    fun `a unit gated on a threshold that is not the gate's own rule is refused`() {
        shouldThrow<IllegalArgumentException> {
            GateReading(oneMinuteLoad = 6.0, cores = 16, attestedThreshold = 8.00)
        }.message!! shouldContain "is not 0.25 x 16 cores"
    }

    @Test
    fun `the gate threshold is 0-25 x cores, the same rule run-series-sh applies`() {
        GateReading(1.0, 16, 4.00).threshold shouldBe 4.0
        GateReading(1.0, 10, 2.50).threshold shouldBe 2.5
        abs(GateReading(1.0, 10, 2.50).attestedThreshold - 2.5) shouldBe 0.0
    }

    @Test
    fun `a unit that cannot name its measuring JVM is refused`() {
        shouldThrow<IllegalArgumentException> { unit("u1", jvm = "  ") }
            .message!! shouldContain "banner"
    }

    // -----------------------------------------------------------------------------------
    // Row keys.
    // -----------------------------------------------------------------------------------

    @Test
    fun `a blank param cell is dropped, so a row encodes the same whatever else was in the file`() {
        // A results file covering two classes carries a Param: column for every parameter
        // either declares, empty where it does not apply.
        RowKey.of("alpha", mapOf("scale" to "N1E3", "degree" to "")) shouldBe
            RowKey.of("alpha", mapOf("scale" to "N1E3"))
    }

    @Test
    fun `a row key round-trips through its encoded form, parameter order and all`() {
        val row = RowKey.of("real", mapOf("direction" to "INSERT", "subject" to "GROUP_BY_TOP_K"))
        RowKey.decode(row.encode()) shouldBe row
        // Encoding is order-independent, so two enumerations that differ only in map
        // ordering do not produce two "different" rows.
        RowKey.of("real", mapOf("subject" to "GROUP_BY_TOP_K", "direction" to "INSERT"))
            .encode() shouldBe row.encode()
        row.describe() shouldBe "real[direction=INSERT, subject=GROUP_BY_TOP_K]"
    }

    @Test
    fun `a row key whose values carry the encoding's own delimiters round-trips`() {
        val row = RowKey.of("m", mapOf("a;b" to "c=d|e\\f", "g h" to "i"))
        RowKey.decode(row.encode()) shouldBe row
    }

    @Test
    fun `a malformed encoded row is refused`() {
        shouldThrow<FloorLedgerException> { RowKey.decode("alpha;noequals") }
            .message!! shouldContain "no '='"
        shouldThrow<FloorLedgerException> { RowKey.decode("alpha;a=b\\z") }
            .message!! shouldContain "unknown escape"
    }

    @Test
    fun `a plan may not name one row twice`() {
        shouldThrow<IllegalArgumentException> {
            DerivationPlan(
                syntheticClass,
                listOf(syntheticRows[0], syntheticRows[0]),
                "-lp",
                syntheticCounts,
            )
        }.message!! shouldContain "may not name one row twice"
    }

    /**
     * The count tripwire is unavoidable, not merely available.
     *
     * A check reachable only through [DerivationPlan.of] is a convention: the primary
     * constructor and the generated `copy` are two ways past it, and either would hand
     * [FloorDerivationLedger.start] a short plan whose completeness is then satisfied
     * vacuously — a floor that is a maximum over a fraction of the class, which is the
     * exact failure this whole file exists to make impossible. So every route is checked.
     */
    @Test
    fun `the raw constructor is checked against the pre-registered count, not only the factory`() {
        shouldThrow<FloorLedgerException> {
            DerivationPlan(syntheticClass, syntheticRows.dropLast(1), "-lp", syntheticCounts)
        }.message!! shouldContain "has 2 rows, but 3 are pre-registered"

        // And a class the table does not name has no checkable universe by either route.
        shouldThrow<FloorLedgerException> {
            DerivationPlan("TypoedBenchmarkk", syntheticRows, "-lp", syntheticCounts)
        }.message!! shouldContain "no pre-registered row count exists"
    }

    @Test
    fun `copying a valid plan down to a subset of its rows is refused just as the constructor is`() {
        val valid = plan()
        shouldThrow<FloorLedgerException> {
            valid.copy(rows = valid.rows.dropLast(1))
        }.message!! shouldContain "has 2 rows, but 3 are pre-registered"
    }

    /**
     * The end of the same argument: a plan that never passed the tripwire must not be able
     * to reach a rendered floor. `start` and `load` are the only two ways to a ledger, and
     * `load` re-checks; this pins that `start` cannot be handed an unchecked plan, because
     * an unchecked plan cannot be constructed at all.
     */
    @Test
    fun `a one-row plan for a three-row class cannot be built, so no ledger can render over it`(
        @TempDir dir: File,
    ) {
        shouldThrow<FloorLedgerException> {
            FloorDerivationLedger.start(
                dir,
                DerivationPlan(syntheticClass, syntheticRows.take(1), "-lp", syntheticCounts),
            )
        }.message!! shouldContain "maximum over a fraction of the class"
        File(dir, FloorDerivationLedger.LEDGER_FILE_NAME).exists() shouldBe false
    }

    // -----------------------------------------------------------------------------------
    // The rendered record is the ordinary one — the ledger adds no estimator of its own.
    // -----------------------------------------------------------------------------------

    /**
     * The runs are `{0.01, 0.0305, 0.9}` per row deliberately: 0.9 is the largest
     * observation in the set and does NOT reach the record, because `classFloorStatistic`
     * takes each row's MEDIAN before folding across rows (`computenet-3sua`). Under the
     * previous estimator this same fixture would have rendered 0.9.
     */
    @Test
    fun `the rendered record is an ordinary ClassNoiseFloor, computing its floor the same way`(
        @TempDir dir: File,
    ) {
        val rendered = completeLedger(dir, listOf(0.01, 0.0305, 0.9))
            .render("2026-08-27", "abcdef012", "mode=AverageTime forks=1")
        rendered.observedRobustDispersion shouldBe 0.0305
        rendered.floor shouldBe roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * 0.0305)
        renderDerivation(rendered) shouldContain "0.061"
    }

    @Test
    fun `an all-zero-dispersion row set is refused by the record it would render, not smuggled through`(
        @TempDir dir: File,
    ) {
        // parseCsv admits a zero error; ClassNoiseFloor is what refuses a zero statistic, and
        // the ledger must not paper over that with a floor of zero.
        val ledger = completeLedger(dir, listOf(0.0, 0.0, 0.0))
        shouldThrow<IllegalArgumentException> { ledger.render("2026-08-27", "abcdef012", "cfg") }
            .message!! shouldContain "observedRobustDispersion must be finite and strictly positive"
    }
}
