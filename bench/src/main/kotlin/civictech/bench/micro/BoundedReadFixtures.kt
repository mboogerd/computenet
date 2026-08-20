package civictech.bench.micro

import civictech.bench.Drive
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.ReadCaveat
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.lookup
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.testkit.awaitDrained
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// =======================================================================================
// V1C-BENCH E1-E3, re-expressed against the LANDED bounded-read surface [BEN1-22], BS-15.
//
// ## What this file is, and what it is not
//
// `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`'s KDoc cites three numbers as
// load-bearing design justification — a ~28 ms live-traffic stall from one whole-state
// copy of a 10^5-element cell, an ~85-99% stall reduction from 200-entry paging, and a
// ~1.7-2.4x total-work premium for that paging. Their source is
// `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`, produced by
// `tickets/V1C-BENCH.md`, whose own instruction was "do not check in a benchmark test".
// Its harness therefore lived as two temporary JUnit files that were DELETED before that
// ticket's diff was finalized (that document's §1 and Appendix A/B say so in as many
// words), so the tree as it stands cannot re-derive a single one of those figures.
//
// This file, `BoundedReadBenchmark.kt` and `BoundedReadProbeTest.kt` are that harness,
// re-expressed as permanent `:bench` artifacts. They are the MECHANISM, not the answer:
// this task's deliverable is artifacts that run, and the comparison entry — whether the
// numbers reproduce within dispersion, and if not which of {harness difference, code
// change since C7, machine difference} explains it — belongs to a sibling task, which
// runs them at full scale and writes `doc/bench/findings.md`. Nothing here writes that
// file, and neither `30-bounded-read-measurement.md` nor `tickets/V1C-BENCH.md` is
// touched by this change.
//
// ## Invariants this re-expression holds, because they are what makes it a replication
//
// - **Real host, never a simulation.** The original measured a threaded
//   `ManagedHost`/`VirtualThreadScheduler` and said so per experiment; every fixture
//   here does the same, and every result it constructs carries [Drive.REAL]. There is
//   deliberately no SIM variant: a `SimulationController` interleaves the read and the
//   traffic on one deterministic thread, which is not the question — the question is what
//   a priority-0 copy does to a real host's own queue.
// - **No constant is touched.** `MAX_CELLS`/`BUDGET_MS`
//   (`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:558,569`) and
//   `MAX_ROWS`/`MAX_BYTES` (`ValueEncoder.kt:53,56`) stay byte-identical to main
//   [BEN1-37], and `inspect/src` is not in this diff at all. [PAGE_LIMIT] below is a
//   literal 200 in bench code, citing the original document's own 200-entry page rather
//   than reaching into `:inspect` for a number.
// - **Results go through the F3 model.** [probeRunEnvironment] builds a
//   `civictech.bench.RunEnvironment`; probe results are `BenchResult`s inside a
//   `FindingsTable`, so a result that cannot state its environment, its dispersion or its
//   drive cannot be constructed at all [BEN1-23..27].
//
// ## The three deliberate differences from the original method
//
// Written down here, once, because the sibling comparison task has to be able to cite
// them rather than rediscover them:
//
// 1. **E3 pages for real.** The original E3 could not: `BoundedStateful`, `StateRead`,
//    `StatePage`, `Cursor` and `ManagedHost.readState` were all forbidden to it, so it
//    simulated paging with a bystander `PageCursorCell` holding a plain `List<Int>` and
//    answering `snapshot()` with the next 200-element slice. All five of those types have
//    since landed, and `SetCell` is the reference `BoundedStateful`, so [pagedWalk] drives
//    the real `ManagedHost.readState` page-by-page against the real target `SetCell`. This
//    is a STRICTLY HARDER counterfactual than the original: a `SetWalk` freezes the
//    enumeration order in one O(n) pass at walk open and recomputes the frontier in
//    another at walk close (`SetCell.openWalk`'s KDoc), and each page reads the live tag
//    maps under `SetCell.stateLock` — none of which the `List<Int>` stand-in paid. The
//    original document's own "what could not be done" section predicted exactly this
//    ("tag-set filtering, frontier computation ... could differ from this document's
//    numbers"). A divergence here is therefore a candidate *harness difference* in the
//    sibling entry's three-way classification, and must not be reported as a code change.
// 2. **E2/E3 measure one metric family, on one shape of drive.** The original's unpaced
//    8,000-add burst is reproduced verbatim ([DRIVE_ADDS]), including its consequence:
//    "dip" means the largest single stall in the target's own arrival stream (maxGap), not
//    a degradation of a paced steady rate. [DriveOutcome.maxGapMs] is that metric.
//    **On E3, though, the original's own drive was not 8,000 and the document contradicts
//    itself about it.** §5's prose says "the identical 8,000-add live-traffic drive from
//    E2", but Appendix A's `E3` body — the code that actually ran — sets `m = 5_000`, takes
//    ONE untimed trial with no warmup drive, and starts its pager thread at t0 rather than
//    [READ_DELAY_MS] in. This re-expression follows the prose and E2's shape (8,000 adds,
//    one warmup drive, [TRIALS] trials, the 1 ms delay) so E2 and E3 stay comparable to
//    each other, which is what §6's comparison is made of. Consequence for the sibling
//    entry: §5's maxGap column was NOT measured under the drive this file's E3 uses, so an
//    E3-against-§5 divergence is a candidate harness difference on this count too,
//    independently of the real-paging one.
// 3. **E1 is JMH; E2/E3 are `@Tag("bench")` JUnit probes.** See
//    `BoundedReadProbeTest`'s KDoc for why that split, which the feature left to the
//    implementer's judgment, went the way it did.
//
// ## 4. NOT a difference, but the property that most changes how a number reads
//
// **The target's state grows monotonically across a condition's trials, so a `SetScale`
// names what the target was PRE-SEEDED to and not what a later trial measured.** Every add
// carries a fresh element — a repeat would be absorbed by the effective-only rule and would
// measure the absorb path instead of the merge path — and an OR-set's `remove` tombstones
// rather than deletes, so nothing ever shrinks. After the warmup drive and k timed drives
// the target holds `scale.elements + WARMUP_ADDS + k * DRIVE_ADDS` elements. At 1e3 with
// [TRIALS] (five) that is 1,000 rising to 42,000: the last trial's cell is 42x the first's,
// and E3's page count rises with it.
//
// This is **the original harness's own behaviour**, reproduced deliberately rather than
// corrected: `30-bounded-read-measurement.md` Appendix A's `Rig` holds ONE monotone
// `seedCounter` shared by `seed()` and `driveTimed()`, and its E2 ran five 8,000-add trials
// per condition on one rig — so its "10^3" condition finished against a ~42,000-element
// cell, the same figure this file now reaches at the same trial count. Silently fixing the
// drift here would produce numbers that are not comparable to §4/§5,
// which is the one thing a replication may not do. It is very likely part of why §4's own
// 10^3 rows look anomalous (a *slower* baseline drain at 10^3 than at 10^5) and why §6 calls
// 10^3 "small/unclear at this scale".
//
// What this file adds is visibility: [LiveTrafficRig.elementsAdded] reports the real size
// per trial and the probes print it, so a sibling comparison task can see the drift instead
// of inferring it. Removing the drift would be a *method change* and belongs to whoever
// decides to depart from the original, with the departure stated.
// =======================================================================================

