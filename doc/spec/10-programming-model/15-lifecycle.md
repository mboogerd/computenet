# 15 — Cell Lifecycle: Cold/Hot Phases

> **Status**: Specified and implemented (core); suspension states in 30/33; admission/activation split decided in 93 I-26 (parked-tail window unimplemented)
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

- Constructor runs; **no logic executes**, no I/O, no port traffic —
  precisely: no data-traffic *dispatch*. Structural **admission** of links is
  legal cold; see §Admission vs activation.
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
  logic. Constructor-time serving (as `SetCell` does today in `init`) is the
  declared **eager** mode — see §Admission vs activation.
- [15-ACTIVATE-01] Activation MUST be idempotent-safe or guarded: re-activation of an already
  hot cell is an error the host rejects.
- Promotion state handoff is **not** activation (matches code; decided in
  [93 I-27](../90-roadmap/93-feature-interactions.md)): `Stateful.restore` /
  `StateMigrating.importFrom` run on the already-hot candidate, and a
  promotion swap (50/53) never re-activates candidate or incumbent — the
  re-activation guard above is untouched. The export/import pair sits
  strictly between `onActivate` and `onDeactivate` and touches neither.
  [15-PROMOTE-01] Decided restriction on the swap's catch-up fallback tier (93 I-27,
  reconciled): T2 is sound only for cells idempotent across a source-identity
  change and MUST emit the `ReBaseline` supersession notice; non-idempotent
  cells (e.g. `CounterCell`) MUST hand off via restore/transform (T0/T1).
  The landed fallback is silent and unrestricted — see 50/53 §G-33.
  ⚠ EARS-GAP: the decided restriction and the landed behavior currently
  disagree (landed fallback is silent/unrestricted, not gated on
  idempotence) — unclear which behavior a scenario should assert until
  50/53 §G-33 is reconciled.

### Hot phase

- [15-HOT-01] Ports are live: inlets dispatch to served implementations; outlets emit.
- Composition operators (`map`, etc.) on a hot cell operate on live flows;
  on a cold cell they build graph structure: a serializable `GraphSpec` of
  **proposed** links (G-30), which MAY be structurally pre-validated cold and
  whose *binding* admission executes when the spec replays onto a host at
  spawn (decided in 93 I-26). (This is the intended DSL semantics; the DSL
  itself is future work — 50/51.)

*(G-51 core resolved, W3.6)*: `GraphSpec.applyRemote` gives remote application a
defined failure contract — partial-apply, always leave-and-report. [15-APPLY-01] Rejected
steps (including an `Exact` re-apply of a live ref, hitting the ordinary
live-ref spawn guard) dead-letter on the target host and fold into a returned
`ApplyReport`; the applier never sees a synchronous throw and remaining steps
still apply. Open, research-gated (95 §R4): compensating rollback of the
successful prefix (partial-apply *atomicity*), a dataflow-linkable
`ApplyReport` outlet (today it is a direct return value), and eager cold
structural pre-validation (cardinality/ownership/contract) ahead of replay.

## Normative rules

1. [15-RULE-01] **No logic in constructors.** All behavior establishment belongs in
   `onActivate`. Rationale: a cold cell must be safe to create anywhere,
   including on machines that will never run it (mobility, 30/33; codegen).
   Sole exception: the declared eager mode — §Admission vs activation.
2. [15-RULE-02] **The structural layers are discoverable without running logic.** Whatever
   declaration style is used, a port's structural layers — name, descriptor
   (contract, direction, cardinality, exclusive bit, protocol capabilities),
   and policy set — exist from construction and are enumerable by the host
   without running cell logic (decided in 93 I-26; see §Admission vs
   activation).
3. [15-RULE-03] **Data-traffic dispatch requires HOT; admission does not.** Admission —
   the structural half of linking — is binding from construction (93 I-26).
   Calling a hosting-specific operation cold remains an error with a clear
   message (guarded phase state), not undefined behavior.
4. **Deactivation mirrors activation.** *(G-16 fully resolved, M3.3:
   `Cell.onDeactivate(ctx)` exists; [15-DESPAWN-01] `despawn(ref)` unregisters the cell and
   invokes it on the host's execution context, and re-spawning a live ref is
   rejected. The drain protocol (30/33) guarantees deactivation runs only
   after every accepted invocation has flushed — its phase-2 task sits below
   data priority — and [15-SNAPSHOT-01] captures `Stateful` snapshots at that point. The
   SUSPENDED state in the diagram above is real: `drainHost`/`resumeHost`
   re-activate the same cells in place, and re-activation after deactivation
   is legal.)*

## Admission vs activation (C-7, resolved)

