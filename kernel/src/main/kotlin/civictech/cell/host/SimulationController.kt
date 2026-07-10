package civictech.cell.host

import kotlinx.coroutines.CoroutineDispatcher
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.resume

/**
 * Deterministic, single-threaded execution over one or more hosts (spec 52's SimulatedHost).
 *
 * With a [seed], the controller picks uniformly among hosts that have work — randomness is
 * ACROSS hosts only; within a host the (priority, submission) order is inviolable, preserving
 * per-link FIFO (spec 31 rule 3) under every seed. Without a seed, the first busy host runs,
 * yielding one fixed order.
 *
 * Suspension (🟣 hosts, spec 32): a stepped task starts undispatched and runs synchronously
 * on the controller thread until it completes (the common case) or genuinely suspends. A
 * suspended task parks its host — matching [CoroutineScheduler]'s sequential drain — and its
 * resumption re-enters the simulation as an ordinary step, dispatched by the task that
 * unblocked it. A quiescent simulation with a parked task is a faithful deadlock.
 *
 * Everything (submission, stepping, awaiting) is expected on one thread; this class is not
 * thread-safe by design.
 */
class SimulationController(seed: Long? = null) {

    private val rng = seed?.let { Random(it) }
    private val schedulers = mutableListOf<SimulatedScheduler>()

    /** Create and register a scheduler; pass one to each simulated host. */
    fun scheduler(color: HostColor = HostColor.BLOCKING): HostScheduler =
        SimulatedScheduler(color).also { schedulers += it }

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

    private inner class SimulatedScheduler(override val color: HostColor) : HostScheduler {
        private val queue = PriorityQueue<ScheduledTask>()
        private var sequence = 0L

        /** True while a started task is suspended; the host runs nothing else until it resumes. */
        private var inFlight = false

        /** Resumptions of the in-flight task, delivered by [SimulatedDispatcher]; run as steps. */
        private val resumptions = ArrayDeque<Runnable>()

        override fun submit(priority: Int, action: suspend () -> Unit) {
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

        fun hasWork(): Boolean = resumptions.isNotEmpty() || (!inFlight && queue.isNotEmpty())

        fun stepOne() {
            resumptions.pollFirst()?.let { it.run(); return }
            val task = queue.poll() ?: return
            inFlight = true
            val completion = object : Continuation<Unit> {
                override val context: CoroutineContext = SimulatedDispatcher()
                override fun resumeWith(result: Result<Unit>) {
                    inFlight = false
                    result.getOrThrow() // actions must not throw; fail the test loudly
                }
            }
            // undispatched start: runs here, now, until completion or first real suspension
            task.action.createCoroutineUnintercepted(completion).resume(Unit)
        }

        private inner class SimulatedDispatcher : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                resumptions.add(block)
            }
        }
    }
}
