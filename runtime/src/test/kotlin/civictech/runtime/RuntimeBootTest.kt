package civictech.runtime

import civictech.cell.BudgetLedger
import civictech.cell.BudgetRefusedException
import civictech.cell.ClaimClass
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.wire.LoopbackPeerTransport
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.Peering
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import civictech.inspect.InspectorFlag
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RuntimeBootTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `boot builds every host, applies the spec on the first, journals it, and open is one-shot`() {
        val journalRoot = tempDir.resolve("journal")
        val manifest = Manifest(
            mapOf(
                "solo" to NodeSpec(
                    hosts = listOf("main", "worker"),
                    journalDir = journalRoot.toString(),
                    replica = 11,
                ),
            ),
        )
        val node = Runtime.boot(manifest, "solo", setSpec())

        try {
            assertEquals(setOf("main", "worker"), node.hosts.keys)
            assertSame(node.hosts.getValue("main"), node.mainHost)
            assertEquals(setOf("items"), node.refs.keys)
            assertEquals(node.registry.localRefs(), node.refs.values.toSet())
            assertEquals(11, node.replica)
            assertSame(BudgetLedger.Unlimited, node.budget)

            node.mainHost.lookup<SetApi<String>>(node.refs.getValue("items"))!!.inlet.call.add("journal-me")
            node.mainHost.quiescence().await(10_000, "journalled runtime write")
            assertTrue(
                Files.isRegularFile(journalRoot.resolve("main").resolve("host.journal")),
                "the main host did not write journalDir/main/host.journal",
            )
            assertTrue(Files.isDirectory(journalRoot.resolve("worker")), "the worker did not receive its own journal dir")

            node.open()
            assertEquals(null, node.boundAddress)
            assertTrue(node.connections.isEmpty())
            assertEquals(null, node.inspector)
            assertThrows<IllegalStateException> { node.open() }
        } finally {
            node.close()
        }

        assertTrue(node.hosts.values.all { it.isDrained }, "close did not drain every manifest host")
    }

    @Test
    fun `a budget policy file yields one node-scoped token bucket ledger`() {
        val policyFile = tempDir.resolve("policy.json")
        Files.writeString(policyFile, Json.encodeToString(EconomicPolicy.placeholder()))
        val node = Runtime.boot(
            Manifest(mapOf("budgeted" to NodeSpec(hosts = listOf("main", "worker"), budget = policyFile.toString()))),
            "budgeted",
            GraphSpec(emptyList()),
        )

        try {
            assertTrue(node.budget is TokenBucketLedger, "budget file did not build a TokenBucketLedger")
            assertEquals("budgeted", (node.budget as TokenBucketLedger).scope)
        } finally {
            node.close()
        }
    }

    @Test
    fun `every manifest host charges the one node ledger`() {
        // An unvouched principal starts with 3 Spawn tokens at price 1, and a refill
        // interval of one hour returns none during the test: the bucket admits exactly
        // three stamped spawns.
        val policy = EconomicPolicy.placeholder().let { base ->
            base.copy(
                refill = base.refill.mapValues { EconomicPolicy.Refill(tokensPerInterval = 1, intervalNanos = 3_600_000_000_000) },
                unvouchedBootstrap = mapOf(ClaimClass.Spawn to 3L),
            )
        }
        val policyFile = tempDir.resolve("policy.json")
        Files.writeString(policyFile, Json.encodeToString(policy))
        val node = Runtime.boot(
            Manifest(mapOf("budgeted" to NodeSpec(hosts = listOf("main", "worker"), budget = policyFile.toString()))),
            "budgeted",
            GraphSpec(emptyList()),
        )
        val principal = PeerId("principal-q")
        fun spawnOn(host: String) = CurrentPeer.with(principal, AuthLevel.Authenticated) {
            node.hosts.getValue(host).managementInlet.call.spawn(SetCell<String>())
        }

        try {
            repeat(3) { spawnOn("main") }
            // The worker's first stamped spawn is refused only if it charges the SAME
            // bucket main just drained: an unbudgeted or separately-budgeted worker admits it.
            assertThrows<BudgetRefusedException> { spawnOn("worker") }
        } finally {
            node.close()
        }
    }

    @Test
    fun `open serves the requested inspector over the node hosts and bridge`() {
        val node = Runtime.boot(
            Manifest(mapOf("visible" to NodeSpec())),
            "visible",
            setSpec(),
            inspector = InspectorFlag.Options(port = 0),
        )

        try {
            assertEquals(null, node.inspector, "the inspector started during boot rather than open")
            node.open()
            assertNotNull(node.inspector)
            assertTrue(node.inspector!!.boundPort > 0)
        } finally {
            node.close()
        }

        assertTrue(node.mainHost.isDrained, "close left the inspected host running")
    }

    @Test
    fun `customizeInspector renames hosts and cells and runs configure, and defaults stay unchanged`() {
        fun topology(node: Runtime.Node): String =
            java.net.URI("http://localhost:${node.inspector!!.boundPort}/api/inspect/topology").toURL().readText()

        val plain = Runtime.boot(
            Manifest(mapOf("n" to NodeSpec())), "n", setSpec(), inspector = InspectorFlag.Options(port = 0),
        )
        try {
            plain.open()
            val text = topology(plain)
            assertTrue("\"n/main\"" in text && "custom-main" !in text, text)
        } finally {
            plain.close()
        }

        val node = Runtime.boot(
            Manifest(
                mapOf(
                    "n" to NodeSpec(
                        transport = "loopback",
                        listen = "loopback://inspector-customization",
                    ),
                ),
            ),
            "n",
            setSpec(),
            inspector = InspectorFlag.Options(port = 0),
            transport = LoopbackPeerTransport(),
        )
        var configured = false
        try {
            node.customizeInspector(
                Runtime.InspectorExtras(
                    hostNames = mapOf("main" to "custom-main", "bridge" to "custom-bridge"),
                    cellNames = mapOf(node.refs.getValue("items") to "renamed-items"),
                ) { configured = true },
            )
            node.open()
            val text = topology(node)
            assertTrue(configured, "configure did not run")
            assertTrue("\"custom-main\"" in text, text)
            assertTrue("\"custom-bridge\"" in text, text)
            assertTrue("renamed-items" in text, text)
            assertTrue("n/main" !in text, text)
            assertThrows<IllegalStateException> { node.customizeInspector(Runtime.InspectorExtras()) }
        } finally {
            node.close()
        }
    }

    @Test
    fun `a supplied transport instance opens the node and a scheme mismatch is refused`() {
        val supplied = RecordingTransport(LoopbackPeerTransport())
        val manifest = Manifest(
            mapOf(
                "listener" to NodeSpec(
                    transport = "loopback",
                    listen = "loopback://supplied-instance",
                ),
            ),
        )
        val node = Runtime.boot(manifest, "listener", GraphSpec(emptyList()), transport = supplied)

        try {
            node.open()
            assertEquals(1, supplied.listenCalls, "open did not use the supplied transport instance")
            assertEquals("loopback://supplied-instance", node.boundAddress?.text)
        } finally {
            node.close()
        }

        val mismatch = assertThrows<IllegalArgumentException> {
            Runtime.boot(
                Manifest(mapOf("listener" to NodeSpec(transport = "ws"))),
                "listener",
                GraphSpec(emptyList()),
                transport = supplied,
            )
        }
        assertTrue(
            mismatch.message!!.contains("transport override scheme 'loopback' does not match node 'listener' scheme 'ws'"),
            mismatch.message,
        )
    }

    @Test
    fun `boot refuses a node absent from the manifest`() {
        val failure = assertThrows<IllegalArgumentException> {
            Runtime.boot(Manifest(mapOf("present" to NodeSpec())), "missing", GraphSpec(emptyList()))
        }

        assertTrue(failure.message!!.contains("manifest has no node 'missing'"), failure.message)
    }

    private fun setSpec(): GraphSpec = GraphSpec(
        listOf(SpawnStep("items", CellFactory { ref -> SetCell<String>(ref) })),
    )

    private class RecordingTransport(private val delegate: PeerTransport) : PeerTransport {
        var listenCalls: Int = 0
            private set

        override val scheme: String get() = delegate.scheme

        override fun parseAddress(text: String): PeerAddress = delegate.parseAddress(text)

        override fun listen(address: PeerAddress, side: Peering.Side): PeerListener {
            listenCalls += 1
            return delegate.listen(address, side)
        }

        override fun dial(address: PeerAddress, side: Peering.Side): PeerConnection = delegate.dial(address, side)
    }
}
