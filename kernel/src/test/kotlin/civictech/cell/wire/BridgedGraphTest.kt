package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.CollectorCell
import civictech.cell.data.DeltaInletProxy
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.tagFold
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.CountCell

/**
 * M5.3: the generative harness (G-31) over the wire. Two registries model two
 * processes; view R's pipeline is split at a random cut and every invocation
 * crossing the cut travels as encoded [WireFrame] bytes through a loopback
 * bridge — the full wire format under deterministic 100-seed scheduling,
 * no network. Control runs prove the harness detects dropped and corrupt
 * frames.
 */
class BridgedGraphTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface FrameInletProxy {
        val inlet: Use<Propagate<ByteArray>>
    }

    interface CounterInletProxy {
        val inlet: Use<Propagate<CounterDelta>>
    }

    class CounterCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals = mutableListOf<CounterDelta>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {
                    arrivals += value
                }
            })
        }
    }

    companion object {
        val PREDICATES: List<(String) -> Boolean> = listOf(
            { it <= "e" },
            { it in setOf("a", "c", "e", "g", "i") },
            { it >= "c" },
        )
    }

    private data class Run(
        val earlyL: CollectorCell,
        val earlyR: CollectorCell,
        val countsR: CounterCollectorCell,
        val letters: List<DeadLetter>,
        val expected: Set<String>,
        val framesCrossed: Int,
    )

    /** [interpose] sees each crossing frame (bytes, index); returning null drops it. */
    private fun runBridged(
        seed: Long,
        ops: Int,
        interpose: (ByteArray, Int) -> ByteArray? = { bytes, _ -> bytes },
    ): Run {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()

        val hostW = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostL = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostA2 = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostR = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val hostBr = ManagedHost(scheduler = controller.scheduler(), registry = registryB)

        val letters = mutableListOf<DeadLetter>()
        listOf(hostW, hostL, hostA2, hostR, hostBr).forEach { host ->
            host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    letters += value
                }
            }, PortRef.generate()))
        }

        // ---- the bridge: A-side egress → interposer ("the socket") → B-hosted ingress
        val egress = BridgeEgressCell()
        val ingress = BridgeIngressCell(InvocationSink(registryB::deliver))
        hostBr.managementInlet.call.spawn(ingress)
        val ingressApi = (HostedCellProxy.create(ingress.ref, registryB, FrameInletProxy::class.java)
                as FrameInletProxy).inlet.call
        var crossed = 0
        egress.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
            override fun propagate(value: ByteArray) {
                interpose(value, crossed++)?.let(ingressApi::propagate)
            }
        }, PortRef.generate()))

        // ---- random pipeline: union → 0..2 filters → count; view R split at a random cut
        val filterIdxs = (0 until rnd.nextInt(3)).map { rnd.nextInt(PREDICATES.size) }
        val stageCount = filterIdxs.size + 2 // union + filters + count
        val terminalIdx = stageCount - 2

        fun stageCell(i: Int): Cell = when {
            i == 0 -> UnionSetCell<String>()
            i <= filterIdxs.size -> FilterCell<String> { s -> PREDICATES[filterIdxs[i - 1]](s) }
            else -> CountCell<String>()
        }

        // cut = index of the first B-side stage; stageCount → all stages on A,
        // the count→collector link crosses instead (CounterDelta over the wire)
        val cut = rnd.nextInt(stageCount + 1)

        // view L: entirely on hostL
        val cellsL = (0 until stageCount).map { stageCell(it) }
        cellsL.forEach { hostL.managementInlet.call.spawn(it) }
        (0 until stageCount - 1).forEach { i ->
            hostL.managementInlet.call.connect(cellsL[i].ref, "outlet", cellsL[i + 1].ref, "inlet")
        }
        val earlyL = CollectorCell()
        hostL.managementInlet.call.spawn(earlyL)
        hostL.managementInlet.call.connect(cellsL[terminalIdx].ref, "outlet", earlyL.ref, "inlet")

        // view R: stages < cut on hostA2 (registry A), >= cut on hostR (registry B)
        fun hostOf(i: Int) = if (i < cut) hostA2 else hostR
        val cellsR = (0 until stageCount).map { stageCell(it) }
        cellsR.forEachIndexed { i, cell -> hostOf(i).managementInlet.call.spawn(cell) }
        (0 until stageCount - 1).forEach { i ->
            if (i < cut && i + 1 >= cut) {
                // the crossing link: downstream inlet reached through the bridge
                val toApi = (HostedCellProxy.create(cellsR[i + 1].ref, egress, DeltaInletProxy::class.java)
                        as DeltaInletProxy).inlet.call
                hostA2.managementInlet.call.connect(cellsR[i].ref, "outlet", Use.fixed(toApi, PortRef.generate()))
            } else {
                hostOf(i).managementInlet.call.connect(cellsR[i].ref, "outlet", cellsR[i + 1].ref, "inlet")
            }
        }
        val earlyR = CollectorCell()
        hostOf(terminalIdx).managementInlet.call.spawn(earlyR)
        hostOf(terminalIdx).managementInlet.call.connect(cellsR[terminalIdx].ref, "outlet", earlyR.ref, "inlet")

        val countsR = CounterCollectorCell()
        if (cut == stageCount) {
            // whole pipeline on A: the count→collector link is the wire crossing
            hostR.managementInlet.call.spawn(countsR)
            val toApi = (HostedCellProxy.create(countsR.ref, egress, CounterInletProxy::class.java)
                    as CounterInletProxy).inlet.call
            hostA2.managementInlet.call.connect(cellsR[stageCount - 1].ref, "outlet", Use.fixed(toApi, PortRef.generate()))
        } else {
            hostOf(stageCount - 1).managementInlet.call.spawn(countsR)
            hostOf(stageCount - 1).managementInlet.call.connect(cellsR[stageCount - 1].ref, "outlet", countsR.ref, "inlet")
        }

        // ---- writers stream to both unions; R's union is bridged when it lives on B
        val writers = (0 until 2).map { SetCell<String>() }
        writers.forEach { writer ->
            hostW.managementInlet.call.spawn(writer)
            val unionL = (HostedCellProxy.create(cellsL[0].ref, registryA, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(unionL, PortRef.generate()))
            val unionR = if (cut == 0) {
                (HostedCellProxy.create(cellsR[0].ref, egress, DeltaInletProxy::class.java)
                        as DeltaInletProxy).inlet.call
            } else {
                (HostedCellProxy.create(cellsR[0].ref, registryA, DeltaInletProxy::class.java)
                        as DeltaInletProxy).inlet.call
            }
            writer.outlet.subscribe(Use.fixed(unionR, PortRef.generate()))
        }
        val writerApis = writers.map {
            (HostedCellProxy.create(it.ref, registryA, SetInletProxy::class.java) as SetInletProxy).inlet.call
        }

        // ---- drive random ops
        val domain = ('a'..'j').map { it.toString() }
        val held = writers.map { mutableSetOf<String>() }
        for (n in 1..ops) {
            val w = rnd.nextInt(writers.size)
            val element = domain[rnd.nextInt(domain.size)]
            if (rnd.nextInt(10) < 7 || element !in held[w]) {
                writerApis[w].add(element); held[w] += element
            } else {
                writerApis[w].remove(element); held[w] -= element
            }
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        val expected = held.flatten().toSet()
            .filter { e -> filterIdxs.all { PREDICATES[it](e) } }.toSet()
        return Run(earlyL, earlyR, countsR, letters, expected, crossed)
    }

    @Test
    fun `bridged views converge with all invariants on every seed`() {
        forEachSeed(0L until 100L) { seed ->
            val run = runBridged(seed, ops = 40)

            run.framesCrossed shouldBeGreaterThan 0 // the wire was actually on the path
            tagFold(run.earlyL.arrivals) shouldBe run.expected
            tagFold(run.earlyR.arrivals) shouldBe run.expected
            run.countsR.arrivals.sumOf { it.amount } shouldBe run.expected.size.toLong()
            run.letters.shouldBeEmpty()
        }
    }

    @Test
    fun `control - dropping one frame diverges the bridged view on at least one seed`() {
        var diverged = 0
        for (seed in 0L until 30L) {
            val run = runBridged(seed, ops = 40) { bytes, index -> if (index == 2) null else bytes }
            val rOk = tagFold(run.earlyR.arrivals) == run.expected &&
                    run.countsR.arrivals.sumOf { it.amount } == run.expected.size.toLong()
            if (!rOk) diverged++
        }
        // if this fails, the harness cannot detect wire faults
        (diverged > 0).shouldBeTrue()
    }

    @Test
    fun `control - a corrupt frame surfaces as a dead letter, traffic continues`() {
        val run = runBridged(seed = 7, ops = 40) { bytes, index ->
            if (index == 1) bytes.copyOf(bytes.size / 2) else bytes // truncation breaks decode
        }
        run.framesCrossed shouldBeGreaterThan 2
        (run.letters.size shouldBeGreaterThan 0)
    }
}
