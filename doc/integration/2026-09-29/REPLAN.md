# ComputeNet integration re-plan (2026-09-29)

The question: after weeks of largely autonomous, highly parallel development,
how well is the implementation an *integrated* realization of the vision
(`doc/spec/00-foundations/01-vision.md`), and what closes the gap so that one
application can use all intended features together?

Evidence: eight assessment reports read at main `cbc7bb52`, plus a backlog fit
at `181c6459`, all in this directory:

| Report | Slice |
|---|---|
| [01](01-programming-model.md) | Programming model and construction surfaces |
| [02](02-dataflow.md) | Dataflow semantics, operators, observation |
| [03](03-execution.md) | Execution model, durability, partitioning, budgets |
| [04](04-distribution.md) | Location transparency, replication, transports, identity |
| [05](05-evolution-verification.md) | Invariants, evolution/promotion, inspector, concord numbers |
| [06](06-demo-matrix.md) | Demo × feature matrix and bypasses |
| [07](07-interactions.md) | 93 feature-interaction decisions vs code |
| [08](08-fragmentation.md) | Duplicated concepts, module islands, doc drift |
| [09](09-backlog-fit.md) | Every open bead placed against this plan |

## Diagnosis

The kernel is more coherent than feared: replication, the journal, the delta
types, the HTTP/SSE shell and the wire bridge each have one implementation
that everything uses. Integration fails one layer up:

1. **No single construction surface.** Six link paths with different
   guarantees; GraphSpec covers only spawn/connect; the placement driver is
   unbuilt. Features are bolted on by imperative calls, so they cannot be
   declared together. (01, 04)
2. **Features are tested pairwise, never stacked.** 93 decides every pair but
   no triple; the one composition gate is in-process, excludes durability and
   hand-partitions. (02, 03, 07)
3. **Many features have zero application users:** evolution/promotion,
   invariant cells, Owned/Leased, membranes, mobility, identity configuration,
   budgets, `:demograph`. (05, 06, 08)
4. **Demos rebuild what the spine cannot give them:** five topology logs,
   three private journals, two input checkpoints, hand partitioning,
   interest→spawn in demo code, per-demo bootstraps. (03, 06)
5. **No epic owned integration.** Each open epic extended one slice. (07, 09)

## Decisions (human, 2026-09-29)

1. **Flagship** = decentralized multi-user backlog prioritization, grown from
   `:demo:alignment`. Social semantics: each user writes only their own slice
   (perspectives/ratings), adds objects, edits/deletes their own objects,
   transfers ownership.
2. **Cross-JVM mobility and membranes (G-52)** are postponed past flagship
   milestone 1.
