package civictech.testkit

import civictech.cell.host.HostScheduler
import civictech.cell.host.QuiescenceTimeout
import civictech.cell.host.quiescence
import org.opentest4j.AssertionFailedError

/**
 * The live-scheduler counterpart of [civictech.cell.host.SimulationController.runToIdle]:
 * block until this host's queue has actually drained, then return.
 *
 * A test-flavoured wrapper over the kernel fence [HostScheduler.quiescence]
 * (computenet-q5jzk): one task at [Int.MAX_VALUE] priority, below every band
 * the host uses, whose completion is positive evidence the queue emptied —
 * including everything the queued work enqueued, however deep the cascade.
 * Starvation delays the answer, never counterfeits it; [timeoutMs] is a hang
 * backstop, not a convergence budget. Why a fence beats "two equal samples",
 * and what it does not cover (attention-parked traffic, timers, other hosts —
 * `awaitUntil` remains the cross-host tool), is argued on
 * [civictech.cell.host.Quiescence]'s KDoc.
 *
 * The only difference from the kernel handle is the failure type: a timeout
 * surfaces as [AssertionFailedError] naming [what] and [timeoutMs], so a test
 * fails as an assertion rather than an `IllegalStateException`.
 */
fun HostScheduler.awaitDrained(what: String, timeoutMs: Long = 30_000) {
    try {
        quiescence().await(timeoutMs, what)
    } catch (e: QuiescenceTimeout) {
        throw AssertionFailedError(e.message, e)
    }
}
