package civictech.runtime

import civictech.cell.data.SetApi
import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Tag("multi-jvm")
@Timeout(240)
class ThreeJvmPlacementTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `one graph produces the same output on one host and three JVMs`() {
        val singleHost = runSingleHost()
        val threeJvm = runThreeJvm()

        assertEquals(setOf("pear"), singleHost, "single-host observable output")
        assertEquals(singleHost, threeJvm, "single-host and three-JVM observable outputs")
    }

    @Test
    fun `killing the operator peer leaves the cross-JVM view empty`() {
        val peers = mutableListOf<JvmPeer.Peer>()
        var node: Runtime.Node? = null
        var view: PlacementFixture.SetFoldCell? = null
        try {
            val manifest = PlacementFixture.threeJvm()
            val manifestFile = writeManifest(manifest)
            node = Runtime.boot(
                manifest,
                "a",
                PlacementFixture.spec { handle, cell ->
                    if (handle == "view") view = cell as PlacementFixture.SetFoldCell
                },
            )
            node.open()
            val aAddress = requireNotNull(node.boundAddress) { "node a did not expose its granted address" }.text

            val b = launchPeer(manifestFile, "b", "a" to aAddress)
            peers += b
            val bAddress = "ws://127.0.0.1:${b.port("ws")}"
            val c = launchPeer(manifestFile, "c", "a" to aAddress, "b" to bAddress)
            peers += c
            c.port("ws")

            b.kill()
            b.process.waitFor(5, TimeUnit.SECONDS)

            applyOperations(node)
            val fold = requireNotNull(view) { "the test JVM did not capture view" }
            neverWithin("view changes after operator peer b was killed", 2_000) {
                fold.membership.isNotEmpty()
            }
        } finally {
            JvmPeer.destroy(peers)
            node?.close()
        }
    }

    private fun runSingleHost(): Set<String> {
        var view: PlacementFixture.SetFoldCell? = null
        val node = Runtime.boot(
            PlacementFixture.singleHost(),
            "a",
            PlacementFixture.spec { handle, cell ->
                if (handle == "view") view = cell as PlacementFixture.SetFoldCell
            },
        )
        try {
            node.open()
            applyOperations(node)
            val fold = requireNotNull(view) { "the test JVM did not capture view" }
            awaitUntil("single-host view converges", 15_000) { fold.membership == setOf("pear") }
            return fold.membership
        } finally {
            node.close()
        }
    }

    private fun runThreeJvm(): Set<String> {
        val peers = mutableListOf<JvmPeer.Peer>()
        var node: Runtime.Node? = null
        var view: PlacementFixture.SetFoldCell? = null
        try {
            val manifest = PlacementFixture.threeJvm()
            val manifestFile = writeManifest(manifest)
            node = Runtime.boot(
                manifest,
                "a",
                PlacementFixture.spec { handle, cell ->
                    if (handle == "view") view = cell as PlacementFixture.SetFoldCell
                },
            )
            node.open()
            val aAddress = requireNotNull(node.boundAddress) { "node a did not expose its granted address" }.text

            val b = launchPeer(manifestFile, "b", "a" to aAddress)
            peers += b
            val bAddress = "ws://127.0.0.1:${b.port("ws")}"
            val c = launchPeer(manifestFile, "c", "a" to aAddress, "b" to bAddress)
            peers += c
            c.port("ws")

            applyOperations(node)
            val fold = requireNotNull(view) { "the test JVM did not capture view" }
            JvmPeer.await("view converges across three JVMs", peers, 45_000) {
                fold.membership == setOf("pear")
            }
            return fold.membership
        } finally {
            JvmPeer.destroy(peers)
            node?.close()
        }
    }

    private fun applyOperations(node: Runtime.Node) {
        val items = node.mainHost.lookup<SetApi<String>>(node.refs.getValue("items"))
            ?: error("items cell is not hosted on node ${node.name}")
        items.inlet.call.add("apple")
        items.inlet.call.add("pear")
        items.inlet.call.remove("apple")
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

    private fun neverWithin(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            assertFalse(condition(), what)
            Thread.sleep(10)
        }
        assertFalse(condition(), what)
    }
}
