import { For } from 'solid-js';

export const EXAMPLES = [
  'Should cities ban private cars from their centres?',
  'Is remote work better for junior engineers?',
  'Should schools replace homework with projects?',
] as const;

/** First visit: what this is, in one sentence, and three questions to try. */
export function EmptyState(props: { onPick: (text: string) => void; disabled?: boolean }) {
  return (
    <section class="welcome" aria-label="Getting started">
      <p class="welcome__lede">
        Ask a question with two sides. Claude and Codex argue for and against it, an AI judge weighs every
        argument, and you watch the answer take shape.
      </p>
      <p class="welcome__try">Try one</p>
      <ul class="welcome__examples">
        <For each={EXAMPLES}>
          {(q) => (
            <li>
              <button type="button" class="example" disabled={props.disabled} onClick={() => props.onPick(q)}>
                {q}
                <span class="example__arrow" aria-hidden="true">→</span>
              </button>
            </li>
          )}
        </For>
      </ul>
    </section>
  );
}
