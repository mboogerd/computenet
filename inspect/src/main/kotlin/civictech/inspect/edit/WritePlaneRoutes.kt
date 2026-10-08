package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.graph.StepEvent
import civictech.cell.host.ManagedHost
import civictech.demo.shell.respond
import civictech.inspect.Event
import civictech.inspect.InspectorModel
import civictech.inspect.InspectorServer
import civictech.inspect.inspectorJson
import civictech.inspect.tailSegments
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/**
 * The write plane's five routes (WKB2 F6, `computenet-wczst`, wczst-D3/D4/D8)
 * — every handler body lives here, and [InspectorServer] only registers and
 * delegates, so every graph-mutating path of the inspector is under `edit/`
 * (`[WKB2-01]`):
 *
 * - `POST /apply/precheck` — body a [DraftDto]; `200` [PlanDto]. No side
 *   effect: no id, no record, no audit entry, no event.
 * - `POST /apply` — body an [ApplyRequestDto]; `202` [ApplyAcceptedDto], or
 *   `422` [RefusedAtPrecheckDto] when the plan is not appliable.
 * - `GET /apply/{id}` — the [ApplyRecord] itself (wczst-D4); `404` unknown.
 * - `POST /apply/{id}/abort` — `202` with the record while that apply is in
 *   STAGE; `409` otherwise; `404` unknown. The outcome follows on `apply.done`.
 * - `GET /applies` — [AppliesDto] from the [AuditRing], oldest first.
 *
 * **Every one calls [WriteGate.admit] first** — the GETs included — and
 * answers a refusal before the body is read or the path is looked at, so a
 * disabled plane is `404 write plane disabled` on all five.
 *
 * Caller faults are `400` with a `{"reason"}` body: an undecodable body is
 * `malformed body`; a [DraftException] carries its own reason; an unresolvable
 * host or despawn ref is described below ([resolve]); and an
 * `IllegalArgumentException` from [StagedApplier.plan] — a despawn listed
 * twice, say — is its message (the wczst.1 review's AMENDS: it is a caller
 * fault, never a 500).
 *
 * [StagedApplier.apply] is never called on an HTTP dispatch thread: it runs on
 * its own virtual thread, `inspector-apply-<id>`, and the terminal record goes
 * into [ring] when it returns.
 */
