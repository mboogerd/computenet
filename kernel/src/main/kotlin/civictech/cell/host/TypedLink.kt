package civictech.cell.host

import civictech.cell.link.LinkResult
import civictech.cell.port.PortIdentity
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.identity

/**
 * Typed wiring over [HostManagementApi.connect] (typed-port-links, 05): connects
 * two typed port *objects* instead of `(CellRef, "stringName")` pairs. The shared
 * [Api] type parameter is the whole point — an outlet's `Subscribe<Api>` and an
 * inlet's `Serve<Api>` unify only when their payload protocols agree, so a
 * mis-wire that today fails at runtime with [LinkResult.Rejected] (or silently)
 * is a **compile error** at the call site instead:
 *
 * ```
 * link(itemsUnion.outlet, wantedCell.left)   // both carry Propagate<SetDelta<String>>
 * link(wantedCell.outlet, count.inlet)
 * ```
 *
 * The wrong direction won't typecheck either: two outlets (`Subscribe`, `Subscribe`)
 * or two inlets (`Serve`, `Serve`) don't fit the `(Subscribe, Serve)` shape, and a
 * payload mismatch (`Subscribe<Propagate<SetDelta<String>>>` into
 * `Serve<Propagate<CounterDelta>>`) fails because `Api` is invariant and cannot
 * unify `Propagate<SetDelta<String>>` with `Propagate<CounterDelta>`.
 *
 * Purely a veneer: [link] resolves each port's `(ownerRef, registeredName)` from
 * its [PortIdentity] back-reference and lowers to the exact same
 * `connect(fromRef, "outlet", toRef, "inlet")` call — same [LinkResult], and a
 * [civictech.cell.graph.GraphBuilder] records a byte-identical
 * [civictech.cell.graph.ConnectStep], so GraphSpec replay / graphs-as-data is
 * unchanged. The stringly-typed [HostManagementApi.connect] stays the low-level
 * escape hatch for the dynamic-by-ref cases (routed proxies, GraphSpec
 * construction, live topology evolution) where only a [civictech.cell.CellRef]
 * is known.
 */
fun <Api> HostManagementApi.link(out: Subscribe<Api>, inn: Serve<Api>): LinkResult {
    val from = out.requireIdentity("outlet")
    val to = inn.requireIdentity("inlet")
    return connect(from.owner, from.name, to.owner, to.name)
}

/**
 * Recovers the port's `(ownerRef, name)` or fails loudly: a port reaches [link]
 * without a [PortIdentity] only when it was never registered on a spawned
 * [civictech.cell.Cell] (an ad-hoc [civictech.cell.port.Use.fixed] endpoint, or
 * a hand-built port never handed to a cell) — for those the caller must use the
 * stringly-typed `connect(ref, name, ...)` escape hatch.
 */
private fun civictech.cell.port.Port.requireIdentity(role: String): PortIdentity =
    identity() ?: throw IllegalArgumentException(
        "link: the $role port carries no (ownerRef, name) identity — it was not registered on a " +
            "spawned cell. Use the stringly-typed connect(ref, name, ...) escape hatch for by-ref wiring.",
    )
