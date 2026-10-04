# Executable model of the composition design: results

Status: results of the `:composition-model` module (`composition-model/`, package
`civictech.compmodel`), built for computenet-gl2i7. It checks the claims of
[per-link-positions.md](per-link-positions.md) (**PLP**) and
[composite-obligation-holders.md](composite-obligation-holders.md) (**COH**) mechanically.
Every number below is from one run of `./gradlew :composition-model:test --rerun` (56 tests,
all passing; 41 s of test time, 43 s wall), which writes them to
`composition-model/build/compmodel/report.txt`. The test task clears that file before it
runs, so the file is one run.

**Which text is modelled.** The notes as revised for the model findings: `2bfa8639` plus COH
F9 (whole-path re-derivation), F12 (new), §3.2 step 8 and recovery row R5, §3.4 (gate in `P`,
rollback only before COMMIT), §6, and PLP §5.3, §5.6 step 4, §5.8 exception, §7, §9. The
dispositions are in `reconciliation/*.md`, "Model findings response". Each old text that a
finding refuted is kept as a control variant, and each control still diverges (§5).

**Revision after the model review.** An adversarial review of the first model found its
positive results weaker than claimed. What changed, per review item:

| Review item | Change |
|---|---|
| 1. Flip marked a frame released before the shard acknowledged it; shard crash kept its queue | Rewritten flip: send, acceptance, sync (`STABLE`), `STABLE` ack, R6, marker barriers, drain ack and R7 are separate transitions; a shard crash loses its inbound wire, unsynced tail and unsent answers; `O` resends in order. Control "R6 on send" diverges. |
| 2. Order validity unproven | Two schedules added (`P`'s suspension across a crash; a stale-then-valid frame at one position). Assertion is now accepted ⊆ valid, plus a named witness for every valid-but-refused order. Full 7-layer stacks are checked (§3). |
| 3. Refinement was `containsAll` | Exact equality against a ghost reference over accepted content at quiescence; a premature addition is a violation at every state. |
| 4. REGION-1 was an artefact | Check replaced by the spec's property (no partial region park; no loss of partial-wave custody). REGION-1 and its wave-boundary remedy withdrawn. |
| 5. `S`-outside-`D` control used RESTART | New control with a crash and no RESTART; the test asserts its trace has a crash and no RESTART. The RESTART control is kept separately. |
| 6. Fused crash points | Promotion: PRECHECK, PREPARE, COMMIT, green, each release and RETIRE are separate. Flip: COMMIT/SHED and ABORT/UNSETTLE are separate. Flip PRECHECK is not modelled (§6). |
| 7. F9-X ambiguity | Settled in the notes (COH F9, PLP §5.8); the model logs outputs through an `X`-suppressed inlet; the old text is a control. |
| 8. Stale or vacuous evidence | Report cleared per run; `-Pcompmodel.seeds` must be a positive integer (0 fails the run); walks assert every seed took steps; order scenarios must each refute something; reachability checks show each finding's configuration is explored under the fixed protocol. |

---

## 1. What is modelled, and at what abstraction

Each model is a deterministic transition system over immutable Kotlin data classes
(`check/Explorer.kt`). It has no wall clock and no threads. Faults and management actions are
ordinary transitions with a per-run budget, so a crash can fall between any two transitions.
Exhaustive mode is breadth-first search with state hashing (the first violation is a shortest
one). Walk mode is seeded random walks; `-Pcompmodel.seeds=N` sets the count (default 200).
`Explorer.reach` finds a shortest path to a configuration, used to show a claim is not vacuous.

| Model element | Encodes | File |
|---|---|---|
| Layers `D O P F A X S`, properties table | COH §2.1, §2.5 | `cell/Vocabulary.kt` |
| Position `(epoch, lane, seq)`, pull id | PLP §0, §3.2, §5.1, §5.4 | `cell/Vocabulary.kt` |
| Leaves: `Effectful` sink, mergeable set, relay, `Effectful`+`Stateful` | COH F8; PLP §5.3 | `cell/CellModel.kt` |
| `D` appends before delivery, acks `STABLE` (`SYNCHRONOUS`) | COH §2.3, H1; PLP §5.8, §6 | `CellModel.inward` |
| `X`: `disposed`, `pullDischarged`, `dead`, gap check, record after the act | PLP §5.1, §5.3, §5.7; COH F3 | `CellModel` |
| Crash: outside `D` lost; inside restored from checkpoint, tail replayed | COH §3.8 `NONE`; PLP I-P2 | `CellModel.crash` |
| RESTART as succession; `ReBaseline`; pull-baseline catch-up | COH §2.4 (M11 fallback); PLP §7; M14 | `CellModel.doRestart` |
| `P` suspend/resume (management holds the intent), `F` fence + `DESIGNATE`, `A` waves, `O` retention | COH §2.4, §2.5, §3.1; PLP §5.8 | `CellModel` |
| F1-F6, F8, F12 | COH §2.6 | `cell/FormationCheck.kt` |
| Flip: R1-R7, in-band barriers, scoped cursors, handoff, abort waiting for R3 | COH §3.2; PLP §5.6, §5.8 | `composite/FlipModel.kt` |
| Promotion: gate in `P`, phases, T1/T2, effect identity, logged outputs | COH §3.4, F9, F11; PLP §7 | `composite/PromotionModel.kt` |
| Replica set: set-lane fan-out, `received`, retention, takeover | COH §3.1; PLP §3.2 | `composite/ReplicaSetModel.kt` |
| Region: atomic suspend, contagious veto, join capture on migrate | COH §3.3; spec 34:163-174 | `composite/RegionModel.kt` |
| Relocation H4 | COH §5.1 | `composite/RelocationModel.kt` |

**Invariants**, as named in the traces:

- **I1 (custody).** An `Owned` payload is consumed at most once (plus the declared ceiling);
  the fence never drops below an accepted `DESIGNATE`; never two live holders; a stale unit
  never reaches the leaf after its `DESIGNATE`; **no frame reaches the leaf while management
  holds the term suspended** (COH §2.4 continuation state, F1).
- **I2 (effects).** An input position is acted at most `1 + (crashes inside its act → X-record
  window)` times; replica failover adds 1 per unpublished acted position (COH §3.1, M9).
- **I3 (no silent loss).** At every state, every accepted frame (and every emitted output) is
  disposed (by ground truth: the effect log by content, the leaf state, downstream), held
  somewhere that survives, or reported.
- **I4 (refinement).** A ghost reference holds the content of every input T accepted. A
  mergeable leaf never holds content outside it (every state), and at quiescence equals it
  minus refused-with-report and still-held inputs (**exact equality**). An acted
  `Effectful`+`Stateful` input has its state transition.
- **I5 (positions).** A position is never re-issued for different content; no disposal above a
  gap on a dense lane; downstream never takes one output twice.
- **Glitch-freedom; region.** The leaf sees complete waves; no partial region park; every
  forwarded arm is held at the join or delivered in a complete wave.

**Not modelled** (coverage limits, §6): `TAIL`/`UNKNOWN` gaps; wire negotiation;
`supersede=false`, closed-lane LRU, GC caps; coalescing (E3); budget charges (§3.7);
couplings (§3.5); M11 (c) continuation and `Owned` re-consumption; the failing frame of a
RESTART; a crash during recovery; partial replica interest; volatile relocation (B4);
cross-host regions; liveness (stuck-but-held is accepted).

---

## 2. What was explored

| Experiment | Configuration | Result |
|---|---|---|
| Order validity, 6 projected scenarios | `effect-core`, `merge-core` `{D,P,X,S}`; `align` `{D,P,A,X,S}`; `fence` `{D,F,A,X,S}`; `fence-dedup` `{D,F,X,S}` with a stale-then-valid frame; `outbox` `{D,O,X,S}`. One crash, reconnect, RESTART, SUSPEND, checkpoint (or DESIGNATE) each | 336 orders, 3.46 M states, all holding runs exhaustive |
| Full 7-layer canonical stack, exhaustive | 6 scenario × fault-set pairs (§3) | 2.73 M states, all HOLD |
| Full 7-layer stacks, walks | 10 model-valid orders × 4 scenarios × 200 seeds, all faults at once | 108,045 steps, all HOLD |
| Projection spot check | 40 sampled orders × 4 scenarios | 151 refutations, each matched by a refuted projection |
| Flip, shard crash ×1 | 3 boundary frames `{r,a},{r,b},{r}`, abort optional | 1,230,705 states, depth 47, HOLDS |
| Flip, router crash ×1 | 2 frames | 1,746,103 states, depth 49, HOLDS |
| Flip, `BATCHED` shard streams, shard crash ×1 | 2 frames; crash loses the unsynced tail | 750,917 states, HOLDS |
| Flip, act → X-record window open, shard crash ×1 | 2 frames | 1,339,256 states, HOLDS |
| Flip, control on management band, release before `Committed` | 3 frames, shard crash ×1 | 478,781 states, HOLDS |
| Promotion, `Effectful` T1 / T2 / T2 other identity (`effectFrom = COMMIT`) | 2 frames; crash, lost ack, rollback | 59,409 / 156,210 / 156,210 states, HOLD |
| Promotion, relay T1 / T2 | 2 frames | 55,130 / 87,280, HOLD |
| Promotion, emitting `Effectful` T1 / T2 | 2 frames | 193,130 / 353,472, HOLD |
| Replica set; exact witness | 3 positions, leader death at any step | 1,268; 1,148, HOLD |
| Region; veto; relocation | 2 waves; 2 frames, crash of either host | 54; 36; 288, HOLD |
| Cell spot runs (canonical) | relay, same-wave, upstream succession, M1 (a), CELL-1 fixed, REPLAY-1 fixed | 561 to 126,897 states, HOLD |
| Walks, 200 seeds each | canonical stack 2 lanes × 3 waves; mergeable 2 × 3; relay 4 frames (crash ×3, reconnect ×3, RESTART ×2, suspend ×2, checkpoint ×3); flip 3 frames, router ×2 + shard ×2; promotion 5 frames (`Effectful` and emitting); replica 5 positions | 38,903 steps, all HOLD |

---

## 3. Order validity

All 7! = 5040 orders of `{D,O,P,F,A,X,S}` are judged two ways: by the conjunction of
exhaustive verdicts on each order's projection onto each scenario's layer subset, and by F1-F6
as transcribed (`FormationCheck`). The test asserts the intended properties, not the present
answer: **every accepted order is valid**, and **every valid-but-refused order has a named
witness** (an unexplained one would fail the test as a new finding).

| | Count | Orders |
|---|---|---|
| Formation-accepted | 8 | `DFOPAXS DFPAOXS DFPOAXS DOFPAXS DOPFAXS DPFAOXS DPFOAXS DPOFAXS` |
| Model-valid | 10 | the 8 above, plus `DFPAXOS DPFAXOS` |
| Accepted but refuted | **0** | soundness holds |
| Valid but refused | 2 | both witnessed by OV-2 |

**OV-2 (stands, cost-only).** `O` between `X` and `S`. F3 refuses it positionally, but F3's
reason (a frame held between `X` and the leaf while its duplicate passes `X`) is about inbound
custody, and `O` holds outbound frames only. COH fixes `O` directly inside `D` for a
deterministic manifest. No text change is needed; F3 could be read as "no layer that holds
inbound frames between `X` and the leaf".

**OV-1 withdrawn, F5's "outside `X`" half now independently necessary.** With the two added
schedules:
- `P` outside `D` is refuted: a crash resets `P`'s suspension, the sender resends the retained
  frame, and it reaches the leaf with no RESUME (I1). F1 holds for `P` for the reason it states
  (monotone state), not only as a cost rule.
- `F` inside `X` (`DXFS`) is refuted: the stale unit passes `X`, `F` refuses it, the refusal
  advances `disposed`, and the valid frame at the same position is dropped as a duplicate (I3).

**Full stacks.** The projection argument assumes no cross-layer interaction a scenario lacks.
It is checked three ways on full 7-layer stacks: the canonical stack exhaustively (effect +
waves with crash, reconnect, suspend, DESIGNATE: 401,205 states; with crash, RESTART, suspend,
DESIGNATE: 145,915; stale-then-valid: 514,827; mergeable with catch-up: 1,056,336; relay with
reconnect: 487,719; relay with checkpoint: 126,897); every model-valid order under walks; and a
sampled refutation check. **This found one interaction the projections cannot see: REPLAY-1
(§4).** Full stacks therefore run with its candidate fix; under the notes' text the canonical
stack itself fails.

**What the claim covers.** Accepted ⊆ valid is exhaustive per projection and walked on full
stacks. Valid-but-refused is exactly the witnessed set, per projection. It is not an exhaustive
proof over full stacks: only the canonical order is explored exhaustively there, under the fault
sets listed.

---

## 4. Findings

Each finding is a test. For a fixed finding, the fixed protocol holds, the configuration of the
old counterexample is shown reachable, and a control that restores the old text diverges.

| Finding | Status | Test |
|---|---|---|
| FLIP-1: ABORT before A processes the fence drops the released R-slice | **Fixed in notes** (COH §3.2 step 8: release waits for R3) | `FlipTest."FINDING FLIP-1 fixed ..."` |
| SWAP-1: `Swap`'s held buffer below `X` lets a duplicate pass `X` twice | **Fixed in notes** (COH §3.4: `P` parks) | `PromotionTest."FINDING SWAP-1 fixed ..."` |
| SWAP-2: T2 rollback after COMMIT resumes the incumbent on a superseded lane | **Fixed in notes** (rollback only before COMMIT) | `PromotionTest."FINDING SWAP-2 fixed ..."` |
| F9-X: `X`-suppressed replay does not re-derive an `Effectful` leaf's output | **Fixed in notes** (COH F9, PLP §5.8) | `PromotionTest."FINDING F9-X fixed ..."` |
| CELL-1: dedup on `applied` does not survive a succession RESTART | **Fixed in notes** (COH F12; PLP §5.3, §7) | `CellModelTest."FINDING CELL-1 fixed ..."` |
| B2: release before B acknowledges `Committed` | Fixed at `299d4e9c`; kept as a control | `FlipTest."B2 ..."` |
| REGION-1: "partial-diamond stall" | **Artefact, withdrawn** (§3 of the review); its configuration is reachable and holds | `RegionTest."REGION-1 withdrawn ..."` |
| OV-1 | **Artefact, withdrawn**; `P` outside `D` is now refuted | §3 |
| OV-2 | Stands, cost-only, witnessed | §3 |
| **REPLAY-1** | **New, open** | `CellModelTest."FINDING REPLAY-1 ..."` |

**Old-text counterexamples** (shortest, from `report.txt`):

```
FLIP-1 (abort releases before R3)      SWAP-2 (rollback after a T2 COMMIT)
1. R1 FlipBegin(p_begin=0)             1. PRECHECK  2. PREPARE  3. COMMIT (T2)
2. routes f1[r,a] (r parked)           4. R receives ReBaseline(e1)
3. A accepts f1[a]; 4. its STABLE ack  5. ROLLBACK from COMMITTED
5. R5 ABORT  6. ABORT to B             6. T accepts in1  7. R receives (1,0,1):p1
7. releases f1[r] to A                    -> fenced: output lost
8. A accepts it; 9. its STABLE ack
10. CRASH A: replay acts f1[a] (cursor 1), drops f1[r] -> (1,r) lost

SWAP-1 (Swap holds below X)            F9-X (outputs not logged)       CELL-1 (no X, applied only)
1. PRECHECK  2. PREPARE                1. T accepts in1 (acts, emits)  1. T accepts p1  2. R takes (50,0,1)
3. T accepts in1 (passes X, held)      2. X record for in1             3. U->T reconnect  4. RESTART
4. reconnect, U resends  5. accepts    3. CRASH: replay suppresses     5. T accepts p1 again (applied reset)
   in1 again (passes X again)             in1; output gone             6. ReBaseline(e50)  7. R takes (100,0,1)
6. ROLLBACK -> in1 acted twice                                            -> p1 taken twice
```

### REPLAY-1 (new, open): `P`'s release is not a `D` record

The notes have `D` log acceptance and control signals. `P`'s release of a parked frame is an
internal step that is not logged. A control record written after a live release is therefore
replayed before it:

```
1. SUSPEND  2. T accepts p1 (parked)  3. RESUME  4. P releases p1
5. R receives (50,0,1):p1             6. RESTART (journaled: epoch 100)
7. CRASH: replay re-parks p1, replays RESUME, then RESTART; p1 is released only after
8. P releases p1 -> re-derived as (100,0,1)  9. R: ReBaseline(e50)  10. R takes (100,0,1):p1
   -> downstream takes p1 twice
```

It needs `P`, a non-`Effectful` emitter (an `Effectful` one is protected by its `X` record)
and an epoch-rotating control between release and crash, so no projected scenario shows it;
only the full stack does. The same shape would reach the promotion gate (SWAP-1's fix) if a
RESTART landed between green and a release **[inference; RESTART during a swap is not
modelled]**. **Candidate fix, holding in the model**
(`Variant.logPRelease`, 126,897 states): `D` logs each release, so replay re-runs it at its
logged point. An alternative is that RESUME releases the whole park within its own turn, before
any later management-band signal. Not yet in the notes; it needs a decision on whether every
custody-holding layer's internal hand-on (`P` release, `A` wave release) is a `D` record.

---

## 5. Controls: does the checker see each defect?

Every control diverges. Minimal traces are in `report.txt`.

| Control | Diverges with |
|---|---|
| DISPUTES:1232 whole-invocation suppression | I4: acted input's state transition missing (M1 (a) holds) |
| computenet-wlwjw, `X` keyed on root wave | I3: second same-wave frame dropped |
| computenet-kxdjx, `dead` not checkpointed | I2: straggler acted again |
| `S` outside `D`, **crash without RESTART** | I5: re-minted identity, output taken twice (trace has CRASH, no RESTART) |
| `S` outside `D`, RESTART | I5 |
| Un-journaled RESTART epoch | I3: output fenced after the epoch reverts |
| CELL-1 old text (`DOPS`, no `X`) | I5 |
| REPLAY-1 notes' text | I5 |
| computenet-d2lue, volatile flip state | I3 at router crash |
| FLIP-1 old text | I3 |
| R6 advanced on send (review blocker 1) | I3 at a shard crash (trace has `CRASH shard`) |
| B2, release before `Committed` | I2 (B's scope installed after it acted on an R-slice) |
| Gainer `R` scope from A's high-water | I3 |
| One unscoped cursor | I3 |
| computenet-8g7kg, handoff acted | I2 |
| SWAP-1 old text | I2 |
| SWAP-2 old text | I3 downstream |
| F9-X old text | I3 downstream |
| computenet-lzfr0 | I3 downstream |
| Unlogged swap window | I3 downstream |
| Candidate dedup starts empty | I-P3 gap |
| Follower suppression advances `disposed`; no follower retention | I3 omission |
| Member-by-member region suspend | region atomicity |
| computenet-5jhg3, join drops partial waves | I3 arm custody |
| No source fence; activate at PREPARE | two live holders; frame outside holder of record |

---

## 6. Coverage gaps, stated precisely

- **Bounds.** Per-cell projections: ≤ 2 lanes, ≤ 2 frames per lane, one fault of each kind.
  Flip: 3 frames with one shard crash, 2 frames with one router crash; both parties crashing in
  one run only under walks (2 + 2 crashes). Promotion: 2 frames exhaustive, 5 under walks.
  Walks are not proofs: before the REPLAY-1 fix was applied to them, 200 seeds of the relay walk
  did not hit REPLAY-1.
- **Flip steps.** Default shard streams are `SYNCHRONOUS` (acceptance is the sync), and act and
  X record are one step; each is opened in its own exhaustive run (`BATCHED`, 2 frames;
  act window, 2 frames), not combined with each other or with a router crash. PRECHECK's veto
  conditions, a replicated shard, and step 9's stall report are not modelled. Liveness of an
  undecided flip is not checked.
- **Promotion.** RESTART and DESIGNATE during a swap, T0, the "adds `X`" and "removes `X`"
  rows of the effect-identity table, and rollback after RETIRE (a new swap) are not modelled.
- **Order validity.** Full stacks are exhaustive only for the canonical order and only under
  the six fault sets listed in §3; other model-valid orders are walked.
- **Not modelled at all.** `TAIL`/`UNKNOWN` gaps; recipient-keyed acks across shared lanes (B3);
  M11 (c) continuation and `Owned` re-consumption; coalescing; budget charges; couplings;
  partial replica overlap; volatile relocation; cross-host regions; crash during recovery.
