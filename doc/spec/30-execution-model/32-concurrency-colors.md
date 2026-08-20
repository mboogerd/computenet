# 32 — Concurrency Colors: Pure, Blocking, Suspending

> **Status**: Specified (accepted ADR); implemented in the kernel (M3.1, G-3/G-27); the single-boundary color rule and the intake-saturation design below are decided design (93), unimplemented
> **Sources**: ADR 2 (Accepted); 93 resolutions I-9, I-12, I-15, I-19
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
- **Single boundary**: color is checked only at spawn-admission. The one
  predicate is `admits(hostColor, cellColor)` — true iff the cell is pure or
  its marker matches the host color — implemented today as the marker check
  in `ManagedHost.spawn`. Every other placement gate (remote spawn/migrate,
  replica placement, `GraphSpec` replay, promotion candidate placement) MUST
  evaluate the same predicate and reject on mismatch (decided in
  [93](../90-roadmap/93-feature-interactions.md) I-15); there is no other
  color check anywhere. Links are color-blind: a color mismatch across a link
  is a cross-host boundary, not an error — the link simply cannot fuse and is
  realized as a cross-host enqueue.
- Color propagates **upstream** (consumer → producer) only as a placement
  heuristic: a pure cell has no logical color; each *instance* SHOULD be
  placed with — adopting the host color of — what it feeds, so chains fuse
  into direct calls inside one host (P2). This is a per-instance placement
  SHOULD, not a data-flow law (93 I-15): a pure producer feeding both colors
  is placed once, fuses with the same-colored subscriber, and reaches the
  other by cross-host enqueue — or is replicated per color neighbourhood if
  both fusions matter. Placement engines SHOULD co-host same-colored chains.

⚠ GAP (G-61): nothing decides where cells land — the color-aware co-hosting
engine (the SHOULD above), `GraphSpec` placement constraints, multi-host
replay routing, spawn redirection (the G-28 remainder), and membrane
co-location cost policies are all unbuilt. Proposal: a placement engine
consuming `CellDescriptor.color` and optional `GraphSpec` placement
constraints to co-host same-colored chains, replicate pure cells per color
neighbourhood, and route multi-host replays; realize M8.1's deferred spawn
redirection as its enforcement hook; and a placement/cost policy bounding how
much in-flight remote traffic a candidate co-location swap may park when
membrane links span many peers (93 I-15/I-11/I-19).

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

The bounded-intake design is decided (93 I-12), unimplemented. An intake
carries a three-state lifecycle — `OPEN | SATURATED | CLOSED` — collapsed into
the single volatile word the send path already reads for closure (30/33), so
the steady-state fast path stays one volatile read + enqueue (P2). Bounding is
opt-in per intake (`IntakeBound(highWater, lowWater, policy)`, default
unbounded), except that an intake whose contract declares a mergeable delta
(20/24) gets an implicit coalescing bound for free. At high-water the intake
flips SATURATED and emits a retractable `SaturationSignal` upstream on the
metadata plane (retracted below low-water): backpressure propagates as an
interest throttle (P6), never a blocked thread. What a saturated send does is
dispatched by the payload's contract class, under one mechanism:

- **Mergeable delta** — the send folds into a bounded per-source (per-wave)
  pending slot; the queue is bounded by source count, not traffic, and
  effective-only emission plus observed-remove tags make coalescing loss-free
  (20/21, 20/24). Distinct waves stay distinct.
- **Exclusive (`Owned`/`Leased`) or non-mergeable** — the send fails fast
  (`IntakeSaturatedException`, a re-resolution signal mirroring closure) and
  parks in-order at the sender's `LocationRegistry` slot for that link, with a
  low-water replay callback. Ownership has not transferred until replay
  delivers; drop is forbidden.
- **Management band** — exempt: never saturated, always accepted, exactly as
  the management inlet stays open on a closed host (30/33).

Block-the-sender — ADR 2's literal `BlockingQueue.put` / `Channel.send` — is
**rejected as the default**: a blocked single-consumer host thread is a local
propagation lock over that host's whole cell set (P4), and the circular wait
that would deadlock a cycle spanning two bounded intakes. It survives only as
an opt-in membrane policy (`Block(timeout)`) that degrades to Park. The four
ADR 2 bridges thus become real at `enqueueHostedInvocation` as **color-correct
non-blocking saturation handlers**: park/coalesce run on the sender's own
color context, so no coroutine ever starves on a blocking call and no thread
on a suspension — the bridges' actual job, honored without the blocking-put
mechanism.

