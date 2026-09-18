package civictech.demo.allocatorobserve.oracle

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * [ReferenceReport] against **hand-derived** numbers.
 *
 * Every expected value below was worked out on paper from the fixture week
 * (design entry fpml.5-D9) and the conventions written up in this module's
 * README; none of it is produced by running any code in this repository. That is
 * what makes this suite a check on the reference rather than a snapshot of it —
 * and the reference is in turn what checks the served views, so a snapshot here
 * would hollow out the whole oracle.
 *
 * The derivations are kept in comments next to the assertions they justify, so
 * a later change to the conventions has to confront the arithmetic rather than
 * re-record it.
 */
class ReferenceReportTest {

    private val tolerance = 1e-6

    private fun report(
        lines: List<String> = LINES,
        history: List<DeclarationSpec> = HISTORY,
        now: Instant = NOW,
        window: Duration = WINDOW,
    ): JsonObject = ReferenceReport.compute(lines, history, now, window)

    private fun JsonElement.d(vararg path: String): Double = at(*path).jsonPrimitive.content.toDouble()

    private fun JsonElement.at(vararg path: String): JsonElement =
        path.fold(this) { element, key -> element.jsonObject.getValue(key) }

    private infix fun Double.shouldBeNear(expected: Double) {
        val delta = kotlin.math.abs(this - expected)
        if (delta > tolerance) error("expected $expected, got $this (delta $delta > $tolerance)")
    }

    @Test
    fun `the fixture week yields the hand-derived sub-interval numbers`() {
        val doc = report()

        doc.at("publishedAt").jsonPrimitive.content shouldBe "2026-08-15T00:00:00Z"
        doc.at("window", "window", "from").jsonPrimitive.content shouldBe "2026-08-08T00:00:00Z"
        doc.at("window", "window", "to").jsonPrimitive.content shouldBe "2026-08-15T00:00:00Z"

        val subIntervals = doc.at("window", "subIntervals").jsonArray
        subIntervals.size shouldBe 2

        // Sub-interval 0 = [08-08T00:00Z, 08-12T00:00Z), declaration D1 (60/40).
        // computenet: r1 3h + r3 4h + r5 2h                       = 9h
        // glass-factory: r2 2h + r4 2h + r6's 08-11T20:00..08-12T00:00 = 8h
        // total 17h; enacted 9/17 = 0.5294117647, 8/17 = 0.4705882353
        // declared 60/100 = 0.6, 40/100 = 0.4; diff = enacted - declared
        val first = subIntervals[0]
        first.at("range", "from").jsonPrimitive.content shouldBe "2026-08-08T00:00:00Z"
        first.at("range", "to").jsonPrimitive.content shouldBe "2026-08-12T00:00:00Z"
        first.d("enactedHours", "computenet") shouldBeNear 9.0
        first.d("enactedHours", "glass-factory") shouldBeNear 8.0
        first.d("enactedShare", "computenet") shouldBeNear 0.529412
        first.d("enactedShare", "glass-factory") shouldBeNear 0.470588
        first.d("declaredShare", "computenet") shouldBeNear 0.6
        first.d("declaredShare", "glass-factory") shouldBeNear 0.4
        first.d("diff", "computenet") shouldBeNear -0.070588
        first.d("diff", "glass-factory") shouldBeNear 0.070588
        // The declaration is echoed as written: raw weights, not shares.
        first.d("declaration", "weights", "computenet") shouldBeNear 60.0
        first.d("declaration", "weights", "glass-factory") shouldBeNear 40.0
        first.d("declaration", "monthlyCapHours") shouldBeNear 100.0
        first.at("declaration", "window") shouldBe JsonNull

        // Sub-interval 1 = [08-12T00:00Z, 08-15T00:00Z), declaration D2 (30/70).
        // computenet: r7 2h + r10 1h                               = 3h
        // glass-factory: r6's 08-12T00:00..02:00 2h + r8 6h + r9 5h = 13h
        // total 16h; enacted 3/16 = 0.1875, 13/16 = 0.8125
        // declared 0.3 / 0.7; diff -0.1125 / +0.1125
        val second = subIntervals[1]
        second.at("range", "from").jsonPrimitive.content shouldBe "2026-08-12T00:00:00Z"
        second.at("range", "to").jsonPrimitive.content shouldBe "2026-08-15T00:00:00Z"
        second.d("enactedHours", "computenet") shouldBeNear 3.0
        second.d("enactedHours", "glass-factory") shouldBeNear 13.0
        second.d("enactedShare", "computenet") shouldBeNear 0.1875
        second.d("enactedShare", "glass-factory") shouldBeNear 0.8125
        second.d("declaredShare", "computenet") shouldBeNear 0.3
        second.d("declaredShare", "glass-factory") shouldBeNear 0.7
        second.d("diff", "computenet") shouldBeNear -0.1125
        second.d("diff", "glass-factory") shouldBeNear 0.1125
    }

