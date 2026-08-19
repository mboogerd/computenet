package civictech.bench.micro

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.lookup
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.testkit.awaitDrained
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// =======================================================================================
// BS-9 / [BEN1-19] — the LATE-JOIN CATCH-UP measurement: what a new subscriber costs a
// source that is already at steady state, and what that cost does to the source's own
// execution context while it is being paid.
//
// ## What is being measured, and why over this path
//
// The consumer is G-43, "bound the push-authoritative re-baseline cost under wide
// fan-out" (`doc/spec/90-roadmap/91-gap-analysis.md:83` — cited, never edited).
// `kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt`'s `catchUpOnLinked` IS the
// push re-baseline path: `SetCell` installs it on its outlet (`SetCell.kt:264`, verified
// at this branch's base commit c1dd1fa5), and on every new link it takes the whole tag
// state under `stateLock` and sends it to just the new subscriber as one
// delta-from-empty through the targeted `FanOutlet.at(link.to).propagate` route
// (`FanOutlet.kt:451`). So a link install against a 10^5-element `SetCell` is a
// re-baseline of 10^5 elements, and this file measures it.
//
// ## THE MECHANISM THAT MAKES "OCCUPANCY" A REAL NUMBER RATHER THAN A METAPHOR
//
// `ManagedHost.managementInlet`'s dispatch routes `connect` through
// `enqueueAwaiting(0)` (`ManagedHost.kt`, the `method.name.startsWith("connect")` arm),
// so the link install — and therefore the catch-up snapshot AND the targeted delivery
// the on-link listener performs — runs **on the host's own single drain thread, at
// scheduler priority 0**, ahead of every piece of ordinary data traffic already queued
// at priority 20. That is the same queue-jumping shape `BoundedReadProbeTest`'s E2
// measures for `snapshotOf`, reached through a different door: a whole-state copy plus a
// whole-state delivery, admitted in front of the source's live work.
//
// Consequently the source's *own* subscribers stall while a late joiner catches up, and
// [CatchUpRig.drive] measures that stall directly — as the largest interruption
// ([DriveOutcome.maxGapMs]) in a PRE-EXISTING subscriber's arrival stream, with the join
// fired a millisecond into an unpaced live-add burst so it lands against a queued
// backlog rather than an idle host. Source and both subscribers live on ONE
// `ManagedHost`/`VirtualThreadScheduler`, which is the point rather than an economy —
// the one-host rationale is `LiveTrafficRig`'s KDoc in `BoundedReadFixtures.kt`, and it
// applies here verbatim: split across hosts there is no shared execution context left to
// be occupied, and the measurement would be of nothing.
//
// ## Invariants
//
// - **REAL drive only, and every result says so.** [CatchUpFixtures.DRIVE] is
//   `BoundedReadFixtures.DRIVE` = [Drive.REAL]. There is deliberately no SIM variant: a
//   `SimulationController` interleaves the join and the live traffic on one deterministic
//   thread, which dissolves the very contention under measurement.
// - **Reuse, not rebuild.** [ArrivalCollectorCell], [TrialStats], [DriveOutcome],
//   [SetScale], [RigWiring] and `BoundedReadFixtures.probeRunEnvironment` all come from
//   `BoundedReadFixtures.kt`, which this change does not edit. [BaselineCollectorCell] is
//   new because it observes something `ArrivalCollectorCell` structurally cannot — see
//   its KDoc.
// - **Results go through the F3 model.** [probeRunEnvironment] returns a
//   `civictech.bench.RunEnvironment`; the probe's rows are `BenchResult`s in a
//   `FindingsTable`, so a result that cannot state environment, dispersion or drive
//   cannot be constructed [BEN1-23..27].
// - **Fences, never two-equal-samples polling.** Quiescence is `testkit`'s
//   `awaitDrained`; baseline arrival is a `CountDownLatch` counted down by the delivery
//   itself; a live drive's completion is the arrival count reaching its target. Every one
//   is bounded by [CatchUpFixtures.DRAIN_TIMEOUT_MS] and fails naming how far it got.
//
// ## TWO DRIFTS ACROSS TRIALS. ONE IS REPRODUCED DELIBERATELY; ONE IS REMOVED.
//
// 1. **Element count drifts UP, and that is kept.** Every add carries a fresh element
//    from one monotone counter (a repeat would be absorbed by the effective-only rule and
//    would measure the absorb path), and an OR-set tombstones rather than deletes, so the
//    source only ever grows. After seeding and k timed drives it holds
//    `scale.elements + WARMUP_ADDS + k * DRIVE_ADDS`. **The 1e5 in a probe method's name
//    is therefore the PRE-SEED size, not what a later trial's catch-up copied** — the
//    same property `BoundedReadFixtures.kt`'s header item 4 states for E2/E3, and the
//    reason [CatchUpRig.elementsAdded] exists and the probe prints it per trial.
// 2. **Fan-out degree does NOT drift, because each trial's joiner is unlinked.** A link
//    fires catch-up exactly once, so every trial needs a *fresh* subscriber; left
//    attached, trial 2 would fan out to two consumers and trial 3 to three, and the
//    occupancy column would carry a systematic upward trend that reads as dispersion.
//    [CatchUpRig.PendingJoin.close] unlinks the joiner (`Link.unlink()`, which detaches
//    the consumer via `FanOutlet.unsubscribe` -> `removeConsumer`) on a quiescent rig, so
//    every trial's drive fans out to exactly one pre-existing subscriber plus, inside the
//    join window, one joiner. `CatchUpFixturesTest` asserts the detach really happened
//    rather than trusting it — an unlink that silently did nothing would reintroduce
//    the trend invisibly.
//
// ## What this file does NOT do
//
// It measures; it does not classify and it does not write. maxGap is a worst-case order
// statistic and [TrialStats] states its own arithmetic: at the trial-to-trial variability
// this family actually shows, a `Reportable` classification would need ~1e4-1e6 trials, so
// these rows are **expected** to classify `Unreportable` at any affordable count. Printing
// honest `TrialStats.describe` lines, the per-trial element counts and the per-trial
// catch-up payload size is the deliverable. `civictech.bench.NOISE_FLOOR` is not widened
// and the trial constants are not inflated to chase reportability; the findings entry and
// its FIRES/RETIRES/INCONCLUSIVE verdict belong to a sibling task.
// =======================================================================================

