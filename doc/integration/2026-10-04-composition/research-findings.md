# Composition research findings (2026-10-04)

How should cell features compose: stateful, effectful, durable, suspendable, partitioned, replicated, glitch-free, evolvable? This note records what four rounds of research established about that question. The design notes alongside it build on it:

- [per-link-positions.md](per-link-positions.md)
- [composite-obligation-holders.md](composite-obligation-holders.md)

The method was to give the same brief to two models, Claude and Codex, read-only and independently. The two answers were then compared. Any claim they disagreed on was checked against the code before it was kept. The spec and kernel were pinned at `27e57964` / `5a644938`; both are byte-identical to `8d71ea64` for `doc/spec`, `kernel/src/main` and `concord/corpus/DISPUTES.md`.

## The design target

The architect wants the host to know nothing about what a cell means. Its job is to route data, and to suspend, activate and relocate cells when told to. Beyond that it provides three things only:

- storage that survives the process,
- re-creation of a dead cell, handing it back its storage,
- reporting the gap that the death left.

Everything else becomes part of the composed cell: durability, the durable boundary, the effect frontier, dead lanes, passivation queues.

Spec 33-mobility.md:18-29 says "the host is the unit of mobility" and rejects per-cell suspend/migrate as "complex and race-prone". That rule was the architect's own simplification. It assumed host suspension would be enough and that mobility could be done by suspending hosts and restarting them reorganised. The architect now doubts it, because suspending a host has to solve the same problems for every cell whose links cross the host. Treat it as open to revisiting.

## 1. Composition is not canonical, and pairwise analysis is not enough

Combining two features often forces an extra decision that neither feature makes on its own. We call that a *choice point*.

Example: a partitioned cell that can also be suspended. Do all partitions suspend together? Who decides? What happens to messages in flight?

We mined `93-feature-interactions.md`, spec chapters 31–34, `22-consistency`, `24-data-cells` §Durability, `42-replication` and `DISPUTES.md`. That produced roughly 300–350 distinct choice points:

- about two thirds decided,
- about 170 open,
- about 30 refused combinations.

Pairwise combinations are mostly decided already. Nearly every open choice point involves three or more features.

A feature's role does not predict whether a combination needs a choice. Its parameters do. Two parameters matter most:

- **Mergeability of state.** For replicated × stateful: mergeable state is determined, single-writer state needs a leader, and non-idempotent state is refused.
- **Interest of a multiplier.** Partitioned and replicated are one mesh with an interest setting. Disjoint interest gives effect-once by construction (31-hosts.md:392-396). That dissolves the "nesting" question entirely.

Question types that recur, beyond those first predicted:

- **continuity** — whether identity survives a mode change
- **precedence** — whose rule wins when two authorities collide
- **scope** — what unit a feature acts on (the host, the glitch-free region)
- **signalling absence**
- **guarantee level**

## 2. Every mode change is a continuation or a succession

Transition (what happens to held work) and continuity (what happens to identity) turned out to be one decision seen from two sides.

| | Continuation | Succession |
|---|---|---|
| Mode changes | suspend/resume, drain, migrate, T0/T1 promotion; durable replay (proven by the journal) | RESTART, cold start, replica spawn, T2 promotion |
| Held work | fence admission → settle accepted work → capture state → release held invocations under their original context | transient work invalidated; state rebuilt by catch-up (sound only for mergeable state) |
| Identity | outlet epoch adopted | fresh epoch minted and announced with `ReBaseline` |
| Selector | the successor can prove it is the one exclusive, complete continuation (93:11480-11492) | otherwise |

Identity has three planes:

- **Name** (cell ref, instanceId): kept by the same instance.
- **Position** (outlet epoch): adopted only with a proof of the predecessor's high-water that covers the whole outlet and survives replay.
- **Content** (tags): always travels verbatim.

Every dedup record must be keyed on a plane that the mode change kept.

## 3. Data composes on its own; obligations are the design surface

An **obligation** is either:

- something the holder owes outside its own merge domain (an act already performed, an exclusive right, a promise), or
- something held in sole custody since it was accepted.

The test: could a successor reach the same disposition from the surviving mergeable state, without guessing whether an act, an acceptance or an exclusion happened? Acceptance is what creates an obligation.

The inventory:

- the effect processed-frontier and the discharged-baseline set
- `Owned`/`Leased` custody
- acceptance custody: the journal, every park queue, held frames, input cursors
- exclusive authority (single writer, effect authority)
- position non-reuse, dead lanes, generation
- settlement of a departed replica's watermark row
- budget charges
- multi-port transactions
- replay protection
- reporting a death

**The rule.** Each obligation has one holder at a time and changes holder only at acceptance. At a mode change it is handled in one of four ways:

- transferred (whole or as a slice),
- discharged,
- announced and fenced,
- reported.

If none of these is possible, the combination is refused or the transition vetoed. Any gap that remains is a declared ceiling, per event.

The primitives are **accept, transfer/slice, discharge, report, announce+fence, refuse, veto**. The host adds **persist** and **re-create (reporting the gap)**.

