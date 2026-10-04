# Reconciliation notes: exact per-link delivery positions

Companion to [`../per-link-positions.md`](../per-link-positions.md). This file is process
history, not implementation guidance: the note is self-contained without it.

## Review response (adversarial review, round 3)

Every finding was checked against the repository at the pin before it was disposed of.

| # | Finding | Verified? | Disposition | Where in the note |
|---|---|---|---|---|
| 1 | Blocker: a repartition boundary frame split into a stable slice (sent now) and a moving slice (parked) reaches one gaining shard twice with one position | Yes: `PSS:151-160` splits, `PSS:258` re-emits to the same shard set | **Fixed.** Each slice is checked against one frontier *scope* (`R` or `rest`) at the gainer; scoped cursors are journaled with the frontier; handoff and drain proof redone; P1 and I-L1 restated per scope | §2 P1; §3.2 I-L1; §5.6 steps 4-6; tests `splitBoundaryFrameBothSlicesTakenAtGainer`, `crashBetweenSlicesKeepsBothDispositions` |
| 2 | Blocker: `ActorIngress` assigns one position to every invocation of a `drive` block; its default `ingressPort` is random | Yes: `HCP:156`, `HCP:166`, `HCP:180-184` | **Fixed.** One `seq` per hosted invocation at the proxy send boundary, sharing one wave; replay-stable ingress lane (derived from `actorId` or persisted); lane declared non-dense | §5.2 rule 6; L10; tests `ActorIngressPositionTest.*` |
| 3 | Blocker: async retention has no persist-before-transmit; unlink/death releases unacknowledged frames | Yes: the earlier text said "in its checkpoint" only; 13:202-205 `[13-NOLOSS-01]` | **Fixed.** `O` appends `Retained(position, frame)` to `D` before transmit, carrying the counter advance; exception for senders declaring replay determinism (journal tail re-derives). Explicit disposition table for unlink, unreachable, dead-and-re-created, retired | §5.8 "Retention", "Unacknowledged frames"; §9; tests `crashAfterTransmitBeforeCheckpointResends`, `unlinkUnreachableKeepsRetention`, `retiredReceiverDeadLettersWithReport` |
| 4 | Blocker: the dead-epoch rule fences pull-merge traffic | Yes: `MessageContext.kt:75-91`; 93:8397-8415 (R4, R5) | **Fixed.** Retire into `dead` only on `supersede=true`; on `supersede=false` entries move to `closed` and stragglers are still disposed | §5.7; §9; test `PullMergeRebaselineTest.oldEpochStragglerStillMerged` |
| 5 | Major: coalescing loses positions; "at most one per lane" is false; host coalescing is cell semantics | Yes: `IntakeControl.kt:73-91` merges same wave + `sourcePort`, which two consecutive frames of one lane share; Coalesce is the default policy (`IntakeSaturation.kt:14`). Further found: merging into a non-latest entry reorders the lane | **Fixed and bounded.** Merge only where no later queued entry shares a lane (contiguous range per lane); carry `Map<lane, LongRange>`; refused at enforcing inlets (the host cannot see the frontier). Named as a host exception; moving it into a cell layer is a maintainer decision | §5.3 "Coalescing"; §6; §10 decision 5; tests `CoalescePositionTest.*` |
| 6 | Major: a refusal leaves the `Effectful` high-water unable to accept the next frame | Yes, by the earlier definitions | **Fixed.** One contiguous `disposed` cursor (acted and terminally refused/dropped/dead-lettered) in `X`; `applied` inner; optional `acts` record where an act must be distinguished. Durability per disposition tabulated | §2 terms; §5.1; §5.3 table; tests `DisposedCursorTest.*` |
| 7 | Major: `AtomicLong` does not give FIFO under concurrent emission | Yes: `FO:305-319` invokes without a lock; intake serializes after the stamp (`MH:1275-1281`) | **Fixed.** Allocation serialized with acceptance under a per-lane lock, `seq` returned on acceptance failure; direct synchronous targets are track-only and may not front an enforcing inlet | §5.2 rule 2; I-P2; L11; test `LinkPositionConcurrencyTest.pauseBetweenAllocateAndSendKeepsFifo` |
| 8 | Major: the `positions` capability had no old-peer-decodable carrier and no reverse signal | Yes: `WC:303-320` throws on populated unknown keys; nothing in the earlier text answered the reverse leg | **Fixed.** Offer in `WireFrame.natures` (decoded by old peers, ignored on data frames `WC:656-671`, unknown axis skipped by `natureVectorFromWire`, `ContractDescriptor.kt:242-254`); `PositionsAccept` only in reply to an Offer; in-band `PositionsBegin` with retroactive positioning of held pre-begin frames. First frames shown for new→old, old→new, new→new | §5.8 "Wire negotiation"; L13; tests `WirePositionCompatTest.*` |
| 9 | Major: the repartition protocol changes `[24-PART-04]` and `[24-SHARD-03]` but was marked decided | Yes: 24:958-962, 24:1628-1631 | **Moved to maintainer decision.** §5.6 repartition is now [conditional]; §10 decision 1 names both clauses with options and a recommendation | §0; §5.6; §10 decision 1 |
| 10 | Minor: the reconciliation appendix obscures the contract | — | **Fixed.** Moved here; the note ends with a pointer | this file |

