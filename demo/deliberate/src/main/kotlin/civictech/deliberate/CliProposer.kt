package civictech.deliberate

import civictech.agora.cell.Polarity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Bounds concurrent CLI processes app-wide (EXP-07); share one instance. Fair, so no caller starves. */
class ProcessGate(maxProcesses: Int = Options.DEFAULT_MAX_PROCESSES) {
    init {
        require(maxProcesses > 0) { "maxProcesses must be positive" }
    }

    private val permits = Semaphore(maxProcesses, true)

    fun <T> run(block: () -> T): T {
        permits.acquire()
        try {
            return block()
        } finally {
            permits.release()
        }
    }
}

/**
 * EXP-03 MERGE through the same CLI machinery as [CliProposer] (process gate,
 * empty temp dir, no tools, timeout): the CLI rewrites two overlapping
 * arguments as one sentence, answered as a JSON string ([parseSentence]).
 */
class CliMerger(private val cli: CliProposer) : Merger {
    override fun merge(claim: String, side: Side, a: String, b: String): String = parseSentence(cli.run(prompt(claim, side, a, b)))

    companion object {
        fun prompt(claim: String, side: Side, a: String, b: String): String {
            val direction = if (side == Polarity.SUPPORT) "FOR (supporting)" else "AGAINST (attacking)"
            return """
                |You are helping map a deliberation. The two arguments below, both $direction the claim, make
                |overlapping points. Rewrite them as ONE argument.
                |
                |Claim:
                |  $claim
                |
                |Argument A:
                |  $a
                |
                |Argument B:
                |  $b
                |
                |Write a single argument that states the shared point once, keeps what each argument adds, and adds
                |nothing new. No numbering, labels or commentary.
                |
                |${CliProposer.CANONICAL_RULES}
                |
                |Output ONLY that sentence as one JSON string, e.g. "The merged argument.". No other text.
            """.trimMargin()
        }

        /**
         * The first non-blank JSON string literal in [text] (surrounding prose,
         * code fences or an enclosing array are tolerated), trimmed. Throws
         * [IllegalArgumentException] when there is none.
         */
        fun parseSentence(text: String): String {
            var start = text.indexOf('"')
            while (start >= 0) {
                val end = closingQuote(text, start) ?: break
                val s = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
                if (s is JsonPrimitive && s.isString && s.content.isNotBlank()) return s.content.trim()
                // Resume after this literal, so the text between two literals is never read as one.
                start = text.indexOf('"', end + 1)
            }
            throw IllegalArgumentException("no JSON string in output: ${text.take(300)}")
        }

        private fun closingQuote(text: String, start: Int): Int? {
            var i = start + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i++
                    '"' -> return i
                }
                i++
            }
            return null
        }
    }
}

/**
 * [Proposer] that shells out to an LLM CLI (EXP-09). Each call runs [command]'s
 * argv inside the [gate], in a fresh empty temp directory (deleted afterwards),
 * with stdin closed and a [timeout] after which the whole process tree is
 * destroyed. The answer is [outFile][command]'s content when the command wrote
 * one, else stdout; it must contain a JSON array of strings ([parseArguments]).
 */
