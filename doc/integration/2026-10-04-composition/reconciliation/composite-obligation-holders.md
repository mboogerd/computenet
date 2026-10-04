# Reconciliation: composite obligation holders

Process record for `../composite-obligation-holders.md` (the "note"). It is not implementation
guidance: every operative rule is in the note. Pinned to `8d71ea64`.

## 1. Review response

Review: adversarial review of the note's first draft ("d2 review"). Findings from the companion
note's review ("d1 review", on `per-link-positions.md`) that touch this note are listed after it.
Section references are to the revised note.

| # | Finding | Severity | Disposition | Evidence checked | Where |
|---|---|---|---|---|---|
| 1 | A follower's suppressed delivery advanced the effect frontier, so a position only a follower received could be skipped forever after takeover | blocker | **Fixed.** Three cursors: *received* (follower warm progress), *disposed* (durable terminal disposition), *acted*. A suppressed delivery advances only *received*. The leader publishes *disposed* after it is `STABLE`; followers retain every suppressed frame above the folded frontier; takeover requires gapless retention and acts on everything above the frontier. Added a precondition that a replica set's inbound lanes are set-keyed, without which no frontier is comparable across instances. | Followers suppress, not act (31:398-410); duplicate preferred to omission (24:1503-1510) | §1, §3.1 |
| 2 | The witness fenced stale tokens but did not dedup positions, so "exactly-once across a partition" was false | blocker | **Fixed by splitting the claim.** (a) An *exact* witness fences `(epoch, writer)` and rejects a logical position applied under any epoch, atomically: exactly-once at that destination. (b) A fence-only witness keeps the one-duplicate-per-unpublished-position ceiling. (c) Declared ceiling. | 24:1488-1522 | §3.1, §6, M9 |
| 3 | A per-link high-water cannot be sliced by key range; exporting the old owner's high-water drops parked frames | blocker | **Fixed.** Slices of `X` are removed. The router records `p_begin` per boundary lane in `FlipBegin`; the old owner must report a stable disposition through `p_begin` before the commit decision; the gainer dedups the moving range against a range-scoped frontier starting at `p_begin`, folded at `FlipDrained`. These are PRECHECK/commit preconditions. | PLP §5.6 (`p_begin`, not A's high-water); PSS:151-174, 211-258 | §3.2 |
| 4 | Only `FlipBegin`/`FlipEnd` were logged; recovery could not tell whether the gainer committed, nor resume a partial release | blocker | **Fixed.** A flip is a durable transaction: `TransferId`, records R1-R7 including the persisted router decision (R5) and a per-command release cursor (R6); idempotent `SETTLE`/`PREPARE`/`COMMIT`/`SHED`/`ABORT` on both shards; a recovery table for a crash after every record. The rule changes `[24-PART-04]` and `[24-SHARD-03]`, so §3.2 is now **conditional on M6**. | PSS:110-118 (volatile flip state); 24:951-962, 24:1624-1631 | §3.0, §3.2, M6 |
| 5 | Relocation committed the target before fencing the source; a source host crash could re-create a second live holder | blocker | **Fixed.** H4 is now a transfer protocol: source capsule stable, residency entry fenced `Departing(tx)` and synced *before* the target is prepared, `Departed(tx)` as the commit point, target activation and versioned route publication only after `COMMIT`, idempotent retirement, and a crash table for both sides. A fenced residency entry is never re-created live (H3). | 33:85-88 (`[33-MOVE-01]`) | §5.1 H3/H4, §5.2, M2 |
| 6 | Budget charges had no obligation protocol | major | **Fixed in design, limitation until ledger support.** Charge id from the delivery position; intent → charge → outcome records in `D`; replay rules; refusal and keyed refund; open charges in the capsule. Until `BudgetLedger` offers keyed durable idempotency and `refund(id)`, one duplicate debit per crash per in-flight charge is declared, reported. The ledger API change is **open** (ECO1). | `Budget.kt:69-75` (bounded key window), `:85-130` (`charge`, closure `undo`) | §3.7, §6, step 13 |
| 7 | Promotion transferred effect history without checking that the candidate performs the same act | major | **Fixed.** Effect identity is a declared manifest property (F11). PRECHECK compares identities: equal keeps acted history; differing or undeclared vetoes unless `effectFrom = COMMIT`; an added `X` starts at the current *disposed* frontier, never empty. The leaf's fresh state frontier is separated from acted history. | PLP §7 T2 row | §2.6 F11, §3.4, M8 |
| 8 | Durable RESTART "kept R6" while replaying the tail, which R6 forbids | major | **Moved to maintainer decision M11**, with options and a recommended *exclusive barrier* rule (checkpoint after every exclusive-consuming delivery; replay the tail; skip the failing frame; discharge its payload). Fallback until decided: durable RESTART is a succession. Named test added. | 23:209-213, 93:8416 (R6); 31:127-130, 93:2867-2870 (R9); MH:1998-2000 | §2.4, §6, M11, §9 |
| 9 | An Outbox released frames on a volatile receiver's in-memory acceptance | major | **Fixed.** Acknowledgement levels `HELD < APPENDED < STABLE < DISPOSED`, required level negotiated per link; a volatile receiver is admitted only with a declared per-link ceiling and no durable-delivery claim. Also added persist-before-transmit and unlink/death disposition (d1 review item 3). | §2.6 F1/§3.8 volatile `ALL` gap | §1, §3.6, §6 |
| 10 | H1 said `append` acknowledged after sync, and the ceiling claimed a `syncEvery − 1` loss bound; `BatchedFileJournal` acknowledges before fsync and disclaims a physical bound | major | **Fixed.** `append` acknowledges ordered acceptance (`APPENDED`); `sync`/`stableThrough` report stability; `syncEvery − 1` is stated only for a process kill, with no physical bound (`[KBLK-26]`). `TAIL` gaps drive succession. | `BatchedFileJournal.kt:13-16, 37-42, 69-73` | §5.1 H1, §3.8, §6 |
| 11 | The route-park exception E1 was larger than needed | minor | **Fixed.** Sender custody is the term's `O` or an external caller's client stub; the host's route keeps lookup and `Located` notices and holds no frame. The exception is withdrawn; former E2/E3 renumbered E1/E2. | 33:42 ("link/proxy internals — never cell logic"); `LocationRegistry.kt:71, 463-480` | §3.6, §5.3 |
| 12 | The reconciliation appendix was process history | minor | **Fixed.** Moved here. | – | this file |

