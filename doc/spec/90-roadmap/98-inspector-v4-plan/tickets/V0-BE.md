# V0-BE — Wire the snapshot fallback; serve the built UI in production

**Status**: Partial — implementation complete, verified, pending merge (evaluator/orchestrator to flip to `Implemented — merged`). (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first
word of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`
**Wave:** 1 · **Branches:** `ticket/v0-be`

## Context

`:inspect` (`inspect/src/main/kotlin/civictech/inspect/`) is the ComputeNet
Inspector backend: a read-only HTTP/SSE view of a host process's live
dataflow graph, served on its own loopback-only port beside the application
(`InspectorServer.kt`'s class doc). Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full before
starting — it is this ticket's binding design and lists constraints that
apply below. This ticket is Wave 1 ("V0 — doorstep") of that plan; it does
two independent, already-scoped things:

**(a) Wire the dormant snapshot fallback.** `GET /cell/{ref}/state`
(`InspectorServer.serveState`, `InspectorServer.kt:399-415`) answers from an
open observation's fold when one exists, otherwise from a
`SnapshotSource` (`observations.snapshotReading(ref)`,
`Observations.kt:227-230`). `SnapshotSource` is a `fun interface`
(`Observations.kt:78-86`) whose shipped default,
`InspectorServer.snapshots` (`InspectorServer.kt:164`), is
`SnapshotSource.Unavailable` — always `null` — so today every cell with no
open observation answers `kind: "unavailable"`, even when the kernel could
answer it. `ManagedHost.snapshotOf(ref): CompletableFuture<Serializable?>`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1075-1094`) is
the host-routed read this seam was always meant to use: it runs the cell's
own `Stateful.snapshot()` on the cell's own execution context (never off
that thread — the entire reason the seam was left unwired at M1), and
returns null immediately for a non-`Stateful` cell or a terminated host.
This accessor landed at M5 for `DataSearch` (`DataSearch.kt`'s "How state is
read" doc, and its private `read(ref, withinMs)` at `DataSearch.kt:207-218`,
which already calls it and bounds the wait). `Observations.kt:68-76`'s own
KDoc records that wiring `snapshotOf` into `InspectorServer`'s construction
is "one line" and deliberately left to "whoever owns M1's remaining
residual" — that is this ticket.

