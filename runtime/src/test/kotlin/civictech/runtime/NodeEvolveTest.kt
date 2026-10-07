package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.evolve.EvolutionHandle
import civictech.cell.evolve.Evolve
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.evolve.StateMigrating
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.PromoteStep
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TopoEvent
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkOptions
import civictech.cell.link.PeerId
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.verify.InvariantCell
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.Serializable
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Proves the runtime seam for declarative evolution. The authority case deliberately asserts
 * that the write-ahead spawn prefix survives a refusal: the shadow candidate is applied before
 * the later [PromoteStep] is denied, so it remains visible in [Runtime.Node.refs].
 */
class NodeEvolveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `node apply exposes an evolution handle and promotes the candidate`() {
        singleCaptured.clear()
        val logicalId = UUID.randomUUID()
        val manifest = manifest("apply")
        Runtime.boot(manifest, "solo", baseSpec(logicalId)).use { node ->
            gate(node).controlInlet.call.setGreen()

            val applied = node.apply(evolutionDelta(logicalId))
            val handle = checkNotNull(applied.evolutions["evo"])

            (1..3).forEach { feed(node, it) }
            awaitUntil("the collector sees the evolution window", 10_000) {
                collector(node).received.size >= 3
            }
            assertEquals(EvolutionHandle.State.PROMOTED, handle.advance())
            assertFalse("incumbent" in node.refs, "the incumbent handle survived promotion")
            assertEquals(applied.refs.getValue("candidate"), node.refs.getValue("candidate"))

            feed(node, 4)
            node.mainHost.quiescence().await(10_000, "post-promotion input")
            assertEquals(listOf(1L, 3L, 6L, 10L), collector(node).received.toList())
        }
    }

    @Test
    @Timeout(60)
    fun `a node reboots the evolved graph from its journal and carries the sum`() {
        singleCaptured.clear()
        val logicalId = UUID.randomUUID()
        val manifest = manifest("recovery")
        val spec = baseSpec(logicalId)
        var first: Runtime.Node? = null
        var second: Runtime.Node? = null
        var third: Runtime.Node? = null
        try {
            first = Runtime.boot(manifest, "solo", spec)
            gate(first).controlInlet.call.setGreen()
            val applied = first.apply(evolutionDelta(logicalId))
            val handle = checkNotNull(applied.evolutions["evo"])

            (1..3).forEach { feed(first!!, it) }
            awaitUntil("the collector sees the pre-close evolution window", 10_000) {
                collector(first!!).received.size >= 3
            }
            assertEquals(EvolutionHandle.State.PROMOTED, handle.advance())
            feed(first, 4)
            first.mainHost.quiescence().await(10_000, "pre-close input")
            val beforeClose = collector(first).received.toList()
            assertEquals(listOf(1L, 3L, 6L, 10L), beforeClose)
            first.close()

            second = Runtime.boot(manifest, "solo", spec)
            assertTrue(second.recovered)
            assertTrue("candidate" in second.refs, "recovered refs ${second.refs.keys} lack candidate")
            assertFalse("incumbent" in second.refs, "recovered refs ${second.refs.keys} keep incumbent")
            assertEquals(beforeClose, collector(second).received.toList())

            feed(second, 5)
            second.mainHost.quiescence().await(10_000, "post-recovery input")
            assertEquals(beforeClose + 15L, collector(second).received.toList())

            second.mainHost.checkpoint(second.journals.getValue("main"))
            second.close()
            third = Runtime.boot(manifest, "solo", spec)
            assertTrue(third.recovered)
            assertTrue("candidate" in third.refs, "compacted refs ${third.refs.keys} lack candidate")
            assertFalse("incumbent" in third.refs, "compacted refs ${third.refs.keys} keep incumbent")
            assertEquals(beforeClose + 15L, collector(third).received.toList())

            feed(third, 6)
            third.mainHost.quiescence().await(10_000, "post-compaction recovery input")
            assertEquals(beforeClose + listOf(15L, 21L), collector(third).received.toList())
        } finally {
            third?.close()
            second?.close()
            first?.close()
        }
    }

    @Test
    @Timeout(60)
    fun `a node closed while shadowing aborts the recovered evolution and keeps production serving`() {
        singleCaptured.clear()
        val logicalId = UUID.randomUUID()
        val manifest = manifest("interrupted-shadow")
        val spec = baseSpec(logicalId)
        var first: Runtime.Node? = null
        var second: Runtime.Node? = null
        try {
            first = Runtime.boot(manifest, "solo", spec)
            gate(first).controlInlet.call.setGreen()
            val applied = first.apply(evolutionDelta(logicalId))
            val handle = checkNotNull(applied.evolutions["evo"])
            val candidate = applied.refs.getValue("candidate")

            feed(first, 1)
            feed(first, 2)
            first.mainHost.quiescence().await(10_000, "interrupted shadow input")
            assertEquals(EvolutionHandle.State.SHADOWING, handle.state)
            val beforeClose = collector(first).received.toList()
            assertEquals(listOf(1L, 3L), beforeClose)
            first.close()

            second = Runtime.boot(manifest, "solo", spec)
            assertTrue(second.recovered)
            assertTrue("incumbent" in second.refs, "recovery removed the serving incumbent")
            assertFalse("candidate" in second.refs, "recovery retained an unjudged shadow")
            // computenet-q37rn: before deferred frame staging, the candidate's own journaled
            // frames were submitted to the intake while the journal walk was still running, and
            // only despawned afterwards once recovery classified the evolution as interrupted —
            // so a staged frame targeting the now-despawned candidate was dead-lettered as
            // "unknown cell" once the scheduler delivered it.
            assertEquals(
                0L,
                second.mainHost.supervisionAccounting().deadLetters,
                "interrupted evolution recovery dead-lettered a replayed candidate frame",
            )

            val cleanup = topologyEvents(second)
            assertTrue(
                cleanup.filterIsInstance<TopoEvent.Unlink>().any { it.to == candidate },
                "recovery did not journal the interrupted evolution's tap unlink",
            )
            assertTrue(
                cleanup.filterIsInstance<TopoEvent.Despawn>().any { it.ref == candidate },
                "recovery did not journal the interrupted evolution's shadow despawn",
            )
            assertEquals(beforeClose, collector(second).received.toList())

            gate(second).controlInlet.call.setGreen()
            feed(second, 3)
            second.mainHost.quiescence().await(10_000, "incumbent after interrupted evolution recovery")
            assertEquals(beforeClose + 6L, collector(second).received.toList())
        } finally {
            second?.close()
            first?.close()
        }
    }

    /**
     * Journal compatibility: a topology checkpoint compacted by a build that did not retain
     * Promote provenance folds to the active same-logical candidate with no incumbent and no
     * Promote record. That fold is written here directly (the candidate journaled as active), and
     * a re-boot with the pre-promotion spec that declares the shadow candidate must still recover.
     */
    @Test
    @Timeout(60)
    fun `a compacted promotion without retained provenance still recovers the declared shadow`() {
        singleCaptured.clear()
        val logicalId = UUID.randomUUID()
        val manifest = manifest("legacy-compacted")
        val candidate = SpawnStep(
            "candidate",
            SingleFactory("candidate"),
            identity = IdentityBinding.NewInstanceOf(logicalId),
            journalId = "main",
        )
        val legacyFold = GraphSpec(
            listOf(
                SpawnStep("relay", SingleFactory("relay")),
                SpawnStep("gate", SingleFactory("gate")),
                candidate,
                SpawnStep("collector", SingleFactory("collector"), journalId = "main"),
                ConnectStep("relay", "outlet", "gate", "dataInlet", staged),
                ConnectStep("gate", "dataOutlet", "candidate", "inlet", staged),
                ConnectStep("candidate", "outlet", "collector", "inlet", staged),
            ),
        )
        Runtime.boot(manifest, "solo", legacyFold).close()

        val declared = GraphSpec(
            baseSpec(logicalId).steps.toMutableList().apply {
                add(3, candidate.copy(shadow = true))
                add(ConnectStep("gate", "dataOutlet", "candidate", "inlet", staged))
            },
        )
        Runtime.boot(manifest, "solo", declared).use { node ->
            assertTrue(node.recovered)
            assertTrue("candidate" in node.refs, "recovered refs ${node.refs.keys} lack candidate")
            assertFalse("incumbent" in node.refs, "recovered refs ${node.refs.keys} keep incumbent")
        }
    }

    @Test
    @Timeout(60)
    fun `remote authority refuses node apply after applying the shadow prefix`() {
        singleCaptured.clear()
        val logicalId = UUID.randomUUID()
        val manifest = manifest("authority")
        Runtime.boot(manifest, "solo", baseSpec(logicalId)).use { node ->
            gate(node).controlInlet.call.setGreen()

            val refusal = CurrentPeer.with(PeerId("mallory")) {
                assertThrows(Evolve.Refused::class.java) {
                    node.apply(evolutionDelta(logicalId))
                }
            }
            assertTrue(refusal.message!!.contains("authority"), refusal.message)
            assertTrue("incumbent" in node.refs)
            assertTrue("candidate" in node.refs, "the write-ahead shadow spawn was not retained")

            feed(node, 7)
            node.mainHost.quiescence().await(10_000, "incumbent after refusal")
            assertEquals(listOf(7L), collector(node).received.toList())
        }
    }

    private fun manifest(name: String): Manifest = Manifest(
        mapOf(
            "solo" to NodeSpec(
                journalDir = tempDir.resolve(name).toString(),
                journalTopology = true,
            ),
        ),
    )

    private fun baseSpec(logicalId: UUID): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("relay", SingleFactory("relay")),
            SpawnStep("gate", SingleFactory("gate")),
            SpawnStep(
                "incumbent",
                SingleFactory("incumbent"),
                identity = IdentityBinding.NewInstanceOf(logicalId),
                journalId = "main",
            ),
            SpawnStep("collector", SingleFactory("collector"), journalId = "main"),
            ConnectStep("relay", "outlet", "gate", "dataInlet", staged),
            ConnectStep("gate", "dataOutlet", "incumbent", "inlet", staged),
            ConnectStep("incumbent", "outlet", "collector", "inlet", staged),
        ),
    )

    private fun evolutionDelta(logicalId: UUID): GraphSpec = GraphSpec(
        listOf(
            SpawnStep(
                "candidate",
                SingleFactory("candidate"),
                identity = IdentityBinding.NewInstanceOf(logicalId),
                journalId = "main",
                shadow = true,
            ),
            SpawnStep("gate-inv", InvariantFactory),
            ConnectStep("candidate", "outlet", "gate-inv", "inlet", staged),
            promoteStep(),
        ),
    )

    private fun promoteStep() = PromoteStep(
        handle = "evo",
        incumbent = "incumbent",
        candidate = "candidate",
        gate = "gate",
        outletName = "outlet",
        downstream = listOf("collector" to "inlet"),
        policy = PromotionPolicy(
            gates = listOf(INVARIANT_NAME),
            window = ObservationWindow(3),
            judge = "node-evolve-judge",
        ),
        gates = listOf("gate-inv"),
    )

    private fun feed(node: Runtime.Node, value: Int) {
        node.mainHost.lookup<RelayProxy>(node.refs.getValue("relay"))!!.inlet.call.provide(value)
    }

    private fun topologyEvents(node: Runtime.Node): List<TopoEvent> = node.journals.getValue("main").replay()
        .map(JournalRecords::decode)
        .filterIsInstance<DecodedJournalRecord.Topology>()
        .flatMap { it.events }

    @Suppress("UNCHECKED_CAST")
    private fun gate(node: Runtime.Node): TrafficLightCell<Consumer<Int>> =
        singleCaptured.getValue(node.refs.getValue("gate")) as TrafficLightCell<Consumer<Int>>

    private fun collector(node: Runtime.Node): CollectorCell =
        singleCaptured.getValue(node.refs.getValue("collector")) as CollectorCell

    interface RelayProxy {
        val inlet: Use<Consumer<Int>>
    }

    class RelayCell(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) = outlet.call.provide(input)
            })
        }
    }

    class SummerV1(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.propagate(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum

        override fun restore(state: Serializable) {
            sum = state as Long
        }
    }

    class SummerV2(override val ref: CellRef) : Cell, Stateful, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var representation = "sum=0"

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    representation = "sum=${sum() + input}"
                    outlet.call.propagate(sum())
                }
            })
        }

        private fun sum(): Long = representation.removePrefix("sum=").toLong()

        override fun snapshot(): Serializable = representation

        override fun restore(state: Serializable) {
            representation = state as String
        }

        override fun importFrom(prior: Serializable) {
            representation = "sum=${prior as Long}"
        }
    }

    class CollectorCell(override val ref: CellRef) : Cell, Stateful {
        val received = Collections.synchronizedList(mutableListOf<Long>())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Long>>())

        init {
            inlet.serve(Propagate { received += it })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        override fun restore(state: Serializable) {
            @Suppress("UNCHECKED_CAST")
            received.apply {
                clear()
                addAll(state as List<Long>)
            }
        }
    }

    private object InvariantFactory : CellFactory {
        override fun create(ref: CellRef): Cell = InvariantCell<Long, Pair<Long, Long>>(
            name = INVARIANT_NAME,
            initial = 0L to 0L,
            fold = { state, value -> state.second to value },
            check = { state, _ ->
                if (state.second < state.first) "${state.second} < ${state.first}" else null
            },
            ref = ref,
        ).also { singleCaptured[it.ref] = it }
    }

    private data class SingleFactory(private val kind: String) : CellFactory {
        override fun create(ref: CellRef): Cell {
            val cell = when (kind) {
                "relay" -> RelayCell(ref)
                "gate" -> TrafficLightCell(consumerInt, ref)
                "incumbent" -> SummerV1(ref)
                "candidate" -> SummerV2(ref)
                "collector" -> CollectorCell(ref)
                else -> error("unknown cell kind $kind")
            }
            singleCaptured[ref] = cell
            return cell
        }
    }

    companion object {
        private const val INVARIANT_NAME = "candidate sum never regresses"
        private val staged = LinkOptions(staged = true)

        @Suppress("UNCHECKED_CAST")
        private val consumerInt = Consumer::class.java as Class<Consumer<Int>>
        private val singleCaptured = ConcurrentHashMap<CellRef, Cell>()
    }
}
