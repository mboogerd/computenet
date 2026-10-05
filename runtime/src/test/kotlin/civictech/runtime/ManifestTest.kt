package civictech.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ManifestTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `manifest round-trips through JSON and loads from a file`() {
        val policyFile = tempDir.resolve("policy.json")
        Files.writeString(policyFile, "{}")
        val manifest = Manifest(
            mapOf(
                "a" to NodeSpec(
                    hosts = listOf("main", "worker"),
                    transport = "ws",
                    transportConfig = mapOf("example" to "kept-as-data"),
                    listen = "ws://127.0.0.1:0",
                    journalDir = "run/a",
                    replica = 7,
                    peerName = "peer-a",
                    budget = policyFile.toFile().absolutePath,
                    journalTopology = true,
                ),
            ),
        )
        val encoded = Json.encodeToString(manifest)

        assertEquals(manifest, Manifest.parse(encoded))
        val file = tempDir.resolve("manifest.json")
        Files.writeString(file, encoded)
        assertEquals(manifest, Manifest.load(file.toFile()))
    }

    @Test
    fun `a relative budget path resolves against the manifest directory`() {
        Files.writeString(tempDir.resolve("policy.json"), "{}")
        val manifestFile = tempDir.resolve("manifest.json")
        Files.writeString(manifestFile, """{"nodes":{"a":{"budget":"policy.json"}}}""")

        val loaded = Manifest.load(manifestFile.toFile())

        assertEquals(
            File(tempDir.toFile(), "policy.json").path,
            loaded.nodes.getValue("a").budget,
        )
    }

    @Test
    fun `a budget naming a missing file is refused during parse`() {
        val failure = invalid(
            """{"nodes":{"a":{"budget":"definitely-missing-policy.json"}}}""",
        )
        assertViolation(failure, "nodes[a].budget", "not found")
    }

    @Test
    fun `a budget naming a missing file is refused during load`() {
        val manifestFile = tempDir.resolve("manifest.json")
        Files.writeString(manifestFile, """{"nodes":{"a":{"budget":"missing.json"}}}""")
        val failure = assertThrows<InvalidManifestException> {
            Manifest.load(manifestFile.toFile())
        }
        assertViolation(failure, "nodes[a].budget", "not found")
    }

    @Test
    fun `placements parse as selector to node entries and default to empty`() {
        val parsed = Manifest.parse(
            """{"nodes":{"a":{},"b":{}},"placements":{"source":"a","sink":"b"}}""",
        )

        assertEquals(mapOf("source" to "a", "sink" to "b"), parsed.placements)
        assertEquals(parsed, Manifest.parse(Json.encodeToString(parsed)))
        assertEquals(emptyMap<String, String>(), Manifest.parse("""{"nodes":{"a":{}}}""").placements)
    }

    @Test
    fun `a placement naming no node is refused`() {
        val failure = invalid(
            """{"nodes":{"a":{"hosts":[]}},"placements":{"source":"missing"}}""",
        )

        assertViolation(failure, "nodes[a].hosts", "at least one host")
        assertViolation(failure, "placements[source]", "unknown node 'missing'")
    }

    @Test
    fun `topology journalling with placements round-trips and still requires a journal directory`() {
        val parsed = Manifest.parse(
            """{"nodes":{"a":{"journalTopology":true,"journalDir":"run/a"}},"placements":{"source":"a"}}""",
        )

        assertEquals(true, parsed.nodes.getValue("a").journalTopology)
        assertEquals(mapOf("source" to "a"), parsed.placements)
        assertEquals(parsed, Manifest.parse(Json.encodeToString(parsed)))

        val failure = invalid(
            """{"nodes":{"a":{"journalTopology":true}},"placements":{"source":"a"}}""",
        )

        val topologyViolations = failure.violations.filter { it.field == "nodes[a].journalTopology" }
        assertEquals(1, topologyViolations.size, failure.message)
        assertTrue(topologyViolations.single().message.contains("requires journalDir"), failure.message)
    }

    @Test
    fun `a dial naming no node is refused`() {
        val failure = invalid(
            """{"nodes":{"a":{"listen":"ws://127.0.0.1:0","dial":["missing"]}}}""",
        )

        assertViolation(failure, "nodes[a].dial[0]", "unknown node 'missing'")
    }

    @Test
    fun `a dialled node without a listen address is refused`() {
        val failure = invalid(
            """{"nodes":{"a":{"listen":"ws://127.0.0.1:0","dial":["b"]},"b":{}}}""",
        )

        assertViolation(failure, "nodes[a].dial[0]", "node 'b' has no listen address")
    }

    @Test
    fun `an unknown transport scheme is refused`() {
        val failure = invalid("""{"nodes":{"a":{"transport":"carrier-pigeon"}}}""")

        assertViolation(failure, "nodes[a].transport", "unknown transport scheme 'carrier-pigeon'")
    }

    @Test
    fun `a node without hosts is refused`() {
        val failure = invalid("""{"nodes":{"a":{"hosts":[]}}}""")

        assertViolation(failure, "nodes[a].hosts", "at least one host")
    }

    @Test
    fun `topology journalling requires a journal directory and defaults off`() {
        val failure = invalid("""{"nodes":{"a":{"journalTopology":true}}}""")

        assertViolation(failure, "nodes[a].journalTopology", "requires journalDir")
        assertEquals(false, Manifest.parse("""{"nodes":{"a":{}}}""").nodes.getValue("a").journalTopology)
    }

    @Test
    fun `two nodes dialling each other are refused`() {
        val failure = invalid(
            """{"nodes":{"a":{"listen":"ws://127.0.0.1:1","dial":["b"]},"b":{"listen":"ws://127.0.0.1:2","dial":["a"]}}}""",
        )

        assertViolation(failure, "nodes[a].dial<->nodes[b].dial", "both dial each other")
    }

    @Test
    fun `validation reports every violation in one failure`() {
        val failure = invalid(
            """{"nodes":{"a":{"hosts":[],"transport":"nope","listen":"ws://127.0.0.1:1","dial":["missing","b"]},"b":{"dial":["a"]}}}""",
        )

        assertEquals(5, failure.violations.size, failure.message)
        assertTrue(failure.message!!.contains("nodes[a].hosts"), failure.message)
        assertTrue(failure.message!!.contains("nodes[a].transport"), failure.message)
        assertTrue(failure.message!!.contains("nodes[a].dial[0]"), failure.message)
        assertTrue(failure.message!!.contains("nodes[a].dial[1]"), failure.message)
        assertTrue(failure.message!!.contains("nodes[a].dial<->nodes[b].dial"), failure.message)
    }

    private fun invalid(json: String): InvalidManifestException =
        assertThrows { Manifest.parse(json) }

    private fun assertViolation(failure: InvalidManifestException, field: String, messagePart: String) {
        assertTrue(
            failure.violations.any { it.field == field && messagePart in it.message },
            "missing $field / '$messagePart' in ${failure.violations}",
        )
    }
}