**Rejected findings:** none. Every finding's evidence held at the pin.

**D1-review findings that touch this note.**

| D1 finding | Disposition here |
|---|---|
| 1. A boundary frame split across a stable and a moving range reaches one inlet twice with one position | Adopted for the partition set: during an open flip, delivery identity at a shard is `(position, range part)` and the frontier is kept per `(lane, range part)` (§3.2). The general child-delivery identity remains the companion's to define. |
| 3. Async output sent before sender custody is durable; unlink/death leave unacknowledged frames unassigned | Adopted: persist-before-transmit, `TAIL` → succession so a lost-tail position is never re-issued, and an explicit disposition at unlink, peer death and retirement (§3.6). |
| 4. Pull-merge (`supersede = false`) must not be fenced | Adopted: only `supersede = true` retires an epoch (§1). Every succession in this note announces `supersede = true`. |
| 5. Host coalescing loses positions and is cell-semantic | Adopted: coalescing moves into `P`, carrying the merged position set (§4). |
| 6. A refusal must advance a contiguous cursor | Adopted: the `disposed` cursor covers acted and refused frames; `acted` is a separate record (§1). |
| 9. Repartition protocol changes are maintainer decisions | Adopted: §3.2 conditional on M6, which names both clauses. |

## 2. Merge of the two original designs

The note's first draft merged two independent designs of the same problem. Decisions that still
shape a rule:

1. **Grammar.** Named layers and composites, with formation rules over declared layer properties so
   a new layer can be placed (§2.1, §2.5).
2. **Primitive.** The layer seam carries the capsule and adds staged `prepare/commit/abort`; the
   capsule took `ref`, descriptor version and port manifest from the second design.
3. **Supervised placement.** Positional rule F2 chosen over a declared reset target, because the
   check need not trust a declaration; the brief's `Durable(Supervised(Suspendable(cell)))` is
   normalised to its valid form.
4. **Effectful × Stateful.** One design decided decomposition, the other kept it open; the dispute
   is undecided by its own text (DIS:1254-1266), so it is M1 with an interim refusal.
5. **Replica failover.** The spec default "no automatic claim" (93:9748-9751) is kept; the opt-in
   claim needs a witness or a declared ceiling (revised by review items 1-2).
