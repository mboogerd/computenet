# M3-BE — Flow feed: tap-based edge rates

Model: `claude-opus-5` (effort xhigh) · Track: backend · Depends: M2-EVAL
merged · Parallel with: M3-FE

Files owned: `inspect/src/**`; a kernel edit is permitted ONLY if taps prove
insufficient, and then only the `ManagedHost.enqueueHostedInvocation` subclass
route described below — flag the orchestrator before taking it.

## Context

This is the one vertical whose event stream does not exist yet — you are
building it. Read `AGENTS.md` "Core invariants" carefully;
`10-target-v3.md` §Constraints 1, 3, 6 are binding here. Seams, in order of
preference:

1. **`FanOutlet.tap(port)`** (`kernel/.../cell/port/FanOutlet.kt`) — the
   purpose-built Observe-role attachment: uncounted, always admitted, fires
   before consumers; wave context available via `CurrentContext.get()`
   (`kernel/.../cell/MessageContext.kt`) at invocation time. `untap(portRef)`
   removes it.
2. **Subclass `ManagedHost` / override `enqueueHostedInvocation`** — a single
   choke-point for all accepted invocations on a host. Broader but requires
   the pilot demo to construct the subclassed host; only if per-outlet taps
   can't attribute traffic to edges.

Fused co-hosted chains compile to direct calls — there is no message to
observe on a fused edge. That is by design (P2); the feed must report such
edges as fused, not silently zero.

## Implement

1. **`FlowCollector`**: on inspector startup (and on `topology.link` adds),
   attach taps to the outlets of inspected cells; on unlink/despawn, untap.
   Each tap handler does the absolute minimum on the graph thread: increment
   a per-link atomic counter and stampede-free record of the last
   `MessageContext` (source id, counter, hop) — no allocation-heavy work, no
   locks shared with the HTTP side beyond atomics/volatile publication.
2. **1 Hz aggregation**: a single scheduler thread snapshots and resets
   counters, computes per-edge rates over the window, and broadcasts one
   `flow.rates` SSE batch per contract (edges with zero traffic omitted;
   `lastWave`/`hop` from the recorded context). Attribution: tap fires
   per-outlet delivery — attribute to the specific link where the tap/edge
   mapping allows; where an outlet fans out to n consumers and per-link
   attribution isn't observable, divide or duplicate per your analysis and
   document the choice in KDoc (the FE only promises per-edge *activity*).
3. **Fused edges**: determine fusion where detectable (co-hosted direct-call
   links); emit `fused: true` on those edges in topology (upgrading M0's
   `null`) and never emit rates for them.
4. **Never block, never leak**: taps must survive slow/absent SSE clients
   (the 1 Hz batch is built regardless and dropped per-client by the existing
   bounded-queue machinery); collector shutdown untaps everything (test).

## Exclusions

Per-message event streaming (only 1 Hz aggregates), journal-tee approach,
wave tracing/step-debugging, UI. No changes to `FanOutlet` semantics.

## Tests / acceptance

- Attribution test: small graph, drive N messages through a known edge,
  assert the rate batch reflects ~N/window on that edge and only that edge.
- Wave stamp test: last wave/hop in the batch matches the driven context.
- Fused edge: build a fused chain (see how kernel tests exercise fusion;
  search for fuse/direct-call tests), assert `fused: true` and no rates.
- Untap-on-unlink and shutdown-untaps-all tests.
- Overhead guard: a micro-benchmark-style test asserting the tap handler does
  no allocation on the hot path is overkill — instead, code-review yourself
  and state in the report exactly what the per-message cost is (reads,
  writes, allocations).
- `./gradlew :inspect:test` green (plus `:kernel:test` if the fallback route
  was taken).