/**
 * The three set sizes V1C-BENCH swept — 10^3 / 10^4 / 10^5 elements.
 *
 * An enum rather than an `@Param` list of strings so JMH fills
 * `BoundedReadBenchmark`'s parameter from the enum itself (the same mechanism
 * `OperatorThroughputBenchmark` uses for `Subject`), and so the JUnit probes and the
 * benchmark cannot drift onto different scales.
 */
enum class SetScale(val elements: Int, val label: String) {
    N1E3(1_000, "1e3"),
    N1E4(10_000, "1e4"),
    N1E5(100_000, "1e5"),
}

/**
 * E1's direct case: a populated but **unhosted** `SetCell<Int>` whose `Stateful.snapshot()`
 * is called on the caller's thread.
 *
 * Unhosted deliberately, following the original method (§3 of the measurement document:
 * "populate a `SetCell<Int>` to `n` elements via direct (unhosted) `inlet.call.add()`,
 * calling `Stateful.snapshot()` directly"). Hosting would add a spawn-time checkpoint
 * (one extra `snapshot()` call, G-26) and change nothing about the copy's cost, so the
 * unhosted form is both faithful and the cheaper fixture.
 */
class DirectCopySubject internal constructor(
    val scale: SetScale,
    private val cell: SetCell<Int>,
) {
    /** One whole-state copy, on the calling thread. */
    fun snapshot(): Serializable = cell.snapshot()
}

/**
 * E1's end-to-end case: the same populated `SetCell<Int>` spawned on a real
 * `ManagedHost`, read through `ManagedHost.snapshotOf` (submit + dequeue + copy + future
 * completion).
 *
 * [close] shuts the scheduler down; a subject that is not closed leaks a
 * `VirtualThreadScheduler` drain thread for the life of the JVM.
 */
class HostedCopySubject internal constructor(
    val scale: SetScale,
    val host: ManagedHost,
    val ref: CellRef,
    private val stop: () -> Unit,
) : AutoCloseable {

    /**
     * One whole-state copy end to end.
     *
     * `snapshotOf` completes with `null` for a cell that is not `Stateful`, for a
     * terminated scheduler, and when the cell's own `snapshot()` throws — three
     * conditions that are all bugs in this fixture rather than measurements, so a null is
     * refused here instead of being timed and averaged in as a very fast copy.
     */
    fun snapshotOf(): Serializable = checkNotNull(host.snapshotOf(ref).get()) {
        "snapshotOf(${scale.label}) completed with null — the cell is not hosted, not " +
            "Stateful, the scheduler is terminated, or snapshot() threw; none of those " +
            "is a measurement"
    }

    override fun close() = stop()
}

/**
 * What one timed live-traffic drive observed at the target's collector — E2's and E3's
 * shared observable.
 *
 * @param durationMs wall time from the first enqueue until the last of the drive's deltas
 *   has arrived at the collector.
 * @param maxGapMs **the dip metric.** The largest interval between two consecutive
 *   arrivals, widened at both ends by the interval from the drive's start to the first
 *   arrival and from the last arrival to the drive's end — so a stall that lands at the
 *   very beginning or the very end of the drive is counted, not hidden. This is the
 *   original document's own definition (§4 and Appendix A's `driveTimed`), reproduced
 *   because §6's whole comparison is stated in it.
 * @param arrivals how many deltas the collector actually observed during the drive.
 */
data class DriveOutcome(
    val durationMs: Double,
    val maxGapMs: Double,
    val arrivals: Int,
) {
    /** Deltas per second over [durationMs] — the original's "throughput" column. */
    val throughputPerSecond: Double get() = arrivals / (durationMs / 1_000.0)
}

