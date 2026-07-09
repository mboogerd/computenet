# 15 — Cell Lifecycle: Cold/Hot Phases

> **Status**: Specified and implemented (core); suspension states in 30/33
> **Sources**: ADR — Task Definitions, ADR — Computelet Kernel
> **Implementation**: `cell.Cell.onActivate`, `cell.host.ManagedHost.spawn`, `cell.port.PortDelegates`, `cell.port.PortRegistry`

## The unified model

One class per cell type serves as **both** the declarative specification and
the running logic — there is no `CellDef` / `CellInstance` split. (Rejected:
parallel Def/Instance hierarchies — duplication and glue; codegen-first —
premature indirection. Familiar precedent: Kotlin's cold `Flow` vs hot
`StateFlow`.)

The cell moves through phases:

```
COLD  --spawn(cell)-->  HOT  --suspend-->  SUSPENDED  --resume-->  HOT
 |                        |                                (30/33)
 construction only     onActivate(ctx) ran;
 ports are metadata    ports hydrated, logic serving
```

### Cold phase

- Constructor runs; **no logic executes**, no I/O, no port traffic.
- Ports declared via delegates (`by input<Api>()`, `by output<Api>()`) or
  explicit construction are dormant metadata, discoverable by hosts.
- The cell object may be freely passed around, composed into graph builders,
  serialized (future), or discarded.

### Activation

- A **Host** takes the cell (`spawn(cell)`), registers it, and calls
  `onActivate(CellContext)` **on the host's execution context** (verified by
  test: activation runs on the host's virtual thread, not the caller's).
- `onActivate` is where the cell serves its inlets
  (`inlet.serve(impl)` / `inlet.delegate(outlet)`) and establishes internal
  logic. Constructor-time serving (as `SetCell` does today in `init`) is
  DEPRECATED for hosted cells — see ⚠ CONFLICT C-7.
- Activation MUST be idempotent-safe or guarded: re-activation of an already
  hot cell is an error the host rejects.

### Hot phase

- Ports are live: inlets dispatch to served implementations; outlets emit.
- Composition operators (`map`, etc.) on a hot cell operate on live flows;
  on a cold cell they build graph structure. (This is the intended DSL
  semantics; the DSL itself is future work — 50/51.)

## Normative rules

1. **No logic in constructors.** All behavior establishment belongs in
   `onActivate`. Rationale: a cold cell must be safe to create anywhere,
   including on machines that will never run it (mobility, 30/33; codegen).
2. **Port declaration is discovery.** Whatever declaration style is used, the
   host must be able to enumerate ports by name without running cell logic.
3. **Hosting-specific operations require HOT.** Calling them cold is an error
   with a clear message (guarded phase state), not undefined behavior.
4. **Deactivation mirrors activation.** *(G-16 resolved, first phase:
   `Cell.onDeactivate(ctx)` exists; `despawn(ref)` unregisters the cell and
   invokes it on the host's execution context, and re-spawning a live ref is
   rejected. Unlink-before-deactivate ordering and state capture arrive with
   links (10/13) and mobility (30/33).)*

## ⚠ CONFLICT (C-7): two initialization styles in code

`MapperCell`, `HostTest` cells: serve inside `onActivate` (spec-conformant).
`SetCell`, `UnionSetCell`, `TrafficLightCell`: serve inside `init`
(constructor), and are used unhosted in tests.

**Resolution**: both are legitimate but must be named. A cell whose entire
behavior is pure, allocation-free serving MAY be an **eager cell** usable
unhosted (useful for tests and embedded composition), but then it MUST NOT
assume a host context and MUST tolerate `onActivate` never being called.
Hosted semantics (queues, isolation, mobility) apply only to spawned cells.
Document per cell which mode it supports; default guidance: use `onActivate`.

## Port discovery (G-17, resolved)

Port resolution is a map lookup in a per-cell `PortRegistry` (`name → Port`);
the reflective getter/field walk is gone. Delegate-declared ports
(`by input()` / `by output()`) register at construction via `provideDelegate`
— ports are therefore **eager**, so cold cells can have their ports enumerated
without touching logic. Explicit-style ports register via
`registerPort("name", FanInlet.create<…>())`; the registry key MUST equal the
property name (client-side proxies derive it from interface getters). The
registry is keyed per cell instance (JVM weak map today; KSP-generated
registries are the KMP path, C-5).
