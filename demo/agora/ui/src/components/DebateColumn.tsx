import { For, Show } from 'solid-js';
import ArgumentRow from './ArgumentRow';
import { AttackIcon, SupportIcon } from './Icons';
import type { ArgRow } from '../layout/debate';

export default function DebateColumn(props: {
  title: string;
  polarity: 'ATTACK' | 'SUPPORT';
  rows: ArgRow[];
}) {
  return (
    <section
      class="debate-col"
      classList={{
        'debate-col--attack': props.polarity === 'ATTACK',
        'debate-col--support': props.polarity === 'SUPPORT',
      }}
    >
      <h3 class="debate-col__title">
        <span class="debate-col__icon">
          {props.polarity === 'ATTACK' ? <AttackIcon size={14} stroke={2} /> : <SupportIcon size={14} stroke={2} />}
        </span>
        {props.title}
        <span class="debate-col__count">{props.rows.length}</span>
      </h3>
      <Show
        when={props.rows.length}
        fallback={
          <p class="debate-col__empty">
            No {props.polarity === 'ATTACK' ? 'attacks' : 'support'} yet — open the focal claim to add one.
          </p>
        }
      >
        <For each={props.rows}>{(row) => <ArgumentRow row={row} />}</For>
      </Show>
    </section>
  );
}
