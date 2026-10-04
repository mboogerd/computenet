# Exact per-link delivery positions

Status: design note, pinned at `8d71ea647a5d7b7931ed6a6b3831c9adc4cb0bf2`. It does not
edit `doc/spec`, and it assigns no requirement ids.

Labels: **[decided]** — decided by this design. **[conditional]** — designed here, but
adopting it changes a decided spec rule; it waits on a maintainer decision (M*n*, §10). **[open]** — needs a
further design decision. **[declared]** — a stated limitation. **[inference]** — reasoned
to rather than read in code or spec.

The companion note `composite-obligation-holders.md` (this directory; **COH**) defines the
layer vocabulary used here: `Durable` (`D`), `Outbox` (`O`), `Suspendable` (`P`),
`EffectDedup` (`X`), `Supervised` (`S`), the *capsule*, and *acceptance*.

**Division of ownership between the two notes.** This note owns, and COH refers to:
delivery positions, lanes (including replica-set lanes), the receiver cursors
(`received`, `applied`, `disposed`, `acted`, `pullDischarged`, scopes), acknowledgement
levels, and `O`'s retention rules (stable-before-transmit, the unlink/death table). COH
owns, and this note refers to: the layers and their order, the formation rules (F1-F11),
the composites, the flip and relocation *transactions* (records, prepare/commit, crash
recovery), and the host contract (H1-H9, exceptions E1-E3). Maintainer decisions use one
numbering across both notes, **M1-M16**: M1-M11 are argued in COH §7, M12-M16 in §10 here.

Abbreviations for citations:

| Short | Path |
|---|---|
| `MH` | `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` |
| `HD` | `kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt` |
| `HCP` | `kernel/src/main/kotlin/civictech/cell/host/HostedCellProxy.kt` |
| `FO` | `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt` |
| `PSS` | `kernel/src/main/kotlin/civictech/cell/partition/PartitionedShardSet.kt` |
| `WC` | `kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt` |
| `13`, `22`, `24` | `doc/spec/10-programming-model/13-links.md`, `doc/spec/20-dataflow-semantics/22-consistency.md`, `.../24-data-cells.md` |
| `42`, `93` | `doc/spec/40-distribution/42-replication.md`, `doc/spec/90-roadmap/93-feature-interactions.md` |

---

## 0. The decision in brief

- Every data frame that crosses a link carries a **delivery position**
  `(epoch, lane, seq)`. `epoch` is the emitting outlet's existing emission epoch (its wave
  `sourceId`, 22:70-85); continuation adopts it, succession mints a fresh one. `lane` is a
  replay-stable key derived from the target's address. `seq` counts live data deliveries
  on that lane.
- Positions are stamped where a frame is **emitted** (an outlet emission, or one hosted
  invocation through a stamped ingress) and carried unchanged by **forwarders** (wire
  bridge, mediated inlet, hosted proxy, partition router). Stamping and the hand-off to
  the receiver's acceptance happen atomically per lane, so each lane is FIFO.
- Each inlet keeps a **delivery frontier**: per `(epoch, lane)`, a contiguous high-water
  of positions it has *disposed of*. It is port and wrapper state, captured with the cell.
- Catch-up **pull baselines** carry a unique id instead of a `seq`, so an emission that
  replay does not reproduce never shifts a live position.
- Positions are a **second plane** beside waves. Waves keep completeness, watermarks and
  `ReBaseline` supersession; merge tags keep convergence. Positions answer "has *this
  delivery* already been disposed of?": effect dedup, duplicate absorption at
  non-idempotent durable inlets, acknowledgement and retention on async links, and
  frontier transfer on handoff.
- The repartition handoff (§5.6) is designed but **[conditional]**: it amends
  `[24-PART-04]` and `[24-SHARD-03]`, so it waits on maintainer decision **M6** (COH §7).

---

## 1. The problem

**Waves are not delivery ids.** `MessageContext.timestamp` names a wave
(`MessageContext.kt:47-54`): a reactive emission copies the incoming context (`FO:235`;
normative transparent flow, 22:24-28), and fan-out duplicates it (22:32). One
`(sourceId, counter)` labels many distinct frames.

**The `Effectful` frontier is keyed on the wave.** It is
`(cellRef, portName) → root sourceId → counter`, a high-water (`HD:401`, `HD:896-901`),
consulted origin-blind on live delivery and replay (`MH:1889-1915`). Three live shapes
suppress real data silently:

| Shape | Arrives at the `Effectful` port | Dropped today because |
|---|---|---|
| Diamond S→(L,R)→E, both arms into one port | `S:4` via L, then `S:4` via R | equal counter (computenet-wlwjw) |
| A handler emits twice on one outlet into E | `S:4`, then `S:4` | equal counter (computenet-wlwjw) |
| **Cross-arm reorder**: two paths from S reach one port | `S:5` via the fast arm, then `S:4` | `5 ≥ 4`; only *per-link* order exists (13:194-198) **[inference from `HD:896-897`]** |

The third row rules out *any* dedup keyed on a per-source high-water.

**The frontier cannot be handed off.** It lives in the host's `HostDurability`
(`HD:401`), so a migrated or repartitioned owner starts with nothing
(computenet-8g7kg), and one high-water per source cannot be restricted to a key range.

**Per-cell durability needs exact positions.** It composes without a global cut only if
every non-idempotent inlet drops duplicates by an exact per-link position, and async
senders retain output until the receiver has durably accepted it (93:2849-2866, R7-R8).

---

## 2. Requirements

**Terms.**

- A **delivery** is one frame on one link.
- **Accepted**: the receiver holds the delivery in custody (§5.8 gives the two
  acknowledgement levels).
- **Disposed**: the receiving term has reached a terminal decision for it — absorbed into
  state, acted on, refused, dropped by admission policy, or dead-lettered with a report.
  Parking and holding are neither. Nor is a replica follower's delivery *suppressed for
  lack of effect authority* (31:401-406): nothing was done to the world, so it advances
  only `received`, never `disposed` (COH §3.1).

| # | Requirement | Needed by | Basis |
|---|---|---|---|
| P1 | **Uniqueness per inlet.** Two distinct deliveries into one inlet never carry the same delivery identity — the position, plus the frontier *scope* where a keyed composite splits one frame (§5.6). | effect dedup (wlwjw) | silent omission is what 24:1391-1405 refuses |
| P2 | **Re-delivery stability.** A re-delivery of the same frame carries the same position: own-journal replay, deterministic upstream re-derivation, retransmit, release of a parked frame, re-routing through a forwarder. | dedup across recovery | `[24-DUR-05]`, `[24-DUR-06]` (24:1346-1367) |
| P3 | **Never re-issued for different content** (93 I-14 Rule S1, for positions). | all | 22:72-85 |
| P4 | **Per-lane FIFO.** Along one lane, frames reach the receiver's acceptance in strictly increasing `seq`. A high-water is then exact. | bounded state, ack | 13:194-198 |
| P5 | **Dense where unfiltered**, so a gap is detectable. | loss detection | 13:185-191 |
| P6 | **Isolation.** An emission that replay does not reproduce never shifts any live position. | replay determinism | §4 option D |
| P7 | **Transferable and scopable.** A frontier moves whole (migration, promotion) or scoped to a key range (repartition) without suppressing anything the new owner has not disposed of. | computenet-8g7kg | 24:951-962 |
| P8 | **Acknowledgeable**, at a stated acceptance level. | async durability | 93:2849-2866 |
| P9 | **Wave-neutral.** A position never enters a wave join key, a completeness set or a tag frontier. | glitch-freedom | 22:36-38 |
| P10 | **In-process/remote parity; additive and old-peer-safe on the wire.** | parity | `AGENTS.md`; 42:482-512 |

A position names a frame, not its bytes (§8, L1), and it is not a consistent cut:
recovery still promises convergence (93:2849-2854).

---

## 3. What a link is

### 3.1 What exists today

- **`Link`** ids are random per instance (`link/Link.kt:116`), and the compacted-recovery
  re-handshake re-applies every folded link (`graph/ApplyContext.kt:138-149`), minting new
  `Link`s for the same endpoints. Not replay-stable.
- **`PortRef.of(cell, name)`** is replay-stable, derived from `(name, cellRef.id,
  instanceId)` (`port/PortRef.kt:22-34`); `PortRef.generate` is random.
- **`FanOutlet`** keeps one consumer entry per target `PortRef` (`FO:83-111`); re-linking a
  target replaces its entry; null-ref consumers share one key (`FO:101-109`); taps are
  keyed separately. The fan-out loop invokes targets with no per-entry lock
  (`FO:305-319`).
- **Cross-node edges** are installed with a *generated* target ref:
  `Use.fixed(RoutedPropagate(…), PortRef.generate())` (`runtime/…/Runtime.kt:77-84`). The
  consumer entry's key is therefore random on every apply.
- **Emission epoch**: `sourceId`/`waveCounter`, adopted on continuation (`FO:435-438`),
  rotated on succession (`FO:445-450`), checkpointed as `RECORD_OUTLET_WAVE` (`HD:29`,
  `port/OutletWaveState.kt:17`).
- **Mediated exposure** forwards each invocation to `organelleInlet.call`
  (`membrane/CompositeCell.kt:194-207`); two exposures may mediate one organelle inlet.
- **Partition router** slices each delta per shard and calls a per-shard
  `HostedCellProxy` (`PSS:151-174`). During a flip it splits a delta into a moving part,
  parked, and a stable part, routed now (`PSS:154-160`); parked parts are re-emitted
  later without their context (`PSS:117`, `PSS:258`).
- **Stamped ingress**: `ActorIngress.driveStamped` calls `next()` once and runs an
  arbitrary block under that one context (`HCP:165-184`), so several hosted invocations
  share one `(actorId, counter)`. Its `ingressPort` defaults to `PortRef.generate()`
  (`HCP:156`).