**(b) Production static serve.** `inspect/ui` is a SolidJS/Vite app
(`inspect/ui/package.json`) that today only runs under `npm run dev`,
proxying `/api/inspect` to the `:inspect` backend
(`inspect/ui/vite.config.ts`). `npm run build` produces a static
`inspect/ui/dist/` (default Vite output directory; not committed —
`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md` repeatedly notes
"`dist/` removed, not committed"). Nothing in `InspectorServer` serves it:
its `init` block (`InspectorServer.kt:233-286`) registers only the
`/api/inspect/...` JSON/SSE routes via `shell.route(...)`
(`civictech.demo.shell.DemoShell.route`, a thin wrapper over
`HttpServer.createContext`). So today, seeing the inspector UI against a
real host requires Vite running as a second process
(`inspect/ui/README.md`'s "Run" section). This ticket makes `InspectorServer`
able to serve the built `dist/` directly, so a demo started with
`--inspect-port` and a prior `npm run build` needs nothing but that one
process.

## Problem

- `Observations.kt:122`: `Observations`' constructor parameter
  `snapshots: SnapshotSource = SnapshotSource.Unavailable` — the fallback's
  wiring point.
- `InspectorServer.kt:164`: `internal var snapshots: SnapshotSource =
  SnapshotSource.Unavailable` — the shipped default the ticket must replace.
  It is a `var`, read through by `Observations`' own constructor call at
  `InspectorServer.kt:170` (`snapshots = SnapshotSource { ref ->
  snapshots.snapshotOf(ref) }`), specifically so a source installed after
  construction (or reassigned, as existing tests do) takes effect — do not
  remove that indirection.
- `InspectorServer.kt:158-163`'s doc says wiring `snapshots` "is an
  orchestrator decision that comes with a kernel accessor, not an
  app-facing extension point" — that decision is this ticket; the kernel
  accessor is `ManagedHost.snapshotOf`, already landed, no kernel edit
  needed.
- `Observations.kt:68-76`'s "M5 update" paragraph documents the seam as
  "Left deliberately unwired" — stale once this ticket lands; update it (and
  the parallel claim at `InspectorServer.kt:158-163`) to say wiring happened,
  rather than leaving a doc comment that actively misdescribes the shipped
  behavior.
- No route in `InspectorServer`'s `init` block (`InspectorServer.kt:239-285`)
  serves anything from a filesystem path; every registered route answers
  JSON or SSE. `inspect/ui/README.md`'s "Run" section is the only production
  path today, and it requires Vite.

## Solution direction

**(a)** Replace `InspectorServer.kt:164`'s default value so it routes
through the registry to `ManagedHost.snapshotOf`, instead of introducing a
second field or changing `Observations`' constructor shape. Concretely,
something in the shape of:

```kotlin
internal var snapshots: SnapshotSource = SnapshotSource { ref ->
    registry.locate(ref)?.snapshotOf(ref)?.let { pending ->
        runCatching { pending.get(BUDGET_MS, TimeUnit.MILLISECONDS) }
            .onFailure { pending.cancel(false) }
            .getOrNull()
    }
}
```

`registry` is the constructor parameter already in scope at this point in
the class body (it is referenced in several other property initializers —
`InspectorModel(registry, ...)`, `Peers(registry, netName)`, etc. — so it is
retained as a field automatically). Mirror `DataSearch.read`'s bounded-wait
pattern exactly (`DataSearch.kt:207-218`: `pending.isDone` fast path
optional, `pending.get(withinMs, TimeUnit.MILLISECONDS)`, `pending.cancel(false)`
on timeout, null on any failure) — this call runs synchronously on the HTTP
dispatcher thread inside `serveState` (`InspectorServer.kt:399`), and binding
constraint 6 ("viz never blocks") applies to it exactly as much as it does to
`DataSearch`'s fan-out. Pick a bounded timeout appropriate to a single-cell
synchronous HTTP response (materially shorter than `DataSearch.BUDGET_MS`'s
2000ms multi-cell budget is a reasonable default, but the exact value is
your judgment call — document why). A cell with no local host
(`registry.locate` null — mirrored/remote, or unknown), a non-`Stateful`
cell, or a read that times out must all resolve to `null`, which
`Observations.snapshotReading` already turns into "no fallback" and
`serveState` already turns into `CellState.UNAVAILABLE` — do not touch
either of those paths.

Do not reimplement or modify the `kind: "snapshot"` response path itself
(`Observations.snapshotReading`, `InspectorServer.serveState`'s branch that
builds `CellState(kind = reading.kind, value = ValueEncoder.encode(...), ...)`)
— it is implemented and tested behind this seam already
(`InspectorObserveTest`'s two snapshot tests, `InspectorObserveTest.kt:264-287`).
Your change is only what `SnapshotSource` the server ships wired by default.

Existing tests assign `server.snapshots = SnapshotSource { ... }` directly
after construction (`InspectorObserveTest.kt:270,282`) to stand in for the
kernel accessor before it was wired; they must keep passing unmodified,
since `snapshots` stays a mutable `var` and an explicit assignment still
overrides the new default.

**(b)** Add a catch-all static route. `DemoShell.route(path, handler)`
(`demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt:54-56`) wraps
`HttpServer.createContext`, which dispatches by *longest matching path
prefix* — the same mechanism `InspectorServer.kt:594-607`'s comment on
`GRAPH_PATH`/`GRAPHS_PATH` already documents and relies on. Registering a
route at `"/"` therefore only ever serves requests no more specific
`/api/inspect/...` context claims first; no explicit precedence check is
needed, but add a test that proves it (see Acceptance).

Add a static-file handler, registered once in the `init` block alongside the
existing routes, that:

- Resolves the request path against a configured UI dist directory (a
  `java.nio.file.Path`), rejecting any resolved path that escapes that
  directory (basic traversal guard — `..` segments, symlink escapes) with a
  404 rather than serving it.
- Answers `text/html` for `.html`, `application/javascript` for `.js`/`.mjs`,
  `text/css` for `.css`, `image/svg+xml` for `.svg`; anything else not
  covered by the ticket's list is your judgment call (a reasonable default
  such as `application/octet-stream`, or extend the map — document your
  choice).
- Serves `index.html` for `GET /` (and, since this app is hash-routed —
  `inspect/ui/src/nav/route.ts`'s `parseHash`/`formatHash`, no path-based
  client routing exists — there is no SPA-fallback requirement: any other
  unresolvable path is a plain 404, not `index.html`).
- Answers 404 for a path that resolves to nothing under the dist directory.
- Degrades to API-only, without throwing, when the configured dist directory
  does not exist at server construction/start time: log a one-line note
  (plain `println`, matching the existing operator-facing startup message
  convention in the demo mains — e.g.
  `demo/skillmatch/.../SkillMatchApp.kt:426`'s
  `println("computenet inspector: ...")` — this module has no logging
  framework dependency and should not gain one) and simply answer 404 (or
  leave the route unregistered) for every static request thereafter.

How the dist directory is located is your design decision; add a
constructor parameter to `InspectorServer` for it (so a future caller can
point at a real build output explicitly) with a default that resolves
something sensible for the common local-dev flow (a JVM launched with the
repo root as its working directory, per the existing
`./gradlew :demo:skillmatch:run` / installed-distribution recipes in
`inspect/ui/README.md`). Do not add an npm/Gradle wiring that builds
`inspect/ui` automatically (binding constraint 10, `10-design-notes.md`) —
this ticket only makes `InspectorServer` capable of serving a `dist/` that
already exists on disk; it does not make one appear. Do not edit any
`demo/**` `Main.kt` to pass the new parameter — that is out of this ticket's
file claim; the default must work unaided for anyone who has already run
`npm run build` in `inspect/ui` before starting a demo from the repo root.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — wire the
  snapshot default (a); add the static-file route, content-type map, and
  dist-directory constructor parameter (b).
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt` — update the
  now-stale "deliberately unwired" KDoc on `SnapshotSource` (a only; do not
  touch the fold/observation logic below it).
- `inspect/src/test/kotlin/civictech/inspect/*` — new focused tests for (a)
  and (b); extend `InspectorObserveTest.kt` or add a new test file, your
  choice.

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — binding
  constraints (below) and V0's scope statement.
- `Observations.kt:34-86` — `StateReading`, `SnapshotSource`'s full KDoc
  (the seam's history and why it was left unwired).
- `DataSearch.kt:190-219` — `DataSearch.read`, the exemplar for a bounded
  `snapshotOf` wait on an HTTP-facing thread; `DataSearch.kt:340-348` for
  `BUDGET_MS`'s sibling constant pattern.
- `InspectorObserveTest.kt:1-94,242-298` — the existing observe/state test
  harness (`HttpProbe`, `set()`/`add()` helpers, `state(ref)`) and the two
  tests that already exercise the `kind: "snapshot"` path via a manually
  assigned `SnapshotSource`; a new test for (a) should sit beside these using
  the same harness, but must NOT set `server.snapshots` itself — it exercises
  the wired default.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1075-1094` —
  `snapshotOf`'s contract (submits to the host's own scheduler at priority 0,
  completes null for a non-`Stateful` cell or a dead host).
- `kernel/src/main/kotlin/civictech/cell/data/CounterCell.kt:14-27` — a
  `Stateful` cell whose outlet emits `CounterDelta`, a shape
  `Observations.viewFor` (`Observations.kt:348-357`) has no fold for. This
  (or `ListCell`, same shape) is a real, easy-to-spawn example of "a cell
  with no built-in View" for the new test in (a) — better than the ticket
  brief's own example (`ObserveCell`): an `ObserveCell` spawned via
  `Observations.start` is registered as an inspector *instrument*
  (`Observations.kt:132-149`) and excluded from `InspectorModel`'s adopted
  nodes, so `model.knows(ref)` is false for it and `serveState` 404s before
  ever reaching the snapshot fallback — it cannot exercise this path. Flag
  this discrepancy in your completion report if you rely on `CounterCell`/
  `ListCell` instead.
- `demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt:32-56` —
  `route`'s longest-prefix-match dispatch; `respond` (line 120-125) for the
  response-writing shape to mirror (or diverge from, if binary-safe writing
  turns out to matter — the four extensions in scope are all text).
- `InspectorServer.kt:594-607` — the existing documented precedent for
  relying on `HttpServer`'s longest-prefix route matching (`GRAPH_PATH` vs
  `GRAPHS_PATH`).
- `inspect/ui/src/nav/route.ts:1-19` — confirms the app is hash-routed (no
  server-side path routing to fall back for).
- `inspect/ui/README.md`'s "Run" section — the production flow this ticket
  is meant to shorten.

Do not modify: `inspect/ui/**` (owned by `V0-FE`, running in parallel this
wave), anything under `kernel/**`, `demo/**`, or `concord/**`.

## Binding constraints this ticket touches (from `10-design-notes.md`)

1. No kernel changes — `ManagedHost.snapshotOf` already exists and is
   pre-approved; if you believe you need a kernel edit, stop and flag it
   rather than making one.
2. Kernel stays transport-neutral — not touched by this ticket, but note it
   if your investigation finds otherwise.
3. Viz never blocks (constraint 6) — the bounded wait in (a) is this
   constraint's concrete instance for this ticket.
4. The loopback-only bind (`InspectorServer.kt:125-133`,
   `InetAddress.getLoopbackAddress()`) is unchanged by (b) — the static
   route is served from the same `DemoShell` instance, same bind address.
   Do not widen it.
5. `inspect/ui` stays npm/Vite, not wired into Gradle (constraint 10) — (b)
   serves a `dist/` that must already exist; it must not attempt to invoke
   `npm run build` itself.
6. No edits under `concord/`; not applicable here but stated for the record.

## Acceptance criteria

- [ ] `GET /cell/{ref}/state` for a `Stateful` cell with an outlet whose
      delta shape has no built-in `View` (e.g. `CounterCell`/`ListCell`),
      published but never observed, answers `kind: "snapshot"` with the
      correct encoded value — using `InspectorServer`'s *default* wiring,
      not a test-assigned `SnapshotSource`.
- [ ] The two existing snapshot-source tests
      (`InspectorObserveTest.kt:264-287`) still pass unmodified: an
      explicitly assigned `SnapshotSource` still overrides the default, and
      an open observation still wins over the snapshot source.
- [ ] A cell with no local host, or a snapshot read that does not land
      inside the bounded wait, answers `kind: "unavailable"` rather than
      throwing or hanging the HTTP thread.
- [ ] With a real `dist/` directory (test-fixture, written by the test
      itself — no dependency on an actual `npm run build`) containing at
      least an `.html`, `.js`, `.css`, and `.svg` file: each is served with
      the correct status and `Content-Type`; a path that resolves to nothing
      under that directory answers 404.
- [ ] With that same `dist/` directory wired, `GET /api/inspect/topology`
      (and at least one other existing API route) still answers correctly —
      proving the static route does not shadow the API routes.
- [ ] With no `dist/` directory present at the configured location,
      constructing and starting `InspectorServer` does not throw, every
      existing API route still answers correctly, and a static request
      answers 404 rather than crashing the server.
- [ ] `Observations.kt`'s `SnapshotSource` KDoc no longer claims the seam is
      unwired.
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :inspect:test
./gradlew :inspect:test --tests 'civictech.inspect.InspectorObserveTest'
```

Run the full `:inspect:test` suite (not just the new/changed test classes)
before reporting completion — this ticket touches shared construction code
(`InspectorServer.kt`'s `init` block and `snapshots` default) that every
other test in the module depends on.

## Report on completion

- Checks run and their results (paste the `:inspect:test` summary line).
- Files actually touched, and any not in the claim above.
- The bounded-wait timeout value you chose for (a) and why.
- How you resolved the default `dist/` directory location for (b), and what
  a caller would need to do to override it.
- Whether you used `CounterCell`/`ListCell` or something else for the new
  "no built-in View" test, and confirmation of the `ObserveCell`-cannot-be-
  used-here discrepancy noted above.
- Anything specified here you could not do, and why.
