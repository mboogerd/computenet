package civictech.bench.micro

import civictech.bench.Findings
import civictech.bench.TriggerClaim
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Every branch of [IterationLengthCriterion], exercised against synthetic inputs
 * (computenet-bzwx).
 *
 * **This test is untagged on purpose.** `IterationLengthAbRenderTest` is `@Tag("bench")`
 * and therefore runs only when someone points it at a real sweep, which means the
 * decision function inside it would otherwise execute exactly once in its life — on the
 * run whose verdict it decides. A criterion nothing checks is a criterion a later
 * refactor can invert while the six required checks stay green. These cases run on every
 * `:bench:test`, cost microseconds, and touch no JMH artifact.
 *
 * The cases are written so that flipping one comparison in `IterationLengthCriterion`
 * fails a NAMED case rather than "something somewhere".
 */
class IterationLengthCriterionTest {

    private fun row(
        subject: String,
        shortScore: Double,
        longScore: Double,
        shortRelativeError: Double = 0.01,
        longRelativeError: Double = 0.01,
    ): IterationLengthCriterion.SubjectAb = IterationLengthCriterion.SubjectAb(
        subject = subject,
        short = IterationLengthCriterion.Arm(shortScore, shortScore * shortRelativeError),
        long = IterationLengthCriterion.Arm(longScore, longScore * longRelativeError),
    )

    // -----------------------------------------------------------------------------
    // Row verdicts
    // -----------------------------------------------------------------------------

    @Test
    fun `a tight row whose whole ratio interval sits below the material ratio COSTS`() {
        row("COUNT", shortScore = 900_000.0, longScore = 440_000.0)
            .verdict shouldBe IterationLengthCriterion.RowVerdict.COSTS
    }

    @Test
    fun `a tight row whose whole ratio interval sits above the material ratio DOES_NOT_COST`() {
        row("TAGGED_SET", shortScore = 800_000.0, longScore = 792_000.0)
            .verdict shouldBe IterationLengthCriterion.RowVerdict.DOES_NOT_COST
    }

    @Test
    fun `a row whose ratio interval straddles the material ratio is UNDECIDED`() {
        // Point ratio 0.90 exactly, with error bars either side of it.
        row("UNION", shortScore = 1_000_000.0, longScore = 900_000.0)
            .verdict shouldBe IterationLengthCriterion.RowVerdict.UNDECIDED
    }

    @Test
    fun `a row whose long arm is wider than the resolvable bound is UNRESOLVED`() {
        row(
            "PRESENCE_COUNT",
            shortScore = 384_000.0,
            longScore = 276_000.0,
            longRelativeError = 0.11,
        ).verdict shouldBe IterationLengthCriterion.RowVerdict.UNRESOLVED
    }

    @Test
    fun `a row whose short arm is wider than the resolvable bound is UNRESOLVED`() {
        row(
            "QUORUM",
            shortScore = 398_000.0,
            longScore = 280_000.0,
            shortRelativeError = 0.11,
        ).verdict shouldBe IterationLengthCriterion.RowVerdict.UNRESOLVED
    }

    @Test
    fun `a row exactly at the resolvable bound in both arms still decides`() {
        row(
            "FLAT_MAP",
            shortScore = 771_000.0,
            longScore = 349_000.0,
            shortRelativeError = IterationLengthCriterion.RESOLVABLE_RELATIVE_ERROR,
            longRelativeError = IterationLengthCriterion.RESOLVABLE_RELATIVE_ERROR,
        ).verdict shouldBe IterationLengthCriterion.RowVerdict.COSTS
    }

    @Test
    fun `a row whose short arm cannot bound a ratio at all is UNRESOLVED, not zero`() {
        IterationLengthCriterion.SubjectAb(
            subject = "FILTER",
            short = IterationLengthCriterion.Arm(score = 0.0, error = 0.0),
            long = IterationLengthCriterion.Arm(score = 100.0, error = 1.0),
        ).verdict shouldBe IterationLengthCriterion.RowVerdict.UNRESOLVED
    }

    // -----------------------------------------------------------------------------
    // Aggregation
    // -----------------------------------------------------------------------------