- **Wire**: `WireFrame.context` crosses the wire (`WC:74`); `WireFrame.version` is never
  emitted and is not a gate (`WC:53-67`).

### 3.2 Definition [decided]

A **lane** is `(epoch, laneKey)`. The **lane key** is derived from the target's address,
never from a generated ref: `PortRef.of(targetCell, targetPortName).id` for a consumer or
typed tap; for a cross-node edge, the same derivation over the edge's `(toRef, inlet)`
address, not the `PortRef.generate()` the runtime installs today. A **link**, for
positions, is a lane together with the inlet it reaches.

The counter lives with the outlet entry. The receiver keys its frontier on the
`(epoch, lane)` carried in the position, never on `sourcePort` or `Link.id`.

**Why the lane is in the key.** An outlet linked to two mediated exposures of one
organelle inlet reaches that inlet over two lanes. With `epoch` alone, `(E, 5)` would
arrive twice as two distinct deliveries and the second would be dropped.

**I-L1 (one path per lane and scope) [decided].** Into a given inlet, a lane's position
is delivered at most once per frontier scope. Forwarders may *filter* a lane (Mediate
suppression, router slicing), and may deliver one position to *different* inlets (a
router slicing one frame to two shards). The one case where one position reaches *one*
inlet twice — two slices of one frame routed to the same shard at different times during
a flip — is admissible only because each slice is checked against a different scope
(§5.6). Any other double path is a defect.

**Replica-set lanes [decided].** Replica instances share `CellRef.id` and differ in
`instanceId` (`CellRef.kt:17-19`), and `PortRef.of` folds the `instanceId` into the id
(`port/PortRef.kt:31-34`), so by the rule above each instance would get its own lane and
counter: no position could then be compared across instances, which a replica set's
disposed-frontier fold and takeover need (COH §3.1). For a target port on an instance of a
replica set, the lane key is therefore derived from the set's **logical** address,
`nameUUIDFromBytes("lane:set:" + portName + ":" + cellRef.id)`, without `instanceId`. The
outlet holds one counter per set lane and stamps once per logical delivery; the delivery
to each covering instance (31:398-399: "a delta rides every covering instance's link") is
a *forwarding* fan-out of that one position. I-L1 already permits one position at distinct
inlets. Allocation is serialized with acceptance at every covering instance under the one
lane lock (§5.2 rule 2); if some instance's acceptance fails after another accepted, the
`seq` is kept, and the frame stays in the sender's custody (`O`, §5.8) for that instance
only — retention and acknowledgement are per recipient (§5.8). A set lane is **dense** at an
instance only when the instance covers the set's whole interest (Total); under partial
overlap it is non-dense there, so no gap check can prove a follower's retention complete, and
COH §3.1 refuses effect-authority takeover by such an instance.

**Volatile lanes [decided].** A target with no derivable address (a null-ref consumer,
an anonymous observer tap) gets a volatile lane: no P2 across recovery. It may feed a
mergeable inlet. A link from it into an enforcing inlet (§5.3) is refused at link time.

### 3.3 Identity under mode changes

- **Continuation** adopts the epoch and every lane counter; receivers' frontiers carry on.
- **Succession** mints a fresh epoch, so every receiver sees new lanes; the old epoch's
  entries are retired as §5.7 says.
- **Relink [decided]** of the same `(epoch, laneKey)` continues the lane: the counter
  resumes. The recovery re-handshake is such a relink; a fresh stream there would make
  every re-derived frame look new to the restored receiver. The objection that a new
  attachment "inherits old receipts" matters only if a counter could go backwards, and
  §5.2 rule 7 prevents that.

---

## 4. Options considered

| | Position | Exactness | Mode changes | Cost |
|---|---|---|---|---|
| **A. `(epoch, lane, seq)`, counter per lane** *(chosen)* | sender-stamped | Exact for diamonds, multi-emit, cross-arm reorder, convergent forwarders, cycles; coalescing and router splitting need the rules of §5.3 and §5.6 | rides the existing epoch adopt/mint machinery | ~40 B/frame (less with per-link announcement, §5.8); one `Long` per live upstream lane |
| **B. `(epoch, seq)`, counter per consumer** | sender-stamped | collides across convergent forwarders | as A | 24 B |
| **C. Minted persistent `(streamId, seq)` per attachment** | sender-stamped | exact | a stream id must be minted, journaled, exchanged and adopted on every continuation; breaks the re-handshake unless it is classed as adoption | a persisted record per link |
| **D. Per-outlet `(epoch, emitSeq)`** | sender-stamped | monotone but sparse per link | as A | 24 B |
| **E. Derived `(sourcePort, root sourceId, counter, emission index)`** | from context | exact only as an unbounded set; a high-water fails the cross-arm reorder | slicing reasons about foreign sources | unbounded |
| **F. Receiver acceptance seq + content hash** | receiver-minted | two identical legitimate frames collide; a lost reply leaves the sender unable to name a frame | survives everything | unbounded set |

**Why not D.** An emission replay does not reproduce — a catch-up to a late joiner, the
re-handshake catch-up (`ApplyContext.kt:138-149` → `FO:474-476`) — shifts every later
frame. Pre-crash a catch-up took 6 and live frame f took 7 (seen, high-water 7). On replay
without the catch-up, f becomes 6 and is dropped, and the next new frame g becomes 7 and
is **suppressed**. The same objection applies to A's own lane if catch-ups share the
live counter; hence §5.4.

**Why not C.** C pays for identity twice — a durable stream record per link, and an
adoption protocol for every continuation — that `OutletWaveState` adoption already gives.
A keeps C's real contribution, per-link distinction, as the `lane` field.

---

## 5. Design

### 5.1 Data model

```kotlin
@Serializable @SerialName("DeliveryPosition")
data class DeliveryPosition(
    val epoch: UUID,           // emitting outlet's sourceId (or the ingress actor id)
    val lane: UUID,            // address-derived lane key (§3.2)
    val seq: Long? = null,     // live data: per-lane sequence
    val pull: UUID? = null,    // pull baseline: unique id; exactly one of seq/pull is set
)

data class MessageContext(
    val timestamp: Timestamp, val sourcePort: PortRef,
    val reBaseline: ReBaselineNotice? = null, val baseline: TagFrontier? = null, val hop: Int = 0,
    val position: DeliveryPosition? = null,   // NEW, additive, default null
)
```

`MessageContext` already rides every path a position must survive — `Invocation`
delivery and buffered replay (22:28-30), `HostedCellProxy` capture, `WireFrame.context`,
and the intake journal tee, which gives P2 for own-log replay (24:1355-1360). The price is
that a reactive copy could leak an inbound position; §5.2 rule 3 clears it.

**Sender state.** Per outlet entry: `seq` and a lane lock (§5.2 rule 2).
`OutletWaveState` gains `laneHighWater: Map<laneKey, Long>` (live and retained-closed
lanes) and `laneFloor: Long` (max `seq` of any lane evicted under this epoch), captured,
adopted and minted with `(sourceId, highWater)`.

**Receiver state** — a `DeliveryFrontier` per inlet, split by the layer that must survive
what (layer names from COH §2.5). This is the one cursor vocabulary for both notes:

