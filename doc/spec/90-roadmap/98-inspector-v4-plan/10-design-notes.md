# Inspector v4 — design notes

**Status**: Specified — decided design for the v4 follow-on run; consumed by
the tickets in `tickets/` and the orchestration plan in `00-orchestration.md`.

## Why this plan exists

The v3 inspector (see `../97-inspector-plan/`, all milestones M0–M5 merged)
delivered the API contract and the v3 toggle model faithfully, but a gap
remains between the interactive mock-ups and the shipped product. This plan
closes the highest-value part of that gap, prioritized on **two axes**:

1. **Axis 1 — enrich ComputeNet itself**: kernel/runtime capability the vision
   needs regardless of UI (observability seams, wave-neutral reads, activity
   surfaces).
2. **Axis 2 — demonstrate & debug**: make a running host inspectable and
   demoable.

Within axis 2, the decided importance ordering is:
**data (see it and watch it change) → activity (passivated/activated, which
host) → errors (including wave/glitch anomalies as an error class)**.
Canvas polish and the mock-up's remaining visual depth are scheduled where
they serve those three, not before.

## Verticals (decided scope)

- **V0 — doorstep**: wire the dormant `SnapshotSource` to the pre-approved
  `ManagedHost.snapshotOf` accessor; serve the built UI from `InspectorServer`
  (production path — today the UI only runs under Vite dev); fix the
  `showNet`-lost-on-reload bug; add a canvas legend; delete dead toggle code;
  refresh `inspect/ui/README.md`.
