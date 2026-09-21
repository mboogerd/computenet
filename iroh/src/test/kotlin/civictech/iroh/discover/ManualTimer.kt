package civictech.iroh.discover

/**
 * A [DialTimer] that never waits: it records what was armed and runs it when a
 * test says the clock has reached that point (ktn1l-D17, [DSC2-DIAL-08]).
 *
 * This is what lets `civictech.iroh.discover` contain no `Thread.sleep` in
 * test code either. A backoff of ten minutes is not waited out and is not
 * shortened to a number that happens to be fast — it is *evaluated*, which
 * means a test asserts the schedule the policy actually consulted rather than
 * an approximation of it. [pending] makes a cancelled retry
 * ([DSC2-DIAL-06]) an observable fact instead of an absence someone has to
 * wait for.
 *
 * Every method is safe from any thread: tasks are armed on the policy thread
 * and advanced from the test thread.
 *
 * @param clock the same `() -> Long` the [DiscoveredPeering] under test holds.
 *   A delay is stored as an absolute `clock() + delayMs`, so advancing the
 *   test's clock and advancing this timer agree by construction.
 */
class ManualTimer(private val clock: () -> Long) : DialTimer {

    private class Armed(val dueAt: Long, val task: Runnable)

    private val armed = mutableListOf<Armed>()

    override fun schedule(delayMs: Long, task: Runnable): AutoCloseable {
        val entry = Armed(clock() + delayMs.coerceAtLeast(0), task)
        synchronized(armed) { armed += entry }
        // Idempotent, and a no-op once the task has run — the handle a caller
        // closes to cancel a retry may well be one that already fired.
        return AutoCloseable { synchronized(armed) { armed -= entry } }
    }

    /** How many tasks are armed and not yet run or cancelled. */
    fun pending(): Int = synchronized(armed) { armed.size }

    /**
     * Run every task due at or before [now], oldest first, and forget them.
     *
     * The tasks run on the CALLER's thread — the test thread — which is
     * exactly what the production timer does on its own: they only post a
     * command, and the policy thread does the work.
     */
    fun advanceTo(now: Long) {
        val due = synchronized(armed) {
            val ready = armed.filter { it.dueAt <= now }.sortedBy { it.dueAt }
            armed.removeAll(ready)
            ready
        }
        due.forEach { it.task.run() }
    }
}
