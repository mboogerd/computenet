# 22 — Consistency: Context, Glitch-Freedom, Topology Versioning

> **Status**: Specified; context machinery and the opt-in glitch-freedom wrapper implemented (static frontier)
> **Sources**: ADR — Glitch Freedom, ADR — Task Connectivity (§2, MessageContext)
> **Implementation**: `cell.MessageContext`/`Timestamp`/`CurrentContext`, `cell.proxy.Invocation.context`, stamping in `cell.port.Outlet`/`FanOutlet`, `cell.consistency.GlitchFreeCell`

## MessageContext (normative, as implemented)

Every data-path invocation carries:

```kotlin
data class MessageContext(
    val timestamp: Timestamp,  // logical time / propagation-wave id
    val sourcePort: PortRef    // identity of the emitting port
)
data class Timestamp(val sourceId: UUID, val counter: Long)  // per-source waves (G-20 decision)
```

Rules:

1. **Origination**: an external event entering the graph (a source cell
   emitting spontaneously) mints a fresh timestamp (wave id) from the emitting
   outlet's own monotonic counter.
2. **Transparent flow**: when a cell emits in response to an inlet invocation,
   the outlet invocation carries the *same* timestamp; `sourcePort` is
   rewritten to the emitting port. Framework responsibility — cell logic never
   touches context. *(Implemented: outlets stamp at emission; `Invocation`
   carries `context`; `Invocation.invoke` is the single restore point, so
   delivery and buffered replay both run under the invocation's own context;
   management invocations have null context and clear any stale wave.)*
3. **Fan-out** duplicates context; **fan-in** delivers each input's own
   context (that is the point — see buffering below).
4. Delegation is not a context no-op: a delegated path still presents the
   correct (new) source port per hop (10/14) — every outlet hop rewrites
   `sourcePort`.

*(G-4 resolved. Context is captured into invocations at the cross-host proxy
(`HostedCellProxy`) and the `Buffering` recorder; wire bridges are M5.)*

## Local glitch-freedom (opt-in)

A **glitch** is observing an inconsistent intermediate state in fork-join
topologies (diamond: `A → B, A → C, B+C → D`; D must not see B's update for
wave *t* paired with C's for wave *t−1*).

Global propagation locks (SID-UP-style) are **rejected**: they serialize the
whole graph (violates P4). Instead, a cell MAY declare itself glitch-free, and
then:

1. **Dependency tracking** — on init and on topology change, traverse upstream
   to discover which inlets/edges feed it for a given wave.
2. **Version buffering** — buffer per-wave inputs until the input set for that
   wave is complete.
3. **Local evaluation barrier** — evaluate exactly once per wave, with a
   consistent snapshot; no locks beyond the cell.
4. **Topology consistency** — link/unlink events are part of the update stream
   and must be causally ordered with value updates (a wave knows which
   topology version it flows over).

### The glitch-free frontier (composition)

Traversal does not recurse to sources: it stops at the **nearest upstream
glitch-free cells**, whose outputs are already per-wave consistent. Only the
edges between that frontier and the declaring cell need tracking and
buffering. Consequently glitch-freedom **composes**: chains of glitch-free
cells cost each cell only its local frontier.

Non-declaring cells process eagerly with zero coordination cost (P4, P2).

Operators that fan one input delta into several outputs keep per-delta
atomicity: `GroupByCell` (M11.3) emits all groups touched by one input delta
as a single `MapDelta` under the input's wave id, so a downstream glitch-free
wrap composes normally.

*(Implemented — static-frontier phase: `GlitchFreeCell` wraps a fan-in edge
set with per-wave version buffering; the frontier is the inlet's current link
set, recomputed on every completeness check, so link/unlink adapts the
condition (the first instance of "topology is part of the completeness
condition"). Waves flush in per-source counter order; per-link FIFO (30/31)
makes wave completion monotone per source. Validated by a diamond-topology
invariant test: 200 seeds of randomized cross-host scheduling glitch-free,
with a control run proving the harness produces glitches without the wrapper.
Upstream frontier traversal awaits multiplex ports (G-13); unwaved traffic
passes through.)*

## Topology versioning

Structural changes must be causally consistent with value updates:

- Each link/unlink applied by a host is stamped into the same logical-time
  domain as data waves.
- A glitch-free cell treats "the set of edges feeding wave *t*" as part of
  wave *t*'s input completeness condition.
- *(G-20 decided and implemented: wave ids are per-source monotonic counters —
  `Timestamp(sourceId, counter)`, minted by the emitting outlet. No global
  time protocol; glitch-free cells buffer per (source, counter). Cross-source
  joins get **convergence**, not simultaneity, unless an explicit coordinator
  cell is inserted. Cycles remain open — see below.)*

## Implementation plan (ordered; 1–3 done)

1. ~~Context on `Invocation` + current-context (G-4).~~ Done.
2. ~~`PortRef` unification.~~ Done (`cell.port.PortRef`).
3. ~~A `GlitchFree` decorator cell implementing version buffering (kernel
   untouched, per P1).~~ Done (`cell.consistency.GlitchFreeCell`).
4. Upstream traversal via the generic management protocol on multiplex ports
   (12) — "describe your frontier" is a management invocation.
5. Topology events stamped by hosts (depends on Link objects, G-12; the
   static-frontier wrapper already recomputes its edge set from live links).

## Interaction with other parts

- **Suspension/migration (30/33)**: buffered waves survive because buffering
  happens at ports (Buffering proxy) and context rides inside invocations.
- **Pull/late-join (21)**: catch-up snapshots are stamped with the wave they
  represent, so a glitch-free consumer can align them with live deltas.
- **Causal merge tags (24)**: observed-remove set tags reuse the `Timestamp`
  type but are minted cell-locally, *not* taken from the current wave —
  OR-set correctness needs a tag unique per add instance, and a wave id
  repeats across every cell the wave touches. The wave context still rides
  delta invocations unchanged; tags and waves are separate uses of one clock
  shape.
- **Cycles (21)**: wave semantics around cycles are unresolved (a cycle
  re-entering a glitch-free cell with the same wave id must be detected —
  candidate: wave id + hop count, or explicit cycle-breaker cells). Open.
