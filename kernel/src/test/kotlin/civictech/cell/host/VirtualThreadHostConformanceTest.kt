package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.testkit.awaitUntil
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * T06 §A: the real [VirtualThreadScheduler] is effectively untested — every
 * existing test drives the deterministic [SimulationController]. This suite
 * is the missing complement: genuine multiple OS threads hammering one host
 * (and, for §A3, two cross-linked hosts), pinning exactly the properties
 * T04's findings 1 and 3 fixed.
 */
class VirtualThreadHostConformanceTest {

    interface RecorderApi {
        fun record(thread: Int, seq: Int)
    }

    private class RecorderCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

        val inlet = registerPort("inlet", FanInlet.create<RecorderApi>())

        init {
            inlet.serve(object : RecorderApi {
                override fun record(thread: Int, seq: Int) {
                    received += thread to seq
                }
            })
        }
    }

    private fun recordInvocation(cellRef: CellRef, thread: Int, seq: Int) = HostedPortInvocation(
        cellRef, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("record", listOf("int", "int"), listOf(thread, seq)),
    )

    @Test
    fun `A1 - per-cell FIFO holds for each sender thread under real concurrency`() {
        val host = ManagedHost(scheduler = VirtualThreadScheduler("t06-a1"))
        val cell = RecorderCell()
        host.managementInlet.call.spawn(cell)

        val threads = 8
        val perThread = 200
        val workers = (0 until threads).map { t ->
            Thread {
                for (seq in 0 until perThread) {
                    host.enqueueHostedInvocation(recordInvocation(cell.ref, t, seq))
                }
            }.apply { name = "t06-a1-sender-$t" }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }
        workers.forEach { it.isAlive.shouldBeFalse() }

        awaitUntil("every sent invocation delivered", timeoutMs = 30_000) {
            cell.received.size == threads * perThread
        }

        val byThread = cell.received.toList().groupBy({ it.first }, { it.second })
        byThread.keys shouldBe (0 until threads).toSet()
        byThread.forEach { (thread, seqs) ->
            seqs shouldBe (0 until perThread).toList()
        }
    }

    private class UpstreamStub(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())
    }

    private class SinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = java.util.concurrent.atomic.AtomicInteger()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) { received.incrementAndGet() }
            })
        }
    }

    private fun deltaInvocation(cellRef: CellRef) = HostedPortInvocation(
        cellRef, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("propagate", listOf("java.lang.Object"), listOf(CounterDelta(1))),
    )

    @Test
    fun `A2 - no lost messages and the saturation announce survives real multi-threaded saturation`() {
        val host = ManagedHost(
            scheduler = VirtualThreadScheduler("t06-a2"),
            intakeBound = IntakeBound(highWater = 5, lowWater = 2, policy = SaturationPolicy.Park),
        )
        val registry = LocationRegistry()
        val upstream = UpstreamStub()
        val sink = SinkCell()
        host.managementInlet.call.spawn(upstream)
        host.managementInlet.call.spawn(sink)
        upstream.outlet.linkTo(sink.inlet as LinkFrom<Propagate<CounterDelta>>)
        registry.publish(sink.ref, host)

        val announces = Collections.synchronizedList(mutableListOf<Boolean>())
        ProtocolSupport.of(upstream.outlet).handle(Protocols.Saturation) { _, message ->
            announces += (message as SaturationSignal).asserted
        }

        val threads = 8
        val perThread = 150
        val workers = (0 until threads).map {
            Thread {
                // LocationRegistry.deliver never blocks, never drops (its own
                // contract) — a SATURATED intake parks and replays via the
                // ordinary onIntakeAvailable hook, never an escaping exception.
                repeat(perThread) { registry.deliver(deltaInvocation(sink.ref)) }
            }.apply { name = "t06-a2-sender" }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }
        workers.forEach { it.isAlive.shouldBeFalse() }

        awaitUntil("every sent invocation delivered, none lost", timeoutMs = 30_000) {
            sink.received.get() == threads * perThread
        }
        awaitUntil("the saturation announce fired at least once (T04 finding 1/3)", timeoutMs = 10_000) {
            announces.contains(true)
        }
        awaitUntil("saturation retracted once quiescent", timeoutMs = 10_000) {
            announces.isNotEmpty() && announces.last() == false
        }
    }

    private fun probe(cellRef: CellRef) = HostedPortInvocation(
        cellRef, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("propagate", listOf("java.lang.Object"), listOf(CounterDelta(1))),
    )

    /**
     * T06 §A3 — the ABBA deadlock T04 finding 1 fixed, reproduced directly:
     * two hosts, each saturating on the other's announce handler, forced
     * into the "both hold their own dataLock, both about to call into the
     * other host" state via a [CyclicBarrier] so the race is deterministic
     * rather than a lucky timing window. Pre-T04 (announce ran inside
     * `synchronized(dataLock)`), this reliably deadlocks: verified locally
     * by reverting `IntakeControl`'s deferred-announce fix, confirming this
     * test times out, then restoring the fix (not committed — see the T06
     * report for the exact revert/restore steps taken).
     */
    @Test
    fun `A3 - cross-linked hosts under concurrent saturation reach quiescence without an ABBA deadlock`() {
        val hostA = ManagedHost(
            scheduler = VirtualThreadScheduler("t06-a3-A"),
            intakeBound = IntakeBound(highWater = 1, lowWater = 0, policy = SaturationPolicy.Park),
        )
        val hostB = ManagedHost(
            scheduler = VirtualThreadScheduler("t06-a3-B"),
            intakeBound = IntakeBound(highWater = 1, lowWater = 0, policy = SaturationPolicy.Park),
        )

        val upstreamA = UpstreamStub()
        val sinkA = SinkCell()
        val upstreamB = UpstreamStub()
        val sinkB = SinkCell()
        hostA.managementInlet.call.spawn(upstreamA)
        hostA.managementInlet.call.spawn(sinkA)
        hostB.managementInlet.call.spawn(upstreamB)
        hostB.managementInlet.call.spawn(sinkB)
        upstreamA.outlet.linkTo(sinkA.inlet as LinkFrom<Propagate<CounterDelta>>)
        upstreamB.outlet.linkTo(sinkB.inlet as LinkFrom<Propagate<CounterDelta>>)

        // both handlers must be "in flight" (holding their own host's
        // dataLock, pre-fix) at the same instant for the deadlock to be
        // deterministic rather than a race that might not align.
        val barrier = CyclicBarrier(2)
        ProtocolSupport.of(upstreamA.outlet).handle(Protocols.Saturation) { _, _ ->
            barrier.await(10, TimeUnit.SECONDS)
            runCatching { hostB.enqueueHostedInvocation(probe(sinkB.ref)) }
        }
        ProtocolSupport.of(upstreamB.outlet).handle(Protocols.Saturation) { _, _ ->
            barrier.await(10, TimeUnit.SECONDS)
            runCatching { hostA.enqueueHostedInvocation(probe(sinkA.ref)) }
        }

        val t1 = Thread { hostA.enqueueHostedInvocation(probe(sinkA.ref)) }.apply { name = "t06-a3-A-sender" }
        val t2 = Thread { hostB.enqueueHostedInvocation(probe(sinkB.ref)) }.apply { name = "t06-a3-B-sender" }
        t1.start()
        t2.start()
        t1.join(15_000)
        t2.join(15_000)

        // pre-T04: both threads hang forever in the ABBA cycle, still alive
        // after the join timeout — that IS the failure this test detects.
        t1.isAlive.shouldBeFalse()
        t2.isAlive.shouldBeFalse()
    }
}
