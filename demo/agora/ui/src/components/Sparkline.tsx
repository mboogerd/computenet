import { createMemo, createSignal, Show } from 'solid-js';
import { hist } from '../solid/graph';
import type { Ref } from '../api/types';

const W = 300;
const H = 48;
const PAD = 5;

/** Session-local credence history (spec §6). `credence` is passed only as a
 *  reactive dependency so the line redraws when a new sample lands.
 *  Dataviz: one series, so no legend; a 2px line over a faint area, the
 *  0.5 midpoint as the recessive reference, an end dot, and a hover
 *  crosshair + value tooltip (the default hover layer for a line). */
export default function Sparkline(props: { nodeRef: Ref; credence: number }) {
  const pts = createMemo(() => {
    void props.credence; // dependency
    const s = hist.series(props.nodeRef);
    if (s.length < 2) return null;
    const span = s.length - 1;
    return s.map((p, i) => ({
      x: PAD + (i / span) * (W - 2 * PAD),
      y: PAD + (1 - p.credence) * (H - 2 * PAD),
      v: p.credence,
    }));
  });
  const line = () =>
    pts()!
      .map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`)
      .join(' ');
  const area = () => {
    const p = pts()!;
    return `M${p[0].x.toFixed(1)},${H - PAD} L${line().replace(/ /g, ' L')} L${p[p.length - 1].x.toFixed(1)},${H - PAD} Z`;
  };
  const last = () => pts()![pts()!.length - 1];

  const [hover, setHover] = createSignal<number | null>(null);
  const onMove = (e: PointerEvent) => {
    const p = pts();
    if (!p) return;
    const rect = (e.currentTarget as SVGElement).getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * W;
    let best = 0;
    for (let i = 1; i < p.length; i++) if (Math.abs(p[i].x - x) < Math.abs(p[best].x - x)) best = i;
    setHover(best);
  };
  const hp = () => {
    const i = hover();
    return i === null ? null : pts()?.[i] ?? null;
  };

  return (
    <Show
      when={pts()}
      fallback={<span class="spark__none">No changes since you opened this page.</span>}
    >
      <div class="spark__wrap">
        <svg
          class="spark"
          viewBox={`0 0 ${W} ${H}`}
          preserveAspectRatio="none"
          role="img"
          aria-label={`Credence history, ${pts()!.length} samples, now ${last().v.toFixed(2)}`}
          onPointerMove={onMove}
          onPointerLeave={() => setHover(null)}
        >
          <defs>
            <linearGradient id="spark-fill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="var(--accept-strong)" stop-opacity="0.22" />
              <stop offset="1" stop-color="var(--accept-strong)" stop-opacity="0" />
            </linearGradient>
          </defs>
          <line
            x1={PAD}
            x2={W - PAD}
            y1={H / 2}
            y2={H / 2}
            stroke="var(--hairline-strong)"
            stroke-dasharray="2 3"
            vector-effect="non-scaling-stroke"
          />
          <path d={area()} fill="url(#spark-fill)" />
          <polyline
            points={line()}
            fill="none"
            stroke="var(--accept-strong)"
            stroke-width="2"
            stroke-linejoin="round"
            stroke-linecap="round"
            vector-effect="non-scaling-stroke"
          />
          <Show when={hp()}>
            {(p) => (
              <line
                x1={p().x}
                x2={p().x}
                y1={0}
                y2={H}
                stroke="var(--text-muted)"
                vector-effect="non-scaling-stroke"
              />
            )}
          </Show>
        </svg>
        {/* Dots live in HTML so preserveAspectRatio="none" can't squash them. */}
        <span
          class="spark__dot"
          style={{ left: `${(last().x / W) * 100}%`, top: `${(last().y / H) * 100}%` }}
        />
        <Show when={hp()}>
          {(p) => (
            <>
              <span
                class="spark__dot spark__dot--hover"
                style={{ left: `${(p().x / W) * 100}%`, top: `${(p().y / H) * 100}%` }}
              />
              <span class="spark__tip" style={{ left: `${(p().x / W) * 100}%` }}>
                {p().v.toFixed(3)}
              </span>
            </>
          )}
        </Show>
      </div>
    </Show>
  );
}
