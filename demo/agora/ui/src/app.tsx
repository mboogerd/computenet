import { onMount, createEffect, Show } from 'solid-js';
import {
  connect,
  graph,
  nodes,
  structuralVersion,
  focal,
  setFocal,
  mode,
  setMode,
  ready,
  conn,
} from './solid/graph';
import DebateView from './components/DebateView';
import GraphCanvas from './components/GraphCanvas';
import DetailPanel from './components/DetailPanel';
import AddClaim from './components/AddClaim';
import ActivityTicker from './components/ActivityTicker';
import StatusPip from './components/StatusPip';
import Toasts from './components/Toasts';
import EmptyState from './components/EmptyState';
import Legend from './components/Legend';
import { theme, motion, toggleTheme, toggleMotion, initPrefs } from './solid/prefs';
import './app.css';

export default function App() {
  initPrefs();
  onMount(() => {
    const [m, f] = location.hash.replace(/^#\/?/, '').split('/');
    if (m === 'map' || m === 'debate') setMode(m);
    if (f) setFocal(decodeURIComponent(f));
    connect();
  });

  // keep mode + focal in the URL hash (deep-linkable; no router dependency)
  createEffect(() => {
    const h = `#/${mode()}/${focal() ?? ''}`;
    if (location.hash !== h) history.replaceState(null, '', h);
  });

  // Auto-pick a focal claim on first data, or when the current one is removed.
  createEffect(() => {
    structuralVersion();
    const f = focal();
    if (!f || !graph.get(f)) {
      const c = graph.focalCandidates();
      if (c.length) setFocal(c[0]);
    }
  });

  const isEmpty = () => Object.keys(nodes).length === 0;

  return (
    <div class="app">
      <header class="app-header">
        <h1>agora</h1>
        <span class="app-tagline">argue, attack the argument, or attack the attack</span>
        <StatusPip />
        <div class="app-header__spacer" />
        <ActivityTicker />
        <div class="mode-toggle" role="tablist">
          <button classList={{ active: mode() === 'debate' }} onClick={() => setMode('debate')}>
            Debate
          </button>
          <button classList={{ active: mode() === 'map' }} onClick={() => setMode('map')}>
            Map
          </button>
        </div>
        <button class="icon-btn" title="Toggle light / dark" onClick={toggleTheme}>
          {theme() === 'dark' ? '☀ Light' : '☾ Dark'}
        </button>
        <button class="icon-btn" title="Toggle animations" onClick={toggleMotion}>
          {motion() ? 'Motion on' : 'Motion off'}
        </button>
        <AddClaim />
      </header>

      <Show when={conn() === 'reconnecting'}>
        <div class="conn-banner">Connection lost — reconnecting…</div>
      </Show>

      <div class="app-body">
        <main class="app-content">
          <Show when={ready()} fallback={<p class="app-placeholder">Connecting…</p>}>
            <Show when={!isEmpty()} fallback={<EmptyState />}>
              <Show when={mode() === 'map'} fallback={<DebateView />}>
                <GraphCanvas />
              </Show>
            </Show>
          </Show>
          <Legend />
        </main>
        <DetailPanel />
      </div>

      <Toasts />
    </div>
  );
}
