package civictech.demo.beadsmirror.http

import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.PollLoopStopped
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.shell.DemoShell
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import com.sun.net.httpserver.HttpExchange

/**
 * The HTTP read surface over [MirrorProjector] (computenet-dqj.4.1): serves
 * [MirrorProjector.view]'s materialized issue map, and one issue's
 * [MirrorProjector.edgeView] slice, as JSON through [DemoShell].
 *
 * **One route, sub-path dispatch.** `/beads/issues` (list) and
 * `/beads/issues/{id}` (single issue + its dependency edges) are both
 * registered on the single JDK `HttpServer` context `/beads/issues` —
 * contexts are prefix-matched, so [register] parses the sub-path itself,
 * matching `TriageApp.handleFeatures`'s `/features`/`/features/{id}` shape.
 *
 * **Field values are embedded raw, never re-escaped.** [MirrorProjector.view]
 * stores each field's `JsonElement.toString()` form (`MirrorProjector.put`),
 * so a stored value is already a complete JSON text — a quoted string for a
 * string column, a bare literal for a number/bool/null. Splicing it directly
 * into the object under construction reproduces the original JSON value;
 * running it through [esc] would instead wrap that JSON text in a second
 * layer of string quoting.
 *
 * **No caller-owned mutable state — and no captured projector either.**
 * [MirrorRoutes] holds no state of its own; every request re-reads
 * [MirrorState.current] live, so a route answered after
 * [MirrorProjector.apply] runs again reflects the new record with no restart
 * (read-after-apply, the epic's serving-half acceptance rule). Taking a
 * [MirrorState] rather than a bare [MirrorProjector] extends that same
 * property across a re-baseline (computenet-dqj.3.3): the projector object
 * itself is *replaced* when the mirror rebuilds from `bd export`, and a
 * constructor-captured reference would go on serving the discarded pre-gap
 * state — zombies and all — for the rest of the process's life. Each request
 * reads the volatile once, so it is answered wholly from one projector, never
 * from a mixture of two.
 *
 * **A frozen fold answers `503`, never `200` (computenet-dqj.12).** When the
 * background poll loop has died — [pollLoopStopped] returns non-null — the
 * feed will not advance again for the life of the process, so the fold these
 * routes serve is a snapshot of a past workspace and nothing here can tell
 * how far past. Both routes then answer **`503 Service Unavailable`** with:
 *
 * ```json
 * {"mirror":"frozen",
 *  "frozen_at_checkpoint":"<the last commit folded, or null>",
 *  "failure":"<throwable class>: <message>",
 *  "stale":{ ...the body this route would have served ... }}
 * ```
 *
 * Three shapes were weighed for that (the item left the call open):
 * a documented staleness *field* on a `200`, a distinct *status code*, and
 * refusing to serve the fold at all.
 *
 * - A staleness field on `200` is only seen by a consumer that looks for it.
 *   The reviewer's measured case is exactly the consumer that does not:
 *   `GET /beads/issues/<id>` answered `200` with `status=in_progress` after
 *   `bd` had closed the issue, and a `jq .status` client had no reason to
 *   read further. A mirror whose whole purpose is to be believed cannot make
 *   "is this true?" opt-in.
 * - Refusing to serve destroys the one thing still worth having. The stale
 *   fold is not garbage: it is the accurate state of the workspace as of
 *   [FeedCheckpoint]'s commit, and a consumer told *how* stale it is can
 *   often still use it.
 *
 * So: relabel, do not withhold. `503` is the status every consumer already
 * reads as "do not treat this as authoritative" — including the dumb ones —
 * and the body still carries both the diagnosis and the whole stale payload
 * under `stale` for a consumer that wants it. The failure is permanent rather
 * than intermittent (the loop is not restarted), so this cannot flap between
 * `200` and `503`: a mirror that has answered `503` once will answer it until
 * the process is restarted.
 *
 * @param pollLoopStopped reads the poller's terminal state — `null` while the
 *   feed is live. A function rather than a value because the poller is
 *   constructed before it can have failed, and read per request for the same
 *   reason [state] is: an answer must reflect the mirror at the moment it was
 *   asked.
 */
