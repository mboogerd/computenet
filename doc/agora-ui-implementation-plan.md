# Agora UI — Implementation Plan

Sequences the build of the reactive frontend specified in [`agora-ui-design-spec.md`](agora-ui-design-spec.md) (read that first). Written to be executed **one work package at a time by Claude Opus**, per the routing and prompt discipline in [`frontend-research/workflow.md`](frontend-research/workflow.md) and [`frontend-research/prompting-guide.md`](frontend-research/prompting-guide.md). Each work package in §5 is a paste-able Opus prompt.

**Stack (decided):** SolidJS + Vite + TypeScript. Standalone app in `agora/ui/`, dev-proxied to the running backend. Testing: Vitest on the pure core, one Playwright smoke.

**Backend (ground truth, verified against [`AgoraApp.kt`](../agora/src/main/kotlin/civictech/agora/AgoraApp.kt)):** `GET /graph` → `NodeDto[]`; `GET /events` → SSE that pushes the *full* `NodeDto[]` on every change and once immediately on (re)connect; `POST /op` form-encoded (`action=claim|edge|stance|remove`). No auth, no topics, no history, no per-user stance readback, no delta push.

---

## 1. Design principles (the load-bearing ones)

1. **One sync seam.** All graph state enters through a single `applySnapshot(NodeDto[])`. Today it's fed by full-snapshot SSE; the eventual per-cell subscription API becomes `applyNodeUpsert`/`applyRemoval` behind the same seam. Build the seam; do **not** build the per-cell methods now (YAGNI — but don't wall them off either).
2. **Diff once, derive everything.** The client diffs each snapshot against prior state. That diff is not a perf trick you can skip — the hot-change pulse, the activity ticker, and the sparkline all *require* "what changed, by how much." It's a feature dependency.
3. **Structure vs. value are different events.** A stance vote changes `credence` only → restyle, never re-layout. An add/remove/retarget is structural → rebuild indexes and re-run layout. The whole "Map mode doesn't jitter when someone votes elsewhere" property falls out of respecting this split (via `structuralVersion`, §3).
4. **No optimism.** Commands POST and wait for the SSE echo (near-instant locally). No speculative local mutation → no reconcile-on-failure bugs.
5. **Immutable records, identity = unchanged.** `diffSnapshot` reuses the *previous* record object for any node whose fields are all equal. Downstream memoization leans on `prev === next`.
6. **Pure core, framework-free.** `api/`, `sync/`, `layout/` import no Solid. They're plain TS, unit-tested once, and would survive a framework change. Solid lives only in `solid/` and `components/`.

---

## 2. Project layout & dependencies

```
agora/ui/
  package.json  tsconfig.json  vite.config.ts  index.html
  src/
    api/types.ts        # Ref, Polarity, NodeDto (wire), NodeRec, Delta
    api/commands.ts     # POST /op helpers + localStorage user id
    sync/diff.ts        # diffSnapshot (pure)
    sync/store.ts       # GraphStore: nodes map + adjacency indexes + structuralVersion (pure)
    sync/history.ts     # per-ref ring buffer + windowed hot/ticker derivation (pure)
    sync/sse.ts         # EventSource adapter → store.applySnapshot; connection state
    solid/graph.ts      # Solid createStore mirror + signals (selection/mode/focal/conn/ticker)
    layout/debate.ts    # pure: focal -> pro/con rows
    layout/map.ts       # pure: reified drawing graph + BFS layered layout
    components/…         # per work package
    styles/tokens.css    app.tsx  main.tsx
  test/fixtures/*.json  test/*.test.ts
  e2e/smoke.spec.ts     # Playwright, WP6 only
```

