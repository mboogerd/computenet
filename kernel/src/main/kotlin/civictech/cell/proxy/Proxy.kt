package civictech.cell.proxy

import civictech.cell.Borrowed
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.nature.ContractRegistry
import civictech.nature.JvmDescriptors
import civictech.gen.wire.ProxyRegistry
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

object Proxy {
    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T : Any> createProxy(proxyType: InvocationHandler): T {
        val clazz = when (val javaType = typeOf<T>().javaType) {
            is Class<*> -> javaType
            is ParameterizedType -> javaType.rawType as Class<*>
            else -> error("Unsupported type: $javaType")
        }

        return fromClass(clazz, proxyType)
    }

    /**
     * Constructs an instance of [clazz] dispatching every method through
     * [invocationHandler] — the shape `Buffering`, `NoOp`,
     * `Callback`, `HostProxy`, `MediateProxy`, and every port
     * (`Outlet`, `Inlet`, `FanOutlet`, `FanInlet`, ...) already build on.
     *
     * C-5 completion (W4.6, spec 10/14 §Reflection budget): every `@Contract`
     * interface has a KSP-generated proxy class (`gen.wire.ContractProcessor`)
     * registered in [ProxyRegistry] — the ahead-of-time-compiled replacement
     * for `java.lang.reflect.Proxy.newProxyInstance`, used first. The runtime
     * dynamic-proxy fallback below is retained only for interfaces outside the
     * `@Contract` surface — the cross-host structural navigation proxies
     * `HostedCellProxy`/`HostProxy` walk over ad hoc `Cell`/`Port` resource
     * types (tier 2/3 dispatch, spec 10/14 §Dispatch tiers), which are not
     * fixed method-dispatch contracts KSP can generate ahead of time.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> fromClass(clazz: Class<out Any>, invocationHandler: InvocationHandler): T {
        if (!clazz.isInterface) {
            throw IllegalArgumentException("Only interfaces can be represented. $clazz is not an interface")
        }
        val generated = ProxyRegistry.factory(clazz)
        return if (generated != null) {
            generated(invocationHandler) as T
        } else {
            Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), invocationHandler) as T
        }
    }

    /**
     * Unwraps a reflective [java.lang.reflect.InvocationTargetException],
     * rethrowing the invocation's real cause — the shape every `Method.invoke`
     * dispatch site in this package repeats (delegating here,
     * [Invocation], and the port outlets).
     */
    internal inline fun <T> unwrapInvocationTarget(block: () -> T): T =
        try {
            block()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }

    /**
     * Creates a proxy that delegates all calls to the implementation returned by [provider].
     */
    fun <T : Any> delegating(clazz: Class<T>, provider: () -> T): T {
        return fromClass(clazz) { _, method, args ->
            unwrapInvocationTarget {
                method.invoke(provider(), *(args ?: emptyArray()))
            }
        }
    }

    /**
     * Creates a proxy that broadcasts all calls to the implementations returned by [provider].
     */
    fun <T : Any> broadcasting(clazz: Class<T>, provider: () -> Iterable<T>): T {
        return fromClass(clazz) { _, method, args ->
            provider().forEach {
                unwrapInvocationTarget {
                    method.invoke(it, *(args ?: emptyArray()))
                }
            }
            null
        }
    }

    /**
     * Creates a proxy that does nothing — delegates to the [NoOp] handler.
     */
    fun <T : Any> noop(clazz: Class<T>): T {
        return fromClass(clazz, NoOp)
    }

    /** Create a sink which discharges methods marked exclusive by generated metadata. */
    fun <T : Any> discharging(clazz: Class<T>): T {
        val descriptor = requireNotNull(ContractRegistry.descriptor(clazz)) {
            "A discharging proxy requires a generated contract descriptor for ${clazz.name}"
        }
        val exclusiveMethods = descriptor.methods.filter { it.exclusive }.mapTo(mutableSetOf()) {
            it.name to it.jvmDescriptor
        }
        return fromClass(clazz) { _, method, args ->
            if ((method.name to JvmDescriptors.of(method)) in exclusiveMethods) {
                args.orEmpty().forEach(::discharge)
            }
            null
        }
    }

