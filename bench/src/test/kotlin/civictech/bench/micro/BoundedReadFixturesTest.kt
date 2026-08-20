package civictech.bench.micro

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.cell.ReadCaveat
import civictech.cell.StateRead
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fast, **untagged** guards on the E1/E2/E3 fixtures — the companion to
 * `BoundedReadProbeTest`, which is `@Tag("bench")` and never runs by default.
 *
 * The division of labour is deliberate. The probes are measurement *entry points*: they
 * take minutes at the larger scales, they are excluded from `:bench:test` unconditionally
 * by F2's gate (`[BEN1-10]`), and a regression in the harness would therefore go
 * unobserved between deliberate sweeps. These tests run on every `./gradlew :bench:test`
 * and stay sub-second, because they exercise the fixtures at 10^3 elements with 50-add
 * drives rather than the probes' 8,000-add bursts — so what they check is that the
 * instrument still *works*, not what it measures.
 *
 * The one that matters most is the unlinked-rig control: every observable a live-traffic
 * measurement rests on is read at the collector, so a rig whose links silently failed to
 * establish would seed, drive and quiesce without throwing and report a beautifully small
 * `maxGap` over zero arrivals. Without a negative control, "it built and the numbers look
 * plausible" cannot be told apart from a working rig.
 */
class BoundedReadFixturesTest {

    @Test
    fun `a direct copy subject holds every populated element`() {
        val subject = BoundedReadFixtures.directCopySubject(SetScale.N1E3)
        addsOf(subject.snapshot()).size shouldBe SetScale.N1E3.elements
    }

    @Test
    fun `a hosted copy subject answers snapshotOf with the same whole state`() {
        BoundedReadFixtures.hostedCopySubject(SetScale.N1E3).use { subject ->
            addsOf(subject.snapshotOf()).size shouldBe SetScale.N1E3.elements
        }
    }

    @Test
    fun `a paged walk covers the whole state in five 200-entry pages`() {
        BoundedReadFixtures.hostedCopySubject(SetScale.N1E3).use { subject ->
            val outcome = BoundedReadFixtures.pagedWalk(
                host = subject.host,
                ref = subject.ref,
                limit = BoundedReadFixtures.PAGE_LIMIT,
                expectedEntries = SetScale.N1E3.elements,
            )
            // 1,000 entries at 200 per page. `pagedWalk` already refuses a page that
            // exceeded the hard limit and refuses an entry total that misses
            // `expectedEntries`; these pin the arithmetic a reader of the probe's output
            // needs in order to interpret a page count.
            outcome.pages shouldBe SetScale.N1E3.elements / BoundedReadFixtures.PAGE_LIMIT
            outcome.entries shouldBe SetScale.N1E3.elements
            outcome.pageLatenciesMs.size shouldBe outcome.pages
            assertTrue(outcome.totalPageWallMs > 0.0, "total page wall time must be positive")

            // Quiescent cell, so nothing mints a tag during the walk and the opening and
            // closing stamps must agree. This is the ONE place the stability check is a
            // real assertion: under the probes' concurrent add drive an unstable stamp is
            // the walk working, not failing.
            outcome.frontierStable.shouldBeTrue()

            // Intermediate pages carry the opening frontier and declare
            // STALE_FRONTIER; the first and last pages carry an exact one and declare
            // nothing. Five pages therefore means exactly this caveat set — asserted
            // rather than ignored, because a page that silently stopped declaring it
            // would be claiming a per-page exact frontier the cell does not compute.
            outcome.caveats shouldBe setOf(ReadCaveat.STALE_FRONTIER)
        }
    }

    @Test
    fun `a paged walk honours a page limit other than the default`() {
        BoundedReadFixtures.hostedCopySubject(SetScale.N1E3).use { subject ->
            val outcome = BoundedReadFixtures.pagedWalk(
                host = subject.host,
                ref = subject.ref,
                limit = 300,
                expectedEntries = SetScale.N1E3.elements,
            )
            // 300 + 300 + 300 + 100. A walk that ignored the requested limit and fell back
            // on StateRead's own default of 200 would report five pages here.
            outcome.pages shouldBe 4
        }
    }

