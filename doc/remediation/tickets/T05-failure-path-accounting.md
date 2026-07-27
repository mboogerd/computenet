# T05 — Failure-path accounting sweep

**Phase 1, step 2 of 3 · CONTINUE the T04 session · Sonnet 5**
**Prereq**: T03 merged (the `Gate` policy is deleted — two accounting fixes
this ticket would otherwise need are moot), T04 complete in this session.
**Write scope**: `kernel/src/main/kotlin/civictech/cell/{host,port,proxy,link,data/op,durability}`,
`wire/src/main/kotlin` (one counter), matching tests,
`concord/corpus/DISPUTES.md` (one entry).
**Do not touch**: `replication/`, `partition/` internals beyond the named
`sliceTo` call-path (T07 owns those files), `data/delta` (T07).

## Problem

Error-handling + DRY + conceptual audits (verified 2026-07-27 at `742f7ca`).
The recurring shape: **a well-designed accounting mechanism exists, and one or
two call sites bypass it.** `sanitizeForDeadLetter` exists but `Admit` doesn't
call it; `parkedDrainedOnTeardown` exists but the cold-inlet tail doesn't feed
it; `absorbAck()` exists at eight operators but not three others; `drainWhile`
exists precisely for resumable replay but `install()` calls `drain()`.

1. **`LocationRegistry.install()` destroys the park batch on a refused replay
   (critical).** `LocationRegistry.kt:276-283`:
   `queue.drain().forEach { check(send(location, it)) { … } }` —
   `ParkQueue.drain()` (`control/ParkQueue.kt:45-49`) snapshots **and
   clears** before anything is sent; `send` (:184-206) returns `false` on
   SATURATED/CLOSED intake. First refusal → `check` throws → every invocation
   in the batch (refused one + ordered successors, `Owned`/`Leased` included)
   is gone — not re-parked, not dead-lettered, not counted — and
   `locations[ref] = location` never executes, so the ref stays unpublished
   with its history destroyed. Violates the method's own KDoc (:163-168,
   "never blocks … never drops") and the system-wide exclusive-payload rule.
   Untracked (G-46 covers crash-time loss, not this). `ParkQueue.drainWhile`
   (:58-63) exists for exactly this and retains the remainder in order.
