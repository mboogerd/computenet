import { For } from 'solid-js';
import { graph, nodes, structuralVersion, focal, setFocal } from '../solid/graph';
import { labelOf } from '../util/label';

/** agora has no "topic" entity, so the frontend supplies the entry point: an
 *  activity-sorted list of claims to focus on (spec §3/§9). Recomputed only on
 *  structural change. */
export default function FocalClaimsPicker() {
  const candidates = () => {
    structuralVersion();
    return graph.focalCandidates();
  };
  return (
    <select
      class="focal-picker"
      value={focal() ?? ''}
      onChange={(e) => setFocal(e.currentTarget.value || null)}
    >
      <For each={candidates()}>
        {(ref) => {
          const n = nodes[ref];
          return <option value={ref}>{n ? labelOf(n) : ref.slice(0, 8)}</option>;
        }}
      </For>
    </select>
  );
}