/**
 * Whether [CatchUpRig.PendingJoin.join] actually establishes the late joiner's link — the
 * **negative control**, in the same spirit and for the same reason as [RigWiring].
 *
 * The failure this exists to make detectable: every catch-up observable is read at the
 * joiner's own collector, so a join whose `connect` silently never happened would spawn,
 * wait, quiesce and throw nothing at all. With [UNLINKED] the rig is asked to do exactly
 * that, and it must report [CatchUpOutcome.NoBaseline] — a *type* that carries no
 * duration, so "no baseline arrived" cannot be misread as a very fast catch-up. Without
 * this control, "it ran and the milliseconds look plausible" cannot be told apart from a
 * working rig.
 */
enum class JoinWiring { LINKED, UNLINKED }

/**
 * What one late join observed at the joiner's own collector.
 *
 * A sealed pair rather than a nullable duration, deliberately: the whole hazard in a
 * catch-up measurement is reporting a number for a baseline that never arrived, and
 * [NoBaseline] has no number to report. A probe that wants a duration must handle the
 * branch where there is none.
 */
sealed interface CatchUpOutcome {

    /**
     * Elements the source had **settled** when the joiner was prepared — the completeness
     * fence's lower bound.
     *
     * Settled, not enqueued, and the distinction is not pedantry: it is a measured
     * correction to this fixture's first form, which armed the fence at
     * [CatchUpRig.elementsAdded] read immediately before `connect`. That counter advances
     * when the driving thread *enqueues* an add, while the catch-up snapshot sees only what
     * the source has *applied*, and under an unpaced burst the source is thousands of adds
     * behind its own inbox. Measured on this branch at 1e5: the fence demanded 110,442
     * elements and the baseline — which had arrived, correctly — carried 109,554, so every
     * trial waited out the full drain timeout and reported no baseline at all.
     *
     * Arming at the count that was settled and quiesced *before* the drive removes the race
     * without weakening the fence: the source can only have grown since, so a delta
     * carrying at least this many elements is necessarily the whole-state delta-from-empty
     * and not a live add. [Baseline.adds] reports what the baseline actually shipped, which
     * is the larger, drive-dependent number.
     */
    val expectedElements: Int

