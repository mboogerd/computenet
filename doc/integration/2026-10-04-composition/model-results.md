# Executable model of the composition design: results

Status: results of the `:composition-model` module (`composition-model/`, package
`civictech.compmodel`), built for computenet-gl2i7. It checks the claims of
[per-link-positions.md](per-link-positions.md) (**PLP**) and
[composite-obligation-holders.md](composite-obligation-holders.md) (**COH**) mechanically.
It does not edit either note. Every number below is from one run of
`./gradlew :composition-model:test --rerun`, which writes them to
`composition-model/build/compmodel/report.txt`.

**Which text is modelled.** The model was written against the notes at `5820e5c1`. The notes
were then finalised at `299d4e9c` (round-2 review, see `reconciliation/*.md`). The model was
re-aligned where that was cheap:

| Experiment | Models | Differences from `299d4e9c` |
|---|---|---|
| Per-cell stack, order validity | `5820e5c1` = `299d4e9c` for F1-F6 | F2/F3 are now labelled conditional on M5/M16. The rule text the model transcribes did not change. |
| Flip | `299d4e9c` | Release waits for B's `Committed` ack (B2). Not modelled: R7 waiting for both drain acks. R2 is never truncated in the model, so this cannot change a loss verdict. The router→shard hand-off is a synchronous `STABLE` acceptance, so "R6 advances on `STABLE` ack" is what the model does. |
| Promotion | `5820e5c1` text of §3.4 | Unchanged in `299d4e9c`'s phase list. Not modelled: M3's "starting frontier = incumbent's `applied` in the COMMIT record". The model's `X` keeps the term's `disposed` frontier. |
| Replica set | `299d4e9c` | Total interest only. That matches M5: partial-overlap takeover is now refused. |
| RESTART | `299d4e9c` | Always a succession. That matches the final M11 for any term that can accept `Owned`/`Leased`, and the "until decided" fallback for all others. M11 (c) continuation is not modelled. |
| Relocation | `5820e5c1` = `299d4e9c` for a durable term | B4 (volatile term's capsule in the `Departing`/`Prepared` records) is not modelled. |

**Independent confirmations of round-2 review findings.** Before seeing the round-2 review,
the model found **B2**'s "release only after B acknowledges `Committed`": §4.2 has a minimal
trace in which an R-slice is lost without it. The model did not find B1, B3, B4 or M1-M6.
Each of those lies outside what is modelled: the model has no `BATCHED` stream, no
recipient-keyed acks, no volatile relocation, no `Owned` re-consumption under M11 (c), and
no `supersede=false` lane.

---

## 1. What is modelled, and at what abstraction

Each model is a deterministic transition system over immutable Kotlin data classes
(`check/Explorer.kt`). It has no wall clock and no threads. Time is just the order of
transitions. Faults and management actions are ordinary transitions with a per-run budget,
so a crash falls between every pair of protocol steps by construction.

The checker has two modes:

- **Exhaustive mode:** breadth-first search with state hashing. The first violation found
  is a shortest one.
- **Random-walk mode:** seeded walks for larger configurations. The seed count is set by
  `-Pcompmodel.seeds=N` (default 200); a failure prints its seed and full trace.

| Model element | Encodes | File |
|---|---|---|
| Layers `D O P F A X S`, properties table | COH §2.1, §2.5 | `cell/Vocabulary.kt` |
| Delivery position `(epoch, lane, seq)`, pull id, lane key | PLP §0, §3.2, §5.1, §5.4 | `cell/Vocabulary.kt` |
| Leaf kinds: `Effectful` sink, mergeable set, non-idempotent counter, relay, `Effectful`+`Stateful` | COH F8; PLP §5.3 | `cell/CellModel.kt` |
| Acceptance at the outermost layer; `D` appends before delivery and acks `STABLE` (`SYNCHRONOUS` stream) | COH §0.4, §2.3, H1; PLP §5.8, §6 | `CellModel.inward` |
| `X`: `disposed` per `(epoch, lane)`, `pullDischarged`, `dead`; gap check; advance after the handler; X record after the act | PLP §5.1, §5.3, §5.7; COH F3; [24-DUR-09] window | `CellModel.xCheck/xAdvance/act` |
| Crash: everything outside `D` is lost; inside `D`, checkpoint + all X records, then the tail replayed in order, before any upstream redelivery | COH §3.8 `NONE`, §4; PLP I-P2, I-P4 | `CellModel.crash/recoverInsideD` |
| RESTART as succession: resets the inside of `S`; `S` mints and journals a fresh epoch, announces `ReBaseline`; pull-baseline catch-up for mergeable leaves | COH §2.4 (M11 fallback); PLP §7; M14 | `CellModel.doRestart` |
| `P` suspend/resume (logged by `D` when outside `P`), `F` fence + `DESIGNATE`, `A` wave alignment (in wave order, one turn), `O` retention + resend | COH §2.4, §2.5, §3.1, §3.6; PLP §5.8 | `CellModel` |
| Upstream U: durable sender, retains until `STABLE`; lost acks / reconnects; optional succession | PLP §5.8, §5.7 | `CellModel` |
| Downstream R: exact per-position dedup, `dead` epochs, content-per-position check | PLP §5.3, P3 | `CellModel.rReceive` |
| Formation rules F1-F6 transcribed over the properties table; F8 | COH §2.6 | `cell/FormationCheck.kt` |
| Repartition flip: R1-R7, in-band fence/drained, scoped cursors at both shards, handoff not acted, abort | COH §3.2; PLP §5.6 | `composite/FlipModel.kt` |
| Promotion: `Swap` under `S`, PREPARE/COMMIT/RETIRE/rollback logged by `D`, T1/T2, effect identities | COH §3.4; PLP §7 | `composite/PromotionModel.kt` |
| Replica set: set-lane positions forwarded to both instances, follower `received`/retention, published `disposed`, takeover, exact witness | COH §3.1; PLP §3.2, §2 | `composite/ReplicaSetModel.kt` |
| Glitch-free region: atomic suspend, contagious veto, join capture on migrate | COH §3.3; spec 34:163-174 | `composite/RegionModel.kt` |
| Relocation H4: six steps, residency fence, crash table | COH §5.1 | `composite/RelocationModel.kt` |

**Invariants**, as named in the traces:

- **I1 (custody).** The custody invariant is checked in several forms:
  - an `Owned` payload is consumed at most once (plus the declared ceiling);
  - the fence never drops below an accepted `DESIGNATE`;
  - there are never two live holders;
  - a frame accepted at relocation is in the state of the holder of record.
- **I2 (effects).** An input position is acted at most `1 + (crashes inside its act→X-record
  window)` times. Replica failover adds 1 per unpublished acted position (COH §3.1, M9).
- **I3 (no silent loss).** Every accepted frame is checked at every state. It must be disposed
  (judged from ground truth: the world's effect log, the leaf state, or emitted outputs), or
  held somewhere live, or reported.
- **I4 (refinement).** At quiescence, a mergeable leaf's state equals the set of accepted
  inputs. An acted `Effectful`+`Stateful` input has its state transition.
- **I5 (positions).** A position is never re-issued for different content. A gap on a dense
  lane is a violation. Downstream never takes one output twice.
- **Glitch-freedom.** The frames that reach the leaf in one turn form complete waves.

**Not modelled.** These are coverage limits:

- `BATCHED` streams and `TAIL`/`UNKNOWN` gaps.
- Wire negotiation; acks keyed by recipient and scope (B3).
- `supersede=false`, closed-lane LRU and GC caps.
- Coalescing (E3).
- Budget charges (§3.7).
- Couplings (§3.5).
- `Owned` re-consumption by a durable RESTART: there is no M11 (c) continuation.
- The failing frame of a RESTART (RESTART is a spontaneous management fault).
- A crash during recovery.
- Partial replica interest; automatic claims; a fence-only witness.
- Volatile relocation (B4); a cross-host region (WAIT/DEGRADE).
- Catch-up at an `Effectful` leaf after RESTART (M14 pull baselines are modelled only for the mergeable leaf).
- Liveness. Stuck-but-held is accepted.

---

## 2. What was explored

The test suite has 48 tests in 9 classes, all passing. The test task took 26.2 s and the
whole Gradle build 32 s on the authoring machine.

| Experiment | Configuration | Result |
|---|---|---|
| Order validity, `effect-core` | `{D,P,X,S}`, `Effectful` leaf, 1 lane × 2 frames (first `Owned`); crash, lost ack, RESTART, SUSPEND, checkpoint ×1 each | 24 orders, 737,408 states; canonical 693,818 states, depth 22 |
| `merge-core` | same, mergeable leaf with catch-up | 24 orders, 1,076,369 states |
| `align` | `{D,P,A,X,S}`, 2 lanes × 1 wave | 120 orders, 1,765,418 states; canonical 1,567,918 states |
| `fence` | `{D,F,A,X,S}`, stale writer on lane 1, `DESIGNATE` | 120 orders, 77,822 states |
| `fence-dedup` | `{D,F,X,S}`, no waves | 24 orders, 195,520 states |
| `outbox` | `{D,O,X,S}`, relay leaf → R; reconnect on both links | 24 orders, 57,282 states |
| Projection spot check | 54 full 7-layer orders, combined scenario (all layers and faults), random walks | 39 refuted, every refutation matched by a refuted projection |
| Flip (`abortFencesFirst`) | 3 boundary frames `{r,a},{r,b},{r}`; router crash ×1, shard crash ×1, abort | 1,237,245 states, 4,414,076 transitions, depth 37, exhaustive, HOLDS |
| Promotion, `Effectful` T1 / T2 / T2 other identity | 3 frames, crash, lost ack, rollback | 90,612 / 203,566 / 122,088 states, all HOLD |
| Promotion, relay T1 / T2 | same | 473,704 / 392,825 states, HOLD |
| Replica set | 3 positions, leader death at any step | 1,268 states HOLDS; exact witness 1,148 HOLDS |
| Region / relocation | 2 waves / 2 frames, crash of either host at every step | 54 / 288 states, HOLD |
| Random walks (200 seeds each) | Canonical stack: 2 lanes × 3 waves; mergeable leaf with 2 lanes × 3 frames; relay with 4 frames. Each with crash ×3, reconnect ×3, RESTART ×2, suspend ×2, checkpoint ×3. Flip with 2+2 crashes; promotion with 5 frames; replica set with 5 positions | 33,117 steps, all HOLD |

---

## 3. Order validity

All 7! = 5040 orders of `{D,O,P,F,A,X,S}` are judged two ways:

- **By the model:** the conjunction of the exhaustive verdicts on the order's projection
  onto each scenario's layer subset. This is sound because a layer that a scenario does not
  exercise is a pass-through with constant state. The spot check above supports it
  empirically.
- **By F1-F6 as transcribed.**

| | Count | Orders |
|---|---|---|
| Formation-accepted | 8 | `DFOPAXS DFPAOXS DFPOAXS DOFPAXS DOPFAXS DPFAOXS DPFOAXS DPOFAXS` |
| Model-valid | 14 | the 8 above plus `DFPAXOS DPFAXOS PDFAOXS PDFAXOS PDFOAXS PDOFAXS` |
| Accepted by the rules but refuted by the model | **0** | Soundness holds: every order the rules accept keeps every invariant. |
| Valid in the model but refused by the rules | 6 | Findings OV-1 and OV-2 below |

The test pins the 6-order disagreement as data (`OrderValidityTest.expectedModelOnly`). A
model change that moves it fails the test.

What the model itself shows for each rule:

- **F1** holds for `X`, `F`, `O` and `S`. Each has a minimal counterexample:
  - `X` outside `D`: replay re-acts.
  - `F` outside `D`: the fence regresses below an accepted `DESIGNATE`.
  - `O` outside `D`: loss of output.
  - `S` outside `D`: identity re-mint, so downstream takes an output twice.
- **F2** holds for every layer inside `S`:
  - `X` inside `S`: the frontier is lost, a gap or a re-act follows.
  - `P` or `A` inside `S`: the acknowledged held work is lost.
  - `O` inside `S`: retention is lost.
  - `F` inside `S`: the fence regresses.
- **F3** holds where a *holding* layer (`P`, `A`) sits between `X` and the leaf. With `P`
  between them: a duplicate passes `X` while the original is parked, and the effect is acted
  twice.
- **F4** holds: with `P` inside `A`, `P` releases a completed wave one frame per turn, and
  the leaf sees a partial wave.
- **F5**, the "outside `A`" half, holds: with `F` inside `A`, a wave completed with a stale
  unit is delivered without it.

**OV-1. F1's custody half is not falsified for `P` or `A`.**
- `PDXS` holds in `effect-core` and `merge-core`, and `PDAXS`/`PADXS` hold in `align`. A
  frame held in `P` or `A` outside `D` is acknowledged only `HELD`. Its sender keeps it until
  `D` acknowledges `STABLE` (PLP §5.8), so a crash loses nothing.
- F1's stated reason, "anything accepted outside `D` is lost by a crash while the log claims
  durability", does not apply: the log claims nothing for that frame.
- What *is* lost is `P`'s suspension state and the prompt `STABLE` acknowledgement. Those are
  liveness and management properties, which this model does not check.
- So F1 is necessary for the monotone-state layers. For custody it is a cost and latency rule
  under PLP's required acknowledgement level, not a correctness rule.

**OV-2. F3 is stated positionally, but its argument covers only holding layers.**
- `DPFAXOS` and `DFPAXOS` hold: `O` sits between `X` and `S`, and `O` does not hold inbound
  frames.
- Likewise, `DXFS` holds in `fence-dedup`: `F` sits inside `X`. So F5's "outside `X`" half is
  not independently necessary. In the full stack it follows from F3 together with F5's
  "outside `A`" half, because `A` must be outside `X`.
- Harmless, since the canonical stack is unique. It is worth stating F3 as "no layer that
  holds inbound frames between `X` and the leaf".

---

## 4. Findings

Each finding below has a shortest counterexample, and each is a test, so the model
reproduces it on every run.

### 4.1 FLIP-1: abort before the fence reaches A drops a parked R-slice

Test: `FlipTest."FINDING FLIP-1 ..."`. The finding still holds at `299d4e9c`.

COH §3.2 step 8 releases parked frames to A "checking them against its `R` scope (frozen at
`p_begin`)". A has an `R` scope only once it has processed `FlipFence`. If the decision is
ABORT before that, A checks the released R-slice against its single cursor. That cursor
already passed the frame's *stable* slice, so the R-slice is dropped as a duplicate:

```
1. router: R1 FlipBegin(p_begin=0)
2. router accepts and routes f1[r, a]      (a-slice to A now, r-slice parked)
3. A acts on f1[a]
4. A writes X record for [(1, a)]
5. router: R5 FlipDecision(ABORT)
6. router sends ABORT to B, UNSETTLE to A
7. router releases parked f1[r] to A (R6 1)
8. A drops f1[r] as duplicate (cursor 1)   -> (1, r) never acted: silent loss
```

Fix modelled as `FlipVariant.abortFencesFirst`: on ABORT, if A has not settled, the router
first places the in-band `FlipFence(p_begin)` on A's path. With it, the whole flip holds: the
exhaustive run (1.24 M states) covers crashes at every step on both sides. The note's test
`abortReleasesParkedToLoserAgainstRScope` assumes A's stable cursor is "already above
`p_begin`". It should also cover abort before the fence.

### 4.2 B2, confirmed independently: release before B acknowledges `Committed`

Test: `FlipTest."B2 confirmed ..."`. This finding applies to the `5820e5c1` text only; it is
fixed in `299d4e9c`.

If COMMIT travels on a channel that does not order it against the router's data path to B
(B is remote: `assignInlet` and `routeInlet` are different lanes), then releasing parked
frames right after *sending* COMMIT loses a slice:

```
...  5. router accepts and routes f2[r, b]   6. B acts on f2[b]   (B's cursor = 2)
... 13. router sends COMMIT to B, SHED to A
    14. router releases parked f1[r] to B (R6 1)
    15. B drops f1[r] as duplicate (cursor 2)   -> COMMIT (R scope) not yet installed at B
```

Two configurations hold:

- release after B's `Committed` ack (`299d4e9c` step 7);
- COMMIT on a preempting management band (`flip/preempting-control`).

### 4.3 SWAP-1: `Swap`'s held buffer sits between `X` and the leaf

Test: `PromotionTest."FINDING SWAP-1 ..."`. The finding still holds at `299d4e9c`.

COH §3.4 step 2 says `Swap` "parks inbound frames in its `held`", and `Swap` is inserted
*inside* `S`, below `X`. That is exactly the shape F3 refuses ("a holding layer between `X`
and the leaf"). A frame passes `X`, waits in `held`, and its retransmitted duplicate passes
`X` too:

```
1. PROMOTE: PRECHECK + PREPARE (Swap parks inbound)
2. T accepts in1                             (passes X, held)
3. U->T reconnect: acks lost, U resends retained
4. T accepts in1                             (passes X again, held)
5. ROLLBACK before RETIRE                    -> in1 acted twice
```

Either of two fixes holds. Both are modelled as `recheckOnRelease`:

- `X` re-checks frames released from `held`;
- `P`, which COH §3.4 says "provides the gate", does the parking outside `X`.

With the fix, every promotion run in §2 holds.

### 4.4 SWAP-2: rollback between COMMIT and RETIRE of a T2 swap

Test: `PromotionTest."FINDING SWAP-2 ..."`. The finding still holds at `299d4e9c`.

COH §3.4 allows "Rollback. Before RETIRE". After a T2 COMMIT, `S` has already announced
`ReBaseline(supersede = true)` for the incumbent's lane. Rolling back resumes the incumbent
on that lane, and downstream fences its outputs:

```
1. PROMOTE (PREPARE)   2. T accepts in1 (held)   3. COMMIT (T2)
4. R receives ReBaseline(e1)
5. ROLLBACK before RETIRE (held released to the incumbent on e1)
6. R receives (1,0,1):p1                  -> fenced as a dead-epoch straggler: output lost
```

The same configuration without rollback-after-COMMIT holds (392,825 states). Possible
remedies:

- after a T2 COMMIT, rollback is itself a succession (another fresh epoch);
- or rollback is restricted to before COMMIT.

### 4.5 F9-X: an `X`-suppressed replay does not re-derive an `Effectful` leaf's emissions

Test: `PromotionTest."FINDING F9-X ..."`. The finding still holds at `299d4e9c`, PLP §5.8.

F9 and PLP §5.8 let a replay-deterministic sender skip persist-before-transmit. Its
"determining record is then the input record whose replay re-derives the frame". At an
`Effectful` inlet, replay *suppresses* an already-disposed input (PLP §5.3 "Replay-derived
frames"; [24-DUR-05]), so the emission is never re-derived. A stateless `Effectful` leaf
with an outlet is admitted by F8, and it loses its unacknowledged output on a crash:

```
1. T accepts in1 (acts, emits output, not yet acknowledged)
2. X record for in1
3. CRASH and recover from D               -> replay suppresses in1; output gone
```

This is the output-side twin of DISPUTES:1232. Either of two changes closes it:

- F9's exemption requires that no inlet on the leaf is `X`-suppressed;
- or such a leaf logs its output.

### 4.6 CELL-1: dedup on `applied` does not survive a succession RESTART

Test: `CellModelTest."FINDING CELL-1 ..."`. The finding still holds at `299d4e9c`, PLP §5.3
and §7.

PLP §5.3 makes every inlet of a durable cell that is not declared idempotent enforce on
`applied`. PLP §7 resets `applied` on a succession RESTART. A duplicate retransmitted after
the RESTART is absorbed again and re-emitted under the fresh epoch, and downstream takes it
twice:

```
1. T accepts (1,0,1):p1        2. R receives (50,0,1):p1
3. U->T link reconnect (ack lost)
4. RESTART (succession)        5. T accepts (1,0,1):p1  (applied was reset)
6. R receives ReBaseline(e50)  7. R receives (100,0,1):p1   -> taken twice
```

Adding `X` (outside `S`) to that term closes it (`DOPXS` holds). The fix is one of:

- non-idempotent durable inlets that can RESTART by succession carry `X` and enforce on
  `disposed`;
- or the duplicate is declared, beside "Durable RESTART until M11".

### 4.7 REGION-1: an atomic region suspend does not exclude a partial-diamond stall

Test: `RegionTest."FINDING REGION-1 ..."`. This contradicts spec 34:166-168, and the finding
still holds at `299d4e9c`.

Spec 34 says the region "suspends atomically ... (a partial-diamond stall cannot exist by
construction)". COH §3.3 realises this as delivering SUSPEND to every member's `P` in one
management turn. That excludes *interleaved* delivery. It does not settle work an arm has
already forwarded:

```
1. m1 forwards wave 1 to J     (J's A holds a partial wave; m2's arm still queued)
2. SUSPEND region atomically    -> region parked with a partial wave at J
```

Nothing is lost, because `A` holds the partial wave in custody and migrate carries it.
`region/design` holds that. But the claim is false. A wave-boundary precondition makes the
claim true (`suspendAtWaveBoundary`, which holds): suspend only when J holds no partial wave
and the members' heads are aligned. That precondition is a DRAIN of the region's open waves,
not just atomic delivery.

---

## 5. Controls: does the checker see each defect?

Every control the brief lists diverges. A minimal trace for each is in `report.txt`.

| Control | Models | Diverges? | Violation |
|---|---|---|---|
| DISPUTES:1232 whole-invocation suppression | `Effectful`+`Stateful` leaf under `D` | **yes** | I4: acted input's state transition missing after replay. M1 (a) "suppress only the act" holds. |
| computenet-wlwjw | `X` keyed on root wave per port | **yes** | I3: second same-wave frame dropped |
| computenet-d2lue | volatile flip state | **yes** | I3: parked R-slice lost at router crash (3 steps) |
| computenet-kxdjx | `dead` omitted from checkpoint | **yes** | I2: dead-epoch straggler acted again after checkpoint + crash |
| `Supervised` outside `Durable` | order `SDOPFAX` | **yes** | I5: downstream takes an output twice (identity re-minted) |
| computenet-lzfr0 | T2 `ReBaseline` supersedes the candidate's lane | **yes** | I3: candidate's outputs fenced downstream |
| computenet-8g7kg | handoff state acted as catch-up | **yes** | I2: moved range re-fired at B |
| Gainer `R` scope from A's high-water | PLP §5.6 step 5 | **yes** | I3 |
| One unscoped cursor | PLP §5.6 step 4 | **yes** | I3 |
| computenet-5jhg3 | join drops partial waves on migrate | **yes** | I3 |
| Un-journaled RESTART epoch | migration step 5 | **yes** | I3: output fenced after crash reverts the epoch |
| Unlogged swap window | `Evolution.kt:268-269` | **yes**, relay leaf | I3. For an `Effectful`-only leaf it does **not** diverge: `X` alone keeps effects exact, so the window matters only for lanes. |
| Candidate dedup starts empty | COH §3.4 table | **yes** | I-P3 gap at `X` |
| Follower suppression advances `disposed` | PLP §2 | **yes** | I3 omission at takeover |
| No follower retention | COH §3.1 | **yes** | I3 omission |
| Member-by-member region suspend | COH §3.3 | **yes** | atomicity |
| No source fence / activate at PREPARE | H4 | **yes** | two live holders / frame outside the holder of record |

The model cannot see three things:

- the cost-only side of F1 for `P`/`A` (OV-1);
- `O`'s exact position inside `D` (OV-2);
- the F5 "outside `X`" half in isolation.

---

## 6. Coverage gaps, stated plainly

The model is bounded:

- per-cell runs use 2 frames per lane, at most 2 lanes, and one fault of each kind;
- the flip uses 3 boundary frames on one client lane;
- promotion, replica set and region use 3 frames or waves;
- random walks extend these, but they are not proofs.

The projection argument for order validity is sound for the model as written. It assumes
that no layer interacts with traffic its scenario lacks.

Section 1 lists what is not modelled. The most consequential gaps:

- `BATCHED`/`TAIL` behaviour (B1);
- recipient-keyed acknowledgements (B3);
- M11 (c) continuation and its `Owned` interaction;
- the flip's R7 waiting on drain acks;
- partial replica overlap. The final notes refuse it.