    @Test
    fun `a strict majority of COSTS rows FIRES`() {
        val rows = listOf(
            row("COUNT", 900_000.0, 400_000.0),
            row("FLAT_MAP", 770_000.0, 350_000.0),
            row("UNION", 780_000.0, 350_000.0),
            row("TAGGED_SET", 800_000.0, 795_000.0),
            row("FILTER", 880_000.0, 875_000.0),
        )
        IterationLengthCriterion.verdictOf(rows) shouldBe IterationLengthCriterion.Verdict.FIRES
    }

    @Test
    fun `exactly half the rows costing is not a strict majority and does not fire`() {
        val rows = listOf(
            row("COUNT", 900_000.0, 400_000.0),
            row("FLAT_MAP", 770_000.0, 350_000.0),
            row("TAGGED_SET", 800_000.0, 795_000.0),
            row("FILTER", 880_000.0, 875_000.0),
        )
        IterationLengthCriterion.verdictOf(rows) shouldBe
            IterationLengthCriterion.Verdict.INCONCLUSIVE
    }

    @Test
    fun `every row not costing RETIRES`() {
        val rows = listOf(
            row("TAGGED_SET", 800_000.0, 795_000.0),
            row("FILTER", 880_000.0, 878_000.0),
            row("COUNT", 900_000.0, 899_000.0),
        )
        IterationLengthCriterion.verdictOf(rows) shouldBe IterationLengthCriterion.Verdict.RETIRES
    }

    @Test
    fun `one unresolved row is enough to block RETIRES`() {
        val rows = listOf(
            row("TAGGED_SET", 800_000.0, 795_000.0),
            row("FILTER", 880_000.0, 878_000.0),
            row("QUORUM", 400_000.0, 399_000.0, longRelativeError = 0.5),
        )
        IterationLengthCriterion.verdictOf(rows) shouldBe
            IterationLengthCriterion.Verdict.INCONCLUSIVE
    }

    @Test
    fun `no rows at all is INCONCLUSIVE, never RETIRES`() {
        IterationLengthCriterion.verdictOf(emptyList()) shouldBe
            IterationLengthCriterion.Verdict.INCONCLUSIVE
    }

    // -----------------------------------------------------------------------------
    // The computenet-i61m subject split
    // -----------------------------------------------------------------------------

    @Test
    fun `the i61m pattern - COUNT and FLAT_MAP fall, TAGGED_SET and FILTER do not - REPRODUCES`() {
        val rows = listOf(
            row("COUNT", 925_676.3, 440_702.1),
            row("FLAT_MAP", 771_230.8, 349_924.1),
            row("TAGGED_SET", 809_249.7, 803_000.0),
            row("FILTER", 884_401.3, 880_000.0),
        )
        IterationLengthCriterion.splitOf(rows) shouldBe IterationLengthCriterion.Split.REPRODUCES
    }

    @Test
    fun `all four named rows falling together DOES_NOT_REPRODUCE the split`() {
        val rows = listOf(
            row("COUNT", 925_000.0, 440_000.0),
            row("FLAT_MAP", 771_000.0, 350_000.0),
            row("TAGGED_SET", 809_000.0, 400_000.0),
            row("FILTER", 884_000.0, 430_000.0),
        )
        IterationLengthCriterion.splitOf(rows) shouldBe
            IterationLengthCriterion.Split.DOES_NOT_REPRODUCE
    }

    @Test
    fun `an undecided row among the four leaves the split UNRESOLVED`() {
        val rows = listOf(
            row("COUNT", 925_000.0, 440_000.0),
            row("FLAT_MAP", 771_000.0, 350_000.0),
            row("TAGGED_SET", 1_000_000.0, 900_000.0),
            row("FILTER", 884_000.0, 880_000.0),
        )
        IterationLengthCriterion.splitOf(rows) shouldBe IterationLengthCriterion.Split.UNRESOLVED
    }

    @Test
    fun `a missing named row leaves the split UNRESOLVED rather than half-answered`() {
        val rows = listOf(
            row("COUNT", 925_000.0, 440_000.0),
            row("FLAT_MAP", 771_000.0, 350_000.0),
            row("TAGGED_SET", 809_000.0, 803_000.0),
        )
        IterationLengthCriterion.splitOf(rows) shouldBe IterationLengthCriterion.Split.UNRESOLVED
    }

