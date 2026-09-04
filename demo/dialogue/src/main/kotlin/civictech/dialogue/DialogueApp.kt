package civictech.dialogue

import civictech.agora.graphJson
import civictech.cell.CellRef
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import civictech.demo.shell.value
import civictech.dialogue.apply.ApplyFailure
import civictech.dialogue.apply.BoundKey
import civictech.dialogue.apply.ReconcileReport
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.Extractor
import civictech.dialogue.extract.RuleExtractor
import civictech.dialogue.extract.SegmentStatus
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.util.UUID
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
 *  - `GET /transcript` (computenet-2aw.5.3) — every loaded utterance with its
 *    per-utterance extraction status ([AGO1-OBS-03]) and counts.
 *  - `GET /provenance?ref=<cell ref>` or `?key=<canonical key>`
 *    (computenet-2aw.5.3, [AGO1-PROV-02]/[AGO1-PROV-04]) — the utterances
 *    behind a bound claim/relation, ref primary and key secondary
 *    (2aw.F5-D1, epic §8 open question (c)). An unbound ref/key answers `200
 *    {"bound":false}` — a structurally distinct, explicit "no provenance"
 *    rather than an error or an empty success.
 *
 * ### 2aw.5-D6 — status/provenance reads never touch the single-thread ledgers
 *
 * [ExtractionAccounting][civictech.dialogue.extract.ExtractionAccounting] and
 * [TranscriptSource] are "not thread-safe: drive from one thread", and an
 * HTTP handler runs on `DemoShell`'s dispatcher thread, not [driver]. So
 * `GET /transcript` and `GET /provenance` never read `runtime.accounting`,
 * `runtime.source` or `runtime.applier.accounting` directly — they read only
 * [transcriptSnapshot] (a `@Volatile` immutable snapshot built exclusively
 * inside [refreshSnapshot], which itself only ever runs on [driver]:
 * from [onSettled] inside the [settle] fence, from the boot sequence, and
 * from the synchronous `load` action), [DialogueRuntime.bindings]
 * (`synchronized`) and [DialogueRuntime.claimProvenance]/
 * [DialogueRuntime.relationProvenance] (backed by `ObserveCell.current()`,
 * itself a `@Volatile` snapshot). The snapshot can therefore lag live state
 * by at most the one utterance currently being settled. The one field
 * [handleTranscriptGet] does *not* take from the snapshot is `replaying`: it
 * reads the live `@Volatile` [replayInFlight], because a lifecycle bit whose
 * whole purpose is "has this finished yet" must not lag by a settle
 * (computenet-xqp9).
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
 * `reconcile()` → **drain again** → [onSettled] → [broadcast], so the frame a
 * subscriber sees after an admission carries the **post**-reconciliation
 * graph — the newly bound claim ref is already in it — rather than the graph
 * as it stood before the applier ran.
 *
 * The second drain is computenet-xqp9's fix and is not symmetry for its own
 * sake. `reconcile()` publishes its new nodes into `AgoraService.nodes`
 * (whence `/graph` serves them at once) but only *stages* the propagation
 * that gives them their credences, so without it the fence published a graph
 * that was structurally complete and numerically stale — an edge already
 * visible while its target still read its unattacked value. With it, "a
 * settle has completed" means the reconcile's own waves have landed, which is
 * what makes `GET /transcript`'s `"replaying":false` a usable *converged*
 * signal. It fences the settle's own effects only: `/graph` is still a live
 * read, and an `onCredence` broadcast chasing an unrelated stance write is
 * still mid-wave by construction.
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

    /**
     * The currently drawable transcript ([TranscriptLoader.load]'s
     * `utterances`), i.e. what the most recent boot `--transcript` or
     * `action=load` handed [TranscriptSource.load] — DialogueApp's own
     * bookkeeping, not [TranscriptSource]'s (which tracks *admission*, not
     * the loaded file). Read only from [driver] (2aw.5-D6).
     */
    private var loadedTranscript: List<Utterance> = emptyList()

    /**
     * Every [ApplyFailure] any [settle] has recorded, oldest first
     * (`GraphApplier`'s own [civictech.dialogue.apply.ApplyAccounting] is
     * off-limits from here — see the class doc — so this is a local, driver-
     * thread-only mirror of the same cumulative idea, scoped to what this
     * app has observed). Read/written only from [driver].
     */
    private val applyFailuresLedger = mutableListOf<ApplyFailure>()

    /** Set right before an async `replay` starts, cleared once it finishes. */
    @Volatile
    private var replayInFlight: Boolean = false

    /**
     * The `@Volatile` snapshot [handleTranscriptGet] and [handleProvenance]
     * read exclusively (2aw.5-D6). Built only by [refreshSnapshot], which
     * runs only on [driver]. The all-empty default is overwritten by the
     * boot [settle] before [start] can ever expose it to a request.
     */
    @Volatile
    private var transcriptSnapshot: TranscriptSnapshot = TranscriptSnapshot(
        loadedUtterances = emptyList(),
        utteranceDtos = emptyList(),
        counts = Counts(pending = 0, extracted = 0, rejected = 0, failed = 0),
        rejectedDtos = emptyList(),
        failedDtos = emptyList(),
        applyFailureDtos = emptyList(),
        admittedCount = 0,
        replaying = false,
    )

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
                loadedTranscript = loaded.utterances
            }
            settle()
        }

        shell.route("/graph") { it.respond(200, graphJson(), "application/json") }
        shell.route("/transcript") { handleTranscript(it) }
        shell.route("/provenance") { handleProvenance(it) }
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
        var reconciled: ReconcileReport? = null
        runtime.afterQuiescence { reconciled = runtime.reconcile() }
        val report = reconciled!!

        // computenet-xqp9: a *second* fence, because `reconcile()` does not
        // finish the work it starts. Its `createClaim`/`createEdge` calls
        // publish nodes into `AgoraService.nodes` — whence `/graph` serves
        // them immediately — and only *stage* the propagation that gives
        // those nodes their credences. So at the instant `reconcile()`
        // returns, the graph is structurally complete and numerically stale:
        // for the three-turn fixture, the ATTACK edge is already visible
        // while its target still reads its unattacked 0.5 rather than the
        // DF-QuAD 0.3. Publishing there is what made `[AGO1-OBS-02]`'s frame
        // — and every read taken just after a settle — a mid-wave sample, and
        // it is what reddened `build-test-fast` three times on 2026-09-04
        // (0.3 where 0.5 had been captured).
        //
        // Draining again before [onSettled]/[broadcast] makes "a settle has
        // completed" mean what callers already read it as: the reconcile's
        // own waves have landed. That gives the surface a *positive*
        // convergence signal — once `GET /transcript` reports
        // `"replaying":false`, the final settle of that replay has drained,
        // so `/graph` is converged — rather than an "it has probably stopped
        // moving by now" wait. Note this fences the settle's own effects
        // only: `/graph` remains a live read, and an `onCredence` broadcast
        // from an unrelated stance write is still mid-wave by construction.
        runtime.afterQuiescence {
            // The boot settle is by construction the first one, so this
            // records exactly what recovery had left to apply.
            if (bootReconcile == null) bootReconcile = report
            onSettled(report)
            broadcast()
        }
    }

    /**
     * computenet-2aw.5.3 (2aw.5-D6/D7): rebuild [transcriptSnapshot] here,
     * inside the fence, on [driver] — the one moment every ledger this reads
     * (`runtime.accounting`, `runtime.source`) is quiescent and safe to read
     * directly. [report]'s failures join [applyFailuresLedger] before the
     * snapshot is rebuilt so the status surface accumulates across settles,
     * mirroring `ApplyAccounting`'s own cumulative shape without reaching
     * into it (`runtime.applier.accounting` stays off-limits to everything
     * outside [DialogueRuntime]/`GraphApplier`, per the class doc).
     */
    private fun onSettled(report: ReconcileReport) {
        applyFailuresLedger += report.failures
        refreshSnapshot()
    }

    /**
     * Rebuild [transcriptSnapshot] from [loadedTranscript],
     * `runtime.source.admitted` and `runtime.accounting` — **must** run on
     * [driver] (called from [onSettled], inside the boot sequence, and from
     * the synchronous `load` action; never from an HTTP handler thread).
     *
     * 2aw.5-D7 — one loaded utterance's status folds over its segments'
     * statuses with precedence failed > rejected > pending > extracted; a
     * blank-text utterance segments to zero segments and folds to
     * `extracted` (the empty-list branch of [foldStatus]). An unadmitted
     * utterance is `pending` without inspecting its segments at all — there
     * is nothing in [ExtractionAccounting] to inspect, since nothing has
     * been offered yet.
     */
    private fun refreshSnapshot() {
        val admittedIds = runtime.source.admitted.map { it.id }.toSet()
        val utteranceDtos = loadedTranscript.map { utterance -> utteranceDto(utterance, utterance.id in admittedIds) }
        transcriptSnapshot = TranscriptSnapshot(
            loadedUtterances = loadedTranscript,
            utteranceDtos = utteranceDtos,
            counts = Counts(
                pending = utteranceDtos.count { it.status == STATUS_PENDING },
                extracted = utteranceDtos.count { it.status == STATUS_EXTRACTED },
                rejected = utteranceDtos.count { it.status == STATUS_REJECTED },
                failed = utteranceDtos.count { it.status == STATUS_FAILED },
            ),
            rejectedDtos = runtime.accounting.rejected.map { RejectedDto(it.segmentId, it.reason) },
            failedDtos = runtime.accounting.failed.map { FailedDto(it.segmentId, it.reason) },
            applyFailureDtos = applyFailuresLedger.map {
                ApplyFailureDto(kind = it.kind.name.lowercase(), key = it.key, reason = it.reason)
            },
            admittedCount = admittedIds.size,
            replaying = replayInFlight,
        )
    }

    private fun utteranceDto(utterance: Utterance, admitted: Boolean): UtteranceDto {
        if (!admitted) {
            return UtteranceDto(
                id = utterance.id,
                turn = utterance.turn,
                speaker = utterance.speaker,
                text = utterance.text,
                admitted = false,
                status = STATUS_PENDING,
                segments = emptyList(),
            )
        }
        val segmentDtos = segment(utterance).map(::segmentDto)
        return UtteranceDto(
            id = utterance.id,
            turn = utterance.turn,
            speaker = utterance.speaker,
            text = utterance.text,
            admitted = true,
            status = foldStatus(segmentDtos.map { it.status }),
            segments = segmentDtos,
        )
    }

    private fun segmentDto(segment: Segment): SegmentDto {
        val raw = runtime.accounting.status(segment.id)
        val rejectedReasons = runtime.accounting.rejected.filter { it.segmentId == segment.id }.map { it.reason }
        return when {
            raw is SegmentStatus.Failed -> SegmentDto(segment.id, STATUS_FAILED, raw.reason)
            rejectedReasons.isNotEmpty() -> SegmentDto(segment.id, STATUS_REJECTED, rejectedReasons.joinToString("; "))
            raw is SegmentStatus.Unknown -> SegmentDto(segment.id, STATUS_PENDING, null)
            else -> SegmentDto(segment.id, STATUS_EXTRACTED, null)
        }
    }

    /**
     * 2aw.5-D7's fold, precedence failed > rejected > pending > extracted.
     *
     * **`internal`, not `private` (computenet-kygh).** `pending` IS
     * deterministically reachable for an admitted utterance through
     * [DialogueApp]'s HTTP surface — computenet-kygh's claim that this was
     * structurally impossible across the whole action surface was wrong
     * (falsified by computenet-miei). Every settle()-fenced action —
     * `step`/`reset`, the boot load, and each admission of a `replay`
     * (its `afterAdmit` settles every one) — does drain the whole host
     * queue via [settle] before [refreshSnapshot] runs, so segmentation and
     * extraction for an admitted utterance are complete by the time those
     * paths rebuild a snapshot. But [load] calls [refreshSnapshot] directly,
     * deliberately OUTSIDE any [settle] fence (see its own comment) — as
     * does `replay`'s `finally`, though its own admissions are settled by
     * `afterAdmit`, so it introduces no new unextracted segment — and
     * [TranscriptSource.load] leaves the admitted
     * ledger untouched by design ("Loading is therefore not a reset"). So
     * `step` admitting `u1`, followed by `load` of a transcript in which
     * `u1` (same id, same turn) now carries additional text, re-segments
     * `u1` against a segment [civictech.dialogue.extract.ExtractionAccounting]
     * never saw: [segmentDto] reads that segment's `SegmentStatus.Unknown`
     * as `pending`, with no race — see `DialogueAppTest`'s
     * `computenet-miei` HTTP-level test, which drives exactly that sequence
     * and asserts the folded `pending`.
     *
     * That HTTP fixture pins `pending > extracted` only: it never puts a
     * `rejected` segment alongside the load-introduced `pending` one in the
     * same utterance. Those remaining rungs are **unpinned there, not
     * unreachable** — measured at computenet-miei's review, `rejected >
     * pending` falls out of the *same* two calls with a cassette whose
     * segment-0 claim is blank (`[rejected, pending]` -> `rejected`), and
     * the empty-list `-> extracted` branch needs only a `step` on a
     * blank-text utterance (as [refreshSnapshot]'s own KDoc already says).
     * So `internal` stays for a narrower reason than reachability: the
     * direct `foldStatus` precedence test below is what actually pins those
     * rungs today, and it needs this visibility. Extending the HTTP fixture
     * to cover them would remove that reason; that is filed, not assumed
     * away.
     */
    internal fun foldStatus(statuses: List<String>): String = when {
        statuses.any { it == STATUS_FAILED } -> STATUS_FAILED
        statuses.any { it == STATUS_REJECTED } -> STATUS_REJECTED
        statuses.any { it == STATUS_PENDING } -> STATUS_PENDING
        else -> STATUS_EXTRACTED
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
        if (exchange.requestMethod == "GET") {
            return handleTranscriptGet(exchange)
        }
        if (exchange.requestMethod != "POST") {
            return exchange.respond(405, "method not allowed: ${exchange.requestMethod}")
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
            loadedTranscript = loaded.utterances
            // 2aw.5-D6: the loaded set changed even though nothing was
            // admitted, so the snapshot needs a refresh here too — on
            // [driver], same as every other refresh, but outside a
            // [settle]/[afterQuiescence] fence: `load` touches only this
            // app's own bookkeeping and `TranscriptSource`, never the host.
            refreshSnapshot()
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
        replayInFlight = true
        driver.execute {
            // A driver-side failure cannot reach the (already answered) client;
            // it is named on stderr rather than swallowed, and the next
            // synchronous action reports the state it left behind.
            try {
                runtime.source.replay(from = from, to = to, pace = pace, afterAdmit = { settle() })
            } catch (e: Exception) {
                System.err.println("dialogue: replay(from=$from, to=$to) failed: $e")
            } finally {
                replayInFlight = false
                // A range with no admissions leaves settle() (and so
                // onSettled/refreshSnapshot) uncalled; refresh here on
                // driver so the snapshot's `replaying` flag still clears.
                refreshSnapshot()
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
    // GET /transcript — computenet-2aw.5.3, [AGO1-OBS-03]
    // ------------------------------------------------------------------

    /**
     * Reads [transcriptSnapshot] only (2aw.5-D6) — never `runtime.accounting`
     * or `runtime.source` directly, on an HTTP handler thread.
     */
    private fun handleTranscriptGet(exchange: HttpExchange) {
        val snapshot = transcriptSnapshot
        val response = TranscriptResponse(
            loaded = snapshot.loadedUtterances.size,
            admitted = snapshot.admittedCount,
            // The live @Volatile flag, not `snapshot.replaying`
            // (computenet-xqp9). `replayInFlight` is set before the 202 that
            // starts a replay and cleared in that replay's `finally`, after
            // its last `settle()` has returned; the snapshot's copy only
            // catches up at the *next* refresh, so a caller polling for
            // "the replay has finished" would read a stale `false` for the
            // whole window between the 202 and the first settle. Since
            // `settle()` now drains the reconcile's own waves before
            // returning, `"replaying":false` is the surface's positive
            // "the graph has converged" signal — it has to be exact.
            replaying = replayInFlight,
            counts = snapshot.counts,
            utterances = snapshot.utteranceDtos,
            rejected = snapshot.rejectedDtos,
            failed = snapshot.failedDtos,
            applyFailures = snapshot.applyFailureDtos,
        )
        exchange.respond(200, Json.encodeToString(TranscriptResponse.serializer(), response), "application/json")
    }

    // ------------------------------------------------------------------
    // GET /provenance — computenet-2aw.5.3, 2aw.F5-D1, [AGO1-PROV-02]/[AGO1-PROV-04]
    // ------------------------------------------------------------------

    /**
     * `?ref=<uuid>` is primary, `?key=<canonical key>` is the secondary
     * lookup (2aw.F5-D1, epic §8 open question (c)). Neither present, or a
     * non-UUID `ref`, is 400. An unbound ref/key answers 200
     * `{"bound":false}` — deliberately not 404: F5's non-goal list bars a
     * provenance panel, but the shape this answers with is the one such a
     * panel would need to tell "nothing here yet" from "bound, zero
     * sources" ([AGO1-PROV-04]), which a 404 cannot do without inventing a
     * body convention of its own.
     *
     * Reads [transcriptSnapshot], `runtime.bindings` and
     * `runtime.claimProvenance`/`runtime.relationProvenance` only (2aw.5-D6)
     * — the three surfaces documented safe for an HTTP handler thread.
     */
    private fun handleProvenance(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            return exchange.respond(405, "method not allowed: ${exchange.requestMethod}")
        }
        val params = queryParams(exchange.requestURI.rawQuery)
        val ref = params["ref"]
        val key = params["key"]
        when {
            ref != null -> respondProvenanceByRef(exchange, ref)
            key != null -> respondProvenanceByKey(exchange, key)
            else -> exchange.respond(400, "missing ref or key")
        }
    }

    private fun respondProvenanceByRef(exchange: HttpExchange, ref: String) {
        val uuid = try {
            UUID.fromString(ref)
        } catch (e: IllegalArgumentException) {
            return exchange.respond(400, "ref must be a UUID, was '$ref'")
        }
        when (val bound = runtime.bindings.keyOf(CellRef(uuid))) {
            null -> exchange.respond(200, """{"ref":${esc(ref)},"bound":false}""", "application/json")
            is BoundKey.OfClaim ->
                respondBound(exchange, ref, "claim", bound.key.value, runtime.claimProvenance(bound.key) ?: emptySet())
            is BoundKey.OfRelation ->
                respondBound(
                    exchange,
                    ref,
                    "relation",
                    bound.key.value,
                    runtime.relationProvenance(bound.key) ?: emptySet(),
                )
        }
    }

    private fun respondProvenanceByKey(exchange: HttpExchange, key: String) {
        val claimKey = ClaimKey(key)
        val claimRef = runtime.bindings.refOf(claimKey)
        if (claimRef != null) {
            return respondBound(
                exchange,
                claimRef.id.toString(),
                "claim",
                key,
                runtime.claimProvenance(claimKey) ?: emptySet(),
            )
        }
        val relationKey = RelationKey(key)
        val relationRef = runtime.bindings.refOf(relationKey)
        if (relationRef != null) {
            return respondBound(
                exchange,
                relationRef.id.toString(),
                "relation",
                key,
                runtime.relationProvenance(relationKey) ?: emptySet(),
            )
        }
        exchange.respond(200, """{"key":${esc(key)},"bound":false}""", "application/json")
    }

    /**
     * The always-carries-`utterances` shape ([AGO1-PROV-04]'s discriminator
     * is `bound` alone, so this branch always includes the field, empty or
     * not). [ids] resolves against [transcriptSnapshot]'s loaded list in
     * turn order; an id the snapshot cannot resolve is emitted as `{"id":…}`
     * alone, per 2aw.5-D8, rather than silently dropped.
     */
    private fun respondBound(exchange: HttpExchange, ref: String, kind: String, key: String, ids: Set<String>) {
        val byId = transcriptSnapshot.loadedUtterances.associateBy { it.id }
        val resolved = ids.mapNotNull { byId[it] }.sortedBy { it.turn }
        val unresolved = ids - resolved.map { it.id }.toSet()
        val utterancesJson = (
            resolved.map {
                """{"id":${esc(it.id)},"turn":${it.turn},"speaker":${esc(it.speaker)},"text":${esc(it.text)}}"""
            } + unresolved.map { """{"id":${esc(it)}}""" }
            ).joinToString(",")
        exchange.respond(
            200,
            """{"ref":${esc(ref)},"bound":true,"kind":${esc(kind)},"key":${esc(key)},"utterances":[$utterancesJson]}""",
            "application/json",
        )
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun start(): DialogueApp = apply { shell.start() }

    /**
     * Graceful first, interrupting only as a backstop (computenet-t3sp).
     *
     * `shutdownNow()` alone interrupts whatever the driver is running, and a
     * driver task is where the durable record of a structure op is written:
     * `AgoraService.createEdge` publishes the edge into the node map (so
     * `/graph` can already serve it) and appends to `graph.jsonl` afterwards.
     * An interrupt taken between those two points leaves an edge that the
     * first process served and the next process cannot recover — the
     * lost-EDGE shape computenet-t3sp recorded. Draining the driver first
     * lets an in-flight settle finish; the interrupt stays as the bound, so a
     * genuinely wedged driver still cannot hold a `stop()` open.
     */
    fun stop() {
        shell.stop()
        driver.shutdown()
        if (!driver.awaitTermination(STOP_DRAIN_MS, TimeUnit.MILLISECONDS)) driver.shutdownNow()
    }

    private companion object {
        /** Bound on a synchronous action; a hang here is a defect, not a budget. */
        const val ACTION_TIMEOUT_MS = 30_000L

        /** Boot recovers a journal and reconciles once, so it gets more room. */
        const val BOOT_TIMEOUT_MS = 120_000L

        /**
         * How long [stop] lets the driver drain before interrupting it. Sized
         * against a single in-flight settle, not against a replay: a
         * `Pace.Wallclock` replay holds the driver for the transcript's
         * duration and is expected to be cut short.
         */
        const val STOP_DRAIN_MS = 5_000L

        /** `AgoraApp.handleOp`'s form parsing, verbatim. */
        fun formParams(exchange: HttpExchange): Map<String, String> =
            exchange.requestBody.readBytes().decodeToString()
                .split("&").filter { it.contains("=") }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to URLDecoder.decode(v, Charsets.UTF_8)
                }

        /** [formParams]'s query-string mirror, for `GET /provenance?ref=…&key=…`. */
        fun queryParams(raw: String?): Map<String, String> =
            (raw ?: "").split("&").filter { it.contains("=") }
                .associate {
                    val (k, v) = it.split("=", limit = 2)
                    URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
                }

        // ------------------------------------------------------------------
        // Extraction status vocabulary (2aw.5-D7)
        // ------------------------------------------------------------------

        const val STATUS_PENDING = "pending"
        const val STATUS_EXTRACTED = "extracted"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_FAILED = "failed"
    }
}

// ------------------------------------------------------------------
// computenet-2aw.5.3 — the fenced snapshot and its wire DTOs
// ------------------------------------------------------------------

/**
 * The `@Volatile` snapshot `GET /transcript` and `GET /provenance` read
 * exclusively (2aw.5-D6). Built only inside [DialogueApp.refreshSnapshot],
 * which runs only on [DialogueApp]'s driver thread.
 */
private data class TranscriptSnapshot(
    /** The currently loaded transcript, in file/turn order — [DialogueApp.loadedTranscript]. */
    val loadedUtterances: List<Utterance>,
    /** One entry per [loadedUtterances], same order. */
    val utteranceDtos: List<UtteranceDto>,
    val counts: Counts,
    /** [civictech.dialogue.extract.ExtractionAccounting.rejected], DTO'd. */
    val rejectedDtos: List<RejectedDto>,
    /** [civictech.dialogue.extract.ExtractionAccounting.failed], DTO'd. */
    val failedDtos: List<FailedDto>,
    /** [DialogueApp.applyFailuresLedger], DTO'd. */
    val applyFailureDtos: List<ApplyFailureDto>,
    val admittedCount: Int,
    /** Whether an async `POST /transcript?action=replay` is currently in flight. */
    val replaying: Boolean,
)

@Serializable
private data class SegmentDto(val id: String, val status: String, val reason: String? = null)

@Serializable
private data class UtteranceDto(
    val id: String,
    val turn: Int,
    val speaker: String,
    val text: String,
    val admitted: Boolean,
    val status: String,
    val segments: List<SegmentDto>,
)

@Serializable
private data class Counts(val pending: Int, val extracted: Int, val rejected: Int, val failed: Int)

@Serializable
private data class RejectedDto(val segmentId: String, val reason: String)

@Serializable
private data class FailedDto(val segmentId: String, val reason: String)

@Serializable
private data class ApplyFailureDto(val kind: String, val key: String, val reason: String)

@Serializable
private data class TranscriptResponse(
    val loaded: Int,
    val admitted: Int,
    val replaying: Boolean,
    val counts: Counts,
    val utterances: List<UtteranceDto>,
    val rejected: List<RejectedDto>,
    val failed: List<FailedDto>,
    val applyFailures: List<ApplyFailureDto>,
)

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
