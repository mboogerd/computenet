package civictech.deliberate

import civictech.agora.AgoraService
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.respond
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The deliberate backend (SPEC §6): an [AgoraService] on a [ManagedHost], a
 * [DeliberationEngine] growing trees over it, and a [DemoShell] serving the
 * UI contract plus the built UI.
 *
 * `/events` is coalesced: engine changes and agora credence callbacks only
 * raise a dirty flag, and one flusher thread broadcasts a full [GraphDto] at
 * most once per [flushIntervalMs] — a round touches dozens of cells, and each
 * would otherwise cost a whole-graph frame.
 *
 * [judge] and [proposers] are injected so tests run without Jev or the CLIs.
 */
class DeliberateApp(
    port: Int = DEFAULT_PORT,
    judge: Judge,
    proposers: List<Proposer>,
    config: DeliberationEngine.Config = DeliberationEngine.Config(),
    private val uiDir: File? = defaultUiDir(),
    private val flushIntervalMs: Long = 100,
) {
    // Bind before starting any scheduler/executor threads. A bind failure must
    // not leave a half-constructed app running in the background.
    private val shell = DemoShell(port)
    private val scheduler = VirtualThreadScheduler("deliberate-host")
    private val registry = LocationRegistry()
    private val host = ManagedHost(
        scheduler = scheduler,
        registry = registry,
        attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
    )
    private val dirty = AtomicBoolean(false)
    private val service = AgoraService(host, registry, onCredence = { _, _ -> dirty.set(true) })
    val engine = DeliberationEngine(service, judge, proposers, config) { dirty.set(true) }

    val boundPort: Int get() = shell.boundPort

    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "deliberate-sse-flush").apply { isDaemon = true }
    }

    init {
        shell.route("/question") { handleQuestion(it) }
        shell.route("/override") { handleOverride(it) }
        shell.route("/graph") { ex ->
            if (ex.requestMethod != "GET") return@route ex.respond(405, "GET only")
            ex.respond(200, graphJson(), "application/json")
        }
        shell.sse("/events") { graphJson() }
        shell.route("/") { serveStatic(it) }
    }

    fun graphJson(): String = JSON.encodeToString(GraphDto.serializer(), engine.snapshot())

    fun start(): DeliberateApp = apply {
        shell.start()
        flusher.scheduleWithFixedDelay({
            try {
                if (dirty.getAndSet(false)) shell.broadcast { graphJson() }
            } catch (e: Exception) {
                // A failed frame must neither cancel the schedule (a thrown
                // task ends it) nor consume the update that requested it.
                dirty.set(true)
                System.err.println("deliberate: broadcast failed: $e")
            }
        }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        flusher.shutdownNow()
        try {
            if (!flusher.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("deliberate: SSE flusher did not stop within ${STOP_TIMEOUT_SECONDS}s")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        engine.close()
        shell.stop()
        scheduler.shutdown()
    }

    internal val flusherTerminated: Boolean get() = flusher.isTerminated

    // ------------------------------------------------------------ handlers

    private fun handleQuestion(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.respond(405, "POST only")
        val params = readForm(ex) ?: return
        val text = params["text"]?.trim()
        if (text.isNullOrEmpty()) return ex.respond(400, "missing text")
        if (text.length > MAX_QUESTION) return ex.respond(400, "question longer than $MAX_QUESTION characters")
        val root = engine.ask(text)
        ex.respond(200, """{"root":"${root.id}"}""", "application/json")
    }

    private fun handleOverride(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return ex.respond(405, "POST only")
        val params = readForm(ex) ?: return
        val id = params["id"]?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            ?: return ex.respond(400, "id must be a claim ref")
        val mode = params["mode"]?.trim()?.uppercase()?.let { m -> Override.entries.firstOrNull { it.name == m } }
            ?: return ex.respond(400, "mode must be AUTO, EXPAND or STOP")
        try {
            engine.setOverride(CellRef(id), mode)
        } catch (_: IllegalArgumentException) {
            return ex.respond(404, "unknown claim $id")
        }
        ex.respond(200, "ok")
    }

    private fun readForm(ex: HttpExchange): Map<String, String>? = try {
        form(ex)
    } catch (_: IllegalArgumentException) {
        ex.respond(400, "malformed form encoding")
        null
    }

    private fun serveStatic(ex: HttpExchange) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") return ex.respond(405, "GET only")
        val dir = uiDir?.takeIf { File(it, "index.html").isFile }
            ?: return ex.respond(200, HINT_PAGE, "text/html; charset=utf-8")
        val path = ex.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
        val file = resolveStaticFile(dir, path) ?: return ex.respond(404, "not found")
        val bytes = file.readBytes()
        ex.responseHeaders.add("Content-Type", contentType(file.name))
        // Hashed assets are immutable; index.html must revalidate to pick up a rebuild.
        ex.responseHeaders.add("Cache-Control", if (path.startsWith("assets/")) "max-age=31536000, immutable" else "no-cache")
        if (ex.requestMethod == "HEAD") {
            ex.sendResponseHeaders(200, -1)
            ex.close()
            return
        }
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val DEFAULT_PORT = 8091
        const val MAX_QUESTION = 1_000
        private const val STOP_TIMEOUT_SECONDS = 5L

        val JSON = Json {
            explicitNulls = false
            encodeDefaults = true
        }

        /**
         * `ui/dist` relative to the module (Gradle `run` uses the subproject as
         * cwd) or `demo/deliberate/ui/dist` relative to the repo root.
         */
        fun defaultUiDir(cwd: File = File("").absoluteFile): File? =
            listOf(File(cwd, "ui/dist"), File(cwd, "demo/deliberate/ui/dist"))
                .firstOrNull { File(it, "index.html").isFile }

        private fun form(ex: HttpExchange): Map<String, String> = ex.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }

        /** Resolve a decoded request path without allowing `..` or symlinks to leave [dir]. */
        internal fun resolveStaticFile(dir: File, path: String): File? = runCatching {
            val root = dir.canonicalFile.toPath()
            val candidate = File(dir.canonicalFile, path).canonicalFile
            candidate.takeIf { it.toPath().startsWith(root) && it.isFile }
        }.getOrNull()

        private fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "map" -> "application/json"
            else -> "application/octet-stream"
        }

        private val HINT_PAGE = """
            <!DOCTYPE html><meta charset="utf-8"><title>deliberate</title>
            <p style="font-family:system-ui;margin:2rem">deliberate backend is running, but the UI is not built:
            run <code>cd demo/deliberate/ui &amp;&amp; npm install &amp;&amp; npm run build</code>, or pass <code>--ui &lt;dir&gt;</code>.
            The API is live at <a href="/graph">/graph</a>.</p>
        """.trimIndent()
    }
}

