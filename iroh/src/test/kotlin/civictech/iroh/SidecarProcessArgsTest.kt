package civictech.iroh

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure-function coverage of `SidecarProcess.effectiveArgs` — the
 * `iroh.relay.url` and `iroh.pkarr.url`/`iroh.dns.origin`/`iroh.dns.nameserver`
 * steering `spawn` applies before starting the child process (computenet-vnscs
 * F2-D5/F2-D8). No sidecar, no process, no `-Piroh.enabled`: runs on every
 * lane, flag or no flag (computenet-o0m3.3).
 */
class SidecarProcessArgsTest {

    private val propertyKey = "iroh.relay.url"
    private val pkarrUrlKey = "iroh.pkarr.url"
    private val dnsOriginKey = "iroh.dns.origin"
    private val dnsNameserverKey = "iroh.dns.nameserver"
    private val propertyKeys = listOf(propertyKey, pkarrUrlKey, dnsOriginKey, dnsNameserverKey)
    private var savedProperties: Map<String, String?> = emptyMap()

    @BeforeEach
    fun saveProperty() {
        savedProperties = propertyKeys.associateWith { System.getProperty(it) }
        propertyKeys.forEach { System.clearProperty(it) }
    }

    @AfterEach
    fun restoreProperty() {
        propertyKeys.forEach { key ->
            val saved = savedProperties[key]
            if (saved == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, saved)
            }
        }
    }

    @Test
    fun `property unset leaves args unchanged`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        assertEquals(args, SidecarProcess.effectiveArgs(args, relayUrl = null))
    }

    @Test
    fun `property set appends relay-url`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        val expected = args + listOf("--relay-url", "https://relay.example")
        assertEquals(expected, SidecarProcess.effectiveArgs(args, relayUrl = "https://relay.example"))
    }

    @Test
    fun `property set but caller already passed --offline does not append`() {
        val args = listOf("--offline")
        assertEquals(args, SidecarProcess.effectiveArgs(args, relayUrl = "https://relay.example"))
    }

    @Test
    fun `property set but caller already passed --relay-url does not append`() {
        val args = listOf("--relay-url", "https://explicit.example")
        assertEquals(args, SidecarProcess.effectiveArgs(args, relayUrl = "https://relay.example"))
    }

    @Test
    fun `empty args with property unset stays empty`() {
        assertEquals(emptyList(), SidecarProcess.effectiveArgs(emptyList(), relayUrl = null))
    }

    // F1-D7: --mdns composes with everything except --offline+--relay-url
    // exclusivity, and effectiveArgs steers only on --offline/--relay-url — it
    // does not know about --mdns at all, so --mdns never changes the outcome.

    @Test
    fun `mdns with property set still appends relay-url`() {
        val args = listOf("--mdns")
        val expected = args + listOf("--relay-url", "https://relay.example")
        assertEquals(expected, SidecarProcess.effectiveArgs(args, relayUrl = "https://relay.example"))
    }

    @Test
    fun `offline and mdns with property set does not append`() {
        val args = listOf("--offline", "--mdns")
        assertEquals(args, SidecarProcess.effectiveArgs(args, relayUrl = "https://relay.example"))
    }

    @Test
    fun `mdns with property unset stays unchanged`() {
        val args = listOf("--mdns")
        assertEquals(args, SidecarProcess.effectiveArgs(args, relayUrl = null))
    }

    // F2-D5/F2-D8: --pkarr-relay-url/--dns-origin/--dns-nameserver steering.

    @Test
    fun `pair unset leaves args unchanged`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        assertEquals(args, SidecarProcess.effectiveArgs(args, relayUrl = null))
    }

    @Test
    fun `pair unset leaves args unchanged with relayUrl set`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        val expected = args + listOf("--relay-url", "https://relay.example")
        assertEquals(
            expected,
            SidecarProcess.effectiveArgs(args, relayUrl = "https://relay.example"),
        )
    }

    @Test
    fun `pair set appends pkarr-relay-url and dns-origin`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        val expected = args + listOf(
            "--pkarr-relay-url", "http://127.0.0.1:1/pkarr",
            "--dns-origin", "irohdns.example.",
        )
        assertEquals(
            expected,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `pair and nameserver set append all three in order`() {
        val args = emptyList<String>()
        val expected = listOf(
            "--pkarr-relay-url", "http://127.0.0.1:1/pkarr",
            "--dns-origin", "irohdns.example.",
            "--dns-nameserver", "127.0.0.1:1",
        )
        assertEquals(
            expected,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
                dnsNameserver = "127.0.0.1:1",
            ),
        )
    }

    @Test
    fun `nameserver without the pair is ignored`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        assertEquals(
            args,
            SidecarProcess.effectiveArgs(args, relayUrl = null, dnsNameserver = "127.0.0.1:1"),
        )
    }

    @Test
    fun `only pkarr url set throws naming both properties`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            SidecarProcess.effectiveArgs(
                emptyList(),
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
            )
        }
        assertTrue(exception.message?.contains("iroh.pkarr.url") == true)
        assertTrue(exception.message?.contains("iroh.dns.origin") == true)
    }

    @Test
    fun `only dns origin set throws naming both properties`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            SidecarProcess.effectiveArgs(
                emptyList(),
                relayUrl = null,
                dnsOrigin = "irohdns.example.",
            )
        }
        assertTrue(exception.message?.contains("iroh.pkarr.url") == true)
        assertTrue(exception.message?.contains("iroh.dns.origin") == true)
    }

    @Test
    fun `pair set but caller passed --offline does not append`() {
        val args = listOf("--offline")
        assertEquals(
            args,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `pair set but caller passed --pkarr-relay-url does not append`() {
        val args = listOf("--pkarr-relay-url", "http://explicit.example/pkarr")
        assertEquals(
            args,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `pair set but caller passed --dns-origin does not append`() {
        val args = listOf("--dns-origin", "explicit.example.")
        assertEquals(
            args,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `pair set but caller passed --dns-nameserver does not append`() {
        val args = listOf("--dns-nameserver", "127.0.0.1:2")
        assertEquals(
            args,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `relay and pair both set append relay first then the pair`() {
        val args = listOf("--secret-key", "ab".repeat(32))
        val expected = args + listOf("--relay-url", "https://relay.example") + listOf(
            "--pkarr-relay-url", "http://127.0.0.1:1/pkarr",
            "--dns-origin", "irohdns.example.",
        )
        assertEquals(
            expected,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = "https://relay.example",
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `pair set with an explicit --relay-url in args still appends the pair`() {
        val args = listOf("--relay-url", "https://explicit.example")
        val expected = args + listOf(
            "--pkarr-relay-url", "http://127.0.0.1:1/pkarr",
            "--dns-origin", "irohdns.example.",
        )
        assertEquals(
            expected,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }

    @Test
    fun `mdns with the pair set still appends`() {
        val args = listOf("--mdns")
        val expected = args + listOf(
            "--pkarr-relay-url", "http://127.0.0.1:1/pkarr",
            "--dns-origin", "irohdns.example.",
        )
        assertEquals(
            expected,
            SidecarProcess.effectiveArgs(
                args,
                relayUrl = null,
                pkarrUrl = "http://127.0.0.1:1/pkarr",
                dnsOrigin = "irohdns.example.",
            ),
        )
    }
}
