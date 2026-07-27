package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.gen.wire.Contract
import civictech.nature.ContractRegistry
import civictech.gen.wire.Key
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.*

@Contract
interface OwnedPush {
    fun push(@Key buffer: Owned<String>)
}

@Contract
interface FrozenPush {
    fun push(buffer: Frozen<String>)
}

@Contract
interface LeasedPush {
    fun push(@Key buffer: Leased<String>)
}

/**
 * M5.6 (G-21 phases 1+2, spec 23): the KSP-emitted exclusive bit makes
 * `Owned`/`Leased` fan-out rejectable everywhere — local links and bridged
 * links — with no runtime reflection in the check.
 */
class OwnershipTest {

    private fun <T : Any> fixed(api: T): Use<T> = Use.fixed(api, PortRef.generate())

    @Test
    fun `the generated metadata carries the ownership bit`() {
        ContractRegistry.descriptor(OwnedPush::class.java)!!.methods.single().exclusive shouldBe true
        ContractRegistry.descriptor(OwnedPush::class.java)!!.methods.single().keyIndex shouldBe 0
        ContractRegistry.descriptor(LeasedPush::class.java)!!.methods.single().exclusive shouldBe true
        ContractRegistry.descriptor(FrozenPush::class.java)!!.methods.single().exclusive shouldBe false
    }

    @Test
    fun `a second subscriber on an Owned-carrying outlet is refused, Frozen fans out`() {
        val owned = FanOutlet.create<OwnedPush>()
        owned.subscribe(fixed(object : OwnedPush {
            override fun push(buffer: Owned<String>) {}
        }))
        shouldThrow<IllegalStateException> {
            owned.subscribe(fixed(object : OwnedPush {
                override fun push(buffer: Owned<String>) {}
            }))
        }

        val frozen = FanOutlet.create<FrozenPush>()
        val seen = mutableListOf<String>()
        repeat(2) {
            frozen.subscribe(fixed(object : FrozenPush {
                override fun push(buffer: Frozen<String>) {
                    seen += buffer.value
                }
            }))
        }
        frozen.call.push(Frozen("x"))
        seen shouldBe listOf("x", "x")
    }

    @Test
    fun `the handshake path returns Rejected for the second link`() {
        val outlet = FanOutlet.create<OwnedPush>()
        val first = outlet.linkTo(FanInlet.create<OwnedPush>() as LinkFrom<OwnedPush>)
        first.shouldBeInstanceOf<LinkResult.Connected>()
        val second = outlet.linkTo(FanInlet.create<OwnedPush>() as LinkFrom<OwnedPush>)
        second.shouldBeInstanceOf<LinkResult.Rejected>()
    }

    class OwnedConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<OwnedPush>())

        init {
            inlet.serve(object : OwnedPush {
                override fun push(buffer: Owned<String>) {
                    received += buffer.take()
                }
            })
        }
    }

    interface OwnedInletProxy {
        val inlet: Use<OwnedPush>
    }

    interface LeasedInletProxy {
        val inlet: Use<LeasedPush>
    }

    @Test
    fun `Owned crosses the bridge as move-by-serialize, and a bridged first link blocks a local second`() {
        val controller = SimulationController(11)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val bridgeA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        Peering.loopback(Peering.Side(registryA, bridgeA), Peering.Side(registryB, bridgeB))

        val consumer = OwnedConsumerCell()
        hostB.managementInlet.call.spawn(consumer)
        controller.runToIdle()

        val remote = (HostedCellProxy.create(consumer.ref, registryA, OwnedInletProxy::class.java)
                as OwnedInletProxy).inlet.call
        val moving = Owned("payload")
        remote.push(moving)
        controller.runToIdle()

        consumer.received shouldBe listOf("payload")
        // the sender's reference died with the encode (move-by-serialize)
        shouldThrow<IllegalStateException> { moving.take() }

        // an outlet whose single allowed link goes through the bridge refuses a local second
        val outlet = FanOutlet.create<OwnedPush>()
        outlet.subscribe(fixed(remote))
        shouldThrow<IllegalStateException> {
            outlet.subscribe(fixed(object : OwnedPush {
                override fun push(buffer: Owned<String>) {}
            }))
        }
    }

    @Test
    fun `Leased is refused at the machine boundary`() {
        val egress = BridgeEgressCell()
        val remote = (HostedCellProxy.create(CellRef(UUID.randomUUID()), egress, LeasedInletProxy::class.java)
                as LeasedInletProxy).inlet.call
        shouldThrow<IllegalArgumentException> { remote.push(Leased("pooled")) }
    }
}