**Rejected findings:** none outright. One part of finding 3 was overstated: a sender that
replays deterministically does not lose a frame transmitted after its last checkpoint,
because replay re-derives it with the same position. The note now states that exception
explicitly instead of requiring a per-frame append from every sender.

**Found while revising** (not raised by the review):

- Cross-node edges are installed with `PortRef.generate()` as the consumer key
  (`runtime/src/main/kotlin/civictech/runtime/Runtime.kt:77-84`), so a lane keyed on the
  entry's `PortRef` would be volatile for every runtime-built remote link. Lane keys are now
  derived from the target address (§3.2).
- Same-lane coalescing into a non-latest queued entry reorders the lane (folded into
  finding 5).
- T2 promotion inherited acted history without an effect-identity check (raised against the
  companion note, review item 7; applied here in §7).

## Cross-note alignment with `composite-obligation-holders.md`

Terms this note adopts from the companion: `Durable` (`D`), `Outbox` (`O`), `EffectDedup`
(`X`), capsule, acceptance. Terms the companion should adopt from this note: *delivery
position* `(epoch, lane, seq | pull)`, *disposed* cursor (vs *applied*), acknowledgement
levels `HELD`/`DURABLE`, frontier *scope*.

Remaining inconsistencies, to be fixed in the companion note:

1. **Frontier slicing.** Its §3.2 has the losing shard's `X` export "frontier entries
   labelled R" as slice entries `(range R, link, source) → position`. That is unsound (its
   own review, item 3): the cursor must be the router-recorded `p_begin`, with old-owner
   settlement (`FlipFence`/`FlipSettled`), and the gainer needs per-scope cursors because one
   boundary position can reach it twice (§5.6 here).
2. **Frontier keys.** Its capsule types `inputFrontiers: Map<LinkId, ExactPosition>`. Link ids
   are not replay-stable (`Link.kt:116`); the key is `(epoch, lane)` (plus scope during a flip).
3. **Follower progress.** Its §3.1 has a follower's `X` "advance on every suppressed delivery".
   Suppression for lack of effect authority is not a disposition; it must not advance
   `disposed`, or a new leader skips an act nobody performed (its review, item 1).
4. **Acknowledgement.** Its `O` drops a frame on the receiver's `accept`, described as "after
   its `D` append", while `D` is optional. It should use the two levels here and release on
   `DURABLE` only for a durable-delivery link.
5. **Persist-before-transmit.** Its `O` should state the per-frame `Retained` append before
   transmit (or the replay-determinism exception) and the unlink/death disposition table.
6. **Host exceptions.** Its §5.3 lists three; host intake coalescing is a fourth (§6 here,
   §10 decision 5).
7. **Repartition and the spec.** Its flip protocol also needs a durable router log and a
   non-catch-up transfer; it should cite the same `[24-PART-04]`/`[24-SHARD-03]` maintainer
   decision rather than present them as decided.

## Earlier reconciliation (merging two independent designs)

Each point where the two first-round designs differed, and the resolution.

