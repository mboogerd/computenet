import { createMemo } from 'solid-js';
import { errorStore, errorVersion } from '../solid/errors';
import { conn, nodes } from '../solid/state';
import { theme, toggleTheme } from '../solid/theme';
import { showErrors, setShowErrors } from '../solid/toggles';
import './Header.css';

/** The snapshot only carries `host` per-node (multi-host grouping is M4's
 *  "graph" concept); for the single-pilot-host M0 case that collapses to
 *  one label, so derive it defensively rather than assuming exactly one. */
function hostLabel(): string {
  const hosts = new Set<string>();
  for (const ref in nodes) {
    const h = nodes[ref]?.host;
    if (h) hosts.add(h);
  }
  if (hosts.size === 0) return '—';
  if (hosts.size === 1) return [...hosts][0];
  return `${hosts.size} hosts`;
}

const CONN_LABEL: Record<string, string> = {
  live: 'Live',
  reconnecting: 'Reconnecting…',
  connecting: 'Connecting…',
};

export default function Header() {
  const host = createMemo(hostLabel);
  return (
    <header class="app-header">
      <h1>Inspector</h1>
      <span class="app-header__host">{host()}</span>
      <span class="conn-pip" classList={{ 'is-live': conn() === 'live' }} data-state={conn()}>
        <span class="conn-pip__dot" /> {CONN_LABEL[conn()] ?? conn()}
      </span>
      <ErrorCounters />
      <div class="app-header__spacer" />
      <button class="icon-btn" title="Toggle light / dark" onClick={toggleTheme}>
        {theme() === 'dark' ? '☀ Light' : '☾ Dark'}
      </button>
    </header>
  );
}

/** M2-FE ticket Implement §4: "small counter strip (dead / parked /
 *  restarts) in the shell header, always visible, from the store totals —
 *  doubles as the affordance to switch the toggle on." Always rendered
 *  (unlike the canvas overlay, not gated on `showErrors()`) — its whole
 *  purpose is to be visible before the toggle is on. Clicking it flips the
 *  Errors toggle. */
function ErrorCounters() {
  const counters = createMemo(() => {
    errorVersion();
    return errorStore.counters;
  });
  return (
    <button
      class="error-counters"
      classList={{ 'is-active': showErrors() }}
      title="Errors: dead letters / parked / restarts — click to toggle the Errors overlay"
      onClick={() => setShowErrors((v) => !v)}
    >
      <span class="error-counters__item error-counters__item--dead">{counters().deadLetters} dead</span>
      <span class="error-counters__item error-counters__item--parked">{counters().parked} parked</span>
      <span class="error-counters__item error-counters__item--restarts">{counters().restarts} restarts</span>
    </button>
  );
}
