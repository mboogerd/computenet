package civictech.demo.beadsmirror.http

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
 * **No caller-owned mutable state.** [MirrorRoutes] holds no state of its
 * own — every request re-reads [projector] live, so a route answered after
 * [MirrorProjector.apply] runs again reflects the new record with no restart
 * (read-after-apply, the epic's serving-half acceptance rule).
 */
class MirrorRoutes(private val projector: MirrorProjector) {

    /** Register this class's routes on [shell]. Call once, before `shell.start()`. */
    fun register(shell: DemoShell) {
        shell.route(BASE_PATH) { exchange -> handle(exchange) }
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, """{"error":"GET only"}""", "application/json")
            return
        }

        val id = exchange.requestURI.path.removePrefix(BASE_PATH).trim('/')
        if (id.isEmpty()) {
            exchange.respond(200, listJson(), "application/json")
            return
        }

        val fields = projector.view()[id]
        if (fields == null) {
            exchange.respond(404, """{"error":"no such issue"}""", "application/json")
            return
        }
        exchange.respond(200, issueJson(id, fields), "application/json")
    }

    /** `GET /beads/issues`: every present issue, keyed by issue id, fields only. */
    private fun listJson(): String {
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
    private fun issueJson(issueId: String, fields: Map<String, String>): String {
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
