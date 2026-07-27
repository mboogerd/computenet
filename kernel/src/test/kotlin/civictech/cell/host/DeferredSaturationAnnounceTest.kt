package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.CounterDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * T04 finding 1 (ABBA deadlock, `742f7ca`): `IntakeControl.checkSaturationOnAccept`/
 * `lowWaterCheck` used to run [Protocols.sendUpstream]'s handler + hop-by-hop
 * relay traversal WHILE `ManagedHost.dataLock` was held. Since a reached
 * handler can legitimately re-enter the same host (management band) or,
 * in the traced production path, another cross-linked host's own
 * `enqueueHostedInvocation`/`dataLock`, running that traversal under the
 * lock is exactly what produces the ABBA deadlock (spurious 5s
 * `TimeoutException`s, not a visible deadlock).
 *
 * `checkSaturationOnAccept` now returns a **deferred action**, invoked only
 * after `synchronized(dataLock)` releases — this pins that direct, testable
 * contract with a single host and a real [VirtualThreadScheduler]. The
 * harder, genuinely adversarial two-host reproduction (mirror-order,
 * cross-linked saturation) belongs to T06's conformance suite.
 */
class DeferredSaturationAnnounceTest {

    private class UpstreamStub(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())
    }

    private class Sink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {}
            })
        }
    }

    interface SinkProxy {
        val inlet: Use<Propagate<CounterDelta>>
    }

    private fun deltaInvocation(cell: CellRef, value: Long, context: MessageContext) =
        HostedPortInvocation(
            cell, "inlet", HostedPortInvocation.Type.PORT_API,
            Invocation("propagate", listOf("java.lang.Object"), listOf(CounterDelta(value)), context),
        )

    @Test
    fun `saturation announce fires only after dataLock releases, so a reentrant same-host call does not deadlock`() {
        val host = ManagedHost(
            scheduler = VirtualThreadScheduler("t04-deadlock-fix"),
            intakeBound = IntakeBound(highWater = 1, lowWater = 0, policy = SaturationPolicy.Park),
        )
        val upstream = UpstreamStub()
        val sink = Sink()
        host.managementInlet.call.spawn(upstream)
        host.managementInlet.call.spawn(sink)
        // real link so announceSaturation's upstream traversal has an edge to
        // reach (it walks the SATURATED inlet's own `linking.links`)
        upstream.outlet.linkTo(sink.inlet as LinkFrom<Propagate<CounterDelta>>)

        // white-box: the exact assertion the ticket names ("no dataLock is
        // held during protocol delivery") is most directly checked by asking
        // the JVM monitor itself, rather than inferring it from timing.
        val dataLockField = ManagedHost::class.java.getDeclaredField("dataLock").apply { isAccessible = true }
        val dataLock = requireNotNull(dataLockField.get(host))

        val heldDuringAnnounce = CompletableFuture<Boolean>()
        val reentrantCompleted = CompletableFuture<Boolean>()
        ProtocolSupport.of(upstream.outlet).handle(Protocols.Saturation) { _, _ ->
            heldDuringAnnounce.complete(Thread.holdsLock(dataLock))
            // finding 1's exact symptom: pre-fix, a handler reaching back
            // into the host's management band from inside this callback,
            // while dataLock is held, risks the ABBA deadlock the ticket
            // traces (two cross-linked hosts in mirror order). A single-host
            // reentrant `enqueueAwaiting`-based call is the ticket's own
            // suggested minimal witness: it always completes promptly
            // post-fix since nothing here holds a lock the scheduler thread
            // needs.
            host.managementInlet.call.lookup(sink.ref, SinkProxy::class.java)
            reentrantCompleted.complete(true)
        }

        val source = PortRef.generate()
        val context = MessageContext(Timestamp(source.id, 1), source)
        // highWater = 1: the very first accepted message saturates —
        // deterministic, no gating/timing needed.
        host.enqueueHostedInvocation(deltaInvocation(sink.ref, 1, context))

        heldDuringAnnounce.get(5, TimeUnit.SECONDS) shouldBe false
        reentrantCompleted.get(5, TimeUnit.SECONDS) shouldBe true
    }
}