2. **The emit-or-absorb-ack rule exists in 5 places; 3 forget the ack
   (high).** The CP-A3 contract ("propagate a non-empty delta, else
   absorb-ack the swallowed wave, or the downstream frontier stalls
   forever"):
   - helper #1 `data/op/TaggedSetOperator.kt:36-38` (Filter/Union/FlatMap)
   - helper #2 `data/op/KeyedBinarySetJoin.kt:54-61` (JoinSet/SemiJoin)
   - inlined **without ack**: `IntersectSetCell.kt:69-72` (carries a live
     `TODO(restructure): ack divergence, owner decision pending`),
     `QuorumSetCell.kt:104` (no `absorbAck` import at all),
     `CountCell.kt:46` (KDoc :31-33 documents the gap as "preserved
     verbatim").
   A `GlitchFreeCell` downstream of any of the three can stall forever on a
   membership-neutral final wave.
3. **The ADMIT tier — the licensed drop point — has neither ownership
   discharge nor a reliable absorb-ack (high).** `port/InletPolicy.kt:68-107`:
   - dropped `Invocation` args are never inspected — an `Owned` is never
     taken/frozen, a `Leased` never released (pool slot leaks). The SPSC rule
     (`FanOutlet.kt:200-204`) means an exclusive-carrying outlet has exactly
     one consumer, so the drop is unrecoverable loss. Every other major drop
     path sanitizes (`DeadLetters.kt:46-59`, `Evolution.kt:81-86`).
   - `mintAck` (:96-107) has three silent `?: return` exits; the one at :103
     (no open link matching `ctx.sourcePort` — wire-reconstructed edges,
     `Use.fixed` producers, replayed journal frames) means the ack is never
     minted and the downstream `WaveFrontier` stalls forever.
     `WaveFrontier.kt:216-221` has a counted tripwire (`unmatchedDrops++`)
     for its analogous case; `Admit` has none.
4. **`recoverFrom` aborts mid-journal, unaccounted (high).**
   `host/HostDurability.kt:110-137`: a bare `forEach` over `journal.replay()`
   with no per-record handling. Any decode/`readObject` throw (or the
   `else -> error(...)` at :130) abandons every remaining record; `recovering`
   resets in the `finally`, and the host resumes live traffic on silently
   truncated state. Worse, `submit` is `enqueueHostedInvocation`, which
   throws `IntakeSaturatedException` at `ManagedHost.kt:397` — so on a
   durable host with an `intakeBound`, replaying a journal longer than
   high-water **deterministically aborts recovery** (nothing drains during
   the synchronous replay under the sim controller). The write path was
   thought through (`checkpoint`'s `require` at :183-190); the read path was
   not.
5. **`despawn` drains two of three park queues (medium).**
   `ManagedHost.clearSupervision` (:276-289) dead-letters + counts the
   suspended queue and the attention-parked queue — but `FanInlet`'s
   ACTIVATE-tier cold tail (`port/FanInlet.kt:85`, drained only at
   handler-install :228-234) vanishes with the object on despawn: no dead
   letter, no counter, no exclusive discharge. (The `Gate.held` sibling case
   is moot — T03 deleted `Gate`.)
6. **`sliceTo` silently no-ops for 5 of 7 delta types (high, conceptual
   audit).** `link/Interest.kt:213-229` fallthrough `else -> delta` ships
   any non-`Scoped` delta **whole** across a partial-interest link. Only
   `SetDelta`/`MapDelta` implement `Scoped`; `PnCounterDelta`,
   `WatermarkDelta`, `CounterDelta`, `ListDelta` don't — yet `PnCounterCell`
   and `WatermarkCell` are `Replicable` and can join non-`Total` meshes,
   silently breaking the "a delta a peer has no interest in never crosses"
   guarantee (:21-25) that partitioning and disclosure reasoning rest on.
7. **Silent-void delivery paths (low).**
   - `FanOutlet.at()` (`port/FanOutlet.kt:186-192`): unresolvable target →
     `Proxy.noop(clazz)` — the delivery path for `baselineTo` and every
     targeted catch-up / `StateRequest` reply. A requester that unlinked
     between request and reply gets its pull answered into the void; a
     consumer depending on the pull for its baseline starves. No counter, no
     dead letter.
   - `wire/.../WsTransport.kt:108-113`: binary frames arriving before an
     admitted hello are dropped (`ingress?.propagate`) — correct for the
     refusal case, but unaccounted.
8. **Residual per-link-ack gap (documented, not fixed here).** The two
   *legitimate* `sliceTo` null results (a `Scoped` delta that slices to
   nothing for one peer: `replication/Replication.kt:510`,
   `partition/PartitionedShardSet.kt:171`) mint no `Progress` on that edge.
   The correct fix needs a per-link ack variant (`absorbAck()` fans over all
   links) — M-effort, interacts with T07's territory. This ticket only
   **files it** (see D below).

## Solution

### A. `install()` drain safety (finding 1)

Reorder: set `locations[ref] = location; indexAdd(ref)` **first**, then
`queue.drainWhile { send(location, it) }`. A refused head stays parked in
order; the `onIntakeAvailable` hook registered inside `send` (:189) re-drives
the drain when intake reopens. The comment at :246-249 wanted drain to precede
visibility — read it, find the ordering test that pins it, and update
test+comment to the new invariant: *visibility first, so a refused replay
parks against a published location instead of destroying the batch*. Add a
regression test: park N invocations (one carrying `Owned`), publish into a
SATURATED host, assert nothing lost, order preserved, and full delivery after
the intake reopens.

### B. One `emitOrAbsorb` helper (finding 2)

1. Add a single free function in `data/op` (e.g. `Emit.kt`):
   `fun <T> emitOrAbsorb(delta: T?, isEmpty: Boolean, outlet: FanOutlet<*>)`
   — shape it so both existing helpers collapse into it (look at both
   signatures first; one function, minimal adapter lambdas).
2. Replace helper #1 (`TaggedSetOperator.kt:36-38`), helper #2
   (`KeyedBinarySetJoin.kt:54-61`), and route `IntersectSetCell` (delete the
   TODO), `QuorumSetCell`, `CountCell` through it. **This is a behavior
   change for those three: they now ack.**
3. Tests: for each of the three, a diamond topology with a `GlitchFreeCell`
   downstream — feed a membership-neutral final wave through the operator
   arm and assert the wave settles (would stall before this fix). Update
   `CountCell.kt:31-33`'s KDoc (gap closed).

### C. `Admit` discharge + tripwire (finding 3)

1. Promote `Proxy.discharge` (`proxy/Proxy.kt:116-127`) to `internal`; in
   `Admit.offer`, before `onDrop`, discharge exclusives in the dropped
   invocation's args (consume/freeze `Owned`, release `Leased`) — consistent
   with the two existing sanitizers.
2. Add a `var unackedDrops: Long, private set` counter incremented at each
   `mintAck` early return, mirroring `WaveFrontier.unmatchedDrops`.
3. Tests: drop an `Owned`-carrying invocation via `Admit` → assert discharge
   (frozen/released) and no leak; drive the :103 unmatched-link path → assert
   the counter increments.

### D. Loud partial recovery (finding 4)

1. Per-record `try/catch` in the replay loop: on failure, `deadLetter` the
   bad record (index + reason), then rethrow a new
   `RecoveryIncomplete(recordIndex, total, cause)` exception — callers must
   not mistake partial replay for complete.
2. Bypass intake gating during replay: `enqueueHostedInvocation` skips the
   SATURATED check when `hostDurability.recovering` (replay is by definition
   already-accepted work; the flag already suppresses journaling).
3. Tests: (a) journal longer than `intakeBound` high-water recovers fully;
   (b) a corrupted middle record → `RecoveryIncomplete` thrown, index
   reported, dead letter emitted.

### E. Despawn cold-inlet drain (finding 5)

Add `FanInlet.drainParked(): List<Invocation>`; in `clearSupervision`,
iterate `PortRegistry.of(cell)`'s `FanInlet`s, drain, and route each through
the existing dead-letter + `parkedDrainedOnTeardownCount` path (loop shape at
`ManagedHost.kt:569-574`). Test: spawn, deliver before `serve`, despawn →
assert dead letters + counter, with an `Owned` payload discharged.

### F. Loud `sliceTo` fallthrough (finding 6)

In `Interest.sliceTo`, when interest is not `Total` and the delta is not
`Scoped`: do **not** ship it whole. Return null and surface the refusal —
follow the file's conventions; minimally a counted, logged refusal that the
caller can observe (dead-letter seam if reachable, else a counter +
`System.err` in line with current registry style). KDoc the rule on
`Replicable`: joining a non-`Total` mesh requires a `Scoped` delta. Test:
`PnCounterCell` delta against a partial interest → refused + counted, not
shipped whole. (Implementing `Scoped` for the lattice deltas is T-deferred —
see COVERAGE.md.)

### G. Silent-void accounting (finding 7)

1. `FanOutlet.at()`: on target-miss, increment a `targetMisses` counter
   (`private set`) and log once per ref (or dead-letter if a host handle is
   reachable without new plumbing — don't build plumbing for this).
2. `WsTransport` pre-hello binary drop: increment a counter on the session
   (visible via the existing transport surface) — no behavior change.

### H. File the residual (finding 8)

Add a `DISPUTES.md` entry (or a `G-*` row in `91-gap-analysis.md`, matching
T02's format — check what T02 landed and follow it): *interest-filtered
emission on `Scoped` slices that legitimately produce null for one peer mints
no per-link Progress; downstream per-source frontiers can under-advance;
needs a per-link ack variant.* Cite `Replication.kt:510`,
`PartitionedShardSet.kt:171`, and `AbsorbAck.kt:7-23`.

## Verification

```bash
./gradlew :kernel:test
./gradlew test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
./gradlew :demo:exchange:test
```

## Report

Per finding: fix + test names. Explicitly state the three operators' behavior
change (they now ack) and any concord scenario that had encoded the old
behavior. **Then continue this session into T06.**
