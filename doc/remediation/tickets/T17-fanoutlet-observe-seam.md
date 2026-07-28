# T17 — `FanOutlet` gets a payload-agnostic observe seam; emission loop drops its redundant copy

**Status:** not-started
**Model:** opus · **Escalate to:** opus (re-split on failure)
**Wave:** 1 · **Branches:** `ticket/T17`

## Context

`FanOutlet<Api>` (`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`) is
the kernel's only broadcast seam. It already distinguishes two attachment
roles: `consumers` (Consume, SPSC-counted) and `taps` (Observe, spec 20/23
§Taps, G-47 — uncounted, always admitted, fire before consumers, never gate a
wave). Both are `ConcurrentHashMap` + a parallel `CopyOnWriteArrayList` for
insertion order (T04 finding 6, commit `fae2ffa`): plain `mutableMapOf`
couldn't survive concurrent `subscribe`/`unsubscribe` racing the per-emission
iteration once hosts went multi-threaded, and `ConcurrentHashMap` alone
doesn't preserve insertion order, which "taps-fire-first" (line 141 KDoc)
depends on.

The only public way to attach to the Observe role is `tap(port: Use<Api>,
...)` (`FanOutlet.kt:275`) — it takes a `Use<Api>`, i.e. a live implementation
of the outlet's *contract interface*. That's the right shape for a consumer
that wants typed payloads. It is the wrong shape for an observer that only
wants to know *that* an emission happened — a message counter, a last-wave
recorder, a rate sampler — and has no business decoding `Api`.

`inspect/src/main/kotlin/civictech/inspect/Flow.kt`'s `FlowCollector` /
`TapSite` is exactly that second kind of observer (`sample()` publishes
`flow.rates`, spec-referenced at `Flow.kt:49`), and today it pays the full
cost of pretending to be the first kind. `TapSite.tapped` (`Flow.kt:254-260`)
builds a JDK dynamic proxy of the outlet's own contract class via
`Proxy.fromClass<Any>(outlet.clazz) { proxy, method, args -> ... }`
(`Flow.kt:256`) and hands that proxy to `outlet.tap(Use.fixed(handler,
tapRef))`. Every invocation lands in one dispatch lambda that must first
decide whether `method` is a real contract method or one of `Object`'s own
(`equals`/`hashCode`/`toString`), because a dynamic proxy forwards those too
(`Flow.kt:257`, screening at `Flow.kt:273-288`). The screening exists for a
concrete hazard, spelled out in the file's own KDoc (`Flow.kt:276-280`):
answering `null` to a primitive-returning method like `hashCode()` throws on
unboxing on whatever thread invoked it — which, for a tap, is a graph thread.
Get the screening wrong (miss a case, a JDK/Kotlin version changes which
`Object` methods a proxy forwards) and a payload-agnostic observer crashes
the emitting cell's thread. This is a footgun every *future* out-of-graph
observer must independently re-derive, because `FanOutlet` currently offers
no attachment shape that doesn't require implementing `Api`.

`FanOutlet.kt:146-147` is the per-emission fan-out loop `call` runs on every
message (`FanOutlet.kt:131-150`):

```kotlin
tapOrder.toList().forEach { key -> taps[key]?.let { target -> invoke(target, method, args) } }
consumerOrder.toList().forEach { key -> consumers[key]?.let { target -> invoke(target, method, args) } }
```

`tapOrder`/`consumerOrder` are already `CopyOnWriteArrayList` — declared and
justified at `FanOutlet.kt:53-79` precisely so that iterating them is safe
against a concurrent `subscribe`/`unsubscribe`/`tap`/`untap` (a COW list's
iterator is a stable snapshot of the array at iterator-creation time; that
*is* the concurrency guarantee T04 finding 6 introduced these lists for).
`.toList()` copies that already-stable snapshot into a second, brand-new
`List` before iterating it — a redundant allocation, paid once per tap/
consumer set per message, on the hottest path in the runtime (every message
any cell ever emits passes through this loop).