    @Test
    fun `the fixture week yields the hand-derived per-project drift and cap`() {
        val doc = report()

        // perProject, over the whole covered window (168h, fully covered):
        // computenet enacted 9 + 3 = 12h of 33h total = 0.3636363636
        //   declared = duration-weighted mean = (0.6 * 96h + 0.3 * 72h) / 168h
        //            = (57.6 + 21.6) / 168 = 79.2/168 = 0.4714285714
        //   drift = 0.3636363636 - 0.4714285714 = -0.1077922078
        // glass-factory 8 + 13 = 21h = 0.6363636364
        //   declared = (0.4 * 96 + 0.7 * 72) / 168 = (38.4 + 50.4)/168 = 0.5285714286
        //   drift = +0.1077922078
        doc.d("window", "perProject", "computenet", "enactedHours") shouldBeNear 12.0
        doc.d("window", "perProject", "computenet", "enactedShare") shouldBeNear 0.363636
        doc.d("window", "perProject", "computenet", "declaredShare") shouldBeNear 0.471429
        doc.d("window", "perProject", "computenet", "drift") shouldBeNear -0.107792
        doc.d("window", "perProject", "computenet", "residual") shouldBeNear -0.107792
        doc.d("window", "perProject", "glass-factory", "enactedHours") shouldBeNear 21.0
        doc.d("window", "perProject", "glass-factory", "enactedShare") shouldBeNear 0.636364
        doc.d("window", "perProject", "glass-factory", "declaredShare") shouldBeNear 0.528571
        doc.d("window", "perProject", "glass-factory", "drift") shouldBeNear 0.107792
        doc.d("window", "perProject", "glass-factory", "residual") shouldBeNear 0.107792

        // Starvation attribution is unavailable (fpml.3-D4): the whole drift is
        // the residual, under a fixed label that is part of the exchange shape.
        doc.at("window", "perProject", "computenet", "residualLabel").jsonPrimitive.content shouldBe
            "starvation attribution unavailable: no socaity draw-exclusion record shape is pinned"

        doc.d("window", "totalHours") shouldBeNear 33.0
        // The window starts exactly at D1's observedAt, so nothing precedes the
        // first declaration.
        doc.at("window", "beforeFirstDeclarationHours").jsonObject.size shouldBe 0

        // Cap: August 2026 is 744h, 336h elapsed at now, so 336/744 = 0.4516129032.
        // Every fixture record falls inside [08-01, 08-15), so hoursToDate = 33.
        // Projected = 33 / 0.4516129032 = 33 * 744 / 336 = 73.0714285714.
        doc.at("cap", "monthStart").jsonPrimitive.content shouldBe "2026-08-01T00:00:00Z"
        doc.at("cap", "monthEnd").jsonPrimitive.content shouldBe "2026-09-01T00:00:00Z"
        doc.at("cap", "now").jsonPrimitive.content shouldBe "2026-08-15T00:00:00Z"
        doc.d("cap", "capHours") shouldBeNear 100.0
        doc.d("cap", "hoursToDate") shouldBeNear 33.0
        doc.d("cap", "elapsedFraction") shouldBeNear 0.451613
        doc.d("cap", "projectedMonthEndHours") shouldBeNear 73.071429
        doc.at("cap", "projectionRule").jsonPrimitive.content shouldBe "linear"
        doc.at("cap", "capReached").jsonPrimitive.content shouldBe "false"

        doc.at("unattributable").jsonObject.size shouldBe 0
        doc.at("unattributableRecords").jsonArray.size shouldBe 0
    }

    @Test
    fun `lines that are not v1 records are excluded and change nothing`() {
        val noise =
            listOf(
                """{"v":2,"project":"computenet","machine":"m1","work_item":"wX",""" +
                    """"started":"2026-08-09T00:00:00Z","ended":"2026-08-09T05:00:00Z"}""",
                "not json",
            )
        val doc = report(lines = LINES + noise)

        doc shouldBe report()
    }

    @Test
    fun `a record with an unparseable ended contributes no hours and is listed`() {
        val broken =
            """{"v":1,"project":"computenet","machine":"m1","work_item":"wBad",""" +
                """"started":"2026-08-09T00:00:00Z","ended":"x"}"""
        val doc = report(lines = LINES + broken)

        // It is a v1 record, so it is accounted for — but it carries no hours,
        // so every number of the straight-through week is unchanged.
        doc.d("window", "totalHours") shouldBeNear 33.0
        doc.d("window", "perProject", "computenet", "enactedHours") shouldBeNear 12.0
        doc.d("cap", "hoursToDate") shouldBeNear 33.0

        doc.at("unattributable", "UNPARSEABLE_ENDED").jsonPrimitive.content shouldBe "1"
        val listed = doc.at("unattributableRecords").jsonArray
        listed.size shouldBe 1
        listed[0].at("workItem").jsonPrimitive.content shouldBe "wBad"
        listed[0].at("ended").jsonPrimitive.content shouldBe "x"
    }

