package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.ManagedHost.LifecycleTransition
import civictech.cell.link.Link
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * V2-KERNEL — [ManagedHost.onLifecycle], the per-cell suspend / resume /
 * drained / host-resumed notification seam.
 *
 * Why it exists: [ManagedHost.isSuspended] and [ManagedHost.isDrained] say what
 * is true *now*. They cannot say when it became true, and a transition that
 * flips back between two reads is invisible to a sampler entirely — so an
 * out-of-kernel observer keeping a per-cell activity log had to poll every
 * known cell, forever, for changes that are individually rare.
 *
 * The contract these tests pin down is [LocationRegistry]'s hook contract,
 * applied to the host: fired from the existing transition points only,
 * synchronously on the mutating thread, after the state change is visible; a
 * throwing listener is a failed *notification*, never a failed transition; the
 * handle really detaches; and a call that changes nothing notifies nothing.
 */
class HostLifecycleListenerTest {

    interface CounterProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** Counts what it accepts; a negative input throws mid-message (SupervisionTest's shape). */
    class FragileCounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input < 0) throw IllegalStateException("poison: $input")
                    received += input
                }
            })
        }
    }

    /** Records `(ref, transition)` in order. */
    private class Recorder {
        val seen = mutableListOf<Pair<CellRef, LifecycleTransition>>()
        val transitions: List<LifecycleTransition> get() = seen.map { it.second }
        fun attach(host: ManagedHost): AutoCloseable = host.onLifecycle { ref, t -> seen += ref to t }
    }

    private class Fixture(cellCount: Int = 1) {
        val controller = SimulationController(seed = 11)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val letters = mutableListOf<DeadLetter>()
        val cells = (0 until cellCount).map { FragileCounterCell() }
        val cell get() = cells.first()

        init {
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            letters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            cells.forEach { host.managementInlet.call.spawn(it) }
            controller.runToIdle()
        }

        fun api(of: FragileCounterCell = cell): Consumer<Int> =
            (HostedCellProxy.create(of.ref, host, CounterProxy::class.java) as CounterProxy).inlet.call

        val manage get() = host.managementInlet.call
    }

    // ------------------------------------------------------ the four transitions

    @Test
    fun `suspend and resume report exactly the transitions they make, for the right ref`() {
        val f = Fixture(cellCount = 2)
        val recorder = Recorder().also { it.attach(f.host) }

        f.manage.suspend(f.cells[0].ref)
        f.controller.runToIdle()
        recorder.seen shouldContainExactly listOf(f.cells[0].ref to LifecycleTransition.SUSPENDED)
        f.host.isSuspended(f.cells[0].ref) shouldBe true
        f.host.isSuspended(f.cells[1].ref) shouldBe false

        f.manage.resume(f.cells[0].ref)
        f.controller.runToIdle()
        recorder.seen shouldContainExactly listOf(
            f.cells[0].ref to LifecycleTransition.SUSPENDED,
            f.cells[0].ref to LifecycleTransition.RESUMED,
        )
        f.host.isSuspended(f.cells[0].ref) shouldBe false
        f.letters.shouldBeEmpty()
    }

    @Test
    fun `a drain reports every cell the host holds, and so does the host resume`() {
        val f = Fixture(cellCount = 3)
        val recorder = Recorder().also { it.attach(f.host) }
        val held = f.cells.map { it.ref }.toSet()

        f.manage.drainHost()
        f.controller.runToIdle()

        recorder.transitions shouldBe List(3) { LifecycleTransition.DRAINED }
        recorder.seen.map { it.first }.toSet() shouldBe held
        f.host.isDrained shouldBe true

        f.manage.resumeHost()
        f.controller.runToIdle()

        recorder.transitions.drop(3) shouldBe List(3) { LifecycleTransition.HOST_RESUMED }
        recorder.seen.drop(3).map { it.first }.toSet() shouldBe held
        f.host.isDrained shouldBe false
    }

    @Test
    fun `supervision-driven suspension reaches the listener like an explicit suspend`() {
        val f = Fixture()
        val recorder = Recorder().also { it.attach(f.host) }
        f.manage.supervise(f.cell.ref, SupervisionPolicy.SUSPEND)

        listOf(1, -1, 2).forEach(f.api()::provide)
        f.controller.runToIdle()

        // a cell parked by a failure is not silently different from one parked
        // by a management call: same seam, same transition, same ref
        recorder.seen shouldContainExactly listOf(f.cell.ref to LifecycleTransition.SUSPENDED)
        f.host.isSuspended(f.cell.ref) shouldBe true
        f.cell.received shouldBe listOf(1)
        f.letters.size shouldBe 1

        f.manage.resume(f.cell.ref)
        f.controller.runToIdle()
        recorder.transitions shouldContainExactly listOf(
            LifecycleTransition.SUSPENDED,
            LifecycleTransition.RESUMED,
        )
        f.cell.received shouldBe listOf(1, 2)
    }

    // ------------------------------------------------------------- idempotence

    @Test
    fun `a repeated suspend on an already-suspended cell notifies nothing`() {
        val f = Fixture()
        val recorder = Recorder().also { it.attach(f.host) }

        f.manage.suspend(f.cell.ref)
        f.manage.suspend(f.cell.ref)
        f.manage.suspend(f.cell.ref)
        f.controller.runToIdle()

        // suspend on an already-suspended cell is a no-op in the kernel
        // (ManagedHost's `if (!suspendedCells.containsKey(ref))`), so no state
        // changed and there is nothing to report
        recorder.transitions shouldContainExactly listOf(LifecycleTransition.SUSPENDED)
    }

    /**
     * The decided answer to "what does supervision SUSPEND do when the cell is
     * already suspended" (V2-KERNEL): the same no-op an explicit `suspend` is.
     *
     * The branch is genuinely reachable — `deliver`'s park gate exempts
     * `PORT_PROTOCOL`, so the always-open metadata plane still enters an
     * already-suspended cell and its handler can still fail. Before this ticket
     * that installed a *fresh* `ParkQueue`, discarding everything the first
     * suspension had parked with no dead letter and no accounting; this test
     * pins both halves of the fix — one notification, and the parked traffic
     * still there to replay.
     */
    @Test
    fun `a second supervision suspension neither notifies again nor discards the parked traffic`() {
        val f = Fixture()
        val recorder = Recorder().also { it.attach(f.host) }
        f.manage.supervise(f.cell.ref, SupervisionPolicy.SUSPEND)

        listOf(-1, 1, 2).forEach(f.api()::provide)
        f.controller.runToIdle()
        recorder.transitions shouldContainExactly listOf(LifecycleTransition.SUSPENDED)

        // a metadata-plane delivery reaches the parked cell and fails inside it
        ProtocolSupport.of(f.cell.inlet).handle(Protocols.Attention) { _, _ ->
            throw IllegalStateException("protocol handler blew up")
        }
        f.host.enqueueHostedInvocation(
            HostedPortInvocation(
                f.cell.ref,
                "inlet",
                HostedPortInvocation.Type.PORT_PROTOCOL,
                Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Attention,
                protocolLink = selfLink(f.cell),
                protocolMessage = Attention(.5f),
            ),
        )
        f.controller.runToIdle()

        // the failure dead-lettered (observability is not a policy) but the cell
        // was already suspended: no second transition
        f.letters.size shouldBe 2
        recorder.transitions shouldContainExactly listOf(LifecycleTransition.SUSPENDED)

        f.manage.resume(f.cell.ref)
        f.controller.runToIdle()
        // the park queue survived the second suspension, in order
        f.cell.received shouldBe listOf(1, 2)
        recorder.transitions shouldContainExactly listOf(
            LifecycleTransition.SUSPENDED,
            LifecycleTransition.RESUMED,
        )
    }

    // --------------------------------------------------- detachment, containment

    @Test
    fun `a closed handle stops receiving while an open one keeps receiving`() {
        val f = Fixture()
        val detached = Recorder()
        val attached = Recorder()
        val handle = detached.attach(f.host)
        attached.attach(f.host)

        f.manage.suspend(f.cell.ref)
        f.controller.runToIdle()
        handle.close()
        f.manage.resume(f.cell.ref)
        f.controller.runToIdle()

        detached.transitions shouldContainExactly listOf(LifecycleTransition.SUSPENDED)
        attached.transitions shouldContainExactly listOf(
            LifecycleTransition.SUSPENDED,
            LifecycleTransition.RESUMED,
        )
    }

    @Test
    fun `a throwing listener breaks neither the transition, the host, nor the other listeners`() {
        val f = Fixture(cellCount = 2)
        f.host.onLifecycle { _, _ -> throw IllegalStateException("listener blew up") }
        val survivor = Recorder().also { it.attach(f.host) }
        // an Error, not an Exception: a listener's TODO() must not abort a drain
        f.host.onLifecycle { _, _ -> throw NotImplementedError("listener not built yet") }

        f.manage.suspend(f.cells[0].ref)
        f.controller.runToIdle()
        f.host.isSuspended(f.cells[0].ref) shouldBe true

        f.manage.drainHost()
        f.controller.runToIdle()
        f.host.isDrained shouldBe true

        f.manage.resumeHost()
        f.controller.runToIdle()
        f.host.isDrained shouldBe false

        // every transition still happened, is still observable through the
        // predicates, and still reached the listener registered between the two
        // throwing ones
        survivor.transitions shouldContainExactly listOf(
            LifecycleTransition.SUSPENDED,
            LifecycleTransition.DRAINED,
            LifecycleTransition.DRAINED,
            LifecycleTransition.HOST_RESUMED,
            LifecycleTransition.HOST_RESUMED,
        )

        // the host still works afterwards
        f.manage.resume(f.cells[0].ref)
        f.controller.runToIdle()
        f.host.isSuspended(f.cells[0].ref) shouldBe false
    }

    // ------------------------------------------------- notification discipline

    /**
     * The [LocationRegistry] hook contract, applied to the host: synchronous, on
     * the thread that performs the mutation, after the state change is visible.
     *
     * Under the deterministic controller the mutating thread is the stepping
     * thread, so "synchronous" is checkable exactly: nothing is reported before
     * the step that runs the management call, and the notification observes the
     * predicate already flipped. This fails if a notification is ever handed off
     * to another queue or thread, or is moved ahead of its state write.
     */
    @Test
    fun `a notification runs on the mutating thread, after the state change is visible`() {
        val f = Fixture()
        val threads = mutableListOf<Thread>()
        val suspendedAtNotification = mutableListOf<Boolean>()
        val drainedAtNotification = mutableListOf<Boolean>()
        f.host.onLifecycle { ref, transition ->
            threads += Thread.currentThread()
            when (transition) {
                LifecycleTransition.SUSPENDED, LifecycleTransition.RESUMED ->
                    suspendedAtNotification += f.host.isSuspended(ref)
                LifecycleTransition.DRAINED, LifecycleTransition.HOST_RESUMED ->
                    drainedAtNotification += f.host.isDrained
            }
        }

        f.manage.suspend(f.cell.ref)
        threads.shouldBeEmpty() // the management call is enqueued, not executed inline
        f.controller.runToIdle()
        f.manage.resume(f.cell.ref)
        f.controller.runToIdle()
        f.manage.drainHost()
        f.controller.runToIdle()
        f.manage.resumeHost()
        f.controller.runToIdle()

        threads shouldBe List(4) { Thread.currentThread() }
        suspendedAtNotification shouldContainExactly listOf(true, false)
        drainedAtNotification shouldContainExactly listOf(true, false)
    }

    /**
     * Placement of the RESUMED notification: before the parked replay, not after.
     *
     * The state change is complete the moment the cell leaves `suspendedCells`;
     * replaying its parked traffic is a *consequence*, and it can throw —
     * `enqueueHostedInvocation` refuses a closed intake — so notifying after it
     * would drop a transition that definitively happened. Resuming a cell on a
     * drained host is exactly that case, and the inspector's waker documents it
     * as a real one.
     */
    @Test
    fun `a resume whose replay is refused still reports the transition`() {
        val f = Fixture()
        f.manage.suspend(f.cell.ref)
        f.api().provide(1) // parks behind the suspension
        f.manage.drainHost()
        f.controller.runToIdle()
        val recorder = Recorder().also { it.attach(f.host) }

        f.manage.resume(f.cell.ref)
        f.controller.runToIdle()

        recorder.transitions shouldContainExactly listOf(LifecycleTransition.RESUMED)
        f.host.isSuspended(f.cell.ref) shouldBe false
        // the replay itself was refused by the closed intake and dead-lettered —
        // pre-existing behavior, and precisely why the notification precedes it
        f.letters.size shouldBe 1
    }

    // ------------------------------------------------------------------ fixtures

    /** A minimal in-process [Link] onto one port — PortProtocolDispatchTest's shape. */
    private fun selfLink(cell: FragileCounterCell): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from = cell.inlet.ref
        override val to = cell.inlet.ref
        override fun unlink() = Unit
    }
}
