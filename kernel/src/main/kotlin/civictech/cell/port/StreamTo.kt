package civictech.cell.port

import civictech.cell.link.Link
import civictech.cell.link.Linked
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.PortLink
import civictech.cell.link.handshake

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
 * negotiation is `bridgeTo`/`bridgeFrom`'s job) is not a local [Linked] port, so
 * it falls through to today's link install unchanged even under [negotiated].
 *
 * PN-12: the default is now [true] — the one deliberate behavior change of the
 * run. A local `streamTo` therefore negotiates by default (policies + allowlist +
 * nature reconcile + `EdgeOpen` as an `Observe` link); routed/cross-process
 * targets are unaffected (not [Linked]). Gated on the demo suite.
 */
fun <Api : Any> FanOutlet<Api>.streamTo(
    target: Api,
    at: PortRef = PortRef.generate(),
    negotiated: Boolean = true,
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
    // T21: the teardown drops the source-side bookkeeping as well as the
    // attachment — exactly what the negotiated `handshake` path above does
    // (`sourceLinking?.remove(link)`), which this bypass had never mirrored.
    // Without it an `unlink()`ed streamTo link stays in `linking.links`
    // forever: `SingleWriterReplication` unlinks a departed follower's
    // shipping link on every `onUnpublish` and rebuilds it on re-announce, so
    // each peer disconnect/reconnect left one dead link behind for
    // `Protocols.sendDownstream`/`AbsorbAck`/`Attention`/topology walks to
    // keep walking.
    val link = PortLink(ref, at) { superseded -> unsubscribe(at); linking.remove(superseded) }
    // T21: streaming again to the same [at] REPLACES the attachment — that is
    // already what `subscribe` does (`consumers[at] = port`), so the superseded
    // link must leave [LinkSupport] with it. `active` is keyed by a fresh random
    // `Link.id`, so without this the bookkeeping accumulates one orphan per
    // re-stream even though the outlet holds a single consumer, and everything
    // that walks `linking.links` (protocol relay, `AbsorbAck` progress,
    // attention, topology walks) counts the corpses. Not `unlink()`: that would
    // run the superseded link's teardown, `unsubscribe(at)`, and tear down the
    // attachment just installed. A caller that keeps the default generated [at]
    // never collides, so this is inert for every pre-T21 call site.
    linking.links.filter { it.to == at }.forEach(linking::remove)
    linking.register(link)
    // PN-9: fire the full on-link multicast (catch-up moved to onLinkedListeners),
    // not just the single onLinked slot, so a streamTo'd link still catches up.
    linking.fireLinked(link)
    return link
}
