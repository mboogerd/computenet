# Typed port links: replace `(ref, "string")` connect pairs

> **Status: IMPLEMENTED** — `GraphBuilder.link(outlet, inlet)` + standalone
> `Use<HostManagementApi>.link` (instance path), lowering to the identical
> `ConnectStep`; see `kernel/.../graph/GraphDsl.kt` and `TypedLinkTest`.

## Origin
Wiring the new intersect view in `demo/shopping` meant writing:
```
manage.connect(itemsUnion.ref, "outlet", wantedCell.ref, "left")
manage.connect(votesUnion.ref, "outlet", wantedCell.ref, "right")
manage.connect(wantedCell.ref, "outlet", refs.getValue("count"), "inlet")
```
Every port is addressed by a **stringly-typed name** (`"outlet"`, `"left"`,
`"right"`, `"inlet"`) against an untyped `CellRef`. Nothing checks that
`wantedCell` even *has* a `"left"` port, that its type matches the source's
outlet payload, or that `"inlet"` wasn't a typo for `"input"`. The failure mode
is a runtime `LinkResult.Rejected` (or silence) far from the mistake. This isn't
demo-only: `RelationalGraphs.leftJoin` wires its internals the same way
(`connect(left, "outlet", matched, "left")`).

The cells already expose **typed port objects** — `itemsUnion.outlet`,
`wantedCell.left`, `wantedCell.right` — of types `Subscribe<...>` and
`Serve<...>`. The connect API just throws that type information away.

## What it is
A typed linking API that connects port *objects*, not `(ref, name)` pairs:
```
link(itemsUnion.outlet, wantedCell.left)
link(votesUnion.outlet, wantedCell.right)
link(wantedCell.outlet, count.inlet)
```
`link` resolves each port's owning ref + registered name from the port object
itself, and the generic signature `link(out: Subscribe<Propagate<T>>, in: Serve<Propagate<T>>)`
makes a payload-type mismatch a **compile error**, not a runtime rejection.

## Why it fits the framework
- Purely additive veneer over the existing host protocol — same "records into a
  `GraphSpec`, no new semantics" contract the `GraphBuilder` already honors
  (GraphDsl.kt:217). `link` lowers to the exact `connect(fromRef, outlet, toRef, inlet)`
  call; it only recovers the strings the caller already typed by hand.
- Port identity is described in AGENTS.md as an explicit, semantic contract
  ("Cell identity and port identity remain explicit"). Typed ports make that
  contract *checkable* at the call site instead of trusting matching string
  literals on both ends.
- It cleans the kernel's own builders (`RelationalGraphs`) as much as the demos.

## Solution sketch
- Give registered ports a back-reference to `(ownerRef, portName)` (the registry
  already knows both at `registerPort` time).
- Add:
  ```
  fun <T> link(out: Subscribe<Propagate<T>>, inn: Serve<Propagate<T>>): LinkResult
  // and a GraphBuilder overload that also records the ConnectStep
  fun <T> GraphBuilder.link(out: Subscribe<Propagate<T>>, inn: Serve<Propagate<T>>): Unit
  ```
- Keep `connect(ref, name, ref, name)` as the low-level/dynamic-by-ref escape
  hatch (needed when only a `CellRef` is known, e.g. routed proxies).

## Inputs / outputs
- **Input:** two typed port objects from already-constructed cells.
- **Output:** a `LinkResult` (or a throwing `GraphBuilder.link` mirroring
  `connect`); the recorded `ConnectStep` is byte-identical to the string form, so
  `GraphSpec` replay/graphs-as-data is unchanged.

## Acceptance criteria
- `link(a.outlet, b.left)` produces the same `ConnectStep("a","outlet","b","left")`
  and runtime link as the string form; a round-trip `GraphSpec` test proves
  equivalence.
- A payload-type mismatch (`Subscribe<SetDelta<String>>` → `Serve<CounterDelta>`)
  fails to compile.
- `RelationalGraphs` and `demo/shopping` are ported to `link`; all existing tests
  pass unchanged.
- The dynamic `connect(ref, name, …)` path still exists and is still used where
  only a ref is available.
