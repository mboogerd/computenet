package civictech.cell.proxy

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * `registry.inlet<D>(ref, "port")` is the reified front door to the routed
 * write-handle `HostedCellProxy.create(...).port.call` builds — same host queue,
 * same staged delivery, without a per-port proxy interface or its unchecked
 * `as` cast (05, `agora-routed-inlet-handle-without-proxy-interface`). These
 * tests pin the round-trip, byte-for-byte equivalence with the proxy path, the
 * eager typed rejections, and that delivery stays staged.
 */
class RoutedInletTest {

    /** The shape agora hand-declares per port — used only to drive the proxy path for the equivalence test. */
    interface SinkProxy {
        val inlet: Use<Propagate<Int>>
    }

    class SinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()

        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())

        /** A non-Propagate inlet, to prove the wrapper-shape guard rejects a mis-targeted port. */
        val consumerInlet = registerPort("consumerInlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Propagate<Int> {
                override fun propagate(value: Int) {
                    received += value
                }
            })
        }
    }

    @Test
    fun `routed handle delivers to the target inlet through the host queue`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = SinkCell()
        host.managementInlet.call.spawn(cell)

        val handle: Propagate<Int> = registry.inlet(cell.ref, "inlet")
        handle.propagate(1)
        handle.propagate(2)
        handle.propagate(3)
        controller.runToIdle()

        cell.received shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `delivery is staged, not a fused synchronous call`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = SinkCell()
        host.managementInlet.call.spawn(cell)

        val handle: Propagate<Int> = registry.inlet(cell.ref, "inlet")
        handle.propagate(42)

        // enqueued on the host queue — the cell has not run yet (every hop staged
        // for attention/magnitude scheduling, not fused inline)
        cell.received.shouldBeEmpty()
        controller.runToIdle()
        cell.received shouldBe listOf(42)
    }

    @Test
    fun `resolved handle is reused across sends`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = SinkCell()
        host.managementInlet.call.spawn(cell)

        // resolve once; every send reuses this one handle (no per-send resolution)
        val handle: Propagate<Int> = registry.inlet(cell.ref, "inlet")
        repeat(1_000) { handle.propagate(it) }
        controller.runToIdle()

        cell.received shouldBe (0 until 1_000).toList()
    }

    @Test
    fun `the invocation matches the proxy path byte-for-byte`() {
        // capture host: intercept the data path so both handles' invocations can be compared
        val queue = LinkedBlockingQueue<HostedPortInvocation>()
        val host = object : ManagedHost() {
            override fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
                queue.put(hostedInvocation)
            }
        }
        val cell = SinkCell()
        host.managementInlet.call.spawn(cell)

        // proxy path: interface + reflective cast
        val viaProxy = (HostedCellProxy.create(cell.ref, host, SinkProxy::class.java) as SinkProxy).inlet.call
        viaProxy.propagate(7)
        val proxyMsg = queue.poll(1, TimeUnit.SECONDS)!!

        // helper path: no interface
        val viaHelper: Propagate<Int> = host.inlet(cell.ref, "inlet")
        viaHelper.propagate(7)
        val helperMsg = queue.poll(1, TimeUnit.SECONDS)!!

        helperMsg.cellRef shouldBe proxyMsg.cellRef
        helperMsg.portName shouldBe proxyMsg.portName
        helperMsg.type shouldBe proxyMsg.type
        helperMsg.invocation.methodName shouldBe proxyMsg.invocation.methodName
        helperMsg.invocation.parameterTypes shouldBe proxyMsg.invocation.parameterTypes
        helperMsg.invocation.args shouldBe proxyMsg.invocation.args
        // the stable wire ids come from the same @Contract Propagate.propagate
        helperMsg.invocation.contractId shouldBe proxyMsg.invocation.contractId
        helperMsg.invocation.methodId shouldBe proxyMsg.invocation.methodId
    }

    @Test
    fun `unknown cell is rejected, naming the cell and port`() {
        val registry = LocationRegistry()
        val ghost = CellRef(UUID.randomUUID())

        val error = assertThrows<IllegalArgumentException> {
            registry.inlet<Int>(ghost, "inlet")
        }
        error.message!! shouldContain ghost.toString()
        error.message!! shouldContain "inlet"
    }

    @Test
    fun `unknown port is rejected, naming the port and the available ports`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = SinkCell()
        host.managementInlet.call.spawn(cell)

        val error = assertThrows<IllegalArgumentException> {
            registry.inlet<Int>(cell.ref, "stanceInlet")
        }
        error.message!! shouldContain "stanceInlet"
        error.message!! shouldContain cell.ref.toString()
        error.message!! shouldContain "inlet" // the available-ports listing
    }

    @Test
    fun `a non-Propagate inlet is rejected, naming the wrong api`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = SinkCell()
        host.managementInlet.call.spawn(cell)

        val error = assertThrows<IllegalStateException> {
            registry.inlet<Int>(cell.ref, "consumerInlet")
        }
        error.message!! shouldContain "consumerInlet"
        error.message!! shouldContain cell.ref.toString()
        error.message!! shouldContain "Consumer"
        error.message!! shouldContain "Propagate"
    }

    @Test
    fun `the registry handle survives relocation, routing through re-resolution`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = SinkCell()
        hostA.managementInlet.call.spawn(cell)

        // resolve against hostA, then relocate the cell to hostB
        val handle: Propagate<Int> = registry.inlet(cell.ref, "inlet")
        handle.propagate(1)
        controller.runToIdle()

        hostA.closeIntake()
        handle.propagate(2) // parks: the registry sink re-resolves on re-publication (spec 33)
        hostB.managementInlet.call.spawn(cell) // spawning on the new host publishes + replays parked traffic
        controller.runToIdle()

        cell.received shouldBe listOf(1, 2)
    }
}