**Precedence against attention (decided in 93 I-9): admission gates before
banding.** Whether a data enqueue is accepted at all — closed or saturated
intake — is evaluated before attention banding orders it; a HIGH-attention
task is not exempt from a refusing intake (supply safety wins over demand
preference). Suspension (demand-side: attention NONE sustained) and
backpressure (supply-side: bounded intake full) are one mechanism at two
triggers: both close the cell's data intake, and senders park and re-resolve
identically to a moved host (30/33). The management inlet stays open under
both.

**Funnel rule (decided in 93 I-19).** A refusing organelle intake behind a
routing funnel (a partitioned cell's router) MUST surface as a per-ref park
signal — an `IntakeClosedException`-shaped fail-fast, parked per-ref in the
`LocationRegistry` — never as a synchronous wait, confining head-of-line
blocking to the hot partition's own key range while the router serves other
ranges untouched.

⚠ GAP (G-34): intakes are unbounded — no saturation signal, no admission
gate; the ADR 2 color bridges are degenerate and every parking bound
(pre-activation park, router funnel, park-at-sender) is unenforceable.
Proposal: three-state OPEN/SATURATED/CLOSED intake flag on the existing
closure fast-path read; saturated sends dispatch by payload class (mergeable
deltas coalesce into bounded per-source pending slots, exclusive/non-mergeable
park in-order at the sender, management band exempt); `SaturationSignal` rides
the metadata plane upstream with a terminal park-overflow policy (visible
dead-letter default, `Block(timeout)` opt-in only, with Block ×
glitch-free-wave semantics pinned), realized at the `enqueueHostedInvocation`
seam keyed by (senderHostColor, targetHostColor), including the cross-wire
saturation frame vs transport flow control (93 I-12/I-15/I-9/I-19/I-26).

## How the colors are realized

- The color lives on the scheduler: `VirtualThreadScheduler` (🔵, one virtual
  thread draining the queue under `runBlocking`) or `CoroutineScheduler` (🟣,
  one coroutine draining sequentially — a suspended task parks the host, which
  is actor semantics: finer granularity means smaller hosts, 33). The
  deterministic `SimulationController` issues schedulers of either color; a
  simulated task runs undispatched until it completes or genuinely suspends,
  and resumptions re-enter the simulation as ordinary steps.
- **The read plane is colorless** (31 §The read plane). Color governs the
  *invocation* path — what may block, what may suspend, and which context an
  invocation runs on. An off-host synchronous read runs on the observer's own
  thread and returns a published immutable value, so it never blocks a
  🔵 thread on a 🟣 host's suspension or vice versa, and needs no bridge. This
  is also why the read is a snapshot rather than a lock: a monitor held across
  a color crossing would be exactly the blocked single-consumer host thread
  rejected below.
- Delivery is suspend-aware: `Invocation.invokeSuspending` calls a target
  suspend fun with a real continuation (falling back to plain invocation for
  non-suspend targets), and the wave context (G-4) rides a coroutine context
  element across suspension points.
- A proxied suspend call is captured fire-and-forget: the trailing
  `Continuation` is stripped at capture and re-supplied at delivery. The same
  holds across the wire/bridge path (decided in 93 I-15): stripped at egress,
  a fresh continuation re-supplied at ingress per the far host's own color —
  nothing about it serializes (P9), and the data-path `WireFrame` carries no
  color field. Color-as-data lives in exactly two places, both off the data
  path: the cell's marker (serialized with the cell / `GraphSpec`
  `CellFactory` when it crosses a boundary to be placed) and the generated
  `CellDescriptor.color` (unbuilt, 40/41), which lets a receiving host
  validate admission without instantiating. Color is absent from `CellRef`,
  `PortRef`, links, and `MessageContext`.

## Rationale for two runtimes (retained from ADR 2)

Flexibility (choose per workload), performance (skip suspend machinery where
unneeded), safety (no accidental blocking-in-coroutine), reuse (pure logic
runs anywhere). The cost — asymmetric bridges — is isolated and explicitly
modeled (currently degenerate, see above).

The legacy `runtime.blocking|suspending` generation that first carried ADR 2
was deleted with M3.1 (G-1): its suspending half was empty stubs, and every
decision of value had already been distilled into this section.