1. **Position scheme.** `(outlet epoch, per-consumer seq)` keyed on epoch, vs a minted
   `(streamId, seq)` per attachment → `(epoch, lane, seq)`. The epoch-only key collides when
   two mediated exposures forward one outlet into one organelle inlet
   (`CompositeCell.kt:194-207`); a minted stream id re-implements `OutletWaveState` adoption.
2. **Relink identity.** Continue vs fresh stream → continue; the recovery re-handshake
   relinks the same pairs (`ApplyContext.kt:138-149`). Counters never go backwards
   (`laneFloor`).
3. **New-lane counter.** From the receiver's high-water, or 0 → above the sender's
   `laneFloor`, captured in `OutletWaveState`; no receiver input.
4. **Carrier.** `MessageContext` field vs separate envelope → field; leakage handled by an
   explicit clear on the reactive copy.
5. **Receiver state.** High-water vs cursor + set + reorder buffer → contiguous cursor per lane;
   FIFO is made true at the sender (review finding 7) rather than repaired by a buffer.
6. **Gap handling.** Diagnostic vs withhold ack → on dense lanes neither dispose nor
   acknowledge above a gap.
7. **Catch-up baselines.** Share the live seq vs keep a discharged set → separate unique pull
   ids; `[24-DUR-08]` re-keyed.
8. **Pull id form.** Counter vs random → random; a restored counter can re-issue an id.
9. **Replay-derived frames.** Re-derived vs retained → re-derived from restored counters.
10. **Acknowledgement meaning.** Durable frontier vs durable acceptance → acceptance, now at two
    levels (review finding 3; companion review item 9).
11. **Content mismatch.** Declared vs refused → both, by detectability (L1).
12. **Mediation.** Forwarding vs emitting → forwarding (`MediateProxy` calls
    `organelleInlet.call`).
13. **In-band markers.** Unsequenced.
14. **Legacy wire peer.** Silent fallback rejected; non-idempotent durable refused; `Effectful`
    is a maintainer decision.
15. **Wire capability precedent.** One design cited a `protocolCapabilities` wire route at
    `WireCodec.kt:103-110`; false — `protocolCapabilities` is a `Link` property (`Link.kt:44`).
    `WireFrame.version` is not a gate (`WC:53-67`). The negotiation now uses `natures`.
16. **Repartition slicing.** Scoped `p_begin` vs R-filtered receipt set with a veto → `p_begin`
    with settle and the veto kept; now per-scope cursors (review finding 1) and conditional on a
    maintainer decision (finding 9).
17. **Router flip buffer** parks bare `SetDelta`s outside their context (`PSS:117`, `:159`,
    `:258`) — found during reconciliation.
18. **Catch-up owed only without a frontier entry (n2jwi)** → maintainer decision, recommended
    against for now.
19. **Disposition state** → `applied`/`disposed` (renamed from `absorbed`/`acted` after review
    finding 6).
20. **Enforcement scope** → `Effectful` plus non-idempotent durable inlets.
21. **Host seam** → append-before-delivery, the existing journal tee.
22. **Non-durable RESTART** → keep `disposed`, reset `applied`; re-handshake is a maintainer
    decision.
23. **T2 promotion** → fresh inner frontiers; `disposed` transferred or the swap vetoed, now with
    an effect-identity check.
24. **Non-derived target refs** → volatile lanes, refused into enforcing inlets; lane keys now
    address-derived.
25. **Retention across a stateless slicing router** → open; conflicts with `[24-SHARD-03]`.
26. **Citation fixes.** `Link.id` random at `Link.kt:116`; `putIfAbsent` only for the null-ref
    key (`FO:101-111`); R-B double-counting a non-idempotent fold is an inference.
27. **Wire cost** → open, to be justified by measurement.

## Alignment with `composite-obligation-holders.md` (after both round-3 revisions)

