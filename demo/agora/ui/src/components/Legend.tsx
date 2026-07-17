import { For } from 'solid-js';
import type { Band } from '../styles/bands';
import { bandVar, BAND_LABEL } from '../styles/bands';
import './Legend.css';

const BANDS: Band[] = [
  'reject-strong',
  'reject-lean',
  'contested',
  'accept-lean',
  'accept-strong',
];

/** The color/shape key (spec §5). Mandatory whenever the visual system is on
    screen; also the home for the cycle-guard and edges-are-arguable explainers. */
export default function Legend() {
  return (
    <div class="legend">
      <div class="legend-group">
        <span class="legend-title">Credence</span>
        <For each={BANDS}>
          {(band) => (
            <span class="legend-item">
              <span
                class="legend-swatch"
                classList={{ 'legend-swatch--contested': band === 'contested' }}
                style={{ background: bandVar(band) }}
              />
              {BAND_LABEL[band]}
            </span>
          )}
        </For>
      </div>

      <div class="legend-group">
        <span class="legend-title">Relation</span>
        <span class="legend-item">
          <svg width="34" height="12" aria-hidden="true">
            <line
              x1="2"
              y1="6"
              x2="26"
              y2="6"
              stroke="var(--edge-muted)"
              stroke-width="1.5"
            />
            <path d="M26 6 l-6 -3 v6 z" fill="var(--edge-muted)" />
          </svg>
          Support
        </span>
        <span class="legend-item">
          <svg width="34" height="12" aria-hidden="true">
            <line
              x1="2"
              y1="6"
              x2="26"
              y2="6"
              stroke="var(--edge-muted)"
              stroke-width="1.5"
              stroke-dasharray="4 3"
            />
            <line x1="26" y1="2" x2="26" y2="10" stroke="var(--edge-muted)" stroke-width="2" />
          </svg>
          Attack
        </span>
      </div>

      <div class="legend-group">
        <span class="legend-title">Signals</span>
        <span class="legend-item">
          <span class="legend-badge">⟳</span>
          Cycle guard — dampens a feedback loop; large changes take a moment to settle
        </span>
        <span class="legend-item">
          <span class="legend-hot" />
          Recently changed a lot
        </span>
      </div>
    </div>
  );
}