## Problem

1. **Proxy-dance forced on every payload-agnostic observer.** `FanOutlet`
   has exactly one attachment API (`tap`, `Api`-typed) for a role (Observe)
   that conceptually doesn't need to see the payload at all. `Flow.kt` is
   the first and only occupant of this seam, and it already had to build a
   full dynamic-proxy dispatcher with hand-rolled `Object`-method screening
   just to count emissions — with a real unboxing-crash hazard baked into
   getting that screening right. `doc/architecture-decisions.md` finding B2
   tracks this as the reason `:inspect` needs `civictech.cell.proxy` in its
   `DemoSurfaceAllowlistTest` allowlist at all (see that test's KDoc,
   `DemoSurfaceAllowlistTest.kt:32-36`, and the entry at line 69).
2. **Redundant allocation on the hottest path.** `FanOutlet.kt:146-147`'s
   `.toList()` copies two already-safe-to-iterate COW snapshots for no
   reason, on every single emission from every outlet in the system
   (architecture-decisions.md finding B11).

## Solution direction

**(1) Add a payload-agnostic observe attachment to `FanOutlet`, on the same
attachment path `putTap` already provides**, and migrate `FlowCollector` to
it, deleting the proxy dance entirely. The audit's steer: something shaped
like `observe(ref: PortRef, onEmit: () -> Unit): LinkResult` (or an
`onEmit(MessageContext?)` that hands the observer the `MessageContext`
`CurrentContext.get()` would have given it inside `invoke` — `TapSite.observe()`
today reads exactly that via `CurrentContext.get()` at `Flow.kt:265`, so the
replacement should give it the same information without a thread-local
re-read if that's convenient). The exact signature, and whether it shares
`putTap`/`removeTap`'s underlying `taps`/`tapOrder` storage directly or wraps
it, is implementer's latitude — but it must:
   - route through the **same map + order-list pair** taps already use (do
     not introduce a third parallel structure — see the explicit non-goal
     below), so ordering relative to `Use<Api>`-typed taps and consumers is
     unaffected, and unlink/relink races are covered by the same T04
     finding-6 guarantees;
   - go through the emission path's existing `invoke`/dispatch point (or an
     equivalent) rather than duplicating the wave-stamping/`CurrentContext`
     setup in `call` (`FanOutlet.kt:131-150`);
   - respect `disclosureFilter` the same way `invoke` does today (an
     Observe-role attachment is still subject to disclosure — spec 40/43
     seam 3), unless there's a documented reason a payload-agnostic observer
     is exempt (it sees no payload to disclose, so exemption may be the
     right call — state the reasoning in the code if you take it).

   Then rewrite `FlowCollector`/`TapSite` in `Flow.kt` against the new
   attachment: `TapSite.tapped`/`observe()`/the `objectMethod` screening
   companion (`Flow.kt:254-289`) all go away, replaced by a direct
   `outlet.observe(...)` call (or whatever the new method is named) that
   increments `count` and records `lastContext`/wave info. `bind`/`unbind`/
   `dropCell`/`sample`/`close` semantics (edge bookkeeping, window batching,
   the trailing-empty-window rule) are unchanged — only how `TapSite`
   attaches to the outlet changes.

**(2) Drop the redundant `.toList()`** at `FanOutlet.kt:146-147` and iterate
`tapOrder`/`consumerOrder` directly — `CopyOnWriteArrayList.forEach` (or a
plain `for`) over the list itself is already the stable snapshot; the copy
buys nothing. Keep the `taps[key]?.let { ... }` / `consumers[key]?.let { ... }`
map lookup exactly as-is — see non-goals.

## Invariants that must survive

State these as the acceptance bar, not just prose:

- **Observers/taps never gate a wave.** The new attachment is Observe-role:
  uncounted by SPSC, no participation in `WaveFrontier`/completeness, same
  as `tap` today.
