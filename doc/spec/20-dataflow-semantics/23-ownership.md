# 23 — Payload Ownership Contracts and the SPSC Rule

> **Status**: Implemented through phase 2 (M5.6); pooling (phase 3) deliberately unbuilt; consumer/observer taps, cycle-edge/backpressure/frontier boundary rules, and recovery obligations design decided in 93 (I-6/I-12/I-18/I-20/I-22), unimplemented
> **Sources**: ADR — SPSC link requirement; 93 feature-interaction resolutions I-6, I-12, I-18, I-20, I-22
> **Implementation**: `cell.Ownership` (Borrowed/Owned/Leased/Frozen); exclusive bit in generated `MethodDescriptor`s; `FanOutlet` link/subscribe enforcement; `Broadcast` refusal; `BridgeEgressCell` boundary rules

## Motivation

On hot paths (HFT-inspired, P2), copying and defensive immutability are too
expensive; in-place mutation of pooled or transferred buffers is required.
That is only safe if **sharing is impossible by construction** (P5).

## The four contracts

Encoded at the type level on payloads:

| Contract | Semantics | Fan-out safe? |
|---|---|---|
| `Borrowed<T>` | Read-only, temporary snapshot view; valid only during the invocation | ✅ |
| `Owned<T>` | Ownership transfer; consumed exactly once; receiver may mutate/retain | ❌ |
| `Leased<T>` | Exclusive mutable access from a shared pool; must be released | ❌ |
| `Frozen<T>` | Immutable form of a previously owned value | ✅ |

Typical flows: `Owned` → mutate in place along a pipeline; `Owned` →
`freeze()` → `Frozen` fan-out to many readers; `Leased` from a buffer pool →
fill → hand off → release.

## The SPSC link rule (normative)

A link whose contract carries `Owned` or `Leased` arguments MUST have exactly
one downstream consumer (unary edge). Enforcement points:

1. **Link time** (primary): the handshake (10/13) inspects the contract's
   payload types; a second link to an `Owned`/`Leased`-carrying outlet is
   `Rejected`. `OneToOnePort` is the natural carrier.
2. **Graph validation** (secondary): whole-graph check before/at activation.
3. The rule constrains the graph-building API and scheduler only — core
   port/link interfaces are unchanged.

Corollaries:

- `Owned`/`Leased` payloads MUST NOT cross into a `Broadcast` proxy; the
  Buffering proxy MAY hold them (buffering preserves exclusivity).
- Suspension/migration must preserve exactly-once: a parked `Owned` message is
  still owned by the link (30/33 drain rules).