    @Test
    fun `the 200-entry page limit is the one the measurement replicates, and the byte budget does not bind it`() {
        // A literal 200 in bench code, citing 30-bounded-read-measurement.md §5/§6 —
        // never read from civictech.inspect.ValueEncoder.MAX_ROWS, which [BEN1-37] forbids
        // this diff from touching and which :bench cannot see anyway.
        BoundedReadFixtures.PAGE_LIMIT shouldBe 200

        // SetCell estimates 64 bytes per entry plus 48 per tag, so a full page of
        // single-add-tag elements estimates 200 * (64 + 48) = 22,400 bytes. The advisory
        // byteBudget must stay above that or the page limit stops governing and "pages of
        // 200" becomes aspirational — silently changing what E3 measures.
        val estimatedFullPageBytes = BoundedReadFixtures.PAGE_LIMIT * (64 + 48)
        estimatedFullPageBytes shouldBe 22_400
        assertTrue(
            StateRead().byteBudget > estimatedFullPageBytes,
            "StateRead's default byteBudget (${StateRead().byteBudget}) must exceed a full " +
                "page's estimate ($estimatedFullPageBytes) or the page limit stops governing",
        )
    }

    @Test
    fun `a linked rig observes one arrival per live add`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            val outcome = rig.drive(ADDS)
            outcome.arrivals shouldBe ADDS
            assertTrue(outcome.durationMs > 0.0, "drive duration must be positive")
            // maxGap is widened to cover the interval from the drive's start to the first
            // arrival, so it is strictly positive for any drive that observed anything.
            assertTrue(outcome.maxGapMs > 0.0, "maxGap must be positive when arrivals exist")
            assertTrue(outcome.throughputPerSecond > 0.0, "throughput must be positive")
            rig.arrivals shouldBe ADDS.toLong()
        }
    }

    @Test
    fun `an unlinked rig observes nothing, which is what makes the linked assertion mean something`() {
        BoundedReadFixtures.rig(SetScale.N1E3, RigWiring.UNLINKED).use { rig ->
            val outcome = rig.drive(ADDS)
            // The adds were enqueued and the host's queue provably drained (the fence, not
            // a poll), and still nothing arrived — because nothing is linked. A rig that
            // reported arrivals here would mean the wiring switch does nothing and the
            // negative control is decorative.
            outcome.arrivals shouldBe 0
            outcome.maxGapMs shouldBe 0.0
            rig.arrivals shouldBe 0L
        }
    }

    @Test
    fun `a probe environment records this process as the measuring JVM and refuses without a harness commit`() {
        val previous = System.getProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY)
        try {
            System.clearProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY)
            // No artifact records the harness commit, and RunEnvironment refuses to exist
            // without it, so the probe refuses rather than inventing one.
            shouldThrow<IllegalArgumentException> { BoundedReadFixtures.requiredHarnessSha() }
                .message!! shouldContain BoundedReadFixtures.HARNESS_SHA_PROPERTY

            System.setProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY, "cafebabe")
            val env: RunEnvironment = BoundedReadFixtures.probeRunEnvironment(
                statistic = "maxGap per drive",
                trials = 4,
                warmupTrials = 2,
            )
            env.harnessCommitSha shouldBe "cafebabe"
            env.measurementIterations shouldBe 4
            env.warmupIterations shouldBe 2
            env.forkCount shouldBe 1
            // The statistic is carried into jmhMode and marked as a probe, so no reader
            // mistakes it for one of JMH's own modes.
            env.jmhMode shouldContain "maxGap per drive"
            env.jmhMode shouldContain "not JMH"
            // In-process measurement, so this process's own JVM IS the measuring JVM: the
            // case civictech.bench.Env.kt's fromJmhLog route exists to exclude for a
            // renderer, and the one case where the two coincide. Both facts must be real
            // strings, never "unknown" placeholders.
            env.jvmVersion shouldBe System.getProperty("java.version")
            assertTrue(
                env.heapSettings.startsWith("-X") || env.heapSettings.startsWith("JVM defaults"),
                "heapSettings must state real flags or explicitly state their absence, " +
                    "was '${env.heapSettings}'",
            )
            assertTrue(env.coreCount > 0, "coreCount must be positive")
        } finally {
            if (previous == null) System.clearProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY)
            else System.setProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY, previous)
        }
    }

    @Test
    fun `every probe result is REAL-driven, because there is no simulated variant`() {
        // The original measured a threaded ManagedHost and said so per experiment; a
        // SimulationController interleaves the read and the traffic on one deterministic
        // thread, which is not the question being replicated.
        BoundedReadFixtures.DRIVE shouldBe Drive.REAL
    }

    @Test
    fun `trial stats refuse to state a dispersion from a single sample`() {
        shouldThrow<IllegalArgumentException> { TrialStats(listOf(1.0)) }
            .message!! shouldContain "at least 2 samples"
        shouldThrow<IllegalArgumentException> { TrialStats(listOf(1.0, Double.NaN)) }
            .message!! shouldContain "finite"
    }

    @Test
    fun `trial stats state the Student-t 99 point 9 percent half-width, not the normal approximation`() {
        val identical = TrialStats(listOf(4.0, 4.0, 4.0))
        identical.mean shouldBe 4.0
        identical.median shouldBe 4.0
        identical.stdDev shouldBe 0.0
        identical.dispersion shouldBe 0.0

        // Two samples, so df = 1 and the Student-t factor is 636.619 — not the normal
        // quantile 3.291. mean 1.5, sd 0.70710678, se = sd/sqrt(2) = 0.5, so the
        // half-width is 636.619 * 0.5 = 318.3095. That two-orders-of-magnitude difference
        // from the normal approximation is the whole reason the choice is spelled out in
        // TrialStats' KDoc: the cheap approximation would report an interval far too tight
        // and let `civictech.bench.classify` call a two-sample probe Reportable.
        val pair = TrialStats(listOf(1.0, 2.0))
        pair.mean shouldBe 1.5
        assertEquals(318.3095, pair.dispersion, 0.001)

        val spread = TrialStats(listOf(1.0, 2.0, 3.0, 4.0, 100.0))
        spread.n shouldBe 5
        spread.min shouldBe 1.0
        spread.max shouldBe 100.0
        spread.median shouldBe 3.0
        spread.p95 shouldBe 100.0
        spread.samples shouldContainExactly listOf(1.0, 2.0, 3.0, 4.0, 100.0)
        spread.describe("ms") shouldContain "n=5"
    }

    @Test
    fun `the t table is conservative beyond its tabulated degrees of freedom`() {
        TrialStats.tCritical999(1) shouldBe 636.619
        TrialStats.tCritical999(2) shouldBe 31.599
        TrialStats.tCritical999(20) shouldBe 3.850
        // Beyond the table the largest tabulated factor is reused, which over-states
        // rather than under-states the interval: t decreases monotonically in df towards
        // the 3.291 normal quantile, so 3.850 is conservative for any larger sample. A
        // future change that "extended" the table below 3.291 would start under-stating
        // dispersion, which is why this is asserted and not merely documented.
        TrialStats.tCritical999(500) shouldBe 3.850
        assertTrue(TrialStats.tCritical999(500) > 3.291, "the fallback must exceed 3.291")
        assertTrue(
            TrialStats.tCritical999(3) > TrialStats.tCritical999(10),
            "t must decrease as degrees of freedom rise",
        )
        shouldThrow<IllegalArgumentException> { TrialStats.tCritical999(0) }
    }

    // ---------------------------------------------------------------------------------
    // PagedWalkOutcome's per-page series accessors [computenet-wsz4].
    //
    // These four are pure arithmetic over `pageLatenciesMs`, and E3's attribution of a
    // live-traffic stall to a page POSITION is built entirely on them — so a silent
    // off-by-one here would not fail anything, it would publish a finding naming the wrong
    // end of the walk. `BoundedReadProbeTest` self-checks them per trial, but it is
    // @Tag("bench") and never runs by default; these cases are the default-suite guard.
    //
    // Hand-built outcomes rather than real walks, deliberately: the question is the
    // arithmetic, and a real walk cannot produce a chosen tie or a two-page series on
    // demand. Exact Double equality throughout, because every expectation below is a
    // literal the accessors SELECT rather than compute — an epsilon here would hide
    // exactly the substitutions these cases exist to catch.
    // ---------------------------------------------------------------------------------

    @Test
    fun `the per-page series names which page was the worst, 1-based, including the close`() {
        // A walk whose LAST page is the worst — the closing frontier's O(n) pass, which
        // three probe runs on 2026-08-20 showed is as often the maximum as the open is.
        // Telling those two apart is the whole point of the position, so a case whose
        // answer is not 1 is the load-bearing one.
        val walk = walkOf(1.0, 5.0, 20.0)

        walk.maxSinglePageMs shouldBe 20.0
        walk.maxSinglePagePosition shouldBe 3
        walk.firstPageMs shouldBe 1.0
        walk.lastPageMs shouldBe 20.0
        // Interior = every page but the first and the last, so one page here — and NOT
        // the 20.0 a series-wide median or a missing `dropLast` would report.
        walk.interiorMedianPageMs shouldBe 5.0

        // The position must INDEX the series, which is the invariant a 0-based slip
        // breaks while every magnitude asserted above still agrees.
        positionIndexesTheMax(walk)
    }

    @Test
    fun `a walk of fewer than three pages has no interior, and says so rather than inventing one`() {
        // One page: the open and the close are the same page, and there is no interior to
        // read the endpoints against. `0.0` would be a claim that the interior is free.
        val single = walkOf(7.5)
        single.maxSinglePagePosition shouldBe 1
        single.firstPageMs shouldBe 7.5
        single.lastPageMs shouldBe 7.5
        single.interiorMedianPageMs shouldBe null
        positionIndexesTheMax(single)

        // Two pages: both are endpoints, still no interior. Asymmetric values, so a first
        // reported as last (or the reverse) cannot pass.
        val pair = walkOf(1.0, 9.0)
        pair.maxSinglePagePosition shouldBe 2
        pair.firstPageMs shouldBe 1.0
        pair.lastPageMs shouldBe 9.0
        pair.interiorMedianPageMs shouldBe null
        positionIndexesTheMax(pair)

        // No pages at all is not reachable through `pagedWalk` (it appends a latency
        // before it can break), but PagedWalkOutcome is a public data class and a caller
        // can construct one. Position 0 says "no page", which is not page 1.
        val empty = walkOf()
        empty.maxSinglePagePosition shouldBe 0
        empty.firstPageMs shouldBe null
        empty.lastPageMs shouldBe null
        empty.interiorMedianPageMs shouldBe null
    }

    @Test
    fun `a tie for the worst page resolves to the earliest position`() {
        // Two pages of identical cost: reporting the later one would attribute an open's
        // cost to a mid-walk page. Documented as earliest-wins, so it is asserted.
        val tied = walkOf(4.0, 4.0, 1.0)
        tied.maxSinglePageMs shouldBe 4.0
        tied.maxSinglePagePosition shouldBe 1
        tied.interiorMedianPageMs shouldBe 4.0
        positionIndexesTheMax(tied)
    }

    @Test
    fun `the interior median takes the upper median of an even interior, as TrialStats does`() {
        // Interior [1.0, 2.0]: the upper median is 2.0. Averaging the two middles (1.5) is
        // the other defensible convention and is NOT the one PagedWalkOutcome documents —
        // it matches TrialStats.median so the two medians one probe line prints agree.
        val walk = walkOf(0.5, 1.0, 2.0, 0.25)
        walk.interiorMedianPageMs shouldBe 2.0
        walk.maxSinglePagePosition shouldBe 3
        walk.firstPageMs shouldBe 0.5
        walk.lastPageMs shouldBe 0.25
        positionIndexesTheMax(walk)

        // The convention this pins, stated as the equality it rests on.
        TrialStats(listOf(1.0, 2.0)).median shouldBe 2.0
    }

    // ---------------------------------------------------------------------------------

    /**
     * A `PagedWalkOutcome` carrying exactly [latencies] — the fields the series accessors
     * never read are set consistently rather than arbitrarily, so no case here depends on
     * an inconsistent fixture.
     */
    private fun walkOf(vararg latencies: Double) = PagedWalkOutcome(
        pages = latencies.size,
        entries = latencies.size * BoundedReadFixtures.PAGE_LIMIT,
        pageLatenciesMs = latencies.toList(),
        frontierStable = false,
        caveats = setOf(ReadCaveat.STALE_FRONTIER),
    )

    /**
     * The invariant that makes a position an attribution: it is 1-based, and it indexes the
     * page whose latency is the reported maximum. A 0-based position satisfies every
     * magnitude assertion in these tests and fails this one.
     */
    private fun positionIndexesTheMax(walk: PagedWalkOutcome) {
        val position = walk.maxSinglePagePosition
        assertTrue(
            position in 1..walk.pages,
            "position $position is not a 1-based page of a ${walk.pages}-page walk",
        )
        assertEquals(
            walk.maxSinglePageMs,
            walk.pageLatenciesMs[position - 1],
            "position $position must index the reported max page (${walk.maxSinglePageMs} ms)",
        )
    }

    /** `SetCell.snapshot()` returns a map of `adds`/`dels`/`counter`; this is its add side. */
    private fun addsOf(snapshot: Any): Map<*, *> {
        val state = snapshot as Map<*, *>
        return state["adds"] as Map<*, *>
    }

    private companion object {
        /**
         * Adds per drive in these guards: 50, not the probes' 8,000. These tests check the
         * instrument, and every one of them runs on the default `:bench:test`, which
         * `[BEN1-10]`'s gate exists to keep sub-second.
         */
        const val ADDS: Int = 50
    }
}