    /**
     * T05 finding 3: promoted from `private` to `internal` so [civictech.cell.port.Admit]
     * can discharge a *dropped* invocation's exclusive args directly
     * (consume `Owned`, release `Leased`) without needing a whole
     * [discharging] proxy — the ADMIT tier drops one already-decoded
     * `Invocation`, not a method call it forwards to a sink.
     */
    internal fun discharge(value: Any?) {
        discharge(value, Collections.newSetFromMap(IdentityHashMap()))
    }

    /**
     * `[SEC1-20]` accounting for [discharge] (computenet-h6sf): the number of times the walk
     * met an exclusive that was **already** consumed or released.
     *
     * The walk cannot throw out of a cleanup path (see [discharge]'s KDoc), and it also must
     * not *mask* a double-discharge — `FanOutlet`'s KDoc treats discharge-exactly-once as the
     * `[SEC1-20]` invariant. So the occurrence is neither propagated nor swallowed: it is
     * counted, the same counted-tripwire shape `civictech.cell.port.InletPolicy.unackedDrops`
     * and `civictech.cell.consistency.WaveFrontier.unmatchedDrops` use for their own silent
     * exits. A host or test that requires the invariant asserts this stays at its prior value.
     *
     * Process-wide and monotonic — it is a tripwire, not a per-invocation result — so read it
     * as a delta across the operation under test, never as an absolute.
     *
     * **Limit, stated where the number is:** the count is derived from an
     * [IllegalStateException] out of `Owned.take()`/`Leased.release()`, which is precisely the
     * consume-once/release-once `check` today, but `Leased.release` also invokes its
     * `returnToPool` callback under the same guard. A pool callback that itself threw
     * `IllegalStateException` would be counted here as a double-discharge (and swallowed).
     * Pooling is unbuilt (`Ownership.kt`, "G-21 phase 3"), so no such callback exists in this
     * repository today; making the distinction exact needs a non-consuming state predicate on
     * `Owned`/`Leased` themselves, which is filed separately.
     */
    val doubleDischarges: Long get() = doubleDischargeCount.get()

    private val doubleDischargeCount = java.util.concurrent.atomic.AtomicLong()