Granularity, authority and precedence choice points reduce to obligation questions only in part. Of 154 classified: 34 reduce, 48 partly, 72 do not.

What reduces:

- who holds an exclusive right,
- the scope an obligation must cover.

The framing also predicts which granularity questions are hard: those where an obligation's scope differs from the unit being moved.

What does not reduce falls into two groups. The first two layers below decide whether a composition is correct; the second two are explicit parameters and assumptions:

1. **Data merge laws** (e.g. a superseding retraction vs a concurrent remove).
2. **Obligation custody.**
3. **Policy** (placement, thresholds, replica extent).
4. **Environment** (failure detection, synchrony), plus liveness duties (cycle quiescence) and the choice of which data source is authoritative.

## 4. Per-cell durability composes without global coordination

Today's shared host journal is not what makes cross-cell recovery consistent. The spec already decides there is "no consistent cut across cells or hosts" (93:2849). Recovery promises convergence, and that comes from two things:

- **Receiver-side logging:** each accepted frame is appended before delivery.
- **Idempotent absorption** of duplicates.

Per-cell journals therefore compose, under four conditions:

1. **No bypass.** Every inbound frame crosses the Durable wrapper and is appended before delivery.
2. **The send→log handoff is closed.** It is synchronous in-process. On async links, the sender retains output until it is acknowledged.
3. **Replay is deterministic**, or the cell logs its non-deterministic decisions and output.
4. **Every non-idempotent inlet drops duplicates by an exact per-link position.**

Wave frontiers are the right naming, but they are not an exact key, for several reasons:

- Reactive emissions inherit the root wave's timestamp.
- Catch-up baselines carry positions that must not advance a frontier.
- Non-determinism can re-issue a position.
- Coalescing and cycles break the mapping from input wave to output wave.

Coordination remains necessary in three places:

- glitch-free joins that must be exact to the wave,
- multi-cell atomic actions (repartition flip, promotion swap),
- cycles.

All three can be expressed as cells and links, not host services. Recommended storage form: one physical log with per-cell logical streams, which keeps group commit.

## 5. Composition is hierarchical

- **Host:** persist, re-create, report the gap.
- **Wrappers:** per-cell concerns. RESTART is a control signal to an outer wrapper that resets only the inner cell, so the outer layers hold what a restart must not roll back.
- **Composites:** obligations and coordination whose scope spans several cells:
  - exclusive authority in a replica set
  - effect-frontier slices on repartition
  - completeness across replicas or a glitch-free region
  - repartition flip and promotion swap
  - multi-port transactions
- **Outside any one cell:** an invocation parked before its target exists (needs a sender-side holder), and a cell's own death (needs a survivor).
- **Links:** carry exact per-link positions.

Codex's summary: no obligation is in principle barred from cell composition, provided the "cell" may encompass the whole region the obligation concerns.

## Defects found along the way (filed)

| Bead | Defect |
|---|---|
| computenet-8g7kg | The effect frontier is not handed off on repartition or migration. A catch-up at an Effectful inlet fires, so a repartition may re-fire the whole moved range. |
| computenet-wlwjw | An Effectful inlet may silently drop a second live frame of the same wave: the frontier is keyed on the root sourceId per port. |
| computenet-d2lue | A crash during an open repartition flip loses parked moving-range commands: the flip state is volatile. |
| computenet-5jhg3 | `GlitchFreeCell.onDeactivate` drops partial waves on drain and migrate, not just on RESTART. |
| computenet-lzfr0 | The T2 promotion `ReBaseline` supersedes the candidate's own lane, not the incumbent's, and fires before downstream rebind. |
| computenet-eilt8 | RESTART retraction keys on tag sourceId, which production tag sources never share with the outlet epoch being superseded. This is a real clash between replay-stable identity (durability) and supersedable identity (RESTART). |
| computenet-kxdjx | `TagState.snapshot()` omits `deadSources`, so a restored consumer re-admits stragglers. |
| computenet-wkopk | The `HELD_LEASE` / `CONSTRUCTION_LEASE` / `MGMT_ACTIVITY` veto vocabulary is recorded as landed (93:3639) but absent from the kernel. |
| computenet-g5tr6 | A supervision-suspended cell that migrates dead-letters its parked traffic, contradicting `[33-MOVE-01]`. |
| computenet-e55gy | Spec contradiction: I-8's direct organelle-link shortcut vs I-10's hidden-by-default organelles. |
| computenet-0qyld | Spec contradiction: I-22 `generation` is host-side only, but its tables say it widens `SourceId`. The code follows the host-side version. |

Still undecided, and central to all of this: `DISPUTES.md:1232-1268`. An Effectful + Stateful cell loses its journal-tail state on replay, because suppression drops the whole invocation. The suppression decision is also made in the host (`ManagedHost.deliver`), outside the PN-9 inlet-policy chain, and it ignores the per-contract effect flag (`@Contract(effect=true)`) that 93 I-17 decided should replace the cell-level marker.
