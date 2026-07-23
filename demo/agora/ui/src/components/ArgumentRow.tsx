import { createMemo, createSignal, For, Show } from 'solid-js';
import CredenceBadge from './CredenceBadge';
import { graph, nodes, structuralVersion, selection, setSelection, debateSort } from '../solid/graph';
import { pulsing } from '../solid/hot';
import { debateRows, effectivePull, type ArgRow } from '../layout/debate';
import { labelOf } from '../util/label';
import './ArgumentRow.css';

const MAX_DEPTH = 2; // NN/g two-level cap (spec §3/§4)

/** One (edge, source) row with TWO hit targets (spec §3): the claim body
 *  selects the source; the edge chip selects the *edge*. Expands one level
 *  deeper on demand ("N replies"), capped at MAX_DEPTH. Pulses when the source
 *  claim's credence just moved a lot. */
export default function ArgumentRow(props: { row: ArgRow; depth?: number }) {
  const depth = () => props.depth ?? 1;
  const edge = () => nodes[props.row.edge.ref] ?? props.row.edge;
  const source = () => nodes[props.row.source.ref] ?? props.row.source;
  const pulse = pulsing(props.row.source.ref);

  const replyCount = () => {
    structuralVersion();
    return graph.incoming.get(props.row.source.ref)?.length ?? 0;
  };
  const [expanded, setExpanded] = createSignal(false);
  const children = createMemo<ArgRow[]>(() => {
    if (!expanded()) return [];
    structuralVersion();
    const r = debateRows(graph, props.row.source.ref, debateSort());
    // Nested replies aren't split into columns, so merge and re-rank by the
    // same metric the top level uses.
    const key = debateSort() === 'effective' ? effectivePull : (x: ArgRow) => x.edge.credence;
    return [...r.attack, ...r.support].sort((a, b) => key(b) - key(a));
  });

  return (
    <div class="arg-row-wrap">
      <div class="arg-row">
        <button
          class="arg-row__claim"
          classList={{ 'is-selected': selection() === source().ref, 'is-pulsing': pulse() }}
          onClick={() => setSelection(source().ref)}
        >
          <span class="arg-row__text">{labelOf(source())}</span>
          <CredenceBadge credence={source().credence} size="sm" />
        </button>
        <button
          class="arg-row__chip"
          classList={{
            'is-selected': selection() === edge().ref,
            'is-attack': edge().polarity === 'ATTACK',
            'is-support': edge().polarity === 'SUPPORT',
          }}
          title="Select this argument (the link itself) — you can argue for or against it"
          onClick={() => setSelection(edge().ref)}
        >
          <CredenceBadge credence={edge().credence} size="sm" />
          <Show when={props.row.challenges > 0}>
            <span class="arg-row__challenges">
              {props.row.challenges} challenge{props.row.challenges > 1 ? 's' : ''}
            </span>
          </Show>
        </button>
        <Show when={debateSort() === 'effective'}>
          <span
            class="arg-row__pull"
            title="Effective pull = link credence × source credence"
          >
            {(edge().credence * source().credence).toFixed(2)} pull
          </span>
        </Show>
      </div>

      <Show when={depth() < MAX_DEPTH && replyCount() > 0}>
        <button class="arg-row__more" onClick={() => setExpanded((v) => !v)}>
          {expanded() ? '▾ hide replies' : `▸ ${replyCount()} ${replyCount() === 1 ? 'reply' : 'replies'}`}
        </button>
      </Show>
      <Show when={expanded()}>
        <div class="arg-row__children">
          <For each={children()}>
            {(child) => <ArgumentRow row={child} depth={depth() + 1} />}
          </For>
        </div>
      </Show>
    </div>
  );
}
