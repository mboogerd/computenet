# T11 — Kernel structural extractions & idiom normalization

**Phase 3 · SOLO (nothing else running) · fresh session · Sonnet 5**
**Prereq**: Phases 0–2 fully merged. This ticket deliberately runs alone: it
touches `ManagedHost` and `Replication` — the two highest-churn files — and
must not race any other agent.
**Write scope**: `kernel/src/main/kotlin/civictech/cell/{host,control,consistency,replication,data,port}`,
`testkit/src/main` (import updates), matching tests, `doc/ARCHITECTURE.md`
package-map updates.
**Do not touch**: supervision extraction (explicitly deferred — see below),
`partition/` flip/ledger logic (RESTRUCTURE-PLAN:310 decision stands).

## Problem

SRP + modularity + API/DX + conceptual audits (verified 2026-07-27 at
`742f7ca`), the structural residue after Phases 0–2 fixed the correctness
issues:

1. **`ManagedHost` (947 lines, 18 responsibilities) is the repo's #1
   merge-conflict surface** (24 of the last 200 main-source commits; 288
   constructions across 87 test files). Two extractions are clean today:
   - `DirectedProtocolLink` (:933-947) — a wire-link adapter class living in
     the host file; purely file-local.
   - `hasDampingWitness` (:909-930) — a nature-policy predicate belonging
     with `FeedbackInlet`/nature reconciliation.
   - The link-admission concern (`connect` ×2, cycle admission, damping
     witness, topology recording — :777-830 + :909-930) is the one seam with
     **no `dataLock` interaction**, so unlike the RS-8 extractions it can
     leave cleanly.
2. **`AttentionPolicy` lives in `.host` while the attention machinery lives
   in `.control`** — flagged as anomalous by `doc/ARCHITECTURE.md:107`
   itself, and one of the ten package cycles (`control ↔ host`:
   `AttentionScheduler.kt:4` imports `host.AttentionPolicy`). A pure file
   move kills one cycle.
3. **`Replication.replicaFrontier` (:151-207) is a 57-line cross-replica
   settlement predicate living in a wiring class** — a *consistency* concern
   with 90 lines of KDoc accreting four historical fixes (R13, PN-7, PN-19,
   FU-2), while its interface (`ReplicaFrontier`) and consumer
   (`WaveFrontier`) live in `.consistency`. The still-open FU-2 ticket
   targets exactly this method; moving it gives FU-2 a unit-testable landing
   site. Related: `rebind` (:426-449) is the replicated-promotion COMMIT
   whose only caller is `evolve.Promotion.promoteReplica` — undiscoverable
   from either end.
4. **15 raw-constructor port sites** (`FanInlet(SomeClass::class.java)` +
   unchecked cast — catalog B1) survive despite `doc/ksp-dx-catalog.md`
   Phase 0 claiming their migration landed; the sanctioned form is
   `FanInlet.create<T>()` (56 sites). T09 corrected the catalog's status
   claim; this ticket does the actual migration.
5. **Two cheap self-documentation gaps** (SRP audit): `WatermarkCell`
   (`data/Watermark.kt:39-44`) carries four independent lattices (rows /
   closed / suspendEpoch / members), each added by a different settlement
   fix, with no class-level enumeration — the next lane-adder can't see the
   pattern. `WaveFrontier.offer`'s three context arms (:199-211 — null
   passthrough / baseline / waved) embody the push-vs-pull catch-up
   divergence tracked by T02's marker, with nothing at the code site saying
   so.

## Solution

Ordered smallest-risk first; run the kernel suite between steps.

### A. `ManagedHost` file-local extractions (S)

Move `DirectedProtocolLink` to its own file in `.host`; move
`hasDampingWitness` next to the nature-reconciliation code it belongs with
(`link/` — follow where `NatureNegotiation`/`Reconciliation` live per
`.nature`; pick the file the predicate reads most naturally from and note
the choice). Pure moves, zero semantic change.

### B. `LinkAdmission` extraction (M)

Extract the link-admission logic (cycle detection, headedness check, damping
witness, topology recording — the bodies behind `connect` ×2 at :777-830)
into `host/LinkAdmission.kt` as (near-)pure functions of
`(cells, topology, outlet, inlet)`. `ManagedHost.connect` becomes a thin
caller. **Constraint from the RS-8 discipline note: if the extraction forces
any lock-order change, stop and report instead of proceeding.** Existing
link/cycle tests pin behavior; add none unless a gap appears.

### C. `AttentionPolicy` → `.control` (S/M)

Move the file; update importers (`AttentionScheduler`, `ManagedHost`,
`testkit/SimWorld.kt:3-6`, tests). Keep the class name; this kills the
`control → host` edge. Update the T10 ratchet baseline in the same PR (the
edge disappears — delete its line per the ratchet's tightening rule) and the
`doc/ARCHITECTURE.md` §2 note that flagged the anomaly.

### D. `replicaFrontier` → `.consistency` (M)

Move the predicate into `civictech.cell.consistency` (e.g. `ReplicaQuorum`),
constructed from what it already reads (watermark cell reader, membership
reader, interest reader — all injected reads today). `Replication` keeps a
one-line factory so call sites barely change. Preserve the four documented
policy switches (`creationFence`, `degrade`, `membershipBarrier`, null-key
path) and their KDoc verbatim — this is a *move*, not a redesign; FU-2 owns
the redesign. Add the missing direct unit test: the predicate against a
hand-built watermark/membership state, no mesh required. Also: KDoc
cross-references on `rebind` ↔ `Promotion.promoteReplica` (one line each
end).

### E. Raw-ctor port migration (S, mechanical)

Find the 15 `FanInlet(...)`/`FanOutlet(...)` raw-constructor sites
(`grep -rn "FanInlet(\|FanOutlet(" kernel/src/main demo wire | grep -v create`);
migrate each to `FanInlet.create<T>()` / `FanOutlet.create<T>()`. Per the
catalog's own note this should land with zero test edits — if a test breaks,
the site wasn't equivalent; investigate before forcing.

### F. Self-documentation (S)

1. `WatermarkCell`: class-level KDoc block enumerating the four lanes, their
   owning fixes (PN-0c, PN-19, FU-2), and the rule: *a fifth lane triggers
   the split into a sibling membership cell* (the deferred design).
2. `WaveFrontier.offer`: a KDoc block over the three-arm `when` naming which
   arm serves push catch-up (null-context) vs pull catch-up (baseline) and
   citing T02's divergence marker — so the next reader stops treating the
   null arm as incidental.

## Verification

```bash
./gradlew :kernel:test
./gradlew test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
./gradlew :demo:exchange:test
./gradlew :kernel:test --tests '*ArchitectureRatchetTest'   # baseline updated, ratchet green
```

## Report

Per extraction: what moved, the diff size, confirmation of zero semantic
change (or the lock-order stop-and-report for B). The ratchet-baseline lines
deleted. Anything among the 15 raw-ctor sites that resisted migration.
