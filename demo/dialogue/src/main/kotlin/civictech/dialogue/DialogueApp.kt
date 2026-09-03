package civictech.dialogue

import civictech.agora.graphJson
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import civictech.demo.shell.value
import civictech.dialogue.apply.ReconcileReport
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.Extractor
import civictech.dialogue.extract.RuleExtractor
import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The AGO1 dialogue backend: `DemoShell` (JDK HttpServer + SSE) over one
 * [DialogueRuntime] — epic computenet-2aw §2.4, §3.6
 * [AGO1-OBS-01]/[AGO1-OBS-02], [AGO1-REPLAY-03], §8/R4; task
 * computenet-2aw.5.2.
 *
 * Routes:
 *
 *  - `GET /graph` — the agora graph as [civictech.agora.NodeDto], encoded by
 *    the **same** [graphJson] `AgoraApp` serves (2aw.5-D3/D10). One encoder,
 *    never a fork: `demo/agora/ui` renders a dialogue-built map unmodified.
 *  - `GET /events` — SSE. The connect-time frame is the current `/graph`;
 *    every later frame is a full snapshot, pushed on each settle and on each
 *    credence move.
 *  - `POST /transcript` — `action=load|replay|step|reset` (form-encoded).
 *    `GET /transcript` is computenet-2aw.5.3's and answers 501 until it lands.
 *
 * ### 2aw.5-D4 — one driver thread, and only one
 *
 * [DialogueRuntime], [TranscriptSource] and `ExtractionAccounting` are all
 * documented "not thread-safe: drive from one thread", while `DemoShell`
 * hands every request to the JDK HttpServer's dispatcher (`server.executor =
 * null`). So **every** call into `runtime.source`, [DialogueRuntime.reconcile],
 * [DialogueRuntime.reset] and [DialogueRuntime.afterQuiescence] is submitted
 * onto [driver], a single-threaded daemon executor; an HTTP handler never
 * touches the runtime itself. The one read that is not on [driver] is
 * `service.graph()` inside [graphJson], and 2aw.5-D10 requires it to be: the
 * `onCredence` hook fires on a host scheduler thread, so [broadcast] cannot
 * be driver-confined.
 *
 * **That read is not atomic, and the caveat belongs here rather than in the
 * bead.** `AgoraService.graph()` iterates a plain `LinkedHashMap` that
 * `createClaim`/`createEdge`/`remove` mutate, so a `/graph` read concurrent
 * with an applying [DialogueRuntime.reconcile] can observe a partly-applied
 * graph or throw `ConcurrentModificationException`. `AgoraApp`'s window is
 * narrower than this one, not equal to it: `DemoShell` runs every handler on
 * the single dispatcher thread, so there its mutations and its `/graph` reads
 * are the *same* thread and only the credence hook races. Here every mutation
 * is on [driver], so every `/graph` read races the applier. Closing it means
 * making `AgoraService`'s node map concurrent, which this task's non-goals bar
 * (no `demo/agora` change) — filed as computenet-47nz.
 *
 * `load`, `step` and `reset` are **synchronous**: submitted, awaited with a
 * bound, answered with their result. `replay` is **asynchronous** and answers
 * `202` immediately, because a [Pace.Wallclock] replay sleeps for the
 * transcript's duration inside the driver and the shell's single dispatcher
 * thread must not be held for it. Actions queue behind an in-flight replay in
 * submission order — documented, not prevented.
 *
 * ### 2aw.5-D5 — per-utterance, quiescence-scoped reconciliation
 *
 * [settle] is the only place [DialogueRuntime.reconcile] is ever called, and
 * it calls it **inside** [DialogueRuntime.afterQuiescence] ([AGO1-APPLY-04]:
 * apply at quiescence, never mid-wave). Nothing here registers an `onChange`.
 * The order inside the fence is load-bearing for [AGO1-OBS-02]: drain →
 * `reconcile()` → [onSettled] → [broadcast], so the frame a subscriber sees
 * after an admission carries the **post**-reconciliation graph — the newly
 * bound claim ref is already in it — rather than the graph as it stood before
 * the applier ran.
 *
 * ### Manual check
 *
 * ```
 * ./gradlew :demo:dialogue:run --args="8090 --transcript <jsonl> --journal <dir>"
 * cd demo/agora/ui && AGORA_BACKEND=http://localhost:8090 npm run dev
 * ```
 *
 * `vite.config.ts` already proxies `/graph` and `/events` to `AGORA_BACKEND`,
 * so no frontend change is needed. Use a port other than 8080: it is routinely
 * squatted by other sessions on this machine. `demoPort` reads the first
 * **non-`--`** argument, so the port must precede the flags.
 */
class DialogueApp(
    port: Int = 8080,
    extractor: Extractor,
    transcriptFile: File? = null,
    journalDir: File? = null,
) {

    /**
     * The single thread every runtime call runs on (2aw.5-D4). Daemon, so a
     * forgotten [stop] cannot keep a JVM alive; named, so a thread dump
     * attributes a stall here rather than to an anonymous pool.
     */
    private val driver: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dialogue-driver").apply { isDaemon = true }
    }

    val runtime = DialogueRuntime(
        extractor = extractor,
        journalDir = journalDir,
        // 2aw.5-D10: agora's own idiom — the credence moves that settle
        // asynchronously after a stance write are not announced by any
        // reconcile, so the hook is what carries them to subscribers.
        onCredence = { _, _ -> broadcast() },
    )

    private val shell = DemoShell(port)

    val boundPort: Int get() = shell.boundPort

    /**
     * What the boot [settle] reconciled. `structureOps == 0` on a recovery
     * from an existing `--journal` directory is the app-level reading of
     * BS-18: the structure log rebuilt the graph under its recorded refs and
     * the binding table replayed, so there is nothing left for the applier to
     * create.
     */
    @Volatile
    var bootReconcile: ReconcileReport? = null
        private set

    /** The load report of the `--transcript` file, if one was given. */
    @Volatile
    var bootLoad: TranscriptLoadReport? = null
        private set

    init {
        // Boot on the driver thread, like every other runtime call. No startup
        // checkpoint (AgoraApp's / DialogueRuntime's hazard note: a checkpoint
        // on the management band jumps ahead of the still-staged replay frames
        // and compacts the journal to PRE-replay state).
        onDriver(BOOT_TIMEOUT_MS) {
            runtime.recover()
            runtime.afterQuiescence { runtime.completeRecovery() }
            if (transcriptFile != null) {
                val loaded = TranscriptLoader.load(transcriptFile)
                runtime.source.load(loaded.utterances)
                bootLoad = loaded.report
            }
            settle()
        }

        shell.route("/graph") { it.respond(200, graphJson(), "application/json") }
        shell.route("/transcript") { handleTranscript(it) }
        shell.sse("/events") { graphJson() }
    }

    // ------------------------------------------------------------------
    // Driver plumbing (2aw.5-D4)
    // ------------------------------------------------------------------

    /**
     * Run [block] on [driver] and wait for it, unwrapping the
     * [ExecutionException] wrapper so a caller sees the runtime's own
     * [OutOfOrderTurnException] / [DuplicateUtteranceIdException] /
     * `IllegalArgumentException` and can answer 400 with its message.
     */
    private fun <T> onDriver(timeoutMs: Long = ACTION_TIMEOUT_MS, block: () -> T): T =
        try {
            driver.submit(Callable { block() }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (wrapped: ExecutionException) {
            throw wrapped.cause ?: wrapped
        }

    /**
     * The fence (2aw.5-D5). **Must** be called from [driver].
     *
     * `reconcile()` appears nowhere else in this file, and never outside an
     * [DialogueRuntime.afterQuiescence] block.
     */
    private fun settle() {
        runtime.afterQuiescence {
            val report = runtime.reconcile()
            // The boot settle is by construction the first one, so this
            // records exactly what recovery had left to apply.
            if (bootReconcile == null) bootReconcile = report
            onSettled()
            broadcast()
        }
    }

    /**
     * Hook for computenet-2aw.5.3: `GET /transcript` serves a `@Volatile`
     * snapshot of per-utterance extraction status, and that snapshot must be
     * built **inside** the fence (2aw.5-D6) rather than read live off the
     * single-threaded ledgers. Deliberately a no-op here so T3's amend is an
     * insertion, not a re-plumbing.
     */
    private fun onSettled() {
        // computenet-2aw.5.3 fills this.
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    // The encoder is imported from civictech.agora, never redefined: one
    // definition of the wire shape is what keeps the two backends from
    // drifting apart (2aw.5-D3, [AGO1-OBS-01]).
    private fun graphJson(): String = graphJson(runtime.service.graph())

    private fun broadcast() = shell.broadcast { graphJson() }

    // ------------------------------------------------------------------
    // POST /transcript
    // ------------------------------------------------------------------

    private fun handleTranscript(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            // GET /transcript — per-utterance extraction status — is
            // computenet-2aw.5.3's, so this branch is a straight replacement
            // for it rather than something it has to unpick.
            return exchange.respond(501, "GET /transcript is not implemented yet (computenet-2aw.5.3)")
        }
        val params = formParams(exchange)
        try {
            when (val action = params["action"]) {
                "load" -> load(exchange, params)
                "replay" -> replay(exchange, params)
                "step" -> step(exchange)
                "reset" -> reset(exchange)
                else -> exchange.respond(400, "unknown action: ${action ?: "(none)"}")
            }
        } catch (e: OutOfOrderTurnException) {
            exchange.respond(400, e.message ?: "out-of-order turn")
        } catch (e: DuplicateUtteranceIdException) {
            exchange.respond(400, e.message ?: "duplicate utterance id")
        } catch (e: IllegalArgumentException) {
            exchange.respond(400, e.message ?: "bad request")
        }
    }

    private fun load(exchange: HttpExchange, params: Map<String, String>) {
        val path = params["path"] ?: return exchange.respond(400, "missing path")
        val report = onDriver {
            val file = File(path)
            require(file.isFile) { "no such transcript file: $path" }
            val loaded = TranscriptLoader.load(file)
            runtime.source.load(loaded.utterances)
            loaded.report
        }
        val issues = report.issues.joinToString(",") {
            """{"lineNumber":${it.lineNumber},"reason":${esc(it.reason)}}"""
        }
        exchange.respond(
            200,
            """{"loaded":${report.parsedCount},"rejected":${report.rejectedCount},"issues":[$issues]}""",
            "application/json",
        )
    }

    /**
     * Asynchronous by design (2aw.5-D4): a [Pace.Wallclock] replay sleeps for
     * the transcript's duration on [driver], and the shell's single dispatcher
     * thread must not be held for it. The reconcile-and-broadcast per
     * admission rides on T1's `afterAdmit` hook, so a subscriber watches the
     * map build utterance by utterance instead of in one jump at the end.
     */
    private fun replay(exchange: HttpExchange, params: Map<String, String>) {
        val from = params["from"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("from must be an integer, was '$it'")
        } ?: 0
        val to = params["to"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("to must be an integer, was '$it'")
        }
        val raw = params["pace"] ?: "max"
        val pace = if (raw == "max") {
            Pace.AsFastAsPossible
        } else {
            val factor = raw.toDoubleOrNull()?.takeIf { it > 0.0 }
                ?: throw IllegalArgumentException("pace must be 'max' or a positive number, was '$raw'")
            Pace.Wallclock(factor)
        }
        driver.execute {
            // A driver-side failure cannot reach the (already answered) client;
            // it is named on stderr rather than swallowed, and the next
            // synchronous action reports the state it left behind.
            try {
                runtime.source.replay(from = from, to = to, pace = pace, afterAdmit = { settle() })
            } catch (e: Exception) {
                System.err.println("dialogue: replay(from=$from, to=$to) failed: $e")
            }
        }
        exchange.respond(
            202,
            """{"accepted":true,"from":$from,"to":${to ?: "null"},"pace":${esc(raw)}}""",
            "application/json",
        )
    }

    private fun step(exchange: HttpExchange) {
        val admitted = onDriver {
            runtime.source.step()?.also { settle() }
        }
        val body = if (admitted == null) {
            """{"admitted":null}"""
        } else {
            """{"admitted":{"id":${esc(admitted.id)},"turn":${admitted.turn},""" +
                """"speaker":${esc(admitted.speaker)},"text":${esc(admitted.text)}}}"""
        }
        exchange.respond(200, body, "application/json")
    }

    /** [AGO1-REPLAY-03]/BS-19: retract everything, then settle it out of the graph. */
    private fun reset(exchange: HttpExchange) {
        onDriver {
            runtime.reset()
            settle()
        }
        exchange.respond(200, """{"reset":true}""", "application/json")
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun start(): DialogueApp = apply { shell.start() }

    fun stop() {
        shell.stop()
        driver.shutdownNow()
    }

    private companion object {
        /** Bound on a synchronous action; a hang here is a defect, not a budget. */
        const val ACTION_TIMEOUT_MS = 30_000L

        /** Boot recovers a journal and reconciles once, so it gets more room. */
        const val BOOT_TIMEOUT_MS = 120_000L

        /** `AgoraApp.handleOp`'s form parsing, verbatim. */
        fun formParams(exchange: HttpExchange): Map<String, String> =
            exchange.requestBody.readBytes().decodeToString()
                .split("&").filter { it.contains("=") }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to URLDecoder.decode(v, Charsets.UTF_8)
                }
    }
}

/**
 * `<port> --transcript <file> --journal <dir> --extractor rule|cassette
 * --cassette <file>`.
 *
 * The port is the first argument that does **not** start with `--`
 * (`demoPort`), so it has to come before the flags.
 */
fun main(args: Array<String>) {
    val port = demoPort(args)
    val transcript = args.value("--transcript")?.let { File(it) }
    val journalDir = args.value("--journal")?.let { File(it).apply { mkdirs() } }
    val extractor: Extractor = when (val kind = args.value("--extractor") ?: "rule") {
        "rule" -> RuleExtractor
        "cassette" -> CassetteExtractor.load(
            File(
                args.value("--cassette")
                    ?: error("--extractor cassette requires --cassette <file>"),
            ),
        )
        // No LlmExtractor here on purpose: epic computenet-2aw §8/R1 gates a
        // live model out of everything runnable from this repo.
        else -> error("--extractor must be 'rule' or 'cassette', was '$kind'")
    }

    val app = DialogueApp(port, extractor, transcript, journalDir).start()
    println("dialogue: http://localhost:${app.boundPort}")
    app.bootLoad?.let { println("  transcript: ${it.parsedCount} utterances loaded, ${it.rejectedCount} rejected") }
    println(
        if (journalDir != null) "  journaling to $journalDir (kill -9 safe)"
        else "  volatile mode; add --journal <dir> to survive restarts",
    )
    announcePort("http", app.boundPort)
}
