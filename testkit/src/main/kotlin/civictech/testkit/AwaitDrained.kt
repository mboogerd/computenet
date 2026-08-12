package civictech.testkit

import civictech.cell.host.HostScheduler
import org.opentest4j.AssertionFailedError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The live-scheduler counterpart of [civictech.cell.host.SimulationController.runToIdle]:
 * block until this host's queue has actually drained, then return.
 *
 * **Why a fence and not a poll.** The deterministic hosts get quiescence for
 * free — `runToIdle()` steps until no scheduler has work, so "settled" is a
 * fact, not an estimate. On [civictech.cell.host.VirtualThreadScheduler] tests
 * have reached for the obvious substitute: sample some observable value every
 * few hundred milliseconds and call it settled once two samples agree. That
 * detector is unsound under load, and provably so: two equal samples mean
 * "nothing changed during this window", which a starved host produces just as
 * readily as a converged one. A test built on it fails on a busy machine with
 * a mid-convergence snapshot and passes on an idle one, which is the definition
 * of a flake.
 *
 * This helper inverts the question. It submits one task at [Int.MAX_VALUE]
 * priority — strictly below every band the host uses (management 0, data 20,
 * drain 30) — and waits for it to run. [HostScheduler.submit]'s ordering
 * contract is `(priority, submission)`, and the drain is single-threaded per
 * host, so that task reaches the front only when the queue holds nothing else:
 * not the work queued before it, and not the work that work enqueued, however
 * deep the cascade. Its completion is therefore a *positive* event — evidence
 * that the host emptied its queue — of exactly the shape [awaitUntil] wants.
 *
 * **Starvation cannot fake it.** A host denied CPU does not run the fence, so
 * this blocks; the answer arrives late rather than wrong. There is no window
 * length to tune and no quiet interval to mistake for convergence. [timeoutMs]
 * is a hang backstop, not a convergence budget: crossing it means the host
 * never drained (livelock, a wedged drain thread, or a machine so oversubscribed
 * that 30s bought no progress), and the test fails loudly saying so instead of
 * asserting on a half-converged graph.
 *
 * **What it does not cover.** Quiescence of the *queue* is quiescence of the
 * *host* only while every path that can still produce work goes through this
 * scheduler. That holds for a single-host graph with no attention parking
 * (`AttentionPolicy.suspendAfter == null`, the default — parked traffic waits
 * off-queue for an interest change) and no timers; the kernel schedules nothing
 * on a delay, so there is no third category. Cross-host and cross-JVM
 * convergence needs a condition over the observed values — `awaitUntil` — and a
 * fence per host at most sharpens it.
 */
fun HostScheduler.awaitDrained(what: String, timeoutMs: Long = 30_000) {
    val drained = CountDownLatch(1)
    submit(Int.MAX_VALUE) { drained.countDown() }
    if (!drained.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw AssertionFailedError("host queue never drained within ${timeoutMs}ms while awaiting: $what")
    }
}
