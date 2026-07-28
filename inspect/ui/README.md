# inspect-ui

Frontend for the ComputeNet Inspector — SolidJS + Vite + TypeScript. Implements
[`doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md`](../../doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md)
against the API in
[`20-api-contract.md`](../../doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md).
Architecture copied from `demo/agora/ui/` per the M0-FE ticket.

## Run

Against a real `:inspect` server (once M0-BE lands):

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
  State, and Flow/Errors placeholders ("arrives with the ... milestone").
  No perspective switching (that was v2 — see `10-target-v3.md`).
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
  `hostFp` memo). "Network hosts" stays disabled (M5).
- **State toggle chips** on the canvas: driven purely by received
  `state.summary` events; since only the selected cell is ever observed in
  M1, at most one node ever shows a chip at a time.

## Notes

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

## Deferred (M2+, per the ticket's Exclusions)

Real errors/flow rendering, the multi-graph navigator. No optimistic writes
— this is a read-only instrument. No CSS framework, router, or state
library (agora precedent).
