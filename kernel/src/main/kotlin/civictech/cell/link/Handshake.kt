package civictech.cell.link

import civictech.cell.control.Magnitude
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.FeedbackInlet
import civictech.cell.port.LinkTo
import civictech.cell.nature.NatureNegotiation
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.protocol.ProtocolId
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.nature.Reconciliation
import civictech.cell.port.natures
import civictech.nature.MergeClass
import civictech.nature.Monotonicity
import civictech.nature.NatureAxis
import civictech.nature.NatureVector

/** See [Link.protocolBridge]. */
fun interface ProtocolBridge {
    fun send(id: ProtocolId, message: Any, upstream: Boolean)
}

/**
 * CP-F3: reconcile a producer's [offered] natures against a consumer's
 * [required] natures, mapping a [Reconciliation.Refuse] onto the typed
 * [LinkResult.Rejected] the handshake returns. Null ⇒ [Reconciliation.Direct]
 * ⇒ the link proceeds exactly as today.
 */
private fun reconcileNatures(offered: NatureVector, required: NatureVector): LinkResult.Rejected? =
    when (val outcome = NatureNegotiation.reconcile(offered, required)) {
        Reconciliation.Direct -> null
        is Reconciliation.Refuse -> LinkResult.Rejected(
            outcome.mismatch,
            "nature mismatch on ${outcome.mismatch.axis}: producer offers " +
                "${outcome.mismatch.offered}, consumer requires ${outcome.mismatch.required} " +
                "(no adapter — typed refusal, CP-F3)",
        )
    }

/**
 * T08 finding 1: the payload class each side declares, when the port is a
 * [FanOutlet]/[FanInlet] — the only two [Linked] port kinds that carry it
 * ([FanOutlet.clazz] / [FanInlet.clazz], both constructor fields already).
 * `null` for any other port kind (e.g. [civictech.cell.port.FeedbackInlet]),
 * which simply opts the check out rather than risking a false refusal.
 */
private fun payloadClassOf(port: Any?): Class<*>? = when (port) {
    is FanOutlet<*> -> port.clazz
    is FanInlet<*> -> port.clazz
    else -> null
}

/**
 * T08 finding 1: `ManagedHost.connect`'s runtime path forces both endpoints to
 * `LinkTo<Any>`/`LinkFrom<Any>` (string-keyed `connect`, and every
 * [civictech.cell.graph.GraphSpec] replay) — nothing before this compared the
 * declared payload class on either side, so a miswired link reported
 * `Connected` and only failed as a `ClassCastException` on the dispatch
 * thread at first delivery, sanitized into a dead letter arbitrarily far from
 * the `connect` that caused it.
 *
 * Erasure caveat, sharper than it first looks: `.clazz` is `Api::class.java`
 * (a [FanOutlet]/[FanInlet] constructor field), and `Api::class` never
 * carries nested generic arguments. Every delta-streaming outlet/inlet in
 * this codebase declares its `Api` as `Propagate<D>`
 * (`FanOutlet.create<Propagate<D>>()`), and `Propagate<D>::class.java` is
 * `Propagate::class.java` regardless of `D` — so this check does NOT
 * distinguish `SetDelta` from `MapDelta` when both sides are
 * `Propagate`-wrapped (two data-cell delta ports of different shapes, the
 * common case). It DOES catch a wrong-shaped connect: a delta outlet
 * (`Propagate<...>`) wired into a write inlet (`SetOps<E>`/`MapOps<K, V>`/…),
 * or any other pair of genuinely different top-level port interfaces —
 * exactly the class of error the erased `connect` path cannot see coming
 * from its own signature.
 *
 * That leaves finding 1's *headline* case open: `SetCell.outlet` into
 * `MapHubCell.inlet` still reports `Connected` and still dies as a
 * `ClassCastException` at first delivery. Tracked as an open residual in
 * `doc/remediation/COVERAGE.md` ("same-wrapper payload mismatch still
 * unchecked") and pinned by `PayloadTypeCheckTest`'s KNOWN GAP test; closing
 * it needs the port to carry a declared payload class independent of `Api`
 * erasure, since generic cells create their ports under a non-reified type
 * parameter and so cannot capture `typeOf<Api>()`.
 */
private fun checkPayload(portOut: Any, target: Any, portOutRef: PortRef, targetRef: PortRef): LinkResult.Rejected? {
    val outClazz = payloadClassOf(portOut) ?: return null
    val inClazz = payloadClassOf(target) ?: return null
    if (outClazz != inClazz) {
        return LinkResult.Rejected(
            "payload mismatch: ${outClazz.name} -> ${inClazz.name} at $portOutRef -> $targetRef",
        )
    }
    return null
}