/**
 * What one sequential paged walk of a cell's state observed — E3's own cost side.
 *
 * @param pages how many `readState` round trips the walk took.
 * @param entries how many whole entries the pages carried in total.
 * @param pageLatenciesMs per-page wall time, in walk order.
 * @param frontierStable whether the walk's opening and closing `TagFrontier` stamps are
 *   equal — `StatePage`'s own stability check. **Necessary but not sufficient for this
 *   family**: an OR-set's observed-remove mints no tag, so a mid-walk removal of an
 *   already-paged element leaves both stamps equal (`SetCell.readBounded`'s KDoc states
 *   this, and it is the family's tag algebra rather than a paging defect). Reported
 *   because it is a fact the original E3 could not observe at all — its `List<Int>`
 *   stand-in carried no frontier — never as evidence the union is a snapshot.
 * @param caveats the union of every `ReadCaveat` any page declared.
 */
data class PagedWalkOutcome(
    val pages: Int,
    val entries: Int,
    val pageLatenciesMs: List<Double>,
    val frontierStable: Boolean,
    val caveats: Set<ReadCaveat>,
) {
    /** Summed page wall time — the numerator of the original §6 total-work premium. */
    val totalPageWallMs: Double get() = pageLatenciesMs.sum()

    /** The largest single page's wall time — the original E3's "max single page" column. */
    val maxSinglePageMs: Double get() = pageLatenciesMs.maxOrNull() ?: 0.0

    /**
     * **Where** the [maxSinglePageMs] page fell, 1-based in walk order; `0` for a walk
     * that took no pages at all. Ties resolve to the earliest such page.
     *
     * This is the accessor that turns E3's "max single page" column from an inference into
     * an attribution. The magnitude alone says how long the worst page took; only its
     * position says *which* part of the walk paid for it, and the three positions mean
     * three different things:
     *
     * - **`1` — the open.** `SetCell.openWalk` takes `stateLock` and makes a full O(n)
     *   pass over both `adds` and `dels`, freezing the enumeration order and merging every
     *   tag into the opening `TagFrontier`; that open happens inside the FIRST
     *   `readBounded` call, i.e. inside one scheduler task.
     * - **[pages] — the close.** The closing frontier is recomputed in another O(n) pass on
     *   the final page.
     * - **anything between** — per-page work, which is the only one of the three that a
     *   smaller page limit would reduce.
     *
     * What it does NOT establish: that the page it names is what a concurrent drive's
     * `DriveOutcome.maxGapMs` measured. Those two figures are timed on different threads
     * against no common clock, so their agreement is evidence and not an identity — which
     * is why `BoundedReadProbeTest`'s E3 prints them side by side per trial rather than
     * only as trial means.
     */
    val maxSinglePagePosition: Int
        get() = if (pageLatenciesMs.isEmpty()) 0 else pageLatenciesMs.indexOf(maxSinglePageMs) + 1

    /** The FIRST page's wall time — the open's page — or `null` if the walk took none. */
    val firstPageMs: Double? get() = pageLatenciesMs.firstOrNull()

    /** The LAST page's wall time — the closing frontier's page — or `null` if none. */
    val lastPageMs: Double? get() = pageLatenciesMs.lastOrNull()

    /**
     * The median of the **interior** pages — every page but the first and the last — or
     * `null` when the walk took fewer than three pages and so has no interior.
     *
     * The reference the two endpoint pages are read against: an endpoint that costs many
     * interior pages is a fixed per-walk cost, while an endpoint indistinguishable from
     * the interior is not. The median rather than the mean, because one page delayed by
     * an unrelated scheduler or GC event would move a mean and is exactly the noise this
     * comparison must survive. Upper median for an even count, matching [TrialStats.median].
     */
    val interiorMedianPageMs: Double?
        get() {
            val interior = pageLatenciesMs.drop(1).dropLast(1)
            if (interior.isEmpty()) return null
            val sorted = interior.sorted()
            return sorted[sorted.size / 2]
        }
}

/**
 * Whether [BoundedReadFixtures.rig] connects the graph it builds — the rig's **negative
 * control**, in the same spirit and for the same reason as `Graphs.kt`'s `Wiring`.
 *
 * Every observable a live-traffic measurement rests on is read at the collector, so a rig
 * whose links were silently never established would seed, drive and quiesce without
 * throwing and simply report `arrivals == 0` and `maxGapMs` computed over nothing. That
 * is indistinguishable from a working rig unless something asserts the difference, which
 * is what [UNLINKED] exists to let `BoundedReadFixturesTest` do on every default run.
 */
enum class RigWiring { LINKED, UNLINKED }

/**
 * The `source -> target` OR-set gossip rig E2 and E3 both drive, on a real
 * `ManagedHost`/`VirtualThreadScheduler`.
 *
 * Shape, reproduced from the original method (§4): a `SetCell<Int>` `source` linked
 * `outlet -> deltaInlet` into a `SetCell<Int>` `target` — the replica-gossip merge path,
 * where only new tag information re-emits — with a bystander [ArrivalCollectorCell] on
 * `target.outlet` timestamping each arrival. **Both cells live on ONE host**, which is the
 * point rather than an economy: one host is one virtual thread draining both cells' work,
 * so the concurrent read and the live traffic genuinely contend for the same execution
 * context. Splitting them across hosts would measure nothing.
 */
