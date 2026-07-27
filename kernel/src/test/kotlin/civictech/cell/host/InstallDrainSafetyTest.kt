package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Owned
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T05 finding 1 (critical): `LocationRegistry.install()`'s `queue.drain()`
 * used to snapshot-and-clear the whole park batch before anything was sent;
 * the first refusal (a freshly published, already-SATURATED host) tripped
 * `check(send(...))`, throwing — and the already-cleared batch (refused head
 * + every ordered successor, `Owned`/`Leased` included) was simply gone: not
 * re-parked, not dead-lettered, not counted, and `locations[ref]` never got
 * assigned so the ref stayed unpublished with its history destroyed.
 *
 * This pins the fix end to end: publish into an already-SATURATED host, and
 * confirm the whole batch (including a live `Owned` payload) survives,
 * un-reordered, un-dropped, and eventually fully delivered once the host's
 * intake reopens.
 */
class InstallDrainSafetyTest {

    interface OwnedSinkApi {
        fun accept(value: Owned<String>)
    }

    interface OwnedSinkProxy {
        val inlet: Use<OwnedSinkApi>
    }

    private class OwnedSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(OwnedSinkApi::class.java))

        init {
            inlet.serve(object : OwnedSinkApi {
                override fun accept(value: Owned<String>) {
                    received += value.take()
                }
            })
        }
    }

    private fun ownedInvocation(cellRef: CellRef, value: Owned<String>) = HostedPortInvocation(
        cellRef, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("accept", listOf(Owned::class.java.name), listOf(value)),
    )

    private fun registryApi(registry: LocationRegistry, cellRef: CellRef): OwnedSinkApi =
        (HostedCellProxy.create(cellRef, registry, OwnedSinkProxy::class.java) as OwnedSinkProxy).inlet.call

    @Test
    fun `install() never destroys a parked batch on a refused replay — order and Owned payloads preserved, full delivery once intake reopens`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(), registry = registry,
            intakeBound = IntakeBound(highWater = 1, lowWater = 0, policy = SaturationPolicy.Park),
        )

        // saturate the host's (host-wide) intake BEFORE `target` is ever
        // published, so `install()`'s very first send() is refused.
        val saturator = OwnedSink()
        host.managementInlet.call.spawn(saturator)
        host.enqueueHostedInvocation(ownedInvocation(saturator.ref, Owned("saturating")))
        host.currentIntakeState shouldBe IntakeState.SATURATED

        val target = OwnedSink()
        val api = registryApi(registry, target.ref) // target unpublished: parks at the registry
        api.accept(Owned("first"))
        api.accept(Owned("second"))
        api.accept(Owned("third"))
        registry.parkedFor(target.ref).size shouldBe 3

        // publish while still SATURATED: install()'s drainWhile is refused
        // immediately and must retain the WHOLE batch, in order — not throw,
        // not destroy it, not deliver any of it early.
        host.managementInlet.call.spawn(target)
        registry.publish(target.ref, host)

        registry.parkedFor(target.ref).size shouldBe 3
        target.received.shouldBeEmpty()

        // low-water reopening (possibly across several cycles under Park
        // policy) drains the rest, in order, once the host can accept again.
        controller.runToIdle()

        target.received shouldBe listOf("first", "second", "third")
        registry.parkedFor(target.ref).shouldBeEmpty()
    }
}