- **V1a — live data**: the open observation must *stream*: coalesced
  `state.summary` (copy `flow.rates`' 1 Hz publish-even-when-quiet window),
  FE refetches the value on summary change, row-flash animation on changed
  rows, and an onChange log panel (one entry per settled effective change —
  the v1 mock-up's third column).
- **V1b — pinned observations**: let the user pin multiple cells to watch
  simultaneously. P6-clean without kernel work: a pin is an explicit causal
  act, same as selection. UI surfaces the cost ("N cells observed"). Pins are
  session-local (not in the URL).
- **V1-DEMO**: a runbook + convenience script for the two-JVM shopping
  convergence demo — one inspector per JVM side by side; no remote-state
  capability needed.
- **V2 — activity**: kernel observability seams — a suspend/resume/drain
  lifecycle listener and an attention-band read accessor. (Two seams the v3
  closing report also named — publish-hook deregistration handles and
  `remoteRefs()` — already landed after that report, in the audit-2026-07-28
  remediation (T21), and are already consumed by the inspector; V2-KERNEL
  verifies rather than re-implements them.) Plus a new `activity` feed
  (timestamped activated/passivated/drained/woken/restarted per cell) with
  ring + GET + SSE; attention rendered in the detail panel; suspended
  emphasis on the canvas.
- **V3 — errors incl. wave health**: supervision timeline per cell; richer
  dead-letter capture (invocation summary + ownership disposition, primitives
  only); and a new **wave-health heuristic** error class computed
  inspector-side from data it already holds (last wave per tapped outlet +
  per-cell frontier stamps): stalled-wave and frontier-lag warnings. Kernel-
  grade detection belongs with `.verify` later and is explicitly out of scope.
- **FE track**: zoom/pan/fit-to-screen on the canvas; rich positioned
  tooltips replacing native `title`; component/DOM smoke tests (today the
  entire rendering layer has zero DOM tests).
- **V1c design note** (doc-only ticket): the wave-neutral bounded state read
  — the missing kernel primitive behind browse-everything state, honest data
  search, and big-cell views (MRB-157). Produces a design document feeding
  the replan checkpoint; implementation is NOT in this plan's ticketed scope.
- **Deferred to replan**: V4 distribution (PeerId→registry, descriptors over
  the wire, replicated pilot), V5 cold/checkpoint reader.

## Current implementation facts (verified 2026-07-29)

Backend `inspect/src/main/kotlin/civictech/inspect/`:

- `InspectorServer.kt` (~724 LOC): routes, six scheduler `Tick`s on one daemon
  thread (heartbeat 15 s, observation sweep 30 s, error poll 2 s, flow sample
  1 s, `graphs.changed` 1 s, lifecycle poll 1 s), `tickAll()` test seam,
  loopback-only bind, `X-Inspector: 1` header gate on POST wake.
- `Observations.kt`: per-cell `ObserveCell`-based subscriptions; multiple
  concurrent observations already supported server-side; idle release after
  5 min swept every 30 s; **`SnapshotSource` defaults to `Unavailable` and is
  never wired** even though `ManagedHost.snapshotOf(ref)` exists (M5) — the
  KDoc says wiring is deliberate follow-up work.
- `InspectorModel.kt`: one monitor guards nodes/edges/seq; all SSE emission
  points. `Edge.role` is always `CONSUME` (OBSERVE never emitted);
  `CellDetail.attention` always null; `LinkCounts.taps` always 0.
- `Errors.kt`: dead-letter tap (Observe-role, primitives extracted at
  capture), parked/restart polled 2 s, two `RingBuffer`s cap 200.
- `Flow.kt`: one payload-agnostic tap per producing outlet; 1 Hz windows,
  publish-even-when-quiet + one trailing empty window. `state.summary` has no
  equivalent coalescing — carried open since M1.
- `Cold.kt`, `Graphs.kt`, `DataSearch.kt`, `Peers.kt`, `SseBroadcaster.kt`
  (per-client bounded queue 256, drop-oldest), `ValueEncoder.kt` (200-row /
  50 KB budget, `$table`/`$truncated`/`$opaque`).
- 22 test files (~4.5 kLOC); `FixtureContractTest` strict-decodes every FE
  fixture.

Frontend `inspect/ui/src/` (~6.1 kLOC, SolidJS, zero runtime deps beyond
solid-js):

- `components/Canvas.tsx` (509 LOC): hybrid SVG + absolutely-positioned DOM;
  Sugiyama layered layout with persistent slots (`layout/layered.ts`,
  `solid/layout.ts`); **no zoom/pan/fit** (plain `overflow: auto`); **no
  legend**; native `title=` tooltips only.
- `components/DetailPanel.tsx` (363 LOC): four stacked sections; state value
  via `components/ValueView.tsx` (structured, not raw JSON); state fetched by
  GET on selection — **no live re-render on `state.summary`**.
- `sync/`: framework-free `TopologyClient` (seq-gap refetch), `store.ts`,
  `diff.ts` (identity-preserving), `errorStore.ts`, `flowStore.ts` (decay
  after 2 missed windows), `detailClient.ts` `DetailController` (exactly one
  observe per selection — the thing V1b generalizes to a pin set),
  `coldClient.ts` (knows the `X-Inspector` header).
- `nav/route.ts`: **bug — `TOGGLE_KEYS` omits the net toggle**, so `showNet`
  is lost on reload/deep-link; stale comment says "stays disabled through
  M5". `ToggleBar.tsx:49-55` "Coming in {milestone}" branch is dead code.
- Tests: 24 pure-module vitest suites, node environment — **no DOM/component
  tests at all**; `mock/serve.mjs` is a real offline dev backend.
- No production serve path: `InspectorServer` serves no static assets; the UI
  runs only under Vite with the `/api/inspect` proxy.

Mock-up references (design targets, not specs): v1 tabbed
(`claude.ai/code/artifact/cddd4787-…`), v2 perspectives
(`claude.ai/code/artifact/19ee08e5-…`); v3 refinement in
`../97-inspector-plan/10-target-v3.md`. Mock-up features this plan draws on:
row-flash on new rows, onChange log, per-perspective legends, positioned edge
tooltips (route / last wave / hop), supervision timeline, state chips on many
cells, invocation payload labels.

## Binding constraints (unchanged from v3)

1. **P2** — fast path untouched; no per-message hook on the data path.
2. **P6** — observation is causal; browsing/listing never subscribes; every
   subscription is an explicit user act (selection, pin) and is released.
3. **Ownership** — taps are Borrowed-only; never consume/copy/delay
   `Owned`/`Leased`.
4. **Per-cell consistency only** (F-5 accepted); every state view carries its
   own frontier stamp.
5. **Kernel stays transport-neutral**; kernel changes are small, explicitly
   listed read-only accessors/listeners threaded through existing structures
   — never runtime reflection, never HTTP/JSON in `kernel/`.
6. **Viz never blocks** — bounded queues, drop-oldest, sampled feeds.
7. **No edits under `concord/`**; `:concord:check` stays green untouched.
8. `../97-inspector-plan/20-api-contract.md` is orchestrator-owned. Contract
   changes are flagged in the ticket report, never edited unilaterally by a
   worker; additive evolution only (unknown fields ignored by the client).
9. Every markdown file under `doc/spec/` needs a `**Status**:` header
   (`:concord:docLints`).
10. `inspect/ui` stays npm/Vite, not wired into Gradle.

## Standing file split

BE tickets own `inspect/src/**` (+ explicitly listed kernel/demo files); FE
tickets own `inspect/ui/**` only. `V2-KERNEL` is the only ticket allowed to
touch `kernel/**`, and only the files it lists. Two tickets run in parallel
only when file claims are disjoint.
