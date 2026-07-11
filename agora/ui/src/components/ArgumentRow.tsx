import { Show } from 'solid-js';
import CredenceBadge from './CredenceBadge';
import { nodes, selection, setSelection } from '../solid/graph';
import { labelOf } from '../util/label';
import type { ArgRow } from '../layout/debate';
import './ArgumentRow.css';

/** One (edge, source) row with TWO hit targets (spec §3): the claim body
 *  selects the source; the edge chip selects the *edge* — where "argue against
 *  this argument" lives. Both read live credence from the reactive store, so
 *  badges update in place without reordering the (structurally-sorted) list. */
export default function ArgumentRow(props: { row: ArgRow }) {
  const edge = () => nodes[props.row.edge.ref] ?? props.row.edge;
  const source = () => nodes[props.row.source.ref] ?? props.row.source;
  return (
    <div class="arg-row">
      <button
        class="arg-row__claim"
        classList={{ 'is-selected': selection() === source().ref }}
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
    </div>
  );
}
