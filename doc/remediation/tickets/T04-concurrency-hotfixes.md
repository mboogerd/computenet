# T04 — Concurrency correctness hotfixes

**Phase 1, step 1 of 3 · single agent, session CONTINUES into T05/T06 · Sonnet 5**
**Prereq**: Phase 0 merged (T01 CI exists; T03's encapsulation changes are in).
**Write scope**: `kernel/src/main/kotlin/civictech/cell/{host,control,protocol,port,proxy}`,
`kernel/src/main/kotlin/civictech/cell/MessageContext.kt`, targeted new unit
tests in `kernel/src/test`.
**Do not touch**: `LocationRegistry.install()` / `InletPolicy.Admit` /
`HostDurability.recoverFrom` bodies (T05 owns those), `data/op` (T05),
`replication`/`partition` (T07).

## Problem

Concurrency audit findings (verified 2026-07-27 at `742f7ca`). Context: three
schedulers exist; 99 test files use the single-threaded `SimulationController`,
`VirtualThreadScheduler` appears in zero tests, `CoroutineScheduler` in one
smoke test — so none of the below has ever been exercised by the suite.

A traced real cross-thread path (WebSocket read thread → kernel), which several
findings hang off: `WsListener.onMessage` (`wire/.../WsTransport.kt:145`) →
`Session.onFrame` → ingress `HostedCellProxy` over `LocationRegistry::deliver`
(`kernel/.../wire/Peering.kt:118-121`) → `LocationRegistry.send` →
`ManagedHost.enqueueHostedInvocation` (`ManagedHost.kt:361`) — journal fsync,
then `synchronized(dataLock){ stage; checkSaturationOnAccept }`, all on the
WS read thread.

1. **ABBA deadlock (critical).** `dataLock` (`ManagedHost.kt:215`) is held
   while `IntakeControl.checkSaturationOnAccept` (:99-108) and
   `lowWaterCheck` (:118-130, called inside `AttentionScheduler.dispatchOne`'s
   `synchronized(dataLock)`, `AttentionScheduler.kt:97-140`) call
   `announceSaturation` (:133-145) → `Protocols.sendUpstream` →
   `ProtocolSupport.deliver` (`Protocols.kt:143-167`), which runs registered
   handlers **and a hop-by-hop relay traversal of the upstream graph** —
   still under `dataLock`. `ManagedHost.kt:646` registers the Saturation
   relay on every port of every spawned cell, so the traversal is graph-wide.
   Reached handlers include `WaveFrontier`'s (:172-195), whose `flushReady()`
   releases buffered waves into cell logic, which can emit through proxies
   into **another host's** `enqueueHostedInvocation` → that host's
   `dataLock`. Two cross-linked hosts in one JVM (the exchange/shopping
   topology) doing this concurrently in mirror order deadlock; symptom is
   spurious 5s `TimeoutException`s from `VirtualThreadScheduler.kt:52`, not a
   visible deadlock. Secondary: `dataLock` held across the JVM-global
   `ProtocolSupport.registries` monitor serializes all hosts in the process.
   **The fix idiom already exists in the same file**: `lowWaterCheck` returns
   listeners that `AttentionScheduler` fires *after* unlock
   (`IntakeControl.kt:124-126`, `AttentionScheduler.kt:141`).
2. **Journal order ≠ acceptance order (high).** `ManagedHost.kt:401-412`: the
   WAL append (:405) is *outside* `dataLock`; staging (:409) is inside. Two
   threads sending to one cell can interleave append/stage so replay
   re-drives a different order than the live run — divergent recovery for any
   non-commutative cell. The comment at :401-404 claims "journal order =
   acceptance order" — currently only true because no test drives a durable
   host from two threads. (Also noted: the coalesce branch at :394 appends
   *inside* the lock, so the two branches disagree.)
3. **Non-atomic `getOrPut` on JVM-global registries (high).**
   `Protocols.kt:173-175`, `port/PortRegistry.kt:26-28`,
   `port/PortNatures.kt:16`, `port/PortIdentity.kt:27` all do unsynchronized
   `getOrPut` on a `Collections.synchronizedMap` — two monitor acquisitions,
   not atomic. Race: scheduler thread binding a port at spawn
   (`ManagedHost.kt:643-646`) vs a WS thread delivering an upstream protocol
   message both construct; the second `put` discards the first instance —
   the one carrying the Saturation relay and `ownerRef`. Backpressure
   silently stops propagating on that edge. The correct form exists in-repo:
   `control/Attention.kt:311-314` wraps its `getOrPut` in
   `synchronized(registries)`.
4. **Plain `HashMap`s read from non-scheduler threads (high).**
   `ManagedHost.kt:129 cells`, `:100 childHosts` (also `:133 cellParents`)
   are plain maps; writers are on the scheduler thread but public readers are
   not: `lookup` (:596-607, used by every demo's HTTP thread and
   `Peering.hostIngress`), `resolveInlet` (:893-896), `subtreeCellCount`
   (:110, called from a *child* host's thread during the quota walk
   :618-626), `IntakeControl`'s `cellsView()` and `HostDurability`'s
   (:65,174,202). A concurrent resize during `containsKey` can false-negative
   (silent null / spurious remote proxy) or spin. `LocationRegistry` already
   made the correct choice (`ConcurrentHashMap` throughout).
5. **Errors kill the drain loop silently (high).** Four `catch (e: Exception)`
   layers — `ManagedHost.kt:540` (deliver/supervision), `:330-338` (enqueue),
   `VirtualThreadScheduler.kt:29-34`, `CoroutineScheduler.kt:43-51` — none
   catches `Throwable`. A `NotImplementedError` (`TODO()` **is an Error**),
   `StackOverflowError`, or `NoClassDefFoundError` from a generated proxy
   escapes all four, kills the virtual thread / cancels the coroutine scope
   (no `SupervisorJob`, no `CoroutineExceptionHandler` at
   `CoroutineScheduler.kt:32`), and the host **keeps accepting traffic
   forever** — `submit` succeeds, nothing drains, `Owned` payloads accumulate
   unaccounted. Contrast `SimulationController.kt:90-93` which is strict by
   design.
6. **Dead-letter fan-out runs on the raising thread (medium-high).** The
   hop-bound guard in `enqueueHostedInvocation` (`ManagedHost.kt:377-385`)
   calls `deadLetter` directly on the caller's thread (WS read thread in the
   trace); `DeadLetters.emit` → `deadLetterOutlet.call.propagate` dispatches
   synchronously into subscribed cells — mutating their state concurrently
   with the scheduler thread. `FanOutlet.consumers`/`taps` are plain
   `mutableMapOf` (racy reads vs management-band `subscribe`).
