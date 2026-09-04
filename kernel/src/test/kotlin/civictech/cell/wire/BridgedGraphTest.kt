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
import civictech.testkit.dst.FrameInterposer
import civictech.testkit.dst.FrameInterposers
import civictech.testkit.dst.StepWindow
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

        /**
         * The per-seed outcome vector of `control - dropping one frame diverges the bridged view
         * on at least one seed`, over its own seed range `0 until 30`, as a LITERAL.
         *
         * Provenance, and why it is not a recomputation of the retrofitted path: the retrofit at
         * 67399fc23 changed this file's injector and nothing else that `runBridged` consults
         * (see the diff — three imports, a KDoc, `interpose` -> `interposer`, and the two
         * control call sites), and arm 1 of the BS-16 test proves the two injectors are
         * frame-for-frame identical. The vector is therefore the pre-retrofit vector, and a
         * later change to `runBridged`, to the wire format, or to the rig's `drop()` moves it.
         */
        val PRE_RETROFIT_DROP_OUTCOMES: List<Boolean> = listOf(
            /* seeds  0..5  */ true, true, false, true, true, false,
            /* seeds  6..11 */ true, true, true, false, true, false,
            /* seeds 12..17 */ false, true, true, false, true, true,
            /* seeds 18..23 */ true, false, true, true, false, true,
            /* seeds 24..29 */ false, true, true, true, true, true,
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

    /**
     * [interposer] sees each crossing frame with its 0-based crossing index standing in for the
     * rig's step ([civictech.testkit.dst.FrameInterposer]); returning the empty list drops it —
     * the DST rig's own [FrameInterposers.drop]/[FrameInterposers.windowed] primitives
     * (computenet-umx.3.2/computenet-umx.3.3) replace the one-off `interpose` lambda this test
     * used to hand-roll.
     */
    private fun runBridged(
        seed: Long,
        ops: Int,
        interposer: FrameInterposer = FrameInterposers.pass,
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
                interposer.apply(value, crossed++).forEach(ingressApi::propagate)
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
        // rig fault: PartitionFault(mode=DROP)'s own primitive, windowed to the single crossing
        // frame at index 2 — see FrameInterposers.drop/windowed (computenet-umx.3.2).
        val dropThirdFrame = FrameInterposers.windowed(StepWindow(2, 3), FrameInterposers.drop())
        var diverged = 0
        for (seed in 0L until 30L) {
            val run = runBridged(seed, ops = 40, interposer = dropThirdFrame)
            val rOk = tagFold(run.earlyR.arrivals) == run.expected &&
                    run.countsR.arrivals.sumOf { it.amount } == run.expected.size.toLong()
            if (!rOk) diverged++
        }
        // if this fails, the harness cannot detect wire faults
        (diverged > 0).shouldBeTrue()
    }

    /** View R intact: the bridged view folds to the same set, and the count agrees. */
    private fun bridgedViewIsIntact(run: Run): Boolean =
        tagFold(run.earlyR.arrivals) == run.expected &&
            run.countsR.arrivals.sumOf { it.amount } == run.expected.size.toLong()

    /**
     * **BS-16 — "the retrofit is behaviour-preserving" ([CHA1-61]), for this file.**
     *
     * computenet-umx.3.2 replaced this file's `interpose: (ByteArray, Int) -> ByteArray?` lambda
     * with the rig's [FrameInterposers.drop]/[FrameInterposers.windowed]. Unlike the duplicator
     * retrofit in `GlitchFreeBridgedDiamondTest`, this one draws no randomness at all, so parity
     * here can be established **exactly** rather than statistically — and the exactness is what
     * makes the pinned per-seed vector below trustworthy without re-running pre-retrofit code:
     *
     *  - **Arm 1** replays the pre-retrofit lambda, copied verbatim from `67399fc23^`, beside
     *    the retrofitted interposer over the crossing indices the control actually reaches, and
     *    requires frame-for-frame agreement. Given agreement, and a `runBridged` body otherwise
     *    unchanged by that commit, the per-seed outcome vector is identical by construction.
     *  - **Arm 2** pins that vector over the control's own seed range and asserts it.
     *  - **Arm 3** is the non-vacuity arm: swap the injector for [FrameInterposers.pass] — the
     *    neutralised injector — and both the frame-level agreement and the per-seed vector must
     *    change. Without it, arm 2 would certify a rig whose `drop()` had silently become a
     *    no-op, since "no frame was ever dropped" also produces a stable vector.
     */
    @Test
    fun `BS-16 CHA1-61 - the rig drop injector is frame-for-frame the pre-retrofit lambda, and the per-seed vector moves when it is neutralised`() {
        // Arm 1 — the pre-retrofit injector, verbatim from 67399fc23^ (BridgedGraphTest.kt:238):
        //     runBridged(seed, ops = 40) { bytes, index -> if (index == 2) null else bytes }
        // `null` meant "drop"; the retrofitted seam says the same thing with an empty list.
        val preRetrofit: (ByteArray, Int) -> ByteArray? = { bytes, index -> if (index == 2) null else bytes }
        val retrofitted = FrameInterposers.windowed(StepWindow(2, 3), FrameInterposers.drop())
        val probe = byteArrayOf(7, 8, 9, 10)
        val indices = 0 until 8
        val preRetrofitFrames = indices.map { i -> listOfNotNull(preRetrofit(probe, i)).map(ByteArray::toList) }
        indices.map { i -> retrofitted.apply(probe, i).map(ByteArray::toList) } shouldBe preRetrofitFrames

        // Arm 2 — the per-seed outcome vector over the drop control's existing seed range.
        val dropVector = (0L until 30L).map { seed ->
            bridgedViewIsIntact(runBridged(seed, ops = 40, interposer = retrofitted))
        }
        dropVector shouldBe PRE_RETROFIT_DROP_OUTCOMES

        // Arm 3 — non-vacuity. A neutralised injector disagrees at the frame level...
        indices.map { i -> FrameInterposers.pass.apply(probe, i).map(ByteArray::toList) } shouldNotBe preRetrofitFrames
        // ...and produces a different, uniformly intact per-seed vector, so arm 2 is not a
        // statement that would survive `drop()` becoming a no-op.
        val neutralisedVector = (0L until 30L).map { seed ->
            bridgedViewIsIntact(runBridged(seed, ops = 40, interposer = FrameInterposers.pass))
        }
        neutralisedVector shouldNotBe PRE_RETROFIT_DROP_OUTCOMES
        neutralisedVector.all { it }.shouldBeTrue()
    }

    @Test
    fun `control - a corrupt frame surfaces as a dead letter, traffic continues`() {
        // truncation breaks decode; windowed to the single crossing frame at index 1, same
        // FrameInterposers.windowed primitive as the drop control above.
        val truncateSecondFrame = FrameInterposers.windowed(
            StepWindow(1, 2),
            FrameInterposer { frame, _ -> listOf(frame.copyOf(frame.size / 2)) },
        )
        val run = runBridged(seed = 7, ops = 40, interposer = truncateSecondFrame)
        run.framesCrossed shouldBeGreaterThan 2
        (run.letters.size shouldBeGreaterThan 0)
    }
}
