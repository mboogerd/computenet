package civictech.cell.port

/**
 * Streaming link through a routed stand-in (M5.7): subscribe [target] —
 * typically a registry-routed proxy api — as a consumer of this outlet,
 * install a link, and fire the outlet-side `onLinked` hook, so late-join
 * catch-up (G-22, spec 21) flows through the same routed path as every
 * subsequent delta: host queue in-process, wire frames across a bridge.
 *
 * This replaces the direct-handshake-then-swap idiom, which needed object
 * access to the target inlet — impossible when the target lives in another
 * process.
 *
 * PN-10: opt-in [negotiated]. Default [false] is byte-for-byte today's bypass.
 * When [true] and [target] is reachable as a local [Linked] port, the stream
 * runs the same target-side handshake a Consume link runs (policies + peer
 * allowlist + nature reconcile + EdgeOpen) with [LinkRole.Observe] — so the
 * mesh's `streamTo`-built edges negotiate and announce, yet never gate a
 * consumer's frontier. A routed proxy target (the cross-process case, whose
 * negotiation is `bridgeTo`/`bridgeFrom`'s job) still installs today's link,
 * but installs today's link unchanged. PN-12 flips the default to [true].
 */
fun <Api : Any> FanOutlet<Api>.streamTo(
    target: Api,
    at: PortRef = PortRef.generate(),
    negotiated: Boolean = false,
): Link {
    if (negotiated) {
        (target as? Linked)?.let { linked ->
            return when (val result = handshake(
                portOut = this,
                target = linked,
                targetRef = at,
                role = LinkRole.Observe,
                install = { subscribe(Use.fixed(target, at)) },
                uninstall = { unsubscribe(at) },
            )) {
                is LinkResult.Connected -> result.link
                else -> error("negotiated streamTo refused: $result")
            }
        }
    }
    subscribe(Use.fixed(target, at))
    val link = PortLink(ref, at) { unsubscribe(at) }
    linking.register(link)
    // PN-9: fire the full on-link multicast (catch-up moved to onLinkedListeners),
    // not just the single onLinked slot, so a streamTo'd link still catches up.
    linking.fireLinked(link)
    return link
}
