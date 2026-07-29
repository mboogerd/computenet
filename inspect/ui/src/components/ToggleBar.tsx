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
 *  "Flow" functional; M5-NET ticket Implement §2 made "Network hosts"
 *  functional — the last of the five, so every toggle here renders as the
 *  active/checked variant; there is no disabled case left to render. */
const TOGGLES: {
  key: string;
  label: string;
  get: () => boolean;
  set: (v: boolean) => void;
}[] = [
  { key: 'hosts', label: 'Process hosts', get: showHosts, set: setShowHosts },
  { key: 'net', label: 'Network hosts', get: showNet, set: setShowNet },
  { key: 'flow', label: 'Flow', get: showFlow, set: setShowFlow },
  { key: 'errors', label: 'Errors', get: showErrors, set: setShowErrors },
  { key: 'state', label: 'State', get: showState, set: setShowState },
];

export default function ToggleBar() {
  return (
    <div class="toggle-bar" role="group" aria-label="Overlay toggles">
      <For each={TOGGLES}>
        {(t) => (
          <label class="toggle toggle--active">
            <input type="checkbox" checked={t.get()} onChange={(e) => t.set(e.currentTarget.checked)} />
            {t.label}
          </label>
        )}
      </For>
    </div>
  );
}
