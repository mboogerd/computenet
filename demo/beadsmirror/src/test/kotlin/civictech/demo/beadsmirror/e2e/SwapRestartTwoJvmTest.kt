package civictech.demo.beadsmirror.e2e

import civictech.cell.graph.TopoEvent
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.KeyedCells
import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.MirrorGraph
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.equality.MirrorExportEquality
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.testkit.JvmPeer
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A compaction swap, kill -9, and journal recovery across real child JVMs. */
class SwapRestartTwoJvmTest {
    private lateinit var listenerWorkspace: BdScratchWorkspace
    private lateinit var dialerWorkspace: BdScratchWorkspace
    private lateinit var testDir: Path

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        testDir = Files.createTempDirectory("beadsmirror-swap-restart-")
        listenerWorkspace = BdScratchWorkspace.create()
        dialerWorkspace = BdScratchWorkspace.create()
    }

    @AfterEach
    fun tearDown() {
        if (::listenerWorkspace.isInitialized) listenerWorkspace.close()
        if (::dialerWorkspace.isInitialized) dialerWorkspace.close()
        if (::testDir.isInitialized) testDir.toFile().deleteRecursively()
    }

    @Tag("multi-jvm")
    @Test
    fun `a swapped dialer recovers in a new JVM and continues to gossip under shared refs`() {
        val rig = "e2e-swap-restart-${System.nanoTime()}"
        val listenerRun = testDir.resolve("listener")
        val dialerRun = testDir.resolve("dialer")
        val listenerId = listenerWorkspace.createIssue("listener seed")
        val dialerId = dialerWorkspace.createIssue("dialer seed")
        val expected = BdExportReader(listenerWorkspace.root).read() + BdExportReader(dialerWorkspace.root).read()
        expected.map { it.id }.toSet() shouldBe setOf(listenerId, dialerId)
        var swappedRefs: Map<String, civictech.cell.CellRef>? = null

        val listener = JvmPeer.launch(
            APP, "--workspace", listenerWorkspace.root.toString(), "--run-dir", listenerRun.toString(),
            "--rig", rig, "--listen", "0", "0",
        )
        try {
            val listenerHttp = listener.port("http")
            val listenerWs = listener.port("ws")
            fun launchDialer() = JvmPeer.launch(
                APP, "--workspace", dialerWorkspace.root.toString(), "--run-dir", dialerRun.toString(),
                "--rig", rig, "--peer", "ws://localhost:$listenerWs", "--poll-interval-ms", "16", "0",
            )

            val firstDialer = launchDialer()
            try {
                val dialerHttp = firstDialer.port("http")
                JvmPeer.await("both initial HTTP folds hold both seeded issues", listOf(listener, firstDialer), CONVERGENCE_MS) {
                    servedFold(listenerHttp)?.view?.keys == setOf(listenerId, dialerId) &&
                        servedFold(dialerHttp)?.view?.keys == setOf(listenerId, dialerId)
                }

                dialerWorkspace.flatten()
                JvmPeer.await("dialer reports a CheckpointGone rebaseline", listOf(listener, firstDialer), CONVERGENCE_MS) {
                    firstDialer.output().lineSequence().any { "Rebaselined" in it && "CheckpointGone" in it }
                }
                // 64 polls at 16 ms is just over the production 1 s floor. Let
                // that elapsed checkpoint compact the historical swap before
                // kill, so this assertion does not depend on the old record
                // still being present.
                Thread.sleep(2_000)
                firstDialer.kill()
                firstDialer.process.waitFor(10, TimeUnit.SECONDS) shouldBe true
                val swap = topologies(dialerRun).lastOrNull { topology ->
                    topology.events.count { it is TopoEvent.Despawn } == 2
                }
                swappedRefs = swap?.events?.filterIsInstance<TopoEvent.Spawn>()?.associate { it.handle to it.ref }

                val restarted = launchDialer()
                try {
                    val restartedHttp = restarted.port("http")
                    JvmPeer.await("restarted dialer converges to both exports", listOf(listener, restarted), CONVERGENCE_MS) {
                        servedFold(restartedHttp)?.let { fold ->
                            MirrorExportEquality.compare(fold.view, fold.edges, expected).isEmpty()
                        } == true
                    }
                    restarted.output().lineSequence().none { "Rebaselined" in it } shouldBe true
                    val recovered = checkNotNull(servedFold(restartedHttp))
                    recovered.view.keys shouldBe setOf(listenerId, dialerId)
                    MirrorExportEquality.compare(recovered.view, recovered.edges, expected) shouldBe emptyList()

                    val laterId = listenerWorkspace.createIssue("listener after dialer restart")
                    JvmPeer.await("a fresh listener issue reaches the restarted dialer", listOf(listener, restarted), CONVERGENCE_MS) {
                        servedFold(restartedHttp)?.view?.containsKey(laterId) == true
                    }
                    restarted.output().lineSequence().none { "Rebaselined" in it } shouldBe true
                } finally {
                    JvmPeer.destroy(restarted)
                }
            } finally {
                JvmPeer.destroy(firstDialer)
            }
        } finally {
            JvmPeer.destroy(listener)
        }

        // Runtime recovery checkpoints the journal, replacing the historical
        // despawn+respawn delta with its folded live topology. Whether or not
        // that historical swap record is still present, the final journal
        // proves the same refs survived recovery and the second process's
        // shutdown.
        val dialerRefs = topologies(dialerRun).last().events.filterIsInstance<TopoEvent.Spawn>()
            .associate { it.handle to it.ref }
        val listenerRefs = topologies(listenerRun).last().events.filterIsInstance<TopoEvent.Spawn>()
            .associate { it.handle to it.ref }
        val expectedDialerRefs = MirrorCellRefs(rig, MirrorCellRefs.DIALER)
        dialerRefs shouldBe mapOf(
            MirrorGraph.MAP_HANDLE to expectedDialerRefs.mapRef,
            MirrorGraph.EDGES_HANDLE to expectedDialerRefs.edgeRef,
        )
        // A live checkpoint may have folded the historical despawn+respawn
        // record before the first JVM was killed. In that case the recovered
        // live topology above is the surviving same-ref assertion.
        swappedRefs?.let { it shouldBe dialerRefs }
        dialerRefs.mapValues { it.value.id } shouldBe listenerRefs.mapValues { it.value.id }
    }

    private fun topologies(runDir: Path): List<DecodedJournalRecord.Topology> =
        checkNotNull(KeyedCells.hostJournal(runDir.resolve(MirrorGraph.JOURNAL_ID).toFile()))
            .replay().map(JournalRecords::decode).filterIsInstance<DecodedJournalRecord.Topology>()

    private data class Fold(val view: Map<String, Map<String, String>>, val edges: Set<MirrorEdge>)

    private fun servedFold(port: Int): Fold? {
        val (status, body) = runCatching { get(port, "/beads/issues") }.getOrNull() ?: return null
        if (status != 200) return null
        val listed = Json.parseToJsonElement(body).jsonObject
        val view = listed.mapValues { (_, fields) ->
            fields.jsonObject.mapValues { (_, value) -> value.toString() }
        }
        val edges = view.keys.flatMap { id ->
            val (issueStatus, issueBody) = get(port, "/beads/issues/$id")
            check(issueStatus == 200) { "issue $id returned $issueStatus" }
            Json.parseToJsonElement(issueBody).jsonObject.getValue("dependencies").jsonArray.map { edge ->
                val item = edge.jsonObject
                MirrorEdge(
                    issueId = item.getValue("issue_id").jsonPrimitive.content,
                    dependsOnIssueId = item.getValue("depends_on_issue_id").jsonPrimitive.content,
                    type = item.getValue("type").jsonPrimitive.content,
                )
            }
        }.toSet()
        return Fold(view, edges)
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            val status = connection.responseCode
            status to (if (status < 400) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun commandAvailable(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val APP = "civictech.demo.beadsmirror.BeadsMirrorAppKt"
        const val CONVERGENCE_MS = 30_000L
    }
}
