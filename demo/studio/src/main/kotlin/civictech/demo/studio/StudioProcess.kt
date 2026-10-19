package civictech.demo.studio

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
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

internal fun substitute(template: String, inputs: Map<String, JsonObject>): String = buildString {
    var offset = 0
    placeholders(template).forEach { p ->
        append(template.substring(offset, p.start))
        val value = inputs[p.port]?.get(p.field) ?: error("invalid input: ${p.port}: missing field '${p.field}'")
        append(if (value == JsonNull) "" else (value as? JsonPrimitive)?.content ?: value.toString())
        offset = p.end
    }
    append(template.substring(offset))
}

internal fun claudeArgv(config: JsonObject, noTools: Boolean = false): List<String> =
    listOf("claude", "-p", "--output-format", "json", "--safe-mode", "--restricted",
        "--disable-slash-commands", "--strict-mcp-config", "--permission-prompts", "none",
        "--no-session-persistence", "--max-turns", config.long("maxTurns", 10).toString(),
        "--max-budget-usd", (config["maxBudgetUsd"] as? JsonPrimitive)?.doubleOrNull?.toString() ?: "1.0",
        "--tools", if (noTools || config.string("tools", "readonly") == "none") "" else "Read,Grep,Glob") +
        (config.string("model").takeIf { it.isNotBlank() }?.let { listOf("--model", it) } ?: emptyList()) +
        (if (!noTools) outputSchema(config)?.let { listOf("--json-schema", it.toString()) } ?: emptyList() else emptyList()) +
        listOf("--")

private val codexDisabled = listOf("multi_agent", "apps", "view_image", "image_generation", "browser_use", "computer_use", "in_app_browser")
internal fun codexArgv(config: JsonObject, out: Path, noTools: Boolean = false, schemaFile: Path? = null): List<String> =
    listOf("codex", "exec", "--skip-git-repo-check", "--ephemeral", "--ignore-rules",
        "-s", "read-only", "-c", "web_search=\"disabled\"",
        "-c", "model_reasoning_effort=\"${config.string("effort", "medium")}\"") +
        (codexDisabled + if (noTools) listOf("shell_tool", "unified_exec") else emptyList()).flatMap { listOf("--disable", it) } +
        (config.string("model").takeIf { it.isNotBlank() }?.let { listOf("-m", it) } ?: emptyList()) +
        (schemaFile?.let { listOf("--output-schema", it.toAbsolutePath().toString()) } ?: emptyList()) +
        listOf("-o", out.toAbsolutePath().toString(), "--", "-")

internal fun sha256(code: String) = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun extractPython(answer: String): String = Regex("```python[^\n]*\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    .find(answer)?.groupValues?.get(1) ?: answer

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val error: String?,
                                  val warnings: List<String> = emptyList())

internal data class DecodedOutput(val text: String, val warnings: List<String> = emptyList())
internal fun readOutput(path: Path): DecodedOutput {
    val bytes = Files.readAllBytes(path)
    return try { DecodedOutput(bytes.decodeToString(throwOnInvalidSequence = true)) }
    catch (_: java.nio.charset.CharacterCodingException) {
        DecodedOutput(bytes.toString(Charsets.UTF_8), listOf("${path.fileName}: output was not valid UTF-8; replaced malformed bytes"))
    }
}

/** Fail before launching either agent, after substitution and generation instructions. */
internal fun checkPrompt(config: JsonObject, prompt: String) {
    val limit = config.long("maxPromptBytes", 1_048_576)
    val size = prompt.toByteArray(Charsets.UTF_8).size
    check(size <= limit) { "prompt is $size UTF-8 bytes; exceeds maxPromptBytes=$limit" }
}

/** Missing identity data refuses cleanup; a PID alone is never authority to kill. */
@Serializable
data class RecordedProcess(val pid: Long, val startedAt: String, val command: String,
                           val arguments: List<String>? = null) {
    internal fun matchingHandle(): ProcessHandle? = ProcessHandle.of(pid).orElse(null)?.takeIf {
        it.isAlive && recordProcess(it) == this
    }
}
internal fun recordProcess(handle: ProcessHandle): RecordedProcess? {
    val info = handle.info()
    return RecordedProcess(handle.pid(), info.startInstant().orElse(null)?.toString() ?: return null,
        info.command().orElse(null) ?: return null, info.arguments().orElse(null)?.toList())
}
internal fun descendants(handle: ProcessHandle): List<ProcessHandle> =
    runCatching { handle.descendants().use { it.toList() } }.getOrDefault(emptyList())
