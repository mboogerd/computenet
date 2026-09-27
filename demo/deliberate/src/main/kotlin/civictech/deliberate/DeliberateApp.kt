package civictech.deliberate

import civictech.agora.AgoraService
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.durability.FileJournal
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
 * The deliberate backend (SPEC §6): a [CredenceGraph] on a [ManagedHost], a
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
    merger: Merger? = null,
    private val flushIntervalMs: Long = 100,
    /** SPEC §11: with a directory, deliberations survive restarts (and `kill -9`); null is volatile. */
    private val dataDir: File? = null,
    private val semantics: SemanticsConfig = SemanticsConfig(),
    /** How often the metadata journal checks whether it has grown enough to compact itself. */
    private val compactEveryMs: Long = 30_000,
) {
    /**
     * SPEC §2 "Credence layers and consensus". [layers] always includes
     * `dfquad` (agora's own semantics, the reference layer); [headline] picks
     * the layer shown as `NodeDto.credence`; [consensus] the layers averaged
     * into `consensus`.
     */
    data class SemanticsConfig(
        val headline: String = SemanticsCatalog.DEFAULT_PRIMARY,
        val layers: List<String> = SemanticsCatalog.IDS,
        val consensus: List<String> = Consensus.DEFAULT_MEMBERS,
        val wlo: WeightedLogOdds = WeightedLogOdds(),
    ) {
        /** Every layer that runs, `dfquad` first. */
        val running: List<String> = (listOf(SemanticsCatalog.DEFAULT_PRIMARY) + layers).distinct()

        init {
            (layers + consensus + headline).forEach {
                require(it in SemanticsCatalog.IDS) { "unknown semantics '$it' (${SemanticsCatalog.IDS.joinToString()})" }
            }
            require(headline in running) { "--semantics $headline is not among the layers ${running.joinToString()}" }
            require(consensus.all { it in running }) { "--consensus ${consensus.joinToString()} names a layer that does not run (${running.joinToString()})" }
            require(consensus.isNotEmpty()) { "--consensus is empty" }
        }
    }

    init {
        // Before anything binds or starts a thread.
        dataDir?.let(::refuseOldFormat)
    }

    // Bind before starting any scheduler/executor threads. A bind failure must
    // not leave a half-constructed app running in the background.
    private val shell = DemoShell(port)
    private val scheduler = VirtualThreadScheduler("deliberate-host")
    private val registry = LocationRegistry()

    private val journal = dataDir?.let { FileJournal(File(it.apply { mkdirs() }, "host.journal")) }

    /**
     * SPEC DUR-01: only the metadata cell is journaled (`journalFor`); every
     * credence cell is volatile and recomputed from the inputs on boot, so
     * the journal never holds a derived frame.
     */
    private val host = ManagedHost(
        scheduler = scheduler,
        registry = registry,
        attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
        journalFor = { ref -> journal?.takeIf { ref == JournaledMetaStore.REF } },
    )
    private val dirty = AtomicBoolean(false)
    private val metaStore = dataDir?.let { JournaledMetaStore(host, registry) }

    /** Every credence layer in one cell graph; its structure log is the one durable record of the trees. */
    private val graph = CredenceGraph(
        host,
        registry,
        LayerSet.of(semantics.running, semantics.consensus, semantics.headline, semantics.wlo),
        structureLog = dataDir?.let { File(it, STRUCTURE_LOG) },
        onCredence = { dirty.set(true) },
    )

    init {
        // Rebuild (the graph replayed its structure log) → replay the metadata journal →
        // wait until it is folded → compact it: the fold now holds every replayed frame,
        // so the checkpoint is quiescent (JournaledMetaStore.checkpoint).
        if (journal != null) {
            host.recoverFrom(journal)
            metaStore!!.awaitReplayed(kotlin.time.Duration.parse("60s"))
            metaStore.checkpoint(journal)
        }
    }

    val engine = DeliberationEngine(graph, judge, proposers, config, merger, store = metaStore) { dirty.set(true) }

    /** Journal length right after the last checkpoint; the periodic compaction measures growth against it. */
    @Volatile
    private var checkpointedBytes = journalFile()?.length() ?: 0L

    private val compactor = journal?.let {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deliberate-compact").apply { isDaemon = true } }
    }

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
        compactor?.scheduleWithFixedDelay({
            try {
                compactIfGrown()
            } catch (e: Exception) {
                System.err.println("deliberate: metadata checkpoint failed: $e")
            }
        }, compactEveryMs, compactEveryMs, TimeUnit.MILLISECONDS)
    }

    private fun journalFile() = dataDir?.let { File(it, "host.journal") }

    /**
     * SPEC DUR-02: the metadata journal compacts itself once it has grown by
     * [COMPACT_MIN_BYTES] and by as much again as its last checkpoint, so it
     * stays within about twice the size of the state it holds.
     */
    internal fun compactIfGrown() {
        val length = journalFile()?.length() ?: return
        if (length - checkpointedBytes <= maxOf(COMPACT_MIN_BYTES, checkpointedBytes)) return
        checkpointNow()
    }

    /** Compacts the metadata journal to one checkpoint of the fold now (a no-op without `--data`). */
    internal fun checkpointNow() {
        val j = journal ?: return
        metaStore!!.checkpoint(j)
        checkpointedBytes = journalFile()!!.length()
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
        compactor?.shutdownNow()
        try {
            if (compactor != null && !compactor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("deliberate: metadata compactor did not stop within ${STOP_TIMEOUT_SECONDS}s")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        engine.close() // persists the metadata one last time
        try {
            checkpointNow()
        } catch (e: Exception) {
            System.err.println("deliberate: final metadata checkpoint failed: $e")
        }
        shell.stop()
        scheduler.shutdown()
    }

    internal val flusherTerminated: Boolean get() = flusher.isTerminated
    internal val compactorTerminated: Boolean get() = compactor?.isTerminated ?: true

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
        /** SPEC DUR-01: the one structure log (claims and edges, in creation order). */
        const val STRUCTURE_LOG = "graph.jsonl"
        /** The metadata journal compacts once it grew by at least this much since its last checkpoint. */
        const val COMPACT_MIN_BYTES = 64L * 1024

        /**
         * A data directory from before the one-graph design (one structure log
         * per layer, a host journal of agora frames) cannot be read by this one:
         * refuse it with a clear message instead of failing inside the replay.
         */
        internal fun refuseOldFormat(dir: File) {
            val old = dir.listFiles { f -> f.name.startsWith("graph-") && f.name.endsWith(".jsonl") }.orEmpty()
            require(old.isEmpty()) {
                "--data $dir holds a deliberation in the old per-layer format (${old.joinToString { it.name }}); " +
                    "start with a fresh data directory"
            }
        }
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
    val maxProcesses = (int("--max-processes") ?: DEFAULT_MAX_PROCESSES)
        .also { require(it > 0) { "--max-processes must be positive: $it" } }

    val config: DeliberationEngine.Config = DeliberationEngine.Config().let { d ->
        d.copy(
            argsPerCall = int("--args-per-call") ?: d.argsPerCall,
            maxRounds = int("--max-rounds") ?: d.maxRounds,
            maxDepth = int("--max-depth") ?: d.maxDepth,
            maxClaims = int("--max-claims") ?: d.maxClaims,
            maxArgsPerSide = int("--max-args-per-side") ?: d.maxArgsPerSide,
            maxArgsPerSideChild = int("--max-args-per-side-child") ?: d.maxArgsPerSideChild,
            saturation = double("--saturation") ?: d.saturation,
            minInfluence = double("--min-influence") ?: d.minInfluence,
            roundDecay = double("--round-decay") ?: d.roundDecay,
            yieldStop = when (val mode = values["--yield-stop"]?.trim()?.lowercase()) {
                null, "on" -> (d.yieldStop ?: DeliberationEngine.YieldStop()).let { y ->
                    DeliberationEngine.YieldStop(
                        window = int("--yield-window") ?: y.window,
                        ratio = double("--yield-ratio") ?: y.ratio,
                        minClaims = int("--yield-min-claims") ?: y.minClaims,
                    )
                }
                "off" -> null
                else -> throw IllegalArgumentException("--yield-stop must be on or off: $mode")
            },
        )
    }

    /** SPEC §11: the durable data directory, or null (volatile). */
    val data get() = values["--data"]?.let(::File)

    private fun list(flag: String) = values[flag]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

    val semantics: DeliberateApp.SemanticsConfig = DeliberateApp.SemanticsConfig().let { d ->
        val w = d.wlo
        DeliberateApp.SemanticsConfig(
            headline = values["--semantics"]?.trim() ?: d.headline,
            layers = list("--semantics-layers") ?: d.layers,
            consensus = list("--consensus") ?: d.consensus,
            wlo = WeightedLogOdds(
                alpha = double("--wlo-alpha") ?: w.alpha,
                k = double("--wlo-k") ?: w.k,
                p = double("--wlo-p") ?: w.p,
                gamma = double("--wlo-gamma") ?: w.gamma,
            ),
        )
    }

    private fun int(flag: String) = values[flag]?.let { requireNotNull(it.toIntOrNull()) { "$flag must be an integer: $it" } }
    private fun double(flag: String) = values[flag]?.let { requireNotNull(it.toDoubleOrNull()) { "$flag must be a number: $it" } }

    companion object {
        /** EXP-07. */
        const val DEFAULT_MAX_PROCESSES = 8
        val FLAGS = setOf(
            "--proposers", "--claude-model", "--codex-model", "--ui", "--max-processes",
            "--args-per-call", "--max-rounds", "--max-depth", "--max-claims", "--max-args-per-side",
            "--max-args-per-side-child", "--saturation", "--min-influence", "--round-decay",
            "--yield-stop", "--yield-window", "--yield-ratio", "--yield-min-claims",
            "--data", "--semantics", "--semantics-layers", "--consensus",
            "--wlo-alpha", "--wlo-k", "--wlo-p", "--wlo-gamma",
        )
        private val D = DeliberationEngine.Config()
        private val Y = DeliberationEngine.YieldStop()
        val USAGE = """
            usage: deliberate [port] [options]            (port default 8091, or ${'$'}PORT)
              --proposers claude,codex    which CLIs propose arguments
              --claude-model <m>          model for the Claude CLI (its default otherwise)
              --codex-model <m>           model for the Codex CLI (its default otherwise)
              --max-processes <n>         concurrent CLI processes, app-wide ($DEFAULT_MAX_PROCESSES)
              --args-per-call <n>         arguments per proposer call per side (${D.argsPerCall})
              --max-rounds <n>            rounds per claim (${D.maxRounds})
              --max-depth <n>             deepest expanded level (${D.maxDepth})
              --max-claims <n>            claims per question (${D.maxClaims})
              --max-args-per-side <n>     arguments per side of the root before it is saturated (${D.maxArgsPerSide})
              --max-args-per-side-child <n>  the same cap for claims below the root (${D.maxArgsPerSideChild})
              --saturation <p>            Jev saturation (1 - p(missing)) that saturates a side (${D.saturation})
              --min-influence <p>         expand a claim only if contribution (reach x relevance x quality) >= p (${D.minInfluence})
              --round-decay <f>           a claim's next round is queued at contribution x f^rounds (${D.roundDecay})
              --yield-stop on|off         stop a question once its returns diminish (on)
              --yield-window <n>          ...when the mean yield of its last n rounds (${Y.window})
              --yield-ratio <f>           ...falls below f x the mean of its earlier rounds (${Y.ratio})
              --yield-min-claims <n>      ...and it holds at least n claims (${Y.minClaims})
              --data <dir>                keep deliberations in <dir> across restarts (default: volatile)
              --semantics <id>            the layer shown as a node's credence (${SemanticsCatalog.DEFAULT_PRIMARY})
              --semantics-layers <ids>    credence layers to propagate (${SemanticsCatalog.IDS.joinToString(",")}); dfquad always runs
              --consensus <ids>           layers averaged (in log-odds) into the consensus (${Consensus.DEFAULT_MEMBERS.joinToString(",")})
              --wlo-k/--wlo-p/--wlo-gamma/--wlo-alpha <x>  weighted log-odds parameters (2.4, 2, 1.3, 1)
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
    // EXP-03 MERGE always asks Claude, whichever CLIs propose.
    val merger = CliMerger(proposers.filterIsInstance<CliProposer>().firstOrNull { it.id == "claude" } ?: CliProposer.claude(gate, opts.claudeModel))
    val app = DeliberateApp(
        opts.port, SlowCallLog.judge(JevJudge()), proposers, opts.config, uiDir, merger,
        dataDir = opts.data, semantics = opts.semantics,
    ).start()
    Runtime.getRuntime().addShutdownHook(Thread { app.stop() })
    announcePort("http", app.boundPort)
    println("deliberate: http://localhost:${app.boundPort}  (proposers: ${proposers.joinToString { it.id }}, ${opts.config})")
    println(if (uiDir != null && File(uiDir, "index.html").isFile) "  serving UI from $uiDir" else "  UI not built — see demo/deliberate/README.md")
    println("  layers: ${opts.semantics.running.joinToString()}  (headline ${opts.semantics.headline}, consensus ${opts.semantics.consensus.joinToString()})")
    println(if (opts.data != null) "  keeping deliberations in ${opts.data} (kill -9 safe)" else "  volatile: add --data <dir> to survive restarts")
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
        override fun quality(question: String, parent: String, child: String, side: Side) =
            timed({ "jev quality" }) { j.quality(question, parent, child, side) }
        override fun assess(question: String, path: List<String>, child: String, side: Side) =
            timed({ "jev assess" }) { j.assess(question, path, child, side) }
        override fun triage(ctx: ClaimContext, candidates: List<Candidate>) =
            timed({ "jev triage (${candidates.size})" }) { j.triage(ctx, candidates) }
        override fun saturation(ctx: ClaimContext, side: Side) = timed({ "jev saturation" }) { j.saturation(ctx, side) }
        override fun relevance(ctx: ClaimContext) = timed({ "jev relevance" }) { j.relevance(ctx) }
    }
}
