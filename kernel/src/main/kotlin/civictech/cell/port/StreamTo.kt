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
 */
fun <Api : Any> FanOutlet<Api>.streamTo(target: Api, at: PortRef = PortRef.generate()): Link {
    subscribe(Use.fixed(target, at))
    val link = PortLink(ref, at) { unsubscribe(at) }
    linking.register(link)
    linking.onLinked(link)
    return link
}
