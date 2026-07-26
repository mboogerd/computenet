# 33 — Mobility: Suspension and Migration

> **Status**: Implemented (core, M3.2–M3.3): closable intake, re-resolution, drain/resume/migrate with snapshot capture
> **Sources**: ADR — Computelet Mobility (supersedes ADR — Blocking Mailbox), ADR — Computelet Kernel
> **Implementation**: Buffering proxy + serve/delegate switching (TrafficLightCell) = the boundary primitive; `ManagedHost.closeIntake`/`IntakeClosedException` + `cell.host.LocationRegistry` (park/replay) = the stale-reference story; `HostManagementApi.drainHost/resumeHost/migrate` + `cell.Stateful` = the drain protocol

## Definitions

- **Suspension**: isolating cells from the graph — their ports stop consuming;
  traffic is parked or bounced. Supports graphs larger than memory, lazy
  activation, live maintenance.
- **Migration**: moving cells to another host: suspend → move → resume
  elsewhere. In kernel terms both are **link manipulation** (P1):
  suspension = unlink/buffer at all ports; migration = unlink, move, relink.
  Attention-driven suspension additionally treats the **glitch-free region**
  as its atomic unit, with a per-cell non-suspendable veto (34 decision 3).

## The core decision: the host is the unit of mobility

Fine-grained per-cell migration protocols (temporary queues, reference
rewriting, RCU epochs — all explored in the superseded Blocking Mailbox ADR)
were rejected as complex and race-prone. Instead:

1. To move or suspend cells, **suspend the whole host**: stop intake, drain
   in-flight messages, capture cell state.
2. Spawn new host(s) with the desired cell subsets; relink.
3. Need finer granularity? **Make hosts smaller.** Host-per-subchain is cheap
   (virtual threads/coroutines), so granularity is a placement decision, not a
   protocol feature.

Suspension and migration thereby share one mechanism, and the fast path stays
minimal (P2): senders pay only a volatile read + enqueue, ever.

## Stale references: closable queues (normative)

- A suspended/moved host's intake is **closed**; sends to it **fail fast**
  (exception), never block, never silently drop. *(Implemented, M3.2:
  `ManagedHost.closeIntake()` — data and router sends throw
  `IntakeClosedException`; the management inlet stays open so a closed host
  remains administrable. Closure is one volatile read on the send path and is
  deliberately NOT a dead letter — it is the re-resolution signal.)*
- Senders (i.e. link/proxy internals — never cell logic) catch closure,
  **re-resolve** the target's current location from the graph, and retry.
  *(Implemented, M3.2: proxies deliver through an `InvocationSink` — a fixed
  intake surfaces the failure at the send site; a `LocationRegistry` sink
  parks closed/unlocated traffic per-ref in order and replays it into the
  next published location before the fast path sees it. Spawning on a
  registry-carrying host publishes the cell; despawn unpublishes.)*
  Re-resolution MAY return a persistent overflow mailbox (disk queue) when the
  target is suspended indefinitely (13, 24-durability) — not yet built.
- Re-resolution resolves only the **same full ref** — park/replay never
  crosses an instance boundary (decided in
  [93 I-3](../90-roadmap/93-feature-interactions.md)). A sender parked on a
  closed intake replays into *that instance's* next published location, never
  into a sibling replica (40/42). Switching replicas is an **explicit
  relink** — a rare-path topology op, never automatic: always safe for a
  mergeable target (replicas are equivalent; tags make the re-catch-up
  idempotent); for a single-writer target the relink MUST target the leader.
- Parked-write safety for single-writer cells (decided in 93 I-25): a parked
  write replays only onto the **epoch-confirmed leader**. RESTART, migration,
  and suspend-resume preserve the instance, so replay lands on the same
  leader ref by the rule above; failover is an explicit promotion relink
  (50/53). While leadership is unresolved or contested the write MUST stay
  parked, released only once a single epoch-confirmed leader exists.

This is the Link-and-Lease invalidation story (10/14) applied to location:
optimistic send, lazy re-resolution, O(rare-event) cost.

## The drain protocol (per host) — implemented (M3.3)

```
1. close intake            → new sends fail fast; senders park/retry via re-resolution
2. drain queue             → process (or park) everything already accepted
3. quiesce cells           → onDeactivate hook (G-16); capture serializable state
4. detach links            → record topology for re-link
5. [migrate] transfer      → cold cells + state + parked invocations
6. respawn & relink        → activation (10/15); replay parked invocations in order
7. republish location      → senders' re-resolution now finds the new host
```

Ordering invariant: for any link, messages accepted before closure are
delivered before messages sent after re-resolution (matches 13's no-loss
invariant; per-link FIFO preserved end-to-end).

[33-MOVE-01] WHEN a cell is migrated to another host mid-stream, the framework
SHALL preserve its state and in-flight deltas — no loss, no duplication, per-link
FIFO — such that its downstream consumers' folds at quiescence equal those of an
identical cell that never moved (a stay-put twin).

How the implementation realizes the steps (`HostManagementApi`):

- **`drainHost()`** is two-phase: the management task (priority 0) closes the
  intake at once; a completion task at priority 30 — *below* data's 20, so no
  priority inversion — runs after every accepted invocation, deactivates the
  cells and captures `Stateful` snapshots (steps 1–3). On a 🟣 host a task
  suspended mid-message completes before deactivation (actor semantics).