| Component | Content | Held by |
|---|---|---|
| `received` | `(epoch, lane) → seq`: highest position present in the term's custody. Proves nothing about the world; never used for dedup. Its one consumer is a replica follower's warm progress and retention (COH §3.1) | the custody-holding layer (`D`, or `P` without `D`) |
| `applied` | `(epoch, lane) → seq`: contiguous cursor of deliveries whose disposition is reflected in the inner cell's state (absorbed, or refused by the cell; at an inlet without `X`, also dropped or dead-lettered ahead of it, §5.3). Every positioned inlet tracks it, enforcing or not; it is the starting frontier when a promotion adds `X` (COH §3.4) | inner cell (inside `S`); rolled back with its state |
| `disposed` | `(epoch, lane) → seq`: contiguous cursor of deliveries with **any** terminal disposition — acted, absorbed, refused, admission-dropped, dead-lettered | `X`; survives anything that rolls inner state back |
| `pullDischarged` | exact capped set of `(epoch, lane, pull)` already disposed of | `X` (today's `[24-DUR-08]` set, re-keyed) |
| `scoped` | `(epoch, lane, scope) → seq` while a repartition flip is open; scopes `stable` and `R` | `X` at keyed shard inlets only (§5.6) |
| `closed`, `dead` | closed lanes (LRU); fenced epochs (capped) | the inlet (§5.7) |
| `acted` *(optional)* | the disposed positions whose external act ran | `X`, only where something must later prove an act: replica takeover and witnesses (COH §3.1), promotion (COH §3.4), an external idempotency key or reconciliation (§5.6 step 7) |

One contiguous `disposed` cursor covers acted *and* refused frames. A refused position 1
followed by position 2 is therefore no gap, and a redelivered 1 is a duplicate and does
not act. Whether a disposed position was *acted* is not needed to decide duplicates; where
a consumer of the frontier does need it, `acted` records it separately. The capsule
carries the frontier keyed by inlet port name, and within it by `(epoch, lane)` (plus
scope during a flip) — never by `Link.id`, which is random per `Link` instance
(`link/Link.kt:116`) and re-minted by the recovery re-handshake.

For a plain `Effectful` sink `applied` and `disposed` are equal at every checkpoint. They
differ only after a RESTART that rolls the inner state back (§7).

### 5.2 Stamping [decided]

1. **Emitting.** In the fan-out loop (`FO:305-319`) and in `at` (`FO:479`), *after* the
   disclosure filter has admitted the target, stamp a live delivery with
   `DeliveryPosition(sourceId, laneKey, next seq)`. A suppressed target consumes no `seq`
   (P5). Taps stamp their own entries.
2. **Allocation is serialized with acceptance, per lane.** Under the lane lock the outlet
   allocates the `seq` and hands the frame to the receiver's *acceptance*: the hosted
   intake's staging and journal append (`MH:1275-1281`; in COH's target, the synchronous
   `accept` on the receiving term's outermost layer, routed by H6), or the enqueue onto a
   bridge's ordered send queue (whose `O` then holds custody). If acceptance throws (intake closed, saturation refusal), the `seq`
   is returned under the same lock, so no gap is created. Two threads emitting through one
   outlet therefore cannot hand a receiver `seq 2` before `seq 1`. The lock order is
   lane lock → host `dataLock`; nothing run under `dataLock` emits (saturation announces are
   already deferred out of it, `MH:1270-1283`) **[inference]**.
   A target reached by a **direct synchronous call** (an unhosted in-process consumer) is
   not locked — holding a lane lock across a foreign handler invites deadlock and
   re-entrant reordering. Such lanes are **track-only**: positions are carried, but no
   enforcing inlet may sit behind a direct synchronous call; enforcing inlets are reached
   through an accepting intake.
3. **The reactive copy clears the inbound position** (`FO:235`) before rule 1. A composite's
   merging outlet that forwards organelle deltas "preserving `MessageContext`" (24:951-955)
   preserves the *wave* and re-stamps the position.
4. **Forwarders** pass the position through untouched: `HostedCellProxy`,
   `WireFrame.context`, `MediateProxy`, and `PartitionedShardSet.route` slices. The router's
   flip buffer must park each slice **with its context**; today it parks bare `SetDelta`s
   and re-emits them outside the originating context (`PSS:117`, `PSS:159`, `PSS:258`),
   losing position and wave **[inference from code]**.
5. **Pull baselines** — `baselineTo` (`FO:474-476`), `StateRequest` replies, positioned
   onLinked catch-up — carry `pull = UUID.randomUUID()` and no `seq` (§5.4).
6. **Stamped ingress: one `seq` per hosted invocation.** `ActorIngress.drive` installs the
   wave context once, as today; each hosted invocation sent from the block is stamped at
   the proxy's send boundary (`HCP:116`) with `DeliveryPosition(actorId, ingressLane,
   next)`, allocated under the ingress lock per rule 2. The calls share one wave and get
   distinct positions. `ingressLane` must be replay-stable: by default
   `nameUUIDFromBytes("ingress:" + actorId)`, or a caller-supplied id persisted with the
   counter. One counter serves every target, so the lane is **non-dense** at each inlet
   (gap checks off, high-water still exact). Persisting the counter stays the connector's
   duty (24:1373-1380); a counter restarted below its persisted value re-issues positions
   (L1).
7. **Lane creation.** A lane key the outlet has never held under this epoch starts above
   `laneFloor`; a key it holds, live or closed, resumes. The counters are captured in the
   same `OutletWaveState` as the topology that created them, so recovery restores them
   rather than re-creating them, including across the compacted re-handshake. A counter
   never goes backwards for a pair a receiver might remember, with no receiver input.
8. **Contextless frames** — `PORT_API` without context, management invocations, the
   context-less `catchUpOnLinked` path (`FO:505-508`) — carry no position and keep today's
   treatment, including `[24-DUR-06]`'s refusal at an `Effectful` inlet (`MH:1878-1888`).

`position` names the delivery; `timestamp` names the wave. Neither is derived from the
other.

### 5.3 Disposing of a delivery [decided; at `Effectful` inlets conditional — M16]

Let `p` be the position and `hw` the inlet's cursor for `(p.epoch, p.lane)` — `disposed`
at an `Effectful` inlet, `applied` elsewhere.

- **Duplicate**: `p.seq ≤ hw` (or, during a flip, `≤` the cursor of the frame's scope,
  §5.6). Dropped, exclusive payloads discharged as today (`MH:1904-1915`), counted.
- **Fenced**: `p.epoch ∈ dead`. Dropped as a succession straggler, counted.
- **Gap on a dense lane**: `p.seq > hw + 1`. Not disposed; the receiver reports the gap
  and, where the segment retains output, asks for a resend from `hw + 1`. Disposing above a
  gap would turn the later retransmit of the missing frame into a silent drop. With rule 2,
  a gap on an in-process or reliable link is a defect, so this path is loud and rare.
  Lanes through a filtering forwarder, and ingress lanes, are **non-dense** and not
  gap-checked.
- **Pull position**: an `Effectful` inlet acts unless `(epoch, lane, pull) ∈
  pullDischarged`, records the id, and advances no live cursor. Others absorb, as today.

Replacing the wave-keyed `[24-DUR-05]`/`[24-DUR-08]` test with this one is M16 (§10); until
it is decided, an `Effectful` inlet tracks positions and keeps today's wave rule.

**Which inlets enforce** (drop duplicates): every `Effectful` inlet (on `disposed`); every
inlet of a durable cell **not declared idempotent** (on `applied`) — new, closing the
double delivery that own-log replay plus upstream re-derivation produces in a shared
journal (`durability/ReplayProvenanceTest.kt:232-240`, example R-B, harmless there only
because its fold is a set **[inference]**). Idempotent inlets **track but do not drop**,
which keeps a non-deterministic upstream's re-issued content convergent rather than lost
(L1). Who declares idempotency is M15 (§10).

**When the cursor advances, and when that is durable.** The cursor advances in the same
record as the state that reflects the disposition, so both are captured together:

| Disposition | Advances | Durable when |
|---|---|---|
| absorbed by an ordinary inlet | after the handler returns | the next checkpoint of `D`; until then own-log replay re-disposes it identically |
| acted (`Effectful`) | after the act | the `X` record after the act — the `[24-DUR-09]` window is unchanged (24:1486-1500) |
| refused, admission-dropped, dead-lettered | at the decision | in the same record as the dead-letter/refusal report, written before the payload is discharged; a crash before it re-decides the frame, never acts on it twice |
| glitch-free join, partial wave buffered | at buffer insertion only where the buffer is in the cell's capture; otherwise at release (`GlitchFreeCell` resets on deactivate, computenet-5jhg3) | with that capture |
| parked or held | never, until disposed | — |

**Replay-derived frames are live positions.** PN-2 baseline-marks replayed frames and
their re-derivations (`FO:230-235`, 24:1336-1341), but replay restores the sender's lane
counters and re-runs the same invocations, so a re-derived frame carries its pre-crash
`seq` (P2). If already disposed it is suppressed — today's `[24-DUR-05]` replay
behaviour; if it is a tail never disposed of, it is disposed and advances the cursor.
That advance is safe, where a wave high-water advance was not, because the lane is FIFO.

**Coalescing.** The host merges a saturated intake's queued mergeable frames of the same
wave and `sourcePort` into one staged entry (`host/IntakeControl.kt:73-91`; the default
policy, `host/IntakeSaturation.kt:14`), journaling every original (`MH:1236`). Two
consecutive frames of one lane qualify, so a merged entry can carry several positions of
one lane, and the merge itself is a cell-semantic act. Rules:

- A frame may be merged into a queued entry only if no entry queued after it carries a
  position on any lane the frame or the entry carries. Each lane's merged positions are then
  a contiguous range, and per-lane order is preserved.
- The staged entry carries `Map<lane, LongRange>` for every merged position; taking it
  advances each lane to its range's end. Replay delivers the journaled originals one by one,
  each with its own position.
- **Coalescing is refused at enforcing inlets.** The coalescer cannot see the frontier,
  so it could merge a duplicate with a new frame into a payload no layer can split. An
  enforcing inlet's saturation policy is `Park`.
- **Where it runs [decided].** The target is COH's: coalescing moves out of the host into
  `P`'s inbox (COH §4, migration step 7). `P` sits outside `X` (COH F3), so it cannot see
  the frontier either, and the three rules above apply to it unchanged. Until step 7 lands,
  the host keeps coalescing under exactly these rules, as the transitional host exception
  **E3** (COH §5.3; §6 here). Coalescing at an enforcing inlet would need a merge step inside
  `X`; no use for it is known **[open]**.

### 5.4 Pull baselines outside the live sequence [decided]

A pull baseline is triggered by topology or protocol (link install, `StateRequest`, the
recovery re-handshake). Input replay does not reproduce it, and the re-handshake adds
ones that never existed pre-crash. If it consumed live `seq`, a missing one would shift
later frames down (loss) and an added one up (duplicate act) — §4's objection to D, on one
lane. A unique id outside the sequence makes the live sequence a function of
input-driven emissions only.

The cost is an exact set of discharged pull ids per inlet: today's `[24-DUR-08]` set,
cap (`HD:97`, 1024 per inlet) and loss mode (an evicted id may re-fire once, never
suppress; 24:1437-1456). The id is random, not a counter: a counter restored from an
older checkpoint would re-issue an id for a different reply and suppress it (P3).

### 5.5 Invariants

- **I-P1 (one path per lane and scope).** §3.2.
- **I-P2 (FIFO feed).** Frames of one lane reach an enforcing inlet's acceptance in `seq`
  order:
  - *At the sender*: allocation serialized with acceptance (§5.2 rule 2).
  - *Own log before upstream re-derivation*: a cell recovering by continuation admits no
    delivery other than its own logged tail until that tail is replayed — the
    continuation's fence, settle, release. Without it, under per-cell logs, a re-derived
    `seq 7` can overtake the receiver's own logged `seq 6` **[inference]**.
  - *Retransmit*: retained frames above the acknowledged position, in order, before any
    new frame.
  - *Forwarders, intake and `P`*: never reorder within a lane; coalescing only as §5.3
    allows; the router's flip buffer only as §5.6 allows.
- **I-P3 (gap discipline).** No disposal above a gap on a dense lane.
- **I-P4 (atomic capture).** A cursor and the state it guards are captured in the same
  checkpoint or capsule: `applied` with the inner snapshot; `disposed`, `pullDischarged`,
  `acted`, `scoped` with `X`; lane counters with `OutletWaveState`; retained output with `O`.
- **I-P5 (no fabrication).** A frontier entry is created only by disposing of a delivery
  or by an explicit transfer; never inferred from a wave timestamp, aggregate state or tag
  frontier.

### 5.6 Frontier handoff

**Whole transfer — migration, suspend/resume, T0/T1 promotion [decided].** The frontier is
port and wrapper state (I-P4), so it rides in the capsule. That removes the migrate half of
computenet-8g7kg, which exists only because the frontier sits in the source host
(`HD:401`).

**Repartition [conditional — M6].** Key range R moves from shard A to shard B, which
keeps its own stable range. The router `PartitionedShardSet` is a forwarder: it routes
slices carrying the client's **boundary** positions (§5.2 rule 4), so a shard's frontier is
keyed by boundary lanes. Within one boundary frame the router may send B a stable slice now
and park B's R-slice until the flip closes (`PSS:154-160`): one position, two deliveries to
one inlet. The design gives each its own **scope**.

The flip is a durable transaction owned by COH §3.2: its records R1-R7 in the router's `D`,
`TransferId` `tx`, idempotent `SETTLE`/`PREPARE`/`COMMIT`/`SHED`/`ABORT`, and the recovery
table for a crash after every record. This section states only what positions and cursors
require of that transaction; step numbers in brackets are COH §3.2's.

1. **Begin [COH 2, R1].** The router durably records, per boundary lane, `p_begin` = the
   last boundary `seq` it routed under the old table, and from then on parks R-slices with
   their contexts. Every R-frame at or below `p_begin` was routed to A; every one above it
   is parked.
2. **Settle the old owner, in band [COH 3, R3].** `SETTLE` travels as an in-band
   `FlipFence(tx, {lane → p_begin})` on each router-to-A path. It must be in band: A's
   boundary lanes are filtered (non-dense), so A cannot tell from `p_begin` alone whether it
   has seen every R-frame at or below it, whereas by FIFO the fence follows every R-frame A
   will ever receive. The fence is a **barrier** (§5.8, "In-band markers"): A processes it —
   freezes its `R` scope, writes `SettledFor(tx)` to its `D`, answers `FlipSettled(tx)` — only
   once everything accepted ahead of it on that lane has a `STABLE` terminal disposition.
   Processing it earlier would check a queued R-slice at or below `p_begin` against the frozen
   scope and drop it. Disposal includes an act repeated once after a crash inside
   `[24-DUR-09]` (L5).
3. **Transfer, not catch-up [COH 4 and 6].** R's state moves to B as a **range-handoff
   record** `(R, state slice, {boundary lane → p_begin}, tx)` on B's control plane
   (`assignInlet`), staged at `PREPARE` and installed at `COMMIT`. B **does not act** on the
   state slice — it continues A's acts and is no new information. Today the moved-in state
   is a catch-up `RoutedCommand` on `routeInlet` (`PSS:233-237`), which an `Effectful` shard
   fires (computenet-8g7kg).
4. **Scoped cursors, at both shards.** Two scopes per boundary lane: `stable` (the shard's
   unchanged range) and `R`. The router guarantees that every slice it routes lies wholly
   inside one scope (it already splits each delta into moving and stable parts,
   `PSS:154-160`); a shard checks a slice only against its scope's cursor.
   - *Gainer B*, at `COMMIT`: `scoped[(lane, R)] = p_begin`; its existing cursor becomes
     `scoped[(lane, stable)]`. So B's stable slice of frame 11 advances `stable` to 11, and
     B's later R-slice of frame 11 is checked against `R` (`p_begin < 11`, so new).
   - *Loser A*, on processing the fence: `scoped[(lane, R)] = p_begin` (frozen: A receives no further
     R-slice unless the flip aborts); its existing cursor becomes `scoped[(lane, stable)]`.
     A's single cursor may already exceed `p_begin` from stable slices routed between begin
     and fence, which is why the R scope is set to `p_begin` and not to it. On abort
     [COH 8] the parked R-slices, all above `p_begin`, are released to A and checked
     against A's `R` scope.

   Scoped cursors are part of the frontier, held in `X`, captured and journaled like it; a
   crash between the two slices of one frame loses neither disposition.
5. **Exactness.** At B, an R-slice with `seq ≤ p_begin` is a duplicate: A routed and settled
   it. An R-slice above `p_begin` was parked and is new to B. The cursor must be `p_begin`,
   **not A's high-water**: A kept receiving stable traffic during the window, so its
   high-water exceeds R-frames it never saw.
6. **Release and merge at drain [COH 7, R6-R7].** The router releases the flip buffer, with
   contexts and in order, before any new traffic on the lane, through its `O`: a released
   slice stays retained (its R2 record) until the shard acknowledges it at `STABLE` for
   `(lane, shard, R)` (§5.8), and only that acknowledgement advances R6. It then sends an
   in-band `FlipDrained(tx)` to each shard. `FlipDrained` is a barrier like the fence: the
   shard processes it only once everything accepted ahead of it on the lane has a `STABLE`
   disposition, then sets its unscoped cursor to `max(scoped[R], scoped[stable])`, drops the
   scoped entries, logs `DrainedFor(tx)` and acknowledges. This is exact: every boundary frame
   the shard receives after the marker has a higher `seq` (router FIFO), and every frame ahead
   of it is disposed (barrier); a re-delivery at or below the maximum is an R-frame at or
   below `p_begin` (disposed by A), a released R-frame (disposed by its new owner), or a
   stable frame. The router keeps R2 until both shards have acknowledged `FlipDrained`.
7. **Veto [COH 1, PRECHECK]** the move of an `Effectful` range when A cannot settle (dead,
   unreachable, or inside `[24-DUR-09]` with no durable disposition), or when only an
   aggregate state-as-delta is available (`partition/PartitionedCell.kt:143`). The exception
   is an external action with a stable idempotency key and a documented reconciliation rule
   (using `acted`).
8. **Crash inside the window** needs `p_begin`, R, the parked slices with their contexts
   and the open-flip state to survive: COH §3.2's records R1-R7 in the router's own `D`,
   which is computenet-d2lue's fix and which contradicts `[24-SHARD-03]` (M6). Until then,
   see L8.
9. Unbuffered flips (`buffered = false`, the CP-D4 control) route R to both owners and are
   refused for `Effectful` ranges.

### 5.7 Garbage collection [decided]

- **Supersession retires an epoch only when authoritative.** On a `ReBaseline` with
  `supersede = true` naming epoch `e`, the inlet moves every `(e, *)` entry into `dead` and
  fences later frames from `e` — 93 R5's dead-lane rule (93:8403-8415). On
  `supersede = false` (pull-merge, derived and replicated producers; 93:8397-8401,
  `MessageContext.kt:75-91`) nothing is fenced: a delayed old-epoch frame may carry
  information absent from the catch-up and must still be taken, as the tag path merges it.
  Its entries **stay live**: a straggler at or below the cursor is still a duplicate and one
  above it is disposed. They leave the live set only on that lane's own `EdgeClose`, or under
  a declared per-inlet bound on superseded-but-unfenced epochs whose eviction is L6's
  duplicate ceiling, never a suppression.
  - `dead` is the dead-lane set 93 I-22 already needs; it shares computenet-kxdjx's snapshot
    gap and G-42's unbounded growth (22:116-122). **[declared]** It is capped, oldest first;
    an evicted dead epoch's straggler is then disposed, not fenced — a duplicate, never an
    omission.
- **Closed lanes.** `EdgeClose` rides after the final data (13:194-198). The receiver marks
  the entry closed and keeps it in a per-inlet LRU, so a relink continues it; the sender
  keeps the counter until it is evicted into `laneFloor`. **[declared]** A closed entry
  evicted while an old sender can still retransmit may be re-disposed once — a duplicate,
  the direction `[24-DUR-08]`'s cap chose (24:1450-1456).
- **Pull ids** keep today's cap and loss mode (§5.4).
- **Live entries are never evicted** except under the declared bound above; they are bounded
  by live upstream lanes plus unfenced superseded epochs.
- **Retained output** is released only by acknowledgement or by an explicit, reported
  disposition (§5.8).

### 5.8 Acceptance, acknowledgement, retention and the wire

**Acknowledgement levels [decided].** A `DeliveryAck(epoch, lane, recipient, scope, seqs,
level, pulls)` is a protocol-plane message (null context, outside the wave domain, 22:36-38)
flowing upstream. It reports a level the receiver has reached for the listed positions, on
one ordered scale (this is the one scale for both notes; COH's `accept` returns it):

- `HELD` — in the receiver's volatile custody. Survives the sender's crash, not the
  receiver's.
- `APPENDED` — in the receiver's `D` stream, acknowledged as ordered acceptance but
  possibly unsynced (COH H1: `BATCHED` `append` returns before fsync,
  `durability/BatchedFileJournal.kt:13-16`). Lost only in a `TAIL` gap, which forces the
  receiver into succession (COH §3.8).
- `STABLE` — synced (`stableThrough` has passed it). After a crash, I-P2's own-log replay
  will dispose of it. For a `SYNCHRONOUS` stream, `APPENDED = STABLE`.
- `DISPOSED` — its terminal disposition (§5.3) is `STABLE`; `disposed` has passed it.

**Acknowledgement and retention are keyed by `(epoch, lane, recipient, scope)`** — recipient
the receiving inlet's address (an instance, for a replica set; a shard, behind a router),
scope `stable`/`R` during a flip (§5.6), otherwise none. One lane can feed several recipients
(an ingress lane, a set lane, a boundary lane), so a lane-wide cursor would let one
recipient's ack release another's frame. `seqs` is a cumulative high-water (`≤ s`) only on a
lane **dense for that recipient** (every `seq` of the lane goes to it: an unfiltered lane, or a
set lane at a Total-coverage instance), and there the receiver never acknowledges past a gap.
Everywhere else `seqs` is an explicit list of intervals, each naming positions the recipient
actually holds; `O` releases exactly those. `pulls` lists accepted pull ids individually.

**Required level, negotiated per link [decided].** At link time the sender declares the
level it requires and the receiver the maximum it can give (`PositionsAccept` carries it on
the wire). `O` releases a retained frame only on an acknowledgement at or above the
required level.

- A durable sender (`O` inside `D`) requires `STABLE` by default. It may require only
  `APPENDED` toward a `BATCHED` receiver if the link declares that class's ceiling (COH §6).
- A receiver whose maximum is below the requirement — a volatile receiver gives only
  `HELD` — is admitted only with a declared per-link ceiling: frames acknowledged `HELD` are
  lost if the receiver crashes before disposing of them into state that survives (L7; the
  `ALL` gap of COH §3.8). The manifest records the link as volatile-acknowledged and no
  durable-delivery claim is made across it. Requiring `DISPOSED` instead does not lift the
  ceiling for a volatile receiver, whose dispositions also end at a crash.
- **In-process hosted links** acknowledge synchronously: acceptance returns the level the
  receiver's append reached (`MH:1275-1281` is today's tee). Both sides append to one
  physical log per host, so a surviving receiver record implies a surviving sender input
  record (prefix property: a torn trailing record is dropped, `[KBLK-12]`, `durability/BatchedFileJournal.kt:45`) **[inference]**. `O` retains nothing on this path
  unless the required level exceeds the returned one; it then retains until the receiver's
  stream reports `stableThrough` past the record.

**Retention: stable before transmit [decided].** On an asynchronous segment the sender's
`O` layer retains, per recipient, every positioned frame and pull frame that recipient has
not yet acknowledged at the required level:

- **A frame is transmitted only once its determining record is `STABLE`.** `O` appends
  `Retained(position, frame)` to the sender's `D` and hands the frame to the transport only
  after `stableThrough` has passed that record. On a `SYNCHRONOUS` stream that is at once; on
  a `BATCHED` stream the frame waits for the next group sync (latency up to the sync
  interval, no extra sync). The record also carries the lane counter's advance, so recovery
  restores the counter as the maximum of the checkpointed counter and the highest retained
  `seq`. A crash at any point therefore never leaves a transmitted frame that neither side
  holds; a `TAIL` gap (COH §3.8) loses only frames no receiver has seen, and the sender's
  upstreams still retain the inputs behind them, which they released only at `STABLE`.
- **Exception**: a sender that declares replay determinism (COH F9) may skip the per-frame
  append. Its determining record is then the input record whose replay re-derives the frame
  with the same position (P2), and the same gate applies: no transmit before that input is
  `STABLE`. Otherwise a `TAIL`-gap succession would re-derive the frame under a fresh epoch
  that the receiver takes as new. This is per-cell durability's condition 3 (93 R8):
  deterministic, or log the output.
- **Declared opt-out**: a link may transmit at `APPENDED` for latency. It then carries the
  ceiling *a frame transmitted before the sender's sync is lost if both sides crash before
  syncing* (L7), and it is refused into an enforcing inlet (§5.3).
- A volatile sender's retention is volatile; its output dies with it, and its successor is a
  succession (fresh epoch), so no receiver can mistake a re-issued position for an old one.
- On reconnect or relocation, `O` resends retained frames in order (I-P2), then resumes.
- A durable sender that takes succession with retention surviving in its stable prefix
  resends those frames under their old positions **before** the `ReBaseline` that retires
  their epoch; FIFO puts them ahead of the fence (§5.7).

**Unacknowledged frames at unlink, death and retirement [decided].** This table is the one
both notes use; COH §3.6 refers to it. `[13-NOLOSS-01]` requires an accepted message to be
delivered or durably parked (13:202-205), and the sender's link is its owner of record
until acceptance at the required level (93:4911), so closing a link does not release
retention.

| Event | Disposition of each unacknowledged frame |
|---|---|
| unlink, receiver reachable | `EdgeClose` follows the data; the unlink completes when the receiver acknowledges through it at the required level |
| unlink or reconnect, receiver unreachable | stays retained in `O` (durably, if the sender has `D`); the pending unlink is reported |
| receiver reported dead | stays retained: a death report does not release it, because the ref may be re-created. Resent in order to whatever instance is re-created at the same ref, by continuation or by succession (a successor that lost the frame needs it; one that has it drops it as a duplicate or, after a fresh `ALL` start, absorbs it under L7's ceiling) |
| sender relocates | travels in `O`'s capsule (COH H4) and is resent from the new location |
| receiver ref retired, or management unlinks with `discard` | refused and dead-lettered from `O` with a report, exclusive payloads discharged through the dead-letter store (`MH:1904-1915` pattern; COH E1) |
| `O`'s storage exhausted | the outlet backpressures with `Stall`; nothing is dropped |

**[declared]** Retention is bounded by storage but unbounded in time while a receiver is
unreachable and its ref not retired.

**Router segments [conditional — M6].** Today the partition router holds no durable state
(`[24-SHARD-03]`, 24:1628), so it cannot retain router-to-remote-shard output. Under M6's
recommendation (c) the router of a set with an `Effectful` or non-idempotent durable shard is
a `Durable` term (COH §3.2) and retains in its own `O` like any sender. Routers of mergeable
sets stay volatile, and their router-to-remote-shard segments are convergence-only, which is
all a mergeable shard needs.

**Refusal [decided].** A link from an upstream into a non-idempotent durable inlet across
an async segment with no retention is refused at link time; into a mergeable inlet it is
the convergence-only boundary 93 R7 declares.

**Wire negotiation [decided].** A populated unknown field makes an older decoder throw
(`ignoreUnknownKeys` is unset; `WC:303-320`, pinned by `StallNoticeWireCompatTest`), so a
new sender must not populate `MessageContext.position` toward an old receiver. The
negotiation uses one carrier old peers already decode and ignore:
`WireFrame.natures: List<Int>` (`WC:115`). It is decoded on every frame; on data frames it
is never read (`WC:656-671`), and on protocol frames `natureVectorFromWire` skips an axis
index it does not know (`nature/…/ContractDescriptor.kt:242-254`).

- **Offer** (sender → receiver): the pair `(POSITIONS_AXIS, 1)` in `natures` on every frame
  of a lane until the lane is settled. `POSITIONS_AXIS` is a wire-only index above every
  `NatureAxis` ordinal, stripped by the new codec before nature reconciliation.
- **Accept** (receiver → sender): a new protocol message
  `PositionsAccept(sourcePort, target, level)`, sent only in reply to an Offer, so only a
  peer that offered — a new peer — ever receives the new type.
- **Begin** (sender → receiver, in-band FIFO): a new protocol message
  `PositionsBegin(sourcePort, target, epoch, lane, firstSeq, preBeginCount)`, sent only after
  an Accept. From the next frame on, positions are populated.
- Frames sent before `PositionsBegin` are unpositioned on the wire but carry the Offer. The
  sender has already allocated their positions and retains them in `O` under the rule above;
  only the field is unpopulated. A new receiver **holds** them (`HELD`, never acknowledged,
  not disposed). The lane is FIFO and dense, so on `Begin` it assigns them
  `firstSeq − preBeginCount … firstSeq − 1` and disposes of them in order. The hold lasts
  one round trip.
- **The hold is scoped to one transport session.** If that session ends before `Begin` —
  sender death after `Accept`, disconnect, or a new session for the same `(sourcePort,
  target)` arriving, which closes the old one first — the receiver resolves the hold before
  admitting anything from a new session: at an enforcing inlet it **discards** the held
  frames (the link was admitted only because the sender retains them, so they return,
  positioned, by resend); at a mergeable inlet it disposes of them as legacy frames, and a
  later positioned resend is absorbed as a convergent duplicate. Nothing held is ever
  assigned a position it was not given by a `Begin` of its own session.

The first frames in each upgrade direction:

| Sender → receiver | First frame on the lane | Receiver does | Thereafter |
|---|---|---|---|
| new → old | data, `natures=[POS,1]`, no `position` | decodes it (known field, ignored); disposes as today; sends nothing new | unpositioned; the sender, having no Accept, treats the lane as legacy for retention and reports it once its pre-acceptance retention cap is reached |
| old → new | data, `natures=[]`, no `position` | sees no Offer: lane is legacy. Mergeable inlet: as today. Enforcing inlet: M13. Never sends `PositionsAccept`. | unpositioned |
| new → new | data, `natures=[POS,1]`, no `position` | holds it; sends `PositionsAccept` | sender sends `PositionsBegin`, then positioned data; receiver positions the held frames retroactively |

A new receiver toward a legacy sender never claims dedup it cannot perform: a link into a
non-idempotent durable inlet is refused, as it would be promised a dedup the peer cannot
carry. Negotiation re-runs on each new session. `WireFrame.version` is not usable here: it
is never emitted (`WC:53-67`).

**Wire cost [open].** ~40 bytes binary per frame, more in JSON. `PositionsBegin` already
announces `(epoch, lane)` once per lane; sending only `seq` or `pull` per frame afterwards
would cut most of the cost. To be justified by measurement.

**In-band markers [decided; the one definition both notes use].** `EdgeOpen`, `EdgeClose`,
`Progress`, `Stall`, `FlipFence`, `FlipDrained` and the negotiation messages are protocol plane
and consume no `seq`; numbering them would bring back §5.4's hazard. They are **not** on the
management band (COH §2.4, H7): they ride the lane's FIFO channel (13:194-198) and are
scheduled in lane order with its data (COH H8). FIFO orders acceptance only, so `FlipFence`
and `FlipDrained` are additionally **barriers over dispositions**: the receiving term holds
the marker in its custody behind the frames accepted ahead of it on that lane, and processes
it only once each of them has a `STABLE` terminal disposition (§5.3); nothing behind it on
that lane is processed first. The stall is at most one sync of the receiver's stream. The other
markers carry no cursor change and need no barrier.

### 5.9 Relation to waves [decided]

Positions are strictly a second plane.

| Concern | Plane |
|---|---|
| glitch-free completeness, per-edge watermarks | wave (unchanged) |
| `ReBaseline` supersession, dead-lane identity | wave epoch, shared with positions |
| merge-tag frontiers, `StateRequest.since`, gossip multipath safety | tag (unchanged) |
| `Effectful` already-acted test (`[24-DUR-05]`) | **position**: replaces the wave-keyed `processedFrontier` for positioned frames (conditional — M16) |
| baseline discharge (`[24-DUR-07]`/`[24-DUR-08]`) | **position** (`pull` id) |
| duplicate absorption at non-idempotent durable inlets; acknowledgement, retention, handoff | **position** (new) |

The planes share one thing, the outlet epoch, whose continuation and succession rules (93
I-14) are the same for both.

---

## 6. The host seam

The host contract is COH §5 (primitives H1-H9, exceptions E1-E3); this section states only
what positions need from it. They need no semantics: H6 routes a frame FIFO per link by
calling the receiving term's `accept` synchronously, and the receiver's `D` appends the
frame, with its position, to an H1 stream before delivering it inward — **append before
delivery**. Today that append is the host's journal tee (`MH:1275-1281`); in COH's target it
is `D.accept` (COH §4). The duplicate decision moves out of `ManagedHost.deliver`
(`MH:1889-1958`) into the inlet and `X` (§11 step 6).

**Transitional exception: host intake coalescing (COH E3).** Merging payloads
(`IntakeControl.kt:73-91`) is a cell-semantic act the host performs today. It moves into
`P` (COH §4, migration step 7); until then the host keeps it under §5.3's rules: never at
enforcing inlets, never reordering a lane, carrying every merged position.

---

## 7. Positions under every mode change

**C** = continuation (epoch and lane counters adopted, receivers' frontiers kept).
**S** = succession (fresh epoch; old epoch announced by `ReBaseline`).

| Mode change | Kind | Sender lanes | Receiver frontier | Announced |
|---|---|---|---|---|
| suspend / resume, drain | C | unchanged; parked frames keep positions | unchanged; nothing advances while parked | nothing |
| migrate | C | lane counters and `O`'s retained frames ride the capsule | rides with the inlet (I-P4); fixes the 8g7kg migrate half; the source is durably fenced before the target activates, so two locations never admit at once (COH H4) | nothing |
| durable recovery | C (`OutletWaveState.kt`) | restored; replay re-issues the same `seq`; retained output restored | restored; own log first (I-P2) | nothing |
| RESTART, durable | **conditional — M11.** 31:127-130 and 93 R9 (93:2867-2870) restore the checkpoint and replay the tail (C); 23:209-213 and 93 I-22 R6 (93:8416) forbid re-driving the invocations that produced state. Under M11's recommendation (c) it is C only for a term with no inlet that accepts an exclusive payload (COH §2.4), S otherwise; **until M11 is decided it is S**, as the code does today (`MH:2006-2014` mints fresh on every RESTART) | C: as durable recovery, the tail replayed with its original positions. S: as non-durable RESTART | C: as durable recovery; the failing frame is skipped by a logged `Skipped(position)`, which advances `disposed`. S: as non-durable RESTART | C: nothing. S: `ReBaseline` |
| RESTART, non-durable | S (`MH:2006-2020`) | fresh epoch | `applied` reset with inner state; `disposed`, `pullDischarged` kept (outer `X`) | `ReBaseline`. Re-running the inbound handshake: M14 |
| replica spawn | S (new instance, new refs) | fresh | inbound: joins the set lanes (§3.2) at the next position it receives; `received` and follower retention start there (COH §3.1); catch-up as today | `EdgeOpen` |
| replica leave | — | lanes closed | entries closed, then LRU | `EdgeClose`; a crash is reported, and retained output follows §5.8 |
| T0/T1 promotion | C | promotion replaces only the leaf inside `S` (COH §3.4); `S` owns the lane counters, so they continue with no adoption and no relink | `applied` continues with the captured state; `X` (outside the swap) keeps `disposed`, `pullDischarged`, `acted` | nothing (93 I-11) |
| T2 promotion | S | `S` mints a fresh epoch and supersedes the term's own (incumbent's) lanes, which fixes computenet-lzfr0 by construction (COH §3.4) | fresh `applied`. `disposed`, `pullDischarged`, `acted` follow COH's effect-identity check (F11, §3.4): kept for equal declared identities backed by the candidate's compatibility assertion; for a different or undeclared identity of an `Effectful` side, the swap is vetoed unless the request declares `effectFrom = COMMIT`, and then `X` restarts at the current `disposed` frontier, never empty | `ReBaseline` |
| repartition | per-range C | router: none (forwarder) | §5.6: scoped cursors at both shards, merge at `FlipDrained` [conditional — M6]; transaction per COH §3.2 | `FlipFence`, `FlipDrained` |
| link / late join | new lane | starts above `laneFloor` | new entry on first frame; catch-up is a pull baseline | `EdgeOpen` |
| unlink | — | counter kept; retention per §5.8 | entry closed | `EdgeClose` |
| relink of the same pair | C of the lane | resumes | resumes | `EdgeOpen` |
| compacted-recovery re-handshake (`ApplyContext.kt:138-149`) | C | restored, never re-created | restored | today: catch-up on every link (computenet-n2jwi); M12 |
| wire reconnect, epochs unchanged | C | negotiation re-runs (§5.8), then resend of every retained, unacknowledged frame per recipient | an unresolved pre-`Begin` hold is resolved first (§5.8); duplicates dropped | `PositionsAccept`/`PositionsBegin` |
| wire reconnect after volatile peer loss | S | fresh epoch | old epoch retired on `supersede=true`; kept live on `supersede=false` (§5.7) | `ReBaseline` / death report |

---

## 8. Limits

| # | Limit | Treatment |
|---|---|---|
| L1 | **Non-determinism re-issues a live position for different content** (wall clock, randomness, scheduler-dependent coalescing, supervision drops, an ingress counter restarted low) | **[declared]** A position names a frame, not its bytes. Enforcement is limited to `Effectful` and non-idempotent durable inlets, so mergeable cells keep convergent absorption. Where the receiver's intake log or the sender's retention still holds the frame, a mismatch is **detected, refused and reported**; elsewhere it is undetectable, as with today's wave frontier. A durable upstream feeding an enforcing inlet must be deterministic or log its output (93 R8); once a determinism marker exists, the absence of both is refused at link time. |
| L2 | Contextless and management frames have no position | **[declared]** Unchanged: `[24-DUR-06]` refusal at `Effectful`, no dedup elsewhere. |
| L3 | **Exactness rests on per-lane FIFO and one path per lane and scope** | **[declared]** A forwarder that reorders or duplicates a lane is a defect outside §5.6. Gap discipline makes violations loud on dense lanes. |
| L4 | `Effectful` + `Stateful` in one cell (`concord/corpus/DISPUTES.md:1232`) | **Not solved.** `applied` and `disposed` state the problem precisely; a wrapper cannot separate an effect buried in a handler. Splitting the cell remains the recommendation. |
| L5 | Act→advance window (`[24-DUR-09]`); external-idempotency ceiling (93 I-7) | **[declared]** At most one duplicate act per crash. |
| L6 | Bounded GC (`dead`, `closed`, `pullDischarged` caps) | **[declared]** The loss mode is a duplicate, never a suppression. |
| L7 | Async segments without durable acknowledgement; volatile receivers; links that transmit at `APPENDED` | **[declared]** Convergence-only (93 R7); refused for enforcing inlets. A volatile receiver can lose `HELD` frames on its own crash; an `APPENDED`-transmit link loses a frame if both ends crash before syncing. |
| L8 | Repartition of an `Effectful` range across a crash inside the flip | **[declared]** Unsupported until the router's flip state is durable (computenet-d2lue; M6). |
| L9 | No consistent cut | **[declared]** Positions make "downstream input ≤ upstream output" checkable per link; they do not enforce it. |
| L10 | Gaps on non-dense lanes (filtered, ingress) | **[declared]** Not detectable; loss detection there relies on a reliable, ordered transport. |
| L11 | Volatile lanes; direct synchronous lanes | **[declared]** No P2 across recovery, or no ordering guarantee; refused into enforcing inlets. |
| L12 | Legacy journals | **[declared]** A frame journaled before positions has none; it is checked against the legacy wave frontier for its epoch, inheriting wlwjw for that tail only. No positioned entry is ever fabricated from it (I-P5). |
| L13 | Legacy wire peers | **[declared]** Unpositioned; treatment at enforcing inlets is M13. A new sender's pre-acceptance retention toward an old receiver is capped and the lane is then reported as legacy. |

---

## 9. Proposed requirement statements (EARS, unnumbered)

- When an outlet delivers a live emission to a target, the outlet shall stamp it with its
  current emission epoch, the target's address-derived lane key and the next sequence
  number of that lane, allocated atomically with the receiver's acceptance of the
  delivery; a target suppressed by disclosure, or an acceptance that fails, shall consume
  no sequence number.
- When a stamped ingress sends a hosted invocation, the ingress shall stamp that invocation
  with its own sequence number on a replay-stable ingress lane.
- When an outlet delivers a catch-up baseline, the outlet shall stamp it with a baseline id
  that is never re-issued and shall not consume a lane sequence number.
- When a cell emits in response to a positioned invocation, the framework shall not copy
  the incoming position onto any outgoing delivery.
- While a hop forwards a delivery without emitting, the hop shall preserve its position,
  shall not reorder a lane's deliveries, and shall not deliver one position into one inlet
  twice within one frontier scope.
- When a target port belongs to a replica-set instance, the outlet shall stamp one position
  per logical delivery on a lane keyed by the set's logical address, and shall deliver that
  same position to every covering instance.
- If a replica follower suppresses a delivery for lack of effect authority, then the
  follower shall not advance its disposed cursor for it.
- If a live-positioned invocation at an `Effectful` inlet is at or below that inlet's
  disposed cursor for its lane, then the inlet shall suppress it and discharge its exclusive
  payloads.
- If a live-positioned invocation at an inlet of a durable cell not declared idempotent is
  at or below that inlet's applied cursor for its lane, then the inlet shall drop it.
- When an inlet refuses, admission-drops or dead-letters a positioned delivery, the inlet
  shall advance its disposed cursor in the same durable record as the report.
- If a dense lane delivers a sequence number above the next expected one, then the receiver
  shall not dispose of the delivery, shall report the gap, and shall not acknowledge past it.
- When a cell recovers by continuation, the cell shall admit no delivery other than its own
  logged tail until that tail has been replayed.
- Before an asynchronous sender transmits a positioned delivery toward an enforcing inlet,
  the sender shall have stably recorded either the delivery as retained or, if it declares
  replay determinism, the input whose replay re-derives it.
- When a receiver acknowledges deliveries, the acknowledgement shall name the recipient and
  frontier scope, and shall be cumulative only on a lane on which every position goes to that
  recipient.
- While a retained delivery is unacknowledged at the link's required level, the sender
  shall keep it across unlink, reconnect, relocation and the receiver's death, and shall
  release it only on such an acknowledgement or by a reported dead-letter when the receiver
  ref is retired or the link is unlinked with `discard`.
- If an inlet receives a `ReBaseline` with `supersede = true` naming an epoch, then the inlet
  shall fence that epoch's later deliveries; a `ReBaseline` with `supersede = false` shall
  fence nothing and shall leave that epoch's entries live.
- When a lane delivers a `FlipFence` or `FlipDrained` marker, the receiver shall not process
  it until every delivery accepted ahead of it on that lane has a stable terminal disposition.
- While the host or a `Suspendable` layer coalesces queued deliveries, it shall coalesce
  only into inlets that do not enforce dedup, shall not reorder any lane, and shall carry every merged position.

---

## 10. Decisions this design asks the maintainer to make

These change decided rules or their reasoning. One numbering serves both notes:
**M1-M11** are argued in COH §7, **M12-M16** here. No decision appears in both. The rest of
this design works under either answer to each, except where noted.

**Decisions argued in COH §7 that this note depends on.**

- **M6 — the repartition protocol (`[24-PART-04]`, `[24-SHARD-03]`).** §5.6 needs the moved
  state as a range-handoff record an `Effectful` owner does not act on, a durable `p_begin`
  per boundary lane, the in-band `FlipFence`/`FlipDrained` markers, scoped cursors, and the
  router's flip state in its own `D` (computenet-d2lue). COH recommends (c): adopt the
  protocol for partition sets with an `Effectful` or non-idempotent durable shard, keep
  ordinary catch-up for mergeable ones; fallback (a): keep both rules and refuse
  repartition of `Effectful` ranges. §5.6 and §5.8 "Router segments" assume (c).
- **M11 — durable RESTART** (93 R9 versus 23 / 93 I-22 R6). §7's durable-RESTART row is
  continuation only under M11 (c), and then only for terms that accept no exclusive payload;
  until decided it is succession.
- **M9 — PN-17's exactly-once claim.** The replica-set lanes of §3.2 are its precondition.
- **M1** (`Effectful` × `Stateful`) is L4 here; **M2**, **M5** (per-term mobility and
  supervision) are why the frontier rides the capsule (§5.6 "Whole transfer").

Host intake coalescing, formerly a decision of this note, is not a maintainer decision: it
moves into `P` per the architect's target, with the host keeping it as transitional
exception E3 until then (§5.3, §6; COH §5.3).

**M12. When is an onLinked catch-up owed? (computenet-n2jwi)**

The compacted-recovery re-handshake fires catch-up on every folded link, so it may re-fire
`Effectful` sinks.

- *a — today's rule*: catch-up on every link install.
- *b*: catch-up only when the receiver holds no frontier entry for the sender's epoch.
- *c*: keep a; re-handshake catch-ups are pull baselines with fresh ids, so they fire at an
  `Effectful` sink once per recovery, as `[24-DUR-07]` decides, and are deduplicated against
  later re-delivery.
- *Recommendation: c now; b only after evidence.* b reverses 93 R6's reasoning (93:2842-2848:
  catch-up exists because state is restored separately from the link), and "holds a frontier
  entry" proxies "state reflects the sender" only where both are captured together (I-P4).
  The checkpoint carry and the journal-less intermediate cell (computenet-dshry,
  24:1286-1291) violate that today. b needs n2jwi's failing test plus an adversarial test
  with a volatile intermediate.

**M13. A legacy wire sender feeding an `Effectful` inlet.**

- *a*: refuse the link. *b*: admit it unpositioned under today's wave rule, marked and
  reported as degraded.
- *Recommendation:* b during a rolling upgrade, a once every peer offers positions. b
  promises no less than today; a turns an upgrade into an outage; a *silent* fallback is
  unacceptable either way.

**M14. Should a non-durable RESTART re-run the inbound handshake?**

Without it a cell whose `applied` state was reset never catches up again (93 N9×N18
residual). *Recommendation:* yes, as a pull-baseline catch-up. `disposed` survives in `X`, so
the sink does not re-fire for deliveries it disposed of, but it does fire for the caught-up
state, as `[24-DUR-07]` decides. The same applies to a durable RESTART while it is a
succession (M11 fallback). COH's succession ("rebuild by catch-up", COH §1, §2.4) assumes
this recommendation; under "no", a restarted mergeable cell stays behind until new input.

**M15. Who declares idempotency?**

§5.3 enforces at durable inlets "not declared idempotent"; the repo has a de facto
replay-stable idempotent vocabulary (24:1333-1336) but no marker. *Options:* a contract-level
marker, a nature axis, inference from the merge law. *Recommendation:* a nature axis; it
already travels on `EdgeOpen`, so link-time refusals can be checked on both sides. Decide the
determinism marker of L1 (G-59's residual) with it: COH already places a replay-determinism
declaration in the term manifest (COH §2.7, F9); M15 decides only its carrier and the
idempotency marker beside it.

**M16. Re-key `[24-DUR-05]` and `[24-DUR-08]` on delivery positions.**

- *Options:* keep the wave key, with wlwjw and the cross-arm reorder as declared defects; or
  re-key positioned frames as §5.3-5.4 do, keeping the wave rule for unpositioned and legacy
  frames.
- *Recommendation:* re-key. The wave key is provably wrong on live traffic (§1), and the
  `[24-DUR-07]`/`[24-DUR-08]` reasoning (24:1414-1430) carries over to an exact baseline id.
  A replay-derived frame now advances `disposed` instead of entering the discharged set;
  each still acts at most once.

---

## 11. Migration path (each step lands alone)

1. **Failing tests first**, parked under their bead ids: `EffectfulSameWaveTest` (diamond,
   double emit, cross-arm reorder; computenet-wlwjw); `EffectfulMigrateRefireTest`,
   `EffectfulRepartitionRefireTest` (computenet-8g7kg); `LinkPositionConcurrencyTest`.
2. **Stamp positions, consume nothing.** `DeliveryPosition`, `MessageContext.position`;
   address-derived lane keys (including the runtime's cross-node edges); per-lane `seq` with
   the lane lock and acceptance rollback; clear on the reactive copy; pull ids on
   `baselineTo`; per-invocation ingress stamping with a stable ingress lane; set-keyed lanes
   for replica-set targets (§3.2); context kept in the router's flip buffer. The wire never populates the field. Behaviour is unchanged.
3. **Capture lane counters**: `laneHighWater`, `laneFloor` in a new additive journal record,
   as `RECORD_OUTLET_WAVE` was added (`HD:163-167`). An old journal replays unchanged.
4. **Track frontiers in the inlet**: `received`/`applied`/`disposed` (and `acted` where
   needed) advancing on acceptance or disposal, no
   enforcement; captured in checkpoint, migration capsule and promotion transfer.
5. **Switch `Effectful` suppression to positions** for positioned frames; unpositioned frames
   keep `processedFrontier` and the discharged set (L12). Refuse host coalescing at
   `Effectful` inlets. wlwjw's tests go green.
6. **Move enforcement out of the host**: the suppression arm (`MH:1889-1958`) moves into the
   inlet and `X`; `HostDurability.processedFrontier` stays read-only for legacy journals. The
   migrate half of 8g7kg goes green.
7. **Absorption enforcement** for non-idempotent durable inlets, with the recovery admission
   fence (I-P2). Depends on M15.
8. **Dense-lane gap discipline** and diagnostics.
9. **Wire**: the `natures` Offer, `PositionsAccept`, `PositionsBegin` and retroactive
   positioning; legacy policy (M13); `DeliveryAck` with the four levels and per-link required
   level; `O` retention with stable-before-transmit and the unlink/death dispositions;
   link-time refusals. Lands with COH migration step 12 (`O`) and needs its step 3 (H1
   levels).
10. **Partitioned handoff**, after computenet-d2lue and M6, as part of COH migration step 8
    (the router under `D` with records R1-R7): `p_begin`, in-band
    `FlipFence`/`FlipSettled`, the range-handoff record, scoped cursors at both shards,
    `FlipDrained`, the veto. The repartition half of 8g7kg goes green.
11. **GC**: `supersede=true` → `dead` (with computenet-kxdjx's snapshot fix),
    `supersede=false` → `closed`, the closed-lane LRU, `laneFloor` eviction, caps.

Spec edits (24 §Effectful and §Partitioned state, 22 §MessageContext, 93 I-7 R8) are a
separate documentation task after the M-decisions (§10 here, COH §7).

---

## 12. Test plan

Kernel tests use `testkit` `SimWorld`/`awaitUntil` with pinned seeds. Each row names what it
proves and the failure it must catch.

**Stamping and exactness**

| Test | Proves | Must catch |
|---|---|---|
| `EffectfulSameWaveTest.diamondArmsIntoOnePortBothAct` / `.doubleEmitBothAct` | P1, wlwjw | second frame suppressed (today) |
| `EffectfulSameWaveTest.crossArmReorderLowerCounterActs` | P4 vs per-source high-water | `S:4` after `S:5` suppressed (today) |
| `LinkPositionStampTest.convergentMediatedLanesBothAct` | I-L1, lane key | two mediated exposures collide on `(epoch, seq)` |
| `LinkPositionStampTest.denseUniqueMonotonePerLane` (property, 100 seeds) | P1, P4, P5 | a duplicate or gap on an emitting lane |
| `LinkPositionStampTest.reactiveCopyDoesNotLeakPosition` | §5.2 rule 3 | inbound position seen downstream |
| `LinkPositionStampTest.disclosureSuppressedTargetConsumesNoSeq` / `.refusedAcceptanceReturnsSeq` | P5, rule 2 | a gap after suppression or an intake refusal |
| `LinkPositionStampTest.runtimeCrossNodeLaneKeyIsReplayStable` | §3.2 | lane key changes across re-apply |
| `LinkPositionConcurrencyTest.pauseBetweenAllocateAndSendKeepsFifo` (two emitting threads, a barrier between allocation and send) | rule 2, I-P2 | `seq 2` accepted before `seq 1` |
| `ActorIngressPositionTest.twoCallsInOneDriveBothAct` / `.ingressLaneStableAcrossConnectorRecreation` | rule 6 | second call suppressed; lane changes with a new connector |

**Disposal, recovery and baselines**

| Test | Proves | Must catch |
|---|---|---|
| `DisposedCursorTest.refusedThenNextActs` / `.refusedRedeliveryDoesNotAct` | §5.1 `disposed` | position 2 treated as a gap after a refusal; refused 1 acts on redelivery |
| `DisposedCursorTest.crashBeforeRefusalRecordRedecides` | §5.3 durability table | a refused frame acted on after a crash |
| `LinkPositionReplayTest.durableRecoveryReissuesSamePositions` | P2, P3 | replay mints a different `seq` |
| `LinkPositionReplayTest.unreproducedCatchUpDoesNotShiftLane` | P6 | a live frame suppressed or re-acted after recovery |
| `LinkPositionReplayTest.compactedRehandshakeRestoresLaneCounters` | rule 7 | lane re-created at a different base |
| `LinkPositionReplayTest.rbDuplicateDroppedAtNonIdempotentDurable` | enforcement | double count |
| `LinkPositionReplayTest.reDerivationCannotOvertakeOwnTail` | I-P2 fence | own-log `seq 6` dropped after re-derived `seq 7` |
| `CoalescePositionTest.sameLaneMergeCarriesRange` / `.noMergeAcrossInterveningSameLaneFrame` / `.enforcingInletNeverCoalesced` | §5.3 coalescing | a merged position lost; a lane reordered; a duplicate merged into an act |
| `BaselinePositionTest.pullBaselineActsAndAdvancesNoLane` / `.recoveredPullBaselineNotRefired` / `.pullIdNeverReissuedAfterRecovery` | §5.4 | live frame suppressed; catch-up re-fired; new reply suppressed |
| `EffectfulTopologyRecoveryStormTest` (computenet-n2jwi) | M12 | under c: one firing per recovery; under b: volatile downstream still caught up |

**Mode changes and handoff**

| Test | Proves | Must catch |
|---|---|---|
| `EffectfulMigrateRefireTest` | P7 whole transfer | re-fire at the destination |
| `EffectfulRepartitionRefireTest.alreadyActedNotRefiredAtNewOwner` | §5.6 step 5 | re-fire (8g7kg) |
| `EffectfulRepartitionRefireTest.parkedFrameBelowOldOwnerHighWaterStillActs` | `p_begin`, not A's high-water | parked R-frame suppressed |
| `EffectfulRepartitionRefireTest.splitBoundaryFrameBothSlicesTakenAtGainer` (B owns `s`, gains `r`; one frame holds both) | step 4 scopes | the parked `r` slice dropped as a duplicate of the `s` slice |
| `EffectfulRepartitionRefireTest.crashBetweenSlicesKeepsBothDispositions` | step 4 durability | one slice lost or re-acted after B crashes between them |
| `EffectfulRepartitionRefireTest.fenceSettlesOldOwnerBeforeShed` | step 2 | a flip completes with A still holding undisposed R-frames |
| `EffectfulRepartitionRefireTest.transferRecordDoesNotAct` / `.flipBufferKeepsContext` / `.scopedEntriesMergeAtFlipDrained` | steps 3, 4, 6 | moved-in state fires; slice loses its position; scoped entries outlive the flip |
| `EffectfulRepartitionRefireTest.abortReleasesParkedToLoserAgainstRScope` (A's stable cursor already above `p_begin`) | step 4, loser side | a released R-slice dropped against A's stable cursor |
| `EffectfulRepartitionRefireTest.fenceWaitsForQueuedRSlice` / `.drainMarkerWaitsForQueuedReleasedSlice` (marker accepted while a slice is still queued) | barrier markers | a queued slice dropped against a frozen or folded cursor |
| `EffectfulRepartitionRefireTest.releaseCursorAdvancesOnlyOnStableAck` (both crash after release, before B syncs) | step 6 | the released slice lost; release resumes past it |
| `EffectfulRepartitionCrashMidFlipTest` (with d2lue; COH's `PartitionSetFlipRecoveryTest` drives the crash points) | step 8 | parked loss or double act |
| `ReplicaSetLaneTest.instancesShareOnePositionPerLogicalDelivery` / `.partialAcceptanceKeepsSeqAndRetainsForRefusedInstance` | §3.2 set lanes | instances' positions differ; a `seq` returned after another instance accepted it |
| `RestartFrontierSplitTest.nonDurableRestartKeepsDisposedResetsApplied` | §5.1 split | re-fire after RESTART, or no catch-up |
| `PromotionPositionTest.t1CandidateContinuesLanes` / `.t2DifferentEffectIdentityVetoes` | §7 | dedup break across the swap; inherited acts for a different sink |
| `PullMergeRebaselineTest.oldEpochStragglerStillMerged` / `.supersedeFalseLaneStaysLive` | §5.7 | a `supersede=false` straggler fenced; its entry evicted as closed and a retransmit re-acted |
| `DeliveryFrontierGcTest.supersedeTrueFencesDeadEpoch` / `.evictionOnlyDuplicatesNeverSuppresses` | §5.7 | straggler acted; eviction suppresses |

**Wire, acknowledgement and retention**

| Test | Proves | Must catch |
|---|---|---|
| `WirePositionCompatTest.newSenderOldReceiverDecodesOffer` (pattern of `StallNoticeWireCompatTest`, old codec build) | §5.8 new → old | old decode throws |
| `WirePositionCompatTest.oldSenderNewReceiverNeverReceivesNewType` | §5.8 old → new | a `PositionsAccept` sent to a peer that did not offer |
| `WirePositionCompatTest.preBeginFramesPositionedRetroactively` | §5.8 new → new | held frames disposed out of order or with wrong positions |
| `WirePositionCompatTest.sessionEndsBeforeBeginResolvesHold` (sender dies after `Accept`; reordered reconnect) | §5.8 session-scoped hold | held frames stranded, or positioned by another session's `Begin` |
| `WirePositionCompatTest.nonIdempotentDurableRefusedToLegacySender` | §5.8 | silent fallback claiming dedup |
| `WirePositionParityTest` (in-process vs two-host `:wire`) | P10 | divergent suppression counts |
| `DeliveryAckRetentionTest.crashAfterTransmitBeforeCheckpointResends` (non-deterministic sender) | stable-before-transmit | a frame neither side holds |
| `DeliveryAckRetentionTest.batchedSenderTransmitsOnlyStableRetained` / `.deterministicSenderWaitsForStableInput` (both ends killed before sync) | STABLE-gated transmit | a transmitted frame lost; one re-derived under a fresh epoch acted twice |
| `DeliveryAckRetentionTest.filteredLaneAckReleasesOnlyThatRecipient` (one ingress lane, seq 1 and 3 to A, 2 to B) | ack keyed by recipient | B's seq 2 released by A's ack |
| `DeliveryAckRetentionTest.heldAckDoesNotReleaseDurableLink` / `.appendedAckReleasesOnlyWithDeclaredBatchedCeiling` | ack levels | release on a volatile or unsynced acceptance |
| `DeliveryAckRetentionTest.deadReceiverRecreatedBySuccessionStillReceivesRetained` / `.succeedingSenderResendsRetainedBeforeReBaseline` | §5.8 dispositions | retained frames dead-lettered at a receiver succession; resent frames fenced as dead-epoch stragglers |
| `DeliveryAckRetentionTest.unlinkUnreachableKeepsRetention` / `.retiredReceiverDeadLettersWithReport` | §5.8 dispositions | retained frames dropped at unlink or death |
| `DeliveryAckRetentionTest.lostAckResendsSamePositions` / `.gapWithholdsAckAndTake` / `.contentMismatchRefusedWhereRetained` | P8, I-P3, L1 | loss; silent drop after a gap; re-issued content silently dropped |

**Concord.** Scenarios for the §9 statements belong in `concord/corpus/24-data-cells/` once
spec text exists; the cross-arm reorder, the convergent mediation and the split boundary
frame are good implementation-neutral witnesses.

---

Reconciliation history and the response to the adversarial review:
[`reconciliation/per-link-positions.md`](reconciliation/per-link-positions.md).