    // -----------------------------------------------------------------------------
    // The rendered sentence
    // -----------------------------------------------------------------------------

    @Test
    fun `the criterion text carries exactly one verdict word, so Findings accepts it`() {
        val rows = listOf(
            row("COUNT", 925_000.0, 440_000.0),
            row("TAGGED_SET", 809_000.0, 803_000.0),
        )
        val verdict = IterationLengthCriterion.verdictOf(rows)
        val statement = "$verdict: ${IterationLengthCriterion.CRITERION}; measured, " +
            IterationLengthCriterion.measuredClause(rows)
        // Findings.entry independently counts whole-word FIRES/RETIRES/INCONCLUSIVE and
        // refuses a statement holding other than exactly one. Asserting through the
        // writer rather than by grepping the string is what keeps this honest if that
        // counting rule ever changes.
        val rendered = Findings.entry(
            date = "2026-08-26",
            subject = "synthetic",
            results = civictech.bench.FindingsTable(
                listOf(
                    civictech.bench.BenchResult(
                        value = 1.0,
                        unit = "ops/s",
                        dispersion = 0.001,
                        drive = civictech.bench.Drive.REAL,
                        env = civictech.bench.RunEnvironment(
                            jvmVendor = "vendor",
                            jvmVersion = "21",
                            heapSettings = "defaults",
                            cpuModel = "cpu",
                            coreCount = 1,
                            os = "os",
                            jmhMode = "Throughput",
                            forkCount = 2,
                            warmupIterations = 5,
                            measurementIterations = 10,
                            harnessCommitSha = "0123456",
                        ),
                    ),
                ),
                labels = listOf("row"),
            ),
            trigger = TriggerClaim.Cited(gapId = "[BEN1-28]", statement = statement),
        )
        rendered shouldContain "INCONCLUSIVE"
    }

    @Test
    fun `the measured clause names the split verdict without a second verdict word`() {
        val rows = listOf(
            row("COUNT", 925_000.0, 440_000.0),
            row("FLAT_MAP", 771_000.0, 350_000.0),
            row("TAGGED_SET", 809_000.0, 803_000.0),
            row("FILTER", 884_000.0, 880_000.0),
        )
        val clause = IterationLengthCriterion.measuredClause(rows)
        clause shouldContain "REPRODUCES"
        listOf("FIRES", "RETIRES", "INCONCLUSIVE").forEach { word ->
            check(!Regex("\\b$word\\b").containsMatchIn(clause)) {
                "the measured clause must carry no verdict word of its own; found '$word' in: $clause"
            }
        }
    }
}

/**
 * Every branch of [IterationLengthResidualCriterion], exercised against synthetic inputs
 * (computenet-ciz9).
 *
 * Untagged, for the same reason [IterationLengthCriterionTest] is: the residual render
 * entry point is `@Tag("bench")` and would otherwise execute its decision function exactly
 * once in its life — on the sweep whose verdict it decides. These cases run on every
 * `:bench:test`, cost microseconds, and touch no JMH artifact.
 *
 * The cases are written so that flipping one comparison, or quietly re-pointing the
 * residual criterion at [IterationLengthCriterion]'s constants, fails a NAMED case.
 */
class IterationLengthResidualCriterionTest {

    private fun row(
        subject: String,
        shortScore: Double,
        longScore: Double,
        shortRelativeError: Double = 0.005,
        longRelativeError: Double = 0.005,
    ): IterationLengthCriterion.SubjectAb = IterationLengthCriterion.SubjectAb(
        subject = subject,
        short = IterationLengthCriterion.Arm(shortScore, shortScore * shortRelativeError),
        long = IterationLengthCriterion.Arm(longScore, longScore * longRelativeError),
    )

    /** A row whose whole interval sits below 0.90. */
    private fun costing(subject: String) = row(subject, 900_000.0, 700_000.0)

    /** A row whose whole interval sits above 0.90. */
    private fun flat(subject: String) = row(subject, 900_000.0, 895_000.0)

    /** A row centred on 0.9156 with `computenet-bzwx`'s own 2-fork error bars. */
    private fun straddling(subject: String) =
        row(subject, 867_384.3, 794_175.2, shortRelativeError = 0.0139, longRelativeError = 0.0223)

