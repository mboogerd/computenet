import { createMemo, Show } from 'solid-js';
import { hist } from '../solid/graph';
import type { Ref } from '../api/types';

/** Session-local credence history (spec §6). `credence` is passed only as a
 *  reactive dependency so the line redraws when a new sample lands. */
export default function Sparkline(props: { nodeRef: Ref; credence: number }) {
  const pts = createMemo(() => {
    void props.credence; // dependency
    const s = hist.series(props.nodeRef);
    if (s.length < 2) return null;
    const W = 200;
    const H = 34;
    const pad = 3;
    const span = s.length - 1;
    return s
      .map((p, i) => {
        const x = pad + (i / span) * (W - 2 * pad);
        const y = pad + (1 - p.credence) * (H - 2 * pad);
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  });

  return (
    <Show
      when={pts()}
      fallback={<span class="spark__none">No changes since you opened this page.</span>}
    >
      <svg class="spark" width="200" height="34">
        <polyline points={pts()!} fill="none" stroke="var(--accept-strong)" stroke-width="1.5" />
      </svg>
    </Show>
  );
}