    @Test
    fun `hours before the first declaration are reported apart and excluded from shares`() {
        // now = 2026-08-14T00:00Z with a 168h window => window
        // [2026-08-07T00:00Z, 2026-08-14T00:00Z), which opens a full day before
        // D1 is observed at 08-08T00:00Z. r0 falls in that uncovered day.
        val r0 =
            """{"v":1,"project":"computenet","machine":"m1","work_item":"w0",""" +
                """"started":"2026-08-07T10:00:00Z","ended":"2026-08-07T12:00:00Z"}"""
        val doc = report(lines = listOf(r0) + LINES, now = Instant.parse("2026-08-14T00:00:00Z"))

        // Hand-derived for this window:
        // uncovered [08-07, 08-08): computenet 2h (r0), nothing else.
        // sub-interval 0 [08-08, 08-12) is unchanged: CN 9h, GF 8h, total 17h.
        // sub-interval 1 [08-12, 08-14): CN r7 2h (r10 is at 08-14T09:00, past
        //   the window); GF r6's 2h + r8 6h + r9 5h = 13h; total 15h.
        //   enacted 2/15 = 0.1333333, 13/15 = 0.8666667; declared 0.3/0.7.
        // perProject over covered 144h (96h under D1, 48h under D2):
        //   CN 9 + 2 = 11h of 32h = 0.34375
        //     declared (0.6*96 + 0.3*48)/144 = 72/144 = 0.5; drift -0.15625
        //   GF 8 + 13 = 21h of 32h = 0.65625
        //     declared (0.4*96 + 0.7*48)/144 = 72/144 = 0.5; drift +0.15625
        // cap: hoursToDate over [08-01, 08-14) = 2 (r0) + 32 (r1..r9) = 34h;
        //   elapsed 13/31 days = 312/744 = 0.4193548387;
        //   projected = 34 * 744 / 312 = 81.0769230769.
        doc.d("window", "beforeFirstDeclarationHours", "computenet") shouldBeNear 2.0
        doc.at("window", "beforeFirstDeclarationHours").jsonObject.size shouldBe 1

        doc.d("window", "totalHours") shouldBeNear 32.0
        doc.d("window", "perProject", "computenet", "enactedHours") shouldBeNear 11.0
        doc.d("window", "perProject", "computenet", "enactedShare") shouldBeNear 0.34375
        doc.d("window", "perProject", "computenet", "declaredShare") shouldBeNear 0.5
        doc.d("window", "perProject", "computenet", "drift") shouldBeNear -0.15625
        doc.d("window", "perProject", "glass-factory", "enactedHours") shouldBeNear 21.0
        doc.d("window", "perProject", "glass-factory", "enactedShare") shouldBeNear 0.65625
        doc.d("window", "perProject", "glass-factory", "declaredShare") shouldBeNear 0.5
        doc.d("window", "perProject", "glass-factory", "drift") shouldBeNear 0.15625

        val subIntervals = doc.at("window", "subIntervals").jsonArray
        subIntervals.size shouldBe 2
        subIntervals[1].d("enactedHours", "computenet") shouldBeNear 2.0
        subIntervals[1].d("enactedShare", "computenet") shouldBeNear 0.133333
        subIntervals[1].d("enactedShare", "glass-factory") shouldBeNear 0.866667

        doc.d("cap", "hoursToDate") shouldBeNear 34.0
        doc.d("cap", "elapsedFraction") shouldBeNear 0.419355
        doc.d("cap", "projectedMonthEndHours") shouldBeNear 81.076923
    }

    @Test
    fun `the re-baselined week corrects computenet's hours`() {
        // The fixture's final content after the mid-week replacement (r5 ends at
        // 12:00 instead of 11:00, so computenet gains one hour under D1):
        // sub-interval 0: CN 10h, GF 8h, total 18h -> 0.5555556 / 0.4444444.
        // perProject: CN 13h of 34h = 0.3823529, declared 0.4714285714,
        //   drift -0.0890756; GF 21h = 0.6176471, drift +0.0890756.
        // cap: hoursToDate 34, projected 34 * 744 / 336 = 75.2857142857.
        val doc = report(lines = REBASELINE_FINAL_LINES)

        val first = doc.at("window", "subIntervals").jsonArray[0]
        first.d("enactedHours", "computenet") shouldBeNear 10.0
        first.d("enactedShare", "computenet") shouldBeNear 0.555556
        first.d("enactedShare", "glass-factory") shouldBeNear 0.444444

        doc.d("window", "perProject", "computenet", "enactedHours") shouldBeNear 13.0
        doc.d("window", "perProject", "computenet", "enactedShare") shouldBeNear 0.382353
        doc.d("window", "perProject", "computenet", "drift") shouldBeNear -0.089076
        doc.d("window", "perProject", "glass-factory", "enactedShare") shouldBeNear 0.617647
        doc.d("window", "perProject", "glass-factory", "drift") shouldBeNear 0.089076
        doc.d("cap", "hoursToDate") shouldBeNear 34.0
        doc.d("cap", "projectedMonthEndHours") shouldBeNear 75.285714
    }
}
