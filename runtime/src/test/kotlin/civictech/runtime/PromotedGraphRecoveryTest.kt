package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.data.Replicable
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.DeliveryTracking
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.StabilityReclaim
import civictech.cell.data.delta.TagLaneContinuity
import civictech.cell.evolve.StateMigrating
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.link.LinkOptions
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.LoopbackPeerTransport
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.Serializable
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `Runtime.Node` promotes journaled cells (`Node.promote`, `Node.promoteReplica`) and a re-boot
 * on the same manifest and journal directory recovers the post-promotion topology
 * (computenet-uwt8b.4; uwt8b-D4, D7..D10; `[24-DUR-02]`, `[53-STATE-03]`, `[53-REPL-02..03]`).
 *
 * Both tests close a node and re-boot it in-process. The real-JVM `kill -9` leg over separate
 * peers belongs to the sibling stacked-gate feature, not here.
 *
 * The single-instance node re-boots with the exact original, pre-promotion [GraphSpec]. The journal
 * is the authority for the post-promotion topology: callers do not need to author a second spec
 * that already knows which candidate won.
 */
class PromotedGraphRecoveryTest {

    @TempDir
    lateinit var tempDir: Path

    // --- single node: gate -> incumbent / shadow candidate -> collector --------------------

    @Test
    @Timeout(60)
    fun `a promoted journaled graph reboots with the candidate live and the carried sum`() {
        singleCaptured.clear()
        val logicalId = UUID.randomUUID()
        val manifest = Manifest(
            mapOf(
                "solo" to NodeSpec(
                    journalDir = tempDir.resolve("solo").toString(),
                    journalTopology = true,
                ),
            ),
        )
        val spec = singleSpec(logicalId)
        val first = Runtime.boot(manifest, "solo", spec)
        var second: Runtime.Node? = null
        try {
            assertFalse(first.recovered)
            gate(first).controlInlet.call.setGreen()
            (1..5).forEach { feed(first, it) }
            first.mainHost.quiescence().await(10_000, "pre-promotion drive")

            first.promote(
                gate = "gate",
                incumbent = "incumbent",
                candidate = "candidate",
                outletName = "outlet",
                downstream = listOf("collector" to "inlet"),
            )
            (6..8).forEach { feed(first, it) }
            first.mainHost.quiescence().await(10_000, "post-promotion drive")
            val preCrash = collector(first).received.toList()
            assertEquals(36L, preCrash.last(), "1..8 sum before the crash")
            first.close()

            second = Runtime.boot(manifest, "solo", spec)
            assertTrue(second.recovered)
            assertTrue("candidate" in second.refs, "recovered refs ${second.refs.keys} lack the candidate")
            assertFalse("incumbent" in second.refs, "recovered refs ${second.refs.keys} keep the incumbent")
            assertInstanceOf(SummerV2::class.java, singleCaptured.getValue(second.refs.getValue("candidate")))
            second.mainHost.quiescence().await(10_000, "recovery drain")
            assertEquals(preCrash, collector(second).received.toList(), "collector state was not carried")

            gate(second).controlInlet.call.setGreen()
            feed(second, 9)
            second.mainHost.quiescence().await(10_000, "post-reboot drive")
            assertEquals(
                preCrash + 45L,
                collector(second).received.toList(),
                "the post-reboot input reaches the collector with the carried sum exactly once",
            )
        } finally {
            second?.close()
            first.close()
        }
    }

    // --- two nodes: replicated SetCell, b promotes its replica -----------------------------

    @Test
    @Timeout(60)
    fun `a promoted replica reboots as the candidate and converges on adds made during its outage`() {
        replicaCaptured.clear()
        val address = "promoted-recovery-${UUID.randomUUID()}"
        val manifest = Manifest(
            mapOf(
                "a" to NodeSpec(transport = "loopback", listen = "loopback://$address", peerName = "a"),
                "b" to NodeSpec(
                    transport = "loopback",
                    dial = listOf("a"),
                    peerName = "b",
                    journalDir = tempDir.resolve("b").toString(),
                    journalTopology = true,
                ),
            ),
        )
        val logicalId = UUID.randomUUID()
        val transport = LoopbackPeerTransport(backoff = { 0L })
        val a = Runtime.boot(manifest, "a", replicaSpec("a", logicalId, journaled = false), transport = transport)
        var b1: Runtime.Node? = null
        var b2: Runtime.Node? = null
        try {
            a.open()
            b1 = Runtime.boot(manifest, "b", replicaSpec("b", logicalId, journaled = true), transport = transport)
            b1.open()
            val aRef = a.refs.getValue("items")
            val bRef = b1.refs.getValue("items")
            val aOps = a.mainHost.lookup<SetApi<String>>(aRef)!!.inlet.call
            aOps.add("before")
            awaitUntil("b's incumbent replica converges before the promotion", 15_000) {
                "before" in membership(replicaCaptured.getValue("b"))
            }

            b1.promoteReplica("items", CandidateFactory("b"))
            assertInstanceOf(CandidateSetCell::class.java, replicaCaptured.getValue("b"))
            b1.close()

            aOps.add("during-outage")
            b2 = Runtime.boot(manifest, "b", replicaSpec("b", logicalId, journaled = true), transport = transport)
            assertTrue(b2.recovered)
            assertEquals(bRef, b2.refs.getValue("items"))
            assertInstanceOf(CandidateSetCell::class.java, replicaCaptured.getValue("b"))
            b2.open()

            awaitUntil("the recovered candidate converges to a's membership", 15_000) {
                membership(replicaCaptured.getValue("b")) == setOf("before", "during-outage")
            }
            assertEquals(setOf("before", "during-outage"), membership(replicaCaptured.getValue("a")))
        } finally {
            b2?.close()
            b1?.close()
            a.close()
        }
    }

