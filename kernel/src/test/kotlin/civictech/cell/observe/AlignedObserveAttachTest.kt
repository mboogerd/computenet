package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.testkit.awaitUntil
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.UUID

/**
 * BS-9's late-attach discriminator for the aligned multi-view sink (spec 20/22
 * §The aligned-composite rule, `[22-OBS-01]`/`[22-OBS-02]`): catch-up is arm
 * state, not a wave, so a sink attached mid-stream can briefly expose the two
 * arms at different seeded points before its first completeness-set release.
 *
 * The pre-alignment composites are the load-bearing discriminator here. They
 * must carry an empty [AlignedComposite.alignedFrom] while they may mix the
 * two catch-up positions; once the reader rule
 * `alignedFrom[s] != null && frontier[s] >= alignedFrom[s]` holds, every
 * composite must be one settled source prefix. The recorder is registered
 * before attach catch-up is drained so those transient composites cannot be
 * mistaken for an absence of the skew the field fences.
 */
class AlignedObserveAttachTest {

    /** Minimal lookup proxy exposing a hosted [SetCell]'s write inlet. */
    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private var tagCounter = 0L

    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    private fun evens(upTo: Int) = (1..upTo).filter { it % 2 == 0 }.toSet()

    private class Graph(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        val filter = FilterCell<Int> { it % 2 == 0 }

        init {
            val mgmt = host.managementInlet.call
            mgmt.spawn(source)
            mgmt.spawn(filter)
            mgmt.connect(source.ref, "outlet", filter.ref, "inlet")
        }
    }

    private fun ops(graph: Graph): SetOps<Int> =
        graph.host.lookup<IntSetInlet>(graph.source.ref)!!.inlet.call

