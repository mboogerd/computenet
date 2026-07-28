import { For } from 'solid-js';
import { showErrors, setShowErrors, showHosts, setShowHosts, showState, setShowState } from '../solid/toggles';
import './ToggleBar.css';

/** All five overlay toggles, in 10-target-v3.md's table order. M0 shipped
 *  every one disabled. M1-FE ticket Implement §3 + "Correction for
 *  clarity" made "Process hosts" and "State" functional; M2-FE ticket
 *  Implement §2 makes "Errors" functional here. "Network hosts" (M5) and
 *  "Flow" (M3) stay disabled with a tooltip naming their milestone, same
 *  convention as M0-FE. */
const TOGGLES: {
  key: string;
  label: string;
  milestone: string;
  get: () => boolean;
  set: (v: boolean) => void;
}[] = [
  { key: 'hosts', label: 'Process hosts', milestone: 'M1', get: showHosts, set: setShowHosts },
  { key: 'net', label: 'Network hosts', milestone: 'M5', get: () => false, set: () => {} },
  { key: 'flow', label: 'Flow', milestone: 'M3', get: () => false, set: () => {} },
  { key: 'errors', label: 'Errors', milestone: 'M2', get: showErrors, set: setShowErrors },
  { key: 'state', label: 'State', milestone: 'M1', get: showState, set: setShowState },
];

const FUNCTIONAL = new Set(['hosts', 'state', 'errors']);

export default function ToggleBar() {
  return (
    <div class="toggle-bar" role="group" aria-label="Overlay toggles">
      <For each={TOGGLES}>
        {(t) =>
          FUNCTIONAL.has(t.key) ? (
            <label class="toggle toggle--active">
              <input type="checkbox" checked={t.get()} onChange={(e) => t.set(e.currentTarget.checked)} />
              {t.label}
            </label>
          ) : (
            <label class="toggle" title={`Coming in ${t.milestone}`}>
              <input type="checkbox" disabled />
              {t.label}
            </label>
          )
        }
      </For>
    </div>
  );
}
