package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-0c (plan §2 F3, spec 40/42 §Delivered watermarks): a departing replica
 * closes its delivered-watermark row so [Replication.stableFrontier] can keep
 * advancing over the surviving members. The companion's grow-only announced
 * membership deliberately retains the departed slot; its converged `closed`
 * marker, rather than a best-effort topology removal, is what removes that row
 * from the causal-stability minimum.
 *
 * **Invariant** (100 seeds): after a clean eviction, every subsequent write by
 * the surviving origin is reflected in the stable frontier. **Control**: when
 * eviction does not close the departed row, the frontier never exceeds the
 * counter that origin had reached before departure.
 */
class MemberDepartureFrontierTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    private data class Run(
        val preDepartureCounter: Long,
        val postWrites: List<Pair<Long, Long>>,
    )

    private fun run(seed: Long, closeOnEvict: Boolean): Run {
        val controller = SimulationController(seed)
        val p0 = Peer(controller)
        val p1 = Peer(controller)
        val p2 = Peer(controller)
        Peering.loopback(p0.side, p1.side)
        Peering.loopback(p1.side, p2.side)
        Peering.loopback(p0.side, p2.side)
        val logicalId = UUID.randomUUID()

        val r0 = SetCell<String>(CellRef(logicalId, 0)).also { p0.replication.replicate(it, p0.host) }
        val r1 = SetCell<String>(CellRef(logicalId, 1)).also { p1.replication.replicate(it, p1.host) }
        val r2 = SetCell<String>(CellRef(logicalId, 2)).also { p2.replication.replicate(it, p2.host) }
        controller.runToIdle()

        val op0 = (HostedCellProxy.create(r0.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val op1 = (HostedCellProxy.create(r1.ref, p1.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val op2 = (HostedCellProxy.create(r2.ref, p2.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        listOf(op0, op1, op2).forEachIndexed { index, operation -> operation.add("pre$index") }
        controller.runToIdle()

        val source = UUID.nameUUIDFromBytes("set-tags:${r0.ref.id}:${r0.ref.instanceId}".toByteArray())
        val preDepartureCounter = r0.tagLaneHighWater()
        p0.replication.stableFrontier(logicalId).perSource[source] shouldBe preDepartureCounter

        p2.replication.evict(r2, p2.host, closeDepartedRow = closeOnEvict) shouldBe true
        controller.runToIdle()

        val postWrites = (0 until 5).map { index ->
            op0.add("post$index")
            controller.runToIdle()
            r0.tagLaneHighWater() to
                (p0.replication.stableFrontier(logicalId).perSource[source] ?: Long.MIN_VALUE)
        }
        return Run(preDepartureCounter, postWrites)
    }

    @Test
    fun `closed departed row lets stable frontier cover every later surviving write - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            run(seed, closeOnEvict = true).postWrites.forEach { (written, stable) ->
                (stable >= written) shouldBe true
            }
        }
    }

    @Test
    fun `control - an unclosed departed row caps stable frontier at the pre-departure counter - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val run = run(seed, closeOnEvict = false)
            run.postWrites.forEach { (_, stable) ->
                (stable <= run.preDepartureCounter) shouldBe true
            }
        }
    }
}
