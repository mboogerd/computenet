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
 * Stepping and awaiting are expected on one thread — the controller's own — and are not
 * thread-safe by design: that single-threadedness *is* the determinism. Submission is the
 * documented exception (T18, finding B10): [HostScheduler.submit] is contractually callable
 * from any thread, so each scheduler's pending queue is guarded. Only the enqueue is; the
 * drain order is untouched, and every simulation that submits from the controller's own
 * thread — which is all of them today — traces exactly as it did before. See
 * [SimulatedScheduler.submit] for that argument in full.
 */
class SimulationController(seed: Long? = null) {

    private val rng = seed?.let { Random(it) }
    private val schedulers = mutableListOf<SimulatedScheduler>()

    companion object {
        /**
         * T12 finding 1: `runToIdle()` was a bare `while (step()) {}` — 440+
         * call sites with no signal beyond T01's 5-minute JUnit backstop when
         * a livelock hangs the simulation. Empirically calibrated: a full,
         * instrumented `./gradlew :kernel:test` run (2026-07-27, this
         * ticket) recorded the step count of every `runToIdle()` call across
         * the whole suite (32,965 calls); the observed maximum was 5,667
         * steps. This sets the default budget at ~10x that measured max,
         * rounded up — generous headroom for legitimate long drains (deep
         * seed sweeps, multi-host meshes) while still turning a genuine
         * livelock into a diagnosable failure instead of riding T01's
         * 5-minute backstop to a bare timeout. Callers with a legitimately
         * larger drain pass an explicit [runToIdle] budget; never raise this
         * default to accommodate one call site.
         */
        const val DEFAULT_BUDGET: Int = 60_000
    }

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

    /**
     * Drain to idle under a hard step [budget] (default [DEFAULT_BUDGET]):
     * quiescence is asserted, not hoped for. Throws when the budget is
     * exhausted rather than looping forever — a stalled simulation fails
     * loudly, with the step count in the message, instead of riding T01's
     * outer JUnit timeout to an undiagnosable hang. Returns the number of
     * steps taken.
     */
    fun runToIdle(budget: Int = DEFAULT_BUDGET): Int {
        var steps = 0
        while (step()) {
            check(++steps <= budget) { "simulation did not quiesce within $budget steps — likely livelock" }
        }
        return steps
    }

    private inner class SimulatedScheduler(override val color: HostColor) : HostScheduler {

        /**
         * Guards [queue] and [sequence], and nothing else (T18). Those two are
         * the whole of this scheduler's state that a foreign thread can reach —
         * it reaches them only through [submit]. Everything else here
         * ([inFlight], [resumptions], the coroutine machinery, and the
         * controller's [schedulers] list) stays controller-thread-only, which is
         * what keeps the simulation deterministic; widening this lock to cover
         * them would buy nothing and hide that boundary.
         *
         * Held by the controller-thread readers too ([hasWork], [stepOne],
         * [shutdown]): a lock that only the producer takes excludes nothing.
         * Uncontended in every existing simulation, since they submit from the
         * controller's thread.
         */
        private val queueLock = Any()

        private val queue = PriorityQueue<ScheduledTask>()
        private var sequence = 0L

        /** True while a started task is suspended; the host runs nothing else until it resumes. */
        private var inFlight = false

        /** Resumptions of the in-flight task, delivered by [SimulatedDispatcher]; run as steps. */
        private val resumptions = ArrayDeque<Runnable>()

        /**
         * Thread-safe enqueue (T18, finding B10), honoring [HostScheduler.submit]'s
         * threading contract. This used to be a bare `queue.add` on a plain
         * [PriorityQueue], relying on the class-wide "one thread" assumption a
         * caller had no way to see: a foreign-thread submit — `ManagedHost.snapshotOf`
         * from an observer thread, or a dead letter raised off-host (T04 finding 6) —
         * raced the controller's own `poll`, and the failure mode was a corrupted
         * heap or a silently dropped task rather than an exception. No wired call
         * site hit it yet; the first deterministic inspector test would have.
         *
         * **Determinism argument.** The lock covers enqueue only. A task is still
         * stamped `(priority, ++sequence)` at submission, and [step]/[runToIdle]
         * still drain strictly in that order, one task at a time, on the
         * controller's thread — nothing about *what runs next* changed, and no
         * randomness was added. Every simulation in the repo submits from the
         * controller's thread, so its sequence numbering, and therefore its whole
         * trace under any seed, is identical to before. A caller that deliberately
         * submits from another thread buys ordinary cross-thread nondeterminism
         * about *where* its own task lands — inherent to submitting concurrently,
         * and the exact price [HostScheduler.submit] names — while every task that
         * does land is drained in the one deterministic order.
         */
        override fun submit(priority: Int, action: suspend () -> Unit) {
            synchronized(queueLock) { queue.add(ScheduledTask(priority, ++sequence, action)) }
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
            synchronized(queueLock) { queue.clear() }
        }

        fun hasWork(): Boolean =
            resumptions.isNotEmpty() || (!inFlight && synchronized(queueLock) { queue.isNotEmpty() })

        fun stepOne() {
            resumptions.pollFirst()?.let { it.run(); return }
            // the poll is inside the lock, the task's execution deliberately
            // outside it: the lock's scope is the queue, not the drain. Holding
            // it across execution would not deadlock (monitors are reentrant, so
            // the ordinary re-entrant submit — see SimulationControllerTest —
            // would still pass) but it would block foreign submitters for a whole
            // task's duration and quietly re-widen the invariant this narrows.
            val task = synchronized(queueLock) { queue.poll() } ?: return
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
