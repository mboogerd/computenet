package civictech.runtime

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.LoopbackPeerTransport
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Suppress("UNCHECKED_CAST")
class PlacementBridgeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a cross-node graph carries data and installs one wire edge on each half`() {
        val cells = ConcurrentHashMap<String, Cell>()
        val spec = splitSpec(cells)
        val manifest = placedManifest()
        val transport = LoopbackPeerTransport(backoff = { 0L })
        val a = Runtime.boot(manifest, "a", spec, transport = transport)
        val b = Runtime.boot(manifest, "b", spec, transport = transport)

        try {
            assertEquals(setOf("w"), a.refs.keys)
            assertEquals(setOf("u"), b.refs.keys)
            val aPlan = requireNotNull(a.placement)
            val bPlan = requireNotNull(b.placement)
            assertEquals(aPlan.refOf("u"), bPlan.refOf("u"))

            a.open()
            b.open()
            val writer = cells.getValue("w") as SetCell<String>
            val union = cells.getValue("u") as UnionSetCell<String>
            writer.inlet.call.add("x")

            awaitUntil("the placed union receives x", 5_000) { "x" in membership(union) }
            val consumerLink = assertInstanceOf(WireEdgeLink::class.java, union.inlet.linking.links.single())
            assertEquals(PortAddress(aPlan.refOf("w"), "outlet"), consumerLink.fromAddr)
            val producerLink = assertInstanceOf(WireEdgeLink::class.java, writer.outlet.linking.links.single())
            assertEquals(PortAddress(aPlan.refOf("u"), "inlet"), producerLink.toAddr)
        } finally {
            b.close()
            a.close()
        }
    }

    @Test
    fun `empty placements retain whole-spec application on every node`() {
        val spec = splitSpec(ConcurrentHashMap())
        val placed = placedManifest()
        val manifest = placed.copy(placements = emptyMap())
        val transport = LoopbackPeerTransport(backoff = { 0L })
        val a = Runtime.boot(manifest, "a", spec, transport = transport)
        val b = Runtime.boot(manifest, "b", spec, transport = transport)

        try {
            assertEquals(setOf("w", "u"), a.refs.keys)
            assertEquals(setOf("w", "u"), b.refs.keys)
            assertNull(a.placement)
            assertNull(b.placement)
        } finally {
            b.close()
            a.close()
        }
    }

    @Test
    fun `node apply plans against prior handles and bridges its cross-node delta`() {
        val cells = ConcurrentHashMap<String, Cell>()
        val base = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "w",
                    factory = CellFactory { ref -> SetCell<String>(ref).also { cells["w"] = it } },
                ),
            ),
        )
        val manifest = placedManifest()
        val transport = LoopbackPeerTransport(backoff = { 0L })
        val a = Runtime.boot(manifest, "a", base, transport = transport)
        val b = Runtime.boot(manifest, "b", base, transport = transport)

        try {
            a.open()
            b.open()
            val delta = GraphSpec(
                listOf(
                    SpawnStep(
                        handle = "v",
                        factory = CellFactory { ref -> UnionSetCell<String>(ref).also { cells["v"] = it } },
                        placement = "sink",
                    ),
                    ConnectStep("w", "outlet", "v", "inlet"),
                ),
            )

            a.apply(delta)
            b.apply(delta)
            val writer = cells.getValue("w") as SetCell<String>
            val union = cells.getValue("v") as UnionSetCell<String>
            writer.inlet.call.add("after-delta")

            awaitUntil("the delta union receives a later add", 5_000) {
                "after-delta" in membership(union)
            }
            assertEquals(setOf("w"), a.refs.keys)
            assertEquals(setOf("v"), b.refs.keys)
            assertEquals(a.placement!!.refOf("v"), b.placement!!.refOf("v"))
        } finally {
            b.close()
            a.close()
        }
    }

    @Test
    fun `a non-fan cross-node target fails boot naming its handle port and half`() {
        val spec = GraphSpec(
            listOf(
                SpawnStep("w", CellFactory { ref -> SetCell<String>(ref) }),
                SpawnStep("bad", CellFactory(::NonFanInletCell), placement = "sink"),
                ConnectStep("w", "outlet", "bad", "inlet"),
            ),
        )
        val manifest = placedManifest()
        val transport = LoopbackPeerTransport(backoff = { 0L })

        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(manifest, "b", spec, transport = transport)
        }

        assertTrue(failure.message!!.contains("bad.inlet"), failure.message)
        assertTrue(failure.message!!.contains("consumer half"), failure.message)
        assertTrue(failure.message!!.contains("FanInlet"), failure.message)
    }

    @Test
    fun `a rejected bridge half fails boot naming the edge half and reason`() {
        val spec = GraphSpec(
            listOf(
                SpawnStep("w", CellFactory { ref -> SetCell<String>(ref) }),
                SpawnStep(
                    "u",
                    CellFactory { ref ->
                        UnionSetCell<String>(ref).also { union ->
                            union.inlet.linking.policies += LinkPolicy {
                                LinkResult.Rejected("test refusal")
                            }
                        }
                    },
                    placement = "sink",
                ),
                ConnectStep("w", "outlet", "u", "inlet"),
            ),
        )
        val manifest = placedManifest()

        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(manifest, "b", spec, transport = LoopbackPeerTransport(backoff = { 0L }))
        }

        assertTrue(failure.message!!.contains("w.outlet -> u.inlet"), failure.message)
        assertTrue(failure.message!!.contains("consumer half"), failure.message)
        assertTrue(failure.message!!.contains("test refusal"), failure.message)
    }

    @Test
    fun `a rejected producer bridge half fails boot naming the edge half and reason`() {
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    "w",
                    CellFactory { ref ->
                        SetCell<String>(ref).also { writer ->
                            writer.outlet.linking.policies += LinkPolicy {
                                LinkResult.Rejected("producer test refusal")
                            }
                        }
                    },
                ),
                SpawnStep("u", CellFactory { ref -> UnionSetCell<String>(ref) }, placement = "sink"),
                ConnectStep("w", "outlet", "u", "inlet"),
            ),
        )

        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(placedManifest(), "a", spec, transport = LoopbackPeerTransport(backoff = { 0L }))
        }

        assertTrue(failure.message!!.contains("w.outlet -> u.inlet"), failure.message)
        assertTrue(failure.message!!.contains("producer half"), failure.message)
        assertTrue(failure.message!!.contains("producer test refusal"), failure.message)
    }

    @Test
    fun `a refused placed boot deactivates cells before rethrowing`() {
        val deactivations = AtomicInteger()
        val base = placedManifest()
        val manifest = base.copy(
            nodes = base.nodes + (
                "b" to base.nodes.getValue("b").copy(
                    hosts = listOf("main", "worker"),
                    journalDir = tempDir.resolve("journals").toString(),
                )
            ),
        )
        val spec = GraphSpec(
            listOf(
                SpawnStep("w", CellFactory { ref -> SetCell<String>(ref) }),
                SpawnStep(
                    "bad",
                    CellFactory { ref -> RefusingInletCell(ref, deactivations) },
                    placement = "sink",
                ),
                ConnectStep("w", "outlet", "bad", "inlet"),
            ),
        )

        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(manifest, "b", spec, transport = LoopbackPeerTransport(backoff = { 0L }))
        }

        assertTrue(failure.message!!.contains("consumer half"), failure.message)
        assertEquals(1, deactivations.get(), "the locally applied prefix was not drained")
        assertTrue(Files.isDirectory(tempDir.resolve("journals").resolve("main")))
        assertTrue(Files.isDirectory(tempDir.resolve("journals").resolve("worker")))
    }

    @Test
    fun `a failed placed delta leaves the node unusable for later placed deltas`() {
        val base = GraphSpec(
            listOf(
                SpawnStep("w", CellFactory { ref -> SetCell<String>(ref) }),
                SpawnStep("u", CellFactory { ref -> UnionSetCell<String>(ref) }, placement = "sink"),
            ),
        )
        val node = Runtime.boot(
            placedManifest(),
            "a",
            base,
            transport = LoopbackPeerTransport(backoff = { 0L }),
        )

        try {
            val partial = GraphSpec(
                listOf(
                    SpawnStep(
                        "v",
                        CellFactory { ref ->
                            SetCell<String>(ref).also {
                                it.outlet.linking.policies += LinkPolicy {
                                    LinkResult.Rejected("partial delta refusal")
                                }
                            }
                        },
                    ),
                    ConnectStep("v", "outlet", "u", "inlet"),
                ),
            )

            val failure = assertThrows<IllegalStateException> { node.apply(partial) }

            assertTrue(failure.message!!.contains("partial delta refusal"), failure.message)
            assertTrue("v" in node.refs, "the local prefix should remain live after partial apply")
            assertThrows<IllegalStateException> { node.placement!!.refOf("v") }

            val followUp = assertThrows<IllegalStateException> {
                node.apply(GraphSpec(listOf(ConnectStep("v", "outlet", "u", "inlet"))))
            }
            assertTrue(followUp.message!!.contains("unknown handle 'v'"), followUp.message)
        } finally {
            node.close()
        }
    }

    @Test
    fun `placement refusal happens before any cell factory runs`() {
        val creations = AtomicInteger()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    "first",
                    CellFactory { ref ->
                        creations.incrementAndGet()
                        SetCell<String>(ref)
                    },
                ),
                SpawnStep(
                    "missing",
                    CellFactory { ref ->
                        creations.incrementAndGet()
                        SetCell<String>(ref)
                    },
                    placement = "not-declared",
                ),
            ),
        )

        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(placedManifest(), "a", spec, transport = LoopbackPeerTransport(backoff = { 0L }))
        }

        assertTrue(failure.message!!.contains("not-declared"), failure.message)
        assertEquals(0, creations.get(), "a cell factory ran before placement planning refused the spec")
    }

    private fun splitSpec(cells: ConcurrentHashMap<String, Cell>): GraphSpec = GraphSpec(
        listOf(
            SpawnStep(
                handle = "w",
                factory = CellFactory { ref -> SetCell<String>(ref).also { cells["w"] = it } },
            ),
            SpawnStep(
                handle = "u",
                factory = CellFactory { ref -> UnionSetCell<String>(ref).also { cells["u"] = it } },
                placement = "sink",
            ),
            ConnectStep("w", "outlet", "u", "inlet"),
        ),
    )

    private fun placedManifest(): Manifest {
        val address = "placement-${UUID.randomUUID()}"
        return Manifest(
            nodes = mapOf(
                "a" to NodeSpec(
                    transport = "loopback",
                    listen = "loopback://$address",
                    peerName = "a",
                ),
                "b" to NodeSpec(
                    transport = "loopback",
                    dial = listOf("a"),
                    peerName = "b",
                ),
            ),
            placements = mapOf("default" to "a", "sink" to "b"),
        )
    }

    private fun membership(cell: UnionSetCell<String>): Set<String> =
        (cell.snapshot() as Map<String, *>).keys

    private class NonFanInletCell(override val ref: CellRef) : Cell {
        val inlet = registerPort(
            "inlet",
            Use.fixed<Propagate<SetDelta<String>>>(Propagate { }, PortRef.generate()),
        )
    }

    private class RefusingInletCell(
        override val ref: CellRef,
        private val deactivations: AtomicInteger,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.linking.policies += LinkPolicy { LinkResult.Rejected("boot cleanup refusal") }
        }

        override fun onDeactivate(ctx: CellContext) {
            deactivations.incrementAndGet()
        }
    }
}
