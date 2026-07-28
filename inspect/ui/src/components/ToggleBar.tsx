import { For } from 'solid-js';
import { showHosts, setShowHosts, showState, setShowState } from '../solid/toggles';
import './ToggleBar.css';

/** All five overlay toggles, in 10-target-v3.md's table order. M0 shipped
 *  every one disabled. M1-FE ticket Implement §3 + "Correction for
 *  clarity": "Process hosts" and "State" become functional here; "Network
 *  hosts" (M5), "Flow" (M3) and "Errors" (M2) stay disabled with a tooltip
 *  naming their milestone, same convention as M0-FE. */
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
  { key: 'errors', label: 'Errors', milestone: 'M2', get: () => false, set: () => {} },
  { key: 'state', label: 'State', milestone: 'M1', get: showState, set: setShowState },
];

const FUNCTIONAL = new Set(['hosts', 'state']);

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
