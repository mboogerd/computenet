package civictech.demo.allocatorobserve.view

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Wiring outcomes for [AllocatorReportViews] (task `computenet-fpml.3.3`,
 * feature `computenet-fpml.3`). The arithmetic these tests read off the
 * report is `SessionLedger`'s and `AllocatorReport.kt`'s and is pinned by
 * their own suites; what is asserted here is that the two live cells, the
 * private fold, and the publish boundary produce it END TO END — through
 * `records.inlet.call.add(...)` and `declarations.inlet.call.add(...)`, never
 * by calling the derivations directly.
 *
 * Four properties carry the feature's rule 3 (fpml.3-D5):
 *
 * - `publish` is the only thing that moves what a reader sees — the
 *   single-threaded form of the feature's fourth example;
 * - a reader spinning against a writer sees only whole reports — its
 *   concurrent form, asserted as a SAFETY property (every observation is one
 *   of exactly two legal reports) with a bounded join and no sleeps, never as
 *   a schedule;
 * - attaching to already-populated cells yields the same report as folding
 *   every delta live (restart equivalence, through `streamTo`'s catch-up);
 * - folding record-by-record with a re-baseline in the middle yields the same
 *   report as a fresh instance over the final membership (incremental equals
 *   batch, over five recorded seeds).
 */
class AllocatorReportViewsTest {

    private companion object {
        const val CN = "computenet"
        const val GF = "glass-factory"

        /** The clock every fixture reads; the window is the 24h ending here. */
        val NOW: Instant = Instant.parse("2026-08-15T12:00:00Z")

        val WINDOW: Duration = Duration.ofHours(24)

        /** Before every window these tests use, so the whole window is covered. */
        val EARLY: Instant = Instant.parse("2026-08-01T00:00:00Z")

        val TOLERANCE = 1e-9

        /** Seeds for the incremental-equals-batch property, recorded rather than tuned. */
        val SEEDS = listOf(1L, 2L, 3L, 4L, 5L)

        /**
         * The `[fixture]` generator's rolling-window and UTC-month boundaries
         * (computenet-1kuib). Recorded here so the fixed boundary-straddling
         * sessions it constructs, and the count-based assertion that pins
         * them, share one source of truth with the window/month arithmetic
         * they target rather than re-deriving it.
         */
        val WINDOW_FROM: Instant = NOW.minus(WINDOW)
        val MONTH_START: Instant = Instant.parse("2026-08-01T00:00:00Z")

        /** The minimum straddling sessions [fixture] must emit BY CONSTRUCTION, per kind, per seed. */
        const val MIN_STRADDLERS_PER_KIND = 1

        const val CONCURRENT_ROUNDS = 400
        const val JOIN_TIMEOUT_MS = 60_000L
        const val MAX_REPORTED_FAILURES = 8
    }

    private fun declaration(
        computenet: Double,
        glassFactory: Double,
        capHours: Double = 100.0,
    ) = AllocationDeclaration(
        weights = mapOf(CN to computenet, GF to glassFactory),
        monthlyCapHours = capHours,
        window = null,
    )

    private fun record(
        project: String,
        started: String,
        ended: String,
        workItem: String,
    ) = SpendRecord(v = 1, project = project, machine = "m1", workItem = workItem, started = started, ended = ended)

    private class Rig(
        val records: SetCell<SpendRecord>,
        val declarations: SetCell<DeclarationEvent>,
        val views: AllocatorReportViews,
    ) {
        fun add(record: SpendRecord) = records.inlet.call.add(record)

        fun remove(record: SpendRecord) = records.inlet.call.remove(record)

        fun declare(event: DeclarationEvent) = declarations.inlet.call.add(event)
    }

    /** Views attached to two empty cells BEFORE anything is fed — the live-fold path. */
    private fun rig(now: Instant = NOW, windowLength: Duration = WINDOW): Rig {
        val records = SetCell<SpendRecord>()
        val declarations = SetCell<DeclarationEvent>()
        val views = AllocatorReportViews.derivedFrom(records, declarations, windowLength, now = { now })
        return Rig(records, declarations, views)
    }

    // ------------------------------------------------------------------
    // the feature's examples 1-3, end to end through the cells
    // ------------------------------------------------------------------

    @Test
    fun `example 1 - 6h and 4h under a 60-40 declaration report enacted 0_6-0_4 at zero drift`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        rig.add(record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w1"))
        rig.add(record(GF, "2026-08-14T20:00:00Z", "2026-08-15T00:00:00Z", "w2"))

        rig.views.publish()
        val report = rig.views.current().shouldNotBeNull()

        report.publishedAt shouldBe NOW
        report.window.window shouldBe TimeRange(NOW.minus(WINDOW), NOW)
        report.window.totalHours shouldBe (10.0 plusOrMinus TOLERANCE)

        val cn = report.window.perProject.getValue(CN)
        cn.enactedHours shouldBe (6.0 plusOrMinus TOLERANCE)
        cn.enactedShare shouldBe (0.6 plusOrMinus TOLERANCE)
        cn.declaredShare shouldBe (0.6 plusOrMinus TOLERANCE)
        cn.drift shouldBe (0.0 plusOrMinus TOLERANCE)

        val gf = report.window.perProject.getValue(GF)
        gf.enactedShare shouldBe (0.4 plusOrMinus TOLERANCE)
        gf.declaredShare shouldBe (0.4 plusOrMinus TOLERANCE)
        gf.drift shouldBe (0.0 plusOrMinus TOLERANCE)

        // fpml.3-D4: the residual is the drift, labelled, never an invented
        // draw-exclusion record.
        report.window.perProject.values.forEach { drift ->
            drift.residual shouldBe (drift.drift plusOrMinus TOLERANCE)
            drift.residualLabel shouldBe RESIDUAL_LABEL
        }
    }

    @Test
    fun `example 2 - a mid-window declaration change diffs each sub-interval against the declaration then in force`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        rig.declare(DeclarationEvent(Instant.parse("2026-08-15T00:00:00Z"), declaration(30.0, 70.0)))
        // 5h of computenet before the change, 5h of glass-factory after it.
        rig.add(record(CN, "2026-08-14T13:00:00Z", "2026-08-14T18:00:00Z", "w1"))
        rig.add(record(GF, "2026-08-15T01:00:00Z", "2026-08-15T06:00:00Z", "w2"))

        val report = rig.views.publish()

        report.window.subIntervals.size shouldBe 2

        val first = report.window.subIntervals[0]
        first.range shouldBe TimeRange(NOW.minus(WINDOW), Instant.parse("2026-08-15T00:00:00Z"))
        first.declaration shouldBe declaration(60.0, 40.0)
        first.enactedHours.getValue(CN) shouldBe (5.0 plusOrMinus TOLERANCE)
        first.enactedHours.getValue(GF) shouldBe (0.0 plusOrMinus TOLERANCE)
        first.diff.getValue(CN) shouldBe (0.4 plusOrMinus TOLERANCE)
        first.diff.getValue(GF) shouldBe (-0.4 plusOrMinus TOLERANCE)

        val second = report.window.subIntervals[1]
        second.range shouldBe TimeRange(Instant.parse("2026-08-15T00:00:00Z"), NOW)
        second.declaration shouldBe declaration(30.0, 70.0)
        second.enactedHours.getValue(GF) shouldBe (5.0 plusOrMinus TOLERANCE)
        second.diff.getValue(CN) shouldBe (-0.3 plusOrMinus TOLERANCE)
        second.diff.getValue(GF) shouldBe (0.3 plusOrMinus TOLERANCE)
    }

    @Test
    fun `example 3 - 50h against a 100h cap at UTC mid-month projects 100h under the linear rule`() {
        // 2026-08 is 31 days; 15.5 days in is exactly half the month elapsed.
        val midMonth = Instant.parse("2026-08-16T12:00:00Z")
        val rig = rig(now = midMonth)
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0, capHours = 100.0)))
        // One 50h session, wholly inside the month and before `now`.
        rig.add(record(CN, "2026-08-02T00:00:00Z", "2026-08-04T02:00:00Z", "w1"))

        val cap = rig.views.publish().cap

        cap.monthStart shouldBe EARLY
        cap.monthEnd shouldBe Instant.parse("2026-09-01T00:00:00Z")
        cap.now shouldBe midMonth
        cap.capHours shouldBe 100.0
        cap.hoursToDate shouldBe (50.0 plusOrMinus TOLERANCE)
        cap.elapsedFraction shouldBe (0.5 plusOrMinus TOLERANCE)
        cap.projectedMonthEndHours.shouldNotBeNull() shouldBe (100.0 plusOrMinus TOLERANCE)
        cap.projectionRule shouldBe PROJECTION_RULE
        cap.capReached shouldBe false
    }

    // ------------------------------------------------------------------
    // the feature's example 4 - rule 3, the batch boundary (fpml.3-D5)
    // ------------------------------------------------------------------

    @Test
    fun `example 4 single-threaded - a batch folded without publish moves nothing a reader can see`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        rig.add(record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w0"))

        val prebatch = rig.views.publish()
        prebatch.window.totalHours shouldBe (6.0 plusOrMinus TOLERANCE)

        batchOfThree().forEach(rig::add)

        // Not merely equal: the SAME instance. Nothing the fold did is observable.
        rig.views.current().shouldNotBeNull() shouldBeSameInstanceAs prebatch

        val postbatch = rig.views.publish()
        postbatch shouldNotBe prebatch
        postbatch.window.totalHours shouldBe (9.0 plusOrMinus TOLERANCE)
        rig.views.current().shouldNotBeNull() shouldBeSameInstanceAs postbatch
    }

    /** Three one-hour glass-factory sessions inside the window — the batch. */
    private fun batchOfThree(): List<SpendRecord> =
        listOf(
            record(GF, "2026-08-15T01:00:00Z", "2026-08-15T02:00:00Z", "b1"),
            record(GF, "2026-08-15T03:00:00Z", "2026-08-15T04:00:00Z", "b2"),
            record(GF, "2026-08-15T05:00:00Z", "2026-08-15T06:00:00Z", "b3"),
        )

    @Test
    fun `example 4 concurrent - every observation of a moving report is one of exactly two legal reports`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        rig.add(record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w0"))
        val batch = batchOfThree()

        // The two legal states, established before any thread starts: the
        // writer alternates between exactly these.
        val without = rig.views.publish()
        batch.forEach(rig::add)
        val with = rig.views.publish()
        batch.forEach(rig::remove)
        val backAgain = rig.views.publish()

        with shouldNotBe without
        // The states are reproducible, which is what makes "one of exactly
        // two" a meaningful assertion rather than a tautology.
        backAgain shouldBe without

        val start = CountDownLatch(1)
        val writerDone = AtomicBoolean(false)
        val observations = AtomicLong(0)
        val failures = ConcurrentLinkedQueue<String>()

        fun report(message: String) {
            if (failures.size < MAX_REPORTED_FAILURES) failures += message
        }

        val writer = Thread({
            try {
                start.await()
                repeat(CONCURRENT_ROUNDS) { round ->
                    if (round % 2 == 0) batch.forEach(rig::add) else batch.forEach(rig::remove)
                    rig.views.publish()
                }
            } catch (t: Throwable) {
                report("writer threw ${t::class.qualifiedName}: ${t.message}")
            } finally {
                writerDone.set(true)
            }
        }, "fpml33-writer")

        val reader = Thread({
            try {
                start.await()
                while (!writerDone.get()) {
                    val seen = rig.views.current()
                    observations.incrementAndGet()
                    if (seen != without && seen != with) {
                        report(
                            "observed a report that is neither legal state: totalHours=" +
                                "${seen?.window?.totalHours} publishedAt=${seen?.publishedAt}",
                        )
                    }
                }
            } catch (t: Throwable) {
                report("reader threw ${t::class.qualifiedName}: ${t.message}")
            }
        }, "fpml33-reader")

        writer.start()
        reader.start()
        start.countDown()
        writer.join(JOIN_TIMEOUT_MS)
        reader.join(JOIN_TIMEOUT_MS)

        writer.isAlive shouldBe false
        reader.isAlive shouldBe false
        failures.toList() shouldBe emptyList<String>()
        // The reader really ran against a moving writer.
        (observations.get() > 0) shouldBe true
    }

    // ------------------------------------------------------------------
    // restart equivalence: a late attach catches up through streamTo
    // ------------------------------------------------------------------

    @Test
    fun `views attached to already-populated cells publish the same report as views folded live`() {
        val declarationEvents =
            listOf(
                DeclarationEvent(EARLY, declaration(60.0, 40.0)),
                DeclarationEvent(Instant.parse("2026-08-15T00:00:00Z"), declaration(30.0, 70.0)),
            )
        val spendRecords =
            listOf(
                record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w1"),
                record(GF, "2026-08-15T01:00:00Z", "2026-08-15T06:00:00Z", "w2"),
                record(CN, "not-a-timestamp", "2026-08-15T06:00:00Z", "w3"),
            )

        val live = rig()
        declarationEvents.forEach(live::declare)
        spendRecords.forEach(live::add)
        val liveReport = live.views.publish()

        // The resume path: both cells are populated first, the views attach
        // afterwards, and `streamTo`'s on-link catch-up delivers the whole
        // tag state as one delta-from-empty.
        val records = SetCell<SpendRecord>()
        val declarations = SetCell<DeclarationEvent>()
        spendRecords.forEach { records.inlet.call.add(it) }
        declarationEvents.forEach { declarations.inlet.call.add(it) }
        val late = AllocatorReportViews.derivedFrom(records, declarations, WINDOW, now = { NOW })
        val lateReport = late.publish()

        lateReport shouldBe liveReport
        // The catch-up really carried state; an empty fold would report zero.
        lateReport.window.totalHours shouldBe (11.0 plusOrMinus TOLERANCE)
        lateReport.unattributableRecords shouldBe setOf(spendRecords[2])
    }

    // ------------------------------------------------------------------
    // incremental equals batch (seeded, deterministic)
    // ------------------------------------------------------------------

    @Test
    fun `folding record by record with a re-baseline equals a fresh instance over the final membership`() {
        SEEDS.forEach { seed ->
            withClue("seed=$seed") {
                val fixture = fixture(seed)

                val incremental = rig()
                fixture.declarations.forEach(incremental::declare)
                fixture.records.forEach(incremental::add)
                // A re-baseline-style pass: take a subset out, put most of it
                // back, and leave the rest removed.
                fixture.removed.forEach(incremental::remove)
                fixture.reAdded.forEach(incremental::add)
                val incrementalReport = incremental.views.publish()

                val records = SetCell<SpendRecord>()
                val declarations = SetCell<DeclarationEvent>()
                fixture.finalMembership.forEach { records.inlet.call.add(it) }
                fixture.declarations.forEach { declarations.inlet.call.add(it) }
                val batch = AllocatorReportViews.derivedFrom(records, declarations, WINDOW, now = { NOW })
                val batchReport = batch.publish()

                incrementalReport shouldBe batchReport
                // Non-vacuity: the fixture straddles the window and month
                // boundaries and carries unparseable records, so neither half
                // of the equality is trivially empty.
                (incrementalReport.window.totalHours > 0.0) shouldBe true
                (incrementalReport.unattributableRecords.isNotEmpty()) shouldBe true

                // computenet-1kuib: the straddling cases above must arise BY
                // CONSTRUCTION, not merely by chance of the random draw — so
                // count them directly off the final membership rather than
                // trusting the random generator to have produced them. A
                // generator change that drops the constructed straddlers (see
                // `fixture`) must fail this, not pass silently.
                val validSessions =
                    fixture.finalMembership.mapNotNull { (sessionOf(it) as? SessionParse.Valid)?.session }
                val windowStraddlers =
                    validSessions.count { it.started.isBefore(WINDOW_FROM) && it.ended.isAfter(WINDOW_FROM) }
                val monthStraddlers =
                    validSessions.count { it.started.isBefore(MONTH_START) && it.ended.isAfter(MONTH_START) }
                withClue("window-straddling sessions (seed=$seed): $windowStraddlers") {
                    (windowStraddlers >= MIN_STRADDLERS_PER_KIND) shouldBe true
                }
                withClue("month-straddling sessions (seed=$seed): $monthStraddlers") {
                    (monthStraddlers >= MIN_STRADDLERS_PER_KIND) shouldBe true
                }
            }
        }
    }

    private class Fixture(
        val records: List<SpendRecord>,
        val declarations: List<DeclarationEvent>,
        val removed: List<SpendRecord>,
        val reAdded: List<SpendRecord>,
        val finalMembership: List<SpendRecord>,
    )

    /**
     * 200 generated records spread across a span that straddles both the UTC
     * month start and the window start, about one in ten of them
     * unattributable (fpml.3-D6), under a three-event declaration history —
     * plus two fixed sessions, [windowStraddler] and [monthStraddler],
     * appended after the random draw so they are never subject to removal.
     *
     * The random draw alone puts a window- or month-straddling session in the
     * fixture only incidentally (computenet-1kuib: measured at seed 5, the
     * random draw alone produces zero month-straddlers). The two appended
     * sessions guarantee at least one of each kind BY CONSTRUCTION, for every
     * seed, independent of what the random draw happens to produce — pinned
     * by the count-based assertion in the test above.
     *
     * Every record gets a distinct `ended` instant (the index is added as
     * nanoseconds). That is deliberate: `SessionLedger` indexes sessions by
     * `ended` and sums `Double` nanos in index order, so sessions sharing an
     * instant share a bucket whose internal order differs between an
     * incremental fold and a batch one — a floating-point difference that has
     * nothing to do with the property under test. The appended sessions keep
     * this: their `ended` instants are one second apart and outside the
     * random draw's span, so neither shares a bucket with a generated session
     * or with each other.
     */
    private fun fixture(seed: Long): Fixture {
        val random = Random(seed)
        val projects = listOf(CN, GF, "socaity")
        val spanStart = Instant.parse("2026-07-25T00:00:00Z")
        val spanSeconds = Duration.between(spanStart, NOW).seconds

        val records =
            (0 until 200).map { i ->
                val started = spanStart.plusSeconds(random.nextLong(spanSeconds))
                val ended = started.plusSeconds(random.nextLong(60, 36_000)).plusNanos(i.toLong() + 1)
                val startedText = if (i % 10 == 0) "not-a-timestamp" else started.toString()
                record(projects[random.nextInt(projects.size)], startedText, ended.toString(), "w$i")
            }

        val declarations =
            listOf(
                DeclarationEvent(Instant.parse("2026-07-20T00:00:00Z"), declaration(60.0, 40.0)),
                DeclarationEvent(Instant.parse("2026-08-14T18:00:00Z"), declaration(30.0, 70.0)),
                DeclarationEvent(Instant.parse("2026-08-15T06:00:00Z"), declaration(50.0, 50.0)),
            )

        val removed = records.filter { random.nextInt(5) == 0 }
        val reAdded = removed.filter { random.nextInt(4) != 0 }
        val dropped = removed.toSet() - reAdded.toSet()

        val windowStraddler =
            record(
                CN,
                WINDOW_FROM.minusSeconds(3600).toString(),
                WINDOW_FROM.plusSeconds(3600).toString(),
                "window-straddle-seed$seed",
            )
        val monthStraddler =
            record(
                GF,
                MONTH_START.minusSeconds(3600).toString(),
                MONTH_START.plusSeconds(3601).toString(),
                "month-straddle-seed$seed",
            )
        val constructed = listOf(windowStraddler, monthStraddler)

        return Fixture(
            records = records + constructed,
            declarations = declarations,
            removed = removed,
            reAdded = reAdded,
            finalMembership = records.filterNot { it in dropped } + constructed,
        )
    }

    // ------------------------------------------------------------------
    // failure accounting and the publish seam
    // ------------------------------------------------------------------

    @Test
    fun `unattributable records ride the report by reason and are never attributed hours`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        val good = record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w1")
        val badStart = record(CN, "whenever", "2026-08-15T06:00:00Z", "w2")
        val badEnd = record(GF, "2026-08-15T01:00:00Z", "soon", "w3")
        val backwards = record(GF, "2026-08-15T06:00:00Z", "2026-08-15T01:00:00Z", "w4")
        listOf(good, badStart, badEnd, backwards).forEach(rig::add)

        val report = rig.views.publish()

        report.unattributable shouldBe
            mapOf(
                UnattributableReason.UNPARSEABLE_STARTED to 1L,
                UnattributableReason.UNPARSEABLE_ENDED to 1L,
                UnattributableReason.ENDED_BEFORE_STARTED to 1L,
            )
        report.unattributableRecords shouldBe setOf(badStart, badEnd, backwards)
        // None of them contributed hours.
        report.window.totalHours shouldBe (6.0 plusOrMinus TOLERANCE)

        // Removing one takes it out of the accounting: this is live membership,
        // not a process-lifetime counter.
        rig.remove(badStart)
        val after = rig.views.publish()
        after.unattributableRecords shouldBe setOf(badEnd, backwards)
    }

    @Test
    fun `onPublish listeners receive exactly the instance current returns, once per publish`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        val seen = mutableListOf<AllocatorReport>()
        rig.views.onPublish { seen += it }

        val first = rig.views.publish()
        rig.add(record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w1"))
        val second = rig.views.publish()

        seen.size shouldBe 2
        seen[0] shouldBeSameInstanceAs first
        seen[1] shouldBeSameInstanceAs second
        rig.views.current().shouldNotBeNull() shouldBeSameInstanceAs second
    }

    @Test
    fun `a throwing listener neither withholds the published report nor starves its peers`() {
        val rig = rig()
        rig.declare(DeclarationEvent(EARLY, declaration(60.0, 40.0)))
        val late = mutableListOf<AllocatorReport>()
        rig.views.onPublish { error("subscriber went away") }
        rig.views.onPublish { late += it }

        val thrown =
            try {
                rig.views.publish()
                null
            } catch (t: Throwable) {
                t
            }

        thrown.shouldNotBeNull().message shouldBe "subscriber went away"
        // The swap happened before the notification, so the report survives...
        val published = rig.views.current().shouldNotBeNull()
        published.publishedAt shouldBe NOW
        // ...and the listener behind the throwing one still got it.
        late shouldBe listOf(published)
    }

    @Test
    fun `current is null before the first publish`() {
        rig().views.current() shouldBe null
    }
}
