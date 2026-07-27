# ADR — Adapter Synthesis: an `Adapt` arm for nature reconciliation (FU-4, phase 1)

**Status**: PROPOSED — awaiting review. Phase-1 deliverable only: design + spike.
No production code changes. Analysis pinned to `main` @ `4cfbb83`.

**Spike**: `kernel/src/test/kotlin/civictech/cell/port/AdaptWaveParticipationSpikeTest.kt`
(green; throwaway — delete or promote on the go/no-go decision).

## Question

When an outlet's nature is below what an inlet requires on some axis, should
`NatureNegotiation.reconcile` be able to name an adapter that lifts the producer
— `Direct | Adapt(candidate) | Refuse` — instead of refusing outright?

## (a) The adaptable-axis table

An axis is adaptable iff a **semantics-preserving lift** exists: a wrapper that
raises the offered level without changing what the values *mean*.

| Axis | Lift required | Adaptable? | Rationale |
|---|---|---|---|
| `WAVE_PARTICIPATION` (UNWAVED→WAVED) | re-origination | **Yes** | The lift is exactly `FanOutlet.originate`: relay each emission as a fresh wave from the adapter's own source lane. Values unchanged, per-link FIFO preserved; the adapter adds per-emission wave structure — precisely what a hand-written waver would do. It cannot (and need not) invent cross-source alignment. Proven end-to-end by the spike. |
| `INSTANCE_SCOPING` (SINGLETON→INTEREST_SCOPED) | per-payload slicing | **Only with developer code** | Making a delta `Scoped` requires knowing how to slice *that payload type* by key — there is no generic lift. A registry entry could carry a developer-supplied slicer, but then the developer has written the adapter anyway; the registry only standardizes its insertion. Stays `Refuse` in phase 1. |
| `MERGE_IDEMPOTENCE` (NON_IDEMPOTENT→IDEMPOTENT) | none exists | **No** | A counted accumulator cannot be made idempotent without changing its semantics. (Dedup-by-delivery-tag is a real technique but needs unbounded tag memory and changes delivery semantics — a redesign, not an adapter.) Hard refusal, guarded by the spike's control. |
| `MONOTONICITY` (NON_MONOTONE→MONOTONE) | none exists | **No** | Monotonicity is a property of the value stream itself; any wrapper that forces it (e.g. running max) changes the values. Hard refusal. |
| `OWNERSHIP` (SHARED→EXCLUSIVE) | none exists | **No** | An adapter cannot conjure exclusivity — upstream aliases may exist; cloning into `Owned` forks the payload identity. Hard refusal (and the instance-set/SPSC refusals in `admitToInstanceSet` are structural, not link-lifts). |
| `COLOR` | n/a | n/a | Not a link-flow axis (placement property, validated at spawn). |

Net: exactly **one** axis has a generic, semantics-preserving lift.

## (b) Registry, not synthesis; offered, not automatic

- **Registry over synthesis.** An `Adapt` is a *lookup* in a registry of
  developer-registered adapter factories keyed by axis (`(axis, fromLevel,
  toLevel)` if an axis ever grows >2 levels). No code generation. The registry
  validates a candidate before naming it: the adapter's own offered vector must
  reconcile `Direct` into the inlet (single-hop, no chain search — open
  question 5 resolved as *single-hop only*).
- **Offered over automatic.** `Adapt(candidate)` is a *richer refusal*, not a
  third acceptance path: the link handshake still returns `Rejected` (now
  naming the available adapter in its reason), and insertion is an explicit
  wiring choice by the developer/DSL. This preserves "no silent bridging"
  verbatim — non-opting graphs see byte-identical behavior, including the
  rejection strings, until a caller explicitly consults the registry.

## (c) Shape and insertion (what phase 2 would build)

```kotlin
sealed interface Reconciliation {
    data object Direct : Reconciliation
    /** A validated, registered lift exists; NOT inserted — offered. */
    data class Adapt(val candidate: AdapterCandidate) : Reconciliation
    data class Refuse(val mismatch: NatureMismatch) : Reconciliation
}
```

- `reconcile` keeps its pure 2-arg form returning `Direct | Refuse`; an
  overload (or sibling `reconcile(offered, required, registry)`) adds the
  `Adapt` arm. `Link.handshake` maps `Adapt` to `Rejected` with the candidate
  named — zero behavior change at links.
- Insertion is a helper the wiring code calls explicitly, e.g.
  `linkVia(producer, inlet, candidate)`: spawn the adapter cell, link
  producer→adapter (Direct — the adapter's inlet requires nothing),
  adapter→inlet (Direct — validated at registration).
- **Determinism / PN-1**: the adapter's `CellRef` derives from the link, not
  from randomness — `UUID.nameUUIDFromBytes("adapt:$axis:${from}:${to}")` — so
  re-wiring after restart/replay reproduces the same ref, and its outlet's
  wave lane follows the usual epoch rules (fresh epoch at cold start, exactly
  like any cell).
- **Placement**: co-host with the *consumer* (the inlet whose requirement it
  satisfies), so a bridged producer edge terminates exactly where a hand-wired
  waver would.
- **Multi-axis mismatch**: phase 1/2 refuse outright (reconcile already reports
  the first refusing axis; no chains, resolving open questions 3/5
  conservatively).

## (d) The spike

`AdaptWaveParticipationSpikeTest` (all test-local, no production edits):

1. **Pinned today**: unwaved producer → ALIGN (WAVED-requiring) inlet is a
   typed refusal on `WAVE_PARTICIPATION` (and per PN-0a/PN-12, bypassing it
   the frontier drops the traffic).
2. **Spike**: a spike-local registry names a **waver** (a relay whose stamped
   vector offers WAVED and whose relay is `outlet.originate { … }`); the test
   inserts it explicitly; the ALIGN inlet then receives all values **in order,
   through the wave path** — every delivery carries a wave id from the waver's
   single source lane in counter order, `unmatchedDrops == 0`.
3. **Control**: `MERGE_IDEMPOTENCE` still refuses through the same spike
   reconcile — the registry has an adapter, but for a different axis.

The lift is ~15 lines including the registry — feasibility is not in doubt.

## (e) Recommendation: **NO-GO** (keep refuse-only; revisit on evidence)

The original gate was: build adapter synthesis only on post-evidence proof of
repeated hand-stacked adapters. Re-checked at `4cfbb83`:

- `grep -ri 'adapter|waver' demo/**/*.kt` → **zero** hand-written wavers or
  scopers across all seven demos (agora, exchange, shopping, skillmatch,
  slotfinder, tiering, backlog-triage).
- The whole per-node composition run hit no case where a refusal forced a
  manual adapter — the refusals introduced by PN-12 surfaced real wiring bugs
  (the silent drops), not missing lifts.
- Only one axis is even generically adaptable, and its lift is small enough
  that a developer who needs one writes it in ~10 lines today.

So the demand gate still reads zero. Ship nothing; keep `Direct | Refuse`.
This ADR + spike are the paid-down design cost: when N (suggest N ≥ 3) real
hand-written wavers accumulate in real graphs, phase 2 is a small, pre-agreed
step — the `Adapt` arm, the registry, and `linkVia`, exactly as sketched in
(c), with the spike promoted to `AdaptWaveParticipationTest`.

**Explicitly out of scope regardless of go/no-go** (per the ticket): multi-axis
chains, genuine synthesis, automatic silent insertion.
