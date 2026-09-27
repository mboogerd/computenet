package civictech.deliberate

import civictech.agora.cell.Polarity
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliProposerTest {

    private fun parse(text: String, max: Int = 5) = CliProposer.parseArguments(text, max)

    @Test
    fun `bare array`() {
        assertEquals(listOf("A is cheap.", "B is fast."), parse("""["A is cheap.", "B is fast."]"""))
    }

    @Test
    fun `fenced array with prose around it`() {
        val text = """
            Here are two new arguments:

            ```json
            [
              "Remote work cuts commuting emissions.",
              "  Offices enable spontaneous collaboration.  "
            ]
            ```
            Let me know if you need more [or fewer].
        """.trimIndent()
        assertEquals(
            listOf("Remote work cuts commuting emissions.", "Offices enable spontaneous collaboration."),
            parse(text),
        )
    }

    @Test
    fun `nested quotes, escapes and brackets inside strings`() {
        val text = """Sure [see below]: ["He said \"no\" [twice].", "Path C:\\tmp is \u00e9crit.", "Tab\there"]"""
        assertEquals(listOf("He said \"no\" [twice].", "Path C:\\tmp is écrit.", "Tab\there"), parse(text))
    }

    @Test
    fun `skips non-string arrays and drops blanks`() {
        assertEquals(listOf("Real one."), parse("""refs [1, 2] then ["", "  ", "Real one."]"""))
    }

    @Test
    fun `over-long array is capped`() {
        assertEquals(listOf("a", "b"), parse("""["a","b","c","d"]""", max = 2))
    }

    @Test
    fun `garbage throws`() {
        assertFailsWith<IllegalArgumentException> { parse("I cannot help with that.") }
        assertFailsWith<IllegalArgumentException> { parse("""["unterminated", "array"""") }
        assertFailsWith<IllegalArgumentException> { parse("[1, 2, 3]") }
    }

    @Test
    fun `prompt carries context, side and cap`() {
        val ctx = ClaimContext("Should X?", listOf("Should X?"), "X is cheap.", listOf("pro one"), listOf("con one"))
        val prompt = CliProposer.prompt(ctx, Polarity.ATTACK, 3)
        listOf("Should X?", "X is cheap.", "pro one", "con one", "AGAINST", "at most 3").forEach {
            assertTrue(it in prompt, "prompt lacks '$it'")
        }
    }

    @Test
    fun `built-in proposer commands disable tool access`() {
        val out = File("answer.json").absoluteFile
        val claude = CliProposer.claude(ProcessGate(1), model = "claude-test").commandLine("prompt", out)
        assertTrue(claude.hasPair("--tools", ""))
        assertTrue("--safe-mode" in claude)
        assertTrue("--restricted" in claude)
        assertTrue("--disable-slash-commands" in claude)
        assertTrue("--strict-mcp-config" in claude)
        assertTrue(claude.hasPair("--permission-prompts", "none"))
        assertTrue(claude.hasPair("--model", "claude-test"))

        val codex = CliProposer.codex(ProcessGate(1), model = "codex-test").commandLine("prompt", out)
        assertTrue("--ignore-user-config" in codex)
        assertTrue("--ignore-rules" in codex)
        assertTrue("--strict-config" in codex)
        for (feature in listOf("shell_tool", "unified_exec", "multi_agent", "apps", "view_image", "image_generation", "browser_use", "computer_use", "in_app_browser")) {
            assertTrue(codex.hasPair("--disable", feature), "Codex command does not disable $feature")
        }
        assertTrue(codex.hasPair("-c", "web_search=\"disabled\""))
        assertFalse("tools.view_image=false" in codex, "codex 0.155 rejects this key under --strict-config")
        assertTrue(codex.hasPair("-s", "read-only"))
        assertTrue(codex.hasPair("-m", "codex-test"))
        assertTrue(codex.hasPair("-o", out.absolutePath))
    }

    @Test
    fun `runs in an empty temp dir, reads out file, and cleans up`() {
        val proposer = CliProposer("fake", { _, out ->
            listOf("sh", "-c", "ls -A | wc -l | tr -d ' ' > '${out.absolutePath}'; pwd")
        }, ProcessGate(1))
        assertEquals("0", proposer.run("p").trim())
        val echo = CliProposer("echo", { prompt, _ -> listOf("sh", "-c", "cat; pwd; printf '%s' \"\$1\"", "sh", prompt) }, ProcessGate(1))
        val lines = echo.run("[\"x\"]").lines()
        val seenDir = File(lines[0])
        assertEquals("[\"x\"]", lines[1], "stdin must be closed (cat reads nothing)")
        assertFalse(seenDir.exists(), "temp dir must be deleted")
    }

    @Test
    fun `non-zero exit fails`() {
        val proposer = CliProposer("fail", { _, _ -> listOf("sh", "-c", "echo boom >&2; exit 3") }, ProcessGate(1))
        val e = assertFailsWith<IllegalStateException> { proposer.run("p") }
        assertTrue("boom" in e.message!!)
    }

    @Test
    fun `timeout destroys the process tree`() {
        val pidFile = Files.createTempFile("deliberate-pid", ".txt").toFile()
        try {
            val proposer = CliProposer(
                "slow",
                { _, _ -> listOf("sh", "-c", "pwd > '${pidFile.absolutePath}'; sleep 60 & echo \$! >> '${pidFile.absolutePath}'; wait") },
                ProcessGate(1),
                timeout = Duration.ofMillis(200),
                descendantsOf = {
                    val childPid = pidFile.readLines()[1].toLong()
                    listOf(ProcessHandle.of(childPid).orElseThrow())
                },
            )
            val error = assertFailsWith<IllegalStateException> { proposer.run("p") }
            assertTrue("timed out" in error.message!!)
            val lines = pidFile.readLines()
            val workDir = File(lines[0])
            val child = ProcessHandle.of(lines[1].toLong())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(child.map { it.isAlive }.orElse(false), "grandchild sleep must be killed")
            assertFalse(workDir.exists(), "temp dir must be deleted after timeout")
        } finally {
            pidFile.delete()
        }
    }

    @Test
    fun `gate never exceeds its bound`() {
        val gate = ProcessGate(3)
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val futures = (1..48).map {
            pool.submit {
                start.await()
                gate.run {
                    peak.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(3, peak.get())
        assertEquals(0, inside.get())
    }

    @Test
    fun `gate validates its bound and releases a permit after failure`() {
        assertFailsWith<IllegalArgumentException> { ProcessGate(0) }
        val gate = ProcessGate(1)
        assertFailsWith<IllegalStateException> { gate.run { error("boom") } }
        assertNotNull(gate.run { Any() })
    }

    private fun List<String>.hasPair(first: String, second: String): Boolean =
        windowed(2).any { it[0] == first && it[1] == second }

    @Test
    fun `merge sentence parsing tolerates prose, fences and an array`() {
        assertEquals("One merged point.", CliMerger.parseSentence(""""One merged point.""""))
        assertEquals("He said \"no\".", CliMerger.parseSentence("Sure:\n```json\n\"He said \\\"no\\\".\"\n```"))
        assertEquals("In an array.", CliMerger.parseSentence("""["In an array."]"""))
        assertEquals("Real.", CliMerger.parseSentence(""""  " then "Real.""""))
        assertFailsWith<IllegalArgumentException> { CliMerger.parseSentence("no quotes at all") }
    }

    @Test
    fun `merge prompt names both arguments, the side and the output format`() {
        val p = CliMerger.prompt("Cities should ban cars.", Polarity.ATTACK, "First.", "Second.")
        assertTrue("AGAINST" in p && "Cities should ban cars." in p && "First." in p && "Second." in p, p)
        assertTrue("ONE argument" in p && "JSON string" in p, p)
    }

    @Test
    fun `merger runs the CLI and parses its answer`() {
        val cli = CliProposer("claude", { prompt, _ ->
            listOf("sh", "-c", "case \"\$1\" in *'Argument B'*) echo '\"A and B.\"';; *) exit 4;; esac", "sh", prompt)
        }, ProcessGate(1))
        assertEquals("A and B.", CliMerger(cli).merge("C.", Polarity.SUPPORT, "A.", "B."))
    }
}
