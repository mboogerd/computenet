package civictech.cell.membrane

import civictech.cell.CurrentContext
import civictech.cell.proxy.Invocation
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Hand-written Mediate proxy (spec 10/14 "mediate-is-serve"): a membrane's
 * Mediate crossing is `serve(proxy)` — a real cell on the per-message path,
 * identical in kind to the red Traffic-Light idiom (30/33), so mediation
 * leaves the delegate-chain-flattening rule untouched.
 *
 * Captures each invocation as an [Invocation] (riding its wave context, spec
 * 20/22) and forwards it to [target] — the transparent-forward half of
 * Mediate. Coupling gates (Symport/Antiport via Buffering, G-53 — liveness is
 * research-gated, 95 §R3) are not wired here. KSP-generating this proxy from
 * a declarative membrane annotation is G-52's residual (50/51).
 *
 * [integrity] is the seam-3 `PORT_API` inbound predicate (spec 40/43, decided
 * 93 I-28, W4.1/G-54): [IntegrityPolicy.RequireSigned] expects every argument
 * to arrive as a [SignedDelta], verifies it with [verifier] (`AuthLevel`
 * strength is [SignatureVerifier.TransportVouched] until phase-2 keys/DIDs
 * exist, 95 §R7), and checks the per-source [SignedDelta.counter] is
 * strictly increasing — the replay defense. Failure (missing envelope, bad
 * signature, non-increasing counter) drops the invocation before it reaches
 * [target] (dead-lettered, never delivered); because Replicable merges are
 * idempotent, drop-and-reconverge is the recovery (no ack, no version
 * vector).
 */
class MediateProxy<Api : Any>(
    private val target: Api,
    private val integrity: IntegrityPolicy = IntegrityPolicy.None,
    private val verifier: SignatureVerifier = SignatureVerifier.TransportVouched,
) : InvocationHandler {
    /** Highest counter seen per minting peer (replay defense, spec 40/43 seam 3). */
    private val lastCounter = ConcurrentHashMap<Any, Long>()

    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        if (integrity == IntegrityPolicy.RequireSigned) {
            val verified = args?.map { verifyOrDrop(it) ?: return null } ?: emptyList()
            val invocation = Invocation.of(method, verified.toTypedArray(), CurrentContext.get())
            return invocation.withTarget(target).invoke()
        }
        val invocation = Invocation.of(method, args, CurrentContext.get())
        return invocation.withTarget(target).invoke()
    }

    /** Verifies and unwraps one [SignedDelta] argument; null (dead-letter) on any failure. */
    private fun verifyOrDrop(arg: Any?): Any? {
        val signed = arg as? SignedDelta<*> ?: return null
        if (!verifier.verify(signed.mintingPeer, signed.counter, signed.payload, signed.signature)) return null
        val previous = lastCounter[signed.mintingPeer] ?: 0L
        if (signed.counter <= previous) return null
        lastCounter[signed.mintingPeer] = signed.counter
        return signed.payload
    }
}