- Cross-machine transfer of `Owned` degenerates to move-by-serialize
  (serialize + drop sender's reference); `Leased` MUST NOT cross machine
  boundaries (a lease on a remote pool is meaningless) — freeze or copy first.
- **Saturation refusal keeps ownership at the sender** (decided in
  [93 I-12](../90-roadmap/93-feature-interactions.md)): when a bounded
  intake refuses an exclusive enqueue, the owner-of-record remains the
  sender's link — ownership has not transferred. The sender parks the
  invocation and replays on drain; `Owned.take()` on the receiver fires
  exactly once, at replay delivery. Drop is forbidden for exclusive
  payloads — park, or the visible dead-letter boundary below, are the only
  admissible dispositions.
- **Glitch-free frontier edges refuse `Leased` at link time** (decided in
  93 I-18): an unbounded-WAIT version buffer would hold the lease past its
  mandatory release, starving the pool — freeze or copy first, exactly as
  at machine boundaries. Buffered `Owned` is safe (buffering preserves
  exclusivity); a stalled wave's buffered `Owned` is released when the
  stall policy resolves the wave — discarding it violates nothing, since
  the sender already moved-from it.
- **Cycle edges** (decided in 93 I-6): `Leased` is forbidden on any cycle
  edge — link-time `Rejected`, since a lease circulating a loop has
  ambiguous release responsibility and a throttled (dropped) lap would
  silently leak it; `freeze()` first. `Owned` is permitted: absorption at
  the cycle head is `take()`-into-loop-state, honoring consume-once;
  re-origination derives a fresh `Owned`/`Frozen` delta for the next lap.
  An `Owned` loop edge is unary under the SPSC rule, so a head with
  external subscribers needs a separate non-exclusive outlet (`Frozen` or
  plain).

## Taps: consumers vs observers (decided in 93 I-20, unbuilt)

The SPSC count is a count of **consumers**, not of attachments. Every
downstream attachment is either a **Consume** link — receives the payload
in its declared contract form and bears the consume-once/release
obligation — or an **Observe** link (a **tap**) — receives a `Borrowed<T>`
projection of the outlet contract, valid only for the emitting invocation,
never retained, mutated, or released.

- The "exactly one downstream consumer" rule MUST count Consume links
  only; taps are accepted regardless of the exclusive bit. Enforcement
  stays at the same funnel: `FanOutlet` splits attachments into a
  consumers list (checked) and a taps list (always admitted).
- `Owned<T>.borrow()` and `Leased<T>.borrow()` yield the read-only view.
  On emit, taps fire first, in emission order (in-host, synchronous), then
  the sole consumer takes the payload — no tap view can alias the buffer
  once the consumer mutates or moves it.
- **Cross-boundary taps freeze**: `Borrowed` MUST NOT cross a machine
  boundary (its validity window does not survive the wire, and the buffer
  may be reused). `BridgeEgressCell` MUST freeze an immutable copy and
  send `Frozen<T>` on the observer stream — the existing "freeze or copy
  first" boundary rule, applied to observation.
- **Discharging sinks**: a NoOp-served inlet whose contract carries
  `Owned`/`Leased` MUST discharge the obligation rather than silently
  drop: `Owned` → `take()`-and-drop, `Leased` → `release()`. Generated
  from the same exclusive bit.

⚠ CONFLICT (C-11): `Shadow.spawn`'s plain NoOp proxy drops `Owned`/`Leased`
payloads without `take()`/`release()`, contradicting the decided
discharging-sink rule for exclusive payloads (93 I-20).

⚠ GAP (G-47): The uncounted read-only Tap (a Borrowed projection fired
before the sole consumer) that lets invariants/shadows/judges observe
exclusive flows is adopted but unbuilt: projection derivation, catch-up,
and copy-fork are open. Proposal: KSP derives Borrowed-projected observer
descriptors from exclusive-carrying contracts (nested/generic payloads,
link-time validation that a tap's contract equals the outlet projection);
taps on exclusive flows are attach-forward-only (no retained history to
replay); a Cloneable/copy capability for `Shadow.forkExclusive` with a
stated failure mode for uncloneable payloads and unspecified Leased forks
(93 I-20).

## Negotiation (PN-12, implemented)

A link is formed by a **handshake** that runs the target-side policies, the peer
allowlist, and a pure `reconcile(offered, required)` over the two ports' declared
**nature vectors** (CP-F3). `reconcile` only ever *composes* (Direct) or *refuses*
loudly (a typed `NatureMismatch` naming the axis) — there is no adapter synthesis.

**Link-flow axes vs structural natures (normative).** Only a small set of
**link-flow axes** may refuse a link — natures that describe *how a payload flows
across this edge*: `OWNERSHIP`, `MERGE_IDEMPOTENCE`, `MONOTONICITY`, and (PN-12)
`WAVE_PARTICIPATION` and `INSTANCE_SCOPING`. `COLOR` is deliberately excluded (a
placement property validated at spawn; a link legitimately crosses colors). The
two PN-12 axes each turn a previously *silent* mismatch into a refusal:

- `WAVE_PARTICIPATION` (`UNWAVED` < `WAVED`): an ALIGN-tier (`WaveFrontier`) inlet
  requires `WAVED`. An unwaved producer streamed onto it is dropped silently today
  (the F1 frontier drop); as a link-flow axis it is refused at formation instead.
- `INSTANCE_SCOPING` (`SINGLETON` < `INTEREST_SCOPED`): a partial-interest inlet
  requires an interest-scopable (`Scoped`) delta. A non-`Scoped` delta rides whole
  and over-delivers silently today; as a link-flow axis it is refused instead.

KSP stamps these as **offers** on producer outlets only (a glitch-free cell's
outlets offer `WAVED`; a `Scoped`-delta outlet offers `INTEREST_SCOPED`) —
offering a stronger level never refuses, so no existing link acquires a new
requirement, and today's behavior is preserved verbatim on every link.

**The CellManifest is not link-flow (normative).** A cell's *structural* natures
— `GLITCH_FREE`, `DURABLE`, `REPLICATED`, `PARTITIONED`, `PULL_SERVING`, `GATED`
(`Manifest`, KSP-derived from marker interfaces onto `CellDescriptor.manifest`) —
describe *what a cell is*, not how an edge flows. They MUST NOT be reconciled at a
link: a volatile consumer of a durable producer is normal (the exchange demo is
exactly that), so making them refuse would repeat the COLOR mistake. They are
consumed by spawn checks (a `DURABLE` cell placed on a null journal selector is
surfaced as a volatile-durability gap, not silently lost), diagnostics, and drift
assertions (`ManifestDriftTest`: declared == installed) — never by `reconcile`.

**Negotiated attachment default (PN-12, the one behavior change).** `tap` and
`streamTo` historically bypassed the handshake entirely. They now **negotiate by
default** (`negotiated = true`) when — and only when — the target is a local
`Linked` port: the attachment runs the same handshake as a Consume link, with
`LinkRole.Observe`, so it announces an `EdgeOpen` and is refused on a nature
mismatch, yet is filtered out of the wave-completeness frontier (Observe edges
never gate a wave). A non-`Linked` target (an ad-hoc `Use.fixed` endpoint, a
routed cross-process proxy whose negotiation is the bridge's job) cannot
negotiate and falls through to the historic bypass unchanged. This change is gated
on the composition demo suite (`ExchangeCompositionExitTest`, `ExchangeScaffoldTest`).

## Recovery and dead letters (decided in 93 I-22, unimplemented)

- **RESTART never re-consumes an `Owned` payload** (93 I-22 R6): RESTART
  restores *state*; it never re-drives the invocations that produced it,
  so a previously-consumed `Owned`/`Leased` is never re-delivered — SPSC
  exactly-once holds by construction. Any resulting loss is reconciled by
  the re-baseline (93 I-22), not by re-consumption.
- **SUSPEND's park is the one Buffering primitive** (93 I-22 R7): the
  per-cell park (30/31) is a granularity of the Buffering proxy blessed
  above — each parked `Owned` is held once and replayed once to the
  single consumer, never fanned.
- **Dead-letter capture applies the boundary rules** (93 I-22 R8): the
  dead-letter outlet is a fan-out, so a live `Owned`/`Leased` reference
  MUST NOT enter it. At capture, `Owned` degenerates to move-by-serialize
  into the dead letter (the wrapper is consumed as the frame is encoded,
  exactly as at `BridgeEgressCell`), and `Leased` is released and
  represented by a redacted marker; the outlet then fans a
  `Frozen`/serialized value.

⚠ GAP (G-46): Exclusive (Owned/Leased) payloads have no defined story off
the happy path: a payload parked-but-unsnapshotted at crash is lost with no
stated at-most-once contract, and the DeadLetter envelope for
freezing/serializing/redacting them is unspecified. Proposal: State the
sender-durability contract that makes crash loss at-most-once acceptable
(or require the producing host to be durable), and pin the DeadLetter
envelope: Owned → move-by-serialize at capture, Leased → released, with a
redaction rule for non-serializable payloads — mergeable parked traffic is
already covered end-to-end by the M10 journal + anti-entropy pair
(93 I-7/I-22/I-12).

The state-level analogue of payload exclusivity — a single-writer
replicated cell (40/42) — carries its own open liveness half:

⚠ GAP (G-44): Single-writer replication (leader→follower log-shipping)
defers its liveness half: no automatic leader election, no failure
detector, no follower-unpark rule under SAFETY_PARK, and split-brain
reconciliation beyond last-epoch-wins is undesigned. Proposal: Opt-in
epoch-claim election folded from the eventually-consistent membership index
with a stated convergence/liveness bound and a generative leader-churn
harness; a failure-detection window that does not become a second heartbeat
protocol; a witness-set-superset unpark rule for SAFETY_PARK; an
application-level reconciliation hook for fenced divergent writes; an
optional ack-from-k durability tier; and per-shard leader routing when
partitions replicate (93 I-25/I-2/I-3/I-8).

## Implementation (G-21, M5.6)

Phase 1 — done: `cell.Ownership` marker wrappers. `Owned.take()` is
consume-once (use-after-move throws); `Owned.freeze()` is the fan-out path.
Phase 2 — done: the KSP contract processor scans parameter types (recursively
through type arguments) for `Owned`/`Leased` and emits the exclusive bit into
the generated `MethodDescriptor` — enforcement reads metadata, never
reflection. Enforcement points, one funnel each:
- **`FanOutlet.subscribe`** — where every attach path converges (handshake
  installs, `Use.fixed` links, cross-host and bridge links): a second
  subscriber on an exclusive contract fails; the handshake wrapper returns
  `Rejected` rather than throwing (source-side, mirroring `Outlet`'s
  cardinality style). "Rejectable everywhere" holds by construction.
- **`Broadcast` proxy** — refuses `Owned`/`Leased` payloads when fanning to
  more than one target.
- **`BridgeEgressCell`** — `Leased` is refused at the machine boundary;
  `Owned` crosses as move-by-serialize (the sender's wrapper is consumed as
  the frame is encoded; `Owned`/`Frozen`/`Borrowed` are wire-registered
  payloads). Boundary rules are send-time checks — the boundary is where the
  knowledge lives.
Phase 3 — pooling (`Leased`) with host-integrated release-on-drain stays
unbuilt until a performance-critical use case exists — pools before profiling
would violate "complexity only where paid for".

The exclusive-bit scan is the template for a family of decided-but-unbuilt
descriptor bits:

⚠ GAP (G-60): A dozen adopted mechanisms hang on undesigned KSP descriptor
bits and lints: protocol descriptors + registry, the
ownership-free/idempotence protocol lint, color, the effect-boundary flag
with opaque-effect detection, `@Key`, magnitude/idempotentMerge bits,
`size()` well-formedness, Eager, determinism, pull-safety, and
fallback-tier markers. Proposal: One descriptor-generation sweep extending
the M5.6 exclusive-bit scan: emit the per-type/per-contract bits into
`CellDescriptor`/`ContractDescriptor`/`ProtocolDescriptor` with stable
cross-peer ids, and fail compilation on the associated violations
(Owned/Leased in generic-protocol contracts, non-null-context expectations,
broadcast-keyed exclusives, opaque I/O outside effect boundaries, non-eager
constructor handlers, catch-up fallback on non-idempotent cells)
(93 I-1/I-5/I-6/I-7/I-8/I-15/I-16/I-17/I-26/I-27).
