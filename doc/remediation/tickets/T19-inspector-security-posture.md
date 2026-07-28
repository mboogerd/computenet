# T19 — Inspector HTTP surface: loopback bind and a real CORS posture on wake

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 1 · **Branches:** `ticket/T19`

## Context

`:inspect` (`InspectorServer`) is a developer instrument that exposes the
kernel's live topology, search, and cell-detail views over plain HTTP, using
the shared `DemoShell` (`demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt`)
that every demo app also uses for its own port. `InspectorServer` opts every
route into `Access-Control-Allow-Origin: *` via a private extension function,
`HttpExchange.allowCrossOrigin()`
(`inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:637-639`),
whose KDoc justifies the wildcard as safe because the endpoints are
"Read-only endpoints, no credentials, developer instrument."

M5-COLD added a route that is not read-only: `POST /api/inspect/graph/{id}/wake`
(`InspectorServer.kt:326-344`, KDoc at `InspectorServer.kt:310-325`). It calls
into `Waker.wake` (`inspect/src/main/kotlin/civictech/inspect/Cold.kt:147-173`),
which resumes every drained host and every individually suspended cell in a
component via `host.managementInlet.call.resumeHost()` /
`.resume(ref)` — a real management-plane mutation, not a read. This route
also carries `exchange.allowCrossOrigin()`
(`InspectorServer.kt:255`, inside the `GRAPH_PATH` route registered at
`InspectorServer.kt:254-258`), same as every GET route (`TOPOLOGY_PATH` at
`:235`, `ERRORS_PATH` at `:242`, `GRAPHS_PATH` at `:246`, `SEARCH_PATH` at
`:260`, `EVENTS_PATH` at `:265`, `CELL_PATH` at `:278`).

A wildcard-CORS `POST` with no custom headers and no non-form `Content-Type`
is a CORS *simple request*: the browser sends it cross-origin with no
preflight and no `OPTIONS` route exists to gate it (none is registered
anywhere in `InspectorServer.kt`). Combined with `DemoShell` binding
`InetSocketAddress(port)` — all interfaces, not loopback
(`demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt:24`, and
`InspectorServer` constructs its `DemoShell(port)` at
`InspectorServer.kt:124`) — any page a developer has open in the same browser
can `fetch('http://<host>:<port>/api/inspect/graph/<id>/wake', {method:
'POST'})` after first enumerating graph ids from the equally wildcard-CORS
`GET /graphs`, and on a shared network a third party can reach the same port
directly. `doc/remediation/AUDIT-2026-07-28.md` §W6 item 1 and
`doc/architecture-decisions.md` finding B8 record this as the accepted,
still-open item this ticket implements; the separate claim in the same audit
that state reads bypass the membrane's `disclosureFilter` was independently
**refuted** (`doc/architecture-decisions.md` Declined table: `43-security.md`
`LocalTrusted` sanctions in-host/same-registry reads, and `DataSearch` only
ever reads hot, locally-hosted cells) — that finding is out of scope here;
this ticket is the HTTP transport surface only.

The dev frontend does not need the wildcard: `inspect/ui/vite.config.ts:14-16`
already proxies `/api/inspect` to the backend with `changeOrigin: true`, so
the UI's own traffic is same-origin through Vite and never depends on
`Access-Control-Allow-Origin`. The one FE call site that issues the wake
request is `inspect/ui/src/sync/coldClient.ts:24-30`
(`defaultWakeTransport.wake`), a bare `fetch(..., { method: 'POST' })` with no
extra headers.

## Problem

1. **`DemoShell` binds all interfaces.** `InetSocketAddress(port)` in
   `DemoShell.kt:24` accepts connections on every NIC, not just loopback. For
   the inspector this also means `?mode=data` search results (live cell
   contents) are network-reachable, not just cross-origin-reachable.
2. **The wake route is a mutation served with wildcard CORS and no
   preflight-forcing shape.** `POST /api/inspect/graph/{id}/wake` (management
   resume, `Cold.kt:147-173`) is indistinguishable, from the browser's CORS
   engine's point of view, from any other simple cross-origin `POST` —
   `allowCrossOrigin()` at `InspectorServer.kt:255` grants it the same `*`
   origin the read routes get, and nothing in the route (`serveGraph`,
   `InspectorServer.kt:326-344`) checks `Origin`, a custom header, or an
   `OPTIONS` preflight.