6. **Departure states.** The *suspended* state, omitted by one design, restored from `[42-WM-08]`.
7. **Partial-wave custody** to the join's `A` (one holder), not the region.
8. **Promotion form.** `Swap` under `S` with no relinks; rollback after RETIRE from
   `[53-ROLLBACK-02]`.
9. **Couplings.** Formation-time disposition and a logged release record; the second design's veto
   of migration with a half-complete group rejected, because the capsule carries the unit whole.
10. **Gap report.** `NONE/TAIL/ALL` plus `UNKNOWN`.
11. **Host and ownership.** "The host knows no ownership mode" rejected: the dead-letter store must
    discharge exclusives (`DeadLetters.kt:302-316`).
12. **Budget.** Spawn admission stays in the host; flow-time charges belong to the term (extended by
    review item 6).
13. **Attention parks.** Custody to `P`, the run decision stays with the scheduler.

## 3. Citation corrections made during the merge

- G-17 is at MH:2260-2272; the brief's `MH ~2193-2201` is the budget and quota ascent.
- `CompositeCell.mediate` is at `:194-226` (179-193 is its KDoc); `flatten` at `:146-160`.
- "Supervision always exists" is 31:197-201 (PROPAGATE default), not 31:200-204.
- `promoteReplica`'s T2 refusal is at `Evolution.kt:302-346`; `suspendedCells` at MH:476.
- Spot-checked and held at `8d71ea64`: `Evolution.kt:246-248, 268-269`; PSS:64, 110-118;
  `TrafficLightCell.kt:48`; HD:651-660; `SingleWriterReplication.kt:138-145, 368-382`;
  93:2790-2808, 2849-2854, 2867-2870, 2881-2888, 4183, 4584-4590, 4911, 8416, 9744-9764,
  11487-11511; 23:209-213; 24:951-962, 1488-1522, 1624-1631; 31:98, 127-130, 197-203, 398-419;
  33:18-32, 42, 85-88; 34:163-185; 42:418-424, 873-880, 910-914; DIS:1232-1268, 1387-1391,
  2236-2237; `BatchedFileJournal.kt:13-16, 37-42, 69-73`; `Budget.kt:69-75, 85-130`;
  `IntakeControl.kt:73-92`; `HELD_LEASE` absent from `kernel/src/main`.

## 4. Alignment with `per-link-positions.md` (after both round-3 revisions)

Ownership and numbering as recorded in the companion's reconciliation file, "Alignment":
this note owns layers, formation rules, composites, flip and relocation transactions and the
host contract; PLP owns positions, lanes, cursors, acknowledgement levels and `O`'s retention
rules. Decisions are M1-M16 across both notes (M1-M11 here, M12-M16 in PLP §10).

Items raised against this note:

| # | Item | Status before | Change in this note |
|---|---|---|---|
| 1 | §3.2 slices the losing shard's frontier "labelled R" | **Already resolved**: slices were gone; `p_begin`, settlement and range-scoped frontiers were present | Range parts `{stable, moving-in, moving-out}` replaced by PLP's scopes `stable`/`R` at both shards. **Changed:** `SETTLE` now travels in band as `FlipFence(tx)`: as written (a control-plane message, with A settling once every R-delivery at or below `p_begin` is disposed) A could not decide, because its boundary lanes are filtered and non-dense, and the management band preempts data. Reply renamed `FlipSettled(tx)`. Test `settleFenceTravelsInBandBehindRoutedFrames` added |
| 2 | Capsule keys frontiers by `LinkId` | Open (verified `Link.kt:116`: random per instance) | `frontiers: Map<PortName, DeliveryFrontier>` keyed `(epoch, lane)` plus scope; `outbox` keyed by lane; `ports` by endpoint address |
| 3 | Follower suppression advanced `disposed` | **Already resolved** (§3.1 `received` only) | Now refers to PLP §2/§5.1 for the rule |
| 4 | `O` released on `accept` with optional `D` | **Already resolved** (levels in §1/§3.6) | Level definitions moved to PLP §5.8; §1 and §3.6 refer |
| 5 | Persist-before-transmit and unlink/death table | **Already resolved** in substance (prose) | Owned by PLP §5.8's table, which took this note's substance where the two differed (re-created receiver still served; `discard`; sender relocation; exhaustion stalls). §3.6 now summarises and refers; `OutboxTest` trimmed to custody cases, retention cases live in PLP's `DeliveryAckRetentionTest` |
| 6 | Host coalescing missing from host exceptions | Open: §4 moved it to `P` with no interim | §5.3 adds **E3 (transitional)**, bounded by PLP §5.3 until migration step 7; §0, §4, §8 and the arch test allow-list updated |
| 7 | Flip protocol vs PLP decision 1 | Open (two numbers for one decision) | Unified as **M6**, argued here; PLP refers. M6 now notes PLP §5.6/§5.8 depend on it; M11 notes PLP §7's RESTART row follows it |

