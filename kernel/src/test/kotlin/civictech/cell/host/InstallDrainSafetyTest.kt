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

    /**
     * Review addendum to T05 finding 1. `install()` keeps the pre-fix
     * drain-before-publish ordering (correctly — publishing first breaks
     * per-ref FIFO, `RelocationTest`'s concurrent-relocation stress test
     * catches that directly). But that ordering leaves a lost wake-up
     * `drainWhile` alone does not close:
     *
     * `send`'s SATURATED branch registers `host.onIntakeAvailable { replay }`,
     * and `onIntakeAvailable` runs the listener **synchronously** when the
     * host is no longer SATURATED by the time the hook is registered — the
     * host crossed low-water in the window between the throw and the
     * registration. That immediate `replay` re-enters `install`'s monitor and
     * bails on its `locations[ref] == expected` guard, because `locations`
     * has not been assigned yet. Result: the retained remainder strands with
     * no hook at all, until some later `deliver`/`publish` happens to
     * re-drive it. `deliver` already guards the identical window by
     * re-registering after it parks; `install` did not.
     *
     * This host models exactly that window: it refuses the first offer with
     * `IntakeSaturatedException`, then accepts — while its real intake state
     * stays OPEN, so `onIntakeAvailable` always takes the synchronous
     * `runNow` path. Pre-fix nothing is ever delivered; post-fix the whole
     * batch drains, in order, before `publish` returns.
     */
    @Test
    fun `install() re-registers the intake hook after publishing, so a runNow wake-up during the drain is not lost`() {
        class RefuseOnceHost(scheduler: HostScheduler) : ManagedHost(scheduler = scheduler) {
            private val refusalsLeft = java.util.concurrent.atomic.AtomicInteger(1)
            val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

            override fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
                // one refusal, then open — the low-water crossing that lands
                // in `send`'s throw -> onIntakeAvailable registration window.
                if (refusalsLeft.getAndDecrement() > 0) throw IntakeSaturatedException(ref)
                @Suppress("UNCHECKED_CAST")
                recorded += (hostedInvocation.invocation.args[0] as Owned<String>).take()
            }
        }

        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = RefuseOnceHost(controller.scheduler())

        val targetRef = CellRef(UUID.randomUUID())
        val api = registryApi(registry, targetRef)
        api.accept(Owned("first"))
        api.accept(Owned("second"))
        api.accept(Owned("third"))
        registry.parkedFor(targetRef).size shouldBe 3

        registry.publish(targetRef, host)

        // pre-fix: the first send is refused, the synchronous replay bails on
        // the not-yet-assigned location, and all three strand unparked-but-
        // undelivered with no hook left to wake them.
        host.recorded shouldBe listOf("first", "second", "third")
        registry.parkedFor(targetRef).shouldBeEmpty()
    }
}
