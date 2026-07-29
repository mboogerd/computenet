import { For } from 'solid-js';
import { legendEntries } from '../util/legend';
import { showErrors, showFlow, showHosts, showNet, showState } from '../solid/toggles';
import './Legend.css';

/** On-canvas key for the encodings `Canvas.tsx` draws with no built-in
 *  caption — the color chip glyph, manifest badges, edge line style, hull
 *  styles, the flow/error overlays, and the state chip's three-part
 *  reading (V0-FE ticket Problem (c)). Mounted in `app.tsx` directly below
 *  `<ToggleBar />` (see that file), as its own compact chrome strip rather
 *  than an absolutely-positioned overlay competing with the canvas's own
 *  absolutely-positioned layers — matches the mock-ups' intent ("a compact
 *  key, not a full redesign", 10-design-notes.md's own framing) without
 *  needing a new positioning context.
 *
 *  Content is derived by the pure, toggle-aware `util/legend.ts` module —
 *  which entries show is directly unit-tested there without mounting this
 *  component (this repo has no DOM/component tests yet; see
 *  `10-design-notes.md`'s current-facts list). Each entry's longer
 *  explanation is a `title` tooltip (same convention as `node-card__chip`/
 *  `node-card__badge` in `Canvas.tsx`) so the visible strip stays compact. */
export default function Legend() {
  const entries = () => legendEntries(showHosts(), showNet(), showFlow(), showErrors(), showState());

  return (
    <div class="legend" role="note" aria-label="Legend">
      <span class="legend__title">Legend</span>
      <For each={entries()}>
        {(e) => (
          <span class="legend__item" title={e.detail}>
            <Swatch id={e.id} />
            <span class="legend__label">{e.label}</span>
          </span>
        )}
      </For>
    </div>
  );
}

/** One small visual sample per legend entry id, styled entirely from
 *  `styles/tokens.css` custom properties (no hardcoded color) so it renders
 *  correctly in both themes via the existing `:root[data-theme]` /
 *  `@media (prefers-color-scheme: dark)` split. */
function Swatch(props: { id: string }) {
  switch (props.id) {
    case 'cell-color':
      return (
        <span class="legend__swatch legend__swatch--row">
          <span class="legend__dot" data-color="PURE" />
          <span class="legend__glyph">P</span>
          <span class="legend__dot" data-color="BLOCKING" />
          <span class="legend__glyph">B</span>
          <span class="legend__dot" data-color="SUSPENDING" />
          <span class="legend__glyph">S</span>
        </span>
      );
    case 'manifest-badge':
      return (
        <span class="legend__swatch legend__swatch--row">
          <span class="legend__badge">D</span>
          <span class="legend__badge">GF</span>
          <span class="legend__badge">R</span>
          <span class="legend__badge">PT</span>
        </span>
      );
    case 'edge-role':
      return (
        <svg width="30" height="14" class="legend__swatch">
          <line x1="1" y1="4" x2="29" y2="4" class="legend__line legend__line--consume" />
          <line x1="1" y1="11" x2="29" y2="11" class="legend__line legend__line--observe" stroke-dasharray="4 2" />
        </svg>
      );
    case 'host-hull':
      return <span class="legend__swatch legend__hull legend__hull--host" />;
    case 'net-hull':
      return <span class="legend__swatch legend__hull legend__hull--net" />;
    case 'edge-flow':
      return <span class="legend__swatch legend__dot legend__dot--flow" />;
    case 'error-badge':
      return (
        <span class="legend__swatch legend__swatch--row">
          <span class="legend__dot legend__dot--error" />
          <span class="legend__dot legend__dot--parked" />
        </span>
      );
    case 'state-chip':
      return <span class="legend__swatch legend__state-chip">3 · a1b2c3·7 · 120ms</span>;
    default:
      return <span class="legend__swatch" />;
  }
}