class LiveTrafficRig internal constructor(
    val scale: SetScale,
    val wiring: RigWiring,
    val host: ManagedHost,
    private val scheduler: VirtualThreadScheduler,
    private val sourceApi: SetApi<Int>,
    /** The target cell's ref — the subject of E2's `snapshotOf` and E3's `readState` walk. */
    val targetRef: CellRef,
    private val collector: ArrivalCollectorCell,
    private val stop: () -> Unit,
) : AutoCloseable {

    /**
     * Element values are drawn from one strictly increasing counter across seeding and
     * every drive, so no add is ever a re-delivery of a tag the target already holds — a
     * repeat would be absorbed by the effective-only rule and would measure the absorb
     * path instead of the merge path (the same discipline `Deltas.kt` states for the
     * throughput generators).
     */
    private var nextElement = 0

    /**
     * Distinct elements pushed through `source` since the rig was built — and therefore,
     * because nothing is ever removed and no add is ever a repeat, the target's exact live
     * membership.
     *
     * **This grows across trials, and that is the single most important thing to know when
     * reading an E2 or E3 number.** See this file's header, item 4: the drift is the
     * original harness's own behaviour, reproduced deliberately, and it means a probe's
     * `SetScale` names the state the target was *pre-seeded* to, not the state a later
     * trial measured. Exposed here — the original never reported it — so a probe can print
     * the real size per trial and a reader is not left to reconstruct it.
     */
    val elementsAdded: Int get() = nextElement

    /** Deltas the collector has observed since the rig was built. */
    val arrivals: Long get() = collector.total.get()

    /**
     * Pre-load the target to [scale] elements, off any timer, and return once every one
     * of them has landed.
     *
     * Bounded, per AGENTS.md: a seed that does not drain fails with a message naming how
     * far it got rather than hanging until the 5-minute per-method timeout turns it into
     * a `TimeoutException` whose stack names nothing.
     */
    fun seed() {
        drive(scale.elements)
    }

    /**
     * Enqueue [adds] live adds through `source` as fast as this thread can, and measure
     * the target's own arrival stream while they drain.
     *
     * **Unpaced, deliberately** — this is the original's burst, not a paced rate, and
     * §4's note on what that does to the meaning of "dip" is reproduced on
     * [DriveOutcome.maxGapMs].
     */
    fun drive(adds: Int): DriveOutcome {
        require(adds > 0) { "adds must be positive, was $adds" }
        val before = collector.total.get()
        collector.arrivalNanos.clear()
        val t0 = System.nanoTime()
        val api = sourceApi.inlet.call
        repeat(adds) { api.add(nextElement++) }

        val timeoutMs = BoundedReadFixtures.DRAIN_TIMEOUT_MS
        if (wiring == RigWiring.LINKED) {
            val deadline = t0 + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (collector.total.get() < before + adds) {
                check(System.nanoTime() < deadline) {
                    "live-traffic drive did not drain within ${timeoutMs}ms " +
                        "(scale=${scale.label} adds=$adds, arrived " +
                        "${collector.total.get() - before}/$adds)"
                }
            }
        } else {
            // No links, so no arrival can ever satisfy the condition above. The drain
            // fence is still positive evidence the host emptied its queue, which is what
            // makes `arrivals == 0` an assertion about the wiring rather than about
            // whether the test waited long enough.
            scheduler.awaitDrained("unlinked rig ${scale.label}", timeoutMs)
        }
        val t1 = System.nanoTime()

        val times = collector.arrivalNanos.toList().sorted()
        var maxGap = 0L
        for (i in 1 until times.size) maxGap = maxOf(maxGap, times[i] - times[i - 1])
        if (times.isNotEmpty()) {
            maxGap = maxOf(maxGap, times.first() - t0)
            maxGap = maxOf(maxGap, t1 - times.last())
        }
        return DriveOutcome(
            durationMs = (t1 - t0) / 1_000_000.0,
            maxGapMs = maxGap / 1_000_000.0,
            arrivals = times.size,
        )
    }

    override fun close() = stop()
}

/**
 * The bystander that makes the dip observable: it counts arrivals on `target.outlet` and
 * timestamps each one.
 *
 * `target` re-emits on every genuinely new tag it merges (the effective-only rule), so one
 * arrival per live add is exactly what a healthy rig produces. The timestamps are the raw
 * material of [DriveOutcome.maxGapMs] and nothing else; they are written by the host's
 * drain thread into a concurrent queue and read by the driving thread only after that
 * thread has observed the drive complete.
 */
class ArrivalCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())

    /** Arrival instants, in arrival order (sorted by the reader anyway — see `drive`). */
    val arrivalNanos = ConcurrentLinkedQueue<Long>()

    /** Total arrivals since construction; never cleared, unlike [arrivalNanos]. */
    val total = AtomicLong(0)

    init {
        inlet.onEach {
            arrivalNanos += System.nanoTime()
            total.incrementAndGet()
        }
    }
}

/**
 * Descriptive statistics over one probe's trials, plus the **explicitly stated dispersion
 * equivalent** `civictech.bench.BenchResult` requires.
 *
 * `BenchResult.dispersion` is documented as "JMH's reported error at 99.9% confidence, or
 * an explicitly stated equivalent". A JUnit-shaped probe has no JMH error to report, so
 * this is that equivalent, stated in full: [dispersion] is the half-width of the
 * **two-sided Student-t 99.9% confidence interval of the mean** over the trial samples,
 * `t(0.9995, n-1) * stddev / sqrt(n)`, using the sample standard deviation (Bessel's
 * correction).
 *
 * Three limits of that number, stated here rather than only in a bead comment, because
 * this is the file the next reader changes:
 *
 * - **It needs at least two trials.** With one sample there is no dispersion to state and
 *   construction refuses, rather than reporting `0.0` — a zero dispersion is a claim of a
 *   perfectly repeatable measurement, which one trial is not evidence for.
 * - **Student-t, not the normal quantile (3.291), and that is not a detail.** At three
 *   trials — the smallest sample this class accepts, and [BoundedReadFixtures.TRIALS]'
 *   earlier value — the t factor is 31.599 against the normal's 3.291, nearly 10x, so a
 *   normal approximation would report an interval an order of magnitude too tight and
 *   `civictech.bench.classify` would call a noisy result `Reportable`. At the five trials
 *   `TRIALS` now defaults to, the factor is 8.610 — the table is indexed by df and df is
 *   `n-1`, so five samples read `T_999[4]` and not `T_999[5]`'s 6.869 — roughly 2.6x the
 *   normal quantile: smaller than the three-trial factor, and still the difference
 *   between a stated interval and an understated one. Being conservative here is the
 *   whole reason the choice is spelled out.
 * - **A low-trial probe result is therefore expected to classify `Unreportable`**, and
 *   that is the honest outcome, not a harness fault. Widening `NOISE_FLOOR` is never the
 *   answer — but for THIS probe's statistic neither is raising the trial count, and that
 *   has to be said plainly rather than left for a sweep to discover. Measured here at
 *   1e3 (2026-08-19, M2 Pro, 3 trials, harness 5bcec676): relative dispersion 3.6-14.8,
 *   i.e. a trial-to-trial coefficient of variation of 0.20-0.81 for maxGap. [dispersion]
 *   falls as `t/sqrt(n)` and [tCritical999] floors at 3.850, so reaching the 0.005 floor
 *   needs `n >= (3.850 * cv / 0.005)^2` — ~2.4e4 trials at the quietest observed
 *   variability and ~3.9e5 at the noisiest, each trial an 8,000-add drive that also grows
 *   the target by 8,000 elements. maxGap is a worst-case order statistic on a shared
 *   machine; it does not concentrate. So a `FindingsTable` built from these probes is
 *   expected to be refused by `Findings.entry` at any trial count a sweep can afford —
 *   see `BoundedReadProbeTest.report`.
 */
