package civictech.runtime

import civictech.cell.data.SetApi
import civictech.testkit.JvmPeer
import civictech.testkit.awaitUntil
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        val singleHost = runSingleHost(PlacementFixture.singleHost())
        val threeJvm = runThreeJvm(PlacementFixture.threeJvm())

        assertEquals(setOf("pear"), singleHost, "single-host observable output")
        assertEquals(singleHost, threeJvm, "single-host and three-JVM observable outputs")
    }

    @Test
    fun `one graph produces the same output on one host and three JVMs over iroh`() {
        val configured = System.getProperty(SIDECAR_PROPERTY)
        val binary = configured?.let(Path::of)
        assumeTrue(
            binary != null && Files.isRegularFile(binary),
            "no $SIDECAR_PROPERTY: run with -Piroh.enabled=true to build and configure the sidecar",
        )
        val transportConfig = irohTransportConfig(binary!!)

        val singleHost = runSingleHost(PlacementFixture.singleHost("iroh", transportConfig))
        val threeJvm = runThreeJvm(PlacementFixture.threeJvm("iroh", transportConfig))

        assertEquals(setOf("pear"), singleHost, "single-host observable output over iroh")
        assertEquals(singleHost, threeJvm, "single-host and three-JVM observable outputs over iroh")
    }

    @Test
    fun `killing the operator peer leaves the cross-JVM view empty`() {
        val peers = mutableListOf<JvmPeer.Peer>()
        var node: Runtime.Node? = null
        var view: PlacementFixture.SetFoldCell? = null
        try {
            val manifest = PlacementFixture.threeJvm()
            val manifestFile = writeManifest(manifest)
            PlacementFixture.resetCaptures()
            node = Runtime.boot(
                manifest,
                "a",
                PlacementFixture.spec(),
            )
            view = PlacementFixture.captured("view") as PlacementFixture.SetFoldCell
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

    private fun runSingleHost(manifest: Manifest): Set<String> {
        PlacementFixture.resetCaptures()
        val node = Runtime.boot(
            manifest,
            "a",
            PlacementFixture.spec(),
        )
        val view = PlacementFixture.captured("view") as PlacementFixture.SetFoldCell
        try {
            node.open()
            applyOperations(node)
            awaitUntil("single-host view converges", 15_000) { view.membership == setOf("pear") }
            return view.membership
        } finally {
            node.close()
        }
    }

    private fun runThreeJvm(manifest: Manifest): Set<String> {
        val peers = mutableListOf<JvmPeer.Peer>()
        var node: Runtime.Node? = null
        var view: PlacementFixture.SetFoldCell? = null
        try {
            val manifestFile = writeManifest(manifest)
            PlacementFixture.resetCaptures()
            node = Runtime.boot(
                manifest,
                "a",
                PlacementFixture.spec(),
            )
            view = PlacementFixture.captured("view") as PlacementFixture.SetFoldCell
            node.open()
            val aAddress = requireNotNull(node.boundAddress) { "node a did not expose its granted address" }.text

            val b = launchPeer(manifestFile, "b", "a" to aAddress)
            peers += b
            val bAddress = b.address()
            val c = launchPeer(manifestFile, "c", "a" to aAddress, "b" to bAddress)
            peers += c
            c.awaitReady("c")

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

    private fun JvmPeer.Peer.address(): String {
        var address: String? = null
        JvmPeer.await("peer announces its granted transport address", listOf(this)) {
            address = output().lineSequence()
                .firstOrNull { it.startsWith(PlacementPeerMain.ADDRESS_LINE_PREFIX) }
                ?.removePrefix(PlacementPeerMain.ADDRESS_LINE_PREFIX)
            address != null
        }
        return requireNotNull(address)
    }

    private fun JvmPeer.Peer.awaitReady(node: String) {
        JvmPeer.await("peer $node opens its runtime", listOf(this)) {
            output().lineSequence().any { it == PlacementPeerMain.READY_LINE_PREFIX + node }
        }
    }

    private fun irohTransportConfig(binary: Path): Map<String, String> = buildMap {
        put("binary", binary.toString())
        val sidecarArgs = buildList {
            val relayUrl = System.getProperty("iroh.relay.url")
            relayUrl?.let { addAll(listOf("--relay-url", it)) }
            val pkarrUrl = System.getProperty("iroh.pkarr.url")
            val dnsOrigin = System.getProperty("iroh.dns.origin")
            require((pkarrUrl == null) == (dnsOrigin == null)) {
                "iroh.pkarr.url and iroh.dns.origin must both be set or both unset"
            }
            if (pkarrUrl != null && dnsOrigin != null) {
                addAll(listOf("--pkarr-relay-url", pkarrUrl, "--dns-origin", dnsOrigin))
                System.getProperty("iroh.dns.nameserver")?.let { addAll(listOf("--dns-nameserver", it)) }
            }
            if (relayUrl == null && pkarrUrl == null) add("--offline")
        }
        if (sidecarArgs.isNotEmpty()) put("sidecarArgs", sidecarArgs.joinToString(" "))
    }

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

    private companion object {
        const val SIDECAR_PROPERTY = "iroh.sidecar.binary"
    }
}
