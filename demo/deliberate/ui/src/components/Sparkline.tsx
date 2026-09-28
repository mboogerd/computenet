import { Show } from 'solid-js';

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

/**
 * Session sparkline of a question's root credence (agora Sparkline pattern,
 * kept local to this UI package — no cross-package import). The caller
 * accumulates `points` client-side with {@link accumulate} (one call per
 * received graph snapshot); this component only draws them, and renders
 * nothing with fewer than two points.
 */
export function Sparkline(props: { points: readonly number[] }) {
  const path = () => pointsToPath(props.points);
  return (
    <Show when={path()}>
      <svg class="spark" width="200" height="34" aria-hidden="true">
        <polyline points={path()!} fill="none" stroke="var(--pro)" stroke-width="1.5" />
      </svg>
    </Show>
  );
}
