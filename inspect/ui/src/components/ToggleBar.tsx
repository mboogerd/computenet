import { For } from 'solid-js';
import './ToggleBar.css';

/** All five overlay toggles from 10-target-v3.md, rendered but disabled —
 *  none are functional in M0 (M0-FE ticket Implement §5 + Exclusions).
 *  "Process hosts" data (placement) exists starting M0, but *rendering*
 *  hulls is explicitly excluded until M1 ("No detail-panel content, hulls,
 *  errors, flow, state chips, or navigator (M1+)"), so its tooltip names M1
 *  rather than the target doc's Feed column — a deliberate reconciliation,
 *  flagged in the M0-FE report. */
const TOGGLES: { key: string; label: string; milestone: string }[] = [
  { key: 'hosts', label: 'Process hosts', milestone: 'M1' },
  { key: 'net', label: 'Network hosts', milestone: 'M5' },
  { key: 'flow', label: 'Flow', milestone: 'M3' },
  { key: 'errors', label: 'Errors', milestone: 'M2' },
  { key: 'state', label: 'State', milestone: 'M1' },
];

export default function ToggleBar() {
  return (
    <div class="toggle-bar" role="group" aria-label="Overlay toggles">
      <For each={TOGGLES}>
        {(t) => (
          <label class="toggle" title={`Coming in ${t.milestone}`}>
            <input type="checkbox" disabled />
            {t.label}
          </label>
        )}
      </For>
    </div>
  );
}
