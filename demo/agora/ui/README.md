# agora-ui

Reactive frontend for the `agora` argumentation graph — SolidJS + Vite + TypeScript.
Implements [`doc/archive/frontend/agora-ui-design-spec.md`](../../../doc/archive/frontend/agora-ui-design-spec.md); sequenced by
[`doc/archive/frontend/agora-ui-implementation-plan.md`](../../../doc/archive/frontend/agora-ui-implementation-plan.md).

## Run

The backend and the UI run as two processes; Vite proxies `/graph`, `/events`, `/op` to `:8080`.

```
./gradlew :demo:agora:run          # terminal 1 — backend on :8080 (add --args="8080 --journal /tmp/agora" to persist)
cd agora/ui && npm install     # first time
npm run dev                    # terminal 2 — UI on :5173
```

Open http://localhost:5173. Two views (toggle in the header): **Debate** (Kialo-style pro/con)
and **Map** (pan/zoom node-link, every edge reified as a junction so you can attack an attack).
Mode + focal claim live in the URL hash.

## Test

```
npm test          # Vitest — the pure core (diff/store/history/sse/debate/map layout)
npm run typecheck # tsc --noEmit
npm run test:e2e  # Playwright smoke — needs the backend running on :8080
```

## Shape

The pure core (`src/api`, `src/sync`, `src/layout`) is framework-free and unit-tested; SolidJS
lives only in `src/solid` and `src/components`.

- **One sync seam.** All graph state enters through `GraphStore.applySnapshot` (`src/sync/store.ts`),
  fed today by full-snapshot SSE (`src/sync/sse.ts`). A future per-cell subscription API swaps in
  behind that seam — no rewrite. Do not add per-cell methods until the backend ships that API.
- **Diff once, derive everything.** `diffSnapshot` reuses the previous record object for unchanged
  nodes (identity = unchanged); hot-pulse, ticker, and sparkline all read the resulting delta/history.
- **Structure vs. value.** A stance vote changes only `credence` → restyle. Add/remove/retarget is
  structural → rebuild indexes + bump `structuralVersion` (the Map-mode layout memo key, so a vote
  never re-lays-out the graph).

## Deferred (see the plan §7)

Matrix inset, force-directed fallback, cluster summary nodes, map search/filter, virtualization,
SSE coalescing, the per-cell subscription adapter, and static-serving from `AgoraApp`. Backend
gaps that block UI features (edge text, topic grouping, history endpoint, per-user stance readback,
identity) are tracked in the design spec §9.

## Notes

- **Debate ranking** (design spec §3 sort caveat): the pro/con columns can rank by
  **link strength** (the edge's own credence, the v1 default) or by **effective pull**
  = `credence(edge) × credence(source)` — the strength the edge actually exerts on the
  focal claim, so a strong link from a discredited source can't outrank a moderate link
  from a solid one. Toggle in the debate bar; the choice persists in `localStorage`.
  Effective mode annotates each row with its `… pull` value. Row *order* still recomputes
  only on structural/sort change (not per vote), so live credence ticks restyle in place.
- `/op` is form-encoded (`k=v&k=v`) — commands always use `URLSearchParams`, never JSON.
- The credence "leaning" bands are their strong pole at 45% opacity via `color-mix` (tokens.css),
  visually confirmed in light and dark.
- `ref` is a reserved prop in Solid — node-id props are named `nodeRef` (see plan §6 #11).
