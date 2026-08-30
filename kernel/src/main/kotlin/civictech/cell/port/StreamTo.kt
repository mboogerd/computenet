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
    //
    // computenet-9wpa re-read that `SingleWriterReplication` claim rather than
    // trusting it, and it holds: `SingleWriterReplication.shipTo` builds the
    // shipping link with `streamTo(sink)` where `sink` is a `HostedCellProxy`
    // routed api (or a lambda wrapping it) — not a `Linked` port, so it takes
    // the bypass below even under the default `negotiated = true` — and
    // `registry.onUnpublish` calls `unlink()` on it per departed follower. The
    // teardown here therefore does run once per peer disconnect in ordinary
    // replication operation. What does NOT follow, contrary to the bead that
    // asked the question, is an attention leak on that path; see below.
    //
    // computenet-9wpa, decided for the TEARDOWN site specifically — this is a
    // real unlink, not a supersession, so computenet-4jpd's line for
    // `Handshake.evictSuperseded` does NOT transfer and was re-derived here.
    // Three notifications were on the table; exactly one is fired:
    //
    // - `linking.onUnlinkListeners` IS fired (the change). It is the
    //   infrastructure multicast whose subscribers key state by `Link.id` —
    //   precisely the identity that dies here — and `LinkSupport.remove` is a
    //   bare map delete that told nobody. The negotiated teardown a few lines
    //   above fires exactly this on the source side (`sourceLinking
    //   ?.onUnlinkListeners`), so the bypass now matches it rather than being
    //   the one link teardown in the kernel that is silent.
    // - `EdgeClose` is NOT fired, and the reason is mechanical rather than
    //   4jpd's "the edge survives": `handshake` delivers `EdgeClose` to
    //   `link.toPort`, and a bypass-path link HAS no `toPort`. The target is a
    //   bare `Api` object — a routed proxy, or a filtering lambda
    //   (`SingleWriterReplication.shipTo`) — never a local `Port`, which is the
    //   very condition that routed it down this path. There is no endpoint to
    //   deliver a topology marker to. Whether a *routed* target should learn of
    //   the close over the wire is a separate question with a much larger blast
    //   radius; it is not answered here.
    // - The cell-facing `linking.onUnlink` slot is NOT fired, because the
    //   negotiated teardown does not fire it on the SOURCE side either — it
    //   fires `support.onUnlink`, the TARGET's slot, and this path has no target
    //   `LinkSupport` at all. Firing the source's would make the bypass louder
    //   than the negotiated path it is supposed to mirror.
    //
    // What this fixes today is a latent trap, not a measured leak: the repo's
    // only `onUnlinkListeners` subscriber is `AttentionSupport.wire`'s frontier
    // GC, guarded on `link.fromPort === port`, and the two-argument `PortLink`
    // below leaves `fromPort` null — so no attention slot is ever created for
    // one of these links, and none was being stranded. Measured in
    // `StreamToUnlinkNotificationTest`, whose third case RECORDS that finding
    // rather than guarding it — see its doc comment: no assertion there
    // discriminates, because a bare-`Api` target has no `AttentionSupport` to
    // report a band up the link even if the link did carry a `fromPort`.
    val link = PortLink(ref, at) { superseded ->
        unsubscribe(at)
        linking.remove(superseded)
        linking.onUnlinkListeners.forEach { it(superseded) }
    }
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
    //
    // computenet-9wpa, decided for the SUPERSESSION site: fire
    // `onUnlinkListeners` and suppress `EdgeClose` / the cell-facing `onUnlink`
    // — the same split `Handshake.evictSuperseded` settled under
    // computenet-4jpd, and here it holds for BOTH of its reasons. 4jpd's:
    // `EdgeClose`/`onUnlink` describe the EDGE, which survives — `subscribe`
    // just re-established the attachment under a new record, so announcing a
    // close would be false. And the teardown site's mechanical one: there is no
    // `toPort` to deliver `EdgeClose` to on this path anyway. The multicast is
    // what `Link.id`-keyed state needs, and that id genuinely dies here.
    linking.links.filter { it.to == at }.forEach { superseded ->
        linking.remove(superseded)
        linking.onUnlinkListeners.forEach { it(superseded) }
    }
    linking.register(link)
    // PN-9: fire the full on-link multicast (catch-up moved to onLinkedListeners),
    // not just the single onLinked slot, so a streamTo'd link still catches up.
    linking.fireLinked(link)
    return link
}
