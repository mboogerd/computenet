# A DSL link mode that stays staged on the host queue

**Origin**: agora demo. The service's central design comment explains why it
abandons the ergonomic graph DSL and hand-routes everything through `streamTo` +
registry proxies — and the reason is a framework limitation, not a domain need.

## The observation

From `AgoraService`'s class doc:

> All wiring is **routed** through the host queue (`streamTo` + registry proxies,
> the demo idiom) rather than DSL-linked: co-hosted DSL links fuse into
> synchronous calls that bypass the scheduler, and magnitude-based prioritization
> needs every hop staged.

So agora *wants* attention/magnitude scheduling (it constructs its host with
`AttentionPolicy(magnitudeBands = MAGNITUDE_BANDS)`, and every delta implements
`Magnitude` so dramatic credence swings pre-empt micro-adjustments —
`MagnitudePriorityTest` proves the ordering). But to keep that working it cannot
use the DSL `link`/`connect`, because co-hosted links **fuse into synchronous
calls** and skip the queue where the magnitude bands are applied. The cost:

- Every hop is wired by hand with `streamTo` + a per-port proxy (see
  `agora-routed-inlet-handle-without-proxy-interface.md`).
- Routed wiring also **bypasses `connect`-time cycle admission** (see
  `agora-dynamic-cycle-head-admission.md`), so a safety check is silently lost.

The kernel already knows how to force a hop onto the queue even co-hosted — it does
exactly that for `FeedbackInlet` re-origination (the "fusion barrier",
`ManagedHost` rebinds `port.barrier = { ctx.enqueueBarrier(it) }`). The capability
exists; it just isn't exposed as a general link option.

## What it is

A DSL link/connect option that guarantees the hop is **enqueued on the host
scheduler** (subject to attention/magnitude bands) instead of fusing into a
synchronous call — the same barrier the framework already applies to feedback
inlets, available to any link.

## Why it's a proper fit for the framework

- Attention/magnitude scheduling (spec 34) is a framework feature. Today it only
  actually takes effect if you avoid the framework's own linking DSL — that's an
  internal contradiction worth resolving in the framework.
- The mechanism is already implemented and trusted for the feedback path; this
  generalizes it rather than inventing new runtime behavior.
- It lets demos use the readable DSL *and* keep scheduling, removing the main
  reason they drop to manual routing — shrinking every demo's wiring code and
  re-enabling `connect`-time cycle admission for them.

## Solution sketch

An opt-in on the link builder that pins staging:

```kotlin
graph {
    link(source.credenceOutlet, edge.sourceInlet, staged = true)  // never fuse
    // or a policy: link(...).onQueue()
}
```

Under the hood, a `staged` link installs the same barrier used for
`FeedbackInlet` (`enqueueBarrier`) so delivery re-enters the host queue and passes
through `AttentionPolicy` band assignment, even when producer and consumer are
co-hosted. Fusion stays the default for links that don't ask for staging (no perf
regression for the common case).

## Expected inputs / outputs

- **Input**: an ordinary DSL link declaration plus a `staged`/`onQueue` marker.
- **Output**: every delivery over that link is enqueued and banded by the host's
  `AttentionPolicy`; magnitude ordering holds across the hop exactly as it does for
  today's `streamTo` routing.
- Un-marked links keep fusing synchronously (unchanged behavior/perf).

## Acceptance criteria

- A co-hosted graph wired with `staged` DSL links reproduces
  `MagnitudePriorityTest`'s ordering (dramatic deltas dispatched before micro
  ones) — i.e. the ordering no longer depends on hand-routing.
- agora can express its wiring with the DSL + `staged` and delete its manual
  `streamTo` plumbing, with `MagnitudePriorityTest`, `AgoraExitTest`,
  `CycleQuiescenceTest` green.
- A `staged` link that closes a locally-visible cycle is still subject to
  `connect`-time cycle admission (`CycleWithoutHead`), restoring the check that
  raw `streamTo` skips.
- Default (un-staged) links show no measurable dispatch overhead vs today.

## Related

- `agora-routed-inlet-handle-without-proxy-interface.md`,
  `agora-dynamic-cycle-head-admission.md` — the two costs this friction imposes.
