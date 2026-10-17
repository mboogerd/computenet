# ADR — Remove glitch-freedom

**Status**: ACCEPTED (2026-10-17) — decided by the project owner. Implementation
is epic `computenet-nogtk` (GFR); this ADR
is its phase 0 deliverable. Analysis pinned to `main` @ `fc33bc9c`.

**Supersedes**: the archived `doc/archive/adr/ADR - Glitch Freedom.md`; the
glitch-freedom resolutions of 93 I-18, I-23 and I-24, and the
completeness half of I-13; research items 95 R11 and R12; plan 96 §E2.

## Context

ComputeNet set out to combine many features in one dataflow graph framework.
The feature-interaction analysis (`93-feature-interactions.md`) and the work
since show that **wave completeness / glitch-freedom combines worst with the
rest**. It is not a feature beside the others but a guarantee every other
feature must preserve, and each feature re-opens it:

- cycles (93 I-5, 95 R16), topology change (I-13), identity and incarnations
  (I-14), supervision / dead-letter / restart (I-18), catch-up snapshots
  (I-24), promotion (`B14-promotion-glitch-freedom` in `DISPUTES.md`),
  boundary denial (SEC1 BS-12/BS-13), replication re-origination (95 R11),
  eviction (`24-WL-GF-01`);
- the demos keep hitting its edges: no batch-as-one-wave across cells (F-19,
  F-22), independent roots cannot align (F-27), edge-of-graph views (F-5),
  and a chain of premature-settlement regressions in the absorb-ack relay.

The project's priorities, as the owner states them:

- **Replication is primordial** to the project's philosophy and stays.
- **Mobility and suspension matter**: the goal is hosting graphs that exceed
  one computer's resources, which requires moving and parking cells freely.
- **Glitch-freedom is not primordial.**

The code survey (2026-10-17) found that the suspension machinery's wire-level
complexity exists almost entirely to feed glitch-freedom. Every `Stall` /
`Resume` notice a host emits — on suspend, resume, RESTART, dead-letter,
boundary denial, stability freeze — is consumed only by `WaveFrontier` or
`AlignedObserve`. The same holds for every `Progress` absorb-ack. Removing
glitch-freedom therefore removes the hardest constraint on mobility rather
than merely one feature.

## Decision

Remove opt-in glitch-freedom from the specification and the code, entirely:

- the glitch-free cell and per-inlet wave-completeness fold
  (`[22-GF-01..03]`, `[22-LIVE-01]`, the ALIGN policy tier, `WaveGate`,
  `CoalescingCombineCell`'s per-wave coalescing);
- the aligned observation frontier (`[22-OBS-01/02]`, `observeAligned`,
  `AlignedCompositeCell`, aligned admission, `FrontierWitness`);
- completeness over silent or stuck edges (absorb-acks, `Progress`, source
  provenance, the WAIT / DEGRADE / RE-SCOPE dispositions);
- the downstream Suspension protocol (`StallNotice.Stall` / `Resume` as wire
  signals, `notifyDownstream`, suspension *regions*, boundary-denial stall
  scoping);
- cross-replica settlement for completeness (`ReplicaFrontier`,
  `ReplicaQuorum`, the JoinBarrier read, PN-7 interest-scoped settlement, the
  FU-2 converged-membership barrier);
- the `WAVE_PARTICIPATION` nature axis and `Manifest.GLITCH_FREE`;
- the TLA+ models under `formal/`, which model only wave completeness.

**Kept**, because something other than glitch-freedom needs it:

| Mechanism | Who still needs it |
|---|---|
| `MessageContext` / `Timestamp(sourceId, counter)`, emission epochs | durable replay dedup (`processedFrontier`), cycle hop guard and re-origination, evolution's wave-state adoption |
| catch-up `baseline` marker, `ReBaseline` | late join, durability recovery, QuorumSet lanes |
| in-band `EdgeOpen` / `EdgeClose`, topology ordering | `PullOnOpen` catch-up, presence lanes, QuorumSet lane count, Waterline retirement |
| delivered-watermark lattice, `CausalStability` | replication tombstone GC (`StabilityReclaim`) |
| Waterline, cycles, `ParkQueue`, lifecycle, epochs | independent features |
| `StabilityFreezeDetector` | in-process diagnostic listener only; its downstream fan-out goes |

**Principle P4 is amended**: the consistency guarantees ComputeNet offers are
per-link FIFO, causal delivery where specified, and convergence. Multi-input
derived state may be observed mid-wave; an application needing an atomic view
keeps the joined state in one cell.

**Wire format**: removing the `Progress`, `StallNotice.Stall` and
`StallNotice.Resume` frame registrations is an intended wire change, authorized
here. `NatureVector.toWire()` encodes axis ordinals, so the deleted
`WAVE_PARTICIPATION` axis keeps a reserved placeholder rather than shifting
later ordinals.

## Alternatives considered

- **Keep it, but freeze it.** Rejected: every new feature still has to define
  its stall, restart and settlement semantics against the frontier, which is
  the tax being removed.
- **Keep only local, single-host diamond buffering.** Rejected: the cost is
  in the cross-host and suspension semantics. A local-only variant either
  wedges under suspension or needs the same signals.
- **Remove it** (chosen).

## Consequences

- Observers may see torn intermediate states across fork-joins. The shared
  `observation()` read API moves onto the non-aligned `ObserveCell` composite.
- `:demo:exchange` loses its glitch-free board and the matching axis of
  `ExchangeCompositionExitTest`; its partition, replication, migration and
  epoch axes remain.
- Concord retires 10 scenarios (22-GF-DIAMOND-01, 22-GF-NESTED-01,
  22-OBS-01, 22-OBS-02, 22-WAVE-FANIN-01, 24-WL-SETTLE-01, 24-OP-COMBINE-02,
  DUR-GF-01, CTL-GF-01, and the aligned half of 24-OP-COMBINE-01) and their
  `DISPUTES.md` entries; `CONCORDANCE.md` is regenerated.
- Open beads that existed to add or change glitch-freedom are closed as
  superseded by this ADR. Beads that only *mention* it are re-scoped.
- Roughly 4,100 kernel lines are deleted outright, plus about 60 absorb-ack
  call sites and about 300 lines of surgery in host, composite, replication
  and wire code.

## Revisit trigger

An application that needs an atomic view over several cells and cannot keep the
joined state in one cell. Any reintroduction must say how it composes with
suspension and migration *before* it says how it composes with anything else.