3. **Backlog** re-fitted per report 09 (applied in bd, see below).
4. **`:demograph`** is wired in (it is the flagship's domain vocabulary);
   **`:loader`** is parked for milestone 1.

Consequence of 1 + 2: identity/authority becomes core Phase 3 work, and
"write only your own slice" is enforced by signed per-principal writes checked
at replication admission, which does not need membranes.

## Tracker structure

| Epic | Phase | Blocked by |
|---|---|---|
| INT — Integrated ComputeNet (computenet-81ocs) | umbrella | — |
| INT0 — Make integration visible (computenet-zjbwo) | 0 | — |
| INT1 — One construction spine (computenet-8i1m6) | 1 | — |
| INT2 — Stacked composition gate (computenet-e8gjk) | 2 | INT1 |
| INT3 — Dormant features into the spine (computenet-4vxtr) | 3 | INT1 |
| FLG1 — Flagship: decentralized prioritization (computenet-jtdrf) | 4 | INT2, INT3 |

INT0 and INT1 are broken down into features now (their shape is decided).
INT2, INT3 and FLG1 are **intentionally not pre-broken-down**: each carries
intent, outcome, epic-level acceptance and breakdown guidance naming the
existing beads to absorb (linked `related`, labelled `replan:p2|p3|p4`), so
`/work`'s breakdown refines them against their intent when their blockers
clear. (`/work` treats an epic with children as already broken down, which is
why those beads are related rather than re-parented.)

### Phase 0 — INT0 features

- Script-generated feature × demo adoption matrix in CI (computenet-a7ukn)
- Doc refresh, absorbing qp4xo (computenet-oe9ss)
- Requirement ids for 43/52/53 (computenet-xn0ox), blocked by the
  concordance attribution bug (computenet-nta1)
- Inspector as a DemoShell flag (computenet-3iv0w)
- Ratchet architecture test against raw subscribe/streamTo in demo mains
  (computenet-837mi)

### Phase 1 — INT1 features

- 1.1 One link primitive; migrate agora and deliberate (computenet-wakkv)
- 1.2a GraphSpec carries features as parameters (computenet-x0oag), after 1.1
- 1.2b Journal the GraphSpec as the topology record (computenet-8xstm,
  re-scoped), after 1.2a and the journal prerequisites s4n8y and o2aj;
  decides DurableMap (n0iaw)
- 1.2c Kernel durable-input checkpoint (computenet-12qyp)
- 1.3 Runtime bootstrap + kernel PeerTransport seam (computenet-gyvli), with
  the transport lifecycle bugs vzb, 1mbp, 8uv6, amf8l, axifn, g1aua as siblings
- 1.4 Placement driver (computenet-8k723), after 1.2a and 1.3
- Prerequisite: Effectful+Stateful journal-tail loss (computenet-rhhry)

## Lanes, and how they relate to the phases

The phases are the dependency order of the **critical path**. Work also runs
in parallel lanes that do not wait for it:

| Lane | Content | Relation to phases |
|---|---|---|
| Critical path | INT0 → INT1 (1.1 → 1.2a → 1.2b; 1.2c; 1.3 → 1.4) → INT2 ∥ INT3 → FLG1 | the phases, in order |
| Prerequisites pulled forward | journal bugs (s4n8y, o2aj, rhhry); announced-ref capture (zlm2) | phase work started early because small and conflict-free; zlm2 may equally wait for INT3 |
| Flagship domain | demograph (computenet-drz8, un-deferred; drz8.2 collective ranking first); alignment board-gate bug (computenet-aa6gl) — fix in the current board now | no spine dependency until FLG1 |
| Wind-down | ECO1 budgets (computenet-66m), kernel-only: 4yvsx, 8s74x, fh9cc → xbs8t → jtof7 → o0lzp | the bootstrap installs its ledger later |
| CI hygiene | flake fixes (report 09 §3 orphans) | needed before INT2's gate becomes a required check |
| Research | deliberate model calibration; FRM1 TLA+; benchmarks; query | orthogonal |

## Backlog placements applied (report 09)

- Labels `replan:p0..p4`, `replan:prereq`, `replan:continue`, `replan:pause`,
  `replan:postpone` on the placed epics and items; everything filed or moved
  for this plan also carries `integration`.
- **Paused** (deferred, with a comment): deliberate structural refactors and
  new features (calibration research continues), WKB2 F7/F8/F10/F11/F13, BDS4
  and BDS5, AGO3/AGO4, the shopping ActorIngress decision.
- **Postponed** (deferred): mobility, membranes, `:loader`/JAR, gossip,
  discovery/NAT, placement economics, connectors, social SOC2/SOC3, TTD,
  KE5 cycles, and the other items in report 09 §3.
- **Merged/closed:** g92i → 1.1; j1f2, d2j2 → 1.3; cj7 dropped as obsolete.
- **Moved:** 4zmnd, 6y3ez, ekxxt from BDS4 to the SDLC epic.
- **Released:** a stale claim on BDS3 (98u).

## Capturing intent, and re-validating against it

Every epic above carries **Intent** (the vision claim it serves), **Outcome**
(observable end state), **Scope / Breakdown guidance**, **Non-goals**,
**Re-plan triggers** (findings that send it back to design rather than
onward), and EARS acceptance at epic level in the acceptance field. Children
partition that acceptance but cannot redefine it.

Today `/work` closes an epic when all children close, without checking the
epic's own acceptance. The epic-close validation gate that fixes this — a
reviewer checks each epic clause against main, files missing children
(recursion) or parks on a fired re-plan trigger — is filed under the SDLC
epic for the remediate-friction lane (computenet-2w5ja).

## Flagship feature mapping

| Flagship behaviour | Platform feature |
|---|---|
| Each user writes only their own slice | identity + signed writes + authority at replication admission; per-principal keyed family; `:demograph` actor/stance/weight |
| Objects: add, owner-only edit/delete, transfer ownership | replicated set + ownership-transfer authority op |
| Collaboration across peers, offline included | interest-driven replication; per-peer durability, recovery, reconvergence |
| Live board | incremental aggregation behind glitch-free aligned views |
| Trust | live invariants: no foreign writes in a slice; aggregate consistent with slices |
| Improving the ranking | evolution orchestrator: shadow → invariants → promotion |
| Spam / Sybil resistance | ECO1 per-principal budgets |
| Beads as candidates | BDS3 glitch-free ready set + beadsmirror replication |
