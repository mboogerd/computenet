package civictech.runtime

import civictech.cell.data.SetApi
import civictech.cell.graph.TopoEvent
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.KeyedCells
import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Tag("multi-jvm")
@Timeout(240)
class PlacementRecoveryJvmTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a killed relay peer recovers its journal, re-bridges, and converges`() {
        val singleHost = runSingleHost()
        assertEquals(setOf("pear"), singleHost, "single-host observable output")

        val peers = mutableListOf<JvmPeer.Peer>()
        var node: Runtime.Node? = null
        try {
            val journalRoot = tempDir.resolve("journals")
            val manifest = PlacementFixture.threeJvmDurable(journalRoot)
            val manifestFile = writeManifest(manifest)
            PlacementFixture.resetCaptures()
            node = Runtime.boot(manifest, "a", PlacementFixture.spec())
            val view = PlacementFixture.captured("view") as PlacementFixture.SetFoldCell
            node.open()
            val aAddress = requireNotNull(node.boundAddress) {
                "node a did not expose its granted address"
            }.text

            val b = launchPeer(manifestFile, "b", "a" to aAddress)
            peers += b
            val bAddress = "ws://127.0.0.1:${b.port("ws")}"
            val c = launchPeer(manifestFile, "c", "a" to aAddress, "b" to bAddress)
            peers += c
            c.port("ws")
            awaitUntil("fresh relay peer reports a fresh boot", 10_000) {
                c.output().contains("computenet-recovered false")
            }
            assertRecoveryBeforePort(c, recovered = false)

            val items = node.mainHost.lookup<SetApi<String>>(node.refs.getValue("items"))
                ?: error("items cell is not hosted on node ${node.name}")
            items.inlet.call.add("apple")
            items.inlet.call.add("pear")
            JvmPeer.await("view converges before relay peer is killed", peers, 45_000) {
                view.membership == setOf("apple", "pear")
            }

            c.kill()
            assertTrue(c.process.waitFor(10, TimeUnit.SECONDS), "relay peer c did not exit after kill -9")
            assertRelayJournal(journalRoot)

            val recoveredC = launchPeer(manifestFile, "c", "a" to aAddress, "b" to bAddress)
            peers += recoveredC
            recoveredC.port("ws")
            awaitUntil("relaunched relay peer reports recovery", 10_000) {
                recoveredC.output().contains("computenet-recovered true")
            }
            assertRecoveryBeforePort(recoveredC, recovered = true)

            items.inlet.call.remove("apple")
            JvmPeer.await("view converges through the recovered relay peer", peers, 45_000) {
                view.membership == setOf("pear")
            }
            assertEquals(singleHost, view.membership, "recovered and single-host observable outputs")
        } finally {
            JvmPeer.destroy(peers)
            node?.close()
        }
    }

    private fun runSingleHost(): Set<String> {
        PlacementFixture.resetCaptures()
        val node = Runtime.boot(
            PlacementFixture.singleHost(),
            "a",
            PlacementFixture.spec(),
        )
        val view = PlacementFixture.captured("view") as PlacementFixture.SetFoldCell
        try {
            node.open()
            val items = node.mainHost.lookup<SetApi<String>>(node.refs.getValue("items"))
                ?: error("items cell is not hosted on node ${node.name}")
            items.inlet.call.add("apple")
            items.inlet.call.add("pear")
            items.inlet.call.remove("apple")
            awaitUntil("single-host view converges", 15_000) {
                view.membership == setOf("pear")
            }
            return view.membership
        } finally {
            node.close()
        }
    }

    private fun assertRelayJournal(journalRoot: Path) {
        val journal = checkNotNull(
            KeyedCells.hostJournal(File(journalRoot.resolve("c").resolve("main").toString())),
        ) { "relay peer c's main journal was not constructed" }
        val records = journal.replay().map(JournalRecords::decode)
        val spawnHandles = records
            .filterIsInstance<DecodedJournalRecord.Topology>()
            .flatMap { it.events }
            .filterIsInstance<TopoEvent.Spawn>()
            .map { it.handle }
            .toSet()

        assertEquals(setOf("relay"), spawnHandles, "relay journal contains only c's local spawn")
        assertTrue(
            records.any { it is DecodedJournalRecord.Frame },
            "relay journal contains at least one data frame",
        )
    }

    private fun assertRecoveryBeforePort(peer: JvmPeer.Peer, recovered: Boolean) {
        val output = peer.output()
        val recoveryLine = output.indexOf("computenet-recovered $recovered")
        val portLine = output.indexOf(JvmPeer.PORT_LINE_PREFIX + "ws ")
        assertTrue(
            recoveryLine >= 0 && portLine > recoveryLine,
            "recovery status was not reported before the port line:\n$output",
        )
    }

    private fun launchPeer(
        manifestFile: Path,
        node: String,
        vararg peers: Pair<String, String>,
    ): JvmPeer.Peer = JvmPeer.launch(
        PlacementPeerMain::class.java.name,
        "--manifest",
        manifestFile.toString(),
        "--node",
        node,
        *peers.flatMap { listOf("--peer", "${it.first}=${it.second}") }.toTypedArray(),
    )

    private fun writeManifest(manifest: Manifest): Path = tempDir.resolve("placement.json").also { file ->
        Files.writeString(file, Json.encodeToString(Manifest.serializer(), manifest))
    }
}
