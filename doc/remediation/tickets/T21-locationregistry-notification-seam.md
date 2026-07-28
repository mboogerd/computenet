# T21 — `LocationRegistry`: symmetric hook detachment and a real notification seam for remote mutations

**Status:** not-started
**Model:** opus · **Escalate to:** opus (re-split on failure)
**Wave:** 2 · **Branches:** `ticket/T21`

## Context

Wave 2 because T19 (`InspectorServer` loopback bind + wake-route CORS) and T20
(`:inspect` test/scheduling work) merge first; this ticket's file claim
overlaps both (`InspectorServer.kt`, `inspect` tests), so it must build on
whatever they land, not race them.

`civictech.cell.host.LocationRegistry` (`kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt`)
is the where-a-`CellRef`-lives index (spec 33/41, G-5, G-15) and the
announcement seam a mirroring registry (`civictech.cell.wire.Peering`) and the
inspector (`inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt`,
`InspectorServer.kt`) both subscribe to. It exposes six hooks:

- `onLocalPublish`, `onLocalUnpublish`, `onLocalTopology` (`LocationRegistry.kt:92-95`,
  `:102-105`, `:111-115`) — fire only for *local* mutations, each returns
  `AutoCloseable`.
- `onPublish`, `onUnpublish` (`LocationRegistry.kt:97-99`, `:107-109`) — fire
  for *any* mutation (local or remote/mirrored), each returns `Unit`.

`publish(ref, host, cell)` (local) fires both `onLocalPublish` and `onPublish`
(`LocationRegistry.kt:292-297`); `publish(ref, sink)` (remote/mirrored) fires
only `onPublish` (`:300-303`); `unpublish` fires both `onLocalUnpublish` and
`onUnpublish` when the removed location was local (`:381-388`);
`mirrorUnpublish` fires only `onUnpublish` (`:391-396`). `link`/`unlink`
(`:128-138`) fire `onLocalLink`/`onLocalUnlink` — there is no any-scope
topology-hook counterpart at all.

Three mutation paths fire nothing:

- `unpublishRemotes(via: InvocationSink)` (`LocationRegistry.kt:402-406`) — the
  transport's peer-disconnect path, called from `Peering.Loopback.partition()`
  (`kernel/src/main/kotlin/civictech/cell/wire/Peering.kt:99-100`). It removes
  every `Remote` location routed through the dead sink and updates the
  `byLogicalId` index, but calls no hook.
- `mirrorLink`/`mirrorUnlink` (`LocationRegistry.kt:141-142`) — the
  announcement-fed remote topology edges, called from `Peering.Side`'s
  `Announce` implementation (`Peering.kt:54-55`). They update `TopologyIndex`
  only.
- Remote refs reachable only through `instancesOf(logicalId)`
  (`LocationRegistry.kt:155-156`) — a replica of a locally-published cell,
  announced on a different registry, that never appears as a link endpoint and
  so is never named by any hook payload; a consumer built after that
  announcement can only find it by scanning `instancesOf` itself.

`InspectorModel` (`inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt`)
compensates for all three gaps with a full shadow copy of the registry's
remote state — `mirrored: LinkedHashSet<CellRef>` (`:72-80`) and
`declared: LinkedHashSet<UUID>` (`:82-87`) — kept true by `reconcilePeers()`
(`:507-554`, three numbered cases matching the three gaps above, each with a
KDoc paragraph naming exactly the hook that does not fire) and
`discoverRemotes()` (`:564-589`, whose own KDoc calls the hole it patches
"residual, honestly stated" and says closing it "wants a remote-refs
projection on `LocationRegistry`, next to `localRefs()`; that is a kernel
change [the prior ticket] does not own"). `InspectorServer.start()` runs both
on a 1 Hz schedule (`GRAPHS_POLL_MS = 1_000L`, `InspectorServer.kt:581`,
scheduled at `:494-497`), O(V+E) per tick.

Separately, `InspectorServer` cannot detach from the any-scope hooks it
registers in its constructor (`registry.onPublish { ... }`,
`registry.onUnpublish { ... }`, `InspectorServer.kt:227-228`) because those two
methods return `Unit`. It works around this with a `@Volatile private var
attached = true` flag (`:212-213`) that `close()` flips to `false`
(`:511-523`) — the listener stays registered on the registry for the
registry's lifetime and merely no-ops; the KDoc directly above the flag
(`:202-211`) states this plainly: "a closed inspector cannot disconnect from
them... noted rather than fixed because widening those two hooks is a kernel
change." Every future any-scope consumer (a metrics exporter, a second
visualization, a control plane) inherits the same leak and would have to
invent the same workaround.

