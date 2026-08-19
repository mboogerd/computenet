package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.classify
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * **BS-9 / `[BEN1-19]` — the late-join catch-up probe.** Given a `SetCell<Int>` source at
 * steady state on a real `ManagedHost`/`VirtualThreadScheduler`, what does a new subscriber
 * cost, and what does paying that cost do to the source's own execution context?
 *
 * Two numbers per trial, both reported, because the acceptance clause asks for both:
 *
 * 1. **Catch-up cost** — wall time from initiating the link until the joiner's collector
 *    holds the complete baseline. `CatchUp.kt`'s `catchUpOnLinked` (installed by `SetCell`
 *    at `SetCell.kt:264`) sends the whole tag state as ONE delta-from-empty over the
 *    targeted `FanOutlet.at(link.to).propagate` route, so this is the cost of a
 *    push-authoritative re-baseline of the source's entire state — which is exactly what
 *    G-43 ("bound the push-authoritative re-baseline cost under wide fan-out",
 *    `doc/spec/90-roadmap/91-gap-analysis.md:83`) will consume through BEN2.
 * 2. **Occupancy of the source's execution context** — the largest stall
 *    (`DriveOutcome.maxGapMs`) the join inflicts on a PRE-EXISTING subscriber's live
 *    arrival stream, measured while an unpaced 8,000-add burst drives through the same
 *    source on the same host. `ManagedHost` dispatches `connect` through
 *    `enqueueAwaiting(0)`, so the link install — snapshot and delivery both — runs on the
 *    host's single drain thread at priority 0, ahead of every add queued at 20.
 *    `CatchUpFixtures.kt`'s header has the full mechanism.
 *
 * ## The pairing is the point: occupancy is a DIFFERENCE, not a level
 *
 * "The stall it inflicts" is only readable against the stall the same rig shows without a
 * join, so every trial drives **twice**: once with no join, once with one. The two
 * conditions are interleaved on ONE rig rather than run on two rigs the way
 * `BoundedReadProbeTest`'s E2 separates its baseline from its concurrent condition — a
 * deliberate departure, for a reason E2 did not have:
 *
 * - E2's reason for two rigs was that the baseline's trials must not run against a target
 *   the concurrent condition had already grown. Interleaving achieves that balance more
 *   tightly, not less: the paired conditions of one trial sit `DRIVE_ADDS` elements apart
 *   rather than `TRIALS * DRIVE_ADDS`.
 * - And here two rigs would cost a second 10^5 seeding, which at this scale is the
 *   dominant cost of the whole probe.
 *
 * What interleaving gives up, stated: the conditions are no longer separated in time, so a
 * machine-wide disturbance lands on both halves of a trial instead of on one condition.
 * For a *difference* that is a strength; for either level read on its own it is neither.
 *
 * ## WHAT THE MECHANISM PREDICTS — a reading aid, not a verdict
 *
 * The joined-condition maxGap and the catch-up cost are predicted to track each other
 * closely, and they did on every run of this artifact so far. That is the mechanism, not a
 * coincidence and not an instrument fault: one drain thread serves the whole host, the
 * priority-0 link install holds it for the copy *and* the delivery, and so the gap it opens
 * in the pre-existing subscriber's arrival stream is the same interval as the catch-up
 * itself. A reader who expected two independent numbers should read this paragraph before
 * concluding the two columns are measuring one thing twice by accident — they are measuring
 * one occupancy from both ends, which is what makes the paired difference against the
 * unjoined condition the informative figure.
 *
 * **No verdict is drawn here.** Whether the observed cost FIRES, RETIRES or leaves G-43's
 * trigger INCONCLUSIVE, and whether it grows linearly in state size, is the sibling
 * reporting task's call on numbers from a run it can vouch for.
 *
 * ## READ THIS BEFORE READING ANY NUMBER THESE PRINT
 *
 * - **The scale in a method's name is the PRE-SEED size.** Every add is a fresh element and
 *   an OR-set never shrinks, so the source grows by `DRIVE_ADDS` per drive and a late trial
 *   catches up a materially larger state than an early one. Each trial therefore prints the
 *   size its own catch-up actually shipped (`baseline adds`) next to the source's element
 *   count. `CatchUpFixtures.kt`'s header, drift 1.
 * - **Fan-out degree, by contrast, does not drift**: each trial's joiner is detached before
 *   the next trial (drift 2), so every drive fans out to one pre-existing subscriber plus,
 *   inside the join window, one joiner.
 * - **The rows are expected to classify `Unreportable`, and that is the honest outcome.**
 *   maxGap is a worst-case order statistic on a shared machine; `TrialStats`' KDoc does the
 *   arithmetic from measured variability and lands on ~1e4-1e6 trials for a `Reportable`
 *   relative dispersion, which no sweep can afford. So `classify` is *reported* per row and
 *   never enforced, `Findings.entry` is deliberately not called (it would refuse an
 *   `Unreportable` row outright), `civictech.bench.NOISE_FLOOR` is not widened, and the
 *   trial count is not inflated to chase a classification it cannot reach. Printing honest
 *   `TrialStats.describe` lines is this probe's deliverable; the findings entry and its
 *   FIRES/RETIRES/INCONCLUSIVE verdict belong to a sibling task.
 *
 * ## Running these
 *
 * `@Tag("bench")`, so they never execute in a default `:bench:test` (`[BEN1-10]` via F2's
 * gate in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`). One scale at a time — the
 * methods are split per scale rather than parameterized precisely so `--tests` can select
 * one, since the tag gate cannot sub-select parameterized invocations:
 *
 * ```
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.LateJoinCatchUpProbeTest' \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD) -i
 * ```
 *
 * Numbers are **printed, never written**: appending an entry to `doc/bench/findings.md` is
 * a hand step performed by whoever can vouch for the run — the rule `Findings`' own KDoc
 * states and `ThroughputReportRenderTest` follows.
 *
 * Sizing, for a caller budgeting a slot — **measured on this branch, not estimated**
 * (2026-08-19, Apple M2 Pro, Temurin 21.0.11, all three methods in one
 * `:bench:test -PbenchOnly=true --rerun` invocation): **under 4 s of wall clock for all
 * three methods**, Gradle included. That is two orders of magnitude below the "single-digit
 * minutes" this probe was budgeted at, and the reason is worth writing down so nobody
 * re-derives it: **seeding is not the dominant cost here.** An add through this rig costs
 * ~2-8 µs (the printed drive durations divided by `DRIVE_ADDS`), so a 10^5 seeding is a
 * fraction of a second — the same order as the `2 * TRIALS + WARMUP_TRIALS` drives that
 * follow it, not dominant over them, and each trial's re-baseline is one delta rather than a
 * per-trial whole-state copy or a paged walk. Whether the bounded-read probes' cost at 10^5
 * is dominated by *their* per-trial copies is not measured here and is not claimed.
 *
 * The consequence is headroom, not a licence: the module's shared 5-minute per-method
 * timeout is ~2 orders of magnitude away, so no `@Timeout` override is needed here (unlike
 * `BoundedReadProbeTest`, whose KDoc warns a 10^5 sweep may approach it). A sweep that
 * raises `CatchUpFixtures.TRIALS` has room to; it should still re-measure rather than
 * assume this line stays true.
 */
@Tag("bench")
class LateJoinCatchUpProbeTest {

    @Test
    fun `late-join catch-up and source occupancy at 1e5 elements`() =
        probeAt(CatchUpFixtures.DEFAULT_SCALE)

    @Test
    fun `late-join catch-up and source occupancy at 1e4 elements`() = probeAt(SetScale.N1E4)

    @Test
    fun `late-join catch-up and source occupancy at 1e3 elements`() = probeAt(SetScale.N1E3)

    /**
     * One scale: seed, warm up, then [CatchUpFixtures.TRIALS] paired trials of
     * (drive alone) then (drive with one late join fired
     * [CatchUpFixtures.JOIN_DELAY_MS] in).
     *
     * The join runs on a second thread because the driving thread is busy being the drive —
     * the same two-thread shape `BoundedReadProbeTest` uses for its concurrent read, and for
     * the same reason: the mechanism under test is a priority-0 submit cutting in front of
     * work that is already queued, which cannot happen if the same thread does both.
     */
    private fun probeAt(scale: SetScale) {
        // Built first, so a missing harness SHA fails in milliseconds instead of after a
        // 10^5 seeding.
        val env = CatchUpFixtures.probeRunEnvironment(
            statistic = "catch-up ms per late join, and maxGap ms per " +
                "${CatchUpFixtures.DRIVE_ADDS}-add unpaced drive with and without one join",
        )

        val baselineGaps = ArrayList<Double>()
        val joinGaps = ArrayList<Double>()
        val catchUpMs = ArrayList<Double>()
        val baselineDurations = ArrayList<Double>()
        val joinDurations = ArrayList<Double>()
        val caughtUpAdds = ArrayList<Int>()
        val burstAlreadyApplied = ArrayList<Int>()
        val joinerArrivals = ArrayList<Int>()
        val elementCounts = ArrayList<Int>()

        CatchUpFixtures.rig(scale).use { rig ->
            rig.seed()
            rig.drive(CatchUpFixtures.WARMUP_ADDS)

            repeat(CatchUpFixtures.TRIALS) {
                // (a) The unjoined half. Same rig, same host, same JIT/GC state, one
                // DRIVE_ADDS burst earlier than its paired half.
                val alone = rig.drive(CatchUpFixtures.DRIVE_ADDS)
                baselineGaps += alone.maxGapMs
                baselineDurations += alone.durationMs

                // (b) The joined half. The joiner is spawned and fenced BEFORE the timer
                // and before the drive: a spawn is ordinary hosting work every graph pays,
                // and its own drain fence would otherwise wait out the drive and destroy
                // the overlap this trial exists to create.
                val pending = rig.prepareJoiner()
                try {
                    val go = CountDownLatch(1)
                    var outcome: CatchUpOutcome? = null
                    val joiner = Thread {
                        go.await()
                        Thread.sleep(CatchUpFixtures.JOIN_DELAY_MS)
                        outcome = pending.join()
                    }.apply { isDaemon = true; start() }

                    go.countDown()
                    val joined = rig.drive(CatchUpFixtures.DRIVE_ADDS)
                    joiner.join(CatchUpFixtures.DRAIN_TIMEOUT_MS)

                    val completed = checkNotNull(outcome) {
                        "the late join did not complete within " +
                            "${CatchUpFixtures.DRAIN_TIMEOUT_MS}ms"
                    }
                    // A NoBaseline here is a broken rig, not a measurement: the source was
                    // seeded and the join was asked to link, so a baseline MUST have been
                    // sent. Refused loudly rather than recorded as a trial whose catch-up
                    // cost happened to be nothing. This branch is not decorative — it is
                    // what caught the completeness fence being armed at an enqueued rather
                    // than a settled element count (CatchUpOutcome.expectedElements records
                    // the measurement), which every trial would otherwise have reported as
                    // a silent NoBaseline.
                    val baseline = when (completed) {
                        is CatchUpOutcome.Baseline -> completed
                        is CatchUpOutcome.NoBaseline -> error(
                            "late join observed NO baseline against a source holding " +
                                "${completed.expectedElements} elements " +
                                "(arrivals=${completed.arrivals}, largest single delta " +
                                "carried ${completed.largestArrivalAdds} adds, waited " +
                                "${completed.waitedMs} ms) — that is a rig fault, not a " +
                                "catch-up cost of zero"
                        )
                    }

                    joinGaps += joined.maxGapMs
                    joinDurations += joined.durationMs
                    catchUpMs += baseline.catchUpMs
                    caughtUpAdds += baseline.adds
                    // How far into the burst the join landed, in adds. The baseline carries
                    // the source's APPLIED state, and `settledElements` is what was applied
                    // before the burst started, so the difference is exactly how much of the
                    // concurrent burst the source had drained when the priority-0 link
                    // install cut in. This — not the joiner's arrival count — is the
                    // evidence that the overlap the probe depends on actually happened.
                    burstAlreadyApplied += baseline.adds - pending.settledElements
                    joinerArrivals += baseline.arrivals
                    elementCounts += rig.elementsAdded
                } finally {
                    // Detach, so the next trial's drive sees the same fan-out degree this
                    // one did (CatchUpFixtures.kt's header, drift 2). In `finally` because
                    // a failed trial must not leave a subscriber attached for the rest of
                    // the sweep.
                    pending.close()
                }
            }
        }

        val alone = TrialStats(baselineGaps)
        val joined = TrialStats(joinGaps)
        val catchUp = TrialStats(catchUpMs)
        report(
            scale = scale,
            env = env,
            rows = listOf(
                "catch-up cost (link initiated -> complete baseline at the joiner)" to catchUp,
                "${scale.label} maxGap with no join (occupancy baseline)" to alone,
                "${scale.label} maxGap during one late join (occupancy)" to joined,
            ),
            extra = listOf(
                "drive duration, no join: ${TrialStats(baselineDurations).describe("ms")}",
                "drive duration, with join: ${TrialStats(joinDurations).describe("ms")}",
                // The state each trial's catch-up actually shipped. The method name's scale
                // is the PRE-SEED size and is neither of these — see this class's KDoc.
                "elements the catch-up shipped, per trial: $caughtUpAdds",
                "source elements after each trial: $elementCounts (pre-seed ${scale.elements})",
                // Where inside the burst the join landed — the overlap witness. 0 would mean
                // the link install beat the first add out of the source's inbox, and
                // DRIVE_ADDS would mean it arrived after the whole burst had drained; either
                // extreme means the contention this probe measures did not occur, and the
                // occupancy column would be measuring an idle host.
                "burst adds already applied when the catch-up snapshot was taken, per " +
                    "trial: $burstAlreadyApplied (of ${CatchUpFixtures.DRIVE_ADDS})",
                // Deltas the joiner had received at the instant its baseline completed. 1 is
                // the ordinary answer and does NOT mean the overlap failed — it means no live
                // delta overtook the baseline, which is what a priority-0 install ahead of
                // priority-20 traffic should produce. The overlap witness is the line above.
                "joiner arrivals when the baseline completed, per trial: $joinerArrivals",
                // The occupancy figure, stated as the difference of the paired medians and
                // reported alongside both so a reader can see which side moved.
                "occupancy (median maxGap during join - median maxGap with no join): " +
                    "%.4f ms".format(joined.median - alone.median),
            ),
        )
    }

    /**
     * Print one scale's results, having first pushed every number through the F3 result
     * model (`[BEN1-23]`..`[BEN1-27]`).
     *
     * The `FindingsTable` construction is the load-bearing part, not the printing: it is
     * what refuses a table mixing drives or environments, and every row it holds carries an
     * explicit `Drive.REAL` and a `RunEnvironment` that could not exist without a JVM, a
     * heap, a CPU, an OS, a stated statistic and a caller-attested harness commit.
     * `classify` is reported per row rather than enforced, and `Findings.entry` is not
     * called — see this class's KDoc for why that refusal is expected here rather than a
     * limit a longer sweep lifts.
     */
    private fun report(
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
                drive = CatchUpFixtures.DRIVE,
                env = env,
            )
        }
        val table = FindingsTable(results, labels = rows.map { it.first })

        println("=== BS-9 late-join catch-up ${scale.label} (${scale.elements} pre-seeded elements) ===")
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