7. **Coroutine context loss (medium).** `PendingReBaseline`
   (`MessageContext.kt:98`) and `ReplayScope` (:127) are bare `ThreadLocal`s
   with no coroutine context element, while `CurrentContext` correctly got
   one (:157-158, used via `proxy/Invocation.kt:56`). A `SuspendingCell`
   resuming after a suspension point during RESTART re-baseline or journal
   replay loses the notice/scope and stamps a live wave instead of a
   baseline. Also `CoroutineScheduler.kt:34-50` sets `drainingThread`
   *before* `task.action()`; after a suspension the task may resume on a
   different worker, so the self-await deadlock guard (:61) stops firing.

## Solution

Work in this order (each step compiles + targeted tests green before the next):

### A. Atomic registry access (finding 3)

1. Wrap the four `getOrPut` sites in `synchronized(registries)`, exactly
   matching `Attention.kt:311-314`. Pure mechanical; zero behavior change.
2. Leak mitigation until instance-scoping (tracked by T02's new marker): in
   the host shutdown cascade (`ManagedHost.kt:617-641, 745-750`), call
   `ProtocolSupport.unbind` for every port of every still-registered cell —
   today `unbind` only runs on explicit `despawn` (:721), so a dropped host
   leaks every port forever. Do the analogous reclaim for `PortRegistry`
   entries if a removal API exists; if none does, add a minimal `internal`
   one.

### B. Concurrent host maps (finding 4)

`cells`, `childHosts`, `cellParents` → `ConcurrentHashMap`. Audit the
iteration sites for snapshot needs (`subtreeCellCount`, checkpoint walks) —
`ConcurrentHashMap` weakly-consistent iteration is acceptable for the quota
walk; state-capture paths already run on the scheduler thread.

### C. Deadlock fix (finding 1)

Change `announceSaturation` from performing delivery to **returning a deferred
action** (`(() -> Unit)?`), following the existing `lowWaterCheck` listener
pattern:

- `checkSaturationOnAccept` returns the deferred announce;
  `enqueueHostedInvocation` invokes it **after** the `synchronized(dataLock)`
  block.
- `lowWaterCheck`'s announce likewise returns deferred;
  `AttentionScheduler.dispatchOne` fires it with the listeners it already
  fires post-unlock (`AttentionScheduler.kt:141`).
- Result: `Protocols.sendUpstream` and the relay traversal never run under
  `dataLock`. Announce ordering relative to staging changes slightly
  (post-release); backpressure tests assert saturation *state* transitions,
  not intra-lock timing — fix any test that asserted the old timing by
  asserting the semantic outcome instead (per AGENTS.md test rules).

### D. WAL order = acceptance order (finding 2)

Move the non-coalesce append (`:405`) **inside** the `synchronized(dataLock)`
block, adjacent to `stage` — making both branches consistent and the comment
at :401-404 true. This puts fsync under `dataLock`; that is a deliberate,
documented tradeoff for now:

```kotlin
// ponytail: append holds dataLock so journal order == acceptance order;
// decouple via accept-sequence + async append if fsync contention matters.
```

Do not build the async-append machinery in this ticket.

### E. Throwable backstops + terminated flag (finding 5)

1. `VirtualThreadScheduler` and `CoroutineScheduler` inner catches →
   `catch (t: Throwable)`; rethrow `VirtualMachineError` (OOM stays fatal);
   keep the loop alive otherwise.
2. Wrap each drain loop in `try/finally` setting a `@Volatile terminated`
   flag; `submit`/`enqueueAwaiting` check it and throw
   `IllegalStateException("host scheduler terminated")` so a dead host fails
   loudly instead of accepting traffic. `ManagedHost.enqueueHostedInvocation`
   surfaces that as a dead letter + rethrow.
3. Widen `ManagedHost.kt:540` and `:330-338` to `Throwable` (same
   VirtualMachineError rethrow) so supervision, the dead letter, and the
   `DEAD_LETTERED` stall notice fire for `Error`s too.
4. Give `CoroutineScheduler`'s scope a `CoroutineExceptionHandler` that
   records + keeps the drain alive (with the backstop above this should be
   unreachable; the handler is belt-and-braces).

### F. Dead-letter dispatch + FanOutlet maps (finding 6)

1. Route `DeadLetters` emission through the host scheduler:
   `scheduler.submit(0) { deadLetterOutlet.call.propagate(dl) }` (keep the
   stderr print + counter synchronous — those are thread-safe). Tests that
   observed dead letters synchronously may need an `awaitUntil`/`runToIdle`.
2. `FanOutlet.consumers`/`taps` → `ConcurrentHashMap` (the existing
   `.toList()` snapshot iteration then becomes genuinely safe).

### G. Coroutine context integrity (finding 7)

1. Add `asContextElement`-style bridging for `PendingReBaseline` and
   `ReplayScope`, mirroring how `CurrentContext` is carried
   (`MessageContext.kt:157-158`, `proxy/Invocation.kt:56` —
   `Invocation.invokeSuspending` must compose all three).
2. In `CoroutineScheduler`, set/clear `drainingThread` inside the task
   execution (so it tracks the actual resume thread), or replace the
   thread-identity guard with a coroutine-context marker — preserve the
   behavior that a same-context `await` fails fast with
   `IllegalStateException` rather than timing out.

### H. Targeted unit tests (this ticket; the big conformance suite is T06)

- Deferred-announce test: single host, real `VirtualThreadScheduler`,
  saturate intake, assert the saturation announce arrives and that no
  `dataLock` is held during protocol delivery (assert via a handler that
  attempts `enqueueAwaiting` on the same host without deadlock).
- Terminated-flag test: cell handler throws `NotImplementedError`; assert the
  host either restarts per supervision or subsequent `submit` fails loudly —
  not silent acceptance.
- Suspending re-baseline test: `SuspendingCell` that suspends mid-handler
  during a replay/re-baseline; assert the emission is stamped as
  baseline/replay, not a live wave.

## Verification

```bash
./gradlew :kernel:test
./gradlew test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
./gradlew :demo:exchange:test        # the composition exit gate
```

## Report

Per finding: fix applied, tests added/adjusted (name any test whose assertion
you changed from timing to semantics, with justification). Note the fsync-
under-lock tradeoff explicitly. **Then continue this session into T05.**