/**
 * FU-8 — is there a *damping witness* for a cycle closing on [head], fed by
 * the closing edge's producer [outlet]? A head guarantees headedness but not
 * termination; admit the loop only when at least one witness holds (spec 21
 * §Cycles, ADR 1 feature 8). Any of:
 *
 *  1. **Magnitude payload** — the weak-tier quiescence damper is live. Tested
 *     the same way [FeedbackInlet] dispatches at runtime (`is Magnitude`),
 *     here against the reified payload class the
 *     [civictech.cell.port.feedbackInlet] delegate records; equivalently the
 *     KSP scan stamps such a producer MONOTONE (2).
 *  2. **Fixpoint convergence** — the producer declares [Monotonicity.MONOTONE]
 *     or an [MergeClass.IDEMPOTENT] merge, so laps fold to a fixpoint.
 *  3. **Explicit quiescence override** — the head was constructed with a
 *     `quiescence > 0` threshold, an intentional divergence damper.
 *
 * Moved from `ManagedHost` (T11-A): a link-admission-time predicate over
 * nature vectors, the same shape as [reconcileNatures] above — both read a
 * port's [natures] to decide whether a link (here, a cycle-closing one)
 * proceeds. `internal` (not `private`): the sole caller is
 * `civictech.cell.host.LinkAdmission.admitCycle` (T11-B), behind
 * `ManagedHost.connect`.
 */
internal fun hasDampingWitness(outlet: Port, head: FeedbackInlet<*>): Boolean {
    head.payloadType?.let { if (Magnitude::class.java.isAssignableFrom(it)) return true }
    val natures = outlet.natures
    if (natures.level(NatureAxis.MONOTONICITY).rank >= Monotonicity.MONOTONE.rank) return true
    if (natures.level(NatureAxis.MERGE_IDEMPOTENCE).rank >= MergeClass.IDEMPOTENT.rank) return true
    return head.quiescence > 0.0
}

/**
 * Runs the handshake shared by the inlet implementations:
 * target policies → source policies → cardinality (checked by the caller) →
 * onLink → install.
 *
 * **Both** endpoints' `linking.policies` are evaluated, in that order (SEC1
 * seam 2, decided 93 I-28 §4.3): the target's first, so every refusal string a
 * caller or test already asserts on is unchanged; then the source's, so a
 * *producing* membrane can refuse a subscriber even though the port being
 * linked *to* is the consumer's own inlet, outside that membrane
 * (`CompositeCell.mediateOutlet`'s producer-side subscribe authority). Both run
 * before `checkPayload`/nature reconciliation/`onLink`/`install`, so a refusal
 * from either side leaves no half-registered port and no subscriber entry.
 * A port with no declared policies short-circuits ([LinkSupport.reject] over an
 * empty list is null), so default-open exposures are unaffected.
 */
