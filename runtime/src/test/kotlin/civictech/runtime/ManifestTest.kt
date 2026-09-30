package civictech.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManifestTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `manifest round-trips through JSON and loads from a file`() {
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
                    budget = "policy.json",
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