    /** Deltas the joiner's collector observed in total, baseline included. */
    val arrivals: Int

    /**
     * The baseline arrived: one delta-from-empty carrying [adds] elements, [catchUpMs]
     * after the link was initiated.
     *
     * @param catchUpMs **the catch-up cost.** Wall time from just before
     *   `managementInlet.call.connect` returns control to the joining thread until the
     *   host's drain thread hands the complete baseline to the joiner's collector. It
     *   therefore includes the priority-0 enqueue wait, the whole-state copy under
     *   `SetCell.stateLock`, and the targeted delivery — the full cost a late joiner
     *   imposes, not the copy alone.
     * @param adds elements the baseline delta carried — **the size of the state this
     *   re-baseline actually shipped**, and the number to read a `catchUpMs` against.
     *   `>= expectedElements` always (the source only grows, and [expectedElements] is a
     *   settled count from before the drive), and typically larger by however much of the
     *   concurrent burst the source had applied when the on-link listener ran.
     * @param dels tombstoned elements it carried. Zero for a rig that never removes, and
     *   reported anyway because catch-up ships tombstones too (`SetCell.kt:264`).
     */
    data class Baseline(
        val catchUpMs: Double,
        val adds: Int,
        val dels: Int,
        override val expectedElements: Int,
        override val arrivals: Int,
    ) : CatchUpOutcome {
        init {
            require(catchUpMs >= 0.0 && catchUpMs.isFinite()) {
                "catchUpMs must be finite and non-negative, was $catchUpMs"
            }
            require(adds >= expectedElements) {
                "a baseline carrying $adds adds cannot be the state of a source holding " +
                    "$expectedElements elements — the completeness fence is broken"
            }
        }
    }

    /**
     * No baseline arrived, and the wait was ended by a positive fence rather than by
     * giving up: either the rig is [JoinWiring.UNLINKED], or the source was empty and
     * `catchUpOnLinked`'s own empty-state guard sent nothing, and in both cases the host's
     * queue was observed to drain with nothing having been delivered.
     *
     * @param waitedMs how long the join waited before concluding.
     * @param largestArrivalAdds the biggest single delta the joiner ever saw, so a reader
     *   can tell "nothing arrived" (0) from "something arrived but never the whole state".
     */
    data class NoBaseline(
        override val expectedElements: Int,
        override val arrivals: Int,
        val waitedMs: Double,
        val largestArrivalAdds: Int,
    ) : CatchUpOutcome
}

/**
 * The late joiner: a bystander on the source's outlet that timestamps the arrival of the
 * **complete baseline** and nothing else.
 *
 * Why this is not [ArrivalCollectorCell]. That cell counts arrivals and timestamps them,
 * which is exactly right for the occupancy side — one arrival per live add is the healthy
 * signal there. But completeness of a catch-up is a statement about a *payload*: the whole
 * state comes down as ONE delta-from-empty, so "the joiner holds all 10^5 elements" is
 * `delta.adds.size >= n` on a single arrival and is invisible to an arrival count. This
 * cell reads that size; it is the smallest cell that can tell a complete baseline from a
 * live add, and reusing the counting collector would have made the completeness fence
 * unwritable.
 *
 * State is a handful of atomics rather than a queue of arrivals, on purpose: a joiner in a
 * 10^5-element trial receives a 10^5-element delta and then thousands of live ones, and the
 * probe needs exactly one instant out of all of it.
 */
class BaselineCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())

    /** Deltas observed since construction. */
    val arrivals = AtomicLong(0)

    /** The largest `adds` map any single arrival carried — 0 when nothing ever arrived. */
    val largestAdds = AtomicInteger(0)

    /** `System.nanoTime()` of the first arrival that met the armed threshold; 0 if none. */
    val baselineNanos = AtomicLong(0)

    /** `adds`/`dels` sizes of that arrival. */
    val baselineAdds = AtomicInteger(0)
    val baselineDels = AtomicInteger(0)

    /** Counted down by the delivery itself — the fence, not a poll. */
    val baselineReached = CountDownLatch(1)

    private val threshold = AtomicInteger(Int.MAX_VALUE)

    /**
     * Arm the completeness fence at [expectedElements] elements. **Must be called before
     * the link is established**, or the baseline can arrive against an unarmed threshold
     * and be missed.
     *
     * [expectedElements] must be a count the source has **settled**, not one its inbox has
     * merely accepted — see [CatchUpOutcome.expectedElements] for the measured reason an
     * enqueued count deadlocks this fence.
     *
     * An [expectedElements] below 1 arms an unreachable threshold, which is the honest
     * encoding of "there is no baseline to wait for": `catchUpOnLinked` sends nothing at
     * all for an empty source (`CatchUp.kt`'s empty-state guard).
     *
     * A one-element source is a genuine blind spot rather than a handled case: its
     * baseline delta is indistinguishable from a single live add. The probe's scales are
     * 10^3 and up, so the ambiguity is unreachable there, and it is stated here rather
     * than defended against.
     */
    fun armFor(expectedElements: Int) {
        threshold.set(if (expectedElements < 1) Int.MAX_VALUE else expectedElements)
    }

    init {
        inlet.onEach { delta ->
            val now = System.nanoTime()
            arrivals.incrementAndGet()
            largestAdds.accumulateAndGet(delta.adds.size) { a, b -> maxOf(a, b) }
            if (delta.adds.size >= threshold.get() && baselineNanos.compareAndSet(0L, now)) {
                baselineAdds.set(delta.adds.size)
                baselineDels.set(delta.dels.size)
                baselineReached.countDown()
            }
        }
    }
}

/**
 * The rig BS-9 drives: a `SetCell<Int>` source with ONE pre-linked [ArrivalCollectorCell]
 * subscriber, on a real `ManagedHost`/`VirtualThreadScheduler`, into which late joiners are
 * attached one per trial.
 *
 * Shape, and the two things about it that are load-bearing:
 *
 * - **The pre-existing subscriber is linked while the source is still EMPTY**, before
 *   [seed]. Linking it later would fire `catchUpOnLinked` for *it* too and the rig's own
 *   construction would perform the very re-baseline it exists to measure. Empty source ⇒
 *   `catchUpOnLinked`'s snapshot returns null ⇒ nothing is sent.
 * - **The pre-existing subscriber is a collector, not a second `SetCell`.** `BoundedRead`'s
 *   rig gossips `source -> target -> collector` because its subject is the *target's*
 *   state. Here the subject is the source's outlet, so a second `SetCell` between them
 *   would only add a merge hop to the stall being measured.
 */