- **`resumeHost()`** re-activates cells, reopens the intake, republishes —
  the registry replays parked traffic in order before new sends land
  (steps 6–7 in place).
- **`migrate(to)`** drains, then spawns each cell on the target; `Stateful`
  state travels as a snapshot forced through a real serialization round-trip
  even in-process (the G-25 seam is exercised, not just claimed). The
  target-side spawn publishes each cell, replaying its parked traffic there.
  The ordering invariant falls out structurally: the accepted batch flushes on
  the old host before the parked batch replays on the new one.
- Step 4 (detach links) needs no code in-process: the cell object moves whole,
  so direct links travel with it and routed links re-resolve via the registry.
  Explicit topology capture becomes real work at the wire boundary (M5).
- Color validation applies at the target: migrating marked cells across colors
  is a caller error; 🟢 pure cells cross freely (32).

Step 3 couples snapshot capture to deactivation because in migration the
instance leaves. In *promotion* (50/53) they decouple (decided in 93 I-27;
matches the landed swap): the snapshot is a pure read taken at a quiescent
inter-invocation boundary, the incumbent is retained hot until retire, and
`onDeactivate` runs only at the final despawn. Promotion's *catch-up
fallback* is narrower than 50/53 §G-33 currently states: it is sound only
for cells idempotent across a source-identity change and MUST emit the
`ReBaseline` supersession notice (93 I-22); cells whose merge is
non-idempotent across sources (`CounterCell`) MUST hand state over by
restore/transform instead. The landed fallback is silent — the candidate
emits under a fresh sourceId with no supersession signal — and diverges from
this decided rule.

## Port-level buffering: the traffic-light primitive (implemented)

`cell.membrane.TrafficLightCell` (promoted from test code in M3.4 — a real
`Cell` with registered ports) demonstrates boundary suspension with zero
fast-path cost:

- **red**: serve a `Buffering` proxy — invocations park in order.
- **green**: replay buffer downstream, then `delegate(dataOutlet)` — the cell
  removes itself from the message path entirely.

This is the reusable primitive for steps 1/6 at port granularity, and the
first membrane behavior in code (11). The generalization M3 promised is a
statement, not new machinery: the same `Buffering` capture — invocations
parked in order with their contexts — is the primitive behind **both**
port-level suspension (this cell) and location-level parking (the
`LocationRegistry`). Suspension is one behavior at two granularities.

A third use of the same primitive is decided, unimplemented (93 I-26): the
**pre-activation parking window**. "Link time" splits into *admission*
(structural — descriptor, cardinality, policy; binding from construction)
and *activation* (behavioral — the served handler). Traffic admitted before
the handler is installed MUST park in this same `Buffering` capture, in
order with contexts, and replay on activation before any post-activation
send lands — to the message path, a cold-admitted-but-unactivated cell is
identical to a suspended one. Eager cells (10/15, C-7) are the
zero-length-window case.

⚠ GAP (G-53): cross-port couplings (Symport/Antiport, 11) can wait forever
when one coupled port never fires for a wave, and the fate of a
half-completed coupled transaction caught in a promotion-swap buffering
window is undefined. Proposal: a timeout/veto/abort policy for stalled
couplings that composes with no-message-loss and the drain protocol, plus
ordering semantics for coupled transactions across a buffered swap window
(93 I-10/I-11).

## Exit criterion (M3) — met

`SubchainMigrationTest`: a running two-cell stateful subchain on its own 🔵
host, fed continuously from a 🔵 source host and observed by a suspending
cell on a 🟣 host, migrates mid-stream to a 🟣 host (pure cells crossing
colors) over 100 seeds of randomized scheduling — zero loss, exactly-once,
per-link FIFO, monotonic wave order, state continuous through the
serialization round-trip. A control run with fixed-host proxies (no
re-resolution) demonstrably loses messages, proving the harness detects loss.

## Gaps to close (G-5)

1. ~~Closable intake for `ManagedHost` + failure surfaced through proxies~~
   — done (M3.2).
2. ~~Re-resolution via a **location registry**~~ — done (M3.2,
   `cell.host.LocationRegistry`; `cell.Handle`, the pre-invocation-model
   attempt, is deleted). ~~Remote addressing unified here~~ — done (M5.4):
   one mechanism, `Location = Local(host) | Remote(bridge sink)`; park/replay
   applies identically to closure, migration, and the wire.
3. ~~`onDeactivate` + state capture~~ — done (M3.3, `cell.Stateful`; ties to
   24 durability snapshots).
4. ~~Host suspend/resume/migrate operations on `HostManagementApi`~~ — done
   (M3.3: `drainHost`/`resumeHost`/`migrate`).
5. ~~Parked-invocation transfer format~~ — done (M3.2: parked traffic IS
   `HostedPortInvocation`s, already the wire form, 14; contexts ride along).

## Constraints from elsewhere

- `Owned`/`Leased` payloads: parked messages retain exclusivity through
  migration; exactly-once consumption must survive replay (20/23).
- Glitch-freedom: parked invocations carry their MessageContext; version
  buffers are cell state, so they capture/restore with the cell (20/22).
- Attention (34): suspension of an uninterested subgraph and re-activation on
  renewed interest is the intended *driver* of this machinery.