/** Command line: see [USAGE]. */
internal class Options(args: Array<String>) {
    private val values = mutableMapOf<String, String>()
    private val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--help" || a == "-h" -> values["--help"] = "true"
                a.startsWith("--") -> {
                    require(a in FLAGS) { "unknown option $a" }
                    require(i + 1 < args.size) { "$a needs a value" }
                    values[a] = args[++i]
                }
                else -> positional += a
            }
            i++
        }
        require(positional.size <= 1) { "expected at most one port argument" }
    }

    val help get() = "--help" in values
    val port: Int = (
        positional.firstOrNull()?.let { requireNotNull(it.toIntOrNull()) { "port must be a number: $it" } }
            ?: System.getenv("PORT")?.toIntOrNull() ?: DeliberateApp.DEFAULT_PORT
        ).also { require(it in 0..65535) { "port must be between 0 and 65535: $it" } }
    val proposers: List<String> = (values["--proposers"] ?: "claude,codex")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .also {
            require(it.isNotEmpty()) { "--proposers is empty" }
            require(it.all { proposer -> proposer == "claude" || proposer == "codex" }) {
                "unknown proposer '${it.first { proposer -> proposer != "claude" && proposer != "codex" }}' (claude, codex)"
            }
        }
    val claudeModel get() = values["--claude-model"]
    val codexModel get() = values["--codex-model"]
    val ui get() = values["--ui"]?.let(::File)
    val maxProcesses = (int("--max-processes") ?: 4)
        .also { require(it > 0) { "--max-processes must be positive: $it" } }

    val config: DeliberationEngine.Config = DeliberationEngine.Config().let { d ->
        d.copy(
            argsPerCall = int("--args-per-call") ?: d.argsPerCall,
            maxRounds = int("--max-rounds") ?: d.maxRounds,
            maxDepth = int("--max-depth") ?: d.maxDepth,
            maxClaims = int("--max-claims") ?: d.maxClaims,
            saturation = double("--saturation") ?: d.saturation,
            relevance = double("--relevance") ?: d.relevance,
        )
    }

    private fun int(flag: String) = values[flag]?.let { requireNotNull(it.toIntOrNull()) { "$flag must be an integer: $it" } }
    private fun double(flag: String) = values[flag]?.let { requireNotNull(it.toDoubleOrNull()) { "$flag must be a number: $it" } }

    companion object {
        val FLAGS = setOf(
            "--proposers", "--claude-model", "--codex-model", "--ui", "--max-processes",
            "--args-per-call", "--max-rounds", "--max-depth", "--max-claims", "--saturation", "--relevance",
        )
        val USAGE = """
            usage: deliberate [port] [options]            (port default 8091, or ${'$'}PORT)
              --proposers claude,codex    which CLIs propose arguments
              --claude-model <m>          model for the Claude CLI (its default otherwise)
              --codex-model <m>           model for the Codex CLI (its default otherwise)
              --max-processes <n>         concurrent CLI processes, app-wide (4)
              --args-per-call <n>         arguments per proposer call per side (2)
              --max-rounds <n>            rounds per claim (3)
              --max-depth <n>             deepest expanded level (3)
              --max-claims <n>            claims per question (60)
              --saturation <p>            saturation threshold (0.7)
              --relevance <p>             relevance threshold (0.5)
              --ui <dir>                  built UI directory (default ui/dist)
            requires TYPESAFE_API_KEY and logged-in `claude` / `codex` CLIs.
        """.trimIndent()
    }
}