**Location:** `agora/ui/` (module-adjacent, matches the repo's dir-per-module convention). **Not wired into Gradle.** The repo has zero node tooling; adding gradle-node-plugin taxes every backend build for a demo UI's benefit. Dev loop = `./gradlew :agora:run` (terminal 1) + `npm run dev` (terminal 2). Eventual production serving = a ~20-line static-file handler in `AgoraApp` behind a `--ui <dir>` flag pointed at `ui/dist` — noted here, **not a v1 work package**.

**Dependencies — strict.** Runtime: `solid-js`, nothing else. Dev: `vite`, `vite-plugin-solid`, `typescript`, `vitest`, `jsdom` (for component tests if any), `@playwright/test` (WP6 only). **Rejected, with reasons:**
- `d3-zoom` / `d3-selection` — one gesture set (wheel/drag/pinch/fit) is ~70 lines of pointer-event handling; d3 drags in its own selection + event-ownership model that fights Solid.
- `cytoscape` / `react-flow` — assume edges-are-lines (agora's edges target edges, §3 of the spec), lock rendering into a foreign loop that fights fine-grained reactivity, and force the palette/pulse styling through a foreign style system. react-flow additionally locks the framework.
- state library — the pure `GraphStore` is ~60 lines; Solid's `createStore` is the reactive layer. No Zustand/Redux/etc.
- CSS framework — the spec's design system is a small set of custom properties (`tokens.css`). Plain CSS.
- router — mode + focal ref live in `location.hash` (`#debate/<ref>` / `#map/<ref>`), ~20 lines.

**Vite dev proxy** (`vite.config.ts`) forwards `/graph`, `/events`, `/op` → `http://localhost:8080`. SSE foot-gun: ensure the proxy does not buffer or compress `/events` (`configure` the proxy to disable compression; verify with curl in WP0 — see §5 WP0 verification).

---

## 3. The core modules (concrete signatures)

### `api/types.ts`
```ts
export type Ref = string;
export type Polarity = 'ATTACK' | 'SUPPORT';

/** Exactly the wire shape from AgoraApp.NodeDto. */
export interface NodeDto {
  ref: Ref; kind: 'CLAIM' | 'EDGE';
  text: string | null; polarity: Polarity | null;
  source: Ref | null; target: Ref | null;
  head: boolean; credence: number;
}

/** Client record. Treat as immutable; identity is reused for unchanged nodes. */
export interface NodeRec extends NodeDto {}   // same shape today; a nominal wrapper if it ever diverges

export interface Delta {
  added: NodeRec[];
  removed: NodeRec[];
  changed: { prev: NodeRec; next: NodeRec }[];
  structural: boolean;   // any add/remove, or a changed source/target/kind
  resync: boolean;       // first snapshot after (re)connect — suppress pulses/ticker
  t: number;             // wall-clock ms when applied (pass in; do not call Date.now in pure code paths under test)
}
```

### `sync/diff.ts` (pure)
```ts
export function diffSnapshot(
  prev: ReadonlyMap<Ref, NodeRec>,
  snapshot: readonly NodeDto[],
  opts: { resync?: boolean; now: number },
): { next: Map<Ref, NodeRec>; delta: Delta };
```
- One pass over `snapshot`: for each dto, if `prev` has the ref and **all 8 fields are `===`**, put the *previous object* into `next` (identity preserved — the invariant WP1 tests explicitly). Else build a new record → `added` or `changed`.
- Second pass: refs in `prev` not seen in `snapshot` → `removed`.
- `structural = added.length || removed.length || changed.some(c => c.prev.source !== c.next.source || c.prev.target !== c.next.target || c.prev.kind !== c.next.kind)`.
- Exact `===` on `credence` — significance classification is `history.ts`'s job, not the diff's.

### `sync/store.ts` (pure, framework-free)
```ts
export class GraphStore {
  nodes: ReadonlyMap<Ref, NodeRec>;
  incoming: ReadonlyMap<Ref, readonly Ref[]>;   // EDGE refs whose target === key  (== "arguments about this node")
  outgoing: ReadonlyMap<Ref, readonly Ref[]>;   // EDGE refs whose source === key
  structuralVersion: number;                    // bumps ONLY on structural deltas — the layout memo key

  applySnapshot(snapshot: readonly NodeDto[], opts: { resync?: boolean; now: number }): Delta;
  subscribe(fn: (d: Delta) => void): () => void;
  get(ref: Ref): NodeRec | undefined;
  focalCandidates(): Ref[];                      // derived on demand, NOT a maintained index
}
```
- Note the spec's "children-by-target" and "incoming-edges-by-ref" are the **same index** — `incoming`. Two adjacency maps total.
- Rebuild indexes **only when `delta.structural`** (full O(n) rebuild; incremental maintenance is unnecessary code at this scale). Bump `structuralVersion` in the same branch.
- `focalCandidates()` = CLAIMs sorted by (`incoming[ref].length` desc, most-recent history timestamp desc, ref tiebreak). Computed when the picker opens.

### `sync/history.ts` (pure)
```ts
export class History {
  record(delta: Delta): HotEvents;             // append per added/changed ref; skip on delta.resync
  series(ref: Ref): ReadonlyArray<{ t: number; credence: number }>;   // for the sparkline
}
export interface HotEvents {
  pulses: Ref[];                                // drift >= PULSE (~0.15) over the ~2.5s window
  ticker: { ref: Ref; t: number; drift: number }[];  // drift >= TICKER (~0.05)
}
```
- Per-ref ring buffer (cap ~50), stored beside the store, **not** on `NodeRec` (keeps records immutable).
- **Windowed** drift: `drift(ref) = |credence_now − credence(sample nearest now−2500ms)|`. One stance produces a burst of per-hop snapshots whose individual deltas are tiny; a per-message threshold would misfire (spec §5/§7). Thresholds mirror `AgoraService.MAGNITUDE_BANDS` (HIGH ≥0.2 / NORMAL ≥0.05) — pulse ~0.15–0.2, ticker ~0.05.
- Everything suppressed when `delta.resync`.

### `sync/sse.ts`
```ts
export class SseClient {
  start(onSnapshot: (dtos: NodeDto[], resync: boolean) => void): void;
  stop(): void;
  onState(fn: (s: 'connecting' | 'live' | 'reconnecting') => void): void;
}
```
- **SSE-only bootstrap.** The server pushes a full snapshot on connect, so the first SSE message *is* the initial load — no separate `GET /graph` fetch on the happy path. (The debug page does both; that's a benign race, don't copy it.)
- EventSource's native auto-retry is the entire reconnect story. On the `onopen` that follows an `onerror`, set `resync = true` for the *next* snapshot only.
- Degraded fallback only: if no message arrives within ~5s of first `start()`, do a one-shot `GET /graph` and surface a "degraded" hint. Don't build manual reconnect loops.

### `api/commands.ts`
```ts
export function userId(): string;   // localStorage 'agora.user' ??= random; persistent (survives reload)
export function createClaim(text: string): Promise<{ ref: Ref }>;
export function createEdge(source: Ref, target: Ref, polarity: Polarity): Promise<{ ref: Ref }>;
export function setStance(ref: Ref, value: number | null): Promise<void>;   // user from userId()
export function remove(ref: Ref): Promise<void>;
```
- Always `new URLSearchParams({...})` as the body with `Content-Type: application/x-www-form-urlencoded`. The backend parses `k=v&k=v` only — **never** JSON.
- On non-2xx, read the text body and throw; the caller shows a toast (spec §7 interaction states). No optimistic update.

---

## 4. Solid bridge & rendering (`solid/graph.ts`, `components/`, `layout/`)

### The two views of the data (the crux)
The Solid layer keeps **two representations of the same nodes**, each suited to its consumer:

1. **A Solid `createStore<Record<Ref, NodeRec>>`** — reactive, read by components. On each `Delta`, inside `batch()`, apply per-ref: `setNodes(reconcile(recordsFrom(store.nodes)))` (or per-ref `setNodes(ref, next)` / delete on removed). Solid diffs at the property level, so a component reading `nodes[ref].credence` re-runs only when *that* credence changes → no whole-canvas re-render.
2. **The plain immutable `GraphStore.nodes` map + `structuralVersion`** — read *imperatively* (non-reactively) by the Map-mode layout memo.

Signals: `selection: Ref | null`, `mode: 'debate' | 'map'`, `focal: Ref | null`, `conn: 'connecting'|'live'|'reconnecting'`, `ticker: HotEvents['ticker']`. `pulses` drive a transient per-node effect (add a CSS class, remove after ~2s). History is **not** reactive — read `history.series(ref)` when the detail panel renders.

**The one Solid-specific correctness rule (Opus will get this wrong by default):** the Map layout memo must depend on `structuralVersion` and `focal`, and read `nodes` imperatively:
```ts
const layout = createMemo(() => {
  structuralVersion();                    // reactive dep — recompute on structural change only
  const f = focal();                      // reactive dep
  return layoutMap(graphStore.nodes, graphStore.incoming, graphStore.outgoing, f);  // imperative reads
});
```
Reading a *signal* for the nodes here would recompute layout on every credence tick — exactly the jitter the whole design avoids. `structuralVersion` is a signal; `graphStore.nodes` is not.

### Debate mode (`layout/debate.ts` pure + DOM components)
```ts
export function debateRows(store: GraphStore, focal: Ref):
  { support: Row[]; attack: Row[] };
// Row = { edge: NodeRec; source: NodeRec; challenges: number }  (challenges = incoming[edge.ref].length)
```
- Children of `focal` = `incoming[focal]` split by `edge.polarity`; each row pairs the edge with its `source` node.
- Sort by `edge.credence` desc (spec §3; keep `edge.credence * source.credence` computed for the product-sort switch).
- Depth cap 2, visited-set per path (cycles are legal — don't recurse forever). "N more replies" explicit labels. No virtualization (disclosure caps visible rows).
- `ArgumentRow` renders **two hit targets** (spec §3): claim-card body → `select(source.ref)`; edge chip → `select(edge.ref)`. The chip is a `CredenceBadge` filled with the edge's band. This is not a retrofit — build it in WP2.

### Map mode (`layout/map.ts` pure + hand-rolled SVG)
```ts
export interface DrawGraph { vertices: Vertex[]; segments: Segment[]; }   // reified: every EDGE is a Vertex
export function drawGraph(store: GraphStore): DrawGraph;
export function layoutMap(nodes, incoming, outgoing, focal: Ref):
  { pos: Map<Ref, { x: number; y: number }>; depth: Map<Ref, number>; unreachable: Ref[] };
```
- **Reify** (spec §3): every `EDGE` → a junction vertex (small diamond, credence-band fill, loop badge if `head`); segments are `source→junction` and `junction→target`, polarity-styled (SUPPORT solid+chevron, ATTACK dashed+T-bar), muted gray at rest.
- **Layout:** BFS (undirected) from `focal` → depth per vertex; focal top-center, layers downward. Within-layer order = discovery order + 2–3 barycenter sweeps (avg neighbor x) to cut crossings (~30 lines; not full Sugiyama, no dummy nodes — segments just span layers). Deterministic (stable sorts, ref tiebreaks).
- **Fixed node dimensions** (claim card ≈220px via `<foreignObject>` reusing the DOM ClaimNode markup, `-webkit-line-clamp`; junction ≈14px) so segment anchors are **analytic** (line∩rect for cards, radius offset for junctions). Never read live DOM geometry (`getBBox`) for anchoring.
- **Memoized on `structuralVersion` + focal.** Credence changes restyle only. Structural changes animate via CSS `transform` transition (under the reduce-motion toggle).
- v1 renders the focal claim's whole reachable component (fine at tens of nodes); `depth` is computed, so a hop cap is a one-line change later. Other components → an "K unconnected claims" hint that opens the picker. Force-directed fallback: **deferred**.
- **Pan/zoom** (`usePanZoom`, ~70 lines): one transform on a root `<g>`; wheel-zoom about cursor, pointer-drag pan, pinch via pointer events, double-click-to-fit. No d3.

---

## 5. Work packages (each is a paste-able Opus prompt)

Sequenced so something demoable exists by WP2. Every prompt below follows the guide's 5-part shape (context / goal / boundaries / output / verification) and names its effort level. Feed the relevant §6 failure-pattern rules into each prompt. **Fable** is reserved for the two design-heavy packages (WP1 review, WP4 layout); everything else is routine Opus.

### WP0 — Scaffold + design tokens · Opus, `medium`
- **Context:** Standing up the agora frontend (SolidJS + Vite + TS) in `agora/ui/`, dev-proxied to the backend on :8080. No frontend tooling exists in the repo yet.
- **Goal:** A themed, empty app shell that boots, proxies the three backend routes, and runs the test runner. `tokens.css` encodes the full spec §5 palette: 5 credence bands (both leaning bands = strong pole @45% over surface), light+dark chrome, contested-band hairline texture, status amber, muted edge gray. A static `Legend` component renders the color/shape key. `vitest` runs (one trivial passing test).
- **Boundaries:** No graph logic, no SSE, no components beyond the shell + Legend. Don't wire into Gradle. Don't add any dependency beyond the listed set.
- **Output:** `package.json`, `vite.config.ts` (with the `/graph|/events|/op` proxy, compression disabled on `/events`), `tsconfig.json`, `index.html`, `src/main.tsx`, `src/app.tsx`, `src/styles/tokens.css`, `src/components/Legend.tsx`, one `test/*.test.ts`.
- **Verification:** `npm run dev` serves a themed shell (screenshot, both themes). **Prove SSE survives the proxy:** with the backend running, `curl -N http://localhost:5173/events` streams `data:` frames unbuffered. `npm test` passes. Report each as VERIFIED/ASSUMED.

### WP1 — Sync core (pure TS) · Fable reviews the design, Opus implements · `high`
- **Context:** The entire UI sits on this layer. It ingests full-snapshot SSE, diffs it into immutable records with identity reuse, maintains adjacency indexes + `structuralVersion`, keeps per-ref rolling history, and derives windowed hot/ticker events. Framework-free.
- **Goal:** Implement `api/types.ts`, `api/commands.ts`, `sync/diff.ts`, `sync/store.ts`, `sync/history.ts`, `sync/sse.ts` to the signatures in this plan's §3. No Solid imports anywhere in these files.
- **Boundaries:** No UI. No optimistic updates. Do **not** build `applyNodeUpsert`/`applyRemoval` (future seam only — keep `applySnapshot` internally structured as per-node upserts so they'd be a small later add). Pure functions take `now` as a parameter; no `Date.now()` in tested paths.
- **Output:** the six files + `test/*.test.ts` + `test/fixtures/*.json`.
- **Verification (Vitest, against fixtures recorded from a live backend):** (a) **identity invariant** — after a credence-only snapshot, every unchanged record is `toBe` (===) its prior object; (b) removal reconciliation — a ref absent from a snapshot lands in `removed` and leaves `nodes`; (c) `structural` flag true on add/remove/retarget, false on credence-only; (d) windowed hot detection — a synthesized per-hop burst yields one pulse per genuinely-moved ref, none for noise; (e) `resync` suppresses all hot/ticker output. Record fixtures via curl including an **edge-on-edge** graph and a **removal-cascade** (remove a claim, edges vanish). Report coverage of each.

### WP2 — Debate MVP (first demoable) · Opus, `high`
- **Context:** With the sync core live, build the default view: Kialo-style pro/con columns around a focal claim, updating live from SSE.
- **Goal:** `solid/graph.ts` (Solid store mirror + signals, per §4), `layout/debate.ts`, and components `FocalClaimsPicker`, focal header, two `DebateColumn`s (1 level deep), `ArgumentRow` (dual hit targets, spec §3), `CredenceBadge`. Live updates: create a claim/edge/stance via the debug page or curl and see the columns react.
- **Boundaries:** No detail panel, no depth-2 expansion, no map mode, no pulse/ticker yet. Selection can just `console.log`/set the signal for now.
- **Output:** the files above + `test/debate.test.ts` (pure `debateRows`).
- **Verification:** drive a live backend with a curl script (claims, support+attack edges, an edge-on-edge, stances) and screenshot the columns; confirm a stance vote elsewhere updates only the affected badge. `npm test` green. VERIFIED/ASSUMED per claim.

### WP3 — Detail panel + write actions · Opus, `high`
- **Context:** The core interaction: select any node — claim *or edge* — and get one shared panel to read it and act on it, including "argue against this argument."
- **Goal:** `DetailPanel` (shared claim/edge), `StanceSlider`, and the create-claim / create-edge / remove flows. "Argue against this argument" creates an EDGE whose `target` is the **selected edge's ref**. First-use dismissible hint for the edge-arguing interaction (spec §2). Wire selection from WP2's rows/chips into the panel.
- **Boundaries:** `StanceSlider` binds to the **device-local "your stance"** value (persisted per node under the `localStorage` user id) — it is **never** bound to `credence`; the aggregate credence is a separate read-only readout beside it (spec §6). No sparkline yet. No map.
- **Output:** the components + `commands.ts` wiring + local-stance persistence helper.
- **Verification:** from the UI, attack a claim, then select the attack edge and attack *it*; watch the original claim's credence recover live (mirrors `AgoraServerTest`'s "attack the attack"). Confirm the slider never snaps back while dragging during a live credence update. Screenshot the panel for a claim and for an edge.

### WP4 — Map mode · Fable designs `layout/map.ts` + update discipline, Opus builds SVG · `high`–`xhigh`
- **Context:** The power-user view: the true node-link shape, where edge-on-edge, cycle heads, and propagation are visible. Edges are reified as junction nodes (spec §3). This is the one package with real algorithmic + reactivity-discipline content.
- **Goal:** `layout/map.ts` (reified `drawGraph` + BFS layered `layoutMap`, pure, unit-tested), `GraphCanvas` (hand-rolled SVG, `<foreignObject>` claim cards reusing ClaimNode markup, junction nodes, polarity segments, head badge), `usePanZoom`, and the mode switcher (hash-routed). Selection reuses the **same** `DetailPanel` from WP3.
- **Boundaries:** No force-directed fallback, no cluster summary nodes, no matrix inset, no search panel (all v2/scale). Layout memo depends on `structuralVersion` + `focal` and reads `nodes` **imperatively** (§4 rule) — credence changes must not re-layout.
- **Output:** the files above + `test/map.test.ts`.
- **Verification (Vitest + live):** layout determinism (same input → same `pos`), correct layering (focal depth 0, neighbors depth 1…), barycenter reduces a hand-built crossing case; **live: cast a stance vote on an off-screen node and assert zero position change** (log `pos` identity or screenshot before/after). Screenshot an edge-on-edge structure and a cycle (`head` badge visible).

### WP5 — Liveness & disclosure polish · Opus, `high`
- **Context:** Make the graph feel alive and teach where to look, across both modes.
- **Goal:** Depth-2 expansion + "N more replies" (Debate); hot-change amber pulse (windowed, from WP1's `HotEvents`); `ActivityTicker` (client-computed, dedupes per node per window, click → select + focus); credence sparkline in the panel labeled "since you opened this page"; dark-mode toggle; global reduce-motion toggle; complete the `Legend` (cycle-guard + edges-are-arguable explainers).
- **Boundaries:** No new data sources — everything derives from WP1's history/delta. No backend changes.
- **Output:** the components + wiring.
- **Verification:** burst test — one strong stance on a deep chain pulses **only** genuinely-moved nodes and the ticker counts each node once, not once per hop. Toggle reduce-motion: all animation stops. Screenshot pulse + ticker mid-propagation.

### WP6 — Hardening + smoke test · Opus, `medium`
- **Context:** Close the non-happy paths and lock behavior with one end-to-end test.
- **Goal:** Connection status pip + `resync` banner suppression, empty-graph CTA, `/op` 400 → toast, first-paint skeleton + degraded fallback (spec §7). Validate the four opacity-derived palette cells with the palette validator (spec §5). One Playwright smoke. `agora/ui/README.md` (run/build/test, the one-seam note, the deferred list).
- **Boundaries:** No new features.
- **Verification:** kill and restart the backend mid-session — state reconciles on reconnect with **no ghost pulses** (resync suppression works). Playwright: boot agora → create a claim → it appears live → click it → panel opens → set a stance → credence readout reflects the echo. Green in CI-less local run.

---

## 6. Failure patterns to feed into the WP prompts (symptom → wrong instinct → correct move)

1. **Whole-canvas re-render on every SSE push** (Map jitters when anyone votes anywhere). → *"memoize the components harder / throttle SSE."* → Verify the diff's **identity invariant** (unchanged records `===` prior) and that the layout memo keys on **`structuralVersion`**, reading `nodes` imperatively (§4). The re-render is a symptom of a reactive dependency on the nodes signal inside layout.
2. **Layout drifts on a credence change** (nodes slide when someone votes). → *"cache positions in component state."* → Layout is a pure function of **structure + focal only**; credence flows exclusively into style props. Don't thread credence into `layoutMap`.
3. **Edge anchors detach** (arrows float or pierce cards after a theme/layout change). → *"measure the DOM with `getBBox` in an effect."* → **Fixed node dimensions** in the layout data; anchors computed analytically (line∩rect). Never read live geometry for anchoring.
4. **StanceSlider fights live updates** (snaps back mid-drag, jumps after release). → *"debounce the store or freeze SSE while the panel is open."* → The slider owns a **device-local** value; the API never echoes stances back, so there is *nothing to reconcile*. Credence is a separate read-only display. If the slider is bound to credence at all, that's the bug.
5. **Reconnect duplicates or ghosts state; pulse storm after laptop sleep.** → *"merge/append snapshots; hand-roll a reconnect loop with extra fetches."* → Snapshots are **absolute** — `applySnapshot` fully reconciles including removals. The server sends one on every connect. Mark the first post-reconnect snapshot `resync` to suppress pulses/ticker.
6. **Pulse/ticker storm from propagation bursts** (one vote → a snapshot per hop → every hop pulses). → *"globally throttle rendering."* → **Windowed** drift detection (vs. ~2.5s ago from the history buffer); ticker dedupes per node per window. Optionally coalesce queued SSE messages (safe — snapshots are absolute); build that only if bursts visibly stutter.
7. **Edge/claim conflation in debate rows** (users can't find "argue against this argument"). → *"add a right-click context menu."* → **Two explicit hit targets** per row from WP2 (edge chip vs. claim body) + the spec's first-use hint. Not a menu.
8. **Long claim text breaks SVG labels** (clipped/overlapping text in Map mode). → *"hand-measure text and wrap `<text>` manually."* → `<foreignObject>` with fixed-width HTML cards (`line-clamp`), reusing the debate-mode ClaimNode markup.
9. **SSE dead through the dev proxy** (works via curl on :8080, silent through Vite). → *"rewrite sync as polling."* → Proxy buffering/compression on `/events`; fix the proxy config (WP0), don't abandon SSE.
10. **`/op` returns 400 "unknown action."** → *"debug the backend."* → The backend parses `k=v&k=v` form encoding only. Always send `URLSearchParams`, never a JSON body.
11. **A component prop named `ref` silently doesn't arrive** (e.g. stance POSTs a malformed `id` → backend "UUID string too large"). → *"the value I'm passing is wrong."* → `ref` is a **reserved prop in Solid** (it's the element-ref binding), so `<Cmp ref={x}/>` never reaches `props.ref`. Name node-id props `nodeRef`/`target`, never `ref`. (Backend `UUID.fromString` throws "too large" for any string >36 chars, which is the tell that a bad/blank id got through.)

---

## 7. Explicitly deferred (named, with the trigger to build it)

| Deferred | Build when |
|---|---|
| `MatrixInset` | a locally dense neighborhood actually exists to warrant an adjacency view |
| Force-directed layout fallback | the layered layout visibly fails on a real graph shape |
| Collapsed-cluster summary nodes | the focal neighborhood exceeds ~a few dozen visible nodes |
| Map search/filter panel | navigation-by-scroll/pan becomes impractical (hundreds of nodes) |
| Virtualized rendering | DOM/SVG node count hurts frame rate |
| SSE message coalescing | propagation bursts visibly stutter the UI |
| Per-cell subscription adapter + viewport-driven (un)subscribe & prefetch | the backend ships the subscription API (the `sse.ts` / `applySnapshot` seam is the landing site — see §1.1) |
| Static serving from `AgoraApp` (`--ui <dir>`) | a real deployment is needed (dev uses the Vite proxy) |
| Product-sort switch (`credence(edge)×credence(source)`) | there's real content to eyeball the two sorts against (spec §3) |

Backend open items that gate UI features live in the spec's §9 (edge text, topic grouping, history endpoint, incremental SSE, identity/auth, **per-user stance readback**, optional magnitude-band-on-wire).
```
