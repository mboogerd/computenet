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
 * The HTTP read surface over N per-workspace [MirrorProjector]s (computenet-dqj.4.1,
 * generalized to N workspaces by task computenet-3bso.1.2): serves
 * [MirrorProjector.view]'s materialized issue map, and one issue's
 * [MirrorProjector.edgeView] slice, as JSON through [DemoShell] — addressed per
 * workspace.
 *
 * **Two contexts, sub-path dispatch within each.** `GET /workspaces` lists the
 * configured workspace identities; `/workspaces/{identity}/beads/issues` (list)
 * and `/workspaces/{identity}/beads/issues/{id}` (single issue + its dependency
 * edges) are both registered on the single JDK `HttpServer` context
 * `/workspaces` — contexts are prefix-matched, so [register] parses the
 * identity and the remaining sub-path itself, the same shape
 * `TriageApp.handleFeatures`'s `/features`/`/features/{id}` and the pre-N-workspace
 * [MirrorRoutes] used for `/beads/issues`/`/beads/issues/{id}` before this task.
 * A request naming a workspace identity this instance does not host answers a
 * plain `404` — that is a config fact (this process never hosts that
 * workspace), never the `503` frozen-fold envelope described below, which is a
 * fact about a workspace this process DOES host.
 *
 * **The legacy unsegmented `/beads/issues` path remains, bound to the sole
 * workspace, exactly when this instance hosts exactly one** — every caller
 * from before N-workspace mode used it unsegmented, and [MirrorRoutesTest]
 * plus every e2e test that reads the HTTP surface keeps working unchanged. An
 * instance hosting more than one workspace registers no unsegmented path: with
 * N folds there is no single one "the" legacy route could mean.
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
 * [MirrorRoutes] holds no state of its own; every request re-reads its
 * workspace's [MirrorState.current] live, so a route answered after
 * [MirrorProjector.apply] runs again reflects the new record with no restart
 * (read-after-apply, the epic's serving-half acceptance rule). Taking a
 * [MirrorState] rather than a bare [MirrorProjector] extends that same
 * property across a re-baseline (computenet-dqj.3.3): the projector object
 * itself is *replaced* when a mirror rebuilds from `bd export`, and a
 * constructor-captured reference would go on serving the discarded pre-gap
 * state — zombies and all — for the rest of the process's life. Each request
 * reads the volatile once, so it is answered wholly from one projector, never
 * from a mixture of two.
 *
 * **A frozen fold answers `503`, never `200` (computenet-dqj.12), per
 * workspace (computenet-3bso.1.2).** When a workspace's background poll loop
 * has died — its [Workspace.pollLoopStopped] returns non-null — the feed will
 * not advance again for the life of the process, so the fold ITS routes serve
 * is a snapshot of a past workspace and nothing here can tell how far past.
 * That workspace's routes then answer **`503 Service Unavailable`** with:
 *
 * ```json
 * {"mirror":"frozen",
 *  "frozen_at_checkpoint":"<the last commit folded, or null>",
 *  "failure":"<throwable class>: <message>",
 *  "stale_status":<the status this route would have answered: 200, or 404>,
 *  "stale":{ ...the body this route would have served ... }}
 * ```
 *
 * A sibling workspace whose poll loop is still alive keeps answering `200` on
 * its own routes — one dead loop freezes exactly one fold, matching
 * [civictech.demo.beadsmirror.WorkspaceMirror]'s structural failure isolation.
 *
 * A miss is served the same way rather than as a bare `404`: `stale_status` is
 * `404` and `stale` carries `{"error":"no such issue"}`, so "this id is
 * unknown" is reported as a fact about a frozen fold and never as a fact about
 * the workspace — the reviewer's run had exactly that, a later-created issue
 * answering `404`.
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
 *   `frozen_at_checkpoint`'s commit, and a consumer told *how* stale it is can
 *   often still use it.
 *
 * So: relabel, do not withhold. `503` is the status every consumer already
 * reads as "do not treat this as authoritative" — including the dumb ones —
 * and the body still carries both the diagnosis and the whole stale payload
 * under `stale` for a consumer that wants it. The failure is permanent rather
 * than intermittent (the loop is not restarted), so this cannot flap between
 * `200` and `503`: a workspace that has answered `503` once will answer it
 * until the process is restarted.
 *
 * **What `200` does and does not promise.** The signal is derived from that
 * workspace's poll loop having *exited on a throwable*, and from nothing else
 * — which is what keeps it honest in the direction that matters (a slow tick,
 * a long `dolt` call, an idle workspace are all still live, and are never
 * reported frozen). The converse limit is real and deliberate: a loop that is
 * merely *stuck* — a tick blocked indefinitely rather than failing — has not
 * stopped, so that workspace's routes still answer `200`, and a `200`
 * therefore means "this workspace's feed has not died", not "this workspace's
 * fold is fresh as of now". Liveness-by-timeout is a separate mechanism
 * (nothing here measures how long a tick has been running); computenet-dqj.12
 * asked only that a *dead* loop stop being invisible.
 */
class MirrorRoutes(
    private val workspaces: List<Workspace>,
) {

    init {
        require(workspaces.isNotEmpty()) {
            "MirrorRoutes needs at least one workspace to serve — there is no fold to answer routes from otherwise"
        }
    }

    /**
     * The pre-N-workspace shape, kept so every existing single-fold caller —
     * chiefly [MirrorRoutesTest] itself — compiles and behaves unchanged: one
     * [MirrorState], served on the legacy unsegmented `/beads/issues` path
     * (register()'s size == 1 case) as well as under a placeholder workspace
     * segment, since a caller using this constructor has no real workspace
     * identity to offer.
     */
    constructor(state: MirrorState, pollLoopStopped: () -> PollLoopStopped? = { null }) :
        this(listOf(Workspace(LEGACY_ONLY_IDENTITY, state, pollLoopStopped)))

    /**
     * One workspace's fold, as [MirrorRoutes] needs it to answer that
     * workspace's routes: its [MirrorState] (read live, per request, per
     * [MirrorRoutes]'s class doc) and its poller's terminal state.
     *
     * @param pollLoopStopped reads this workspace's poller's terminal state —
     *   `null` while its feed is live. A function rather than a value because
     *   the poller is constructed before it can have failed, and read per
     *   request for the same reason [state] is: an answer must reflect this
     *   workspace's mirror at the moment it was asked.
     */
    data class Workspace(
        val identity: String,
        val state: MirrorState,
        val pollLoopStopped: () -> PollLoopStopped? = { null },
    )

    /** Register this class's routes on [shell]. Call once, before `shell.start()`. */
    fun register(shell: DemoShell) {
        shell.route(WORKSPACES_PATH) { exchange -> handleWorkspaceScoped(exchange) }
        if (workspaces.size == 1) {
            val sole = workspaces.single()
            shell.route(LEGACY_BASE_PATH) { exchange -> handleIssues(exchange, sole, exchange.requestURI.path) }
        }
    }

    /**
     * `/workspaces` (bare — lists identities) and
     * `/workspaces/{identity}/beads/issues[/{id}]` (workspace-scoped issue
     * routes), both registered under the one `/workspaces` context.
     */
    private fun handleWorkspaceScoped(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, """{"error":"GET only"}""", "application/json")
            return
        }

        val rest = exchange.requestURI.path.removePrefix(WORKSPACES_PATH).trim('/')
        if (rest.isEmpty()) {
            exchange.respond(200, workspacesJson(), "application/json")
            return
        }

        val identity = rest.substringBefore('/')
        val workspace = workspaces.firstOrNull { it.identity == identity }
        if (workspace == null) {
            // Plain 404: an unknown workspace identity is a config fact ("this
            // process never hosts that workspace"), not a frozen-fold fact, so
            // it must never be wrapped in the 503 stale envelope below.
            exchange.respond(404, """{"error":"no such workspace"}""", "application/json")
            return
        }

        val subPath = "/" + rest.removePrefix(identity).trim('/')
        handleIssues(exchange, workspace, subPath)
    }

    /** `GET /workspaces`: the configured workspace identities, as a JSON array. */
    private fun workspacesJson(): String =
        workspaces.joinToString(prefix = "[", postfix = "]") { esc(it.identity) }

    /**
     * The issue routes proper, shared by the legacy unsegmented path (where
     * [path] is the exchange's raw request path, already `/beads/issues...`)
     * and the workspace-scoped path (where [path] has had `/workspaces/{id}`
     * stripped down to the same `/beads/issues...` shape by
     * [handleWorkspaceScoped]) — one implementation, one place the frozen/live
     * split and the read ordering are decided.
     */
    private fun handleIssues(exchange: HttpExchange, workspace: Workspace, path: String) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, """{"error":"GET only"}""", "application/json")
            return
        }
        if (!path.startsWith(ISSUES_SUFFIX)) {
            exchange.respond(404, """{"error":"no such route"}""", "application/json")
            return
        }

        // Read the current projector ONCE per request: a re-baseline may swap
        // it between two reads, and an answer stitched from both would show a
        // pre-gap issue's fields beside a post-rebuild edge list.
        val projector = workspace.state.current
        // Read the poller's state ONCE too, and read it AFTER the projector:
        // a fold read while the feed was still live and labelled by a failure
        // observed a moment later is at worst pessimistic (it says "may be
        // stale" about state that was current), whereas the other order could
        // label a genuinely frozen fold as live.
        val frozen = workspace.pollLoopStopped()

        val id = path.removePrefix(ISSUES_SUFFIX).trim('/')
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

    /** `GET .../beads/issues`: every present issue, keyed by issue id, fields only. */
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
     * `GET .../beads/issues/{id}`: [fields] plus the `dependencies` array of
     * this issue's owning-side edges (`MirrorEdge.issueId == id` — `bd dep add
     * B A` shows up under `GET .../beads/issues/B`, not under `A`).
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
        const val WORKSPACES_PATH = "/workspaces"
        const val LEGACY_BASE_PATH = "/beads/issues"
        const val ISSUES_SUFFIX = "/beads/issues"
        const val LEGACY_ONLY_IDENTITY = "default"
    }
}
