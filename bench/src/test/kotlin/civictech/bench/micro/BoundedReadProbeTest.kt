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
 * `ManagedHost.readState` page by page against the real target cell under the identical
 * live-traffic drive.
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
 * `scale.elements + WARMUP_ADDS + k * DRIVE_ADDS` — at 1e3 with three trials, 1,000 rising
 * to 26,000. That drift is the original harness's own (one monotone `seedCounter` shared by
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
            }
        }

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
                // are necessary but NOT sufficient for "the union is a snapshot", because
                // an observed-remove mints no tag (SetCell.readBounded's KDoc). Under a
                // concurrent add drive the expected answer is UNSTABLE, and an unstable
                // stamp is the walk working, not failing.
                "frontier stable (opening stamp == closing stamp): " +
                    "$frontierStableTrials/${BoundedReadFixtures.TRIALS} trials — " +
                    "instability is expected under a concurrent add drive, and stability " +
                    "would NOT prove the union is a snapshot (observed-remove mints no tag)",
            ),
        )
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
     * comparison task's step, on a sweep whose trial count earns it.
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
}
