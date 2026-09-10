package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * f7h.5-D1 / f7h.5-D2 — the registry half of the parked-write release
 * (feature computenet-f7h.5, [MEM1-16]). Two independent primitives, both
 * pinned here because task 3 (the engine-side wiring that calls them) is a
 * separate item and neither primitive has a caller yet.
 *
 * [LocationRegistry.unpark] drains one ref's parked queue in park order and
 * leaves it empty — the "a later republish of the old ref replays nothing"
 * half of [MEM1-16], proven at the registry rather than at the engine.
 *
 * [LocationRegistry.noteForwardedPort] / [LocationRegistry.forwardedPorts]
 * record which port names are command-forwarded single-writer writes, keyed
 * by logical id so the record survives the leader instance changing.
 */
class LocationRegistryUnparkTest {

    private fun ref(id: UUID = UUID.randomUUID()) = CellRef(id)

    private fun invocation(cellRef: CellRef, portName: String = "inlet", value: String = "v") =
        HostedPortInvocation(
            cellRef,
            portName,
            HostedPortInvocation.Type.PORT_API,
            Invocation("provide", listOf("java.lang.Object"), listOf(value)),
        )

    // -------------------------------------------------------------- unpark

    @Test
    fun `unpark drains parked invocations in park order and leaves parkedFor empty`() {
        val registry = LocationRegistry()
        val target = ref()
        val first = invocation(target, value = "1")
        val second = invocation(target, value = "2")
        val third = invocation(target, value = "3")

        registry.deliver(first)
        registry.deliver(second)
        registry.deliver(third)
        registry.parkedFor(target).size shouldBe 3

        val drained = registry.unpark(target)

        drained shouldContainExactly listOf(first, second, third)
        registry.parkedFor(target).shouldBeEmpty()
    }

    @Test
    fun `a second unpark on an already-drained ref returns empty`() {
        val registry = LocationRegistry()
        val target = ref()
        registry.deliver(invocation(target))
        registry.unpark(target)

        registry.unpark(target).shouldBeEmpty()
    }

    @Test
    fun `a republish after unpark replays nothing — the queue stays empty`() {
        val registry = LocationRegistry()
        val target = ref()
        registry.deliver(invocation(target))
        registry.parkedFor(target).size shouldBe 1

        registry.unpark(target)

        val host = ManagedHost(registry = registry)
        registry.publish(target, host)

        registry.parkedFor(target).shouldBeEmpty()
    }

    @Test
    fun `unpark of a published ref with nothing parked returns empty and leaves location and instances unchanged`() {
        val registry = LocationRegistry()
        val target = ref()
        val host = ManagedHost(registry = registry)
        registry.publish(target, host)

        val locationBefore = registry.location(target)
        val replicasBefore = registry.replicasOf(target.id)

        registry.unpark(target).shouldBeEmpty()

        registry.location(target) shouldBe locationBefore
        registry.replicasOf(target.id) shouldBe replicasBefore
    }

    // ---------------------------------------------------- forwarded ports

    @Test
    fun `forwardedPorts of an unknown logical id is empty`() {
        val registry = LocationRegistry()

        registry.forwardedPorts(UUID.randomUUID()).shouldBeEmpty()
    }

    @Test
    fun `noting the same port twice for one id yields a one-element set`() {
        val registry = LocationRegistry()
        val id = UUID.randomUUID()

        registry.noteForwardedPort(id, "writeInlet")
        registry.noteForwardedPort(id, "writeInlet")

        registry.forwardedPorts(id) shouldBe setOf("writeInlet")
    }

    @Test
    fun `two logical ids keep separate forwarded-port sets`() {
        val registry = LocationRegistry()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        registry.noteForwardedPort(first, "writeInlet")
        registry.noteForwardedPort(second, "otherInlet")

        registry.forwardedPorts(first) shouldBe setOf("writeInlet")
        registry.forwardedPorts(second) shouldBe setOf("otherInlet")
    }
}