    // -----------------------------------------------------------------------------
    // The pair the criterion is stated over
    // -----------------------------------------------------------------------------

    @Test
    fun `the residual criterion names exactly TAGGED_SET and UNION`() {
        IterationLengthResidualCriterion.SUBJECTS shouldBe listOf("TAGGED_SET", "UNION")
    }

    @Test
    fun `the residual criterion raises the fork count above computenet-bzwx's two`() {
        check(IterationLengthResidualCriterion.FORKS > 2) {
            "the whole point of this sweep is more forks than computenet-bzwx's 2; found " +
                "${IterationLengthResidualCriterion.FORKS}"
        }
    }

    @Test
    fun `the residual criterion holds its own thresholds rather than importing them`() {
        // Equal by pre-registered decision, but SEPARATE constants: this case exists so
        // that a later change to the family criterion's numbers is visible here as a
        // deliberate choice rather than an invisible retroactive move of this run's
        // boundary. If you are changing one of these, change it because the criterion
        // changed — not to make a measured row decide.
        IterationLengthResidualCriterion.MATERIAL_RATIO shouldBe 0.90
        IterationLengthResidualCriterion.RESOLVABLE_RELATIVE_ERROR shouldBe 0.10
    }

    // -----------------------------------------------------------------------------
    // Row verdicts, under the residual's OWN constants
    // -----------------------------------------------------------------------------

    @Test
    fun `a tight row whose whole interval sits below the material ratio COSTS`() {
        IterationLengthResidualCriterion.rowVerdictOf(costing("TAGGED_SET")) shouldBe
            IterationLengthCriterion.RowVerdict.COSTS
    }

    @Test
    fun `a tight row whose whole interval sits above the material ratio DOES NOT COST`() {
        IterationLengthResidualCriterion.rowVerdictOf(flat("UNION")) shouldBe
            IterationLengthCriterion.RowVerdict.DOES_NOT_COST
    }

    @Test
    fun `computenet-bzwx's own TAGGED_SET error bars still straddle the boundary`() {
        // The row this item exists to resolve, at the power that failed to resolve it.
        IterationLengthResidualCriterion.rowVerdictOf(straddling("TAGGED_SET")) shouldBe
            IterationLengthCriterion.RowVerdict.UNDECIDED
    }

    @Test
    fun `the same TAGGED_SET point ratio resolves once the error bars shrink`() {
        // Same 0.9156 point ratio, error bars scaled by the factor 16 forks is expected to
        // buy. This is what "powered" means for this design, asserted rather than assumed.
        val tightened = row(
            "TAGGED_SET",
            867_384.3,
            794_175.2,
            shortRelativeError = 0.0139 / 3.3,
            longRelativeError = 0.0223 / 3.3,
        )
        IterationLengthResidualCriterion.rowVerdictOf(tightened) shouldBe
            IterationLengthCriterion.RowVerdict.DOES_NOT_COST
    }

    @Test
    fun `a row with an error bar wider than the resolvable limit is UNRESOLVED`() {
        IterationLengthResidualCriterion.rowVerdictOf(
            row("UNION", 900_000.0, 700_000.0, longRelativeError = 0.11),
        ) shouldBe IterationLengthCriterion.RowVerdict.UNRESOLVED
    }

    // -----------------------------------------------------------------------------
    // Aggregation
    // -----------------------------------------------------------------------------

