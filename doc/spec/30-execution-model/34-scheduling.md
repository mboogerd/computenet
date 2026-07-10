# 34 — Scheduling and Attention-Driven Execution

> **Status**: Implemented (M6): decisions below are code
> **Sources**: ADR 0 (§5), ADR 1 (§6, §7, §8), ADR — Computelet Kernel (attention propagation as a generic protocol)
> **Implementation**: `cell.attention.AttentionSupport`/`AttentionBand` over generic-protocol
> sub-channels (`cell.port.Protocols`, G-13 minimal); host mapping in `ManagedHost` +
> `AttentionPolicy` (band dispatch, stride floor, NONE-window park/replay);
> `GlitchFreeCell.WaveMode` WAIT/DEGRADE. Verified: `AttentionGenerativeTest`
> (100 seeds + starvation control), `GlitchFreeSuspensionTest`.
> Remaining: attention does not cross the wire (bridged links carry no
> protocol endpoints — revisit with replication, 42); notices are single-hop
> (a join sees only direct upstream parks, not transitive ones).

## Principle

Computation follows **interest** (P6). Subscribing to a result implies partial
responsibility for its inputs; unattended subgraphs quiesce; attended ones get
resources proportional to attention. The suspension machinery (33) is the
enforcement arm: no interest → suspend; renewed interest → resume.

## What is fixed by the ADRs

1. **Attention is a generic protocol** (12-multiplex): it propagates
   **upstream** (consumer → producer) along data links without cell-specific
   logic. Precise directionality is why ports/links know their data direction.
2. **Interest is compositional**: a cell's attention level derives from its
   downstream subscribers' attention (fan-out: programmable, max default; see
   below).
3. **Suspendability is the resource lever**: scheduling decisions are
   expressed as suspend/resume/migrate operations (33), not as thread
   priorities inside cell logic.
4. Cycles need **magnitude-based throttling** to quiesce (21, G-19) — the
   scheduler must not let feedback loops claim attention forever.

## Design sketch (implemented, M6 — G-6 resolved)

Minimal attention model compatible with the above:

- `Attention(level: Float, deadlineHint?)` as a well-known upstream protocol
  message; emitted by sinks (UIs, subscriptions, invariant monitors).
- Each cell/port aggregates downstream attention (default: max; sum for
  load-signaling variants) and re-emits upstream on change — itself
  incremental dataflow (attention updates are deltas; magnitude throttling
  applies to attention itself to prevent oscillation storms).
- Hosts map attention to concrete resources:
  - 0 for longer than a policy window → suspend (33);
  - >0 on a suspended subgraph → resume;
  - relative levels → queue priority bands (extending the existing
    management > router > data bands with per-attention data bands);
  - persistent high attention + remote hotspot → migration candidate (40/42).

## Decisions (M6 planning; formerly "open questions")

1. **Aggregation is programmable per cell, damped by quantization.**
   Aggregation is a per-cell strategy (`AttentionAggregator` on
   `AttentionSupport`): a pure fold of the cell's own level and its downstream
   links' levels into one, declaratively defined, assembled (strategies
   compose — decay wraps a base), and configured at spawn or live. Shipped
   strategies: **max** (the default — attention is a priority signal, not a
   load meter; sum double-counts shared subgraphs on diamonds), **sum** (load
   semantics, for consumers that want the double-count), and **decay** (the
   base's result halves per configured half-life without a fresh signal).
   Time-aware strategies read **scheduling-step ticks bound by the host** —
   never wall time — so the deterministic simulation stays deterministic (P1);
   re-evaluation between signals is an explicit `refresh` owned by the
   host/harness cadence, not the dispatch hot path. Whatever the strategy, the
   *effective* value is quantized into discrete **bands** —
   `NONE < LOW < NORMAL < HIGH` — and a cell re-emits upstream **only when its
   aggregated band changes**. Quantization is the damping: intra-band jitter
   is structurally incapable of propagating, which is the magnitude-throttling
   rule (21, G-19) applied to attention itself — and it is what keeps
   non-monotone strategies (sum, decay) from re-exciting cycles. A sink that
   loses interest emits level 0 or unlinks (unlink already shrinks the link
   set).
2. **Fairness floor = park-not-drop + a deterministic service stride.**
   Two floors, one per resource lever:
   - *Suspension floor*: attention-driven suspension uses the same
     park-and-replay buffering as 33 — a quiesced subgraph loses latency,
     never messages. Suspension requires band NONE sustained for a policy
     window (measured in host scheduling steps, not wall time, so the
     simulated host stays deterministic).
   - *Queue floor*: within the data region of the host queue, bands map to
     priorities, but after `stride` consecutive dequeues of
     higher-band tasks the host MUST service the oldest queued lower-band
     task (by sequence number). `stride` is host policy (default 16;
     `∞` disables). This bounds starvation at `stride × task-time` without
     timers or randomness.
3. **Glitch-freedom × suspension: correctness by default (WAIT), degradation
   opt-in.** Because suspension parks rather than drops, a glitch-free join
   above a suspended branch simply sees an incomplete wave and holds the
   group — correct, latency-unbounded. That is the default (**WAIT**). A
   glitch-free cell MAY opt into **DEGRADE**: hosts publish
   suspended/resumed notices for cells they park, the notice travels
   *downstream* (with data, against attention), and a degrading join removes
   the suspended link from its wave frontier — reusing the frontier-shrink
   semantics unlink already has (22) — and restores it on resume, treating
   post-resume replayed waves as late-join catch-up (21). No new kernel
   mechanism: a notice is an ordinary generic-protocol message.
4. **Economic layer** (peers advertising willingness to compute for others'
   interest) — still deferred to 40/42; the band protocol above is
   forward-compatible (levels are floats, bands are a local mapping).
