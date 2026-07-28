import { onMount, Show } from 'solid-js';
import Canvas from './components/Canvas';
import DetailPanel from './components/DetailPanel';
import Header from './components/Header';
import Navigator from './components/Navigator';
import ToggleBar from './components/ToggleBar';
import { initDetail } from './solid/detail';
import { fetchGraphs } from './solid/graphs';
import { initRoute, screen } from './solid/route';
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
        <div class="app-body">
          <Canvas />
          <DetailPanel />
        </div>
      </Show>
    </div>
  );
}
