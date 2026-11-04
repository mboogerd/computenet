import { For, Show } from 'solid-js';

/** One question's session-local credence history: the root ref it belongs to
 *  and the shown credence at each update that changed it. */
export interface SparkState {
  root: string;
  points: number[];
}

const clamp01 = (x: number): number => (Number.isFinite(x) ? Math.min(1, Math.max(0, x)) : 0);

/**
 * Pure reducer behind the sparkline: appends `credence` to `state`'s points
 * when it differs from the last recorded one, starts a fresh series when
 * `root` changes from `state`'s (a different question was selected), and
 * otherwise leaves `state` untouched (an unchanged credence records nothing).
 * `credence` is `undefined` before the root node is known; that starts (or
 * keeps) an empty series for `root` without recording a point.
 */
export function accumulate(state: SparkState | undefined, root: string, credence: number | undefined): SparkState {
  if (state === undefined || state.root !== root) return { root, points: credence === undefined ? [] : [credence] };
  if (credence === undefined) return state;
  const last = state.points[state.points.length - 1];
  if (last === credence) return state;
  return { root, points: [...state.points, credence] };
}

/**
 * The polyline `points` attribute for a session-credence series, or `null`
 * with fewer than two points (SPEC: render nothing, not a flat line).
 */
export function pointsToPath(points: readonly number[], w = 200, h = 34, pad = 3): string | null {
  if (points.length < 2) return null;
  const span = points.length - 1;
  return points
    .map((c, i) => {
      const x = pad + (i / span) * (w - 2 * pad);
      const y = pad + (1 - clamp01(c)) * (h - 2 * pad);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

const W = 132;
const H = 36;
const PAD = 6; // room for the 4px end dot and its 2px surface ring

/** x/y of each point in the sparkline's own box (same mapping as {@link pointsToPath}). */
function coords(points: readonly number[], w: number, h: number, pad: number): [number, number][] {
  const span = Math.max(1, points.length - 1);
  return points.map((c, i) => [pad + (i / span) * (w - 2 * pad), pad + (1 - clamp01(c)) * (h - 2 * pad)]);
}

/**
 * Session sparkline of a question's root credence (agora Sparkline pattern,
 * kept local to this UI package — no cross-package import). The caller
 * accumulates `points` client-side with {@link accumulate} (one call per
 * received graph snapshot); this component only draws them, and renders
 * nothing with fewer than two points.
 *
 * Drawn as a small stat tile (dataviz: a single current value plus its trend):
 * the current credence and its change this session in text tokens, then a 2px
 * line in the neutral credence ink over a ~8% area wash, a hairline at ½ (the
 * yes/no divide), an end dot, and one hover target per update.
 */
export function Sparkline(props: { points: readonly number[] }) {
  const path = () => pointsToPath(props.points, W, H, PAD);
  const xy = () => coords(props.points, W, H, PAD);
  const area = () => {
    const c = xy();
    const base = H - PAD;
    return `${c[0][0].toFixed(1)},${base} ${path()} ${c[c.length - 1][0].toFixed(1)},${base}`;
  };
  const last = () => props.points[props.points.length - 1];
  const delta = () => Math.round((last() - props.points[0]) * 100);
  const deltaText = () => (delta() === 0 ? '±0' : `${delta() > 0 ? '+' : '−'}${Math.abs(delta())}`);
  const end = () => xy()[xy().length - 1];
  const slot = () => (W - 2 * PAD) / Math.max(1, props.points.length - 1);

  return (
    <Show when={path()}>
      <div class="sparktile" role="group" aria-label="Credence this session">
        <span class="sparktile__label">This session</span>
        <span class="sparktile__value">
          {Math.round(clamp01(last()) * 100)}%
          <span
            class="sparktile__delta"
            classList={{ 'is-up': delta() > 0, 'is-down': delta() < 0 }}
            title={`Change since the first update you saw: ${deltaText()} points over ${props.points.length - 1} updates`}
          >
            {deltaText()} pts
          </span>
        </span>
        <svg class="spark" width={W} height={H} viewBox={`0 0 ${W} ${H}`} aria-hidden="true">
          <line class="spark__mid" x1={PAD} x2={W - PAD} y1={H / 2} y2={H / 2} />
          <polygon class="spark__area" points={area()} />
          <polyline class="spark__line" points={path()!} />
          <For each={xy()}>
            {([x], i) => (
              <rect class="spark__hit" x={x - slot() / 2} y={0} width={slot()} height={H} rx={3}>
                <title>{`Update ${i() + 1}: ${Math.round(clamp01(props.points[i()]) * 100)}%`}</title>
              </rect>
            )}
          </For>
          <circle class="spark__end" cx={end()[0]} cy={end()[1]} r={4} />
        </svg>
      </div>
    </Show>
  );
}