class MirrorRoutes(
    private val state: MirrorState,
    private val pollLoopStopped: () -> PollLoopStopped? = { null },
) {

    /** Register this class's routes on [shell]. Call once, before `shell.start()`. */
    fun register(shell: DemoShell) {
        shell.route(BASE_PATH) { exchange -> handle(exchange) }
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, """{"error":"GET only"}""", "application/json")
            return
        }

        // Read the current projector ONCE per request: a re-baseline may swap
        // it between two reads, and an answer stitched from both would show a
        // pre-gap issue's fields beside a post-rebuild edge list.
        val projector = state.current
        // Read the poller's state ONCE too, and read it AFTER the projector:
        // a fold read while the feed was still live and labelled by a failure
        // observed a moment later is at worst pessimistic (it says "may be
        // stale" about state that was current), whereas the other order could
        // label a genuinely frozen fold as live.
        val frozen = pollLoopStopped()

        val id = exchange.requestURI.path.removePrefix(BASE_PATH).trim('/')
        if (id.isEmpty()) {
            respondFold(exchange, frozen, listJson(projector))
            return
        }

        val fields = projector.view()[id]
        if (fields == null) {
            // A 404 from a frozen fold is the most misleading answer of all —
            // the reviewer's run had a later-created issue 404 — so it, too,
            // is served as "frozen", carrying the negative answer under
            // `stale` rather than as the answer.
            respondFold(exchange, frozen, """{"error":"no such issue"}""", liveStatus = 404)
            return
        }
        respondFold(exchange, frozen, issueJson(projector, id, fields))
    }

    /**
     * Serves [liveBody] with [liveStatus] while the feed is live, or — when
     * [frozen] is non-null — a `503` naming the failure and the checkpoint the
     * fold stopped at, with [liveBody] preserved verbatim under `stale`. See
     * this class's doc for why relabelling beats both a quiet `200` and a
     * bare refusal.
     */
    private fun respondFold(
        exchange: HttpExchange,
        frozen: PollLoopStopped?,
        liveBody: String,
        liveStatus: Int = 200,
    ) {
        if (frozen == null) {
            exchange.respond(liveStatus, liveBody, "application/json")
            return
        }
        val body = buildString {
            append("""{"mirror":"frozen",""")
            append("\"frozen_at_checkpoint\":")
            append(frozen.checkpoint?.let(::esc) ?: "null")
            append(",\"failure\":").append(esc(frozen.failure.toString()))
            append(",\"stale_status\":").append(liveStatus)
            append(",\"stale\":").append(liveBody)
            append('}')
        }
        exchange.respond(503, body, "application/json")
    }

    /** `GET /beads/issues`: every present issue, keyed by issue id, fields only. */
    private fun listJson(projector: MirrorProjector): String {
        val issues = projector.view()
        return buildString {
            append('{')
            issues.entries.forEachIndexed { i, (issueId, fields) ->
                if (i > 0) append(',')
                append(esc(issueId)).append(':').append(fieldsObject(fields))
            }
            append('}')
        }
    }

    /**
     * `GET /beads/issues/{id}`: [fields] plus the `dependencies` array of this
     * issue's owning-side edges (`MirrorEdge.issueId == id` — `bd dep add B A`
     * shows up under `GET /beads/issues/B`, not under `A`).
     */
    private fun issueJson(projector: MirrorProjector, issueId: String, fields: Map<String, String>): String {
        val edges = projector.edgeView().filter { it.issueId == issueId }
        return buildString {
            append('{')
            fields.entries.forEach { (field, value) -> append(esc(field)).append(':').append(value).append(',') }
            append("\"dependencies\":").append(edgesArray(edges))
            append('}')
        }
    }

    private fun fieldsObject(fields: Map<String, String>): String = buildString {
        append('{')
        fields.entries.forEachIndexed { i, (field, value) ->
            if (i > 0) append(',')
            append(esc(field)).append(':').append(value)
        }
        append('}')
    }

    private fun edgesArray(edges: List<MirrorEdge>): String = buildString {
        append('[')
        edges.forEachIndexed { i, edge ->
            if (i > 0) append(',')
            append("{\"issue_id\":").append(esc(edge.issueId))
            append(",\"depends_on_issue_id\":").append(esc(edge.dependsOnIssueId))
            append(",\"type\":").append(esc(edge.type))
            append('}')
        }
        append(']')
    }

    private companion object {
        const val BASE_PATH = "/beads/issues"
    }
}
