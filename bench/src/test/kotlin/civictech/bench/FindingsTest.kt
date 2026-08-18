package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [Findings.entry]'s five refusal rules (epic section 5 "Findings file",
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

    private fun validTable(): FindingsTable = FindingsTable(listOf(reportableResult()))

    // ---------------------------------------------------------------------------
    // Control: a fully valid entry renders and carries every template piece.
    // ---------------------------------------------------------------------------

    @Test
    fun `a complete valid entry renders the full template`() {
        val rendered = Findings.entry(
            date = "2026-08-18",
            subject = "OrMapCell insert/retract throughput",
            results = validTable(),
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
        rendered shouldContain "| subject | insert (ops/s ± err) | retract (ops/s ± err) | notes |"
        rendered shouldContain "Trigger: G-21 phase 3 — FIRES, because retract throughput regressed 40%."
    }

    // ---------------------------------------------------------------------------
    // Rule 1 [BEN1-25] (BS-11 writer half): Unreportable results are refused, and
    // the refusal names the refused result.
    // ---------------------------------------------------------------------------

    @Test
    fun `entry refuses a table containing an Unreportable result`() {
        val bad = unreportableResult()
        val table = FindingsTable(listOf(reportableResult(), bad))

        val ex = shouldThrow<FindingsRefusalException> {
            Findings.entry(date = "2026-08-18", subject = "x", results = table)
        }
        ex.message shouldContain "Unreportable"
        // The message must name THIS result, not just say "some result is bad".
        ex.message shouldContain "value=${bad.value}"
        ex.message shouldContain "dispersion=${bad.dispersion}"
    }

    @Test
    fun `entry admits a table of only Reportable results`() {
        val table = FindingsTable(listOf(reportableResult(), reportableResult()))
        // Must not throw.
        Findings.entry(date = "2026-08-18", subject = "x", results = table)
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
        results.none { it == List::class.java || it == Collection::class.java } shouldBe true
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
    // Rule 3 [BEN1-30]: an entry missing date, subject, or results table is
    // refused as incomplete.
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

    // ---------------------------------------------------------------------------
    // Rule 4 [BEN1-31]: a cited G-id must state exactly one of
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
    // Rule 5 [BEN1-32]: an entry answering no trigger question is emitted only
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
