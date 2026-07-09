# 34 — Scheduling and Attention-Driven Execution

> **Status**: Exploratory (principle fixed; mechanism largely undesigned)
> **Sources**: ADR 0 (§5), ADR 1 (§6, §7, §8), ADR — Computelet Kernel (attention propagation as a generic protocol)
> **Implementation**: none beyond ManagedHost's static 3-level priority queue

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
   downstream subscribers' attention (fan-out: max/sum — to be decided; see
   below).
3. **Suspendability is the resource lever**: scheduling decisions are
   expressed as suspend/resume/migrate operations (33), not as thread
   priorities inside cell logic.
4. Cycles need **magnitude-based throttling** to quiesce (21, G-19) — the
   scheduler must not let feedback loops claim attention forever.

## Design sketch (proposal, ⚠ GAP G-6)

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

## Open questions (not yet decidable)

- Aggregation semantics (max vs sum vs decay) and update damping.
- Fairness/starvation floors for low-attention but live subgraphs.
- Interaction with glitch-freedom: suspending one branch of a diamond stalls
  wave completeness at the join — glitch-free cells likely need to advertise
  "incomplete-by-suspension" so joins can decide (wait vs degrade). Unresolved.
- Economic layer (peers advertising willingness to compute for others'
  interest) — deferred to 40/42; keep the local protocol compatible with it.