class CatchUpRig internal constructor(
    val scale: SetScale,
    val wiring: RigWiring,
    val joinWiring: JoinWiring,
    val host: ManagedHost,
    private val scheduler: VirtualThreadScheduler,
    private val sourceApi: SetApi<Int>,
    /** The source's ref — the cell whose `outlet` a late joiner links to. */
    val sourceRef: CellRef,
    private val existing: ArrivalCollectorCell,
    private val stop: () -> Unit,
) : AutoCloseable {

    private val nextElement = AtomicInteger(0)

    /**
     * Distinct elements pushed through the source since the rig was built — and therefore,
     * because nothing is removed and no add is a repeat, the source's exact live membership
     * and the size of the state a catch-up now has to ship.
     *
     * **Grows across trials.** See this file's header, drift 1: a probe method's scale names
     * the pre-seed size, and this is what a given trial actually measured. An
     * `AtomicInteger` rather than the plain `var` its sibling uses because a joining thread
     * reads it *while* the driving thread is adding — that cross-thread read is by design
     * here, not incidental.
     */
    val elementsAdded: Int get() = nextElement.get()

    /** Deltas the pre-existing subscriber has observed since the rig was built. */
    val existingArrivals: Long get() = existing.total.get()

    /** Late joiners [prepareJoiner] has created — the fan-out this rig has ever carried. */
    val joinersPrepared: Int get() = joinerCount.get()

    private val joinerCount = AtomicInteger(0)

    /**
     * Bring the source to [scale] elements, off any timer, returning once every one has
     * landed at the pre-existing subscriber.
     */
    fun seed() {
        drive(scale.elements)
    }

    /**
     * Enqueue [adds] live adds through the source as fast as this thread can, and measure
     * the pre-existing subscriber's own arrival stream while they drain.
     *
     * Unpaced, and the maxGap arithmetic is character-for-character the one
     * `LiveTrafficRig.drive` uses — including the widening at both ends, so a stall landing
     * at the very start or end of the drive is counted rather than hidden. That is a
     * deliberate duplication, not an oversight: `LiveTrafficRig` has an `internal`
     * constructor, no source ref to link a joiner to, and a `source -> target -> collector`
     * shape this measurement does not want, so it cannot be reused; and hoisting the shared
     * arithmetic into a common helper would mean editing `BoundedReadFixtures.kt`, which
     * this change may not touch. Identical arithmetic is what makes this probe's occupancy
     * column comparable to E2's — see [DriveOutcome.maxGapMs] for the definition itself,
     * which is stated there and not restated here.
     */
    fun drive(adds: Int): DriveOutcome {
        require(adds > 0) { "adds must be positive, was $adds" }
        val before = existing.total.get()
        existing.arrivalNanos.clear()
        val t0 = System.nanoTime()
        val api = sourceApi.inlet.call
        repeat(adds) { api.add(nextElement.getAndIncrement()) }

        val timeoutMs = CatchUpFixtures.DRAIN_TIMEOUT_MS
        if (wiring == RigWiring.LINKED) {
            val deadline = t0 + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (existing.total.get() < before + adds) {
                check(System.nanoTime() < deadline) {
                    "live-traffic drive did not drain within ${timeoutMs}ms " +
                        "(scale=${scale.label} adds=$adds, arrived " +
                        "${existing.total.get() - before}/$adds)"
                }
            }
        } else {
            // No link, so no arrival can ever satisfy the condition above. The drain fence
            // is still positive evidence the host emptied its queue, which is what makes
            // `arrivals == 0` an assertion about the wiring rather than about whether the
            // test waited long enough.
            scheduler.awaitDrained("unlinked catch-up rig ${scale.label}", timeoutMs)
        }
        val t1 = System.nanoTime()

        val times = existing.arrivalNanos.toList().sorted()
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

    /**
     * Await quiescence of this rig's host. The fence the caller needs between a timed
     * trial and the graph surgery that ends it.
     */
    fun awaitQuiescent(what: String) {
        scheduler.awaitDrained("$what ${scale.label}", CatchUpFixtures.DRAIN_TIMEOUT_MS)
    }

    /**
     * Spawn a fresh late joiner **without linking it**, and fence its spawn.
     *
     * Split from the join itself because the timer must start at the link, not at the
     * spawn: "what a late joiner costs" is the cost of the *catch-up*, and a spawn is
     * ordinary hosting work every graph pays anyway. Call it on a quiescent rig, off the
     * timer — the `awaitDrained` here would otherwise wait out a live drive already in
     * flight and serialize the very overlap the probe is built to create.
     *
     * **Being called on a quiescent rig is also what makes the completeness fence
     * well-defined**, not just cheap: the element count captured after the drain fence is a
     * count the source has *settled*, which is the only kind [BaselineCollectorCell.armFor]
     * can be armed at (see [CatchUpOutcome.expectedElements] for what happens otherwise).
     * Calling this mid-drive would both destroy the overlap and hand the fence an enqueued
     * count.
     */
    fun prepareJoiner(): PendingJoin {
        val collector = BaselineCollectorCell()
        host.managementInlet.call.spawn(collector)
        awaitQuiescent("late joiner spawn")
        joinerCount.incrementAndGet()
        // Read AFTER the fence: every add enqueued so far has been applied, so this is the
        // source's settled membership and a safe lower bound for the baseline's size.
        return PendingJoin(collector, settledElements = elementsAdded)
    }

    /**
     * [prepareJoiner] + [PendingJoin.join] + detach, for a quiescent rig — the whole
     * late-join operation as one call, which is what `CatchUpFixturesTest` exercises.
     * The probe uses the two-phase form because it needs the join to run concurrently with
     * a live drive.
     */
    fun joinSubscriber(): CatchUpOutcome = prepareJoiner().use { it.join() }

    override fun close() = stop()

    /**
     * A joiner that has been spawned but not yet linked. [join] is the timed operation;
     * [close] detaches it again.
     */
    inner class PendingJoin internal constructor(
        private val collector: BaselineCollectorCell,
        /**
         * The source's settled element count at prepare time — what [join] arms the
         * completeness fence at, and what the outcome reports as
         * [CatchUpOutcome.expectedElements].
         */
        val settledElements: Int,
    ) : AutoCloseable {

        private var link: Link? = null
        private var joined = false

        /** The joiner's ref, so a caller can assert about the cell it attached. */
        val joinerRef: CellRef get() = collector.ref

        /** Deltas this joiner has observed — baseline plus any live traffic since. */
        val arrivals: Long get() = collector.arrivals.get()

        /** True once [close] has detached the link, so a test can assert the teardown ran. */
        val detached: Boolean get() = joined && link == null

        /**
         * Link this joiner to the source's outlet and wait for the complete baseline.
         *
         * Once per joiner: `catchUpOnLinked` fires on link establishment, so a second join
         * on the same collector would measure nothing. Refused rather than silently
         * re-timed.
         *
         * The fence is armed at [settledElements] — the source's settled size at prepare
         * time — before `connect` is called, never at the live [CatchUpRig.elementsAdded]
         * counter: that counter runs ahead of the source's applied state under a concurrent
         * burst, and arming at it makes the fence unsatisfiable
         * ([CatchUpOutcome.expectedElements] records the measurement). Under concurrent live
         * traffic the real baseline is larger than the armed threshold, which is why
         * [CatchUpOutcome.Baseline.adds] is reported next to `expectedElements` rather than
         * asserted equal to it.
         */
        fun join(): CatchUpOutcome {
            check(!joined) {
                "join() is once per joiner — catchUpOnLinked fires on link establishment, " +
                    "so a second join on the same collector would time an empty wait"
            }
            joined = true
            val expected = settledElements
            collector.armFor(expected)

            val t0 = System.nanoTime()
            if (joinWiring == JoinWiring.LINKED) {
                link = connectedLink(
                    host.managementInlet.call
                        .connect(sourceRef, "outlet", collector.ref, "inlet"),
                    "source.outlet -> joiner.inlet",
                )
            }

            val timeoutMs = CatchUpFixtures.DRAIN_TIMEOUT_MS
            val reached = if (joinWiring == JoinWiring.LINKED && expected >= 1) {
                collector.baselineReached.await(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                // Either nothing was linked, or the source is empty and
                // `catchUpOnLinked`'s empty-state guard sends nothing. Neither can ever
                // satisfy the latch, so the honest end of the wait is the host's own drain
                // fence — positive evidence the queue emptied with nothing delivered —
                // rather than the timeout.
                awaitQuiescent("late join with no baseline to await")
                false
            }
            val waitedMs = (System.nanoTime() - t0) / 1_000_000.0
            val baselineNanos = collector.baselineNanos.get()

            return if (reached && baselineNanos != 0L) {
                CatchUpOutcome.Baseline(
                    catchUpMs = (baselineNanos - t0) / 1_000_000.0,
                    adds = collector.baselineAdds.get(),
                    dels = collector.baselineDels.get(),
                    expectedElements = expected,
                    arrivals = collector.arrivals.get().toInt(),
                )
            } else {
                CatchUpOutcome.NoBaseline(
                    expectedElements = expected,
                    arrivals = collector.arrivals.get().toInt(),
                    waitedMs = waitedMs,
                    largestArrivalAdds = collector.largestAdds.get(),
                )
            }
        }

        /**
         * Detach this joiner from the source's outlet, restoring the fan-out degree the
         * next trial's drive will see (this file's header, drift 2).
         *
         * `Link.unlink()` runs `FanOutlet.unsubscribe` -> `removeConsumer`, so the source
         * stops fanning out to this joiner entirely — which is why the joiner is unlinked
         * rather than despawned: `ManagedHost.despawn` removes the cell but leaves the
         * outlet's consumer entry in place, and every later emission would dead-letter
         * instead of costing nothing.
         *
         * Quiesces first. The consumer map is a `ConcurrentHashMap`, so this is not about
         * corrupting it; it is that a trial whose deliveries are still in flight has not
         * finished being measured, and detaching mid-flight would silently drop arrivals the
         * fence had already counted on.
         */
        override fun close() {
            val established = link ?: return
            awaitQuiescent("late joiner detach")
            established.unlink()
            link = null
        }
    }
}

/**
 * The fixtures BS-9's probe is built from [BEN1-08].
 *
 * Everything touching `:kernel`/`:testkit` lives here in `bench/src/main`, and
 * `bench/src/test` reaches no further than this API — the same split
 * `BoundedReadFixtures`/`BoundedReadProbeTest` and `Graphs`/`OperatorThroughputBenchmark`
 * already use.
 */
object CatchUpFixtures {

    /**
     * The pre-seed size BS-9 names: **10^5 live elements**. A [SetScale] rather than a
     * literal, so this probe and the bounded-read probes cannot drift onto different
     * meanings of "1e5".
     */
    val DEFAULT_SCALE: SetScale = SetScale.N1E5

    /**
     * The live-traffic burst a join is fired into. Deliberately
     * `BoundedReadFixtures.DRIVE_ADDS` **by reference, not by value**: the occupancy
     * column here and E2's are the same statistic over the same shape of drive, so they are
     * only comparable while the two cannot diverge. Changing the burst is a change to that
     * constant, in that file, for both probes at once.
     */
    const val DRIVE_ADDS: Int = BoundedReadFixtures.DRIVE_ADDS

    /** The discarded warmup drive's size, by reference for the same reason. */
    const val WARMUP_ADDS: Int = BoundedReadFixtures.WARMUP_ADDS

    /**
     * Trials per condition. **Three — and raising it is an edit to this line, never a CLI
     * flag.**
     *
     * The reason it cannot be a flag is `RunEnvironment`: a renderer publishes the
     * measurement configuration it reads from these constants, so a run whose real trial
     * count came from `-D` would be published under a configuration it never used — the
     * exact disagreement `BoundedReadFixtures`' JMH-knob block spells out for E1. Three is
     * also the smallest sample [TrialStats] can state a dispersion over, and it sizes this
     * artifact as something that runs in minutes rather than as a sweep. A full-scale sweep
     * raises this constant; see this file's header for why raising it cannot make the maxGap
     * rows `Reportable` either way.
     */
    const val TRIALS: Int = 3

    /**
     * Discarded warmup drives before any trial is timed. One — non-zero because
     * `RunEnvironment.warmupIterations` refuses to be non-positive, and because the first
     * drive against a fresh host pays JIT and link-path costs no later drive does.
     */
    const val WARMUP_TRIALS: Int = 1

    /**
     * How far into the live drive the join is initiated, in milliseconds.
     *
     * `BoundedReadFixtures.READ_DELAY_MS` by reference: E2 fires its concurrent read
     * ~1 ms in so it lands against an already-queued backlog of data-priority work instead
     * of an empty queue, and a priority-0 `connect` needs exactly the same setup for the
     * queue-jump to be observable at all (this file's header, "the mechanism").
     */
    const val JOIN_DELAY_MS: Long = BoundedReadFixtures.READ_DELAY_MS

    /**
     * Hang backstop for a drive, a drain, or a baseline wait, in milliseconds. Not a
     * convergence budget: crossing it means the host never drained or the baseline never
     * arrived, and the fixture says so rather than passing.
     */
    const val DRAIN_TIMEOUT_MS: Long = BoundedReadFixtures.DRAIN_TIMEOUT_MS

    /** Every result carries [Drive.REAL]; there is no SIM variant. See this file's header. */
    val DRIVE: Drive get() = BoundedReadFixtures.DRIVE

    /**
     * Build the `source -> pre-existing subscriber` rig on a real host, **unseeded**.
     *
     * Seeding is the caller's, and the link is established here, first, while the source is
     * still empty — see [CatchUpRig]'s KDoc for why that order is not interchangeable.
     *
     * A refused link is a fixture bug, not a measurement, so the `connect` result is checked
     * rather than discarded: the failure this closes is a silently unlinked rig reporting a
     * beautifully small stall over zero arrivals.
     */
    fun rig(
        scale: SetScale = DEFAULT_SCALE,
        wiring: RigWiring = RigWiring.LINKED,
        joinWiring: JoinWiring = JoinWiring.LINKED,
    ): CatchUpRig {
        val scheduler = VirtualThreadScheduler("bench-catchup-${scale.label}")
        val host = ManagedHost(scheduler = scheduler)
        val source = SetCell<Int>()
        val existing = ArrivalCollectorCell()

        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(existing)
        if (wiring == RigWiring.LINKED) {
            connectedLink(
                host.managementInlet.call
                    .connect(source.ref, "outlet", existing.ref, "inlet"),
                "source.outlet -> existing.inlet",
            )
        }
        scheduler.awaitDrained("catch-up rig construction ${scale.label}", DRAIN_TIMEOUT_MS)

        val sourceApi = host.lookup<SetApi<Int>>(source.ref)
            ?: error("source SetCell ${source.ref} not hosted after spawn")
        return CatchUpRig(
            scale = scale,
            wiring = wiring,
            joinWiring = joinWiring,
            host = host,
            scheduler = scheduler,
            sourceApi = sourceApi,
            sourceRef = source.ref,
            existing = existing,
        ) { scheduler.shutdown() }
    }

    /**
     * The `RunEnvironment` this probe measures under [BEN1-23], delegated to
     * `BoundedReadFixtures.probeRunEnvironment` — which is where the argument for an
     * in-process probe reading its own JVM properties (and refusing without a
     * caller-attested harness commit) is written down in full. Repeating either the
     * mechanism or the argument here would let the two drift.
     */
    fun probeRunEnvironment(statistic: String): RunEnvironment =
        BoundedReadFixtures.probeRunEnvironment(
            statistic = statistic,
            trials = TRIALS,
            warmupTrials = WARMUP_TRIALS,
        )
}

/**
 * The `Link` of a connection that was established, or a failure naming what did not
 * connect.
 *
 * `LinkResult.Deferred` is refused too, and deliberately: it is the cross-host proxy
 * answer, whose outcome the initiator cannot observe. This rig is single-host by
 * construction, so a `Deferred` would mean the graph is not what the fixture believes it to
 * be — and an unobservable handshake is exactly the state that produces a silently
 * unlinked rig reporting a beautifully small stall over zero arrivals. (`BoundedReadFixtures`
 * makes the same check with the same reasoning; its helper is `private` to that object and
 * this one additionally has to return the `Link` so a joiner can be detached, so the two
 * coexist rather than one being hoisted — hoisting would mean editing that file.)
 */
internal fun connectedLink(result: LinkResult, what: String): Link {
    check(result is LinkResult.Connected) {
        "catch-up rig link $what did not connect: $result"
    }
    return result.link
}
