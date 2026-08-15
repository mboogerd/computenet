package civictech.cell.membrane

import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundarySeam
import civictech.cell.CurrentContext
import civictech.cell.DenialReason
import civictech.cell.link.PeerId
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
 * [target] (dead-lettered, never delivered — accounted through [denials],
 * below); because Replicable merges are idempotent, drop-and-reconverge is
 * the recovery (no ack, no version vector).
 *
 * [denials] is the exposure's boundary-denial accounting sink (spec 40/43
 * `[SEC1-25]`/`[SEC1-26]`), threaded in by `CompositeCell.mediate()`. Each of
 * [verifyOrDrop]'s three failure branches is named and reported through it —
 * `UNSIGNED` (the argument did not arrive as a [SignedDelta]), `BAD_SIGNATURE`
 * (`[verifier]` rejected the envelope), `REPLAY` (the counter did not
 * strictly increase for this minting peer) — before `invoke` returns null, so
 * the class doc's "dead-lettered, never delivered" claim is real: the refused
 * arguments reach the host's spec-23-R8 sanitizer via [denials], never a
 * silent drop. [SignatureVerifier.TransportVouched] is a byte-compare against
 * the peer name ([SEC1-24]) — the replay counter, not signature strength, is
 * what defeats a resend. Accounting never itself throws or drops (`invoke`'s
 * `null` return is unconditional on any failure branch). [denials] is null
 * when this proxy is constructed outside a membrane (tests, direct use), in
 * which case there is no exposure to account against and a refusal is simply
 * not reported anywhere.
 */
class MediateProxy<Api : Any>(
    private val target: Api,
    private val integrity: IntegrityPolicy = IntegrityPolicy.None,
    private val verifier: SignatureVerifier = SignatureVerifier.TransportVouched,
    internal val denials: BoundaryDenialSink? = null,
) : InvocationHandler {
    /** Highest counter seen per minting peer (replay defense, spec 40/43 seam 3). */
    private val lastCounter = ConcurrentHashMap<Any, Long>()

    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        if (integrity == IntegrityPolicy.RequireSigned) {
            val subject = method?.let { "${it.declaringClass.simpleName}#${it.name}" }
            val verified = args?.map { arg ->
                when (val result = verifyOrDrop(arg)) {
                    is VerifyResult.Verified -> result.payload
                    is VerifyResult.Denied -> {
                        denials?.deny(
                            seam = BoundarySeam.INTEGRITY,
                            reason = result.reason,
                            principal = result.mintingPeer,
                            subject = subject,
                            detail = result.detail,
                            deniedArgs = args.toList(),
                        )
                        return null
                    }
                }
            } ?: emptyList()
            val invocation = Invocation.of(method, verified.toTypedArray(), CurrentContext.get())
            return invocation.withTarget(target).invoke()
        }
        val invocation = Invocation.of(method, args, CurrentContext.get())
        return invocation.withTarget(target).invoke()
    }

    /** The outcome of verifying one argument: the unwrapped payload, or a named, accountable refusal. */
    private sealed interface VerifyResult {
        data class Verified(val payload: Any?) : VerifyResult
        data class Denied(val reason: DenialReason, val mintingPeer: PeerId?, val detail: String?) : VerifyResult
    }

    /** Verifies and unwraps one [SignedDelta] argument; a named [VerifyResult.Denied] (dead-letter) on any failure. */
    private fun verifyOrDrop(arg: Any?): VerifyResult {
        val signed = arg as? SignedDelta<*>
            ?: return VerifyResult.Denied(DenialReason.UNSIGNED, mintingPeer = null, detail = null)
        if (!verifier.verify(signed.mintingPeer, signed.counter, signed.payload, signed.signature)) {
            return VerifyResult.Denied(DenialReason.BAD_SIGNATURE, signed.mintingPeer, detail = null)
        }
        val previous = lastCounter[signed.mintingPeer] ?: 0L
        if (signed.counter <= previous) {
            return VerifyResult.Denied(
                DenialReason.REPLAY,
                signed.mintingPeer,
                detail = "counter=${signed.counter} not > last accepted $previous",
            )
        }
        lastCounter[signed.mintingPeer] = signed.counter
        return VerifyResult.Verified(signed.payload)
    }
}