class TrialStats(raw: List<Double>) {

    /** The trial samples, in trial order. */
    val samples: List<Double> = raw.toList()

    init {
        require(raw.size >= 2) {
            "TrialStats needs at least 2 samples to state a dispersion, got ${raw.size}"
        }
        require(raw.all { it.isFinite() }) { "every sample must be finite, got $raw" }
    }

    private val sorted = samples.sorted()

    val n: Int get() = samples.size
    val mean: Double = samples.average()
    val median: Double = sorted[sorted.size / 2]
    val p95: Double = sorted[((sorted.size * 95) / 100).coerceAtMost(sorted.size - 1)]
    val min: Double = sorted.first()
    val max: Double = sorted.last()

    /** Sample standard deviation (n-1 denominator). */
    val stdDev: Double = kotlin.math.sqrt(
        samples.sumOf { (it - mean) * (it - mean) } / (samples.size - 1),
    )

    /** The 99.9% two-sided confidence half-width of [mean]. See this class's KDoc. */
    val dispersion: Double =
        tCritical999(samples.size - 1) * stdDev / kotlin.math.sqrt(samples.size.toDouble())

    /** `mean ± dispersion`, with median/min/max alongside — the line a probe prints. */
    fun describe(unit: String): String =
        "n=$n mean=%.4f ± %.4f $unit (99.9%% CI) median=%.4f p95=%.4f min=%.4f max=%.4f"
            .format(mean, dispersion, median, p95, min, max)

    companion object {
        /**
         * Two-sided Student-t critical values at 99.9% confidence (0.0005 per tail), by
         * degrees of freedom, from the standard table.
         *
         * Index 0 is unused so that `T_999[df]` reads directly. Beyond the table the
         * df=20 value is reused, which is **conservative**: t decreases monotonically in
         * df towards the 3.291 normal quantile, so 3.850 over-states rather than
         * under-states the interval for any larger sample.
         */
        private val T_999 = doubleArrayOf(
            Double.NaN,
            636.619, 31.599, 12.924, 8.610, 6.869,
            5.959, 5.408, 5.041, 4.781, 4.587,
            4.437, 4.318, 4.221, 4.140, 4.073,
            4.015, 3.965, 3.922, 3.883, 3.850,
        )

        fun tCritical999(df: Int): Double {
            require(df >= 1) { "df must be >= 1, was $df" }
            return T_999[minOf(df, T_999.size - 1)]
        }
    }
}

/**
 * The fixtures E1's benchmark and E2/E3's probes are built from [BEN1-08].
 *
 * Everything that touches `:kernel` or `:testkit` lives here, in `bench/src/main`, and
 * neither `bench/src/jmh` nor `bench/src/test` reaches past this API — the same split
 * `Graphs.kt`/`OperatorThroughputBenchmark.kt` already use, and the reason the JMH source
 * set needs no dependency of its own.
 */
object BoundedReadFixtures {

    /**
     * The page limit E3 walks at: **200 entries**, a literal here, citing
     * `30-bounded-read-measurement.md` §5 ("answers each `snapshot()` call with the next
     * 200-element slice") and §6's "200-element slices".
     *
     * Deliberately NOT read from `civictech.inspect.ValueEncoder.MAX_ROWS`, which is also
     * 200 today. [BEN1-37] forbids this diff from changing that constant, and `:bench`
     * does not depend on `:inspect` at all (its `ModuleDependencyTest` sibling would
     * refuse), so importing it is not even available; but the stronger reason is that the
     * number being replicated is the *measurement's* page size. If someone later retunes
     * the encoder budget, this replication must keep walking at 200 or it stops being a
     * replication.
     *
     * At 200 entries the advisory `StateRead.byteBudget` does not bind: `SetCell`
     * estimates 64 bytes per entry plus 48 per tag, so a 200-entry page of
     * single-add-tag elements estimates 22,400 bytes against the 50,000 default. The
     * limit governs, which is what makes "pages of 200" true rather than aspirational.
     */
    const val PAGE_LIMIT: Int = 200

    /**
     * The live-traffic burst E2 and E3 drive: **8,000 adds**, unpaced, exactly the
     * original's `m`.
     */
    const val DRIVE_ADDS: Int = 8_000

