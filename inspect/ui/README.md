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

## Deferred (M1+, per the ticket's Exclusions)

Detail-panel content beyond a selected node's name, hulls (process/network
host grouping), errors, flow, state chips, the multi-graph navigator. No
optimistic writes — this is a read-only instrument. No CSS framework,
router, or state library (agora precedent).

## Notes

- `fixtures/topology.json` is the skillmatch pipeline's topology, laid out
  across 3 hosts (13 cells total: 10 pipeline cells + the 3 `ObserveCell`
  sinks the pilot's `stateJson()` reads from) — see the M0-FE report for how
  the exact host split was chosen (M0-BE didn't exist yet to consult) and
  why `manifests` is left `[]` everywhere (we cannot honestly claim
  DURABLE/GLITCH_FREE/etc. without running `ContractRegistry.cellDescriptor`,
  which is M0-BE's job — the M0 evaluator reconciles this fixture to the real
  server response).
- `mock/serve.mjs` is a dependency-free static server for that fixture, not a
  stand-in for `:inspect` — it serves the topology once and heartbeats the
  SSE stream, nothing else.
