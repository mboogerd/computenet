package civictech.cell.membrane

import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundarySeam
import civictech.cell.CurrentContext
import civictech.cell.DenialReason
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.dischargeRefusedArgs
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
 * which case there is no exposure to account against and a refusal is
 * reported nowhere — but its exclusives are still discharged (below).
 *
 * ## Refused exclusives, and why the envelope is opened first (BS-5, `[SEC1-23]`)
 *
 * A `Denied` verdict refuses the **whole** invocation, so every [Owned]/
 * [Leased] in the argument array must be discharged exactly once — offending
 * argument or not, raw or envelope-wrapped. Under
 * [IntegrityPolicy.RequireSigned] every argument arrives as a [SignedDelta],
 * so the exclusive is never a top-level argument: it is [SignedDelta.payload].
 * The host's single spec-23-R8 sanitizer
 * (`DeadLetters.sanitizeForDeadLetter`) inspects top-level arguments only, by
 * design — the host is deliberately ignorant of membrane types, and the
 * repository keeps exactly **one** sanitizer. So the unwrapping happens here,
 * at the membrane, where [SignedDelta] is a local type: [refusedArgs] hands
 * the sink each readable envelope's payload in place of the envelope, and the
 * envelope identity the record would otherwise lose rides [BoundaryDenial]'s
 * `detail` (see [envelopeNote]) rather than a live handle in the fan-out.
 *
 * Discharge itself stays in exactly one place per path: with a reporter
 * attached it is the host's sanitizer (`Owned -> Frozen`, `Leased ->
 * `Redacted`), and this class deliberately does **not** also discharge
 * locally there — a pre-consumed `Owned` would degrade the dead letter's
 * Frozen value to a Redacted marker. With no sink at all ([denials] null),
 * there is no sanitizer downstream, so the refused arguments are discharged
 * here via `civictech.cell.dischargeRefusedArgs` (consume/release only, never
 * a second `Frozen`/`Redacted` substitution). The unattached-sink case — a
 * sink whose membrane was never spawned — is closed at the sink itself, in
 * [civictech.cell.BoundaryDenialSink.deny].
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
                        // The whole invocation is refused, so every argument's
                        // exclusive is discharged — including the ones the
                        // RequireSigned envelope hides from the host's
                        // top-level-only R8 sanitizer (BS-5).
                        val refused = refusedArgs(args)
                        if (denials == null) {
                            dischargeRefusedArgs(refused)
                        } else {
                            denials.deny(
                                seam = BoundarySeam.INTEGRITY,
                                reason = result.reason,
                                principal = result.mintingPeer,
                                subject = subject,
                                detail = listOfNotNull(result.detail, envelopeNote(args, refused))
                                    .joinToString("; ")
                                    .ifEmpty { null },
                                deniedArgs = refused,
                            )
                        }
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

    /**
     * The refused invocation's arguments as the spec-23-R8 sanitizer must see
     * them: each **readable** [SignedDelta] replaced by its payload, every
     * other argument as-is.
     *
     * "Readable" is the whole envelope population here — a `SignedDelta` whose
     * signature or counter was rejected is still a well-formed envelope whose
     * `payload` field can be read; what the verifier rejected is the *claim*
     * the envelope makes, not its structure. An `UNSIGNED` refusal has no
     * envelope to open for that argument, and it passes through untouched.
     *
     * **What this costs, stated where it happens.** Unwrapping is
     * unconditional over readable envelopes — the decided design (this is
     * `computenet-usd.2.1`'s "unwrapped to its payload; raw args as-is"), and
     * it is what makes discharge cover a payload the top-level-only sanitizer
     * would miss *inside a container* as well as a bare `Owned`/`Leased`. The
     * price is that an **ordinary** (non-exclusive) refusal's dead letter now
     * captures the payload where it used to capture the whole `SignedDelta`,
     * so the envelope's peer/counter are no longer readable off the captured
     * argument. They are not restored by [envelopeNote] either, which fires
     * only for exclusives on purpose: the denial rate is set by whatever a
     * remote peer chooses to send (`computenet-usd.6`, see
     * `DeadLetters.boundaryDenial`), so the refusal path may not grow a string
     * build per refusal for an identity the [civictech.cell.BoundaryDenial]
     * already names as `principal`, and that `detail` already carries the
     * counter for the one reason it discriminates on (`REPLAY`).
     */
    private fun refusedArgs(args: Array<out Any>): List<Any?> =
        args.map { (it as? SignedDelta<*>)?.payload ?: it }

    /**
     * The envelope identity that unwrapping would otherwise drop from the
     * audit trail, as a `detail` fragment — emitted only when unwrapping
     * actually surfaced an exclusive, so an ordinary refusal's
     * [civictech.cell.BoundaryDenial] is byte-identical to before (its
     * *captured argument* is not — see [refusedArgs]). `null` when there is
     * nothing to say.
     *
     * A record is a **report**, never a payload carrier
     * ([civictech.cell.BoundaryDenial]), so the peer/counter go in as text
     * beside the [DenialReason]; the envelope object itself never travels.
     */
    private fun envelopeNote(args: Array<out Any>, refused: List<Any?>): String? {
        if (refused.none { it is Owned<*> || it is Leased<*> }) return null
        val envelopes = args.filterIsInstance<SignedDelta<*>>()
            .filter { it.payload is Owned<*> || it.payload is Leased<*> }
            .map { "${it.mintingPeer.name}#${it.counter}" }
        if (envelopes.isEmpty()) return null
        return "exclusive payload(s) unwrapped from SignedDelta envelope(s) " +
            "[${envelopes.joinToString(", ")}] for spec-23-R8 discharge"
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
