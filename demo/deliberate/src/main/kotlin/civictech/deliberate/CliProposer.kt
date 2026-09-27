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
                |Give at most $max new arguments $direction the claim. Each must be:
                |- a single declarative sentence that is self-contained and understandable without the claim or the question;
                |- a substantively new reason, not a rewording of any existing argument above;
                |- without numbering, labels or commentary.
                |
                |Output ONLY a JSON array of at most $max strings, e.g. ["First argument.", "Second argument."]. No other text.
            """.trimMargin()
        }

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
