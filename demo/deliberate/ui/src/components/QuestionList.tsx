import { createMemo, For, Show } from 'solid-js';
import type { QuestionDto } from '../api/types';

const sameRoots = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((r, i) => r === b[i]);

/**
 * The question switcher. Every SSE frame carries all-new question objects, so
 * the list is keyed by root (like the tree's useChildRefs): a button survives
 * a frame, and with it keyboard focus.
 */
export function QuestionList(props: { questions: QuestionDto[]; selected?: string; onSelect: (root: string) => void }) {
  const roots = createMemo(() => props.questions.map((q) => q.root), undefined, { equals: sameRoots });
  const byRoot = createMemo(() => new Map(props.questions.map((q) => [q.root, q])));
  return (
    <Show when={roots().length > 1}>
      <nav class="qlist" aria-label="Questions">
        <For each={roots()}>
          {(root) => {
            const q = () => byRoot().get(root);
            const selected = () => root === props.selected;
            return (
              <button
                type="button"
                class="qlist__item"
                classList={{ 'is-selected': selected() }}
                aria-current={selected() ? 'true' : undefined}
                title={`${q()?.text ?? ''} — ${q()?.claims ?? 0} claims${q()?.active ? ', deliberating' : ''}`}
                onClick={() => props.onSelect(root)}
              >
                <span class="qlist__dot" classList={{ 'is-active': q()?.active === true }} aria-label={q()?.active ? 'deliberating' : 'settled'} />
                <span class="qlist__text">{q()?.text}</span>
              </button>
            );
          }}
        </For>
      </nav>
    </Show>
  );
}
