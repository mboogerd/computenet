# Routed inlet handles without a per-port proxy interface

**Origin**: agora demo (`demo/agora/AgoraService.kt`). Every place the service
needs to *send* to a cell's inlet by `CellRef` it goes through a hand-written
one-method interface plus a reflective proxy cast. The pattern is pure ceremony
and it's the least readable part of an otherwise clean service.

## The observation

To get a routed write-handle to a named inlet, agora declares one interface per
port and threads it through `HostedCellProxy.create(...) as X`:

```kotlin
interface StanceInletProxy   { val stanceInlet:   Use<Propagate<StanceDelta>> }
interface InfluenceInletProxy{ val influenceInlet:Use<Propagate<InfluenceDelta>> }
interface SourceInletProxy   { val sourceInlet:   Use<Propagate<CredenceUpdate>> }
interface HubInletProxy      { val inlet:         Use<Propagate<CredenceUpdate>> }

private fun routedStance(id: CellRef): Propagate<StanceDelta> =
    (HostedCellProxy.create(id, registry, StanceInletProxy::class.java)
        as StanceInletProxy).stanceInlet.call
// …three more identical helpers, one per port…
```

Four interfaces + four helpers exist only to name a port and recover a
`Propagate<T>`. The property name "MUST match the registered port name" (per the
service's own comment) — a stringly-typed contract hidden behind a type, with an
unchecked cast at every call.

## What it is

A first-class API to obtain a routed `Propagate<D>` (or `Use<Api>`) for a named
port on a cell addressed by `CellRef`, given the payload type, **without declaring
a proxy interface per port**.

## Why it's a proper fit for the framework

- Routed, register-through-the-host wiring is the *recommended* demo idiom (it's
  what keeps every hop staged for attention/magnitude scheduling — see
  `agora-scheduler-staged-links.md`). The framework endorses this path but makes it
  verbose, so every demo pays the same tax (shopping, slotfinder, skillmatch all
  have the same proxy interfaces).
- The proxy machinery (`HostedCellProxy`, `LocationRegistry`) already exists; this
  is a thin, type-safe front door to it, not new runtime capability.
- It removes an unchecked `as` cast and a stringly-typed name/property coupling
  from the hot path — a correctness and readability win.

## Solution sketch

A reified helper on the registry/host that resolves a port by name and returns its
call handle:

```kotlin
// today (4 interfaces + 4 helpers) collapses to:
val stance:    Propagate<StanceDelta>    = registry.inlet(id,   "stanceInlet")
val influence: Propagate<InfluenceDelta> = registry.inlet(target,"influenceInlet")
val hub:       Propagate<CredenceUpdate> = registry.inlet(hub.ref,"inlet")

// signature:
inline fun <reified D : Any> LocationRegistry.inlet(
    cell: CellRef, port: String,
): Propagate<D>
```

Resolution can validate the port exists and its payload type matches `D` at
lookup time (using the same registered-port metadata the proxy already reads),
turning today's unchecked cast into a checked failure with a clear message.

## Expected inputs / outputs

- **Input**: a `CellRef`, a port name, and (reified) the payload type.
- **Output**: a `Propagate<D>` bound to that cell's inlet, routed through the host
  queue exactly as the current proxy path is.
- **Error**: unknown cell / unknown port / payload-type mismatch → a typed failure
  naming the cell and port, instead of a `ClassCastException` or a silent
  wrong-port send.

## Acceptance criteria

- agora's four `*InletProxy` interfaces and four `routed*` helpers collapse to
  call sites of the new helper, with `AgoraServerTest`/`AgoraExitTest` unchanged
  and green.
- A wrong port name or a payload-type mismatch is rejected at lookup with a message
  naming both, covered by a kernel test.
- No new allocation on the steady-state send path beyond what the proxy does today
  (cache the resolved handle; the helper may return a reusable `Propagate`).

## Related

- `agora-scheduler-staged-links.md` — why routed handles (not DSL links) are used
  at all.
