package civictech.cell.link

import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkTo
import civictech.cell.nature.NatureNegotiation
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.protocol.ProtocolId
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.nature.Reconciliation
import civictech.cell.port.natures
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
 * Runs the target-side handshake shared by the inlet implementations:
 * policies → cardinality (checked by the caller) → onLink → install.
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
    support.reject(LinkRequest(portOut.ref, targetRef, CurrentPeer.get(), role))?.let { return it }

    // T08 finding 1: the payload-type witness — before natures/install, so a
    // structurally wrong-shaped connect never reaches onLink/install at all.
    checkPayload(portOut, target, portOut.ref, targetRef)?.let { return it }

    // CP-F3: reconcile the two port nature-vectors after policies, before
    // install. Direct (the same-nature/default fast path) costs nothing; a
    // scoped-axis conflict becomes a loud typed refusal where today the
    // mismatch would drop silently at first emission.
    reconcileNatures(portOut.natures, (target as? Port)?.natures ?: NatureVector.DEFAULT)
        ?.let { return it }

    val sourceLinking = (portOut as? Linked)?.linking
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
            support.register(link)
            sourceLinking?.register(link)
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
    support.reject(LinkRequest(from, targetRef, CurrentPeer.get(), role))?.let { return it }

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
            support.register(link)
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