internal class WritePlaneRoutes(
    private val gate: WriteGate,
    private val applier: StagedApplier,
    private val ring: AuditRing,
    private val hosts: Map<String, ManagedHost>,
) {

    /** `/apply/…`, dispatched on method + tail segments like `InspectorServer.serveCell`. */
    fun serveApply(exchange: HttpExchange) {
        val identity = when (val admission = gate.admit(exchange)) {
            is WriteGate.Admission.Refused -> return exchange.respondRefusal(admission)
            is WriteGate.Admission.Admitted -> admission.identity
        }
        val tail = exchange.tailSegments(InspectorServer.APPLY_PATH)
        val method = exchange.requestMethod
        try {
            when {
                tail == listOf(PRECHECK) && method == "POST" -> precheck(exchange)
                tail == listOf(PRECHECK) -> exchange.respondProblem(404, APPLY_SHAPE)
                tail.isEmpty() && method == "POST" -> apply(exchange, identity)
                tail.size == 1 && method == "GET" -> record(exchange, tail[0])
                tail.size == 2 && tail[1] == ABORT && method == "POST" -> abort(exchange, tail[0])
                else -> exchange.respondProblem(404, APPLY_SHAPE)
            }
        } catch (refusal: RouteRefusal) {
            exchange.respondProblem(refusal.status, refusal.reason)
        }
    }

    /** `/applies` — `GET` only. */
    fun serveApplies(exchange: HttpExchange) {
        when (val admission = gate.admit(exchange)) {
            is WriteGate.Admission.Refused -> return exchange.respondRefusal(admission)
            is WriteGate.Admission.Admitted -> Unit
        }
        if (exchange.requestMethod != "GET") return exchange.respondProblem(404, "expected GET /applies")
        exchange.respondJson(200, inspectorJson.encodeToString(AppliesDto.serializer(), AppliesDto(ring.entries())))
    }

    // ---- handlers -------------------------------------------------------------

    private fun precheck(exchange: HttpExchange) {
        val dto = decodeBody(exchange) { inspectorJson.decodeFromJsonElement(DraftDto.serializer(), it) }
        val plan = when (val resolved = resolve(dto)) {
            is Resolved.Refused -> resolved.plan
            is Resolved.Ready -> planOf(resolved.draft)
        }
        exchange.respondJson(200, inspectorJson.encodeToString(PlanDto.serializer(), plan))
    }

    /**
     * wczst-D3, in this order: parse → compile/resolve → plan → (422 | mint an
     * id, start the apply thread, 202). F7 (`baseSeq` check, in-flight
     * refusal) and F8 (confirmation) slot in between parse and plan.
     */
    private fun apply(exchange: HttpExchange, identity: String) {
        lateinit var submitted: JsonElement
        val request = decodeBody(exchange) { body ->
            submitted = body.jsonObject["draft"] ?: throw IllegalArgumentException("no draft")
            inspectorJson.decodeFromJsonElement(ApplyRequestDto.serializer(), body)
        }
        val draft = when (val resolved = resolve(request.draft)) {
            is Resolved.Refused -> return exchange.respondRefused(resolved.plan)
            is Resolved.Ready -> resolved.draft
        }
        val plan = planOf(draft)
        if (!plan.appliable) return exchange.respondRefused(plan)

        val applyId = UUID.randomUUID().toString()
        val worker = Thread.ofVirtual().name("inspector-apply-$applyId").start {
            runCatching { applier.apply(draft, applyId, identity, request.baseSeq, submittedDraft = submitted) }
                .onSuccess(ring::record)
                // this module has no logger (the startup note in InspectorServer uses println too)
                .onFailure { System.err.println("inspector apply $applyId failed: $it") }
        }
        // The applier registers the record before it takes its apply lock
        // (wczst-D2.4), but on the worker thread: wait for that, so the id this
        // 202 hands out is readable through GET /apply/{id} from the moment the
        // client has it. admit() and the registration are pure bookkeeping, so
        // this wait is short; it ends early only if the worker died first.
        while (applier.record(applyId) == null && worker.isAlive) LockSupport.parkNanos(REGISTRATION_POLL_NANOS)
        if (applier.record(applyId) == null) {
            return exchange.respondProblem(500, "apply $applyId failed before it was registered")
        }
        exchange.respondJson(202, inspectorJson.encodeToString(ApplyAcceptedDto.serializer(), ApplyAcceptedDto(applyId)))
    }

    private fun record(exchange: HttpExchange, applyId: String) {
        val record = applier.record(applyId) ?: return exchange.respondProblem(404, "unknown apply: $applyId")
        exchange.respondJson(200, inspectorJson.encodeToString(ApplyRecord.serializer(), record))
    }

    /** wczst-D4: the 202 body is the current record (phase STAGE); the outcome follows on `apply.done`. */
    private fun abort(exchange: HttpExchange, applyId: String) {
        applier.record(applyId) ?: return exchange.respondProblem(404, "unknown apply: $applyId")
        if (!applier.abort(applyId)) return exchange.respondProblem(409, "apply $applyId is not in STAGE")
        val record = checkNotNull(applier.record(applyId))
        exchange.respondJson(202, inspectorJson.encodeToString(ApplyRecord.serializer(), record))
    }

    // ---- request resolution ---------------------------------------------------

    /** A draft ready to plan, or the compiler's own refusal plan (a node that did not resolve). */
    private sealed interface Resolved {
        data class Ready(val draft: Draft) : Resolved
        data class Refused(val plan: PlanDto) : Resolved
    }

    /**
     * [DraftCompiler.compile] (a [DraftException] is 400 with its reason), then
     * wczst-D1's mapping of [DraftDto.host] and [DraftDto.despawns]: a null
     * host is the sole host, or 400 when there are several; an unknown host is
     * 400; an undecodable despawn ref is 400.
     */
    private fun resolve(dto: DraftDto): Resolved {
        val compiled = try {
            DraftCompiler.compile(dto)
        } catch (e: DraftException) {
            throw RouteRefusal(400, e.reason)
        }
        val ok = when (compiled) {
            is Compiled.Refused -> return Resolved.Refused(compiled.plan.toDto())
            is Compiled.Ok -> compiled
        }
        val host = dto.host
            ?: hosts.keys.singleOrNull()
            ?: throw RouteRefusal(400, "draft.host is required: this inspector has ${hosts.size} hosts")
        if (host !in hosts) throw RouteRefusal(400, "unknown host '$host'")
        val despawns: List<CellRef> = dto.despawns.mapIndexed { i, encoded ->
            InspectorServer.decodeRef(encoded)
                ?: throw RouteRefusal(400, "despawns[$i]: '$encoded' is not an encoded cell ref (\"<uuid>:<instanceId>\")")
        }
        val promotions = dto.promotions.mapIndexed(::promotion)
        return Resolved.Ready(Draft(host, ok.spec, ok.boundary, despawns, promotions))
    }

    /** Maps the wire promotion shape after the ordinary graph has compiled. */
    private fun promotion(index: Int, dto: PromotionRequestDto): PromotionRequest {
        val incumbent = encodedRef(dto.incumbent, "promotions[$index].incumbent")
        val gate = dto.gate?.let { encodedRef(it, "promotions[$index].gate") }
        val replicaCandidate = dto.replicaCandidate?.let {
            try {
                DraftCompiler.resolveFactory(
                    catalogueId = it.catalogueId,
                    params = it.params,
                    handle = "promotions[$index].replicaCandidate",
                )
            } catch (e: DraftException) {
                throw RouteRefusal(400, e.reason)
            }
        }
        val policy = dto.policy?.let {
            try {
                PromotionPolicy(
                    gates = emptyList(),
                    window = ObservationWindow(it.windowWaves),
                    judge = it.judge,
                )
            } catch (e: IllegalArgumentException) {
                throw RouteRefusal(400, e.message ?: e.toString())
            }
        }
        return try {
            PromotionRequest(
                incumbent = incumbent,
                outletName = dto.outletName,
                gate = gate,
                candidateHandle = dto.candidate,
                replicaCandidate = replicaCandidate,
                policy = policy,
            )
        } catch (e: IllegalArgumentException) {
            throw RouteRefusal(400, e.message ?: e.toString())
        }
    }

    private fun encodedRef(encoded: String, field: String): CellRef =
        InspectorServer.decodeRef(encoded)
            ?: throw RouteRefusal(400, "$field: '$encoded' is not an encoded cell ref (\"<uuid>:<instanceId>\")")

    /** [StagedApplier.plan]; its `IllegalArgumentException` is a caller fault, answered 400 (AMENDS wczst.1). */
    private fun planOf(draft: Draft): PlanDto = try {
        applier.plan(draft)
    } catch (e: IllegalArgumentException) {
        throw RouteRefusal(400, e.message ?: e.toString())
    }

    /** The body parsed as JSON and handed to [decode]; any failure of either is `400 malformed body`. */
    private fun <T> decodeBody(exchange: HttpExchange, decode: (JsonElement) -> T): T {
        val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
        return try {
            decode(inspectorJson.parseToJsonElement(body))
        } catch (e: RuntimeException) {
            // SerializationException and IllegalArgumentException both land here
            throw RouteRefusal(400, MALFORMED_BODY)
        }
    }

    /** A caller fault found while resolving a request; caught at the top of [serveApply]. */
    private class RouteRefusal(val status: Int, val reason: String) : RuntimeException(reason, null, false, false)

    private fun HttpExchange.respondRefused(plan: PlanDto) =
        respondJson(422, inspectorJson.encodeToString(RefusedAtPrecheckDto.serializer(), RefusedAtPrecheckDto(plan = plan)))

    private fun HttpExchange.respondProblem(status: Int, reason: String) =
        respondJson(status, InspectorServer.problem(reason))

    private fun HttpExchange.respondJson(status: Int, body: String) = respond(status, body, JSON)

    companion object {
        const val PRECHECK = "precheck"
        const val ABORT = "abort"
        const val MALFORMED_BODY = "malformed body"
        const val APPLY_SHAPE =
            "expected POST /apply/precheck, POST /apply, GET /apply/{id} or POST /apply/{id}/abort"
        private const val JSON = "application/json"
        private const val REGISTRATION_POLL_NANOS = 100_000L

        /**
         * The server half of wczst-D5/D6: turns [StagedApplier]'s observations
         * into `apply.*` events and staged-mark restamps on [model]. Built
         * before the applier (the applier takes it as a constructor argument),
         * which is why it is not a member of a [WritePlaneRoutes] instance.
         *
         * On `onPhase(RETIRE)` the phase event goes out **first** and the
         * restamps after it, so a client sees the apply leave STAGE before the
         * marks clear. UNWIND restamps nothing: its cells keep their mark
         * until their own `removed` events.
         *
         * A failure to emit is printed and swallowed — the listener is called
         * synchronously on the applying thread, and an instrument's emission
         * fault must not decide an apply's outcome.
         */
        fun listener(model: InspectorModel): ApplyListener = object : ApplyListener {
            override fun onPhase(record: ApplyRecord) = guarded(record.applyId) {
                model.applyEvent(Event.APPLY_PHASE, applyPhasePayload(record))
                if (record.phase == ApplyPhase.RETIRE) {
                    record.stagedRefs.mapNotNull(InspectorServer::decodeRef).forEach(model::restamp)
                }
            }

            override fun onStep(applyId: String, event: StepEvent) = guarded(applyId) {
                model.applyEvent(Event.APPLY_STEP, applyStepPayload(applyId, event))
            }

            override fun onStaged(applyId: String, ref: CellRef) = guarded(applyId) { model.restamp(ref) }

            override fun onDone(record: ApplyRecord) = guarded(record.applyId) {
                model.applyEvent(Event.APPLY_DONE, applyDonePayload(record))
            }
        }

        private inline fun guarded(applyId: String, emit: () -> Unit) {
            try {
                emit()
            } catch (e: RuntimeException) {
                System.err.println("inspector apply $applyId: event emission failed: $e")
            }
        }
    }
}
