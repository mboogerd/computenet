# 22 — Consistency: Context, Glitch-Freedom, Topology Versioning

> **Status**: Specified (semantics); ⚠ entirely unimplemented — highest-priority semantic gap
> **Sources**: ADR — Glitch Freedom, ADR — Task Connectivity (§2, MessageContext)
> **Implementation**: none (germ `Invocation` carries no context)

## MessageContext (normative)

Every data-path invocation carries:

```kotlin
data class MessageContext(
    val timestamp: Timestamp,  // logical time / propagation-wave id
    val sourcePort: PortRef    // identity of the emitting port
)
@JvmInline value class Timestamp(val time: Long)
```

Rules:

1. **Origination**: an external event entering the graph (a source cell
   emitting spontaneously) mints a fresh timestamp (wave id).
2. **Transparent flow**: when a cell emits in response to an inlet invocation,
   the outlet invocation carries the *same* timestamp; `sourcePort` is
   rewritten to the emitting port. Framework responsibility — cell logic never
   touches context (capture the current context in the host while executing an
   invocation; stamp emissions from it).
3. **Fan-out** duplicates context; **fan-in** delivers each input's own
   context (that is the point — see buffering below).
4. Delegation is not a context no-op: a delegated path still presents the
   correct (new) source port per hop (10/14).

⚠ GAP (G-4): add `context` to `Invocation`; thread through proxies, bridges,
and hosts. Everything below depends on it.

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

## Topology versioning

Structural changes must be causally consistent with value updates:

- Each link/unlink applied by a host is stamped into the same logical-time
  domain as data waves.
- A glitch-free cell treats "the set of edges feeding wave *t*" as part of
  wave *t*'s input completeness condition.
- ⚠ GAP (G-20): no design yet for *who* assigns wave ids in a decentralized
  graph (a per-source counter? hybrid logical clocks? per-partition?).
  *Proposal*: per-source monotonic counters + source id (making Timestamp a
  pair), with glitch-free cells buffering per (source, counter). This matches
  the "no global time protocol" principle; cross-source joins get
  *convergence*, not simultaneity, unless an explicit coordinator cell is
  inserted. Needs a worked design before implementation.

## Implementation plan (proposal, ordered)

1. Context on `Invocation` + host-local current-context (G-4).
2. `PortRef` unification (germ reuses legacy `civictech.kernel.port.PortRef` in
   tests — one canonical PortRef in the germ model).
3. A `GlitchFree` decorator cell/port wrapper implementing version buffering
   over any inner cell (keeps kernel untouched, per P1).
4. Upstream traversal via the generic management protocol on multiplex ports
   (12) — "describe your frontier" is a management invocation.
5. Topology events stamped by hosts (depends on Link objects, G-12).

## Interaction with other parts

- **Suspension/migration (30/33)**: buffered waves survive because buffering
  happens at ports (Buffering proxy) and context rides inside invocations.
- **Pull/late-join (21)**: catch-up snapshots are stamped with the wave they
  represent, so a glitch-free consumer can align them with live deltas.
- **Cycles (21)**: wave semantics around cycles are unresolved (a cycle
  re-entering a glitch-free cell with the same wave id must be detected —
  candidate: wave id + hop count, or explicit cycle-breaker cells). Open.