Both notes were revised in parallel; each reviser listed inconsistencies in the other. Every
item was checked against the current text of both notes and, where it turned on a fact, the
code at `8d71ea64`. **Ownership** is now stated at the top of both notes: this note owns
positions, lanes (incl. replica-set lanes), cursors, acknowledgement levels and `O`'s
retention rules; the companion (COH) owns layers, formation rules, composites, the flip and
relocation transactions and the host contract. **Decision numbering** is unified as M1-M16:
M1-M11 in COH §7 (unchanged), this note's former decisions 2, 3, 4, 6, 7 → M12, M13, M14,
M15, M16; former decision 1 → M6 (COH's text is authoritative); former decision 5 dropped.

Items raised against this note:

| # | Item | Status before | Change in this note |
|---|---|---|---|
| 1 | No set-level lane for replica sets | Open. `PortRef.of` folds `instanceId` into the id (`port/PortRef.kt:31-34`); replicas share `CellRef.id` (`CellRef.kt:17-19`), so lanes were per instance | §3.2 "Replica-set lanes": lane key from the set's logical address, one counter per set lane, forwarding fan-out of one position to every covering instance, partial-acceptance rule. Density holds only under Total interest; the partial-overlap takeover check is **open** in both notes. EARS and tests added |
| 2 | Split boundary frame: two mechanisms | Both existed: scopes `R`/`rest` here, `(position, range part)` with three parts in COH | One mechanism, owned here: scopes `stable` and `R`, kept at **both** shards (the loser side was missing here; it is needed on abort, because A's stable cursor may pass `p_begin` before the fence). COH's three parts map to `R` at the gainer and `R` at the loser. Test `abortReleasesParkedToLoserAgainstRScope` added |
| 3 | Single "durable acceptance" level | Open (`HELD`/`DURABLE`) | §5.8 adopts `HELD < APPENDED < STABLE < DISPOSED`, the per-link required level, the `BATCHED` ceiling and in-process synchronous acknowledgement, moved here from COH §3.6. `DURABLE` was ambiguous: `BatchedFileJournal.append` returns before fsync (`BatchedFileJournal.kt:13-16`) |
| 4 | Durable RESTART as continuation without naming the R6 collision | Open (verified: `MH:1997-2022` mints fresh; 23:209-213 R6) | §7 row is conditional on M11: continuation under (c), succession until decided |
| 5 | T2 row lacks the effect-identity check | Partly resolved (same-identity rule present) | Row now follows COH F11/§3.4 fully: undeclared identity vetoes, `effectFrom = COMMIT`, restart at current `disposed`; also T0/T1 and T2 rows say `S` owns the lane counters (no adoption) and that lzfr0 is fixed by construction |
| 6 | `Durable(Supervised(Suspendable(cell)))` in §5.1 | **Already resolved**: the form does not occur in this note | None |
| 7 | `absorbed/acted` vs `received/disposed/acted` | Partly resolved (`applied`/`disposed`/`acts`) | One vocabulary, owned by §5.1: `received`, `applied`, `disposed`, `acted` (renamed from `acts`), `pullDischarged`, `scoped`. `received` added; follower suppression stated as no disposition in §2 |

Also changed here while aligning:

- §5.6 now names COH §3.2 as owner of the flip transaction and maps each step to COH's
  step and record numbers; markers carry COH's `tx` instead of `routingEpoch`.
- §5.8's unlink/death table is the single table for both notes. It took COH's substance
  where they differed: a receiver re-created by **succession** at the same ref still gets
  retained frames (the old row dead-lettered them, which loses data the successor needs);
  `discard` unlink, sender relocation and storage exhaustion (`Stall`, never drop) rows
  added. New: a succeeding durable sender resends surviving retained frames before the
  `ReBaseline` that retires their epoch, otherwise §5.7 would fence them.
- Persist-before-transmit now states the `BATCHED` case: a lost `Retained` record forces
  sender succession (COH §3.8), so "never a frame neither side holds" is claimed only for
  `SYNCHRONOUS` streams.
- §5.8 "Router segments" is no longer open: under M6 (c) the router of an
  `Effectful`/non-idempotent set is a `Durable` term with its own `O`.
- §6 now defers to COH §5; host coalescing is COH's transitional exception E3, moving
  into `P` (COH migration step 7). `P` sits outside `X`, so coalescing stays refused at
  enforcing inlets there too.
- Capsule keying stated: frontier by inlet port name, then `(epoch, lane[, scope])`, never
  `Link.id` (`link/Link.kt:116`).
- `[KBLK-12]` (moved here from COH) now cites `BatchedFileJournal.kt:45`.

No genuine design conflict remained after this alignment.