    /**
     * Trials per condition: the original's five, raised here from an earlier three.
     *
     * The 2026-08-19 replication entry (`computenet-x9e.6.4`) ran this file at three
     * trials — the smallest sample [TrialStats] can state a dispersion over — and
     * recommended raising it to five in the same breath it disclosed why: the whole
     * six-test E2/E3 probe suite runs in ~2 s, so cost was never the obstacle, and a
     * fifth trial "makes the medians materially more robust against exactly the outlier
     * trials E2's 10³/10⁴ baselines show" without changing any `Unreportable`
     * classification (five trials still needs on the order of 10⁴ trials to reach
     * `NOISE_FLOOR`; see that entry's "What F3 refused" section for the arithmetic).
     * `computenet-xlst` raises it on that recommendation.
     */
    const val TRIALS: Int = 5

    /**
     * Discarded warmup drives per condition, before any trial is timed. One, against the
     * original's single 1,000-add warmup drive per rig; kept non-zero because
     * `RunEnvironment.warmupIterations` refuses to be non-positive, and because the first
     * drive against a fresh host pays JIT and link-path costs no later drive does.
     */
    const val WARMUP_TRIALS: Int = 1

    /** The warmup drive's size — the original's discarded 1,000-add drive. */
    const val WARMUP_ADDS: Int = 1_000

    /**
     * How long into the drive the concurrent read is fired, in milliseconds. The original
     * fired its `snapshotOf` "~1 ms into the drive" so that the copy lands against an
     * already-queued backlog of data-priority work rather than an empty queue — which is
     * the whole mechanism under test (a priority-0 submit jumping the queue).
     */
    const val READ_DELAY_MS: Long = 1

    // JMH knobs for E1, declared here rather than as literals in the annotations, for the
    // reason `OperatorThroughputBenchmark`'s KDoc gives for doing the same: a renderer
    // recording the `RunEnvironment` of a sweep must not be able to disagree with the
    // configuration the benchmark actually ran under.
    //
    // **Raised to the repository's forks=5 / warmup=5 / measurement=5 convention on
    // computenet-x9e.6.4**, the full-scale sweep, from the artifact's original
    // 1 fork / 3 warmup / 5 measurement. Two reasons, both discovered by running it:
    //
    // 1. A single fork reports an error bar computed over one JVM's iterations, so
    //    fork-to-fork variation — the dominant term at 10^5, where G1 young collections
    //    set the tail (the original document's §8) — is invisible to it rather than
    //    absent from it. Five forks put that variation inside the reported dispersion.
    // 2. Raising the sample at the command line (`-f`, `-wi`, `-i`) is NOT an equivalent
    //    workaround, and an earlier revision of this comment said it was. A renderer
    //    building a `RunEnvironment` reads these constants; a run whose real fork count
    //    came from a flag would be published under the configuration declared here, which
    //    is precisely the disagreement these constants exist to prevent. Raising the
    //    sample means editing this block, which is why the block is editable.
    //
    // The sweep at this config costs ~7 minutes of wall clock for the six combinations
    // (2 methods x 3 scales), measured on computenet-x9e.6.4; the 10^5 case re-seeds
    // 100,000 elements per fork, and this repository's benchmark runs share a machine
    // with concurrent agent builds.

    /** E1's JMH mode: per-call latency, which is what the original E1 reported. */
    const val E1_JMH_MODE: String = "AverageTime"

    /** E1 forks. */
    const val E1_FORKS: Int = 5

    /** E1 warmup iterations. */
    const val E1_WARMUP_ITERATIONS: Int = 5

    /** E1 measurement iterations. */
    const val E1_MEASUREMENT_ITERATIONS: Int = 5

    /** Seconds per E1 iteration. */
    const val E1_ITERATION_SECONDS: Int = 1

    /**
     * Hang backstop for a drive or a drain, in milliseconds. Not a convergence budget:
     * crossing it means the host never drained, and the fixture fails saying so — the
     * discipline `awaitDrained`'s own KDoc sets out.
     */
    const val DRAIN_TIMEOUT_MS: Long = 120_000

    /** The system property a probe reads its harness commit from. */
    const val HARNESS_SHA_PROPERTY: String = "civictech.bench.harnessSha"

    /**
     * E1's direct subject: an unhosted `SetCell<Int>` populated to [scale] elements.
     *
     * Population goes through `inlet.call.add`, one add per element, so every element
     * carries exactly one locally minted add-tag and no tombstones — the state shape the
     * original measured, and the shape [PAGE_LIMIT]'s byte arithmetic assumes.
     */
    fun directCopySubject(scale: SetScale): DirectCopySubject {
        val cell = SetCell<Int>()
        val api = cell.inlet.call
        for (i in 0 until scale.elements) api.add(i)
        return DirectCopySubject(scale, cell)
    }

    /** E1's end-to-end subject: the same populated cell, spawned on a real `ManagedHost`. */
    fun hostedCopySubject(scale: SetScale): HostedCopySubject {
        val cell = SetCell<Int>()
        val api = cell.inlet.call
        for (i in 0 until scale.elements) api.add(i)

        val scheduler = VirtualThreadScheduler("bench-boundedread-e1-${scale.label}")
        val host = ManagedHost(scheduler = scheduler)
        host.managementInlet.call.spawn(cell)
        scheduler.awaitDrained("E1 hosted subject ${scale.label}", DRAIN_TIMEOUT_MS)
        return HostedCopySubject(scale, host, cell.ref) { scheduler.shutdown() }
    }

