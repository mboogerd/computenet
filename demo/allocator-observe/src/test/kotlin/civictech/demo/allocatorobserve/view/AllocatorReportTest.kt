package civictech.demo.allocatorobserve.view

import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

private const val TOLERANCE = 1e-9

private fun Map<String, Double>.shouldHaveApprox(vararg expected: Pair<String, Double>) {
    keys shouldBe expected.map { it.first }.toSet()
    expected.forEach { (project, value) -> getValue(project) shouldBe (value plusOrMinus TOLERANCE) }
}

class AllocatorReportTest {

    private val decl6040 =
        AllocationDeclaration(weights = mapOf("CN" to 60.0, "GF" to 40.0), monthlyCapHours = 100.0, window = null)
    private val decl3070 =
        AllocationDeclaration(weights = mapOf("CN" to 30.0, "GF" to 70.0), monthlyCapHours = 100.0, window = null)

    // --- Feature example 1: single declaration over the whole window ---

    @Test
    fun `example 1 - single declaration in force over the whole window`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val t1 = Instant.parse("2026-04-01T01:00:00Z")
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t0, decl6040)))
        val window = TimeRange(t0, t1)

        val hours: (String, Instant, Instant) -> Double = { project, from, to ->
            if (from == t0 && to == t1) {
                when (project) {
                    "CN" -> 6.0
                    "GF" -> 4.0
                    else -> 0.0
                }
            } else {
                0.0
            }
        }

        val report = windowReport(window, timeline, setOf("CN", "GF"), hours)

        report.subIntervals.size shouldBe 1
        val sub = report.subIntervals[0]
        sub.declaration shouldBe decl6040
        sub.enactedShare.shouldHaveApprox("CN" to 0.6, "GF" to 0.4)
        sub.declaredShare.shouldHaveApprox("CN" to 0.6, "GF" to 0.4)
        sub.diff.shouldHaveApprox("CN" to 0.0, "GF" to 0.0)

        val cnDrift = report.perProject.getValue("CN")
        cnDrift.drift shouldBe (0.0 plusOrMinus TOLERANCE)
        cnDrift.residual shouldBe (0.0 plusOrMinus TOLERANCE)
        cnDrift.residualLabel shouldBe RESIDUAL_LABEL

        val gfDrift = report.perProject.getValue("GF")
        gfDrift.drift shouldBe (0.0 plusOrMinus TOLERANCE)
        gfDrift.residual shouldBe (0.0 plusOrMinus TOLERANCE)
        gfDrift.residualLabel shouldBe RESIDUAL_LABEL

        report.totalHours shouldBe (10.0 plusOrMinus TOLERANCE)
        report.beforeFirstDeclarationHours shouldBe emptyMap()
    }

    // --- Feature example 2: mid-window declaration change, unequal sub-intervals ---

    @Test
    fun `example 2 - mid-window declaration change diffs each sub-interval against the declaration then in force`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val tm = t0.plusSeconds(3600) // 1h sub-interval under 60/40
        val t1 = tm.plusSeconds(10800) // 3h sub-interval under 30/70
        val timeline =
            DeclarationTimeline(
                listOf(
                    DeclarationEvent(t0, decl6040),
                    DeclarationEvent(tm, decl3070),
                ),
            )
        val window = TimeRange(t0, t1)

        // Total across the window: CN 5h, GF 5h, split so each sub-interval's
        // total is 5h: all of CN's hours land in the first sub-interval (under
        // 60/40), all of GF's in the second (under 30/70).
        val hours: (String, Instant, Instant) -> Double = { project, from, to ->
            when {
                from == t0 && to == tm -> if (project == "CN") 5.0 else 0.0
                from == tm && to == t1 -> if (project == "GF") 5.0 else 0.0
                else -> 0.0
            }
        }

        val report = windowReport(window, timeline, setOf("CN", "GF"), hours)

        report.subIntervals.size shouldBe 2
        val first = report.subIntervals[0]
        first.declaration shouldBe decl6040
        first.enactedShare.shouldHaveApprox("CN" to 1.0, "GF" to 0.0)
        first.declaredShare.shouldHaveApprox("CN" to 0.6, "GF" to 0.4)
        first.diff.shouldHaveApprox("CN" to 0.4, "GF" to -0.4)

        val second = report.subIntervals[1]
        second.declaration shouldBe decl3070
        second.enactedShare.shouldHaveApprox("CN" to 0.0, "GF" to 1.0)
        second.declaredShare.shouldHaveApprox("CN" to 0.3, "GF" to 0.7)
        second.diff.shouldHaveApprox("CN" to -0.3, "GF" to 0.3)

        // Window-level: enactedShare 0.5/0.5 (5h/10h each); declaredShare is the
        // |I|-weighted mean over unequal sub-interval lengths (1h vs 3h, 4h total):
        // CN = (0.6*3600 + 0.3*10800) / 14400 = 0.375
        // GF = (0.4*3600 + 0.7*10800) / 14400 = 0.625
        val cn = report.perProject.getValue("CN")
        cn.enactedShare shouldBe (0.5 plusOrMinus TOLERANCE)
        cn.declaredShare shouldBe (0.375 plusOrMinus TOLERANCE)
        cn.drift shouldBe (0.125 plusOrMinus TOLERANCE)
        cn.residual shouldBe (0.125 plusOrMinus TOLERANCE)

        val gf = report.perProject.getValue("GF")
        gf.enactedShare shouldBe (0.5 plusOrMinus TOLERANCE)
        gf.declaredShare shouldBe (0.625 plusOrMinus TOLERANCE)
        gf.drift shouldBe (-0.125 plusOrMinus TOLERANCE)
        gf.residual shouldBe (-0.125 plusOrMinus TOLERANCE)

        report.totalHours shouldBe (10.0 plusOrMinus TOLERANCE)
    }

    // --- fpml.3-D8 gap: hours before the first declaration ---

    @Test
    fun `fpml_3-D8 gap - hours before the first declaration are reported separately and excluded from shares`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val t1 = Instant.parse("2026-04-01T01:00:00Z") // first declaration observed here
        val t2 = Instant.parse("2026-04-01T02:00:00Z")
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t1, decl6040)))
        val window = TimeRange(t0, t2)

        val hours: (String, Instant, Instant) -> Double = { project, from, to ->
            when {
                from == t0 && to == t1 -> if (project == "CN") 2.0 else 3.0 // uncovered gap
                from == t1 && to == t2 -> if (project == "CN") 6.0 else 4.0 // covered
                else -> 0.0
            }
        }

        val report = windowReport(window, timeline, setOf("CN", "GF"), hours)

        report.beforeFirstDeclarationHours.shouldHaveApprox("CN" to 2.0, "GF" to 3.0)

        // Only the covered sub-interval appears, and its arithmetic excludes the gap entirely.
        report.subIntervals.size shouldBe 1
        report.subIntervals[0].range shouldBe TimeRange(t1, t2)
        report.totalHours shouldBe (10.0 plusOrMinus TOLERANCE) // 6 + 4, NOT +2 +3 from the gap

        val cn = report.perProject.getValue("CN")
        cn.enactedShare shouldBe (0.6 plusOrMinus TOLERANCE)
        cn.declaredShare shouldBe (0.6 plusOrMinus TOLERANCE)
        cn.drift shouldBe (0.0 plusOrMinus TOLERANCE)
    }

    // --- project asymmetries: present in weights only, present in hours only ---

    @Test
    fun `a project in the weights with no hours has enactedShare 0 and its declared share`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val t1 = Instant.parse("2026-04-01T01:00:00Z")
        val decl = AllocationDeclaration(weights = mapOf("CN" to 60.0, "GF" to 40.0), monthlyCapHours = 100.0, window = null)
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t0, decl)))
        val window = TimeRange(t0, t1)

        val hours: (String, Instant, Instant) -> Double = { project, _, _ -> if (project == "CN") 10.0 else 0.0 }

        val report = windowReport(window, timeline, setOf("CN", "GF"), hours)

        val gf = report.perProject.getValue("GF")
        gf.enactedShare shouldBe (0.0 plusOrMinus TOLERANCE)
        gf.declaredShare shouldBe (0.4 plusOrMinus TOLERANCE)
    }

    @Test
    fun `a project with hours but absent from the weights has declaredShare 0`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val t1 = Instant.parse("2026-04-01T01:00:00Z")
        val decl = AllocationDeclaration(weights = mapOf("CN" to 100.0), monthlyCapHours = 100.0, window = null)
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t0, decl)))
        val window = TimeRange(t0, t1)

        val hours: (String, Instant, Instant) -> Double = { project, _, _ ->
            when (project) {
                "CN" -> 0.0
                "X" -> 10.0
                else -> 0.0
            }
        }

        val report = windowReport(window, timeline, setOf("CN", "X"), hours)

        val x = report.perProject.getValue("X")
        x.enactedShare shouldBe (1.0 plusOrMinus TOLERANCE)
        x.declaredShare shouldBe (0.0 plusOrMinus TOLERANCE)
        x.drift shouldBe (1.0 plusOrMinus TOLERANCE)

        val cn = report.perProject.getValue("CN")
        cn.enactedShare shouldBe (0.0 plusOrMinus TOLERANCE)
        cn.declaredShare shouldBe (1.0 plusOrMinus TOLERANCE)
    }

    // --- Feature example 3 / fpml.3-D9: cap tracking with linear projection ---

    @Test
    fun `example 3 - cap tracking at the exact UTC midpoint of April`() {
        val monthStart = Instant.parse("2026-04-01T00:00:00Z")
        val now = Instant.parse("2026-04-16T00:00:00Z") // exact midpoint: April has 30 days
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(monthStart, decl6040.copy(monthlyCapHours = 100.0))))

        val notReached = capReport(now, timeline, hoursToDate = 50.0)
        notReached.capHours shouldBe (100.0 plusOrMinus TOLERANCE)
        notReached.elapsedFraction shouldBe (0.5 plusOrMinus TOLERANCE)
        notReached.projectedMonthEndHours!! shouldBe (100.0 plusOrMinus TOLERANCE)
        notReached.projectionRule shouldBe "linear"
        notReached.capReached shouldBe false

        val reached = capReport(now, timeline, hoursToDate = 100.0)
        reached.capReached shouldBe true
    }

    @Test
    fun `capReport with no declaration in force has a null cap and still computes a projection`() {
        val now = Instant.parse("2026-04-16T00:00:00Z")
        val timeline = DeclarationTimeline(emptyList())

        val report = capReport(now, timeline, hoursToDate = 50.0)

        report.capHours shouldBe null
        report.capReached shouldBe false
        report.projectedMonthEndHours!! shouldBe (100.0 plusOrMinus TOLERANCE)
        report.projectionRule shouldBe "linear"
    }

    // --- fpml.3-D1: UTC month boundaries ---

    @Test
    fun `fpml_3-D1 - instants either side of a UTC month boundary fall in different months`() {
        val timeline = DeclarationTimeline(emptyList())
        val endOfFebruary = Instant.parse("2026-02-28T23:59:59Z")
        val startOfMarch = Instant.parse("2026-03-01T00:00:00Z")

        val febReport = capReport(endOfFebruary, timeline, hoursToDate = 0.0)
        val marReport = capReport(startOfMarch, timeline, hoursToDate = 0.0)

        febReport.monthStart shouldBe Instant.parse("2026-02-01T00:00:00Z")
        febReport.monthEnd shouldBe Instant.parse("2026-03-01T00:00:00Z")

        marReport.monthStart shouldBe Instant.parse("2026-03-01T00:00:00Z")
        marReport.monthEnd shouldBe Instant.parse("2026-04-01T00:00:00Z") // March has 31 days
    }

    // --- fpml.3-D3 edge: now == monthStart ---

    @Test
    fun `fpml_3-D3 edge - now equal to monthStart has elapsedFraction 0 and no projection`() {
        val monthStart = Instant.parse("2026-04-01T00:00:00Z")
        val timeline = DeclarationTimeline(emptyList())

        val report = capReport(monthStart, timeline, hoursToDate = 0.0)

        report.elapsedFraction shouldBe (0.0 plusOrMinus TOLERANCE)
        report.projectedMonthEndHours shouldBe null
        report.projectionRule shouldBe "linear"
    }

    @Test
    fun `TimeRange requires from less than or equal to to`() {
        val t0 = Instant.parse("2026-04-01T00:00:00Z")
        val t1 = Instant.parse("2026-04-01T01:00:00Z")
        runCatching { TimeRange(t1, t0) }.isFailure shouldBe true
        TimeRange(t0, t0) // from == to is allowed
    }
}