    /**
     * C-11 residual 1 (computenet-ulss, 93 I-6 / I-8): the walk reaches an exclusive nested
     * in a **plain payload object's field**, not only one held directly, in a collection, or
     * in a type argument. Before the widening, an `Owned` inside a data-class parameter was
     * left live by a proxy that believed it had discharged — no take, no release, no dead
     * letter, no accounting — which is exactly the silent drop AGENTS.md's exclusive-payload
     * invariant forbids. Its KSP half is `ContractProcessor.carriesExclusive`, which had to
     * widen with it: without that, the method carrying the nested exclusive is never marked
     * exclusive and this walk is never entered.
     *
     * Rules of the object walk, each load-bearing:
     *
     * - **[seen] is an identity set**, so an aliased payload reachable twice in one argument
     *   graph is discharged once (a second `take()` would throw), and a cyclic graph
     *   terminates.
     * - **`Borrowed`/`Frozen` are not opened.** Both are explicitly non-consuming views
     *   (spec 23 §Taps); descending into one could consume an exclusive whose sole consumer
     *   is somebody else.
     * - **Platform declarations are not opened *reflectively*** — [dischargeFields] refuses
     *   them, because opening JDK internals would trip module access. Reach into the platform
     *   containers that can hold payload is therefore given by an explicit branch above, one
     *   per shape, using the container's own public accessor: `Map`, `Iterable`, `Array`,
     *   and (computenet-woto) `kotlin.Pair`, `kotlin.Triple`, `kotlin.Result` and
     *   `java.util.Optional`. That list is not arbitrary — each entry is a shape
     *   `ContractProcessor.carriesExclusive` reaches, because it tests `type.arguments`
     *   *before* its own platform stop, so `Pair<Owned<T>, _>` and friends do mark their
     *   method exclusive. (It is a subset, not an equality — see the next paragraph.)
     *   Until this widening the runtime walk had no such precedence, and an
     *   exclusive held only inside one of them stayed live while the descriptor asserted the
     *   method was discharged (measured 2026-08-16 under review of computenet-ulss; pinned by
     *   `ProxyDischargeReachTest`).
     *
     *   **A platform container outside that list is still unreached, and deliberately.**
     *   The scan sees a type argument of *any* declaration, so a user-written platform-adjacent
     *   generic could in principle carry one; no such shape occurs in this repository's
     *   contracts, and each addition costs a hand-written accessor branch, so shapes are added
     *   on evidence rather than pre-emptively. A `Result`'s **failure** is likewise not
     *   walked: `exceptionOrNull()` yields a `Throwable`, which is a diagnostic, not payload
     *   the SPSC handshake ever transferred.
     * - **Both an `Owned`'s payload and a `Leased`'s value are walked** (computenet-woto for
     *   `Owned`, computenet-zyg1 for `Leased`). `take()` returns the moved value, so an
     *   `Owned` nested inside the value of an outer `Owned` is within this walk — previously
     *   `take()`'s result was discarded and the inner exclusive was silently dropped. Note
     *   what the scan does and does not say here: `carriesExclusive` returns `true` at
     *   `EXCLUSIVE_MARKERS` on the *outer* marker, **before** its type-argument walk, so it
     *   never inspects the inner one individually. What it asserts is that the whole
     *   parameter is exclusive payload this method is the sole consumer of; the walk owes a
     *   consumer to everything transferred with it, and the inner `Owned` has no other.
     *
     *   `Leased` was previously stopped at `release()`, justified by the ownership contract:
     *   `release()` returns `Unit` and hands the value back to its pool, which from that
     *   instant is its owner, so consuming exclusives reachable from it would be consuming
     *   the *pool's* payload — the over-reach direction of the same invariant. **That
     *   justification asserted a transfer the code does not perform.**
     *   `Leased.returnToPool` defaults to `{}` (`Ownership.kt`), pooling is G-21 phase 3 and
     *   deliberately unbuilt, and no `Leased` anywhere in this repository's *main* source
     *   sets is constructed with a pool callback at all — every non-default `returnToPool` is
     *   a test counter. So no pool receives the value, the inner exclusive had no consumer
     *   whatsoever, and the descriptor still asserted the method was discharged: the same
     *   silent drop, one shape over (measured 2026-08-18 under review, reproduced 2026-08-23
     *   against this walk — `Carrier(Leased(Env(inner)))` with `Env(val o: Owned<String>)`
     *   gave `releases=1` and the inner `Owned` still live). The walk therefore descends into
     *   `Leased.value` after a successful `release()`, and only after one: a release that
     *   *failed* (an already-released lease) means this walk is not the one that discharged
     *   the lease, so it owes nothing reachable through it — the same asymmetry `Owned`'s
     *   already-taken branch has, and the guard against consuming a live consumer's payload
     *   from a cleanup path.
     *
     *   **What would change this answer** (for whoever builds G-21 phase 3): a `returnToPool`
     *   that genuinely transfers the value to a pool. From the instant a real pool receives
     *   it, the pool is the value's owner, exclusives reachable from it are the pool's
     *   payload, and walking them here becomes the over-reach the original justification
     *   feared. The decision recorded here is *not* "a `Leased`'s value is payload"; it is
     *   "today nothing else consumes it, and an exclusive with no consumer at all is the
     *   worse of the two failures". Revisit this branch, and the
     *   `ProxyDischargeReachTest` cases naming computenet-zyg1, together with that work.
     * - **Function values are not opened** (computenet-h6sf, defect 1 — over-reach). A
     *   `kotlin.Function*` type is a *platform* declaration, so `carriesExclusive` stops at it
     *   and can never mark a method exclusive on account of a captured exclusive. A lambda's
     *   runtime *carrier* class, however, is an ordinary non-platform class whose fields hold
     *   exactly those captures, so the field walk used to descend into it and consume an
     *   exclusive that no contract ever declared as payload — an exclusive consumed by
     *   something that never owned it, the mirror image of the drop this walk exists to
     *   prevent. Measured 2026-08-16 under review of computenet-ulss (`class WithFn(val f: ()
     *   -> Unit)` capturing an `Owned`; pinned by `ProxyDischargeReachTest`). The same stop
     *   is applied structurally in [dischargeFields] for carriers that are *not*
     *   `kotlin.Function` — a Java functional interface (`Runnable`) is one — see its KDoc.
     * - **Reflection failures are swallowed per field** rather than aborting the walk: this
     *   runs on suppression and denial paths, where discharging the fields that *are*
     *   reachable is strictly better than propagating out of a cleanup.
     * - **An already-consumed exclusive is counted, not thrown and not swallowed**
     *   (computenet-h6sf, defect 2). `Owned.take()`/`Leased.release()` used to propagate out
     *   of here, which on `Proxy.discharging`'s handler and
     *   `civictech.cell.port.InletPolicy.offer` — both `args.forEach(::discharge)`, unguarded
     *   — abandoned the *remaining* arguments and fields undischarged: a cleanup path that
     *   silently drops the rest of an exclusive payload. Wrapping the consumption in a blanket
     *   `runCatching` would fix that by masking `[SEC1-20]` double-discharge instead, so the
     *   occurrence is recorded on [doubleDischarges] and the walk continues. Read that
     *   counter's KDoc for what it does and does not distinguish.
     *
     * ## Why the reach is *not* narrowed further (the decision, computenet-h6sf)
     *
     * The same review measured a second over-reach shape: `Cmd(val item: Owned, val registry:
     * SharedRegistry)` where `SharedRegistry` declares an `Owned` property of its own — both
     * are consumed, though only `item` is intuitively "this invocation's". Neither a depth
     * bound nor an opt-in payload marker is taken, and deliberately:
     *
     * - **The compile-time scan reaches it too.** `ContractProcessor.carriesExclusive` walks
     *   `getAllProperties()` transitively with no depth bound, so a method taking `Cmd` is
     *   marked `exclusive` *because of* `registry.held`. Every consumer of that bit — the link
     *   handshake's SPSC rule, the suppression proxy, ADMIT accounting — already treats it as
     *   a sole-consumer payload. A runtime walk narrower than the bit that summoned it would
     *   leave that exclusive with no consumer at all: the silent drop again, now with the
     *   descriptor asserting it was handled.
     * - **A depth bound cannot separate the two cases**: `Cmd.registry.held` and
     *   `OwnedEnvelope.payload` differ in nesting by one, and in ownership by nothing a
     *   counter can see. Any cutoff drops legitimate payload at some depth.
     * - **The type level already has the escape hatch.** A reference that is genuinely shared
     *   rather than transferred is declared `Borrowed`/`Frozen`, and both walks stop there
     *   (spec 23 §Taps, computenet-yzsc). Declaring a live `Owned` in a type reachable from a
     *   contract parameter *is* declaring it transferred; that is what the ownership types
     *   mean.
     *
     * So the rule is: **the runtime walk's reach is exactly the compile-time scan's reach**.
     * If those two disagree, the divergence is the bug, in whichever direction it points.
     *
     * **Where the rule is not yet exact, stated here rather than in a report.** The scan
     * reads *declared* types; this walk reads *runtime* classes, and the two cannot be made
     * to coincide by a stop list alone. The compiler-generated-carrier stops above and in
     * [dischargeFields] close the cases that were measured. One residual is known and
     * remains: a parameter declared as a supertype (`Any`, an interface) whose runtime value
     * is a class holding an `Owned` is invisible to the scan — which therefore does not mark
     * the method exclusive at all — yet is opened and consumed here if the method is
     * exclusive for some *other* parameter. Measured 2026-08-17 under review
     * (`Holder(val any: Any)` holding a class with an `Owned` property: consumed). Closing it
     * needs the walk to be descriptor-driven rather than purely reflective; filed separately,
     * not done here.
     */
    private fun discharge(value: Any?, seen: MutableSet<Any>) {
        if (value == null || !seen.add(value)) return
        when (value) {
            is Owned<*> -> consuming { value.take() }?.let { discharge(it, seen) }
            is Leased<*> -> consuming { value.release() }?.let { discharge(value.value, seen) }
            is Map<*, *> -> value.forEach { (key, item) ->
                discharge(key, seen)
                discharge(item, seen)
            }
            is Iterable<*> -> value.forEach { discharge(it, seen) }
            is Array<*> -> value.forEach { discharge(it, seen) }
            is Pair<*, *> -> {
                discharge(value.first, seen)
                discharge(value.second, seen)
            }
            is Triple<*, *, *> -> {
                discharge(value.first, seen)
                discharge(value.second, seen)
                discharge(value.third, seen)
            }
            is Result<*> -> discharge(value.getOrNull(), seen)
            is java.util.Optional<*> -> discharge(value.orElse(null), seen)
            is Borrowed<*>, is Frozen<*>, is Function<*> -> Unit
            else -> dischargeFields(value, seen)
        }
    }