    /**
     * Build the `source -> target -> collector` live-traffic rig on a real host.
     *
     * The links are established through `ManagedHost.connect` by port name, which is the
     * original harness's own wiring call and the same admission path the graph DSL's
     * `link` takes. A refused link is a fixture bug, not a measurement, so each
     * `connect` result is checked rather than discarded — the failure mode this closes is
     * a silently unlinked rig reporting a beautifully small dip over zero arrivals.
     *
     * The returned rig is **not seeded**: seeding is the caller's, because E2 needs two
     * independently seeded rigs (baseline and concurrent) and E3 needs one.
     */
    fun rig(scale: SetScale, wiring: RigWiring = RigWiring.LINKED): LiveTrafficRig {
        val scheduler = VirtualThreadScheduler("bench-boundedread-${scale.label}")
        val host = ManagedHost(scheduler = scheduler)
        val source = SetCell<Int>()
        val target = SetCell<Int>()
        val collector = ArrivalCollectorCell()

        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(target)
        host.managementInlet.call.spawn(collector)
        if (wiring == RigWiring.LINKED) {
            // outlet -> deltaInlet is the replica-gossip merge path (spec 42): only new
            // tag information re-emits, which is what makes one arrival per live add the
            // healthy signal.
            connected(
                host.managementInlet.call
                    .connect(source.ref, "outlet", target.ref, "deltaInlet"),
                "source.outlet -> target.deltaInlet",
            )
            connected(
                host.managementInlet.call
                    .connect(target.ref, "outlet", collector.ref, "inlet"),
                "target.outlet -> collector.inlet",
            )
        }
        scheduler.awaitDrained("rig construction ${scale.label}", DRAIN_TIMEOUT_MS)

        val sourceApi = host.lookup<SetApi<Int>>(source.ref)
            ?: error("source SetCell ${source.ref} not hosted after spawn")
        return LiveTrafficRig(
            scale = scale,
            wiring = wiring,
            host = host,
            scheduler = scheduler,
            sourceApi = sourceApi,
            targetRef = target.ref,
            collector = collector,
        ) { scheduler.shutdown() }
    }

    /**
     * A link that was refused is a fixture bug, never a measurement.
     *
     * `LinkResult.Deferred` is refused here too, and deliberately: it is the cross-host
     * proxy answer, whose outcome the initiator cannot observe. This rig is single-host by
     * construction, so a `Deferred` would mean the graph is not what the fixture believes
     * it to be — and an unobservable handshake is exactly the state that produces a
     * silently unlinked rig reporting a beautifully small dip over zero arrivals.
     */
    private fun connected(result: civictech.cell.link.LinkResult, what: String) {
        check(result is civictech.cell.link.LinkResult.Connected) {
            "bounded-read rig link $what did not connect: $result"
        }
    }

    /**
     * Walk [ref]'s state through `ManagedHost.readState`, **one page at a time, each page
     * awaited before the next is submitted**.
     *
     * That sequencing is the point, not an implementation convenience: `BoundedRead.kt`'s
     * "one page = one scheduler task" is the claim under test, and a walk that pipelined
     * its requests would put several priority-0 tasks in the queue at once and measure
     * something else entirely. It is also how a real paged reader (`DataSearch`) drives
     * the surface — one round trip at a time — and it is exactly what the original E3's
     * driver thread did with its `snapshotOf` stand-in.
     *
     * Every non-`Page` result is a refusal with a named reason, and every one of them
     * means this fixture asked for something the cell cannot answer. Each is therefore an
     * error rather than an early, fast, small-looking walk.
     *
     * @param limit entries per page; [PAGE_LIMIT] unless a caller is deliberately probing
     *   another page size.
     */
    fun pagedWalk(
        host: ManagedHost,
        ref: CellRef,
        limit: Int = PAGE_LIMIT,
        expectedEntries: Int? = null,
    ): PagedWalkOutcome {
        require(limit > 0) { "limit must be positive, was $limit" }
        val latencies = ArrayList<Double>()
        val caveats = LinkedHashSet<ReadCaveat>()
        var entries = 0
        var cursor: Cursor? = null
        var openingFrontier: TagFrontier? = null
        var closingFrontier: TagFrontier? = null
        // A walk over n entries at `limit` per page takes ceil(n/limit) pages; the slack
        // covers pages shortened by the advisory byte budget. Bounded so a cursor that
        // never terminates fails with a message instead of spinning to the 5-minute
        // per-method timeout.
        val maxPages = if (expectedEntries != null) expectedEntries / limit + 16 else Int.MAX_VALUE

        while (true) {
            val t0 = System.nanoTime()
            val result = host.readState(ref, StateRead(cursor = cursor, limit = limit)).get()
            latencies += (System.nanoTime() - t0) / 1_000_000.0

            val page = when (result) {
                is StateReadResult.Page -> result.page
                is StateReadResult.Unavailable -> error(
                    "paged walk of $ref refused at page ${latencies.size}: ${result.reason}"
                )
                is StateReadResult.Unbounded -> error(
                    "paged walk of $ref answered a whole copy at page ${latencies.size} — " +
                        "the fixture never passes StateRead.allowWholeCopy, so this cell " +
                        "is not the BoundedStateful subject the walk assumes"
                )
            }
            require(page.entries.size <= limit) {
                "page ${latencies.size} carried ${page.entries.size} entries against a " +
                    "hard limit of $limit"
            }
            entries += page.entries.size
            caveats += page.caveats
            if (latencies.size == 1) openingFrontier = page.frontier
            closingFrontier = page.frontier

            cursor = page.next ?: break
            check(latencies.size < maxPages) {
                "paged walk of $ref did not terminate within $maxPages pages " +
                    "(limit=$limit, entries so far $entries)"
            }
        }

        if (expectedEntries != null) {
            check(entries == expectedEntries) {
                "paged walk of $ref returned $entries entries, expected $expectedEntries"
            }
        }
        return PagedWalkOutcome(
            pages = latencies.size,
            entries = entries,
            pageLatenciesMs = latencies,
            // Both endpoint stamps are exact by SetCell's contract. Two nulls mean a
            // family that carries no frontier at all, which is not stability — see
            // PagedWalkOutcome.frontierStable.
            frontierStable = openingFrontier != null && openingFrontier == closingFrontier,
            caveats = caveats,
        )
    }