Also changed here: §1 vocabulary block replaced by a pointer to PLP (cursors, levels,
scopes); §3.1 set-lane precondition now cites PLP §3.2 and marks the partial-overlap takeover
check open; §3.4 uses `applied` for the leaf's frontier; §7 lists M12-M16 with PLP's
recommendations; migration steps 7, 8, 10, 12 cross-reference PLP's steps.

No genuine design conflict remained after this alignment.

## Round 2 review response

| Finding | Disposition | Location |
|---|---|---|
| B1 — write-before-transmit not durable on a batched sender | **Fixed in PLP §5.8** (stable before transmit); summary updated here | §3.6; §6 refusals |
| B2 — R6/R7 record release, not disposition; fence/drain band contradiction | **Fixed.** R6 = shard-acknowledged `STABLE` cursor; R7 after both shards ack `FlipDrained`; R2 kept until then; release starts after B acks `Committed`; fence and drain are barriers over prior dispositions, scheduled as PLP §5.8 defines (H8 now says in-band markers are not management band) | §3.2 records, steps 3, 7, 8, recovery table; §5.1 H8; tests in `PartitionSetFlipRecoveryTest` |
| B3 — ack/capsule keyed by lane only | **Fixed in PLP §5.8**; capsule `outbox` keyed `(epoch, lane, recipient, scope)` | §2.3; §3.6 |
| B4 — volatile relocation loses the capsule at steps 2-3 | **Fixed.** The synced `Departing` and `Prepared` records carry the opaque capsule for a term without `D`; abort resumes from it; the target deletes its staged copy before first admission. Added to H1 precisely | §5.1 H1, H4 text and crash table; tests `volatileSourceCrashAfterDepartingResumesFromStagedCapsule`, `volatileTargetDeletesStagedCopyBeforeFirstAdmission` |
| M1 — exclusive barrier after the irreversible act | **Demoted to limitation.** The barrier is withdrawn; M11 (c) now makes durable RESTART a continuation only for terms that accept no `Owned`/`Leased` (and declare no `LeaseHolding`); every other durable RESTART stays a succession | §2.4; §6 ceilings and open; §7 M11; tests |
| M2 — charge id from an ordinal | **Fixed.** `(term ref, position, declared claim site)`, charged at acceptance before the leaf runs; unpositioned frames charged unkeyed under the ceiling; order marked conditional on the keyed ledger | §3.7; §6; test `chargeIdUnchangedWhenReplayTakesOtherBranch` |
| M3 — added `X` reads a frontier that does not exist; F11 strings prove nothing | **Fixed.** Starting frontier = incumbent's captured `applied` (PLP §5.1) logged in the COMMIT record, veto if any incident lane lacks it; kept history requires a per-version effect-compatibility assertion | §3.4 text and table; §6; M8 text; tests |
| M5 — partial-interest takeover claimed "no omission" | **Demoted to refusal.** Takeover requires Total coverage; partial overlap refused (coverage certificate **open**); "no omission" now conditional on M9 and scoped to Total coverage | §3.1; §6; §7 M9; test `takeoverRefusedUnderPartialOverlap` |
| §1 partials COH 1, 4, 5, 6, 7, 8 | Resolved by M5, B2, B4, M2, M3, M1 respectively | as above |
| §3 conditional labels | Headings now name the pending decision: §2.3, §2.6, §5.1 (M3/M4), §2.4 `S` (M5), H4 (M2), §3.3 (M7), §3.4 logging (M8), §3.1 takeover (M9); §3.2 already M6 | headings listed |
| Rejected | None. M1's reviewer-preferred alternative (one recoverable consume-and-dispose step) was not designed: no sound form was found within scope, so the refusal branch was taken | — |
