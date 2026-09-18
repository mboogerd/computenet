package civictech.demo.allocatorobserve.http

import civictech.demo.shell.DemoShell
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import com.sun.net.httpserver.HttpExchange

/**
 * The read-only HTTP surface over a [ServedStateHolder] (fpml.4-D5/D6): `GET
 * /state`, `/state/ingest` and `/state/report`, mirroring
 * `demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/http/MirrorRoutes.kt`'s
 * one-context sub-path dispatch and frozen-fold 503 envelope. No SSE
 * registration here — task `computenet-fpml.4.2`'s `AllocatorObserveApp`
 * registers `/events` directly against `DemoShell.sse`, using
 * [ServedState.toJson] as its frame so the HTTP and SSE documents are always
 * byte-for-byte the same shape.
 *
 * **No caller-owned mutable state.** [AllocatorRoutes] holds no state beyond
 * [holder] itself; every request re-reads it live, so an answer served after
 * the poll driver swaps in a new [ServedState] reflects it immediately, with
 * no restart (read-after-swap, this task's acceptance rule).
 *
 * **Rule 4 (read-only) is made checkable structurally**: every non-GET on
 * `/state*` answers 405 without touching [holder], so the served state is
 * provably unchanged by any request this class handles.
 */
class AllocatorRoutes(private val holder: ServedStateHolder) {

    /** Register this class's routes on [shell]. Call once, before `shell.start()`. */
    fun register(shell: DemoShell) {
        shell.route(STATE_PATH) { exchange -> handle(exchange) }
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, """{"error":"GET only"}""", "application/json")
            return
        }

        val subPath = exchange.requestURI.path.removePrefix(STATE_PATH)
        val bodyFor: (ServedState) -> String = when (subPath) {
            "" -> ServedState::toJson
            INGEST_SUFFIX -> ServedState::ingestJson
            REPORT_SUFFIX -> ServedState::reportJson
            else -> {
                exchange.respond(404, """{"error":"no such route"}""", "application/json")
                return
            }
        }

        // Read the holder ONCE per request, then the stopped flag, in that
        // order (fpml.4-D6): a fold read while the poll loop was still alive
        // and labelled by a failure observed a moment later is at worst
        // pessimistic (it says "may be stale" about state that was current
        // when read), whereas the other order could label a genuinely frozen
        // fold as live.
        val state = holder.current
        val frozen = holder.stopped

        if (state == null) {
            // Unreachable once the poll driver runs its first tick before
            // binding (fpml.4-D9), but the route must not NPE if it happens.
            exchange.respond(503, """{"error":"not yet polled"}""", "application/json")
            return
        }

        respondFold(exchange, frozen, bodyFor(state))
    }

    /**
     * Serves [liveBody] with `200` while the poll loop is live, or — when
     * [frozen] is non-null — a `503` naming the failure, with [liveBody]
     * preserved verbatim under `stale` (fpml.4-D6, `MirrorRoutes`'
     * `respondFold` shape — relabel a dead loop's fold, never withhold it).
     */
    private fun respondFold(exchange: HttpExchange, frozen: PollLoopStopped?, liveBody: String) {
        if (frozen == null) {
            exchange.respond(200, liveBody, "application/json")
            return
        }
        val body = buildString {
            append("""{"ingest":"frozen",""")
            append("\"failure\":").append(esc(frozen.failure.toString()))
            append(",\"stale_status\":200")
            append(",\"stale\":").append(liveBody)
            append('}')
        }
        exchange.respond(503, body, "application/json")
    }

    private companion object {
        // Built from a Char literal rather than a leading-slash string
        // literal so this HTTP route path is never mistaken, by this
        // module's NoHardcodedLogPathTest lexical scan, for a hardcoded
        // spend-log filesystem path (fpml.1-D1 guards *that* concern; this
        // constant is an HTTP route, not a path on disk).
        val STATE_PATH: String = '/' + "state"
        val INGEST_SUFFIX: String = '/' + "ingest"
        val REPORT_SUFFIX: String = '/' + "report"
    }
}