3. **The KDoc at `InspectorServer.kt:631-636` is false.** It says "Read-only
   endpoints, no credentials" as the reason the wildcard is safe; M5 made
   that untrue for one route sharing the same helper.

## Solution direction

Two independent, additive changes plus a doc correction — do them in this
order so each is separately testable:

1. **Loopback bind.** Give `DemoShell` an optional bind-address constructor
   parameter that defaults to today's behavior (`InetSocketAddress(port)`,
   all interfaces) so the seven existing demo call sites
   (`demo/exchange/.../Main.kt:158`, `demo/shopping/.../Main.kt:93`,
   `demo/agora/.../AgoraApp.kt:43`, `demo/skillmatch/.../SkillMatchApp.kt:252`,
   `demo/tiering/.../TieringApp.kt:138`,
   `demo/backlog-triage/.../TriageApp.kt:166`,
   `demo/slotfinder/.../SlotFinderApp.kt:128`) are unaffected without editing
   them. `InspectorServer` passes loopback (`InetAddress.getLoopbackAddress()`)
   at its own `DemoShell(port)` call site (`InspectorServer.kt:124`). Exact
   parameter shape (e.g. `DemoShell(port: Int, bindAddress: InetAddress =
   InetAddress.getByName("0.0.0.0"))` or an `InetSocketAddress` overload) is
   your judgment — keep it additive and keep the default behavior identical
   for every caller that does not pass it.
