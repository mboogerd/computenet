package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.classify
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * **V1C-BENCH E2 and E3, re-expressed** against the landed `snapshotOf`/`readState`
 * surface (`[BEN1-22]`, BS-15) — the occupancy dip one whole-state copy imposes on a
 * cell's own live traffic, and the same dip when the same read is paged instead.
 *
 * ## THE JUDGMENT THIS CLASS RECORDS: JUnit probes, not JMH
 *
 * The parent feature (`computenet-x9e.6`) left the shape of E2 open — "likely lands as a
 * `@Tag(bench)` JUnit probe copying `SnapshotOfHardeningTest`'s real-host harness pattern
 * rather than JMH — implementer's judgment, stated in the entry". **It went the JUnit
 * way, for both E2 and E3**, and the sibling comparison task should cite this paragraph
 * for why:
 *
 * 1. **The observable is not a per-invocation latency.** E2's number is `maxGap` — the
 *    largest interruption in a *third* cell's arrival stream while two threads contend for
 *    one host's single drain thread. JMH measures the wall time of a body it invokes; it
 *    has no vocabulary for "the worst stall a bystander observed during the body", and
 *    reshaping E2 so that a JMH body returned it would mean one invocation = one whole
 *    8,000-add drive plus a concurrent read, i.e. ~30-90 ms per op with a `Level.Invocation`
 *    setup that seeds 10^5 elements — precisely the regime JMH's own docs call unreliable.
 * 2. **The comparison is between two conditions, not two numbers.** §6's result is
 *    baseline-vs-concurrent within one rig and paged-vs-whole across two, with the
 *    condition switched *between* trials on a host whose JIT/GC state is deliberately
 *    shared. JMH isolates forks, which is the right default and the wrong one here.
 * 3. **A real-host harness for exactly this already exists in the tree.**
 *    `kernel/src/test/kotlin/civictech/cell/host/SnapshotOfHardeningTest.kt` drives
 *    `snapshotOf` from a foreign thread against a real host with bounded waits; the
 *    original V1C-BENCH harness was itself two JUnit files. Following that pattern keeps
 *    the replication recognisable against the document it replicates.
 *
 * What the JUnit route costs, stated rather than glossed: no fork isolation, no JMH warmup
 * accounting, and a dispersion that has to be constructed by hand — see `TrialStats`,
 * which states its own limits, including that a low-trial probe is *expected* to classify
 * `Unreportable`.
 *
 * ## E3 is a real paged walk — the one deliberate method change
 *
 * The original E3 could not page for real: `BoundedStateful`, `StateRead`, `StatePage`,
 * `Cursor` and `ManagedHost.readState` were all forbidden to `V1C-BENCH`, so it stood a
 * `PageCursorCell` holding a plain `List<Int>` in for "the same underlying state" and
 * answered each `snapshot()` with the next 200-element slice. All five types have landed
 * and `SetCell` is the reference `BoundedStateful`, so [e3At] drives the real
 * `ManagedHost.readState` page by page against the real target cell under E2's live-traffic
 * drive — which is identical to E2's by construction, but NOT to the original E3's own
 * drive: Appendix A drove `m = 5_000` there, once, with no warmup and no 1 ms delay, while
 * §5's prose claims 8,000 (`BoundedReadFixtures`' header, difference 2).
 *
 * **That makes E3 strictly more expensive than the original's stand-in, and a divergence
 * from §5's numbers is therefore a harness difference before it is anything else.** The
 * real walk pays, per walk, two O(n) passes over the tag maps under the cell's own
 * `stateLock` (`SetCell.openWalk` freezes the enumeration order and computes the opening
 * frontier in one; the closing frontier is recomputed in another), plus per page a live
 * read of the tag maps under that same lock. The `List<Int>` stand-in paid none of it. The
 * original document predicted exactly this in its own "what could not be done" section —
 * "tag-set filtering, frontier computation ... could differ from this document's numbers".
 * Anything left after that difference is accounted for is a candidate code change since
 * C7 or a machine difference; the sibling entry owns that three-way call, and this class
 * owns telling it which differences are structural.
 *
 * ## READ THIS BEFORE READING ANY NUMBER THESE PRINT
 *
 * **The scale in a method's name is what the target was PRE-SEEDED to, not what a later
 * trial measured.** Every add is a fresh element and an OR-set never shrinks, so after the
 * warmup drive and k timed drives the target holds
 * `scale.elements + WARMUP_ADDS + k * DRIVE_ADDS` — at 1e3 and `BoundedReadFixtures.TRIALS`
 * (five, raised from three by `computenet-x9e.6.4`'s own recommendation), 1,000 rising to
 * 42,000. That drift is the original harness's own (one monotone `seedCounter` shared by
 * `seed()` and `driveTimed()`, and five 8,000-add trials per condition), reproduced
 * deliberately so these numbers stay comparable to §4/§5; `BoundedReadFixtures`' header
 * item 4 has the full argument. Every probe below therefore prints
 * `target elements after each trial`, which the original never did — read the numbers
 * against that list, not against the method name.
 *
 * ## Running these
 *
 * `@Tag("bench")`, so they never execute in a default `:bench:test`
 * (`[BEN1-10]` via F2's gate in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`). One
 * scale at a time — the methods are split per scale rather than parameterized precisely so
 * `--tests` can select one, since the tag gate cannot sub-select parameterized invocations:
 *
 * ```
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.BoundedReadProbeTest' \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD) -i
 * ```
 *
 * Numbers are **printed, never written**: appending an entry to `doc/bench/findings.md` is
 * the measurement task's hand step, performed by whoever can vouch for the run — the same
 * rule `Findings`' own KDoc states and `ThroughputReportRenderTest` follows.
 *
 * The sweep sizing, for a caller budgeting a slot: one method at 10^3 seeds 1,000 elements
 * per rig and drives `BoundedReadFixtures.TRIALS` + 1 bursts of 8,000 adds per condition —
 * seconds. At 10^5 the seeding dominates and E2 pays it twice (two rigs). The module's
 * per-method timeout is the shared 5-minute default; a full sweep at 10^5 that approaches
 * it should raise it per class rather than shrinking the drive.
 */
@Tag("bench")
class BoundedReadProbeTest {

    // ------------------------------------------------------------------------------
    // E2 — the occupancy cost under live traffic, one whole copy.
    // ------------------------------------------------------------------------------

    @Test
    fun `E2 whole-copy occupancy dip at 1e3 elements`() = e2At(SetScale.N1E3)

    @Test
    fun `E2 whole-copy occupancy dip at 1e4 elements`() = e2At(SetScale.N1E4)

    @Test
    fun `E2 whole-copy occupancy dip at 1e5 elements`() = e2At(SetScale.N1E5)

    // ------------------------------------------------------------------------------
    // E3 — the paged counterfactual, driven through the LANDED ManagedHost.readState.
    // ------------------------------------------------------------------------------

    @Test
    fun `E3 paged readState occupancy at 1e3 elements`() = e3At(SetScale.N1E3)

    @Test
    fun `E3 paged readState occupancy at 1e4 elements`() = e3At(SetScale.N1E4)

    @Test
    fun `E3 paged readState occupancy at 1e5 elements`() = e3At(SetScale.N1E5)

    /**
     * E2 at one scale: the same unpaced 8,000-add drive with and without one concurrent
     * `ManagedHost.snapshotOf(target)`, on two independently seeded rigs.
     *
     * Two rigs rather than one, following the original: a condition's trials share one
     * host so JIT and GC state is shared rather than re-paid per trial, but the two
     * conditions must not share one, or the baseline's own trials would run against a
     * target whose tag maps the concurrent condition had already grown.
     */
    private fun e2At(scale: SetScale) {
        // Built first, so a missing harness SHA fails in milliseconds instead of after a
        // 10^5 seeding.
        val env = BoundedReadFixtures.probeRunEnvironment(
            statistic = "maxGap per ${BoundedReadFixtures.DRIVE_ADDS}-add unpaced drive",
        )

        val baseline = ArrayList<Double>()
        val baselineDurations = ArrayList<Double>()
        val baselineElements = ArrayList<Int>()
        BoundedReadFixtures.rig(scale).use { rig ->
            rig.seed()
            rig.drive(BoundedReadFixtures.WARMUP_ADDS)
            repeat(BoundedReadFixtures.TRIALS) {
                val outcome = rig.drive(BoundedReadFixtures.DRIVE_ADDS)
                baseline += outcome.maxGapMs
                baselineDurations += outcome.durationMs
                baselineElements += rig.elementsAdded
            }
        }

        val concurrent = ArrayList<Double>()
        val concurrentDurations = ArrayList<Double>()
        val concurrentElements = ArrayList<Int>()
        val readLatencies = ArrayList<Double>()
        BoundedReadFixtures.rig(scale).use { rig ->
            rig.seed()
            rig.drive(BoundedReadFixtures.WARMUP_ADDS)
            repeat(BoundedReadFixtures.TRIALS) {
                // The read is fired from a SECOND thread a millisecond into the drive, so
                // it lands against an already-queued backlog of data-priority work. That
                // is the mechanism under test: `snapshotOf` submits at scheduler priority
                // 0, above ordinary data traffic's 20, so it cuts in front of everything
                // queued and then holds the single drain thread for the whole copy.
                val go = CountDownLatch(1)
                var latencyMs = -1.0
                val reader = Thread {
                    go.await()
                    Thread.sleep(BoundedReadFixtures.READ_DELAY_MS)
                    val t0 = System.nanoTime()
                    checkNotNull(rig.host.snapshotOf(rig.targetRef).get()) {
                        "concurrent snapshotOf completed with null — not a measurement"
                    }
                    latencyMs = (System.nanoTime() - t0) / 1_000_000.0
                }.apply { isDaemon = true; start() }

                go.countDown()
                val outcome = rig.drive(BoundedReadFixtures.DRIVE_ADDS)
                reader.join(BoundedReadFixtures.DRAIN_TIMEOUT_MS)
                check(latencyMs >= 0.0) {
                    "concurrent snapshotOf did not complete within " +
                        "${BoundedReadFixtures.DRAIN_TIMEOUT_MS}ms"
                }
                concurrent += outcome.maxGapMs
                concurrentDurations += outcome.durationMs
                concurrentElements += rig.elementsAdded
                readLatencies += latencyMs
            }
        }

        val baselineGap = TrialStats(baseline)
        val concurrentGap = TrialStats(concurrent)
        report(
            experiment = "E2",
            scale = scale,
            env = env,
            rows = listOf(
                "E2 ${scale.label} baseline maxGap (no concurrent read)" to baselineGap,
                "E2 ${scale.label} concurrent maxGap (one whole snapshotOf)" to concurrentGap,
            ),
            extra = listOf(
                "baseline duration: ${TrialStats(baselineDurations).describe("ms")}",
                "concurrent duration: ${TrialStats(concurrentDurations).describe("ms")}",
                "concurrent snapshotOf latency: ${TrialStats(readLatencies).describe("ms")}",
                // The scale in the method name is the PRE-SEED size; these are the sizes
                // the trials actually measured. See this class's KDoc and
                // BoundedReadFixtures' header item 4.
                "target elements after each trial: baseline=$baselineElements " +
                    "concurrent=$concurrentElements (pre-seed ${scale.elements})",
                // §4's dip, stated the way §4 states it: the difference of the two
                // conditions' medians. Reported alongside both medians, never instead of
                // them, so a reader can see which side moved.
                "maxGap dip (median concurrent - median baseline): " +
                    "%.4f ms".format(concurrentGap.median - baselineGap.median),
            ),
        )
    }

    /**
     * E3 at one scale: a sequential paged walk of the target through the landed
     * `ManagedHost.readState`, at a 200-entry page limit, under the identical drive.
     *
     * `expectedEntries` is deliberately NOT passed to `pagedWalk` here. The walk freezes
     * its enumeration order when it opens, and live traffic keeps adding elements to the
     * same cell while it runs, so the number of entries a concurrent walk returns is a
     * property of the race and not a fixture invariant — asserting a count would turn a
     * correct measurement into a flake. The termination bound is the walk's own
     * `next == null`; `BoundedReadFixturesTest` is where the entry count is pinned, on a
     * quiescent cell.
     *
     * ## The per-page series, and what three runs of it actually showed
     *
     * `maxSinglePageMs` alone cannot say *which* page carries the stall, so this method
     * also prints that page's position, the two endpoint pages, the interior median, and
     * each trial's `maxGap` beside its own worst page and the two wall times
     * (`[computenet-wsz4]`). **Read the position column; do not assume it.** Three
     * consecutive runs on 2026-08-20, one machine (M2 Pro, 15 trials each, five per scale),
     * disagree about which end of the walk is the maximum — and that disagreement is the
     * most useful thing they found.
     *
     * Stable across all three:
     *
     * - **The interior is flat and cheap; the two ENDPOINTS are not.** The interior median
     *   page stayed 0.05-0.11 ms at every scale in every run, while the first and last pages
     *   ran hundreds of times that — 392x-1129x (first) and 128x-454x (last) at 1e5 in run
     *   1. Two O(n) passes, not one: `SetCell.openWalk` freezes the enumeration order and
     *   computes the opening frontier, and the closing frontier is recomputed on the final
     *   page. Together the endpoints were ~60% of run 1's total page wall at 1e5 (51.0 of
     *   85.5 ms mean) over ~600 pages.
     * - **The maximum is an endpoint page in most trials**, so a smaller page limit cannot
     *   reduce it — the one conclusion that survived all three runs.
     *
     * NOT stable, which is the reason this column had to be printed rather than inferred:
     *
     * - **Which endpoint is the maximum flips between runs.** At 1e5: run 1 put it at
     *   position `1` in 5/5 trials, run 2 at position `pages` — the close — in 4/5, run 3
     *   at `[1, 546, 587, 412, 1]` of `[508, 546, 587, 625, 666]`. The open and the close
     *   are the same order of magnitude, so which one is larger is decided by machine noise.
     *   "The first page carries the stall" is therefore NOT a finding in either direction;
     *   "an endpoint page carries it" is.
     * - **`maxGap` and the worst page can disagree by multiples, and `walkWall` vs `drive`
     *   says when.** `maxGap` is measured over the drive's window only, so a walk that
     *   outlasts its drive hides its own late pages. Run 3 at 1e5, same line, both
     *   directions: t1 `maxGap` 57.8596 against page 58.9258 at 1/508 with walkWall 169.98
     *   inside a 177.49 ms drive (contained, figures agree), and t3 `maxGap` 10.2273 against
     *   page 27.5722 at 587/587 with walkWall 154.97 against a 99.37 ms drive (not
     *   contained, and the close's cost never reached the collector). **This is a candidate
     *   explanation for the sign instability the V1C-BENCH entry's §6 caveat records**
     *   (`computenet-xlst`): at 1e5 the walk and the drive are the same length, so whether
     *   the close lands inside the measured window is a race, and E3's `maxGap` measures a
     *   different fraction of the walk each run.
     * - **A `maxGap` can also belong to no page at all**, and an interior page can spike.
     *   Run 1 at 1e3 showed `maxGap` 11.2151 ms against a worst page of 3.9211 ms — a stall
     *   the walk does not account for (the drive shares this machine with everything else).
     *   Run 3 at 1e5 t4 put the max at page 412 of 625 at 29.5561 ms, ~400x that run's
     *   interior median — a one-off, not a structural cost, and distinguishable from one
     *   only by its position.
     *
     * All of that is visible only because the position and the two wall times are printed
     * per trial. Trial means would have shown run 1's endpoint costs and hidden every one of
     * the instabilities above — which is exactly how "the first page" became a plausible
     * reading of a maximum.
     */
    private fun e3At(scale: SetScale) {
        val env = BoundedReadFixtures.probeRunEnvironment(
            statistic = "maxGap per ${BoundedReadFixtures.DRIVE_ADDS}-add unpaced drive, " +
                "concurrent with one ${BoundedReadFixtures.PAGE_LIMIT}-entry paged walk",
        )

        val gaps = ArrayList<Double>()
        val durations = ArrayList<Double>()
        val totalPageWall = ArrayList<Double>()
        val maxSinglePage = ArrayList<Double>()
        val pageCounts = ArrayList<Int>()
        val elementCounts = ArrayList<Int>()
        val walkEntries = ArrayList<Int>()
        val walkCaveats = LinkedHashSet<String>()
        var frontierStableTrials = 0
        // The whole per-page series per trial, retained so the position figures below are
        // read off the same walks the scalar columns above summarise rather than off a
        // second set. Five trials x a few hundred doubles; the memory is nothing next to
        // the target cell itself.
        val walks = ArrayList<PagedWalkOutcome>()

        BoundedReadFixtures.rig(scale).use { rig ->
            rig.seed()
            rig.drive(BoundedReadFixtures.WARMUP_ADDS)
            repeat(BoundedReadFixtures.TRIALS) {
                val go = CountDownLatch(1)
                var walk: PagedWalkOutcome? = null
                val pager = Thread {
                    go.await()
                    Thread.sleep(BoundedReadFixtures.READ_DELAY_MS)
                    walk = BoundedReadFixtures.pagedWalk(
                        host = rig.host,
                        ref = rig.targetRef,
                        limit = BoundedReadFixtures.PAGE_LIMIT,
                    )
                }.apply { isDaemon = true; start() }

                go.countDown()
                val outcome = rig.drive(BoundedReadFixtures.DRIVE_ADDS)
                pager.join(BoundedReadFixtures.DRAIN_TIMEOUT_MS)
                val completed = checkNotNull(walk) {
                    "paged walk did not complete within " +
                        "${BoundedReadFixtures.DRAIN_TIMEOUT_MS}ms"
                }

                gaps += outcome.maxGapMs
                durations += outcome.durationMs
                totalPageWall += completed.totalPageWallMs
                maxSinglePage += completed.maxSinglePageMs
                pageCounts += completed.pages
                walkEntries += completed.entries
                elementCounts += rig.elementsAdded
                completed.caveats.forEach { walkCaveats += it.name }
                if (completed.frontierStable) frontierStableTrials++
                walks += completed
            }
        }

        // The position figures index into the series they describe, so an off-by-one would
        // silently mis-attribute the stall to the wrong end of the walk — the exact error
        // this probe's output exists to rule out. Checked per trial rather than trusted.
        walks.forEachIndexed { index, walk ->
            val position = walk.maxSinglePagePosition
            check(position in 1..walk.pages && walk.pageLatenciesMs[position - 1] == walk.maxSinglePageMs) {
                "trial ${index + 1}: max page position $position does not index the max page " +
                    "(${walk.maxSinglePageMs} ms) in a ${walk.pages}-page series"
            }
            check(walk.firstPageMs == walk.pageLatenciesMs.first()) { "first page is not the first page" }
            check(walk.lastPageMs == walk.pageLatenciesMs.last()) { "last page is not the last page" }
        }

        val firstPages = walks.map { checkNotNull(it.firstPageMs) { "a completed walk took no pages" } }
        val lastPages = walks.map { checkNotNull(it.lastPageMs) { "a completed walk took no pages" } }
        val interiorMedians = walks.mapNotNull { it.interiorMedianPageMs }
        val maxPositions = walks.map { it.maxSinglePagePosition }
        val noInteriorTrials = walks.size - interiorMedians.size

        report(
            experiment = "E3",
            scale = scale,
            env = env,
            rows = listOf(
                "E3 ${scale.label} paged maxGap (${BoundedReadFixtures.PAGE_LIMIT}/page)"
                    to TrialStats(gaps),
                "E3 ${scale.label} total page wall time" to TrialStats(totalPageWall),
            ),
            extra = listOf(
                "drive duration: ${TrialStats(durations).describe("ms")}",
                "max single page: ${TrialStats(maxSinglePage).describe("ms")}",
                "pages per walk: $pageCounts (limit ${BoundedReadFixtures.PAGE_LIMIT})",
                "entries per walk: $walkEntries",
                // ---------------------------------------------------------------------
                // The per-page series [computenet-wsz4]. "max single page" above is a
                // magnitude; these lines say WHICH page it was, which is what lets an
                // entry attribute the stall to the walk's open, its close, or its
                // interior as a measurement instead of as a reading of the maximum.
                // Read them together with the mechanism: SetCell.openWalk makes two O(n)
                // passes under stateLock inside the FIRST readBounded call, and the
                // closing frontier is recomputed on the LAST page — so the first and last
                // page are the two structurally expensive positions, and the interior
                // median is the reference they are large or small against.
                // ---------------------------------------------------------------------
                "first page (the open): ${describeTrials(firstPages)}",
                "last page (the closing frontier): ${describeTrials(lastPages)}",
                "interior median page (every page but the first and last): " +
                    describeTrials(interiorMedians) +
                    if (noInteriorTrials > 0) {
                        " — $noInteriorTrials/${walks.size} trials walked fewer than three " +
                            "pages and have no interior"
                    } else "",
                "max page position (1-based, walk order) per trial: $maxPositions " +
                    "of $pageCounts pages — 1 is the open, the page count is the close, " +
                    "anything between is per-page work",
                "endpoint page as a multiple of the interior median, per trial: " +
                    ratiosAgainstInterior(walks),
                // The attribution's own limit, printed next to the numbers rather than
                // left to the entry: maxGap is the worst stall the DRIVE's collector saw
                // and max single page is what the PAGER thread measured, on two threads
                // with no common clock. Their agreeing to several decimals says one page
                // and not the walk carries the stall; it does not timestamp the gap onto
                // that page, and no figure this probe can print would. `walkWall` and
                // `drive` are printed with them because a walk that outlasts the drive
                // hides its own late pages from maxGap entirely — see `gapVersusPage`.
                "maxGap vs max single page @position, per trial: " +
                    gapVersusPage(gaps, durations, walks),
                "walk shape (page latencies ms, walk order) per trial: " + walkShapes(walks),
                // The walk freezes its enumeration order when it opens, so `entries` is the
                // size the walk SAW while `target elements` is the size at the trial's end;
                // the difference is the traffic that arrived after the walk opened. Both are
                // printed because the scale in the method name is neither of them — it is
                // the pre-seed size. See this class's KDoc.
                "target elements after each trial: $elementCounts " +
                    "(pre-seed ${scale.elements})",
                "page caveats declared: ${walkCaveats.ifEmpty { setOf("none") }}",
                // Reported because the original E3's List<Int> stand-in carried no
                // frontier at all, so this is a fact only the real walk can produce — and
                // reported with its limit attached: for an OR-set, equal endpoint stamps
                // are necessary but NOT sufficient for "the union is a snapshot" — since
                // computenet-v2ka a local remove mints a del-dot and is caught, but a
                // REORDERED remote del below a per-source max still is not
                // (SetCell.readBounded's KDoc). Under a concurrent add drive the expected
                // answer is UNSTABLE, and an unstable stamp is the walk working, not failing.
                "frontier stable (opening stamp == closing stamp): " +
                    "$frontierStableTrials/${BoundedReadFixtures.TRIALS} trials — " +
                    "instability is expected under a concurrent add drive, and stability " +
                    "would NOT prove the union is a snapshot (a reordered remote del can " +
                    "still evade it)",
            ),
        )
    }

    /**
     * `TrialStats.describe`, or a stated absence — never a fabricated dispersion.
     *
     * `TrialStats` refuses fewer than two samples on purpose (a zero dispersion is a claim
     * of perfect repeatability), and a per-page figure can legitimately have fewer samples
     * than there are trials: a walk of fewer than three pages has no interior. Saying so
     * is the honest line; substituting `0.0` or dropping the row would not be.
     */
    private fun describeTrials(samples: List<Double>): String =
        if (samples.size >= 2) {
            TrialStats(samples).describe("ms")
        } else {
            "n=${samples.size} — fewer than the two samples TrialStats needs to state a " +
                "dispersion, so no interval is claimed: ${samples.map { "%.4f".format(it) }}"
        }

    /**
     * `first/interior-median` and `last/interior-median` per trial — how many interior
     * pages each endpoint page costs.
     *
     * The ratio and not just the two magnitudes, because that is the figure that survives
     * a machine change: an open costing several hundred interior pages (measured 392x-1129x
     * at 1e5, 2026-08-20, M2 Pro, harness e4c04bb2) is a statement about the
     * O(n)-vs-O(limit) shape, while "34 ms" is a statement about this laptop.
     */
    private fun ratiosAgainstInterior(walks: List<PagedWalkOutcome>): String =
        walks.withIndex().joinToString(separator = "; ") { (index, walk) ->
            val interior = walk.interiorMedianPageMs
            val trial = "t${index + 1}"
            if (interior == null || interior <= 0.0) {
                "$trial=n/a (no interior page, or an interior median of zero)"
            } else {
                "$trial first=%.1fx last=%.1fx".format(
                    (walk.firstPageMs ?: 0.0) / interior,
                    (walk.lastPageMs ?: 0.0) / interior,
                )
            }
        }

    /**
     * The drive's `maxGap` beside the walk's worst page, that page's position, and the two
     * wall times that say whether the drive could have observed that page at all — per
     * trial, never as two means.
     *
     * Per trial matters twice over. Two trial means can coincide while no single trial's
     * figures do, and it is the single-trial coincidence that says one page carried that
     * trial's stall. And when they *disagree*, the fourth and fifth figures are what
     * explain it: `maxGap` is measured over the drive's window only, so a walk whose total
     * page wall exceeds the drive's duration runs on past the window's end and its late
     * pages — the closing frontier's page above all — cannot appear as a gap in the
     * drive's arrival stream however expensive they are. A trial with a large late max page
     * and a small `maxGap` is that containment failing, not the page being cheap.
     */
    private fun gapVersusPage(
        gaps: List<Double>,
        driveDurations: List<Double>,
        walks: List<PagedWalkOutcome>,
    ): String = walks.indices.joinToString(separator = "; ") { index ->
        val walk = walks[index]
        "t${index + 1} maxGap=%.4f page=%.4f @%d/%d walkWall=%.4f drive=%.4f".format(
            gaps[index],
            walk.maxSinglePageMs,
            walk.maxSinglePagePosition,
            walk.pages,
            walk.totalPageWallMs,
            driveDurations[index],
        )
    }

    /**
     * Each trial's series as its head, its tail, and an elision — the readable form of a
     * 588-page series.
     *
     * The whole series per trial is what the bead allowed and what a reader almost never
     * wants: at 1e5 it is ~600 numbers x 5 trials of mostly identical interior pages. The
     * head and tail are where the two O(n) passes live, and the summary lines above carry
     * the interior. A position outside the excerpt is still named by the `@position`
     * figure, so nothing about the attribution depends on the elided middle.
     */
    private fun walkShapes(walks: List<PagedWalkOutcome>): String =
        walks.withIndex().joinToString(separator = "; ") { (index, walk) ->
            val series = walk.pageLatenciesMs
            val rendered = if (series.size <= SHAPE_HEAD + SHAPE_TAIL) {
                series.map { "%.4f".format(it) }.toString()
            } else {
                val head = series.take(SHAPE_HEAD).map { "%.4f".format(it) }
                val tail = series.takeLast(SHAPE_TAIL).map { "%.4f".format(it) }
                "[${head.joinToString()}, … ${series.size - SHAPE_HEAD - SHAPE_TAIL} " +
                    "interior pages elided …, ${tail.joinToString()}]"
            }
            "t${index + 1}=$rendered"
        }

    /**
     * Print one experiment's results, having first pushed every number through the F3
     * result model (`[BEN1-23]`..`[BEN1-27]`).
     *
     * The `FindingsTable` construction is the load-bearing part, not the printing: it is
     * what refuses a table mixing drives or environments, and every row it holds carries
     * an explicit `Drive.REAL` and a `RunEnvironment` that could not exist without a JVM,
     * a heap, a CPU, an OS, a stated statistic and a harness commit. `classify` is reported
     * per row rather than enforced — a low-trial probe is expected to be `Unreportable`
     * (see `TrialStats`), and hiding that would be the dishonesty the gate exists to
     * prevent. `Findings.entry` is deliberately NOT called: it would refuse an
     * `Unreportable` row outright, and rendering the markdown entry is the sibling
     * comparison task's step.
     *
     * **That refusal is not a limit a longer sweep lifts, and the sibling entry must plan
     * for it.** At the maxGap variability actually measured here a `Reportable`
     * classification needs `1e4`-`1e5` trials (`TrialStats`' KDoc does the arithmetic from
     * the measured numbers), which no sweep can afford — so an entry for E2/E3 has to
     * state the dispersion and its `Unreportable` classification in its own words rather
     * than obtain them from `Findings.entry`, and widening `NOISE_FLOOR` to make the
     * writer accept these rows would be the dishonesty that gate exists to prevent.
     */
    private fun report(
        experiment: String,
        scale: SetScale,
        env: RunEnvironment,
        rows: List<Pair<String, TrialStats>>,
        extra: List<String>,
    ) {
        val results = rows.map { (_, stats) ->
            BenchResult(
                value = stats.mean,
                unit = "ms",
                dispersion = stats.dispersion,
                drive = BoundedReadFixtures.DRIVE,
                env = env,
            )
        }
        // Constructed, not merely computed: this is the type that refuses a
        // mixed-drive or mixed-environment table, so building it is the check.
        val table = FindingsTable(results, labels = rows.map { it.first })

        println("=== $experiment ${scale.label} (${scale.elements} elements) ===")
        println(
            "env: JVM ${env.jvmVendor}/${env.jvmVersion} · heap ${env.heapSettings} · " +
                "${env.cpuModel}, ${env.coreCount} cores, ${env.os}"
        )
        println("harness: ${env.harnessCommitSha} · drive=${table.drive} · ${env.jmhMode}")
        table.results.zip(table.labels!!).forEachIndexed { index, (result, label) ->
            println(
                "$label: ${rows[index].second.describe("ms")} " +
                    "[F3: ${result.value} ± ${result.dispersion} ${result.unit}, " +
                    "relDispersion=${result.relativeDispersion}, ${classify(result)}]"
            )
        }
        extra.forEach { println("  $it") }
        println(
            "  samples: " + rows.joinToString(separator = "; ") { (label, stats) ->
                "$label=${stats.samples.map { "%.4f".format(it) }}"
            }
        )
    }

    private companion object {
        /**
         * Pages shown at each end of a walk's series by [walkShapes].
         *
         * Four and two rather than a round number: the open's page is one, and the three
         * after it are what says the open is a spike rather than a warm-up ramp; the
         * close's page is one, and the page before it is its own such control.
         */
        const val SHAPE_HEAD: Int = 4
        const val SHAPE_TAIL: Int = 2
    }
}
