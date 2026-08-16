import { createMemo, Show } from 'solid-js';
import { observed, snapshotOnly, unpinAll } from '../solid/detail';
import { errorStore, errorVersion } from '../solid/errors';
import { goHome, screen } from '../solid/route';
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
      {/* M4-FE ticket Implement §1: "Back returns to Home" — only shown on
          the Graph screen; Home has nothing to go "back" from. */}
      <Show when={screen() === 'graph'}>
        <button class="icon-btn" title="Back to graphs" onClick={goHome}>
          ‹ Graphs
        </button>
      </Show>
      <span class="app-header__host">{host()}</span>
      <span class="conn-pip" classList={{ 'is-live': conn() === 'live' }} data-state={conn()}>
        <span class="conn-pip__dot" /> {CONN_LABEL[conn()] ?? conn()}
      </span>
      <ErrorCounters />
      <ObservedCounter />
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
 *  Errors toggle.
 *
 *  computenet-0994: a BoundaryPolicy refusal is reported as its own "denied"
 *  item, not folded into "dead" — `errorStore.counters.deadLetters` is
 *  already fault-only (`sync/errorStore.ts`'s `applyDeadLetter`), and
 *  `boundaryDenialCount` is the derived not-a-fault split. It reuses the
 *  `--item--wave` class (the `--wave-health` not-a-fault color register) the
 *  wave-health item below already established, rather than inventing a
 *  fourth visual treatment for what is the same "worth a look, not a
 *  failure" register. */
function ErrorCounters() {
  const counters = createMemo(() => {
    errorVersion();
    return errorStore.counters;
  });
  const boundaryDenials = createMemo(() => {
    errorVersion();
    return errorStore.boundaryDenialCount;
  });
  return (
    <button
      class="error-counters"
      classList={{ 'is-active': showErrors() }}
      title="Errors: dead letters (faults) / denied (BoundaryPolicy refusals — not a fault, SEC1-29) / parked / restarts / wave-health (heuristic, informational — not a defect count) — click to toggle the Errors overlay"
      onClick={() => setShowErrors((v) => !v)}
    >
      <span class="error-counters__item error-counters__item--dead">{counters().deadLetters} dead</span>
      <span
        class="error-counters__item error-counters__item--wave"
        title="BoundaryPolicy refusals — refused, not a cell fault"
      >
        {boundaryDenials()} denied
      </span>
      <span class="error-counters__item error-counters__item--parked">{counters().parked} parked</span>
      <span class="error-counters__item error-counters__item--restarts">{counters().restarts} restarts</span>
      <span class="error-counters__item error-counters__item--wave">{counters().waveHealth} wave</span>
    </button>
  );
}

/** V1B-FE ticket Solution direction §4: "a small 'N cells observed'
 *  affordance in the header, mirroring `ErrorCounters`' pattern — a button,
 *  always visible, click toggles a related overlay" — here, click unpins
 *  everything, since there is no separate overlay to toggle. The count is
 *  the size of the *live* observed set (`pinned ∪ {selection}`), excluding
 *  `snapshotOnly` refs: a refused (409) observe never opened a real
 *  server-side subscription, so it should not count toward "cones you are
 *  touching" (P6, 10-design-notes.md §Binding constraints 2). */
function ObservedCounter() {
  const count = createMemo(() => {
    let n = 0;
    for (const ref of observed()) if (!snapshotOnly().has(ref)) n++;
    return n;
  });
  return (
    <button
      class="observed-counter"
      title="Cells currently observed (live state subscriptions) — click to unpin all"
      onClick={unpinAll}
    >
      {count()} observed
    </button>
  );
}
