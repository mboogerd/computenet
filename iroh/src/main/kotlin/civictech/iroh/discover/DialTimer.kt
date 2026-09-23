package civictech.iroh.discover

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The one place a *delay* is realised in `civictech.iroh.discover` (ktn1l-D17,
 * [DSC2-DIAL-08]).
 *
 * Every "when" in this package is a number — `clock()` plus
 * `DialPolicy.schedule(attempt)` — and this seam is what turns that number
 * into a later wake-up. Splitting the two is what makes a backoff *observable*
 * rather than waited on: a test supplies a `ManualTimer` over a manual clock
 * and advances it, so `civictech.iroh.discover` contains no `Thread.sleep` in
 * production or test code, and a retry schedule measured in minutes is proven
 * in microseconds.
 *
 * The scheduled [Runnable] is run on the timer's own thread and must therefore
 * obey the same rule every other callback in this package obeys: **enqueue
 * only**. [DiscoveredPeering] schedules nothing but a `Due` post.
 */
interface DialTimer {

    /**
     * Run [task] once, no sooner than [delayMs] from now.
     *
     * @return a handle whose `close()` cancels the task if it has not run. It
     *   is idempotent, and closing it after the task ran is a no-op — which is
     *   what makes the caller's "cancel the armed retry for this key"
     *   ([DSC2-DIAL-06]) safe against a task that fired a moment earlier.
     */
    fun schedule(delayMs: Long, task: Runnable): AutoCloseable

    /**
     * Release whatever the implementation owns. Default: nothing — a test
     * double owns no thread. [DiscoveredPeering.close] calls it exactly once.
     */
    fun shutdown() {}

    companion object {
        /** The production timer: one daemon thread, `iroh-discover-timer`. */
        fun threaded(): DialTimer = ThreadedDialTimer()
    }
}

/**
 * [DialTimer.threaded]'s implementation: a single-thread
 * [ScheduledExecutorService] on a daemon thread of its own.
 *
 * Single-threaded on purpose. The tasks it runs post one `Due` command each,
 * so they cost nothing and need no parallelism; a pool would only add threads
 * that can interleave with the policy thread for no gain. Daemon, because this
 * timer must never be the reason a JVM stays alive — a host that drops its
 * [DiscoveredPeering] without closing it has a leak, not a hang.
 */
private class ThreadedDialTimer : DialTimer {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "iroh-discover-timer").apply { isDaemon = true }
        }

    override fun schedule(delayMs: Long, task: Runnable): AutoCloseable {
        val future = executor.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