internal fun recordedTree(process: Process): List<RecordedProcess> =
    (listOf(process.toHandle()) + descendants(process.toHandle())).mapNotNull(::recordProcess)

internal fun destroyRecorded(records: List<RecordedProcess>) {
    // Verify EVERY saved identity, even when its former parent has exited/reused its PID.
    records.mapNotNull { it.matchingHandle() }.forEach { destroyHandleTree(it) }
}

/** Cancellation closes the launch race as well as killing an already-started child. */
internal class ProcessFence(private val observed: (List<RecordedProcess>) -> Unit = {}) {
    private var cancelled = false
    private var process: Process? = null
    @Synchronized fun start(builder: ProcessBuilder): Process {
        check(!cancelled) { "run fenced" }
        return builder.start().also { process = it }
    }
    fun observe(process: Process) { observed(recordedTree(process)) }
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

internal open class StudioProcesses : AutoCloseable {
    internal val active = ConcurrentHashMap.newKeySet<Process>()
    @Volatile private var closed = false

    /** All streams are files; a child cannot deadlock by filling a pipe. */
    open fun run(argv: List<String>, cwd: Path, dir: Path, timeoutSec: Long, stdin: String? = null, environment: Map<String, String> = emptyMap(), fence: ProcessFence = ProcessFence()): ProcessResult {
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
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            p = fence.start(builder)
            active += p
            fence.observe(p)
            if (closed) { destroyTree(p); error = "studio closed" }
            else {
                while (p.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        destroyTree(p)
                        error = "timed out after $timeoutSec seconds"
                        break
                    }
                    if (p.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) break
                    fence.observe(p)
                }
            }
            exit = if (p.isAlive || error != null) -1 else p.exitValue()
        } catch (e: InterruptedException) {
            p?.let(::destroyTree)
            Thread.currentThread().interrupt()
            error = "interrupted"
        } catch (e: Exception) {
            p?.let(::destroyTree)
            error = e.message ?: e.javaClass.simpleName
        } finally { p?.let { active -= it } }
        val out = readOutput(stdout)
        val err = readOutput(stderr)
        return ProcessResult(exit, out.text, err.text,
            error ?: if (exit != 0) err.text.takeLast(2000).ifBlank { "process exited $exit" } else null,
            out.warnings + err.warnings)
    }

    override fun close() { closed = true; active.forEach(::destroyTree) }
}

/**
 * Kill the parent and wait for its death BEFORE signalling children: a shell must
 * never resume its next command because we killed its sleep. Snapshot on both
 * sides of the parent kill, and stop descendant parents before their children too.
 * JDK has no process-group launch API, and macOS has no setsid binary. A child
 * forked/reparented between snapshots or daemonized before discovery can escape;
 * this is best-effort tree cleanup, not an OS containment boundary. SIGKILL cannot
 * undo effects already issued, and there is a scheduling window at the deadline
 * before the parent receives it. No grace interval lets the shell keep running.
 */
internal fun destroyTree(process: Process) = destroyHandleTree(process.toHandle())

private fun destroyHandleTree(root: ProcessHandle) {
    val before = descendants(root)
    root.destroyForcibly()
    val children = (before + descendants(root)).distinctBy { it.pid() }
    // Preserve interruption, but do not let worker cancellation skip child cleanup.
    val interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var interruptedDuringCleanup = false
    fun kill(handle: ProcessHandle) {
        if (!handle.isAlive) return
        handle.destroyForcibly()
        while (handle.isAlive && System.nanoTime() < deadline) {
            try { handle.onExit().get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
            catch (_: InterruptedException) { interruptedDuringCleanup = true }
            catch (_: Exception) { break }
        }
    }
    try {
        kill(root)
        // If an OS refuses to kill a parent, do not unblock it by killing a child.
        if (root.isAlive) return
        val byPid = children.associateBy { it.pid() }
        fun depth(h: ProcessHandle): Int {
            var parent = h.parent().orElse(null)
            var depth = 0
            val seen = mutableSetOf<Long>()
            while (parent != null && parent.pid() in byPid && seen.add(parent.pid())) {
                depth++; parent = parent.parent().orElse(null)
            }
            return depth
        }
        children.sortedBy(::depth).forEach(::kill)
    } finally {
        if (interrupted || interruptedDuringCleanup) Thread.currentThread().interrupt()
    }
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