- **Per-source ordering of deliveries is unchanged.** Taps still fire before
  consumers ("taps-fire-first", `FanOutlet.kt:141`); the new attachment's
  position in that order (alongside existing taps, via the shared
  `tapOrder`) must not reorder anything already observable.
- **Link/unlink during an in-flight wave must not lose or double-deliver.**
  This is the T04 finding-6 class this file was already hardened against
  (`FanOutlet.kt:53-67`) — a concurrent `subscribe`/`unsubscribe`/`tap`/
  `untap`/`observe`/`unobserve` racing the emission loop must not corrupt
  iteration, drop a delivery to a live subscriber, or double-deliver to one
  being removed. **This needs a new kernel test** exercising the new
  attachment concurrently with emission (see Acceptance criteria) — it does
  not currently have one of its own; `FanOutletTest.kt`'s existing tests are
  single-threaded.
- **No change to existing consumer/tap semantics or public behavior.** Every
  existing `FanOutletTest.kt` case, and every existing caller of `tap`/
  `untap`/`subscribe`/`unsubscribe`, keeps behaving exactly as before.
- **Kernel stays transport-free.** The new method lives in
  `civictech.cell.port` alongside `tap`; it introduces no dependency beyond
  what `FanOutlet.kt` already imports.

## Non-goals (explicit — do not do these)