    /**
     * The `RunEnvironment` a `@Tag("bench")` JUnit probe measures under [BEN1-23].
     *
     * ## Why reading this process's own JVM properties is correct HERE
     *
     * `civictech.bench.Env.kt` deleted exactly these reads from `RunEnvironment.forRun`
     * and routed the JVM triple through `MeasuringJvm.fromJmhLog` instead
     * (computenet-hqid). That defect was specific and it is worth naming, because the fix
     * is easy to over-apply: the renderer of a JMH sweep runs in the Gradle `:bench:test`
     * JVM **after the measuring forks have exited**, so its own `java.version` describes
     * a process that measured nothing.
     *
     * A JUnit-shaped probe is the opposite case. It performs the measurement in the very
     * JVM that reports it — there is no fork, no later render step, and no second process
     * to confuse. So "the process asking" and "the process that measured" are one, and
     * `MeasuringJvm`'s contract ("read off the run's own artifacts, never off the process
     * doing the reading") is satisfied *because* they coincide, not evaded. `MeasuringJvm`
     * is a plain data class with a public constructor precisely so a non-JMH measurement
     * can state its own; `fromJmhLog` is the answer for a JMH log, not the only legal
     * source.
     *
     * The residual is stated rather than hidden: nothing here can prove the caller really
     * is the measuring process. That guarantee comes from this function's only callers
     * being in-process probes, which is why it lives beside them and says so.
     *
     * ## The harness commit is caller-attested, and refused if absent
     *
     * No artifact records which commit produced a run, so the SHA arrives as
     * `-D`[HARNESS_SHA_PROPERTY] — the same contract `ThroughputReportRenderTest` uses,
     * and already forwarded to the test JVM by `bench/build.gradle.kts`. A missing one
     * fails the probe rather than inventing a value, because `RunEnvironment` refuses to
     * exist without it.
     *
     * @param statistic what one sample of this probe IS — e.g. `"maxGap per 8000-add
     *   drive"`. It fills `RunEnvironment.jmhMode`, whose contract is "JMH's benchmark
     *   mode, or an explicitly stated equivalent for a non-JMH measurement", and it is
     *   annotated as a probe so no reader mistakes it for a JMH mode.
     * @param trials measurement trials; becomes `measurementIterations`.
     * @param warmupTrials discarded warmup drives; becomes `warmupIterations`.
     */
    fun probeRunEnvironment(
        statistic: String,
        trials: Int = TRIALS,
        warmupTrials: Int = WARMUP_TRIALS,
    ): RunEnvironment = RunEnvironment.forRun(
        measuringJvm = thisProcessMeasuringJvm(),
        jmhMode = "$statistic (JUnit @Tag(\"bench\") probe, in-process; not JMH)",
        // One JVM — this one. A probe does not fork, and saying `1` is a statement about
        // the measurement rather than a placeholder.
        forkCount = 1,
        warmupIterations = warmupTrials,
        measurementIterations = trials,
        harnessCommitSha = requiredHarnessSha(),
    )

    /** The caller-attested harness commit, or a refusal naming how to supply it. */
    fun requiredHarnessSha(): String {
        val sha = System.getProperty(HARNESS_SHA_PROPERTY)
        require(!sha.isNullOrBlank()) {
            "set -D$HARNESS_SHA_PROPERTY=\$(git rev-parse --short HEAD): no run artifact " +
                "records which harness commit produced a measurement, and RunEnvironment " +
                "refuses to exist without it"
        }
        return sha
    }

    /**
     * This JVM, as the measuring JVM. Legal only for an in-process probe — see
     * [probeRunEnvironment]'s KDoc for the whole argument.
     */
    fun thisProcessMeasuringJvm(): MeasuringJvm {
        val vendor = System.getProperty("java.vendor").orEmpty().ifBlank { "unknown vendor" }
        val vendorVersion = System.getProperty("java.vendor.version")?.takeIf { it.isNotBlank() }
        val version = System.getProperty("java.version")
        check(!version.isNullOrBlank()) { "system property java.version is not set" }
        val arguments = ManagementFactory.getRuntimeMXBean().inputArguments
        val heapFlags = arguments.filter { argument ->
            HEAP_FLAG_PREFIXES.any { argument.startsWith(it) }
        }
        return MeasuringJvm(
            vendor = if (vendorVersion == null) vendor else "$vendor ($vendorVersion)",
            version = version,
            heapSettings = when {
                heapFlags.isNotEmpty() -> heapFlags.joinToString(separator = " ")
                arguments.isEmpty() -> "JVM defaults (no VM options)"
                else -> "JVM defaults (no heap flag among ${arguments.size} VM options)"
            },
        )
    }

    /**
     * Heap-setting flag prefixes. The same set `Env.kt` filters `# VM options` by, and
     * duplicated rather than shared because that list is `private` to `MeasuringJvm`'s
     * companion; widening its visibility would be an edit to `Env.kt`, which this change
     * does not make (`computenet-x9e.8` owns that file).
     */
    private val HEAP_FLAG_PREFIXES = listOf(
        "-Xms", "-Xmx", "-Xmn",
        "-XX:MinHeapSize", "-XX:InitialHeapSize", "-XX:MaxHeapSize",
        "-XX:MinRAMPercentage", "-XX:InitialRAMPercentage", "-XX:MaxRAMPercentage",
        "-XX:MaxRAM=",
    )

    /** Every probe result carries [Drive.REAL]; there is no SIM variant. See this file's header. */
    val DRIVE: Drive = Drive.REAL
}
