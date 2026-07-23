# Dynamic (topology-driven) cycle-head admission

**Origin**: agora demo (`demo/agora`). Building and testing the argumentation
graph, I found `AgoraService` reimplements the kernel's now-landed cycle model in
application code, because the landed model doesn't fit how agora decides head-ness.

## The observation

The kernel *has* a cycle model now — `civictech.cell.port.CycleHead<D>` +
`FeedbackInlet<D>` (`feedbackInlet(quiescence, onLap)`), two-tier quiescence
(I-6), a fusion barrier, and link-time admission in `ManagedHost.connect`
(`CycleWithoutHead` is rejected unless the closing edge lands on a `FeedbackInlet`).

agora doesn't use any of it. Instead:

- `EdgeCell` carries a plain `FanInlet` `sourceInlet` and a `quiescence: Double`
  field; when `quiescence > 0` it hand-gates absorption inside the inlet's
  `propagate` (`EdgeCell.kt`).
- `AgoraService.createEdge` computes `head = reaches(from = target, to = source)`
  at insertion time and constructs the edge with `quiescence = if (head) … else 0`.
- All wiring is `streamTo` routed (not `connect`-linked), which **bypasses the
  link-time `CycleWithoutHead` admission check entirely** — so the kernel's own
  safety net never runs for agora.

The README says the eventual migration is "head `EdgeCell`s migrate onto
`feedbackInput` with no domain-logic change." In practice that migration is
blocked by a real mismatch, not just unfinished work.

## The mismatch (why the landed model doesn't fit)

`CycleHead`/`FeedbackInlet` is a **static, per-cell declaration**: a cell type
decides at construction that it owns a feedback terminus. agora's head-ness is a
**dynamic, per-edge, topology-dependent runtime decision**: the *same* `EdgeCell`
type is a head or not depending only on whether the edge it represents happens to
close a cycle given the current graph — something knowable only when the service
inserts it, and which could in principle change as other edges come and go.

To adopt the kernel model as-is, every `EdgeCell` that *might* ever close a cycle
would have to pre-declare feedback machinery it usually doesn't need, and the
service would still have to choose, per link, whether the source feed lands on the
ordinary inlet or the feedback inlet.

## What it is

A framework affordance for **link-time head designation by the party that owns the
topology** — i.e. admit a specific *link* as the cycle-closing feedback edge and
have the host treat its delivery as an absorbing `FeedbackInlet` (fresh timestamp,
two-tier quiescence, fusion barrier), without the *cell* having to statically
declare that inlet.

## Why it's a proper fit for the framework

- The cycle machinery (fresh-epoch re-origination, I-6 two-tier absorb, fusion
  barrier, `Leased`-on-cycle rejection, hop-guard backstop) is subtle, safety-
  critical, and already lives in the kernel. Every topology-owning service that
  admits cycles at runtime (argumentation graphs, belief propagation, constraint
  networks, trust/PageRank-style flows) will otherwise re-derive agora's
  approximation and get the epoch/fusion details subtly wrong.
- It closes the gap the spec itself anticipates (21 §Cycles, 93 I-5/I-6) for the
  *dynamic-topology* case, complementing the existing static-declaration case.
- It removes the current silent hole where routed (`streamTo`) wiring sidesteps
  `connect`-time admission — the head decision becomes explicit to the host again.

## Solution sketch

Add a host-level admission call that promotes a specific link to a feedback edge,
reusing `FeedbackInlet` internally:

```kotlin
// Service owns topology; it tells the host "this link is the cycle head".
host.admitFeedbackLink(
    from = source, outlet = "credenceOutlet",
    to = edge,     inlet  = "sourceInlet",
    quiescence = 1e-3,
)
// The host wraps delivery on `to.inlet` with FeedbackInlet semantics for this
// producer only: fresh Timestamp per lap, two-tier quiescence absorb, barrier.
```

Equivalently, a promotable inlet: `FanInlet.create(feedbackWhen = { producer -> … })`
so an ordinary inlet becomes absorbing for a producer the service marks as
cycle-closing, leaving non-head producers on the normal glitch-free path.

Either way the *cell* keeps one `sourceInlet`; the *host* applies feedback
semantics to the one inbound link the service designated.

## Expected inputs / outputs

- **Input**: a (producer cell/outlet → consumer cell/inlet) link the caller asserts
  is the cycle-closing edge, plus a quiescence threshold.
- **Output**: delivery on that link gets `FeedbackInlet` treatment — sub-threshold
  `Magnitude` deltas absorbed without re-origination, non-`Magnitude` deltas
  idempotent-merged, each admitted lap under a fresh timestamp/hop-0; the outbound
  broadcast from the consumer is never gated.
- Non-designated links to the same inlet keep ordinary fan-in / glitch-free
  completeness semantics.

## Acceptance criteria

- A service can build a mutual-attack 2-cycle and a self-loop purely through the
  host API (no hand-written absorb gate) and it terminates within the head
  threshold — i.e. agora's `EdgeCell.quiescence` field and its manual
  `sourceInlet` gate can be **deleted**, with `CycleQuiescenceTest` and
  `AgoraExitTest` (cyclic seeds) still green.
- Re-origination for a designated head enqueues on the host queue (fusion barrier)
  even co-hosted; stack depth stays O(1) per lap.
- Admitting a feedback link that does *not* actually close a cycle is a no-op for
  correctness (behaves like an ordinary link).
- A cycle-closing link that is *not* admitted still trips the runtime hop-guard
  backstop (`CycleError`), so the safety net that `streamTo` currently skips is
  restored.

## Related

- `agora-scheduler-staged-links.md` — the reason agora uses `streamTo` (and thus
  skips `connect` admission) in the first place.
