# 32 — Concurrency Colors: Pure, Blocking, Suspending

> **Status**: Specified (accepted ADR); implemented in the kernel (M3.1, G-3/G-27)
> **Sources**: ADR 2 (Accepted)
> **Implementation**: `cell.host.HostColor` on `HostScheduler` (`VirtualThreadScheduler` = 🔵, `CoroutineScheduler` = 🟣, `SimulationController.scheduler(color)` for both), `BlockingCell`/`SuspendingCell` markers, spawn validation in `ManagedHost`, `Invocation.invokeSuspending`

## The three colors

Logic is classified by execution style:

- 🟢 **Pure** — local, non-blocking, non-suspending; computes and returns.
  May be lifted into either other color.
- 🔵 **Blocking** — may block (file IO, JDBC, locks). MUST run on a virtual
  thread.
- 🟣 **Suspending** — may suspend (Kotlin coroutines). MUST run in a
  coroutine context.

Discipline per color: blocking logic calls only blocking/sync APIs; suspending
logic calls only suspend functions; pure logic does neither.

## Compatibility and coercion (normative)

| Host color | May host |
|---|---|
| Virtual-thread host | 🔵, 🟢 (coerced to 🔵) |
| Coroutine host | 🟣, 🟢 (coerced to 🟣) |

- 🔵 and 🟣 never coexist in one host.
- 🟢 cells are written once with no adapter classes — pure logic served on a
  port simply runs on whatever host context invokes it; the "coercion" is
  placement (no logic duplication, P5). Explicit markers (`BlockingCell`,
  `SuspendingCell`; unmarked ⇒ pure) exist so spawn can validate host
  compatibility; a wrong-color spawn is rejected.
- Color propagates **upstream** (consumer → producer): a pure cell adopts the
  color of what it feeds, so chains fuse into direct calls inside one host
  (P2). Placement engines SHOULD co-host same-colored chains.

## Bridges (degenerate while intakes are unbounded)

ADR 2 specified four **unidirectional, type-safe bridges** keyed by
(senderColor, targetColor) — `Channel.send` vs `BlockingQueue.put`, wrapped in
`suspendCancellableCoroutine` / `runBlocking` at color crossings — so that no
coroutine is ever starved by a blocking call and no thread by suspension.

In the kernel as implemented this table **degenerates**: every host intake is
an unbounded `(priority, sequence)` queue, so a cross-host send — any color to
any color — is one non-blocking offer (`enqueueHostedInvocation` →
`HostScheduler.submit`). No bridge classes exist because there is nothing for
them to do; the asymmetry only appears when an intake can exert backpressure.
`enqueueHostedInvocation` remains the seam where bridges would live.

⚠ GAP (deferred, no milestone): bounded intakes / backpressure. When intakes
can refuse or delay, the four ADR 2 bridge mechanisms become real code at the
`submit` boundary. Until then the safety properties hold vacuously.

## How the colors are realized

- The color lives on the scheduler: `VirtualThreadScheduler` (🔵, one virtual
  thread draining the queue under `runBlocking`) or `CoroutineScheduler` (🟣,
  one coroutine draining sequentially — a suspended task parks the host, which
  is actor semantics: finer granularity means smaller hosts, 33). The
  deterministic `SimulationController` issues schedulers of either color; a
  simulated task runs undispatched until it completes or genuinely suspends,
  and resumptions re-enter the simulation as ordinary steps.
- Delivery is suspend-aware: `Invocation.invokeSuspending` calls a target
  suspend fun with a real continuation (falling back to plain invocation for
  non-suspend targets), and the wave context (G-4) rides a coroutine context
  element across suspension points.
- A proxied suspend call is captured fire-and-forget: the trailing
  `Continuation` is stripped at capture and re-supplied at delivery.

## Rationale for two runtimes (retained from ADR 2)

Flexibility (choose per workload), performance (skip suspend machinery where
unneeded), safety (no accidental blocking-in-coroutine), reuse (pure logic
runs anywhere). The cost — asymmetric bridges — is isolated and explicitly
modeled (currently degenerate, see above).

The legacy `runtime.blocking|suspending` generation that first carried ADR 2
was deleted with M3.1 (G-1): its suspending half was empty stubs, and every
decision of value had already been distilled into this section.