Two initialization styles exist in code — `MapperCell` and the `HostTest`
cells serve inside `onActivate`; `SetCell`, `UnionSetCell`,
`TrafficLightCell` serve inside `init` and run unhosted in tests. Both are
legitimate under the decided rule (93 I-26): a port is a **layered
artifact**. Its *structural* layers (name, descriptor, policies) exist from
construction and are immutable; its *behavioral* layer (the served handler
and protocol sub-handlers) is established at the cell's
**handler-establishment time**. "Link time" decomposes into **admission**
(structural, phase-independent: policy predicates, cardinality budget, the
ownership exclusive bit, contract compatibility, protocol-capability
intersection — all read only the structural layers, so they run in any phase
and are binding from construction; this is the precise meaning of 10/12's
"cardinality enforced at link time") and **activation** (behavioral, gated on
handler-establishment).

**Handler-establishment time is a declared per-cell property** (93 I-26):

- *Hosted cell* (default): handler-establishment = `onActivate` (HOT). The
  parking window is the spawn→activate interval.
- [15-EAGER-01] *Eager cell*: handler-establishment = **construction** — `serve`/`delegate`
  run in `init`, the parking window is zero-length, and the cell may carry
  traffic unhosted (useful for tests and embedded composition). An eager cell
  MUST be pure, allocation-free serving, MUST NOT assume a host context, and
  MUST tolerate `onActivate` never being called. Hosted semantics (queues,
  isolation, mobility) apply only to spawned cells. Eagerness is a declared
  capability (an `Eager` marker the host/KSP can check), not an ad-hoc
  exception; default guidance stays `onActivate`.

[15-PARK-01] A link admitted against a cell whose handler is not yet installed is live
topology with a **parked tail**: inbound invocations MUST park in the
Buffering primitive (30/33), in order, with their contexts, and replay on
activation before any post-activation send lands — to the message path, a
cold-admitted-but-unactivated cell is identical to a suspended one. Generic
protocols arriving before handler install are **dropped**, not parked; every
generic protocol is mandated idempotent and reorder/duplicate-tolerant, so
the drop is safe (93 I-26). A *structural-only* `onLink` (the default;
consults only descriptor/policy metadata) completes at admission in any
phase; a *stateful* `onLink` (consults the cell's hot state) defers on the
always-open management inlet and replays its handshake at activation,
surfacing `Connected`/`Rejected` then — the `LinkResult.Deferred` contract
(10/13). The parked-tail window is decided design, unimplemented.

What exists on a port in each phase:

| Port sub-part | COLD | at ACTIVATION | HOT | SUSPENDED |
|---|---|---|---|---|
| name (registry key) | present | present | present | present |
| descriptor (contract, direction, cardinality, exclusive bit, protocol caps) | present | present | present | present |
| policy set (+ identity slot) | present | present | present | present |
| `onLink` (structural-only) | callable | callable | callable | callable |
| `onLink` (stateful veto) | deferred | becomes callable | callable | callable |
| handler (serve/delegate) | absent¹ | being installed | present | present, not dispatching |
| protocol sub-handlers | absent¹ | being installed | present | bypass-delivered or dropped |
| data-traffic dispatch | parks | replay parked → dispatch | dispatch | parks |

¹ Except eager cells: handler and protocol sub-handlers are present at COLD.

⚠ GAP (G-55): the admission (structural, from construction) vs activation
(behavioral, handler-establishment) split needs its enforcement surface:
stateful-`onLink` classification, deferred-admission result surfacing
including cross-host, Eager verification, dropped-protocol observability, and
remote-spawn rejection channels. Proposal: a per-port structural-only vs
stateful `onLink` declaration with defined defer/replay/result-surfacing of
admission requests to not-yet-hot cells, composing with `LinkResult.Deferred`
and registry park/replay across the wire; a KSP-checked `Eager` capability
(handler in constructor, pure, allocation-free, host-context-free) from which
unhosted-linking permission derives; a count/log policy for protocols dropped
before handler install; and a typed rejection surface for wrong-color or
invalid remote spawns pinned against G-26/G-12 (93 I-26/I-15).

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

⚠ GAP (G-60): a dozen adopted mechanisms hang on undesigned KSP descriptor
bits and lints: protocol descriptors + registry, the
ownership-free/idempotence protocol lint, color, the effect-boundary flag
with opaque-effect detection, `@Key`, magnitude/idempotentMerge bits,
`size()` well-formedness, `Eager`, determinism, pull-safety, and
fallback-tier markers. Proposal: one descriptor-generation sweep extending
the M5.6 exclusive-bit scan — emit the per-type/per-contract bits into
`CellDescriptor`/`ContractDescriptor`/`ProtocolDescriptor` with stable
cross-peer ids, and fail compilation on the associated violations
(Owned/Leased in generic-protocol contracts, non-null-context expectations,
broadcast-keyed exclusives, opaque I/O outside effect boundaries, non-eager
constructor handlers, catch-up fallback on non-idempotent cells)
(93 I-1/I-5/I-6/I-7/I-8/I-15/I-16/I-17/I-26/I-27).
