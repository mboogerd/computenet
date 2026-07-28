# T24 — InspectorServer routing/scheduling hygiene; assess post-T21 PeerReconciler residue

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 3 · **Branches:** `ticket/T24`

## Session note (read this first)

Per `doc/remediation/AUDIT-2026-07-28-PLAN.md` Wave 3, this ticket runs in a
**fresh session seeded with T21's completion report as a handoff** — T21
(`doc/remediation/tickets/T21-*.md`, wave 2, opus tier) lands immediately
before this ticket and reshapes `InspectorModel.kt`: it adds
`LocationRegistry.remoteRefs()` and fires the registry's mutation hooks from
`unpublishRemotes`/`mirrorLink`/`mirrorUnlink`, which is expected to retire
most or all of the 1 Hz peer-reconciliation sweep
(`reconcilePeers`/`discoverRemotes`, ~85 lines per the audit's estimate). If
this session does not have T21's report, read T21's ticket file and diff
against `main` before touching `InspectorModel.kt`.

**Every line number in this ticket is pinned to commit `dcfbb33`** (before
T19 and T21 land). T19 (wave 1) also touches `InspectorServer.kt` (CORS/bind
changes) and T21 touches `InspectorModel.kt` — both will have shifted lines
in this file by the time this ticket runs. Anchor by the symbol names cited
below (function/property names, KDoc anchors) and re-locate each one in the
actual checkout before editing; do not trust the line numbers without
verifying them first.

## Context

`:inspect`'s two hub files were built incrementally across six inspector
milestones (M0–M5) plus the T19/T21 remediation tickets landing just before
this one:

- `InspectorModel.kt` (`inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt`)
  is the inspector's materialized topology view: one class holding one
  `synchronized(lock)` monitor (`private val lock = Any()`, currently
  `:66`) that guards every mutation and every read, and a monotonic `seq`
  (`:121`) that increments once per emitted delta (`emitEvent`, currently
  `:637-640`). The class KDoc (currently `:21-36`) states the contract this
  exists to serve: "a client that applies events with `seq > snapshot.seq`
  sees each change exactly once, in the order it happened," and that holding
  the monitor across `emit` is safe because emission is a non-blocking
  hand-off to per-client bounded queues, not a socket write.
- `InspectorServer.kt` (`inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt`)
  is the DI root: it constructs `InspectorModel` and every other collaborator
  (`FlowCollector`, `Observations`, `DataSearch`, `Waker`, `Errors`,
  `SseBroadcaster`), registers HTTP routes by hand against the JDK
  `com.sun.net.httpserver.HttpServer` (via `demo/shell`'s `DemoShell`), runs
  two hand-rolled sub-path dispatchers for the `/graph/{id}/...` and
  `/cell/{ref}/...` subtrees, and drives six periodic polls off one
  `ScheduledExecutorService`.

Both files were accepted findings in the 2026-07-28 architecture audit:
`doc/architecture-decisions.md:41` (B6, `InspectorModel` accretion — 205→745
lines, edited by every milestone, one shipped semantic merge bug) and
`doc/architecture-decisions.md:42` (B7, `InspectorServer` fuses a DI root,
hand-rolled order/length-dependent routing, 6 schedules, 11 test accessors).
The design rationale and the exact scope this ticket implements is
`doc/remediation/AUDIT-2026-07-28.md` §W6, items 2 and 3, plus the free-rider
note at the end of that section (all quoted under Solution direction below).

**What was refuted, and stays refuted here.** The same audit's adversarial
refutation pass considered extracting the seq/emit machinery out of
`InspectorModel` as part of the B6 cleanup and rejected it: §W6 item 2 states
"Keep the seq/emit fusion: the refutation established it is load-bearing for
the exactly-once event contract, and extracting it re-creates the same
monitor behind an interface." Nothing in this ticket touches `seq`, `emit`,
`emitEvent`, `frame`, or the `synchronized(lock)` shape — they are explicitly
out of scope, not merely deprioritized.

## Problem

Concretely, with evidence (line numbers pinned to `dcfbb33` — re-locate by
symbol name):

1. **Two divergent hand-rolled sub-path dispatchers.** `serveGraph`
   (`InspectorServer.kt:326-344`) and `serveCell` (`:346-365`) each
   re-implement the same "strip the route prefix, split on `/`, drop empty
   segments" parse inline and slightly differently:
   ```kotlin
   // serveGraph, :327
   val segments = exchange.requestURI.path.removePrefix(GRAPH_PATH).split('/').filter { it.isNotEmpty() }
   // serveCell, :347-348
   val segments = exchange.requestURI.path.removePrefix(CELL_PATH)
       .split('/').filter { it.isNotEmpty() }
   ```
   A third endpoint author has no shared helper to reach for and will most
   likely write a third copy, or worse, a subtly different one.
2. **A registration-order-and-prefix-length trap, documented 300 lines from
   the constants it depends on.** `GRAPH_PATH` is registered right after
   `GRAPHS_PATH` with a comment explaining why
   (`InspectorServer.kt:250-253`: "Registered *after* `GRAPHS_PATH` and
   deliberately one character shorter than it: the JDK http server matches
   contexts by longest path prefix, so `/graphs` still reaches its own
   handler while `/graph/{id}/wake` reaches this one.") — but the two string
   constants that make this true, `GRAPHS_PATH` and `GRAPH_PATH`, are
   declared in the companion object nearly 300 lines below
   (`InspectorServer.kt:565` and `:568`), with no comment there pointing
   back to the constraint. A future endpoint under `BASE_PATH` that happens
   to prefix-collide with an existing one (there is no naming convention
   that prevents this) has nothing forcing them to rediscover the
   constraint before it silently misroutes.
3. **Six inline `scheduleAtFixedRate` calls, each welded to a bespoke test
   accessor.** `start()` (`InspectorServer.kt:467-507`) registers, in order:
   heartbeat (`model::heartbeat`, `:469-472`, no test accessor exists),
   idle-observation sweep (`observations::sweep`, `:473-476`, exposed as
   `sweepIdleObservations()` at `:529`), error-lane poll (`errors::poll`,
   `:477-480`, exposed as `pollErrorsNow()` at `:532`), flow-window sample
   (`flow::sample`, `:484-487`, exposed as `sampleFlowNow()` at `:535`), the
   combined peer-reconcile + graph-change announcement
   (`model.reconcilePeers()` then `model.publishGraphChanges()` in one
   `runCatching`-wrapped pair, `:494-497`, exposed as two accessors,
   `reconcilePeersNow()` at `:550` and `publishGraphChangesNow()` at `:544`),
   and lifecycle-change announcement (`model::publishLifecycleChanges`,
   `:503-506`, exposed as `publishLifecycleChangesNow()` at `:547`). Six
   physical schedules, six (well, five distinct actions across six
   registrations) test seams, each named after its own collaborator, each
   one more to add correctly the next time a poller is added.
4. **Eleven `internal` test accessors total** (`InspectorServer.kt:525-556`):
   the six tick-triggers above plus five state readers that are not
   schedule-related (`observedRefs`, `tappedOutlets`, `errorSnapshot()`,
   `knowsNow()`, `componentsNow()`). Only the six tick-triggers are this
   ticket's concern — the five state readers are legitimate test seams into
   collaborator state and are not part of the "test accessors" problem the
   audit names.
5. **The default host-label expression is copy-pasted at three sites,** and
   is load-bearing on being byte-for-byte identical (a divergence would
   double-count the same physical host as two different `Node.host` labels
   in `GraphSummary`):
   - `InspectorServer.kt:119` (the `Set<ManagedHost>` convenience
     constructor): `"host-" + it.ref.id.toString().substringBefore('-')`
   - `InspectorModel.kt:743` (`private companion object`'s
     `defaultHostName(host: ManagedHost)`, called from `nodeOf` at `:672`):
     `"host-" + host.ref.id.toString().substringBefore('-')`
   - `Peers.kt:79-82` (`private companion object`'s `labelOf(sink:
     InvocationSink)`, the `Node.net` label for a remote/mirrored ref):
     ```kotlin
     fun labelOf(sink: InvocationSink): String = when (sink) {
         is BridgeEgressCell -> PREFIX + sink.ref.id.toString().substringBefore('-')
         else -> PREFIX + Integer.toHexString(System.identityHashCode(sink))
     }
     ```
     where `PREFIX = "peer-"` (`Peers.kt:67`). Its own KDoc (`:76-77`) already
     says "Formatted like `InspectorModel`'s own default host name" —
     acknowledging the duplication without closing it.

   Note the accessibility trap if you reuse `InspectorModel.defaultHostName`
   as-is: it lives inside `private companion object` (`InspectorModel.kt:724`),
   so it is not visible from `InspectorServer.kt` or `Peers.kt` today. That
   privacy is presumably *why* the other two sites copied the expression
   instead of calling it.
6. **`InspectorModel`'s peer-reconciliation residue may or may not still
   warrant extraction.** Before T21, this is `mirrored`
   (`InspectorModel.kt:80`), `declared` (`:87`, shared with `declareLink`'s
   idempotency bookkeeping — not purely peer-reconciliation), `mirroredPublish`
   (`:488`), `adopt` (`:495-504`), `reconcilePeers` (`:535-554`), `anchored`
   (`:557-558`), `touchesInstrument` (`:560-562`, also used outside peer
   reconciliation), and `discoverRemotes` (`:581-589`) — roughly the
   `:476-589` region including KDoc, on the order of 110 lines. T21 is
   expected to delete most of `reconcilePeers`/`discoverRemotes` by
   replacing the registry-diff sweep with real hook firing plus
   `LocationRegistry.remoteRefs()`. What survives that deletion, and whether
   it is still large/cohesive enough to justify its own type, is unknown
   until T21's actual diff is in hand.

## Solution direction

Four independent pieces of `doc/remediation/AUDIT-2026-07-28.md` §W6 items 2
and 3, plus its free-rider note, decided as follows:

1. **Routing.** Extract one shared path-segment helper (e.g. a private
   function taking the exchange and the route's prefix constant, returning
   the non-empty tail segments) and use it from both `serveGraph` and
   `serveCell`. Keep route registration as the single ordered sequence it
   already effectively is inside `init` — do **not** split each vertical
   into its own handler class or file; that is explicitly named LATITUDE by
   the audit ("a full per-vertical handler split is LATITUDE — do it only if
   it stays a mechanical move") and is not required here. State the
   prefix-length/registration-order constraint once, next to the thing that
   actually has to stay true for it to hold — either inline where
   `GRAPHS_PATH`/`GRAPH_PATH` are declared (pointing forward to where they're
   registered) or by naming the constraint in one place both the
   registration comment and the constant declarations can point at. The
   existing comment at `InspectorServer.kt:250-253` is good content; the
   defect is that nothing beside the constants themselves (`:561-568`)
   forces a future author back to it. Add the cheap regression test the
   audit asks for: a request to `GET /api/inspect/graphs` and a request to
   `POST /api/inspect/graph/{id}/wake` reach their own distinct handlers
   (i.e. `/graphs` is never swallowed by the shorter `/graph` prefix, and
   vice versa) — this is the concrete failure mode the ordering trap
   protects against, made assertable instead of only commented.
2. **Schedules.** Give each polled collaborator a `tick()`-shaped entry (name
   + period + the action) and drive `start()` from one list of such entries
   instead of six inline `scheduleAtFixedRate` calls — each entry still gets
   its own `heartbeats.scheduleAtFixedRate(...)` registration at its own
   cadence (heartbeat, sweep, error-poll, and flow-sample genuinely differ;
   the two `GRAPHS_POLL_MS` entries may stay separate registrations or be
   merged into one action list at that cadence — your judgment, as long as
   `reconcilePeers()` still runs before `publishGraphChanges()` per the
   existing comment at `:491-493`, and `publishLifecycleChanges()`'s
   ordering relative to those two is unaffected). Replace the six ad hoc
   `…Now()` test accessors with a single `tickAll()` internal test seam that
   runs every entry's action once, synchronously, on the calling thread —
   this is what the audit means by "a single `tickAll()` test seam replacing
   the per-schedule accessors." Do not touch the five non-schedule test
   accessors (`observedRefs`, `tappedOutlets`, `errorSnapshot()`,
   `knowsNow()`, `componentsNow()`); they stay as-is.

   **Judgment call, and it matters for not weakening tests:** roughly 58
   call sites across `inspect/src/test/**` currently invoke individual
   `…Now()` accessors expecting *only* that one action to run (e.g.
   `InspectorNetTest.kt:71` calls `reconcilePeersNow()` alone). Before
   collapsing every call site onto a blanket `tickAll()`, check whether
   running all six actions together introduces any test-visible
   interference (an unexpected early `observations.sweep()`, `errors.poll()`,
   or `flow.sample()` mid-assertion). If it does not — the common case, since
   these actions are all read-then-maybe-emit passes over independent state —
   collapse fully. If a specific test genuinely needs an isolated single
   action, keep a narrowly-scoped named accessor for that one case and say
   so in the completion report (the acceptance criterion below explicitly
   allows "justified survivors"). Do not weaken an assertion to make the
   collapse fit; keep the narrower accessor instead.
3. **Labels.** One label-builder used by all three sites (`InspectorServer`'s
   `Set<ManagedHost>` constructor, `InspectorModel.defaultHostName`,
   `Peers.labelOf`'s `BridgeEgressCell` branch). The natural shape is a
   function of `(prefix: String, id: UUID) -> String` — `InspectorModel` uses
   it with `"host-"`, `Peers` uses it with `"peer-"` (its own `PREFIX`
   constant). Placement is your call: promote `InspectorModel`'s existing
   `defaultHostName`-shaped logic to somewhere reachable from all three files
   (its current `private companion object` is not visible outside the file —
   note that before assuming you can just call it from `InspectorServer` as
   the audit's free-rider phrasing implies), or introduce one small shared
   `internal` declaration and point all three call sites at it. Whichever you
   pick, the three sites must call the *same* code, not three copies that
   happen to match today.
4. **`PeerReconciler` extraction — assess, do not assume.** Read T21's
   landed diff/report first (see Session note). Locate what remains of the
   peer-reconciliation surface described in Problem item 6. If a coherent
   responsibility of roughly 60 lines or more survives — enough that pulling
   it into its own small class would leave `InspectorModel` meaningfully
   easier to read without fragmenting a single conceptual operation across
   two files — extract it, following the same `synchronized`/monitor
   discipline `InspectorModel` already uses (the extraction must not
   introduce a second lock or split the seq/emit-guarding monitor; see the
   explicit non-goal below). If T21 has reduced the residue below that bar,
   or extracting it would just relocate a handful of one-line delegations,
   do **not** extract it — record "not warranted post-T21: <what remains,
   how many lines, why it doesn't clear the bar>" in the completion report.
   Either outcome satisfies this ticket; a forced extraction of a thin
   residue does not.

**Explicit non-goal.** Do not extract, wrap, or relocate the seq/emit
mechanism (`seq`, `emitEvent`, `frame`, the `lock` monitor itself, or any of
`InspectorModel`'s public mutators that call `emitEvent` under
`synchronized(lock)`). The audit's refutation (quoted under Context) found
this fusion load-bearing for the exactly-once event contract; re-wrapping it
behind an interface reproduces the same monitor with extra indirection, not
less coupling. If the PeerReconciler assessment in item 4 finds an
extraction candidate, that candidate must call back into `InspectorModel`'s
existing `synchronized` methods (`adopt`/`unpublished`/`linked`/`unlinked`/
etc.) rather than acquiring its own lock or touching `seq`/`emit` directly.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — shared
  path-segment helper for `serveGraph`/`serveCell`; routing-constraint
  comment consolidation; schedule list + `tickAll()` seam replacing the six
  `…Now()` accessors; label-builder call site in the `Set<ManagedHost>`
  constructor.
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` — label
  builder (definition or promoted visibility) and its call site in
  `defaultHostName`/`nodeOf`; `PeerReconciler` extraction if item 4's bar is
  cleared, otherwise unchanged here beyond whatever T21 already landed.
- `inspect/src/main/kotlin/civictech/inspect/Peers.kt` — `labelOf`'s
  `BridgeEgressCell` branch calls the shared label builder instead of
  restating the expression.
- `inspect/src/test/**` — the routing-distinctness test
  (`/graphs` vs. `/graph/{id}/wake`); update every `…Now()` call site
  affected by the `tickAll()` collapse; add/adjust tests for a
  `PeerReconciler` extraction if one happens.

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W6 (items 2 and 3, plus the
  free-rider note at the section's end) — the decided scope this ticket
  implements, verbatim.
- `doc/architecture-decisions.md:41-42` — findings B6 and B7, their accepted
  severity and solution pointer.
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` — class KDoc
  (`:21-36`, the seq/emit exactly-once contract — do not disturb), `lock`
  (`:66`), `defaultHostName` (`:743`, currently unreachable outside the file
  — see Problem item 5), the peer-reconciliation region named in Problem
  item 6 (`:476-589` pre-T21 — re-locate post-T21).
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — route
  registration in `init` (`:234-282`), the prefix-ordering comment
  (`:250-253`), `serveGraph` (`:326-344`) and `serveCell` (`:346-365`), the
  `start()` schedule block (`:467-507`), the eleven `internal` test
  accessors (`:525-556`), the path constants (`:561-568`), the
  `Set<ManagedHost>` constructor's host-label copy (`:119`).
- `inspect/src/main/kotlin/civictech/inspect/Peers.kt:66-83` — `labelOf`,
  its KDoc already noting the duplication with `InspectorModel`'s default
  host name.
- T21's completion report/diff (handoff) — what it actually deleted from
  `InspectorModel.kt`'s peer-reconciliation region; this determines the
  outcome of Solution direction item 4.
- `inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt` — an
  existing user of `reconcilePeersNow()`, representative of the call sites
  the `tickAll()` collapse must not break.

Do not modify: `kernel/**`, `inspect/src/main/kotlin/civictech/inspect/Dto.kt`,
`inspect/src/main/kotlin/civictech/inspect/Observations.kt` (owned by T20),
`inspect/ui/fixtures/**`, `inspect/ui/**`. If you find yourself needing to
touch `civictech.cell.host.LocationRegistry` or any other kernel type to make
the `PeerReconciler` assessment work, stop — that surface belongs to T21 and
this ticket only consumes its result.

## Acceptance criteria

- [ ] One path-segment helper exists; both `serveGraph` and `serveCell` use
      it instead of their own inline parse.
- [ ] A test asserts `GET /api/inspect/graphs` and
      `POST /api/inspect/graph/{id}/wake` reach distinct handlers (the
      routing-distinctness regression the prefix-length trap protects).
- [ ] The registration-order/prefix-length constraint is stated once, in a
      place that is actually beside what it depends on, not duplicated or
      left orphaned 300 lines from the constants.
- [ ] The six schedule actions are driven from one list; the six `…Now()`
      test accessors are collapsed to a single `tickAll()` (or a strictly
      smaller set of named survivors, each justified in the completion
      report as needing isolation from the rest).
- [ ] The five non-schedule test accessors (`observedRefs`, `tappedOutlets`,
      `errorSnapshot()`, `knowsNow()`, `componentsNow()`) are unchanged.
- [ ] One label-builder is used by all three sites (`InspectorServer`'s
      `Set<ManagedHost>` constructor, `InspectorModel.defaultHostName`,
      `Peers.labelOf`'s `BridgeEgressCell` branch) — no independent copies
      of the `prefix + id.toString().substringBefore('-')` expression
      remain.
- [ ] `PeerReconciler` extraction is either done (with a genuine ≥~60-line
      cohesive responsibility moved, using `InspectorModel`'s existing
      synchronized methods rather than a new lock) or explicitly declined
      in the completion report as "not warranted post-T21," with the
      residue's actual size stated.
- [ ] `seq`, `emitEvent`, `frame`, and the `synchronized(lock)` monitor shape
      are untouched — no new lock, no extraction of the emit path.
- [ ] `./gradlew :inspect:test` is green, with no weakened or deleted
      assertions (a test that legitimately needs a narrower tick accessor
      keeps one rather than losing its assertion).
- [ ] No unrelated files in the diff (in particular, no edits under
      `kernel/**`, `Dto.kt`, `Observations.kt`, or `inspect/ui/**`).

## Verify

```bash
./gradlew :inspect:test
```

## Report on completion

- Checks run and their results.
- Files actually touched, and any not in the claim above.
- Which CORS/bind/T19 and T21 changes you found already landed when you
  started (so the evaluator knows which pinned line numbers you had to
  re-locate).
- The `tickAll()` collapse outcome: fully collapsed, or which named
  accessors survived and why.
- The `PeerReconciler` decision: extracted (with its final line count and
  what it now owns) or declined (with the post-T21 residue's actual size).
- Anything specified here you could not do, and why.