- **Do not shrink `DemoSurfaceAllowlistTest`'s `:inspect` `.proxy` entry**
  (`kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt:69`).
  That entry is forced by *two* things today: `Flow.kt`'s proxy dance (which
  this ticket retires) **and** `LocationRegistry.Remote.sink`'s declared
  type `civictech.cell.proxy.InvocationSink`, used from
  `inspect/src/main/kotlin/civictech/inspect/Peers.kt`. This ticket only
  fixes the `Flow.kt` half. The `Remote.sink` leak is deliberately deferred,
  batched with the already-planned `LocationRegistry`/`InstanceIndex`/
  `DeliveryHold` extraction (see `architecture-decisions.md`'s W5 note: "stays
  batched with the already-deferred ... extraction so distribution call
  sites churn once"). Leave the allowlist entry and its KDoc as-is; if you
  believe `Peers.kt` no longer needs `.proxy` after this ticket, that is a
  finding to report, not an action to take here.
- **Do not replace the two-structure (`ConcurrentHashMap` + `CopyOnWriteArrayList`)
  design** with something else (a single ordered concurrent map, a lock,
  etc.). That two-structures-in-sync shape is exactly what T04 finding 6
  (commit `fae2ffa`) put in place to fix a real concurrency bug; redesigning
  it is out of scope here and would re-open that bug class. Fix (2) by
  deleting the redundant copy, not by restructuring the data.
- **Do not touch scheduling bands.** A "`snapshotOf` at band 0" finding was
  raised and explicitly **declined/refuted** in `doc/architecture-decisions.md`
  (Declined table, "`snapshotOf` at band 0 preempts management..."). This
  ticket has nothing to do with scheduling bands; do not go looking there.
- **Do not touch `ManagedHost.kt` / `SimulationController.kt`** (that's T18's
  `snapshotOf` cancellation/thread-safety work), **`InspectorServer.kt`**
  (T19), **`Observations.kt`** (T20), or **`LocationRegistry.kt`** (T21).
  These are adjacent W5 items owned by sibling tickets in this wave.

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt` — new
  payload-agnostic observe attachment on the `putTap` path; drop the
  `.toList()` in the emission loop.
- `inspect/src/main/kotlin/civictech/inspect/Flow.kt` — migrate
  `FlowCollector`/`TapSite` off `Proxy.fromClass`/`Use.fixed` onto the new
  attachment; delete the `Object`-method screening.
- New or extended test under
  `kernel/src/test/kotlin/civictech/cell/port/` (e.g. extending
  `FanOutletTest.kt` or a new file in the same package) covering the new
  attachment, including the concurrent link/unlink-during-emission case.

Touching files outside this list: note it in the completion report rather
than expanding silently. Parallel work in this wave is scheduled on this
claim.

## Read first

- `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:53-79` — the
  `ConcurrentHashMap` + `CopyOnWriteArrayList` ordering rationale (T04
  finding 6) that both the new attachment and the loop fix must respect.
- `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:131-150` — `call`,
  the emission loop, `invoke`; the wave-stamping/`CurrentContext` setup any
  new dispatch path must reuse rather than duplicate.
- `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:266-300` — `tap`/
  `untap`, the existing Observe-role attachment path (`putTap`/`removeTap`)
  to mirror or share.
- `inspect/src/main/kotlin/civictech/inspect/Flow.kt:104-296` —
  `FlowCollector`/`TapSite`, especially `Flow.kt:254-260` (the proxy
  construction) and `Flow.kt:273-288` (the `Object`-method screening this
  ticket deletes). The class KDoc at `Flow.kt:48-103` explains *why* a tap
  and not a consumer — that reasoning is unaffected by this ticket and
  should still hold for the new attachment.
- `doc/architecture-decisions.md` findings B2 and B11, and the Declined
  table's `snapshotOf`-at-band-0 row — the audit rationale and the explicit
  refutation that keeps scheduling bands out of scope.
- `kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt:1-70`
  — explains exactly why `.proxy` is (still, partially) forced for
  `:inspect`, and why this ticket does not touch that allowlist.
- Commit `fae2ffa` ("concord(T04): concurrency correctness hotfixes") finding
  6 — the origin of the `ConcurrentHashMap`/`CopyOnWriteArrayList` shape;
  `git show fae2ffa -- kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`.
- `kernel/src/test/kotlin/civictech/cell/port/FanOutletTest.kt` — existing
  test style/helpers (e.g. `attachBufferingPort`) to follow for the new test.

Do not modify: `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`,
`kernel/src/main/kotlin/civictech/cell/host/SimulationController.kt` (T18);
`inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` (T19);
`kernel/src/main/kotlin/civictech/cell/observe/Observations.kt` (T20);
`kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` (T21).

## Acceptance criteria

- [ ] `FanOutlet` exposes a payload-agnostic observe attachment on the same
      `putTap`/`removeTap`-backed storage as `tap`/`untap`, returning a
      `LinkResult` (or documented equivalent) and composable with
      unlink/detach the same way `tap` is.
- [ ] `Flow.kt` contains no `Proxy.fromClass` call and no `Object`-method
      screening (`hashCode`/`equals`/`toString` dispatch) — `grep -n
      "Proxy.fromClass\|declaringClass" inspect/src/main/kotlin/civictech/inspect/Flow.kt`
      returns nothing.
- [ ] `FanOutlet.kt`'s emission loop contains no `.toList()` call —
      `tapOrder`/`consumerOrder` are iterated directly.
- [ ] A kernel test exercises the new attachment under concurrent
      link/unlink racing emission (the T04 finding-6 class) and passes
      deterministically (no flaky sleep-based synchronization — use the
      existing `awaitUntil`/`SimWorld` testkit conventions where a bounded
      wait is genuinely needed).
- [ ] Every existing `FanOutletTest.kt` case still passes unchanged.
- [ ] `inspect/src/test/kotlin/civictech/inspect/InspectorFlowTest.kt` and
      `InspectorFlowStreamTest.kt` still pass unchanged — `flow.rates`
      behavior (rates, wave/hop reporting, the trailing-empty-window rule)
      is observationally identical before and after the migration.
- [ ] `DemoSurfaceAllowlistTest.kt` is untouched and still green (the
      `.proxy` entry stays, per non-goals).
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.port.*' :inspect:test
```

## Report on completion

- Checks run and their results.
- Files actually touched, and any not in the claim above.
- The exact signature chosen for the new observe attachment, and how it
  shares state with `tap`/`untap`.
- Whether the new attachment routes through `disclosureFilter`, and why.
- Anything specified here you could not do, and why.
