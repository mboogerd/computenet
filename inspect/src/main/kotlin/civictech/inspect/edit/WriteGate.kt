package civictech.inspect.edit

import civictech.demo.shell.respond
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.HttpExchange

/**
 * The check every write-plane route calls **first** (WKB2 F5): it decides
 * whether this request may mutate anything, from the plane and the request's
 * headers alone.
 *
 * Three refusals, in a fixed order, each answered before the next is looked at:
 *
 * 1. the plane is [WritePlane.Disabled] ⇒ **404** `write plane disabled` — a
 *    server that was not opted in answers as though the route were not there,
 *    whatever the request carries (`[WKB2-06]`);
 * 2. [WRITE_HEADER] is absent ⇒ **400** `missing required header: X-Inspector-Write`
 *    (`[WKB2-44]`);
 * 3. its value is not the process [Capability] ⇒ **403** `capability rejected`,
 *    compared in constant time (see [Capability.matches], `[WKB2-43]`).
 *
 * **None of them reads `exchange.requestBody`.** A refused request's body is
 * never parsed, so nothing a caller without the capability sends can reach a
 * parser, a plan or a host. The gate touches no host state either: it holds
 * nothing but the [WritePlane].
 *
 * **Why a custom header, and what it buys against a browser.** [WRITE_HEADER]
 * is not a CORS-safelisted header, so a cross-origin browser `POST` carrying it
 * must be preflighted with `OPTIONS` — and `InspectorServer` registers no
 * `OPTIONS` handler anywhere, so that preflight fails closed and the real
 * request is never sent. That is the same argument the wake route's KDoc makes
 * for `InspectorServer.WAKE_HEADER` (see `InspectorServer.serveGraph`); the
 * write plane adds the capability on top, because unlike a wake it builds and
 * tears down graph.
 *
 * On admission the caller's identity label is set on the exchange as
 * [IDENTITY_ATTRIBUTE] (`[WKB2-49]`, gate half) so the route — and the audit
 * record it writes — can name who applied the change without re-deriving it.
 */
internal class WriteGate(private val plane: WritePlane) {

    /** What [admit] decided. */
    sealed interface Admission {
        /** Answer [status] with `{"reason": reason}` and do nothing else — see [respondRefusal]. */
        data class Refused(val status: Int, val reason: String) : Admission

        /** Go ahead; the request is attributed to [identity]. */
        data class Admitted(val identity: String) : Admission
    }

    fun admit(exchange: HttpExchange): Admission {
        val enabled = plane as? WritePlane.Enabled
            ?: return Admission.Refused(404, DISABLED)
        val presented = exchange.requestHeaders.getFirst(WRITE_HEADER)
            ?: return Admission.Refused(400, MISSING_HEADER)
        if (!enabled.capability.matches(presented)) return Admission.Refused(403, REJECTED)
        exchange.setAttribute(IDENTITY_ATTRIBUTE, enabled.identityLabel)
        return Admission.Admitted(enabled.identityLabel)
    }

    companion object {
        /** The header a write-plane caller presents its [Capability] in. */
        const val WRITE_HEADER = "X-Inspector-Write"

        /** The exchange attribute an admitted request's identity label is stored under. */
        const val IDENTITY_ATTRIBUTE = "inspector.identity"

        const val DISABLED = "write plane disabled"
        const val MISSING_HEADER = "missing required header: $WRITE_HEADER"
        const val REJECTED = "capability rejected"
    }
}

/** Answer a [WriteGate.Admission.Refused] as the inspector's usual `{"reason": …}` problem body. */
internal fun HttpExchange.respondRefusal(refused: WriteGate.Admission.Refused) =
    respond(refused.status, InspectorServer.problem(refused.reason), "application/json")