fun main(args: Array<String>) {
    val opts = try {
        Options(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("deliberate: ${e.message}\n\n${Options.USAGE}")
        exitProcess(2)
    }
    if (opts.help) {
        println(Options.USAGE)
        return
    }
    if (System.getenv("TYPESAFE_API_KEY").isNullOrBlank()) {
        System.err.println("deliberate: TYPESAFE_API_KEY is not set (Jev judges every step)")
        exitProcess(2)
    }
    val gate = ProcessGate(opts.maxProcesses)
    val proposers = opts.proposers.map {
        when (it) {
            "claude" -> CliProposer.claude(gate, opts.claudeModel)
            "codex" -> CliProposer.codex(gate, opts.codexModel)
            else -> error("validated proposer became unknown: $it")
        }
    }
    val uiDir = opts.ui ?: DeliberateApp.defaultUiDir()
    val app = DeliberateApp(opts.port, SlowCallLog.judge(JevJudge()), proposers, opts.config, uiDir).start()
    Runtime.getRuntime().addShutdownHook(Thread { app.stop() })
    announcePort("http", app.boundPort)
    println("deliberate: http://localhost:${app.boundPort}  (proposers: ${proposers.joinToString { it.id }}, ${opts.config})")
    println(if (uiDir != null && File(uiDir, "index.html").isFile) "  serving UI from $uiDir" else "  UI not built — see demo/deliberate/README.md")
}

/**
 * Logs every Jev call slower than [thresholdMs] to stderr (a request can sit
 * in retries/timeouts without ever recording an error). CLI proposer calls are
 * deliberately not timed here: from the outside their latency is dominated by
 * the wait for a [ProcessGate] permit, not by the CLI.
 */
internal object SlowCallLog {
    private const val thresholdMs = 20_000L

    private inline fun <T> timed(what: () -> String, call: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return call()
        } finally {
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms >= thresholdMs) System.err.println("deliberate: slow call ${ms} ms: ${what()}")
        }
    }

    fun judge(j: Judge): Judge = object : Judge {
        override fun plausibility(question: String, path: List<String>, claim: String) =
            timed({ "jev plausibility" }) { j.plausibility(question, path, claim) }
        override fun relationStrength(question: String, parent: String, child: String, side: Side) =
            timed({ "jev relationStrength" }) { j.relationStrength(question, parent, child, side) }
        override fun duplicates(claim: String, side: Side, existing: List<String>, candidates: List<String>) =
            timed({ "jev duplicates" }) { j.duplicates(claim, side, existing, candidates) }
        override fun saturation(ctx: ClaimContext, side: Side) = timed({ "jev saturation" }) { j.saturation(ctx, side) }
        override fun relevance(ctx: ClaimContext) = timed({ "jev relevance" }) { j.relevance(ctx) }
    }
}
