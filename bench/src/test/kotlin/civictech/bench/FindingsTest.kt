package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [Findings.entry]'s six refusal rules (epic section 5 "Findings file",
 * `[BEN1-25]`, `[BEN1-27]`, `[BEN1-30]`..`[BEN1-32]`).
 *
 * Every test here demonstrates a *refusal* — it builds the offending shape and asserts
 * [Findings.entry] throws [FindingsRefusalException] (or, for the "marked incomplete"
 * rule, that it renders successfully but distinguishably, never as a plain finding).
 * Each is written so that removing the corresponding check in `Findings.kt` makes the
 * specific test fail — not just "some test somewhere" (mutation-checked; see the
 * task's `bd comment` for what was mutated).
 */
class FindingsTest {

    // ---------------------------------------------------------------------------
    // Shared fixtures
    // ---------------------------------------------------------------------------

    private fun validEnvironment(): RunEnvironment = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.4",
        heapSettings = "-Xms1g -Xmx4g",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 14.5",
        jmhMode = "Throughput",
        forkCount = 2,
        warmupIterations = 3,
        measurementIterations = 5,
        harnessCommitSha = "0123456789abcdef0123456789abcdef01234567",
    )

    private fun validResult(
        value: Double = 100.0,
        dispersion: Double = 1.0,
        drive: Drive = Drive.SIM,
        env: RunEnvironment = validEnvironment(),
    ): BenchResult = BenchResult(
        value = value,
        unit = "ops/s",
        dispersion = dispersion,
        drive = drive,
        env = env,
    )

    /** A [BenchResult] whose relative dispersion is strictly above [NOISE_FLOOR]. */
    private fun unreportableResult(drive: Drive = Drive.SIM): BenchResult {
        val value = 1.0
        return validResult(value = value, dispersion = value * (NOISE_FLOOR + 0.001), drive = drive)
    }

    /** A [BenchResult] whose relative dispersion is at or below [NOISE_FLOOR]. */
    private fun reportableResult(drive: Drive = Drive.SIM): BenchResult {
        val value = 1.0
        return validResult(value = value, dispersion = value * (NOISE_FLOOR - 0.001), drive = drive)
    }

    private fun validTable(): FindingsTable =
        FindingsTable(listOf(reportableResult()), labels = listOf("result"))

    // ---------------------------------------------------------------------------
    // Control: a fully valid entry renders and carries every template piece.
    // ---------------------------------------------------------------------------

    @Test
    fun `a complete valid entry renders the full template`() {
        val table = FindingsTable(
            listOf(reportableResult(), reportableResult()),
            labels = listOf("insert", "retract"),
        )
        val rendered = Findings.entry(
            date = "2026-08-18",
            subject = "OrMapCell insert/retract throughput",
            results = table,
            trigger = TriggerClaim.Cited(
                gapId = "G-21 phase 3",
                statement = "FIRES, because retract throughput regressed 40%.",
            ),
        )

        rendered shouldContain "## 2026-08-18 — OrMapCell insert/retract throughput"
        rendered shouldContain "Harness: 0123456789abcdef0123456789abcdef01234567"
        rendered shouldContain "JVM Eclipse Adoptium/21.0.4"
        rendered shouldContain "heap -Xms1g -Xmx4g"
        rendered shouldContain "Apple M2 Pro, 10 cores, Mac OS X 14.5"
        rendered shouldContain "JMH: mode=Throughput forks=2 warmup=3 iters=5 · drive=SIM"
        rendered shouldContain "| subject | value | notes |"
        rendered shouldContain "| insert | 1.0 ± 0.004 ops/s | |"
        rendered shouldContain "| retract | 1.0 ± 0.004 ops/s | |"
        rendered shouldContain "Trigger: G-21 phase 3 — FIRES, because retract throughput regressed 40%."
    }

    // ---------------------------------------------------------------------------
    // Rule 1 [BEN1-25] (BS-11 writer half), AMENDED by computenet-785b: the gate is
    // claim-relative. A standalone row is rendered with its error bar whatever its
    // dispersion; a COMPARISON is refused unless the claimed effect exceeds the two
    // rows' combined error bars.
    // ---------------------------------------------------------------------------

    @Test
    fun `entry admits a dispersed standalone result, rendering its error bar`() {
        val noisy = unreportableResult()
        classify(noisy) shouldBe Reportability.Unreportable
        val table = FindingsTable(listOf(reportableResult(), noisy), labels = listOf("a", "b"))

        // Before 2026-08-22 this refused. A number that states its own precision is
        // reportable; withholding it told the reader less, not more.
        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = table)
        rendered shouldContain "| b | ${noisy.value} ± ${noisy.dispersion} ops/s | |"
    }

    @Test
    fun `entry admits the PROBE-A negative-value result, whose ratio classify cannot use`() {
        // value=-100.0, dispersion=50.0: relativeDispersion is -0.5. classify still
        // refuses it as above the harness sanity bound (computenet-x9e.3.6's shape), and
        // that no longer decides whether the row may be printed.
        val negative = validResult(value = -100.0, dispersion = 50.0)
        classify(negative) shouldBe Reportability.Unreportable
        val table = FindingsTable(listOf(reportableResult(), negative), labels = listOf("a", "b"))

        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = table)
        rendered shouldContain "| b | -100.0 ± 50.0 ops/s | |"
    }

    @Test
    fun `entry refuses a comparison whose effect is inside the combined error bars`() {
        val left = validResult(value = 100.0, dispersion = 10.0)
        val right = validResult(value = 108.0, dispersion = 10.0)
        resolveEffect(left, right) shouldBe EffectResolution.Unresolved
        val table = FindingsTable(listOf(left, right), labels = listOf("a", "b"))

        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = table,
                comparisons = listOf(ComparisonClaim("a", "b", "b is slower than a")),
            )
        }
        ex.message shouldContain "does not exceed the combined 99.9% error bars"
        // The message must name the arithmetic, not merely assert a refusal.
        ex.message shouldContain "claims an effect of 8.0"
        ex.message shouldContain "20.0"
    }

    @Test
    fun `entry renders a comparison whose effect clears the combined error bars`() {
        val left = validResult(value = 100.0, dispersion = 10.0)
        val right = validResult(value = 130.0, dispersion = 10.0)
        resolveEffect(left, right) shouldBe EffectResolution.Resolved
        val table = FindingsTable(listOf(left, right), labels = listOf("a", "b"))

        val rendered = Findings.entry(
            date = "2026-08-18",
            subject = "x",
            results = table,
            comparisons = listOf(ComparisonClaim("a", "b", "b outruns a by ~30%")),
        )
        rendered shouldContain "Comparisons (effect vs combined error bars):"
        rendered shouldContain "- a vs b: |Δ| = 30.0 ops/s > combined 99.9% error 20.0 ops/s"
        rendered shouldContain "b outruns a by ~30%"
    }

    @Test
    fun `a comparison exactly equal to its combined error bars is refused, not admitted`() {
        // The criterion is STRICT, matching the fan-out criterion it generalizes.
        val left = validResult(value = 100.0, dispersion = 10.0)
        val right = validResult(value = 120.0, dispersion = 10.0)
        resolveEffect(left, right) shouldBe EffectResolution.Unresolved

        shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = FindingsTable(listOf(left, right), labels = listOf("a", "b")),
                comparisons = listOf(ComparisonClaim("a", "b", "b is faster")),
            )
        }
    }

    @Test
    fun `entry refuses a comparison naming a row the table does not carry`() {
        val table = FindingsTable(
            listOf(validResult(value = 100.0, dispersion = 1.0), validResult(value = 200.0, dispersion = 1.0)),
            labels = listOf("a", "b"),
        )
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = table,
                comparisons = listOf(ComparisonClaim("a", "c", "c is faster")),
            )
        }
        ex.message shouldContain "'c', which is not a row of this table"
    }

    @Test
    fun `entry refuses a comparison of a row with itself`() {
        val table = FindingsTable(
            listOf(validResult(value = 100.0, dispersion = 1.0), validResult(value = 200.0, dispersion = 1.0)),
            labels = listOf("a", "b"),
        )
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = table,
                comparisons = listOf(ComparisonClaim("a", "a", "a differs from a")),
            )
        }
        ex.message shouldContain "same row on both sides"
    }

    @Test
    fun `entry refuses a comparison that states nothing`() {
        val table = FindingsTable(
            listOf(validResult(value = 100.0, dispersion = 1.0), validResult(value = 200.0, dispersion = 1.0)),
            labels = listOf("a", "b"),
        )
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = table,
                comparisons = listOf(ComparisonClaim("a", "b", "   ")),
            )
        }
        ex.message shouldContain "statement is blank"
    }

    @Test
    fun `entry refuses a comparison that subtracts across units`() {
        val nsPerOp = validResult(value = 100.0, dispersion = 1.0).copy(unit = "ns/op")
        val opsPerS = validResult(value = 200.0, dispersion = 1.0)
        val table = FindingsTable(listOf(nsPerOp, opsPerS), labels = listOf("a", "b"))
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = table,
                comparisons = listOf(ComparisonClaim("a", "b", "a beats b")),
            )
        }
        ex.message shouldContain "subtracts across units"
    }

    @Test
    fun `an entry drawing no comparison renders no comparisons block`() {
        val table = FindingsTable(
            listOf(reportableResult(), reportableResult()),
            labels = listOf("a", "b"),
        )
        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = table)
        (rendered.contains("Comparisons")) shouldBe false
    }

    // ---------------------------------------------------------------------------
    // Rule 2 [BEN1-27] (BS-12): no path around FindingsTable's single-drive
    // invariant. entry() takes ONLY a FindingsTable — no overload taking a raw
    // List<BenchResult> or Collection<BenchResult>.
    // ---------------------------------------------------------------------------

    @Test
    fun `Findings declares exactly one entry method, accepting only FindingsTable`() {
        val entryMethods = Findings::class.java.declaredMethods.filter { it.name == "entry" }
        entryMethods.size shouldBe 1
        val results = entryMethods.single().parameterTypes.toList()
        results shouldContainElement FindingsTable::class.java
        // The only List parameter is `comparisons: List<ComparisonClaim>`
        // (computenet-785b); no overload takes results as a raw List/Collection of
        // BenchResult, which is the invariant [BEN1-27] rests on.
        entryMethods.single().genericParameterTypes
            .map { it.typeName }
            .none { it.contains("BenchResult") } shouldBe true
    }

    @Test
    fun `entry inherits FindingsTable's refusal of a mixed-drive table`() {
        // FindingsTable itself refuses at construction (asserted in ResultModelTest);
        // this demonstrates entry() offers no way around that refusal to reach
        // Findings.entry at all.
        shouldThrow<IllegalArgumentException> {
            FindingsTable(listOf(reportableResult(drive = Drive.SIM), reportableResult(drive = Drive.REAL)))
        }
    }

    // ---------------------------------------------------------------------------
    // Rule 2b (computenet-x9e.3.5, unowned seam found by feature review of PR #315):
    // a mixed-RunEnvironment table must not silently report every row under the
    // first result's environment. FindingsTable refuses at construction (mirroring
    // the single-Drive refusal above), so `Findings.kt`'s
    // `results.results.first().env` stays safe to use — this test is the one that
    // must go red if that refusal (or the `.first().env` read it protects) is ever
    // regressed back to trusting an unvalidated table.
    // ---------------------------------------------------------------------------

    @Test
    fun `entry inherits FindingsTable's refusal of the two-result mixed-environment case`() {
        // The exact shape measured in the bug report: a second result carrying a
        // different harness SHA, JVM vendor/version, JMH mode, and fork count.
        val first = reportableResult()
        val second = reportableResult().copy(
            env = validEnvironment().copy(
                harnessCommitSha = "shatwo0000000000000000000000000000000000",
                jvmVendor = "GraalVM",
                jvmVersion = "17.0.1",
                jmhMode = "AverageTime",
                forkCount = 99,
            ),
        )

        val ex = shouldThrow<IllegalArgumentException> {
            FindingsTable(listOf(first, second), labels = listOf("insert", "retract"))
        }
        ex.message shouldContain "RunEnvironment"
        ex.message shouldContain "harnessCommitSha"
        ex.message shouldContain "jvmVendor"
        ex.message shouldContain "jvmVersion"
        ex.message shouldContain "jmhMode"
        ex.message shouldContain "forkCount"

        // No path around the FindingsTable refusal reaches Findings.entry either.
        Findings::class.java.declaredMethods
            .filter { it.name == "entry" }
            .single()
            .parameterTypes.toList() shouldContainElement FindingsTable::class.java
    }

    @Test
    fun `entry admits a multi-row table that shares one RunEnvironment, like findings md's first entry`() {
        // doc/bench/findings.md's first entry is a three-row table of three separate
        // JMH runs that happened to share one environment; the fix must leave that
        // shape renderable, not merely refuse the mixed case.
        val table = FindingsTable(
            listOf(reportableResult(), reportableResult(), reportableResult()),
            labels = listOf("run 1", "run 2", "run 3"),
        )
        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = table)
        rendered shouldContain "| run 1 |"
        rendered shouldContain "| run 2 |"
        rendered shouldContain "| run 3 |"
    }

    // ---------------------------------------------------------------------------
    // Rule 3 [BEN1-30]: an entry missing date, subject, results table, or per-row
    // labels is refused as incomplete.
    // ---------------------------------------------------------------------------

    @Test
    fun `entry refuses a missing date`() {
        shouldThrow<FindingsRefusalException> {
            Findings.entry(date = null, subject = "x", results = validTable())
        }.message shouldContain "date"
    }

    @Test
    fun `entry refuses a blank date`() {
        shouldThrow<FindingsRefusalException> {
            Findings.entry(date = "   ", subject = "x", results = validTable())
        }.message shouldContain "date"
    }

    @Test
    fun `entry refuses a missing subject`() {
        shouldThrow<FindingsRefusalException> {
            Findings.entry(date = "2026-08-18", subject = null, results = validTable())
        }.message shouldContain "subject"
    }

    @Test
    fun `entry refuses a blank subject`() {
        shouldThrow<FindingsRefusalException> {
            Findings.entry(date = "2026-08-18", subject = "  ", results = validTable())
        }.message shouldContain "subject"
    }

    @Test
    fun `entry refuses a missing results table`() {
        shouldThrow<FindingsRefusalException> {
            Findings.entry(date = "2026-08-18", subject = "x", results = null)
        }.message shouldContain "results table"
    }

    @Test
    fun `entry refuses a results table with no per-row labels`() {
        val unlabelled = FindingsTable(listOf(reportableResult()))
        shouldThrow<FindingsRefusalException> {
            Findings.entry(date = "2026-08-18", subject = "x", results = unlabelled)
        }.message shouldContain "labels"
    }

    // ---------------------------------------------------------------------------
    // Rule 4 [BEN1-30] (reviewer finding on computenet-x9e.3, PR #315): the results
    // table carries a caller-supplied per-row label distinct from the unit column,
    // and never hard-codes a unit in its header — the unit rendered is each row's
    // own BenchResult.unit.
    // ---------------------------------------------------------------------------

    @Test
    fun `renderTable renders each row's own caller-supplied label, not the shared unit`() {
        // Same unit on both rows (as an insert/retract pair of one operator would
        // share), distinguished only by their labels.
        val table = FindingsTable(
            listOf(reportableResult(), reportableResult()),
            labels = listOf("insert", "retract"),
        )
        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = table)

        rendered shouldContain "| insert | 1.0 ± 0.004 ops/s | |"
        rendered shouldContain "| retract | 1.0 ± 0.004 ops/s | |"
        // The old writer put the unit in column 1; the label must be there instead.
        (rendered.contains("| ops/s |")) shouldBe false
    }

    @Test
    fun `renderTable renders each result's own unit, never a hard-coded ops per s`() {
        val nsResult = validResult(value = 4.321050323941347, dispersion = 0.004992364297944783)
            .copy(unit = "ns/op")
        val table = FindingsTable(listOf(nsResult), labels = listOf("run 1"))

        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = table)

        rendered shouldContain "| run 1 | 4.321050323941347 ± 0.004992364297944783 ns/op | |"
        rendered.contains("ops/s") shouldBe false
        rendered.contains("insert (ops/s") shouldBe false
        rendered.contains("retract (ops/s") shouldBe false
    }

    @Test
    fun `renderTable's header names no unit at all`() {
        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = validTable())
        rendered shouldContain "| subject | value | notes |"
        rendered.contains("insert (ops/s") shouldBe false
        rendered.contains("retract (ops/s") shouldBe false
    }

    // ---------------------------------------------------------------------------
    // Rule 5 [BEN1-31]: a cited G-id must state exactly one of
    // FIRES/RETIRES/INCONCLUSIVE, else the writer refuses.
    // ---------------------------------------------------------------------------

    @Test
    fun `entry refuses a cited G-id whose statement states no verdict word`() {
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = validTable(),
                trigger = TriggerClaim.Cited(
                    gapId = "G-21 phase 3",
                    statement = "Numbers look fine, nothing conclusive to say here.",
                ),
            )
        }
        ex.message shouldContain "G-21 phase 3"
        ex.message shouldContain "exactly one"
    }

    @Test
    fun `entry refuses a cited G-id that is blank`() {
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = validTable(),
                trigger = TriggerClaim.Cited(gapId = "", statement = "FIRES because x."),
            )
        }
        ex.message shouldContain "gapId"
        ex.message shouldContain "blank"
    }

    @Test
    fun `entry refuses a cited G-id that is all whitespace`() {
        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = validTable(),
                trigger = TriggerClaim.Cited(gapId = "   ", statement = "FIRES because x."),
            )
        }
        ex.message shouldContain "gapId"
        ex.message shouldContain "blank"
    }

    @Test
    fun `entry refuses a cited G-id whose statement states two verdict words`() {
        shouldThrow<FindingsRefusalException> {
            Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = validTable(),
                trigger = TriggerClaim.Cited(
                    gapId = "G-21 phase 3",
                    statement = "Could be read as FIRES or RETIRES depending on baseline.",
                ),
            )
        }
    }

    @Test
    fun `entry admits a cited G-id whose statement states exactly one verdict word`() {
        // Must not throw, for each of the three verdict words.
        for (verdict in listOf("FIRES", "RETIRES", "INCONCLUSIVE")) {
            val rendered = Findings.entry(
                date = "2026-08-18",
                subject = "x",
                results = validTable(),
                trigger = TriggerClaim.Cited(gapId = "G-21 phase 3", statement = "$verdict, because reasons."),
            )
            rendered shouldContain "Trigger: G-21 phase 3 — $verdict, because reasons."
        }
    }

    // ---------------------------------------------------------------------------
    // Rule 6 [BEN1-32]: an entry answering no trigger question is emitted only
    // explicitly marked incomplete, never presented as a finding.
    // ---------------------------------------------------------------------------

    @Test
    fun `entry with no trigger claim renders explicitly marked incomplete`() {
        val rendered = Findings.entry(date = "2026-08-18", subject = "x", results = validTable())
        rendered shouldContain "MARKED INCOMPLETE"
    }

    @Test
    fun `entry with no trigger claim is the default when trigger is not supplied`() {
        val withDefault = Findings.entry(date = "2026-08-18", subject = "x", results = validTable())
        val withExplicitNone = Findings.entry(
            date = "2026-08-18",
            subject = "x",
            results = validTable(),
            trigger = TriggerClaim.None,
        )
        withDefault shouldBe withExplicitNone
    }
}
