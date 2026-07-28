import { For } from 'solid-js';
import {
  showErrors,
  setShowErrors,
  showFlow,
  setShowFlow,
  showHosts,
  setShowHosts,
  showNet,
  setShowNet,
  showState,
  setShowState,
} from '../solid/toggles';
import './ToggleBar.css';

/** All five overlay toggles, in 10-target-v3.md's table order. M0 shipped
 *  every one disabled. M1-FE ticket Implement §3 + "Correction for
 *  clarity" made "Process hosts" and "State" functional; M2-FE ticket
 *  Implement §2 made "Errors" functional; M3-FE ticket Implement §2 made
 *  "Flow" functional; M5-NET ticket Implement §2 makes "Network hosts"
 *  functional here — the last of the five, so nothing is disabled any more
 *  and the milestone tooltip has no case left to cover. */
const TOGGLES: {
  key: string;
  label: string;
  milestone: string;
  get: () => boolean;
  set: (v: boolean) => void;
}[] = [
  { key: 'hosts', label: 'Process hosts', milestone: 'M1', get: showHosts, set: setShowHosts },
  { key: 'net', label: 'Network hosts', milestone: 'M5', get: showNet, set: setShowNet },
  { key: 'flow', label: 'Flow', milestone: 'M3', get: showFlow, set: setShowFlow },
  { key: 'errors', label: 'Errors', milestone: 'M2', get: showErrors, set: setShowErrors },
  { key: 'state', label: 'State', milestone: 'M1', get: showState, set: setShowState },
];

const FUNCTIONAL = new Set(['hosts', 'net', 'state', 'errors', 'flow']);

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
