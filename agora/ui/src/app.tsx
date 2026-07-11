import { onMount, createEffect, Show } from 'solid-js';
import { connect, graph, structuralVersion, focal, setFocal, ready } from './solid/graph';
import DebateView from './components/DebateView';
import Legend from './components/Legend';
import './app.css';

export default function App() {
  onMount(connect);

  // Auto-pick a focal claim on first data, or when the current one is removed.
  createEffect(() => {
    structuralVersion();
    const f = focal();
    if (!f || !graph.get(f)) {
      const c = graph.focalCandidates();
      if (c.length) setFocal(c[0]);
    }
  });

  return (
    <div class="app">
      <header class="app-header">
        <h1>agora</h1>
        <span class="app-tagline">argue, attack the argument, or attack the attack</span>
      </header>
      <main class="app-main">
        <Show when={ready()} fallback={<p class="app-placeholder">Connecting…</p>}>
          <DebateView />
        </Show>
        <Legend />
      </main>
    </div>
  );
}
