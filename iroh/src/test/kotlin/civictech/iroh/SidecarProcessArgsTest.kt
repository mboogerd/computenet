package civictech.iroh

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pure-function coverage of `SidecarProcess.effectiveArgs` — the
 * `iroh.relay.url` steering `spawn` applies before starting the child process.
 * No sidecar, no process, no `-Piroh.enabled`: runs on every lane, flag or no
 * flag (computenet-o0m3.3).
 */
class SidecarProcessArgsTest {

    private val propertyKey = "iroh.relay.url"
    private var savedProperty: String? = null

    @BeforeEach
    fun saveProperty() {
        savedProperty = System.getProperty(propertyKey)
        System.clearProperty(propertyKey)
    }

    @AfterEach
    fun restoreProperty() {
        if (savedProperty == null) {
            System.clearProperty(propertyKey)
        } else {
            System.setProperty(propertyKey, savedProperty)
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
}
