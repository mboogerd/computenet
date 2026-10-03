package civictech.runtime

import civictech.cell.CellRef
import civictech.cell.BudgetLedger
import civictech.cell.BudgetRefusedException
import civictech.cell.ClaimClass
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.TopoEvent
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.KeyedCellFactory
import civictech.cell.graph.KeyedFamily
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.KeyedCells
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.wire.LoopbackPeerTransport
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.Peering
import civictech.testkit.awaitUntil
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    fun `boot applies replicated graph steps through the node replication`() {
        val address = "runtime-boot-${UUID.randomUUID()}"
        val manifest = Manifest(
            mapOf(
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
        )
        val logicalId = UUID.randomUUID()
        val cells = mutableListOf<SetCell<String>>()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "items",
                    factory = CellFactory { ref -> SetCell<String>(ref).also(cells::add) },
                    identity = IdentityBinding.NewInstanceOf(logicalId),
                    replicated = true,
                ),
            ),
        )
        val transport = LoopbackPeerTransport(backoff = { 0L })
        val a = Runtime.boot(manifest, "a", spec, transport = transport)
        var b: Runtime.Node? = null
        try {
            assertNotNull(a.replication)
            a.open()
            val bNode = Runtime.boot(manifest, "b", spec, transport = transport)
            b = bNode
            assertNotNull(bNode.replication)
            bNode.open()

            val aItems = cells.first()
            a.mainHost.lookup<SetApi<String>>(aItems.ref)!!.inlet.call.add("runtime-replication")
            awaitUntil("node a applies the routed add", 10_000) {
                "runtime-replication" in aItems.membership()
            }
            awaitUntil("node b receives the routed add", 10_000) {
                val bItems = cells.last()
                "runtime-replication" in bItems.membership()
            }
        } finally {
            b?.close()
            a.close()
        }
    }

    @Test
    fun `boot binds a journalId spawn to its named host journal across reboot`() {
        val journalRoot = tempDir.resolve("journal-id")
        val manifest = Manifest(
            mapOf(
                "durable" to NodeSpec(
                    hosts = listOf("main", "worker"),
                    journalDir = journalRoot.toString(),
                ),
            ),
        )
        val ref = CellRef(UUID.randomUUID())
        val cells = mutableListOf<SetCell<String>>()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "items",
                    factory = CellFactory { chosen -> SetCell<String>(chosen).also(cells::add) },
                    identity = IdentityBinding.Exact(ref),
                    journalId = "main",
                ),
            ),
        )
        val first = Runtime.boot(manifest, "durable", spec)
        try {
            assertEquals(false, first.recovered)
            assertEquals(setOf("main", "worker"), first.journals.keys)
            first.mainHost.lookup<SetApi<String>>(ref)!!.inlet.call.add("survives-reboot")
            first.mainHost.quiescence().await(10_000, "journalId write")
            assertTrue(Files.isRegularFile(journalRoot.resolve("main").resolve("host.journal")))
            assertTrue(
                first.journals.getValue("main").replay()
                    .none { JournalRecords.decode(it) is DecodedJournalRecord.Topology },
                "journalTopology=false wrote a topology record",
            )
        } finally {
            first.close()
        }

        val second = Runtime.boot(manifest, "durable", spec)
        try {
            assertEquals(false, second.recovered)
            val journal = KeyedCells.hostJournal(File(journalRoot.toString(), "main"))
            checkNotNull(journal) { "the named host journal was not constructed" }
            second.mainHost.recoverFrom(journal).awaitApplied(10_000)
            val recovered = cells.last()
            assertTrue("survives-reboot" in recovered.membership())
        } finally {
            second.close()
        }
    }

    @Test
    fun `topology-journalled boot applies once then recovers graph state and durable input`() {
        val journalRoot = tempDir.resolve("topology-recovery")
        val manifest = Manifest(
            mapOf(
                "durable" to NodeSpec(
                    journalDir = journalRoot.toString(),
                    journalTopology = true,
                ),
            ),
        )
        val itemsCapture = "items-${UUID.randomUUID()}"
        val auditCapture = "audit-${UUID.randomUUID()}"
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "items",
                    factory = CapturingSetFactory(itemsCapture),
                    journalId = "main",
                    inputs = setOf("feed"),
                ),
                SpawnStep(
                    handle = "audit",
                    factory = CapturingSetFactory(auditCapture),
                    journalId = "main",
                ),
            ),
        )

        val first = Runtime.boot(manifest, "durable", spec)
        val firstRefs = first.refs
        try {
            assertEquals(false, first.recovered)
            val topologySpawns = first.journals.getValue("main").replay()
                .map(JournalRecords::decode)
                .filterIsInstance<DecodedJournalRecord.Topology>()
                .flatMap { it.events }
                .filterIsInstance<TopoEvent.Spawn>()
                .map { it.handle }
                .toSet()
            assertEquals(setOf("items", "audit"), topologySpawns)

            val items = first.mainHost.lookup<SetApi<String>>(firstRefs.getValue("items"))!!.inlet.call
            val cursor = first.inputs.getValue("items").getValue("feed").commit {
                items.add("survives-reboot")
                "cursor-1"
            }
            assertEquals("cursor-1", cursor)
            first.mainHost.quiescence().await(10_000, "topology-journalled durable input")
            assertTrue("survives-reboot" in capturedSets.getValue(itemsCapture).membership())
        } finally {
            first.close()
        }

        val second = Runtime.boot(manifest, "durable", spec)
        try {
            assertTrue(second.recovered)
            assertEquals(firstRefs, second.refs)
            assertTrue("survives-reboot" in capturedSets.getValue(itemsCapture).membership())
            assertEquals("cursor-1", second.inputs.getValue("items").getValue("feed").committed())
        } finally {
            second.close()
            capturedSets.remove(itemsCapture)
            capturedSets.remove(auditCapture)
        }
    }

    @Test
    fun `topology recovery refuses a spec whose non-family handle is absent from the journal`() {
        val journalRoot = tempDir.resolve("topology-missing-handle")
        val manifest = Manifest(
            mapOf(
                "durable" to NodeSpec(
                    journalDir = journalRoot.toString(),
                    journalTopology = true,
                ),
            ),
        )
        val existingRef = CellRef(UUID.randomUUID())
        val existing = SpawnStep(
            handle = "existing",
            factory = CapturingSetFactory("existing-${UUID.randomUUID()}"),
            identity = IdentityBinding.Exact(existingRef),
            journalId = "main",
        )
        Runtime.boot(manifest, "durable", GraphSpec(listOf(existing))).close()

        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(
                manifest,
                "durable",
                GraphSpec(
                    listOf(
                        existing,
                        SpawnStep(
                            handle = "missing",
                            factory = CapturingSetFactory("missing-${UUID.randomUUID()}"),
                            journalId = "main",
                        ),
                    ),
                ),
            )
        }

        assertTrue(failure.message!!.contains("missing"), failure.message)
        assertTrue(failure.message!!.contains(journalRoot.resolve("main").toString()), failure.message)
    }

    @Test
    fun `node apply appends one topology delta through its boot context`() {
        val manifest = Manifest(
            mapOf(
                "durable" to NodeSpec(
                    journalDir = tempDir.resolve("topology-delta").toString(),
                    journalTopology = true,
                ),
            ),
        )
        Runtime.boot(manifest, "durable", GraphSpec(emptyList())).close()
        val node = Runtime.boot(manifest, "durable", GraphSpec(emptyList()))
        try {
            assertTrue(node.recovered)
            val journal = node.journals.getValue("main")
            val before = journal.replay().count { JournalRecords.decode(it) is DecodedJournalRecord.Topology }

            val applied = node.apply(
                GraphSpec(
                    listOf(
                        SpawnStep(
                            handle = "later",
                            factory = CapturingSetFactory("later-${UUID.randomUUID()}"),
                            journalId = "main",
                        ),
                    ),
                ),
            )

            val records = journal.replay().map(JournalRecords::decode)
                .filterIsInstance<DecodedJournalRecord.Topology>()
            assertEquals(before + 1, records.size)
            assertEquals("later", (records.last().events.single() as TopoEvent.Spawn).handle)
            assertEquals(applied.refs.getValue("later"), node.refs.getValue("later"))
        } finally {
            node.close()
        }
    }

    @Test
    fun `boot exposes declared durable inputs on the runtime node`() {
        val manifest = Manifest(
            mapOf(
                "inputs" to NodeSpec(
                    journalDir = tempDir.resolve("inputs-journal").toString(),
                ),
            ),
        )
        val node = Runtime.boot(
            manifest,
            "inputs",
            GraphSpec(
                listOf(
                    SpawnStep(
                        handle = "records",
                        factory = CellFactory { ref -> SetCell<String>(ref) },
                        journalId = "main",
                        inputs = setOf("spend"),
                    ),
                ),
            ),
        )

        try {
            assertNotNull(node.inputs["records"]?.get("spend"))
            assertEquals(null, node.inputs.getValue("records").getValue("spend").committed())
        } finally {
            node.close()
        }
    }

    @Test
    fun `boot binds a journalId naming another host's journal to a main-host spawn`() {
        // A journalId equal to the spawning host's own name cannot tell the
        // per-ref binding from the host default; "worker" on mainHost can.
        val journalRoot = tempDir.resolve("journal-id-worker")
        val manifest = Manifest(
            mapOf(
                "durable" to NodeSpec(
                    hosts = listOf("main", "worker"),
                    journalDir = journalRoot.toString(),
                ),
            ),
        )
        val ref = CellRef(UUID.randomUUID())
        val cells = mutableListOf<SetCell<String>>()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "items",
                    factory = CellFactory { chosen -> SetCell<String>(chosen).also(cells::add) },
                    identity = IdentityBinding.Exact(ref),
                    journalId = "worker",
                ),
            ),
        )
        val first = Runtime.boot(manifest, "durable", spec)
        try {
            first.mainHost.lookup<SetApi<String>>(ref)!!.inlet.call.add("in-worker-journal")
            first.mainHost.quiescence().await(10_000, "journalId write")
        } finally {
            first.close()
        }

        val second = Runtime.boot(manifest, "durable", spec)
        try {
            val worker = checkNotNull(KeyedCells.hostJournal(File(journalRoot.toString(), "worker")))
            second.mainHost.recoverFrom(worker).awaitApplied(10_000)
            assertTrue("in-worker-journal" in cells.last().membership())
        } finally {
            second.close()
        }
    }

    @Test
    fun `boot exposes keyed families and the runtime replication`() {
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "writers",
                    factory = KeyedCellFactory { _, ref -> SetCell<String>(ref) },
                    family = KeyedFamily("runtime-writers"),
                ),
            ),
        )
        val node = Runtime.boot(Manifest(mapOf("families" to NodeSpec())), "families", spec)
        try {
            assertTrue(node.refs.isEmpty(), "a family handle must not be exposed as one cell ref")
            assertEquals(setOf("writers"), node.families.keys)
            assertNotNull(node.families["writers"])
            assertNotNull(node.replication)
        } finally {
            node.close()
        }
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

        val transport = LoopbackPeerTransport()
        val network = Manifest(
            mapOf(
                "n" to NodeSpec(
                    transport = "loopback",
                    listen = "loopback://inspector-customization",
                ),
                "peer" to NodeSpec(
                    transport = "loopback",
                    dial = listOf("n"),
                ),
            ),
        )
        val node = Runtime.boot(
            network,
            "n",
            setSpec(),
            inspector = InspectorFlag.Options(port = 0),
            transport = transport,
        )
        val peer = Runtime.boot(network, "peer", GraphSpec(emptyList()), transport = transport)
        var configured = false
        try {
            node.customizeInspector(
                Runtime.InspectorExtras(
                    hostNames = mapOf("main" to "custom-main", "bridge" to "custom-bridge"),
                    cellNames = mapOf(node.refs.getValue("items") to "renamed-items"),
                ) { configured = true },
            )
            node.open()
            peer.open()
            val text = topology(node)
            assertTrue(configured, "configure did not run")
            assertTrue("\"custom-main\"" in text, text)
            assertTrue("\"custom-bridge\"" in text, text)
            assertTrue("renamed-items" in text, text)
            assertTrue("n/main" !in text, text)
            assertThrows<IllegalStateException> { node.customizeInspector(Runtime.InspectorExtras()) }
        } finally {
            peer.close()
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

    private data class CapturingSetFactory(private val capture: String) : CellFactory {
        override fun create(ref: CellRef): SetCell<String> =
            SetCell<String>(ref).also { capturedSets[capture] = it }
    }

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

    companion object {
        private val capturedSets = ConcurrentHashMap<String, SetCell<String>>()
    }
}
