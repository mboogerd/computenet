package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins the repartition flip-window hold behaviour of [LocationRegistry] —
 * hold/isHeld/release (currently L329/L339/L342), consulted by [LocationRegistry.deliver]
 * and [LocationRegistry.replay] — against the registry/host public surface only, so
 * these tests must pass identically whether `held` lives directly on
 * [LocationRegistry] or is extracted into `DeliveryHold` behind the same
 * delegates (computenet-iyi.1.1, running concurrently). Governing spec:
 * doc/spec/20-dataflow-semantics/24-*.md Partitioned state "park the flip
 * window" ([24-PART-04]), 93 I-19 funnel rule, CP-D4.
 *
 * Only one direct test existed before this file
 * ([BoundedReadProvenanceTest]'s "a ref held for a migration flip is never
 * answered from the stale local object", the MIGRATING read at
 * [BoundedReadProvenanceTest] L120-133). The park/drain ordering, the funnel
 * rule, the intake-wake-up interaction, the install()-ignores-held asymmetry,
 * and registry-less null-safety are pinned here.
 */
class RepartitionHoldTest {

    interface CollectorProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** Mirrors [RelocationTest]'s fixture: a plain inlet that records what it receives, in order. */
    class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    private fun inv(ref: CellRef, n: Int) = HostedPortInvocation(
        ref, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("provide", listOf("java.lang.Object"), listOf(n)),
    )

    private fun parkedArgs(registry: LocationRegistry, ref: CellRef): List<Int> =
        registry.parkedFor(ref).map { it.invocation.args.single() as Int }

    // -------------------------------------------------------------- BS-7

    @Test
    fun `BS-7 hold parks, release drains in order`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = CollectorCell()
        host.managementInlet.call.spawn(cell)

        registry.hold(cell.ref)
        registry.deliver(inv(cell.ref, 1))
        registry.deliver(inv(cell.ref, 2))
        registry.deliver(inv(cell.ref, 3))
        controller.runToIdle()

        // nothing handed to the host while held
        cell.received.shouldBeEmpty()
        parkedArgs(registry, cell.ref) shouldBe listOf(1, 2, 3)

        registry.release(cell.ref)
        controller.runToIdle()

        // drained exactly once, in park order
        cell.received shouldBe listOf(1, 2, 3)
        registry.parkedFor(cell.ref).shouldBeEmpty()
    }

    // -------------------------------------------------------------- BS-8

    @Test
    fun `BS-8 funnel rule - holding one ref does not block delivery to another`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val held = CollectorCell()
        val free = CollectorCell()
        host.managementInlet.call.spawn(held)
        host.managementInlet.call.spawn(free)

        registry.hold(held.ref)
        registry.deliver(inv(held.ref, 1))
        registry.deliver(inv(free.ref, 2))
        controller.runToIdle()

        // the unheld ref's invocation delivers immediately...
        free.received shouldBe listOf(2)
        registry.parkedFor(free.ref).shouldBeEmpty()
        // ...while the held ref's stays parked
        held.received.shouldBeEmpty()
        parkedArgs(registry, held.ref) shouldBe listOf(1)
    }

    // -------------------------------------------------------------- BS-9

    @Test
    fun `BS-9 held ref does not drain on intake wake-up`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(), registry = registry,
            intakeBound = IntakeBound(1, 0, SaturationPolicy.Park),
        )
        val other = CollectorCell()
        val held = CollectorCell()
        host.managementInlet.call.spawn(other)
        host.managementInlet.call.spawn(held)

        // saturate the host's intake with unrelated traffic: the first
        // invocation fills the queue to highWater, the second is refused and
        // parks, registering a low-water wake-up
        registry.deliver(inv(other.ref, 1))
        registry.deliver(inv(other.ref, 2))
        host.currentIntakeState shouldBe IntakeState.SATURATED

        // hold and deliver while the host is still saturated, so the ref's
        // own onIntakeAvailable registration queues as a low-water listener
        // rather than firing immediately
        registry.hold(held.ref)
        registry.deliver(inv(held.ref, 100))
        parkedArgs(registry, held.ref) shouldBe listOf(100)

        // crossing low water (draining "other"'s queued head) fires every
        // registered wake-up, including the held ref's replay hook
        controller.runToIdle()

        other.received shouldBe listOf(1, 2)
        // nothing drained for the held ref despite the wake-up
        held.received.shouldBeEmpty()
        parkedArgs(registry, held.ref) shouldBe listOf(100)

        registry.release(held.ref)
        controller.runToIdle()

        held.received shouldBe listOf(100)
        registry.parkedFor(held.ref).shouldBeEmpty()
    }

    // -------------------------------------------------------------- BS-10

    /**
     * Pins the current install()-ignores-held asymmetry deliberately: unlike
     * [LocationRegistry.deliver]/[LocationRegistry.replay], [LocationRegistry.install]
     * (reached here via a re-[civictech.cell.host.Host.spawn] onto a new host, spec 33
     * step 7) drains a ref's parked queue into the newly installed location
     * without consulting `held` at all. This is a documented DeliveryHold
     * boundary, filed as OQ-3 on epic computenet-iyi — NOT a bug to fix here.
     */
    @Test
    fun `BS-10 install drains despite an active hold (pinned, see OQ-3)`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val cell = CollectorCell()
        hostA.managementInlet.call.spawn(cell)

        registry.hold(cell.ref)
        registry.deliver(inv(cell.ref, 1))
        registry.deliver(inv(cell.ref, 2))
        parkedArgs(registry, cell.ref) shouldBe listOf(1, 2)

        // re-publish onto hostB while the hold is still active
        hostB.managementInlet.call.spawn(cell)
        controller.runToIdle()

        // the parked batch drained into the new location despite the hold
        cell.received shouldBe listOf(1, 2)
        registry.parkedFor(cell.ref).shouldBeEmpty()
        // the hold itself is untouched by install() — only deliver/replay respect it
        registry.isHeld(cell.ref).shouldBeTrue()
    }

    // -------------------------------------------------------------- BS-16

    @Test
    fun `BS-16 registry-less host stays null-safe on hold-read paths`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = null)
        val cell = SetCell<String>()
        cell.inlet.call.add("k0")
        cell.inlet.call.add("k1")

        // spawn: no registry to consult isHeld against, no NPE
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        // bounded read: ManagedHost's `registry?.isHeld(ref) == true` guard is
        // null-safe, so a registry-less host answers normally rather than
        // MIGRATING
        val pending = host.readState(cell.ref, StateRead())
        controller.runToIdle()
        val result = pending.get()
        result.shouldBeInstanceOf<StateReadResult.Page>()

        // despawn: likewise no NPE
        host.managementInlet.call.despawn(cell.ref)
        controller.runToIdle()

        val afterDespawn = host.readState(cell.ref, StateRead())
        controller.runToIdle()
        afterDespawn.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.NOT_HOSTED)
    }
}