class CliProposer internal constructor(
    override val id: String,
    private val command: (prompt: String, outFile: File) -> List<String>,
    private val gate: ProcessGate,
    private val timeout: Duration = Duration.ofSeconds(120),
    private val descendantsOf: (Process) -> List<ProcessHandle> = { it.descendants().toList() },
) : Proposer {

    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
    }

    override fun propose(ctx: ClaimContext, side: Polarity, max: Int): List<String> =
        parseArguments(run(prompt(ctx, side, max)), max)

    /** Runs the CLI once and returns its answer text; throws on non-zero exit or timeout. */
    internal fun run(prompt: String): String {
        val scratch = Files.createTempDirectory("deliberate-$id-").toFile()
        try {
            val work = File(scratch, "work").apply { mkdir() }
            val out = File(scratch, "answer.txt")
            val stdout = File(scratch, "stdout.txt")
            val stderr = File(scratch, "stderr.txt")
            val process = gate.run {
                val p = ProcessBuilder(commandLine(prompt, out))
                    .directory(work)
                    .redirectOutput(stdout)
                    .redirectError(stderr)
                    .start()
                try {
                    p.outputStream.close()
                    if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        destroyTree(p)
                        throw IllegalStateException("$id timed out after ${timeout.toMillis()} ms")
                    }
                    p
                } catch (e: InterruptedException) {
                    destroyTree(p)
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    if (p.isAlive) destroyTree(p)
                    throw e
                }
            }
            check(process.exitValue() == 0) {
                "$id exited ${process.exitValue()}: ${stderr.readText().takeLast(500)}"
            }
            return if (out.isFile && out.length() > 0) out.readText() else stdout.readText()
        } finally {
            scratch.deleteRecursively()
        }
    }

    internal fun commandLine(prompt: String, outFile: File): List<String> = command(prompt, outFile)

    private fun destroyTree(process: Process) {
        val descendants = try {
            descendantsOf(process)
        } catch (_: RuntimeException) {
            emptyList()
        }
        descendants.asReversed().forEach { it.destroyForcibly() }
        // Let the direct process reap terminated children before forcing it down;
        // otherwise a killed child can remain observable as a zombie.
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        /** Claude Code CLI in print mode with every tool disabled. */
        fun claude(gate: ProcessGate, model: String? = null) = CliProposer("claude", { prompt, _ ->
            // `--tools` is variadic: `--` stops it from swallowing the prompt as a tool name.
            listOf(
                "claude", "-p", "--output-format", "text", "--safe-mode", "--restricted",
                "--tools", "", "--disable-slash-commands", "--strict-mcp-config",
                "--permission-prompts", "none", "--no-session-persistence",
            ) +
                model.flag("--model") + listOf("--", prompt)
        }, gate)

        /** Codex CLI with local/hosted tools disabled; the last message goes to the out file (stdout carries logs). */
        fun codex(gate: ProcessGate, model: String? = null) = CliProposer("codex", { prompt, out ->
            listOf(
                "codex", "exec", "--skip-git-repo-check", "--ephemeral", "--ignore-user-config",
                "--ignore-rules", "--strict-config", "--disable", "shell_tool", "--disable", "unified_exec",
                "--disable", "multi_agent", "--disable", "apps",
                // codex 0.155 rejects `-c tools.view_image=…` under --strict-config; image
                // viewing/generation and browser/computer use are features there.
                "--disable", "view_image", "--disable", "image_generation", "--disable", "browser_use",
                "--disable", "computer_use", "--disable", "in_app_browser", "-s", "read-only",
                "-c", "web_search=\"disabled\"",
                "-c", "model_reasoning_effort=\"low\"",
            ) +
                model.flag("-m") + listOf("-o", out.absolutePath, "--", prompt)
        }, gate)

        private fun String?.flag(name: String) = if (this == null) emptyList() else listOf(name, this)

        fun prompt(ctx: ClaimContext, side: Polarity, max: Int): String {
            val direction = if (side == Polarity.SUPPORT) "FOR (supporting)" else "AGAINST (attacking)"
            fun bullets(items: List<String>) = if (items.isEmpty()) "  (none yet)" else items.joinToString("\n") { "  - $it" }
            val path = if (ctx.path.isEmpty()) "  (the claim is the question itself)"
            else ctx.path.mapIndexed { i, c -> "  ${i + 1}. $c" }.joinToString("\n")
            return """
                |You are helping map a deliberation. Propose new arguments $direction the claim below.
                |
                |Question under deliberation:
                |  ${ctx.question}
                |
                |Path of claims from the question down to this claim:
                |$path
                |
                |Claim:
                |  ${ctx.claim}
                |
                |Existing arguments for the claim:
                |${bullets(ctx.pros)}
                |
                |Existing arguments against the claim:
                |${bullets(ctx.cons)}
                |
                |Give at most $max new arguments $direction the claim, each a substantively new reason, not a rewording of an existing argument above.
                |
                |$CANONICAL_RULES
                |
                |$CANONICAL_EXAMPLES
                |
                |Output ONLY a JSON array of at most $max strings, e.g. ["First argument.", "Second argument."]. No numbering, labels or other text.
            """.trimMargin()
        }

        /**
         * The canonical form every argument is asked for (proposals and merges
         * alike): one checkable proposition, the reason rather than its bearing
         * on the claim — how strongly it bears is Jev's strength judgment.
         */
        val CANONICAL_RULES = """
            |Write each argument in canonical form, so that it can be judged true or false on its own:
            |1. ONE proposition: a single subject-predicate assertion. No second clause that draws a conclusion ("…, which shows…", "…, suggesting…", "…, so…", "…, making…", "because…"), and no list of separate reasons joined by "and".
            |2. State the REASON, not its bearing on the claim. Give the fact, finding or principle itself; never add that it supports, undermines, is evidence for, or is best explained by the claim's thesis. How strongly it bears on the claim is judged separately.
            |3. EXPLICIT SUBJECT: name every person, place, group and thing ("the Northfield public library", "adults over 65", "the Model K2 kettle"). No pronouns or references to things outside the sentence ("he", "this", "these trials", "such bans").
            |4. EXPLICIT SCOPE: say how many, how often, where, for whom ("most", "in at least five branches", "in trials of adults aged 40–65"). Do not overgeneralise ("all", "always", "never") unless that is literally true.
            |5. NO HEDGES: no "can", "may", "might", "could", "often", "tends to", "suggests", "some evidence". If uncertainty or frequency is the point, state it as a quantity or proportion ("in 3 of 11 trials", "in roughly a third of cases").
            |6. DATED ONLY WHEN IT MATTERS: for an event or a state that changes over time (office-holders, prices, opening hours, current rules, the latest research), name the year or period ("in the 2024 season", "as of 2025"). Do not date timeless facts.
            |7. ACCURATE AND CHECKABLE: prefer established, checkable facts; never invent studies, numbers or quotes. Give precise numbers or citations only when they are widely known. If unsure of a detail, drop the detail rather than guess.
            |8. SHORT: at most 25 words. Do not restate the claim's own wording.
        """.trimMargin()

        /**
         * Four examples of the form, about invented, mundane subjects only: they
         * teach the shape of a canonical argument and must not leak content into
         * any deliberation (no real person, political figure or contested topic).
         */
        val CANONICAL_EXAMPLES = """
            |Examples (bad → canonical; the subjects are invented and only show the form):
            |- "The new kettle is great because it boils fast, so everyone should buy one." → "The Model K2 kettle boils one litre of water in under three minutes in the manufacturer's published tests."  (one proposition: the reason, without the conclusion)
            |- "It rains a lot there, suggesting the harvest will suffer." → "The village of Eastmere recorded rain on more than half of the days in June 2024."  (explicit subject, scope as a quantity, dated because weather changes)
            |- "Reading to children may help them learn words." → "Pupils read to daily scored higher on vocabulary tests in 7 of the 9 classroom trials of the Northfield district review."  (no hedge: the frequency is stated as a proportion)
            |- "The library is popular and should stay open." → "The Northfield public library lent more than 40,000 books in 2023."  (the fact with its scope and year, not the recommendation)
        """.trimMargin()

        /**
         * The first well-formed JSON array of strings in [text] (surrounding prose and
         * code fences are tolerated), trimmed, blanks dropped, capped to [max].
         * Throws [IllegalArgumentException] when there is none.
         */
        fun parseArguments(text: String, max: Int): List<String> {
            for (start in text.indices) {
                if (text[start] != '[') continue
                val end = matchingBracket(text, start) ?: continue
                val array = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
                if (array !is JsonArray || !array.all { it is JsonPrimitive && it.isString }) continue
                return array.map { (it as JsonPrimitive).content.trim() }.filter { it.isNotEmpty() }.take(max)
            }
            throw IllegalArgumentException("no JSON array of strings in output: ${text.take(300)}")
        }

        /** Index of the `]` closing the `[` at [start], skipping brackets inside JSON strings. */
        private fun matchingBracket(text: String, start: Int): Int? {
            var depth = 0
            var inString = false
            var i = start
            while (i < text.length) {
                val c = text[i]
                when {
                    inString && c == '\\' -> i++
                    c == '"' -> inString = !inString
                    inString -> {}
                    c == '[' -> depth++
                    c == ']' -> if (--depth == 0) return i
                }
                i++
            }
            return null
        }
    }
}
