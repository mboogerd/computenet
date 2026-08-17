package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.host.LocationRegistry
import java.util.concurrent.CompletableFuture

/**
 * A bounded read issuable against a bare [CellRef], for a caller that does
 * not hold the hosting [civictech.cell.host.ManagedHost] (KRD-01) — the
 * routing [civictech.cell.host.ManagedHost.readState] already does
 * internally for a ref absent from its own `cells` map, generalized to any
 * caller that only has a [LocationRegistry].
 *
 * Homed in `civictech.cell.observe` rather than beside `readState` itself: a
 * read is explicitly not a subscription (see
 * [civictech.cell.BoundedRead]'s header), so it sits with the other
 * app-facing read surface rather than inside the push-side
 * [civictech.cell.observe.Observe] / [civictech.cell.observe.AlignedObserve]
 * machinery.
 *
 * Routing mirrors `readState`'s own internal ordering — the migration arm
 * first, because a held ref may still be present on its current host, and a
 * local answer would be a stale answer wearing a fresh timestamp:
 *
 * 1. [ref] is held for a migration flip ([LocationRegistry.holds]) ->
 *    an already-completed [StateReadResult.Unavailable] with
 *    [StateReadResult.Reason.MIGRATING]. Delegating straight to `readState`
 *    would also answer MIGRATING for a held-but-still-local ref, since
 *    `readState` checks its own hold first — this explicit pre-check exists
 *    so the routed entry gives that same one answer regardless of whether
 *    [registry] still places the held ref locally.
 * 2. [registry] places [ref] on a local host ([LocationRegistry.Local]) ->
 *    that host's own `readState(ref, request)` future, unchanged — the
 *    seam's own [CompletableFuture], not a wrapper that could complete
 *    differently (KRD-02).
 * 3. [registry] places [ref] on another host ([LocationRegistry.Remote]) ->
 *    an already-completed [StateReadResult.Unavailable] with
 *    [StateReadResult.Reason.MIGRATING]. Remote/scatter-gather bounded read
 *    is open research (see [civictech.cell.BoundedRead]'s remote-read note);
 *    a `Remote` ref is answered MIGRATING, full stop (KRD-03).
 * 4. [registry] does not place [ref] at all -> an already-completed
 *    [StateReadResult.Unavailable] with [StateReadResult.Reason.NOT_HOSTED]
 *    (KRD-04).
 *
 * Every refusal is a normally-completed future carrying a named
 * [StateReadResult] — never `null`, never an empty page, never an exception
 * (KRD-08). No [StateReadResult.Reason] arm is minted and none is collapsed
 * into another (KRD-05): this is the same taxonomy `readState` already
 * answers with, not a second one.
 *
 * Strictly additive (KRD-28): this delegates to
 * [civictech.cell.host.ManagedHost.readState] as-is and changes nothing
 * about its signature, [StateRead], [civictech.cell.StatePage], or
 * [StateReadResult].
 */
fun readRouted(
    registry: LocationRegistry,
    ref: CellRef,
    request: StateRead,
): CompletableFuture<StateReadResult> {
    fun unavailable(reason: StateReadResult.Reason): CompletableFuture<StateReadResult> =
        CompletableFuture<StateReadResult>().also { it.complete(StateReadResult.Unavailable(reason)) }

    // Ordered so the migration check happens before location is even
    // consulted: a held ref may still be Local, and readState's own
    // holds-first check is what this pre-check is aligning with.
    if (registry.holds.isHeld(ref)) return unavailable(StateReadResult.Reason.MIGRATING)

    return when (val location = registry.location(ref)) {
        is LocationRegistry.Local -> location.host.readState(ref, request)
        is LocationRegistry.Remote -> unavailable(StateReadResult.Reason.MIGRATING)
        null -> unavailable(StateReadResult.Reason.NOT_HOSTED)
    }
}
