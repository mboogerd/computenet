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
import LoadingState from './components/LoadingState';
import {
  AgoraMark,
  AlertIcon,
  DebateIcon,
  MapIcon,
  MoonIcon,
  MotionOffIcon,
  MotionOnIcon,
  SunIcon,
} from './components/Icons';
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
        <div class="app-brand">
          <AgoraMark />
          <h1>agora</h1>
        </div>
        <span class="app-tagline">argue, attack the argument, or attack the attack</span>
        <StatusPip />
        <div class="app-header__spacer" />
        <ActivityTicker />
        <div class="mode-toggle segmented" role="tablist" aria-label="View">
          <button
            role="tab"
            aria-selected={mode() === 'debate'}
            classList={{ active: mode() === 'debate' }}
            onClick={() => setMode('debate')}
          >
            <DebateIcon size={14} />
            Debate
          </button>
          <button
            role="tab"
            aria-selected={mode() === 'map'}
            classList={{ active: mode() === 'map' }}
            onClick={() => setMode('map')}
          >
            <MapIcon size={14} />
            Map
          </button>
        </div>
        <button
          class="icon-btn pressable"
          title={theme() === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          aria-label={theme() === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          onClick={toggleTheme}
        >
          <span class="icon-swap" data-on={theme() === 'dark' ? 'a' : 'b'}>
            <span class="icon-swap__a"><SunIcon /></span>
            <span class="icon-swap__b"><MoonIcon /></span>
          </span>
        </button>
        <button
          class="icon-btn pressable"
          title={motion() ? 'Animations on — click to turn off' : 'Animations off — click to turn on'}
          aria-label="Animations"
          aria-pressed={motion()}
          onClick={toggleMotion}
        >
          <span class="icon-swap" data-on={motion() ? 'a' : 'b'}>
            <span class="icon-swap__a"><MotionOnIcon /></span>
            <span class="icon-swap__b"><MotionOffIcon /></span>
          </span>
        </button>
        <AddClaim />
      </header>

      <Show when={conn() === 'reconnecting'}>
        <div class="conn-banner" role="status">
          <AlertIcon size={14} />
          Connection lost — reconnecting…
        </div>
      </Show>

      <div class="app-body">
        <main class="app-content">
          <Show when={ready()} fallback={<LoadingState />}>
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
