package civictech.cell.membrane

import civictech.cell.CurrentContext
import civictech.cell.proxy.Invocation
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * Hand-written Mediate proxy (spec 10/14 "mediate-is-serve"): a membrane's
 * Mediate crossing is `serve(proxy)` — a real cell on the per-message path,
 * identical in kind to the red Traffic-Light idiom (30/33), so mediation
 * leaves the delegate-chain-flattening rule untouched.
 *
 * Captures each invocation as an [Invocation] (riding its wave context, spec
 * 20/22) and forwards it to [target] immediately — the transparent-forward
 * half of Mediate. Flow-time `BoundaryPolicy` evaluation (spec 40/43,
 * W4.1/G-54) and coupling gates (Symport/Antiport via Buffering, G-53 —
 * liveness is research-gated, 95 §R3) are not wired here; this class is the
 * pass-through proxy [civictech.cell.membrane.CompositeCell.mediate] serves,
 * and the seam later policy/coupling work installs into. KSP-generating this
 * proxy from a declarative membrane annotation is G-52's residual (50/51).
 */
class MediateProxy<Api : Any>(private val target: Api) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        val invocation = Invocation.of(method, args, CurrentContext.get())
        return invocation.withTarget(target).invoke()
    }
}