`doc/remediation/AUDIT-2026-07-28.md` §W5 items 1-2 and
`doc/architecture-decisions.md` finding B9 record this pair as accepted,
`planned` work; this ticket implements it.

## Problem

1. `onPublish`/`onUnpublish` return `Unit` while their four siblings
   (`onLocalPublish`, `onLocalUnpublish`, `onLocalTopology`) return
   `AutoCloseable` — an asymmetric contract on the same class for no
   documented reason other than "not yet widened." Any any-scope subscriber
   leaks for the registry's lifetime; `InspectorServer` is today's only
   consumer and already carries the disarm-flag workaround as visible debt.
2. `unpublishRemotes`, `mirrorLink`, and `mirrorUnlink` mutate registry state
   that any-scope subscribers care about (a peer's cells and edges) without
   notifying anyone. `InspectorModel` has built and must maintain a ~85-line,
   1 Hz, O(V+E) polling sweep (`reconcilePeers` + `discoverRemotes`) purely to
   discover what these three paths changed. Every future registry consumer
   that needs remote-state accuracy would have to rebuild the same sweep, or
   depend on the inspector's.

## Solution direction

Three changes, in this order (each independently testable):

1. **Symmetrize hook return types.** `onPublish` and `onUnpublish`
   (`LocationRegistry.kt:97-99`, `:107-109`) return `AutoCloseable` exactly
   like `onLocalPublish`/`onLocalUnpublish`/`onLocalTopology` — same pattern:
   append to the `CopyOnWriteArrayList`, return `AutoCloseable { list -=
   listener }`. This is additive: every existing caller
   (`InspectorServer.kt:227-228`, `Peering.kt:172-173`'s
   `chainOnReannounce`) currently discards the `Unit` result and keeps
   compiling unchanged if it continues to ignore the return value. Update
   `InspectorServer` to capture and hold both handles alongside the existing
   `hooks: List<AutoCloseable>` (`:196-200`), close them from `close()`
   (`:511-523`), and delete the `attached` field (`:212-213`) and its guard
   checks (`:227-228`) along with the KDoc that documents the workaround
   (`:202-211`) — state the real contract instead (or delete the paragraph
   entirely if the symmetry makes it unnecessary).
2. **Add a remote-refs read projection.** Beside `localRefs()`
   (`LocationRegistry.kt:188-190`), add a method exposing every `Remote`
   location's `CellRef` (e.g. `remoteRefs(): Set<CellRef>` mirroring
   `localRefs()`'s shape, filtering `locations` on `Location.Remote` instead
   of `Location.Local`). Exact shape (a `Set<CellRef>`, something keyed by
   sink, something else) is your judgment — `InspectorModel.discoverRemotes()`'s
   own KDoc (`:564-589`) already names the projection it wants and why
   (closing the "announced only via a link endpoint or `instancesOf`, never
   directly" catch-up hole for an inspector built after the announcement).
3. **Fire the any-scope hooks from the three silent paths.**
   - `unpublishRemotes` (`LocationRegistry.kt:402-406`): call `onUnpublish`'s
     `notify` for every ref actually removed, same helper `unpublish`/
     `mirrorUnpublish` already use (`:311-317`).
   - `mirrorLink`/`mirrorUnlink` (`:141-142`): these currently have no
     any-scope topology-hook counterpart to fire — `onLocalLink`/
     `onLocalUnlink` are local-only by contract (the KDoc at `:72-75` says
     "Remote publishes never re-announce, so mirrored registries cannot
     loop" — the equivalent guarantee for topology is that `mirrorLink`
     itself never re-announces onward, not that nothing may observe it
     in-process). Decide and document either: (a) a new any-scope topology
     hook pair (`onTopology(linked, unlinked): AutoCloseable`, fired by both
     `link`/`unlink` and `mirrorLink`/`mirrorUnlink`), mirroring the
     publish/unpublish local/any split, or (b) folding mirrored-link
     visibility into the `remoteRefs()` projection instead of a push hook,
     if that is sufficient for `InspectorModel` to stop polling for it.
     State which you chose and why in the completion report.
   - Once `InspectorModel` can react to a `mirrorLink`/`mirrorUnlink`
     notification (or read a live projection) *and* to an `unpublishRemotes`
     notification, retire `reconcilePeers()` (`InspectorModel.kt:507-554`)
     and `discoverRemotes()` (`:564-589`) and the 1 Hz schedule that drives
     them (`InspectorServer.kt:494-497`, and `reconcilePeersNow()` at `:550`
     if nothing else needs it). `InspectorModel.unpublished(ref)`
     (`:429-447`) removes a node but does not currently retract edges whose
     endpoint just vanished — that retraction is presently done by
     `reconcilePeers`' `anchored()` check (`:556-558`) sweeping `edges`
     against `nodes` on every tick. With the sweep gone, `unpublished` (or
     the new mirror-topology handler) must drop those dangling mirrored
     edges itself, in the same event, not rely on a poll to notice — this is
     the one piece of real design work in this ticket, not a rename.

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` — symmetric
  `AutoCloseable` hooks, `remoteRefs()` (or equivalent), hook firing from
  `unpublishRemotes`/`mirrorLink`/`mirrorUnlink`.
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` — replace
  `reconcilePeers`/`discoverRemotes`'s poll-driven adoption with event-driven
  adoption fed by the new/widened hooks; extend `unpublished`/the new
  mirror-topology handler to retract dangling mirrored edges without a sweep.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — hold and
  close the two newly-`AutoCloseable` hook handles; delete `attached` and its
  KDoc; delete the `reconcilePeers`/`publishGraphChanges` 1 Hz schedule entry
  at `:494-497` (or replace it with whatever `publishGraphChanges` alone still
  needs, if anything — check whether `publishGraphChanges` has an independent
  reason to stay scheduled before deleting the whole block).
- `kernel/src/test/**` — new test(s) proving: (a) `onPublish`/`onUnpublish`
  return a working detach handle (register, close, mutate registry, assert
  the closed listener saw nothing further while a still-open one did); (b) a
  peer-disconnect-shaped `unpublishRemotes` call and a `mirrorLink`/
  `mirrorUnlink` call each reach a registered any-scope hook.
- `inspect/src/test/**` — adjust `InspectorNetTest.kt`'s `snapshot()` helper
  (`inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt:70-75`,
  which calls `reconcilePeersNow()` before every read) and any scheduling
  test that asserts the retired 1 Hz sweep exists, to match the event-driven
  path — see Acceptance criteria for what must still hold afterward.

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W5 items 1-2 — the accepted work
  items this ticket implements, and the ordering note ("Own ticket + wire
  tests: it is a registry-semantics change").
- `doc/architecture-decisions.md` finding B9 — the accepted finding record
  (severity, location, solution, status `planned`).
- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` (whole
  file) — hooks `:92-115`; `link`/`unlink` firing local topology hooks
  `:128-138`; `mirrorLink`/`mirrorUnlink` `:141-142`; `instancesOf`
  `:155-156`; `localRefs` `:188-190`; `publish` (local, firing both publish
  hooks) `:292-297`; `publish` (remote, firing only `onPublish`) `:300-303`;
  the shared `notify` helper `:311-317`; `unpublish` `:381-388`;
  `mirrorUnpublish` `:391-396`; `unpublishRemotes` `:402-406`. Read the whole
  file, not just these spans — `install`/`deliver`/`replay`'s park-ordering
  contract (`:196-371`, especially the long comment at `:319-362`) is what
  "hooks fire synchronously on the mutating thread, and must not reorder
  publish/park-replay" means in the invariant below.
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:60-90` (the
  `mirrored`/`declared` shadow sets and why they exist) and `:476-589`
  (`mirroredPublish`, `adopt`, `reconcilePeers`, `discoverRemotes` — each
  KDoc paragraph names the exact hook gap it patches; treat these as the
  precise spec for what your new hooks must cover) and `:395-474`
  (`published`/`unpublished`/`linked`/`unlinked` — the event-driven handlers
  the new hooks must feed, and where dangling-edge retraction needs to move
  to).
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:192-232`
  (the `hooks` list, the `attached`-flag workaround and its honest KDoc, and
  the constructor's any-scope hook registration) and `:490-510` (the 1 Hz
  `GRAPHS_POLL_MS` schedule, `GRAPHS_POLL_MS = 1_000L` at `:581`).
- `inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt` (whole
  file) — the peer-visibility semantics that must keep passing: peer labels
  and no process host (`:92-122`), the peer's own links arriving as edges
  (`:124-150`), a disconnect retracting cells *and* links with healing
  restoring them (`:152-183`, including the class KDoc at `:20-38` and the
  `snapshot()` helper at `:70-75` that currently forces a sweep before every
  read), cross-boundary component joins via a declared link (`:185-218`),
  and remote-cell placement-only semantics (`:220-246`).
- `kernel/src/main/kotlin/civictech/cell/wire/Peering.kt` — what fires
  registry mutations on peer events today: `published`/`linked`/`unlinked`/
  `unpublished` implementations at `:53-56`, the disconnect path
  (`unpublishRemotes` calls) at `:99-100`, local-hook registration for the
  announce-outward direction at `:143-146`, and `chainOnReannounce`'s
  existing `onPublish` use at `:172-173` (confirms today's `Unit`-return
  callers already ignore the return value — your `AutoCloseable` change must
  not force them to change).
- The M5 merge bug at commit `476d047` (`inspector(M5): fix cross-branch
  rename — NET's adopt() stamps via COLD's stamped()`) — the regression
  class to guard against: a peer-adopted node shipped once without its
  lifecycle/graph stamp because a rename on one branch silently desynced
  from a sibling branch's emission helper. Your event-driven adoption path
  must still stamp every emitted node the same way the poll-driven path did.

Do not modify: `kernel/src/main/kotlin/civictech/cell/wire/Peering.kt`
(read-only context — you may need to trace its call sites, not change them),
`wire/**` (`WireCodec` and friends — read-only context),
`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` (T18, merged),
`inspect/src/main/kotlin/civictech/inspect/Observations.kt`,
`inspect/src/main/kotlin/civictech/inspect/Flow.kt`.

## Acceptance criteria

- [ ] `LocationRegistry.onPublish` and `onUnpublish` both return
      `AutoCloseable`; a kernel-level test registers a listener, closes the
      handle, mutates the registry, and asserts the closed listener received
      nothing further while a second, still-open listener did.
- [ ] `InspectorServer` holds and closes handles for both any-scope hooks it
      registers; `close()` leaves no listener attached to the registry (test:
      construct a server, close it, mutate the registry directly, assert no
      further model state change occurs); the `attached` field is gone.
- [ ] A kernel-level test drives a peer-disconnect-shaped `unpublishRemotes`
      call and asserts a registered any-scope hook observes it.
- [ ] A kernel-level test drives a `mirrorLink`/`mirrorUnlink` call and
      asserts a registered hook (or read projection, per whichever direction
      you chose) observes it.
- [ ] `InspectorModel.reconcilePeers`/`discoverRemotes` and the 1 Hz schedule
      that drove them are gone (or, if `publishGraphChanges` has an
      independent reason to keep a schedule entry, only the
      `reconcilePeers()` call within it is gone — state which in the report).
- [ ] `InspectorNetTest` passes with its documented peer-visibility semantics
      intact — peer labels and no process host, mirrored links arriving and
      leaving as edges, a disconnect retracting both cells and links with
      healing restoring them, cross-boundary component joins via a declared
      link, and remote-cell placement-only semantics — either unmodified or
      with assertions strictly strengthened (never weakened) to fit the
      event-driven path; adjusting the `snapshot()` test helper's forced-sweep
      call to the new mechanism is expected, not a violation of this
      criterion.
- [ ] Hooks still fire synchronously on the mutating thread (no dispatch to
      another thread/queue introduced), and the existing publish/park-replay
      ordering contract in `install`/`deliver`/`replay`
      (`LocationRegistry.kt:196-371`) is unchanged — state this explicitly in
      the report, and add or point to a test that would fail if a hook call
      were moved off the mutating thread or reordered relative to a park
      replay.
- [ ] Every peer-adopted node your new event-driven path emits still carries
      its lifecycle/graph stamp (the `476d047` regression class does not
      reappear) — cite the specific emission call in the report.
- [ ] No `Owned`/`Leased` payload handling changed anywhere in the diff.
- [ ] `:wire:test` passes unmodified (the wire path's observable behavior is
      unaffected by this ticket).
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.host.*' :wire:test :inspect:test
```

## Report on completion

- Checks run and their results.
- Which direction you chose for mirrored-topology notification (new
  any-scope `onTopology` hook pair vs. folding into `remoteRefs()`) and why.
- How dangling mirrored-edge retraction moved from the `reconcilePeers`
  sweep into the event-driven path, and where that logic now lives.
- Confirmation (with the specific call site) that peer-adopted nodes still
  receive lifecycle/graph stamps under the new path.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
