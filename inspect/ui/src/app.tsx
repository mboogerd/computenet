import { onMount, Show } from 'solid-js';
import Canvas from './components/Canvas';
import ColdScreen from './components/ColdScreen';
import DetailPanel from './components/DetailPanel';
import Header from './components/Header';
import Legend from './components/Legend';
import Navigator from './components/Navigator';
import ToggleBar from './components/ToggleBar';
import { currentGraphCold } from './solid/cold';
import { initDetail } from './solid/detail';
import { fetchGraphs } from './solid/graphs';
import { currentGraphGone, goHome, initRoute, screen } from './solid/route';
import { connect } from './solid/state';
import { initTheme } from './solid/theme';
import './app.css';

/** 10-target-v3.md "Navigator (home screen, M4)": two screens, Home and
 *  Graph, switched purely on the `screen()` signal (M4-FE ticket Implement
 *  §1) — no router library. `initRoute()` must run before `connect()` so
 *  the very first topology fetch already carries a deep-linked graph's
 *  `?graph=` filter (see `solid/route.ts`). */
export default function App() {
  initTheme();
  initDetail();
  onMount(() => {
    initRoute();
    connect();
    fetchGraphs();
  });

  return (
    <div class="app">
      <Header />
      <Show
        when={screen() === 'graph'}
        fallback={<Navigator />}
      >
        <ToggleBar />
        <Legend />
        <div class="app-body">
          <Show when={!currentGraphGone()} fallback={<GraphGone />}>
            {/* M5-COLD: a parked component renders as the cold screen — the
                same structure, ghosted, with the notice and the explicit wake
                — instead of the live canvas. The transition back is driven by
                the server's `lifecycle`/`graphs.changed` events landing in the
                `GraphList` this reads, so nothing here has to poll. */}
            <Show when={!currentGraphCold()} fallback={<ColdScreen />}>
              <Canvas />
            </Show>
          </Show>
          <DetailPanel />
        </div>
      </Show>
    </div>
  );
}

/** Shown when the component being viewed no longer exists (see
 *  `solid/route.ts`'s `currentGraphGone`). M4-EVAL asks the UI not to pretend
 *  continuity across a merge or split: the id is gone, the graph the user was
 *  looking at is now part of (or split out of) a different component, and
 *  saying that plainly is better than an empty canvas. */
function GraphGone() {
  return (
    <div class="canvas">
      <div class="canvas__gone">
        <p class="canvas__gone-title">This graph no longer exists.</p>
        <p class="canvas__gone-body">
          Graphs are connected components over the live link set, so they merge and split whenever links change — and
          their ids change with them. Its cells are still running, under a different component.
        </p>
        <button class="canvas__gone-action" onClick={goHome}>
          Back to graphs
        </button>
      </div>
    </div>
  );
}
