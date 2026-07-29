# inspect-ui

Frontend for the ComputeNet Inspector — SolidJS + Vite + TypeScript. Implements
[`doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md`](../../doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md)
against the API in
[`20-api-contract.md`](../../doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md).
Architecture copied from `demo/agora/ui/` per the M0-FE ticket.

## Run

Against a real `:inspect` server:

```
./gradlew :demo:skillmatch:run --args="8080 --inspect-port 7071"
cd inspect/ui && npm install   # first time
npm run dev                    # UI on :5173, proxies /api/inspect -> :7071
```

Against the checked-in fixture (no backend needed — this is what M0-FE ships
and verifies against):

```
cd inspect/ui && npm install
npm run mock                   # terminal 1 — serves fixtures/topology.json on :7071
npm run dev                    # terminal 2 — UI on :5173
```

Override the backend port with `INSPECT_BACKEND=http://localhost:<port> npm run dev`.

## Test

```
npm test        # Vitest — the pure core (sync/diff, sync/store, sync/client, layout/layered)
npm run typecheck
npm run build
```

## Shape

Framework-free core (`src/api`, `src/sync`, `src/layout`), SolidJS confined to
`src/solid`/`src/components` — the `demo/agora/ui` architecture, copied
verbatim per the ticket.

- **One sync seam.** All topology state enters through `TopologyStore`
  (`src/sync/store.ts`): `applySnapshot` for the initial load and any
  post-gap/-disconnect resync, plus one mutator per SSE event kind
  (`applyNodeAdded/Removed`, `applyEdgeAdded/Removed`, `applyLifecycle`).
  `TopologyClient` (`src/sync/client.ts`) is the only thing that knows about
  `fetch`/`EventSource`; it seq-filters events against the last-applied seq
  and refetches the snapshot on a gap or a reconnect (contract's "SSE
  events" section) — the future per-cell subscription API this seam is meant
  to absorb would still enter through the same two classes.
- **Diff once, derive everything.** `sync/diff.ts` reuses the previous record
  object for anything byte-for-byte unchanged; `src/solid/state.ts` mirrors
  that into Solid's fine-grained store the same way.
- **Structure vs. value.** Node/edge add or remove bumps `structuralVersion`
  (the layout memo's key); everything else — today, only a `lifecycle` event
  — is a pure value change that restyles a node card (ghosting) without
  moving it.
- **Insertion-stable layout.** `src/layout/layered.ts` is a longest-path
  Sugiyama-style layering (sources left, sinks right) that remembers each
  node's (layer, slot) across calls, so a newly-added node never reshuffles
  an unrelated one — stronger than agora's Map-mode layout, which
  intentionally re-lays-out the whole graph from a focal claim on any
  structural change (see `test/layout.test.ts` for the property this buys).

## M1: detail panel, state, process-host hulls

Per `doc/spec/90-roadmap/97-inspector-plan/tickets/M1-FE.md`:

- **Detail panel** (`src/components/DetailPanel.tsx`) always stacks all four
  subsections on selection — Descriptor & placement (`GET /cell/{ref}`),
  State, Errors (M2), and Flow (M3); the latter two are real subsections
  today, not placeholders (see the "## M3" section below). No perspective
  switching (that was v2 — see `10-target-v3.md`).
- **State subscription lifecycle** (`src/sync/detailClient.ts`,
  `src/solid/detail.ts`): selection drives exactly one `POST .../observe` /
  `DELETE .../observe` pair (P6 — browsing never subscribes); `state.summary`
  SSE events for the selected ref trigger a `GET .../state` refetch.
  `DetailController` is framework-free and directly unit-tested
  (`test/detailClient.test.ts`) the same way `sync/client.ts` is.
- **Value rendering** (`src/components/ValueView.tsx`): `$table` as a data
  table (tombstoned rows struck through), objects/lists as an indented tree,
  `$truncated` as a "showing N of M" note, `opaque` as a code block. The
  dispatch logic is pure guard functions in `src/api/types.ts`
  (`tableOf`/`truncatedOf`/`opaqueOf`/...), tested per-shape in
  `test/value.test.ts` against one `fixtures/cell-state-*.json` per shape.
- **Process-host hulls** (`src/layout/hulls.ts`): padded-bbox hulls grouping
  nodes by `Node.host`, rendered beneath edges, recomputed on
  `structuralVersion` change *or* a host reassignment (a pure value change
  that does not itself bump `structuralVersion` — see `Canvas.tsx`'s
  `hostFp` memo). "Network hosts" became functional in M5-NET (see the
  "## M5-NET" section below) and, after V0-FE, its state survives reload
  and deep-link like the other four toggles.
- **State toggle chips** on the canvas: driven purely by received
  `state.summary` events; since only the selected cell is ever observed in
  M1, at most one node ever shows a chip at a time.

## M3: flow toggle (pulses, rates, fused edges)

Per `doc/spec/90-roadmap/97-inspector-plan/tickets/M3-FE.md`:

- **Flow store** (`src/sync/flowStore.ts`): per-edge `{rate, lastWave, hop}`
  from `flow.rates` SSE batches (no snapshot endpoint exists for this feed —
  see `src/api/types.ts`'s M3 comment). An edge absent from a batch keeps its
  last reading through one missed 1 Hz window (grace) and decays to zero
  (removed) on a second *consecutive* miss.
- **Canvas overlay** (`src/components/Canvas.tsx`, `src/util/flow.ts`): a
  rate label + SMIL `animateMotion` pulses per active edge, count/speed
  stepped by one of three rate bands (never per-message); a fused edge gets
  a thick stroke and a "fused" label instead, no pulses, ever (M3-BE "never
  emit[s] rates for [fused edges]"). Under `prefers-reduced-motion: reduce`
  (`src/solid/motion.ts`) no pulses render at all — a static per-band
  `data-band` stroke class carries the signal instead. A wide invisible
  `.edge-hit` line (the visible lines stay under `.canvas__svg`'s blanket
  `pointer-events: none`) carries the hover tooltip: route, last wave, hop,
  rate (or the fused wording).
- **Flow subsection** (`src/components/DetailPanel.tsx`, replacing the M1/M2
  placeholder): a per-port table for the selected cell — direction, the
  summed rate of every non-fused edge touching that port, last wave; a port
  whose every edge is fused is labeled `fused` instead of a rate.
- **State toggle chips**: already shipped in M1 (`src/solid/detail.ts` +
  `Canvas.tsx`'s `.node-state-chip` layer, driven by `state.summary`) — this
  ticket's Implement §5 follow-through found nothing left to do.
- `fixtures/flow-rates.json` is a M3-FE addition: a *sequence* of three
  `flow.rates` SSE envelopes (there being no snapshot to pair with a single
  fixture the way `fixtures/errors.json` pairs with `GET /errors`),
  deliberately built to exercise the decay rule end to end — see
  `test/flow-fixture.test.ts`.
- `mock/serve.mjs` gained a synthetic `flow.rates` broadcaster (one edge
  marked `fused: true` in its own served copy only — never in the checked-in
  `fixtures/topology.json`) so the toggle, pulses, fused rendering, and the
  Flow subsection can all be checked by hand ahead of M3-BE.

## M4: navigator (cards, constellation, name/problems search)

Per `doc/spec/90-roadmap/97-inspector-plan/tickets/M4-FE.md`:

- **Two screens, one hash.** `App` (`src/app.tsx`) switches between Home
  (`Navigator.tsx`) and the existing Graph screen (ToggleBar + Canvas +
  DetailPanel) purely on a `screen()` signal — no router library
  (10-target-v3.md Navigator; agora precedent). `src/nav/route.ts` is the
  framework-free hash parse/format pair (`#/` for Home,
  `#/g/<graphId>/<ref>/<toggles>` for a Graph screen); `src/solid/route.ts`
  wires it up (`initRoute()`, `enterGraph()`, `goHome()`) and drives
  `TopologyClient.setGraphFilter` so entering a graph fetches its `?graph=`-
  filtered topology (M4-BE ticket §5) while the shared SSE connection stays
  global and the client filters deltas itself (`solid/state.ts`).
  `solid/routeState.ts` is the raw `screen`/`currentGraphId` signal pair, in
  its own leaf module for the same cycle-avoidance reason `solid/selection.ts`
  already is.
- **Graph cards + live refresh** (`Navigator.tsx`'s `GraphCards`): `GET
  /graphs` (`solid/graphs.ts`) refetched once on boot and again on every
  `graphs.changed` SSE event; health pills derived by `src/nav/health.ts`
  (dead/parked pills only when nonzero, a lifecycle pill always). An unnamed
  graph (`name: null`) renders the id with a dashed border
  (10-target-v3.md: "do NOT invent names").
- **Constellation** (`Navigator.tsx`'s `ConstellationGrid`,
  `src/layout/constellation.ts`): groups the shared topology store's nodes/
  edges by `Node.graph` and lays out each component independently via
  `layout/layered.ts`'s Sugiyama layering — now parameterized
  (`LayeredLayoutConfig`) so this thumbnail-scale caller and the full-size
  canvas share one algorithm instead of two. Dots only, faint edges, no
  labels beyond the card header; a graph with `deadLetters > 0` gets a red
  border/dots, `lifecycle: "cold"` dims the card — real cold-lifecycle
  detection landed in M5-COLD (`Cold.kt`/`Waker.kt` on the backend,
  `ColdScreen.tsx`/`solid/cold.ts` on the frontend), not just "always hot".
- **Search** (`Navigator.tsx`'s `SearchPanel`, `solid/search.ts`,
  `src/nav/search.ts`): `name` mode searches as-you-type; `problems` mode
  searches once on chip-select; `data` mode's chip is not gated — M5-SEARCH
  landed it and it issues a request like the other two modes today.
  Clicking a `problems` hit opens its graph with the Errors toggle
  forced on (`enterGraph(..., { forceErrors: true })`); toggle state itself
  is untouched by navigation (`solid/toggles.ts`'s existing module-level
  signals), so "thumbnail click-through preserves toggles" holds for free.
- `TopologyClient` (`sync/client.ts`) gained `setGraphFilter` (threads
  `?graph=` into its own fetch) and now also force-resyncs topology on a
  `graphs.changed` event, not just forwarding it — a merge/split can leave
  `Node.graph` stale on cells the contract otherwise has no delta for.
- `mock/serve.mjs` splices a second, small, unnamed component into its
  served (never the checked-in) snapshot and implements `/graphs`,
  `/search`, and the `?graph=` filter dynamically against it — M4-BE runs in
  parallel and does not exist in this worktree, same reasoning as the M2/M3
  mock extensions.
- `fixtures/graphs.json`, `fixtures/search-name.json`,
  `fixtures/search-problems.json` are new, hand-authored per contract; the
  named graph's id/counts/health cross-reference the existing
  `fixtures/topology.json`/`fixtures/errors.json` (see
  `test/graphs-fixture.test.ts`).

## M5-NET: network-host hulls, remote-cell placement

Per `doc/spec/90-roadmap/97-inspector-plan/tickets/M5-NET.md`:

- **Network-host hulls** (`src/layout/hulls.ts`'s `computeNetHulls`/
  `netFingerprint`): dashed padded-bbox hulls grouping nodes by `Node.net`,
  painted *before* the process hulls and padded twice as wide, so "net
  outside, proc inside" holds both geometrically and in paint order. A hull
  is tagged `peer` when no member of it reports a process host. The process
  hull became solid at the same time, which is what 10-target-v3.md's toggle
  table always said — the distinction only starts carrying information once
  a net hull sits around it. `test/net.test.ts` pins the nesting property.
- **The Network hosts toggle is functional**, the last of the five; nothing
  in `ToggleBar` is disabled any more.
- **Remote cells** (`src/util/placement.ts`): `host === null` is the
  discriminator — it is the server's own statement, since a peer-announced
  ref has a mirrored location (a bridge, not a `ManagedHost`) and the
  contract's `host` is null for exactly those. Deliberately *not*
  `net !== 'local'`: the local JVM's network host is whatever `--net-name`
  says. The detail panel's placement rows name both levels (with a `peer`
  tag), and State/Flow/Errors each render one verbatim sentence —
  `REMOTE_NOTICE`.
- **Card anchors** (`src/layout/ports.ts`'s `cardAnchor`): an edge endpoint
  whose port the node never declared now attaches to the middle of the card's
  in/out side instead of the whole edge being dropped. This is M3-EVAL's open
  question 1, which M5 makes real: a peer-announced cell arrives as a bare
  `CellRef`, so it has no descriptor, no ports, and its mirrored edges carry
  raw port ids.
- `fixtures/topology-nets.json` is a **synthetic** two-network snapshot
  (local `jvm-a` over two process hosts + one announced peer with no process
  host), added for the same reason M1-FE added the multi-host one.

### Two-JVM run recipe (what the network hulls were verified against)

```
./gradlew :demo:shopping:installDist
./demo/shopping/build/install/shopping/bin/shopping 18081 --listen 19101 --inspect-port 17071 --net-name jvm-a
./demo/shopping/build/install/shopping/bin/shopping 18082 --peer ws://localhost:19101 --inspect-port 17072 --net-name jvm-b
INSPECT_BACKEND=http://localhost:17071 npm run dev   # in inspect/ui
```

Open the UI, enter the `shopping` graph (16 cells · 2 nets), and turn on
Process hosts + Network hosts. Add an item at <http://localhost:18081> to
give the Flow toggle something to show on the cross-boundary edge.

## FE-CANVAS: canvas viewport (zoom/pan/fit)

Per `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/FE-CANVAS.md`: the
canvas gained a real viewport instead of raw browser scrollbars. Mouse wheel
or trackpad scroll pans; Ctrl/Cmd-modified wheel (and trackpad pinch, which
arrives as the same event) zooms about the cursor; dragging the background
pans; the corner `ZoomControls` (`−` / percentage / `+` / `Fit`) and the
`+`/`-`/`0` keys work the same way. Entering a graph for the first time fits
it to screen; leaving and re-entering the same graph restores the viewport
you left it at, per graph id, for the session (`src/solid/viewport.ts`) — not
in the URL hash, which stays the shareable graph/selection/toggle identity.
The pure zoom/pan/fit maths lives in `src/nav/viewport.ts`
(`test/viewport.test.ts`); `Canvas.tsx` applies it as a single CSS transform
on one `.canvas__pan` wrapper around the existing SVG + card layers, so they
cannot diverge from each other.

## Notes

- M4-EVAL re-stamped `Node.graph` on both topology fixtures: after M4 the
  server emits a non-null component id for *every* published cell (an
  unlinked cell is a component of one), so the checked-in `"graph": null`
  was a shape the backend can no longer produce. Each fixture is one
  connected component, so each got the single id the heuristic yields —
  `g-<lexicographically-min member uuid>` — which is also the id
  `fixtures/graphs.json` already calls "skillmatch"
  (`test/graphs-fixture.test.ts` pins both facts).
- `fixtures/topology.json` is a verbatim capture of the real skillmatch
  pilot's `GET /api/inspect/topology` (reconciled to reality by M0-EVAL): 16
  cells (10 named pipeline cells + 6 `ObserveCell` sinks) on the single
  `skillmatch` process host, 18 `CONSUME` edges. `fixtures/topology-multihost.json`
  is a **synthetic** M1-FE addition (not a server capture) — 3 hosts, 2 cells
  each — added because the single-host golden fixture cannot exercise the
  process-host hull toggle's grouping; see `test/hulls.test.ts`.
- `fixtures/cell-detail.json` and `fixtures/cell-state-*.json` are M1-FE
  additions, hand-authored against `20-api-contract.md`'s `CellDetail`/
  `CellState`/`Value` shapes ahead of a real `:inspect` M1 server response.
  Two are FE-side assumptions flagged for M1-EVAL's "reconcile fixtures to
  the server, not vice versa": the tombstoned-row wire shape (contract
  doesn't specify one) and sending the ref unencoded in the URL path.
- `mock/serve.mjs` is a dependency-free static server for the fixtures, not a
  stand-in for `:inspect`. M1-FE extended it with `/cell/{ref}`,
  `/cell/{ref}/state`, and `POST`/`DELETE /cell/{ref}/observe`: observing a
  cell starts a 2s timer that grows a fake table and broadcasts
  `state.summary`, so the live-update and observe-lifecycle behavior can be
  checked by hand without a real backend (`npm run mock` + `npm run dev`,
  then select a node). Still not a semantics stand-in — single global
  observation slot, one fake dataset.

## Deferred (per the tickets' Exclusions)

Cold-graph UX (M5-COLD), data search (M5-SEARCH), per-message flow animation
(M3-FE's own Exclusions: "not in v3 v1 scope"), and remote *state/flow/error*
feeds (M5-NET's own Exclusions — a remote cell is topology + placement only).
No optimistic writes — this is a read-only instrument. No CSS framework,
router, or state library (agora precedent).
