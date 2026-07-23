# Typed port wiring — make `connect` type-checked instead of stringly-typed

> **Status: IMPLEMENTED** — typed `spawn`/`TypedCellHandle` + `link` (instance
> path) and KSP-generated `<Cell>Ports` InletId/OutletId objects with typed
> `connect` overloads (ref-only path); `FanInlet.create<>()`/delegates replace
> the raw-Class casts repo-wide.

## Origin

Building the tiering pipeline reads like this today
(`demo/tiering/.../TieringApp.kt`):

```kotlin
connect(vals,     "outlet", tierAvg, "inlet")
connect(contribs, "outlet", prefAvg, "inlet")
connect(tierAvg,  "outlet", fused,   "left")
connect(prefAvg,  "outlet", fused,   "right")
```

Port names are **strings**, and the payload types are erased. Nothing stops
`connect(tierAvg, "outlet", fused, "right")` (wrong side), a typo in `"inlet"`,
or wiring a `SetDelta` outlet into a `MapDelta` inlet — all fail at runtime, not
compile time. The demos also litter cell definitions with
`registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<E>>>))`
— an **unchecked cast** repeated for every port because the type can't be carried
through. This is the single biggest readability/safety tax in the demo code, and
it obscures the dataflow graph the framework is supposedly about.

## What it is

A typed wiring API that carries the payload type from port declaration to
`connect`, so mis-wiring is a compile error and the unchecked casts disappear.
The port objects already expose typed shapes (`Serve<Propagate<SetDelta<E>>>`,
`Subscribe<...>`); the goal is to let `connect` consume those typed handles
directly instead of `(cellRef, "stringName")`.

## Why it fits the framework

- The framework's whole thesis (per `AGENTS.md`) is that "dispatch classes,
  direction, cardinality, ownership ... are semantic contracts, not optimization
  hints." Encoding those contracts in the **type system at wiring time** makes the
  contract executable rather than aspirational.
- Generated descriptors/proxies are already "authoritative runtime metadata"
  (AGENTS.md); the typed port handles are the compile-time projection of that same
  metadata, so this is additive, not a parallel model.
- It changes ergonomics only — the underlying link/registry semantics are
  untouched, preserving wire compatibility.

## Solution sketch

Give ports typed connectable handles and overload `connect` on them:

```kotlin
// today:  connect(vals, "outlet", tierAvg, "inlet")
// typed:  connect(vals.outlet, tierAvg.inlet)   // both sides carry Propagate<SetDelta<Valuation>>
```

- `Outlet<P>` / `Inlet<P>` expose a phantom-typed handle `PortHandle<P>` obtained
  from the cell's generated proxy (`host.lookup<T>(ref)` already returns typed
  proxies — extend that path so the port handle, not a string, is the connect
  argument).
- `connect(out: OutletHandle<P>, in: InletHandle<P>)` — the shared `P` makes
  direction and payload-type agreement compile-checked; wrong side or wrong
  payload won't typecheck.
- Provide a `registerPort` helper that infers the `Class<Propagate<T>>` from a
  reified type parameter, removing the `as Class<...>` casts:
  `inlet<SetDelta<E>>("inlet")` instead of the manual erased-class construction.
- Keep the string-based `connect` as the dynamic/graph-evolution escape hatch
  (GraphSpec construction still needs name-addressable ports).

## Inputs / outputs

- **Input**: two typed port handles (an outlet and an inlet) whose protocol types
  unify.
- **Output**: a `Link`, identical to today's; only the call site is type-checked.
- **Also**: a reified `inlet<T>()/outlet<T>()` registration helper returning the
  correctly-typed port with no unchecked cast in app code.

## Acceptance criteria

- `connect(a.outlet, b.inlet)` compiles only when the payload protocols match;
  connecting mismatched payloads or two inlets/two outlets is a compile error.
- The tiering pipeline rewires with zero string literals and zero
  `@Suppress("UNCHECKED_CAST")` in `FuseCell`/`SetHubCell`/`MapHubCell` port
  declarations.
- Runtime link identity, cardinality, direction, and wire encoding are byte-for-
  byte unchanged (existing wire/kernel tests pass; no descriptor format change).
- The string-addressed `connect` remains available for GraphSpec / live topology
  evolution (F-4) where ports are resolved by name at runtime.
- A short before/after in one demo demonstrates the readability win; generator
  (`gen/`) still emits the same descriptors.
