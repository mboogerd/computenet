import { For, Show } from 'solid-js';
import ArgumentRow from './ArgumentRow';
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
        {props.title}
        <span class="debate-col__count">{props.rows.length}</span>
      </h3>
      <Show
        when={props.rows.length}
        fallback={<p class="debate-col__empty">Nothing yet.</p>}
      >
        <For each={props.rows}>{(row) => <ArgumentRow row={row} />}</For>
      </Show>
    </section>
  );
}
