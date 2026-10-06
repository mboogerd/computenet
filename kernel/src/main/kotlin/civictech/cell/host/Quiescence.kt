package civictech.cell.host

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A host did not drain within the caller's timeout (computenet-q5jzk Q3).
 *
 * An [IllegalStateException] rather than a test-framework type so the kernel
 * stays free of opentest4j; `civictech.testkit.awaitDrained` rethrows it as an
 * `AssertionFailedError` carrying the same message.
 */
class QuiescenceTimeout(message: String) : IllegalStateException(message)

/**
 * A fence on one host's scheduler queue: completes once the queue has drained
 * at every band — management 0, router 10, data 20, drain 30 — including all
 * the work that queued work enqueued, however deep the cascade.
 *
 * **Why a fence and not a poll.** The deterministic host gets quiescence for
 * free — [SimulationController.runToIdle] steps until no scheduler has work, so
 * "settled" is a fact. On a live scheduler the obvious substitute is to sample
 * an observable value and call it settled once two samples agree. That detector
 * is unsound under load: two equal samples mean "nothing changed during this
 * window", which a starved host produces as readily as a converged one.
 *
 * [HostScheduler.quiescence] inverts the question. It submits one task at
 * [Int.MAX_VALUE] priority — strictly below every band the host uses — and this
 * handle completes when that task runs. [HostScheduler.submit] orders
 * `(priority, sequence)` and drains single-threaded per host (one task runs to
 * completion before the next starts, on every scheduler — a 🟣 task that
 * suspends parks the whole host), so the fence reaches the front only when the
 * queue holds nothing else: not the work queued before it, and not the work that
 * work enqueued. Every same-host delivery goes through that queue (staging in
 * `ManagedHost.enqueueHostedInvocation` is always followed by a data-band
 * dispatch task, or by an armed `drainBatch` that re-arms while work remains),
 * so completion is *positive* evidence that the host emptied its queue.
 *
 * **Recovery is exclusive with external fences.** While `ManagedHost.recoverFrom`
 * is restoring journal records it deliberately gates data dispatch; a staged frame
 * may then temporarily have no pending scheduler task. A low-level scheduler fence
 * cannot see that frame. `ManagedHost.quiescence()` therefore throws
 * [IllegalStateException] if its recovery record loop is active. Serialize external
 * fences and drain/migration calls after `recoverFrom` returns, then use
 * [Recovery.awaitApplied], whose fence is taken only after the gate has lifted.
 * Passive kernel observers that can wait across recovery use ManagedHost's internal
 * recovery-aware fence instead: it defers completion while the gate is raised and
 * re-arms only after staged delivery has been reactivated. That narrower seam is not a
 * public replacement for this host-wide external-barrier contract.
 *
 * **Starvation cannot fake it.** A host denied CPU does not run the fence, so
 * [await] blocks; the answer arrives late rather than wrong. The timeout is a
 * hang backstop, not a convergence budget: crossing it means the host never
 * drained (livelock, a wedged drain thread, a machine so oversubscribed the
 * timeout bought no progress), and [await] throws [QuiescenceTimeout] instead
 * of returning.
 *
 * **What it does not cover.** Quiescence of the *queue* is quiescence of the
 * *host* only while every path that can still produce work goes through this
 * scheduler. That holds for a single-host graph with no attention parking
 * (`AttentionPolicy.suspendAfter == null`, the default — parked traffic waits
 * off-queue for an interest change) and no timers; the kernel schedules nothing
 * on a delay, so there is no third category. Work another thread submits
 * *after* the fence was taken is not covered either — a caller that needs
 * "nothing more" holds its own writers off. Cross-host and cross-JVM
 * convergence needs a condition over the observed values (`awaitUntil`); a
 * fence per host at most sharpens it.
 *
 * **Simulation.** Never block a [SimulationController]'s stepping thread on
 * [await]: nothing steps while it waits, so it can only time out. Step with
 * `runToIdle()` and then read [isReached], or pass [asFuture] to the simulated
 * scheduler's own `await`, which steps until the future completes.
 */
class Quiescence internal constructor(private val future: CompletableFuture<Unit>) {

    /** True once the fence task has run: the queue drained at some point after the fence was taken. */
    val isReached: Boolean get() = future.isDone

    /**
     * Block until the fence has run, or throw [QuiescenceTimeout] naming [what]
     * and [timeoutMs]. Only legal from outside the host's execution context — a
     * host task awaiting its own fence waits for itself.
     */
    fun await(timeoutMs: Long = 30_000, what: String = "quiescence") {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw QuiescenceTimeout("host queue never drained within ${timeoutMs}ms while awaiting: $what")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** The underlying completion, for composition or a simulated scheduler's `await`. */
    fun asFuture(): CompletableFuture<Unit> = future
}

/**
 * Take a [Quiescence] fence on this scheduler: submit one task at
 * [Int.MAX_VALUE] priority that completes the handle (see [Quiescence] for why
 * that is sound). A terminated scheduler makes [HostScheduler.submit] throw, and
 * that propagates — a fence on a dead host is a caller bug, not a timeout.
 */
fun HostScheduler.quiescence(): Quiescence {
    val future = CompletableFuture<Unit>()
    submit(Int.MAX_VALUE) { future.complete(Unit) }
    return Quiescence(future)
}

/**
 * The result of [ManagedHost.recoverFrom]: the replay has *staged* every frame
 * (and restored checkpoint state), but staging is not delivery — each frame is
 * applied by a later data-band scheduler task, and its deliveries may cascade
 * onto further same-host frames. [awaitApplied] fences on all of it.
 *
 * The fence is a [Quiescence] taken immediately after the synchronous replay
 * loop returned, so it is queued behind every frame the replay submitted and,
 * by the fence argument, behind every frame those deliveries staged on this
 * host. When it completes, each replayed frame and each same-host consequence
 * has been delivered — applied by its cell or dead-lettered. The limits are
 * [Quiescence]'s: attention-parked traffic, other hosts, and writes submitted
 * after the fence are outside it.
 *
 * A checkpoint before [awaitApplied] is safe — `checkpoint` runs at management
 * priority 0 and overtakes the still-staged replay frames, but carries them into
 * the compacted journal (computenet-xy7w4 D3) — yet compacts less: call
 * [awaitApplied] first to compact the whole replayed tail.
 *
 * [suppressedReplayDuplicates] is updated while the staged replay cascades are
 * delivered. Read it after [isApplied] becomes true (or after [awaitApplied])
 * for the final count. Its backing recovery session remains stable even after
 * a later recovery replaces the host's replay-position set.
 */
class Recovery internal constructor(
    /** The number of journal `Frame` records the replay submitted; checkpoint, frontier, discharge and outlet-wave records are not frames. */
    val replayedFrames: Int,
    private val replayDuplicateSuppressionCount: () -> Int,
    private val quiescence: Quiescence,
) {
    /** Exact same-journal replay derivations suppressed because their target-port positions were already replayed directly. */
    val suppressedReplayDuplicates: Int get() = replayDuplicateSuppressionCount()

    /** True once every replayed frame and its same-host cascade has been delivered. */
    val isApplied: Boolean get() = quiescence.isReached

    /**
     * Block until the replay has been applied, or throw [QuiescenceTimeout].
     * Never call it on a [SimulationController]'s stepping thread — see [Quiescence].
     */
    fun awaitApplied(timeoutMs: Long = 30_000) {
        quiescence.await(timeoutMs, "recovery of $replayedFrames replayed frames")
    }

    /** The underlying completion, for composition or a simulated scheduler's `await`. */
    fun asFuture(): CompletableFuture<Unit> = quiescence.asFuture()
}
