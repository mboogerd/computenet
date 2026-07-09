package civictech.cell.host

import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Deterministic, single-threaded execution over one or more hosts (spec 52's SimulatedHost).
 *
 * With a [seed], the controller picks uniformly among hosts that have work — randomness is
 * ACROSS hosts only; within a host the (priority, submission) order is inviolable, preserving
 * per-link FIFO (spec 31 rule 3) under every seed. Without a seed, the first busy host runs,
 * yielding one fixed order.
 *
 * Everything (submission, stepping, awaiting) is expected on one thread; this class is not
 * thread-safe by design.
 */
class SimulationController(seed: Long? = null) {

    private val rng = seed?.let { Random(it) }
    private val schedulers = mutableListOf<SimulatedScheduler>()

    /** Create and register a scheduler; pass one to each simulated host. */
    fun scheduler(): HostScheduler = SimulatedScheduler().also { schedulers += it }

    /** Run one task on one host. Returns false if all hosts are idle. */
    fun step(): Boolean {
        val busy = schedulers.filter { it.hasWork() }
        if (busy.isEmpty()) return false
        val pick = rng?.let { busy[it.nextInt(busy.size)] } ?: busy.first()
        pick.stepOne()
        return true
    }

    fun runToIdle() {
        while (step()) {
            // drain
        }
    }

    private inner class SimulatedScheduler : HostScheduler {
        private val queue = PriorityQueue<ScheduledTask>()
        private var sequence = 0L

        override fun submit(priority: Int, action: () -> Unit) {
            queue.add(ScheduledTask(priority, ++sequence, action))
        }

        override fun <T> await(future: CompletableFuture<T>): T {
            while (!future.isDone) {
                check(step()) { "simulation quiescent but awaited future incomplete" }
            }
            return try {
                future.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        override fun shutdown() {
            queue.clear()
        }

        fun hasWork(): Boolean = queue.isNotEmpty()

        fun stepOne() {
            queue.poll()?.action?.invoke()
        }
    }
}
