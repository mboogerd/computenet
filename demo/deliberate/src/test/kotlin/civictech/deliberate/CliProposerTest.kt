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
        assertTrue(claude.hasPair("--output-format", "json"), "SPEC §12: usage comes from the JSON envelope")

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
        // --ignore-user-config drops the user's model choice; unpinned, codex 0.155 fell back to
        // gpt-6-astra, which rejects effort "none" and is not what the cost rates assume.
        val unpinned = CliProposer.codex(ProcessGate(1)).commandLine("prompt", out)
        assertTrue(unpinned.hasPair("-m", Pricing.DEFAULT_CODEX_MODEL))
        assertTrue(codex.hasPair("-o", out.absolutePath))
        assertTrue("--json" in codex, "SPEC §12: usage comes from the JSONL event stream")
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
    fun `timeout still kills a grandchild missed by the first descendant snapshot`() {
        // Deterministic reproduction of computenet-lmpn3's mechanism: destroyTree
        // takes a snapshot of descendants BEFORE giving the timed-out process up to a
        // second of grace to exit naturally (so a killed child does not linger as an
        // observable zombie). Under host load, a shell that has not yet forked (or
        // recorded) its background job is invisible to that first snapshot; the
        // grace-period wait then gives it time to spawn while nothing is watching,
        // and — pre-fix — it is never looked at again, so it outlives its parent as
        // an orphan. This test forces exactly that shape via a call-counting
        // descendantsOf, so the race is exercised on every run regardless of host
        // load or scheduling luck, rather than only sometimes under contention.
        val pidFile = Files.createTempFile("deliberate-pid", ".txt").toFile()
        try {
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val proposer = CliProposer(
                "slow",
                { _, _ -> listOf("sh", "-c", "pwd > '${pidFile.absolutePath}'; sleep 60 & echo \$! >> '${pidFile.absolutePath}'; wait") },
                ProcessGate(1),
                timeout = Duration.ofMillis(200),
                descendantsOf = {
                    // First call (destroyTree's pre-grace-period snapshot): behave as
                    // though the grandchild had not been recorded yet. Every later call
                    // (only the fix makes one) sees the real, by-then-recorded pid.
                    if (calls.getAndIncrement() == 0) {
                        emptyList()
                    } else {
                        val lines = pidFile.readLines()
                        if (lines.size < 2) emptyList() else listOf(ProcessHandle.of(lines[1].toLong()).orElseThrow())
                    }
                },
            )
            val error = assertFailsWith<IllegalStateException> { proposer.run("p") }
            assertTrue("timed out" in error.message!!)

            val lines = pidFile.readLines()
            assertTrue(lines.size >= 2, "grandchild pid was never recorded: $lines")
            val childPid = lines[1].toLong()

            // A pid ProcessHandle.of no longer finds has already exited (and been
            // reaped): that counts as killed, not as a lookup failure.
            fun alive() = ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (alive() && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(alive(), "grandchild missed by the first descendant snapshot must still be killed")
            assertTrue(calls.get() >= 2, "fix must re-snapshot descendants after the kill grace period")
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
        assertTrue("ONE proposition" in p && "EXPLICIT SUBJECT" in p && "at most 25 words" in p, p)
    }

    @Test
    fun `proposal prompt requires the canonical claim form`() {
        val p = CliProposer.prompt(
            ClaimContext("Should cities ban cars?", emptyList(), "Cities should ban cars.", emptyList(), emptyList()),
            Polarity.SUPPORT,
            1,
        )
        assertTrue("ONE proposition" in p, p)
        assertTrue("State the REASON, not its bearing on the claim" in p, p)
        assertTrue("EXPLICIT SUBJECT" in p && "EXPLICIT SCOPE" in p && "NO HEDGES" in p, p)
        assertTrue("DATED ONLY WHEN IT MATTERS" in p && "never invent studies" in p && "at most 25 words" in p, p)
        assertTrue("bad → canonical" in p, p)
    }

    @Test
    fun `a link prompt asks about the connection, not the truth of either end`() {
        val ctx = ClaimContext(
            "Should the pool open earlier?", listOf("Should the pool open earlier?"),
            "“Swimmers queue at 7am.” is a reason for “The pool should open earlier.”",
            listOf("Queues show unmet demand."), emptyList(),
            LinkContext("Swimmers queue at 7am.", "The pool should open earlier.", Polarity.SUPPORT),
        )
        val holds = CliProposer.prompt(ctx, Polarity.SUPPORT, 1)
        val fails = CliProposer.prompt(ctx, Polarity.ATTACK, 2)
        for (p in listOf(holds, fails)) {
            assertTrue("Swimmers queue at 7am." in p && "The pool should open earlier." in p, p)
            assertTrue("not whether either is true" in p && "do not dispute it" in p, p)
            assertTrue("Queues show unmet demand." in p && "ONE proposition" in p && "JSON array" in p, p)
        }
        assertTrue("why the connection HOLDS" in holds && "a reason FOR" in holds, holds)
        assertTrue("why the connection FAILS" in fails && "at most 2" in fails, fails)
        assertTrue("even if the argument is true" in fails && "does not bear on the claim" in fails, fails)
        assertTrue("undercuts that link" in fails && "counter-argument, not an undercutter" in fails, fails)
        assertTrue("reason it points the other way" !in fails, fails)
    }

    @Test
    fun `proposer and merger prompts carry no topic from the demo runs`() {
        // The examples teach the form only: no real person, political figure or contested topic
        // may leak from the prompt into a deliberation (computenet-dq2fy.12).
        val prompts = listOf(
            CliProposer.prompt(ClaimContext("Q?", emptyList(), "C.", emptyList(), emptyList()), Polarity.SUPPORT, 1),
            CliProposer.prompt(ClaimContext("Q?", listOf("Q?"), "C.", listOf("A."), listOf("B.")), Polarity.ATTACK, 2),
            CliMerger.prompt("C.", Polarity.ATTACK, "A.", "B."),
            CliProposer.prompt(
                ClaimContext("Q?", listOf("Q?"), "“A.” is a reason for “Q?”", emptyList(), emptyList(), LinkContext("A.", "Q?", Polarity.SUPPORT)),
                Polarity.ATTACK, 1,
            ),
        )
        val deny = Regex(
            "\\b(trump|donald|god|gods|divine|religio\\w*|mystical|collagen|cars?|pedestrian\\w*|animals?|language|" +
                "coffee|diabetes|elections?|president\\w*|politic\\w*|paris|madrid|oslo|northfield|librar\\w*|" +
                "homework|four-day|sundays?|junior engineers?)\\b",
            RegexOption.IGNORE_CASE,
        )
        for (p in prompts) {
            val hits = deny.findAll(p).map { it.value }.toList()
            assertTrue(hits.isEmpty(), "prompt mentions demo-run topics $hits")
        }
    }

    @Test
    fun `merger runs the CLI and parses its answer`() {
        val cli = CliProposer("claude", { prompt, _ ->
            listOf("sh", "-c", "case \"\$1\" in *'Argument B'*) echo '\"A and B.\"';; *) exit 4;; esac", "sh", prompt)
        }, ProcessGate(1))
        assertEquals("A and B.", CliMerger(cli).merge("C.", Polarity.SUPPORT, "A.", "B."))
    }

    // ------------------------------------------------------------ SPEC §12 usage

    /** A recorded `claude -p --output-format json` envelope (shape verified live, 2026-09-27). */
    private val claudeEnvelope = """
        {"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0323,
         "usage":{"input_tokens":2,"cache_creation_input_tokens":7902,"cache_read_input_tokens":3397,"output_tokens":40,
                  "output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0}},
         "modelUsage":{"claude-sonnet-5":{"inputTokens":2,"outputTokens":40,"costUSD":0.0323}},
         "permission_denials":[],
         "result":"Here you go:\n```json\n[\"The Model K2 kettle boils water in three minutes.\", \"Second.\"]\n```"}
    """.trimIndent()

    /** A recorded `codex exec --json` stream, with a second turn added to exercise summing. */
    private val codexEvents = """
        {"type":"thread.started","thread_id":"t"}
        {"type":"turn.started"}
        {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"[\"from stream\"]"}}
        {"type":"turn.completed","usage":{"input_tokens":12608,"cached_input_tokens":8448,"cache_write_input_tokens":0,"output_tokens":20,"reasoning_output_tokens":13}}
        not json at all
        {"type":"turn.completed","usage":{"input_tokens":300000,"cached_input_tokens":1000,"cache_write_input_tokens":500,"output_tokens":7,"reasoning_output_tokens":0}}
    """.trimIndent()

    private fun <T> recording(block: () -> T): Pair<T, List<CallUsage>> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<CallUsage>()
        return Usage.within({ seen += it }, block) to seen
    }

    @Test
    fun `claude JSON envelope yields the result text for the parser and the call's usage`() {
        val (text, usage) = recording { CliProposer.CLAUDE_JSON.read(claudeEnvelope, null) }
        assertEquals(
            listOf("The Model K2 kettle boils water in three minutes.", "Second."),
            CliProposer.parseArguments(text, 5),
            "the envelope's own arrays (permission_denials) must never be read as the answer",
        )
        val u = usage.single()
        assertEquals("claude", u.backend)
        assertEquals(listOf("claude-sonnet-5"), u.models)
        assertEquals(2L + 3397 + 7902, u.inputTokens)
        assertEquals(3397L, u.cachedInputTokens)
        assertEquals(7902L, u.cacheWriteTokens)
        assertEquals(40L, u.outputTokens)
        assertEquals(0.0323, u.reportedUsd)
    }

    @Test
    fun `a claude error envelope fails the call, plain text passes through, bad usage never fails it`() {
        assertFailsWith<IllegalStateException> {
            CliProposer.CLAUDE_JSON.read("""{"type":"result","is_error":true,"subtype":"error_max_turns","result":"nope"}""", null)
        }
        val (plain, none) = recording { CliProposer.CLAUDE_JSON.read("[\"a\"]", null) }
        assertEquals("[\"a\"]", plain)
        assertTrue(none.isEmpty())
        val (text, noUsage) = recording { CliProposer.CLAUDE_JSON.read("""{"is_error":false,"result":"[\"b\"]","usage":"garbled"}""", null) }
        assertEquals("[\"b\"]", text)
        assertTrue(noUsage.isEmpty())
    }

    @Test
    fun `claude diagnostics around its envelope cannot expose envelope arrays to argument parsing`() {
        val noisy = "diagnostic: credential helper was slow\n" + claudeEnvelope.replace("\n", " ") + "\ndiagnostic: done"
        val (text, usage) = recording { CliProposer.CLAUDE_JSON.read(noisy, null) }
        assertEquals(
            listOf("The Model K2 kettle boils water in three minutes.", "Second."),
            CliProposer.parseArguments(text, 5),
        )
        assertEquals(0.0323, usage.single().reportedUsd)
    }

    @Test
    fun `codex usage sums every completed turn and the answer comes from the out file`() {
        val u = assertNotNull(CliProposer.codexUsage(codexEvents, "gpt-5.6-sol"))
        assertEquals(CallUsage("codex", listOf("gpt-5.6-sol"), 312_608, 9_448, 500, 27, 13, longestPromptTokens = 300_000), u)
        assertEquals(null, CliProposer.codexUsage("{\"type\":\"turn.started\"}", "m"))
        val reader = CliProposer.codexReader("gpt-5.6-sol")
        val (fromFile, usage) = recording { reader.read(codexEvents, "[\"from file\"]") }
        assertEquals("[\"from file\"]", fromFile)
        assertEquals(listOf(u), usage)
        val (fromStream, _) = recording { reader.read(codexEvents, null) }
        assertEquals("[\"from stream\"]", fromStream)
    }

    @Test
    fun `a CLI run reports its usage to the bound sink`() {
        val envelope = claudeEnvelope.replace("\n", " ")
        val cli = CliProposer("claude", { _, _ -> listOf("sh", "-c", "printf '%s' \"\$1\"", "sh", envelope) }, ProcessGate(1), reader = CliProposer.CLAUDE_JSON)
        val ctx = ClaimContext("Q?", emptyList(), "Q?", emptyList(), emptyList())
        val (args, usage) = recording { cli.propose(ctx, Polarity.SUPPORT, 1) }
        assertEquals(listOf("The Model K2 kettle boils water in three minutes."), args)
        assertEquals(0.0323, usage.single().reportedUsd)
    }
}