    /**
     * Keep the handshaken link and its protocol lanes, but queue only the
     * non-absorbing `items` data arm, as the shared aligned-observe fixture does.
     */
    private fun rerouteThroughHostQueue(
        host: ManagedHost,
        outlet: Subscribe<Propagate<SetDelta<Int>>>,
        inletRef: PortRef,
        target: CellRef,
        portName: String,
    ) {
        val routed: Propagate<SetDelta<Int>> = host.inlet(target, portName)
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    private fun drive(graph: Graph, first: Int, last: Int, seed: Long) {
        val writer = ops(graph)
        val random = Random(seed)
        for (value in first..last) {
            writer.add(value)
            repeat(random.nextInt(4)) { graph.controller.step() }
        }
    }

    private fun mixesWaves(views: Map<String, Any?>): Boolean {
        val items = views["items"] as Set<*>
        val filtered = views["filtered"] as Set<*>
        return filtered != items.filter { (it as Int) % 2 == 0 }.toSet()
    }

    @Test
    fun `a wave released between arm connects stays outside the reader guarantee`() {
        val graph = Graph(seed = 1)
        val writer = ops(graph)
        val delegate = graph.host.managementInlet.call
        var connects = 0
        var betweenConnects: AlignedComposite? = null
        var spawnedSink: AlignedCompositeCell? = null
        val driveBetweenConnects = object : HostManagementApi by delegate {
            override fun spawn(cell: Cell): CellRef = delegate.spawn(cell).also {
                spawnedSink = cell as? AlignedCompositeCell
            }

            override fun connect(
                from: CellRef,
                outletName: String,
                to: CellRef,
                inletName: String,
            ): LinkResult {
                val result = delegate.connect(from, outletName, to, inletName)
                if (++connects == 1) {
                    writer.add(2)
                    graph.controller.runToIdle()
                    betweenConnects = checkNotNull(spawnedSink).composite()
                }
                return result
            }
        }

        val sink = Use.fixed<HostManagementApi>(driveBetweenConnects).observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        try {
            val between = checkNotNull(betweenConnects)
            between.views shouldBe mapOf("items" to setOf(2), "filtered" to emptySet<Int>())
            mixesWaves(between.views).shouldBeTrue()
            between.frontier.values.single() shouldBe 1L
            between.alignedFrom shouldBe emptyMap()

            graph.controller.runToIdle()
            writer.add(3)
            graph.controller.runToIdle()

            val aligned = sink.composite()
            val sourceId = aligned.frontier.keys.single()
            aligned.alignedFrom.getValue(sourceId) shouldBe 2L
            (aligned.frontier.getValue(sourceId) >= aligned.alignedFrom.getValue(sourceId)).shouldBeTrue()
            mixesWaves(aligned.views) shouldBe false
        } finally {
            sink.close()
        }
    }

    @Test
    fun `BS-9 - attached at wave 40, every composite from alignedFrom on is one settled wave, over 50 seeds`() {
        val settled = mapOf<String, Any?>("items" to (1..70).toSet(), "filtered" to evens(70))

        for (seed in 0L until 50L) {
            val graph = Graph(seed)
            val writer = ops(graph)
            (1..40).forEach(writer::add)
            graph.controller.runToIdle()

            // Spawn before linking so the recorder can see each arm's
            // catch-up publication. The builder connects both arms as part of
            // its call, which would make this intermediate publication
            // unobservable to an application listener.
            val sink = AlignedCompositeCell(
                mapOf("items" to View.set<Int>(), "filtered" to View.set<Int>()),
            )
            val mgmt = graph.host.managementInlet.call
            mgmt.spawn(sink)
            try {
                val recorded = Collections.synchronizedList(mutableListOf<AlignedComposite>())
                // Register before catch-up is drained: the first arm install is
                // allowed to publish the intentionally pre-alignment skew.
                sink.onComposite { recorded.add(it) }
                mgmt.connect(graph.source.ref, "outlet", sink.ref, "items")
                mgmt.connect(graph.filter.ref, "outlet", sink.ref, "filtered")
                graph.controller.runToIdle()

                rerouteThroughHostQueue(
                    graph.host,
                    graph.source.outlet,
                    sink.inlets.getValue("items").ref,
                    sink.ref,
                    "items",
                )
                drive(graph, first = 41, last = 70, seed = seed)
                graph.controller.runToIdle()

                awaitUntil("seed $seed reaches settled wave 70") {
                    recorded.lastOrNull()?.let { composite ->
                        composite.views == settled && composite.frontier.values.singleOrNull() == 70L
                    } == true
                }

                val snapshots = recorded.toList()
                val sourceId = sink.composite().frontier.keys.single()
                val alignedAt = sink.composite().alignedFrom.getValue(sourceId)
                check(alignedAt > 40L) {
                    "seed $seed alignedFrom[$sourceId] was $alignedAt"
                }

                val alignedSnapshots = snapshots.filter { composite ->
                    val firstAligned = composite.alignedFrom[sourceId]
                    firstAligned != null &&
                        (composite.frontier[sourceId] ?: Long.MIN_VALUE) >= firstAligned
                }
                alignedSnapshots.forEach { composite ->
                    mixesWaves(composite.views) shouldBe false
                    val items = composite.views.getValue("items") as Set<*>
                    val prefixSize = items.size
                    items shouldBe (1..prefixSize).toSet()
                }

                snapshots.mapNotNull { it.alignedFrom[sourceId] }.distinct() shouldBe listOf(alignedAt)
                snapshots.any { it.alignedFrom[sourceId] == null && mixesWaves(it.views) }.shouldBeTrue()
                sink.composite().droppedEdges shouldBe emptySet()
                sink.unmatchedDeltas shouldBe 0L
                sink.bufferedWaves shouldBe 0
            } finally {
                sink.close()
            }
        }
    }

    @Test
    fun `KE2-18 - push catch-up installs as arm state and sets nothing`() {
        val graph = Graph(seed = 3)
        val writer = ops(graph)
        writer.add(1)
        writer.add(2)
        writer.add(3)
        graph.controller.runToIdle()

        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        try {
            graph.controller.runToIdle()

            sink.current() shouldBe mapOf("items" to setOf(1, 2, 3), "filtered" to setOf(2))
            sink.bufferedWaves shouldBe 0
            sink.heldWaves() shouldBe emptyMap()
            sink.composite().frontier shouldBe emptyMap()
            sink.composite().alignedFrom shouldBe emptyMap()
            sink.unmatchedDeltas shouldBe 0L
        } finally {
            sink.close()
        }
    }

    @Test
    fun `KE2-18 - a pull baseline installs, joins no completeness set and advances no watermark`() {
        val graph = Graph(seed = 9)
        val writer = ops(graph)
        writer.add(1)
        writer.add(2)
        writer.add(3)
        graph.controller.runToIdle()

        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        try {
            graph.controller.runToIdle()
            rerouteThroughHostQueue(
                graph.host,
                graph.source.outlet,
                sink.inlets.getValue("items").ref,
                sink.ref,
                "items",
            )

            // Establish a real source lane and a non-empty aligned frontier from
            // which the baseline must not move either marker.
            writer.add(4)
            graph.controller.runToIdle()
            val before = sink.composite()
            val sourceId = before.frontier.keys.single()
            val baselineValue = 99

            CurrentContext.with(
                MessageContext(
                    timestamp = Timestamp(sourceId, 1_000L),
                    sourcePort = graph.source.outlet.ref,
                    baseline = TagFrontier(emptyMap()),
                ),
            ) {
                sink.inlets.getValue("items").call.propagate(
                    SetDelta(adds = mapOf(baselineValue to setOf(freshTag()))),
                )
            }

            (sink.current().getValue("items") as Set<*>) shouldBe setOf(1, 2, 3, 4, baselineValue)
            sink.bufferedWaves shouldBe 0
            sink.heldWaves() shouldBe emptyMap()
            sink.composite().frontier shouldBe before.frontier
            sink.composite().alignedFrom shouldBe before.alignedFrom
            sink.unmatchedDeltas shouldBe 0L

            // The next even wave reaches the fused filtered arm but not the
            // queued items arm. A baseline stamped at 1_000 must not have moved
            // that edge's watermark past this wave.
            writer.add(6)
            while (sink.bufferedWaves == 0 && graph.controller.step()) {
                // One task at a time makes the held-wave boundary observable.
            }
            sink.bufferedWaves shouldBe 1
            sink.heldWaves().values.single() shouldBe setOf(
                DroppedEdge("items", sink.inlets.getValue("items").linking.links.single().id),
            )

            graph.controller.runToIdle()
            mixesWaves(sink.composite().views) shouldBe false
        } finally {
            sink.close()
        }
    }

    @Test
    fun `an install after alignment keeps alignedFrom and frontier, and publishes the installed view`() {
        val graph = Graph(seed = 17)
        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        try {
            rerouteThroughHostQueue(
                graph.host,
                graph.source.outlet,
                sink.inlets.getValue("items").ref,
                sink.ref,
                "items",
            )
            drive(graph, first = 1, last = 4, seed = 17L)
            graph.controller.runToIdle()

            val before = sink.composite()
            val sourceId = before.frontier.keys.single()
            val alignedAt = before.alignedFrom.getValue(sourceId)
            (before.frontier.getValue(sourceId) > alignedAt) shouldBe true

            val installedValue = 101
            CurrentContext.with(null) {
                sink.inlets.getValue("items").call.propagate(
                    SetDelta(adds = mapOf(installedValue to setOf(freshTag()))),
                )
            }

            (sink.composite().views.getValue("items") as Set<*>) shouldBe
                setOf(1, 2, 3, 4, installedValue)
            sink.composite().alignedFrom shouldBe before.alignedFrom
            sink.composite().frontier shouldBe before.frontier
            sink.composite().droppedEdges shouldBe emptySet()
            sink.bufferedWaves shouldBe 0
            sink.unmatchedDeltas shouldBe 0L
        } finally {
            sink.close()
        }
    }
}
