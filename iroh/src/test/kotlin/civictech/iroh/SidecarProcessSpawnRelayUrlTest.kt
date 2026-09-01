package civictech.iroh

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * The seam `SidecarProcessArgsTest` cannot reach: that `spawn` actually
 * *applies* `effectiveArgs` to the argv it hands the child, and reads the
 * `iroh.relay.url` property to do it.
 *
 * Without this, `effectiveArgs` could be a correct pure function that `spawn`
 * never calls — and every existing test, local and CI, would stay green.
 * Nothing else covers it: the `iroh-sidecar` lane's relay-liveness assert
 * (`computenet-o0m3.4`) proves the job-local relay process survived, not that
 * any JVM-spawned sidecar was pointed at it, and the suites' peers hold direct
 * loopback addresses via `ADD_PEER` so they link up regardless. That would
 * leave the feature's claim — CI reaches no n0 relay/discovery infrastructure
 * — resting on reading the source (computenet-o0m3, feature review).
 *
 * The child here is a stub script, not the sidecar: it records its argv and
 * writes a `PROTOCOL.md` §1 handshake line. So this runs on every lane, with
 * or without `-Piroh.enabled`, and needs no Rust toolchain.
 */
@DisabledOnOs(OS.WINDOWS)
class SidecarProcessSpawnRelayUrlTest {

    private val propertyKey = "iroh.relay.url"
    private val relayUrl = "http://127.0.0.1:49312"

    /** A port and a 64-hex nodeId, the shape `spawn` insists the child writes. */
    private val handshakeLine = """{"port":12345,"nodeId":"${"ab".repeat(32)}"}"""

    private var savedProperty: String? = null
    private lateinit var dir: Path
    private lateinit var argvFile: Path
    private lateinit var stub: Path

    @BeforeEach
    fun setUp() {
        savedProperty = System.getProperty(propertyKey)
        System.clearProperty(propertyKey)

        dir = Files.createTempDirectory("sidecar-spawn-argv")
        argvFile = dir.resolve("argv")
        stub = dir.resolve("stub-sidecar")
        stub.writeText(
            """
            #!/bin/sh
            : > '$argvFile'
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a" >> '$argvFile'; done
            printf '%s\n' '$handshakeLine'
            """.trimIndent() + "\n",
        )
        check(stub.toFile().setExecutable(true)) { "stub sidecar must be executable" }
    }

    @AfterEach
    fun tearDown() {
        if (savedProperty == null) System.clearProperty(propertyKey)
        else System.setProperty(propertyKey, savedProperty)
        dir.toFile().deleteRecursively()
    }

    /** The argv the stub child actually received, one argument per line. */
    private fun argvOf(args: List<String>): List<String> {
        SidecarProcess.spawn(stub, args = args).use { }
        return argvFile.readLines()
    }

    @Test
    fun `spawn appends --relay-url from the iroh_relay_url property`() {
        System.setProperty(propertyKey, relayUrl)
        val caller = listOf("--secret-key", "cd".repeat(32))

        // Literal expectation, not a re-computation of effectiveArgs: this is
        // the argv the sidecar binary must see for the CI lane's self-hosting
        // claim to hold.
        assertEquals(
            listOf("--secret-key", "cd".repeat(32), "--relay-url", relayUrl),
            argvOf(caller),
        )
    }

    @Test
    fun `spawn passes argv through untouched when the property is unset`() {
        val caller = listOf("--secret-key", "cd".repeat(32))
        assertEquals(caller, argvOf(caller))
    }

    @Test
    fun `spawn leaves an --offline caller alone even with the property set`() {
        System.setProperty(propertyKey, relayUrl)
        assertEquals(listOf("--offline"), argvOf(listOf("--offline")))
    }
}
