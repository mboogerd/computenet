import { For, Show } from 'solid-js';
import type { QuestionDto } from '../api/types';

export function QuestionList(props: { questions: QuestionDto[]; selected?: string; onSelect: (root: string) => void }) {
  return (
    <Show when={props.questions.length > 1}>
      <nav class="qlist" aria-label="Questions">
        <For each={props.questions}>
          {(q) => (
            <button
              type="button"
              class="qlist__item"
              classList={{ 'is-selected': q.root === props.selected }}
              aria-current={q.root === props.selected ? 'true' : undefined}
              title={`${q.text} — ${q.claims} claims${q.active ? ', deliberating' : ''}`}
              onClick={() => props.onSelect(q.root)}
            >
              <span class="qlist__dot" classList={{ 'is-active': q.active }} aria-label={q.active ? 'deliberating' : 'settled'} />
              <span class="qlist__text">{q.text}</span>
            </button>
          )}
        </For>
      </nav>
    </Show>
  );
}
