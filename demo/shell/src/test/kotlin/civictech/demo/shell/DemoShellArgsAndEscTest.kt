package civictech.demo.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T12 finding 5: [esc]'s edge cases — the tiering/skillmatch hand-rolled
 * escaper only covered backslash and quote, silently mis-encoding control
 * characters and (as bare text, not `\n`) newlines. This pins the fixed,
 * `JsonPrimitive`-backed form. [value]/[flag] get a companion smoke test
 * since they moved here too.
 */
class DemoShellArgsAndEscTest {

    @Test
    fun `esc quotes and escapes backslashes`() {
        assertEquals("\"plain\"", esc("plain"))
        assertEquals("\"a\\\"b\"", esc("a\"b"))
        assertEquals("\"a\\\\b\"", esc("a\\b"))
    }

    @Test
    fun `esc escapes newlines and control characters the hand-rolled version missed`() {
        // the old `esc` (backslash + quote only) would have emitted a literal
        // newline byte inside the JSON string, corrupting the frame
        assertEquals("\"a\\nb\"", esc("a\nb"))
        assertEquals("\"a\\tb\"", esc("a\tb"))
        assertEquals("\"a\\u0001b\"", esc("ab"))
    }

    @Test
    fun `value finds the argument after a flag`() {
        val args = arrayOf("--journal", "/tmp/x", "--seed", "/tmp/seeds")
        assertEquals("/tmp/x", args.value("--journal"))
        assertEquals("/tmp/seeds", args.flag("--seed"))
    }

    @Test
    fun `value is null when the flag is absent or trailing`() {
        assertNull(arrayOf<String>().value("--journal"))
        assertNull(arrayOf("--journal").value("--journal")) // no value follows
        assertNull(arrayOf("--other", "x").flag("--journal"))
    }
}
