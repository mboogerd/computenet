package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T18 (audit finding B10): [ManagedHost.snapshotOf]'s cross-thread contract.
 *
 * `snapshotOf` is the host-routed state read the inspector's content search
 * calls from an HTTP thread — never from the host's own execution context. It
 * hands the read to [HostScheduler.submit], whose threading contract was silent
 * until T18 while one implementation, [SimulationController]'s, held a plain
 * `PriorityQueue` behind a class KDoc that said "not thread-safe by design".
 * Nothing wired hit that race yet; the first deterministic inspector test would
 * have, and its failure mode was a corrupted heap or a silently dropped task
 * rather than an exception.
 *
 * The chosen resolution is *safe accept*, not fail-fast: submission is now
 * contractually legal from any thread and the simulated queue is guarded. So
 * these tests pin the accepting behavior — a foreign-thread read against a
 * simulated host is queued, drained in order, and answered — plus the two
 * properties that make that safe to have chosen: no task is lost under a
 * genuine producer/drain race, and the drain itself stays deterministic.
 *
 * The second half is cancellation: a search that gives up on a slow cell
 * cancels its future, and the task already queued on the host must not spend a
 * whole state copy on an answer nobody will read.
 */
class SnapshotOfHardeningTest {

    /**
     * A [Stateful] cell that counts its own `snapshot()` calls — the observable
     * the cancellation criterion is stated in ("does not call `snapshot()` on
     * the cell"), which a returned value alone cannot witness.
     *
     * Note that spawning already costs one call: `ManagedHost` takes a spawn-time
     * checkpoint of every `Stateful` cell (G-26). Every test below therefore
     * zeroes the counter after spawn.
     */
    private class CountingStateCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        val snapshots = AtomicInteger()

        @Suppress("unused")
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())

        override fun snapshot(): Serializable = snapshots.incrementAndGet()

        override fun restore(state: Serializable) {
            snapshots.set(state as Int)
        }
    }

    /** A simulated host with one counting cell spawned and its snapshot counter zeroed. */
    private fun simulated(): Triple<SimulationController, ManagedHost, CountingStateCell> {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = CountingStateCell().also { host.managementInlet.call.spawn(it) }
        cell.snapshots.set(0)
        return Triple(controller, host, cell)
    }

    @Test
    fun `a foreign thread's read against a simulated host is queued, then answered on the next drain`() {
        val (controller, host, cell) = simulated()

        lateinit var pending: CompletableFuture<Serializable?>
        // the read is issued from a thread that is not the controller's — the
        // shape DataSearch has (an HTTP worker), and the shape that used to race
        // the simulated queue. join() gives this thread happens-before on the
        // submit, so the assertions below read settled state.
        Thread { pending = host.snapshotOf(cell.ref) }.apply { start() }.join(TIMEOUT_MS)

        // routed, not executed inline: the whole point of the seam is that the
        // copy happens on the host's execution context, which has not run yet
        pending.isDone shouldBe false
        cell.snapshots.get() shouldBe 0

        controller.runToIdle()

        pending.isDone shouldBe true
        pending.get() shouldBe 1
        cell.snapshots.get() shouldBe 1
    }

    @Test
    fun `concurrent foreign-thread reads neither corrupt the simulated queue nor lose a task`() {
        val (controller, host, cell) = simulated()

        val reads = ConcurrentLinkedQueue<CompletableFuture<Serializable?>>()
        val go = CountDownLatch(1)
        val finished = CountDownLatch(PRODUCERS)
        val producers = (0 until PRODUCERS).map {
            Thread {
                go.await()
                repeat(PER_PRODUCER) { reads += host.snapshotOf(cell.ref) }
                finished.countDown()
            }.apply { isDaemon = true; start() }
        }

        go.countDown()
        // drain *while* the producers submit: this is the actual race — a
        // foreign `add` interleaved with the controller's own `poll`, which is
        // what an unguarded PriorityQueue cannot survive. Bounded, so a wedged
        // run fails with a message rather than hanging (AGENTS.md).
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (finished.count > 0L) {
            controller.step()
            check(System.nanoTime() < deadline) { "producers did not finish within ${TIMEOUT_MS}ms" }
        }
        producers.forEach { it.join(TIMEOUT_MS) }
        controller.runToIdle()

        val submitted = PRODUCERS * PER_PRODUCER
        reads.size shouldBe submitted
        // every submitted task survived the race and ran exactly once: no
        // dropped enqueue, no duplicate, no heap left in a state that swallows
        // or reorders an entry into oblivion
        reads.count { it.isDone } shouldBe submitted
        cell.snapshots.get() shouldBe submitted
    }

    @Test
    fun `a joined foreign-thread submit keeps a seeded simulation reproducible and in FIFO position`() {
        // the determinism argument for choosing safe-accept, made executable:
        // guarding the queue changed enqueue only, so (priority, sequence) still
        // fixes the drain order. A foreign submit that establishes happens-before
        // (here, join()) has as definite a sequence number as any local one.
        fun trace(seed: Long): List<String> {
            val controller = SimulationController(seed)
            val log = mutableListOf<String>()
            val a = controller.scheduler()
            val b = controller.scheduler()
            repeat(10) { i -> a.submit(10) { log += "a$i" } }
            Thread { b.submit(10) { log += "foreign" } }.apply { start() }.join(TIMEOUT_MS)
            repeat(10) { i -> b.submit(10) { log += "b$i" } }
            controller.runToIdle()
            return log
        }

        val runs = (1..5).map { trace(11) }

        runs.distinct().size shouldBe 1
        // and it holds its FIFO slot on its own host: submitted first, drained first
        runs.first().first { it == "foreign" || it.startsWith("b") } shouldBe "foreign"
    }

    @Test
    fun `a read cancelled before its task runs never snapshots the cell`() {
        val (controller, host, cell) = simulated()

        val pending = host.snapshotOf(cell.ref)
        // the task is queued and has not run — precisely the window DataSearch's
        // deadline expires in
        pending.isDone shouldBe false
        pending.cancel(false) shouldBe true

        controller.runToIdle()

        cell.snapshots.get() shouldBe 0
        pending.isCancelled shouldBe true
    }

    @Test
    fun `an uncancelled read on the same path does snapshot the cell`() {
        // the control for the test above: same host, same queueing, cancellation
        // the only difference — so the zero there is the check firing, not a
        // task that never ran.
        val (controller, host, cell) = simulated()

        val pending = host.snapshotOf(cell.ref)
        controller.runToIdle()

        cell.snapshots.get() shouldBe 1
        pending.get() shouldBe 1
    }

    private companion object {
        const val PRODUCERS = 8
        const val PER_PRODUCER = 200
        const val TIMEOUT_MS = 30_000L
    }
}