    /**
     * Runs one exclusive's consumption so that an already-discharged obligation neither
     * escapes into the cleanup path nor disappears — see [doubleDischarges]. The catch is
     * deliberately narrow (`IllegalStateException`, around the consumption alone) rather than
     * a `runCatching` over the walk: any other failure is not a double-discharge and must
     * still surface.
     *
     * Returns what the consumption yielded, or `null` when it was already discharged, so
     * `Owned.take()`'s moved value can be walked in turn (computenet-woto) without the caller
     * having to distinguish the two outcomes again.
     */
    private inline fun <T> consuming(consume: () -> T): T? =
        try {
            consume()
        } catch (_: IllegalStateException) {
            doubleDischargeCount.incrementAndGet()
            null
        }

    /**
     * The field walk behind [discharge]'s `else` branch.
     *
     * Synthetic and hidden runtime classes are not opened, for the same reason
     * [discharge] stops at `is Function<*>`: they are compiler-generated carriers whose
     * fields are *captures*, and a compiler-generated class can never be the declared type
     * of a contract parameter, so `ContractProcessor.carriesExclusive` can never mark a
     * method exclusive on account of anything inside one. `is Function<*>` alone does not
     * cover this — measured under review 2026-08-17, a capture behind a **Java** functional
     * interface (`class WithRunnable(val r: Runnable)`, `Runnable { captured.take() }`) is
     * not a `kotlin.Function`, and its carrier class
     * (`…$$Lambda/0x…`, `isHidden=true isSynthetic=true`) was still opened and the capture
     * consumed. Pinned by `ProxyDischargeReachTest`.
     */
    private fun dischargeFields(value: Any, seen: MutableSet<Any>) {
        var clazz: Class<*>? = value.javaClass
        if (clazz!!.isEnum || clazz.isPrimitive || isPlatformClass(clazz)) return
        if (clazz.isSynthetic || clazz.isHidden) return
        while (clazz != null && !isPlatformClass(clazz)) {
            clazz.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                // Only primitives are excluded by *declared* type: a field declared
                // `List<Owned<T>>` erases to a platform type and still holds exclusives, so
                // the decision to open a value belongs to its runtime class, above.
                if (field.type.isPrimitive) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.onSuccess { discharge(it, seen) }
            }
            clazz = clazz.superclass
        }
    }

    /** Declarations [dischargeFields] must not open — see [discharge]'s KDoc. */
    private fun isPlatformClass(clazz: Class<*>): Boolean {
        val name = clazz.name
        return name.startsWith("kotlin.") || name.startsWith("java.") ||
            name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
    }
}