2. **Stop granting the wake route wildcard-simple-request CORS.** Pick one
   mechanism and apply it consistently to `GRAPH_PATH`'s `POST .../wake` only
   (leave every GET route's `allowCrossOrigin()` as-is):
   - **Option A (GET-only wildcard):** `allowCrossOrigin()` stays as the
     helper for GET routes; the wake route either omits the CORS header
     entirely (cross-origin `fetch` then fails the browser's CORS check, same
     effect as a same-origin-only policy) or answers a real preflight
     (`OPTIONS`) that only allows the intended origin(s).
   - **Option B (preflight-forcing custom header):** require a header such as
     `X-Inspector: 1` on the wake request; a cross-origin `POST` carrying a
     non-simple header is no longer a CORS simple request, so the browser
     preflights it with `OPTIONS`, and since `InspectorServer` registers no
     `OPTIONS` handler (a 404/405 default), the preflight fails closed and
     the browser never sends the real request. The server should also
     defensively reject a `POST .../wake` that arrives without the header
     (belt-and-suspenders against non-browser callers), returning an
     appropriate 4xx.

   Whichever you pick, update `inspect/ui/src/sync/coldClient.ts:24-30`
   (`defaultWakeTransport.wake`) to match — e.g. add the required header to
   the `fetch` call under Option B. The Vite dev proxy
   (`inspect/ui/vite.config.ts:14-16`) keeps working either way: it proxies
   same-origin, so no CORS header is ever consulted for the dev UI's own
   traffic.
3. **Rewrite the KDoc at `InspectorServer.kt:631-636`.** State the real
   posture: which routes get wildcard CORS and why (read-only GETs, developer
   instrument, still bound to loopback by default), and how the wake route is
   different and why (management mutation, gated by [the mechanism you
   chose]). Do not leave the falsified "read-only endpoints" claim standing
   for any route.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — wake-route
  CORS handling, KDoc rewrite at `:631-639`, loopback address passed to its
  `DemoShell(...)` construction.
- `demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt` — optional
  bind-address parameter, defaulting to current behavior.
- One FE call-site file under `inspect/ui/src/**` —
  `inspect/ui/src/sync/coldClient.ts` (`defaultWakeTransport.wake`), plus its
  test if one already pins the wake `fetch` call (check
  `inspect/ui/test/**` / `inspect/ui/src/sync/` for an existing
  `coldClient` test before assuming none exists).
- `inspect/src/test/**` — one test (extend `InspectorColdTest.kt` or add a
  new file) pinning the chosen wake-gating behavior.

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W6 item 1 — the accepted work item
  this ticket implements, and its ordering note ("do first in this package,
  it is one line each").
- `doc/architecture-decisions.md` finding B8 — the accepted finding record
  (severity, location, solution, status `planned`), and the Declined table's
  "Search/state reads bypass `disclosureFilter`" row — read this so you do
  not also try to "fix" the refuted membrane-bypass claim; it is out of scope
  and closed.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:230-282`
  (route registration, all `allowCrossOrigin()` call sites),
  `:310-345` (wake route + its KDoc), `:625-639` (`query()`, `noContent()`,
  `allowCrossOrigin()` + the KDoc to rewrite).
- `demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt:20-45` — the
  shared shell's constructor, `boundPort`, and `route()`; this file is
  consumed by every demo app, so the new parameter must not change any
  existing call site's behavior.
- `inspect/src/main/kotlin/civictech/inspect/Cold.kt:107-174` — `Waker` and
  its KDoc: why wake is a real management mutation (`resumeHost`/`resume`
  through `managementInlet.call`), and why the endpoint answers 202 rather
  than 200.
- `inspect/ui/vite.config.ts:1-22` — confirm the dev proxy is same-origin
  (`changeOrigin: true`, target `INSPECT_BACKEND` or `localhost:7071`) so you
  can state in the report that it is unaffected.
- `inspect/ui/src/sync/coldClient.ts` — the one FE call site issuing the wake
  request; also skim `inspect/ui/src/nav/cold.ts` and
  `inspect/ui/src/solid/cold.ts` only if you need to trace how
  `defaultWakeTransport` is wired in, but do not edit them unless the header
  change requires it.
- `inspect/src/test/kotlin/civictech/inspect/InspectorColdTest.kt` — the
  existing M5-COLD test file (wake predicate, listing cost, wake behavior,
  search integration) and its use of `HttpProbe` — the pattern to mirror for
  the new gating test.

Do not modify: `inspect/src/main/kotlin/civictech/inspect/Observations.kt`
(T20), `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` (T21,
wave 2), `inspect/src/main/kotlin/civictech/inspect/Flow.kt` (T17), any
`demo/*/src/main/**` application code beyond the one-line bind-address change
this ticket's solution direction describes as unnecessary (the default
parameter should make demo call sites need no edits at all — if you find
yourself editing a demo app's `Main.kt`/`*App.kt`, stop and reconsider the
`DemoShell` API shape instead).

## Acceptance criteria

- [ ] The inspector's `HttpServer` binds to loopback by default (test using
      `InspectorServer`'s effective bind address, or — if that is not
      practically assertable from a JVM test — a documented manual probe in
      the completion report showing a non-loopback connection attempt is
      refused).
- [ ] A cross-origin CORS-simple-request `POST /api/inspect/graph/{id}/wake`
      cannot succeed under the chosen mechanism: a test asserts the gate
      (e.g. a request without the required header, or from a disallowed
      method/origin shape, is rejected — 4xx or missing the CORS header that
      would let a browser accept the response).
- [ ] GET routes' existing CORS behavior is unchanged for the documented
      workflows (topology, errors, graphs, search, events, cell detail/state
      all still carry `Access-Control-Allow-Origin: *` or equivalent, per
      whichever option you chose for Option A vs. B — GETs must not become
      newly gated).
- [ ] `InspectorServer.kt`'s KDoc at `:631-639` no longer claims the
      endpoints are all read-only; it states the actual per-route posture.
- [ ] `DemoShell`'s existing behavior (bind address, `boundPort`, routing) is
      unchanged for every caller that does not opt into the new parameter;
      `:demo:*` tests are green.
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :inspect:test :demo:shopping:test :demo:skillmatch:test
```

If `inspect/ui/src/sync/coldClient.ts` (or a test pinning it) changed:

```bash
cd inspect/ui && npm test
```

## Report on completion

- Checks run and their results (including the manual bind-address probe, if
  you used one instead of an automated test).
- Which CORS mechanism you chose (Option A or B) and why, and the exact
  rejection behavior a cross-origin caller now sees on `.../wake`.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
