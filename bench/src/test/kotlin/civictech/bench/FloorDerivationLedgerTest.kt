package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
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

    private fun unit(id: String, jvm: String = jdk21): UnitAttestation = UnitAttestation(
        unitId = id,
        measuringJvm = jvm,
        gate = gate(),
        timestamp = "2026-08-27T09:14:02Z",
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
     * `observedMaxRelativeDispersion` equals the whole-set maximum, bit for bit.
     *
     * The partitions are generated from a FIXED seed, so a failure is reproducible and is
     * not replaced by a friendlier seed.
     */
    @Test
    fun `folding over any unit partition yields the whole-set maximum`(@TempDir dir: File) {
        val random = Random(20260826)
        // One dispersion per (row, run) cell, so the whole-set maximum is known up front.
        repeat(12) { trial ->
            val cells: Map<Pair<RowKey, Int>, Double> = syntheticRows.flatMap { row ->
                (0 until CLASS_FLOOR_OBSERVATIONS_PER_ROW).map { run ->
                    (row to run) to random.nextDouble(0.001, 0.9)
                }
            }.toMap()
            val wholeSetMaximum = cells.values.max()

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
            rendered.observedMaxRelativeDispersion shouldBe wholeSetMaximum
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
        split.render(args.first, args.second, args.third).floor shouldBe
            roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * 0.047)
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
        ledger.render("2026-08-27", "abcdef012", "cfg").observedMaxRelativeDispersion shouldBe 0.01
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
        // Nothing was taken in: the ledger still renders the pre-refusal maximum.
        ledger.render("2026-08-27", "abcdef012", "cfg")
            .observedMaxRelativeDispersion shouldBe 0.03
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
        ledger.file.writeText((listOf("floor-derivation-ledger v2") + lines.drop(1)).joinToString("\n"))
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
    // The rendered record is the ordinary one — the estimator is untouched.
    // -----------------------------------------------------------------------------------

    @Test
    fun `the rendered record is an ordinary ClassNoiseFloor, computing its floor the same way`(
        @TempDir dir: File,
    ) {
        val rendered = completeLedger(dir, listOf(0.0305, 0.01, 0.02))
            .render("2026-08-27", "abcdef012", "mode=AverageTime forks=1")
        rendered.observedMaxRelativeDispersion shouldBe 0.0305
        rendered.floor shouldBe roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * 0.0305)
        renderDerivation(rendered) shouldContain "0.061"
    }

    @Test
    fun `an all-zero-dispersion row set is refused by the record it would render, not smuggled through`(
        @TempDir dir: File,
    ) {
        // parseCsv admits a zero error; ClassNoiseFloor is what refuses a zero maximum, and
        // the ledger must not paper over that with a floor of zero.
        val ledger = completeLedger(dir, listOf(0.0, 0.0, 0.0))
        shouldThrow<IllegalArgumentException> { ledger.render("2026-08-27", "abcdef012", "cfg") }
            .message!! shouldContain "observedMaxRelativeDispersion must be finite and strictly positive"
    }
}
