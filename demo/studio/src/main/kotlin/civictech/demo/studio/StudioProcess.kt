package civictech.demo.studio

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Parse the template, never the substituted values. There is no shell expansion. */
internal fun tokenize(template: String): List<String> {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var started = false
    var i = 0
    while (i < template.length) {
        val c = template[i++]
        when {
            c == '\\' && quote != '\'' -> {
                if (i == template.length) bad("trailing backslash in command")
                val next = template[i++]
                if (next == '\n') continue
                // Inside double quotes, a backslash before an ordinary character is literal.
                if (quote == '"' && next !in listOf('"', '\\', '$', '`', '\n')) token.append('\\')
                token.append(next)
                started = true
            }
            quote != null -> if (c == quote) quote = null else token.append(c)
            c == '\'' || c == '"' -> { quote = c; started = true }
            c.isWhitespace() -> if (started) { result += token.toString(); token.clear(); started = false }
            else -> { token.append(c); started = true }
        }
    }
    if (quote != null) bad("unclosed quote in command")
    if (started) result += token.toString()
    if (result.isEmpty() || result[0].isBlank()) bad("command must not be empty")
    return result
}

internal fun substitute(template: String, inputs: Map<String, JsonObject>): String = placeholder.replace(template) {
    val value = inputs[it.groupValues[1]]?.get(it.groupValues[2].ifEmpty { "new" })
    if (value == null || value == JsonNull) "" else (value as? JsonPrimitive)?.content ?: value.toString()
}

internal fun claudeArgv(config: JsonObject, prompt: String, noTools: Boolean = false): List<String> =
    listOf("claude", "-p", "--output-format", "json", "--safe-mode", "--restricted",
        "--disable-slash-commands", "--strict-mcp-config", "--permission-prompts", "none",
        "--no-session-persistence", "--max-turns", config.long("maxTurns", 10).toString(),
        "--max-budget-usd", (config["maxBudgetUsd"] as? JsonPrimitive)?.doubleOrNull?.toString() ?: "1.0",
        "--tools", if (noTools || config.string("tools", "readonly") == "none") "" else "Read,Grep,Glob") +
        (config.string("model").takeIf { it.isNotBlank() }?.let { listOf("--model", it) } ?: emptyList()) +
        listOf("--", prompt)

private val codexDisabled = listOf("multi_agent", "apps", "view_image", "image_generation", "browser_use", "computer_use", "in_app_browser")
internal fun codexArgv(config: JsonObject, prompt: String, out: Path, noTools: Boolean = false): List<String> =
    listOf("codex", "exec", "--skip-git-repo-check", "--ephemeral", "--ignore-rules",
        "-s", "read-only", "-c", "web_search=\"disabled\"",
        "-c", "model_reasoning_effort=\"${config.string("effort", "medium")}\"") +
        (codexDisabled + if (noTools) listOf("shell_tool", "unified_exec") else emptyList()).flatMap { listOf("--disable", it) } +
        (config.string("model").takeIf { it.isNotBlank() }?.let { listOf("-m", it) } ?: emptyList()) +
        listOf("-o", out.toAbsolutePath().toString(), "--", prompt)

internal fun sha256(code: String) = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun extractPython(answer: String): String = Regex("```python[^\n]*\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    .find(answer)?.groupValues?.get(1) ?: answer

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val error: String?)

/** Cancellation closes the launch race as well as killing an already-started child. */
internal class ProcessFence {
    private var cancelled = false
    private var process: Process? = null
    @Synchronized fun start(builder: ProcessBuilder): Process {
        check(!cancelled) { "run fenced" }
        return builder.start().also { process = it }
    }
    @Synchronized fun cancel() { cancelled = true; process?.let(::destroyTree) }
}

internal class PythonSandbox(private val binary: Path = Path.of("/usr/bin/sandbox-exec")) {
    fun command(work: Path, profile: Path): List<String> {
        check(Files.isExecutable(binary)) { "sandbox-exec unavailable: $binary; refusing to run programmable Python" }
        return listOf(binary.toString(), "-f", profile.toAbsolutePath().toString(), "-D", "WORK=${work.toRealPath()}", "python3", "cell.py")
    }
    companion object {
        const val PROFILE = "(version 1)(allow default)(deny network*)(deny file-write*)(allow file-write* (subpath (param \"WORK\")) (literal \"/dev/null\"))"
    }
}

internal class StudioProcesses : AutoCloseable {
    internal val active = ConcurrentHashMap.newKeySet<Process>()
    @Volatile private var closed = false

    /** All streams are files; a child cannot deadlock by filling a pipe. */
    fun run(argv: List<String>, cwd: Path, dir: Path, timeoutSec: Long, stdin: String? = null, environment: Map<String, String> = emptyMap(), fence: ProcessFence = ProcessFence()): ProcessResult {
        Files.createDirectories(cwd)
        Files.createDirectories(dir)
        val stdout = dir.resolve("stdout.txt")
        val stderr = dir.resolve("stderr.txt")
        Files.writeString(stdout, "")
        Files.writeString(stderr, "")
        var p: Process? = null
        var error: String? = null
        var exit = -1
        try {
            check(!closed) { "studio closed" }
            // A file also bounds the wait when a child never reads its stdin.
            val input = dir.resolve("stdin.json")
            Files.writeString(input, stdin ?: "")
            val builder = ProcessBuilder(argv).directory(cwd.toFile()).redirectInput(input.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
            builder.environment().putAll(environment)
            p = fence.start(builder)
            active += p
            if (closed) { destroyTree(p); error = "studio closed" }
            else if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                destroyTree(p)
                error = "timed out after $timeoutSec seconds"
            }
            exit = if (p.isAlive) -1 else p.exitValue()
        } catch (e: InterruptedException) {
            p?.let(::destroyTree)
            Thread.currentThread().interrupt()
            error = "interrupted"
        } catch (e: Exception) {
            p?.let(::destroyTree)
            error = e.message ?: e.javaClass.simpleName
        } finally { p?.let { active -= it } }
        val out = Files.readString(stdout)
        val err = Files.readString(stderr)
        return ProcessResult(exit, out, err, error ?: if (exit != 0) err.takeLast(2000).ifBlank { "process exited $exit" } else null)
    }

    override fun close() { closed = true; active.forEach(::destroyTree) }
}

/** Same bounded, two-snapshot cleanup as deliberate's CLI runner. */
internal fun destroyTree(process: Process) {
    fun snapshot() = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    var children = snapshot()
    children.asReversed().forEach { it.destroyForcibly() }
    try {
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            children = (children + snapshot()).distinctBy { it.pid() }
            children.asReversed().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        children.asReversed().forEach {
            if (it.isAlive) it.destroyForcibly()
            runCatching { it.onExit().get(5, TimeUnit.SECONDS) }
        }
    } catch (_: InterruptedException) { process.destroyForcibly(); Thread.currentThread().interrupt() }
}

internal fun atomicWrite(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val tmp = Files.createTempFile(path.parent, ".${path.fileName}", ".tmp")
    try {
        Files.writeString(tmp, text)
        try { Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(tmp) }
}
