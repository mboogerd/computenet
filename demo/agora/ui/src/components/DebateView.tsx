import { createMemo, Show } from 'solid-js';
import {
  graph,
  nodes,
  structuralVersion,
  focal,
  selection,
  setSelection,
  debateSort,
  setDebateSort,
} from '../solid/graph';
import { debateRows } from '../layout/debate';
import FocalClaimsPicker from './FocalClaimsPicker';
import DebateColumn from './DebateColumn';
import CredenceBadge from './CredenceBadge';
import { labelOf } from '../util/label';
import './DebateView.css';

/** Kialo-style pro/con view (spec §3). Row ORDER is memoized on structure +
 *  focal (not credence), so a live vote updates badges in place without the
 *  rows jumping around. */
export default function DebateView() {
  const rows = createMemo(() => {
    structuralVersion();
    const f = focal();
    if (!f || !graph.get(f)) return { support: [], attack: [] };
    return debateRows(graph, f, debateSort());
  });
  const focalNode = () => {
    const f = focal();
    return f ? nodes[f] : undefined;
  };

  return (
    <div class="debate">
      <div class="debate__bar">
        <label class="debate__bar-label">Focal claim</label>
        <FocalClaimsPicker />
        <div class="debate__bar-spacer" />
        <div
          class="debate__sort"
          role="group"
          title="How to rank arguments. Effective pull = the link's own credence × its source claim's credence — the strength it actually exerts here."
        >
          <span class="debate__sort-label">Rank by</span>
          <button
            classList={{ active: debateSort() === 'link' }}
            onClick={() => setDebateSort('link')}
          >
            Link strength
          </button>
          <button
            classList={{ active: debateSort() === 'effective' }}
            onClick={() => setDebateSort('effective')}
          >
            Effective pull
          </button>
        </div>
      </div>

      <Show
        when={focalNode()}
        fallback={<p class="debate__empty">Pick a claim above, or add one, to begin.</p>}
      >
        {(fn) => (
          <>
            <button
              class="debate__focal"
              classList={{ 'is-selected': selection() === fn().ref }}
              onClick={() => setSelection(fn().ref)}
              title="Open this claim to set a stance or add an argument"
            >
              <span class="debate__focal-text">{labelOf(fn())}</span>
              <CredenceBadge credence={fn().credence} />
            </button>
            <div class="debate__cols">
              <DebateColumn title="Attack" polarity="ATTACK" rows={rows().attack} />
              <DebateColumn title="Support" polarity="SUPPORT" rows={rows().support} />
            </div>
          </>
        )}
      </Show>
    </div>
  );
}
