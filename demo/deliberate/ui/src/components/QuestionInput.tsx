import { createSignal, Show } from 'solid-js';

/** The one question field (UI-01). Submission is owned by the caller so the
 *  empty state's examples go through the same path. */
export function QuestionInput(props: { ask: (text: string) => Promise<boolean>; busy?: boolean; error?: string }) {
  const [text, setText] = createSignal('');

  const submit = async (e: Event) => {
    e.preventDefault();
    const t = text().trim();
    if (!t || props.busy) return;
    if (await props.ask(t)) setText('');
  };

  return (
    <form class="ask" onSubmit={submit}>
      <label class="visually-hidden" for="ask-input">Your question</label>
      <input
        id="ask-input"
        class="ask__input"
        type="text"
        placeholder="Ask a question with two sides…"
        autocomplete="off"
        enterkeyhint="go"
        value={text()}
        onInput={(e) => setText(e.currentTarget.value)}
        disabled={props.busy}
      />
      <button class="ask__submit" type="submit" disabled={props.busy || !text().trim()} aria-label="Deliberate">
        <span class="ask__label">{props.busy ? 'Asking…' : 'Deliberate'}</span>
        <span class="ask__arrow" aria-hidden="true">→</span>
      </button>
      <Show when={props.error}>
        <p class="ask__error" role="alert">{props.error}</p>
      </Show>
    </form>
  );
}