internal fun <Api> handshake(
    portOut: LinkTo<Api>,
    target: Linked,
    targetRef: PortRef,
    role: LinkRole = LinkRole.Consume,
    install: () -> Unit,
    uninstall: (Link) -> Unit,
): LinkResult {
    val support = target.linking
    // identity rides the delivery (M8.2): bridged requests carry their peer
    val request = LinkRequest(portOut.ref, targetRef, CurrentPeer.get(), role)
    support.reject(request)?.let { return it }

    // The producing side's own admission, over the SAME request (same identity,
    // same role): "who may subscribe to my feed" is the producer's question, and
    // for an exposed outlet the handshake target is the consumer's inlet, so the
    // target gate above cannot ask it. Evaluated second so target-side refusals
    // keep priority, and before any install so a refusal is topology-invisible.
    val sourceLinking = (portOut as? Linked)?.linking
    sourceLinking?.reject(request)?.let { return it }

    // T08 finding 1: the payload-type witness — before natures/install, so a
    // structurally wrong-shaped connect never reaches onLink/install at all.
    checkPayload(portOut, target, portOut.ref, targetRef)?.let { return it }

    // CP-F3: reconcile the two port nature-vectors after policies, before
    // install. Direct (the same-nature/default fast path) costs nothing; a
    // scoped-axis conflict becomes a loud typed refusal where today the
    // mismatch would drop silently at first emission.
    reconcileNatures(portOut.natures, (target as? Port)?.natures ?: NatureVector.DEFAULT)
        ?.let { return it }

    val link = PortLink(portOut.ref, targetRef, portOut, target as? Port, role) { link ->
        // The close is terminal on the link's in-process protocol/data FIFO:
        // announce it while both endpoints are still reachable, then detach.
        link.toPort?.let { port ->
            val protocols = ProtocolSupport.of(port)
            if (protocols.handles(Protocols.TopologyOrder)) {
                protocols.deliver(Protocols.TopologyOrder, link, EdgeClose)
            }
        }
        uninstall(link)
        sourceLinking?.remove(link)
        support.remove(link)
        support.onUnlink(link)
        support.onUnlinkListeners.forEach { it(link) }
        sourceLinking?.onUnlinkListeners?.forEach { it(link) }
    }
    return when (val result = support.onLink(link)) {
        is LinkResult.Connected -> {
            install()
            // Both sides retain the identity this link was ESTABLISHED with, so
            // a later rebind re-authorizes the original peer rather than
            // whoever is ambient at rebind time ([SEC1-10]; the source side is
            // the one promotion consults).
            support.register(link, request.identity)
            sourceLinking?.register(link, request.identity)
            // Only topology-interested consumers pay for edge markers.  Open
            // precedes onLinked catch-up and every subsequent data invocation.
            link.toPort?.let { port ->
                val protocols = ProtocolSupport.of(port)
                if (protocols.handles(Protocols.TopologyOrder)) {
                    protocols.deliver(Protocols.TopologyOrder, link, EdgeOpen)
                }
            }
            support.onLinked(link)
            sourceLinking?.onLinked?.invoke(link)
            support.onLinkedListeners.forEach { it(link) }
            sourceLinking?.onLinkedListeners?.forEach { it(link) }
            result
        }

        else -> result
    }
}

/**
 * Handshake for a pre-built [link] whose counterpart is not a local [Port] —
 * a bridged `WireEdgeLink` resolved across the wire (spec 41 point 4, closes
 * C-13). Runs the same target-side gate a local link runs: the [local] port's
 * link policies + peer allowlist (43, identity from [CurrentPeer]) and its
 * `onLink` admission; on acceptance installs the given [link] (rather than
 * minting a `PortLink`), wires the unlink teardown + the `onLinked`/`onUnlink`
 * multicast hooks, and — for the producer side ([fireEdgeOpen]) — emits
 * `EdgeOpen` downstream across the bridge through the negotiated protocol path
 * (the consumer side instead receives it as an ordinary in-band frame).
 *
 * This is the overload `bridgeTo`/`bridgeFrom` route through so a bridged edge
 * negotiates identically to a local one; transport stays out of `cell.port`
 * (the caller supplies the already-resolved [link] and its `protocolBridge`).
 */
internal fun handshake(
    link: Link,
    from: PortRef,
    targetRef: PortRef,
    local: Linked,
    role: LinkRole = LinkRole.Consume,
    fireEdgeOpen: Boolean = false,
    counterpart: NatureVector = NatureVector.DEFAULT,
): LinkResult {
    val support = local.linking
    val request = LinkRequest(from, targetRef, CurrentPeer.get(), role)
    support.reject(request)?.let { return it }

    // CP-F3: the bridged edge runs the *same* pure reconcile as a local link,
    // so the verdict is location-transparent (BridgedHandshakeTest asserts
    // localVerdict == remoteVerdict). The remote endpoint's vector arrives as
    // [counterpart]; today's callers pass DEFAULT (additive, zero behavior
    // change) — carrying the peer's descriptor vector across the wire is a
    // follow-on. `fireEdgeOpen` marks the producer side, fixing which vector is
    // offered vs required.
    val localNatures = (local as? Port)?.natures ?: NatureVector.DEFAULT
    val offered = if (fireEdgeOpen) localNatures else counterpart
    val required = if (fireEdgeOpen) counterpart else localNatures
    reconcileNatures(offered, required)?.let { return it }

    return when (val result = support.onLink(link)) {
        is LinkResult.Connected -> {
            // Same establishing-identity retention as the in-process path: a
            // bridged link's peer is exactly the identity a rebind must re-present.
            support.register(link, request.identity)
            link.onUnlink { l ->
                support.remove(l)
                support.onUnlink(l)
                support.onUnlinkListeners.forEach { it(l) }
            }
            if (fireEdgeOpen) {
                // Only topology-interested peers pay: crosses the wire iff the
                // remote inlet handles TopologyOrder, exactly as the local path.
                Protocols.sendDownstream(link, Protocols.TopologyOrder, EdgeOpen)
            }
            support.onLinked(link)
            support.onLinkedListeners.forEach { it(link) }
            result
        }

        else -> result
    }
}