    @Test
    fun `both rows losing throughput fires`() {
        IterationLengthResidualCriterion.verdictOf(
            listOf(costing("TAGGED_SET"), costing("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Verdict.FIRES
    }

    @Test
    fun `both rows not losing throughput retires`() {
        IterationLengthResidualCriterion.verdictOf(
            listOf(flat("TAGGED_SET"), flat("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Verdict.RETIRES
    }

    @Test
    fun `rows resolving to opposite sides is undecided, not a majority`() {
        IterationLengthResidualCriterion.verdictOf(
            listOf(costing("TAGGED_SET"), flat("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Verdict.INCONCLUSIVE
    }

    @Test
    fun `one straddling row leaves the pair undecided`() {
        IterationLengthResidualCriterion.verdictOf(
            listOf(straddling("TAGGED_SET"), flat("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Verdict.INCONCLUSIVE
    }

    @Test
    fun `a missing row is undecided rather than a one-row answer`() {
        IterationLengthResidualCriterion.verdictOf(
            listOf(costing("TAGGED_SET")),
        ) shouldBe IterationLengthResidualCriterion.Verdict.INCONCLUSIVE
    }

    @Test
    fun `an extra subject is undecided rather than folded into the pair`() {
        IterationLengthResidualCriterion.verdictOf(
            listOf(costing("TAGGED_SET"), costing("UNION"), costing("COUNT")),
        ) shouldBe IterationLengthResidualCriterion.Verdict.INCONCLUSIVE
    }

    @Test
    fun `no rows at all is undecided`() {
        IterationLengthResidualCriterion.verdictOf(emptyList()) shouldBe
            IterationLengthResidualCriterion.Verdict.INCONCLUSIVE
    }

    // -----------------------------------------------------------------------------
    // Agreement, which is a separate question from the verdict
    // -----------------------------------------------------------------------------

    @Test
    fun `two costing rows agree that the longer iteration costs`() {
        IterationLengthResidualCriterion.agreementOf(
            listOf(costing("TAGGED_SET"), costing("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Agreement.AGREE_COSTS
    }

    @Test
    fun `two flat rows agree that it does not`() {
        IterationLengthResidualCriterion.agreementOf(
            listOf(flat("TAGGED_SET"), flat("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Agreement.AGREE_DOES_NOT_COST
    }

    @Test
    fun `rows on opposite sides DISAGREE`() {
        IterationLengthResidualCriterion.agreementOf(
            listOf(costing("TAGGED_SET"), flat("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Agreement.DISAGREE
    }

    @Test
    fun `an unresolved row makes agreement unanswerable rather than false`() {
        IterationLengthResidualCriterion.agreementOf(
            listOf(straddling("TAGGED_SET"), flat("UNION")),
        ) shouldBe IterationLengthResidualCriterion.Agreement.NOT_BOTH_RESOLVED
    }

    // -----------------------------------------------------------------------------
    // The rendered sentence
    // -----------------------------------------------------------------------------

    @Test
    fun `the residual criterion sentence carries exactly one lowercase verdict vocabulary`() {
        // Findings.entry counts FIRES / RETIRES / INCONCLUSIVE case-sensitively as whole
        // words and refuses a statement holding other than exactly one. The criterion and
        // the measured clause must therefore contribute none; the caller prefixes one.
        listOf("FIRES", "RETIRES", "INCONCLUSIVE").forEach { word ->
            check(!Regex("\\b$word\\b").containsMatchIn(IterationLengthResidualCriterion.CRITERION)) {
                "the criterion text must carry no verdict word; found '$word'"
            }
        }
    }

    @Test
    fun `the residual criterion sentence says it does not restate the other six rows`() {
        IterationLengthResidualCriterion.CRITERION shouldContain "TAGGED_SET and UNION only"
        IterationLengthResidualCriterion.CRITERION shouldContain "neither re-measured nor re-stated"
    }

    @Test
    fun `the measured clause names both rows and the agreement without a verdict word`() {
        val rows = listOf(costing("TAGGED_SET"), flat("UNION"))
        val clause = IterationLengthResidualCriterion.measuredClause(rows)
        clause shouldContain "TAGGED_SET"
        clause shouldContain "UNION"
        clause shouldContain "DISAGREE"
        clause shouldContain "16 forks per arm"
        listOf("FIRES", "RETIRES", "INCONCLUSIVE").forEach { word ->
            check(!Regex("\\b$word\\b").containsMatchIn(clause)) {
                "the measured clause must carry no verdict word of its own; found '$word' in: $clause"
            }
        }
    }

    @Test
    fun `Findings accepts a residual trigger statement built the way the render path builds it`() {
        val rows = listOf(flat("TAGGED_SET"), flat("UNION"))
        val verdict = IterationLengthResidualCriterion.verdictOf(rows)
        val statement = "$verdict: ${IterationLengthResidualCriterion.CRITERION}; measured, " +
            IterationLengthResidualCriterion.measuredClause(rows)
        val matches = listOf("FIRES", "RETIRES", "INCONCLUSIVE")
            .count { Regex("\\b$it\\b").containsMatchIn(statement) }
        matches shouldBe 1
    }
}
