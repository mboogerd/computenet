import { createMemo, For, Show } from 'solid-js';
import type { GraphSummary, SearchMode } from '../api/types';
import { buildConstellations } from '../layout/constellation';
import { deriveHealthPills } from '../nav/health';
import { isSearchModeEnabled, SEARCH_MODES } from '../nav/search';
import { graphs, graphsLoading } from '../solid/graphs';
import { enterGraph } from '../solid/route';
import { runSearch, searchError, searchHits, searchLoading, searchMode, searchQuery, setSearchMode, setSearchQuery } from '../solid/search';
import { edges, nodes, structuralVersion } from '../solid/state';
import './Navigator.css';

/** 10-target-v3.md "Navigator (home screen, M4)": left rail (search + graph
 *  cards), main area (constellation) — M4-FE ticket Implement §1-4. */
export default function Navigator() {
  return (
    <div class="navigator">
      <aside class="navigator__rail">
        <SearchPanel />
        <GraphCards />
      </aside>
      <main class="navigator__main">
        <ConstellationGrid />
      </main>
    </div>
  );
}

function GraphCards() {
  return (
    <div class="graph-cards">
      <Show when={!graphsLoading()} fallback={<p class="navigator__status">Loading graphs…</p>}>
        <Show when={graphs().length} fallback={<p class="navigator__status">No graphs reported yet.</p>}>
          <For each={graphs()}>{(g) => <GraphCard graph={g} />}</For>
        </Show>
      </Show>
    </div>
  );
}

/** 10-target-v3.md Navigator: "name-or-generated-id, cell/host counts,
 *  health pills"; M4-FE ticket Implement §2: "name (or the `g-…` id styled
 *  as 'unnamed', dashed border per the v2 mockup)". */
function GraphCard(props: { graph: GraphSummary }) {
  const pills = createMemo(() => deriveHealthPills(props.graph.health, props.graph.lifecycle));
  return (
    <button
      class="graph-card"
      classList={{ 'graph-card--unnamed': !props.graph.name }}
      title={`${props.graph.health.restarts} restart${props.graph.health.restarts === 1 ? '' : 's'}`}
      onClick={() => enterGraph(props.graph.id)}
    >
      <div class="graph-card__name">{props.graph.name ?? props.graph.id}</div>
      <div class="graph-card__counts">
        {props.graph.cells} cells · {props.graph.hosts} hosts · {props.graph.nets} nets
      </div>
      <div class="graph-card__pills">
        <For each={pills()}>{(p) => <span class="health-pill" data-kind={p.kind}>{p.label}</span>}</For>
      </div>
    </button>
  );
}

/** 10-target-v3.md Navigator: "Search with modes: name (live filter),
 *  problems (...), data (M5)"; M4-FE ticket Implement §4. */
function SearchPanel() {
  function selectMode(mode: SearchMode): void {
    if (!isSearchModeEnabled(mode)) return;
    setSearchMode(mode);
    if (mode === 'problems') runSearch('problems', '');
    else setSearchQuery(''); // 'name': as-you-type — nothing to show until the user types
  }

  function onInput(v: string): void {
    setSearchQuery(v);
    if (searchMode() === 'name') runSearch('name', v);
  }

  return (
    <div class="search-panel">
      <input
        class="search-panel__input"
        type="search"
        placeholder="Search graphs…"
        value={searchQuery()}
        disabled={searchMode() !== 'name'}
        onInput={(e) => onInput(e.currentTarget.value)}
      />
      <div class="search-panel__modes" role="group" aria-label="Search mode">
        <For each={SEARCH_MODES}>
          {(m) => (
            <button
              class="search-chip"
              classList={{ 'is-active': searchMode() === m.mode }}
              disabled={!m.enabled}
              title={m.disabledReason}
              onClick={() => selectMode(m.mode)}
            >
              {m.label}
            </button>
          )}
        </For>
      </div>
      <Show when={searchMode() !== 'data'}>
        <Show
          when={!searchLoading()}
          fallback={<p class="navigator__status">Searching…</p>}
        >
          <Show when={!searchError()} fallback={<p class="navigator__status">Search failed.</p>}>
            <ul class="search-hits">
              <For each={searchHits()}>
                {(hit) => (
                  <li>
                    <button
                      class="search-hit"
                      onClick={() => enterGraph(hit.graph, { ref: hit.ref, forceErrors: searchMode() === 'problems' })}
                    >
                      <span class="search-hit__label">{hit.label}</span>
                      <span class="search-hit__detail">{hit.detail}</span>
                    </button>
                  </li>
                )}
              </For>
            </ul>
          </Show>
        </Show>
      </Show>
    </div>
  );
}

/** 10-target-v3.md Navigator: "Constellation (main): structure-only
 *  thumbnails of every component, cold ones dimmed; click to enter a
 *  graph's canvas"; M4-FE ticket Implement §3. Reads the same shared
 *  `nodes`/`edges` topology store Canvas.tsx does — on Home the current
 *  graph filter is null (`solid/route.ts`), so that store holds every
 *  graph's nodes/edges unfiltered, exactly what grouping by `Node.graph`
 *  needs. */
function ConstellationGrid() {
  const constellations = createMemo(() => {
    structuralVersion();
    return buildConstellations(Object.values(nodes), Object.values(edges));
  });
  const summaryOf = createMemo(() => {
    const m = new Map<string, GraphSummary>();
    for (const g of graphs()) m.set(g.id, g);
    return m;
  });

  return (
    <div class="constellation-grid">
      <Show when={constellations().length} fallback={<p class="navigator__status">No graphs to show yet.</p>}>
        <For each={constellations()}>
          {(c) => {
            const summary = () => summaryOf().get(c.graphId);
            return (
              <button
                class="constellation-card"
                classList={{
                  'constellation-card--cold': summary()?.lifecycle === 'cold',
                  'constellation-card--erring': (summary()?.health.deadLetters ?? 0) > 0,
                }}
                onClick={() => enterGraph(c.graphId)}
              >
                <div class="constellation-card__header">{summary()?.name ?? c.graphId}</div>
                <svg class="constellation-card__svg" viewBox={`0 0 ${Math.max(c.width, 1)} ${Math.max(c.height, 1)}`}>
                  <For each={c.edges}>
                    {(e) => <line class="constellation-edge" x1={e.x1} y1={e.y1} x2={e.x2} y2={e.y2} />}
                  </For>
                  <For each={c.dots}>{(d) => <circle class="constellation-dot" cx={d.x} cy={d.y} r={2} />}</For>
                </svg>
              </button>
            );
          }}
        </For>
      </Show>
    </div>
  );
}