    private fun singleSpec(logicalId: UUID): GraphSpec {
        val staged = LinkOptions(staged = true)
        return GraphSpec(
            buildList {
                // Runtime's host-journal fallback journals these unbound relay/gate cells too;
                // recovery must therefore suppress their replay-derived copies downstream.
                add(SpawnStep("relay", SingleFactory("relay")))
                add(SpawnStep("gate", SingleFactory("gate")))
                add(
                    SpawnStep(
                        "incumbent", SingleFactory("incumbent"),
                        identity = IdentityBinding.NewInstanceOf(logicalId), journalId = "main",
                    ),
                )
                add(
                    SpawnStep(
                        "candidate", SingleFactory("candidate"),
                        identity = IdentityBinding.NewInstanceOf(logicalId), journalId = "main", shadow = true,
                    ),
                )
                add(SpawnStep("collector", SingleFactory("collector"), journalId = "main"))
                add(ConnectStep("relay", "outlet", "gate", "dataInlet", staged))
                add(ConnectStep("gate", "dataOutlet", "incumbent", "inlet", staged))
                add(ConnectStep("incumbent", "outlet", "collector", "inlet", staged))
                add(ConnectStep("gate", "dataOutlet", "candidate", "inlet", staged))
            },
        )
    }

    private fun replicaSpec(node: String, logicalId: UUID, journaled: Boolean): GraphSpec = GraphSpec(
        listOf(
            SpawnStep(
                handle = "items",
                factory = ReplicaFactory(node),
                identity = IdentityBinding.NewInstanceOf(logicalId),
                replicated = true,
                journalId = if (journaled) "main" else null,
            ),
        ),
    )

    private fun feed(node: Runtime.Node, value: Int) {
        node.mainHost.lookup<RelayProxy>(node.refs.getValue("relay"))!!.inlet.call.provide(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun gate(node: Runtime.Node): TrafficLightCell<Consumer<Int>> =
        singleCaptured.getValue(node.refs.getValue("gate")) as TrafficLightCell<Consumer<Int>>

    private fun collector(node: Runtime.Node): CollectorCell =
        singleCaptured.getValue(node.refs.getValue("collector")) as CollectorCell

    @Suppress("UNCHECKED_CAST")
    private fun membership(cell: Cell): Set<String> = when (cell) {
        is SetCell<*> -> (cell as SetCell<String>).membership()
        is CandidateSetCell -> cell.membership()
        else -> error("unexpected replica class ${cell.javaClass.name}")
    }

    // --- fixtures ---------------------------------------------------------------------------

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

    /** The candidate class witness: it keeps its state as a string, so import is a real migration. */
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
        val received = java.util.Collections.synchronizedList(mutableListOf<Long>())
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

    /** Class/version witness while retaining SetCell's exact replicated semantics. */
    class CandidateSetCell(
        override val ref: CellRef,
        private val delegate: SetCell<String> = SetCell(ref),
    ) : Cell,
        Replicable<SetDelta<String>>,
        Stateful,
        DeliveryTracking by delegate,
        StabilityReclaim by delegate,
        TagLaneContinuity by delegate {
        val inlet = registerPort("inlet", delegate.inlet)
        override val outlet = registerPort("outlet", delegate.outlet)
        override val deltaInlet = registerPort("deltaInlet", delegate.deltaInlet)

        fun membership(): Set<String> = delegate.membership()

        override fun snapshot(): Serializable = delegate.snapshot()

        override fun restore(state: Serializable) = delegate.restore(state)
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

    private data class ReplicaFactory(private val node: String) : CellFactory {
        override fun create(ref: CellRef): Cell = SetCell<String>(ref).also { replicaCaptured[node] = it }
    }

    private data class CandidateFactory(private val node: String) : CellFactory {
        override fun create(ref: CellRef): Cell = CandidateSetCell(ref).also { replicaCaptured[node] = it }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val consumerInt = Consumer::class.java as Class<Consumer<Int>>
        private val singleCaptured = ConcurrentHashMap<CellRef, Cell>()
        private val replicaCaptured = ConcurrentHashMap<String, Cell>()
    }
}
